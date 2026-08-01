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
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive

/**
 * 窗口内检测管线（[WakeWorker]）：clean-room 重写现有捕获循环。见 DESIGN-WAKE.md §3.2。
 *
 * 级联：
 *  - L0 能量门（RMS + 过零率，无 NN）挡掉安静帧；大缓冲读取让 CPU 批处理后睡；
 *  - L1 [SileroVad] 二次确认（仅在 L0 放行的帧上推理）；
 *  - L2 [WakeDetector]（判决器由外部注入：P3=DTW 桥接，P4=microWakeWord KWS）。
 *
 * 语音帧喂 [WakeDetector.accept]（流式判决器在此出分）；段结束调 [WakeDetector.onSegmentEnd]
 * （段式判决器在此出分）。命中即回调 onWake 并结束（由 [WakeController] 决定后续）。
 */
internal class WakeAudioPipeline(
    context: Context,
    private val config: WakeConfig,
    private val detectorFactory: () -> WakeDetector,
) : WakeWorker {

    private companion object {
        private const val TAG = "WakeAudioPipeline"

        // 分段/VAD 调参（工程初值，P7 目标表实测调优）
        private const val VAD_PROB_THRESHOLD = 0.5f
        private const val SPEECH_START_FRAMES = 2
        private const val END_SILENCE_MS = 350L
        private const val MIN_SEGMENT_MS = 250L
        private const val MAX_SEGMENT_MS = 1600L
        private const val NOISE_EMA_ALPHA = 0.05f
        private const val RMS_NOISE_MARGIN = 0.001f
    }

    private val appContext = context.applicationContext

    @Volatile
    private var running = false

    @SuppressLint("MissingPermission")
    override suspend fun run(onWake: (WakeDetection) -> Unit, onError: (String) -> Unit) {
        if (running) return
        running = true

        val sampleRate = config.sampleRate
        val frameSize = if (sampleRate == 8000) 256 else 512
        val frameMs = (frameSize * 1000L) / sampleRate

        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            onError("AudioRecord.getMinBufferSize 失败: sampleRate=$sampleRate result=$minBuf")
            running = false
            return
        }

        // 大缓冲：一次 read 覆盖 ~captureBufferMs，减少唤醒次数
        val framesPerRead = max(1, ((config.captureBufferMs * sampleRate / 1000L).toInt()) / frameSize)
        val readBuf = ShortArray(frameSize * framesPerRead)

        // 音频源分层门控：
        //  · 仅当【真·系统/特权应用】(FLAG_SYSTEM) 且已授 CAPTURE_AUDIO_HOTWORD 时用 HOTWORD 源(1999)——
        //    低归属、后台稳、系统层不掐，是它的合法用途（内嵌进 OnyxUI 当系统应用的目标形态）。
        //  · 否则(普通侧载 / 官改包 root 假授)一律常规 MIC：诚实归属，避免被小米「敏感权限检测」判可疑回收。
        //  · HOTWORD 源若设备 HAL 不支持 → 自动回退 MIC。见记忆 arix-wake-redesign。
        val privileged = (appContext.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        val hasHotword = try {
            appContext.checkSelfPermission("android.permission.CAPTURE_AUDIO_HOTWORD") == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) { false }
        val bufBytes = max(minBuf, readBuf.size * 2)
        fun newRecord(src: Int) = AudioRecord(src, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufBytes)

        var micSource = if (privileged && hasHotword) 1999 /* MediaRecorder.AudioSource.HOTWORD(hidden) */ else MediaRecorder.AudioSource.MIC
        var audioRecord = newRecord(micSource)
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED && micSource == 1999) {
            audioRecord.release()
            WakeLog.d("HOTWORD 源不可用(HAL 不支持)，回退常规 MIC")
            micSource = MediaRecorder.AudioSource.MIC
            audioRecord = newRecord(micSource)
        }
        WakeLog.d("音频源: ${if (micSource == 1999) "HOTWORD(系统/特权)" else "MIC(常规)"}")
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            onError("AudioRecord 未初始化: sampleRate=$sampleRate")
            running = false
            return
        }

        val vad = if (config.vadEnabled) SileroVad(appContext, sampleRate, frameSize) else null
        val detector = detectorFactory()
        detector.reset()

        val frame = ShortArray(frameSize)

        var speechActive = false
        var speechStartCount = 0
        var speechMs = 0L
        var silenceMs = 0L
        var noiseEma = 0f
        var detection: WakeDetection? = null

        // 静音探针：真实麦克风底噪永远不会是「精确全零」，连续全零 = 被系统静音（MIUI 后台音频策略
        // 静音时 read() 照常返回但样本全零、无任何报错）。让不可见的失败在日志里可见。
        var zeroStreakMs = 0L
        var muteLogged = false

        try {
            audioRecord.startRecording()
            Log.d(TAG, "pipeline started sampleRate=$sampleRate frameSize=$frameSize framesPerRead=$framesPerRead")
            WakeLog.d("开麦，监听中… (VAD=${if (config.vadEnabled) "开" else "关"}, 录音态=${audioRecord.recordingState})")

            loop@ while (running) {
                if (!currentCoroutineContext().isActive) break
                val read = audioRecord.read(readBuf, 0, readBuf.size)
                if (read <= 0) continue

                // 静音探针（在整块 read 粒度上判断，代价可忽略）
                var allZero = true
                for (i in 0 until read) { if (readBuf[i] != 0.toShort()) { allZero = false; break } }
                if (allZero) {
                    zeroStreakMs += read * 1000L / sampleRate
                    if (!muteLogged && zeroStreakMs >= 3000L) {
                        muteLogged = true
                        WakeLog.d("⚠ 音频流疑似被系统静音：连续 ${zeroStreakMs / 1000}s 全零样本（read 正常返回、无报错——MIUI 后台静音的典型签名）")
                    }
                } else {
                    if (muteLogged) WakeLog.d("✓ 音频流恢复（收到非零样本，静音解除）")
                    zeroStreakMs = 0L; muteLogged = false
                }

                var off = 0
                while (off + frameSize <= read) {
                    System.arraycopy(readBuf, off, frame, 0, frameSize)
                    off += frameSize

                    val rms = computeRms(frame, frameSize)
                    val zcr = computeZcr(frame, frameSize)

                    // 噪声底 EMA（仅静默期估计）
                    if (!speechActive) {
                        noiseEma = if (noiseEma <= 0f) rms
                        else (1f - NOISE_EMA_ALPHA) * noiseEma + NOISE_EMA_ALPHA * rms
                    }
                    val rmsGate = max(config.minRms, noiseEma + RMS_NOISE_MARGIN)

                    // L0：能量门（+ 过零率排除高频噪声）。静默期外挡掉安静/噪声帧。
                    val energetic = rms >= rmsGate
                    val voiceLike = zcr <= config.zeroCrossRateMax
                    val l0Pass = speechActive || (energetic && voiceLike)

                    // L1：VAD 二次确认（仅在 L0 放行时推理）
                    val isSpeech = when {
                        !l0Pass -> false
                        vad == null -> energetic
                        else -> vad.probability(frame, frameSize) >= VAD_PROB_THRESHOLD
                    }

                    if (isSpeech) {
                        speechStartCount++
                        if (!speechActive && speechStartCount >= SPEECH_START_FRAMES) {
                            speechActive = true
                            WakeLog.d("检测到语音，采集中…")
                        }
                        if (speechActive) {
                            silenceMs = 0L
                            speechMs += frameMs
                            detector.accept(frame, frameSize)?.let { detection = it }
                            if (detection != null) break@loop
                            if (speechMs >= MAX_SEGMENT_MS) {
                                detection = endSegment(detector, speechMs)
                                if (detection != null) break@loop
                                // 到达最大段长仍未命中：重置继续下一段
                                speechActive = false; speechStartCount = 0; speechMs = 0L; silenceMs = 0L
                            }
                        }
                    } else {
                        speechStartCount = 0
                        if (speechActive) {
                            silenceMs += frameMs
                            if (silenceMs >= END_SILENCE_MS) {
                                detection = endSegment(detector, speechMs)
                                if (detection != null) break@loop
                                speechActive = false; speechMs = 0L; silenceMs = 0L
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "pipeline error: ${e.message}")
            onError(e.message ?: "wake pipeline error")
        } finally {
            try { audioRecord.stop() } catch (_: Exception) {}
            try { audioRecord.release() } catch (_: Exception) {}
            try { vad?.close() } catch (_: Exception) {}
            try { (detector as? AutoCloseable)?.close() } catch (_: Exception) {}
            running = false
        }

        detection?.let { onWake(it) }
    }

    override fun stop() {
        running = false
    }

    /** 段结束：满足最短时长才交段式判决器评估。 */
    private fun endSegment(detector: WakeDetector, speechMs: Long): WakeDetection? {
        if (speechMs < MIN_SEGMENT_MS) {
            WakeLog.d("语音段 ${speechMs}ms 太短(<${MIN_SEGMENT_MS}ms)，忽略")
            detector.reset()
            return null
        }
        WakeLog.d("语音段结束 ${speechMs}ms，判决中…")
        val result = detector.onSegmentEnd()
        detector.reset()
        return result
    }

    private fun computeRms(pcm: ShortArray, len: Int): Float {
        if (len <= 0) return 0f
        var sum = 0.0
        val n = min(len, pcm.size)
        for (i in 0 until n) {
            val v = pcm[i].toDouble() / 32768.0
            sum += v * v
        }
        return sqrt(sum / n).toFloat()
    }

    /** 过零率：符号变化次数 / 样本数。高值 = 高频/噪声。 */
    private fun computeZcr(pcm: ShortArray, len: Int): Float {
        val n = min(len, pcm.size)
        if (n < 2) return 0f
        var crossings = 0
        for (i in 1 until n) {
            if ((pcm[i] >= 0) != (pcm[i - 1] >= 0)) crossings++
        }
        return crossings.toFloat() / (n - 1).toFloat()
    }
}
