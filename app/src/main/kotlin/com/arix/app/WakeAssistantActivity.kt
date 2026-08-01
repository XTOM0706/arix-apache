package com.arix.app

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.theme.LocalXtomAccents
import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.WhisperClient
import com.arix.cloudapi.model.ChatMessage
import com.arix.tool.TtsTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 唤醒后的 Siri 式浮层助手（半透明 Activity，不跳进主 app）。见需求：应用外唤醒→浮层，
 * 语音对话+及时反馈+打字，多轮不自动停止。语音识别按「语音识别」页设置走云端 WhisperClient 或本地 sherpa。
 */
class WakeAssistantActivity : ComponentActivity() {
    companion object { const val EXTRA_OPENING = "xtom_opening" }   // 主动「来电」时的开场白
    // 再次唤起(singleTask onNewIntent 复用组合)时递增，驱动入场动画重播（否则只有首次有动画）
    private val reTriggerState = androidx.compose.runtime.mutableIntStateOf(0)
    // 浮层是否盖在自家 App 界面上（主动「来电」时最常见：正开着聊天页就被拉起）。见 onStart。
    private val overOwnAppState = androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 锁屏直接唤起：让本 Activity 越过锁屏显示 + 亮屏（普通 Activity 默认被挡在锁屏后）。
        // 不主动请求解锁——语音对话在锁屏上即可用；要进主 app 时再由系统按需验证。
        if (WakeService.lockScreenWakeEnabled(this)) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true); setTurnScreenOn(true)
            } else {
                @Suppress("DEPRECATION")
                window.addFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                )
            }
        }
        // 全屏沉浸：内容铺满方屏。不做的话状态栏/导航栏（手势小白条）区会露出窗口黑底 = 上下大黑边。
        // setDecorFitsSystemWindows(false) 让内容延伸到系统栏后面，再隐藏系统栏（上/下滑可临时唤出，不挤压内容）。
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
        }
        hideSystemBars()
        // 液态玻璃：把背后(桌面/上个 App)真磨砂模糊。需 Android 12+、开了「背景模糊」设置(耗电、默认开)、
        // 且系统当前允许跨窗口模糊(isCrossWindowBlurEnabled：开发者选项关模糊/低端机/省电时为 false)；任一不满足则轻压暗兜底，不崩。
        val crossBlurOn = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            WakeService.bgBlurEnabled(this) && windowManager.isCrossWindowBlurEnabled
        if (crossBlurOn) {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_BLUR_BEHIND or android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { blurBehindRadius = 48; dimAmount = 0.35f }
        } else {
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            window.attributes = window.attributes.apply { dimAmount = 0.5f }
        }
        setContent {
            com.arix.app.theme.XtomTheme {
                WakeAssistantScreen(reTrigger = reTriggerState.intValue, overOwnApp = overOwnAppState.value, onClose = { finish() }, onOpenChat = {
                    startActivity(
                        android.content.Intent(this, MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                    finish()
                })
            }
        }
    }

    // 半透明 Activity 不会让背后的 MainActivity onStop，所以 super.onStart() 把自己计入后，
    // 计数 >1 就说明背后还有自家界面（聊天页等）→ 浮层要补遮罩，否则两层 UI 透叠在一起；
    // ==1 说明背后是桌面/别的 App → 保持全透明，维持「透出桌面磨砂」的既定设计。
    override fun onStart() {
        super.onStart()
        overOwnAppState.value = AppForeground.startedActivityCount > 1
    }

    /** 隐藏状态栏 + 导航栏（含手势小白条），内容铺满方屏。上/下滑可临时唤出，不挤压内容。 */
    private fun hideSystemBars() {
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // 弹输入法/关对话框/从别处返回后系统会把系统栏重新显出——重获焦点即重隐，避免小白条/状态栏黑边回来。
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    // singleTask：本 Activity 已开着时，新的主动「来电」经 onNewIntent 送达而非重建。
    // 必须 setIntent 覆盖旧 intent，否则后续任何读 getIntent() 的地方（如开场白 EXTRA_OPENING）会拿到过期的旧值。
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        reTriggerState.intValue++   // 再次唤起→重播入场动画
    }

    override fun onDestroy() {
        super.onDestroy()
        // 浮层关掉后让唤醒服务重新武装（应用外→浮层→关掉后仍能再次唤醒）
        WakeService.notifyOverlayClosed()
    }
}

private enum class AsstPhase { IDLE, LISTENING, TRANSCRIBING, THINKING, SPEAKING }

// 高频状态隔离：音量每帧写、流式每 token 写。放进 holder，只让读它的子组件重组，
// 不再每帧重跑整个 WakeAssistantScreen（那会连累流光/布局 → 卡顿抽搐）。
private class AmpState { var v by mutableStateOf(0f) }
private class StreamState { var v by mutableStateOf("") }

