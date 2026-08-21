/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source.
 */

package com.arix.wake

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max

/** [VoiceTurn.recordUtterance] 的结束原因。 */
enum class TurnEnd {
    /** 说完了：VAD 检测到尾静音。正常路径。 */
    SILENCE,

    /** 到了最长时长上限还在说。内容仍然给你，只是被截断了。 */
    MAX_LENGTH,

    /** 全程没听到人声。 */
    NO_SPEECH,

    /** 麦克风打不开（被占用/无权限）。 */
    MIC_UNAVAILABLE,
}

/**
 * 一轮语音的结果。
 *
 * [samples] 是 16kHz 单声道、归一化到 [-1,1] 的 **FloatArray**——直接就是本项目两条 STT
 * （本地 sherpa 与 WhisperClient）要的格式。VAD 内部用的是 PCM16，转换在这里做完，
 * 调用方不用管。[end] 说明为何结束。
 */
data class VoiceTurnResult(val samples: FloatArray?, val end: TurnEnd) {
    // data class 含数组 → equals/hashCode 需手写，否则比的是引用（IDE 也会警告）
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VoiceTurnResult) return false
        return end == other.end && (samples?.contentEquals(other.samples) ?: (other.samples == null))
    }
    override fun hashCode(): Int = 31 * (samples?.contentHashCode() ?: 0) + end.hashCode()
}

/**
 * 语音轮次录音器：**VAD 自动断句**，不用手点停止。
 *
 * 与 [WakeEnrollment] 里那份私有的 recordOne 是同一套断句思路（等到人声出现 → 尾静音超过
 * endSilenceMs 即收音），提取成公开可复用件，供「语音通话」这类连续多轮场景用。
 * app 模块拿不到 internal 的 [SileroVad]，所以由 wake 模块统一对外提供这一层。
 *
 * ## 回声这件事（barge-in 的物理前提）
 * 通话模式要在 AI 朗读时继续开麦（好让用户能插嘴打断）。但喇叭放的正是 AI 自己的声音，
 * 麦克风会把它收回去、被 VAD 判成"用户在说话"→ **AI 自己打断自己**。
 * 解法是交给系统的回声消除：
 *  - 音源用 [MediaRecorder.AudioSource.VOICE_COMMUNICATION]（通话音源，平台通常自带 AEC/AGC/NS）
 *  - 再显式挂 [AcousticEchoCanceler] / [NoiseSuppressor]（部分设备要手动开）
 * 手表不一定实现了这些（isAvailable 可能为 false），所以只能尽力而为——[echoCancel] 打开时
 * 若设备不支持，调用方应把打断灵敏度调低或干脆不开 barge-in，而不是假装有 AEC。
 */
