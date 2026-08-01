package com.arix.app.ui

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arix.app.ChatEffectsPrefs
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.PI

// ============================================================
// 聊天特效 —— 触感 / 正在输入 / 滑动回复 / 流式渐显 / 按压回弹。
//
// 三条硬约束（都是本项目踩过的坑，写在最前面免得下次又踩）：
//  ① **动画一律帧驱动**。手表上「系统动画时长缩放 = 0」极常见（省电模式 / Android 11 默认），
//     标准的 animate*AsState / tween 在那种设备上直接跳到终值 = 一动不动。见 FrameMotion.kt。
//  ② **热路径不读盘**。这些东西挂在气泡渲染和每个 token 的流式路径上，是全项目最热的几条路径；
//     配置一律经 [LocalChatEffects] 一次性发下来，不在组件内部去 read SharedPreferences。
//  ③ **进度在 lambda 里读**。graphicsLayer / drawWithContent 的 lambda 里读 State 只让**图层**失效，
//     不会触发重组。写成 `val p by ...` 再用，就变成每帧重组整条气泡了。
// ============================================================

/** 特效配置下发点。由聊天页在顶层 provide 一次，气泡与流式预览共用同一份快照。 */
val LocalChatEffects = staticCompositionLocalOf { ChatEffectsPrefs.DEFAULT }

// ------------------------------------------------------------
// 触感
// ------------------------------------------------------------

/**
 * 触感反馈的统一出口。
 *
 * 不用 Compose 的 `LocalHapticFeedback`：它只有 LongPress / TextHandleMove 两种语义，
 * 表达不了「发出去了」「被拒了」「拖到位了」这些区别，而这恰恰是触感值钱的地方——
 * 用手感区分结果，用户不用看屏幕也知道发生了什么（手表上尤其重要）。
 * 所以直接走 View 的 [HapticFeedbackConstants]，按 API 等级降级。
 */
