 package com.arix.app
 
 import android.Manifest
 import android.content.pm.PackageManager
 import android.media.AudioFormat
 import android.media.AudioRecord
 import android.media.MediaRecorder
 import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
 import androidx.activity.ComponentActivity
 import androidx.activity.compose.setContent
 import androidx.activity.compose.rememberLauncherForActivityResult
 import androidx.activity.result.contract.ActivityResultContracts
 import androidx.compose.animation.core.animateFloatAsState
 import androidx.compose.foundation.ExperimentalFoundationApi
 import androidx.compose.foundation.background
 import androidx.compose.foundation.clickable
 import androidx.compose.foundation.combinedClickable
 import androidx.compose.foundation.horizontalScroll
 import androidx.compose.foundation.layout.Arrangement
 import androidx.compose.foundation.layout.Box
 import androidx.compose.foundation.layout.Column
 import androidx.compose.foundation.layout.Row
 import androidx.compose.foundation.layout.PaddingValues
 import androidx.compose.foundation.layout.Spacer
 import androidx.compose.foundation.layout.fillMaxSize
 import androidx.compose.foundation.layout.fillMaxWidth
 import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
 import androidx.compose.foundation.layout.padding
 import androidx.compose.foundation.layout.size
 import androidx.compose.foundation.layout.width
 import androidx.compose.foundation.layout.widthIn
 import androidx.compose.foundation.lazy.LazyColumn
 import androidx.compose.foundation.lazy.items
 import androidx.compose.foundation.lazy.rememberLazyListState
 import androidx.compose.foundation.rememberScrollState
 import androidx.compose.foundation.shape.CircleShape
 import androidx.compose.foundation.shape.RoundedCornerShape
 import androidx.compose.foundation.text.KeyboardOptions
 import androidx.compose.foundation.verticalScroll
 import androidx.compose.material.icons.Icons
 import androidx.compose.material.icons.automirrored.outlined.ArrowBack
 import androidx.compose.material.icons.automirrored.outlined.Chat
 import androidx.compose.material.icons.outlined.Add
 import androidx.compose.material.icons.outlined.Folder
 import androidx.compose.material.icons.outlined.History
 import androidx.compose.material.icons.outlined.Menu
 import androidx.compose.material.icons.outlined.Mic
 import androidx.compose.material.icons.outlined.Settings
 import androidx.compose.material3.Button
 import androidx.compose.material3.ButtonDefaults
 import androidx.compose.material3.Card
 import androidx.compose.material3.CardDefaults
 import androidx.compose.material3.DrawerValue
 import androidx.compose.material3.DropdownMenu
 import androidx.compose.material3.DropdownMenuItem
 import androidx.compose.material3.ExperimentalMaterial3Api
 import androidx.compose.material3.HorizontalDivider
 import androidx.compose.material3.Slider
 import androidx.compose.material3.Switch
 import androidx.compose.material3.Icon
 import androidx.compose.foundation.Canvas
 import androidx.compose.foundation.gestures.detectTapGestures
 import androidx.compose.foundation.gestures.detectTransformGestures
 import androidx.compose.foundation.gestures.awaitEachGesture
 import androidx.compose.foundation.gestures.awaitFirstDown
 import androidx.compose.foundation.gestures.calculateZoom
 import androidx.compose.foundation.gestures.calculatePan
 import androidx.compose.ui.input.pointer.positionChange
 import androidx.compose.runtime.mutableStateMapOf
 import androidx.compose.runtime.withFrameNanos
 import kotlinx.coroutines.isActive
 import androidx.compose.ui.geometry.Offset
 import androidx.compose.ui.geometry.CornerRadius
 import androidx.compose.ui.geometry.Size
 import androidx.compose.ui.graphics.PathEffect
 import androidx.compose.ui.graphics.drawscope.Stroke
 import androidx.compose.ui.input.pointer.pointerInput
 import androidx.compose.ui.text.AnnotatedString
 import androidx.compose.ui.text.drawText
 import androidx.compose.ui.text.rememberTextMeasurer
 import androidx.compose.material3.IconButton
 import androidx.compose.material3.LinearProgressIndicator
 import androidx.compose.material3.MaterialTheme
 import androidx.compose.material3.ModalDrawerSheet
 import androidx.compose.material3.ModalNavigationDrawer
 import androidx.compose.material3.NavigationDrawerItem
 import androidx.compose.material3.NavigationDrawerItemDefaults
 import androidx.compose.material3.OutlinedTextField
 import androidx.compose.material3.OutlinedTextFieldDefaults
 import androidx.compose.material3.Scaffold
 import androidx.compose.material3.Switch
 import androidx.compose.material3.SwitchDefaults
 import androidx.compose.material3.Text
 import androidx.compose.material3.TextButton
 import androidx.compose.material3.TopAppBar
 import androidx.compose.material3.TopAppBarDefaults
 import androidx.compose.material3.rememberDrawerState
 import androidx.compose.runtime.Composable
 import androidx.compose.runtime.DisposableEffect
 import androidx.compose.runtime.Immutable
 import androidx.compose.runtime.LaunchedEffect
 import androidx.compose.runtime.collectAsState
 import androidx.compose.runtime.derivedStateOf
 import androidx.compose.runtime.getValue
 import androidx.compose.runtime.key
 import androidx.compose.runtime.mutableLongStateOf
 import androidx.compose.runtime.mutableStateListOf
 import androidx.compose.runtime.mutableStateOf
 import androidx.compose.runtime.remember
 import androidx.compose.runtime.rememberCoroutineScope
 import androidx.compose.runtime.setValue
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
 import androidx.compose.ui.draw.alpha
 import androidx.compose.ui.draw.clip
 import androidx.compose.ui.draw.clipToBounds
 import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.platform.LocalClipboardManager
 import androidx.compose.ui.platform.LocalContext
 import androidx.compose.ui.text.font.FontFamily
 import androidx.compose.ui.text.font.FontWeight
 import androidx.compose.ui.text.input.PasswordVisualTransformation
 import androidx.compose.ui.unit.dp
 import androidx.compose.ui.unit.sp
 import androidx.core.content.ContextCompat
 import com.arix.cloudapi.CloudApiClient
 import com.arix.cloudapi.CloudApiConfig
 import com.arix.cloudapi.WhisperClient
 import com.arix.cloudapi.model.ChatMessage
 import android.content.Intent
 import androidx.compose.material.icons.outlined.Warning
 import com.arix.tool.PackageManager as XtomPackageManager
import com.arix.tool.PackageDef
 import com.arix.tool.OperitCompat
 import com.arix.tool.CloudMarketplace
 import com.arix.tool.ImportExport
 import com.arix.tool.PluginCreatorTool
import com.arix.tool.TtsTool
import com.arix.tool.ShellTool
 import java.io.File
 import com.arix.stt.LanguageModel
 import com.arix.stt.SttEngine
 import com.arix.stt.SttModelManager
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.delay
 import kotlinx.coroutines.isActive
 import kotlinx.coroutines.launch
 import kotlinx.coroutines.withContext
 import org.json.JSONObject
 import java.text.SimpleDateFormat
 import java.util.Date
 import java.util.Locale
// —— 列表侧改用真 M3 组件所需（ListItem / FilterChip / IconButton / Checkbox）+ 图标（一律 Material 矢量图，不用 emoji）
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.ViewList
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.AutoDelete
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Gavel
import androidx.compose.material.icons.outlined.Healing
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.People
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.vector.ImageVector
import com.arix.app.ui.SettingsHint
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.topChromeGapHeight

