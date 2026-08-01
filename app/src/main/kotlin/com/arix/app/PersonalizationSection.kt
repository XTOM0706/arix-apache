package com.arix.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 可折叠分区卡：标题行常驻可点，内容默认收起，点标题展开。个性化页从一大坨拆成分区，减少遮挡。
@Composable fun ExpandSection(
    title: String,
    subtitle: String?,
    scheme: ColorScheme,
    initiallyOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    var open by remember(title) { mutableStateOf(initiallyOpen) }
    val rot by animateFloatAsState(if (open) 180f else 0f, label = "expand")
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = scheme.surface),
        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),   // 固定 16dp：extraLarge 太圆了（用户反馈）
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant),   // 描边分隔：无阴影时靠边框把分区从背景里勾出来
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { open = !open }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (subtitle != null) Text(subtitle, color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp))
                }
                Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.rotate(rot))
            }
            AnimatedVisibility(open) {
                Column(Modifier.padding(bottom = 8.dp)) {
                    content()
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}
