package com.arix.app

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.theme.LocalXtomAccents
import com.arix.app.ui.XtomCard
import com.arix.app.ui.PageScaffold
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun CrashReportPage(
    scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context,
    reports: List<File>, onReportsChanged: () -> Unit
) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    val clipboard = LocalClipboardManager.current
    var selectedReport by remember { mutableStateOf<File?>(null) }
    var reportContent by remember { mutableStateOf("") }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    if (selectedReport != null) {
        val file = selectedReport!!
        reportContent = remember(file) { CrashHandler.readCrashReport(file) }
        PageScaffold(scroll = false) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { selectedReport = null }) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回", tint = scheme.primary)
                }
                Text(file.name, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Row {
                    // 复制到剪贴板：最省事的「发给开发者」——粘到微信/QQ 直接发
                    IconButton(onClick = {
                        clipboard.setText(AnnotatedString(reportContent))
                        android.widget.Toast.makeText(context, tr("已复制崩溃报告，可粘贴发给开发者"), android.widget.Toast.LENGTH_SHORT).show()
                    }) { Icon(Icons.Outlined.ContentCopy, contentDescription = "复制", tint = scheme.primary) }
                    // 导出/分享文件：把 .txt 文件本身发给开发者（邮件/微信/网盘等）
                    IconButton(onClick = {
                        val sendText = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_SUBJECT, "Arix 崩溃报告: ${file.name}")
                            putExtra(Intent.EXTRA_TEXT, reportContent)
                        }
                        try {
                            val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            sendText.putExtra(Intent.EXTRA_STREAM, uri)
                            sendText.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: Exception) { /* FileProvider 失败则退回纯文本分享 */ }
                        context.startActivity(Intent.createChooser(sendText, "发送崩溃报告给开发者"))
                    }) { Icon(Icons.Outlined.Share, contentDescription = "导出/分享", tint = scheme.primary) }
                    IconButton(onClick = { showDeleteConfirm = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = scheme.error)
                    }
                }
            }
            HorizontalDivider(color = scheme.surfaceContainerHighest)
            Spacer(modifier = Modifier.height(8.dp))
            Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                reportContent.lines().forEach { line ->
                    val color = when {
                        line.startsWith("---") || line.startsWith("===") -> scheme.primary
                        line.startsWith("Caused by:") || line.startsWith("FATAL") -> scheme.error
                        line.contains("Exception") || line.contains("Error") -> scheme.error
                        line.startsWith("\tat ") || line.startsWith("    at ") -> scheme.onSurfaceVariant
                        else -> scheme.onSurface
                    }
                    Text(line.ifBlank { " " }, color = color, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        if (showDeleteConfirm) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(tr("确认删除"), color = scheme.error) },
                text = { Text(tr("删除后该报告将无法恢复"), color = scheme.onSurface) },
                confirmButton = {
                    TextButton(onClick = {
                        CrashHandler.clearReport(file)
                        showDeleteConfirm = false; selectedReport = null; onReportsChanged()
                    }) { Text(tr("删除"), color = scheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirm = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) }
                },
                containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
            )
        }
    } else {
        PageScaffold(scroll = false) {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("崩溃报告 (${reports.size})", color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                if (reports.isNotEmpty()) {
                    TextButton(onClick = {
                        CrashHandler.clearAllReports(context); onReportsChanged()
                    }) { Text(tr("全部清除"), color = scheme.error, fontSize = 12.sp) }
                }
            }
            HorizontalDivider(color = scheme.surfaceContainerHighest)
            if (reports.isNotEmpty()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(tr("点开任意报告可「复制」或「导出文件」发给开发者排查。"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Spacer(modifier = Modifier.height(8.dp))

            if (reports.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(tr("暂无崩溃报告"), color = scheme.onSurfaceVariant, fontSize = 13.sp)
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(reports.size, key = { reports[it].absolutePath }) { idx ->
                        val file = reports[idx]
                        val preview = remember(file) { CrashHandler.readCrashReport(file).take(200) }
                        val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
                        XtomCard(modifier = Modifier.padding(vertical = 3.dp), onClick = { selectedReport = file }) {
                            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Outlined.Warning, contentDescription = null, tint = scheme.error, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(file.name.replace("crash_", "").replace(".txt", ""), color = scheme.error, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                Spacer(modifier = Modifier.weight(1f))
                                Text(sdf.format(Date(file.lastModified())), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(preview, color = scheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 3, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
            }
        }
    }
}
