package com.arix.app.theme
import com.arix.app.tr

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp

// ============================================================
// XtomTheme —— 主题引擎入口（DESIGN.md §1）
// 持久化的 ThemeConfig 驱动主题：配色/字体/形状令牌全由配置解析而来。
// App 顶层用它包住内容，改设置即时全局重组生效。
// 注：本 material3 版本的 MaterialExpressiveTheme 尚为 internal（未开放），
// 故用标准 MaterialTheme 承载令牌；表现力观感由大圆角形状 + 配色令牌提供。
// M3E 开放后可无缝切换（令牌驱动方式不变）。
// ============================================================

@Composable
fun XtomTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    // 首帧从磁盘装载已保存主题+语言；默认已复现当前观感/中文，异步装载不闪。
    LaunchedEffect(Unit) { ThemeConfigStore.load(context); com.arix.app.I18n.load(context) }

    val config by ThemeConfigStore.config.collectAsState()
    // 订阅语言：改语言→本函数重组→全 App 内容重组→tr() 用新语言重新求值（即时切换不重启）。
    val lang by com.arix.app.I18n.lang.collectAsState()
    val systemDark = isSystemInDarkTheme()
    val dark = when (config.darkMode) {
        DarkMode.DARK -> true
        DarkMode.LIGHT -> false
        DarkMode.SYSTEM -> systemDark
    }
    // remember：resolveColorScheme 在 WALLPAPER/MONET 分支要读系统壁纸/取色，不便宜；且新 ColorScheme 实例
    // 会让全树读色的组件失效。配置/明暗不变就别重算（治「改任意设置全 App 抖一帧」）。
    val baseScheme = androidx.compose.runtime.remember(config, dark) { resolveColorScheme(context, config, dark) }
    // 全局玻璃：把「容器面」令牌整体改成半透明——这样**每一个**用 surfaceContainer 系列的组件
    // （Material Surface/Card、XtomCard、抽屉、胶囊、输入框、设置卡…）自动变玻璃，透出背后被模糊的背景，
    // 不用逐个组件去接。配合 MainActivity 把背景整屏模糊。手表版不考虑性能，默认开。
    // 玻璃不再靠「令牌半透明」——那只是把颜色调淡，不是模糊，像亚克力板（用户反馈）。
    // 真玻璃走 haze：面用 hazeEffect 采样背后内容做真模糊（见 XtomGlass.glassSurface）。这里配色不动。
    val colorScheme = baseScheme

    // 字号全局生效：只改 Density.fontScale（.sp→px 的换算系数），不动 density（dp→px），
    // 于是所有 .sp 文字(含代码里硬编码的 fontSize=13.sp)按比例缩放，而 dp 布局/间距不变——
    // 影响面可控、不会撑破布局。比缩放 Typography 更稳：本项目多数 Text 用硬编码 sp 而非 typography 令牌。
    // 显示密度 displayDensity 则乘到 density（dp→px）上：整体放大/缩小所有 dp 布局+文字（真 DPI 缩放），
    // 区别于只放大文字的 fontScale。范围保守(0.85..1.15)，避免撑破手表方屏。
    val baseDensity = LocalDensity.current
    val scaledDensity = androidx.compose.runtime.remember(baseDensity, config.displayDensity, config.fontScale) {
        Density(baseDensity.density * config.displayDensity, baseDensity.fontScale * config.fontScale)
    }

    // 屏幕适配（圆屏/圆角内缩）：订阅 version，用户拖滑杆即时全 App 生效。
    val fitVersion by com.arix.app.ScreenFitPrefs.version.collectAsState()
    val screenFit = androidx.compose.runtime.remember(fitVersion) {
        ScreenFit(
            insetH = com.arix.app.ScreenFitPrefs.insetH(context).dp,
            insetV = com.arix.app.ScreenFitPrefs.insetV(context).dp,
            floatInset = com.arix.app.ScreenFitPrefs.floatInset(context).dp,
        )
    }

    CompositionLocalProvider(
        LocalThemeConfig provides config,
        LocalXtomAccents provides xtomAccents(dark),
        com.arix.app.LocalLang provides lang,
        LocalScreenFit provides screenFit,
        LocalDensity provides scaledDensity,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            shapes = xtomShapes(config.shape),
            // remember：自定义字体那条分支里 xtomFontFamily 会 `Typeface.createFromFile` **真解析一遍字体文件**
            // （见 XtomTypography 里的校验注释）。不 remember 的话，主题每重组一次就在主线程重解析一次字体，
            // 还顺带产生新的 Typography 实例让全树读排版的组件一起失效。
            typography = androidx.compose.runtime.remember(config.font, config.customFontPath) {
                xtomTypography(config.font, config.customFontPath)
            },
        ) {
            // tr() 是普通函数读 _lang.value，不是被 Compose 观察的 State——只 provide LocalLang
            // 只会让「读 LocalLang.current」的少数组件重组，绝大多数 tr() 调用点不会更新（=切语言 UI 不同步）。
            // 用 key(lang) 强制整棵内容子树在语言变化时重建，令每个 tr() 重新求值——切换即时全局生效，无需 recreate()。
            androidx.compose.runtime.key(lang) { content() }
        }
    }
}
