package com.arix.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.wake.TurnEnd
import com.arix.wake.VoiceTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.selects.select
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.withContext
import kotlin.math.sin

// ============================================================
// 语音通话（ChatGPT 式）
//
// 与原来的「语音输入弹窗」的区别：那个是一次性的「录一段→填进输入框→你自己点发送」。
// 这个是**连续对话**：VAD 自动断句(不用点停止) → 转写 → 走正常发送链路 → 朗读答案 →
// 自动继续听下一轮，直到你挂断。AI 说话时也在听，你一开口就掐掉它的话（barge-in）。
//
// 设计取舍：
//  · **不碰 ChatScreen 的发送链路**。转写完只做两件事：填 input.text、把 pendingAutoSend 置 true，
//    剩下的走它原有的 LaunchedEffect(pendingAutoSend)。所以通话产生的消息就是正常消息，
//    会入库、有记忆、能翻历史——不是唤醒助手那种关掉就丢的临时会话。
//  · 「AI 说完了没有」靠 isSending 由 true 变 false 判断，不去插手流式过程。
// ============================================================

// 通话是全屏压黑的「打电话」界面，不跟随明暗主题：底永远是黑的，所以前景必须是固定的亮色。
// 用 scheme.onSurface 的话在浅色主题下是近黑 → 黑底黑字，什么都看不见。
private val CallBg = Color(0xF2000000)
private val CallFg = Color(0xFFF2F2F2)
private val CallFgDim = Color(0xFFAAAAAA)

/** 通话阶段。 */
enum class CallPhase { LISTENING, TRANSCRIBING, THINKING, SPEAKING }

/**
 * @param tts 复用调用方的实例。**别在这里 new**：那会让系统 TTS 起两个服务绑定、离线神经模型
 *   常驻两份（手表上几十 MB×2），而且本页的 stopSpeaking() 停不掉对方那个 → 两个声音一起响。
 * @param isSending 上层是否正在出答案
 * @param onTranscript 转写好的用户话 → 调用方塞进输入框并触发发送
 * @param lastAssistantId 发送前记下最后一条 AI 消息的 id，作为「本轮之前」的水位线
 * @param answerSince 给定水位线，取其后新增的 AI 消息（waifu 模式一条回答会拆成多个气泡，
 *   只取最后一条会念半句）。没有新答案返回 null —— 发送失败/被 STOP 时必须是 null，
 *   否则会把上一轮的旧答案再念一遍。
 */
