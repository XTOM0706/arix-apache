package com.arix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

// ============================================================
// MarqueeText —— 自推帧的跑马灯
//
// 为什么不用 Modifier.basicMarquee()：它走标准动画 API，而手表上「系统动画缩放 = 0」
// 极常见（省电模式 / Android 11 默认），此时 Compose 的动画直接跳到终值 = 一动不动。
// 本项目为此栽过多次（唤醒流光、ToolActivityTicker 的转圈），既有结论是：凡是「必须一直动」
// 的东西一律用 withFrameNanos 手推帧——帧回调不受动画缩放影响。
//
// 节奏上刻意不做匀速无限刷：这东西挂在顶栏上，一直匀速滑过去很吵。改成
// 「停一下 → 滑到底 → 停一下 → 快速回卷」，眼睛只在需要看尾部时被拉一次。
// ============================================================

/**
 * 单行文本，超宽时才滚动；没超宽就是一个普通 [Text]（短名字不该在顶栏晃）。
 *
 * @param active 组件逻辑上是否活跃。false 时停在原位不空转——顶栏这类常驻 UI 不能白烧电。
 *   （包在 AnimatedVisibility 里时不必传：不可见就不组合，帧循环随之被取消。）
 * @param speedDpPerSec 滚动速度，dp/秒。
 * @param pauseMillis 两端停顿时长：滚到头停住让人读完，再回卷。
 * @param rewindFactor 回卷相对正向的倍速。回程没有阅读价值，快点收回去。
 * @param edgeFade 两端淡出遮罩宽度，让截断处不是硬切；传 0.dp 关掉。
 */
@Composable
fun MarqueeText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    active: Boolean = true,
    speedDpPerSec: Float = 24f,
    pauseMillis: Long = 1400L,
    rewindFactor: Float = 3f,
    edgeFade: Dp = 6.dp,
) {
    val density = LocalDensity.current
    // 文本本征宽 / 可用宽：由 Layout 量出来回填，用来判断「是不是真的超出了」
    var contentW by remember { mutableStateOf(0) }
    var viewportW by remember { mutableStateOf(0) }
    // 文本一变就从头开始，否则换了角色卡还停在上一个名字滚到一半的位置
    var offsetPx by remember(text) { mutableStateOf(0f) }
    val overflow = contentW > viewportW + 1

    LaunchedEffect(text, active, overflow, contentW, viewportW) {
        offsetPx = 0f
        if (!active || !overflow) return@LaunchedEffect
        val speed = with(density) { speedDpPerSec.dp.toPx() } / 1000f   // px/ms
        val maxOffset = (contentW - viewportW).toFloat()
        while (true) {
            delay(pauseMillis)          // 停顿用 delay 而非动画：这里只是等待，不涉及插值
            var last = 0L
            while (offsetPx < maxOffset) {
                withFrameNanos { now ->
                    if (last != 0L) offsetPx = (offsetPx + (now - last) / 1_000_000f * speed).coerceAtMost(maxOffset)
                    last = now
                }
            }
            delay(pauseMillis)
            last = 0L
            while (offsetPx > 0f) {
                withFrameNanos { now ->
                    if (last != 0L) offsetPx = (offsetPx - (now - last) / 1_000_000f * speed * rewindFactor).coerceAtLeast(0f)
                    last = now
                }
            }
        }
    }

    // 淡出遮罩：只在真的会滚（=真的被截断）时上，否则短名字两头也发虚，白白牺牲对比度。
    // 离屏合成 + DstIn 是本项目一贯的渐隐做法（同顶栏底缘）。
    val fadePx = with(density) { edgeFade.toPx() }
    val fadeMod = if (overflow && fadePx > 0f) Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val f = (fadePx / size.width).coerceIn(0f, 0.4f)
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent, f to Color.Black,
                    1f - f to Color.Black, 1f to Color.Transparent,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    else Modifier

    Layout(
        content = {
            Box {
                Text(
                    text = text,
                    color = color,
                    style = style,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Visible,   // 不省略：超出部分要留着给滚动看
                )
            }
        },
        modifier = modifier.clipToBounds().then(fadeMod),
    ) { measurables, constraints ->
        // 用无限宽量出文本真实宽度，再把自己收进父给的宽度里——这就是「有没有超出」的唯一判据
        val placeable = measurables.first().measure(
            Constraints(minWidth = 0, maxWidth = Constraints.Infinity, minHeight = 0, maxHeight = constraints.maxHeight)
        )
        val w = if (constraints.hasBoundedWidth) placeable.width.coerceAtMost(constraints.maxWidth) else placeable.width
        layout(w.coerceAtLeast(constraints.minWidth), placeable.height) {
            // 在放置阶段回填尺寸：值稳定后不再变，不会来回重组
            if (contentW != placeable.width) contentW = placeable.width
            if (viewportW != w) viewportW = w
            placeable.place(-offsetPx.roundToInt(), 0)
        }
    }
}
