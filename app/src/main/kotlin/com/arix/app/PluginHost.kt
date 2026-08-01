package com.arix.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

// ============================================================
// 插件宿主接缝（DESIGN.md §9）—— 本轮仅预留，不建插件运行时。
//
// 未来的 WebView/JS 插件页会挂载到这里：核心 UI 通过此宿主提供一个可承载
// 插件前端的位置，并在导航中出现一个入口；插件对工具/设备的调用走现有
// ToolPermissionManager 权限闸门，主题色以 CSS 变量注入使插件界面一致。
//
// 现在它只是一个空占位，避免核心重写期把整个插件引擎一起建（范围失控）。
// 插件机制待独立工作流深入调研后再定（详见 DESIGN.md §9）。
// ============================================================

@Composable
fun PluginHost(pluginId: String? = null, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(tr("插件宿主（预留）"), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