@Composable fun MemoryPage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    val memoryManager = remember { MemoryManager(context) }
    val cardManager = remember { CharacterCardManager(context) }
    val cards by cardManager.allCards.collectAsState(initial = emptyList())
    val memories by memoryManager.allMemories.collectAsState(initial = emptyList())
    val stateConvManager = remember { ConversationManager(context) }
    val stateConvs by stateConvManager.repo.activeSummaries.collectAsState(initial = emptyList())
    var searchQuery by remember { mutableStateOf("") }
    // 编辑器状态收成一个对象：null=关闭。原来是 7 个必须逐个手动保持同步的散装 remember，
    // 漏改一个就是「打开显示上一条的内容」。
    var editor by remember { mutableStateOf<MemoryEditorState?>(null) }
    val scheme = MaterialTheme.colorScheme
    val accents = com.arix.app.theme.LocalXtomAccents.current
    // 设了背景图的页面分组卡不能半透明：半透明卡压在照片上会糊到读不出字
    val translucent = remember { PageBackgroundPrefs.get(context, "memory") == null }
    var typeFilter by remember { mutableStateOf<String?>(null) }
    var folderFilter by remember { mutableStateOf<String?>(null) }  // null=全部；""=未分组；否则=某文件夹名
    var graphView by remember { mutableStateOf(false) }
    var linkMode by remember { mutableStateOf(false) }              // 图内连线模式：点两条记忆建立/断开关联
    var pendingLink by remember { mutableStateOf<Long?>(null) }      // 连线模式下已点选的第一条记忆
    // 记忆功能开关（补边/关联面板/标签/局部图谱）。持成 state 而不是每次重组去读：读的是进程内缓存
    // 没错，但页面里有个「按来源着色」芯片会就地改它，不做成 state 就不会触发重组。
    // 进页面时重读一次，接住在设置页改完再切回来的情况（仿 ChatAppearancePage 的 EffectsSection）。
    var feat by remember { mutableStateOf(MemoryFeaturePrefs.snapshot(context)) }
    LaunchedEffect(Unit) { feat = MemoryFeaturePrefs.snapshot(context) }
    // 标签筛选：选中的标签 id（null=不按标签筛）。成员 id 按需查，不常驻。
    val allTags by memoryManager.allTags.collectAsState(initial = emptyList())
    var tagFilter by remember { mutableStateOf<Long?>(null) }
    // 局部图谱：graphRoot != null 时图谱只画这条记忆 graphDepth 跳内的邻居（不再是全局毛线球）
    var graphRoot by remember { mutableStateOf<Long?>(null) }
    var graphDepth by remember { mutableStateOf(2) }
    var cardFilterAll by remember { mutableStateOf(true) }        // true=全部角色卡
    var cardFilterId by remember { mutableStateOf<Long?>(null) }  // 选中的角色卡(null=通用/全局)
    // 关联边(记忆图谱连线)的 type/weight：连线时用这套默认值建边；点已存在的边则打开编辑弹窗
    // 标签不 remember：译文得随语言切换重算，remember 无 key 会把中文钉死
    val edgeTypeLabels = listOf("related" to tr("相关"), "causes" to tr("导致"), "part_of" to tr("从属"))
    // 归档边不进可选列表（它由冲突消解自动打，不该让人手动建），但点开一条已存在的边时要认得出它是什么
    fun edgeTypeLabel(t: String) = when (t) {
        MemoryManager.EDGE_SUPERSEDED_BY -> tr("被取代")
        MemoryManager.EDGE_SUPERSEDES -> tr("取代")
        else -> edgeTypeLabels.firstOrNull { it.first == t }?.second ?: tr("相关")
    }
    var edgeType by remember { mutableStateOf("related") }
    var edgeWeight by remember { mutableStateOf(1.0f) }
    var edgeEditPair by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    // 救援存量数据
    var maintOpen by remember { mutableStateOf(false) }   // 记忆维护/导入导出工具区，默认收起——让记忆内容占满屏（治「多遮挡少内容」）
    var salvageOpen by remember { mutableStateOf(false) }
    var salvageStatus by remember { mutableStateOf("") }
    var salvaging by remember { mutableStateOf(false) }
    val salvageConfigManager = remember { CloudApiConfigManager(context) }
    // 多选归档 + 共享角色卡选择弹窗
    var selectMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var cardPicker by remember { mutableStateOf<((Long?) -> Unit)?>(null) }
    var cardPickerAllowNull by remember { mutableStateOf(false) }
    var cardPickerTitle by remember { mutableStateOf(tr("选择角色卡")) }
    // 文件夹归入弹窗（多选归入用）：选已有文件夹 / 新建 / 移出
    var folderPickerOpen by remember { mutableStateOf(false) }
    var newFolderName by remember { mutableStateOf("") }
    // 状态卡查看器
    var stateViewOpen by remember { mutableStateOf(false) }
    var stateRefresh by remember { mutableStateOf(0) }
    // 语义记忆索引：为已有记忆建立向量，让检索按意思而非关键词
    var embedBusy by remember { mutableStateOf(false) }
    var embedStatus by remember { mutableStateOf("") }
    var embedOpen by remember { mutableStateOf(false) }
    // 自动整理教训（逻辑全在 MemoryTidy）：待确认列表 / 回收站都是它的持久化状态，这里只读流。
    var tidyOpen by remember { mutableStateOf(false) }
    var tidyBusy by remember { mutableStateOf(false) }
    var tidyStatus by remember { mutableStateOf("") }
    var tidyAuto by remember { mutableStateOf(MemoryTidy.autoEnabled(context)) }
    val tidyPending by MemoryTidy.pending.collectAsState()
    val tidyTrash by MemoryTidy.trash.collectAsState()
    // 顺带把周期任务补排上：XtomApp 冷启动那次可能被系统吞掉，用户来过记忆页就一定排得上（KEEP 幂等）
    LaunchedEffect(Unit) {
        MemoryTidy.ensureLoaded(context)
        if (MemoryTidy.autoEnabled(context)) MemoryTidy.schedule(context)
    }
    // 标签不 remember：译文得随语言切换重算
    val typeLabels = listOf(
        "preference" to tr("偏好"), "fact" to tr("事实"), "event" to tr("事件"), "relation" to tr("关系"), "todo" to tr("待办"),
        // 后三类是「关于怎么干活」的，和前五类「关于人」并列；顺序跟 MemoryTool 的 enum 一致，筛选芯片才对得上
        "lesson" to tr("教训"), "environment" to tr("环境"), "convention" to tr("约定"),
    )
    fun typeLabel(t: String) = typeLabels.firstOrNull { it.first == t }?.second ?: tr("事实")
    fun openEditor(m: com.arix.data.entity.MemoryEntity?) {
        editor = if (m == null) MemoryEditorState()
        else MemoryEditorState(id = m.id, title = m.title, content = m.content, type = m.type,
            importance = m.importance, pinned = m.pinned, folder = m.folder)
    }

    // 搜索结果 hoist 到条件之外。原来 remember/LaunchedEffect 写在 `if (searchQuery.isNotBlank())` 分支里：
    // 查询在「空↔非空」之间切换时那份状态整个被销毁重建，且每换一次查询结果先回到 emptyList
    // → 列表在新结果回来前闪一下空。现在结果一直留着，等新结果算完才整批换，中途不闪；
    // 在途期间用 searching 让空态说「搜索中」而不是谎称「未找到」。
    val search by produceState(MemorySearchState(), searchQuery, memories) {
        val q = searchQuery
        if (q.isBlank()) return@produceState   // 空查询不清结果：下次再搜同一词能立刻复用
        // 模糊排序(FuzzyMatch.rankBy)是 CPU 活，赶下主线程再回填（对齐聊天页「解析挪后台」）：
        // Room 查询本就走自己的执行器，但 rank 会在主线程 resume，长候选集会卡输入。
        value = withContext(Dispatchers.Default) { MemorySearchState(q, memoryManager.search(q)) }
    }
    val searching = searchQuery.isNotBlank() && search.query != searchQuery
    val baseMemories = if (searchQuery.isBlank()) memories else search.results
    // 所有非空文件夹名（从记忆流派生，响应式；空=未分组不入列）。记忆化：只随记忆流变，
    // 否则每次重组（每键入/选中变化）都要 map+filter+distinct+sorted 整份重扫（对齐聊天页「别在重组体里重算」）。
    val folders = remember(memories) { memories.map { it.folder }.filter { it.isNotBlank() }.distinct().sorted() }
    // 角色卡 id→卡 映射记忆化：替代行内 cards.find{} 的 O(卡数) 线性扫（下面列表项用）。
    val cardById = remember(cards) { cards.associateBy { it.id } }
    // 置顶优先，再按重要度、时间。整条派生链（3 个 O(n) 过滤 + O(n log n) 排序）记忆化：
    // 只在输入真变时重算，不再每次重组全跑一遍——这是本页最直接的热路径（同聊天页列表热路径）。
    // 标签成员：只在真的选了标签时查一次（走 memory_tag_cross_ref 的 join，只要 id 不要整行）。
    // null = 没按标签筛（不是"查不到"），下面的过滤直接放行。
    val tagMemberIds by produceState<Set<Long>?>(null, tagFilter, memories) {
        val t = tagFilter
        value = if (t == null) null else withContext(Dispatchers.IO) { memoryManager.idsByTag(t).toSet() }
    }
    val displayedMemories = remember(baseMemories, typeFilter, cardFilterAll, cardFilterId, folderFilter, tagMemberIds) {
        baseMemories
            .filter { typeFilter == null || it.type == typeFilter }
            .filter { cardFilterAll || it.characterCardId == cardFilterId }
            .filter { folderFilter == null || it.folder == folderFilter }
            .filter { tagMemberIds == null || it.id in tagMemberIds!! }
            .sortedWith(compareByDescending<com.arix.data.entity.MemoryEntity> { it.pinned }.thenByDescending { it.importance }.thenByDescending { it.updatedAt })
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // 顶部悬浮玻璃让位。这页顶部工具区不滚(下面 LazyColumn 才滚)，让不出「从玻璃下滑过去化开」，退化成普通留白
        Spacer(Modifier.topChromeGapHeight())
        // 待确认横幅：自动整理拿不准的教训停在这儿等用户拍板。
        // 它必须在「记忆维护」收起时也看得见——藏在折叠区里的询问等于没问过，而没被回答的条目会一直不删。
        if (tidyPending.isNotEmpty() && !maintOpen) {
            Surface(
                onClick = { maintOpen = true; tidyOpen = true },
                shape = RoundedCornerShape(12.dp),
                color = scheme.secondaryContainer,
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoDelete, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        tr("有 %d 条教训拿不准要不要留，点这里确认").format(tidyPending.size),
                        style = MaterialTheme.typography.bodySmall, color = scheme.onSecondaryContainer,
                    )
                }
            }
        }
        // 记忆维护：救援 / 状态卡 / 语义索引。默认收起（maintOpen）——展开才占屏，平时把空间让给记忆列表。带展开动画。
        androidx.compose.animation.AnimatedVisibility(visible = maintOpen) { SettingsSection(title = tr("记忆维护"), icon = Icons.Outlined.Healing, translucent = translucent) {
            // 救援存量数据：瘦身(治爆满) + 回填状态卡(治开新对话不像他)
            MemToolRow(
                icon = Icons.Outlined.Healing,
                title = tr("救援存量数据"),
                subtitle = tr("共 %d 条").format(memories.size),
                expanded = salvageOpen,
                onToggle = { salvageOpen = !salvageOpen },
            ) {
                SettingsHint(tr("整理记忆=去重+按重要度剪枝到上限(保留置顶)，治记忆爆满。回填状态卡=从历史给每个角色卡估算关系/语气，治开新对话不像他。"))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = !salvaging, onClick = {
                        salvaging = true; salvageStatus = tr("整理中…")
                        scope.launch {
                            val r = MemorySalvage.compact(context)
                            salvageStatus = tr("整理完成：%d → %d 条（清理 %d）").format(r.before, r.after, r.removed)
                            salvaging = false
                        }
                    }, modifier = Modifier.weight(1f)) { Text(tr("整理记忆")) }
                    Button(enabled = !salvaging, onClick = {
                        salvaging = true; salvageStatus = tr("回填中…")
                        scope.launch {
                            val cfg = salvageConfigManager.getActiveConfig()
                            if (cfg == null) { salvageStatus = tr("无可用模型配置"); salvaging = false; return@launch }
                            val r = MemorySalvage.bootstrapStateCards(context, cfg, onProgress = { salvageStatus = it })
                            salvageStatus = tr("回填完成：%d 个角色卡（跳过 %d 个无历史）").format(r.seeded, r.skipped)
                            salvaging = false
                        }
                    }, modifier = Modifier.weight(1f)) { Text(tr("回填状态卡")) }
                }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.FilledTonalButton(enabled = !salvaging, onClick = {
                    cardPickerTitle = tr("选择要联合救援的角色"); cardPickerAllowNull = false
                    cardPicker = { cid ->
                        if (cid != null) {
                            salvaging = true; salvageStatus = tr("联合救援中…")
                            scope.launch {
                                val cfg = salvageConfigManager.getActiveConfig()
                                if (cfg == null) { salvageStatus = tr("无可用模型配置"); salvaging = false; return@launch }
                                val r = MemorySalvage.salvageCard(context, cfg, cid, onProgress = { salvageStatus = it })
                                salvageStatus = tr("「%s」联合救援完成：从对话抽回 %d 条记忆，熟悉度 %.2f").format(r.cardName, r.memoriesExtracted, r.relationshipLevel)
                                salvaging = false
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(tr("按角色联合救援（对话→记忆 + 状态）")) }
                Spacer(Modifier.height(8.dp))
                androidx.compose.material3.FilledTonalButton(enabled = !salvaging, onClick = {
                    cardPickerTitle = tr("选择要 AI 合并去重的角色"); cardPickerAllowNull = false
                    cardPicker = { cid ->
                        if (cid != null) {
                            salvaging = true; salvageStatus = tr("AI 合并去重中…")
                            scope.launch {
                                val cfg = salvageConfigManager.getActiveConfig()
                                if (cfg == null) { salvageStatus = tr("无可用模型配置"); salvaging = false; return@launch }
                                val r = MemorySalvage.mergeCard(context, cfg, cid, onProgress = { salvageStatus = it })
                                salvageStatus = if (r.removed > 0) tr("AI 合并完成：%d → %d 条（合掉 %d）").format(r.before, r.after, r.removed) else tr("无可合并的重复项（或条数不足/调用失败）")
                                salvaging = false
                            }
                        }
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(tr("AI 合并去重（语义近似的记忆合并）")) }
                SettingsHint(tr("提示：整理记忆只删同名+超量；真正的语义重复用「AI 合并去重」。置顶记忆不参与合并。"))
                if (salvageStatus.isNotBlank()) Text(salvageStatus, style = MaterialTheme.typography.bodySmall, color = scheme.primary, modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp))
                SettingsHint(tr("回填/救援后需在应用设置开启「交互状态层」才注入生效。"))
            }
            // 状态卡查看器：看/清空每个角色的相处状态(救援/回填结果可见)
            MemToolRow(
                icon = Icons.Outlined.Psychology,
                title = tr("状态卡（相处状态）"),
                subtitle = tr("看/清空每个角色跨对话积累的关系与语气"),
                expanded = stateViewOpen,
                onToggle = { stateViewOpen = !stateViewOpen; stateRefresh++ },
            ) {
                // 状态卡快照：每张卡都要读 SharedPreferences + 解析 JSON（InteractionState.snapshot），
                // 原来写在展开区内容体里每次重组主线程全跑一遍。改 produceState 赶下 IO 线程 + 只随
                // cards/stateConvs/stateRefresh 变时重算（对齐聊天页「重活挪后台、别在重组体里重算」）。
                val entries by produceState(
                    emptyList<Pair<com.arix.data.entity.CharacterCardEntity, org.json.JSONObject>>(),
                    cards, stateConvs, stateRefresh,
                ) {
                    value = withContext(Dispatchers.IO) {
                        cards.mapNotNull { c ->
                            val recentConvId = stateConvs.filter { it.characterCardId == c.id }.maxByOrNull { it.updatedAt }?.id ?: 0L
                            val s = InteractionState.snapshot(context, c.id, recentConvId)
                            val hasAny = s.optDouble("relationship_level", 0.0) > 0.0 || s.optString("tone").isNotBlank() || s.optString("interaction_mode").isNotBlank() || s.optString("summary").isNotBlank()
                            if (hasAny) c to s else null
                        }
                    }
                }
                if (entries.isEmpty()) {
                    SettingsHint(tr("暂无状态卡。用上面「回填状态卡」从历史生成，或开启「交互状态层」后聊天积累。"))
                } else {
                    entries.forEach { (c, s) ->
                        val rel = s.optDouble("relationship_level", 0.0).toFloat().coerceIn(0f, 1f)
                        val tone = s.optString("tone"); val mode = s.optString("interaction_mode")
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface, modifier = Modifier.weight(1f))
                                // 手搓的圆角 clickable 文字换成真 TextButton：够 48dp 可点、有水波纹、有禁用态
                                TextButton(onClick = { InteractionState.clearCard(context, c.id); stateRefresh++ }) {
                                    Text(tr("清空"), color = scheme.error, style = MaterialTheme.typography.labelMedium)
                                }
                            }
                            Text(tr("熟悉度 %d%%").format((rel * 100).toInt()), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            // 手搓的两层 Box 进度条 → 真 LinearProgressIndicator
                            LinearProgressIndicator(progress = { rel }, modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp))
                            Text(tr("语气：%s").format(tone.ifBlank { tr("— 尚未积累") }), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            Text(tr("相处：%s").format(mode.ifBlank { tr("— 尚未积累") }), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            val mood = s.optString("closing_mood"); val pq = s.optString("pending_question"); val summary = s.optString("summary")
                            if (mood.isNotBlank()) Text(tr("收尾氛围：%s").format(mood), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            if (pq.isNotBlank()) Text(tr("上次待决：%s").format(pq), style = MaterialTheme.typography.bodySmall, color = scheme.primary)
                            if (summary.isNotBlank()) Text(tr("上次摘要：%s").format(summary), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 3, modifier = Modifier.padding(top = 2.dp))
                        }
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                    }
                    SettingsHint(tr("话题/待决问题是每个对话各自的(此处只看跨对话的关系/语气)。"))
                }
            }
            // 语义记忆索引：为已有记忆建立向量，让检索按意思而非关键词
            MemToolRow(
                icon = Icons.Outlined.Memory,
                title = tr("语义记忆索引"),
                subtitle = tr("让 AI 按「意思」而非关键词检索记忆"),
                expanded = embedOpen,
                onToggle = { embedOpen = !embedOpen },
            ) {
                SettingsHint(tr("在模型配置里设好「向量记忆(Embedding)」模型后，点这里为已有记忆建立语义向量——之后 AI 按「意思」而非关键词检索记忆，更准。新记忆会自动建索引。"))
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.OutlinedButton(enabled = !embedBusy, onClick = {
                    embedBusy = true; embedStatus = tr("建立中…")
                    scope.launch {
                        val n = withContext(Dispatchers.IO) { runCatching { memoryManager.backfillEmbeddings() }.getOrDefault(-2) }
                        embedStatus = when (n) {
                            -1 -> tr("请先在模型配置里设「向量记忆」模型")
                            -2 -> tr("建立失败")
                            0 -> tr("已是最新，无需新建")
                            else -> tr("已为 %d 条记忆建立索引").format(n)
                        }
                        embedBusy = false
                    }
                }) { Text(if (embedBusy) tr("建立中…") else tr("建立/更新语义索引")) }
                if (embedStatus.isNotBlank()) SettingsHint(embedStatus)
            }
            // 自动整理教训：评估自动沉淀的「教训」还有没有用。和上面几个手动动作并列，
            // 但它平时是后台自己跑的（MemoryTidy 周期任务 + 限频），这里给的是开关、手动触发、以及它问你的那些条目。
            MemToolRow(
                icon = Icons.Outlined.AutoDelete,
                title = tr("自动整理教训"),
                subtitle = if (tidyPending.isNotEmpty()) tr("有 %d 条待你确认").format(tidyPending.size)
                           else tr("清掉再也用不上的失败经验，拿不准的问你"),
                expanded = tidyOpen,
                onToggle = { tidyOpen = !tidyOpen },
            ) {
                SettingsHint(tr("「教训」是工具被拒/参数写错/回复被截断时自动记下的经验，只进不出会挤占检索名额。整理只看教训，不碰别的记忆；置顶的一律不动；判定明确没用的才删，拿不准的列在下面问你。删掉的进回收站，随时能恢复。"))
                com.arix.app.ui.SettingsToggle(
                    icon = null,
                    title = tr("后台自动整理"),
                    subtitle = tr("闲时低频跑一轮，不占用发消息的时间"),
                    checked = tidyAuto,
                    onCheckedChange = { tidyAuto = it; MemoryTidy.setAutoEnabled(context, it) },
                )
                Spacer(Modifier.height(6.dp))
                androidx.compose.material3.FilledTonalButton(enabled = !tidyBusy, onClick = {
                    tidyBusy = true; tidyStatus = tr("整理中…")
                    scope.launch {
                        // 手动触发用「摘要」用途的便宜模型，没配则回退对话模型；一个都没有也能跑，
                        // 那种情况下只做确定性那几步（合并近似、清工具已消失的），一条都不会误删。
                        val cfg = withContext(Dispatchers.IO) { runCatching { salvageConfigManager.getConfigForPurpose("summary") }.getOrNull() }
                        val r = withContext(Dispatchers.IO) { MemoryTidy.runOnce(context, cfg, onProgress = { tidyStatus = it }) }
                        tidyStatus = tr("整理完成：评估 %d 条，合并 %d、清理 %d、待你确认 %d").format(r.scanned, r.merged, r.deleted, r.asked)
                        tidyBusy = false
                    }
                }, modifier = Modifier.fillMaxWidth()) { Text(if (tidyBusy) tr("整理中…") else tr("立即整理一次")) }
                if (tidyStatus.isNotBlank()) SettingsHint(tidyStatus)

                // 待确认：在用户点之前，这些条目原封不动待在记忆库里
                if (tidyPending.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(tr("拿不准的（%d）").format(tidyPending.size), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = scheme.primary, modifier = Modifier.padding(horizontal = 4.dp))
                    tidyPending.forEach { p ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Text(p.title, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface, maxLines = 2)
                            Text(MemoryTidy.reasonText(p.reasonCode), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { scope.launch { MemoryTidy.resolveKeep(context, p.id) } }) { Text(tr("留着")) }
                                TextButton(onClick = { scope.launch { MemoryTidy.resolveDelete(context, p.id) } }) { Text(tr("删掉"), color = scheme.error) }
                            }
                        }
                        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
                    }
                }

                // 回收站：自动删掉的东西必须能找回来，否则「自动」两个字就不该给它
                if (tidyTrash.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Text(tr("最近清理（%d）").format(tidyTrash.size), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = scheme.primary, modifier = Modifier.weight(1f).padding(horizontal = 4.dp))
                        TextButton(onClick = { MemoryTidy.clearTrash(context) }) { Text(tr("清空"), color = scheme.onSurfaceVariant) }
                    }
                    tidyTrash.forEachIndexed { i, t ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                            Column(Modifier.weight(1f)) {
                                Text(t.title, style = MaterialTheme.typography.bodySmall, color = scheme.onSurface, maxLines = 1)
                                Text(MemoryTidy.whyText(t.whyCode), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { scope.launch { MemoryTidy.restore(context, i) } }) {
                                Icon(Icons.Outlined.RestoreFromTrash, contentDescription = tr("恢复"), tint = scheme.primary, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        } }
        // 多选归档条：把选中记忆批量归到某角色卡（治「未归属/通用」记忆）
        if (selectMode) {
            // 走 FlowRow 而非 Row：三个按钮的译文比中文长得多（德/俄），挤一行会被推出屏幕外点不到，放不下就换行。
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(tr("已选 %d 条").format(selectedIds.size), color = scheme.primary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.align(Alignment.CenterVertically))
                TextButton(enabled = selectedIds.isNotEmpty(), onClick = {
                    cardPickerTitle = tr("把选中记忆归到"); cardPickerAllowNull = true
                    cardPicker = { cid -> scope.launch { selectedIds.toList().forEach { memoryManager.setCard(it, cid) }; selectedIds.clear(); selectMode = false } }
                }) { Text(tr("归到角色卡")) }
                TextButton(enabled = selectedIds.isNotEmpty(), onClick = { newFolderName = ""; folderPickerOpen = true }) { Text(tr("归入文件夹")) }
                TextButton(onClick = { selectMode = false; selectedIds.clear() }) { Text(tr("取消"), color = scheme.onSurfaceVariant) }
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { selectMode = true }) { Text(tr("多选归档"), color = scheme.onSurfaceVariant) }
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            // 「+ 新建」原来是定高 40dp 的文字按钮（定高 + 文字 = 长译文被切）。换成真 IconButton：
            // 图标不随译文变长，48dp 触摸区/水波纹/禁用态由组件给。
            FilledTonalIconButton(onClick = { openEditor(null) }) {
                Icon(Icons.Outlined.Add, contentDescription = tr("新建记忆"))
            }
            com.arix.app.ui.XtomField(
                value = searchQuery, onValueChange = { searchQuery = it },
                modifier = Modifier.weight(1f),
                placeholder = tr("搜索记忆..."),
                leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
            )
            // 「清除」文字按钮 → IconButton（省宽，且译文再长也不挤）
            if (searchQuery.isNotBlank()) IconButton(onClick = { searchQuery = "" }) {
                Icon(Icons.Outlined.Close, contentDescription = tr("清除"), tint = scheme.onSurfaceVariant)
            }
            // 列表 / 链球图 切换：手搓 Surface chip → 真 FilterChip
            FilterChip(
                selected = graphView,
                onClick = { graphView = !graphView; if (!graphView) { linkMode = false; pendingLink = null; graphRoot = null } },
                label = { Text(tr("链球图")) },
                leadingIcon = { Icon(if (graphView) Icons.AutoMirrored.Outlined.ViewList else Icons.Outlined.BubbleChart, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
            )
            // 维护工具开关：救援/状态卡/语义索引/导入导出 平时收起，点这里展开（默认让位给记忆内容）
            IconButton(onClick = { maintOpen = !maintOpen }) {
                Icon(Icons.Outlined.Healing, contentDescription = tr("记忆维护"), tint = if (maintOpen) scheme.primary else scheme.onSurfaceVariant)
            }
        }
        // 导入导出：跟维护工具一起收进 maintOpen（默认不显示，省顶部空间），带动画
        androidx.compose.animation.AnimatedVisibility(visible = maintOpen) { Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.End) {
            com.arix.app.ui.ImportExportButtons(
                context = context, scope = scope,
                fileBaseName = if (cardFilterAll) "memories_all" else "memories",
                produceJson = { ImportExport.exportMemories(context, if (cardFilterAll) null else cardFilterId) },
                consumeJson = { ImportExport.importMemories(it, context) },
                onResult = { msg -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() },
            )
        } }
        // 三排筛选：手搓 Surface chip → 真 FilterChip（选中态、水波纹、触摸区都是组件给的）。
        // 原来三排靠底色(primary/secondary/tertiary)区分「这排在筛什么」；FilterChip 统一配色后
        // 改由排首的图标点明：类型 / 角色卡 / 文件夹。
        MemFilterRow(icon = Icons.AutoMirrored.Outlined.Label) {
            MemFilterChip(tr("全部"), typeFilter == null) { typeFilter = null }
            typeLabels.forEach { (t, l) -> MemFilterChip(l, typeFilter == t) { typeFilter = t } }
        }
        // 标签筛选。标签一直在库里（MemoryTool 每次写记忆都在往里塞），但页面上从来没显示过——
        // 模型花 token 写的东西，人看不见也没法用。没有标签时整排不出现，不占空间。
        if (feat.tagUi && allTags.isNotEmpty()) {
            MemFilterRow(icon = Icons.Outlined.Sell) {
                MemFilterChip(tr("全部标签"), tagFilter == null) { tagFilter = null }
                allTags.forEach { t -> MemFilterChip(t.name, tagFilter == t.id) { tagFilter = if (tagFilter == t.id) null else t.id } }
            }
        }
        // 角色卡 / 文件夹筛选=高级筛选，跟维护工具一起收进 maintOpen（默认不显示，省顶部空间），带动画。
        androidx.compose.animation.AnimatedVisibility(visible = maintOpen) {
            Column {
                MemFilterRow(icon = Icons.Outlined.Person) {
                    MemFilterChip(tr("全部角色"), cardFilterAll) { cardFilterAll = true }
                    MemFilterChip(tr("通用/全局"), !cardFilterAll && cardFilterId == null) { cardFilterAll = false; cardFilterId = null }
                    cards.filter { !it.isDefault }.forEach { c -> MemFilterChip(c.name, !cardFilterAll && cardFilterId == c.id) { cardFilterAll = false; cardFilterId = c.id } }
                }
                if (folders.isNotEmpty()) {
                    MemFilterRow(icon = Icons.Outlined.Folder) {
                        MemFilterChip(tr("全部文件夹"), folderFilter == null) { folderFilter = null }
                        MemFilterChip(tr("未分组"), folderFilter == "") { folderFilter = "" }
                        folders.forEach { f -> MemFilterChip(f, folderFilter == f) { folderFilter = f } }
                    }
                }
            }
        }

        if (graphView) {
            // 类型色走主题令牌（primary/secondary/tertiary + 中性），黑白模式即灰阶不五彩、动态色模式即动态色
            val typeColor: (String) -> Color = { t -> when (t) { "preference" -> scheme.primary; "fact" -> scheme.onSurfaceVariant; "event" -> scheme.secondary; "relation" -> scheme.tertiary; "todo" -> scheme.primaryContainer; "lesson" -> scheme.error; "environment" -> scheme.secondaryContainer; "convention" -> scheme.tertiaryContainer; else -> scheme.surfaceContainerHighest } }
            // 来源色：用户最想在图上一眼看出的其实是「这条是我说的，还是它自己脑补的」。
            // 手写/工具写/对话抽取/硬信号/日记 各一色，其余走中性。
            val sourceColor: (String) -> Color = { s -> when (s) { "user_input" -> scheme.primary; "ai_tool" -> scheme.secondary; "auto_extract" -> scheme.tertiary; "hard_signal" -> scheme.primaryContainer; "lesson" -> scheme.error; "diary" -> scheme.secondaryContainer; "document" -> scheme.tertiaryContainer; else -> scheme.surfaceContainerHighest } }
            // 一个节点该是什么颜色：按类型（现状）还是按来源（开关决定）。
            // 判断写在 lambda 体内而不是 `if (…) { m -> … } else { m -> … }`——后者的花括号会被当成
            // if 的代码块而不是 lambda（本文件 memTrailing 处踩过同一个坑）。
            val nodeColor: (com.arix.data.entity.MemoryEntity) -> Color =
                { m -> if (feat.graphColorBySource) sourceColor(m.source) else typeColor(m.type) }
            // 局部图谱：从某条记忆出发只画它 N 跳内的邻居。全局 80 节点毛线球在手表上找不到任何东西，
            // 而这份邻域正是检索时 expandNeighbors 会走的那一片——看到的就是它实际能捞到的。
            if (graphRoot != null) {
                val rootTitle = remember(graphRoot, memories) { memories.firstOrNull { it.id == graphRoot }?.title ?: "" }
                MemFilterRow(icon = Icons.Outlined.Hub) {
                    MemFilterChip(tr("回到全局"), false) { graphRoot = null }
                    MemFilterChip(tr("1 跳"), graphDepth == 1) { graphDepth = 1 }
                    MemFilterChip(tr("2 跳"), graphDepth == 2) { graphDepth = 2 }
                }
                if (rootTitle.isNotBlank()) Text(
                    tr("只看「%s」附近").format(rootTitle),
                    color = scheme.primary, style = MaterialTheme.typography.bodySmall, maxLines = 2,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            // 连线模式（仅图谱里）：点两条记忆建立/断开关联。从上面的工具条挪到这儿——它只在图谱下有意义，
            // 挤在工具条里会把搜索框压没。
            MemFilterRow(icon = Icons.Outlined.Link) {
                MemFilterChip(tr("连线"), linkMode) { linkMode = !linkMode; pendingLink = null }
                MemFilterChip(tr("按来源着色"), feat.graphColorBySource) {
                    val n = feat.copy(graphColorBySource = !feat.graphColorBySource)
                    MemoryFeaturePrefs.save(context, n); feat = n
                }
            }
            if (linkMode) {
                Text(
                    if (pendingLink == null) tr("连线模式：点第一条记忆") else tr("再点一条 → 已关联则编辑边、否则按下方类型/权重建立关联"),
                    color = scheme.tertiary, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 4.dp)
                )
                // 新边的类型 + 权重（连线时套用；点已有边则弹窗编辑）
                MemFilterRow(icon = null) {
                    edgeTypeLabels.forEach { (t, l) -> MemFilterChip(l, edgeType == t) { edgeType = t } }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("权重 %.1f").format(edgeWeight), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    Slider(value = edgeWeight, onValueChange = { edgeWeight = it }, valueRange = 0.2f..3f, modifier = Modifier.weight(1f).padding(start = 8.dp))
                }
            }
            // 局部模式喂**全量** memories：邻居可能被上面的类型/角色卡/文件夹筛选挡在外面，
            // 而「这条记忆周围有什么」不该受筛选条影响。全局模式仍然只画筛出来的那批。
            MemoryGraph(
                if (graphRoot != null) memories else displayedMemories,
                nodeColor, scheme.outlineVariant, scheme.primary, scheme.onSurface,
                highlightId = pendingLink ?: graphRoot,
                rootId = graphRoot, depth = graphDepth,
            ) { m ->
                if (!linkMode) { openEditor(m); return@MemoryGraph }
                val a = pendingLink
                when {
                    a == null -> pendingLink = m.id
                    a == m.id -> pendingLink = null   // 再点自己=取消
                    else -> {
                        val b = m.id
                        pendingLink = null            // 立即清，防连点在协程 in-flight 期间又触发一次连线
                        scope.launch {
                            if (memoryManager.isLinked(a, b)) { edgeEditPair = a to b }   // 已关联：打开边编辑弹窗(改 type/weight 或断开)
                            else {
                                memoryManager.linkPair(a, b, edgeType, edgeWeight)
                                android.widget.Toast.makeText(context, tr("已建立关联（%s·权重%.1f）").format(edgeTypeLabel(edgeType), edgeWeight), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            }
        } else if (displayedMemories.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 24.dp)) {
                    Icon(Icons.AutoMirrored.Outlined.ViewList, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    // searching=搜索在途：说「搜索中」，别谎称「未找到」
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
            // 分组式列表：不再是一堆各自描边的卡片各飘各的，而是一整块——外圈大圆角、内部间距收紧、
            // 不用分割线（靠 2dp 缝隙分行）。行本体是真 ListItem（leading 类型图标 / headline 标题 /
            // supporting 正文预览+元信息 / trailing 置顶+删除）。
            // ListItem 在 material3 1.4.0 里没有 onClick 重载，所以外面套 Surface(onClick) 拿点击/水波纹/圆角裁剪，
            // ListItem 自身容器透明，配色由外层 Surface 决定。
            LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(displayedMemories.size, key = { displayedMemories[it].id }, contentType = { "memory" }) { idx ->
                    val mem = displayedMemories[idx]
                    val selected = selectedIds.contains(mem.id)
                    val cardName = mem.characterCardId?.let { cid -> cardById[cid]?.name }
                    val srcLabel = when (mem.source) { "ai_tool" -> tr("AI主动"); "auto_extract" -> tr("AI抽取"); else -> tr("手动") }
                    // 手搓的 ★/☆ 和「删除」小字换成真 IconButton：48dp 触摸区 + 正确裁剪的水波纹。
                    // 多选时不给 trailing——那时整行是选择目标，右边不该再有别的可点的东西。
                    // （外层 if 的 else 分支要再包一层花括号，否则 `else @Composable { … }` 会被当成代码块）
                    val memTrailing: (@Composable () -> Unit)? = if (selectMode) null else {
                        {
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
                    }
                    Surface(
                        onClick = { if (selectMode) { if (selected) selectedIds.remove(mem.id) else selectedIds.add(mem.id) } else openEditor(mem) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = memGroupShape(idx, displayedMemories.size),
                        color = if (selected) scheme.secondaryContainer else scheme.surfaceContainer,
                    ) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = {
                                if (selectMode) Checkbox(checked = selected, onCheckedChange = { if (selected) selectedIds.remove(mem.id) else selectedIds.add(mem.id) })
                                else Icon(memTypeIcon(mem.type), contentDescription = typeLabel(mem.type), tint = scheme.primary)
                            },
                            headlineContent = {
                                Text(mem.title, color = scheme.onSurface, fontWeight = FontWeight.Medium, maxLines = 2)
                            },
                            supportingContent = {
                                Column {
                                    // 不设 maxLines=1：这是这行唯一的内容说明，多语言下必被省略号吃掉
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
                                        if (cardName != null) Text(cardName, color = scheme.primary, style = MaterialTheme.typography.labelSmall, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
                                    }
                                    // 被新说法取代的旧条目：**没删，只是不再被检索到**。这一行是它唯一的可见处——
                                    // 自动归档敢自动跑的前提就是用户看得见、点一下就能撤。
                                    if (MemoryManager.isSuperseded(mem)) {
                                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                                            Icon(Icons.Outlined.History, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(12.dp))
                                            Spacer(Modifier.width(2.dp))
                                            Text(tr("已被更新的说法取代，不再参与检索"), color = scheme.tertiary,
                                                style = MaterialTheme.typography.labelSmall, maxLines = 2, modifier = Modifier.weight(1f))
                                            TextButton(
                                                onClick = { scope.launch { memoryManager.unarchiveSuperseded(mem.id) } },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                                                modifier = Modifier.heightIn(min = 32.dp),
                                            ) { Text(tr("恢复"), style = MaterialTheme.typography.labelSmall) }
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
        // 本地 val：下面几个面板要在 lambda 里用到非空 id，走局部变量拿智能转换最稳
        val edId: Long? = ed.id
        // 详情里的两块面板（关联 / 提到了但没连）都挂在这一条记忆上，换条就重置。
        var links by remember(ed.id) { mutableStateOf<List<Pair<com.arix.data.entity.MemoryEntity, MemEdge>>?>(null) }
        var linksRev by remember(ed.id) { mutableStateOf(0) }
        var mentions by remember(ed.id) { mutableStateOf<List<MemoryMentions.Mention>?>(null) }
        var mentionBusy by remember(ed.id) { mutableStateOf(false) }
        // 本次弹窗里刚连上的：立刻从「没连」列表里消失，不必等全表重扫
        val justLinked = remember(ed.id) { mutableStateListOf<Long>() }
        var newTag by remember(ed.id) { mutableStateOf("") }
        // 标签回填。**没加载完就不许写回去**：update(tags=null) 才是"不动标签"，
        // 加载失败或开关关着时保存，绝不能把模型花 token 写的标签清空。
        LaunchedEffect(ed.id, feat.tagUi) {
            val id = ed.id
            if (!feat.tagUi || id == null) return@LaunchedEffect
            val names = withContext(Dispatchers.IO) { memoryManager.getTagNames(id) }
            editor = editor?.let { if (it.id == id && !it.tagsLoaded) it.copy(tags = names, tagsLoaded = true) else it }
        }
        LaunchedEffect(ed.id, linksRev, feat.backlinks) {
            val id = ed.id
            if (!feat.backlinks || id == null) { links = null; return@LaunchedEffect }
            links = withContext(Dispatchers.IO) { memoryManager.relatedOf(id) }
        }
        // 扫描结果**只在缓存已经作数时**直接取（那是查表，零成本）；缓存过期就等用户点「扫一下」，
        // 绝不因为打开了一条记忆就去全表扫一遍（见 MemoryMentions 的性能设计）。
        LaunchedEffect(ed.id, memories, feat.mentions, feat.mentionMinLen) {
            val id = ed.id
            if (!feat.mentions || id == null) { mentions = null; return@LaunchedEffect }
            // 已经有结果就别再动：刚点完「连上」会改 updatedAt → 缓存失效 → 这里一清，
            // 用户眼前的列表就整块消失了。已连上的那几条由 justLinked 就地划掉即可。
            if (mentions != null) return@LaunchedEffect
            if (MemoryMentions.isFresh(memories, feat.mentionMinLen))
                mentions = MemoryMentions.forMemory(memories, feat.mentionMinLen, id)
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editor = null },
            title = { Text(if (ed.id == null) tr("新建记忆") else tr("编辑记忆"), color = scheme.onSurface) },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    com.arix.app.ui.XtomField(value = ed.title, onValueChange = { editor = ed.copy(title = it) },
                        label = tr("标题"), modifier = Modifier.fillMaxWidth())
                    Spacer(modifier = Modifier.height(6.dp))
                    com.arix.app.ui.XtomField(value = ed.content, onValueChange = { editor = ed.copy(content = it) },
                        label = tr("内容"), modifier = Modifier.fillMaxWidth(), singleLine = false, minLines = 4, maxLines = 5)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(tr("类型"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    // 编辑器里的类型选择也换成真 FilterChip（带选中勾），跟上面的筛选一个观感
                    MemFilterRow(icon = null) {
                        typeLabels.forEach { (t, l) -> MemFilterChip(l, ed.type == t) { editor = ed.copy(type = t) } }
                    }
                    Text(tr("重要度 %.1f").format(ed.importance), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    Slider(value = ed.importance, onValueChange = { editor = ed.copy(importance = it) }, valueRange = 0f..1f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = ed.pinned, onCheckedChange = { editor = ed.copy(pinned = it) })
                        Text(tr("置顶（始终注入）"), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
                    }
                    // 文件夹：以前改单条得先「多选归档」再走批量流程，这里直接改。
                    Spacer(Modifier.height(4.dp))
                    Text(tr("文件夹"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    MemFilterRow(icon = null) {
                        MemFilterChip(tr("未分组"), ed.folder.isBlank()) { editor = ed.copy(folder = "") }
                        folders.forEach { f -> MemFilterChip(f, ed.folder == f) { editor = ed.copy(folder = f) } }
                    }
                    com.arix.app.ui.XtomField(
                        value = ed.folder, onValueChange = { editor = ed.copy(folder = it) },
                        placeholder = tr("或直接输入文件夹名"), singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    // 标签
                    if (feat.tagUi) {
                        Spacer(Modifier.height(8.dp))
                        Text(tr("标签"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                        if (ed.tags.isNotEmpty()) androidx.compose.foundation.layout.FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            ed.tags.forEach { t ->
                                MemTagChip(t) { editor = ed.copy(tags = ed.tags - t, tagsLoaded = true) }
                            }
                        }
                        val addTag = {
                            val t = newTag.trim()
                            if (t.isNotBlank() && t !in ed.tags) editor = ed.copy(tags = ed.tags + t, tagsLoaded = true)
                            newTag = ""
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            com.arix.app.ui.XtomField(
                                value = newTag, onValueChange = { newTag = it }, placeholder = tr("新标签"),
                                singleLine = true, modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = addTag) { Icon(Icons.Outlined.Add, contentDescription = tr("添加标签"), tint = scheme.primary) }
                        }
                    }
                    // 关联（N）：读 relatedIds + relatedMeta。superseded_by 单独标出来——
                    // 冲突消解会自动打这种边并降权，用户在别处根本看不到「谁把我这条顶掉了」，
                    // 而看得见、撤得掉正是让自动逻辑敢自动跑的前提。
                    if (feat.backlinks && edId != null) {
                        val ls = links
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.AccountTree, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (ls == null) tr("关联") else tr("关联（%d）").format(ls.size),
                                color = scheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            // 局部图谱：只画这条记忆两跳内的邻居，不是全局那团毛线球
                            if (feat.localGraph) TextButton(onClick = {
                                graphRoot = edId; graphDepth = 2; graphView = true; linkMode = false; pendingLink = null
                                editor = null
                            }) { Text(tr("看关系"), style = MaterialTheme.typography.labelMedium) }
                        }
                        when {
                            ls == null -> Text(tr("读取中…"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            ls.isEmpty() -> Text(tr("还没有关联。相关的记忆连起来，AI 才能顺着找到它们。"),
                                color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            else -> ls.forEach { (other, edge) ->
                                MemLinkRow(
                                    title = other.title,
                                    edgeLabel = edgeTypeLabel(edge.type),
                                    weight = edge.weight,
                                    archived = edge.type == MemoryManager.EDGE_SUPERSEDED_BY,
                                    onOpen = { openEditor(other) },
                                    onUnlink = { scope.launch { memoryManager.unlinkPair(edId, other.id); linksRev++ } },
                                    onRestore = if (edge.type != MemoryManager.EDGE_SUPERSEDED_BY) null else {
                                        { scope.launch { memoryManager.unarchiveSuperseded(edId); linksRev++ } }
                                    },
                                )
                            }
                        }
                    }
                    // 提到了但没连：完全离线、零 LLM、零网络。只列出来问，绝不自动建边。
                    if (feat.mentions && edId != null) {
                        val ms = mentions?.filter { it.id !in justLinked }
                        Spacer(Modifier.height(10.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.AddLink, contentDescription = null, tint = scheme.tertiary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (ms == null) tr("提到了但没连") else tr("提到了但没连（%d）").format(ms.size),
                                color = scheme.tertiary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            if (ms == null) TextButton(enabled = !mentionBusy, onClick = {
                                mentionBusy = true
                                scope.launch {
                                    mentions = MemoryMentions.forMemory(memories, feat.mentionMinLen, edId)
                                    mentionBusy = false
                                }
                            }) { Text(if (mentionBusy) tr("扫描中…") else tr("扫一下"), style = MaterialTheme.typography.labelMedium) }
                        }
                        when {
                            ms == null -> Text(tr("在标题和正文里找出现过的其它记忆标题。不联网、不调模型，只列出来问你。"),
                                color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            ms.isEmpty() -> Text(tr("没找到可补的关联。"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            else -> {
                                ms.forEach { mt ->
                                    MemMentionRow(
                                        title = mt.title,
                                        reason = if (mt.incoming) tr("它的内容里提到了这条") else tr("这条的内容里提到了它"),
                                        onOpen = { scope.launch { memoryManager.getById(mt.id)?.let { openEditor(it) } } },
                                        onLink = {
                                            justLinked.add(mt.id)
                                            scope.launch { memoryManager.linkPair(edId, mt.id, "related", 1.0f); linksRev++ }
                                        },
                                    )
                                }
                                if (ms.size > 1) TextButton(onClick = {
                                    val ids = ms.map { it.id }
                                    justLinked.addAll(ids)
                                    scope.launch { ids.forEach { memoryManager.linkPair(edId, it, "related", 1.0f) }; linksRev++ }
                                }) { Text(tr("全部连上（%d）").format(ms.size), style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val eid = ed.id
                        // tags 传 null = 不动标签（没加载完/开关关着时的安全值），传列表才会整批替换
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

    // 关联边编辑：改 type/weight 或断开（点图谱里已存在的边打开）
    edgeEditPair?.let { pair ->
        val a = pair.first; val b = pair.second
        var et by remember(a, b) { mutableStateOf("related") }
        var ew by remember(a, b) { mutableStateOf(1.0f) }
        var aTitle by remember(a, b) { mutableStateOf("") }
        var bTitle by remember(a, b) { mutableStateOf("") }
        LaunchedEffect(a, b) {
            val e = memoryManager.edgeMeta(a, b); et = e.type; ew = e.weight
            aTitle = memoryManager.getById(a)?.title ?: ""; bTitle = memoryManager.getById(b)?.title ?: ""
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { edgeEditPair = null },
            title = { Text(tr("编辑关联边"), color = scheme.onSurface, style = MaterialTheme.typography.titleMedium) },
            text = { Column {
                Text("$aTitle ↔ $bTitle", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Text(tr("类型"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                MemFilterRow(icon = null) {
                    edgeTypeLabels.forEach { (t, l) -> MemFilterChip(l, et == t) { et = t } }
                }
                Text(tr("权重 %.1f").format(ew), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                Slider(value = ew, onValueChange = { ew = it }, valueRange = 0.2f..3f)
            } },
            confirmButton = { TextButton(onClick = { scope.launch { memoryManager.linkPair(a, b, et, ew) }; edgeEditPair = null }) { Text(tr("保存"), color = scheme.primary) } },
            dismissButton = { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { scope.launch { memoryManager.unlinkPair(a, b) }; edgeEditPair = null }) { Text(tr("断开"), color = scheme.error) }
                TextButton(onClick = { edgeEditPair = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) }
            } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )
    }

    // 共享角色卡选择弹窗（联合救援 / 多选归档 共用）
    cardPicker?.let { onPick ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { cardPicker = null },
            title = { Text(cardPickerTitle, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium) },
            text = {
                // 定高 300dp → heightIn(max=)：条目少时别撑出一片空白，译文长时也不会被钉死的高度切掉
                LazyColumn(modifier = Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    val extra = if (cardPickerAllowNull) 1 else 0
                    if (cardPickerAllowNull) item {
                        MemPickerRow(tr("通用（无归属）"), Icons.Outlined.Person, memGroupShape(0, cards.size + 1)) { onPick(null); cardPicker = null }
                    }
                    items(cards.size, key = { cards[it].id }, contentType = { "card" }) { i ->
                        val c = cards[i]
                        MemPickerRow(
                            c.name + (if (c.isDefault) tr("（默认）") else ""),
                            Icons.Outlined.Person,
                            memGroupShape(i + extra, cards.size + extra),
                        ) { onPick(c.id); cardPicker = null }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { cardPicker = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )
    }

    // 文件夹归入弹窗（多选归入共用；参照上面「多选归档到角色卡」）：选已有 / 新建 / 移出未分组
    if (folderPickerOpen) {
        val applyFolder: (String) -> Unit = { name ->
            val ids = selectedIds.toList()
            scope.launch { ids.forEach { memoryManager.setFolder(it, name) }; selectedIds.clear(); selectMode = false }
            folderPickerOpen = false
        }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { folderPickerOpen = false },
            title = { Text(tr("把选中记忆归入文件夹"), color = scheme.onSurface, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column {
                    LazyColumn(modifier = Modifier.heightIn(max = 240.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        item { MemPickerRow(tr("未分组（移出）"), Icons.Outlined.Folder, memGroupShape(0, folders.size + 1)) { applyFolder("") } }
                        items(folders.size, key = { folders[it] }, contentType = { "folder" }) { i ->
                            val f = folders[i]
                            MemPickerRow(f, Icons.Outlined.Folder, memGroupShape(i + 1, folders.size + 1)) { applyFolder(f) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    com.arix.app.ui.XtomField(value = newFolderName, onValueChange = { newFolderName = it }, placeholder = tr("新建文件夹名"), singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Button(enabled = newFolderName.isNotBlank(), onClick = { applyFolder(newFolderName.trim()) }, modifier = Modifier.fillMaxWidth()) { Text(tr("新建并归入")) }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { folderPickerOpen = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )
    }
}

// ============================================================
// 列表侧的小零件。规矩同 SettingsKit：只用主题令牌、字号走排版尺度不写 10sp/11sp、
// 图标一律 Material 矢量图（不用 emoji）、带文字的东西不定高。
// ============================================================

/** 编辑器状态。null=编辑器关闭。收成一个对象，省得 7 个 remember 各改各的、漏一个就串内容。 */
@Immutable
private data class MemoryEditorState(
    val id: Long? = null,
    val title: String = "",
    val content: String = "",
    val type: String = "fact",
    val importance: Float = 0.5f,
    val pinned: Boolean = false,
    /** 文件夹（""=未分组）。以前改单条得走「多选归档」的批量流程。 */
    val folder: String = "",
    val tags: List<String> = emptyList(),
    /**
     * 标签**读回来过没有**。false 时保存一律传 `tags=null`（=不动标签）——
     * 标签是 MemoryTool 一直在写的东西，拿一份空列表覆盖上去等于把模型花 token 记的全清了。
     * 新建记忆时列表本来就该是空的，保存走 add(tags=…) 那条路，不看这个标志。
     */
    val tagsLoaded: Boolean = false,
)

/** 搜索结果 + 它对应的查询词。带着 query 才能判断「结果是不是当前这次查询的」，从而不误报「未找到」。 */
@Immutable
private data class MemorySearchState(
    val query: String = "",
    val results: List<com.arix.data.entity.MemoryEntity> = emptyList(),
)

/** 记忆类型 → Material 矢量图标（列表行的 leadingContent）。 */
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

/**
 * 分组列表的圆角：整组像一整块——首行上圆角大、末行下圆角大、中间小圆角，
 * 靠 2dp 缝隙分行，不用分割线。只有一行时四角都大。
 */
private fun memGroupShape(index: Int, count: Int): RoundedCornerShape {
    val big = 20.dp
    val small = 4.dp
    val top = if (index == 0) big else small
    val bottom = if (index == count - 1) big else small
    return RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
}

/** 一排横滑的筛选 chip，排首用图标点明这排在筛什么（类型/角色卡/文件夹）。 */
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

/** 真 FilterChip（选中打勾），替代原来手搓的 Surface+Text 假 chip。 */
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

/** 标签芯片：点一下删掉（右边就是叉，点了干什么一目了然）。用真 FilterChip，跟全页一个观感。 */
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

/**
 * 「关联」面板里的一行：点标题跳过去，右边断开。
 * @param archived 这条边是 superseded_by —— 意思是**对面那条把我这条顶掉了**，
 *   我不再参与检索。这是用户唯一能看到自动归档发生过的地方，所以要单独标出来并给「恢复」。
 */
@Composable
private fun MemLinkRow(
    title: String,
    edgeLabel: String,
    weight: Float,
    archived: Boolean,
    onOpen: () -> Unit,
    onUnlink: () -> Unit,
    onRestore: (() -> Unit)?,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                if (archived) Icons.Outlined.History else Icons.Outlined.Link,
                contentDescription = null,
                tint = if (archived) scheme.tertiary else scheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(6.dp))
            Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(vertical = 4.dp)) {
                Text(title, color = scheme.onSurface, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                Text(
                    if (archived) tr("已被取代 · 这条不再参与检索") else tr("%s · 权重 %.1f").format(edgeLabel, weight),
                    color = if (archived) scheme.tertiary else scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall, maxLines = 2,
                )
            }
            IconButton(onClick = onUnlink) {
                Icon(Icons.Outlined.LinkOff, contentDescription = tr("断开"), tint = scheme.error, modifier = Modifier.size(18.dp))
            }
        }
        if (onRestore != null) Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = onRestore,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier.heightIn(min = 32.dp),
            ) { Text(tr("恢复这条"), style = MaterialTheme.typography.labelSmall) }
        }
    }
}

/** 「提到了但没连」面板里的一行：说清为什么列出来，右边一个「连上」。绝不自动连。 */
@Composable
private fun MemMentionRow(title: String, reason: String, onOpen: () -> Unit, onLink: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable(onClick = onOpen).padding(vertical = 4.dp)) {
            Text(title, color = scheme.onSurface, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            Text(reason, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 2)
        }
        TextButton(
            onClick = onLink,
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
            modifier = Modifier.heightIn(min = 32.dp),
        ) { Text(tr("连上"), style = MaterialTheme.typography.labelMedium) }
    }
}

/** 选择弹窗里的一行（角色卡 / 文件夹）：真 ListItem + Surface(onClick)，跟主列表一个观感。 */
@Composable
private fun MemPickerRow(label: String, icon: ImageVector, shape: RoundedCornerShape, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = shape, color = scheme.surfaceContainer) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { Icon(icon, contentDescription = null, tint = scheme.primary) },
            headlineContent = { Text(label, color = scheme.onSurface, maxLines = 2) },
        )
    }
}

/**
 * 分组卡里的一条可展开工具行：左图标 + 标题/副标题，右边 展开/收起 箭头。
 * 视觉与 SettingsKit 的 SettingsRow 对齐（同样的图标尺寸/排版尺度/圆角水波纹），
 * 只是把右侧的 chevron 换成展开态箭头、下面挂自己的内容。
 */
@Composable
private fun MemToolRow(
    icon: ImageVector,
    title: String,
    subtitle: String? = null,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))   // 先 clip 再 clickable，水波纹才是圆角的
                .clickable(onClick = onToggle)
                .padding(horizontal = 4.dp, vertical = 10.dp)
                .heightIn(min = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                // 不设 maxLines=1：副标题是这行唯一的解释，多语言下必然被省略号吃掉
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2)
            }
            Spacer(Modifier.width(8.dp))
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (expanded) tr("收起") else tr("展开"),
                tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp),
            )
        }
        if (expanded) Column(modifier = Modifier.fillMaxWidth().padding(start = 32.dp, bottom = 8.dp), content = content)
    }
}


// 记忆图谱：对齐 Operit 观感——节点是圆角矩形文字 chip（标签在框内）+ 底层阴影 + 类型色边框，
// 关联(relatedIds)走虚线跨链、结构(同类型序列)走实线。**性能取向**（Operit 那个每次都重跑实时力模拟+逐节点
// withTransform 缩放 → 卡；这里不抄它的物理）：布局一次算好(remember)、标签测量一次缓存、关联边预解析成
// 索引对(绝不在每帧 split 字符串)、chip 尺寸固定只平移缩放位置(不做逐节点 transform)。类型色边框保留 Arix
// 的分类信息(Operit 是纯灰 chip)；调色板用主题令牌达成同款观感，非照搬其硬编码 hex。
private data class MemGNode(val id: Long, val x: Float, val y: Float, val prevId: Long)   // 只存位置；渲染时按 id 取最新记忆
private data class MemGChip(val w: Float, val h: Float)   // 未缩放 chip 尺寸(px)，由标签测量得
// 稳定布局：节点(初始圆周) + 边(供实时力模拟) + 参数。只在加/删记忆时重算。
private class MemGLayout(val nodes: List<MemGNode>, val ea: IntArray, val eb: IntArray, val es: BooleanArray, val kIdeal: Float, val px: FloatArray, val py: FloatArray)
/**
 * 从 [rootId] 出发沿 relatedIds 走 [depth] 跳，返回该邻域的 id 集（含 root 自己）。
 *
 * 规则**刻意和 [MemoryManager] 的 expandNeighbors 对齐**（同一份 relatedIds、同样两跳、总数封顶），
 * 所以图上看到的就是检索时真能捞到的那一片。差别只有两点，都是"看"和"用"的区别：
 * 这里不打分（只要节点集），也不隐藏被归档的旧说法——"谁把我这条顶掉了"正是要看的东西。
 * 纯内存计算：调用方在局部模式下喂的是全量 memories，邻居一定在这份列表里，不必再回库查。
 */
private fun memNeighborhood(
    memories: List<com.arix.data.entity.MemoryEntity>,
    rootId: Long, depth: Int, max: Int = 40,
): Set<Long> {
    val byId = memories.associateBy { it.id }
    val out = LinkedHashSet<Long>()
    out.add(rootId)
    var frontier = listOf(rootId)
    repeat(depth.coerceAtLeast(1)) {
        if (frontier.isEmpty() || out.size >= max) return@repeat
        val next = ArrayList<Long>()
        for (id in frontier) {
            val m = byId[id] ?: continue
            for (r in m.relatedIds.split(",")) {
                val rid = r.trim().toLongOrNull() ?: continue
                if (rid in out || rid !in byId) continue
                if (out.size >= max) break
                out.add(rid); next.add(rid)
            }
        }
        frontier = next
    }
    return out
}

@Composable
private fun MemoryGraph(
    memories: List<com.arix.data.entity.MemoryEntity>,
    nodeColor: (com.arix.data.entity.MemoryEntity) -> Color,
    edgeColor: Color, pinRing: Color, labelColor: Color,
    highlightId: Long? = null,          // 连线模式下已选中的第一条记忆，高亮描边
    rootId: Long? = null,               // 非 null=局部图谱：只画这条记忆 depth 跳内的邻居
    depth: Int = 2,
    onTap: (com.arix.data.entity.MemoryEntity) -> Unit,
) {
    // 局部模式先把节点集收窄到邻域，再交给下面原样的布局/力模拟/渲染——渲染侧一行不用改。
    //
    // key **不能直接用 memories**：List<MemoryEntity> 的相等是逐元素深比较，会把每条记忆的
    // embedding 大字符串都比一遍。用「id 集 + 边的长度和」当身份：前者认加/删记忆，
    // 后者认「连了一条新边」（局部图谱得跟着长出来；全局模式用不上，省掉这次 O(n)）。
    val edgeSig = if (rootId == null) 0L else memories.sumOf { it.relatedIds.length.toLong() }
    val scoped = remember(memories.mapTo(HashSet<Long>()) { it.id }, rootId, depth, edgeSig) {
        if (rootId == null) memories else {
            val ids = memNeighborhood(memories, rootId, depth)
            memories.filter { it.id in ids }
        }
    }
    // 布局（节点表+边+初始圆周位置）按「节点 id 集」缓存：只在加/删记忆时重算。力模拟在下面**逐帧**跑（照 Operit）。
    val layout = remember(scoped.mapTo(HashSet<Long>()) { it.id }) {
        val mems = scoped.sortedByDescending { it.importance }.take(80)
        val n = mems.size
        val idIndex = HashMap<Long, Int>(); mems.forEachIndexed { i, m -> idIndex[m.id] = i }
        val ea = ArrayList<Int>(); val eb = ArrayList<Int>(); val es = ArrayList<Boolean>()
        val prevOf = LongArray(n) { -1L }
        mems.withIndex().groupBy { it.value.type.ifBlank { "fact" } }.values.forEach { grp ->
            val gs = grp.sortedByDescending { it.value.importance }
            for (k in 1 until gs.size) { ea.add(gs[k - 1].index); eb.add(gs[k].index); es.add(false); prevOf[gs[k].index] = mems[gs[k - 1].index].id }
        }
        mems.forEachIndexed { i, m -> m.relatedIds.split(",").mapNotNull { it.trim().toLongOrNull() }.forEach { rid -> idIndex[rid]?.let { j -> ea.add(i); eb.add(j); es.add(true) } } }
        val px = FloatArray(n); val py = FloatArray(n)
        for (i in 0 until n) { val a = (2 * Math.PI * i / n.coerceAtLeast(1)).toFloat(); px[i] = 0.5f + 0.30f * kotlin.math.cos(a); py[i] = 0.5f + 0.30f * kotlin.math.sin(a) }
        val kIdeal = (1.7f / kotlin.math.sqrt(n.coerceAtLeast(1).toFloat())).coerceIn(0.13f, 0.40f)   // 加大理想间距：节点铺得更开、别堆一坨
        MemGLayout(mems.mapIndexed { i, m -> MemGNode(m.id, px[i], py[i], prevOf[i]) }, ea.toIntArray(), eb.toIntArray(), es.toBooleanArray(), kIdeal, px, py)
    }
    val nodes = layout.nodes
    val nCount = nodes.size
    // 实时工作位置（力模拟逐帧改 / 真人拖节点也改）。FloatArray 不触发重组，靠 frame 状态驱动 Canvas 重绘。
    val posX = remember(layout) { layout.px.copyOf() }
    val posY = remember(layout) { layout.py.copyOf() }
    var frame by remember(layout) { mutableStateOf(0) }
    var reheat by remember(layout) { mutableStateOf(0) }   // 拖完/需要时重新点燃力模拟
    // 照 Operit：Fruchterman-Reingold 力导向，一帧一迭代——看着从圆环散开成关系网、沉降(平均位移很小)后自动停，不常烧 CPU。
    LaunchedEffect(layout, reheat) {
        if (nCount == 0) return@LaunchedEffect
        val ea = layout.ea; val eb = layout.eb; val es = layout.es; val k = layout.kIdeal
        val maxIter = 260; var iter = 0
        while (iter < maxIter && isActive) {
            val fx = FloatArray(nCount); val fy = FloatArray(nCount)
            for (i in 0 until nCount) for (j in i + 1 until nCount) {
                var dx = posX[i] - posX[j]; var dy = posY[i] - posY[j]
                var d2 = dx * dx + dy * dy; if (d2 < 1e-5f) { dx = 1e-3f; dy = 0f; d2 = 1e-6f }
                val d = kotlin.math.sqrt(d2); val rep = k * k / d; val ux = dx / d; val uy = dy / d
                fx[i] += ux * rep; fy[i] += uy * rep; fx[j] -= ux * rep; fy[j] -= uy * rep
            }
            for (e in ea.indices) {
                val a = ea[e]; val b = eb[e]
                val dx = posX[a] - posX[b]; val dy = posY[a] - posY[b]
                val d = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
                val att = d * d / k * (if (es[e]) 1.4f else 0.10f); val ux = dx / d; val uy = dy / d
                fx[a] -= ux * att; fy[a] -= uy * att; fx[b] += ux * att; fy[b] += uy * att
            }
            val temp = 0.05f * (1f - iter.toFloat() / maxIter); var moved = 0f
            for (i in 0 until nCount) {
                val fd = kotlin.math.sqrt(fx[i] * fx[i] + fy[i] * fy[i]).coerceAtLeast(1e-5f)
                val mvx = fx[i] / fd * kotlin.math.min(fd, temp); val mvy = fy[i] / fd * kotlin.math.min(fd, temp)
                posX[i] += mvx; posY[i] += mvy
                posX[i] += (0.5f - posX[i]) * 0.004f; posY[i] += (0.5f - posY[i]) * 0.004f
                moved += kotlin.math.abs(mvx) + kotlin.math.abs(mvy)
            }
            // 每帧居中+定标铺满画布。**用离质心的均方根(RMS)半径定标，而不是 min/max 包围盒**——
            // 后者只要有一两个弱连接节点飞出去，就会把主群整体压成中间一坨（「堆在一起不自然」的根因）。
            // RMS 对离群点稳健：主群按 RMS 半径铺开充满画布，个别离群点自己飘远（顶多贴边），不再挤压主群。
            var mcx = 0f; var mcy = 0f
            for (i in 0 until nCount) { mcx += posX[i]; mcy += posY[i] }
            mcx /= nCount; mcy /= nCount
            var rms = 0f
            for (i in 0 until nCount) { val ddx = posX[i] - mcx; val ddy = posY[i] - mcy; rms += ddx * ddx + ddy * ddy }
            rms = kotlin.math.sqrt(rms / nCount).coerceAtLeast(1e-3f)
            val s = 0.30f / rms   // 目标 RMS 半径 ~0.30（主群铺到画布多半区域，留边距）
            for (i in 0 until nCount) { posX[i] = 0.5f + (posX[i] - mcx) * s; posY[i] = 0.5f + (posY[i] - mcy) * s }
            frame++; iter++
            if (iter > 45 && moved / nCount < 0.0005f) break   // 沉降了→停
            withFrameNanos { }
        }
    }
    val scheme = MaterialTheme.colorScheme
    // 位置固定(按 id 集缓存)，但边/标签/chip 按最新记忆内容渲染 → 连线增删、置顶/标题改动即时反映而位置不动
    val memById = remember(scoped) { scoped.associateBy { it.id } }
    val idIndex = remember(nodes) { HashMap<Long, Int>(nodes.size * 2).apply { nodes.forEachIndexed { i, n -> put(n.id, i) } } }
    // 关联边：随最新 relatedIds 重算(不在每帧 split)，无向去重，虚线只画一遍。带 type/weight（权重定粗细、类型定颜色）
    val relEdges = remember(nodes, memById) {
        val seen = HashSet<Long>()
        val out = ArrayList<Triple<Int, Int, MemEdge>>()
        nodes.forEachIndexed { i, n ->
            val m = memById[n.id] ?: return@forEachIndexed
            val meta = MemEdges.parse(m.relatedMeta)
            m.relatedIds.split(",").mapNotNull { it.trim().toLongOrNull() }.forEach { rid ->
                idIndex[rid]?.let { j -> if (j != i && seen.add(minOf(i, j).toLong() shl 20 or maxOf(i, j).toLong())) out.add(Triple(i, j, meta[rid] ?: MemEdge())) }
            }
        }
        out
    }
    val structEdges = remember(nodes) {
        val out = ArrayList<Pair<Int, Int>>()
        nodes.forEachIndexed { i, n -> if (n.prevId >= 0) idIndex[n.prevId]?.let { j -> out.add(i to j) } }
        out
    }
    // chip 标签测量并缓存（Operit 式：文字在框内）；随标题变化重测
    val measurer = rememberTextMeasurer()
    val padX = 11f; val padY = 5f
    val labels = remember(nodes, memById, labelColor) {
        nodes.map { measurer.measure(AnnotatedString((memById[it.id]?.title ?: "").ifBlank { "·" }.take(10)), style = androidx.compose.ui.text.TextStyle(fontSize = 10.sp, color = labelColor)) }
    }
    val chips = remember(labels) { labels.map { MemGChip(it.size.width + padX * 2, it.size.height + padY * 2) } }
    // Operit chip 观感：浅底层做层次 + 中性灰 fill + 类型色边框（用主题令牌，深浅色自适应，非照搬 hex）
    val fillColor = scheme.surfaceContainerHigh
    val underlayColor = scheme.surfaceContainerHighest
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f) }

    var scale by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    Canvas(modifier = Modifier.fillMaxSize().clipToBounds()
        // 统一手势：1 指按在节点上=拖该节点(直接改 posX/posY 实时位置) / 空白=平移 / 2 指=缩放+平移 / 没拖+点节点=打开
        .pointerInput(nodes, chips) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val w = size.width.toFloat(); val h = size.height.toFloat(); val base = kotlin.math.min(w, h)
                fun hit(pos: Offset): Int {
                    var idx = -1
                    for (i in nodes.indices) {
                        val ccx = w / 2f + (posX[i] - 0.5f) * base * scale + pan.x
                        val ccy = h / 2f + (posY[i] - 0.5f) * base * scale + pan.y
                        val c = chips[i]
                        if (pos.x in (ccx - c.w / 2f)..(ccx + c.w / 2f) && pos.y in (ccy - c.h / 2f)..(ccy + c.h / 2f)) idx = i
                    }
                    return idx
                }
                val hitIdx = hit(down.position)
                var dragged = false
                while (true) {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.isEmpty()) break
                    if (pressed.size >= 2) {
                        dragged = true
                        scale = (scale * ev.calculateZoom()).coerceIn(0.5f, 4f)
                        val np = pan + ev.calculatePan(); val mx = w * scale * 0.6f; val my = h * scale * 0.6f
                        pan = Offset(np.x.coerceIn(-mx, mx), np.y.coerceIn(-my, my))
                        pressed.forEach { it.consume() }
                    } else {
                        val ch = pressed[0]; val delta = ch.positionChange()
                        if (!dragged && delta.getDistance() > 6f) dragged = true
                        if (dragged) {
                            if (hitIdx >= 0 && hitIdx < nCount) {
                                posX[hitIdx] += delta.x / (base * scale); posY[hitIdx] += delta.y / (base * scale); frame++
                            } else {
                                val np = pan + delta; val mx = w * scale * 0.6f; val my = h * scale * 0.6f
                                pan = Offset(np.x.coerceIn(-mx, mx), np.y.coerceIn(-my, my))
                            }
                            ch.consume()
                        }
                    }
                }
                if (!dragged && hitIdx >= 0) memById[nodes[hitIdx].id]?.let { onTap(it) }
            }
        }
    ) {
        frame.let { }   // 读 frame → 力模拟每帧改 posX/posY + frame++ 时触发重绘
        val w = size.width; val h = size.height; val base = kotlin.math.min(w, h)
        val n = nodes.size
        val cx = FloatArray(n); val cy = FloatArray(n)
        for (i in 0 until n) { cx[i] = w / 2f + (posX[i] - 0.5f) * base * scale + pan.x; cy[i] = h / 2f + (posY[i] - 0.5f) * base * scale + pan.y }
        // 边：结构=细实线，关联(relatedIds)=虚线跨链(Operit 式)。都连 chip 中心。
        structEdges.forEach { (a, b) -> drawLine(edgeColor.copy(alpha = 0.5f), Offset(cx[a], cy[a]), Offset(cx[b], cy[b]), strokeWidth = 2f) }
        relEdges.forEach { (a, b, ed) ->
            // 类型→色（related=中性 / causes=主色 / part_of=三级色），权重→线宽
            val col = when (ed.type) { "causes" -> scheme.primary; "part_of" -> scheme.tertiary; else -> edgeColor }
            drawLine(col.copy(alpha = 0.9f), Offset(cx[a], cy[a]), Offset(cx[b], cy[b]), strokeWidth = 1.6f + ed.weight.coerceIn(0.2f, 3f) * 1.4f, pathEffect = dash)
        }
        // 节点 chip：底层阴影 + 灰底 + 类型色边框 + 框内文字；置顶=pinRing 外环
        for (i in 0 until n) {
            val node = nodes[i]; val m = memById[node.id] ?: continue; val c = chips[i]
            val tlx = cx[i] - c.w / 2f; val tly = cy[i] - c.h / 2f
            val r = kotlin.math.min(c.h * 0.48f, 18f)
            val corner = CornerRadius(r, r); val sz = Size(c.w, c.h)
            drawRoundRect(underlayColor, topLeft = Offset(tlx, tly + 1.5f), size = sz, cornerRadius = corner)
            drawRoundRect(fillColor, topLeft = Offset(tlx, tly), size = sz, cornerRadius = corner)
            val selected = node.id == highlightId
            drawRoundRect(if (selected) pinRing else nodeColor(m), topLeft = Offset(tlx, tly), size = sz, cornerRadius = corner, style = Stroke(width = if (selected || m.pinned) 2.6f else 1.6f))
            if (selected) drawRoundRect(pinRing, topLeft = Offset(tlx - 4f, tly - 4f), size = Size(c.w + 8f, c.h + 8f), cornerRadius = CornerRadius(r + 4f, r + 4f), style = Stroke(width = 2.4f))
            else if (m.pinned) drawRoundRect(pinRing, topLeft = Offset(tlx - 3f, tly - 3f), size = Size(c.w + 6f, c.h + 6f), cornerRadius = CornerRadius(r + 3f, r + 3f), style = Stroke(width = 2f))
            drawText(labels[i], topLeft = Offset(tlx + padX, tly + padY))
        }
    }
}
