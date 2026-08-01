package com.arix.app

import android.content.Context
import java.io.File

// 文生图（角色卡头像）配置：用户自己填 key。OpenAI 兼容 /images/generations 接口。
object ImageGenPrefs {
    private const val PREFS = "image_gen"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun baseUrl(c: Context): String = p(c).getString("base", "https://api.openai.com/v1") ?: ""
    fun apiKey(c: Context): String = p(c).getString("key", "") ?: ""
    fun model(c: Context): String = p(c).getString("model", "dall-e-3") ?: ""
    fun size(c: Context): String = p(c).getString("size", "1024x1024") ?: "1024x1024"

    /**
     * 供应商。`""` = 自动（按已填的 Base URL 认，见 [com.arix.tool.ImageGenProviders]）。
     * 其余取值 `openai` / `qwen` / `zhipu`——**这几个字符串是和 ImageGenProviders 共享的契约**，别单方面改。
     *
     * 之所以除了自动识别还要给一个显式选项：同一个网关地址后面可能挂着不同家的模型，
     * URL 认不出来时用户得有办法直接告诉我们是哪家，而不是对着一句看不懂的报错猜。
     */
    fun provider(c: Context): String = p(c).getString("provider", "") ?: ""
    fun setProvider(c: Context, v: String) { p(c).edit().putString("provider", v).apply() }
    fun set(c: Context, base: String, key: String, model: String, size: String) {
        p(c).edit().putString("base", base).putString("key", key).putString("model", model).putString("size", size).apply()
    }

    // 调 OpenAI 兼容文生图接口，返回保存到本地的图片 URI 字符串；失败返回 (null, 错误信息)
    suspend fun generate(c: Context, prompt: String): Pair<String?, String?> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val base = baseUrl(c).trimEnd('/'); val key = apiKey(c).trim(); val model = model(c).trim()
        if (key.isBlank()) return@withContext null to "请先配置文生图 API key"
        try {
            val url = java.net.URL("$base/images/generations")
            val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 120000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $key")
            }
            val body = org.json.JSONObject().apply {
                put("model", model); put("prompt", prompt); put("n", 1); put("size", size(c)); put("response_format", "b64_json")
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val resp = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) return@withContext null to "HTTP $code: ${resp.take(200)}"
            val json = org.json.JSONObject(resp); val data = json.optJSONArray("data")?.optJSONObject(0)
            val b64 = data?.optString("b64_json", "") ?: ""
            val urlStr = data?.optString("url", "") ?: ""
            val bytes: ByteArray = when {
                b64.isNotBlank() -> android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                urlStr.isNotBlank() -> java.net.URL(urlStr).openStream().use { it.readBytes() }
                else -> return@withContext null to "响应无图片数据"
            }
            val f = File(c.cacheDir, "avatar_gen_${System.currentTimeMillis()}.png")
            f.writeBytes(bytes)
            androidx.core.content.FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", f).toString() to null
        } catch (e: Exception) { null to (e.message ?: "生成失败") }
    }
}
