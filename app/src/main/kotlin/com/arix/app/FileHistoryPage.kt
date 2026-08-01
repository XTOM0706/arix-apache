package com.arix.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arix.app.theme.LocalXtomAccents
import com.arix.tool.FileChange
import com.arix.tool.FileHistory
import kotlinx.coroutines.launch
import com.arix.app.ui.topChromeGapHeight

// ============================================================
// 文件改动历史 —— 让 AI 改文件这件事不再是黑箱：
// 改了哪些文件、改了什么（diff）、以及一键退回改动前。
// 数据来自 FileHistory（快照存在工作区之外，AI 碰不到）。
// ============================================================

@Composable
fun FileHistoryPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    val scope = rememberCoroutineScope()

    var records by remember { mutableStateOf<List<FileChange>>(emptyList()) }
    var used by remember { mutableStateOf(0L) }
    var loading by remember { mutableStateOf(true) }
    var bump by remember { mutableStateOf(0) }
    var expandedId by remember { mutableStateOf<String?>(null) }
    // 待确认的回滚：first=记录，second=提示语（null 表示普通二次确认，非冲突）
    var pendingRevert by remember { mutableStateOf<Pair<FileChange, String?>?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(bump) {
        loading = true
        records = FileHistory.all(context)
        used = FileHistory.usedBytes(context)
        loading = false
    }

    Column(Modifier.fillMaxSize().padding(12.dp)) {
        Spacer(Modifier.topChromeGapHeight())
        Text(
            tr("AI 改过的文件都记在这里，可以看 diff、也可以退回改动前。"),
            color = scheme.onSurfaceVariant, style = type.bodySmall,
            modifier = Modifier.padding(bottom = 4.dp)
        )
        if (records.isNotEmpty()) Text(
            "${records.size} " + tr("条记录 · 快照占用 ") + fmtSize(used),
            color = scheme.onSurfaceVariant, style = type.labelSmall,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        when {
            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = scheme.primary)
            }
            records.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Outlined.History, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(40.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(tr("还没有文件改动"), color = scheme.onSurface, style = type.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr("等 AI 写、改、删或移动了工作目录里的文件，这里就会留下可回滚的记录。"),
                        color = scheme.onSurfaceVariant, style = type.bodySmall
                    )
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(records.size, key = { records[it].id }) { idx ->
                    val rec = records[idx]
                    ChangeCard(
                        rec = rec,
                        expanded = expandedId == rec.id,
                        onToggle = { expandedId = if (expandedId == rec.id) null else rec.id },
                        onRevert = { pendingRevert = rec to null },
                    )
                }
            }
        }
    }

    // 二次确认：普通回滚问一遍；检测到冲突时把警告原文摆在这里，让用户知道自己要覆盖什么
    pendingRevert?.let { (rec, warn) ->
        val conflict = warn != null
        AlertDialog(
            onDismissRequest = { pendingRevert = null },
            icon = { Icon(if (conflict) Icons.Outlined.Warning else Icons.AutoMirrored.Outlined.Undo, null, tint = if (conflict) LocalXtomAccents.current.warning else scheme.primary) },
            title = { Text(if (conflict) tr("文件已被改过") else tr("回滚这次改动？"), color = scheme.onSurface, style = MaterialTheme.typography.titleSmall) },
            text = {
                Text(
                    warn ?: (tr("将把「") + rec.path + tr("」恢复到这次改动之前的状态。回滚本身也会记一条历史，可以再撤销。")),
                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val target = rec
                    val already = conflict
                    pendingRevert = null
                    scope.launch {
                        val r = FileHistory.revert(context, target.id, confirmed = already)
                        if (r.needsConfirm) pendingRevert = target to r.message
                        else { toast = r.message; bump++ }
                    }
                }) { Text(if (conflict) tr("仍然回滚") else tr("回滚"), color = if (conflict) scheme.error else scheme.primary) }
            },
            dismissButton = { TextButton(onClick = { pendingRevert = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )
    }

    toast?.let { msg ->
        AlertDialog(
            onDismissRequest = { toast = null },
            text = { Text(msg, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) },
            confirmButton = { TextButton(onClick = { toast = null }) { Text(tr("知道了"), color = scheme.primary) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )
    }
}

@Composable
private fun ChangeCard(rec: FileChange, expanded: Boolean, onToggle: () -> Unit, onRevert: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    Surface(
        shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.clickable { onToggle() }.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(opIcon(rec.op), null, tint = opTint(rec.op), modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        rec.path, color = scheme.onSurface, style = type.bodyMedium,
                        fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        opLabel(rec.op) + " · " + fmtTime(rec.time),
                        color = scheme.onSurfaceVariant, style = type.labelSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp)
                )
            }

            // 不可回滚的记录必须当面说清原因，不能让用户点了才发现退不回来
            rec.snapshotSkipped?.takeIf { rec.op != FileHistory.OP_MOVE }?.let { why ->
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Warning, null, tint = LocalXtomAccents.current.warning, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(why, color = LocalXtomAccents.current.warning, style = type.labelSmall)
                }
            }

            if (expanded) {
                Spacer(Modifier.height(8.dp))
                DiffBlock(rec.diffPreview)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        fmtSize(rec.beforeSize) + " → " + fmtSize(rec.afterSize),
                        color = scheme.onSurfaceVariant, style = type.labelSmall, modifier = Modifier.weight(1f)
                    )
                    if (rec.revertable) TextButton(onClick = onRevert) {
                        Icon(Icons.AutoMirrored.Outlined.Undo, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(tr("回滚"), color = scheme.primary, style = type.labelLarge)
                    } else Text(tr("不可回滚"), color = scheme.onSurfaceVariant, style = type.labelSmall)
                }
            }
        }
    }
}

