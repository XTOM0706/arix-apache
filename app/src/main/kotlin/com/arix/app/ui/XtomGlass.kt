package com.arix.app.ui

import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

/**
 * 全局玻璃 = 「Arix 高级效果」（真磨砂玻璃）。
 *
 * 实现走「模糊一次·多处采样」自建管线（见 [rememberGlassBackdrop]），**不再用 haze 的 hazeEffect**——
 * haze 在本机的 inputScale 降采样是点采样放大，会糊成马赛克块。这里把背景(壁纸)预模糊成一张图，
 * 每个玻璃面按自身在窗口里的位置采样它（drawImage 默认双线性放大 → 平滑不露块），一次模糊处处用、还省。
 *
 * @param on 玻璃是否开启（globalGlass && 设备支持）
 * @param backdrop 预模糊好的背景图（下采样版）；null=无壁纸(纯色背景)，玻璃退化为薄霜 tint
 * @param srcW/srcH 背景在屏上的**全分辨率**像素尺寸（把 backdrop 放大铺满到这个尺寸）
 * @param originProvider 背景左上角在 window 里的坐标（玻璃面位置减它 = 在背景图里的采样偏移）
 */
data class GlassState(
    val on: Boolean,
    val backdrop: ImageBitmap? = null,
    val srcW: Int = 0,
    val srcH: Int = 0,
    val originProvider: () -> Offset = { Offset.Zero },
)

val LocalGlass = staticCompositionLocalOf { GlassState(false) }
val LocalGlassBackdrop get() = LocalGlass   // 兼容旧引用名

/**
 * 玻璃面。[base] 传**不透明**基色。
 * - 开 + 有壁纸：采样预模糊背景对应区域（双线性放大，平滑）+ 一层薄霜(base@0.35)。
 * - 开 + 无壁纸：纯色背景没什么可模糊，退化为半透明薄霜。
 * - 关：不透明 [base]。
 */
@Composable
fun Modifier.glassSurface(shape: Shape, base: Color): Modifier {
    val g = LocalGlass.current
    if (!g.on) return this.background(base, shape)
    val backdrop = g.backdrop
    if (backdrop == null || g.srcW == 0 || g.srcH == 0) {
        // 纯色背景/尚未就绪：半透明薄霜（透出底色，够读又有玻璃感），不需要模糊
        return this.background(base.copy(alpha = 0.6f), shape)
    }
    val tint = base.copy(alpha = 0.35f)
    var pos by remember { mutableStateOf(Offset.Zero) }
    return this
        .onGloballyPositioned { pos = it.positionInWindow() }
        .clip(shape)
        .drawBehind {
            val o = g.originProvider()
            // 本面左上角在背景图中的像素位置 → 把整张(放大到全屏尺寸的)背景平移 -offset，使对应区域落在本面
            val dx = (pos.x - o.x).roundToInt()
            val dy = (pos.y - o.y).roundToInt()
            drawImage(
                image = backdrop,
                dstOffset = IntOffset(-dx, -dy),
                dstSize = IntSize(g.srcW, g.srcH),   // 双线性放大铺满全屏尺寸 → 平滑不露块
            )
            drawRect(tint)   // 薄霜
        }
}
