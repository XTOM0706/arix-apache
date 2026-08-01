package com.arix.tool

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================
// 工作流 —— 把「一串工具调用 + 变量传递 + 出错处理」存成可复用流程。
// 步骤里可用 {{input}}(运行输入)、{{last}}(上一步结果)、{{stepN}}(第N步结果) 引用。
// 每步照走 ToolManager（含权限审批）；可被 STOP 取消（步间 ensureActive）。持久化到 filesDir/workflows.json。
//
// 触发器（[WorkflowTrigger]）只是**入口层**：描述「什么时候该跑」，真正挂系统监听、防抖、
// 调 [WorkflowEngine.run] 的是 app 层的 WorkflowTriggers。这里只负责定义与持久化。
// ============================================================

/**
 * 触发方式。id 是**落盘用的稳定字符串**——不能用 enum 序号存，改一次顺序老数据全错位。
 */
enum class WorkflowTriggerType(val id: String) {
    NONE("none"),                                 // 只手动跑
    INTERVAL("interval"),                         // 每 N 分钟
    DAILY("daily"),                               // 每天 hh:mm
    CRON("cron"),                                 // cron 表达式（星期几 + 多时刻）
    BOOT("boot"),                                 // 开机
    SCREEN_ON("screen_on"),
    SCREEN_OFF("screen_off"),
    BATTERY_LOW("battery_low"),
    POWER_CONNECTED("power_connected"),
    POWER_DISCONNECTED("power_disconnected"),
    WIFI_CONNECTED("wifi_connected"),
    WIFI_DISCONNECTED("wifi_disconnected"),
    HEADSET_PLUGGED("headset_plugged"),
    HEADSET_UNPLUGGED("headset_unplugged"),
    BT_CONNECTED("bt_connected"),                 // 指定蓝牙设备连上
    BT_DISCONNECTED("bt_disconnected"),           // 指定蓝牙设备断开
    GEOFENCE_ENTER("geo_enter"),                  // 进入某个地点半径内
    GEOFENCE_EXIT("geo_exit"),                    // 离开某个地点半径
    NOTIF_MATCH("notif_match"),                   // 通知内容命中（含正则）
    APP_LAUNCHED("app_launched"),                 // 指定 App 切到前台
    APP_CLOSED("app_closed"),                     // 指定 App 退出前台
    APP_FOREGROUND_LONG("app_fg_long");           // 指定 App 前台连续超过 N 分钟

    /** 走 WorkManager 排期的（其余都是系统事件监听）。两类的挂载/摘除方式完全不同。 */
    val isTimed: Boolean get() = this == INTERVAL || this == DAILY || this == CRON

    /** 地理围栏两向。挂的是 LocationManager 的 proximity alert（系统侧围栏，不是我们自己轮询定位）。 */
    val isGeo: Boolean get() = this == GEOFENCE_ENTER || this == GEOFENCE_EXIT

    /** 需要 UsageStatsManager 采样的三种。这类没有系统回调，只能取样——所以采样窗口要卡死（见 WorkflowTriggers）。 */
    val isApp: Boolean get() = this == APP_LAUNCHED || this == APP_CLOSED || this == APP_FOREGROUND_LONG

    val isBt: Boolean get() = this == BT_CONNECTED || this == BT_DISCONNECTED

    companion object {
        fun of(id: String?): WorkflowTriggerType = values().firstOrNull { it.id == id } ?: NONE
    }
}

/**
 * cron 表达式（5 段：分 时 日 月 周）。**纯 Kotlin，不碰 Android**，好让它能被直接单测。
 *
 * 支持 `*`、`5`、`1-5`、`1,3,5`、`* / 15`（星号斜杠，此处为避开注释结束符加了空格）、`1-9/2`；月份可写 `jan..dec`，星期可写 `sun..sat`（0 和 7 都算周日）。
 * 「星期几 + 时刻」= `0 8 * * mon-fri`；「一天多个时刻」= `0 8,12,18 * * *`。
 *
 * 「日」和「周」都被限定时按**标准 cron 的 OR 语义**（`0 0 1 * mon` = 每月 1 号**或**每个周一），
 * 不是取交集——这条反直觉但必须跟 crontab 一致，否则用户从别处抄来的表达式行为会变。
 *
 * 只解析到分钟：秒级触发在手表上没有意义（WorkManager 的排期误差本来就在分钟量级）。
 */