@Composable
internal fun WakeAssistantScreen(
    reTrigger: Int = 0,
    // 浮层是否盖在自家 App 界面上。悬浮窗/数字助手会话两条宿主路径没有「自己」这个 Activity，
    // 直接问前台状态即可；Activity 路径要减掉自己，故由 [WakeAssistantActivity] 显式传入。
    overOwnApp: Boolean = AppForeground.isForeground,
    onClose: () -> Unit,
    onOpenChat: () -> Unit,
) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val configManager = remember { CloudApiConfigManager(context) }
    val ttsTool = remember { TtsTool(context) }

    var phase by remember { mutableStateOf(AsstPhase.IDLE) }
    var status by remember { mutableStateOf(tr("正在准备…")) }
    val stream = remember { StreamState() }        // 流式回答（隔离，只让流式气泡重组）
    var input by remember { mutableStateOf("") }
    val attachments = remember { mutableStateListOf<String>() } // 待发送附件 uri（图片/文件）
    var lastWasVoice by remember { mutableStateOf(false) } // 上轮是否语音输入 → 决定回答后是否自动继续听
    var autoListenTick by remember { mutableStateOf(0) }   // ++ 触发下一轮自动聆听
    val amp = remember { AmpState() }              // 实时音量（隔离，只让 AudioViz 重组）
    var hasStream by remember { mutableStateOf(false) } // 是否有流式内容（低频，用于是否显示流式气泡）
    val reasoning = remember { StreamState() }     // 流式思考（隔离，只让思考块重组）
    val conversation = remember { mutableStateListOf<ChatMessage>() }
    @Suppress("UNused") var recorderStop by remember { mutableStateOf<(() -> Unit)?>(null) }
    // 身份上下文：绑定当前/默认角色卡 → 唤醒出来的是「他」。开场算一次续接摘要 + 取模型系统提示。
    var cardId by remember { mutableStateOf<Long?>(null) }
    var convId by remember { mutableStateOf<Long?>(null) } // 唤醒对话入库的会话 id（首轮建，对话管理可见、可回看）
    var continuationText by remember { mutableStateOf<String?>(null) }
    var apiSysPrompt by remember { mutableStateOf<String?>(null) }
    // 会话身份（头像+名称）：AI 取当前角色卡名+头像，用户取全局偏好 —— 与主聊天一致，让唤醒出来的是「他」
    var identity by remember { mutableStateOf(ChatIdentity()) }

    // 一轮对话：文字 → 组装人设/记忆/状态/环境 → AI 流式 → TTS → 回到聆听
    fun runTurn(userText: String) {
        val atts = attachments.toList()
        if (userText.isBlank() && atts.isEmpty()) return
        attachments.clear()
        conversation.add(ChatMessage("user", userText, attachments = atts.ifEmpty { null }))
        phase = AsstPhase.THINKING; status = tr("思考中…"); stream.v = ""; reasoning.v = ""; hasStream = false
        scope.launch {
            val cfg = configManager.getChatConfig()
            if (cfg == null) { status = tr("未配置对话模型（去「模型配置」激活一个用途为“对话”的模型）"); phase = AsstPhase.IDLE; return@launch }
            val sysPrompt = try {
                WakeAssistantContext.buildSystemPrompt(context, cardId, null, userText, continuationText, apiSysPrompt)
            } catch (_: Exception) { apiSysPrompt }
            val client = CloudApiClient(cfg)
            // 附件：图片→base64、文本文件→注入上下文；图片按「识图」路由（有独立识图模型→先识别成文字，否则图直接发主模型）
            var images: List<String>? = null
            if (atts.isNotEmpty()) {
                try {
                    val (imgs, fileCtx) = withContext(Dispatchers.IO) { resolveWakeAttachments(context, atts) }
                    if (fileCtx.isNotBlank() && conversation.isNotEmpty()) {
                        val li = conversation.lastIndex
                        conversation[li] = conversation[li].copy(content = (conversation[li].content + "\n\n" + fileCtx).trim())
                    }
                    if (imgs.isNotEmpty()) {
                        val vision = configManager.getActiveByPurpose("vision")
                        if (vision != null && (vision.model.trim() != cfg.model || vision.baseUrl.trimEnd('/') != cfg.baseUrl)) {
                            status = tr("识图中…")
                            val desc = describeWakeImages(CloudApiConfig(vision.baseUrl.trimEnd('/'), vision.apiKey.trim(), vision.model.trim()), imgs)
                            if (desc.isNotBlank() && conversation.isNotEmpty()) {
                                val li = conversation.lastIndex
                                conversation[li] = conversation[li].copy(content = (conversation[li].content + "\n\n" + PromptLang.pick("【图片内容（识图模型识别）】\n", "Image content (recognized by vision model):\n") + desc).trim())
                            } else images = imgs // 识图失败→图直接发主模型，绝不丢图
                        } else images = imgs // 无独立识图模型→图发主模型（其本身可能支持视觉）
                    }
                } catch (_: Exception) {}
            }
            var answer = ""
            try {
                // 直接把 conversation 交给工具循环：工具调用/结果会实时 add 进去 → UI 立刻显示思考块/工具卡
                answer = streamWithTools(context, client, conversation, sysPrompt, images) { r, c -> reasoning.v = r; stream.v = c; hasStream = c.isNotBlank() }
            } catch (c: kotlinx.coroutines.CancellationException) { throw c   // 浮层关闭=取消：直接结束，别落库/朗读
            } catch (e: Exception) { answer = "出错: ${e.message}" }
            // 出错时附上用的是哪个端点/模型，便于判断是否取错配置（401 常见于 key/端点不匹配）
            if (answer.startsWith("出错")) answer += "\n[模型 ${cfg.model} @ ${cfg.baseUrl}]"
            stream.v = ""; reasoning.v = ""; hasStream = false
            conversation.add(ChatMessage("assistant", answer))
            // 入库：首轮建会话、之后每轮保存 → 唤醒对话能在「对话管理」里找到并回看，不再离页即丢。
            // 会话绑定当前角色卡；标题默认「新对话」→ saveMessages 触发自动起标题（与主聊天一致）。
            try {
                if (convId == null) convId = configManager.conversationManager.create(characterCardId = cardId, source = "voice")
                convId?.let { configManager.saveConversation(it, conversation.toList()) }
            } catch (_: Exception) {}
            // 朗读（Edge/云端/系统，见语音朗读设置）
            phase = AsstPhase.SPEAKING; status = tr("回答中…")
            try { ttsTool.speak(answer) } catch (_: Exception) {}
            // 语音输入的话，读完自动继续听下一轮（免手多轮）；打字输入则回到待命
            if (lastWasVoice) autoListenTick++ else { phase = AsstPhase.IDLE; status = tr("点麦克风继续说，或打字") }
        }
    }

    // 录音 → 云端识别 → 交给 runTurn
    fun startListening() {
        if (phase == AsstPhase.LISTENING) return
        phase = AsstPhase.LISTENING; status = tr("在听…（说完点停止）")
        val stopFlag = java.util.concurrent.atomic.AtomicBoolean(false)
        recorderStop = { stopFlag.set(true) }
        scope.launch {
            val samples = withContext(Dispatchers.IO) { recordUntilStopped(stopFlag) { a -> amp.v = a } }
            amp.v = 0f
            if (samples == null || samples.isEmpty()) { phase = AsstPhase.IDLE; status = tr("没录到声音，点麦克风重试"); return@launch }
            phase = AsstPhase.TRANSCRIBING; status = tr("识别中…")
            // STT 只用「语音识别」页设的专用配置（硅基流动/Groq/自建），自成一套 key/端点。
            // **不再兜底拿对话模型的 baseUrl+key 去打 STT** —— 那会把 Gemini/DeepSeek 的 key 混到语音识别上（端点根本不接 whisper→出错），
            // 就是「语音识别 apikey 和别的混了」的根因。没配就明确让去语音识别页配，绝不串对话模型的配置。
            val text: String = if (SttPrefs.provider(context) == "local") {
                // 用户在「语音识别」页选了本地：走本地 sherpa 识别（内置中文自动就绪），不再误报"没配置"。
                val (t, err) = localSttTranscribe(context, samples)
                if (t.isNullOrBlank()) { status = err ?: "没听清，点麦克风重试"; phase = AsstPhase.IDLE; return@launch }
                t.trim()
            } else {
                val sttCfg = SttPrefs.resolveCloud(context)?.let { CloudApiConfig(it.baseUrl, it.apiKey, it.model) }
                if (sttCfg == null) { status = tr("未配置语音识别（去「语音识别」页设置，推荐硅基流动免费或本地中文）"); phase = AsstPhase.IDLE; return@launch }
                val result = try { WhisperClient(sttCfg).transcribe(samples, 16000, SttPrefs.lang(context)) } catch (e: Exception) { null }
                val t = result?.text?.trim().orEmpty()
                if (t.isBlank()) { status = tr("没听清（%s），点麦克风重试").format(result?.error ?: tr("识别为空")); phase = AsstPhase.IDLE; return@launch }
                t
            }
            lastWasVoice = true // 本轮是语音输入 → 回答后自动继续听
            runTurn(text)
        }
    }

    // 语音输入后自动继续听（免手多轮）：回答读完 autoListenTick++ → 这里触发下一轮聆听
    androidx.compose.runtime.LaunchedEffect(autoListenTick) { if (autoListenTick > 0) startListening() }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        // 绑定当前/默认角色卡 + 算续接摘要 + 取模型系统提示（让唤醒助手带人设/记忆/人格延续）
        try {
            // 优先上次在聊的角色卡（ActiveChatContext），否则默认卡
            val cardMgr = CharacterCardManager(context)
            val card = (com.arix.tool.ActiveChatContext.characterCardId?.let { cardMgr.getById(it) }) ?: cardMgr.getDefault()
            cardId = card?.id
            com.arix.tool.ActiveChatContext.characterCardId = cardId
            identity = ChatIdentity(
                userName = IdentityPrefs.userName(context).ifBlank { "我" },
                userAvatar = IdentityPrefs.userAvatar(context),
                aiName = card?.name ?: "助手",
                aiAvatar = card?.avatarPath?.takeIf { a -> a.startsWith("content://") || a.startsWith("file://") || a.startsWith("http") || a.startsWith("/") }
                    ?: cardId?.let { IdentityPrefs.aiAvatar(context, it) },
            )
            apiSysPrompt = configManager.getActiveByPurpose("chat")?.systemPrompt
            // 每次唤醒都是全新对话：不注入上次对话的续接摘要（continuationText 保持 null）。
            // 角色卡人设 + 长期记忆仍注入，所以唤醒出来还是「他」，只是不接上次那轮的话题。
        } catch (_: Exception) {}
        // 唤醒应答语：先说一句"我在"之类（固定/自定义/AI 生成），让用户知道已唤起、且是「他」在回应
        try {
            // 主动「来电」：主动消息把开场白经 EXTRA_OPENING 传进来，就用它当第一句（AI 主动开口），否则走常规唤醒应答语
            val opening = (context as? android.app.Activity)?.intent?.getStringExtra(WakeAssistantActivity.EXTRA_OPENING)?.takeIf { it.isNotBlank() }
            val greet = WakeService.wakeGreeting(context)
            val greetText = when {
                opening != null -> opening
                greet.isBlank() -> ""
                greet == "[AI]" -> generateGreeting(configManager.getChatConfig(), apiSysPrompt)
                else -> greet
            }
            if (greetText.isNotBlank()) {
                conversation.add(ChatMessage("assistant", greetText))
                phase = AsstPhase.SPEAKING; status = "…"
                try { ttsTool.speak(greetText) } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
        startListening()
    }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { try { ttsTool.shutdown() } catch (_: Exception) {} } }
    // 任务清单是全局单例：进浮层先清一次（别显示上次/主聊天残留），离开也清（别泄到下次唤醒）
    androidx.compose.runtime.DisposableEffect(Unit) { com.arix.tool.TodoBus.clear(); onDispose { com.arix.tool.TodoBus.clear() } }

    // 进入动画 + 唤醒震动：浮层出现时背景渐暗、内容上滑淡入，并轻震一下告知已唤起
    var appear by remember { mutableStateOf(false) }
    // 首次组合不清（reTrigger 初值 0 时 conversation 本来就是空的），之后每次**重新唤起**都开新一轮
    var lastTrigger by remember { mutableStateOf(reTrigger) }
    androidx.compose.runtime.LaunchedEffect(reTrigger) {
        // 重新唤起 = 新一轮对话，把上一轮的记录清掉。
        // 本 Activity 是 singleTask：用户拿 X 关是 finish()（状态自然没了），但按 home/返回键退出时
        // Activity 只是停住、没销毁，下次唤醒复用同一份组合 —— 于是 conversation 这个 remember 还活着，
        // 老对话原样杵在那儿，且界面上压根没有清除入口。
        // 清掉不丢东西：每轮首问就已入库（convId），在「对话管理」里照样回看得到。
        if (reTrigger != lastTrigger) {
            lastTrigger = reTrigger
            conversation.clear(); convId = null; continuationText = null
            stream.v = ""; reasoning.v = ""; hasStream = false
            attachments.clear(); input = ""
        }
        appear = false; kotlinx.coroutines.delay(50); appear = true   // 复位再触发→每次唤起(含再次)都重播入场淡入/上滑
        try {
            val vib = context.getSystemService(android.content.Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O)
                vib?.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vib?.vibrate(30)
        } catch (_: Exception) {}
    }
    val enterAlpha by androidx.compose.animation.core.animateFloatAsState(if (appear) 1f else 0f, tween(320, easing = FastOutSlowInEasing), label = "asstAlpha")
    val enterShift by androidx.compose.animation.core.animateFloatAsState(if (appear) 0f else 48f, tween(380, easing = FastOutSlowInEasing), label = "asstShift")

    // 液态玻璃：根 Box 透明 —— 窗口 FLAG_BLUR_BEHIND 已把背后桌面模糊成磨砂、dim 轻压暗，桌面仍可见。
    // 前景玻璃质感靠半透明表面（胶囊/气泡）。整屏随进入淡入（graphicsLayer 绘制期读状态，不破坏子重组）。
    // pointerInput 拦掉触摸：背景只画不消费，不拦的话点在空白处会穿到背后的界面上去。
    Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = enterAlpha }
        .pointerInput(Unit) { detectTapGestures { } }) {
        // 盖在自家 App 界面上时（主动「来电」最常见：正开着聊天页就被拉起）补一层表面色遮罩：
        // 背后是自家 UI 的话，磨砂+dim 透出来的顶栏/输入栏会和浮层叠成「两层界面」。
        // 盖在桌面/别的 App 上（overOwnApp=false）保持全透明，不动「透出桌面磨砂」的既定设计。
        if (overOwnApp) Box(Modifier.fillMaxSize().background(scheme.surface.copy(alpha = 0.94f)))
        RisingLight(busy = phase == AsstPhase.THINKING || phase == AsstPhase.SPEAKING, reTrigger = reTrigger, modifier = Modifier.fillMaxSize())
        // 唤醒进入：一道流光贴屏幕边缘环绕一周后消失（一次性入场特效）
        EdgeTraceLight(reTrigger = reTrigger, modifier = Modifier.fillMaxSize())

        // 顶部左：当前角色卡身份（头像+名称）——一进来就知道在和「谁」对话
        Row(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Avatar(uri = identity.aiAvatar, fallback = identity.aiName, size = 36.dp)
            Spacer(Modifier.width(9.dp))
            Text(identity.aiName, color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }

        // 右上角：清空 + 关闭。
        // 「清空」只在真有话可清时出现——空着还摆个按钮，圆屏上纯占地方。
        Row(modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (conversation.isNotEmpty()) {
                Box(modifier = Modifier.size(38.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(scheme.surfaceContainerHighest.copy(alpha = 0.7f)).clickable {
                    // 清的是**显示**，不是数据：这轮从首问起就已入库，「对话管理」里回看得到
                    conversation.clear(); convId = null; continuationText = null
                    stream.v = ""; reasoning.v = ""; hasStream = false
                    attachments.clear(); input = ""
                    phase = AsstPhase.IDLE; status = ""
                }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Refresh, tr("清空，开始新一轮"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
            }
            Box(modifier = Modifier.size(38.dp).shadow(4.dp, CircleShape).clip(CircleShape).background(scheme.surfaceContainerHighest.copy(alpha = 0.7f)).clickable(onClick = onClose), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.Close, tr("关闭"), tint = scheme.onSurface, modifier = Modifier.size(20.dp))
            }
        }

        // 内容区撑满整屏：对话流占输入区上方全部空间 + 状态 + 音频可视化 + 输入胶囊；整体随进入上滑
        // 屏幕适配：唤醒助手是贴边全屏浮层，圆屏上被切得最狠，按用户手调的量再往里收
        val floatFit = com.arix.app.theme.LocalScreenFit.current.floatInset
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(start = 16.dp + floatFit, end = 16.dp + floatFit, top = 58.dp + floatFit, bottom = 26.dp + floatFit).graphicsLayer { translationY = enterShift.dp.toPx() }, horizontalAlignment = Alignment.CenterHorizontally) {
            // 对话流：富消息（用户气泡 / AI 思考块+工具卡+正文）+ 流式，撑满可滚；新消息/流式自动滚到底（追着跑）
            val convScroll = rememberScrollState()
            val askPending by com.arix.tool.AskUserBus.pending.collectAsState()
            val todoList by com.arix.tool.TodoBus.state.collectAsState()
            androidx.compose.runtime.LaunchedEffect(conversation.size, stream.v, reasoning.v, phase) {
                runCatching { convScroll.animateScrollTo(convScroll.maxValue) }
            }
            // ask_user 呼出时自动滚过去（免得藏在下面要手动翻）；todo 不自动跟随
            androidx.compose.runtime.LaunchedEffect(askPending != null) {
                if (askPending != null) { kotlinx.coroutines.delay(80); runCatching { convScroll.animateScrollTo(convScroll.maxValue) } }
            }
            // 顶/底柔和淡出：用 DstIn alpha 蒙版让内容在上下边缘渐隐到透明（露出背后模糊桌面），而非盖一条黑边
            Box(modifier = Modifier.fillMaxWidth().weight(1f)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    val fade = (34.dp.toPx() / size.height).coerceIn(0f, 0.45f)
                    drawRect(
                        brush = Brush.verticalGradient(
                            0f to Color.Transparent, fade to Color.Black,
                            1f - fade to Color.Black, 1f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                }) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(convScroll), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.Bottom)) {
                    val recent = conversation.takeLast(30)
                    val base = conversation.size - recent.size
                    recent.forEachIndexed { i, msg ->
                        androidx.compose.runtime.key(base + i) {
                            val vis = remember { androidx.compose.animation.core.MutableTransitionState(false).apply { targetState = true } }
                            AnimatedVisibility(visibleState = vis, enter = fadeIn(tween(240)) + slideInVertically(tween(260, easing = FastOutSlowInEasing)) { it / 4 }) {
                                ConversationMsg(msg, scheme, identity)
                            }
                        }
                    }
                    // 流式/思考中气泡（隔离，只随 token 重组）
                    if (phase == AsstPhase.THINKING || hasStream || reasoning.v.isNotBlank()) {
                        StreamingAsstBubble(reasoning, stream, scheme, identity)
                    }
                    // AI 反问澄清 / 任务清单卡：语音浮层里也能用（复用主聊天同款卡片）
                    askPending?.let { AppearAnim { AskUserCard(it) } }
                    if (todoList.isNotEmpty()) AppearAnim { TodoCard(todoList) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(status, color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(8.dp))

            // 音频可视化（输入框上方）——读隔离的 amp holder，不连累整屏
            AudioViz(amp = amp, active = phase == AsstPhase.LISTENING, modifier = Modifier.fillMaxWidth().height(44.dp))
            Spacer(Modifier.height(10.dp))

            // 附件预览（胶囊上方，复用应用内 AttachmentThumb）
            if (attachments.isNotEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    attachments.forEachIndexed { i, uri ->
                        AttachmentThumb(uri, onRemove = { if (i < attachments.size) attachments.removeAt(i) })
                    }
                }
                Spacer(Modifier.height(8.dp))
            }

            // 输入胶囊：与内部聊天 ChatInputBar 同一视觉（Surface 圆角26 + surfaceContainerHigh + 描边 + 阴影 + 圆形键）
            val micActive = phase == AsstPhase.LISTENING
            WakeInputCapsule(
                text = input, onTextChange = { input = it }, micActive = micActive,
                onPick = { kind -> AttachmentPickerActivity.pick(context, kind) { picked -> attachments.addAll(picked) } },
                onMic = { if (micActive) recorderStop?.invoke() else startListening() },
                onSend = { val t = input; input = ""; lastWasVoice = false; runTurn(t) },
            )
        }
    }
}

/** 输入胶囊：复刻内部聊天 [ChatInputBar] 的视觉——一体圆角胶囊(surfaceContainerHigh+描边+阴影)，
 *  内含输入框 + 右侧圆形键（有文字→发送 / 聆听中→停止 / 否则→麦克风）。 */
@Composable
private fun WakeInputCapsule(
    text: String,
    onTextChange: (String) -> Unit,
    micActive: Boolean,
    onPick: (String) -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val hasText = text.isNotBlank()
    androidx.compose.material3.Surface(
        color = scheme.surfaceContainerHigh.copy(alpha = 0.68f), // 半透明玻璃质感（透出压暗的桌面）；模糊留项目级统一做
        shape = RoundedCornerShape(26.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
        shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                var menuOpen by remember { mutableStateOf(false) }
                Box(Modifier.size(38.dp).clip(CircleShape).clickable { menuOpen = true }, contentAlignment = Alignment.Center) {
                    Icon(Icons.Outlined.Add, contentDescription = tr("附件"), tint = scheme.primary, modifier = Modifier.size(24.dp))
                }
                androidx.compose.material3.DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(tr("拍照"), color = scheme.onSurface) }, leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null, tint = scheme.primary) }, onClick = { menuOpen = false; onPick("camera") })
                    androidx.compose.material3.DropdownMenuItem(text = { Text(tr("图片"), color = scheme.onSurface) }, leadingIcon = { Icon(Icons.Outlined.Image, null, tint = scheme.primary) }, onClick = { menuOpen = false; onPick("image") })
                    androidx.compose.material3.DropdownMenuItem(text = { Text(tr("文件"), color = scheme.onSurface) }, leadingIcon = { Icon(Icons.Outlined.AttachFile, null, tint = scheme.primary) }, onClick = { menuOpen = false; onPick("file") })
                }
            }
            // 悬浮窗宿主平时带 FLAG_LAYOUT_NO_LIMITS（消上下黑边），代价是 ime inset 恒为 0、内容根的
            // imePadding() 失效 → 键盘弹起盖住本胶囊。输入框拿到焦点时让宿主临时摘掉该 flag，失焦再加回来。
            // 非悬浮窗宿主（Activity / 数字助手会话）下该调用是空操作。
            androidx.compose.runtime.DisposableEffect(Unit) {
                onDispose { WakeOverlayHost.setImeMode(false) }
            }
            androidx.compose.foundation.text.BasicTextField(
                value = text, onValueChange = onTextChange,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp).heightIn(min = 20.dp, max = 120.dp)
                    .onFocusChanged { WakeOverlayHost.setImeMode(it.isFocused) },
                textStyle = androidx.compose.ui.text.TextStyle(color = scheme.onSurface, fontSize = 15.sp),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                maxLines = 4,
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (text.isEmpty()) Text(tr("打字…"), color = scheme.onSurfaceVariant, fontSize = 15.sp)
                        inner()
                    }
                },
            )
            Box(
                modifier = Modifier.size(40.dp).clip(CircleShape)
                    .background(if (micActive) scheme.error else scheme.primary)
                    .clickable { if (hasText && !micActive) onSend() else onMic() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (micActive) Icons.Outlined.Stop else if (hasText) Icons.AutoMirrored.Outlined.Send else Icons.Outlined.Mic,
                    contentDescription = null, tint = scheme.onPrimary, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 流式气泡：隔离读 [StreamState]（思考 + 正文），只在 token 到达时重组自身，不连累整屏（修抽搐/卡顿）。 */
@Composable
private fun StreamingAsstBubble(reasoning: StreamState, stream: StreamState, scheme: androidx.compose.material3.ColorScheme, identity: ChatIdentity) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
        Avatar(uri = identity.aiAvatar, fallback = identity.aiName, size = 30.dp); Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.Start) {
            Text(identity.aiName, color = scheme.onSurface.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            if (stream.v.isBlank()) {
                // 无正文：思考中脉冲（ReasoningBlock active；有思考内容则一并显示，与应用内聊天一致）
                ReasoningBlock(reasoning.v, active = true)
            } else {
                if (reasoning.v.isNotBlank()) ReasoningBlock(reasoning.v, active = false)
                Box(Modifier.widthIn(max = 260.dp).shadow(3.dp, RoundedCornerShape(16.dp), clip = false).clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHighest.copy(alpha = 0.68f)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                    MarkdownText(stream.v, color = scheme.onSurface, fontSize = 15.sp, streaming = true)
                }
            }
        }
    }
}

