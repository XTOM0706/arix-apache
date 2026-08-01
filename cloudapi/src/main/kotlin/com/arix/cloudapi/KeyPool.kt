package com.arix.cloudapi

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.ConcurrentHashMap

// ============================================================
// 密钥池 —— apiKey 字段支持多行/逗号分隔多个 key，请求时按健康度轮换。
// 分散单 key 的配额/限流；无需改 DB（复用 apiKey 字段）。
//
// 三层容错（从轻到重）：
//  · 短冷却    429 限流 → 冷却 60 秒，到期自动回轮转；
//  · 自动禁用  连续失败到阈值、或 401/403（认证错误是确定性的，再撞也是 401）→ 整枚停用一段时间；
//  · 半开探测  禁用到期后**只放一枚探测请求**过去，成功就完全恢复，失败就按指数退避继续禁用。
// 一整圈都不可用时退回「最快恢复的那枚」（least-bad）——绝不返回空，用户永远发得出去。
//
// 健康档案（冷却到期、成败统计、最近失败原因）存在 [KeyHealthStore] 里并**跨进程存活**：
// 以前这些全在裸 ConcurrentHashMap 里，进程一死就没，重启后又拿死 key 撞一次。
// ⚠ 统计里只有指纹没有明文，见 KeyHealthStore 顶部的三条硬约束。
// ============================================================
object KeyPool {

    // ---------------- 阈值（改之前先看理由） ----------------

    /** 连续失败到几次就自动禁用这枚。定 3：1 次可能是网络抖动、2 次可能还是同一波瞬时故障，
     *  3 次连续失败基本可以判定是这枚 key 自己的问题（Kelivo 的 maxFailuresBeforeDisable 同量级）。
     *  再往上调会让用户多等好几个首字延迟才把坏 key 摘掉。 */
    private const val MAX_CONSECUTIVE_FAILURES = 3

    /** 429 限流的短冷却：60 秒。限流窗口通常就是分钟级，冷却太久等于白白闲置一枚好 key。 */
    private const val COOLDOWN_RATELIMIT_MS = 60_000L

    /** 401/403 的首轮禁用时长：30 分钟。认证错误多半是 key 被删/欠费/权限没开，
     *  这些都要用户去后台操作才可能变好；30 分钟既不至于把「刚充完值」的 key 关太久，
     *  也不会让每次发送都拿它撞一遍。 */
    private const val DISABLE_BASE_AUTH_MS = 30 * 60_000L

    /** 连续失败（429 打满等）触发的首轮禁用时长：15 分钟。比认证错误短——
     *  限流类问题是会自己好的，恢复得快一点更划算。 */
    private const val DISABLE_BASE_FAIL_MS = 15 * 60_000L

    /** 禁用时长的指数退避上限：6 小时。真死的 key 不该每 15 分钟浪费用户一次首字延迟；
     *  封顶 6 小时又能保证「用户白天充了值」当天一定还会被自动试回来。 */
    private const val DISABLE_MAX_MS = 6 * 3600_000L

    /** 退避轮数上限（只为防 shl 溢出，实际时长由 DISABLE_MAX_MS 封顶）。 */
    private const val MAX_DISABLE_ROUND = 8

    /** 半开探测的在途窗口：60 秒内不把这枚再发给别人。
     *  没有这个窗口，一枚刚过恢复期的死 key 会被并发请求同时抢走一大把，等于对着 401 连打。
     *  60 秒足够覆盖一次首字延迟 + 上层重试。 */
    private const val PROBE_WINDOW_MS = 60_000L

    // ---------------- 内存索引 ----------------

    /** 池的解析缓存：原始 apiKey 字段 -> (key 列表, 指纹列表, 池指纹)。
     *  纯内存、不落盘（含明文）。缓存它是为了别在每次发送前重复做 split + SHA-256。 */
    private class PoolMeta(val keys: List<String>, val fps: List<String>, val poolFp: String)
    private val pools = ConcurrentHashMap<String, PoolMeta>()

    /** 健康状态版本号：任何一枚 key 的健康发生变化就 +1。
     *  配置页 collectAsState 后重新调 [snapshot] 即可实时刷新（不用轮询）。 */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    // ---------------- 生命周期 ----------------

    /** app 侧调用一次（Application.onCreate 的后台线程即可），装载磁盘上的健康档案。
     *  不调也能跑，只是退化成「进程内有效」的老行为。 */
    fun init(context: Context) = KeyHealthStore.init(context)