class CronSpec private constructor(
    private val minutes: List<Int>,
    private val hours: List<Int>,
    private val doms: List<Int>,
    private val months: List<Int>,
    private val dows: List<Int>,
    private val domRestricted: Boolean,
    private val dowRestricted: Boolean,
) {
    /**
     * [from] 之后**严格下一个**匹配时刻；一年内都不匹配（比如 `0 0 30 2 *` 2 月 30 号）返回 null。
     *
     * 按天扫（最多 366 次）而不是按分钟扫（最多 52 万次）：每次重排都要算一遍，
     * 逐分钟 Calendar.add 在手表的 CPU 上是能被感知到的开销。
     */
    fun nextAfter(from: Long): Long? {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = from
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            add(java.util.Calendar.MINUTE, 1)          // 严格之后，避免刚跑完又算出同一分钟
        }
        for (day in 0..366) {
            if (dayMatches(cal)) {
                val curH = if (day == 0) cal.get(java.util.Calendar.HOUR_OF_DAY) else 0
                val curM = if (day == 0) cal.get(java.util.Calendar.MINUTE) else 0
                for (h in hours) {
                    if (h < curH) continue
                    for (m in minutes) {
                        if (h == curH && m < curM) continue
                        val t = (cal.clone() as java.util.Calendar).apply {
                            set(java.util.Calendar.HOUR_OF_DAY, h)
                            set(java.util.Calendar.MINUTE, m)
                            set(java.util.Calendar.SECOND, 0)
                            set(java.util.Calendar.MILLISECOND, 0)
                        }.timeInMillis
                        if (t > from) return t
                    }
                }
            }
            cal.add(java.util.Calendar.DAY_OF_MONTH, 1)
            cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
            cal.set(java.util.Calendar.MINUTE, 0)
        }
        return null
    }

    private fun dayMatches(c: java.util.Calendar): Boolean {
        if ((c.get(java.util.Calendar.MONTH) + 1) !in months) return false
        val dom = c.get(java.util.Calendar.DAY_OF_MONTH)
        val dow = c.get(java.util.Calendar.DAY_OF_WEEK) - 1      // Calendar 的 SUNDAY=1 → cron 的 0
        return when {
            domRestricted && dowRestricted -> dom in doms || dow in dows   // 标准 cron 的 OR
            domRestricted -> dom in doms
            dowRestricted -> dow in dows
            else -> true
        }
    }

    companion object {
        private val MONTH_NAMES = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
            .withIndex().associate { (i, s) -> s to i + 1 }
        private val DOW_NAMES = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat")
            .withIndex().associate { (i, s) -> s to i }

        /** 解析失败一律返回 null（不抛）：这串是用户手打的，格式错是常态，UI 据此显示「表达式无效」。 */
        fun parse(expr: String?): CronSpec? {
            val f = expr?.trim()?.split(Regex("\\s+"))?.filter { it.isNotEmpty() } ?: return null
            if (f.size != 5) return null
            val min = field(f[0], 0, 59, emptyMap()) ?: return null
            val hour = field(f[1], 0, 23, emptyMap()) ?: return null
            val dom = field(f[2], 1, 31, emptyMap()) ?: return null
            val mon = field(f[3], 1, 12, MONTH_NAMES) ?: return null
            // 7 和 0 都是周日：crontab 两种写法都收，否则抄来的表达式会莫名少触发一天
            val dow = (field(f[4], 0, 7, DOW_NAMES) ?: return null).map { if (it == 7) 0 else it }.distinct().sorted()
            return CronSpec(min, hour, dom, mon, dow, f[2] != "*", f[4] != "*")
        }

        /** 一段字段：`*` / `n` / `a-b` / 上述任一 `/step`，逗号可并列。任何越界或写错都返回 null。 */
        private fun field(s: String, lo: Int, hi: Int, names: Map<String, Int>): List<Int>? {
            val out = sortedSetOf<Int>()
            for (part in s.split(',')) {
                if (part.isBlank()) return null
                val slash = part.indexOf('/')
                val step: Int = if (slash < 0) 1 else {
                    val n = part.substring(slash + 1).toIntOrNull()
                    if (n == null || n <= 0) return null else n     // 步长 0 会把下面的 while 变成死循环
                }
                val body = if (slash < 0) part else part.substring(0, slash)
                val a: Int
                val b: Int
                when {
                    body == "*" -> { a = lo; b = hi }
                    body.contains('-') -> {
                        val p = body.split('-')
                        if (p.size != 2) return null
                        a = num(p[0], names) ?: return null
                        b = num(p[1], names) ?: return null
                    }
                    else -> {
                        a = num(body, names) ?: return null
                        b = if (slash >= 0) hi else a          // `5/10` = 从 5 起每 10 步，`5` = 只有 5
                    }
                }
                if (a < lo || b > hi || a > b) return null
                var v = a
                while (v <= b) { out.add(v); v += step }
            }
            return if (out.isEmpty()) null else out.toList()
        }

        private fun num(s: String, names: Map<String, Int>): Int? =
            s.trim().lowercase().let { names[it] ?: it.toIntOrNull() }
    }
}

