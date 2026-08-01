package com.arix.tool

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
// ui_control —— 界面自动化（一个工具多用）。
// 首选无障碍服务（能读屏、免 root、手势由系统合成）；无障碍没开时操作类动作退到 Shizuku 的 input 命令。
// dump 看当前界面文字与可点坐标 → tap/long_press/swipe/click_text/set_text 操作 → back/home/recents/notifications。
// ============================================================
class UiControlTool(private val context: Context) : Tool {
    override val name = "ui_control"
    override val description = "界面自动化：action=dump 读当前屏幕(文字+可点坐标)、click_text 按文字点、tap 按坐标点、long_press 长按、swipe 滑动、set_text 往输入框写字、back/home/recents/notifications 系统操作。先 dump 看清界面再操作。需开无障碍服务；无障碍没开时，除 dump 外可用 Shizuku 兜底。"

    /**
     * 模型侧（英文，省中文税）。比中文那份多说了**观察绑定**和**坐标空间**两件事：
     * 这两条是模型能不能少点错一下的关键，而用户在权限页上并不需要看见它们。
     */
    override val llmDescription = "UI automation on the device screen. action=dump reads the current screen (visible text + tappable coordinates) and returns an observation_id plus the screen size; " +
        "click_text taps by visible text (most reliable, needs no coordinates); tap/long_press/swipe act on coordinates; set_text types into the focused input; back/home/recents/notifications are system keys. " +
        "Work in dump -> act pairs: act on what the newest dump showed and pass that dump's observation_id back. Actions carrying an id are checked against that screen (id, age, foreground app, and the element under x,y) and are refused if it moved on, so you re-observe instead of tapping blind. " +
        "All coordinates are raw screen pixels, never screenshot-image pixels - each dump prints the exact coordinate space and the conversion. " +
        "Needs the accessibility service; without it dump is unavailable and the other actions fall back to Shizuku."

