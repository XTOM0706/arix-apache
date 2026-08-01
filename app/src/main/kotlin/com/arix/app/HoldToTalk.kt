package com.arix.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

// ============================================================
// 按住说话 —— 录一段 → 转写 → **填进输入框**（不直接发，用户能先改再发）。
//
// 这个功能项目里曾经有、后来被主动删掉（见 ChatScreen 里麦克风那处注释），理由是「语音通话是它的超集」。
// 那个判断只在**说完就想发**时成立。真实场景里另一半需求是「我懒得打字，但发出去之前想看一眼」——
// 识别错一个字就得重发一轮，通话模式给不了这个机会。所以两条路都要有，且**不能互相抢手势**。
//
// ## 手势分配：短按麦克风 = 打开本浮层，长按麦克风 = 语音通话（原样保留）
// 为什么不把「按住说话」直接做在那颗 40dp 的麦克风图标上：
//  · 它已经挂着 combinedClickable 的 onLongClick（长按进通话）。长按在**手指还没抬起**时就触发，
//    而按住说话必须在**按下那一刻**就开录 —— 两者必然打架：每次想进通话都会先偷录一段。
//  · 手表上 40dp 的触点做「上滑取消」实在太小，滑一点点就滑出控件、手势直接断掉。
// 点一下换来一个整屏的大按钮，长按那条路一行没动，是这里唯一不用赌的方案。
//
// 录音本身没有复用 wake 模块的 VoiceTurn：那个是 **VAD 自动断句**（说完自动收音），而按住说话的
// 语义是「手指松开才算说完」，正好是它做不到的那一件事（取消协程会把已录的采样一起丢掉）。
// 转写则完全走既有那条：本地 sherpa [localSttTranscribe] / 云端 WhisperClient，配置读 [SttPrefs]。
// ============================================================

/** 浮层阶段。 */
private enum class PttPhase { READY, RECORDING, TRANSCRIBING, FAILED }

/** 上滑多少算取消。手表屏小，60dp 已经是「明显往上带了一下」。 */
private val CANCEL_DISTANCE = 60.dp

/** 短于这个时长的一律当误触，不去转写（省一次网络/模型调用，也免得回填一句空话）。 */
private const val MIN_RECORD_MS = 400L

/** 兜底上限：手指卡住 / 控件被别的窗口盖掉时不至于一直录下去。 */
private const val MAX_RECORD_MS = 60_000L

/** 光球直径。手表上要一根手指按得住、还能往上带一段距离。 */
private val ORB_SIZE = 140.dp

/**
 * 按住说话浮层。
 *
 * @param onText 转写结果。调用方应当**只填进输入框**，不要顺手发出去——「先改再发」就是这个功能存在的理由。
 * @param onDismiss 关闭浮层。
 */
