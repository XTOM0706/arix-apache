package com.arix.tool

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================
// skill —— 技能录制器（自包含，不动共享文件）。
// 录制：用户在其它 App 里真实点/输入，无障碍事件被翻成一串 UI 操作步骤存起来。
// 回放：把步骤逐条交给 UiControlTool（复用它的 tap/swipe/click_text/set_text/系统键 + 无障碍闸门）。
// 步骤 schema 与 ui_control 参数对齐：{action,text,x,y,x2,y2,duration}，另加 wait(回放前等待毫秒)。
//
// 录制需要无障碍服务把事件喂给这里：见文件末尾「HOOK 片段」，由用户挂到 XtomAccessibilityService。
// 还需放开无障碍配置的 eventTypes（见「CONFIG 片段」），否则收不到点击/输入事件。
// ============================================================

// ---- 持久化：filesDir/skills.json，形如 [{name,desc,steps:[...]}]，与 WorkflowStore 同构 ----
object SkillStore {
    private fun file(c: Context) = File(c.filesDir, "skills.json")
    fun all(c: Context): JSONArray =
        try { if (file(c).exists()) JSONArray(file(c).readText()) else JSONArray() } catch (_: Exception) { JSONArray() }
    fun names(c: Context): List<String> = all(c).let { arr ->
        (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } }
    }
    fun get(c: Context, name: String): JSONObject? {
        val arr = all(c); for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("name") == name) return it }; return null
    }
    fun save(c: Context, skill: JSONObject) {
        val name = skill.optString("name"); if (name.isBlank()) return
        val arr = all(c); val out = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("name") != name) out.put(it) }
        out.put(skill)
        try { file(c).writeText(out.toString()) } catch (_: Exception) {}
    }
    fun delete(c: Context, name: String) {
        val arr = all(c); val out = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("name") != name) out.put(it) }
        try { file(c).writeText(out.toString()) } catch (_: Exception) {}
    }
}

// ---- 录制器：全局单例，无障碍服务在录制期间把事件喂进来 ----
object SkillRecorder {
    @Volatile var isRecording: Boolean = false
        private set
    @Volatile var recordingName: String = ""
        private set

    private val steps = ArrayList<JSONObject>()
    private var lastEventAt = 0L
    private const val MAX_STEPS = 200
    private const val MAX_WAIT_MS = 4000L   // 录制时的停顿封顶（回放不会傻等超过这个）

    /** 开始录制。已在录制中则拒绝。清空缓冲。 */
    @Synchronized fun start(name: String): Boolean {
        if (isRecording) return false
        steps.clear(); lastEventAt = System.currentTimeMillis()
        recordingName = name; isRecording = true
        return true
    }

    /** 结束录制并落库，返回步数（0=没录到任何可用步骤，未保存）。 */
    @Synchronized fun stop(context: Context): Int {
        if (!isRecording) return -1
        isRecording = false
        val captured = steps.size
        if (captured > 0) {
            val arr = JSONArray(); steps.forEach { arr.put(it) }
            SkillStore.save(context, JSONObject().put("name", recordingName).put("desc", "录制").put("steps", arr))
        }
        steps.clear(); recordingName = ""
        return captured
    }

    /** 放弃录制不保存。 */
    @Synchronized fun cancel() { isRecording = false; steps.clear(); recordingName = "" }

    /**
     * 无障碍服务在录制期间调用：把一条事件翻成步骤。跨包公开供 XtomAccessibilityService 调用。
     * 点击→click_text(有文字更抗布局变化) 否则 tap 坐标；文本变化→set_text(连续输入合并成一条)；滚动→粗略 swipe。
     */
    @Synchronized fun onEvent(event: AccessibilityEvent?) {
        if (!isRecording || event == null) return
        if (steps.size >= MAX_STEPS) return
        val now = System.currentTimeMillis()
        val gap = (now - lastEventAt).coerceIn(0L, MAX_WAIT_MS)
        lastEventAt = now

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val node = event.source
                val label = (node?.text ?: node?.contentDescription)?.toString()?.trim().orEmpty()
                val step = JSONObject().put("wait", gap)
                if (label.isNotBlank() && label.length <= 60) {
                    step.put("action", "click_text").put("text", label)
                } else {
                    val r = boundsOf(node) ?: return
                    step.put("action", "tap").put("x", r.centerX()).put("y", r.centerY())
                }
                steps.add(step)
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                // 用节点当前完整文本作为 set_text 目标；连续输入(同一步 set_text)就地更新而不是堆一堆
                val txt = (event.source?.text ?: event.text?.joinToString(""))?.toString() ?: return
                val last = steps.lastOrNull()
                if (last != null && last.optString("action") == "set_text") {
                    last.put("text", txt)
                } else {
                    steps.add(JSONObject().put("wait", gap).put("action", "set_text").put("text", txt))
                }
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // scrollDeltaX/Y 是 API 28+ 才有；低版本拿不到方向，直接跳过滚动录制（不影响点击/输入）
                if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.P) return
                // 无障碍拿不到真实手势轨迹，只能按节点范围 + 滚动增量方向粗略造一条 swipe（best-effort）
                val r = boundsOf(event.source) ?: return
                if (r.width() <= 0 || r.height() <= 0) return
                val cx = r.centerX(); val cy = r.centerY()
                val dy = event.scrollDeltaY; val dx = event.scrollDeltaX
                val step = JSONObject().put("wait", gap).put("action", "swipe").put("duration", 300)
                when {
                    dy != 0 && dy != Int.MIN_VALUE -> {
                        val amp = (r.height() * 0.4).toInt().coerceAtLeast(40)
                        val sign = if (dy > 0) -1 else 1   // 内容向下滚(dy>0)=手指上滑
                        step.put("x", cx).put("y", cy).put("x2", cx).put("y2", cy + sign * amp)
                    }
                    dx != 0 && dx != Int.MIN_VALUE -> {
                        val amp = (r.width() * 0.4).toInt().coerceAtLeast(40)
                        val sign = if (dx > 0) -1 else 1
                        step.put("x", cx).put("y", cy).put("x2", cx + sign * amp).put("y2", cy)
                    }
                    else -> return
                }
                steps.add(step)
            }
        }
    }

    private fun boundsOf(node: AccessibilityNodeInfo?): Rect? {
        if (node == null) return null
        return try { val r = Rect(); node.getBoundsInScreen(r); if (r.width() > 0 && r.height() > 0) r else null } catch (_: Exception) { null }
    }

    /** 回放：逐步交给 UiControlTool（复用无障碍闸门/服务未开的提示）。步间遵守录制停顿(封顶)。 */
    suspend fun play(context: Context, skill: JSONObject): String = withContext(Dispatchers.Default) {
        val steps = skill.optJSONArray("steps") ?: return@withContext "技能「${skill.optString("name")}」没有步骤"
        if (steps.length() == 0) return@withContext "技能没有步骤"
        val ui = UiControlTool(context)
        val report = StringBuilder("▶ 回放技能「${skill.optString("name")}」\n")
        for (i in 0 until steps.length()) {
            currentCoroutineContext().ensureActive()   // STOP 可在步间停下
            val step = steps.optJSONObject(i) ?: continue
            val wait = step.optLong("wait", 0L).coerceIn(0L, MAX_WAIT_MS)
            if (wait > 0) delay(wait)
            val action = step.optString("action")
            // 只挑 ui_control 认得的参数传过去
            val params = JSONObject().put("action", action)
            for (k in listOf("text", "x", "y", "x2", "y2", "duration")) if (step.has(k)) params.put(k, step.get(k))
            val r = ui.execute(params)
            report.append("  ${i + 1}. $action → ${if (r.isError) "✗" else "✓"} ${r.content.take(80).replace("\n", " ")}\n")
            if (r.isError) { report.append("（第 ${i + 1} 步失败，已停止）"); return@withContext report.toString() }
        }
        report.append("✅ 完成（${steps.length()} 步）")
        report.toString()
    }
}

