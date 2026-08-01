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
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt

// ============================================================
// 聊天气泡玻璃 —— 玻璃**绑在气泡身上**（气泡自己的 drawBehind）
// ------------------------------------------------------------
// 玻璃是气泡的一部分：画进气泡自己的 drawBehind，气泡怎么动（滚动/回弹/动画）玻璃怎么动，
// **永远在文字底下，不可能错位**。不搞独立层/登记表去跟气泡「对表同步」——那是之前错位 bug 的来源。
//
// 采样：背景（预模糊壁纸）是屏幕固定的，气泡按自身窗口位置采对应那块（drawImage 双线性放大）。
// 回弹补偿：底部橡皮筋是 LazyColumn 的 graphicsLayer{translationY}（不触发 relayout→pos 是旧的），
// 气泡的 drawBehind 在这个平移图层内，会跟着平移；为了让模糊仍贴住屏幕固定的背景，采样偏移里
// 减掉这个 translationY（[LocalChatOverscroll] 传进来）→ 回弹时「气泡模糊和背景对不上」也一并修好。
//
// 与全局 glassSurface 的区别：只多了「回弹补偿 + 聊天专用」；其余（输入栏/顶栏/设置卡）仍用 glassSurface。
// ============================================================

/** 聊天列表把 overscroll 的 translationY 通过它传给每个气泡做采样补偿。默认 0（不在聊天流时）。 */
val LocalChatOverscroll = staticCompositionLocalOf<() -> Float> { { 0f } }

/**
 * 气泡玻璃面。替换气泡上的 `.glassSurface(shape, base)`。
 * - 玻璃关：不透明 base。玻璃开但无壁纸：半透明薄霜。
 * - 玻璃开且有壁纸：clip 成气泡形状，drawBehind 里采样屏幕固定背景对应区域 + 薄霜。玻璃随气泡走，绝不脱节。
 */
@Composable
fun Modifier.chatGlassCutout(shape: Shape, base: Color): Modifier {
    val g = LocalGlass.current
    if (!g.on) return this.background(base, shape)
    val backdrop = g.backdrop
    // ⚠ 这两处 alpha 必须**乘上**调用方传进来的 alpha，不能直接写死覆盖。
    // 写死的后果：全局玻璃默认是开的，于是"气泡不透明度"那根滑杆对绝大多数用户**完全没反应**——
    // 拖到底也还是 0.35，看起来就是个坏掉的开关。乘法保留了玻璃自己的通透度层级，
    // 同时让用户调的透明度真的起作用（base.alpha=1 时与原来完全等价，老观感不变）。
    if (backdrop == null || g.srcW == 0 || g.srcH == 0) return this.background(base.copy(alpha = 0.6f * base.alpha), shape)
    val tint = base.copy(alpha = 0.35f * base.alpha)
    var pos by remember { mutableStateOf(Offset.Zero) }   // 只在 drawBehind 里读 → 写它只重绘不重组
    return this
        .onGloballyPositioned { pos = it.positionInWindow() }
        .clip(shape)
        .drawBehind {
            // ⚠ 绝不在这里读 overscroll 的 translationY：一读，回弹时每个气泡玻璃每帧重画 → 顶部下拉卡（还可能把顶部
            // 模糊弄花）。代价：回弹那 40dp 内模糊跟气泡一起轻移（不补偿贴死固定背景），换下拉丝滑——划算。
            val o = g.originProvider()
            val dx = (pos.x - o.x).roundToInt()
            val dy = (pos.y - o.y).roundToInt()
            drawImage(image = backdrop, dstOffset = IntOffset(-dx, -dy), dstSize = IntSize(g.srcW, g.srcH))
            drawRect(tint)
        }
}
