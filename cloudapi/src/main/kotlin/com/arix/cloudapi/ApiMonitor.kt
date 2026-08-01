package com.arix.cloudapi

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ApiMonitor {
    data class CallRecord(
        val timestamp: Long,
        val provider: String,
        val model: String,
        val endpoint: String,
        val promptTokens: Int,
        val completionTokens: Int,
        val totalTokens: Int,
        val latencyMs: Long,
        val success: Boolean,
        val errorMsg: String?
    )

    private val records = mutableListOf<CallRecord>()

    // 持久化的**按模型累计**用量（跨重启，供使用统计页算花费）。model -> [调用次数, 入token, 出token]。
    @Volatile private var appCtx: Context? = null
    private val modelAgg = java.util.concurrent.ConcurrentHashMap<String, LongArray>()
    data class ModelUsage(val model: String, val calls: Long, val inTokens: Long, val outTokens: Long)

    fun init(context: Context) {
        appCtx = context.applicationContext
        runCatching {
            val s = appCtx?.getSharedPreferences("xtom_api_usage", Context.MODE_PRIVATE)?.getString("agg", "") ?: ""
            if (s.isNotBlank()) { val o = JSONObject(s); o.keys().forEach { m -> val a = o.optJSONArray(m); if (a != null) modelAgg[m] = longArrayOf(a.optLong(0), a.optLong(1), a.optLong(2)) } }
        }
    }
    private fun saveAgg() {
        val ctx = appCtx ?: return
        runCatching {
            val o = JSONObject(); modelAgg.forEach { (m, a) -> o.put(m, JSONArray().put(a[0]).put(a[1]).put(a[2])) }
            ctx.getSharedPreferences("xtom_api_usage", Context.MODE_PRIVATE).edit().putString("agg", o.toString()).apply()
        }
    }
    /** 按模型累计用量（跨重启），花费在 UI 侧配合 ModelPricing 算。 */
    fun modelUsage(): List<ModelUsage> = modelAgg.map { (m, a) -> ModelUsage(m, a[0], a[1], a[2]) }.sortedByDescending { it.inTokens + it.outTokens }
    fun clearModelUsage() { modelAgg.clear(); saveAgg() }

    fun record(
        baseUrl: String, model: String,
        promptTokens: Int, completionTokens: Int, totalTokens: Int,
        latencyMs: Long, success: Boolean, errorMsg: String?
    ) {
        val detected = ApiProvider.detect(baseUrl)
        val provider = if (detected == ApiProvider.OTHER) baseUrl.take(30) else detected.displayName
        records.add(CallRecord(
            timestamp = System.currentTimeMillis(),
            provider = provider, model = model, endpoint = baseUrl,
            promptTokens = promptTokens, completionTokens = completionTokens,
            totalTokens = totalTokens, latencyMs = latencyMs,
            success = success, errorMsg = errorMsg
        ))
        if (records.size > 500) records.removeAt(0)
        // 按模型累计并持久化（只累计有 token 的成功调用）
        if (model.isNotBlank() && (promptTokens > 0 || completionTokens > 0)) {
            val a = modelAgg.getOrPut(model) { LongArray(3) }
            synchronized(a) { a[0]++; a[1] += promptTokens.coerceAtLeast(0); a[2] += completionTokens.coerceAtLeast(0) }
            saveAgg()
        }
    }

    fun getSummary(): ApiMonitorSummary {
        val total = records.size
        val successes = records.count { it.success }
        val totalTokens = records.sumOf { it.totalTokens.toLong() }
        val totalLatency = records.sumOf { it.latencyMs }
        val avgLatency = if (total > 0) totalLatency / total else 0L
        val byProvider = records.groupBy { it.provider }.mapValues { (_, recs) ->
            ProviderStats(recs.size, recs.count { it.success }, recs.sumOf { it.totalTokens.toLong() },
                if (recs.isNotEmpty()) recs.sumOf { it.latencyMs } / recs.size else 0L)
        }
        return ApiMonitorSummary(total, successes, totalTokens, avgLatency, byProvider)
    }

    fun getRecent(limit: Int = 20): List<CallRecord> = records.takeLast(limit)

    fun clear() { records.clear() }

    fun exportToJson(): String = JSONArray().apply {
        records.forEach { r ->
            put(JSONObject().apply {
                put("time", SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(r.timestamp)))
                put("provider", r.provider); put("model", r.model)
                put("tokens", r.totalTokens); put("latency_ms", r.latencyMs)
                put("success", r.success)
            })
        }
    }.toString(2)

    data class ApiMonitorSummary(
        val totalCalls: Int, val successfulCalls: Int,
        val totalTokens: Long, val avgLatencyMs: Long,
        val byProvider: Map<String, ProviderStats>
    )

    data class ProviderStats(
        val calls: Int, val successful: Int,
        val tokens: Long, val avgLatencyMs: Long
    )
}
