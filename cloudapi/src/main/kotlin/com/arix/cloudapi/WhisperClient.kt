package com.arix.cloudapi

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class WhisperClient(private val config: CloudApiConfig) {

    // 复用全局共享 OkHttpClient（语音上传专用超时），不再每次构造新建。
    private val client = HttpClientProvider.stt

    data class TranscriptionResult(
        val text: String,
        val error: String?,
        val httpCode: Int,
        val responseTimeMs: Long
    )

    suspend fun transcribe(
        samples: FloatArray,
        sampleRate: Int,
        language: String = "zh"
    ): TranscriptionResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()

        try {
            val wavBytes = floatArrayToWav(samples, sampleRate)

            val fileBody = wavBytes.toRequestBody("audio/wav".toMediaType())
            val multipart = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", "recording.wav", fileBody)
                .addFormDataPart("model", config.model.ifBlank { "whisper-1" })
                .addFormDataPart("language", language)
                .addFormDataPart("response_format", "json")
                .build()

            // 灵活拼端点：避免 baseUrl 已带 /v1 时出现双 /v1（如旧 Groq 配置 .../openai/v1）。
            val b = config.baseUrl.trimEnd('/')
            val url = when {
                b.endsWith("/audio/transcriptions") -> b
                b.endsWith("/v1") -> "$b/audio/transcriptions"
                else -> "$b/v1/audio/transcriptions"
            }
            val request = Request.Builder()
                .url(url)
                .post(multipart)
                .header("Authorization", "Bearer ${config.apiKey}")
                .build()

            client.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - startMs
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    TranscriptionResult(
                        text = "",
                        error = "HTTP ${response.code}: $errBody",
                        httpCode = response.code,
                        responseTimeMs = elapsed
                    )
                } else {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    TranscriptionResult(
                        text = json.optString("text", ""),
                        error = null,
                        httpCode = response.code,
                        responseTimeMs = elapsed
                    )
                }
            }
        } catch (e: Exception) {
            TranscriptionResult(
                text = "",
                error = e.message ?: "Unknown error",
                httpCode = -1,
                responseTimeMs = System.currentTimeMillis() - startMs
            )
        }
    }

    companion object {
        fun floatArrayToWav(samples: FloatArray, sampleRate: Int): ByteArray {
            val numChannels = 1
            val bitsPerSample = 16
            val byteRate = sampleRate * numChannels * bitsPerSample / 8
            val blockAlign = numChannels * bitsPerSample / 8
            val dataSize = samples.size * 2

            val bos = ByteArrayOutputStream(44 + dataSize)

            fun writeInt(value: Int, bytes: Int) {
                for (i in 0 until bytes) bos.write((value shr (i * 8)) and 0xFF)
            }
            fun writeShort(value: Int) = writeInt(value, 2)

            // RIFF header
            bos.write("RIFF".toByteArray())
            writeInt(36 + dataSize, 4)
            bos.write("WAVE".toByteArray())

            // fmt chunk
            bos.write("fmt ".toByteArray())
            writeInt(16, 4)           // chunk size
            writeShort(1)             // PCM
            writeShort(numChannels)
            writeInt(sampleRate, 4)
            writeInt(byteRate, 4)
            writeShort(blockAlign)
            writeShort(bitsPerSample)

            // data chunk
            bos.write("data".toByteArray())
            writeInt(dataSize, 4)
            for (sample in samples) {
                val pcm = (sample * 32767).toInt().coerceIn(-32768, 32767)
                writeShort(pcm)
            }

            return bos.toByteArray()
        }
    }
}
