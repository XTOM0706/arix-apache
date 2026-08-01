package com.arix.tool

import org.json.JSONArray
import org.json.JSONObject

/**
 * browser —— AI 驱动的浏览器（参照 browser-use）。一个工具多用（action 区分），驱动一个常驻浏览器会话：
 * 先 open 打开页面，工具会返回**编号的可交互元素**+可见正文；再按编号 click/type、scroll/back 连续操作，
 * 每步都回读新页面状态。适合需要「像人一样操作网页」的任务：登录、搜索翻页、点进详情、填表提交、多步流程。
 * 只取信息用 open_page（一次性、更省）；需要点来点去、填东西、走流程才用 browser。
 */
class BrowserTool(private val context: android.content.Context) : Tool {
    override val name = "browser"
    override val description =
        "AI 驱动的浏览器：像人一样连续操作网页（登录/搜索翻页/点进详情/填表提交等多步流程）。" +
        "先 action=open 打开 url，工具会返回带**编号**的可交互元素（链接/按钮/输入框…）和页面文本；" +
        "再用 action=click/type 按 index 操作、scroll/back 翻动，每步都回读新状态，据此决定下一步，直到完成。" +
        "**碰到需要登录或人机验证（你过不了的）时用 action=assist**：会弹一个小窗让真人帮忙登录/过验证，人做完你再继续（登录态自动带上）。" +
        "只是抓一次网页正文用 open_page 更省；要真交互才用本工具。"
    override val permissionLevel = AndroidPermissionLevel.ACCESSIBILITY

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("open", "state", "click", "type", "scroll", "back", "assist", "close")))
                put("description", "open=打开url / state=重读当前页元素 / click=点第index个 / type=往第index个输入text / scroll=滚动 / back=后退 / assist=弹窗请真人登录或过人机验证 / close=关会话")
            })
            put("reason", JSONObject().apply { put("type", "string"); put("description", "action=assist 时给真人看的一句话，说明要帮忙做什么（如“请登录你的账号”）") })
            put("url", JSONObject().apply { put("type", "string"); put("description", "action=open 时要打开的网址") })
            put("index", JSONObject().apply { put("type", "integer"); put("description", "click/type 时目标元素的编号（来自上一步返回的元素表）") })
            put("text", JSONObject().apply { put("type", "string"); put("description", "action=type 时要输入的文字") })
            put("enter", JSONObject().apply { put("type", "boolean"); put("description", "type 后是否回车/提交表单（搜索框常用），默认 false") })
            put("direction", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("down", "up"))); put("description", "scroll 方向，默认 down") })
        })
        put("required", JSONArray(listOf("action")))
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val action = params.optString("action", "").trim().lowercase()
        return try {
            when (action) {
                "open", "navigate", "goto" -> {
                    val url0 = params.optString("url", "").trim()
                    if (url0.isBlank()) return ToolResult("请提供 url", isError = true)
                    val url = if (!url0.startsWith("http")) "https://$url0" else url0
                    ToolResult(BrowserAgent.formatState(BrowserAgent.navigate(context, url)))
                }
                "state", "read", "" -> ToolResult(BrowserAgent.formatState(BrowserAgent.state(context)))
                "click", "tap" -> {
                    if (!params.has("index")) return ToolResult("请提供 index（要点的元素编号）", isError = true)
                    guardWrite("click", isSubmit = false)?.let { return it }
                    ToolResult(BrowserAgent.formatState(BrowserAgent.click(context, params.optInt("index"))))
                }
                "type", "input", "fill" -> {
                    if (!params.has("index")) return ToolResult("请提供 index（要输入的输入框编号）", isError = true)
                    val enter = params.optBoolean("enter", false)
                    guardWrite("type", isSubmit = enter)?.let { return it }
                    ToolResult(BrowserAgent.formatState(BrowserAgent.type(context, params.optInt("index"), params.optString("text", ""), enter)))
                }
                "scroll" -> ToolResult(BrowserAgent.formatState(BrowserAgent.scroll(context, params.optString("direction", "down") != "up")))
                "back" -> ToolResult(BrowserAgent.formatState(BrowserAgent.back(context)))
                // 请真人帮忙登录/过人机验证：弹小窗，人做完后重新导航（带上新 cookie=登录态）回读页面
                "assist", "login", "verify", "captcha" -> {
                    val url = BrowserAgent.lastUrl.ifBlank { params.optString("url", "").let { if (it.isNotBlank() && !it.startsWith("http")) "https://$it" else it } }
                    if (url.isBlank()) return ToolResult("当前没有打开的页面，请先 action=open 打开要登录的网址", isError = true)
                    val reason = params.optString("reason", "").ifBlank { "需要登录或人机验证" }
                    val ok = BrowserAssistBus.request(url, reason)
                    if (!ok) ToolResult("人工协助未完成（用户取消或超时），可稍后重试或换条路。")
                    else {
                        // 真人已登录/过验证 → 本会话确定带登录态；接下来的首个写操作强制确认一次。
                        BrowserAgent.onAssisted()
                        ToolResult("✅ 人工协助完成。当前页面：\n" + BrowserAgent.formatState(BrowserAgent.navigate(context, url)))
                    }
                }
                "close" -> { BrowserAgent.close(); ToolResult("已关闭浏览器会话") }
                else -> ToolResult("未知 action：$action（可用 open/state/click/type/scroll/back/close）", isError = true)
            }
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { ToolResult("浏览器操作失败：${e.message}", isError = true) }
    }

    /**
     * 写操作事前确认。堵最高危路径：**被注入的网页内容驱动 AI，去操纵一个已登录的浏览器提交表单/发帖/点确认**。
     *
     * 什么时候拦：
     *  - `type` 且 enter=true（回车提交表单）——这本身就是「提交」，一律确认；
     *  - 在**已登录会话**里的 `type`/`click`——AI 在操纵一个已登录账号，确认；
     *  - `assist`（真人登录）之后的**首个写操作**——强制确认一次（哪怕设了「始终允许」）。
     * 读类动作（open/navigate/state/scroll/back、assist 拉登录窗）不进这里，零摩擦。
     *
     * 返回 null=放行；返回 ToolResult=被拦下（当作工具结果回给 AI，让它别硬重试）。
     * 注意：这里**不能**包 catch(Exception)——confirmAction 的挂起点被 STOP 取消时抛的 CancellationException
     * 必须原样往上抛（由 execute 外层的 catch(CancellationException){throw e} 接住），否则 STOP 停不掉。
     */
    private suspend fun guardWrite(action: String, isSubmit: Boolean): ToolResult? {
        val loggedIn = BrowserAgent.looksLoggedIn()
        val forceAfterAssist = BrowserAgent.assistWriteUnconfirmed
        // 只 gate 写/提交/登录态操作；非提交、又不在登录态、也不是 assist 后首个写 → 放行（正常浏览零摩擦）。
        if (!isSubmit && !loggedIn && !forceAfterAssist) return null

        // 用户此前对「登录态写操作」的选择：尊重它。但 assist 后的首个写操作强制问一次（忽略「始终允许」）。
        if (!forceAfterAssist) {
            when (ToolPermissionManager.override(PERM_KEY)) {
                ToolPermission.ALLOW -> return null
                ToolPermission.FORBID -> return ToolResult(
                    "已被用户的安全设置拦下：带登录态的浏览器写操作被设为「始终禁止」。请换别的方式，或让用户到设置里改。",
                    isError = true,
                )
                else -> {}
            }
        }

        val host = hostOf(BrowserAgent.lastUrl)
        val where = if (host.isNotBlank()) "「$host」" else "当前网页"
        // 只有真判定为登录态才说「已登录」，免得给一个普通站点的搜索提交也扣上「已登录」的帽子、误导用户。
        val site = if (loggedIn) "已登录的$where" else where
        val what = when {
            isSubmit -> "提交表单/搜索"
            action == "click" -> "点击（可能是发送/发布/确认之类的提交）"
            else -> "在输入框里填写内容"
        }
        val decision = ToolPermissionManager.confirmAction(
            key = PERM_KEY,
            level = AndroidPermissionLevel.ACCESSIBILITY,
            intent = "AI 要在$site 上$what，是否允许？",
            riskNote = "浏览器带着登录态（可能是你本人登录的账号）。若这一步是被网页里的内容诱导的，" +
                "可能会替你发帖/提交表单/改动账号——做完难撤回。",
            detail = "网页写操作事前确认 · ${BrowserAgent.lastUrl.take(200)}",
        )
        // 问不出来（无可交互确认界面/权限系统没起来）：对高危写操作 **fail-closed**——拦下比放行安全。
        if (decision == null) return ToolResult(
            "当前无法向用户确认这步网页写操作（没有可用的确认界面）。已暂不执行，请到前台重试或改用只读方式。",
            isError = true,
        )
        val allowed = decision == ToolPermissionManager.Decision.ALLOW_ONCE ||
            decision == ToolPermissionManager.Decision.ALWAYS_ALLOW
        if (!allowed) return ToolResult(
            "用户拒绝了这步网页写操作（在已登录页面上的提交/点击）。请不要重试，先问清楚他想怎么做。",
            isError = true,
        )
        // 放行：清掉「assist 后首个写操作」的一次性强制标记（这次已经确认过了）。
        if (forceAfterAssist) BrowserAgent.consumeAssistWriteConfirm()
        return null
    }

    private fun hostOf(url: String): String =
        try { android.net.Uri.parse(url).host ?: "" } catch (_: Exception) { "" }

    companion object {
        // 「始终允许/始终禁止」记在这个键上。**故意不叫 "browser"**：用户在写操作确认框上点「始终允许」，
        // 意思是「以后别再为登录态写操作拦我」，不是「把 browser 工具本身设为始终允许」——后者会连读网页/导航
        // 也不再走权限、还把本该 gate 的写操作一并放了。两件事必须分开存（对齐 UiDangerGuard 的 PERM_KEY 做法）。
        private const val PERM_KEY = "browser·登录态写操作"
    }
}
