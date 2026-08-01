package com.arix.tool

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * AI 行为流总线 —— 「用户能看见 AI 到底干了什么」的唯一数据源。
 *
 * 为什么要有它：此前工具的可见性是**碎的**——[ToolStreamBus] 只有一条「当前工具的实时输出」、
 * XSearchProgressBus 只管深度搜索、聊天里的工具卡只活在那条消息里。用户想回答
 * 「刚才这半分钟 AI 到底调了什么、成没成、谁在调」时，没有任何一个地方能一次说清。
 * 这里把每次工具调用记成一条可回看的时间轴。
 *
 * 只在内存里、不落盘：一次会话内的「刚才发生了什么」是这东西的全部用途，落盘要在
 * 工具执行的热路径上写 IO，手表上是纯负担；进程重启后这份记录没有价值（真正要留档的是
 * 对话本身，那早就落盘了）。上限 [CAP] 条，环形丢最老的。
 *
 * 埋点只有一处：[ToolManager.execute]（全项目唯一的工具执行闸），所以覆盖 AI / 插件 /
 * 用户脚本 / 外部 MCP 全部调用方，不需要每个工具自己上报。
 */
object ToolActivityBus {

    /** 一次调用的结局。RUNNING 是进行中；DENIED = 被权限策略/功能包开关挡下（没真跑）。 */
    enum class Status { RUNNING, OK, ERROR, DENIED, CANCELLED }

    /**
     * 一条行为记录。**不可变**：Compose 靠对象相等判断重组，可变字段 + 原地改会让面板不刷新。
     * 结束时用 [finish] 造一条新的替换掉环里那条。
     */
    @androidx.compose.runtime.Immutable   // 只读快照(replace-not-mutate)，含 JSONObject? 会被默认推为不稳定；
                                          // 标稳后行为流列表行能按结构相等跳过重组（同聊天页给 ChatBubble 标 @Immutable）
    data class Entry(
        val seq: Long,                  // 自增序号，也是替换/查找的 key
        val callId: String,             // 模型给的 tool_call id，聊天里的工具结果卡按它回指查耗时
        val toolName: String,
        val callerLabel: String,        // 「AI」「插件「xxx」」——直接给用户看的一句话
        val callerKind: CallerKind,     // 供面板按请求方筛选
        val conversationId: Long?,      // 所属对话（后台任务/唤醒助手调的工具没有，为 null）
        val startedAt: Long,
        val argsPreview: String,        // 一行短摘要，实时提示条用（廉价，不做完整序列化）
        val status: Status = Status.RUNNING,
        val durationMs: Long = 0L,
        val result: String = "",        // 已截断的返回文本
        val resultTruncated: Boolean = false,
        /**
         * 参数原始对象。**只在 RUNNING 期间持有**——结束时立刻序列化成 [argsJson] 并丢掉引用，
         * 免得 200 条记录攥着 file_write 那种可能上 MB 的参数不放。
         */
        val argsRef: JSONObject? = null,
        val argsJson: String = "",      // 美化后的完整参数（已截断）
    ) {
        val isRunning: Boolean get() = status == Status.RUNNING
    }

    enum class CallerKind { AI, PLUGIN, USER_SCRIPT, MCP }

    private const val CAP = 200                 // 环形上限；再多用户也不会往回翻
    private const val ARGS_LIMIT = 8_000        // 近期条的参数/返回留存上限（字符）
    private const val RESULT_LIMIT = 8_000
    /**
     * 分层保留：只有最近 [DETAIL_KEEP] 条保留完整参数/返回（供卡片展开细看），更老的压成
     * [OLD_LIMIT] 字摘要。理由：用户展开的永远是"刚看到 AI 干的那件事"，几乎不会往回翻到第 180 条；
     * 而"每条都撑满 8000×2"是常驻内存的上界。分层后最坏上界从 ~6.4MB 降到 ~0.77MB，
     * 近期卡片的全量细节不受影响。压缩是 O(1)：每条只在"跌出近期窗口"的那一刻压一次。
     */
    private const val DETAIL_KEEP = 40
    private const val OLD_LIMIT = 400

    private val seqGen = java.util.concurrent.atomic.AtomicLong(0)
    private val ring = ArrayDeque<Entry>()      // 只在 lock 内碰
    private val lock = Any()

    /**
     * 变更计数。面板订阅它、再去 [snapshot] 取列表。
     *
     * 为什么不直接把 List 放进 StateFlow：那样每来一条事件都要拷一份 200 元素的列表，
     * 哪怕面板根本没打开。计数器自增是几个纳秒，列表只在真有人看的时候才生成。
     */
    private val _version = MutableStateFlow(0L)
    val version: StateFlow<Long> = _version

    /**
     * 当前正在跑的那一条（没有则 null），给聊天页的「实时动作条」用。
     *
     * 单独开一条流而不是让它去 snapshot 里筛：实时提示条是**常驻订阅**的，不能让它每次
     * 事件都去扫整个环。并发工具（子agent 后台跑）时以最新开始的那条为准，与 [ToolStreamBus]
     * 的「最新 begin 者拿走通道」是同一套取舍。
     */
    private val _running = MutableStateFlow<Entry?>(null)
    val running: StateFlow<Entry?> = _running

    /** 面板打开时才调：取一份倒序（最新在前）快照。 */
    fun snapshot(): List<Entry> = synchronized(lock) { ring.toList() }.asReversed()