    fun keysOf(raw: String): List<String> =
        raw.split('\n', ',').map { it.trim() }.filter { it.isNotBlank() }

    private fun metaOf(raw: String): PoolMeta = pools.getOrPut(raw) {
        // 用户在配置页边改边试时，每个中间态字符串都会留一条缓存；给个上限，超了整张丢掉重建
        // （缓存里是明文，不能无限长在内存里）。
        if (pools.size > 64) pools.clear()
        val keys = keysOf(raw)
        val fps = keys.map { KeyHealthStore.fingerprint(it) }
        // 池指纹 = 各 key 指纹的再哈希：同样不含明文，用来给轮转游标归档
        PoolMeta(keys, fps, KeyHealthStore.fingerprint(fps.joinToString("|")))
    }

    /** 这枚 key 最早什么时候能重新参与轮转（<= now 表示现在就能用）。 */
    private fun availableAt(r: KeyRec?): Long {
        if (r == null) return 0L                       // 没档案 = 全新 key，当健康的用
        return maxOf(r.cooldownUntil, r.disabledUntil, r.probeUntil)
    }

    // ---------------- 选 key ----------------

    /**
     * 从多 key 里按健康度轮换取下一枚；单 key 或空则原样返回。
     * 一整圈都不可用时退回最快恢复的那枚——**永远不会返回空**。
     */
    fun next(raw: String): String {
        val meta = metaOf(raw)
        val keys = meta.keys
        if (keys.isEmpty()) return raw.trim()
        val now = System.currentTimeMillis()
        if (keys.size == 1) {
            // 单 key 没得选：禁用它只会让用户彻底发不出去，所以只记档不停用
            KeyHealthStore.rec(meta.fps[0]).lastSeen = now
            return keys[0]
        }
        // 轮询一圈找可用 key（含刚过恢复期、等待半开探测的那种）
        for (n in keys.indices) {
            val idx = KeyHealthStore.nextCursor(meta.poolFp).mod(keys.size)
            val fp = meta.fps[idx]
            val r = KeyHealthStore.peek(fp)
            if (availableAt(r) <= now) {
                val rec = r ?: KeyHealthStore.rec(fp)
                synchronized(rec) {
                    rec.lastSeen = now
                    // 它是「被禁用过、刚熬到恢复时间」的那种 → 这次就是半开探测，
                    // 开一个在途窗口把它挡住，别让并发请求一起涌向一枚还没验明的 key。
                    if (rec.disabledUntil > 0L) rec.probeUntil = now + PROBE_WINDOW_MS
                }
                return keys[idx]
            }
        }
        // 全都在冷却/禁用中：选最快恢复的那枚（least-bad）。CloudApiClient 的 429 重试段依赖这个
        // 行为——宁可拿一枚还在冷却的去撞（并按 Retry-After 等一下），也不能让用户发不出去。
        val best = keys.indices.minByOrNull { availableAt(KeyHealthStore.peek(meta.fps[it])) } ?: 0
        return keys[best]
    }

    // ---------------- 上报结果 ----------------

    /**
     * 汇报某次调用结果。
     *  · 2xx        → 清冷却/解禁用/连续失败清零
     *  · 401/403    → 立刻禁用（认证错误是确定性的，重试永远不会变好），按轮次指数退避
     *  · 429        → 短冷却 60s，并计入连续失败；连打 [MAX_CONSECUTIVE_FAILURES] 次则升级为禁用
     *  · 其它(5xx…) → **只记原因不罚 key**：服务端故障/链路抖动跟这枚 key 无关，罚它是误伤
     *                 （原实现「其它不动」就是这个判断，保留）。
     * [errorBody] 只用来存最近一次失败原因，落盘前会脱敏（有的供应商把 key 回显在报错里）。
     */
    fun reportResult(key: String, httpCode: Int, errorBody: String? = null) {
        if (key.isBlank()) return
        val fp = KeyHealthStore.fingerprint(key)
        val r = KeyHealthStore.rec(fp)
        val now = System.currentTimeMillis()
        synchronized(r) {
            r.lastSeen = now
            r.total++
            r.probeUntil = 0L                       // 探测有结果了，在途窗口关掉
            when {
                httpCode in 200..299 -> {
                    r.success++
                    r.lastSuccessAt = now
                    r.consecutiveFailures = 0
                    r.cooldownUntil = 0L
                    r.disabledUntil = 0L            // 半开探测成功 = 完全恢复
                    r.disableRound = 0
                }
                httpCode == 401 || httpCode == 403 -> {
                    markFailure(r, now, httpCode, KeyHealthStore.redact(errorBody, key))
                    disable(r, now, DISABLE_BASE_AUTH_MS)
                }
                httpCode == 429 -> {
                    markFailure(r, now, httpCode, KeyHealthStore.redact(errorBody, key))
                    r.cooldownUntil = now + COOLDOWN_RATELIMIT_MS
                    if (r.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) disable(r, now, DISABLE_BASE_FAIL_MS)
                }
                else -> {
                    // 软失败：计入总数（成功率如实反映），但不动连续失败计数、不冷却
                    r.lastFailureCode = httpCode
                    r.lastFailureReason = KeyHealthStore.redact(errorBody, key)
                    r.lastFailureAt = now
                }
            }
        }
        touched()
    }

