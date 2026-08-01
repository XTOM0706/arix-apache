package com.arix.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================
// ForwardMessageDialog —— 消息「转发到…」的目标会话选择弹窗。
//
// 只管「选目标 + 触发转发」，实际的追加逻辑在 ConversationManager.forwardMessage
// （落库/来源说明前缀/敏感结果占位都在那边处理，这里不重复）。
//
// 接线说明（ChatScreen.kt 需要做的事，本文件不碰 ChatScreen.kt）：
//   1. BubbleAction 里加一个新分支，例如 `data object ForwardTo : BubbleAction`
//      （加在 ChatComponents.kt 的 sealed interface BubbleAction 里，Share/ShareImage 附近）。
//   2. 气泡长按菜单里加一项，例如放在「分享为图片」之后：
//        BubbleMenuItem(tr("转发到…"), Icons.AutoMirrored.Outlined.Forward) { onAction(bubble, BubbleAction.ForwardTo) }
//      （Icons.AutoMirrored.Outlined.Forward 已确认存在于 material-icons-extended，可直接用）
//   3. ChatScreen.kt 的 onBubbleAction 里加一个分支（Share/ShareImage 附近，约 1916 行处）：
//        BubbleAction.ForwardTo -> { contextMenuIdx = -1; forwardBubble = bubble; showForwardDialog = true }
//      （forwardBubble/showForwardDialog 是两个新的 remember { mutableStateOf(...) }，ChatScreen.kt 自己声明）
//   4. 在 ChatScreen 顶层合适位置渲染本弹窗：
//        val fb = forwardBubble
//        if (showForwardDialog && fb != null) ForwardMessageDialog(
//            scope = scope, context = context,
//            currentConversationId = <当前会话 id 变量，比如 conversationId>,
//            currentConversationTitle = convTitle,
//            role = fb.role, text = fb.text, attachments = fb.attachments,
//            onDismiss = { showForwardDialog = false },
//            onForwarded = { targetTitle ->
//                showForwardDialog = false
//                android.widget.Toast.makeText(context, String.format(tr("已转发到「%s」"), targetTitle), android.widget.Toast.LENGTH_SHORT).show()
//            },
//        )
// ============================================================

@Composable
fun ForwardMessageDialog(
    scope: CoroutineScope,
    context: android.content.Context,
    currentConversationId: Long?,
    currentConversationTitle: String,
    role: String,
    text: String,
    attachments: List<String>?,
    onDismiss: () -> Unit,
    onForwarded: (targetTitle: String) -> Unit,
) {
    val convManager = remember { ConversationManager(context) }
    // 轻量投影：和会话列表页同一条路径，只拉元数据，不会因大对话破 2MB 游标窗口。
    val activeConvs by convManager.repo.activeSummaries.collectAsState(initial = emptyList())
    val candidates = remember(activeConvs, currentConversationId) {
        activeConvs.filter { it.id != currentConversationId }
    }
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(tr("转发到…"), color = scheme.onSurface) },
        text = {
            if (candidates.isEmpty()) {
                Text(tr("没有其它对话可转发"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(candidates.size, key = { candidates[it].id }) { i ->
                        val c = candidates[i]
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    scope.launch {
                                        // 目标会话锁定不挡转发——锁定只挡删除/批量清理，追加消息不算这两类操作。
                                        val ok = convManager.forwardMessage(c.id, role, text, attachments, currentConversationTitle)
                                        withContext(Dispatchers.Main) { if (ok) onForwarded(c.title) }
                                    }
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(
                                c.title.ifBlank { tr("新对话") },
                                color = scheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            if (c.isLocked) {
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Outlined.Lock, contentDescription = tr("已锁定"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
        containerColor = scheme.surface,
        shape = RoundedCornerShape(24.dp),
    )
}
