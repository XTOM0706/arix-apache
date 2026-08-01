package com.arix.stt

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File

/**
 * 离线神经 TTS（sherpa-onnx OfflineTts 包装）：无 key、离线，音质优于系统 TTS。
 * 从模型目录自适应构建配置；构建失败 [create] 返回 null，调用方退回系统 TTS。
 */
class NeuralTts private constructor(private val tts: OfflineTts) {

    @Volatile
    private var track: AudioTrack? = null

    /** 合成并播放（阻塞到大致播完）。speed=语速倍率。 */
    fun speak(text: String, speed: Float = 1.0f) {
        if (text.isBlank()) return
        // 复位放在**合成之前**：generate() 在手表上要好几秒，barge-in 多半发生在这段时间里。
        // 若像原来那样在 play() 里复位，stop() 置的 true 会被抹掉 → 整句照样播完 →
        // 主循环已进入下一轮聆听(无 AEC) → 录到 AI 自己的声音 → 转写 → 把 AI 的话当用户输入发回去，自我循环。
        stopped = false
        synthesizing = true
        val audio = try { tts.generate(text, 0, speed.coerceIn(0.5f, 2.0f)) } finally { synthesizing = false }
        if (stopped) return   // 合成期间被叫停了，别再播
        play(audio.samples, audio.sampleRate)
    }

    private fun play(samples: FloatArray, sampleRate: Int) {
        if (samples.isEmpty()) return
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val t = AudioTrack(
            // 通话期间走通话流（USAGE_VOICE_COMMUNICATION），否则平台 AEC 拿不到参考信号、
            // 消不掉 AI 自己的声音 → 麦克风收回去当成用户插话 → AI 自我打断。普通朗读仍走媒体流。
            com.arix.cloudapi.SpeechRoute.attributes(),
            AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .build(),
            maxOf(minBuf, samples.size * 4),
            AudioTrack.MODE_STREAM,
            AudioManager.AUDIO_SESSION_ID_GENERATE
        )
        track = t
        try {
            t.play()
            t.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
            // 等到大致播完（write 只保证写入缓冲，不保证播完）。
            // 分片睡：整段 sleep 的话 stop() 之后这里还要傻等到原时长走完——语音通话里
            // 用户插嘴打断后，下一轮聆听会被这个没醒的线程拖住。
            val totalMs = (samples.size * 1000L / sampleRate) + 250
            var slept = 0L
            while (slept < totalMs && !stopped) {
                val step = minOf(50L, totalMs - slept)
                Thread.sleep(step)
                slept += step
            }
        } finally {
            try { t.stop() } catch (_: Exception) {}
            try { t.release() } catch (_: Exception) {}
            track = null
        }
    }

    @Volatile
    private var stopped = false

    /** generate() 正在跑（阻塞 JNI）。release() 必须等它完，否则是原生 use-after-free。 */
    @Volatile
    private var synthesizing = false

    /** 立刻停掉当前播放（barge-in / 挂断）。play() 里的等待也会随之提前结束。 */
    fun stop() {
        stopped = true
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
    }

    fun release() {
        stop()
        // generate() 是**阻塞 JNI 调用**，协程取消打断不了它。此时 release 掉原生句柄
        // → sherpa 在自己的线程里继续用已释放的指针 → SIGSEGV。等它自己结束（有上限，
        // 不能无限等；超时就只能放弃释放，宁可泄漏也别崩）。
        val deadline = System.currentTimeMillis() + 3000
        while (synthesizing && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(20) } catch (_: InterruptedException) { break }
        }
        if (synthesizing) return   // 还在合成：不释放，泄漏一个句柄好过崩掉整个 App
        try { tts.release() } catch (_: Exception) {}
    }

    companion object {
        /** 从模型目录构建；缺文件或初始化失败返回 null（调用方退回其它引擎）。onLog 吐诊断。 */
        fun create(modelDir: File, onLog: (String) -> Unit = {}): NeuralTts? {
            return try {
                val files = modelDir.listFiles()?.joinToString(", ") { it.name + (if (it.isDirectory) "/" else "") } ?: "(空)"
                onLog("神经模型目录: ${modelDir.absolutePath}")
                onLog("目录内文件: $files")

                val model = File(modelDir, "model.onnx")
                val tokens = File(modelDir, "tokens.txt")
                if (!model.exists() || !tokens.exists()) { onLog("缺 model.onnx 或 tokens.txt"); return null }

                val lexicon = File(modelDir, "lexicon.txt")
                val dict = File(modelDir, "dict")
                fun ruleOf(name: String) = File(modelDir, name).let { if (it.exists()) it.absolutePath else "" }
                val ruleFsts = listOf("date.fst", "phone.fst", "number.fst")
                    .map { ruleOf(it) }.filter { it.isNotBlank() }.joinToString(",")
                onLog("lexicon=${lexicon.exists()} dict=${dict.isDirectory} rules=${if (ruleFsts.isBlank()) "无" else "有"}")

                // 关键：sherpa-onnx-jni 依赖 libonnxruntime.so(由 onnxruntime-android 提供)。
                // 先显式加载它，否则 jni 找不到 OrtGetApiBase → dlopen 失败。
                try { System.loadLibrary("onnxruntime"); onLog("已预载 onnxruntime") }
                catch (t: Throwable) { onLog("预载 onnxruntime 失败: ${t.message}") }

                val vits = OfflineTtsVitsModelConfig.builder()
                    .setModel(model.absolutePath)
                    .setTokens(tokens.absolutePath)
                    .setLexicon(if (lexicon.exists()) lexicon.absolutePath else "")
                    .setDictDir(if (dict.isDirectory) dict.absolutePath else "")
                    .build()
                val modelConfig = OfflineTtsModelConfig.builder()
                    .setVits(vits).setNumThreads(2).setDebug(false).build()
                val config = OfflineTtsConfig.builder()
                    .setModel(modelConfig).setRuleFsts(ruleFsts).build()

                NeuralTts(OfflineTts(config))
            } catch (t: Throwable) {
                onLog("神经初始化失败: ${t.message}")
                null
            }
        }
    }
}