class VoiceTurn(
    context: Context,
    private val sampleRate: Int = 16000,
    private val frameSize: Int = 512,
) {
    private companion object {
        private const val TAG = "VoiceTurn"
        /** VAD 判定为人声的概率阈值。与 wake 模块其余部分保持一致。 */
        private const val SPEECH_PROB = 0.5f

        // L0 能量门：与 WakeAudioPipeline 同一套思路——纯静音帧只更新噪声底，不跑 ONNX VAD。
        private const val MIN_RMS = 0.003f
        private const val NOISE_MARGIN = 0.001f
        private const val NOISE_ALPHA = 0.05f
    }

    private val appContext = context.applicationContext

    /** VAD 会话复用：recordUtterance / awaitSpeech 每轮都新建 OrtSession 的开销在手表上不可忽略。 */
    private var vad: SileroVad? = null

    private fun vadInstance(): SileroVad = vad ?: SileroVad(appContext, sampleRate, frameSize).also { vad = it }

    /** 释放复用的 VAD 会话。VoiceTurn 生命周期结束时由调用方调用（见 VoiceCall 的 DisposableEffect）。 */
    fun release() {
        vad?.close()
        vad = null
    }

    private fun energyGateOpen(r: Float, noiseEma: Float): Boolean =
        r >= maxOf(MIN_RMS, noiseEma + NOISE_MARGIN)

    private fun updateNoise(noiseEma: Float, r: Float): Float =
        if (noiseEma <= 0f) r else (1f - NOISE_ALPHA) * noiseEma + NOISE_ALPHA * r

    /** 设备是否真的支持回声消除。不支持时 barge-in 会误触发，调用方需据此降级。 */
    val echoCancelSupported: Boolean get() = runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false)

    /**
     * 录一轮话，VAD 自动判断说完。
     *
     * @param maxMs 最长录多久（防止一直说下去）
     * @param minSpeechMs 低于这个时长的人声当噪音丢弃
     * @param endSilenceMs 人声后静音多久算说完
     * @param leadingSilenceMs 一直没人说话就放弃（0=不限，直到 maxMs）
     * @param echoCancel 是否开回声消除（AI 在放声音时必须开）
     * @param onAmplitude 实时音量回调（0..1），给 UI 画波形
     */
    @SuppressLint("MissingPermission")
    suspend fun recordUtterance(
        maxMs: Long = 15000L,
        minSpeechMs: Long = 250L,
        endSilenceMs: Long = 700L,
        leadingSilenceMs: Long = 0L,
        echoCancel: Boolean = false,
        onAmplitude: ((Float) -> Unit)? = null,
    ): VoiceTurnResult = withContext(Dispatchers.Default) {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext VoiceTurnResult(null, TurnEnd.MIC_UNAVAILABLE)

        // 通话音源：平台一般会在这条链路上自动挂 AEC/AGC/降噪。放声音时必须用它，否则回声必然自触发。
        val source = if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val recorder = try {
            AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frameSize * 4))
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord 创建失败: ${e.message}")
            return@withContext VoiceTurnResult(null, TurnEnd.MIC_UNAVAILABLE)
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { recorder.release() }
            return@withContext VoiceTurnResult(null, TurnEnd.MIC_UNAVAILABLE)
        }

        // 显式挂效果器：部分设备用了 VOICE_COMMUNICATION 也不自动开
        var aec: AcousticEchoCanceler? = null
        var ns: NoiseSuppressor? = null
        if (echoCancel) {
            aec = runCatching { AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
            ns = runCatching { NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull()
            if (aec == null) Log.w(TAG, "本机没有回声消除；barge-in 可能被 AI 自己的声音误触发")
        }

        val vad = vadInstance().also { it.reset() }
        val buffer = ShortArray(frameSize)
        val speech = ShortBuf(sampleRate * 4)   // 别用 ArrayList<Short>：每个采样点都装箱
        val frameMs = (frameSize * 1000L) / sampleRate

        var seenSpeech = false
        var silenceMs = 0L
        var speechMs = 0L
        var quietMs = 0L
        var noiseEma = 0f
        var end = TurnEnd.MAX_LENGTH
        val startedAt = System.currentTimeMillis()

        try {
            recorder.startRecording()
            while (System.currentTimeMillis() - startedAt < maxMs) {
                coroutineContext.ensureActive()   // 挂断/退出要能立刻停，别让录音线程赖着
                val read = recorder.read(buffer, 0, buffer.size)
                // 负数=麦克风被别的应用抢走(ERROR_DEAD_OBJECT/-3/-6…)。continue 的话会 100% CPU
                // 空转到 maxMs 才罢休，必须直接退出。
                if (read < 0) { end = TurnEnd.MIC_UNAVAILABLE; break }
                if (read < frameSize) continue

                val frameRms = rms(buffer, read)
                onAmplitude?.invoke(frameRms)

                // L0 能量门：没开口前的纯静音/低噪帧不跑 VAD。已检测到 speech 后必须继续跑 VAD，
                // 否则无法判断尾静音、会把句尾整段漏掉。
                if (!seenSpeech) {
                    val gateOpen = energyGateOpen(frameRms, noiseEma)
                    noiseEma = updateNoise(noiseEma, frameRms)
                    if (!gateOpen) {
                        quietMs += frameMs
                        if (leadingSilenceMs > 0 && quietMs >= leadingSilenceMs) { end = TurnEnd.NO_SPEECH; break }
                        continue
                    }
                }

                val isSpeech = vad.probability(buffer, frameSize) >= SPEECH_PROB
                if (isSpeech) {
                    seenSpeech = true
                    silenceMs = 0L
                    speechMs += frameMs
                    speech.addAll(buffer, frameSize)
                } else if (seenSpeech) {
                    // 说话中间的短停顿也要收进去，否则拼出来的音频会一顿一顿的
                    silenceMs += frameMs
                    speech.addAll(buffer, frameSize)
                    if (silenceMs >= endSilenceMs) { end = TurnEnd.SILENCE; break }
                } else {
                    quietMs += frameMs
                    if (leadingSilenceMs > 0 && quietMs >= leadingSilenceMs) { end = TurnEnd.NO_SPEECH; break }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // 挂断/退出走这条。ensureActive() 抛的就是它，且它是 Exception 子类——
                      // 被下面的 catch 吞掉的话会退化成「录音失败」日志，且违反项目「绝不吞 Cancellation」铁律
        } catch (e: Exception) {
            Log.e(TAG, "录音失败: ${e.message}")
        } finally {
            onAmplitude?.invoke(0f)
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            // vad 实例复用，不在这里 close；由 release() 统一释放。
        }

        // 麦克风中途没了要如实上报：不能被下面的 !seenSpeech 覆盖成 NO_SPEECH——
        // 那样调用方会当「没人说话」一直重试，永远开不了口也不知道为什么。
        if (end == TurnEnd.MIC_UNAVAILABLE) return@withContext VoiceTurnResult(null, TurnEnd.MIC_UNAVAILABLE)
        if (!seenSpeech) return@withContext VoiceTurnResult(null, TurnEnd.NO_SPEECH)
        if (speechMs < minSpeechMs) return@withContext VoiceTurnResult(null, TurnEnd.NO_SPEECH)
        // PCM16 → 归一化 float：两条 STT 都吃 FloatArray，转换在这做，调用方不用管
        VoiceTurnResult(FloatArray(speech.size()) { speech.get(it) / 32768f }, end)
    }

    /**
     * 可增长的 ShortArray。用 ArrayList&lt;Short&gt; 的话每个采样点都要装箱——20 秒就是 32 万个
     * Short 对象（只有 -128..127 被缓存）外加反复扩容的 Object[]，手表上每轮几 MB 垃圾。
     */
    private class ShortBuf(initial: Int) {
        private var a = ShortArray(initial)
        private var n = 0
        fun size() = n
        fun get(i: Int) = a[i]
        fun addAll(src: ShortArray, count: Int) {
            if (n + count > a.size) a = a.copyOf(maxOf(a.size * 2, n + count))
            System.arraycopy(src, 0, a, n, count)
            n += count
        }
    }

    /**
     * 只等「用户开口」这一个事件，不录内容——给 barge-in 用：AI 朗读期间跑这个，
     * 一旦检测到人声就返回 true，调用方随即掐掉 TTS 转入聆听。
     *
     * @param minSpeechMs 连续多久的人声才算数。**别调太小**：AI 自己的回声、环境噪音都会
     *   蹭到单帧 VAD，太灵敏会导致 AI 说一半就把自己打断。
     * @return true=检测到人声（该打断了）；false=直到 [maxMs] 或协程取消都没人说话
     */
    @SuppressLint("MissingPermission")
    suspend fun awaitSpeech(
        maxMs: Long = 60000L,
        minSpeechMs: Long = 400L,
        echoCancel: Boolean = true,
    ): Boolean = withContext(Dispatchers.Default) {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext false
        val source = if (echoCancel) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.MIC
        val recorder = try {
            AudioRecord(source, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, frameSize * 4))
        } catch (_: Exception) { return@withContext false }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) { runCatching { recorder.release() }; return@withContext false }

        val aec = if (echoCancel) runCatching { AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val ns = if (echoCancel) runCatching { NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val vad = vadInstance().also { it.reset() }
        val buffer = ShortArray(frameSize)
        val frameMs = (frameSize * 1000L) / sampleRate
        var speechMs = 0L
        var noiseEma = 0f
        var hit = false
        val startedAt = System.currentTimeMillis()

        try {
            recorder.startRecording()
            while (System.currentTimeMillis() - startedAt < maxMs) {
                coroutineContext.ensureActive()
                val read = recorder.read(buffer, 0, buffer.size)
                if (read < 0) break   // 同上：麦克风没了，别空转
                if (read < frameSize) continue
                val frameRms = rms(buffer, read)
                val gateOpen = energyGateOpen(frameRms, noiseEma)
                noiseEma = updateNoise(noiseEma, frameRms)
                if (!gateOpen) { speechMs = 0L; continue }
                if (vad.probability(buffer, frameSize) >= SPEECH_PROB) {
                    speechMs += frameMs
                    if (speechMs >= minSpeechMs) { hit = true; break }
                } else {
                    speechMs = 0L   // 要求连续，零星一两帧不算
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e   // 同上，绝不吞
        } catch (_: Exception) {
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
            runCatching { aec?.release() }
            runCatching { ns?.release() }
            // vad 实例复用，不在这里 close；由 release() 统一释放。
        }
        hit
    }

    /** 帧音量（0..1），给 UI 画波形用。 */
    private fun rms(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i] / 32768.0; sum += v * v }
        return kotlin.math.sqrt(sum / max(1, n)).toFloat().coerceIn(0f, 1f)
    }
}