/**
 * 唤醒助手的工具循环（精简版 ChatScreen.runWithToolLoop，复用 [com.arix.tool.ToolManager]，不含聊天卡片 UI）：
 * 流式回答 + 最多 5 轮工具调用；到上限仍无结论则去掉 tools 再问一次，逼模型基于已有结果作答。
 * msgs 为本轮工作副本，工具调用/结果消息只在其内累积。
 */
// 去掉「有 tool_calls 但缺配对 tool 回复」的悬空 assistant 消息，以及找不到归属的 tool 回复——
// 这种残缺序列（上次语音中途被打断/关掉留下的）发给 OpenAI 兼容端点会直接 400。
private fun sanitizeToolPairing(msgs: List<ChatMessage>): List<ChatMessage> {
    if (msgs.none { !it.toolCalls.isNullOrEmpty() }) return msgs   // 没工具调用，快路径
    val respIds = msgs.filter { it.role == "tool" }.mapNotNull { it.toolCallId }.toSet()
    val keptCallIds = HashSet<String>()
    val out = ArrayList<ChatMessage>(msgs.size)
    for (m in msgs) {
        when {
            m.role == "assistant" && !m.toolCalls.isNullOrEmpty() -> {
                if (m.toolCalls!!.all { it.id in respIds }) { m.toolCalls!!.forEach { keptCallIds.add(it.id) }; out.add(m) }
                // 否则整条丢弃（它的 tool 回复也会因下面的判断被丢）
            }
            m.role == "tool" -> if (m.toolCallId != null && m.toolCallId in keptCallIds) out.add(m)
            else -> out.add(m)
        }
    }
    return out
}

