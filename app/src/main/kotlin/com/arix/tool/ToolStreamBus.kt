package com.arix.tool

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 工具执行的**实时输出**总线。长跑工具（尤其终端 linux_exec / shell）在跑的过程中，把已产生的
 * stdout/stderr 边跑边推到这里；聊天页订阅它，在流式气泡里实时显示，别让用户对着「思考中」干等。
 *
 * 单条通道即可：主聊天的工具循环是**串行**执行工具的（一次一个），所以不用按 call id 分流。
 * 非聊天场景（主动/日记 worker）调工具也会推，但没人订阅=无害（StateFlow 只是存着最新值）。
 */
object ToolStreamBus {
    data class Run(val name: String, val output: String)

    private val _state = MutableStateFlow(Run("", ""))
    val state: StateFlow<Run> = _state
    // 拥有权令牌：并发工具(子agent 后台跑 linux_exec)共享这一条通道时，最新 begin 者拿走通道；
    // 旧 run 的 update/end 令牌不匹配即忽略，不会把当前 run 的显示清掉/串台（治并发相互污染）。
    private val seq = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile private var owner = 0L

    /** 开始跑某工具，返回本次令牌（update/end 要带上）。 */
    fun begin(name: String): Long { val t = seq.incrementAndGet(); owner = t; _state.value = Run(name, ""); return t }

    /** 推送当前累计输出（整段快照）。只有当前拥有者的 token 才生效。 */
    fun update(token: Long, output: String) { if (token == owner) _state.value = _state.value.copy(output = output) }

    /** 工具跑完/中断，收起实时输出。只有当前拥有者才收，别让先结束的把后来者的显示清掉。 */
    fun end(token: Long) { if (token == owner) { owner = 0L; _state.value = Run("", "") } }
}
