package com.arix.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Redeem
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Title
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.ui.ImportExportButtons
import com.arix.app.ui.XtomCard
import com.arix.app.ui.XtomField
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.WhisperClient
import com.arix.stt.SttEngine
import com.arix.stt.SttModelManager
import com.arix.tool.ImportExport
import com.arix.tool.TtsTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.arix.app.ui.topChromeGapHeight

// ============================================================
// ConfigPage —— 模型配置（RikkaHub 式「用途卡」）
// 一列用途卡：对话/推理/视觉/标题 + 语音朗读(TTS) + 语音识别(STT，Step 4)。
// 每张卡显示当前激活模型，点开=该用途的配置列表+编辑器（PurposeEditor 复用）。
// 底层数据不变：api_configs 每条一个 purpose，每 purpose 各有一个 isActive（switchTo 只清同 purpose）。
// ============================================================

/** LLM 类用途（走 api_configs）。key 对应 ApiConfigEntity.purpose，各用途各自选激活模型。 */
private data class PurposeDef(val key: String, val label: String, val icon: ImageVector, val hint: String)

private val LLM_PURPOSES = listOf(
    PurposeDef("chat", "对话模型", Icons.Outlined.ChatBubbleOutline, "日常聊天、唤醒助手用这个。配好后主聊天/唤醒直接用它。"),
    PurposeDef("reasoning", "推理模型", Icons.Outlined.Psychology, "思考型/带推理链的模型（如 DeepSeek-R1）。"),
    PurposeDef("vision", "视觉模型", Icons.Outlined.Image, "能看图的多模态模型；发图片时自动路由到它。"),
    PurposeDef("title", "标题模型", Icons.Outlined.Title, "自动给对话起标题，用便宜快的小模型即可。未设则用对话模型。"),
    PurposeDef("agent", "子 Agent 模型", Icons.Outlined.Groups, "AI 派子 agent 并行干活时用，独立于主对话。未设则回退对话模型。"),
    PurposeDef("translate", "命令翻译", Icons.Outlined.Translate, "执行 shell/代码前那个「这条是干啥」按钮用它把命令解释成人话，独立上下文。未设则回退对话模型。"),
    PurposeDef("approval", "权限审批", Icons.Outlined.VerifiedUser, "开了「模型自动审批」后，用它判断 AI 这次工具调用危不危险（安全的自动放行、可疑的仍弹窗问你）。用便宜快的小模型即可。未设则回退对话模型。"),
    PurposeDef("summary", "摘要模型", Icons.Outlined.Compress, "长对话自动压缩上下文时用它把早期消息滚动摘要，用便宜快的小模型即可。未设则回退对话模型。"),
    PurposeDef("embedding", "向量记忆(Embedding)", Icons.Outlined.Memory, "为记忆建立语义索引，让「他记得你」按意思检索而非关键词。需 embedding 专用模型(如 text-embedding-3-small / 硅基流动 BAAI/bge-m3)。未设则用关键词检索。"),
)

/**
 * i18n 收集锚点 —— 永远不会被调用，只为让 `tools/i18n_wrap.py` 扫得到这些串。
 *
 * [LLM_PURPOSES] 的 label/hint 走的是「延迟 tr()」：字面量以中文原串存进 [PurposeDef]，
 * 渲染时才 `tr(pd.label)` / `tr(pd.hint)`。收集脚本只认 `tr() 里直接写中文字面量` 这一种写法，
 * 扫不到上面那些裸字面量，重跑一次就会把它们从 i18n/i18n_table.json 里删掉。
 * ⚠️ 改上面的 label/hint，这里必须同步改。
 */
@Suppress("unused")
private fun purposeI18nKeys() = listOf(
    tr("对话模型"), tr("日常聊天、唤醒助手用这个。配好后主聊天/唤醒直接用它。"),
    tr("推理模型"), tr("思考型/带推理链的模型（如 DeepSeek-R1）。"),
    tr("视觉模型"), tr("能看图的多模态模型；发图片时自动路由到它。"),
    tr("标题模型"), tr("自动给对话起标题，用便宜快的小模型即可。未设则用对话模型。"),
    tr("子 Agent 模型"), tr("AI 派子 agent 并行干活时用，独立于主对话。未设则回退对话模型。"),
    tr("命令翻译"), tr("执行 shell/代码前那个「这条是干啥」按钮用它把命令解释成人话，独立上下文。未设则回退对话模型。"),
    tr("权限审批"), tr("开了「模型自动审批」后，用它判断 AI 这次工具调用危不危险（安全的自动放行、可疑的仍弹窗问你）。用便宜快的小模型即可。未设则回退对话模型。"),
    tr("摘要模型"), tr("长对话自动压缩上下文时用它把早期消息滚动摘要，用便宜快的小模型即可。未设则回退对话模型。"),
    tr("向量记忆(Embedding)"), tr("为记忆建立语义索引，让「他记得你」按意思检索而非关键词。需 embedding 专用模型(如 text-embedding-3-small / 硅基流动 BAAI/bge-m3)。未设则用关键词检索。"),
)

