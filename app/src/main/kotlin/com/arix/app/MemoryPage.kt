package com.arix.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arix.app.ui.topChromeGapHeight
import com.arix.app.ui.XtomField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 记忆管理页 —— Apache-2.0 精简版。
 *
 * 相比原版删掉了：图谱视图/连线模式、关联面板、救援/状态卡/语义索引/自动整理（MemoryTidy/MemorySalvage）、
 * 多选归档、角色卡筛选（随陪伴砍掉角色卡体系）。
 * 保留：搜索 / 类型·文件夹·标签筛选 / 列表（置顶、删除）/ 编辑器（标题·内容·类型·重要度·置顶·文件夹·标签）。
 */
@Composable
fun MemoryPage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    val memoryManager = remember { MemoryManager(context) }
    val memories by memoryManager.allMemories.collectAsState(initial = emptyList())
    val allTags by memoryManager.allTags.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    var editor by remember { mutableStateOf<MemoryEditorState?>(null) }
    val scheme = MaterialTheme.colorScheme
    val accents = com.arix.app.theme.LocalXtomAccents.current
    val translucent = remember { PageBackgroundPrefs.get(context, "memory") == null }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    var folderFilter by remember { mutableStateOf<String?>(null) }  // null=全部；""=未分组；否则=某文件夹名
    var tagFilter by remember { mutableStateOf<String?>(null) }

    val typeLabels = listOf(
        "preference" to tr("偏好"), "fact" to tr("事实"), "event" to tr("事件"), "relation" to tr("关系"), "todo" to tr("待办"),
        "lesson" to tr("教训"), "environment" to tr("环境"), "convention" to tr("约定"),
    )
    fun typeLabel(t: String) = typeLabels.firstOrNull { it.first == t }?.second ?: tr("事实")

    fun openEditor(m: MemoryEntity?) {
        editor = if (m == null) MemoryEditorState()
        else MemoryEditorState(id = m.id, title = m.title, content = m.content, type = m.type,
            importance = m.importance, pinned = m.pinned, folder = m.folder)
    }

    val search by produceState(MemorySearchState(), searchQuery, memories) {
        val q = searchQuery
        if (q.isBlank()) return@produceState
        value = withContext(Dispatchers.Default) { MemorySearchState(q, memoryManager.search(q)) }
    }
    val searching = searchQuery.isNotBlank() && search.query != searchQuery
    val baseMemories = if (searchQuery.isBlank()) memories else search.results
    val folders = remember(memories) { memories.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted() }

    val displayedMemories = remember(baseMemories, typeFilter, folderFilter, tagFilter) {
        baseMemories
            .filter { typeFilter == null || it.type == typeFilter }
            .filter { folderFilter == null || it.folder == folderFilter }
            .filter { tagFilter == null || tagFilter in it.tags }
            .sortedWith(compareByDescending<MemoryEntity> { it.pinned }.thenByDescending { it.importance }.thenByDescending { it.updatedAt })
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Spacer(Modifier.topChromeGapHeight())
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { openEditor(null) }) {
                Icon(Icons.Outlined.Add, contentDescription = tr("新建记忆"))
            }
            XtomField(
                value = searchQuery, onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = tr("搜索记忆..."),
                leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
            )
            if (searchQuery.isNotBlank()) IconButton(onClick = { searchQuery = "" }) {
                Icon(Icons.Outlined.Close, contentDescription = tr("清除"), tint = scheme.onSurfaceVariant)
            }
        }
        MemFilterRow(icon = Icons.AutoMirrored.Outlined.Label) {
            MemFilterChip(tr("全部"), typeFilter == null) { typeFilter = null }
            typeLabels.forEach { (t, l) -> MemFilterChip(l, typeFilter == t) { typeFilter = t } }
        }
        if (allTags.isNotEmpty()) {
            MemFilterRow(icon = Icons.Outlined.Sell) {
                MemFilterChip(tr("全部标签"), tagFilter == null) { tagFilter = null }
                allTags.forEach { t -> MemFilterChip(t, tagFilter == t) { tagFilter = if (tagFilter == t) null else t } }
            }
        }
        if (folders.isNotEmpty()) {
            MemFilterRow(icon = Icons.Outlined.Folder) {
                MemFilterChip(tr("全部文件夹"), folderFilter == null) { folderFilter = null }
                MemFilterChip(tr("未分组"), folderFilter == "") { folderFilter = "" }
                folders.forEach { f -> MemFilterChip(f, folderFilter == f) { folderFilter = f } }
            }
        }

        if (displayedMemories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ViewList, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        when {
                            searching -> tr("搜索中…")
                            searchQuery.isNotBlank() -> tr("未找到匹配的记忆")
                            else -> tr("暂无记忆")
                        },
                        color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge,
                    )
                    if (searchQuery.isBlank()) Text(tr("对话中你说的重要信息，AI 会自动记到这里"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        } else {
            val dateFmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(displayedMemories.size, key = { displayedMemories[it].id }, contentType = { "memory" }) { idx ->
                    val mem = displayedMemories[idx]
                    val srcLabel = when (mem.source) { "ai_tool" -> tr("AI主动"); "auto_extract" -> tr("AI抽取"); else -> tr("手动") }
                    val memTrailing: (@Composable () -> Unit) = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { scope.launch { memoryManager.setPinned(mem.id, !mem.pinned) } }) {
                                Icon(
                                    if (mem.pinned) Icons.Outlined.Star else Icons.Outlined.StarBorder,
                                    contentDescription = if (mem.pinned) tr("取消置顶") else tr("置顶"),
                                    tint = if (mem.pinned) scheme.primary else scheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { scope.launch { memoryManager.delete(mem.id) } }) {
                                Icon(Icons.Outlined.Delete, contentDescription = tr("删除"), tint = scheme.error)
                            }
                        }
                    }
                    Surface(
                        onClick = { openEditor(mem) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = memGroupShape(idx, displayedMemories.size),
                        color = scheme.surfaceContainer,
                    ) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = { Icon(memTypeIcon(mem.type), contentDescription = typeLabel(mem.type), tint = scheme.primary) },
                            headlineContent = { Text(mem.title, color = scheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 2) },
                            supportingContent = {
                                Column {
                                    Text(mem.content.take(140), color = scheme.onSurfaceVariant, maxLines = 2)
                                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                        Text(
                                            tr("%s · %s · 重要度 %.1f").format(srcLabel, dateFmt.format(Date(mem.createdAt)), mem.importance),
                                            color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 2, modifier = Modifier.weight(1f),
                                        )
                                        if (mem.folder.isNotBlank()) {
                                            Icon(Icons.Outlined.Folder, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(12.dp))
                                            Spacer(Modifier.width(2.dp))
                                            Text(mem.folder, color = scheme.tertiary, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                        }
                                    }
                                }
                            },
                            trailingContent = memTrailing,
                        )
                    }
                }
            }
        }
    }

    editor?.let { ed ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editor = null },
            title = { Text(if (ed.id == null) tr("新建记忆") else tr("编辑记忆"), color = scheme.onSurface) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    XtomField(value = ed.title, onValueChange = { editor = ed.copy(title = it) },
                        label = tr("标题"), modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(6.dp))
                    XtomField(value = ed.content, onValueChange = { editor = ed.copy(content = it) },
                        label = tr("内容"), modifier = Modifier.fillMaxWidth(), singleLine = false, minLines = 4, maxLines = 5)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(tr("类型"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    MemFilterRow(icon = null) {
                        typeLabels.forEach { (t, l) -> MemFilterChip(l, ed.type == t) { editor = ed.copy(type = t) } }
                    }
                    Text(tr("重要度 %.1f").format(ed.importance), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Slider(value = ed.importance, onValueChange = { editor = ed.copy(importance = it) }, valueRange = 0f..1f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = ed.pinned, onCheckedChange = { editor = ed.copy(pinned = it) })
                        Text(tr("置顶（始终注入）"), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(tr("文件夹"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    MemFilterRow(icon = null) {
                        MemFilterChip(tr("未分组"), ed.folder.isBlank()) { editor = ed.copy(folder = "") }
                        folders.forEach { f -> MemFilterChip(f, ed.folder == f) { editor = ed.copy(folder = f) } }
                    }
                    XtomField(
                        value = ed.folder, onValueChange = { editor = ed.copy(folder = it) },
                        placeholder = tr("或直接输入文件夹名"), singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(tr("标签"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    if (ed.tags.isNotEmpty()) FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ed.tags.forEach { t ->
                            MemTagChip(t) { editor = ed.copy(tags = ed.tags - t, tagsLoaded = true) }
                        }
                    }
                    var newTag by remember(ed.id) { mutableStateOf("") }
                    val addTag = {
                        val t = newTag.trim()
                        if (t.isNotBlank() && t !in ed.tags) editor = ed.copy(tags = ed.tags + t, tagsLoaded = true)
                        newTag = ""
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        XtomField(
                            value = newTag, onValueChange = { newTag = it }, placeholder = tr("新标签"),
                            singleLine = true, modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = addTag) { Icon(Icons.Outlined.Add, contentDescription = tr("添加标签"), tint = scheme.primary) }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val eid = ed.id
                        val tagsToSave = if (ed.tagsLoaded) ed.tags else null
                        if (eid != null) {
                            memoryManager.update(eid, ed.title, ed.content, importance = ed.importance, tags = tagsToSave)
                            memoryManager.setType(eid, ed.type); memoryManager.setPinned(eid, ed.pinned)
                            memoryManager.setFolder(eid, ed.folder)
                        } else {
                            val nid = memoryManager.add(ed.title, ed.content, importance = ed.importance, type = ed.type,
                                pinned = ed.pinned, tags = ed.tags)
                            if (ed.folder.isNotBlank()) memoryManager.setFolder(nid, ed.folder)
                        }
                    }
                    editor = null
                }) { Text(tr("保存"), color = scheme.primary) }
            },
            dismissButton = {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (ed.id != null) TextButton(onClick = { scope.launch { memoryManager.delete(ed.id) }; editor = null }) { Text(tr("删除"), color = scheme.error) }
                    TextButton(onClick = { editor = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) }
                }
            },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )
    }
}

// ============================================================
// 列表侧的小零件
// ============================================================

/** 编辑器状态。null=编辑器关闭。 */
@Immutable
private data class MemoryEditorState(
    val id: Long? = null,
    val title: String = "",
    val content: String = "",
    val type: String = "fact",
    val importance: Float = 0.5f,
    val pinned: Boolean = false,
    val folder: String = "",
    val tags: List<String> = emptyList(),
    val tagsLoaded: Boolean = false,
)

/** 搜索结果 + 它对应的查询词。 */
@Immutable
private data class MemorySearchState(
    val query: String = "",
    val results: List<MemoryEntity> = emptyList(),
)

/** 记忆类型 → Material 矢量图标。 */
private fun memTypeIcon(type: String): ImageVector = when (type) {
    "preference" -> Icons.Outlined.Favorite
    "event" -> Icons.Outlined.Event
    "relation" -> Icons.Outlined.People
    "todo" -> Icons.Outlined.CheckCircle
    "lesson" -> Icons.Outlined.WarningAmber
    "environment" -> Icons.Outlined.Devices
    "convention" -> Icons.Outlined.Gavel
    else -> Icons.Outlined.Lightbulb
}

/** 分组列表的圆角：整组像一整块。 */
private fun memGroupShape(index: Int, count: Int): RoundedCornerShape {
    val big = 20.dp
    val small = 4.dp
    val top = if (index == 0) big else small
    val bottom = if (index == count - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** 一排横滑的筛选 chip，排首用图标点明这排在筛什么。 */
@Composable
private fun MemFilterRow(icon: ImageVector?, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Row(
            modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

/** 真 FilterChip（选中打勾）。 */
@Composable
private fun MemFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (!selected) null else {
            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        },
    )
}

/** 标签芯片：点一下删掉。 */
@Composable
private fun MemTagChip(name: String, onRemove: () -> Unit) {
    FilterChip(
        selected = true,
        onClick = onRemove,
        label = { Text(name) },
        leadingIcon = { Icon(Icons.Outlined.Sell, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
        trailingIcon = { Icon(Icons.Outlined.Close, contentDescription = tr("移除标签"), modifier = Modifier.size(FilterChipDefaults.IconSize)) },
    )
}
