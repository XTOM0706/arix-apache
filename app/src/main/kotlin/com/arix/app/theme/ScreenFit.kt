package com.arix.app.theme

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 屏幕适配的当前取值（用户在个性化页手调，见 com.arix.app.ScreenFitPrefs）。
 *
 * 手表圆屏被表圈切掉四角、圆角矩形屏被设备圆角裁切，所以内容要往里收。
 * 由 [XtomTheme] 统一 provide，各页直接 `LocalScreenFit.current` 取，不用逐层传参。
 *
 * - [insetH]/[insetV]：普通页面内容的额外内缩，在 MainActivity 路由处统一施加
 *   （PageScaffold 只有 5 个页面在用，盖不全，路由处才管得住全部 30+ 页）。
 * - [floatInset]：悬浮界面（聊天玻璃顶栏/输入栏、唤醒助手浮层）的额外内缩，
 *   它们贴边浮着，被切得比普通内容更狠，所以单独一个量。
 */
@Immutable
data class ScreenFit(
    val insetH: Dp = 0.dp,
    val insetV: Dp = 0.dp,
    val floatInset: Dp = 0.dp,
)

val LocalScreenFit = staticCompositionLocalOf { ScreenFit() }

/**
 * 普通内容的屏幕适配内缩（[ScreenFit.insetH]/[ScreenFit.insetV]）的 Modifier 版。
 *
 * **为什么要有它**：以前每个需要适配的地方都手写 `LocalScreenFit.current` + 字面量相加，
 * 于是很容易「只加了横向、忘了纵向」，或者干脆整块漏掉（顶栏就漏了很久：全局内缩加在路由的
 * 内容 Box 上，而顶栏是它的兄弟节点，压根吃不到 → 圆屏上返回箭头和大标题被表圈切掉）。
 * 新增贴边元素时一律用这个，别再手拼。
 *
 * 四条边分别可关：贴屏顶的顶栏用 `screenFitPadding(bottom = false)`（下边缘不在屏幕边上，
 * 收了就是白空一截）；被别的机制（如 M3 顶栏已把 insetV 带进 Scaffold 内距）顶下来的内容
 * 用 `top = false` 免得内缩两次。
 */
@Composable
fun Modifier.screenFitPadding(
    horizontal: Boolean = true,
    top: Boolean = true,
    bottom: Boolean = true,
): Modifier {
    val fit = LocalScreenFit.current
    val h = if (horizontal) fit.insetH else 0.dp
    return this.padding(
        start = h,
        end = h,
        top = if (top) fit.insetV else 0.dp,
        bottom = if (bottom) fit.insetV else 0.dp,
    )
}

/**
 * 悬浮件的额外内缩（[ScreenFit.floatInset]）的 Modifier 版。悬浮件贴边浮着、被切得比普通内容狠，
 * 所以是独立的一个量（用户在个性化页的「悬浮界面」滑杆调）。
 *
 * ⚠ 横纵两个方向要分开想清楚：这根滑杆原来只被拿去加在横向 padding 上，纵向一直没人加，
 * 结果圆屏上顶部胶囊左右收了、上边照样被切。默认两向都收。
 */
@Composable
fun Modifier.floatFitPadding(
    horizontal: Boolean = true,
    vertical: Boolean = true,
): Modifier {
    val f = LocalScreenFit.current.floatInset
    return this.padding(
        horizontal = if (horizontal) f else 0.dp,
        vertical = if (vertical) f else 0.dp,
    )
}
