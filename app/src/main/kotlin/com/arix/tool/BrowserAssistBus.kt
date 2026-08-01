package com.arix.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 浏览器「请人帮忙」总线：AI 的隐身浏览器碰到需要**登录**或**人机验证**（隐身也过不了的）时，
 * 通过它请求前台弹一个可交互小窗，让真人来登录/过验证；真人点「完成」后 AI 继续。
 *
 * cookie 是全局共享的（CookieManager 单例），且弹窗 WebView 与 AI 浏览器用同一个 UA，
 * 所以真人在小窗里拿到的登录态 / cf_clearance 之类，AI 浏览器重新导航时就带上了。
 */
object BrowserAssistBus {
    data class Req(val url: String, val reason: String, val done: CompletableDeferred<Boolean>)

    private val _req = MutableStateFlow<Req?>(null)
    val req: StateFlow<Req?> = _req

    /**
     * 等真人来处理的上限。
     *
     * 原来是**无限等**（裸 `d.await()`）。可这个弹窗要能被人看见，得满足一串条件：App 在前台、
     * 聊天页在组合里、屏幕亮着。手表上这些随时会不成立——用户把手放下、屏幕灭了，请求就永远挂在这，
     * 整轮对话不报错、不结束、不动弹，正是「没报错，直接停住不动」的一种。
     * 5 分钟是给真人留的余量（登录/过验证码确实要点几下），到点就当他没在，把控制权交回给模型。
     */
    private const val WAIT_MS = 5 * 60 * 1000L

    /** 请求人工协助，挂起直到真人点完成(true)/取消(false)；[WAIT_MS] 内没人应答则放弃(false)。同一时刻只处理一个。 */
    suspend fun request(url: String, reason: String): Boolean {
        // 已有在处理的，先拒了旧的（极少并发）
        _req.value?.done?.complete(false)
        val d = CompletableDeferred<Boolean>()
        _req.value = Req(url, reason, d)
        // withTimeoutOrNull 而不是 withTimeout：超时是"没人在场"这个**正常结局**，
        // 不该抛 TimeoutCancellationException——那是 CancellationException，会一路上窜把整轮掐了。
        return try { withTimeoutOrNull(WAIT_MS) { d.await() } ?: false }
        finally { if (_req.value?.done === d) _req.value = null }
    }

    /** UI 侧：真人点完成/取消。 */
    fun finish(ok: Boolean) { _req.value?.done?.complete(ok) }
}
