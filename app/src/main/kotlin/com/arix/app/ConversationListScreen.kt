package com.arix.app

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.LockOpen
import androidx.compose.material.icons.outlined.MoreVert
// 置顶态用实心/空心 PushPin 区分（已置顶=实心、未置顶=空心）——这是**状态**区分，不是普通图标，
// 保留实心。Filled/Outlined 同名不同接收者，Kotlin 按接收者消歧，可同时 import。
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arix.app.ui.XtomField
import com.arix.app.ui.PageScaffold
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
// ConversationListScreen —— 对话管理页（活跃/已归档，重命名/置顶/归档/删除）
//
// 观感规矩同 MemoryPage / SettingsKit：只用主题令牌不写死颜色、字号一律走排版尺度
// （不写 10sp/11sp）、图标一律 Material 矢量图（不用 emoji/文字符号）、带文字的东西不定高、
// 描述性文字不设 maxLines=1（33 种语言，德/法/俄的译文比中文长得多）。
// ============================================================

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationListScreen(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context, onSelectConversation: (Long) -> Unit) {
    val convManager = remember { ConversationManager(context) }
    val cardManager = remember { CharacterCardManager(context) }
    // 轻量投影流：只拉元数据，不含 messagesJson/branchesJson。大对话不会撑爆 2MB 游标窗口（曾致启动崩溃）。
    val activeConvs by convManager.repo.activeSummaries.collectAsState(initial = emptyList())
    val archivedConvs by convManager.repo.archivedSummaries.collectAsState(initial = emptyList())
    val cards by cardManager.allCards.collectAsState(initial = emptyList())
    var showArchived by remember { mutableStateOf(false) }
    var renamingId by remember { mutableStateOf<Long?>(null) }
    var renameText by remember { mutableStateOf("") }
    var menuConvId by remember { mutableStateOf<Long?>(null) }             // 打开溢出菜单(⋮)的对话；低频动作全收在里面
    var folderFilter by remember { mutableStateOf<String?>(null) }        // null=全部；"__none__"=未分组；否则具体文件夹名
    var movingConv by remember { mutableStateOf<Pair<Long, String>?>(null) } // (对话id, 当前文件夹) 打开移动弹窗
    var moveFolderText by remember { mutableStateOf("") }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    val baseList = if (showArchived) archivedConvs else activeConvs
    // 派生集合记忆化（同聊天页「别在重组体里重算」）：folders/hasUnfiled 只随 baseList 变，
    // 不 remember 的话每次任意 state 变（切多选/开菜单）都要 map+filter+distinct+sorted 整份重扫。
    val folders = remember(baseList) { baseList.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted() }
    val hasUnfiled = remember(baseList) { baseList.any { it.folder.isBlank() } }
    // 角色卡 id→卡 映射记忆化：原来每个可见行 cards.find{} 是 O(卡数) 线性扫，行数×卡数每帧白算；
    // 换 associateBy 后行内查为 O(1)（对齐聊天页「每项非记忆化成本」治法）。
    val cardsById = remember(cards) { cards.associateBy { it.id } }
    // 选中的文件夹被清空后回退到「全部」，避免筛出空列表卡住
    if (folderFilter != null && folderFilter != "__none__" && folderFilter !in folders) folderFilter = null
    val conversations = remember(baseList, folderFilter) {
        when (folderFilter) {
            null -> baseList
            "__none__" -> baseList.filter { it.folder.isBlank() }
            else -> baseList.filter { it.folder == folderFilter }
        }
    }
    val scheme = MaterialTheme.colorScheme
    // 当前语言 → 货币(符号, 相对USD粗略汇率)，用于会话卡片上的花费展示随语言本地化。
    val lang by I18n.lang.collectAsState()
    val (curSym, curRate) = remember(lang) { currencyFor(lang.code) }

    // 导出对话：把内部格式「转化」成 Markdown / 纯文本 / JSON 后存文件并拉起系统分享。
    fun exportConv(convId: Long, title: String, kind: String) {
        scope.launch {
            val text = when (kind) {
                "md" -> com.arix.tool.ImportExport.exportConversationMarkdown(convId, context)
                "txt" -> com.arix.tool.ImportExport.exportConversationText(convId, context)
                else -> com.arix.tool.ImportExport.exportConversation(convId, context)
            }
            if (text == null) { android.widget.Toast.makeText(context, tr("导出失败"), android.widget.Toast.LENGTH_SHORT).show(); return@launch }
            val mime = when (kind) { "md" -> "text/markdown"; "txt" -> "text/plain"; else -> "application/json" }
            // 文件名净化用**黑名单**（只拦文件系统真不允许的字符），不能用白名单：
            // 原来是 [^\w一-龥-]，而 Java 的 \w 默认只认 ASCII，
            // 于是「Привет чат」「مرحبا」这类标题被整串洗成下划线，导出文件全叫 ______2026….md。
            // 中文能幸免只因为白名单里单独列了 一-龥——等于只照顾了中英文。
            val safe = title.ifBlank { tr("对话") }
                .replace(Regex("""[\\/:*?"<>|\x00-\x1F]"""), "_")   // Windows/Android 通用非法字符 + 控制字符
                .trim().trimEnd('.')                                  // 结尾的点/空格在部分文件系统上会被吞
                .take(60)                                             // 留出时间戳与扩展名的余量，防超长路径
                .ifBlank { "_" }
            val name = "${safe}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.$kind"
            withContext(Dispatchers.IO) {
                val f = com.arix.tool.ImportExport.saveToFile(context, text, name)
                withContext(Dispatchers.Main) { runCatching { com.arix.tool.ImportExport.shareFile(context, f, mime) }.onFailure { android.widget.Toast.makeText(context, tr("分享失败：%s").format(it.message ?: ""), android.widget.Toast.LENGTH_SHORT).show() } }
            }
        }
    }

    PageScaffold(scroll = false) {
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // 手搓的两段 Surface pill → 真 SingleChoiceSegmentedButtonRow（选中勾、水波纹、圆角分段都是组件给的）。
            // 给 weight 而不是包裹内容：带计数的译文（德/俄）比中文长，挤不下时让它自己压缩，别把右边的按钮推出屏幕。
            SingleChoiceSegmentedButtonRow(modifier = Modifier.weight(1f)) {
                SegmentedButton(
                    selected = !showArchived,
                    onClick = { showArchived = false; selectionMode = false; selectedIds = emptySet() },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(tr("活跃 (%d)").format(activeConvs.size)) }
                SegmentedButton(
                    selected = showArchived,
                    onClick = { showArchived = true; selectionMode = false; selectedIds = emptySet() },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(tr("已归档 (%d)").format(archivedConvs.size)) }
            }
            if (conversations.isNotEmpty()) TextButton(onClick = { selectionMode = !selectionMode; if (!selectionMode) selectedIds = emptySet() }) {
                Text(if (selectionMode) tr("完成") else tr("多选"), style = MaterialTheme.typography.labelLarge)
            }
        }
        // 多选批量操作条：全选 / 已选计数 / 批量删除。
        // 走 FlowRow 而非 Row：三样东西的译文比中文长得多（德/俄），挤一行会被推出屏幕外点不到，放不下就换行。
        if (selectionMode) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(tr("已选 %d").format(selectedIds.size), color = scheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically))
                TextButton(onClick = { selectedIds = if (selectedIds.size == conversations.size) emptySet() else conversations.map { it.id }.toSet() }) {
                    Text(if (selectedIds.size == conversations.size && conversations.isNotEmpty()) tr("取消全选") else tr("全选"), style = MaterialTheme.typography.labelLarge)
                }
                // 不再定高 32dp：定高 + 文字 = 长译文被切；高度交给按钮自己算
                Button(
                    enabled = selectedIds.isNotEmpty(),
                    onClick = { confirmBatchDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    Icon(Icons.Outlined.Delete, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                    Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                    Text(tr("删除 (%d)").format(selectedIds.size))
                }
            }
        }
        // 文件夹筛选：有分组时显示「全部 / 未分组 / 各文件夹」。手搓 Surface chip → 真 FilterChip，
        // 排首的图标点明这排在筛什么（与 MemoryPage 的筛选排一个观感）。
        if (folders.isNotEmpty()) {
            ConvFilterRow(icon = Icons.Outlined.Folder) {
                ConvFilterChip(tr("全部"), folderFilter == null) { folderFilter = null }
                if (hasUnfiled) ConvFilterChip(tr("未分组"), folderFilter == "__none__") { folderFilter = "__none__" }
                folders.forEach { f -> ConvFilterChip(f, folderFilter == f) { folderFilter = f } }
            }
        }
        if (conversations.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                    Icon(if (showArchived) Icons.Outlined.Inbox else Icons.AutoMirrored.Outlined.Chat, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(if (showArchived) tr("暂无已归档对话") else tr("暂无对话"), color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
                    if (!showArchived) Text(tr("点击首页 + 新建"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                }
            }
        } else {
            val dateFmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
            // 分组式列表：不再是一堆各自描边的卡片各飘各的，而是一整块——外圈大圆角、靠 2dp 缝隙分行、不用分割线。
            // 行本体是真 ListItem（leading 会话/勾选 / headline 标题 / supporting 分组+角色+时间用量 / trailing 置顶+⋮）。
            // ListItem 在 material3 1.4.0 里没有 onClick 重载，所以外面套 Surface(onClick) 拿点击/水波纹/圆角裁剪，
            // ListItem 自身容器透明，配色由外层 Surface 决定。
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(conversations.size, key = { conversations[it].id }, contentType = { "conv" }) { idx ->
                    val conv = conversations[idx]
                    val card = cardsById[conv.characterCardId]
                    val selected = conv.id in selectedIds
                    // 多选态下不进重命名态：那时 trailing 让位给勾选，确定/取消无处可放，会留下一个改不完也退不出的输入框
                    val renaming = renamingId == conv.id && !selectionMode
                    // 该对话累计用量/花费（常驻小字，随语言本地化货币）：token 累加 + 各消息按模型价估算累加。
                    // 列表不再拉 messagesJson（会破 2MB 窗口），改按 id 单列懒取该对话的消息 JSON 再算——
                    // 单列即便 1MB 也稳在窗口内；LazyColumn 只对可见项触发，随 updatedAt 变化刷新。
                    var usage by remember(conv.id) { mutableStateOf(ConvUsage(0L, 0.0, false)) }
                    LaunchedEffect(conv.id, conv.updatedAt) {
                        usage = withContext(Dispatchers.IO) { computeConvUsage(convManager.repo.getMessagesJson(conv.id)) }
                    }
                    // 原来一排 6 个 10sp 的 TextButton（重命名/导出/移动/置顶/归档/删除）挤在卡片底部——又小又点不准。
                    // 现在：高频的「置顶」留在外面当 IconButton，其余全收进 ⋮ 溢出菜单。
                    // 多选时不给 trailing——那时整行是选择目标，右边不该再有别的可点的东西。
                    // （外层 if 的 else 分支要再包一层花括号，否则 `else @Composable { … }` 会被当成代码块）
                    val convTrailing: (@Composable () -> Unit)? = if (selectionMode) null else {
                        {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (renaming) {
                                    // 重命名进行时把 trailing 换成 确定/取消：原来这两个按钮挤在标题下面，现在归到行尾
                                    IconButton(onClick = { scope.launch { convManager.repo.setTitle(conv.id, renameText.ifBlank { conv.title }) }; renamingId = null }) {
                                        Icon(Icons.Outlined.Check, contentDescription = tr("确定"), tint = scheme.primary)
                                    }
                                    IconButton(onClick = { renamingId = null }) {
                                        Icon(Icons.Outlined.Close, contentDescription = tr("取消"), tint = scheme.onSurfaceVariant)
                                    }
                                } else {
                                    IconButton(onClick = { scope.launch { convManager.repo.setPinned(conv.id, !conv.isPinned) } }) {
                                        // 实心/空心区分置顶态，而不是只靠颜色深浅——形状差异一眼看得出，
                                        // 且色弱用户也分得清。与记忆页的 Star/StarBorder 同一套路数。
                                        Icon(
                                            if (conv.isPinned) Icons.Filled.PushPin else Icons.Outlined.PushPin,
                                            contentDescription = if (conv.isPinned) tr("取消置顶") else tr("置顶"),
                                            tint = if (conv.isPinned) scheme.primary else scheme.onSurfaceVariant,
                                        )
                                    }
                                    Box {
                                        IconButton(onClick = { menuConvId = conv.id }) {
                                            Icon(Icons.Outlined.MoreVert, contentDescription = tr("更多"), tint = scheme.onSurfaceVariant)
                                        }
                                        DropdownMenu(expanded = menuConvId == conv.id, onDismissRequest = { menuConvId = null }) {
                                            DropdownMenuItem(
                                                text = { Text(tr("重命名")) },
                                                leadingIcon = { Icon(Icons.Outlined.Edit, contentDescription = null) },
                                                onClick = { menuConvId = null; renamingId = conv.id; renameText = conv.title },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(tr("移动到文件夹")) },
                                                leadingIcon = { Icon(Icons.Outlined.DriveFileMove, contentDescription = null) },
                                                onClick = { menuConvId = null; movingConv = conv.id to conv.folder; moveFolderText = "" },
                                            )
                                            // 锁定：挡删除（单条/批量）、不参与批量清理类操作，见 ConversationManager.delete/deleteMany。
                                            DropdownMenuItem(
                                                text = { Text(if (conv.isLocked) tr("解锁") else tr("锁定")) },
                                                leadingIcon = { Icon(if (conv.isLocked) Icons.Outlined.LockOpen else Icons.Outlined.Lock, contentDescription = null) },
                                                onClick = { menuConvId = null; scope.launch { convManager.setLocked(conv.id, !conv.isLocked) } },
                                            )
                                            HorizontalDivider()
                                            // 三种导出格式直接摊平成三条：菜单里再套一层菜单在手表小屏上根本点不到
                                            DropdownMenuItem(
                                                text = { Text(tr("导出") + " · Markdown") },
                                                leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                                                onClick = { menuConvId = null; exportConv(conv.id, conv.title, "md") },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(tr("导出") + " · " + tr("纯文本 TXT")) },
                                                leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                                                onClick = { menuConvId = null; exportConv(conv.id, conv.title, "txt") },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(tr("导出") + " · JSON") },
                                                leadingIcon = { Icon(Icons.Outlined.FileDownload, contentDescription = null) },
                                                onClick = { menuConvId = null; exportConv(conv.id, conv.title, "json") },
                                            )
                                            HorizontalDivider()
                                            if (showArchived) DropdownMenuItem(
                                                text = { Text(tr("取消归档")) },
                                                leadingIcon = { Icon(Icons.Outlined.Unarchive, contentDescription = null) },
                                                onClick = { menuConvId = null; scope.launch { convManager.repo.setArchived(conv.id, false) } },
                                            ) else DropdownMenuItem(
                                                text = { Text(tr("归档")) },
                                                leadingIcon = { Icon(Icons.Outlined.Archive, contentDescription = null) },
                                                onClick = { menuConvId = null; scope.launch { convManager.repo.setArchived(conv.id, true) } },
                                            )
                                            DropdownMenuItem(
                                                text = { Text(tr("删除"), color = scheme.error) },
                                                leadingIcon = { Icon(Icons.Outlined.Delete, contentDescription = null, tint = scheme.error) },
                                                // 走 convManager.delete 而不是 repo.delete：后者只删库行，
                                                // 会把被压缩掉的原文存档留在工作区里（模型仍检索得到）。
                                                // 锁定的会话 delete() 内部会拦下返回 false，这里按返回值弹出说明原因的提示。
                                                onClick = {
                                                    menuConvId = null
                                                    if (conv.isLocked) {
                                                        android.widget.Toast.makeText(context, tr("已锁定的对话不能删除，先解锁再试"), android.widget.Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        scope.launch { convManager.delete(conv.id) }
                                                    }
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Surface(
                        onClick = { if (selectionMode) { selectedIds = if (selected) selectedIds - conv.id else selectedIds + conv.id } else onSelectConversation(conv.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = convGroupShape(idx, conversations.size),
                        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainer,
                    ) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = {
                                if (selectionMode) Checkbox(checked = selected, onCheckedChange = { checked -> selectedIds = if (checked) selectedIds + conv.id else selectedIds - conv.id })
                                else Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, tint = scheme.primary)
                            },
                            headlineContent = {
                                if (renaming) XtomField(value = renameText, onValueChange = { renameText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                                else Row(verticalAlignment = Alignment.CenterVertically) {
                                    // 锁的标记：只在非重命名态显示，编辑态让位给输入框
                                    if (conv.isLocked) {
                                        Icon(Icons.Outlined.Lock, contentDescription = tr("已锁定"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                    }
                                    Text(conv.title, color = scheme.onSurface, fontWeight = if (conv.isPinned) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
                                }
                            },
                            supportingContent = {
                                Column {
                                    if (conv.folder.isNotBlank() || card != null) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (conv.folder.isNotBlank()) {
                                                Icon(Icons.Outlined.Folder, contentDescription = tr("文件夹"), tint = scheme.tertiary, modifier = Modifier.size(12.dp))
                                                Spacer(Modifier.width(2.dp))
                                                Text(conv.folder, color = scheme.tertiary, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(end = 6.dp))
                                            }
                                            if (card != null) Text(card.name, color = scheme.primary, style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                    // 时间 + 该对话累计用量/花费并成一行小字：原来它们各占一行，把卡片撑高了一截却没多说什么
                                    Text(
                                        buildString {
                                            append(dateFmt.format(Date(conv.updatedAt)))
                                            append(" · "); append(tr("用量")); append(" "); append(compactTokens(usage.tokens)); append(" token")
                                            if (usage.priced) { append(" · "); append(formatCost(usage.costUsd, curSym, curRate)) }
                                        },
                                        color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall,
                                    )
                                }
                            },
                            trailingContent = convTrailing,
                        )
                    }
                }
            }
        }
    }

    // 移动到文件夹弹窗：选现有分组 / 新建 / 移出
    val moving = movingConv
    if (moving != null) {
        AlertDialog(
            onDismissRequest = { movingConv = null },
            title = { Text(tr("移动到文件夹"), color = scheme.onSurface) },
            text = {
                Column {
                    if (folders.isNotEmpty()) {
                        Text(tr("选择现有："), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        ConvFilterRow(icon = null) {
                            folders.forEach { f -> ConvFilterChip(f, moving.second == f) { scope.launch { convManager.repo.setFolder(moving.first, f) }; movingConv = null } }
                        }
                    }
                    Text(tr("或新建："), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(4.dp))
                    XtomField(value = moveFolderText, onValueChange = { moveFolderText = it }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = moveFolderText.isNotBlank(), onClick = { scope.launch { convManager.repo.setFolder(moving.first, moveFolderText.trim()) }; movingConv = null }) { Text(tr("移入"), color = scheme.primary) }
            },
            dismissButton = {
                Row {
                    if (moving.second.isNotBlank()) TextButton(onClick = { scope.launch { convManager.repo.setFolder(moving.first, "") }; movingConv = null }) { Text(tr("移出分组"), color = scheme.error) }
                    TextButton(onClick = { movingConv = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) }
                }
            },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp),
        )
    }

    // 批量删除确认：锁定的会话不参与批量删除（deleteMany 内部自动跳过），这里先数一下提前说明，
    // 免得用户点了删除、锁定的那几个却原封不动留在列表里而不知道为什么。
    val lockedSelectedCount = remember(selectedIds, conversations) { conversations.count { it.id in selectedIds && it.isLocked } }
    if (confirmBatchDelete) {
        AlertDialog(
            onDismissRequest = { confirmBatchDelete = false },
            title = { Text(tr("批量删除"), color = scheme.onSurface) },
            text = {
                Column {
                    Text(tr("确定删除选中的 %d 个对话？此操作不可恢复。").format(selectedIds.size), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                    if (lockedSelectedCount > 0) {
                        Spacer(Modifier.height(4.dp))
                        Text(tr("其中 %d 个已锁定，不会被删除。").format(lockedSelectedCount), color = scheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val ids = selectedIds.toList()
                    // 同单条删除：批量也要连原文存档一起清，别只修单条那条路径。锁定的 id 由 deleteMany 内部自动跳过。
                    scope.launch { convManager.deleteMany(ids) }
                    confirmBatchDelete = false; selectedIds = emptySet(); selectionMode = false
                }) { Text(tr("删除"), color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmBatchDelete = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp),
        )
    }
} // end ConversationListScreen

// ============================================================
// 列表侧的小零件。与 MemoryPage 同款（那边是 private 的，跨文件用不了，只能同形复制一份）。
// ============================================================

/**
 * 分组列表的圆角：整组像一整块——首行上圆角大、末行下圆角大、中间小圆角，
 * 靠 2dp 缝隙分行，不用分割线。只有一行时四角都大。
 */
private fun convGroupShape(index: Int, count: Int): RoundedCornerShape {
    val big = 20.dp
    val small = 4.dp
    val top = if (index == 0) big else small
    val bottom = if (index == count - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** 一排横滑的筛选 chip，排首用图标点明这排在筛什么。 */
@Composable
private fun ConvFilterRow(icon: ImageVector?, content: @Composable RowScope.() -> Unit) {
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

/** 真 FilterChip（选中打勾），替代原来手搓的 Surface+Text 假 chip。 */
@Composable
private fun ConvFilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (!selected) null else {
            { Icon(Icons.Outlined.Check, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) }
        },
    )
}

// ---- 用量/花费：从会话 messagesJson 累计 token 与花费 ----
private data class ConvUsage(val tokens: Long, val costUsd: Double, val priced: Boolean)

// 解析该对话的消息 JSON：累加各条(promptTokens+completionTokens)=真实计费 token；
// 花费=各条按其 model 走 ModelPricing 估价后累加(混用模型也准；未知价的条目不计花费)。
private fun computeConvUsage(messagesJson: String): ConvUsage {
    return try {
        val arr = org.json.JSONArray(messagesJson)
        var tokens = 0L; var cost = 0.0; var priced = false
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (!o.has("totalTokens")) continue
            val p = o.optInt("promptTokens", 0)
            val c = o.optInt("completionTokens", 0)
            tokens += (p + c).toLong()
            val model = if (o.has("model")) o.optString("model") else null
            ModelPricing.costUsd(model, p, c)?.let { cost += it; priced = true }
        }
        ConvUsage(tokens, cost, priced)
    } catch (_: Exception) { ConvUsage(0L, 0.0, false) }
}

private fun compactTokens(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(java.util.Locale.US, n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(java.util.Locale.US, n / 1_000.0)
    else -> n.toString()
}

// 花费展示：USD → 目标货币(粗略汇率)，小额多留小数。<=0 显示「免费」。
private fun formatCost(usd: Double, sym: String, rate: Double): String {
    if (usd <= 0.0) return tr("免费")
    val v = usd * rate
    val digits = if (v < 1.0) 4 else if (v < 100.0) 2 else 0
    return sym + "%.${digits}f".format(java.util.Locale.US, v)
}

// 语言 code → (货币符号, 相对 USD 的粗略汇率)。仅用于本地化「花费」的观感，非实时汇率；未列出的回退美元。
private fun currencyFor(code: String): Pair<String, Double> = when (code) {
    "zh", "zh-TW" -> "¥" to 7.2
    "ja" -> "¥" to 155.0
    "ko" -> "₩" to 1350.0
    "ru" -> "₽" to 92.0
    "uk" -> "₴" to 41.0
    "hi", "bn", "ta" -> "₹" to 83.0
    "th" -> "฿" to 36.0
    "vi" -> "₫" to 25000.0
    "tr" -> "₺" to 34.0
    "he" -> "₪" to 3.7
    "tl" -> "₱" to 58.0
    "id" -> "Rp" to 16000.0
    "ms" -> "RM" to 4.7
    "sv" -> "kr" to 10.6
    "da", "nb" -> "kr" to 6.9
    "es", "pt", "fr", "de", "it", "nl", "el", "fi", "cs", "ro", "hu", "pl" -> "€" to 0.92
    else -> "$" to 1.0
}
