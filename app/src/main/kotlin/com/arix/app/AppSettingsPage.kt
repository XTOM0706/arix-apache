package com.arix.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 原「应用设置」页已按信息架构拆分归位：
//   对话设置  -> DialogSettingsPage（上下文压缩 / AI 直接执行 / 对话后自动记忆 / 快捷短语）
//   陪伴设置  -> CompanionSettingsPage（交互状态层 / 健康·天气·通知感知 / 陪伴包 / 主动消息 / 每日日记）
//   工具密钥  -> ToolKeysPage（地图 Key / 生活 Key / 提醒管理）
//   语义记忆索引 -> MemoryPage（记忆页内）
// 本壳仅作占位，路由改由设置中心分发到上述各页。
@Composable fun AppSettingsPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    // 不滚的居中占位页：给顶部悬浮玻璃让位，普通 padding 即可
    Box(modifier = Modifier.fillMaxSize().padding(start = 24.dp, end = 24.dp, bottom = 24.dp, top = 24.dp + com.arix.app.ui.topChromeGap()), contentAlignment = Alignment.Center) {
        Text(tr("设置已拆分到各分类页"), color = scheme.onSurfaceVariant, fontSize = 13.sp)
    }
}
