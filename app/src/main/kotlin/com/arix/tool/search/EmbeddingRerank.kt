package com.arix.tool.search

import android.content.Context
import com.arix.app.SearchApiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.sqrt

// ============================================================
// EmbeddingRerank —— 结果相似度重排（Perplexica baseSearch 思路）
// 对 query 与每条结果(标题+摘要)算 embedding cosine：砍离题(<minSim)、去近重复(>dupSim)、按分取 topK。
// 用可配的 OpenAI 兼容 /embeddings 端点，不增包体；未配置则原样返回。
// ============================================================
object EmbeddingRerank {

    suspend fun rerank(
        context: Context, query: String, results: List<SearchResult>,
        topK: Int = 20, minSim: Float = 0.35f, dupSim: Float = 0.9f
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        if (!SearchApiPrefs.embedEnabled(context) || results.size < 2) return@withContext results
        val base = SearchApiPrefs.embedBaseUrl(context)
        val model = SearchApiPrefs.embedModel(context)
        if (base.isBlank() || model.isBlank()) return@withContext results

        val texts = ArrayList<String>(results.size + 1)
        texts.add(query)
        results.forEach { texts.add((it.title + " " + it.snippet).take(400)) }
        val vecs = embed(base, SearchApiPrefs.embedKey(context), model, texts) ?: return@withContext results
        if (vecs.size != texts.size) return@withContext results

        val qv = vecs[0]
        // 每条结果带上 (result, 向量, 与query相似度)
        val scored = results.indices
            .map { Triple(results[it], vecs[it + 1], cosine(qv, vecs[it + 1])) }
            .filter { it.third >= minSim }
            .sortedByDescending { it.third }

        val kept = ArrayList<Triple<SearchResult, FloatArray, Float>>()
        for (item in scored) {
            if (kept.none { cosine(it.second, item.second) > dupSim }) kept.add(item)
            if (kept.size >= topK) break
        }
        if (kept.isEmpty()) results else kept.map { it.first }
    }

    private fun embed(base: String, key: String, model: String, texts: List<String>): List<FloatArray>? {
        return try {
            val conn = URL("$base/embeddings").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10000; conn.readTimeout = 20000
            conn.setRequestProperty("Content-Type", "application/json")
            if (key.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $key")
            conn.doOutput = true
            val body = JSONObject().apply { put("model", model); put("input", JSONArray(texts)) }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
            val json = conn.inputStream.bufferedReader().use { it.readText() }; conn.disconnect()
            val data = JSONObject(json).optJSONArray("data") ?: return null
            // 按 index 字段回填（OpenAI 兼容响应不保证 data 顺序 == input 顺序）
            val out = arrayOfNulls<FloatArray>(data.length())
            for (i in 0 until data.length()) {
                val o = data.optJSONObject(i) ?: return null
                val idx = o.optInt("index", i)
                val arr = o.optJSONArray("embedding") ?: return null
                if (idx in out.indices) out[idx] = FloatArray(arr.length()) { arr.optDouble(it).toFloat() }
            }
            if (out.any { it == null }) null else out.filterNotNull()
        } catch (_: Exception) { null }
    }

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0f; var na = 0f; var nb = 0f
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        val d = sqrt(na) * sqrt(nb)
        return if (d == 0f) 0f else dot / d
    }
}
