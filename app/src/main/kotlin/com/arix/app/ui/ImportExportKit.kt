package com.arix.app.ui
import com.arix.app.tr

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.tool.ImportExport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 通用「导入 / 导出」组件，供角色卡 / 世界书 / 记忆 / 配置各页复用（单项与整页都用它）。
 *
 * 导出（永远是我们自己的格式）四种方式：系统分享 / 保存到文件(系统选择器) / 复制JSON / 导出到应用目录。
 * 导入两种方式：从文件(系统选择器) / 粘贴JSON。导入会自动识别并转换主流第三方格式（酒馆/Operit 等）。
 *
 * @param produceJson 生成要导出的 JSON（挂起）。为 null 时不显示「导出」。
 * @param consumeJson 处理导入的 JSON，返回结果提示（挂起）。为 null 时不显示「导入」。
 * @param onResult    结果回调（提示文案），页面可用 Snackbar/Toast 展示并刷新列表。
 */
@Composable
fun ImportExportButtons(
    context: android.content.Context,
    scope: CoroutineScope,
    fileBaseName: String,
    produceJson: (suspend () -> String)? = null,
    consumeJson: (suspend (String) -> String)? = null,
    onResult: (String) -> Unit = {},
    compact: Boolean = false,
) {
    val scheme = MaterialTheme.colorScheme
    var exportOpen by remember { mutableStateOf(false) }
    var importOpen by remember { mutableStateOf(false) }
    var pasteOpen by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<String?>(null) }

    fun stampName(): String {
        val t = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return (fileBaseName.ifBlank { "xtom" }.replace(Regex("[^\\w\\u4e00-\\u9fa5-]"), "_")) + "_$t.json"
    }

    // 系统「新建文档」——保存到用户选的位置
    val createDoc = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val json = pendingExport
        if (uri != null && json != null) scope.launch(Dispatchers.IO) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }; true }.getOrDefault(false)
            withContext(Dispatchers.Main) { onResult(if (ok) "已保存到文件" else "保存失败") }
        }
        pendingExport = null
    }
    // 系统「打开文档」——从用户选的文件导入
    val openDoc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val consume = consumeJson
        if (uri != null && consume != null) scope.launch(Dispatchers.IO) {
            val text = runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() } }.getOrNull()
            val msg = if (text.isNullOrBlank()) "读取失败或文件为空"
                      else runCatching { consume(text) }.getOrElse { "导入失败: ${it.message}" }
            withContext(Dispatchers.Main) { onResult(msg) }
        }
    }

    fun doExport(kind: String) {
        val produce = produceJson ?: return
        scope.launch {
            val json = runCatching { produce() }.getOrElse { onResult("导出失败: ${it.message}"); return@launch }
            when (kind) {
                "share" -> withContext(Dispatchers.IO) {
                    val f = ImportExport.saveToFile(context, json, stampName())
                    withContext(Dispatchers.Main) { runCatching { ImportExport.shareFile(context, f) }.onFailure { onResult("分享失败: ${it.message}") } }
                }
                "file" -> { pendingExport = json; runCatching { createDoc.launch(stampName()) }.onFailure { onResult("无法打开文件选择器") } }
                "clip" -> { ImportExport.copyToClipboard(context, json); onResult("已复制 JSON 到剪贴板") }
                "dir" -> withContext(Dispatchers.IO) {
                    val f = ImportExport.saveToFile(context, json, stampName())
                    withContext(Dispatchers.Main) { onResult("已导出到应用目录: exports/${f.name}") }
                }
            }
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (produceJson != null) Box {
            Chip(scheme, Icons.Outlined.FileDownload, "导出", compact) { exportOpen = true }
            DropdownMenu(expanded = exportOpen, onDismissRequest = { exportOpen = false }) {
                DropdownMenuItem(text = { Text(tr("系统分享…")) }, onClick = { exportOpen = false; doExport("share") })
                DropdownMenuItem(text = { Text(tr("保存到文件…")) }, onClick = { exportOpen = false; doExport("file") })
                DropdownMenuItem(text = { Text(tr("复制 JSON")) }, onClick = { exportOpen = false; doExport("clip") })
                DropdownMenuItem(text = { Text(tr("导出到应用目录")) }, onClick = { exportOpen = false; doExport("dir") })
            }
        }
        if (consumeJson != null) Box {
            Chip(scheme, Icons.Outlined.FileUpload, "导入", compact) { importOpen = true }
            DropdownMenu(expanded = importOpen, onDismissRequest = { importOpen = false }) {
                DropdownMenuItem(text = { Text(tr("从文件导入…")) }, onClick = { importOpen = false; runCatching { openDoc.launch(arrayOf("application/json", "text/*", "*/*")) }.onFailure { onResult("无法打开文件选择器") } })
                DropdownMenuItem(text = { Text(tr("粘贴 JSON 导入")) }, onClick = { importOpen = false; pasteText = ""; pasteOpen = true })
            }
        }
    }

    if (pasteOpen) AlertDialog(
        onDismissRequest = { pasteOpen = false },
        title = { Text(tr("粘贴 JSON 导入"), color = scheme.onSurface, fontSize = 15.sp) },
        text = {
            Column {
                Text(tr("支持本应用格式，也会自动识别并转换主流格式（酒馆 SillyTavern / Operit 等）。"), color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))
                XtomField(value = pasteText, onValueChange = { pasteText = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp), textStyle = TextStyle(fontSize = 12.sp), placeholder = tr("在此粘贴 JSON…"), singleLine = false, minLines = 6)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val consume = consumeJson; val txt = pasteText
                pasteOpen = false
                if (consume != null && txt.isNotBlank()) scope.launch {
                    val msg = runCatching { consume(txt) }.getOrElse { "导入失败: ${it.message}" }
                    onResult(msg)
                }
            }) { Text(tr("导入"), color = scheme.primary) }
        },
        dismissButton = { TextButton(onClick = { pasteOpen = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
        containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
    )
}

@Composable
private fun Chip(scheme: ColorScheme, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, compact: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 3.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = scheme.primary, modifier = Modifier.size(if (compact) 14.dp else 16.dp))
        if (!compact) { Spacer(Modifier.width(4.dp)); Text(label, color = scheme.primary, fontSize = 12.sp) }
    }
}
