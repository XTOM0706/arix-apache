package com.arix.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.ui.XtomCard
import com.arix.app.ui.XtomField
import com.arix.app.ui.PageScaffold
import kotlinx.coroutines.launch

/**
 * 「项目」页（GPT 式 Projects）：把相关对话归组成命名项目 + 给项目一条项目指令。
 * 归属复用对话的 folder（项目名==文件夹名），项目列表 = 有对话的文件夹名 ∪ [ProjectStore] 里的项目名。
 */
@Composable
fun ProjectsPage(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    onOpenConversation: (Long) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val convManager = remember { ConversationManager(context) }
    val activeConvs by convManager.repo.activeSummaries.collectAsState(initial = emptyList())
    var tick by remember { mutableStateOf(0) }

    // 项目名 = store 里定义的 ∪ 对话里正在用的 folder；按对话数、再按名字排
    val projectNames = remember(activeConvs, tick) {
        val fromStore = ProjectStore.list(context).map { it.name }
        val fromConvs = activeConvs.map { it.folder }.filter { it.isNotBlank() }.distinct()
        (fromStore + fromConvs).distinct().sortedWith(
            compareByDescending<String> { n -> activeConvs.count { it.folder == n } }.thenBy { it }
        )
    }

    var creating by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var newInstruction by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf<String?>(null) }   // 展开的项目名
    var renaming by remember { mutableStateOf<String?>(null) }
    var renameText by remember { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }

    PageScaffold(scroll = false) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Dashboard, null, tint = scheme.primary, modifier = Modifier.size(20.dp))
            Text(tr(" 项目"), color = scheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { newName = ""; newInstruction = ""; creating = true }) { Text(tr("+ 新建"), color = scheme.primary, fontSize = 13.sp) }
        }
        Text(tr("把相关对话归组成项目；给项目写一条「项目指令」，项目内每次对话都会自动带上。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))

        if (projectNames.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                Text(tr("还没有项目。点「+ 新建」，或在对话管理里把对话「移动」到某个分组。"), color = scheme.onSurfaceVariant, fontSize = 12.sp)
            }
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(projectNames, key = { it }) { name ->
                    val members = activeConvs.filter { it.folder == name }
                    val instruction = ProjectStore.instructionFor(context, name)
                    val isOpen = expanded == name
                    XtomCard(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(12.dp))
                        .clickable { expanded = if (isOpen) null else name }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                            if (instruction.isNotBlank()) Text(tr("有指令"), color = scheme.tertiary, fontSize = 10.sp, modifier = Modifier.padding(end = 8.dp))
                            Text("${members.size} " + tr("对话"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                        }
                        if (isOpen) {
                            Spacer(Modifier.height(8.dp))
                            var instr by remember(name) { mutableStateOf(instruction) }
                            XtomField(value = instr, onValueChange = { instr = it }, label = tr("项目指令（可选）"), placeholder = tr("如：用简体中文、优先给可执行步骤、这个项目是关于……"), singleLine = false, maxLines = 4, modifier = Modifier.fillMaxWidth())
                            Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                                TextButton(onClick = { scope.launch { ProjectStore.upsert(context, name, instr, System.currentTimeMillis()); tick++; android.widget.Toast.makeText(context, tr("已保存项目指令"), android.widget.Toast.LENGTH_SHORT).show() } }) { Text(tr("保存指令"), color = scheme.primary, fontSize = 12.sp) }
                                Spacer(Modifier.weight(1f))
                                TextButton(onClick = { renameText = name; renaming = name }) { Text(tr("重命名"), color = scheme.onSurfaceVariant, fontSize = 12.sp) }
                                TextButton(onClick = { deleting = name }) { Text(tr("解散"), color = scheme.error, fontSize = 12.sp) }
                            }
                            Button(onClick = {
                                scope.launch {
                                    val id = convManager.create(title = "新对话")
                                    convManager.repo.setFolder(id, name)
                                    ProjectStore.upsert(context, name, instr, System.currentTimeMillis())  // 确保项目登记(空项目也留存)
                                    onOpenConversation(id)
                                }
                            }, shape = RoundedCornerShape(50), modifier = Modifier.padding(top = 4.dp)) { Text(tr("＋ 在本项目新对话"), fontSize = 12.sp) }
                            if (members.isEmpty()) Text(tr("暂无对话。新建一条，或去对话管理把已有对话「移动」进来。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                            else members.forEach { c ->
                                Text("• ${c.title.ifBlank { tr("未命名对话") }}", color = scheme.onSurface, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clickable { onOpenConversation(c.id) })
                            }
                        }
                    }
                }
            }
        }
    }

    // 新建项目
    if (creating) {
        AlertDialog(onDismissRequest = { creating = false },
            title = { Text(tr("新建项目")) },
            text = {
                Column {
                    XtomField(value = newName, onValueChange = { newName = it }, label = tr("项目名"), placeholder = tr("如：装修 / 论文 / 减脂"), singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (newName.trim().isNotBlank() && newName.trim() in projectNames)
                        Text(tr("已有同名项目，换个名字（避免覆盖它的指令）"), color = scheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(8.dp))
                    XtomField(value = newInstruction, onValueChange = { newInstruction = it }, label = tr("项目指令（可选）"), placeholder = tr("项目内每次对话自动带上的说明"), singleLine = false, maxLines = 4, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(enabled = newName.isNotBlank() && newName.trim() !in projectNames, onClick = {
                ProjectStore.upsert(context, newName.trim(), newInstruction, System.currentTimeMillis()); tick++; expanded = newName.trim(); creating = false
            }) { Text(tr("创建")) } },
            dismissButton = { TextButton(onClick = { creating = false }) { Text(tr("取消")) } })
    }

    // 重命名：改 store + 把成员对话的 folder 一并迁移
    renaming?.let { old ->
        AlertDialog(onDismissRequest = { renaming = null },
            title = { Text(tr("重命名项目")) },
            text = { Column {
                XtomField(value = renameText, onValueChange = { renameText = it }, label = tr("新名字"), singleLine = true, modifier = Modifier.fillMaxWidth())
                if (renameText.trim().isNotBlank() && renameText.trim() != old && renameText.trim() in projectNames)
                    Text(tr("已有同名项目，换个名字（避免与它合并、覆盖指令）"), color = scheme.error, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            } },
            confirmButton = { TextButton(enabled = renameText.isNotBlank() && renameText.trim() != old && renameText.trim() !in projectNames, onClick = {
                val nw = renameText.trim()
                scope.launch {
                    activeConvs.filter { it.folder == old }.forEach { convManager.repo.setFolder(it.id, nw) }
                    ProjectStore.rename(context, old, nw); tick++
                    if (expanded == old) expanded = nw
                }
                renaming = null
            }) { Text(tr("确定")) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(tr("取消")) } })
    }

    // 解散：删项目元数据 + 成员对话移出分组（对话本身不删）
    deleting?.let { name ->
        AlertDialog(onDismissRequest = { deleting = null },
            title = { Text(tr("解散项目") + "「$name」？") },
            text = { Text(tr("项目内的对话不会删除，只会移出该分组。项目指令会被清除。")) },
            confirmButton = { TextButton(onClick = {
                scope.launch {
                    activeConvs.filter { it.folder == name }.forEach { convManager.repo.setFolder(it.id, "") }
                    ProjectStore.delete(context, name); tick++
                    if (expanded == name) expanded = null
                }
                deleting = null
            }) { Text(tr("解散"), color = scheme.error) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(tr("取消")) } })
    }
}