private suspend fun streamWithTools(
    context: android.content.Context,
    client: CloudApiClient,
    conversation: MutableList<ChatMessage>,
    sysPrompt: String?,
    images: List<String>?,
    onStream: (reasoning: String, content: String) -> Unit,
): String {
    val maxRounds = 5
    var last = ""
    // 「工具被拒绝后停止本轮」：语音路径同样遵守（设置项默认关）。只认用户当场点的拒绝。
    val denyStopsTurn = ConfigModePrefs.toolDenyStopsTurn(context)
    var deniedStop = false
    // 防 400：清掉历史里「有 tool_calls 却没配对 tool 回复」的悬空消息（上次语音被中断残留会让下次请求 400）
    val cleaned = sanitizeToolPairing(conversation)
    if (cleaned.size != conversation.size) { conversation.clear(); conversation.addAll(cleaned) }
    for (round in 0 until maxRounds) {
        // ask_user/todo 现在在语音浮层里也渲染卡片（见 WakeAssistantScreen），所以语音里也放开可用
        val tools = com.arix.tool.ToolManager.getToolsJson()
        var content = ""; var reason = ""
        val result = client.streamChat(
            conversation.toList(), sysPrompt, 0, if (round == 0) images else null, // 图片只随第一轮发
            tools = if (tools.length() > 0) tools else null,
            onReasoningChunk = { r -> reason += r; onStream(reason, content) },
            onContentChunk = { c -> content += c; onStream(reason, content) },
        )
        if (result.error != null) return "出错: ${result.error}"
        if (result.toolCalls.isNotEmpty()) {
            val toolCallMsgs = result.toolCalls.map { ChatMessage.ToolCallMsg(it.id, it.name, it.arguments, it.extra) }
            // 实时入库：UI 立刻显示「思考块 + 调用 X 工具」卡片
            conversation.add(ChatMessage("assistant", content, reasoning = reason.ifBlank { null }, toolCalls = toolCallMsgs))
            onStream("", "") // 已入库，清空流式气泡
            // 同聊天页：这一批里只要用户当场拒了一个，后面的一律不执行（拒绝短路）
            var batchDenied = false
            result.toolCalls.forEach { tc ->
                // 每个 tool_call_id 都必须配一条结果（协议要求），所以哪怕已经决定要停，也要把这一批走完再停
                val out = if (batchDenied) PromptLang.pick("未执行：同一批里前面的工具调用被用户拒绝了，这一个也一并取消。", "Not executed: a previous tool call in this batch was rejected by the user, so this one is cancelled too.") else try {
                    val r = com.arix.tool.ToolManager.execute(com.arix.tool.ToolCall(tc.id, tc.name, org.json.JSONObject(tc.arguments)))
                    if (r.userDenied) { batchDenied = true; if (denyStopsTurn) deniedStop = true }
                    // 超长结果落盘 + 只内联头尾（答案常在尾部），同聊天页
                    com.arix.tool.ToolOutputStore.forModel(context, tc.name, r.content)
                } catch (c: kotlinx.coroutines.CancellationException) { throw c    // 别吞取消：浮层关了就该停，不能继续入库/朗读
                } catch (e: Exception) { PromptLang.pick("工具执行失败: ${e.message}", "Tool execution failed: ${e.message}") }
                conversation.add(ChatMessage("tool", out, toolCallId = tc.id)) // 实时显示结果卡
            }
            if (deniedStop) {
                // 用户按设置要求「拒了就停」：不再问模型（连下面那次兜底归纳也不问）
                last = "已按你的设置停止这一轮（你拒绝了这次工具调用）。"
                break
            }
        } else {
            last = content.ifEmpty { result.fullContent }; break
        }
    }
    if (last.isBlank() && !deniedStop) {
        var content = ""
        val fin = client.streamChat(conversation.toList(), sysPrompt, 0, null, tools = null,
            onReasoningChunk = {}, onContentChunk = { c -> content += c; onStream("", content) })
        last = if (fin.error != null) "出错: ${fin.error}" else content.ifEmpty { fin.fullContent }
    }
    return last
}

