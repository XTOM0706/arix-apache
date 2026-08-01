package com.arix.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.theme.LocalXtomAccents
import com.arix.app.ui.XtomCard
import com.arix.app.ui.XtomField
import com.arix.app.ui.PageScaffold
import com.arix.tool.PluginCreatorTool
import kotlinx.coroutines.launch
import java.io.File

@Composable fun PluginCreatorPage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    var pluginName by remember { mutableStateOf("") }
    var pluginDesc by remember { mutableStateOf("") }
    var pluginType by remember { mutableStateOf("skill") }
    var resultMsg by remember { mutableStateOf("") }

    PageScaffold {
        Text(tr("插件制作"), color = scheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(tr("创建 Skill、沙盒包或 MCP 配置"), color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 12.dp))

        Text(tr("插件名称"), color = scheme.onSurface, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        XtomField(value = pluginName, onValueChange = { pluginName = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, placeholder = "例如: 天气查询", textStyle = TextStyle(fontSize = 13.sp))

        Spacer(modifier = Modifier.height(12.dp))
        Text(tr("插件描述"), color = scheme.onSurface, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        XtomField(value = pluginDesc, onValueChange = { pluginDesc = it }, modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 3, placeholder = "描述这个插件的功能...", textStyle = TextStyle(fontSize = 13.sp))

        Spacer(modifier = Modifier.height(12.dp))
        Text(tr("插件类型"), color = scheme.onSurface, fontSize = 13.sp)
        Spacer(modifier = Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("skill" to "Skill", "sandbox" to "沙盒包", "mcp" to "MCP").forEach { (type, label) ->
                Button(onClick = { pluginType = type },
                    colors = ButtonDefaults.buttonColors(containerColor = if (pluginType == type) scheme.primary else scheme.surfaceContainerHighest),
                    shape = RoundedCornerShape(14.dp), modifier = Modifier.height(32.dp)) {
                    Text(label, color = if (pluginType == type) scheme.onPrimary else scheme.onSurface, fontSize = 12.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = {
            if (pluginName.isBlank()) { resultMsg = "请输入插件名称"; return@Button }
            val creator = PluginCreatorTool(context)
            scope.launch {
                val params = org.json.JSONObject().apply { put("name", pluginName); put("type", pluginType); put("description", pluginDesc) }
                val result = creator.execute(params)
                resultMsg = if (result.isError) "错误: ${result.content}" else result.content
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), shape = RoundedCornerShape(14.dp)) {
            Text(tr("创建插件"), color = scheme.onPrimary, fontWeight = FontWeight.Bold)
        }

        if (resultMsg.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            XtomCard {
                Text(resultMsg, color = if (resultMsg.startsWith("错误")) scheme.error else scheme.primary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text(tr("已创建的插件"), color = scheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        val skillsDir = File(context.filesDir, "operit_skills")
        val pkgDir = File(context.filesDir, "operit_packages")
        val mcpDir = File(context.filesDir, "operit_mcp")
        listOf("Skill" to skillsDir, "沙盒包" to pkgDir, "MCP" to mcpDir).forEach { (label, dir) ->
            if (dir.exists()) {
                val items = dir.listFiles()?.toList() ?: emptyList()
                if (items.isNotEmpty()) {
                    Text("$label (${items.size}):", color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    items.forEach { f -> Text("  ${f.name}", color = scheme.onSurfaceVariant, fontSize = 11.sp) }
                }
            }
        }
    }
}
