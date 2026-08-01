package com.arix.app.ui

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

// ============================================================
// FrameMotion —— 自推帧的插值工具（**不受系统「动画时长缩放」影响**）。
//
// 为什么不用 animate*AsState / tween：手表上「动画缩放 = 0」极常见（省电模式 / Android 11 默认），
// 此时 Compose 的标准动画直接跳到终值 = 一动不动。本项目为此栽过多次（唤醒流光、ToolActivityTicker
// 的转圈、聊天页删除气泡的幽灵残留），既有结论是：凡「必须动起来」的一律用 withFrameNanos 手推帧
// —— 帧回调不受动画缩放影响。同 ui/Marquee.kt 的做法，这里把它抽成通用件，别再各页各抄一份。
//
// 返回 State<Float> 而不是 Float 是刻意的：调用方可以在 `graphicsLayer { }` / `drawBehind { }` /
// `layout { }` 的 lambda 里读它 —— 那是绘制/布局阶段的读取，只让图层失效，**不会每帧重组**。
// 直接 `by` 解构成 Float 也行（小叶子节点无所谓），但列表/整页级别的动画务必走 lambda 读。
// ============================================================

/** 让程序触发的动画（如 `animateScrollToPage`）忽略系统动画缩放：`scope.launch(FullMotion) { … }`。 */
val FullMotion: MotionDurationScale = object : MotionDurationScale {
    override val scaleFactor: Float get() = 1f
}

/** 冲过头再收回来的缓动（back-out）。给「弹出来」的图标/徽标用，比纯减速有生气。 */
val OvershootEasing: Easing = Easing { t ->
    val c1 = 1.70158f
    val c3 = c1 + 1f
    val x = t - 1f
    1f + c3 * x * x * x + c1 * x * x
}

/**
 * 呼吸循环：0 → 1 → 0 的平滑往复（余弦，两端不生硬）。
 *
 * @param cycles 呼吸几次后停下并归零；**默认 3 次而不是无限** —— 这是手表，
 *   常驻动画等于一直申请帧回调、一直耗电。要长亮的场景才显式传 0（无限）。
 */
@Composable
fun rememberBreath(periodMs: Int = 3000, cycles: Int = 3): State<Float> {
    val v = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(periodMs, cycles) {
        var start = 0L
        while (true) {
            val stop = withFrameNanos { now ->
                if (start == 0L) start = now
                val elapsed = (now - start) / 1_000_000f
                if (cycles > 0 && elapsed >= periodMs.toFloat() * cycles) {
                    v.floatValue = 0f
                    true
                } else {
                    val t = (elapsed % periodMs) / periodMs
                    v.floatValue = (1f - cos(t * 2f * PI.toFloat())) / 2f
                    false
                }
            }
            if (stop) break
        }
    }
    return v
}

/**
 * 一条 0→1 的进度，[key] 每变一次就重跑一遍。入场 / 切换动画用它。
 *
 * @param enabled false 时直接停在 1（等于「不做动画」），用来按条件关掉入场。
 */
@Composable
fun rememberFrameProgress(
    key: Any?,
    durationMs: Int = 300,
    easing: Easing = FastOutSlowInEasing,
    enabled: Boolean = true,
): State<Float> {
    val p = remember { mutableFloatStateOf(if (enabled && durationMs > 0) 0f else 1f) }
    LaunchedEffect(key, enabled, durationMs) {
        if (!enabled || durationMs <= 0) { p.floatValue = 1f; return@LaunchedEffect }
        p.floatValue = 0f
        var start = 0L
        while (true) {
            val done = withFrameNanos { now ->
                if (start == 0L) start = now
                val t = ((now - start) / 1_000_000f / durationMs).coerceIn(0f, 1f)
                p.floatValue = easing.transform(t)
                t >= 1f
            }
            if (done) break
        }
        p.floatValue = 1f
    }
    return p
}

/**
 * 像 `animateFloatAsState`，但自己推帧。[target] 一变就从**当前值**平滑走过去（中途改目标不会跳）。
 */
@Composable
fun rememberFrameFloat(
    target: Float,
    durationMs: Int = 260,
    easing: Easing = FastOutSlowInEasing,
): State<Float> {
    val v = remember { mutableFloatStateOf(target) }
    LaunchedEffect(target, durationMs) {
        val from = v.floatValue
        if (from == target || durationMs <= 0) { v.floatValue = target; return@LaunchedEffect }
        var start = 0L
        while (true) {
            val done = withFrameNanos { now ->
                if (start == 0L) start = now
                val t = ((now - start) / 1_000_000f / durationMs).coerceIn(0f, 1f)
                v.floatValue = from + (target - from) * easing.transform(t)
                t >= 1f
            }
            if (done) break
        }
        v.floatValue = target
    }
    return v
}

/**
 * 依次入场：第 [index] 项按 [stagger] 的比例错开，在整体进度 [progress] 里各占一段，
 * 淡入的同时从下方 [rise] 处升上来。
 *
 * 进度在 `graphicsLayer` 的 lambda 里读 → 只让该节点的图层失效，**整页不会每帧重组**。
 */
fun Modifier.staggerIn(
    progress: State<Float>,
    index: Int,
    stagger: Float = 0.10f,
    rise: Dp = 12.dp,
): Modifier = this.graphicsLayer {
    val head = (index * stagger).coerceIn(0f, 0.9f)
    val local = ((progress.value - head) / (1f - head)).coerceIn(0f, 1f)
    alpha = local
    translationY = rise.toPx() * (1f - local)
}

/**
 * 纵向展开/收起：按 [progress] 裁掉下半部分（内容照常测量，只是没全露出来）。
 * 等价于 `animateContentSize` 的展开手感，但走帧驱动，动画缩放=0 时照样动。
 *
 * 进度在 `layout` 的 lambda 里读 → 只触发重新布局，不重组。
 */
fun Modifier.revealVertically(progress: State<Float>): Modifier = this
    .clipToBounds()
    .layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        val h = (placeable.height * progress.value.coerceIn(0f, 1f)).roundToInt()
        layout(placeable.width, h) { placeable.place(0, 0) }
    }