@Composable
fun VoiceCallOverlay(
    context: android.content.Context,
    tts: com.arix.tool.TtsTool,
    isSending: Boolean,
    onTranscript: (String) -> Unit,
    lastAssistantId: () -> Long?,
    answerSince: (Long?) -> String?,
    onHangUp: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val voiceTurn = remember { VoiceTurn(context) }
    val ttsTool = tts

    var phase by remember { mutableStateOf(CallPhase.LISTENING) }
    var hint by remember { mutableStateOf("") }
    var amp by remember { mutableStateOf(0f) }
    var heard by remember { mutableStateOf("") }        // 最近一句「你说的」，给用户确认识别对不对
    var reply by remember { mutableStateOf("") }        // AI 这轮的回答，随流式生成实时增长（屏幕上边说边显示）
    var micDead by remember { mutableStateOf(false) }   // 麦克风打不开：循环已停，只等用户挂断

    // barge-in 要成立需要**两个**条件同时满足，缺一不可：
    //  ① 本机有 AEC 实现
    //  ② 播放端与录音端都在通话链路上（SpeechRoute.enterCall 成功切了 MODE_IN_COMMUNICATION）
    // 只查 ①（AcousticEchoCanceler.isAvailable()）是**假的安全感**——它只说「这设备有 AEC」，
    // 不说「能消掉你这个声音」。TTS 若播在媒体流上，AEC 拿不到参考流、什么也消不掉，
    // 于是 AI 一开口就把自己打断。
    var canBargeIn by remember { mutableStateOf(false) }

    // 进出通话切系统音频模式；离开时**必须**还原，否则整机音频路由卡在通话态
    DisposableEffect(Unit) {
        com.arix.cloudapi.SpeechRoute.enterCall(context)
        canBargeIn = com.arix.cloudapi.SpeechRoute.inCall && voiceTurn.echoCancelSupported
        onDispose {
            // 主循环随 composable 一起被取消；这里只需把还在响的声音掐掉。
            // **不能 shutdown()** —— 这是 ChatScreen 的实例，销毁了会连带 release 掉正在跑的
            // sherpa 原生句柄（generate() 是阻塞 JNI，协程取消打断不了它）→ use-after-free 崩溃。
            runCatching { ttsTool.stopSpeaking() }
            com.arix.cloudapi.SpeechRoute.exitCall(context)
        }
    }

    // isSending 是普通参数，LaunchedEffect 里读到的是启动那一刻的快照。要在协程里**观察它的变化**
    // 必须经 rememberUpdatedState + snapshotFlow，否则永远看到旧值。
    val sendingNow = androidx.compose.runtime.rememberUpdatedState(isSending)
    val answerNow = androidx.compose.runtime.rememberUpdatedState(answerSince)
    val lastIdNow = androidx.compose.runtime.rememberUpdatedState(lastAssistantId)

    // ── 通话主循环 ─────────────────────────────────────────────
    // 写成**一条顺序流程**而不是几个互相触发的 LaunchedEffect：后者会踩两个坑——
    //  ① onTranscript 后 isSending 还没来得及变 true（发送在上层另一个 effect 里排队），
    //     "答完了吗"若只看 !isSending 会当场判定答完，去读上一轮的旧答案。必须先等它**升**起来。
    //  ② 多个 effect 互相 turn++ 容易转成忙循环。
    LaunchedEffect(Unit) {
        while (true) {
            // 1) 听
            phase = CallPhase.LISTENING
            hint = tr("在听…")
            heard = ""
            reply = ""
            val r = voiceTurn.recordUtterance(
                maxMs = 20000L,
                endSilenceMs = 800L,          // 说话中的自然停顿别急着收音
                leadingSilenceMs = 15000L,    // 一直没人说话就歇一下，不空录着耗电
                echoCancel = false,           // 这轮 AI 没在说话，普通麦克风路径即可
                onAmplitude = { amp = it },
            )
            if (r.end == TurnEnd.MIC_UNAVAILABLE) {
                // 麦克风被占用/权限没了：**不能默默 return**，那样通话就成了一个不动的死界面。
                // 停在这里等用户挂断，并说清原因。
                phase = CallPhase.LISTENING; amp = 0f
                hint = tr("麦克风打不开：可能被其它应用占用，或没给录音权限。点下面挂断。")
                micDead = true
                return@LaunchedEffect
            }
            val samples = r.samples
            if (samples == null) {
                hint = tr("没听到声音，说句话试试")
                kotlinx.coroutines.delay(400)   // 兜底：万一麦克风瞬间返回空，别转成忙循环
                continue
            }

            // 2) 转写
            phase = CallPhase.TRANSCRIBING
            hint = tr("识别中…")
            val text = transcribe(context, samples)
            if (text.isNullOrBlank()) { hint = tr("没听清，再说一次"); kotlinx.coroutines.delay(400); continue }
            heard = text

            // 3) 交给既有发送链路。先记下水位线：本轮答案 = 这条之后新增的 AI 消息。
            phase = CallPhase.THINKING
            hint = tr("思考中…")
            val marker = lastIdNow.value()
            onTranscript(text)

            // 4) 等它真的开始发（升沿）。给 20s：冷启动/首次加载模型可能慢，5s 太紧会误判「没发出去」。
            //    真没发（没配模型等）时不会白等——上层根本不置 isSending，超时后如实告知。
            val started = withTimeoutOrNull(20000L) {
                snapshotFlow { sendingNow.value }.first { it }
            } != null
            if (!started) { hint = tr("没有发出去（模型没配好？）"); kotlinx.coroutines.delay(1200); continue }

            // 5) 流式接收答案：一边等答完（isSending 降沿），一边把新增文字实时显示（"流式输出"）。
            //    answerSince 读的是实时 chatBubbles，生成中就在增长——直接映射到 reply 即可边生成边显示。
            reply = ""
            kotlinx.coroutines.coroutineScope {
                val stream = launch {
                    snapshotFlow { answerNow.value(marker) }.collect { a ->
                        if (!a.isNullOrBlank()) { reply = a; if (phase == CallPhase.THINKING) hint = tr("回答中…") }
                    }
                }
                snapshotFlow { sendingNow.value }.first { !it }   // 降沿=答完
                stream.cancel()
            }

            // 只念**本轮新增**的答案。发送失败/被 STOP → 没有新气泡 → null，绝不重念上一轮。
            val answer = answerNow.value(marker)
            if (answer.isNullOrBlank()) { hint = tr("这轮没有回答"); kotlinx.coroutines.delay(800); continue }

            // 6) 读；能打断的话，同时开一路听你插话，两者**真并行**竞速
            phase = CallPhase.SPEAKING
            hint = if (canBargeIn) tr("回答中…（想插话直接说）") else tr("回答中…")
            val speaking = launch(Dispatchers.IO) {
                try { ttsTool.speak(answer) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
            try {
                if (canBargeIn) {
                    val barge = async { voiceTurn.awaitSpeech(maxMs = 120000L, minSpeechMs = 400L, echoCancel = true) }
                    // select：读完 or 用户开口，谁先到听谁的。
                    // 不用 select 的话 awaitSpeech 会一直等满 120s —— 读完了界面还显示「回答中…」、
                    // 麦克风白开两分钟，用户以为卡死了。
                    val interrupted = select {
                        speaking.onJoin { false }
                        barge.onAwait { it }
                    }
                    if (interrupted) runCatching { ttsTool.stopSpeaking() }   // 系统TTS/离线神经够不到协程取消，显式停
                    speaking.cancelAndJoin()   // 云/Minimax/Edge：取消即停播。join 掉再进下一轮，否则播放器还在拆、新录音已经开了
                    barge.cancelAndJoin()      // 不 join 的话它的 AudioRecord 还占着麦，下一轮 recordUtterance 打不开
                } else {
                    speaking.join()
                }
            } finally {
                runCatching { ttsTool.stopSpeaking() }
            }
            // 读完或被打断 → while 回到第 1 步继续听
        }
    }

    // ── UI ────────────────────────────────────────────────────
    // 返回键 = 挂断，而不是把整个页面导航走
    androidx.activity.compose.BackHandler { onHangUp() }

    // 入场：整屏淡入 + 光球轻微放大。手动帧驱动 appear——系统「动画时长缩放=0」时 animate* 会一帧跳完
    // 就没有过渡了（本项目在唤醒页/通话页反复踩过，见 CallOrb 注释）。
    var appear by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var s = 0L
        while (appear < 1f) {
            val now = withFrameNanos { it }
            if (s == 0L) s = now
            appear = ((now - s) / 1_000_000f / 300f).coerceIn(0f, 1f)   // ~300ms 淡入
        }
    }

    // pointerInput 拦掉触摸：background 只画不消费，不拦的话点在光球上会穿到底下的聊天列表
    // （滚动、气泡长按菜单都会触发）。
    Box(
        Modifier.fillMaxSize()
            // 纯黑换成极暗竖向渐变：更像「通话」质感，又够暗保证亮色前景清晰。
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF0C0E16), Color(0xFF000000))))
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp)
                .graphicsLayer { alpha = appear },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            CallOrb(
                phase = phase, amplitude = amp, dead = micDead,
                modifier = Modifier.size(128.dp).graphicsLayer { val sc = 0.86f + 0.14f * appear; scaleX = sc; scaleY = sc },
            )
            Spacer(Modifier.height(18.dp))
            Text(hint, color = CallFg, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (heard.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                // 回显识别结果：识别错了用户能当场看出来，不用等 AI 答非所问才反应过来
                Text(
                    "「$heard」", color = CallFgDim, fontSize = 13.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            // 流式输出：AI 的回答边生成边显示、自动滚到底——长回答不必等念到哪才知道它说了啥。
            if (reply.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                val rs = rememberScrollState()
                LaunchedEffect(reply) { rs.scrollTo(rs.maxValue) }
                Text(
                    reply, color = CallFg, fontSize = 15.sp, lineHeight = 22.sp,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rs).padding(horizontal = 6.dp),
                )
            }
            if (!canBargeIn && reply.isBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(tr("本机不支持回声消除，AI 说话时无法插话——等它说完再说"), color = CallFgDim, fontSize = 10.sp)
            }
        }
        // 挂断
        Box(
            Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp)
                .graphicsLayer { alpha = appear }
                .size(56.dp).clip(CircleShape).background(scheme.error)
                .clickable { onHangUp() },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(Icons.Outlined.CallEnd, contentDescription = tr("挂断"), tint = scheme.onError, modifier = Modifier.size(26.dp))
        }
    }
}

