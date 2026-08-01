package com.arix.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.arix.app.theme.LocalThemeConfig
import com.arix.app.theme.XtomMotion
import com.arix.app.theme.spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

// ============================================================
// XtomChatComponents —— 聊天视觉件（DESIGN.md §8 第2步 / §3）
// 气泡面 + “思考中”动效 + 流式光标，全部令牌化、线性快速动效。
// ============================================================

/**
 * 气泡容器：用户消息用 primary，助手用高容器面；圆角取 shapes.large。
 * 内容颜色由 Surface.contentColor 自动供给（onPrimary / onSurface）。
 */
@Composable
fun XtomBubbleSurface(
    isUser: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // 用户气泡用**淡蓝容器**（primaryContainer）而非大面积深蓝 primary——蓝只做点缀，不做大块底色。
    // 全局玻璃：气泡也玻璃化（透背后壁纸模糊）；关玻璃时退化成半透明/纯色。
    val shape = MaterialTheme.shapes.large
    val glassOn = LocalGlassBackdrop.current.on
    val a = if (glassOn) 0.60f else 1f
    val base = if (isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides fg) {
        // 气泡内边距按外观设置的「消息密度」取值（只读令牌，不改逻辑）。padding 直接链到外层 Box，省掉一层只为内边距的 Box。
        val bubblePadding = LocalThemeConfig.current.messageDensity.spacing().bubblePadding
        Box(modifier = modifier.widthIn(min = 40.dp, max = 320.dp).clip(shape).chatGlassCutout(shape, base).padding(bubblePadding.dp)) {
            content()
        }
    }
}

/**
 * “思考中”动效：三点波浪脉动 + 轻微上下弹，可打断、省电。速度跟随 ThemeConfig.motion。
 *
 * ⚠ **必须帧驱动**，不能用 `rememberInfiniteTransition + tween`（这里原来就是那么写的）。
 * 手表上「系统动画时长缩放 = 0」极常见（省电模式 / Android 11 默认），那种设备上标准动画会
 * 一帧跳到终值 —— 于是这三个点**完全静止**，而这恰恰是"AI 到底在跑还是卡了"唯一的信号，
 * 静止的时候用户只会以为程序死了。`withFrameNanos` 不受动画缩放影响，见 FrameMotion.kt。
 *
 * 相位而不是三条独立动画：一个 0→1 的循环相位，三个点各偏移 1/3，天然是"波浪"不是"齐闪"，
 * 也只用一个协程、一次帧回调。
 */
@Composable
fun ThinkingIndicator(modifier: Modifier = Modifier, label: String = "思考中") {
    val periodMs = (XtomMotion.durationMs(LocalThemeConfig.current.motion) * 4).coerceAtLeast(600)
    val phase = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(periodMs) {
        var start = 0L
        while (true) {
            androidx.compose.runtime.withFrameNanos { now ->
                if (start == 0L) start = now
                phase.floatValue = ((now - start) / 1_000_000f % periodMs) / periodMs
            }
        }
    }
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            Box(
                Modifier
                    .padding(horizontal = 2.dp)
                    // 在 graphicsLayer 的 lambda 里读 phase → 只让这三个小图层每帧失效，子树不重组
                    .graphicsLayer {
                        val local = (phase.floatValue + i / 3f) % 1f
                        // 前半周期做一次弹跳，后半周期歇着：有节奏，不是匀速上下摆
                        val lift = if (local < 0.5f) kotlin.math.sin(local * 2f * kotlin.math.PI.toFloat()) else 0f
                        translationY = -lift * 4.dp.toPx()
                        alpha = 0.3f + lift * 0.7f
                    }
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 流式输出光标：闪烁竖条，颜色取 primary，动效速度跟随主题。 */
@Composable
fun XtomStreamingCursor(modifier: Modifier = Modifier) {
    val speed = LocalThemeConfig.current.motion
    var visible by remember { mutableStateOf(true) }
    LaunchedEffect(Unit) {
        while (isActive) { delay(600); visible = false; delay(300); visible = true }
    }
    val alpha = animateFloatAsState(   // 留 State，读延到 graphicsLayer draw 阶段，闪烁不再每次重组光标 Box
        targetValue = if (visible) 1f else 0.15f,
        animationSpec = XtomMotion.tween(speed),
        label = "cursor",
    )
    Box(
        modifier = modifier
            .padding(start = 3.dp, bottom = 2.dp)
            .graphicsLayer { this.alpha = alpha.value }
            .size(width = 2.dp, height = 15.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(MaterialTheme.colorScheme.primary),
    )
}