    override val permissionLevel = AndroidPermissionLevel.ACCESSIBILITY
    // 无障碍和 Shizuku 都没有时它一个动作也落不了地（见 fallback），此时不下发 schema，省 token 免白撞
    override val requires = ToolRequirement.UI_AUTOMATION

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            // 参数 description 只发给模型、界面一个字都不显示 → 一律英文（见 Tool.llmDescription 的注释）
            put("action", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("dump", "tap", "long_press", "swipe", "click_text", "set_text", "back", "home", "recents", "notifications"))); put("description", "What to do.") })
            put("text", JSONObject().apply { put("type", "string"); put("description", "click_text: the visible text to tap. set_text: the text to type.") })
            put("x", JSONObject().apply { put("type", "integer"); put("description", "tap/long_press/swipe start x, in RAW SCREEN PIXELS - the same space dump prints (see its coordinate_space line). If you got this number off a screenshot image, scale it first: screen_x = img_x * screen_width / img_width.") })
            put("y", JSONObject().apply { put("type", "integer"); put("description", "tap/long_press/swipe start y, raw screen pixels (same space as x).") })
            put("x2", JSONObject().apply { put("type", "integer"); put("description", "swipe end x, raw screen pixels.") })
            put("y2", JSONObject().apply { put("type", "integer"); put("description", "swipe end y, raw screen pixels.") })
            put("duration", JSONObject().apply { put("type", "integer"); put("description", "swipe duration ms (default 300) / long_press duration ms (default 600).") })
            // ⚠ 类型写 string 而不是 integer：ToolArgValidator 对标量一律放行字符串，但反过来
            // 声明成 integer 时模型回 "obs-3f2a-7" 也照样过——写 string 才和真实取值形状一致。
            put("observation_id", JSONObject().apply {
                put("type", "string")
                put("description", "The id returned by the most recent action=dump. Pass it with tap/long_press/swipe/set_text so the action can be verified against the screen you actually saw. " +
                    "It is refused (nothing runs) if it is not the newest observation, if that observation is older than ${UiObservationStore.STALE_MS}ms, if the foreground app changed, or if x,y no longer holds the element you observed - then dump again and retry with the fresh id. " +
                    "Omitting it still works today but the action runs unverified. Not needed for click_text or the system keys.")
            })
        })
        put("required", JSONArray(listOf("action")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.Default) {
        val action = params.optString("action", "")
        // 「AI 正在操作」的常驻光晕：整段自动化期间亮着。它和高亮共用一个窗口，
        // 所以同样在每次注入手势前被 detachForGesture 摘掉，不会造成遮挡。
        com.arix.app.UiActionOverlay.beginSession(context)
        try {
            val svc = com.arix.app.XtomAccessibilityService.instance
            // ★ 观察绑定预检：必须在事前确认**之前**。两个原因：
            //   ① 本来就不该执行的一步，没道理先把用户拉起来点一次确认；
            //   ② 确认弹窗是挂起等人的，用户想上半分钟——那段时间不该算进观察的新鲜期，
            //      否则「用户点了同意」反而必然触发「观察过期」，同意等于白点。
            val bind = UiObservationBinding.precheck(context, svc, action, params)
            bind.reject?.let { return@withContext it }
            // ★ 事前确认：必须在这里，而不是在 viaAccessibility 里面——
            // 那里第一句就 detachForGesture 摘掉了浮层，等用户点框的这段时间屏幕上会缺一块提示；
            // 更要紧的是拦下时根本不该走到「准备注入手势」那一步。
            UiDangerGuard.guard(context, svc, action, params)?.let { return@withContext it }
            val r = if (svc != null) viaAccessibility(svc, action, params) else fallback(action, params)
            return@withContext UiObservationBinding.withHint(r, bind.hint)
        } finally {
            // 走到这里手势已经落地，没有事件在飞 —— 此刻把光晕挂回来是安全的。
            // 两句都是纯 post，不含挂起点，不会吃掉取消。
            com.arix.app.UiActionOverlay.resumeSessionIndicator(context)
            com.arix.app.UiActionOverlay.endSessionSoon(context)
        }
    }

    /** 无障碍没开时的退路：能读屏的动作直接劝开无障碍，操作类动作交给 Shizuku。 */
    private suspend fun fallback(action: String, params: JSONObject): ToolResult {
        // dump 不做 Shizuku 兜底：uiautomator dump 要落盘再读、慢得多，且拿不到无障碍那份 text/可点标记，
        // 给 AI 的信息反而更差。读屏这件事就认准无障碍。
        if (action == "dump") {
            try { context.startActivity(Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            return ToolResult("读屏需要无障碍服务：已帮你打开「系统设置→无障碍」，找到 Arix 打开后再让我操作。", isError = true)
        }
        if (ShizukuAccess.granted()) return viaShizuku(action, params)

        return ToolResult(
            "界面自动化需要无障碍服务或 Shizuku，两者当前都不可用。" +
            "可调 request_permission(permission=\"accessibility\") 或 request_permission(permission=\"shizuku\") 申请后重试。", isError = true)
    }

    /**
     * 往可视化总线报一条动作。埋在**执行之后**，因为 success 是可视化里最有价值的一位信息
     * （点空了要画红框），在 execute 入口埋点根本拿不到结果。
     * 整段吞异常：可视化坏了绝不能连累 AI 的操作本身。
     */
    private fun report(
        action: String, rect: android.graphics.Rect?, success: Boolean,
        endX: Int? = null, endY: Int? = null, label: String = "", viaShizuku: Boolean = false,
    ) {
        try {
            com.arix.app.UiActionBus.emit(
                context,
                com.arix.app.UiActionEvent(
                    action = action, rect = rect, endX = endX, endY = endY,
                    targetLabel = label, success = success, viaShizuku = viaShizuku,
                ),
            )
        } catch (_: Exception) {}
    }

    /** 首选路径：无障碍。手势由系统合成，且只有这条路能读屏。 */
    private suspend fun viaAccessibility(svc: com.arix.app.XtomAccessibilityService, action: String, params: JSONObject): ToolResult {
      // ★ 唯一的摘窗埋点（无障碍路径）：放在 when 之前，任何分支都躲不过去。
      // dispatchGesture 注入的事件走的不是"用户手指"那条链路，FLAG_NOT_TOUCHABLE 保不住它；
      // 屏幕上只要悬着我们这个别家应用的窗口，目标 View 就可能因触摸遮挡策略静默丢弃事件。
      // 所以必须在这里把窗口物理 removeView 掉，而不是隐藏它。
      // dump 也一并摘：顺带让我们自己的浮层节点不会混进给 AI 的界面快照里。
      com.arix.app.UiActionOverlay.detachForGesture()
      return try {
        when (action) {
            // dump 前把 Arix 自己的悬浮窗让位/摘掉，抓完恢复：可聚焦的唤醒浮层会成为无障碍的
            // activeWindow，不处理的话 svc.dump() 读到的就是我们自己的浮层，而不是被操作的 App。见 [OverlayVisibility]。
            "dump" -> {
                // 文本、节点表、前台包名必须在**同一个让位窗口内**取齐：分开取的话中间浮层已经挂回来，
                // 记下的节点/包名就可能是我们自己的浮层，那份「观察」从出生起就是假的。
                // dump() 内部会再走一次节点树（两趟共 ≤240 个节点访问，可忽略），换来的是
                // dump 文本格式一个字都不用动 —— 那是模型认界面的既定契约。
                val snap = com.arix.app.OverlayVisibility.hideOwnOverlaysForDump {
                    val nodes = svc.dumpNodes()
                    val pkg = try { svc.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
                    Triple(svc.dump(), nodes, pkg)
                }
                val (text, nodes, pkg) = snap
                if (nodes.isEmpty()) {
                    // 一个节点都没读到 = 这一刻我们**确知自己看不见屏幕**。留着上一条旧观察，
                    // 模型就能拿着一个「还有效」的 id 去盲点早已翻篇的坐标，所以直接作废。
                    UiObservationStore.invalidate()
                    ToolResult(text)
                } else {
                    val (w, h) = UiScreenSpace.size(context)
                    val rec = UiObservationStore.record(w, h, pkg, nodes)
                    ToolResult(UiScreenSpace.contract(context, rec) + text)
                }
            }
            // ↓ 手势类（会注入触摸）：整段包进 hideOwnOverlaysForGesture——注入前让唤醒浮层失焦让位、
            //   OCR/TTS 钮摘窗，免得目标 App 因触摸遮挡策略把注入的手势静默丢弃；取 label 也放进块里，
            //   让位后读 rootInActiveWindow 才不会读到浮层自己。UiActionOverlay 已在上面 detachForGesture 摘掉。
            "click_text" -> {
                val t = params.optString("text", "").trim()
                if (t.isBlank()) return ToolResult("请提供要点的 text", isError = true)
                com.arix.app.OverlayVisibility.hideOwnOverlaysForGesture {
                    // 按文字点的目标位置只有读屏才知道：先在结构化节点里找一遍，拿到真实矩形去画框。
                    // 优先可点节点——clickText 本身也是往可点祖先走，这样画的框和真正被点的东西最接近。
                    val hits = svc.dumpNodes().filter { it.label.contains(t) }
                    val hit = hits.firstOrNull { it.clickable } ?: hits.firstOrNull()
                    val ok = svc.clickText(t); delay(120)
                    report("click_text", hit?.bounds, ok, label = t)
                    if (ok) ToolResult("已点「$t」") else ToolResult("界面上没找到可点的「$t」（先 dump 看看）", isError = true)
                }
            }
            "tap" -> {
                val x = params.optInt("x", -1); val y = params.optInt("y", -1)
                if (x < 0 || y < 0) return ToolResult("请提供 x,y", isError = true)
                com.arix.app.OverlayVisibility.hideOwnOverlaysForGesture {
                    // 标签必须**点之前**取：点完界面往往已经跳走，那时读到的是新页面上的元素，
                    // 浮层会理直气壮地写「点了『X』」而 X 根本不是刚才点的东西——比不显示更误导。
                    // 也必须**让位之后**取：否则 labelAt 读 rootInActiveWindow 会读到唤醒浮层自己。
                    val tapLabel = labelAt(svc, x, y)
                    // 目标身份校验：观察里这个点上是什么、现在还是不是它。零额外成本——
                    // tapLabel 是为了画高亮框本来就要取的那一个。带了 observation_id 才校验。
                    UiObservationBinding.verifyTarget(params, x, y, tapLabel)
                        ?.let { return@hideOwnOverlaysForGesture it }
                    val ok = svc.tapAt(x, y); delay(120)
                    report("tap", com.arix.app.UiActionBus.rectAround(x, y), ok, label = tapLabel)
                    if (ok) ToolResult("已点 ($x,$y)") else ToolResult("点击失败", isError = true)
                }
            }
            "long_press" -> {
                val x = params.optInt("x", -1); val y = params.optInt("y", -1)
                if (x < 0 || y < 0) return ToolResult("请提供 x,y", isError = true)
                val dur = params.optInt("duration", 600).toLong()
                com.arix.app.OverlayVisibility.hideOwnOverlaysForGesture {
                    val pressLabel = labelAt(svc, x, y)   // 同上：让位后取，且长按同样会让界面跳走
                    UiObservationBinding.verifyTarget(params, x, y, pressLabel)
                        ?.let { return@hideOwnOverlaysForGesture it }
                    val ok = svc.longPress(x, y, dur); delay(dur + 120)
                    report("long_press", com.arix.app.UiActionBus.rectAround(x, y), ok, label = pressLabel)
                    if (ok) ToolResult("已长按 ($x,$y) ${dur}ms") else ToolResult("长按失败", isError = true)
                }
            }
            "swipe" -> {
                val x = params.optInt("x", -1); val y = params.optInt("y", -1)
                val x2 = params.optInt("x2", -1); val y2 = params.optInt("y2", -1)
                if (x < 0 || y < 0 || x2 < 0 || y2 < 0) return ToolResult("请提供 x,y,x2,y2", isError = true)
                com.arix.app.OverlayVisibility.hideOwnOverlaysForGesture {
                    val ok = svc.swipe(x, y, x2, y2, params.optInt("duration", 300).toLong()); delay(120)
                    report("swipe", com.arix.app.UiActionBus.rectAround(x, y, 20), ok, endX = x2, endY = y2)
                    if (ok) ToolResult("已滑动 ($x,$y)→($x2,$y2)") else ToolResult("滑动失败", isError = true)
                }
            }
            "set_text" -> {
                val t = params.optString("text", "")
                com.arix.app.OverlayVisibility.hideOwnOverlaysForGesture {
                    // 输入框位置要在写字**前**取：写完焦点可能就没了/框会重排。
                    // 让位（唤醒浮层失焦）后取，焦点/activeWindow 才交还给目标 App 的输入框，而不是浮层。
                    val box = try { svc.focusedInputBounds() } catch (_: Exception) { null }
                    val ok = svc.setText(t); delay(120)
                    report("set_text", box, ok, label = t.take(20))
                    if (ok) ToolResult("已输入文字") else ToolResult("没有聚焦的输入框（先点一下输入框）", isError = true)
                }
            }
            "back", "home", "recents", "notifications" -> {
                val ok = svc.global(action); delay(120)
                report(action, null, ok, label = action)
                if (ok) ToolResult("已执行 $action") else ToolResult("操作失败", isError = true)
            }
            else -> ToolResult("未知 action", isError = true)
        }
      } catch (c: kotlinx.coroutines.CancellationException) {
        // 用户按了停止：分支里有 delay，取消会从那儿抛上来。必须重抛，
        // 被下面的 catch(Exception) 吞掉的话这次工具调用会"假装成功"，STOP 就停不干净。
        throw c
      } catch (e: Exception) { ToolResult("界面操作失败: ${e.message}", isError = true) }
    }

    /** 坐标点命中了哪个元素：给纯坐标的 tap 也配上「点的是什么」的说明，纯数字对用户没意义。 */
    private fun labelAt(svc: com.arix.app.XtomAccessibilityService, x: Int, y: Int): String = try {
        // 取包含该点的最小矩形——嵌套布局里最小的那个才是用户眼中被点的东西
        svc.dumpNodes().filter { it.bounds.contains(x, y) }
            .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }?.label.orEmpty()
    } catch (_: Exception) { "" }

    /**
     * 兜底路径：Shizuku 以 shell UID 跑 input/keyevent —— 应用自己跑 input 必被 INJECT_EVENTS 拒，
     * 借 Shizuku 的身份才真能注入。
     * click_text 不兜底：认字要读屏，而读屏只有无障碍能做。
     */
    private suspend fun viaShizuku(action: String, params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        // 这条路走到这里说明无障碍没开 → 读不了屏 → 没有元素矩形，只能拿参数里的坐标造框，
        // 也没有 targetLabel。可视化因此比无障碍路径粗，但绝不能干脆不画：
        // 否则「只装了 Shizuku」的设备上这个功能会静默消失，用户以为是坏了。
        val px = params.optInt("x", -1); val py = params.optInt("y", -1)
        val px2 = params.optInt("x2", -1); val py2 = params.optInt("y2", -1)
        val vRect = if (px >= 0 && py >= 0) com.arix.app.UiActionBus.rectAround(px, py) else null

        // ★ 唯一的摘窗埋点（Shizuku 路径）：`input tap/swipe/text` 同样经系统输入派发注入，
        // 同样会被目标 App 的 filterTouchesWhenObscured / Android 12+ 触摸遮挡策略静默丢弃。
        // 摘窗要在拼命令之前就做完，保证 exec 那一刻屏幕上没有我们的窗口。
        com.arix.app.UiActionOverlay.detachForGesture()

        val cmd: String = when (action) {
            "tap" -> {
                val x = params.optInt("x", -1); val y = params.optInt("y", -1)
                if (x < 0 || y < 0) return@withContext ToolResult("请提供 x,y", isError = true)
                "input tap $x $y"
            }
            "long_press" -> {
                val x = params.optInt("x", -1); val y = params.optInt("y", -1)
                if (x < 0 || y < 0) return@withContext ToolResult("请提供 x,y", isError = true)
                // input 没有长按子命令：原地 swipe 够久就是长按
                "input swipe $x $y $x $y ${params.optInt("duration", 600)}"
            }
            "swipe" -> {
                val x = params.optInt("x", -1); val y = params.optInt("y", -1)
                val x2 = params.optInt("x2", -1); val y2 = params.optInt("y2", -1)
                if (x < 0 || y < 0 || x2 < 0 || y2 < 0) return@withContext ToolResult("请提供 x,y,x2,y2", isError = true)
                "input swipe $x $y $x2 $y2 ${params.optInt("duration", 300)}"
            }
            "set_text" -> {
                val t = params.optString("text", "")
                if (t.isBlank()) return@withContext ToolResult("请提供 text", isError = true)
                // 命令经 sh -c 跑，单引号包住防止空格/元字符被拆；文本里的单引号按 '\'' 收尾再续
                "input text '${t.replace("'", "'\\''")}'"
            }
            "back" -> "input keyevent 4"
            "home" -> "input keyevent 3"
            "recents" -> "input keyevent 187"
            "notifications" -> "cmd statusbar expand-notifications"
            "click_text" -> return@withContext ToolResult(
                "click_text 要先认字，只有无障碍能做（Shizuku 兜底看不见界面）。" +
                "请调 request_permission(permission=\"accessibility\") 开无障碍；或先 dump 拿到坐标后改用 tap。", isError = true)
            else -> return@withContext ToolResult("未知 action", isError = true)
        }
        val r = ShizukuAccess.exec(cmd)
            ?: return@withContext ToolResult("Shizuku 授权在执行前掉了，可调 request_permission(permission=\"shizuku\") 重新申请。", isError = true)
        val (code, out) = r
        // 必须认退出码：input 被拒(Permission denial)也只是打日志，吞掉结果就会谎报成功
        val ok = code == 0
        report(
            action, if (action == "swipe") com.arix.app.UiActionBus.rectAround(px, py, 20) else vRect, ok,
            endX = if (action == "swipe") px2 else null,
            endY = if (action == "swipe") py2 else null,
            label = if (action in setOf("back", "home", "recents", "notifications")) action else "",
            viaShizuku = true,
        )
        if (!ok) return@withContext ToolResult("Shizuku 执行失败（退出码 $code）：${out.ifBlank { "无输出" }}", isError = true)
        delay(120)
        ToolResult("已执行 $action（经 Shizuku）${if (out.isNotBlank()) "：$out" else ""}")
    }
}