class ChatHaptics(
    private val view: android.view.View,
    private val level: ChatEffectsPrefs.Haptic,
) {
    private fun fire(constant: Int) {
        if (level == ChatEffectsPrefs.Haptic.OFF) return
        runCatching { view.performHapticFeedback(constant) }
    }

    /** 轻点：进入多选、勾选、切分支这类"确认收到"的小反馈。 */
    fun tick() = fire(HapticFeedbackConstants.CLOCK_TICK)

    /** 长按菜单弹出。LIGHT 档也保留——长按没反馈会让人以为没按到。 */
    fun longPress() = fire(HapticFeedbackConstants.LONG_PRESS)

    /** 消息发出去了。 */
    fun sent() = fire(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
        else HapticFeedbackConstants.KEYBOARD_TAP
    )

    /** 一轮生成完成。只在 FULL 档给——LIGHT 档的人多半不想每答完一句都被震一下。 */
    fun done() {
        if (level != ChatEffectsPrefs.Haptic.FULL) return
        fire(if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
    }

    /** 出错 / 被拒绝 / 被停止。 */
    fun reject() = fire(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
        else HapticFeedbackConstants.LONG_PRESS
    )

    /** 手势拖到触发线了（滑动回复）。 */
    fun gestureThreshold() = fire(
        if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        else HapticFeedbackConstants.CLOCK_TICK
    )
}

@Composable
fun rememberChatHaptics(): ChatHaptics {
    val view = LocalView.current
    val level = LocalChatEffects.current.haptic
    return remember(view, level) { ChatHaptics(view, level) }
}

// 注：「AI 正在思考」的三点跳动不在这里 —— 它是 [ThinkingIndicator]（XtomChatComponents.kt），
// 属于核心 UI 而不是可选特效（它是"还在跑 vs 卡住了"唯一的信号，不该能被关掉）。
// 那边原本用 rememberInfiniteTransition+tween，在「动画缩放=0」的手表上完全静止，已一并改成帧驱动。

// ------------------------------------------------------------
// 滑动回复
// ------------------------------------------------------------

/** 拖到多远算触发。太短会误触（列表本身是纵向滚的，横向分量总有一点）。 */
private val REPLY_TRIGGER = 56.dp

/**
 * 横向拖气泡 → 回复这条。Telegram/微信都是这个手势，是"不用长按开菜单"的最短路径。
 *
 * 方向按气泡所在侧走：左侧气泡向右拖，右侧气泡向左拖——都是"往屏幕中间拽"，
 * 手感上像是把它拉出来引用。反方向拖不响应（那是列表的滑动/返回手势的地盘）。
 *
 * @param onReply 拖过触发线并松手时调用。
 */
@Composable
fun Modifier.swipeToReply(
    enabled: Boolean,
    fromLeft: Boolean,
    onReply: () -> Unit,
): Modifier {
    if (!enabled) return this
    val haptics = rememberChatHaptics()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    // 本次拖动是否已经越过触发线：用来做"越线那一下"的单次触感，别每帧都震
    val armed = remember { mutableFloatStateOf(0f) }
    val triggerPx = with(androidx.compose.ui.platform.LocalDensity.current) { REPLY_TRIGGER.toPx() }
    val accent = MaterialTheme.colorScheme.primary

    return this
        .drawWithContent {
            drawContent()
            // 回复图标画在气泡"被拖开"露出来的那条缝里。用 drawWithContent 而不是叠一个
            // Composable：不占布局、不参与测量，滚动列表里多一个节点是要还的。
            val d = abs(offset.value)
            if (d > 1f) {
                val a = (d / triggerPx).coerceIn(0f, 1f)
                val r = 9.dp.toPx() * (0.6f + 0.4f * a)
                val cx = if (fromLeft) offset.value / 2f else size.width + offset.value / 2f
                drawCircle(color = accent.copy(alpha = 0.18f * a), radius = r, center = androidx.compose.ui.geometry.Offset(cx, size.height / 2f))
            }
        }
        .graphicsLayer { translationX = offset.value }
        .pointerInput(enabled, fromLeft) {
            detectHorizontalDragGestures(
                onDragEnd = {
                    val fired = abs(offset.value) >= triggerPx
                    armed.floatValue = 0f
                    // FullMotion：回弹是**程序触发**的动画，不套这个的话在「动画缩放=0」的设备上
                    // 会瞬间归位、完全看不到回弹（本项目的老坑，见 FrameMotion.kt）。
                    scope.launch(FullMotion) { offset.animateTo(0f) }
                    if (fired) onReply()
                },
                onDragCancel = {
                    armed.floatValue = 0f
                    scope.launch(FullMotion) { offset.animateTo(0f) }
                },
            ) { change, drag ->
                // 只吃"往中间拽"的方向；另一个方向留给别的手势
                val next = (offset.value + drag).let { if (fromLeft) it.coerceIn(0f, triggerPx * 1.5f) else it.coerceIn(-triggerPx * 1.5f, 0f) }
                if (next != offset.value) {
                    change.consume()
                    scope.launch { offset.snapTo(next) }
                }
                // 越线那一下震一次；退回线内后复位，允许再次触发
                val over = abs(next) >= triggerPx
                if (over && armed.floatValue == 0f) { armed.floatValue = 1f; haptics.gestureThreshold() }
                else if (!over) armed.floatValue = 0f
            }
        }
}

// ------------------------------------------------------------
// 流式渐显
// ------------------------------------------------------------

/**
 * 给**正在流式接收**的正文下边缘加一层渐隐遮罩：新字是从"雾"里浮出来的，而不是硬蹦一行。
 *
 * 为什么是遮罩而不是逐字淡入：逐字淡入要给每个字符单独的 alpha，等于每来一个 token 就重建一次
 * AnnotatedString —— 而本项目的 Markdown 管线是**按全文做 LRU 缓存**的（见 MarkdownText.kt），
 * 逐字 alpha 会让缓存全部失效，把好不容易调顺的流式性能砸回去。
 * 遮罩走的是绘制阶段的一次 BlendMode，跟文本管线完全不相干，代价是一个离屏图层。
 *
 * 只该套在**流式那一条**气泡上（isSending 时的预览气泡），历史消息不要套。
 */
fun Modifier.streamRevealMask(active: Boolean, fade: Dp = 22.dp): Modifier {
    if (!active) return this
    return this
        // DstIn 混合要求内容先渲到一张离屏纹理上，否则会跟它下面的背景一起被"擦"掉
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val h = fade.toPx().coerceAtMost(size.height)
            if (h <= 0f) return@drawWithContent
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startY = size.height - h,
                    endY = size.height,
                ),
                topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - h),
                size = androidx.compose.ui.geometry.Size(size.width, h),
                blendMode = BlendMode.DstIn,
            )
        }
}

// ------------------------------------------------------------
// 按压回弹
// ------------------------------------------------------------

/**
 * 按下时轻微缩一点、松手弹回来。纯视觉，不拦截任何事件（用 pointerInput 的 awaitPointerEventScope
 * 只**观察**按下/抬起，不 consume），所以叠在已有的 combinedClickable 上不会打架。
 */
@Composable
fun Modifier.pressBounce(enabled: Boolean, scaleTo: Float = 0.975f): Modifier {
    if (!enabled) return this
    val pressed = remember { mutableFloatStateOf(0f) }
    val s = rememberFrameFloat(target = if (pressed.floatValue > 0.5f) scaleTo else 1f, durationMs = 120)
    return this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    // Initial 通道 = 在任何人处理之前先看一眼，且**不 consume**：
                    // 纯观察，点击/长按/滚动照旧由各自的手势去处理，不会被这层截胡。
                    val ev = awaitPointerEvent(PointerEventPass.Initial)
                    pressed.floatValue = if (ev.changes.any { it.pressed }) 1f else 0f
                }
            }
        }
        // 在 lambda 里读 → 只让本气泡的图层失效，不重组
        .graphicsLayer { scaleX = s.value; scaleY = s.value }
}

/** 供外部按帧读的按压缩放（需要自己控制按压来源时用）。 */
@Composable
fun rememberPressScale(pressed: Boolean, scaleTo: Float = 0.975f): State<Float> =
    rememberFrameFloat(target = if (pressed) scaleTo else 1f, durationMs = 120)