/**
 * 通话光球：按阶段变色，听的时候随音量脉动。
 * 用 withFrameNanos 自己驱动而非 animate*：系统「动画时长缩放=0」（手表/省电常见）会让 tween
 * 一帧跳完，光球就完全不动了——本项目在唤醒页踩过这个坑。
 */
@Composable
private fun CallOrb(phase: CallPhase, amplitude: Float, dead: Boolean = false, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val target = if (dead) scheme.error else when (phase) {
        CallPhase.LISTENING -> scheme.primary
        CallPhase.TRANSCRIBING -> scheme.tertiary
        CallPhase.THINKING -> scheme.secondary
        CallPhase.SPEAKING -> scheme.primary
    }
    // 帧循环里读到的参数是启动那一刻的快照 → 用 rememberUpdatedState 兜住，才能每帧拿到最新音量/目标色。
    val ampTarget = androidx.compose.runtime.rememberUpdatedState(amplitude)
    val colTarget = androidx.compose.runtime.rememberUpdatedState(target)
    var t by remember { mutableStateOf(0f) }        // 呼吸相位 0..1
    var spin by remember { mutableStateOf(0f) }      // 处理态旋转相位 0..1
    var ampS by remember { mutableStateOf(0f) }      // 低通平滑后的音量（脉动丝滑不抖）
    var col by remember { mutableStateOf(target) }   // 缓动过渡的颜色（换阶段颜色渐变而非硬切）
    // 单一常驻帧循环（只在挂断时停）：一处同时驱动 呼吸/旋转/音量低通/颜色缓动——全帧驱动，
    // 不用 animate*（系统「动画时长缩放=0」会让 tween 一帧跳完、动画就没了，本项目反复踩过）。
    // 通话时用户在场、~60fps 顺滑不算空转浪费（原来 LISTENING 不驱动 t 是怕 idle 空转，通话场景无此顾虑）。
    LaunchedEffect(dead) {
        if (dead) return@LaunchedEffect
        var last = 0L
        while (true) {   // withFrameNanos 是取消点，dispose 时抛 Cancellation 退出
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0f else (now - last) / 1_000_000f
            last = now
            t = (t + dt / 2600f) % 1f
            spin = (spin + dt / 3400f) % 1f
            ampS += (ampTarget.value - ampS) * (dt / 90f).coerceIn(0f, 1f)                 // 音量低通
            col = androidx.compose.ui.graphics.lerp(col, colTarget.value, (dt / 240f).coerceIn(0f, 1f))  // 颜色缓动
        }
    }
    val color = if (dead) target else col
    Canvas(modifier) {
        val base = size.minDimension / 2f * 0.55f
        val breath = sin(t * 2f * Math.PI.toFloat()) * 0.5f + 0.5f       // 0..1
        // 听：随（平滑后的）音量鼓一下；说/想/转写：自己缓慢呼吸
        val pulse = if (dead) 0f else when (phase) {
            CallPhase.LISTENING -> ampS * 0.5f
            else -> breath * 0.16f
        }
        val r = base * (1f + pulse)
        val c = center
        // 外层柔光：径向渐变从半透明晕开到全透明，随呼吸微微起伏（不再是硬边圆叠圆=廉价）。
        val glowR = r * 2f * (1f + breath * 0.06f)
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                0f to color.copy(alpha = 0.42f), 0.5f to color.copy(alpha = 0.2f), 1f to color.copy(alpha = 0f),
                center = c, radius = glowR),
            radius = glowR, center = c)
        // 处理态(转写/思考)：一圈缓慢旋转的弧，给「在忙」的动感（听/说时不画，免得喧宾夺主）。
        if (!dead && (phase == CallPhase.TRANSCRIBING || phase == CallPhase.THINKING)) {
            val ringR = r * 1.4f
            drawArc(
                color = color.copy(alpha = 0.9f),
                startAngle = spin * 360f, sweepAngle = 90f, useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(c.x - ringR, c.y - ringR),
                size = androidx.compose.ui.geometry.Size(ringR * 2f, ringR * 2f),
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = r * 0.06f, cap = androidx.compose.ui.graphics.StrokeCap.Round))
        }
        // 核心：中心亮、向边缘平滑收拢（高光偏上给点体积感，不是死板平圆）。
        drawCircle(
            brush = androidx.compose.ui.graphics.Brush.radialGradient(
                0f to color.copy(alpha = 0.98f), 0.7f to color, 1f to color.copy(alpha = 0.6f),
                center = androidx.compose.ui.geometry.Offset(c.x, c.y - r * 0.25f), radius = r * 1.15f),
            radius = r, center = c)
        // 一点顶部高光，像真实球体反光
        drawCircle(
            color = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.28f),
            radius = r * 0.28f,
            center = androidx.compose.ui.geometry.Offset(c.x - r * 0.28f, c.y - r * 0.38f))
    }
}

/** 走「语音识别」页的专用配置（本地 sherpa 或云端），不串对话模型的 key。 */
private suspend fun transcribe(context: android.content.Context, samples: FloatArray): String? =
    withContext(Dispatchers.IO) {
        try {
            if (SttPrefs.provider(context) == "local") {
                localSttTranscribe(context, samples).first?.trim()
            } else {
                val c = SttPrefs.resolveCloud(context) ?: return@withContext null
                com.arix.cloudapi.WhisperClient(
                    com.arix.cloudapi.CloudApiConfig(c.baseUrl, c.apiKey, c.model)
                ).transcribe(samples, 16000, SttPrefs.lang(context)).text?.trim()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }
