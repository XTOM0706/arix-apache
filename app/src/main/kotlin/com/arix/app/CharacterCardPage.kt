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
 import androidx.compose.material.icons.automirrored.outlined.Send
 import androidx.compose.material.icons.outlined.Add
 import androidx.compose.material.icons.outlined.History
 import androidx.compose.material.icons.outlined.Link
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
 import androidx.compose.material3.Icon
 import androidx.compose.material3.IconButton
 import androidx.compose.material3.LinearProgressIndicator
 import androidx.compose.material3.MaterialTheme
 import androidx.compose.material3.ModalDrawerSheet
 import androidx.compose.material3.ModalNavigationDrawer
 import androidx.compose.material3.NavigationDrawerItem
 import androidx.compose.material3.NavigationDrawerItemDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
 import androidx.compose.runtime.remember
 import androidx.compose.runtime.rememberCoroutineScope
 import androidx.compose.runtime.setValue
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
 import androidx.compose.ui.draw.alpha
 import androidx.compose.ui.draw.clip
 import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.platform.LocalClipboardManager
 import androidx.compose.ui.platform.LocalContext
 import androidx.compose.ui.text.font.FontFamily
 import androidx.compose.ui.text.font.FontWeight
 import androidx.compose.ui.unit.dp
 import androidx.compose.ui.unit.sp
 import androidx.core.content.ContextCompat
 import com.arix.app.ui.XtomField
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
import com.arix.app.ui.topChromeGapHeight
 import java.io.File
 import com.arix.stt.LanguageModel
 import com.arix.stt.SttEngine
 import com.arix.stt.SttModelManager
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.delay
 import kotlinx.coroutines.flow.first
 import kotlinx.coroutines.isActive
 import kotlinx.coroutines.launch
 import kotlinx.coroutines.withContext
 import org.json.JSONObject
 import java.text.SimpleDateFormat
 import java.util.Date
 import java.util.Locale

 // 导入后把 JSON 里的多开场白/越狱指令/深度提示接到刚建好的卡上。这三项不在 CharacterCardEntity 字段里，
 // ImportExport.importCharacterCard 只落主字段（不改 ImportExport.kt/ImportConverters.kt，那是另一个 agent 的地盘），
 // 这里在导入前后各拍一次卡片 id 快照，按创建顺序把新增的卡对上 JSON 里同顺序的每张卡——
 // 比按卡名配对更准：同名卡在批量导入时也不会互相配错。解析失败/对不上就算了，主字段已经导进去了，这三项是锦上添花。
 private suspend fun attachRoleplayExtras(context: android.content.Context, cardManager: CharacterCardManager, json: String, beforeIds: Set<Long>) {
     try {
         val root = org.json.JSONObject(json)
         val rawCards: List<org.json.JSONObject> = root.optJSONArray("cards")?.let { arr ->
             (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
         } ?: listOf(root)
         val newCards = cardManager.allCards.first().filter { it.id !in beforeIds }.sortedBy { it.id }
         for ((card, raw) in newCards.zip(rawCards)) {
             val extras = CardPng.extractRoleplayExtras(raw)
             if (extras.alternateGreetings.isNotEmpty()) CardRoleplayStore.setAlternateGreetings(context, card.id, extras.alternateGreetings)
             if (extras.postHistoryInstructions.isNotBlank()) CardRoleplayStore.setPostHistoryInstructions(context, card.id, extras.postHistoryInstructions)
             if (extras.depthPromptText.isNotBlank()) CardRoleplayStore.setDepthPrompt(context, card.id, extras.depthPromptDepth ?: 4, extras.depthPromptRole, extras.depthPromptText)
         }
     } catch (_: Exception) {}
 }

 @Composable fun CharacterCardPage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
     val cardManager = remember { CharacterCardManager(context) }; val cards by cardManager.allCards.collectAsState(initial = emptyList())
     var showEditor by remember { mutableStateOf(false) }; var editingCard by remember { mutableStateOf<com.arix.data.entity.CharacterCardEntity?>(null) }
     var showGenerator by remember { mutableStateOf(false) }
     // 从别的 AI 的公开聊天导入 → 提炼角色卡 + 记忆
     var showChatImport by remember { mutableStateOf(false) }
     var chatImportText by remember { mutableStateOf("") }
     var chatImportBusy by remember { mutableStateOf(false) }
     var chatImportStatus by remember { mutableStateOf("") }
     // 贴文本之外，也可以直接给链接：取正文单独一套状态（跟「提炼并生成」的 busy 分开，两件事能各自进行中）
     var chatImportUrl by remember { mutableStateOf("") }
     var chatImportFetching by remember { mutableStateOf(false) }
     var chatImportStatusIsError by remember { mutableStateOf(false) }
     var editorName by remember { mutableStateOf("") }; var editorDesc by remember { mutableStateOf("") }
     var editorSetting by remember { mutableStateOf("") }; var editorOpening by remember { mutableStateOf("") }
     var editorWorldBook by remember { mutableStateOf("") }
     var editorAvatar by remember { mutableStateOf("") }
     var editorTone by remember { mutableStateOf("") }; var editorLength by remember { mutableStateOf("") }; var editorLang by remember { mutableStateOf("") }
     var editorWaifu by remember { mutableStateOf(false) }; var editorWaifuDelay by remember { mutableStateOf("250") }
     var editorTreeId by remember { mutableStateOf<Long?>(null) }
     var editorVoice by remember { mutableStateOf("") }   // 本卡专属 TTS 音色（空=用全局音色）
     var editorExamples by remember { mutableStateOf("") }   // 对话示例(few-shot)，注入系统提示教说话风格
     var editorRules by remember { mutableStateOf("") }      // 显示替换规则(每行 find => replace)，仅改显示
     // 酒馆 v2 spec 补齐三项，均走 CardRoleplayStore 旁路存储（不进 CharacterCardEntity）：
     var editorGreetings by remember { mutableStateOf("") }     // 多开场白：候选文本，条目间用 --- 分隔行
     var editorPhi by remember { mutableStateOf("") }           // 越狱指令 post_history_instructions
     var editorDepthText by remember { mutableStateOf("") }     // 深度提示正文
     var editorDepthDepth by remember { mutableStateOf("4") }   // 深度提示插入位置（倒数第 N 条），字符串装数字输入框
     var editorDepthRole by remember { mutableStateOf("system") } // 深度提示说话角色
     // 本卡的工具范围：**排除**掉的功能包 id（空=不限制）。见 CardToolStore
     var editorToolOff by remember { mutableStateOf(setOf<String>()) }
     var toolScopeOpen by remember { mutableStateOf(false) }   // 明细列表默认收起：50 来个包全组合出来太重，也太吓人
     var worldTreesBump by remember { mutableStateOf(0) }
     val worldTrees = remember(showEditor, worldTreesBump) { WorldTreeStore.all(context) }
     var showImgConfig by remember { mutableStateOf(false) }
     var imgGenLoading by remember { mutableStateOf(false) }
     var imgGenErr by remember { mutableStateOf("") }
     var worldGenLoading by remember { mutableStateOf(false) }
     val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
         if (uri != null) { try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}; editorAvatar = uri.toString() }
     }
     // PNG 角色卡导入：解 PNG 里内嵌的 chara/ccv3(base64) → 归一化导入（酒馆式卡片）
     val pngImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
         if (uri != null) scope.launch {
             val beforeIds = cardManager.allCards.first().map { it.id }.toSet()
             val msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                 try {
                    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext tr("读取失败")
                    val json = CardPng.extractCardJson(bytes) ?: return@withContext tr("这张 PNG 里没有内嵌角色卡数据（不是酒馆式卡？）")
                     val m = ImportExport.importCharacterCard(json, context)
                    attachRoleplayExtras(context, cardManager, json, beforeIds)
                    m
                } catch (e: Exception) { tr("导入失败: %s").format(e.message) }
             }
             android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
         }
     }
     // 扫码导入：从二维码图片解出卡 JSON（我们自己的格式）→ 导入
     val cardQrImportPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
         if (uri != null) scope.launch {
             val beforeIds = cardManager.allCards.first().map { it.id }.toSet()
             val msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                 try {
                    val bmp = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@withContext tr("读取失败")
                    val text = QrKit.decode(bmp) ?: return@withContext tr("未识别到二维码")
                     val m = ImportExport.importCharacterCard(text, context)
                    attachRoleplayExtras(context, cardManager, text, beforeIds)
                    m
                } catch (e: Exception) { tr("导入失败: %s").format(e.message) }
             }
             android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
         }
     }
     // 角色卡二维码分享（把单卡导出 JSON 编码成二维码，另一台扫码/选图导入）
     var shareQr by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

     fun openEditor(card: com.arix.data.entity.CharacterCardEntity?) {
         editingCard = card; editorName = card?.name ?: ""; editorDesc = card?.description ?: ""
         editorSetting = card?.characterSetting ?: ""; editorOpening = card?.openingStatement ?: ""
         editorWorldBook = card?.worldBook ?: ""
         editorAvatar = card?.avatarPath ?: ""
         editorTone = card?.tone ?: ""; editorLength = card?.length ?: ""; editorLang = card?.language ?: ""
         editorWaifu = card?.waifuEnabled ?: false; editorWaifuDelay = (card?.waifuDelayMs ?: 250).toString()
         editorTreeId = card?.id?.let { WorldTreeStore.boundTreeId(context, it) }
         editorVoice = card?.id?.let { com.arix.tool.TtsTool.cardVoicePref(context, it) } ?: ""
         editorExamples = card?.id?.let { CardRoleplayStore.examplesText(context, it) } ?: ""
         editorRules = card?.id?.let { CardRoleplayStore.rulesText(context, it) } ?: ""
         editorGreetings = CardRoleplayStore.alternateGreetingsText(context, card?.id)
         editorPhi = card?.id?.let { CardRoleplayStore.postHistoryInstructions(context, it) } ?: ""
         editorDepthText = CardRoleplayStore.depthPromptText(context, card?.id)
         editorDepthDepth = CardRoleplayStore.depthPromptDepth(context, card?.id).toString()
         editorDepthRole = CardRoleplayStore.depthPromptRole(context, card?.id)
         editorToolOff = CardToolStore.excluded(context, card?.id); toolScopeOpen = false
         showEditor = true
     }
 
     val scheme = MaterialTheme.colorScheme
     val accents = com.arix.app.theme.LocalXtomAccents.current
     Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
         // 顶部悬浮玻璃让位。这页顶部工具条不滚(下面 LazyColumn 才滚)，让不出「从玻璃下滑过去化开」，退化成普通留白
         Spacer(Modifier.topChromeGapHeight())
         Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
             Button(onClick = { openEditor(null) }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary), shape = RoundedCornerShape(50), modifier = Modifier.weight(1f).heightIn(min = 40.dp)) { Icon(Icons.Outlined.Add, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text(tr("新建角色卡"), fontSize = 13.sp) }
             Button(onClick = { showGenerator = true }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer), shape = RoundedCornerShape(50), modifier = Modifier.heightIn(min = 40.dp)) { Text(tr("✨ AI 生成"), fontSize = 13.sp) }
         }
         // 从别的 AI 的公开聊天导入：贴对话文本 → 提炼角色卡 + 记忆
         Row(modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
             androidx.compose.material3.OutlinedButton(onClick = { chatImportText = ""; chatImportStatus = ""; showChatImport = true }, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth()) {
                 Text(tr("📥 从其他 AI 的聊天导入角色卡"), fontSize = 12.sp)
             }
         }
         // 整页导入导出：导出全部 / 导入（支持酒馆·Operit 等主流角色卡，自动转成本应用格式）
         Row(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
             TextButton(onClick = { runCatching { pngImportPicker.launch(arrayOf("image/png", "image/*")) }.onFailure { android.widget.Toast.makeText(context, tr("无法打开图片选择器"), android.widget.Toast.LENGTH_SHORT).show() } }) { Text(tr("PNG导入"), color = scheme.primary, fontSize = 12.sp) }
             TextButton(onClick = { runCatching { cardQrImportPicker.launch(arrayOf("image/*")) }.onFailure { android.widget.Toast.makeText(context, tr("无法打开图片选择器"), android.widget.Toast.LENGTH_SHORT).show() } }) { Text(tr("扫码导入"), color = scheme.primary, fontSize = 12.sp) }
             com.arix.app.ui.ImportExportButtons(
                 context = context, scope = scope, fileBaseName = "cards_all",
                 produceJson = {
                     val arr = org.json.JSONArray()
                     cards.forEach { arr.put(org.json.JSONObject(ImportExport.exportCharacterCard(it))) }
                     org.json.JSONObject().apply { put("version", 1); put("cards", arr) }.toString(2)
                 },
                 consumeJson = { j ->
                     val beforeIds = cardManager.allCards.first().map { it.id }.toSet()
                     val m = ImportExport.importCharacterCard(j, context)
                     attachRoleplayExtras(context, cardManager, j, beforeIds)
                     m
                 },
                 onResult = { msg -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() },
             )
         }
         LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
             items(cards.size, key = { cards[it].id }, contentType = { "card" }) { idx ->
                 val card = cards[idx]
                 // GPT/抽屉风大卡片：大头像(图片/emoji) + 名称 + 描述；莫奈令牌 + 描边
                 androidx.compose.material3.Surface(
                     onClick = { openEditor(card) },
                     modifier = Modifier.fillMaxWidth(),
                     shape = RoundedCornerShape(20.dp),
                     color = scheme.surfaceContainer,
                     border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
                 ) {
                     Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                         CardAvatar(card.avatarPath, card.name, size = 52.dp)
                         Spacer(modifier = Modifier.width(14.dp))
                         Column(modifier = Modifier.weight(1f)) {
                             Text(card.name, color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                             if (card.description.isNotBlank()) Text(card.description, color = scheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 2)
                             if (card.isDefault) Text(tr("系统默认 · 不可删除"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                         }
                         // 单卡导出（我们自己的格式）
                         com.arix.app.ui.ImportExportButtons(
                             context = context, scope = scope, fileBaseName = "card_${card.name}", compact = true,
                             produceJson = { ImportExport.exportCharacterCard(card) },
                             onResult = { msg -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() },
                         )
                         Spacer(Modifier.width(6.dp))
                         // 选中即设为默认角色卡（当前对话角色）
                         if (card.isDefault) {
                             androidx.compose.material3.Surface(shape = RoundedCornerShape(50), color = scheme.primary) {
                                 Text(tr("✓ 当前"), color = scheme.onPrimary, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                             }
                         } else {
                             androidx.compose.material3.Surface(onClick = { scope.launch { cardManager.setDefault(card.id) } }, shape = RoundedCornerShape(50), color = scheme.surfaceContainerHighest, border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant)) {
                                 Text(tr("选中"), color = scheme.onSurface, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                             }
                         }
                     }
                 }
             }
         }
     }
 
     // Editor dialog（莫奈令牌 + 图片头像）
     if (showEditor) { androidx.compose.material3.AlertDialog(
         onDismissRequest = { showEditor = false },
         title = { Text(if (editingCard == null) tr("新建角色卡") else tr("编辑角色卡"), color = scheme.onSurface) },
         text = { Column(modifier = Modifier.height(400.dp).verticalScroll(rememberScrollState())) {
             // 头像：图片预览 + 选图 + emoji 输入（图片优先）
             Row(verticalAlignment = Alignment.CenterVertically) {
                 CardAvatar(editorAvatar, editorName, size = 56.dp)
                 Spacer(Modifier.width(12.dp))
                 Column {
                     Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                         Button(onClick = { avatarPicker.launch(arrayOf("image/*")) }, shape = RoundedCornerShape(50), modifier = Modifier.height(34.dp), colors = ButtonDefaults.buttonColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer)) { Text(tr("选图"), fontSize = 11.sp) }
                         Button(enabled = !imgGenLoading, onClick = {
                             imgGenErr = ""; imgGenLoading = true
                             scope.launch {
                                 val prompt = "角色头像插画，${editorName}，${editorDesc}，${editorSetting}。高质量人物头像，居中，简洁背景".take(900)
                                 val (uri, err) = ImageGenPrefs.generate(context, prompt)
                                 imgGenLoading = false
                                  if (uri != null) editorAvatar = uri else imgGenErr = err ?: tr("生成失败")
                             }
                         }, shape = RoundedCornerShape(50), modifier = Modifier.height(34.dp), colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary)) { Text(if (imgGenLoading) tr("生成中…") else tr("✨AI头像"), fontSize = 11.sp) }
                     }
                     Row(verticalAlignment = Alignment.CenterVertically) {
                         TextButton(onClick = { showImgConfig = true }) { Text(tr("⚙ 文生图配置"), color = scheme.onSurfaceVariant, fontSize = 10.sp) }
                         if (editorAvatar.isNotBlank()) TextButton(onClick = { editorAvatar = "" }) { Text(tr("清除"), color = scheme.onSurfaceVariant, fontSize = 10.sp) }
                     }
                     if (imgGenErr.isNotBlank()) Text(imgGenErr, color = scheme.error, fontSize = 10.sp, maxLines = 2)
                 }
             }
             Spacer(modifier = Modifier.height(6.dp))
             XtomField(value = editorAvatar, onValueChange = { editorAvatar = it }, label = tr("或用 emoji/图片链接作头像"), modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
             Spacer(modifier = Modifier.height(6.dp))
             XtomField(value = editorName, onValueChange = { editorName = it }, label = tr("名称"), modifier = Modifier.fillMaxWidth(), singleLine = true)
             Spacer(modifier = Modifier.height(6.dp))
             XtomField(value = editorDesc, onValueChange = { editorDesc = it }, label = tr("描述"), modifier = Modifier.fillMaxWidth(), singleLine = true)
             Spacer(modifier = Modifier.height(6.dp))
             XtomField(value = editorSetting, onValueChange = { editorSetting = it }, label = tr("人设 / 性格"), modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 3, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
             Spacer(modifier = Modifier.height(6.dp))
             XtomField(value = editorWorldBook, onValueChange = { editorWorldBook = it }, label = tr("背景故事（本卡专属，选填；共享世界用下方「世界书」）"), modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 4, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
             Spacer(modifier = Modifier.height(6.dp))
             XtomField(value = editorOpening, onValueChange = { editorOpening = it }, label = tr("开场白"), modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 2, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
             Spacer(modifier = Modifier.height(6.dp))
             // 对话示例(few-shot)：示范说话风格，注入系统提示。可自然写「用户：… / 角色：…」
             XtomField(value = editorExamples, onValueChange = { editorExamples = it }, label = tr("对话示例（选填，示范说话风格，会注入提示；如「用户：… 角色：…」）"), modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 6, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
             Spacer(modifier = Modifier.height(6.dp))
             // 显示替换规则（对齐酒馆 regex）：每行 find => replace，find 为正则，仅改显示不改存储
             XtomField(value = editorRules, onValueChange = { editorRules = it }, label = tr("显示替换规则（选填，每行 find => replace，find 是正则，只改AI回复的显示）"), modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 5, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
             Spacer(modifier = Modifier.height(6.dp))
             // 多开场白（酒馆 alternate_greetings）：除上面「开场白」这条默认的之外的候选，新开对话时可挑一条/随机
             XtomField(value = editorGreetings, onValueChange = { editorGreetings = it }, label = tr("多开场白（选填，除默认开场白外的候选；多条时每条单独一行写 ---）"), modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 5, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
             Spacer(modifier = Modifier.height(6.dp))
             // 越狱指令（酒馆 post_history_instructions）：插在对话历史之后的系统指令，权重比人设/系统提示更高
             XtomField(value = editorPhi, onValueChange = { editorPhi = it }, label = tr("越狱指令（选填，插在对话历史之后的系统指令，权重高于前面的人设/系统提示）"), modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 4, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
             Spacer(modifier = Modifier.height(6.dp))
             // 深度提示（酒馆 depth_prompt）：插到对话历史倒数第 N 条位置的一段文本，可指定说话角色
             Text(tr("深度提示（选填，插入对话历史倒数第 N 条位置）"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
             XtomField(value = editorDepthText, onValueChange = { editorDepthText = it }, placeholder = tr("要插入的文本，留空=不启用"), modifier = Modifier.fillMaxWidth(), singleLine = false, maxLines = 3, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
             Spacer(modifier = Modifier.height(4.dp))
             Row(verticalAlignment = Alignment.CenterVertically) {
                 XtomField(value = editorDepthDepth, onValueChange = { s -> editorDepthDepth = s.filter { it.isDigit() } }, label = tr("深度"), modifier = Modifier.width(90.dp), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
                 Spacer(modifier = Modifier.width(8.dp))
                 listOf("system" to tr("系统"), "user" to tr("用户"), "assistant" to tr("角色")).forEach { (v, label) ->
                     androidx.compose.material3.FilterChip(
                         selected = editorDepthRole == v,
                         onClick = { editorDepthRole = v },
                         label = { Text(label, fontSize = 11.sp) },
                         modifier = Modifier.padding(end = 6.dp),
                     )
                 }
             }
             Spacer(modifier = Modifier.height(6.dp))
             XtomField(value = editorVoice, onValueChange = { editorVoice = it }, label = tr("本卡专属音色（选填，空=用全局；CosyVoice 如 …:alex，或 Minimax voice_id 如 male-qn-qingse）"), modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
             Spacer(modifier = Modifier.height(6.dp))
             // 绑定世界书（多个角色卡可绑同一棵；默认无）
             Text(tr("绑定世界书"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
             Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                 val chip: @Composable (String, Boolean, () -> Unit) -> Unit = { label, sel, onClick ->
                     androidx.compose.material3.Surface(onClick = onClick, shape = RoundedCornerShape(50), color = if (sel) scheme.primary else scheme.surfaceContainerHighest, border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.padding(end = 6.dp)) {
                         Text(label, color = if (sel) scheme.onPrimary else scheme.onSurface, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                     }
                 }
                 chip(tr("无"), editorTreeId == null) { editorTreeId = null }
                 worldTrees.forEach { t -> chip(t.name.ifBlank { tr("未命名") }, editorTreeId == t.id) { editorTreeId = t.id } }
             }
             Spacer(modifier = Modifier.height(4.dp))
             // 让 AI 为这个角色生成一份世界书并绑定
             Button(enabled = !worldGenLoading && editorName.isNotBlank(), onClick = {
                 worldGenLoading = true
                 scope.launch {
                     try {
                         val cfg = CloudApiConfigManager(context).getActive()
                         if (cfg == null) { worldGenLoading = false; return@launch }
                         val apiCfg = CloudApiConfig(cfg.baseUrl.trimEnd('/'), cfg.apiKey.trim(), cfg.model.trim())
                         var acc = ""
                         CloudApiClient(apiCfg).streamChat(messages = listOf(ChatMessage("user", "为下面这个角色构建一份契合的世界书（世界观/世界背景）。角色：${editorName}｜${editorDesc}｜人设：${editorSetting}。用 JSON 输出，字段 name(世界名), description(一句话), content(充实的世界背景：地理/历史/势力/规则等)，只输出 JSON。")), enableThinking = 0, onReasoningChunk = {}, onContentChunk = { acc += it })
                         val cleaned = acc.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
                         val js = try { org.json.JSONObject(cleaned) } catch (_: Exception) { null }
                         if (js != null) { val id = WorldTreeStore.save(context, 0L, js.optString("name", editorName + " · 世界书"), js.optString("description"), js.optString("content", cleaned)); editorTreeId = id; worldTreesBump++ }
                     } catch (_: Exception) {} finally { worldGenLoading = false }
                 }
             }, shape = RoundedCornerShape(50), modifier = Modifier.height(34.dp), colors = ButtonDefaults.buttonColors(containerColor = scheme.primaryContainer, contentColor = scheme.onPrimaryContainer)) { Text(if (worldGenLoading) tr("生成中…") else tr("✨为此角色生成世界书"), fontSize = 11.sp) }
             Spacer(modifier = Modifier.height(6.dp))
             Row(modifier = Modifier.fillMaxWidth()) {
                 XtomField(value = editorTone, onValueChange = { editorTone = it }, label = tr("语气"), modifier = Modifier.weight(1f), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
                 Spacer(modifier = Modifier.width(4.dp))
                 XtomField(value = editorLength, onValueChange = { editorLength = it }, label = tr("回复长度"), modifier = Modifier.weight(1f), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
                 Spacer(modifier = Modifier.width(4.dp))
                 XtomField(value = editorLang, onValueChange = { editorLang = it }, label = tr("语言"), modifier = Modifier.weight(1f), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
             }
             Spacer(modifier = Modifier.height(10.dp))
             // ── 工具范围（本卡不带哪些能力）──────────────────────────────
             // 排除表存 per-card prefs（CardToolStore）；发请求前从工具表里剔掉，模型连 schema 都看不到。
             // 好处是双份的：省 token + 少幻觉（工具面越大，它越爱去调不该调的）。
             run {
                 val allPkgs = remember(toolScopeOpen) {
                     try { com.arix.tool.PackageManager.getAllPackages().filter { com.arix.tool.PackageManager.isEnabled(it.id) && it.tools.isNotEmpty() }.sortedBy { it.category + it.name } }
                     catch (_: Exception) { emptyList() }
                 }
                 Text(tr("工具范围"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                 Text(
                     if (editorToolOff.isEmpty()) tr("不限制：这张卡能用所有已启用的功能包")
                     else "${editorToolOff.size} " + tr("个功能包不在本卡范围内，它看不到这些工具"),
                     color = scheme.onSurfaceVariant, fontSize = 10.sp,
                 )
                 Spacer(modifier = Modifier.height(4.dp))
                 val chip2: @Composable (String, Boolean, () -> Unit) -> Unit = { label, sel, onClick ->
                     androidx.compose.material3.Surface(onClick = onClick, shape = RoundedCornerShape(50), color = if (sel) scheme.primary else scheme.surfaceContainerHighest, border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.padding(end = 6.dp)) {
                         Text(label, color = if (sel) scheme.onPrimary else scheme.onSurface, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp))
                     }
                 }
                 Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                     chip2(tr("不限制"), editorToolOff.isEmpty()) { editorToolOff = emptySet() }
                     chip2(tr("干活形态"), editorToolOff == CardToolStore.WORK_EXCLUDES) { editorToolOff = CardToolStore.WORK_EXCLUDES }
                     chip2(tr("陪伴形态"), editorToolOff == CardToolStore.COMPANION_EXCLUDES) { editorToolOff = CardToolStore.COMPANION_EXCLUDES }
                     chip2(if (toolScopeOpen) tr("收起明细") else tr("自定义…"), false) { toolScopeOpen = !toolScopeOpen }
                 }
                 if (toolScopeOpen) {
                     Spacer(modifier = Modifier.height(4.dp))
                     Text(tr("勾上=这张卡带这个包（不勾=不带）"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                     // 用普通 Column 逐行铺（不是 LazyColumn）：外层已经在 verticalScroll 里，嵌纵向滚动既卡又难点
                     allPkgs.forEach { pkg ->
                         Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                             androidx.compose.material3.Checkbox(
                                 checked = pkg.id !in editorToolOff,
                                 onCheckedChange = { on -> editorToolOff = if (on) editorToolOff - pkg.id else editorToolOff + pkg.id },
                             )
                             Text("${pkg.name}  ·  ${pkg.category}", color = scheme.onSurface, fontSize = 11.sp, modifier = Modifier.weight(1f))
                         }
                     }
                 }
             }
             Spacer(modifier = Modifier.height(6.dp))
             Row(verticalAlignment = Alignment.CenterVertically) {
                 androidx.compose.material3.Switch(checked = editorWaifu, onCheckedChange = { editorWaifu = it })
                 // weight(1f)：不给权重的话译文会吃掉整行宽度，把右边定宽 90dp 的延迟框挤出屏幕
                 Text(tr("Waifu 模式"), color = scheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.weight(1f).padding(start = 6.dp))
                 if (editorWaifu) {
                     Spacer(modifier = Modifier.width(8.dp))
                     XtomField(value = editorWaifuDelay, onValueChange = { editorWaifuDelay = it }, label = tr("延迟(ms)"), modifier = Modifier.width(90.dp), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp))
                 }
             }
         } },
         confirmButton = { TextButton(onClick = { scope.launch {
             val card = editingCard
             val delay = editorWaifuDelay.toIntOrNull() ?: 250
             // 三项旁路数据存取统一走这一个 lambda：新建/编辑两条分支字段完全一样，只是 cardId 来源不同
             fun saveRoleplayExtras(id: Long) {
                 CardRoleplayStore.setExamplesText(context, id, editorExamples)
                 CardRoleplayStore.setRulesText(context, id, editorRules)
                 CardRoleplayStore.setAlternateGreetingsText(context, id, editorGreetings)
                 CardRoleplayStore.setPostHistoryInstructions(context, id, editorPhi)
                 CardRoleplayStore.setDepthPrompt(context, id, editorDepthDepth.toIntOrNull() ?: 4, editorDepthRole, editorDepthText)
             }
             if (card != null) { cardManager.update(card.copy(name = editorName, description = editorDesc, characterSetting = editorSetting, worldBook = editorWorldBook, openingStatement = editorOpening, avatarPath = editorAvatar, tone = editorTone, length = editorLength, language = editorLang, waifuEnabled = editorWaifu, waifuDelayMs = delay, updatedAt = System.currentTimeMillis())); WorldTreeStore.bind(context, card.id, editorTreeId); com.arix.tool.TtsTool.setCardVoicePref(context, card.id, editorVoice); saveRoleplayExtras(card.id); CardToolStore.setExcluded(context, card.id, editorToolOff) }
             else { val newId = cardManager.create(editorName, editorDesc, editorSetting, editorOpening, editorAvatar, editorTone, editorLength, editorLang, editorWorldBook); WorldTreeStore.bind(context, newId, editorTreeId); com.arix.tool.TtsTool.setCardVoicePref(context, newId, editorVoice); saveRoleplayExtras(newId); CardToolStore.setExcluded(context, newId, editorToolOff) }
             showEditor = false
         } }) { Text(tr("保存"), color = scheme.primary) } },
         dismissButton = { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
             if (editingCard != null && !editingCard!!.isDefault) TextButton(onClick = { scope.launch { cardManager.delete(editingCard!!.id) }; showEditor = false }) { Text(tr("删除"), color = scheme.error) }
             Row(verticalAlignment = Alignment.CenterVertically) {
                 // 二维码分享：把这张卡的导出 JSON 编码成二维码给别人扫（内容仍是我们自己的格式）
                 editingCard?.let { ec -> TextButton(onClick = { scope.launch {
                     val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ImportExport.exportCharacterCard(ec) }
                     try { shareQr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { QrKit.encode(json) } }
                     catch (_: Exception) { android.widget.Toast.makeText(context, tr("这张卡内容偏大，二维码放不下，请改用文件/复制导出"), android.widget.Toast.LENGTH_LONG).show() }
                 } }) { Text(tr("二维码"), color = scheme.primary) } }
                 TextButton(onClick = { showEditor = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) }
             } }
         },
         containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
     ) }

     // 二维码分享弹窗
     shareQr?.let { bmp ->
         androidx.compose.material3.AlertDialog(
             onDismissRequest = { shareQr = null },
             title = { Text(tr("角色卡二维码"), color = scheme.onSurface) },
             text = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                 androidx.compose.foundation.Image(bitmap = bmp.asImageBitmap(), contentDescription = tr("二维码"), modifier = Modifier.size(260.dp))
                 Text(tr("对方在角色卡页点「扫码导入」选这张图即可导入"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
             } },
             confirmButton = { TextButton(onClick = { shareQr = null }) { Text(tr("关闭"), color = scheme.primary) } },
             containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
         )
     }
 
     // 文生图配置（用户自己填 key）
     if (showImgConfig) {
         var b by remember { mutableStateOf(ImageGenPrefs.baseUrl(context)) }
         var k by remember { mutableStateOf(ImageGenPrefs.apiKey(context)) }
         var m by remember { mutableStateOf(ImageGenPrefs.model(context)) }
         var sz by remember { mutableStateOf(ImageGenPrefs.size(context)) }
         var imgProvider by remember { mutableStateOf(ImageGenPrefs.provider(context)) }
         androidx.compose.material3.AlertDialog(
             onDismissRequest = { showImgConfig = false },
             title = { Text(tr("文生图配置（头像）"), color = scheme.onSurface) },
             text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                 Text(tr("支持 OpenAI 兼容接口 / 通义万相 / 智谱 CogView，key 用你自己的。"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                 Spacer(Modifier.height(6.dp))
                 // 供应商：默认「自动」= 按 Base URL 认。认不出来时（自建网关/中转）用户得能直接指定，
                 // 否则只会拿到一句看不懂的上游报错。取值字符串与 ImageGenProviders 共享，别改。
                 Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                     listOf("" to tr("自动"), "openai" to "OpenAI", "qwen" to tr("通义万相"), "zhipu" to tr("智谱 CogView")).forEach { (v, label) ->
                         androidx.compose.material3.FilterChip(
                             selected = imgProvider == v,
                             onClick = { imgProvider = v; ImageGenPrefs.setProvider(context, v) },
                             label = { Text(label, fontSize = 11.sp) },
                             modifier = Modifier.padding(end = 6.dp),
                         )
                     }
                 }
                 Spacer(Modifier.height(6.dp))
                 XtomField(value = b, onValueChange = { b = it }, label = "Base URL", modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                 Spacer(Modifier.height(6.dp))
                 XtomField(value = k, onValueChange = { k = it }, label = "API Key", modifier = Modifier.fillMaxWidth(), singleLine = true, password = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                 Spacer(Modifier.height(6.dp))
                 Row {
                     XtomField(value = m, onValueChange = { m = it }, label = tr("模型"), modifier = Modifier.weight(1f), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                     Spacer(Modifier.width(6.dp))
                     XtomField(value = sz, onValueChange = { sz = it }, label = tr("尺寸"), modifier = Modifier.width(120.dp), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                 }
             } },
             confirmButton = { TextButton(onClick = { ImageGenPrefs.set(context, b.trim(), k.trim(), m.trim(), sz.trim().ifBlank { "1024x1024" }); showImgConfig = false }) { Text(tr("保存"), color = scheme.primary) } },
             dismissButton = { TextButton(onClick = { showImgConfig = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
             containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
         )
     }

     // AI 对话生成角色卡（仿 Operit）：和 AI 多轮对话，AI 会反问、写出设定给你看，满意后一键采用。
     if (showGenerator) {
         val messages = remember { mutableStateListOf<Pair<String, String>>() }
         var input by remember { mutableStateOf("") }
         var streaming by remember { mutableStateOf("") }
         var sending by remember { mutableStateOf(false) }
         val chatListState = rememberLazyListState()
         val genSys = "你是「角色卡创作助手」。通过多轮自然对话引导用户创造一个满意的 AI 角色：主动反问用户的偏好与细节、给出建议、把你构思的角色设定写出来给用户看并征求意见，一次别问太多。当用户表示满意或说「生成角色卡」时，先用一段话总结，然后另起一行用 <CARD> 和 </CARD> 包裹一段 JSON（字段：name(角色名), description, setting, worldName(这个世界/世界观的名字，别用角色名), worldBook(角色所处世界的完整世界背景/世界观，写充实：地理/历史/势力/规则等), opening, tone, length, language），只包裹 JSON 本身；平时对话不要输出 <CARD>。"
         val cardRegex = remember { Regex("<CARD>([\\s\\S]*?)</CARD>") }
         // 收尾用的「未闭合 <CARD>」正则也 remember：原来 stripCard 每调一次就现编译一个 Regex，
         // 而它在每条消息每次重组都被调用（气泡正文），白编译一堆一模一样的正则。
         val cardTailRegex = remember { Regex("<CARD>[\\s\\S]*") }
         fun stripCard(t: String) = t.replace(cardRegex, "").replace(cardTailRegex, "").trim()
         fun applyCard(js: org.json.JSONObject) {
             editorName = js.optString("name", ""); editorDesc = js.optString("description", "")
             editorSetting = js.optString("setting", "")
             editorOpening = js.optString("opening", ""); editorTone = js.optString("tone", "")
             editorLength = js.optString("length", ""); editorLang = js.optString("language", ""); editorAvatar = ""
             // 世界书一起生成：把 worldBook 存成独立「世界书」并绑定该卡（可被多卡共享）；本卡专属背景留空
             val wb = js.optString("worldBook", "")
             if (wb.isNotBlank()) {
                 val wName = js.optString("worldName", "").ifBlank { "未命名世界书" }
                 editorTreeId = WorldTreeStore.save(context, 0L, wName, js.optString("worldDesc", editorDesc), wb)
                 worldTreesBump++; editorWorldBook = ""
             } else { editorTreeId = null; editorWorldBook = "" }
             showGenerator = false; editingCard = null; showEditor = true
         }
         fun send(userText: String) {
             if (sending) return
             val t = userText.trim(); if (t.isNotBlank()) messages.add("user" to t)
             input = ""; sending = true; streaming = ""
             scope.launch {
                 try {
                     val cfg = CloudApiConfigManager(context).getActive() ?: run { messages.add("assistant" to tr("请先在「模型配置」里设置 API")); sending = false; return@launch }
                     val apiCfg = CloudApiConfig(cfg.baseUrl.trimEnd('/'), cfg.apiKey.trim(), cfg.model.trim())
                     val hist = messages.map { ChatMessage(it.first, it.second) }
                     var acc = ""
                     CloudApiClient(apiCfg).streamChat(messages = hist, systemPrompt = genSys, enableThinking = 0, onReasoningChunk = {}, onContentChunk = { acc += it; streaming = acc })
                     messages.add("assistant" to acc); streaming = ""
                 } catch (e: Exception) { messages.add("assistant" to tr("出错了: %s").format(e.message)) } finally { sending = false }
             }
         }
         androidx.compose.runtime.LaunchedEffect(Unit) { if (messages.isEmpty()) messages.add("assistant" to tr("想创造一个什么样的角色？说说性格、背景、说话风格都行，我会帮你完善，也会问你一些细节～")) }
         androidx.compose.runtime.LaunchedEffect(messages.size, streaming) { val n = messages.size; if (n > 0) chatListState.animateScrollToItem(n - 1) }
         val lastCard = remember(messages.size, sending) { if (sending) null else messages.lastOrNull { it.first == "assistant" }?.let { m -> cardRegex.find(m.second)?.let { try { org.json.JSONObject(it.groupValues[1].trim().removePrefix("```json").removeSuffix("```").trim()) } catch (_: Exception) { null } } } }
         androidx.compose.material3.AlertDialog(
             onDismissRequest = { if (!sending) showGenerator = false },
             title = { Text(tr("AI 对话生成角色卡"), color = scheme.onSurface) },
             text = { Column(modifier = Modifier.height(400.dp)) {
                 LazyColumn(state = chatListState, modifier = Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                     // 追加式对话：index 即稳定身份（第 i 条一旦落定 role/正文就不再变，流式那条是下面单独 item）；
                     // contentType 按 role 分池复用。正文 stripCard 结果按 text 记忆化，滚动进出不再逐帧重跑正则。
                     items(messages.size, contentType = { messages[it].first }) { i ->
                         val (role, text) = messages[i]; val isUser = role == "user"
                         val display = if (isUser) text else remember(text) { stripCard(text).ifBlank { text } }
                         Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
                             androidx.compose.material3.Surface(color = if (isUser) scheme.primary else scheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 250.dp)) {
                                 Text(display, color = if (isUser) scheme.onPrimary else scheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(9.dp))
                             }
                         }
                     }
                     if (sending) item {
                         Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                             androidx.compose.material3.Surface(color = scheme.surfaceContainerHigh, shape = RoundedCornerShape(14.dp), modifier = Modifier.widthIn(max = 250.dp)) {
                                 Text(stripCard(streaming).ifBlank { "…" }, color = scheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(9.dp))
                             }
                         }
                     }
                 }
                 if (lastCard != null) {
                     Spacer(Modifier.height(6.dp))
                     Button(onClick = { applyCard(lastCard) }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary)) { Text(tr("✅ 采用这个角色卡（去编辑）"), fontSize = 12.sp) }
                 }
                 Spacer(Modifier.height(6.dp))
                 Row(verticalAlignment = Alignment.CenterVertically) {
                     XtomField(value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f), placeholder = tr("回复或提要求…"), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp), singleLine = false, maxLines = 3, enabled = !sending)
                     IconButton(onClick = { if (input.isNotBlank()) send(input) }, enabled = !sending && input.isNotBlank()) { Icon(Icons.AutoMirrored.Outlined.Send, tr("发送"), tint = scheme.primary) }
                 }
             } },
             confirmButton = { TextButton(onClick = { send("请根据我们的对话，现在生成最终角色卡。") }, enabled = !sending) { Text(tr("让AI生成"), color = scheme.primary) } },
             dismissButton = { TextButton(onClick = { if (!sending) showGenerator = false }) { Text(tr("关闭"), color = scheme.onSurfaceVariant) } },
             containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
         )
     }

     // 从其他 AI 的公开聊天导入角色卡：贴对话文本 / 给一个链接 → CardFromChat 提炼人设+记忆 → 建卡
     if (showChatImport) {
         // 给链接时用 open_page 取正文，取到手后照原「贴文本」流程走。
         fun fetchChatImportUrl(url: String) {
             if (chatImportFetching) return
             chatImportFetching = true; chatImportStatusIsError = false
             chatImportStatus = tr("正在访问链接…")
             scope.launch {
                 val res = com.arix.tool.OpenPageTool(context).execute(org.json.JSONObject().put("url", url))
                 if (res.isError || res.content.isBlank()) {
                     chatImportStatusIsError = true; chatImportStatus = tr("没能取到网页正文，换个链接或直接粘贴文本")
                 } else {
                     chatImportText = res.content
                     chatImportStatus = tr("已取到网页正文，可以点「提炼并生成」了")
                 }
                 chatImportFetching = false
             }
         }
         androidx.compose.material3.AlertDialog(
             onDismissRequest = { if (!chatImportBusy && !chatImportFetching) showChatImport = false },
             title = { Text(tr("从其他 AI 的聊天导入"), color = scheme.onSurface) },
             text = {
                 Column {
                     Text(tr("把你在别的 AI 上跟某个角色的对话复制过来贴在这里，或者直接给一个公开聊天的链接，会提炼出那个角色的人设做成角色卡，并把对话里关于你的重要信息存成记忆。"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                     Spacer(Modifier.height(8.dp))
                     XtomField(value = chatImportText, onValueChange = { chatImportText = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp), placeholder = tr("粘贴对话文本…"), singleLine = false, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp), enabled = !chatImportBusy)
                     Spacer(Modifier.height(8.dp))
                     Row(verticalAlignment = Alignment.CenterVertically) {
                         XtomField(
                             value = chatImportUrl, onValueChange = { chatImportUrl = it }, modifier = Modifier.weight(1f),
                             placeholder = tr("或者贴一个公开聊天链接…"), singleLine = true,
                             leading = { Icon(Icons.Outlined.Link, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp)) },
                             textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp), enabled = !chatImportBusy && !chatImportFetching,
                         )
                         Spacer(Modifier.width(6.dp))
                         TextButton(
                             enabled = !chatImportBusy && !chatImportFetching && chatImportUrl.trim().isNotBlank(),
                             onClick = { fetchChatImportUrl(chatImportUrl.trim()) },
                         ) { Text(if (chatImportFetching) tr("取…") else tr("取正文"), color = scheme.primary) }
                     }
                     if (chatImportStatus.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(chatImportStatus, color = if (chatImportStatusIsError) scheme.error else scheme.primary, style = MaterialTheme.typography.bodySmall) }
                 }
             },
             confirmButton = {
                 TextButton(enabled = !chatImportBusy && !chatImportFetching && chatImportText.trim().length >= 20, onClick = {
                     chatImportBusy = true; chatImportStatusIsError = false; chatImportStatus = tr("生成中…")
                     scope.launch {
                         val cfg = CloudApiConfigManager(context).getActive()
                         if (cfg == null) { chatImportStatusIsError = true; chatImportStatus = tr("请先在「模型配置」里设置 API"); chatImportBusy = false; return@launch }
                         val apiCfg = CloudApiConfig(cfg.baseUrl.trimEnd('/'), cfg.apiKey.trim(), cfg.model.trim())
                         val r = CardFromChat.generate(context, apiCfg, chatImportText) { s -> chatImportStatus = s }
                         chatImportBusy = false
                         if (r.ok) { chatImportStatus = ""; showChatImport = false; android.widget.Toast.makeText(context, r.message, android.widget.Toast.LENGTH_LONG).show() }
                         else { chatImportStatusIsError = true; chatImportStatus = r.message }
                     }
                 }) { Text(if (chatImportBusy) tr("生成中…") else tr("提炼并生成"), color = scheme.primary) }
             },
             dismissButton = { TextButton(onClick = { if (!chatImportBusy && !chatImportFetching) showChatImport = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
             containerColor = scheme.surface, shape = RoundedCornerShape(24.dp),
         )
     }
 } // end CharacterCardPage

 // 角色卡头像：图片 URI → Coil 图片；否则把内容当 emoji/首字显示。全令牌化。
 @Composable
 private fun CardAvatar(avatar: String, name: String, size: androidx.compose.ui.unit.Dp) {
     val scheme = MaterialTheme.colorScheme
     val isImg = avatar.startsWith("content://") || avatar.startsWith("file://") || avatar.startsWith("http") || avatar.startsWith("/")
     Box(modifier = Modifier.size(size).clip(CircleShape).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
         if (isImg) {
             coil.compose.AsyncImage(
                 model = coil.request.ImageRequest.Builder(LocalContext.current).data(avatar).crossfade(true).build(),
                 contentDescription = null,
                 modifier = Modifier.size(size).clip(CircleShape),
                 contentScale = androidx.compose.ui.layout.ContentScale.Crop,
             )
         } else {
             Text(avatar.ifBlank { name.take(1).ifBlank { "?" } }, color = scheme.primary, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold)
         }
     }
 }
 
 // ============================================================
 // AppSettingsPage
 // ============================================================
 
