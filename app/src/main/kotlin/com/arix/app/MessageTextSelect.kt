package com.arix.app

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arix.app.ui.rememberFrameProgress

// ============================================================
// 消息内选段复制 —— 长按菜单「选择文本」打开的整屏选字页。
//
// ## 为什么是一个独立弹窗，而不是把 SelectionContainer 加回气泡里
// 每条气泡各挂一个 SelectionContainer 正是当初被**主动删掉**的东西（见 ChatComponents 里
// "去掉每条气泡的 SelectionContainer" 那段注释）：它是 Compose 列表里最贵的「每项」组件之一，
// 小气泡一多，滚动进出反复组合它就卡——用户实测过「关玻璃、纯文本无 Markdown 仍卡」，
// 成本锁死在组合开销本身，不是玻璃也不是 Markdown。把它加回去等于把那个 bug 原样请回来。
//
// 这里的做法是把成本从「每条 × 一直存在」压到「一个 × 只在打开时」：
//  · LazyColumn 的每一项**一个字都没动**，滚动路径的组合成本与改动前完全相同；
//  · 弹窗是 Dialog，独立于消息列表的组合树，关掉即离开组合，SelectionContainer 随之销毁；
//  · 弹窗里是一整块 Text，不是列表——就算选中的是一条几万字的长消息，也只有一个可选区。
//
// 正文用**纯文本**而不是 Markdown 渲染：要选的是原文（含 `**` `#` 这些标记本身也可能是用户想复制的），
// Markdown 渲染后既选不到源码、又会把一段拆成多个可选区，跨块划选会断。换行与空行原样保留。
// ============================================================

/**
 * 整屏选字弹窗。
 *
 * @param text 消息原文。
 * @param onDismiss 关闭。
 */
@Composable
fun MessageTextSelectDialog(text: String, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    val scroll = rememberScrollState()
    // 入场淡入走帧驱动（ui/FrameMotion）：手表上系统「动画时长缩放=0」很常见，tween 会一帧跳完 = 没有过渡。
    val appear = rememberFrameProgress(key = Unit, durationMs = 180)

    Dialog(
        onDismissRequest = onDismiss,
        // 关掉平台默认宽度限制，才能真的铺满——手表上那点宽度经不起默认边距再啃一圈。
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surface,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxSize(0.96f).graphicsLayer { alpha = appear.value },
        ) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        tr("选择文本"),
                        color = scheme.onSurface,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = tr("关闭"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                Text(
                    tr("长按开始选择，拖动两端调整范围。"),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(6.dp))
                Box(Modifier.weight(1f)) {
                    // ⚠ SelectionContainer 只在这里出现一次，且只在弹窗打开期间存在。
                    // 绝不能把它挪进消息列表的每一项——那正是当初的卡顿根因（见文件头注释）。
                    SelectionContainer {
                        Text(
                            // 空消息也给一个占位，免得弹出来是一片空白让人以为坏了
                            remember(text) { text.ifBlank { tr("(空消息)") } },
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            // 行高放宽一点：手指划选时行与行之间要有落脚的余量，否则很容易多选一行
                            lineHeight = MaterialTheme.typography.bodyMedium.fontSize * 1.55f,
                            modifier = Modifier.fillMaxWidth().verticalScroll(scroll),
                        )
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 「复制全部」留着：整条复制本来就是长按菜单里的「复制」，但人已经在这个页面上了，
                    // 让他退出去再点一次是没道理的。
                    TextButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                        Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("复制全部"), color = scheme.primary)
                    }
                    TextButton(onClick = onDismiss) { Text(tr("关闭"), color = scheme.onSurfaceVariant) }
                }
            }
        }
    }
}