    /**
     * 汇报一次网络层失败（连不上/超时/SSL…）。
     * ⚠ 协程取消**必须原样重抛**且绝不能算到 key 头上：用户按 STOP 会以 IOException("Canceled")
     * 的形态冒出来，把它算成 key 失败等于「用户越常按停止，好 key 越容易被禁」。
     */
    fun reportNetworkFailure(key: String, t: Throwable) {
        if (t is kotlinx.coroutines.CancellationException) throw t
        if (key.isBlank()) return
        val msg = t.message.orEmpty()
        if (msg.contains("canceled", true) || msg.contains("socket closed", true)) return  // STOP 关 socket，不是 key 的错
        val fp = KeyHealthStore.fingerprint(key)
        val r = KeyHealthStore.rec(fp)
        val now = System.currentTimeMillis()
        synchronized(r) {
            r.lastSeen = now
            r.total++
            r.probeUntil = 0L
            r.lastFailureCode = -1
            r.lastFailureReason = KeyHealthStore.redact(msg.ifBlank { t.javaClass.simpleName }, key)
            r.lastFailureAt = now
            // 同软失败：网络问题是链路的，不罚 key
        }
        touched()
    }

    /**
     * 只补记「最近一次失败原因」，**不动任何计数**。
     * 给这么个函数是因为 OkHttp 的 body 只能读一次：[reportResult] 在拿到 response 的那一刻就得调
     * （要立刻决定换不换 key），而服务器原话要等 `response.body.string()` 之后才有。
     * 谁先谁后都不该影响统计，所以把「记原因」单独拆出来。
     */
    fun noteFailureReason(key: String, reason: String?) {
        if (key.isBlank() || reason.isNullOrBlank()) return
        val r = KeyHealthStore.peek(KeyHealthStore.fingerprint(key)) ?: return
        synchronized(r) { r.lastFailureReason = KeyHealthStore.redact(reason, key) }
        KeyHealthStore.markDirty()
    }

    private fun markFailure(r: KeyRec, now: Long, code: Int, reason: String?) {
        r.consecutiveFailures++
        r.lastFailureCode = code
        r.lastFailureReason = reason
        r.lastFailureAt = now
    }

    /** 禁用这枚 key：时长按已禁用轮数指数退避，封顶 [DISABLE_MAX_MS]。 */
    private fun disable(r: KeyRec, now: Long, baseMs: Long) {
        r.disableRound = (r.disableRound + 1).coerceAtMost(MAX_DISABLE_ROUND)
        val span = (baseMs shl (r.disableRound - 1)).coerceAtMost(DISABLE_MAX_MS)
        r.disabledUntil = now + span
        r.cooldownUntil = 0L                        // 禁用比冷却重，冷却字段让位，状态判定不用二选一
    }

    /** 健康有变：版本号 +1 让 UI 刷新，并置脏交给 [KeyHealthStore] 异步合批落盘。
     *  ⚠ 这里**不做任何写盘**——它跑在发送链路上。 */
    private fun touched() {
        _revision.value = _revision.value + 1
        KeyHealthStore.markDirty()
    }

    // ---------------- 只读快照（供配置页显示） ----------------