/** diff 渲染：+ 绿 / - 红，颜色全走令牌（success / error），长行横向滚动不撑破手表窄屏。 */
@Composable
private fun DiffBlock(diff: String) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    val type = MaterialTheme.typography
    Surface(shape = RoundedCornerShape(10.dp), color = scheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp).horizontalScroll(rememberScrollState())) {
            if (diff.isBlank()) Text(tr("（无 diff）"), color = scheme.onSurfaceVariant, style = type.labelSmall)
            else diff.lineSequence().take(200).forEach { line ->
                val color = when {
                    line.startsWith("+") -> accents.success
                    line.startsWith("-") -> scheme.error
                    line.startsWith("@@") -> accents.info
                    else -> scheme.onSurfaceVariant
                }
                Text(line, color = color, style = type.labelSmall, maxLines = 1)
            }
        }
    }
}

private fun opIcon(op: String): ImageVector = when (op) {
    FileHistory.OP_DELETE -> Icons.Outlined.Delete
    FileHistory.OP_MOVE -> Icons.AutoMirrored.Outlined.DriveFileMove
    FileHistory.OP_REVERT -> Icons.AutoMirrored.Outlined.Undo
    FileHistory.OP_WRITE -> Icons.Outlined.Add
    else -> Icons.Outlined.Edit
}

@Composable private fun opTint(op: String) = when (op) {
    FileHistory.OP_DELETE -> MaterialTheme.colorScheme.error
    FileHistory.OP_REVERT -> LocalXtomAccents.current.info
    else -> MaterialTheme.colorScheme.primary
}

private fun opLabel(op: String) = when (op) {
    FileHistory.OP_WRITE -> tr("写入")
    FileHistory.OP_EDIT -> tr("编辑")
    FileHistory.OP_DELETE -> tr("删除")
    FileHistory.OP_MOVE -> tr("移动")
    FileHistory.OP_REVERT -> tr("回滚")
    else -> op
}

private fun fmtTime(t: Long): String =
    java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(t))

private fun fmtSize(b: Long): String = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "%.1f MB".format(b.toDouble() / (1024 * 1024))
}
