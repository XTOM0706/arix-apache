package com.arix.app

import com.arix.tool.ImportExport
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.PushPin
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.SettingsToggle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.model.ChatMessage
import kotlinx.coroutines.launch
import com.arix.app.ui.topChromeGapHeight

// 世界书管理：管理"世界背景"。UI 风格与角色卡一致（莫奈 + 大卡片 + 描边）。
@OptIn(ExperimentalMaterial3Api::class)
@Composable fun WorldTreePage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    val accents = com.arix.app.theme.LocalXtomAccents.current
    var trees by remember { mutableStateOf(WorldTreeStore.all(context)) }
    fun reload() { trees = WorldTreeStore.all(context) }

    // 编辑器状态：一本书 = 基本信息 + 一串条目；条目各自的设置在下面的「条目编辑器」子弹窗里改
    var showEditor by remember { mutableStateOf(false) }
    var editId by remember { mutableStateOf(0L) }
    var eName by remember { mutableStateOf("") }
    var eDesc by remember { mutableStateOf("") }
    var eOrder by remember { mutableStateOf("0") }
    val eEntries = remember { mutableStateListOf<WorldTreeStore.Entry>() }
    fun openEditor(t: WorldTreeStore.Tree?) {
        editId = t?.id ?: 0L; eName = t?.name ?: ""; eDesc = t?.description ?: ""; eOrder = (t?.order ?: 0).toString()
        eEntries.clear()
        // 老书读回来时 WorldTreeStore 已把「整本一组设置」折叠成一条条目，这里拿到的永远是条目列表；
        // 新建的书先摆一条空的，用户点开就能写，不用先想明白「条目」是什么
        eEntries.addAll(t?.entries?.takeIf { it.isNotEmpty() } ?: listOf(WorldTreeStore.Entry(id = 1L)))
        showEditor = true
    }

    // 条目编辑器（子弹窗）：-1 = 没在编辑
    var entryIdx by remember { mutableStateOf(-1) }
    var xName by remember { mutableStateOf("") }
    var xKeywords by remember { mutableStateOf("") }
    var xContent by remember { mutableStateOf("") }
    var xPosition by remember { mutableStateOf("system") }
    var xDepth by remember { mutableStateOf("0") }
    var xOrder by remember { mutableStateOf("0") }
    var xConstant by remember { mutableStateOf(false) }
    fun openEntry(i: Int) {
        val e = eEntries.getOrNull(i) ?: return
        xName = e.name; xKeywords = e.keywords; xContent = e.content; xPosition = e.position
        xDepth = e.depth.toString(); xOrder = e.order.toString(); xConstant = e.constant
        entryIdx = i
    }
    var showGen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // 顶部悬浮玻璃让位。这页顶部工具条不滚(下面 LazyColumn 才滚)，让不出「从玻璃下滑过去化开」，退化成普通留白
        Spacer(Modifier.topChromeGapHeight())
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { openEditor(null) }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary), shape = RoundedCornerShape(50), modifier = Modifier.weight(1f).heightIn(min = 40.dp)) { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(tr("新建世界书"), fontSize = 13.sp) }
            Button(onClick = { showGen = true }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer), shape = RoundedCornerShape(50), modifier = Modifier.heightIn(min = 40.dp)) { Text(tr("✨ AI 生成"), fontSize = 13.sp) }
        }
        // 整页导入导出：导出全部 / 导入（支持酒馆 lorebook / world-info 等，自动转成本应用世界书）
        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End) {
            com.arix.app.ui.ImportExportButtons(
                context = context, scope = scope, fileBaseName = "worldbooks_all",
                produceJson = {
                    val arr = org.json.JSONArray()
                    trees.forEach { arr.put(exportBook(it)) }
                    org.json.JSONObject().apply { put("version", 1); put("worldBooks", arr) }.toString(2)
                },
                consumeJson = { ImportExport.importWorldBook(it, context) },
                onResult = { msg -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show(); reload() },
            )
        }
        if (trees.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tr("还没有世界书。默认无世界背景。\n新建或让 AI 生成一个吧。"), color = scheme.onSurfaceVariant, fontSize = 13.sp) }
        } else LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(trees.size, key = { trees[it].id }) { idx ->
                val t = trees[idx]
                Surface(onClick = { openEditor(t) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = scheme.surfaceContainer, border = BorderStroke(1.dp, scheme.outlineVariant)) {
                    Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp).clip(CircleShape).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Public, null, tint = scheme.primary, modifier = Modifier.size(24.dp)) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.name.ifBlank { tr("未命名世界书") }, color = scheme.onSurface, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1)
                            val on = remember(t) { t.entries.count { it.enabled } }
                            Text(tr("条目") + " $on/${t.entries.size}" + if (t.description.isNotBlank()) " · ${t.description}" else "",
                                color = scheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2)
                        }
                        com.arix.app.ui.ImportExportButtons(
                            context = context, scope = scope, fileBaseName = "worldbook_${t.name}", compact = true,
                            produceJson = { exportBook(t).toString(2) },
                            onResult = { msg -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() },
                        )
                    }
                }
            }
        }
    }

    // 世界书编辑器：基本信息 + 条目列表（逐条增删改）。每条条目自己的触发词/正文/注入位/深度/顺序/常驻在子弹窗里改。
    if (showEditor) AlertDialog(
        onDismissRequest = { showEditor = false },
        title = { Text(if (editId > 0) tr("编辑世界书") else tr("新建世界书"), color = scheme.onSurface) },
        text = { Column(Modifier.height(400.dp)) {
            SettingsSection(tr("基本信息"), Icons.Outlined.Public, translucent = false) {
                com.arix.app.ui.XtomField(value = eName, onValueChange = { eName = it }, label = tr("名称"), modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                com.arix.app.ui.XtomField(value = eDesc, onValueChange = { eDesc = it }, label = tr("一句话描述"), modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp))
                // 这是「本书在列表里的排序」，跟条目自己的注入顺序是两回事
                com.arix.app.ui.XtomField(value = eOrder, onValueChange = { eOrder = it.filter { c -> c.isDigit() }.take(3) }, label = tr("本书排序（小在前）"), modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = TextStyle(fontSize = 12.sp))
            }
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.ListAlt, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("条目") + " (${eEntries.size})", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = scheme.primary)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    val nid = (eEntries.maxOfOrNull { it.id } ?: 0L) + 1
                    eEntries.add(WorldTreeStore.Entry(id = nid, order = eEntries.size))
                    openEntry(eEntries.lastIndex)
                }) { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(tr("新增条目"), fontSize = 12.sp) }
            }
            // 一本酒馆世界书能有几十上百条，列表必须是懒的
            LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                items(eEntries.size, key = { eEntries[it].id }) { i ->
                    val e = eEntries[i]
                    val title = e.name.ifBlank { e.keywords.split('\n', ',').firstOrNull { s -> s.isNotBlank() } ?: tr("未命名条目") }
                    val where = if (e.depth > 0) tr("深度") + " ${e.depth}" else if (e.position == "user") tr("用户消息") else tr("系统提示")
                    val how = if (e.constant || e.keywords.isBlank()) tr("常驻") else tr("触发词") + " ${e.keywords.split('\n', ',').count { s -> s.isNotBlank() }}"
                    SettingsToggle(
                        icon = if (e.constant || e.keywords.isBlank()) Icons.Outlined.PushPin else Icons.Outlined.Article,
                        title = title.take(30),
                        subtitle = "$how · $where · " + tr("顺序") + " ${e.order}",
                        checked = e.enabled,
                        onCheckedChange = { v -> eEntries[i] = eEntries[i].copy(enabled = v) },
                        trailing = { IconButton(onClick = { openEntry(i) }) { Icon(Icons.Outlined.Edit, tr("编辑"), tint = scheme.primary, modifier = Modifier.size(18.dp)) } },
                    )
                }
            }
        } },
        confirmButton = { TextButton(onClick = {
            // content 传空串：条目化之后正文的唯一来源是 entries，整本的 content 由 WorldTreeStore 从 entries 反算镜像
            WorldTreeStore.save(context, editId, eName, eDesc, "", order = eOrder.toIntOrNull() ?: 0, entries = eEntries.toList())
            showEditor = false; reload()
        }) { Text(tr("保存"), color = scheme.primary) } },
        dismissButton = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            if (editId > 0) TextButton(onClick = { WorldTreeStore.delete(context, editId); showEditor = false; reload() }) { Text(tr("删除"), color = scheme.error) }
            TextButton(onClick = { showEditor = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) }
        } },
        containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
    )

    // 条目编辑器：每条各有自己的触发词/正文/注入位/深度/顺序/常驻
    if (entryIdx >= 0 && entryIdx < eEntries.size) AlertDialog(
        onDismissRequest = { entryIdx = -1 },
        title = { Text(tr("编辑条目"), color = scheme.onSurface) },
        text = { Column(Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
            com.arix.app.ui.XtomField(value = xName, onValueChange = { xName = it }, label = tr("条目名（只给自己看）"), modifier = Modifier.fillMaxWidth(), singleLine = true)
            Spacer(Modifier.height(6.dp))
            com.arix.app.ui.XtomField(value = xContent, onValueChange = { xContent = it }, label = tr("正文（这条设定本身）"), modifier = Modifier.fillMaxWidth(), singleLine = false, minLines = 4, textStyle = TextStyle(fontSize = 12.sp))
            Spacer(Modifier.height(6.dp))
            com.arix.app.ui.XtomField(value = xKeywords, onValueChange = { xKeywords = it }, label = tr("触发词/正则（逗号或换行分隔）"), modifier = Modifier.fillMaxWidth(), singleLine = false, enabled = !xConstant, textStyle = TextStyle(fontSize = 12.sp))
            Spacer(Modifier.height(2.dp))
            SettingsToggle(
                icon = Icons.Outlined.PushPin, title = tr("常驻"), subtitle = tr("不看触发词，每轮都注入"),
                checked = xConstant, onCheckedChange = { xConstant = it },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("注入位置："), color = scheme.onSurfaceVariant, fontSize = 12.sp)
                listOf("system" to tr("系统提示"), "user" to tr("用户消息")).forEach { (k, v) ->
                    TextButton(onClick = { xPosition = k }) { Text(v, color = if (xPosition == k) scheme.primary else scheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = if (xPosition == k) FontWeight.Bold else FontWeight.Normal) }
                }
            }
            // 深度 + 顺序（对齐酒馆）：深度 0=不按深度注入(走上面的注入位置)，N=插到倒数第 N 个用户回合之前；顺序小的在前
            Row(verticalAlignment = Alignment.CenterVertically) {
                com.arix.app.ui.XtomField(value = xDepth, onValueChange = { xDepth = it.filter { c -> c.isDigit() }.take(3) }, label = tr("深度(0=不按深度)"), modifier = Modifier.weight(1f), singleLine = true, textStyle = TextStyle(fontSize = 12.sp))
                Spacer(Modifier.width(6.dp))
                com.arix.app.ui.XtomField(value = xOrder, onValueChange = { xOrder = it.filter { c -> c.isDigit() }.take(3) }, label = tr("顺序(小在前)"), modifier = Modifier.weight(1f), singleLine = true, textStyle = TextStyle(fontSize = 12.sp))
            }
            Spacer(Modifier.height(4.dp))
            Text(tr("深度大于 0 时按深度插进对话，注入位置不再生效。"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
        } },
        confirmButton = { TextButton(onClick = {
            eEntries[entryIdx] = eEntries[entryIdx].copy(
                name = xName.trim(), keywords = xKeywords, content = xContent,
                position = xPosition, depth = xDepth.toIntOrNull() ?: 0, order = xOrder.toIntOrNull() ?: 0,
                constant = xConstant,
            )
            entryIdx = -1
        }) { Text(tr("完成"), color = scheme.primary) } },
        dismissButton = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { eEntries.removeAt(entryIdx); entryIdx = -1 }) { Icon(Icons.Outlined.Delete, null, tint = scheme.error, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(tr("删除此条"), color = scheme.error) }
            TextButton(onClick = { entryIdx = -1 }) { Text(tr("取消"), color = scheme.onSurfaceVariant) }
        } },
        containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
    )

    // AI 对话生成世界书（与角色卡一致）
    if (showGen) {
        val messages = remember { mutableStateListOf<Pair<String, String>>() }
        var input by remember { mutableStateOf("") }
        var streaming by remember { mutableStateOf("") }
        var sending by remember { mutableStateOf(false) }
        val chatListState = rememberLazyListState()
        val sys = "你是「世界观创作助手」。通过多轮对话引导用户构建一个满意的世界背景（世界书）：主动反问细节（地理、历史、势力、规则、风格等）、给出建议、写出设定给用户看并征求意见，一次别问太多。当用户满意或说「生成」时，先用一段话总结，然后另起一行用 <TREE> 和 </TREE> 包裹一段 JSON（字段：name, description, content），只包裹 JSON 本身；平时对话不要输出 <TREE>。"
        val reg = remember { Regex("<TREE>([\\s\\S]*?)</TREE>") }
        fun strip(t: String) = t.replace(reg, "").replace(Regex("<TREE>[\\s\\S]*"), "").trim()
        fun send(u: String) {
            if (sending) return
            val t = u.trim(); if (t.isNotBlank()) messages.add("user" to t)
            input = ""; sending = true; streaming = ""
            scope.launch {
                try {
                    val cfg = CloudApiConfigManager(context).getActive() ?: run { messages.add("assistant" to tr("请先在「模型配置」里设置 API")); sending = false; return@launch }
                    val apiCfg = CloudApiConfig(cfg.baseUrl.trimEnd('/'), cfg.apiKey.trim(), cfg.model.trim())
                    var acc = ""
                    CloudApiClient(apiCfg).streamChat(messages = messages.map { ChatMessage(it.first, it.second) }, systemPrompt = sys, enableThinking = 0, onReasoningChunk = {}, onContentChunk = { acc += it; streaming = acc })
                    messages.add("assistant" to acc); streaming = ""
                } catch (e: Exception) { messages.add("assistant" to tr("出错了: %s").format(e.message)) } finally { sending = false }
            }
        }
        LaunchedEffect(Unit) { if (messages.isEmpty()) messages.add("assistant" to tr("想构建一个什么样的世界？说说风格、时代、地理或核心设定都行，我来帮你完善，也会问你一些细节～")) }
        LaunchedEffect(messages.size, streaming) { val n = messages.size; if (n > 0) chatListState.animateScrollToItem(n - 1) }
        val lastTree = remember(messages.size, sending) { if (sending) null else messages.lastOrNull { it.first == "assistant" }?.let { m -> reg.find(m.second)?.let { try { org.json.JSONObject(it.groupValues[1].trim().removePrefix("```json").removeSuffix("```").trim()) } catch (_: Exception) { null } } } }
        AlertDialog(
            onDismissRequest = { if (!sending) showGen = false },
            title = { Text(tr("AI 对话生成世界书"), color = scheme.onSurface) },
            text = { Column(Modifier.height(400.dp)) {
                LazyColumn(state = chatListState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(messages.size) { i ->
                        val (role, text) = messages[i]; val isUser = role == "user"
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                            Surface(color = if (isUser) scheme.primary else scheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 250.dp)) {
                                Text(if (isUser) text else strip(text).ifBlank { text }, color = if (isUser) scheme.onPrimary else scheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(9.dp))
                            }
                        }
                    }
                    if (sending) item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) { Surface(color = scheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 250.dp)) { Text(strip(streaming).ifBlank { "…" }, color = scheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(9.dp)) } } }
                }
                if (lastTree != null) {
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = { WorldTreeStore.save(context, 0L, lastTree.optString("name"), lastTree.optString("description"), lastTree.optString("content")); showGen = false; reload() }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary)) { Text(tr("✅ 保存这棵世界书"), fontSize = 12.sp) }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.arix.app.ui.XtomField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = tr("回复或提要求…"), textStyle = TextStyle(fontSize = 12.sp), singleLine = false, maxLines = 3, enabled = !sending)
                    IconButton(onClick = { if (input.isNotBlank()) send(input) }, enabled = !sending && input.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.Send, tr("发送"), tint = scheme.primary) }
                }
            } },
            confirmButton = { TextButton(onClick = { send("请根据我们的对话，现在生成最终世界书。") }, enabled = !sending) { Text(tr("让AI生成"), color = scheme.primary) } },
            dismissButton = { TextButton(onClick = { if (!sending) showGen = false }) { Text(tr("关闭"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )
    }
}

/**
 * 导出一本世界书。
 * 在老格式 {name, description, content} 之上**多带一份 entries**：老版本 App / 别的地方只读 content 照样导得进去，
 * 新版本读 entries 才能把每条各自的触发词/注入位/深度/顺序/常驻原样带走 —— 只写 entries 会让旧版读到空书。
 */
private suspend fun exportBook(t: WorldTreeStore.Tree): org.json.JSONObject =
    org.json.JSONObject(ImportExport.exportWorldBook(t.name, t.description, t.content))
        .put("entries", WorldTreeStore.entriesToJson(t.entries))
