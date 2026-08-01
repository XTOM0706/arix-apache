package com.arix.app

import android.content.Context
import android.graphics.Rect
import kotlinx.coroutines.flow.MutableStateFlow

// ============================================================
// UiActionBus —— AI 屏幕操作事件总线（「别黑箱化」的数据面）。
// ui_control 每做完一个动作就往这里推一条：做了什么、点在哪、成没成。
// 谁来画是另一回事（UiActionOverlay 订阅它画高亮框），总线本身不认识 UI，
// 这样以后想在聊天页/日志里复用同一份事件，不用再改工具埋点。
// 一次只有一个动作在执行，够用一个全局 StateFlow。
// ============================================================

/**
 * 一次界面操作的语义快照。
 *
 * [rect] 是目标元素在**屏幕绝对坐标**下的矩形（来自 getBoundsInScreen）；
 * 只给了坐标没给元素时（tap/long_press/swipe），由埋点侧围绕坐标造一个小方框，
 * 这样画的人永远只需要处理「矩形」一种东西。全局动作（back/home…）没有位置，rect 为 null。
 */
data class UiActionEvent(
    val action: String,                 // tap / long_press / swipe / click_text / set_text / back / home / recents / notifications
    val rect: Rect?,                    // 目标区域；全局动作为 null
    val endX: Int? = null,              // swipe 终点（画箭头用）
    val endY: Int? = null,
    val targetLabel: String = "",       // 「发送」这类目标文字，没有就空
    val success: Boolean = true,
    val viaShizuku: Boolean = false,    // 走的哪条执行路径，排障时有用
    // 每条事件唯一递增：浮层靠它判断「来了新动作」并重启动画。
    // 时间戳不行——同一毫秒内连点会被当成同一条。
    val seq: Long = nextSeq(),
) {
    companion object {
        private var seqCounter = 0L
        @Synchronized private fun nextSeq(): Long = ++seqCounter
    }
}

object UiActionBus {

    val state = MutableStateFlow<UiActionEvent?>(null)

    /**
     * 推一条事件，并顺手保证浮层已经挂上。
     *
     * 之所以在这里唤起浮层而不是让浮层自己常驻监听：AI 操作别的 App 时 Arix 在后台，
     * 没有任何 Composable 在跑，没人会去 collect 这个 flow。事件到达就是唯一的启动时机。
     * 用户关掉开关 / 没给悬浮窗权限时 onEvent 内部静默返回，事件照样进 state（不影响以后复用）。
     *
     * 注意 [UiActionEvent.success] 会被透传给浮层：**只有成功的动作才会把窗口加回来画高亮**。
     * 这是「窗口不能在手势瞬间存在」那条时序的一部分，也让「看见高亮 == 真的点中了」成立。
     * 事件本身无论成败都进 state，日志/聊天页将来复用不受影响。
     */
    fun emit(context: Context, event: UiActionEvent) {
        state.value = event
        try { UiActionOverlay.onEvent(context.applicationContext, event.success) } catch (_: Exception) {}
    }

    fun clear() { state.value = null }

    /** 围绕一个点造小方框：只有坐标的动作也能走「画矩形」这一条路径。 */
    fun rectAround(x: Int, y: Int, half: Int = 36): Rect = Rect(x - half, y - half, x + half, y + half)
}