/** 录音直到 stopFlag 置位 / 说完静音 / 15s 上限，返回 16k 单声道 float。onAmplitude 报实时音量(可视化)。 */
@SuppressLint("MissingPermission")
/** 唤醒助手附件解析：图片→base64，文本文件→注入上下文。复刻应用内 resolveAttachments 的行为。 */
private fun resolveWakeAttachments(context: android.content.Context, uris: List<String>): Pair<List<String>, String> {
    val images = mutableListOf<String>()
    val textCtx = StringBuilder()
    val cr = context.contentResolver
    val textExt = Regex("\\.(txt|md|json|xml|csv|log|kt|java|py|js|ts|c|cpp|h|go|rs|html|css|yml|yaml|sql)$", RegexOption.IGNORE_CASE)
    for (u in uris) {
        try {
            val uri = android.net.Uri.parse(u)
            val mime = cr.getType(uri) ?: ""
            var name = uri.lastPathSegment ?: "文件"
            try { cr.query(uri, null, null, null, null)?.use { c -> val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME); if (idx >= 0 && c.moveToFirst()) c.getString(idx)?.let { name = it } } } catch (_: Exception) {}
            when {
                mime.startsWith("image/") -> cr.openInputStream(uri)?.use { images.add(android.util.Base64.encodeToString(it.readBytes(), android.util.Base64.NO_WRAP)) }
                mime.startsWith("text/") || mime.contains("json") || mime.contains("xml") || textExt.containsMatchIn(name) -> {
                    val text = cr.openInputStream(uri)?.bufferedReader()?.use { it.readText() } ?: ""
                    textCtx.append(PromptLang.pick("【附件文件：${name}】\n", "Attachment file: ${name}\n")).append(text.take(8000))
                    if (text.length > 8000) textCtx.append(PromptLang.pick("\n…(内容过长，已截断)", "\n…(content too long, truncated)"))
                    textCtx.append("\n\n")
                }
                else -> textCtx.append(PromptLang.pick("【附件文件：${name}（${mime.ifBlank { "未知类型" }}，无法读取内容）】\n\n", "Attachment file: ${name} (${mime.ifBlank { "Unknown type" }}, content cannot be read)\n\n"))
            }
        } catch (e: Exception) { textCtx.append(PromptLang.pick("【附件读取失败：${e.message ?: ""}】\n\n", "Attachment read failed: ${e.message ?: ""}\n\n")) }
    }
    return images to textCtx.toString().trim()
}

