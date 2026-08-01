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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * 自定义唤醒词录入（clean-room）：录 N 段用户语音 → clean-room [MfccFrontend] 取特征 →
 * 融合成特征原型 → 存进 [WakeTemplateStore]（可存多条、各自命名/启停），供 [EmbeddingPrototypeDetector] 比对。
 * 见 DESIGN-WAKE.md §5/§6。用 clean-room [SileroVad] 做录音端点检测。
 *
 * 注意「一次录入 = 一条模板」：enroll(3) 是让用户把**同一个**唤醒词说 3 遍，融合成 1 条更稳的原型
 * （平均能压掉单次的偶然噪声），不是 3 条模板。要多条就多次 enroll，各起各的名。
 */
class WakeEnrollment(context: Context) {

    private companion object {
        private const val TAG = "WakeEnrollment"
        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 512
    }

    private val appContext = context.applicationContext
    val templates = WakeTemplateStore(appContext)

    init {
        // 老用户的单原型收编成一条模板，不用重录
        try { templates.migrateLegacy() } catch (_: Exception) {}
    }

    /** 至少有一条启用中的模板 = 可以开唤醒了。 */
    fun hasPrototype(): Boolean = templates.hasAnyEnabled()

    fun clearPrototype() = templates.clearAll()

    /**
     * 录 [count] 次、融合成一条原型，以 [name] 存为新模板。**返回新模板 id**，失败返回 null。
     * 同一个唤醒词说 count 遍，别说不同的词——它们会被平均成一条。
     * 返回 id 是为了让 UI 录完能直接弹命名框改这条（先存后改名，用户中途取消也不丢录音）。
     */
    suspend fun enroll(count: Int = 3, name: String = "", onProgress: ((String) -> Unit)? = null): String? {
        val feats = ArrayList<FloatArray>()
        for (i in 1..count) {
            onProgress?.invoke("请说第 $i 次唤醒词")
            val pcm = recordOne()
            if (pcm != null) {
                val f = MfccFrontend.extract(pcm, pcm.size)
                if (f.isNotEmpty() && f.size % MfccFrontend.featureDim == 0) {
                    feats.add(f)
                    onProgress?.invoke("第 $i 次完成")
                } else {
                    onProgress?.invoke("第 $i 次失败：特征无效")
                }
            } else {
                onProgress?.invoke("第 $i 次失败：未检测到有效语音")
            }
        }
        val proto = fuse(feats) ?: return null
        return withContext(Dispatchers.IO) { templates.add(proto, name) }
    }

    /** 取帧数最多数的一组求均值作为原型（DTW 容忍长度差异，此举稳定原型）。 */
    private fun fuse(list: List<FloatArray>): FloatArray? {
        if (list.isEmpty()) return null
        val bySize = list.groupBy { it.size }
        val best = bySize.entries.maxWithOrNull(compareBy({ it.value.size }, { it.key })) ?: return null
        val group = best.value
        val dim = best.key
        val mean = FloatArray(dim)
        for (f in group) for (i in 0 until dim) mean[i] += f[i]
        for (i in 0 until dim) mean[i] /= group.size
        Log.d(TAG, "fused prototype from ${group.size}/${list.size} recordings, dim=$dim")
        return mean
    }

    @SuppressLint("MissingPermission")
    private suspend fun recordOne(
        maxRecordMs: Long = 6000L,
        minSpeechMs: Long = 250L,
        endSilenceMs: Long = 350L,
    ): ShortArray? = withContext(Dispatchers.Default) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return@withContext null

        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            max(minBuf, FRAME_SIZE * 4),
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            return@withContext null
        }

        val vad = SileroVad(appContext, SAMPLE_RATE, FRAME_SIZE)
        val buffer = ShortArray(FRAME_SIZE)
        val speech = ArrayList<Short>()
        val frameMs = (FRAME_SIZE * 1000L) / SAMPLE_RATE

        var seenSpeech = false
        var silenceMs = 0L
        var speechMs = 0L
        val startedAt = System.currentTimeMillis()

        try {
            recorder.startRecording()
            while (System.currentTimeMillis() - startedAt < maxRecordMs) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read < FRAME_SIZE) continue
                val isSpeech = vad.probability(buffer, FRAME_SIZE) >= 0.5f
                if (isSpeech) {
                    seenSpeech = true
                    silenceMs = 0L
                    speechMs += frameMs
                    for (i in 0 until FRAME_SIZE) speech.add(buffer[i])
                } else if (seenSpeech) {
                    silenceMs += frameMs
                    if (silenceMs >= endSilenceMs) break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "record failed: ${e.message}")
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            try { recorder.release() } catch (_: Exception) {}
            try { vad.close() } catch (_: Exception) {}
        }

        if (!seenSpeech || speechMs < minSpeechMs) return@withContext null
        ShortArray(speech.size) { speech[it] }
    }
}
