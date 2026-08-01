package com.arix.cloudapi

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

// ============================================================
// 密钥池的「健康档案」存储 —— KeyPool 的持久化后端。
//
// 为什么要有这个文件：KeyPool 原来把冷却时间放在裸 ConcurrentHashMap 里，**进程一死就全没了**，
// 重启后又拿那枚已经 401 的死 key 去撞一次，用户每次冷启动都要白等一个首字延迟。
// 冷却/禁用到期时间、每 key 的成败统计都必须跨进程存活。
//
// ⚠ 三条硬约束，改这个文件前先读：
//  1) **不存 key 明文**。统计将来可能进备份（见 FullBackup/GitHubBackup），明文进备份等于泄密。
//     索引一律用 [fingerprint]（SHA-256 前 16 位十六进制）。API key 本身是高熵随机串，
//     单向哈希不可逆推；失败原因文本落盘前也要过 [redact] 洗掉可能被服务端回显的 key。
//  2) **绝不在发送链路上写盘**。上报只改内存 + 置脏位，真正的序列化+SharedPreferences 写
//     交给 IO 协程合批（[SAVE_DEBOUNCE_MS]）。一轮工具循环几十次上报 → 最多落一次盘。
//  3) 拿不到 Context 就**只在内存里跑**，功能不降级、不抛异常（cloudapi 是 library 模块，
//     CloudApiClient 手上没有 Context —— 与 ApiExtrasStore / ApiMonitor 同一套路子：
//     app 侧在 Application.onCreate 里调一次 [init]，之后读写端都不需要 Context）。
// ============================================================
object KeyHealthStore {

    private const val PREFS = "xtom_key_health"
    private const val FIELD = "v1"

    /** 合批窗口：置脏后 5 秒才真正落盘。够长（一轮对话的几十次上报合成一次写），
     *  又够短（用户看完一轮就切后台/杀进程，统计也已经在盘上）。 */
    private const val SAVE_DEBOUNCE_MS = 5_000L

    /** 30 天没再出现过的指纹清掉：用户换过的 key 会永久占位，不清会无限堆积。
     *  比 RikkaHub 的 24h 长得多——这里存的是「这枚 key 好不好」的长期结论，
     *  不是一次性的轮转游标，一天就忘等于每天重新踩一遍坑。 */
    private const val STALE_MS = 30L * 24 * 3600_000L

    /** 落盘条目上限，防极端情况（脚本批量刷 key）把 prefs 撑大。超了按最近使用时间保留。 */
    private const val MAX_ENTRIES = 200

    @Volatile private var app: Context? = null
    @Volatile private var loaded = false

    /** 指纹 -> 健康档案。发送链路只碰这张表（纯内存，无锁读）。 */
    private val recs = ConcurrentHashMap<String, KeyRec>()

    /** 池指纹 -> 轮转游标。持久化它是为了重启后别又从第 1 枚开始打（RikkaHub 同款考虑）。 */
    private val cursors = ConcurrentHashMap<String, AtomicInteger>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val saveScheduled = AtomicBoolean(false)

    // ---------------- 生命周期 ----------------

    /** app 侧调用一次（Application.onCreate 的后台线程里即可）：缓存 application context 并装载磁盘档案。 */
    fun init(context: Context) {
        if (app == null) app = context.applicationContext
        if (!loaded) load()
    }

    private fun load() {
        loaded = true
        val ctx = app ?: return
        try {
            val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(FIELD, "") ?: ""
            if (raw.isBlank()) return
            val root = JSONObject(raw)
            val now = System.currentTimeMillis()
            root.optJSONObject("keys")?.let { keys ->
                keys.keys().forEach { fp ->
                    val o = keys.optJSONObject(fp) ?: return@forEach
                    val seen = o.optLong("seen")
                    if (now - seen > STALE_MS) return@forEach   // 过期档案不装载，下次落盘自然消失
                    recs[fp] = KeyRec.fromJson(o)
                }
            }
            root.optJSONObject("cursors")?.let { cs ->
                cs.keys().forEach { pf -> cursors[pf] = AtomicInteger(cs.optInt(pf)) }
            }
        } catch (_: Throwable) {
            // 档案坏了不该让 App 起不来：丢掉重新统计即可（这份数据是可再生的）
        }
    }

    // ---------------- 指纹 / 脱敏 ----------------

    /** key 的稳定指纹：SHA-256 十六进制前 16 位。落盘、统计、日志一律用它，明文只活在内存里。 */
    fun fingerprint(key: String): String = try {
        val d = MessageDigest.getInstance("SHA-256").digest(key.trim().toByteArray(Charsets.UTF_8))
        buildString(16) { for (i in 0 until 8) append(String.format("%02x", d[i])) }
    } catch (_: Throwable) {
        // 极端兜底：拿不到 SHA-256 时用长度+hashCode，仍然不含明文
        "h" + key.length + "_" + Integer.toHexString(key.hashCode())
    }

    /** 展示用掩码（**只在内存里算，绝不落盘**）：sk-1234…cdef。 */
    fun mask(key: String): String {
        val k = key.trim()
        return if (k.length <= 12) "*".repeat(k.length.coerceAtLeast(4)) else k.take(6) + "…" + k.takeLast(4)
    }