/** 唤醒助手识图：用「识图」模型把图片识别成文字，供主模型据此作答。复刻应用内 describeImages。 */
private suspend fun describeWakeImages(config: CloudApiConfig, images: List<String>): String = try {
    var out = ""
    CloudApiClient(config).streamChat(
        messages = listOf(ChatMessage("user", PromptLang.pick("请详细描述这张/这些图片的内容：文字(逐字OCR)、物体、场景、数据、关键信息，尽量完整客观，供另一个模型据此回答用户。只输出描述本身，不要寒暄。", "Please describe the content of this/these image(s) in detail: text (verbatim OCR), objects, scenes, data, and key information — as complete and objective as possible, for another model to answer the user based on it. Only output the description itself, no chit-chat."))),
        images = images, enableThinking = 0, onReasoningChunk = {}, onContentChunk = { out += it },
    )
    out.trim()
} catch (_: Exception) { "" }

/** 唤醒应答语（AI 生成模式）：让模型按人设说一句极简应答，如"我在"。 */
private suspend fun generateGreeting(cfg: CloudApiConfig?, sysPrompt: String?): String {
    cfg ?: return ""
    return try {
        var out = ""
        CloudApiClient(cfg).streamChat(
            messages = listOf(ChatMessage("user", PromptLang.pick("用户刚喊了你一声。回一句最简短自然的应答就好（像\"我在\"\"在呢\"），是你平时说话的样子。", "The user just called out to you. Reply with the briefest, most natural response (like \"I'm here\"), in your usual manner of speaking."))),
            systemPrompt = sysPrompt, enableThinking = 0, onReasoningChunk = {}, onContentChunk = { out += it },
        )
        out.trim().replace("\n", " ").take(30)
    } catch (_: Exception) { "" }
}

/** 唤醒助手本地 sherpa 识别：确保模型就绪→加载→识别→释放。返回 (文字, 错误)。
 *  模型已改为按需下载（不再随包携带），没下就诚实报错、引导去语音识别页下——
 *  绝不在唤醒这条路上偷偷拉 26MB。 */