    /**
     * 该配置的每枚 key 的健康快照，顺序与 apiKey 字段里的书写顺序一致。
     * 纯读，不改任何状态；masked 是运行时算的，磁盘上没有明文。
     */
    fun snapshot(raw: String): List<KeyStatus> {
        val meta = metaOf(raw)
        val now = System.currentTimeMillis()
        return meta.keys.mapIndexed { i, k ->
            val fp = meta.fps[i]
            val r = KeyHealthStore.peek(fp)
            val avail = availableAt(r)
            val state = when {
                r == null -> KeyState.ACTIVE
                r.disabledUntil > now -> KeyState.DISABLED
                r.disabledUntil > 0L -> KeyState.PROBING       // 熬过恢复期、等一次探测验明正身
                r.cooldownUntil > now -> KeyState.COOLING
                else -> KeyState.ACTIVE
            }
            KeyStatus(
                index = i,
                masked = KeyHealthStore.mask(k),
                fingerprint = fp,
                state = state,
                totalRequests = r?.total ?: 0L,
                successfulRequests = r?.success ?: 0L,
                consecutiveFailures = r?.consecutiveFailures ?: 0,
                successRate = if ((r?.total ?: 0L) > 0L) (r!!.success.toFloat() / r.total.toFloat()) else -1f,
                availableInMs = (avail - now).coerceAtLeast(0L),
                lastFailureCode = r?.lastFailureCode ?: 0,
                lastFailureReason = r?.lastFailureReason,
                lastFailureAtMs = r?.lastFailureAt ?: 0L,
                lastSuccessAtMs = r?.lastSuccessAt ?: 0L,
            )
        }
    }

    /** 用户手动把这个配置里的所有 key 放回轮转（清冷却/解禁用/连续失败清零，累计统计保留）。 */
    fun reenableAll(raw: String) {
        val meta = metaOf(raw)
        meta.fps.forEach { fp ->
            val r = KeyHealthStore.peek(fp) ?: return@forEach
            synchronized(r) { r.cooldownUntil = 0L; r.disabledUntil = 0L; r.disableRound = 0; r.consecutiveFailures = 0; r.probeUntil = 0L }
        }
        touched()
        KeyHealthStore.flush()
    }

    /** 用户手动放回单枚 key（配置页点某一行的「重新启用」）。 */
    fun reenable(key: String) {
        if (key.isBlank()) return
        val r = KeyHealthStore.peek(KeyHealthStore.fingerprint(key)) ?: return
        synchronized(r) { r.cooldownUntil = 0L; r.disabledUntil = 0L; r.disableRound = 0; r.consecutiveFailures = 0; r.probeUntil = 0L }
        touched()
        KeyHealthStore.flush()
    }

    /** 清空全部 key 的健康统计（含累计次数）。 */
    fun resetStats() {
        KeyHealthStore.clearAll()
        touched()
    }

    /** app 侧想在退到后台时保一手可调；平时不用——正常路径靠 [KeyHealthStore] 的合批落盘。 */
    fun flush() = KeyHealthStore.flush()
}

/** key 的可用状态。 */
enum class KeyState {
    /** 正常参与轮转。 */
    ACTIVE,
    /** 短期冷却中（429 限流），到点自动回来。 */
    COOLING,
    /** 已被自动禁用，等恢复时间。 */
    DISABLED,
    /** 恢复期已过，正等一次半开探测来验明正身。 */
    PROBING,
}

/**
 * 一枚 key 的只读健康快照（配置页用）。
 * ⚠ 里面**没有 key 明文**：[masked] 是运行时从内存里的明文算出来的展示串，[fingerprint] 是 SHA-256 前缀。
 */
data class KeyStatus(
    /** 在 apiKey 字段里的序号，从 0 起。 */
    val index: Int,
    /** 展示用掩码，形如 sk-abc…wxyz。 */
    val masked: String,
    val fingerprint: String,
    val state: KeyState,
    /** 有结果回报的调用次数（被 STOP 取消的不计）。 */
    val totalRequests: Long,
    val successfulRequests: Long,
    val consecutiveFailures: Int,
    /** 0f..1f；还没有任何记录时为 -1f（UI 显示「暂无数据」而不是 0%）。 */
    val successRate: Float,
    /** 还要多久才能重新参与轮转，0 表示现在可用。 */
    val availableInMs: Long,
    /** 最近一次失败的 HTTP 码；-1 表示网络层失败，0 表示没失败过。 */
    val lastFailureCode: Int,
    /** 最近一次失败原因（已脱敏、已截断）。 */
    val lastFailureReason: String?,
    val lastFailureAtMs: Long,
    val lastSuccessAtMs: Long,
)