// ---- AI 工具入口：录/停/列/看/放/删 ----
class SkillTool(private val context: Context) : Tool {
    override val name = "skill"
    override val description = "技能录制器：把一串界面操作录成序列存起来，之后一键回放（免每次让 AI 现看现点）。" +
        "action=record_start(name 开始录制，之后你在目标App里真实点按/输入即被记录)、record_stop(停止并保存)、record_cancel(放弃)、" +
        "list(列出技能)、show(name 看步骤)、play(name 回放)、delete(name 删)。回放走无障碍代操作，需先开启无障碍服务。"
    override val permissionLevel = AndroidPermissionLevel.ACCESSIBILITY
    // 录制和回放都走无障碍代操作，两条特权都没有时整件事做不了
    override val requires = ToolRequirement.UI_AUTOMATION

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("record_start", "record_stop", "record_cancel", "list", "show", "play", "delete")))
                put("description", "操作")
            })
            put("name", JSONObject().apply { put("type", "string"); put("description", "技能名字（record_start/show/play/delete 需要）") })
        })
        put("required", JSONArray(listOf("action")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        when (params.optString("action", "")) {
            "record_start" -> {
                val name = params.optString("name", "").trim()
                if (name.isBlank()) return@withContext ToolResult("record_start 需要 name", isError = true)
                if (com.arix.app.XtomAccessibilityService.instance == null)
                    return@withContext ToolResult("录制需要先开启无障碍服务（设置→无障碍→Arix）。", isError = true)
                if (!SkillRecorder.start(name))
                    return@withContext ToolResult("已经在录制「${SkillRecorder.recordingName}」，先 record_stop/record_cancel。", isError = true)
                ToolResult("开始录制技能「$name」。现在去目标 App 里正常点按/输入即可，完成后调 skill record_stop 保存。")
            }
            "record_stop" -> {
                val n = SkillRecorder.recordingName
                val count = SkillRecorder.stop(context)
                when {
                    count < 0 -> ToolResult("当前没有在录制", isError = true)
                    count == 0 -> ToolResult("没录到任何可用操作（未保存）。可能无障碍配置未放开点击/输入事件类型。", isError = true)
                    else -> ToolResult("已保存技能「$n」（$count 步）。用 skill play name=$n 回放。")
                }
            }
            "record_cancel" -> { SkillRecorder.cancel(); ToolResult("已放弃录制") }
            "list" -> {
                val ns = SkillStore.names(context)
                val rec = if (SkillRecorder.isRecording) "（正在录制「${SkillRecorder.recordingName}」）\n" else ""
                if (ns.isEmpty()) ToolResult(rec + "还没有技能") else ToolResult(rec + "技能：\n" + ns.joinToString("\n") { "· $it" })
            }
            "show" -> {
                val skill = SkillStore.get(context, params.optString("name", "").trim())
                    ?: return@withContext ToolResult("没找到该技能", isError = true)
                ToolResult(skill.toString(2))
            }
            "play" -> {
                val name = params.optString("name", "").trim()
                if (SkillRecorder.isRecording) return@withContext ToolResult("正在录制中，先 record_stop 再回放", isError = true)
                val skill = SkillStore.get(context, name) ?: return@withContext ToolResult("没有技能「$name」", isError = true)
                ToolResult(SkillRecorder.play(context, skill))
            }
            "delete" -> {
                val name = params.optString("name", "").trim()
                SkillStore.delete(context, name); ToolResult("已删除技能「$name」")
            }
            else -> ToolResult("未知 action", isError = true)
        }
    }
}
