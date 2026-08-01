package com.arix.app.theme

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ============================================================
// XtomBlur —— 模糊工具（DESIGN.md §4）
// 默认只在关键面（顶栏/输入条/弹窗/抽拉表/遮罩）模糊；弱设备自动降级。
// 用法：Modifier.xtomBlur()（读当前主题的模糊质量 + 设备能力自适应）。
// ============================================================

/** 设备是否有条件开模糊：需 API31+ 且非低内存机（方屏手表 GPU 弱）。 */
fun isBlurCapable(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
    return am?.isLowRamDevice != true
}

/**
 * 按主题配置 + 设备能力施加模糊。OFF 或弱设备返回原 Modifier（不模糊）。
 * KEY_SURFACES 用较小半径；HIGH 用完整半径。
 */
@Composable
fun Modifier.xtomBlur(radius: Dp = 16.dp): Modifier {
    val config = LocalThemeConfig.current
    val context = LocalContext.current
    if (config.blur == BlurQuality.OFF || !isBlurCapable(context)) return this
    val effective = if (config.blur == BlurQuality.HIGH) radius else radius * 0.6f
    return this.blur(effective)
}
