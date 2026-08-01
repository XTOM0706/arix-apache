package com.arix.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 语义向量客户端：调 OpenAI 兼容的 /embeddings 端点，把文本转成向量，供语义记忆检索用。
 * 轻量 HttpURLConnection、无额外依赖。失败返回 null（调用方回退关键词检索）。
 */
object EmbeddingClient {

    /** 端点补全，与 CloudApiClient 的 /chat/completions 规则同源。 */
    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/embeddings") -> base
            Regex("/(v\\d+[a-z]*|openai)$").containsMatchIn(base) -> "$base/embeddings"
            else -> "$base/v1/embeddings"
        }
    }

    /** 取单条文本的向量。 */
    suspend fun embed(baseUrl: String, apiKey: String, model: String, text: String): FloatArray? =
        embedBatch(baseUrl, apiKey, model, listOf(text)).firstOrNull()

    /** 批量取向量（顺序与输入一致）；整体失败返回空。 */
    suspend fun embedBatch(baseUrl: String, apiKey: String, model: String, texts: List<String>): List<FloatArray> = withContext(Dispatchers.IO) {
        if (texts.isEmpty() || baseUrl.isBlank() || model.isBlank()) return@withContext emptyList()
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint(baseUrl)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 4000; readTimeout = 6000   // 查询在发送路径上，端点慢/挂要快速失败回退关键词(配熔断)
                setRequestProperty("Content-Type", "application/json")
                if (apiKey.isNotBlank()) setRequestProperty("Authorization", "Bearer ${apiKey.trim()}")
                doOutput = true
            }
            val body = JSONObject().apply {
                put("model", model.trim())
                put("input", JSONArray().apply { texts.forEach { put(it.take(4000)) } })
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: return@withContext emptyList()
            if (code !in 200..299) return@withContext emptyList()
            val data = JSONObject(resp).optJSONArray("data") ?: return@withContext emptyList()
            (0 until data.length()).map { i ->
                val arr = data.getJSONObject(i).getJSONArray("embedding")
                FloatArray(arr.length()) { j -> arr.getDouble(j).toFloat() }
            }
        } catch (_: Exception) { emptyList() } finally { conn?.disconnect() }
    }

    // ---- 向量工具 ----

    fun serialize(v: FloatArray): String = JSONArray().apply { v.forEach { put(it.toDouble()) } }.toString()

    fun deserialize(s: String?): FloatArray? = try {
        if (s.isNullOrBlank()) null else {
            val a = JSONArray(s); FloatArray(a.length()) { a.getDouble(it).toFloat() }
        }
    } catch (_: Exception) { null }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return -1f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val denom = Math.sqrt((na * nb).toDouble()).toFloat()
        return if (denom == 0f) -1f else dot / denom
    }
}