@Composable fun ConfigPage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context, hasAudioPerm: Boolean, requestPerm: () -> Unit) {
    val configManager = remember { CloudApiConfigManager(context) }
    val configs by configManager.allConfigs.collectAsState(initial = emptyList())
    val scheme = MaterialTheme.colorScheme

    // QR 配置分享：把全部配置 JSON 转成二维码给另一台扫；扫码/选图则解码后导入
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val qrImportPicker = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val msg = withContext(Dispatchers.IO) {
                try {
                    val bmp = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
                        ?: return@withContext tr("无法读取图片")
                    val text = QrKit.decode(bmp) ?: return@withContext tr("未识别到二维码")
                    ImportExport.importConfig(text, context)
                } catch (e: Exception) { tr("导入失败: %s").format(e.message) }
            }
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    fun exportQr() {
        scope.launch {
            val json = withContext(Dispatchers.IO) {
                val arr = org.json.JSONArray()
                configs.forEach { arr.put(org.json.JSONObject(ImportExport.exportConfig(it))) }
                org.json.JSONObject().apply { put("version", 1); put("configs", arr) }.toString()
            }
            try { qrBitmap = withContext(Dispatchers.IO) { QrKit.encode(json) } }
            catch (_: Exception) { android.widget.Toast.makeText(context, tr("配置内容过多，二维码放不下，请改用文件/复制导出"), android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    // 当前展开的用途卡（null = 全部收起，只看概览）。
    var openKey by remember { mutableStateOf<String?>(null) }
    fun toggle(k: String) { openKey = if (openKey == k) null else k }
    // 从别的功能一键跳来时（ConfigJump.request），自动展开对应用途卡
    LaunchedEffect(Unit) { ConfigJump.consume()?.let { openKey = it } }

    var showQuick by remember { mutableStateOf(false) }
    var showFree by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.topChromeGapHeight())   // 顶部悬浮玻璃让位：在滚动内容里，内容能从玻璃下滑过去化开
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("模型配置"), color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text(tr("按用途分别指定模型：每个用途各选一个激活模型，互不影响。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
            }
            androidx.compose.material3.FilledTonalButton(onClick = { showFree = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Icon(Icons.Outlined.Redeem, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(tr("免费模型"), fontSize = 12.sp)
            }
            Spacer(Modifier.width(6.dp))
            androidx.compose.material3.FilledTonalButton(onClick = { showQuick = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)) {
                Icon(Icons.Outlined.Bolt, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(tr("快速配置"), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))

        // ===== LLM 类用途卡 =====
        LLM_PURPOSES.forEach { pd ->
            val active = configs.find { it.isActive && it.purpose == pd.key }
            PurposeCard(
                icon = pd.icon, label = tr(pd.label),
                summary = active?.let { "${it.name.ifBlank { tr("未命名") }} · ${it.model.ifBlank { "?" }}" },
                expanded = openKey == pd.key, onToggle = { toggle(pd.key) },
            ) {
                Text(tr(pd.hint), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(bottom = 8.dp))
                PurposeEditor(configManager, scope, context, pd.key)
            }
            Spacer(Modifier.height(8.dp))
        }

        // ===== 语音朗读（TTS）卡 =====
        run {
            val active = configs.find { it.isActive && it.purpose == "tts" }
            PurposeCard(
                icon = Icons.Outlined.RecordVoiceOver, label = tr("语音朗读 (TTS)"),
                summary = active?.let { tr("云端 %s").format(it.model.ifBlank { "?" }) } ?: tr("Edge在线 / 系统语音"),
                summaryIsSet = true, // TTS 总有可用引擎（Edge/系统），不显示"未设置"
                expanded = openKey == "tts", onToggle = { toggle("tts") },
            ) {
                TtsSection(configManager, scope, context)
            }
            Spacer(Modifier.height(8.dp))
        }

        // ===== 语音识别（STT）卡 =====
        run {
            val prov = remember(configs) { SttPrefs.provider(context) }
            val provLabel = when (prov) { "siliconflow" -> tr("硅基流动"); "groq" -> "Groq"; "custom" -> tr("自建 API"); "local" -> tr("本地离线"); else -> null }
            PurposeCard(
                icon = Icons.Outlined.Mic, label = tr("语音识别 (STT)"),
                summary = provLabel, expanded = openKey == "stt", onToggle = { toggle("stt") },
            ) {
                SttSection(scope, context, hasAudioPerm, requestPerm)
            }
            Spacer(Modifier.height(8.dp))
        }

        // 导入导出（导出全部 / 导入，兼容 base_url/api_key 等命名）
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            ImportExportButtons(
                context = context, scope = scope, fileBaseName = "api_configs",
                produceJson = {
                    val arr = org.json.JSONArray()
                    configs.forEach { arr.put(org.json.JSONObject(ImportExport.exportConfig(it))) }
                    org.json.JSONObject().apply { put("version", 1); put("configs", arr) }.toString(2)
                },
                consumeJson = { ImportExport.importConfig(it, context) },
                onResult = { msg -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() },
            )
        }
        // 二维码分享：一台生成、另一台扫码/选图导入（内容仍是我们自己的 JSON）
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { exportQr() }) { Text(tr("二维码分享"), color = scheme.primary, fontSize = 12.sp) }
            TextButton(onClick = { runCatching { qrImportPicker.launch(arrayOf("image/*")) }.onFailure { android.widget.Toast.makeText(context, tr("无法打开图片选择器"), android.widget.Toast.LENGTH_SHORT).show() } }) { Text(tr("扫码导入(选图)"), color = scheme.primary, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (qrBitmap != null) AlertDialog(
        onDismissRequest = { qrBitmap = null },
        title = { Text(tr("配置二维码"), color = scheme.onSurface, fontSize = 15.sp) },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.Image(
                    bitmap = qrBitmap!!.asImageBitmap(), contentDescription = tr("配置二维码"),
                    modifier = Modifier.size(260.dp).clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Color.White).padding(8.dp),
                )
                Spacer(Modifier.height(8.dp))
                Text(tr("用另一台设备的「扫码导入」对准此码，或截图后选图导入。"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { qrBitmap = null }) { Text(tr("关闭"), color = scheme.primary) } },
        containerColor = scheme.surface, shape = RoundedCornerShape(24.dp),
    )

    if (showQuick) QuickConfigDialog(configManager, scope, context, onDismiss = { showQuick = false })
    if (showFree) FreeModelDialog(configManager, scope, context, onDismiss = { showFree = false })
}

/**
 * 快速配置：选一个服务商预设（自动填 baseUrl）+ 填一次 key/model，勾选要套用的用途，一键给多个用途批量建激活模型。
 * 省得同类模型一个用途一个用途重复填。
 */
@Composable
private fun QuickConfigDialog(configManager: CloudApiConfigManager, scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // 服务商预设：名称 → baseUrl、示例模型
    val presets = remember {
        listOf(
            Triple(tr("硅基流动"), "https://api.siliconflow.cn/v1", "deepseek-ai/DeepSeek-V3"),
            Triple("DeepSeek", "https://api.deepseek.com/v1", "deepseek-chat"),
            Triple(tr("智谱 GLM"), "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
            Triple(tr("月之暗面 Kimi"), "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
            Triple("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-chat"),
            Triple("Groq", "https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"),
            Triple("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
            Triple(tr("自定义"), "", ""),
        )
    }
    var presetIdx by remember { mutableStateOf(0) }
    var baseUrl by remember { mutableStateOf(presets[0].second) }
    var apiKey by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(presets[0].third) }
    // 可套用的用途
    // 显示名走 tr()；remember 按当前语言 key，否则切语言后缓存的还是旧语言的标签
    val applyTargets = remember(LocalLang.current) { listOf("chat" to tr("对话"), "reasoning" to tr("推理"), "vision" to tr("视觉"), "title" to tr("标题"), "agent" to tr("子Agent"), "translate" to tr("命令翻译")) }
    val selected = remember { mutableStateListOf("chat") }
    var saving by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Bolt, null, tint = scheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("快速配置"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface) } },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("选服务商 → 填一次 key/模型 → 勾选要套用的用途，一键批量配好。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
                Text(tr("服务商"), color = scheme.onSurface, fontSize = 12.sp)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
                    presets.forEachIndexed { i, p ->
                        val on = presetIdx == i
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) scheme.primary else scheme.surfaceContainerHighest).clickable { presetIdx = i; if (p.second.isNotBlank()) baseUrl = p.second; if (p.third.isNotBlank()) model = p.third }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(p.first, color = if (on) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
                XtomField(value = baseUrl, onValueChange = { baseUrl = it }, label = tr("API 地址"), singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                Spacer(Modifier.height(6.dp))
                XtomField(value = apiKey, onValueChange = { apiKey = it }, label = tr("API Key（多行/逗号=密钥池）"), singleLine = false, maxLines = 3, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                Spacer(Modifier.height(6.dp))
                XtomField(value = model, onValueChange = { model = it }, label = tr("模型名"), singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                Spacer(Modifier.height(10.dp))
                Text(tr("套用到这些用途"), color = scheme.onSurface, fontSize = 12.sp)
                androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
                    applyTargets.forEach { (key, label) ->
                        val on = key in selected
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) scheme.primary else scheme.surfaceContainerHighest).clickable { if (on) selected.remove(key) else selected.add(key) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(label, color = if (on) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && apiKey.isNotBlank() && baseUrl.isNotBlank() && model.isNotBlank() && selected.isNotEmpty(), onClick = {
                saving = true
                scope.launch {
                    val name = presets[presetIdx].first
                    selected.toList().forEach { purpose ->
                        runCatching { configManager.add(name = name, baseUrl = baseUrl, apiKey = apiKey, model = model, purpose = purpose) }
                    }
                    android.widget.Toast.makeText(context, tr("已给 %d 个用途配好模型").format(selected.size), android.widget.Toast.LENGTH_SHORT).show()
                    saving = false; onDismiss()
                }
            }) { Text(if (saving) tr("配置中…") else tr("一键配好"), color = scheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
        containerColor = scheme.surface, shape = RoundedCornerShape(24.dp),
    )
}

/**
 * 拉模型失败 → 人话原因（对齐 ChatScreen.friendlyError 的写法：先给一句人话，再附原始信息）。
 * keyBlank 单独判：大多数服务商没 key 连列表都不给，且各家拒法不一——
 * 实测 硅基流动/Groq/智谱 回 401，Gemini 回的却是 404「Requested entity was not found」。
 * 没填 key 时一律先劝填 key，别拿「检查地址」误导用户。
 */
private fun modelFetchError(code: Int, raw: String, keyBlank: Boolean): String {
    val hint = when {
        keyBlank && (code == 401 || code == 403 || code == 404) -> tr("先把这家的 API Key 填上再拉——大多数服务商不给 key 连模型列表都不让看（没有 key 就点上面的「领取」去申请）。")
        code == 401 || code == 403 -> tr("Key 无效或无权限：检查上面填的 API Key（没有就点「领取」去申请）。")
        code == 404 -> tr("这个地址没有 /models 接口（404），检查 API 地址是否填对。")
        code == 429 -> tr("请求太频繁或额度用尽，稍后再试。")
        code in 500..599 -> tr("服务端错误，稍后重试或换个服务商。")
        raw.contains("timeout", true) || raw.contains("timed out", true) -> tr("请求超时，检查网络后重试。")
        raw.contains("resolve host", true) || raw.contains("failed to connect", true) ||
            raw.contains("ConnectException", true) || raw.contains("UnknownHost", true) -> tr("连不上服务器，检查网络 / API 地址 / 是否需要代理。")
        else -> tr("拉取模型失败")
    }
    return hint + "\n" + raw.take(200)
}

/**
 * 免费模型：挑一个服务商 → 领 key → **从服务商接口现拉模型列表** → 选一个 → 勾选要配的用途，一键配好。
 *
 * 为什么不再硬编码免费模型名：名单烂得太快（改版时实测：原先写死的 4 个 OpenRouter 免费模型全部已下架）。
 * 现在模型名一律来自 ModelCatalog.fetchDetailed 的实时结果；只有「服务商名 / 地址 / 领 key 链接」还是常量——那些是稳定事实。
 * 免费与否只认接口返回的价格：给价的（如 OpenRouter）标免费/付费；不给价的如实标「未知」，绝不猜。
 */
@Composable
private fun FreeModelDialog(configManager: CloudApiConfigManager, scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    data class FP(val name: String, val baseUrl: String, val keyUrl: String, val note: String = "")
    // 用 LocalLang 作 key：切语言后重算，避免 remember 缓存住旧语言的用途名/说明
    val purposes = remember(LocalLang.current) { listOf("chat" to tr("对话"), "reasoning" to tr("推理"), "vision" to tr("视觉"), "embedding" to tr("向量记忆"), "title" to tr("标题")) }
    val providers = remember(LocalLang.current) {
        listOf(
            FP(tr("硅基流动"), "https://api.siliconflow.cn/v1", "https://cloud.siliconflow.cn/account/ak",
                tr("国内直连，注册送额度。接口不返回价格，免费与否请以官网价格页为准。")),
            FP("OpenRouter", "https://openrouter.ai/api/v1", "https://openrouter.ai/keys",
                tr("接口带价格，能直接筛出免费模型；不填 key 也能先浏览列表。免费模型有速率限制。")),
            FP("Google Gemini", "https://generativelanguage.googleapis.com/v1beta/openai", "https://aistudio.google.com/apikey",
                tr("AI Studio 免费额度，多模态强。海外需网络。接口不返回价格。")),
            FP("Groq", "https://api.groq.com/openai/v1", "https://console.groq.com/keys",
                tr("免费、极快。海外需网络。接口不返回价格。")),
            FP(tr("智谱 GLM"), "https://open.bigmodel.cn/api/paas/v4", "https://open.bigmodel.cn/usercenter/apikeys",
                tr("国内直连，带 Flash 字样的通常免费。接口不返回价格。")),
            FP(tr("sub2api 桥接"), "", "",
                tr("你自建的「订阅转 API」端点：填它的地址+token，再从它暴露的模型里选。Arix 只按 OpenAI 兼容方式调用它。")),
        )
    }
    var idx by remember { mutableStateOf(0) }
    var baseUrl by remember { mutableStateOf(providers[0].baseUrl) }
    var key by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var onlyFree by remember { mutableStateOf(true) }
    // 拉取状态
    var loading by remember { mutableStateOf(false) }
    var fetched by remember { mutableStateOf<List<com.arix.app.ModelCatalog.ModelInfo>>(emptyList()) }
    var pricingKnown by remember { mutableStateOf(false) }
    var errText by remember { mutableStateOf("") }
    var reloadTick by remember { mutableStateOf(0) }   // 「重试」用：+1 即重跑拉取
    val checked = remember { mutableStateListOf("chat") }
    var saving by remember { mutableStateOf(false) }

    // baseUrl/key 变了就重拉（防抖 600ms）。key 为空也拉：OpenRouter 免 key 能列；
    // 其他家会 401 —— 那正是我们要给用户看的「先填 key」提示，比空列表诚实。
    LaunchedEffect(baseUrl, key, reloadTick) {
        picked = ""; fetched = emptyList(); errText = ""; pricingKnown = false
        if (baseUrl.isBlank()) { loading = false; return@LaunchedEffect }   // 不复位会卡住上一轮的转圈
        kotlinx.coroutines.delay(600)
        loading = true
        // fetchDetailed 内部已切 IO 线程；取消异常由它原样抛出，这里不做 catch 吞掉
        when (val r = com.arix.app.ModelCatalog.fetchDetailed(baseUrl.trim(), key.trim())) {
            is com.arix.app.ModelCatalog.Result.Ok -> {
                fetched = r.models; pricingKnown = r.pricingKnown
                if (!r.pricingKnown) onlyFree = false      // 判不了免费就别拿「只看免费」骗人
                if (r.models.isEmpty()) errText = tr("这家没返回任何模型。")
            }
            is com.arix.app.ModelCatalog.Result.Err -> errText = modelFetchError(r.code, r.message, key.isBlank())
        }
        loading = false
    }

    val shown = remember(fetched, query, onlyFree) {
        val base = fetched.filter { !onlyFree || it.free == true }
        val q = query.trim()
        if (q.isBlank()) base
        else {
            // 模型 id 又长又带斜杠/版本号，手表小键盘打全打对太难，精确不中时按模糊补
            val exact = base.filter { it.id.contains(q, ignoreCase = true) }
            val have = exact.mapTo(HashSet()) { it.id }
            exact + com.arix.tool.FuzzyMatch.rank(q, base.filter { it.id !in have }, 30) { it.id }.map { it.item }
        }
    }
    val cap = 60   // 不上 LazyColumn（外层已 verticalScroll，嵌套会崩）：截断+搜索缩小范围即可

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Redeem, null, tint = scheme.primary, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(tr("免费模型"), fontSize = 16.sp, fontWeight = FontWeight.Bold, color = scheme.onSurface) } },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(tr("挑服务商 → 领 key → 从它现有的模型里选一个 → 勾选要配的用途，一键配好。模型列表实时从服务商拉取，不是写死的。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(bottom = 6.dp)) {
                    providers.forEachIndexed { i, p ->
                        val on = idx == i
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) scheme.primary else scheme.surfaceContainerHighest).clickable { idx = i; baseUrl = p.baseUrl; onlyFree = true }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(p.name, color = if (on) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
                if (providers[idx].note.isNotBlank()) Text(providers[idx].note, color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
                XtomField(value = baseUrl, onValueChange = { baseUrl = it }, label = tr("API 地址"), singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = TextStyle(fontSize = 12.sp))
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XtomField(value = key, onValueChange = { key = it }, label = tr("API Key（多行/逗号=密钥池）"), singleLine = false, maxLines = 3, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp))
                    if (providers[idx].keyUrl.isNotBlank()) TextButton(onClick = { runCatching { uriHandler.openUri(providers[idx].keyUrl) } }) { Text(tr("领取"), fontSize = 12.sp, color = scheme.primary) }
                }

                Spacer(Modifier.height(8.dp))
                // ---- 模型列表：加载中 / 出错可重试 / 列表 ----
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("选一个模型"), color = scheme.onSurface, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    if (loading) Text(tr("拉取中…"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                    else if (baseUrl.isNotBlank()) TextButton(onClick = { reloadTick++ }, contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)) { Text(if (errText.isNotBlank()) tr("重试") else tr("刷新"), fontSize = 11.sp, color = scheme.primary) }
                }
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), color = scheme.primary, trackColor = scheme.surfaceContainerHighest)

                if (errText.isNotBlank()) {
                    Surface(color = scheme.errorContainer, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        Column(Modifier.padding(8.dp)) {
                            Text(errText, color = scheme.onErrorContainer, fontSize = 11.sp)
                            if (providers[idx].keyUrl.isNotBlank()) TextButton(onClick = { runCatching { uriHandler.openUri(providers[idx].keyUrl) } }, contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp)) { Text(tr("去领取 %s 的 key").format(providers[idx].name), fontSize = 11.sp, color = scheme.primary) }
                        }
                    }
                }

                if (fetched.isNotEmpty()) {
                    // 免费判定说明：给价的按价判，不给价的如实说不知道
                    Text(
                        if (pricingKnown) tr("免费与否按服务商接口返回的价格判定（输入/输出单价都为 0 = 免费）。")
                        else tr("这家接口不返回价格，无法判断哪些免费——下面是它全部可用模型，是否免费请以服务商价格页为准。"),
                        color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (pricingKnown) {
                            androidx.compose.material3.Checkbox(checked = onlyFree, onCheckedChange = { onlyFree = it })
                            Text(tr("只看免费"), color = scheme.onSurface, fontSize = 11.sp, modifier = Modifier.padding(end = 6.dp))
                        }
                        XtomField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.weight(1f), textStyle = TextStyle(fontSize = 12.sp), placeholder = tr("搜索模型"))
                    }
                    Text(tr("共 %d 个可选").format(shown.size), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                    shown.take(cap).forEach { m ->
                        val on = picked == m.id
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(top = 3.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (on) scheme.primaryContainer else scheme.surfaceContainerHighest)
                                .clickable { picked = m.id }.padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(m.id, color = if (on) scheme.onPrimaryContainer else scheme.onSurface, fontSize = 11.sp, modifier = Modifier.weight(1f))
                            val badge = when (m.free) { true -> tr("免费"); false -> tr("付费"); null -> tr("未知") }
                            Text(badge, color = if (m.free == true) scheme.primary else scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    if (shown.size > cap) Text(tr("还有 %d 个没显示，用搜索缩小范围").format(shown.size - cap), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    if (shown.isEmpty() && !loading) Text(if (onlyFree) tr("按价格判定，这家当前没有免费模型；去掉「只看免费」看全部。") else tr("没有匹配的模型。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
                }

                Spacer(Modifier.height(10.dp))
                Text(tr("把它配到这些用途"), color = scheme.onSurface, fontSize = 12.sp)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(top = 4.dp)) {
                    purposes.forEach { (k, label) ->
                        val on = k in checked
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) scheme.primary else scheme.surfaceContainerHighest).clickable { if (on) checked.remove(k) else checked.add(k) }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text(label, color = if (on) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = !saving && picked.isNotBlank() && baseUrl.isNotBlank() && checked.isNotEmpty(), onClick = {
                saving = true
                scope.launch {
                    val name = providers[idx].name
                    var n = 0
                    checked.toList().forEach { p ->
                        // 不用 runCatching：它连 CancellationException 一起吞（本项目铁律：取消必须重抛）
                        try { configManager.add(name = name, baseUrl = baseUrl, apiKey = key, model = picked, purpose = p); n++ }
                        catch (c: kotlinx.coroutines.CancellationException) { throw c }
                        catch (_: Exception) { /* 单个用途写失败就跳过，最后按实际成功数提示 */ }
                    }
                    android.widget.Toast.makeText(context, tr("已给 %d 个用途配好模型").format(n), android.widget.Toast.LENGTH_SHORT).show()
                    saving = false; onDismiss()
                }
            }) { Text(if (saving) tr("配置中…") else tr("一键配好"), color = scheme.primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
        containerColor = scheme.surface, shape = RoundedCornerShape(24.dp),
    )
}

/** 用途卡外壳：卡头（图标+名称+当前激活摘要+展开箭头）+ 展开体。 */
@Composable
private fun PurposeCard(
    icon: ImageVector,
    label: String,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    summaryIsSet: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accents = com.arix.app.theme.LocalXtomAccents.current
    XtomCard {
        Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onToggle), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = scheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(label, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                val set = summary != null || summaryIsSet
                Text(
                    summary ?: tr("未设置"),
                    color = if (set) scheme.primary else scheme.onSurfaceVariant,
                    fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
        }
        AnimatedVisibility(visible = expanded) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) { content() }
        }
    }
}

/**
 * 某个用途（purpose）的模型编辑器：该用途下的配置 chips（选激活）+ 新建 + 完整编辑器。
 * 自包含状态，可放进任意用途卡（对话/推理/视觉/标题/朗读云端模型都复用它）。
 */
@Composable
private fun PurposeEditor(
    configManager: CloudApiConfigManager,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    purpose: String,
) {
    val scheme = MaterialTheme.colorScheme
    val accents = com.arix.app.theme.LocalXtomAccents.current
    val allConfigs by configManager.allConfigs.collectAsState(initial = emptyList())
    val ofPurpose = allConfigs.filter { it.purpose == purpose }

    var editingId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(ofPurpose.map { it.id }, ofPurpose.map { it.isActive }) {
        if (editingId == null || ofPurpose.none { it.id == editingId })
            editingId = ofPurpose.firstOrNull { it.isActive }?.id ?: ofPurpose.firstOrNull()?.id
    }
    val current = ofPurpose.find { it.id == editingId }

    var configName by remember(editingId) { mutableStateOf(current?.name ?: "") }
    var baseUrl by remember(editingId) { mutableStateOf(current?.baseUrl ?: "") }
    var apiKey by remember(editingId) { mutableStateOf(current?.apiKey ?: "") }
    var model by remember(editingId) { mutableStateOf(current?.model ?: "") }
    var modelList by remember(editingId) { mutableStateOf<List<String>>(emptyList()) }
    var fetchingModels by remember { mutableStateOf(false) }
    var fetchMsg by remember { mutableStateOf("") }
    // 填好 baseUrl+key 自动拉模型列表(防抖 800ms)，用户不用手填模型名
    LaunchedEffect(baseUrl, apiKey) {
        if (baseUrl.isBlank() || apiKey.isBlank()) return@LaunchedEffect
        kotlinx.coroutines.delay(800)
        fetchingModels = true; fetchMsg = tr("自动拉取模型中…")
        val list = com.arix.app.ModelCatalog.fetch(baseUrl.trim(), apiKey.trim())
        modelList = list; fetchMsg = if (list.isEmpty()) tr("没拉到，可手填模型名") else String.format(tr("共 %s 个可选，点选即可"), list.size)
        fetchingModels = false
    }
    var systemPrompt by remember(editingId) { mutableStateOf(current?.systemPrompt ?: "") }
    val supportsVision = current?.supportsVision ?: false
    val supportsAudio = current?.supportsAudio ?: false
    val supportsVideo = current?.supportsVideo ?: false
    var temperature by remember(editingId) { mutableStateOf(current?.temperature?.toString() ?: "") }
    var maxTokens by remember(editingId) { mutableStateOf(current?.maxTokens?.toString() ?: "") }
    var topP by remember(editingId) { mutableStateOf(current?.topP?.toString() ?: "") }
    var freqPenalty by remember(editingId) { mutableStateOf(current?.frequencyPenalty?.toString() ?: "") }
    var presPenalty by remember(editingId) { mutableStateOf(current?.presencePenalty?.toString() ?: "") }
    var customHeaders by remember(editingId) { mutableStateOf(current?.customHeaders ?: "") }
    var testResult by remember(editingId) { mutableStateOf("") }
    var pickedNote by remember(editingId) { mutableStateOf("") }
    // 进阶：自定义请求体模板 + 供应商内置联网搜索透传（旁挂在 ApiExtrasStore，不进 DB 结构）
    var bodyTemplate by remember(editingId) { mutableStateOf("") }
    var webSearch by remember(editingId) { mutableStateOf(false) }
    /** 用户显式选的协议；null = 自动按域名认（见 ChatProtocol）。 */
    var protocolPick by remember(editingId) { mutableStateOf<com.arix.cloudapi.ChatProtocol?>(null) }
    var balanceResult by remember(editingId) { mutableStateOf("") }
    // 载入该配置(baseUrl+model)已存的进阶参数；同时 bind 让聊天路径能反查到
    LaunchedEffect(editingId) {
        com.arix.cloudapi.ApiExtrasStore.bind(context)
        bodyTemplate = com.arix.cloudapi.ApiExtrasStore.bodyTemplateText(baseUrl.trimEnd('/'), model.trim())
        webSearch = com.arix.cloudapi.ApiExtrasStore.webSearch(baseUrl.trimEnd('/'), model.trim())
        protocolPick = com.arix.cloudapi.ApiExtrasStore.protocolOverride(baseUrl.trimEnd('/'), model.trim())
    }

    // 生成参数说明弹窗
    var showParamHelp by remember { mutableStateOf(false) }
    if (showParamHelp) AlertDialog(
        onDismissRequest = { showParamHelp = false },
        title = { Text(tr("生成参数说明（人话版）"), color = scheme.onSurface, fontSize = 15.sp) },
        text = { Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
            @Composable fun p(t: String, d: String) { Text(t, color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold); Text(d, color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp)) }
            p(tr("温度 temperature"), tr("控制随机性。调高→回答更发散、有创意，但更容易跑偏/胡说；调低→更稳、更保守、更可复现。写代码/事实类调低(0.2~0.5)，创作/闲聊可调高(0.8~1.2)。"))
            p(tr("top_p"), tr("另一种控随机性的方式(只保留累计概率前 p 的候选词)。调低→更聚焦保守；调高→更多样。一般温度和 top_p 只调其一。"))
            p(tr("最大Token"), tr("单次回复的长度上限。调高→能写更长，但更慢更费钱；调低→更短，可能被截断。"))
            p(tr("频率惩罚 frequency_penalty"), tr("调高→越少重复用过的词(减少啰嗦复读)；为 0 或负→更可能重复。"))
            p(tr("存在惩罚 presence_penalty"), tr("调高→更爱引入新话题/新词(更发散)；调低→更聚焦当前话题。"))
            Text(tr("留空 = 用服务商默认值，不下发该参数。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        } },
        confirmButton = { TextButton(onClick = { showParamHelp = false }) { Text(tr("知道了"), color = scheme.primary) } },
        containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
    )

    // 服务商预设选择器
    var showProviderPicker by remember { mutableStateOf(false) }
    var providerSearch by remember { mutableStateOf("") }
    fun openUrl(u: String) { if (u.isNotBlank()) runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(u))) } }
    fun applyProvider(p: ApiProvider) {
        baseUrl = p.base; model = p.model
        if (configName.isBlank() || ApiProviders.all.any { it.name == configName }) configName = p.name
        pickedNote = p.note; showProviderPicker = false
    }
    if (showProviderPicker) AlertDialog(
        onDismissRequest = { showProviderPicker = false },
        title = { Text(tr("选择 API 服务商"), color = scheme.onSurface, fontSize = 15.sp) },
        text = { Column(Modifier.fillMaxWidth()) {
            XtomField(value = providerSearch, onValueChange = { providerSearch = it }, placeholder = tr("搜索服务商 / 模型…"), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                ApiProviders.groups.forEach { g ->
                    val items = if (providerSearch.isBlank()) g.items else {
                        val exact = g.items.filter { it.name.contains(providerSearch, true) || it.model.contains(providerSearch, true) }
                        exact + com.arix.tool.FuzzyMatch.rankBy(providerSearch, g.items - exact.toSet(), 10) { listOf(it.name, it.model) }.map { it.item }
                    }
                    if (items.isNotEmpty()) {
                        Text("${g.emoji} ${tr(g.title)}", color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                        items.forEach { p ->
                            Surface(onClick = { applyProvider(p) }, shape = RoundedCornerShape(12.dp), color = scheme.surfaceContainerHighest, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Column(Modifier.weight(1f)) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(tr(p.name), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                            val badge = when (p.free) { FreeKind.NO_KEY -> tr("免费·免Key") to scheme.primary; FreeKind.FREE_KEY -> tr("免费·领Key") to scheme.primary; else -> null }
                                            if (badge != null) { Spacer(Modifier.width(6.dp)); Surface(shape = RoundedCornerShape(50), color = badge.second.copy(alpha = 0.18f)) { Text(badge.first, color = badge.second, fontSize = 9.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp)) } }
                                        }
                                        Text(p.model.ifBlank { tr("（模型需自填）") }, color = scheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        if (p.note.isNotBlank()) Text(tr(p.note), color = scheme.onSurfaceVariant, fontSize = 9.sp, maxLines = 3, modifier = Modifier.padding(top = 1.dp))
                                    }
                                    if (p.keyUrl.isNotBlank()) Text(tr("🔗 领Key"), color = scheme.primary, fontSize = 10.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { openUrl(p.keyUrl) }.padding(horizontal = 8.dp, vertical = 6.dp))
                                }
                            }
                        }
                    }
                }
            }
        } },
        confirmButton = { TextButton(onClick = { showProviderPicker = false }) { Text(tr("关闭"), color = scheme.primary) } },
        containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
    )

    // 该用途下的配置 chips（点选=切为激活并载入编辑）+ 新建
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        ofPurpose.forEach { cfg ->
            val sel = cfg.id == editingId
            Surface(
                onClick = { editingId = cfg.id; testResult = ""; pickedNote = ""; scope.launch { if (!cfg.isActive) configManager.switchTo(cfg.id) } },
                shape = RoundedCornerShape(50),
                color = if (sel) scheme.primary else scheme.surfaceContainerHighest,
                border = if (cfg.isActive && !sel) BorderStroke(1.dp, scheme.primary) else null,
                modifier = Modifier.padding(end = 6.dp),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (cfg.isActive) { Text("✓", color = if (sel) scheme.onPrimary else scheme.primary, fontSize = 11.sp); Spacer(Modifier.width(3.dp)) }
                    Text(cfg.name.ifBlank { tr("未命名") }, color = if (sel) scheme.onPrimary else scheme.onSurface, fontSize = 12.sp)
                }
            }
        }
        Surface(onClick = { editingId = null; testResult = ""; pickedNote = "" }, shape = RoundedCornerShape(50), color = scheme.primaryContainer) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Add, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(3.dp)); Text(tr("新建"), color = scheme.onPrimaryContainer, fontSize = 12.sp)
            }
        }
    }
    Spacer(Modifier.height(10.dp))

    // ── 编辑器 ──
    Text(if (editingId == null) tr("新建配置") else tr("编辑配置"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(8.dp))
    XtomField(value = configName, onValueChange = { configName = it }, label = tr("配置名称"), modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    XtomField(value = baseUrl, onValueChange = { baseUrl = it }, label = "Base URL", modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    XtomField(value = apiKey, onValueChange = { apiKey = it }, label = tr("API Key（多个换行/逗号=密钥池，自动轮换）"), password = true, singleLine = false, maxLines = 4, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(6.dp))
    XtomField(value = model, onValueChange = { model = it }, label = "Model", modifier = Modifier.fillMaxWidth())
    // 拉取可用模型：填好 baseUrl+key 点一下，GET /models 列出全部模型直接选，免手填
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        androidx.compose.material3.OutlinedButton(enabled = !fetchingModels && baseUrl.isNotBlank(), onClick = {
            fetchingModels = true; fetchMsg = tr("拉取中…"); modelList = emptyList()
            scope.launch {
                val list = com.arix.app.ModelCatalog.fetch(baseUrl.trim(), apiKey.trim())
                // tr() 的 key 里不能出现 $（i18n_wrap.py 收集时会跳过模板串），带变量的一律走 String.format
                modelList = list; fetchMsg = if (list.isEmpty()) tr("没拉到（检查地址/key，或该商不支持 /models）") else String.format(tr("共 %s 个，点选"), list.size)
                fetchingModels = false
            }
        }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            Text(if (fetchingModels) tr("拉取中…") else tr("拉取可用模型"), fontSize = 12.sp)
        }
        if (fetchMsg.isNotBlank()) Text(fetchMsg, color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(start = 10.dp))
    }
    if (modelList.isNotEmpty()) {
        androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp).heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
            modelList.forEach { mid ->
                val on = model == mid
                Box(Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) scheme.primary else scheme.surfaceContainerHighest).clickable { model = mid }.padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text(mid, color = if (on) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Surface(onClick = { providerSearch = ""; showProviderPicker = true }, shape = RoundedCornerShape(50), color = scheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Public, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr("从服务商预设填入"), color = scheme.onPrimaryContainer, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.weight(1f))
            Text(tr("全部厂商 · 含免费/免Key"), color = scheme.onPrimaryContainer, fontSize = 10.sp)
        }
    }
    if (pickedNote.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text(pickedNote, color = scheme.onSurfaceVariant, fontSize = 10.sp) }

    Spacer(Modifier.height(10.dp))
    XtomField(value = systemPrompt, onValueChange = { systemPrompt = it }, label = tr("System Prompt (可选)"), singleLine = false, maxLines = 3, modifier = Modifier.fillMaxWidth())

    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(tr("生成参数（留空=服务商默认）"), color = scheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(6.dp))
        Text(tr("ⓘ 说明"), color = scheme.primary, fontSize = 10.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { showParamHelp = true }.padding(horizontal = 6.dp, vertical = 2.dp))
    }
    Spacer(Modifier.height(4.dp))
    val numStyle = TextStyle(fontSize = 12.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        XtomField(value = temperature, onValueChange = { temperature = it }, label = tr("温度"), modifier = Modifier.weight(1f), textStyle = numStyle)
        XtomField(value = maxTokens, onValueChange = { maxTokens = it }, label = tr("最大Token"), modifier = Modifier.weight(1f), textStyle = numStyle)
        XtomField(value = topP, onValueChange = { topP = it }, label = "top_p", modifier = Modifier.weight(1f), textStyle = numStyle)
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        XtomField(value = freqPenalty, onValueChange = { freqPenalty = it }, label = tr("频率惩罚"), modifier = Modifier.weight(1f), textStyle = numStyle)
        XtomField(value = presPenalty, onValueChange = { presPenalty = it }, label = tr("存在惩罚"), modifier = Modifier.weight(1f), textStyle = numStyle)
        Spacer(Modifier.weight(1f))
    }
    Spacer(Modifier.height(6.dp))
    XtomField(value = customHeaders, onValueChange = { customHeaders = it }, label = tr("自定义请求头 JSON (可选)"), placeholder = "{\"X-Foo\":\"bar\"}", singleLine = false, maxLines = 2, modifier = Modifier.fillMaxWidth(), textStyle = numStyle)

    // ── 进阶：供应商内置联网搜索 + 自定义请求体模板 ──
    Spacer(Modifier.height(10.dp))
    Text(tr("进阶（可选）"), color = scheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))

    // ── 协议 ──
    // 默认「自动」就够用：官方域名认得出来。这个选择器是给中转站/自建代理准备的——
    // 它们的域名里没有 api.anthropic.com 这类关键字，自动判定认不出，但背后可能就是原生端点。
    Text(tr("接口协议"), color = scheme.onSurface, fontSize = 12.sp)
    Text(
        tr("默认自动按域名认。原生协议能拿到兼容层给不了的东西（Anthropic 的思考签名回传、Gemini 的 thoughtSignature 与 systemInstruction）。中转站认不出时在这里直说。"),
        color = scheme.onSurfaceVariant, fontSize = 9.sp,
    )
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        // null = 自动（不落 override），OpenAI 是唯一显式指定
        val opts: List<Pair<String, com.arix.cloudapi.ChatProtocol?>> = listOf(
            tr("自动") to null,
            tr("OpenAI") to com.arix.cloudapi.ChatProtocol.OPENAI,
        )
        opts.forEach { (label, p) ->
            val on = protocolPick == p
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                    .background(if (on) scheme.primary else scheme.surfaceContainerHighest)
                    .clickable { protocolPick = p }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label, color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                    fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
    // 选了自动时，把「实际会走哪条」显示出来。否则用户填了 api.anthropic.com 也不知道它到底走了原生还是兼容。
    if (protocolPick == null) {
        Text(
            String.format(
                tr("自动判定：%s"),
                com.arix.cloudapi.ChatProtocol.detect(baseUrl.trimEnd('/')).displayName,
            ),
            color = scheme.onSurfaceVariant, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp),
        )
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Switch(checked = webSearch, onCheckedChange = { webSearch = it })
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(tr("供应商内置联网搜索"), color = scheme.onSurface, fontSize = 12.sp)
            Text(tr("Qwen(enable_search)/智谱GLM(web_search) 自动注入；其它供应商请用下方请求体模板。"), color = scheme.onSurfaceVariant, fontSize = 9.sp)
        }
    }
    Spacer(Modifier.height(6.dp))
    XtomField(value = bodyTemplate, onValueChange = { bodyTemplate = it }, label = tr("自定义请求体 JSON (合并进请求体，可选)"), placeholder = "{\"enable_thinking\":true,\"safe_mode\":false}", singleLine = false, maxLines = 6, modifier = Modifier.fillMaxWidth(), textStyle = numStyle)
    Text(tr("按当前 Base URL + Model 归档；顶层键会合并/覆盖进请求体（可下发 provider 特有参数如 enable_thinking / provider 路由等）。留空=不加。"), color = scheme.onSurfaceVariant, fontSize = 9.sp, modifier = Modifier.padding(top = 2.dp))

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
            balanceResult = tr("查询中…")
            scope.launch {
                balanceResult = try {
                    val c = com.arix.cloudapi.CloudApiClient(com.arix.cloudapi.CloudApiConfig(baseUrl.trimEnd('/'), apiKey.trim(), model.trim()))
                    c.queryBalance()
                } catch (e: Exception) { "❌ ${e.message}" }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest), modifier = Modifier.heightIn(min = 32.dp), shape = RoundedCornerShape(16.dp)) { Text(tr("查余额"), color = scheme.primary, fontSize = 11.sp) }
        Spacer(Modifier.width(8.dp))
        if (balanceResult.isNotBlank()) Text(balanceResult, color = if (balanceResult.startsWith("❌") || balanceResult.startsWith("查询失败")) scheme.error else scheme.onSurfaceVariant, fontSize = 11.sp)
    }

    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = {
            testResult = tr("测试中…")
            scope.launch {
                try {
                    val c = com.arix.cloudapi.CloudApiClient(com.arix.cloudapi.CloudApiConfig(baseUrl.trimEnd('/'), apiKey.trim(), model.trim(), customHeaders = customHeaders.trim().ifBlank { null }))
                    var got = ""
                    val r = c.streamChat(listOf(com.arix.cloudapi.model.ChatMessage("user", "hi")), null, 0, null, onReasoningChunk = {}, onContentChunk = { got += it })
                    // ✅/❌ 前缀是下面判成败的哨兵（startsWith("✅")），必须留在 tr() 外面：
                    // 混进 key 里就得指望 33 种译文都不把这个字符弄丢。
                    testResult = if (r.error != null) "❌ ${r.error}" else "✅ " + tr("连接成功")
                } catch (e: Exception) { testResult = "❌ ${e.message}" }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest), modifier = Modifier.heightIn(min = 32.dp), shape = RoundedCornerShape(16.dp)) { Text(tr("测试连接"), color = scheme.primary, fontSize = 11.sp) }
        Spacer(Modifier.width(8.dp))
        if (testResult.isNotBlank()) Text(testResult, color = if (testResult.startsWith("✅")) scheme.primary else scheme.error, fontSize = 11.sp)
    }

    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        if (current != null) {
            Button(onClick = { scope.launch { configManager.delete(current.id); editingId = null } }, colors = ButtonDefaults.buttonColors(containerColor = scheme.errorContainer), modifier = Modifier.heightIn(min = 34.dp), shape = RoundedCornerShape(17.dp)) { Text(tr("删除"), color = scheme.onErrorContainer, fontSize = 12.sp) }
            Spacer(Modifier.width(6.dp))
        }
        Button(onClick = {
            if (configName.isBlank()) { testResult = "❌ " + tr("请填配置名称"); return@Button }
            val bt = bodyTemplate.trim()
            if (bt.isNotBlank()) { try { org.json.JSONObject(bt) } catch (e: Exception) { testResult = "❌ " + tr("请求体模板不是合法 JSON"); return@Button } }
            val tmp = temperature.trim().toFloatOrNull(); val mtk = maxTokens.trim().toIntOrNull()
            val tp = topP.trim().toFloatOrNull(); val fp = freqPenalty.trim().toFloatOrNull(); val pp = presPenalty.trim().toFloatOrNull()
            val ch = customHeaders.trim().ifBlank { null }
            val id = editingId; val keepActive = current?.isActive ?: false
            scope.launch {
                if (id != null) configManager.update(id, configName, baseUrl, apiKey, model, systemPrompt, purpose, keepActive, supportsVision, supportsAudio, supportsVideo, tmp, tp, mtk, fp, pp, ch)
                else {
                    val newId = configManager.add(configName, baseUrl, apiKey, model, systemPrompt, purpose, supportsVision, supportsAudio, supportsVideo, tmp, tp, mtk, fp, pp, ch)
                    editingId = newId
                    configManager.switchTo(newId) // 新建即设为该用途的激活模型（RikkaHub：配好就用）
                }
                // 进阶参数按 baseUrl+model 旁挂存储（与主配置分离，不动 DB 结构）
                com.arix.cloudapi.ApiExtrasStore.set(context, baseUrl.trimEnd('/'), model.trim(), bodyTemplate, webSearch)
                // 协议单独存：set() 只管 body/webSearch 两项，且会原样保留已有的 protocol 键，
                // 所以这两句的先后顺序不影响结果（但别把它合进上面那句，理由见 setProtocol 的注释）
                com.arix.cloudapi.ApiExtrasStore.setProtocol(context, baseUrl.trimEnd('/'), model.trim(), protocolPick)
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), modifier = Modifier.heightIn(min = 34.dp), shape = RoundedCornerShape(17.dp)) { Text(if (editingId != null) tr("更新") else tr("保存并启用"), color = scheme.onPrimary, fontSize = 12.sp) }
    }
}