/**
 * 一个工作流的触发配置。存在工作流 JSON 的 `trigger` 字段里。
 *
 * **向后兼容**：老数据没有 `trigger` 字段，[from] 收到 null 就整份取默认值（type=NONE、enabled=false），
 * 反序列化不会崩，也不会凭空给老工作流挂上监听。新字段一律加在这个对象里、都带默认值。
 */
data class WorkflowTrigger(
    val type: WorkflowTriggerType = WorkflowTriggerType.NONE,
    val enabled: Boolean = false,
    /** INTERVAL：每几分钟。手表上低于 [MIN_INTERVAL_MIN] 没意义（WorkManager 周期下限也在这个量级），存进来就夹住。 */
    val intervalMin: Int = 60,
    /** DAILY：每天几点几分。 */
    val hour: Int = 8,
    val minute: Int = 0,
    /**
     * 最小触发间隔（分钟）。**这是省钱省电的关键旋钮**：开关屏一晚上能来几十次，
     * 每次都真跑一遍工作流 = 几十次大模型调用。默认 [DEFAULT_GAP_MIN] 分钟内只认第一次。
     */
    val minGapMin: Int = DEFAULT_GAP_MIN,
    /**
     * 触发时传给工作流的输入（步骤里 {{input}} 引用）。
     * 事件类触发还可以在这里写 {{title}}/{{text}}/{{app}}/{{pkg}}/{{device}}，由触发层先替换掉再交给引擎。
     */
    val input: String = "",
    // ---- 以下都是「某一类触发器专用」的配置。全部带默认值，老数据反序列化不会受影响。 ----
    /** CRON：5 段表达式（分 时 日 月 周），见 [CronSpec]。 */
    val cron: String = "",
    /** APP_* / NOTIF_MATCH：包名（可只写一段，按 contains 匹配）。 */
    val pkg: String = "",
    /** NOTIF_MATCH：标题+正文里要命中的文字；[regex]=true 时按正则。 */
    val pattern: String = "",
    val regex: Boolean = false,
    /** BT_*：蓝牙设备名（contains）或 MAC 地址（全等）。留空=任意设备。 */
    val device: String = "",
    /** GEOFENCE_*：圆心与半径。半径别小于 [MIN_GEO_RADIUS_M]——比定位误差还小的围栏只会反复误触发。 */
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val radiusM: Int = 200,
    /** APP_FOREGROUND_LONG：前台连续超过几分钟算数。 */
    val fgMinutes: Int = 30,
) {
    /**
     * 这一类触发器需要的配置齐不齐。**不齐就当没开**——否则会出现「围栏圆心是 (0,0)（几内亚湾）却照常挂着监听」、
     * 「通知触发器没写关键词于是每条通知都跑一遍工作流」这种默认值造成的灾难。UI 会把「没配齐」单独标出来。
     */
    val configured: Boolean
        get() = when (type) {
            WorkflowTriggerType.CRON -> CronSpec.parse(cron) != null
            WorkflowTriggerType.GEOFENCE_ENTER, WorkflowTriggerType.GEOFENCE_EXIT ->
                (lat != 0.0 || lon != 0.0) && lat in -90.0..90.0 && lon in -180.0..180.0 && radiusM >= MIN_GEO_RADIUS_M
            // 关键词和包名至少给一个：两个都空 = 匹配全部通知
            WorkflowTriggerType.NOTIF_MATCH -> pattern.isNotBlank() || pkg.isNotBlank()
            WorkflowTriggerType.APP_LAUNCHED, WorkflowTriggerType.APP_CLOSED -> pkg.isNotBlank()
            WorkflowTriggerType.APP_FOREGROUND_LONG -> pkg.isNotBlank() && fgMinutes >= 1
            else -> true
        }

    /** 真正需要挂监听的条件：开着 + 选了类型 + 该填的填了。缺一都不挂。 */
    val active: Boolean get() = enabled && type != WorkflowTriggerType.NONE && configured

    fun toJson(): JSONObject = JSONObject()
        .put("type", type.id)
        .put("enabled", enabled)
        .put("intervalMin", intervalMin)
        .put("hour", hour)
        .put("minute", minute)
        .put("minGapMin", minGapMin)
        .put("input", input)
        .put("cron", cron)
        .put("pkg", pkg)
        .put("pattern", pattern)
        .put("regex", regex)
        .put("device", device)
        .put("lat", lat)
        .put("lon", lon)
        .put("radiusM", radiusM)
        .put("fgMinutes", fgMinutes)

    companion object {
        const val DEFAULT_GAP_MIN = 10
        const val MIN_INTERVAL_MIN = 15
        /** 围栏半径下限。手表上网络定位误差常年 50~150m，围栏比误差还小就会在原地反复进出。 */
        const val MIN_GEO_RADIUS_M = 100
        val NONE = WorkflowTrigger()

        fun from(o: JSONObject?): WorkflowTrigger {
            if (o == null) return NONE   // 老数据 / 没配过：静默取默认，绝不抛
            return try {
                WorkflowTrigger(
                    type = WorkflowTriggerType.of(o.optString("type", "none")),
                    enabled = o.optBoolean("enabled", false),
                    intervalMin = o.optInt("intervalMin", 60).coerceAtLeast(MIN_INTERVAL_MIN),
                    hour = o.optInt("hour", 8).coerceIn(0, 23),
                    minute = o.optInt("minute", 0).coerceIn(0, 59),
                    minGapMin = o.optInt("minGapMin", DEFAULT_GAP_MIN).coerceAtLeast(1),
                    input = o.optString("input", ""),
                    cron = o.optString("cron", ""),
                    pkg = o.optString("pkg", ""),
                    pattern = o.optString("pattern", ""),
                    regex = o.optBoolean("regex", false),
                    device = o.optString("device", ""),
                    lat = o.optDouble("lat", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    lon = o.optDouble("lon", 0.0).takeIf { !it.isNaN() } ?: 0.0,
                    radiusM = o.optInt("radiusM", 200).coerceIn(MIN_GEO_RADIUS_M, 20_000),
                    fgMinutes = o.optInt("fgMinutes", 30).coerceIn(1, 480),
                )
            } catch (_: Exception) { NONE }
        }
    }
}

object WorkflowStore {
    private fun file(c: Context) = File(c.filesDir, "workflows.json")
    fun all(c: Context): JSONArray = try { if (file(c).exists()) JSONArray(file(c).readText()) else JSONArray() } catch (_: Exception) { JSONArray() }
    fun names(c: Context): List<String> = all(c).let { arr -> (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } } }
    fun get(c: Context, name: String): JSONObject? {
        val arr = all(c); for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("name") == name) return it }; return null
    }
    fun save(c: Context, wf: JSONObject) {
        val name = wf.optString("name"); if (name.isBlank()) return
        val arr = all(c); val out = JSONArray()
        var oldTrigger: JSONObject? = null
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") != name) out.put(o) else oldTrigger = o.optJSONObject("trigger")
        }
        // 触发器是**用户在页面上配的**，而 AI 的 workflow create 只带 name/desc/steps。
        // 同名重建时若不把老触发器搬过来，用户配好的自动触发会被 AI 一次改写默默清掉。
        if (!wf.has("trigger")) oldTrigger?.let { wf.put("trigger", it) }
        out.put(wf)
        try { file(c).writeText(out.toString()) } catch (_: Exception) {}
        notifyChanged(c)
    }
    fun delete(c: Context, name: String) {
        val arr = all(c); val out = JSONArray()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (it.optString("name") != name) out.put(it) }
        try { file(c).writeText(out.toString()) } catch (_: Exception) {}
        notifyChanged(c)
    }

    // ---------- 触发器存取 ----------

    fun trigger(c: Context, name: String): WorkflowTrigger = WorkflowTrigger.from(get(c, name)?.optJSONObject("trigger"))

    fun setTrigger(c: Context, name: String, t: WorkflowTrigger) {
        val wf = get(c, name) ?: return
        save(c, wf.put("trigger", t.toJson()))
    }

    /**
     * 所有**真正开着**的触发器。注册中心据此决定挂哪些监听——没人用的一律不挂（手表上耗电是第一约束）。
     */
    fun activeTriggers(c: Context): List<Pair<String, WorkflowTrigger>> {
        val arr = all(c)
        val out = ArrayList<Pair<String, WorkflowTrigger>>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name").takeIf { it.isNotBlank() } ?: continue
            val t = WorkflowTrigger.from(o.optJSONObject("trigger"))
            if (t.active) out.add(n to t)
        }
        return out
    }

    /**
     * 增删改后让触发注册中心重挂监听。
     * 吞掉一切异常：存取层绝不能因为「通知不到 UI 层」而写不进文件。注册中心没 init 过时是空操作。
     */
    private fun notifyChanged(c: Context) {
        try { com.arix.app.WorkflowTriggers.onStoreChanged(c) } catch (_: Throwable) {}
    }
}