    /** 落盘前洗掉失败原因里可能被服务端回显的 key 明文（有的供应商会把整条 Authorization 回显在报错里）。 */
    fun redact(reason: String?, key: String): String? {
        if (reason.isNullOrBlank()) return null
        val k = key.trim()
        val cleaned = if (k.length >= 8) reason.replace(k, mask(k)) else reason
        return cleaned.trim().take(160)
    }

    // ---------------- 读写 ----------------

    fun rec(fp: String): KeyRec = recs.getOrPut(fp) { KeyRec() }

    fun peek(fp: String): KeyRec? = recs[fp]

    /** 取该池的下一个轮转序号（自增）。游标变化**不单独触发落盘**——它每次请求都变，
     *  为它写盘就是白白的写放大；等下一次真正的健康变化落盘时顺手带上即可。 */
    fun nextCursor(poolFingerprint: String): Int =
        cursors.getOrPut(poolFingerprint) { AtomicInteger(0) }.getAndIncrement()

    /** 置脏：只排一次延迟落盘任务，不阻塞调用方（发送链路会走到这里）。 */
    fun markDirty() {
        if (app == null) return                       // 没 Context = 纯内存模式，不排任务
        if (!saveScheduled.compareAndSet(false, true)) return
        scope.launch {
            try {
                delay(SAVE_DEBOUNCE_MS)               // 合批窗口
                saveScheduled.set(false)
                persist()
            } catch (e: CancellationException) {
                saveScheduled.set(false)
                throw e                                // 取消必须原样重抛，不能吞
            } catch (_: Throwable) {
                saveScheduled.set(false)
            }
        }
    }

    /** 立即落盘（app 侧想在退出/进配置页前保一手时可调；同样跑在 IO，不阻塞调用线程）。 */
    fun flush() {
        if (app == null) return
        scope.launch {
            try {
                persist()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
            }
        }
    }

    private fun persist() {
        val ctx = app ?: return
        val now = System.currentTimeMillis()
        // 先剪枝：过期的、超量的都不写
        var entries = recs.entries.filter { now - it.value.lastSeen <= STALE_MS }
        if (entries.size > MAX_ENTRIES) entries = entries.sortedByDescending { it.value.lastSeen }.take(MAX_ENTRIES)
        val keys = JSONObject()
        entries.forEach { (fp, r) -> keys.put(fp, r.toJson()) }
        val cs = JSONObject()
        cursors.forEach { (pf, c) -> cs.put(pf, c.get()) }
        val root = JSONObject().put("keys", keys).put("cursors", cs)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(FIELD, root.toString()).apply()
    }

    /** 清空全部档案（供「重置密钥健康统计」用）。 */
    fun clearAll() {
        recs.clear()
        cursors.clear()
        markDirty()
        flush()
    }

    /** 只读遍历（快照用）。 */
    fun all(): Map<String, KeyRec> = recs
}

/**
 * 一枚 key 的健康档案（可变，进程内共享）。字段全部 @Volatile，写入走 [KeyPool] 里的 synchronized 段，
 * 读取（选 key、出快照）无锁——发送链路上不允许有锁竞争。
 *
 * 这里**只有指纹没有明文**：整个对象序列化进 SharedPreferences 也不会泄露 key。
 */
class KeyRec {
    /** 有结果回报的调用次数（发出去但被 STOP 取消的不算，否则成功率会被用户的停止操作拉垮）。 */
    @Volatile var total: Long = 0
    @Volatile var success: Long = 0
    /** 连续失败次数，成功即清零——判「这枚 key 自己坏了」的主信号。 */
    @Volatile var consecutiveFailures: Int = 0
    /** 短期冷却到期（429 限流之类），到期自动回到轮转。 */
    @Volatile var cooldownUntil: Long = 0
    /** 自动禁用到期（连续失败到阈值 / 401 认证失败），到期进入半开探测。 */
    @Volatile var disabledUntil: Long = 0
    /** 已经被禁用过几轮——用于指数退避，真死的 key 不该每 15 分钟浪费一次首字延迟。 */
    @Volatile var disableRound: Int = 0
    @Volatile var lastFailureCode: Int = 0
    @Volatile var lastFailureReason: String? = null
    @Volatile var lastFailureAt: Long = 0
    @Volatile var lastSuccessAt: Long = 0
    /** 最近一次被用到的时间，用于过期剪枝。 */
    @Volatile var lastSeen: Long = 0
    /** 半开探测的在途窗口（**不落盘**：进程重启后「有没有一个探测正在飞」毫无意义）。 */
    @Volatile var probeUntil: Long = 0

    fun toJson(): JSONObject = JSONObject()
        .put("t", total).put("s", success)
        .put("cf", consecutiveFailures)
        .put("cd", cooldownUntil).put("dis", disabledUntil).put("dr", disableRound)
        .put("lc", lastFailureCode).put("lr", lastFailureReason ?: "")
        .put("lf", lastFailureAt).put("ls", lastSuccessAt)
        .put("seen", lastSeen)

    companion object {
        fun fromJson(o: JSONObject): KeyRec = KeyRec().apply {
            total = o.optLong("t"); success = o.optLong("s")
            consecutiveFailures = o.optInt("cf")
            cooldownUntil = o.optLong("cd"); disabledUntil = o.optLong("dis"); disableRound = o.optInt("dr")
            lastFailureCode = o.optInt("lc")
            lastFailureReason = o.optString("lr").takeIf { it.isNotBlank() }
            lastFailureAt = o.optLong("lf"); lastSuccessAt = o.optLong("ls")
            lastSeen = o.optLong("seen")
        }
    }
}
