package com.arix.app.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily

// ============================================================
// XtomTypography —— 字体令牌（DESIGN.md §1/§2）
// 用户可选字体族，走 Typography 令牌。DEFAULT/SERIF/MONO 用系统族；CUSTOM 从用户选的 ttf/otf 文件加载。
// ============================================================

fun xtomFontFamily(font: FontChoice, customPath: String = ""): FontFamily = when (font) {
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.MONO -> FontFamily.Monospace
    FontChoice.ROUNDED -> FontFamily.Default // TODO: 内置圆体字体后替换
    FontChoice.CUSTOM -> {
        val f = java.io.File(customPath)
        // 关键：Font(File) 只建描述符、延迟到 Compose 解析时才真正 parse，非字体/损坏会在那时抛→整屏崩(boot loop)。
        // 这里先用 Typeface.createFromFile 真解析一遍校验：失败/回退到系统默认字型 都判无效→用 Default，绝不把坏文件交给 Compose 解析器。
        if (customPath.isBlank() || !f.exists()) FontFamily.Default
        else try {
            val tf = android.graphics.Typeface.createFromFile(f)
            if (tf != null && tf != android.graphics.Typeface.DEFAULT) FontFamily(Font(f)) else FontFamily.Default
        } catch (_: Throwable) { FontFamily.Default }
    }
    FontChoice.DEFAULT -> FontFamily.Default
}

fun xtomTypography(font: FontChoice, customPath: String = ""): Typography {
    val b = Typography()
    if (font == FontChoice.DEFAULT) return b
    val f = xtomFontFamily(font, customPath)
    return Typography(
        displayLarge = b.displayLarge.copy(fontFamily = f),
        displayMedium = b.displayMedium.copy(fontFamily = f),
        displaySmall = b.displaySmall.copy(fontFamily = f),
        headlineLarge = b.headlineLarge.copy(fontFamily = f),
        headlineMedium = b.headlineMedium.copy(fontFamily = f),
        headlineSmall = b.headlineSmall.copy(fontFamily = f),
        titleLarge = b.titleLarge.copy(fontFamily = f),
        titleMedium = b.titleMedium.copy(fontFamily = f),
        titleSmall = b.titleSmall.copy(fontFamily = f),
        bodyLarge = b.bodyLarge.copy(fontFamily = f),
        bodyMedium = b.bodyMedium.copy(fontFamily = f),
        bodySmall = b.bodySmall.copy(fontFamily = f),
        labelLarge = b.labelLarge.copy(fontFamily = f),
        labelMedium = b.labelMedium.copy(fontFamily = f),
        labelSmall = b.labelSmall.copy(fontFamily = f),
    )
}