/** 本地 sherpa 识别。 */
internal suspend fun localSttTranscribe(context: android.content.Context, samples: FloatArray): Pair<String?, String?> =
    withContext(Dispatchers.IO) {
        try {
            val mm = com.arix.stt.SttModelManager(context)
            val lang = SttPrefs.lang(context)
            // 只有真随包带了才复制；现在中文模型默认不内置，这句多数情况直接跳过
            if (!mm.isModelReady(lang) && com.arix.stt.SttModelManager.modelForLang(lang)?.bundled == true) {
                try { mm.copyBundledModel(lang) {} } catch (_: Exception) {}
            }
            if (!mm.isModelReady(lang)) return@withContext null to "本地模型未就绪，去「语音识别」页下载"
            val m = com.arix.stt.SttModelManager.modelForLang(lang) ?: return@withContext null to "不支持的语言: $lang"
            val eng = com.arix.stt.SttEngine(mm.modelDir(lang), m)
            val load = eng.load()
            if (load.isFailure) return@withContext null to "本地模型加载失败: ${load.exceptionOrNull()?.message}"
            try {
                val r = eng.recognize(samples, 16000)
                if (r.isSuccess) r.getOrThrow().text to null else null to "识别失败: ${r.exceptionOrNull()?.message}"
            } finally { eng.release() }
        } catch (e: Throwable) { null to (e.message ?: "本地识别异常") }
    }

private fun recordUntilStopped(stopFlag: java.util.concurrent.atomic.AtomicBoolean, onAmplitude: (Float) -> Unit = {}): FloatArray? {
    val sampleRate = 16000
    val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) return null
    val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2)
    if (recorder.state != AudioRecord.STATE_INITIALIZED) { recorder.release(); return null }
    val samples = ArrayList<Float>(sampleRate * 4)
    val buf = ShortArray(minBuf / 2)
    val startedAt = System.currentTimeMillis()
    var seenSpeech = false
    var silenceMs = 0L
    try {
        recorder.startRecording()
        while (!stopFlag.get()) {
            val read = recorder.read(buf, 0, buf.size)
            if (read <= 0) continue
            var sum = 0.0
            for (i in 0 until read) { val v = buf[i] / 32768f; samples.add(v); sum += v * v }
            val rms = sqrt(sum / read).toFloat()
            onAmplitude(rms)
            val chunkMs = read * 1000L / sampleRate
            if (rms > 0.02f) { seenSpeech = true; silenceMs = 0 }
            else if (seenSpeech) { silenceMs += chunkMs; if (silenceMs > 1200) break } // 说完静音自动停
            if (System.currentTimeMillis() - startedAt > 15000) break
        }
    } catch (_: Exception) {
    } finally {
        try { recorder.stop() } catch (_: Exception) {}
        recorder.release()
    }
    return if (samples.isEmpty()) null else FloatArray(samples.size) { samples[it] }
}

/** 对话气泡：你=右对齐主色气泡，AI=左对齐高对比表面气泡；两侧带头像+名称（AI 取角色卡，你取全局身份）。 */
@Composable
private fun AsstBubble(me: Boolean, text: String, scheme: androidx.compose.material3.ColorScheme, identity: ChatIdentity) {
    val name = if (me) identity.userName else identity.aiName
    val avatar = if (me) identity.userAvatar else identity.aiAvatar
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (me) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        if (!me) { Avatar(uri = avatar, fallback = name, size = 30.dp); Spacer(Modifier.width(8.dp)) }
        Column(horizontalAlignment = if (me) Alignment.End else Alignment.Start) {
            Text(name, color = scheme.onSurface.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
            Box(
                Modifier.widthIn(max = 260.dp)
                    .shadow(3.dp, RoundedCornerShape(16.dp), clip = false)
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (me) scheme.primary.copy(alpha = 0.88f) else scheme.surfaceContainerHighest.copy(alpha = 0.68f))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                if (me) Text(text, color = scheme.onPrimary, fontSize = 15.sp, lineHeight = 21.sp)
                else MarkdownText(text, color = scheme.onSurface, fontSize = 15.sp)
            }
        }
        if (me) { Spacer(Modifier.width(8.dp)); Avatar(uri = avatar, fallback = name, size = 30.dp) }
    }
}

