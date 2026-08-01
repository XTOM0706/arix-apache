package com.arix.app

import android.content.Context
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.arix.app.ui.SettingsHint
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.SettingsToggle
import com.arix.app.ui.XtomField
import com.arix.app.ui.topChromeGapHeight
import com.arix.tool.FuzzyMatch
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
// 运行日志查看器 —— 看 [AppLog] 的内存环形缓冲。
//
// 崩溃有崩溃报告页；这一页管的是「没崩但不对劲」：请求失败、工具异常、备份失败、MCP 重连。
// 内容只在内存里，进程一死就没了；复制/分享是用户手点的动作，不会自己往外发。
// ============================================================
@Composable fun AppLogPage(context: Context) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val opaque = remember { PageBackgroundPrefs.get(context, "app_log") != null }

    var recording by remember { mutableStateOf(AppLog.enabled(context)) }
    var query by remember { mutableStateOf("") }
    var minLevel by remember { mutableStateOf(AppLog.Level.D) }
    var tick by remember { mutableStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    // 轻量自动刷新：环形缓冲没有可观察的流，这里每秒重取一次快照。1 秒对一个诊断页足够，
    // 也不会像逐条监听那样让写日志的后台线程反过来驱动 UI 重组。
    LaunchedEffect(recording) {
        while (recording) {
            delay(1000)
            tick++
        }
    }

    val entries = remember(tick, query, minLevel) {
        val all = AppLog.recent()
        val leveled = when (minLevel) {
            AppLog.Level.D -> all
            AppLog.Level.W -> all.filter { it.level != AppLog.Level.D }
            AppLog.Level.E -> all.filter { it.level == AppLog.Level.E }
        }
        // 模糊匹配：打错一个字/只记得半个词也搜得到。空查询直接给全量，不排序。
        if (query.isBlank()) leveled
        else FuzzyMatch.rankBy(query, leveled, fields = { listOf(it.tag, it.msg) }).map { it.item }
    }

    val sdf = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.topChromeGapHeight())

        SettingsSection(tr("记录"), Icons.Outlined.BugReport, translucent = !opaque) {
            SettingsToggle(
                icon = Icons.Outlined.BugReport,
                title = tr("记录运行日志"),
                subtitle = String.format(
                    tr("只存在内存里的最近 %d 条，不写文件、不上传，退出 App 即清空。关掉会立即倒掉已有记录。"),
                    AppLog.CAPACITY,
                ),
                checked = recording,
                onCheckedChange = { recording = it; AppLog.setEnabled(context, it); tick++ },
            )
            SettingsHint(tr("日志只记「发生了什么」：动作、结果、错误类型。密钥、对话正文、位置和健康数据一律不记。"))
        }

        // 工具条：过滤 + 搜索 + 复制/分享/清空
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.FilterList, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
                listOf(
                    AppLog.Level.D to tr("全部"),
                    AppLog.Level.W to tr("警告以上"),
                    AppLog.Level.E to tr("仅错误"),
                ).forEach { (lv, label) ->
                    val on = lv == minLevel
                    Surface(
                        onClick = { minLevel = lv },
                        shape = RoundedCornerShape(50),
                        color = if (on) scheme.primary else scheme.surfaceContainerHighest,
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) scheme.onPrimary else scheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        )
                    }
                }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            XtomField(
                value = query,
                onValueChange = { query = it },
                placeholder = tr("搜索日志…"),
                singleLine = true,
                leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onSurfaceVariant) },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                val text = AppLog.dump()
                if (text.isNotBlank()) {
                    clipboard.setText(AnnotatedString(text))
                    android.widget.Toast.makeText(context, tr("已复制运行日志"), android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Icon(Icons.Outlined.ContentCopy, contentDescription = tr("复制"), tint = scheme.primary) }
            IconButton(onClick = {
                val text = AppLog.dump()
                if (text.isNotBlank()) {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Arix 运行日志")
                        putExtra(Intent.EXTRA_TEXT, text)
                    }
                    runCatching { context.startActivity(Intent.createChooser(send, tr("分享运行日志")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                }
            }) { Icon(Icons.Outlined.Share, contentDescription = tr("分享"), tint = scheme.primary) }
            IconButton(onClick = { confirmClear = true }) {
                Icon(Icons.Outlined.Delete, contentDescription = tr("清空"), tint = scheme.error)
            }
        }

        if (entries.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (!recording) tr("记录已关闭。打开上面的开关后，之后发生的事情才会记下来。")
                    else if (query.isNotBlank()) tr("没有匹配的日志")
                    else tr("暂无日志。出问题时再回来看，这里会有现场。"),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(entries, key = { it.seq }, contentType = { "log" }) { e ->
                    val levelColor = when (e.level) {
                        AppLog.Level.E -> scheme.error
                        AppLog.Level.W -> scheme.tertiary
                        AppLog.Level.D -> scheme.onSurfaceVariant
                    }
                    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                        // 手表窄屏：时间/级别/tag 一行，正文另起一行，绝不横着挤三列
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(sdf.format(Date(e.ts)), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(6.dp))
                            Text(e.level.name, color = levelColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Text(e.tag, color = scheme.primary, style = MaterialTheme.typography.labelSmall)
                        }
                        Text(e.msg, color = scheme.onSurface, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                    }
                }
                item(key = "tail") { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(tr("清空运行日志"), color = scheme.onSurface) },
            text = { Text(tr("将删除当前内存里的全部日志记录。日志本来就不落盘，清空只是提前倒掉。"), color = scheme.onSurfaceVariant) },
            confirmButton = {
                TextButton(onClick = { AppLog.clear(); confirmClear = false; tick++ }) { Text(tr("清空"), color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface,
            shape = RoundedCornerShape(24.dp),
        )
    }
}
