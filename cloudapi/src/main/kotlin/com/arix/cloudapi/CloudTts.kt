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
 * OpenAI 兼容云 TTS：POST <baseUrl>/audio/speech {model,input,voice,response_format:mp3} → 音频 → 播放。
 * 复用用户已配的 provider(baseUrl/key)，国内可挑能通的服务(如硅基流动 CosyVoice)，免额外 key。
 */
object CloudTts {

    private val client = HttpClientProvider.stt // 复用较长超时的共享客户端(音频上传/下载)

    suspend fun speak(
        context: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        text: String,
        voice: String = "",
        speed: Float = 1.0f,
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (text.isBlank() || baseUrl.isBlank()) { onLog("云端: baseUrl/文本为空"); return false }
        val audio = try { synth(baseUrl, apiKey, model, text, voice, speed, onLog) } catch (e: Exception) {
            onLog("云端异常: ${e.message}"); null
        } ?: return false
        if (audio.isEmpty()) { onLog("云端: 音频为空"); return false }
        onLog("云端: 取到音频 ${audio.size / 1024}KB，播放…")
        val ok = playAudio(context.applicationContext, audio)
        if (!ok) onLog("云端: 播放失败(MediaPlayer)")
        return ok
    }

    private suspend fun synth(baseUrl: String, apiKey: String, model: String, text: String, voice: String, speed: Float, onLog: (String) -> Unit): ByteArray? =
        withContext(Dispatchers.IO) {
            val url = endpoint(baseUrl)
            onLog("云端: POST $url (model=$model${if (voice.isNotBlank()) ", voice=$voice" else ""})")
            val payload = JSONObject().apply {
                put("model", model)
                put("input", text)
                if (voice.isNotBlank()) put("voice", voice)
                put("response_format", "mp3")
                if (speed != 1.0f) put("speed", speed)
            }.toString().toRequestBody("application/json".toMediaType())

            val req = Request.Builder().url(url).post(payload).apply {
                if (apiKey.isNotBlank()) header("Authorization", "Bearer $apiKey")
            }.build()

            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val err = try { resp.body?.string()?.take(200) } catch (_: Exception) { null }
                    onLog("云端: HTTP ${resp.code}${if (!err.isNullOrBlank()) " — $err" else ""}")
                    return@withContext null
                }
                resp.body?.bytes()
            }
        }

    /** 拼 /audio/speech（认 /v1、/v4、/openai、/compatible-mode/v1；末尾 # 强制原样）。 */
    private fun endpoint(baseUrl: String): String {
        val b = baseUrl.trim()
        if (b.endsWith("#")) return b.trimEnd('#')
        val t = b.trimEnd('/')
        return when {
            t.endsWith("/audio/speech") -> t
            t.endsWith("/v1") || t.endsWith("/v4") || t.endsWith("/openai") || t.endsWith("/compatible-mode/v1") -> "$t/audio/speech"
            else -> "$t/v1/audio/speech"
        }
    }

    private suspend fun playAudio(context: Context, bytes: ByteArray): Boolean =
        suspendCancellableCoroutine { cont ->
            val f = File(context.cacheDir, "cloud_tts_${System.currentTimeMillis()}.mp3")
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