@Composable
fun HoldToTalkSheet(
    context: android.content.Context,
    onText: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val cancelPx = remember(density) { with(density) { CANCEL_DISTANCE.toPx() } }

    var hasPerm by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    // 没权限**不能静默失败**：按下去什么都不发生是最难查的那种"bug"。这里当场弹系统授权框，
    // 用户拒了就在浮层里说清「没给录音权限」，而不是留一个按了没反应的按钮。
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPerm = granted
    }

    var phase by remember { mutableStateOf(PttPhase.READY) }
    var canceling by remember { mutableStateOf(false) }   // 手指已上滑到取消区（只驱动 UI）
    var amp by remember { mutableStateOf(0f) }            // 实时音量 0..1
    var elapsedMs by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf("") }        // 失败原因（如实说，不吞）

    // 录音停止 / 取消这两个信号走 AtomicBoolean 而不是 Compose state：录音循环跑在 IO 线程上，
    // 用普通状态就得依赖快照跨线程可见性的细节，出问题很难查。原子量在这里既够用又没有歧义。
    val stopFlag = remember { AtomicBoolean(true) }       // true = 没在录
    val cancelFlag = remember { AtomicBoolean(false) }
    var recordJob by remember { mutableStateOf<Job?>(null) }

    // 浮层消失（关闭 / 返回 / 切页）必须把麦克风收掉，否则录音线程留着占麦，
    // 下次通话、唤醒助手都会「麦克风打不开」。
    DisposableEffect(Unit) {
        onDispose { stopFlag.set(true); recordJob?.cancel() }
    }

    val closeNow = rememberUpdatedState(onDismiss)
    val textNow = rememberUpdatedState(onText)

    // 返回键 = 关掉浮层，而不是把整个页面导航走（同语音通话页的处理）。
    androidx.activity.compose.BackHandler { stopFlag.set(true); recordJob?.cancel(); onDismiss() }

    // 计时：帧驱动。系统「动画时长缩放=0」在手表上很常见，凡是必须动起来的一律 withFrameNanos
    // （见 ui/FrameMotion.kt 的说明）。顺带把秒数也推起来，不必再开一个 delay 循环。
    LaunchedEffect(phase) {
        if (phase != PttPhase.RECORDING) return@LaunchedEffect
        var start = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (start == 0L) start = now
            elapsedMs = (now - start) / 1_000_000L
        }
    }

    // 手势回调经 rememberUpdatedState 传进 pointerInput：pointerInput 的 key 必须恒定为 Unit，
    // ⚠ 一旦把 phase/hasPerm 当 key，按下瞬间 phase 变 RECORDING 就会**重启检测器、丢掉这一次手势**，
    // 结果是永远收不到"松手"、录音停不下来。这是本文件最容易写错的一处。
    val startRecording: () -> Unit = {
        if (!hasPerm) {
            permLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else if (phase != PttPhase.RECORDING && phase != PttPhase.TRANSCRIBING) {
            message = ""
            canceling = false
            elapsedMs = 0L
            cancelFlag.set(false)
            stopFlag.set(false)
            phase = PttPhase.RECORDING
            recordJob = scope.launch {
                val samples = try {
                    recordWhileHeld(stopFlag) { amp = it }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    null
                }
                amp = 0f
                val heldMs = elapsedMs
                when {
                    cancelFlag.get() -> phase = PttPhase.READY
                    samples == null -> {
                        phase = PttPhase.FAILED
                        message = tr("麦克风打不开：可能被其它应用占用，或录音权限被系统收回了。")
                    }
                    heldMs < MIN_RECORD_MS || samples.isEmpty() -> {
                        phase = PttPhase.FAILED
                        message = tr("说话时间太短，按住多说一会儿")
                    }
                    else -> {
                        phase = PttPhase.TRANSCRIBING
                        val text = sttTranscribe(context, samples)
                        if (text.isNullOrBlank()) {
                            phase = PttPhase.FAILED
                            message = tr("没听清，或者语音识别还没配好（去 设置 → 语音识别 配一下）")
                        } else {
                            textNow.value(text)
                            closeNow.value()
                        }
                    }
                }
            }
        }
    }
    val onPressStart = rememberUpdatedState(startRecording)

    val onPressEnd = rememberUpdatedState<(Boolean) -> Unit>({ cancel ->
        if (phase == PttPhase.RECORDING) {
            cancelFlag.set(cancel)
            canceling = cancel
            // 只置停止位，**不 cancel 协程**：cancel 会把已经录到的采样一起丢掉，
            // 而录音循环自己会收尾并 release AudioRecord。
            stopFlag.set(true)
        }
    })

    val onDragY = rememberUpdatedState<(Boolean) -> Unit>({ inZone -> canceling = inZone })

    // 压黑整屏 + 吃掉触摸：不拦的话手指会穿到底下的消息列表（滚动 / 气泡长按菜单都会被触发）。
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xF20C0E16), Color(0xF2000000))))
            .pointerInput(Unit) { detectTapGestures { } }
    ) {
        // 右上角关闭
        Box(
            Modifier.align(Alignment.TopEnd).padding(12.dp).size(40.dp).clip(CircleShape)
                .clickable { stopFlag.set(true); recordJob?.cancel(); onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = tr("关闭"), tint = Color(0xFFAAAAAA), modifier = Modifier.size(22.dp))
        }

        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            // 上滑取消的提示：录音时才出现，滑进取消区就变红加粗
            if (phase == PttPhase.RECORDING) {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = null,
                    tint = if (canceling) scheme.error else Color(0xFFAAAAAA),
                    modifier = Modifier.size(if (canceling) 28.dp else 22.dp),
                )
                Text(
                    if (canceling) tr("松开即取消") else tr("上滑取消"),
                    color = if (canceling) scheme.error else Color(0xFFAAAAAA),
                    fontSize = 12.sp,
                    fontWeight = if (canceling) FontWeight.Bold else FontWeight.Normal,
                )
                Spacer(Modifier.height(16.dp))
            }

            MicOrb(
                recording = phase == PttPhase.RECORDING,
                busy = phase == PttPhase.TRANSCRIBING,
                canceling = canceling,
                amplitude = amp,
                color = if (canceling) scheme.error else scheme.primary,
                modifier = Modifier.size(ORB_SIZE).pointerInput(Unit) {
                    // 自己走 awaitEachGesture 而不是 detectDragGestures：需要的是「按下那一刻就开录」+
                    // 「整段过程都读得到位移」+「抬手或被系统抢走都要收音」，三件事组合起来没有现成检测器。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        onPressStart.value()
                        var cancelZone = false
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            val inZone = (ch.position.y - down.position.y) < -cancelPx
                            if (inZone != cancelZone) { cancelZone = inZone; onDragY.value(inZone) }
                            if (!ch.pressed) break
                            ch.consume()
                        }
                        onPressEnd.value(cancelZone)
                    }
                },
            )

            Spacer(Modifier.height(20.dp))

            val title = when {
                !hasPerm -> tr("需要录音权限")
                phase == PttPhase.RECORDING && canceling -> tr("松开即取消")
                phase == PttPhase.RECORDING -> tr("正在听…  松开填进输入框")
                phase == PttPhase.TRANSCRIBING -> tr("识别中…")
                phase == PttPhase.FAILED -> tr("没成功")
                else -> tr("按住说话")
            }
            Text(title, color = Color(0xFFF2F2F2), fontSize = 15.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center)

            if (phase == PttPhase.RECORDING) {
                Spacer(Modifier.height(6.dp))
                Text(fmtDuration(elapsedMs), color = Color(0xFFAAAAAA), fontSize = 13.sp)
            }

            val sub = when {
                !hasPerm -> tr("按一下麦克风授权。没有录音权限，按住说话和语音通话都用不了。")
                phase == PttPhase.FAILED -> message
                phase == PttPhase.READY -> tr("松手不会直接发出去——文字会填进输入框，你可以改完再发。")
                else -> ""
            }
            if (sub.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    sub, color = Color(0xFFAAAAAA), fontSize = 12.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            if (!hasPerm) {
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                    Text(tr("去授权"), color = scheme.primary)
                }
            }
            if (phase == PttPhase.FAILED) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { phase = PttPhase.READY; message = "" }) {
                    Text(tr("再试一次"), color = scheme.primary)
                }
            }
        }
    }
}