object WorkflowEngine {
    private val depth = java.util.concurrent.atomic.AtomicInteger(0)

    /** 顺序跑每一步，变量替换后交给 ToolManager 执行；出错按 onError(stop/continue) 处理。返回过程报告。 */
    suspend fun run(context: Context, wf: JSONObject, input: String): String {
        if (depth.get() >= 3) return "工作流嵌套过深（防递归/自我调用），已中止"
        depth.incrementAndGet()
        try {
            return runInner(context, wf, input)
        } finally { depth.decrementAndGet() }
    }

    private suspend fun runInner(context: Context, wf: JSONObject, input: String): String {
        val steps = wf.optJSONArray("steps") ?: return "工作流「${wf.optString("name")}」没有步骤"
        if (steps.length() == 0) return "工作流没有步骤"
        val outputs = ArrayList<String>()
        val report = StringBuilder("▶ 运行工作流「${wf.optString("name")}」\n")
        for (i in 0 until steps.length()) {
            currentCoroutineContext().ensureActive()   // STOP 可在步间停下
            val step = steps.optJSONObject(i) ?: continue
            val tool = step.optString("tool")
            if (tool.isBlank()) { report.append("  ${i + 1}. (缺 tool，跳过)\n"); continue }
            // 单遍替换（一次扫过所有 {{var}}），避免「上一步结果里含 {{stepN}} 被二次展开」的污染
            val vars = HashMap<String, String>()
            vars["input"] = esc(input); vars["last"] = esc(outputs.lastOrNull() ?: "")
            outputs.forEachIndexed { k, o -> vars["step${k + 1}"] = esc(o) }
            val argsStr = Regex("""\{\{(\w+)\}\}""").replace(step.optJSONObject("args")?.toString() ?: "{}") { m -> vars[m.groupValues[1]] ?: m.value }
            val args = try { JSONObject(argsStr) } catch (_: Exception) { null }
            if (args == null) {
                report.append("  ${i + 1}. $tool → ✗ 参数格式错误（变量替换后不是合法 JSON，检查是否漏了引号）\n")
                if (step.optString("onError", "stop") != "continue") { report.append("（第 ${i + 1} 步参数错误，已停止）"); return report.toString() }
                outputs.add(""); continue
            }
            // caller 沿用外层：工作流是替谁跑的，每步就按谁的权限走（见 CallerContext）
            val r = ToolManager.execute(ToolCall("wf_$i", tool, args, caller = currentToolCaller()))
            outputs.add(r.content)
            report.append("  ${i + 1}. $tool → ${if (r.isError) "✗" else "✓"} ${r.content.take(140).replace("\n", " ")}\n")
            if (r.isError && step.optString("onError", "stop") != "continue") { report.append("（第 ${i + 1} 步出错，已停止）"); return report.toString() }
        }
        report.append("✅ 完成")
        return report.toString()
    }

    // 把要嵌进 JSON 字符串里的值转义（截断避免参数过长）
    private fun esc(s: String): String = JSONObject.quote(s.take(2000)).let { it.substring(1, it.length - 1) }
}
