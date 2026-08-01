package com.arix.app.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

// ============================================================
// XtomAccents —— 语义强调色令牌（成功/警告/信息）
// 标准 M3 ColorScheme 没有“成功绿/警告黄”这类角色，但页面确实需要
// （确认=绿、警告=黄、工具=青）。在此集中定义一处，页面通过
// LocalXtomAccents 取用，绝不在页面里写死这些色值。亮/暗各一套。
// ============================================================

data class XtomAccents(
    val success: Color,
    val onSuccess: Color,
    val warning: Color,
    val onWarning: Color,
    val info: Color,
)

fun xtomAccents(dark: Boolean): XtomAccents = if (dark) XtomAccents(
    success = Color(0xFFA6E3A1),
    onSuccess = Color(0xFF1E1E2E),
    warning = Color(0xFFF9E2AF),
    onWarning = Color(0xFF1E1E2E),
    info = Color(0xFF89DCEB),
) else XtomAccents(
    success = Color(0xFF40A02B),      // 功能色保留：成功该绿
    onSuccess = Color(0xFFFFFFFF),
    warning = Color(0xFFDF8E1D),      // 功能色保留：警告该橙
    onWarning = Color(0xFFFFFFFF),
    info = Color(0xFF82ABD0),         // 信息/中性提示收进 Arix 蓝 300（淡），与浅色模式点缀一致
)

val LocalXtomAccents = staticCompositionLocalOf { xtomAccents(dark = true) }