/** 渲染一条历史消息（复用应用内聊天组件）：用户气泡 / AI（思考块+工具调用卡+正文气泡）/ 工具结果卡。 */
@Composable
private fun ConversationMsg(msg: ChatMessage, scheme: androidx.compose.material3.ColorScheme, identity: ChatIdentity) {
    when (msg.role) {
        "user" -> AsstBubble(me = true, text = msg.content, scheme = scheme, identity = identity)
        "tool" -> Row(Modifier.fillMaxWidth().padding(start = 38.dp)) { ToolResultCard(msg.content) }
        else -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start, verticalAlignment = Alignment.Top) {
            Avatar(uri = identity.aiAvatar, fallback = identity.aiName, size = 30.dp); Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.Start) {
                Text(identity.aiName, color = scheme.onSurface.copy(alpha = 0.7f), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp))
                if (!msg.reasoning.isNullOrBlank()) ReasoningBlock(msg.reasoning!!, active = false)
                if (!msg.toolCalls.isNullOrEmpty()) ToolCallCard(msg.toolCalls!!)
                if (msg.content.isNotBlank()) {
                    Box(Modifier.widthIn(max = 260.dp).shadow(3.dp, RoundedCornerShape(16.dp), clip = false).clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHighest.copy(alpha = 0.68f)).padding(horizontal = 12.dp, vertical = 9.dp)) {
                        MarkdownText(msg.content, color = scheme.onSurface, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}

/**
 * 唤醒入场特效：一道流光从左上角出发，贴屏幕边缘（圆角矩形周界）**环绕一周**，末段整体淡出消失。
 * 一次性播放（LaunchedEffect 触发 progress 0→1）；彗星式拖尾（越靠头越亮）+ 主色光晕。
 *
 * reTrigger：本 Activity 是 singleTask，再次唤醒走 onNewIntent **复用同一份组合**，
 * LaunchedEffect(Unit) 便永不重跑 = 只有首次有动画、要重启 App 才恢复。故用它做 key 驱动重播。
 */
@Composable
private fun EdgeTraceLight(reTrigger: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val isRound = androidx.compose.ui.platform.LocalContext.current.resources.configuration.isScreenRound
    val view = androidx.compose.ui.platform.LocalView.current
    // 用真实帧时间驱动，绕开系统「动画时长缩放」——某些设备/省电/部分 Android 11 会把它设成 0，
    // 那样 animateFloatAsState 会一帧跳到终点 = 流光根本不显示。withFrameNanos 不受该缩放影响。
    var progress by remember { mutableStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(reTrigger) {
        progress = 0f
        val durMs = 1400f
        var startNs = 0L; var raw = 0f
        while (raw < 1f) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            if (startNs == 0L) startNs = now
            raw = (((now - startNs) / 1_000_000f) / durMs).coerceIn(0f, 1f)
            progress = FastOutSlowInEasing.transform(raw)
        }
        progress = 1f
    }
    Canvas(modifier = modifier) {
        if (progress <= 0.001f || progress >= 0.999f) return@Canvas
        val stroke = 3.dp.toPx()
        val inset = stroke
        // 贴合真实屏幕形状：圆屏(手表)走整圆周界；方/矩屏读设备真实圆角(API31+)，否则退回 30dp。
        val path = Path().apply {
            if (isRound) {
                addOval(androidx.compose.ui.geometry.Rect(inset, inset, size.width - inset, size.height - inset))
            } else {
                val rad = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                    view.rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() else null)
                    ?: 30.dp.toPx()
                addRoundRect(
                    androidx.compose.ui.geometry.RoundRect(
                        left = inset, top = inset, right = size.width - inset, bottom = size.height - inset,
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(rad, rad),
                    )
                )
            }
        }
        val pm = androidx.compose.ui.graphics.PathMeasure()
        pm.setPath(path, false)
        val total = pm.length
        val head = progress * total
        val trail = total * 0.30f          // 拖尾长度
        val tail = (head - trail).coerceAtLeast(0f)
        val fade = if (progress > 0.8f) (1f - (progress - 0.8f) / 0.2f).coerceIn(0f, 1f) else 1f // 环绕将满时整体淡出
        val steps = 12
        for (i in 0 until steps) {
            val f0 = i / steps.toFloat(); val f1 = (i + 1) / steps.toFloat()
            val seg = Path()
            pm.getSegment(tail + (head - tail) * f0, tail + (head - tail) * f1, seg, true)
            val a = f1 * fade               // 越靠彗头越亮
            drawPath(seg, color = scheme.primary.copy(alpha = a * 0.35f), style = Stroke(width = stroke * 3.5f, cap = StrokeCap.Round))
            drawPath(seg, color = Color.White.copy(alpha = a * 0.9f), style = Stroke(width = stroke, cap = StrokeCap.Round))
        }
    }
}

/**
 * 流光从屏幕底部溢上（半透明，遮挡小）：启动时上涨(flood 0→1) + 流动光带。
 * reTrigger 同 EdgeTraceLight：singleTask 复用组合，不按它做 key 就只有首次会上涨。
 */
@Composable
private fun RisingLight(busy: Boolean, reTrigger: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    // 上涨也走帧驱动：animateFloatAsState 受系统「动画时长缩放」影响，手表/省电模式常把它设成 0 → 一帧跳满，没有上涨过程。
    var flood by remember { mutableStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(reTrigger) {
        flood = 0f
        val durMs = 900f
        var startNs = 0L; var raw = 0f
        while (raw < 1f) {
            val now = androidx.compose.runtime.withFrameNanos { it }
            if (startNs == 0L) startNs = now
            raw = (((now - startNs) / 1_000_000f) / durMs).coerceIn(0f, 1f)
            flood = FastOutSlowInEasing.transform(raw)
        }
        flood = 1f
    }
    // 帧驱动的流动光带：同样绕开动画时长缩放=0（否则 A11 等设备上光带不流动）
    var shimmer by remember { mutableStateOf(0f) }
    androidx.compose.runtime.LaunchedEffect(busy) {
        // 空闲(非 busy)时不再逐帧驱动流光——这是全屏 Canvas，唤醒页是 singleTask 常驻会一直重绘、拖住手表 idle。
        // 只在 busy（聆听/思考/说话）时流动；空闲留一帧静态光带即可，省电且不影响观感。
        if (!busy) return@LaunchedEffect
        val dur = 1600f
        var startNs = 0L
        while (true) {   // withFrameNanos 是取消点，dispose/换 key 时会抛 Cancellation 退出
            val now = androidx.compose.runtime.withFrameNanos { it }
            if (startNs == 0L) startNs = now
            shimmer = (((now - startNs) / 1_000_000f) % dur) / dur
        }
    }
    val cols = listOf(scheme.primary, scheme.tertiary, accents.info)
    Canvas(modifier = modifier) {
        val h = size.height; val w = size.width
        val top = h * (1f - flood) // 流光顶部随 flood 上升，溢满全屏
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Transparent,
                0.55f to scheme.primary.copy(alpha = 0.10f),
                1f to scheme.primary.copy(alpha = 0.34f),
                startY = top, endY = h,
            ),
            topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(w, h - top),
        )
        for (i in 0..2) {
            val ph = (shimmer + i / 3f) % 1f
            val cx = w * ph
            val cy = h - h * flood * 0.35f - i * 40f
            drawCircle(
                brush = Brush.radialGradient(listOf(cols[i].copy(alpha = 0.22f * flood), Color.Transparent), center = Offset(cx, cy), radius = w * 0.5f),
                radius = w * 0.5f, center = Offset(cx, cy),
            )
        }
    }
}

/** 音频可视化条（聆听时随实时音量脉动，输入框上方）。 */
@Composable
private fun AudioViz(amp: AmpState, active: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val t = rememberInfiniteTransition(label = "viz")
    val phase by t.animateFloat(0f, (2.0 * Math.PI).toFloat(), infiniteRepeatable(tween(900, easing = LinearEasing)), label = "vp")
    val loud = (amp.v * 7f).coerceIn(0f, 1f)  // 读隔离 holder：仅本组件随音量重组
    Canvas(modifier = modifier) {
        val bars = 28
        val bw = size.width / bars
        for (i in 0 until bars) {
            // 只在 active（聆听）时读 phase → 空闲时 Canvas 不再逐帧失效重绘（省电、别在没听时也 60fps 转）。
            val level = if (active) {
                val base = (sin((phase + i * 0.5f).toDouble()) * 0.5 + 0.5).toFloat()
                0.12f + base * (0.15f + loud * 0.85f)
            } else {
                val base = (sin((i * 0.5f).toDouble()) * 0.5 + 0.5).toFloat()   // 静态波形，不读 phase
                0.08f + base * 0.12f
            }
            val bh = size.height * level.coerceIn(0.06f, 1f)
            drawRoundRect(
                color = scheme.primary.copy(alpha = if (active) 0.9f else 0.4f),
                topLeft = Offset(i * bw + bw * 0.25f, (size.height - bh) / 2f),
                size = androidx.compose.ui.geometry.Size(bw * 0.5f, bh),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw * 0.25f, bw * 0.25f),
            )
        }
    }
}
