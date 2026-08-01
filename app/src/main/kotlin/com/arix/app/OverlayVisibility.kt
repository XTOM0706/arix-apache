package com.arix.app

import kotlinx.coroutines.delay

/**
 * 自有悬浮窗协调器：ui_control 读屏（dump）或注入手势（tap/swipe/long_press/click_text/set_text）前，
 * 把 Arix 自己的悬浮窗临时「让位」，完事再恢复。
 *
 * 两个场景、两条原因：
 *  · dump：可聚焦的唤醒浮层会成为无障碍的 activeWindow，而 `svc.dump()` 读的正是 rootInActiveWindow =
 *    读到我们自己。让它失焦（[WakeOverlayHost.pushPassive]）即可把 activeWindow 交还给背后真正的 App。
 *  · 手势（注入触摸）：屏幕上只要悬着我们这个「别家应用」的窗口，目标 App 就可能因触摸遮挡策略
 *    （filterTouchesWhenObscured / Android 12+ 触摸遮挡）把注入的手势**静默丢弃**。让唤醒浮层失焦让位、
 *    把 OCR/TTS 钮物理摘窗，注入点上方就没有我们的窗口了。动作高亮层由调用方 [UiActionOverlay.detachForGesture]
 *    另行摘掉（它托着正在跑的会话协程），这里只治**唤醒浮层**这个独立窗口没让位的老问题。
 *
 * 唤醒浮层用「失焦」而非摘窗，是因为它托着正在跑这轮 ui_control 的协程（rememberCoroutineScope），
 * 摘窗会连它一起销毁、直接把正在执行的 ui_control 取消掉。OCR 钮、TTS 钮、动作高亮层都是不可聚焦的独立
 * 窗口，本就不进 rootInActiveWindow；仍一并物理摘掉（照 [UiActionOverlay] 的 removeViewImmediate 做法，
 * 不 GONE/alpha），既防御 dump 实现将来改用 getWindows，也免得小钮正好压在手势注入点上把事件遮掉。
 *
 * 时序：让位/摘窗都是异步 post 到主线程、且窗口/无障碍状态更新有延迟，注入前必须等一小会儿让它生效——
 * dump 要等无障碍把 activeWindow 真的切过去（[DUMP_SETTLE_MS]）；手势只需等唤醒浮层失焦生效、不必等无障碍
 * 窗口那份慢刷新，且手势对延迟敏感，故用更短的 [GESTURE_SETTLE_MS]。无论 block 成功/失败/被取消都恢复
 * （finally），绝不把用户正在看的浮层永久摘掉。
 */
object OverlayVisibility {

    /** dump 让位后等无障碍把 activeWindow 切到背后 App 的余量（ms）。真机需验：太短会抓到自己，太长拖慢每次 dump。 */
    private const val DUMP_SETTLE_MS = 80L

    /**
     * 手势注入前等唤醒浮层失焦（FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCHABLE 生效）的余量（ms）。
     * 比 dump 短：只需等本窗口失焦、不必等无障碍那份 activeWindow 慢刷新；手势对延迟敏感，能短则短。
     * 但也不能为 0：pushPassive 是异步 post 到主线程，得给一帧让 updateViewLayout 落地。真机需验最优值。
     */
    private const val GESTURE_SETTLE_MS = 40L

    /** dump 期间藏起自有浮层，抓完恢复。block 里跑真正的 `svc.dump()`。 */
    suspend fun <T> hideOwnOverlaysForDump(block: suspend () -> T): T =
        hideOwnOverlays(DUMP_SETTLE_MS, includeActionOverlay = true, block)

    /**
     * 手势注入期间让自有浮层不遮挡，注入完恢复。block 里跑「取 label + 注入手势 + 上报」——
     * 取 label（labelAt / dumpNodes / focusedInputBounds）也放进来，是因为它们读 rootInActiveWindow，
     * 让位后才不会读到唤醒浮层自己。动作高亮层已由调用方 [UiActionOverlay.detachForGesture] 摘掉，这里不再碰。
     */
    suspend fun <T> hideOwnOverlaysForGesture(block: suspend () -> T): T =
        hideOwnOverlays(GESTURE_SETTLE_MS, includeActionOverlay = false, block)

    private suspend fun <T> hideOwnOverlays(settleMs: Long, includeActionOverlay: Boolean, block: suspend () -> T): T {
        hideAll(includeActionOverlay)
        try {
            delay(settleMs)          // 挂起点：STOP 时会从这里抛 Cancellation，交给下面 finally 恢复浮层
            return block()
        } finally {
            restoreAll()
        }
    }

    private fun hideAll(includeActionOverlay: Boolean) {
        runCatching { WakeOverlayHost.pushPassive() }            // 可聚焦→会污染 dump/遮住手势：失焦让位（不摘窗，它托着正在跑的协程）
        runCatching { FloatingScreenOcrButton.detachForDump() }  // 不可聚焦，物理摘窗（既防 dump 抓到、又免遮住手势注入点）
        runCatching { FloatingTtsPlayer.detachForDump() }        // 同上，且不打断朗读
        // 动作高亮层：dump 路径在此幂等 hide；手势路径已由调用方 detachForGesture 先摘掉，故按需（避免重复）。
        if (includeActionOverlay) runCatching { UiActionOverlay.hide() }
    }

    private fun restoreAll() {
        runCatching { WakeOverlayHost.popPassive() }
        runCatching { FloatingScreenOcrButton.reattachAfterDump() }
        runCatching { FloatingTtsPlayer.reattachAfterDump() }
        // 动作高亮层不在此强拉回：会话若仍在，UiActionOverlay 的 resumeSessionIndicator 会自然把光晕挂回。
        // 注：hideOwnOverlays 的 delay 是挂起点，STOP 时 CancellationException 会经 try/finally 自然重抛
        // （finally 只做恢复、不吞异常），符合项目「先重抛 Cancellation 再 catch(Exception)」的铁律。
    }
}