/** mm:ss。 */
private fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * 麦克风光球：录音时随音量鼓动，转写时转一圈弧，进取消区变红缩一圈。
 *
 * 整块动画由**一个** withFrameNanos 循环驱动。理由与通话页的光球相同：手表上系统「动画时长缩放=0」
 * 极常见，animate*AsState / tween 会一帧跳到终值 = 完全不动（本项目反复栽过）。
 * 待机态不申请帧（常驻帧回调 = 一直耗电，见 rememberBreath 的说明）。
 */
@Composable
private fun MicOrb(
    recording: Boolean,
    busy: Boolean,
    canceling: Boolean,
    amplitude: Float,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val ampNow = rememberUpdatedState(amplitude)
    val colNow = rememberUpdatedState(color)
    var t by remember { mutableStateOf(0f) }       // 呼吸相位
    var spin by remember { mutableStateOf(0f) }    // 转写时的旋转弧
    var ampS by remember { mutableStateOf(0f) }    // 低通平滑后的音量（不抖）
    var col by remember { mutableStateOf(color) }

    val animating = recording || busy
    LaunchedEffect(animating) {
        if (!animating) { ampS = 0f; col = colNow.value; return@LaunchedEffect }
        var last = 0L
        while (true) {   // withFrameNanos 是取消点，离开组合时抛 Cancellation 自然退出
            val now = withFrameNanos { it }
            val dt = if (last == 0L) 0f else (now - last) / 1_000_000f
            last = now
            t = (t + dt / 2400f) % 1f
            spin = (spin + dt / 1200f) % 1f
            ampS += (ampNow.value - ampS) * (dt / 80f).coerceIn(0f, 1f)
            col = lerp(col, colNow.value, (dt / 200f).coerceIn(0f, 1f))
        }
    }

    val shown = if (animating) col else color
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val base = size.minDimension / 2f * 0.52f
            val breath = sin(t * 2f * Math.PI.toFloat()) * 0.5f + 0.5f
            val pulse = when {
                canceling -> -0.10f
                recording -> ampS * 0.55f
                busy -> breath * 0.12f
                else -> 0f
            }
            val r = base * (1f + pulse)
            val c = center
            // 外圈柔光随音量一起涨：「它确实听到我在说话」这件事必须一眼看得见
            val glowR = r * 2f * (1f + (if (recording) ampS * 0.25f else breath * 0.05f))
            drawCircle(
                brush = Brush.radialGradient(
                    0f to shown.copy(alpha = 0.40f), 0.5f to shown.copy(alpha = 0.18f), 1f to shown.copy(alpha = 0f),
                    center = c, radius = glowR),
                radius = glowR, center = c)
            if (busy) {
                val ringR = r * 1.45f
                drawArc(
                    color = shown.copy(alpha = 0.9f),
                    startAngle = spin * 360f, sweepAngle = 90f, useCenter = false,
                    topLeft = Offset(c.x - ringR, c.y - ringR),
                    size = Size(ringR * 2f, ringR * 2f),
                    style = Stroke(width = r * 0.07f, cap = StrokeCap.Round))
            }
            drawCircle(
                brush = Brush.radialGradient(
                    0f to shown.copy(alpha = 0.98f), 0.7f to shown, 1f to shown.copy(alpha = 0.6f),
                    center = Offset(c.x, c.y - r * 0.25f), radius = r * 1.15f),
                radius = r, center = c)
        }
        // 球心图标（Material 矢量图标，项目铁律：不用 emoji）
        Icon(
            if (canceling) Icons.Outlined.Delete else Icons.Outlined.Mic,
            contentDescription = if (canceling) tr("取消") else tr("按住说话"),
            tint = Color.White,
            modifier = Modifier.size(34.dp),
        )
    }
}

