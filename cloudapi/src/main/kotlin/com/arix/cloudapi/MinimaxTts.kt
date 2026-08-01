package com.arix.cloudapi

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import kotlin.coroutines.resume

/**
 * Minimax 原生 TTS（T2A v2）。借鉴市场「机能说话了（MiniMax API版本）」(SUNNYFREE0618)、「渡渡语音」(do-do026)。
 * 与 OpenAI 兼容的 CloudTts 不同：Minimax 有自己的 API（要 GroupId + voice_id，情绪/语气参数丰富，音频以 hex 返回）。
 * POST https://api.minimax.chat/v1/t2a_v2?GroupId=<gid>  →  {data:{audio:"<hex mp3>"}} → 解码播放。
 */
object MinimaxTts {

    private val client = HttpClientProvider.stt

    suspend fun speak(
        context: Context,
        groupId: String,
        apiKey: String,
        voiceId: String,
        model: String,
        text: String,
        speed: Float = 1.0f,
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (text.isBlank()) { onLog("Minimax: 文本为空"); return false }
        if (groupId.isBlank() || apiKey.isBlank()) { onLog("Minimax: 缺 GroupId/Key"); return false }
        val audio = try { synth(groupId, apiKey, voiceId, model, text, speed, onLog) } catch (e: Exception) {
            onLog("Minimax 异常: ${e.message}"); null
        } ?: return false
        if (audio.isEmpty()) { onLog("Minimax: 音频为空"); return false }
        onLog("Minimax: 取到音频 ${audio.size / 1024}KB，播放…")
        return playAudio(context.applicationContext, audio)
    }

    private suspend fun synth(groupId: String, apiKey: String, voiceId: String, model: String, text: String, speed: Float, onLog: (String) -> Unit): ByteArray? =
        withContext(Dispatchers.IO) {
            val url = "https://api.minimax.chat/v1/t2a_v2?GroupId=${groupId.trim()}"
            val body = JSONObject().apply {
                put("model", model.ifBlank { "speech-02-hd" })
                put("text", text)
                put("stream", false)
                put("voice_setting", JSONObject().apply {
                    if (voiceId.isNotBlank()) put("voice_id", voiceId)
                    put("speed", speed.toDouble())
                    put("vol", 1.0)
                    put("pitch", 0)
                })
                put("audio_setting", JSONObject().apply {
                    put("sample_rate", 32000)
                    put("format", "mp3")
                    put("channel", 1)
                })
            }.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder().url(url).post(body)
                .header("Authorization", "Bearer ${apiKey.trim()}")
                .header("Content-Type", "application/json").build()

            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string()
                if (!resp.isSuccessful || txt.isNullOrBlank()) { onLog("Minimax: HTTP ${resp.code}"); return@withContext null }
                val json = try { JSONObject(txt) } catch (_: Exception) { onLog("Minimax: 返回非 JSON"); return@withContext null }
                val baseResp = json.optJSONObject("base_resp")
                val code = baseResp?.optInt("status_code", -1) ?: -1
                if (code != 0) { onLog("Minimax: ${baseResp?.optString("status_msg") ?: "错误码 $code"}"); return@withContext null }
                val hex = json.optJSONObject("data")?.optString("audio", "") ?: ""
                if (hex.isBlank()) { onLog("Minimax: data.audio 为空"); return@withContext null }
                try { hexToBytes(hex) } catch (_: Exception) { onLog("Minimax: 音频 hex 解码失败"); null }
            }
        }

    private fun hexToBytes(s: String): ByteArray {
        val clean = if (s.length % 2 == 0) s else s.dropLast(1)
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i < out.size) {
            out[i] = ((Character.digit(clean[i * 2], 16) shl 4) + Character.digit(clean[i * 2 + 1], 16)).toByte()
            i++
        }
        return out
    }

    private suspend fun playAudio(context: Context, bytes: ByteArray): Boolean =
        suspendCancellableCoroutine { cont ->
            val f = File(context.cacheDir, "minimax_tts_${System.currentTimeMillis()}.mp3")
            val mp = MediaPlayer()
            // 通话期间必须播到通话流：AEC 只能消除通话参考流，播在媒体流上它消不掉
            // → AI 的声音会被自己的麦克风收回去、当成用户插话 → 自我打断。
            runCatching { mp.setAudioAttributes(SpeechRoute.attributes()) }
            var resumed = false
            fun finish(ok: Boolean) {
                if (!resumed) { resumed = true; try { mp.release() } catch (_: Exception) {}; f.delete(); cont.resume(ok) }
            }
            try {
                f.writeBytes(bytes)
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener { finish(true) }
                mp.setOnErrorListener { _, _, _ -> finish(false); true }
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
                cont.invokeOnCancellation { finish(false) }
            } catch (_: Exception) { finish(false) }   // setDataSource/prepareAsync 抛错也要释放 mp+删临时文件
        }
}