/**
 * 语音朗读（TTS）卡体：引擎选择 + （云端时）内嵌云端 TTS 模型编辑 + 音色 + 离线神经语音下载 + 试听。
 * 从旧 TtsPage 搬入，底层仍调 TtsTool / TtsModelManager，业务不变。
 */
@Composable
private fun TtsSection(
    configManager: CloudApiConfigManager,
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
) {
    val scheme = MaterialTheme.colorScheme
    val accents = com.arix.app.theme.LocalXtomAccents.current
    val ttsModelManager = remember { com.arix.stt.TtsModelManager(context) }
    val ttsTool = remember { TtsTool(context) }
    var ready by remember { mutableStateOf(ttsModelManager.isReady()) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf(TtsTool.enginePref(context)) }
    var lastEngine by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf(TtsTool.voicePref(context)) }
    var mmxGroup by remember { mutableStateOf(TtsTool.mmxGroup(context)) }
    var mmxKey by remember { mutableStateOf(TtsTool.mmxKey(context)) }
    var mmxVoice by remember { mutableStateOf(TtsTool.mmxVoice(context)) }
    var mmxModel by remember { mutableStateOf(TtsTool.mmxModel(context)) }
    fun saveMmx() = TtsTool.setMinimax(context, mmxGroup, mmxKey, mmxVoice, mmxModel)

    Text(tr("语音引擎"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf("auto" to tr("自动"), "cloud" to tr("云端"), "minimax" to "Minimax", "neural" to tr("离线神经"), "edge" to tr("Edge在线"), "system" to tr("系统")).forEach { (key, label) ->
            Button(
                onClick = { engine = key; TtsTool.setEnginePref(context, key) },
                colors = ButtonDefaults.buttonColors(containerColor = if (engine == key) scheme.primary else scheme.surfaceContainerHighest),
                modifier = Modifier.heightIn(min = 34.dp), shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) { Text(label, color = if (engine == key) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 11.sp) }
        }
    }
    Text(tr("自动=离线神经(已下)→云端→系统。国内推荐「云端」，用下面的云端朗读模型(如硅基流动 CosyVoice)。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))

    if (engine == "cloud" || engine == "auto") {
        Spacer(Modifier.height(10.dp))
        Text(tr("云端朗读模型"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(tr("如硅基流动 baseUrl=https://api.siliconflow.cn/v1，模型=FunAudioLLM/CosyVoice2-0.5B。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        PurposeEditor(configManager, scope, context, "tts")
        Spacer(Modifier.height(8.dp))
        XtomField(value = voice, onValueChange = { voice = it; TtsTool.setVoicePref(context, it) }, label = tr("音色 voice（CosyVoice 必填，如 FunAudioLLM/CosyVoice2-0.5B:alex）"), modifier = Modifier.fillMaxWidth())
    }

    if (engine == "minimax") {
        Spacer(Modifier.height(10.dp))
        Text(tr("Minimax 语音（原生 T2A · 情绪/语气丰富）"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text(tr("在 platform.minimaxi.com 拿 GroupId 与 API Key；voice_id 用官方音色(如 male-qn-qingse)或你克隆的音色。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        XtomField(value = mmxGroup, onValueChange = { mmxGroup = it; saveMmx() }, label = "GroupId", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        XtomField(value = mmxKey, onValueChange = { mmxKey = it; saveMmx() }, label = "API Key", modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        XtomField(value = mmxVoice, onValueChange = { mmxVoice = it; saveMmx() }, label = tr("voice_id（如 male-qn-qingse；也可在角色卡里各绑各的）"), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(6.dp))
        XtomField(value = mmxModel, onValueChange = { mmxModel = it; saveMmx() }, label = tr("模型（默认 speech-02-hd）"), modifier = Modifier.fillMaxWidth())
    }

    Spacer(Modifier.height(12.dp))
    Text(tr("离线神经语音（vits-melo · 中英 · 无 key）"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(4.dp))
    Text(ttsModelManager.statusText(), color = if (ready) scheme.primary else scheme.secondary, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
    if (!ready && !downloading) {
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            downloading = true; progress = tr("准备下载…")
            scope.launch {
                val r = ttsModelManager.download { p -> progress = p }
                downloading = false; ready = ttsModelManager.isReady()
                progress = if (r.isSuccess) tr("下载完成 ✓") else tr("失败: %s").format(r.exceptionOrNull()?.message)
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text(tr("下载神经语音模型（约几十 MB）"), color = scheme.onPrimary)
        }
    }
    if (downloading || progress.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(progress, color = scheme.primary, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }

    Spacer(Modifier.height(12.dp))
    Button(enabled = !testing, onClick = {
        testing = true; lastEngine = ""
        scope.launch {
            val used = try { ttsTool.speak("你好，我是 Arix 的语音朗读。") } catch (e: Exception) { "fail" }
            lastEngine = used; testing = false
        }
    }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
        Text(if (testing) tr("朗读中…") else tr("试听一句"), color = scheme.onPrimary)
    }
    if (lastEngine.isNotBlank()) {
        val label = when (lastEngine) { "neural" -> tr("离线神经语音 ✓"); "cloud" -> tr("云端 ✓"); "minimax" -> "Minimax ✓"; "edge" -> tr("Edge 在线 ✓"); "system" -> tr("系统语音 ✓"); else -> tr("失败：无可用引擎/无网络") }
        Text(tr("刚才用了：%s").format(label), color = if (lastEngine == "fail") scheme.error else scheme.primary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * 语音识别（STT）卡体：引擎(硅基流动/Groq/自建/本地) + 语言 + key/自建端点 + 本地模型下载/加载 + 录音测试。
 * 从旧 SttPage 搬入；配置走独立的 SttPrefs（与对话模型互不干扰），底层仍调 WhisperClient / SttEngine / SttModelManager。
 */
@Composable
private fun SttSection(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    hasAudioPerm: Boolean,
    requestPerm: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accents = com.arix.app.theme.LocalXtomAccents.current
    val modelManager = remember { SttModelManager(context) }
    var engine by remember { mutableStateOf<SttEngine?>(null) }; var engineLang by remember { mutableStateOf("") }
    var modelReady by remember { mutableStateOf(modelManager.isModelReady("zh")) }
    var isPreparing by remember { mutableStateOf(false) }; var prepProgress by remember { mutableStateOf("") }; var downloadingLang by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }; var isRecognizing by remember { mutableStateOf(false) }; var recordingSeconds by remember { mutableLongStateOf(0L) }
    // 从持久化 SttPrefs 载入（跨页共享，唤醒助手/语音输入复用同一份）
    var sttProvider by remember { mutableStateOf(SttPrefs.provider(context)) }; var sttLang by remember { mutableStateOf(SttPrefs.lang(context)) }
    var whisperApiKey by remember { mutableStateOf(SttPrefs.apiKey(context)) }; var customBaseUrl by remember { mutableStateOf(SttPrefs.customBaseUrl(context)) }
    var customModel by remember { mutableStateOf(SttPrefs.customModel(context)) }
    var sttResult by remember { mutableStateOf("") }
    var loadError by remember { mutableStateOf("") }

    LaunchedEffect(sttProvider, sttLang) { if (sttProvider == "local" && sttLang == "zh" && SttModelManager.modelForLang("zh")?.bundled == true && !modelManager.isModelReady("zh") && !isPreparing && downloadingLang.isEmpty()) { isPreparing = true; val result = modelManager.copyBundledModel("zh") { progress -> prepProgress = progress }; isPreparing = false; if (result.isSuccess) modelReady = true } }
    DisposableEffect(Unit) { onDispose { engine?.release() } }

    // 本地模型就绪后自动加载 engine——省去手动「加载模型」那步：内置中文选了本地就直接能录音，不再卡在"没配置"。
    LaunchedEffect(sttProvider, sttLang, modelReady) {
        if (sttProvider == "local" && modelManager.isModelReady(sttLang) && (engine == null || engineLang != sttLang)) {
            val m = SttModelManager.modelForLang(sttLang) ?: return@LaunchedEffect
            loadError = ""
            val eng = SttEngine(modelManager.modelDir(sttLang), m)
            val result = withContext(Dispatchers.IO) { eng.load() }
            if (result.isSuccess) { engine?.release(); engine = eng; engineLang = sttLang }
            else loadError = result.exceptionOrNull()?.message ?: tr("加载失败")
        }
    }

    Text(tr("识别引擎"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        // 引擎名要翻；下面「语言」那排是各语言本名(中文/English/日本語…)，是选项本身不是界面文案，不翻
        listOf("siliconflow" to tr("硅基流动(免费)"), "groq" to tr("Groq(免费)"), "custom" to tr("自建API"), "local" to tr("本地")).forEach { (key, label) ->
            Button(onClick = { sttProvider = key; SttPrefs.setProvider(context, key) }, colors = ButtonDefaults.buttonColors(containerColor = if (sttProvider == key) scheme.primary else scheme.surfaceContainerHighest), modifier = Modifier.heightIn(min = 30.dp), shape = RoundedCornerShape(14.dp)) { Text(label, color = if (sttProvider == key) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 11.sp) }
            Spacer(Modifier.width(3.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(tr("语言"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(6.dp))
    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        listOf("zh" to "中文", "en" to "English", "mix" to "中+英", "pt" to "Português", "vi" to "Tiếng Việt", "ar" to "العربية", "id" to "Indonesia", "ja" to "日本語", "ru" to "Русский", "th" to "ภาษาไทย").forEach { (code, label) ->
            Button(onClick = { sttLang = code; SttPrefs.setLang(context, code) }, colors = ButtonDefaults.buttonColors(containerColor = if (sttLang == code) scheme.primary else scheme.surfaceContainerHighest), modifier = Modifier.heightIn(min = 30.dp), shape = RoundedCornerShape(14.dp)) { Text(label, color = if (sttLang == code) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 11.sp) }
            Spacer(Modifier.width(3.dp))
        }
    }
    Spacer(Modifier.height(8.dp))
    if (sttProvider == "siliconflow" || sttProvider == "groq" || sttProvider == "custom") {
        when (sttProvider) {
            "siliconflow" -> { Text(tr("硅基流动 SenseVoiceSmall (免费·中文强·国内直连)"), color = scheme.primary, fontSize = 13.sp); Text(tr("siliconflow.cn 注册 → API 密钥；免费模型 FunAudioLLM/SenseVoiceSmall"), color = scheme.onSurfaceVariant, fontSize = 11.sp) }
            "groq" -> { Text(tr("Groq Whisper (免费·快·国内需代理)"), color = scheme.primary, fontSize = 13.sp); Text(tr("console.groq.com → API Keys → 创建"), color = scheme.onSurfaceVariant, fontSize = 11.sp) }
            else -> { Text(tr("自定义 Whisper API (OpenAI 兼容)"), color = scheme.secondary, fontSize = 13.sp); Spacer(Modifier.height(4.dp))
                XtomField(value = customBaseUrl, onValueChange = { customBaseUrl = it; SttPrefs.setCustomBaseUrl(context, it) }, label = "API Base URL", modifier = Modifier.fillMaxWidth(), singleLine = true)
                Spacer(Modifier.height(6.dp)); XtomField(value = customModel, onValueChange = { customModel = it; SttPrefs.setCustomModel(context, it) }, label = tr("模型 (如 whisper-1)"), modifier = Modifier.fillMaxWidth(), singleLine = true) }
        }
        Spacer(Modifier.height(6.dp)); XtomField(value = whisperApiKey, onValueChange = { whisperApiKey = it; SttPrefs.setApiKey(context, it) }, label = "API Key", modifier = Modifier.fillMaxWidth(), singleLine = true, password = true)
        Spacer(Modifier.height(8.dp))
    }
    if (sttProvider == "local") {
        val curLangModel = SttModelManager.modelForLang(sttLang)!!; val curReady = modelManager.isModelReady(sttLang); val isBundled = curLangModel.bundled
        Text(tr("本地模型"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(modelManager.modelStatusText(sttLang), color = if (curReady) scheme.primary else scheme.secondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
        if (isPreparing || downloadingLang.isNotEmpty()) { Spacer(Modifier.height(4.dp)); LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = scheme.primary); Text(prepProgress, color = scheme.onSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        if (!isBundled && !curReady && downloadingLang.isEmpty() && !isPreparing) { Spacer(Modifier.height(8.dp)); Button(onClick = { downloadingLang = sttLang; scope.launch { val result = modelManager.downloadModel(sttLang) { p -> prepProgress = p }; downloadingLang = ""; if (result.isSuccess) modelReady = true } }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(tr("下载 %s 模型").format(curLangModel.label), color = scheme.onPrimary) } }
        if (curReady && engine != null && engineLang == sttLang) { Spacer(Modifier.height(4.dp)); Text(tr("模型已加载，可直接录音"), color = scheme.primary, fontSize = 11.sp) }
        else if (curReady && loadError.isEmpty()) { Spacer(Modifier.height(4.dp)); Text(tr("正在加载模型…"), color = scheme.onSurfaceVariant, fontSize = 11.sp) }
        if (loadError.isNotBlank()) { Spacer(Modifier.height(8.dp)); Text(tr("加载失败: %s").format(loadError), color = scheme.error, fontSize = 11.sp); Spacer(Modifier.height(4.dp)); Button(onClick = { engine?.release(); engine = null; engineLang = ""; loadError = ""; scope.launch { val eng = SttEngine(modelManager.modelDir(sttLang), curLangModel); val result = withContext(Dispatchers.IO) { eng.load() }; if (result.isSuccess) { engine = eng; engineLang = sttLang } else loadError = result.exceptionOrNull()?.message ?: tr("加载失败") } }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(tr("重新加载模型"), color = scheme.onPrimary) } }
        Spacer(Modifier.height(8.dp))
    }
    val curReady = modelManager.isModelReady(sttLang)
    val canRecord = when (sttProvider) { "local" -> hasAudioPerm && curReady && engine != null && !isPreparing && downloadingLang.isEmpty(); else -> hasAudioPerm && whisperApiKey.isNotBlank() }
    Text(tr("录音测试"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp))
    if (!hasAudioPerm) { Button(onClick = requestPerm, colors = ButtonDefaults.buttonColors(containerColor = scheme.secondary), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(tr("授予录音权限"), color = scheme.onPrimary) } }
    else if (!canRecord) { Text(when { sttProvider == "local" && isPreparing -> tr("正在准备内置模型…"); sttProvider == "local" && downloadingLang.isNotEmpty() -> tr("正在下载模型…"); sttProvider == "local" && !curReady -> tr("模型未就绪，请先下载"); sttProvider == "local" && loadError.isNotBlank() -> tr("模型加载失败：%s").format(loadError); sttProvider == "local" && engine == null -> tr("正在加载模型…"); else -> tr("请填写 API Key") }, color = scheme.onSurfaceVariant, fontSize = 13.sp) }
    else { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Button(onClick = { if (isRecording) return@Button; isRecording = true; val startedAt = System.currentTimeMillis(); scope.launch(Dispatchers.IO) { val sampleRate = 16000; val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(sampleRate / 10); val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2); if (recorder.state != AudioRecord.STATE_INITIALIZED) { withContext(Dispatchers.Main) { isRecording = false }; return@launch }; val samples = mutableListOf<Float>(); val shortBuf = ShortArray(bufferSize); try { recorder.startRecording(); while (isActive && isRecording) { val read = recorder.read(shortBuf, 0, shortBuf.size); if (read > 0) for (i in 0 until read) samples.add(shortBuf[i] / 32768f); val elapsed = (System.currentTimeMillis() - startedAt) / 1000; if (elapsed != recordingSeconds) withContext(Dispatchers.Main) { recordingSeconds = elapsed }; if (elapsed >= 15) break } } finally { try { recorder.stop() } catch (_: Exception) {}; recorder.release() }; withContext(Dispatchers.Main) { isRecording = false; isRecognizing = true }; val cloud = SttPrefs.resolveCloud(context); if (cloud != null) { val wConfig = CloudApiConfig(cloud.baseUrl, cloud.apiKey, cloud.model); val whisperClient = WhisperClient(wConfig); val result = whisperClient.transcribe(samples.toFloatArray(), sampleRate, sttLang); withContext(Dispatchers.Main) { isRecognizing = false; sttResult = if (result.error != null) tr("错误: %s").format(result.error) else result.text } } else { val eng = engine ?: return@launch; val result = eng.recognize(samples.toFloatArray(), sampleRate); withContext(Dispatchers.Main) { isRecognizing = false; sttResult = if (result.isSuccess) result.getOrThrow().text else tr("错误: %s").format(result.exceptionOrNull()?.message) } } } }, enabled = canRecord && !isRecognizing, colors = ButtonDefaults.buttonColors(containerColor = scheme.error), shape = RoundedCornerShape(14.dp)) { Text(if (isRecognizing) tr("识别中...") else tr("开始录音"), color = scheme.onPrimary) }
        if (isRecording) { Spacer(Modifier.width(8.dp)); Button(onClick = { isRecording = false }, colors = ButtonDefaults.buttonColors(containerColor = scheme.secondary), shape = RoundedCornerShape(14.dp)) { Text(tr("停止"), color = scheme.onPrimary) } }
    }
        if (isRecording) { Spacer(Modifier.height(4.dp)); Text(tr("录音中: %ss").format(recordingSeconds), color = scheme.error, fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
    }
    if (sttResult.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Text(tr("识别结果"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold); Spacer(Modifier.height(4.dp)); Text(sttResult, color = scheme.onSurface, fontSize = 15.sp); Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { TextButton(onClick = { sttResult = "" }) { Text(tr("清除"), color = scheme.onSurfaceVariant, fontSize = 11.sp) } } }
}