/**
 * 一直录到 [stop] 置位（= 手指松开）为止。16kHz 单声道，输出归一化 FloatArray——
 * 正是本项目两条 STT（本地 sherpa / WhisperClient）都直接吃的格式。
 *
 * 没用 wake 模块的 VoiceTurn.recordUtterance：那个靠 VAD 自动断句，而这里的「说完」定义是松手。
 */
@SuppressLint("MissingPermission")
private suspend fun recordWhileHeld(
    stop: AtomicBoolean,
    onAmplitude: (Float) -> Unit,
): FloatArray? = withContext(Dispatchers.IO) {
    val sampleRate = 16000
    val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    if (minBuf <= 0) return@withContext null
    val recorder = try {
        AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf * 2, 4096))
    } catch (_: Exception) {
        return@withContext null   // 含 SecurityException：权限在录音前一刻被撤销
    }
    if (recorder.state != AudioRecord.STATE_INITIALIZED) { runCatching { recorder.release() }; return@withContext null }

    // 别用 ArrayList<Float>：每个采样点都要装箱，60 秒接近百万个对象。手动扩容的数组便宜得多
    // （wake 模块的 ShortBuf 是同一个理由，那个是 internal，app 模块够不到）。
    var buf = FloatArray(sampleRate * 4)
    var n = 0
    val pcm = ShortArray(1024)
    val startedAt = System.currentTimeMillis()
    try {
        recorder.startRecording()
        while (!stop.get() && System.currentTimeMillis() - startedAt < MAX_RECORD_MS) {
            coroutineContext.ensureActive()   // 浮层被销毁要能立刻停，别让录音线程赖着占麦
            val read = recorder.read(pcm, 0, pcm.size)
            // 负数 = 麦克风被别的应用抢走(ERROR_DEAD_OBJECT 等)。continue 会 100% CPU 空转到超时，必须退出。
            if (read < 0) break
            if (read == 0) continue
            if (n + read > buf.size) buf = buf.copyOf(max(buf.size * 2, n + read))
            var sum = 0.0
            for (i in 0 until read) {
                val v = pcm[i] / 32768f
                buf[n + i] = v
                sum += v.toDouble() * v
            }
            n += read
            // RMS 放大再截断：正常说话的 RMS 只有 0.05~0.2，直接当 0..1 用的话球几乎不动、看着像没在录。
            onAmplitude((sqrt(sum / read).toFloat() * 4f).coerceIn(0f, 1f))
        }
    } catch (c: CancellationException) {
        throw c   // 项目铁律：Cancellation 必须重抛。ensureActive() 抛的就是它，而它是 Exception 的子类
    } catch (_: Exception) {
        // 录音中途异常：已经录到的部分照样交出去，比整段丢掉强
    } finally {
        onAmplitude(0f)
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
    }
    if (n == 0) null else buf.copyOf(n)
}

/**
 * 转写。走「语音识别」页的专用配置（本地 sherpa 或云端），**不串对话模型的 key**——
 * 与语音通话那条路读的是同一份 [SttPrefs]，用户配一次两处都能用。
 */
private suspend fun sttTranscribe(context: android.content.Context, samples: FloatArray): String? =
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
