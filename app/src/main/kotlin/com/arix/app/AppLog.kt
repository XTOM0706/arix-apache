package com.arix.app

import android.content.Context

// ============================================================
// AppLog —— 全 App 统一的「运行日志」入口（内存环形缓冲，**不落盘、不外发**）。
//
// 为什么要有它：崩溃有 CrashReportPage 可看，可绝大多数问题不是崩溃——请求失败、
// 工具异常、备份失败、MCP 掉线，用户什么证据都拿不到，只能说「它坏了」。
// 有了这个环形缓冲，出问题当场进「运行日志」页复制/分享就是一份可读的现场。
//
// ⛔ 绝不能写进日志的东西（这条是本项目的既有承诺，别破）：
//    · API key / token / Authorization / cookie / 密码
//    · 完整对话正文（用户说了什么、AI 回了什么）
//    · 定位坐标、健康数据、通讯录、通知正文
//  写日志时只写「发生了什么」：动作名、结果、错误类型、耗时、长度这类元信息。
//  [redact] 是最后一道网，不是许可证——别指望它替你把不该写的擦干净。
//
// 用法（往各处接的时候就这么写）：
//    AppLog.d("backup", "GitHub 上传成功 size=" + n)
//    AppLog.w("mcp", "连接断开，5s 后重连")
//    AppLog.e("api", "请求失败", e)
// ============================================================
object AppLog {

    enum class Level { D, W, E }

    /** seq 是进程内单调递增的序号——列表 key 用它，同毫秒重复消息不会撞 key。 */
    data class Entry(val seq: Long, val ts: Long, val level: Level, val tag: String, val msg: String)

    /** 环形容量：手表内存有限，300 条约几十 KB，够覆盖一次完整的失败复现。 */
    const val CAPACITY = 300

    /** 单条上限：日志是给人看现场的，不是转储用的。超长一律截断。 */
    private const val MAX_MSG = 400

    private const val PREFS = "arix_app_log"
    private const val KEY_ENABLED = "enabled"

    private val ring = ArrayDeque<Entry>(CAPACITY)
    private val lock = Any()

    /** 只在持 lock 时自增，不需要额外的原子类型。 */
    private var seq = 0L

    /**
     * 开关缓存。默认**开**：这东西只在内存里转、不落盘也不联网，关了等于出问题时永远没证据。
     * 离开设备的动作（复制/分享）始终要用户手点，那才是需要默认保守的一侧。
     */
    @Volatile private var enabledCache: Boolean? = null

    /** 没有 Context 也能记（用进程级 application context 兜底；连它都还没起来就先记着）。 */
    private fun isEnabled(): Boolean {
        enabledCache?.let { return it }
        val c = XtomApp.appContext ?: return true
        val v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        enabledCache = v
        return v
    }

    fun enabled(c: Context): Boolean {
        enabledCache?.let { return it }
        val v = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        enabledCache = v
        return v
    }

    fun setEnabled(c: Context, on: Boolean) {
        enabledCache = on
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) clear()   // 关掉就把已有的也倒掉，别留着一份没人管的现场
    }

    // ---- 写入 ----

    fun d(tag: String, msg: String) = add(Level.D, tag, msg)
    fun w(tag: String, msg: String) = add(Level.W, tag, msg)

    fun e(tag: String, msg: String, t: Throwable? = null) {
        // 只记异常类型和一行 message，不记整条堆栈：堆栈是崩溃报告的活儿，
        // 而且业务异常的 message 里最容易夹带 URL/参数这类不该留的东西。
        val detail = t?.let { "${it.javaClass.simpleName}: ${it.message?.take(120) ?: ""}" }
        add(Level.E, tag, if (detail.isNullOrBlank()) msg else "$msg | $detail")
    }

    private fun add(level: Level, tag: String, msg: String) {
        if (!isEnabled()) return
        val clean = redact(msg).let { if (it.length > MAX_MSG) it.take(MAX_MSG) + "…" else it }
        synchronized(lock) {
            ring.addLast(Entry(++seq, System.currentTimeMillis(), level, tag.take(24), clean))
            while (ring.size > CAPACITY) ring.removeFirst()
        }
    }

    // ---- 读取 ----

    /** 最近的记录，新 → 旧。 */
    fun recent(): List<Entry> = synchronized(lock) { ring.asReversed().toList() }

    fun size(): Int = synchronized(lock) { ring.size }

    fun clear() = synchronized(lock) { ring.clear() }

    /** 导出成可粘贴的纯文本（复制/分享用）。 */
    fun dump(): String {
        val sdf = java.text.SimpleDateFormat("MM-dd HH:mm:ss.SSS", java.util.Locale.US)
        return recent().asReversed().joinToString("\n") { e ->
            "${sdf.format(java.util.Date(e.ts))} ${e.level.name}/${e.tag}: ${e.msg}"
        }
    }

    // ---- 兜底脱敏 ----

    private val PATTERNS: List<Pair<Regex, String>> = listOf(
        // "Bearer xxxxx" / "Basic xxxxx"
        // 替换串里的 \$1 是「正则第 1 个捕获组」，用反斜杠转义免得被读成 Kotlin 模板
        Regex("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._\\-+/=]{6,}") to "\$1 ***",
        // 常见密钥前缀：sk-… / ghp_… / github_pat_… / xoxb-…
        Regex("(?i)\\b(sk|ghp|gho|ghs|ghu|xoxb|xoxp|hf|pat)[-_][A-Za-z0-9_\\-]{8,}") to "\$1_***",
        Regex("(?i)\\bgithub_pat_[A-Za-z0-9_]{8,}") to "github_pat_***",
        // key=xxx / "token": "xxx" / password: xxx
        Regex("(?i)\\b(api[-_]?key|apikey|access[-_]?token|token|secret|password|passwd|pwd|authorization|cookie|session)\\b\\s*[=:]\\s*\"?[^\\s\",;&]{4,}") to "\$1=***",
        // URL 里的敏感 query
        Regex("(?i)([?&](?:key|token|secret|sign|access_token|api_key)=)[^&\\s]+") to "\$1***",
        // 邮箱
        Regex("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}") to "***@***",
        // 裸的长随机串（32 位以上的十六进制/base64 样式）——大概率是密钥或 id
        Regex("\\b[A-Za-z0-9_\\-]{32,}\\b") to "***",
    )

    /**
     * 最后一道网：把看起来像凭据的东西打掉。宁可多打码也不放行——日志少一段无所谓，
     * 泄一次 key 就是真出事。注意它不认识「这段是对话正文」，正文根本就不该传进来。
     */
    fun redact(s: String): String {
        var out = s
        for ((re, rep) in PATTERNS) out = re.replace(out, rep)
        return out
    }
}