    /** 按 tool_call id 找记录——聊天里的工具结果卡靠它显示耗时。找不到（如重启后）返回 null。 */
    fun byCallId(callId: String): Entry? =
        synchronized(lock) { ring.lastOrNull { it.callId == callId } }

    fun clear() {
        synchronized(lock) { ring.clear() }
        _running.value = null
        _version.value = _version.value + 1
    }

    // ---- 埋点 API（只给 ToolManager.execute 用）----

    /** 工具开始执行。返回的 seq 要交给 [finish]。 */
    fun begin(call: ToolCall): Long {
        val seq = seqGen.incrementAndGet()
        val e = Entry(
            seq = seq,
            callId = call.id,
            toolName = call.name,
            callerLabel = call.caller.label,
            callerKind = kindOf(call.caller),
            conversationId = ActiveChatContext.conversationId,
            startedAt = System.currentTimeMillis(),
            argsPreview = cheapPreview(call.arguments),
            argsRef = call.arguments,
        )
        synchronized(lock) {
            ring.addLast(e)
            while (ring.size > CAP) ring.removeFirst()
            // 刚跌出"近期完整保留"窗口的那条：把它的大字段压成摘要，释放常驻内存。
            // 边界每次 begin 前移一格，所以每条恰好在跨出窗口时被压一次（O(1)）。
            val idx = ring.size - 1 - DETAIL_KEEP
            if (idx >= 0) ring[idx].let { old -> old.compacted()?.let { ring[idx] = it } }
        }
        _running.value = e
        _version.value = _version.value + 1
        return seq
    }

    /**
     * 工具结束。这里才做完整的参数序列化——热路径上（begin 时）每次都 toString 一遍参数，
     * 遇到大 content 就是白烧一次字符串拷贝；结束时做一次、存下来、把引用丢掉。
     */
    fun finish(seq: Long, status: Status, result: String) {
        var done: Entry? = null
        synchronized(lock) {
            val i = ring.indexOfLast { it.seq == seq }
            if (i < 0) return                       // 已被环挤掉：无所谓，本来就是最老的记录
            val old = ring[i]
            val pretty = runCatching { old.argsRef?.toString(2) ?: "" }.getOrElse { old.argsRef?.toString() ?: "" }
            // 若这条已跌出近期窗口才结束（超长任务），直接按老条上限存，别把一条老记录重新撑到 8000
            val recent = (ring.size - 1 - i) < DETAIL_KEEP
            val argsCap = if (recent) ARGS_LIMIT else OLD_LIMIT
            val resCap = if (recent) RESULT_LIMIT else OLD_LIMIT
            val e = old.copy(
                status = status,
                durationMs = System.currentTimeMillis() - old.startedAt,
                result = result.take(resCap),
                resultTruncated = result.length > resCap,
                argsRef = null,                     // 丢引用，别攥着大参数
                argsJson = pretty.take(argsCap),
            )
            ring[i] = e
            done = e
        }
        // 只有「结束的正是当前显示的那条」才清实时条，否则会把后开始的那条的提示清掉（同 ToolStreamBus 的令牌思路）
        if (_running.value?.seq == seq) _running.value = null
        if (done != null) _version.value = _version.value + 1
    }

    /**
     * 把一条已跌出近期窗口的记录压成摘要：大字段截到 [OLD_LIMIT]。
     * 已经够小就返回 null（不必替换环里那条，省一次对象分配）。
     * 只压已结束的完整字段；RUNNING 条 argsJson/result 本就为空，天然 no-op（argsRef 待 finish 再丢）。
     */
    private fun Entry.compacted(): Entry? {
        if (argsJson.length <= OLD_LIMIT && result.length <= OLD_LIMIT) return null
        return copy(
            result = result.take(OLD_LIMIT),
            resultTruncated = resultTruncated || result.length > OLD_LIMIT,
            argsJson = argsJson.take(OLD_LIMIT),
        )
    }

    private fun kindOf(c: ToolCaller): CallerKind = when (c) {
        is ToolCaller.Ai -> CallerKind.AI
        is ToolCaller.Plugin -> CallerKind.PLUGIN
        is ToolCaller.UserScript -> CallerKind.USER_SCRIPT
        is ToolCaller.Mcp -> CallerKind.MCP
    }

    /**
     * 一行参数摘要，**不做完整序列化**：只扫最多前 4 个键、每个值截 40 字。
     * 实时提示条要的是「正在执行 shell: npm install…」这种一眼可读的东西，
     * 为它把整个参数 JSON 拼出来（可能含整份文件内容）纯属浪费。
     */
    private fun cheapPreview(args: JSONObject): String = runCatching {
        val sb = StringBuilder()
        var n = 0
        for (k in args.keys()) {
            if (n >= 4) { sb.append("…"); break }
            val v = args.opt(k)
            // 嵌套对象/数组不展开——展开就等于序列化，正是这里要躲的
            val s = when (v) {
                null, JSONObject.NULL -> continue
                is JSONObject -> "{…}"
                is org.json.JSONArray -> "[${v.length()}]"
                else -> v.toString()
            }
            if (n > 0) sb.append(", ")
            sb.append(k).append('=').append(s.replace('\n', ' ').take(40))
            n++
        }
        sb.toString()
    }.getOrDefault("")
}
