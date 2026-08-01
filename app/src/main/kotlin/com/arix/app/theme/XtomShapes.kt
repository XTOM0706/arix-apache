package com.arix.app.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

// ============================================================
// XtomShapes —— 形状令牌（DESIGN.md §2）
// 组件统一取自 MaterialTheme.shapes；档位由 ThemeConfig.shape 决定。
// EXPRESSIVE = M3E 大圆角（默认）。
// ============================================================

fun xtomShapes(scale: ShapeScale): Shapes = when (scale) {
    ShapeScale.COMPACT -> Shapes(
        extraSmall = RoundedCornerShape(4.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(10.dp),
        large = RoundedCornerShape(14.dp),
        extraLarge = RoundedCornerShape(20.dp),
    )
    ShapeScale.STANDARD -> Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(14.dp),
        large = RoundedCornerShape(20.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )
    ShapeScale.EXPRESSIVE -> Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(18.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )
}
