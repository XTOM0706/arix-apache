package com.arix.tool

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 文生图的多供应商后端。
 *
 * 为什么需要：原来只有一条 OpenAI 兼容的 `/images/generations`。国内用户拿不到 OpenAI key，
 * 于是「画一张图」这个能力对他们**整个不存在**——不是画得差，是根本调不出来。
 * 通义(DashScope 万相)和智谱(CogView)都能免翻墙拿到 key，其中 cogview-3-flash 还是免费的。
 *
 * 两家的接口形状和 OpenAI 都不一样，所以各写一条：
 *  - 通义：**异步任务**——先 POST 拿 task_id，再轮询 `/tasks/{id}` 直到 SUCCEEDED 才有图片地址。
 *  - 智谱：同步返回，但**只给 url、不认 `response_format=b64_json`**（照 OpenAI 那条发过去会被拒）。
 *
 * 配置沿用现有的「文生图」设置（SharedPreferences `image_gen`，与 [com.arix.app.ImageGenPrefs] 同一份），
 * 不新开设置项：
 *  - 供应商 = 工具参数 `provider` > prefs `provider` > **按已填的 base URL 自动认**（填了 dashscope 就是通义）。
 *  - key = prefs `key_<provider>`（若 UI 日后做了分供应商的 key）> prefs `key`。
 *  - 模型同理，并且**认不出来就用该供应商的默认模型**：把 `dall-e-3` 原样发给通义只会得到一条看不懂的报错。
 */
object ImageGenProviders {
    const val OPENAI = "openai"
    const val QWEN = "qwen"
    const val ZHIPU = "zhipu"

    /** 供应商 → 给用户看的名字 + 去哪儿拿 key。生成失败时要说清下一步，不能只丢一句「没配置」。 */
    val LABELS = mapOf(
        OPENAI to "OpenAI 兼容接口",
        QWEN to "通义万相（阿里云百炼 DashScope）",
        ZHIPU to "智谱 CogView",
    )

    private fun prefs(c: Context) = c.getSharedPreferences("image_gen", Context.MODE_PRIVATE)

    private fun norm(s: String): String = when (s.trim().lowercase()) {
        "qwen", "tongyi", "dashscope", "wanx", "wan", "aliyun", "bailian", "通义", "万相" -> QWEN
        "zhipu", "glm", "cogview", "bigmodel", "chatglm", "智谱" -> ZHIPU
        else -> OPENAI
    }

    /** 这次该用哪家。显式参数 > 设置里的 provider > 按 base URL 猜。 */
    fun resolve(c: Context, explicit: String?): String {
        val e = explicit?.trim().orEmpty()
        if (e.isNotBlank() && !e.equals("auto", true)) return norm(e)
        val saved = prefs(c).getString("provider", "").orEmpty()
        if (saved.isNotBlank() && !saved.equals("auto", true)) return norm(saved)
        val base = prefs(c).getString("base", "").orEmpty().lowercase()
        return when {
            base.contains("dashscope") || base.contains("aliyuncs") -> QWEN
            base.contains("bigmodel") || base.contains("zhipu") -> ZHIPU
            else -> OPENAI
        }
    }

    /** 生成一张图，成功返回可直接在聊天里渲染的 URI，失败返回错误原因。 */
    suspend fun generate(c: Context, prompt: String, provider: String): Pair<String?, String?> = when (provider) {
        QWEN -> qwen(c, prompt)
        ZHIPU -> zhipu(c, prompt)
        else -> com.arix.app.ImageGenPrefs.generate(c, prompt)   // 老路原样保留
    }

    // ---- 配置读取 ----

    private fun key(c: Context, provider: String): String {
        val p = prefs(c)
        p.getString("key_$provider", "")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        return p.getString("key", "")?.trim().orEmpty()
    }

    /**
     * 模型名：优先分供应商的 `model_<provider>`；否则用设置里那个**但要认得出是这家的**，
     * 认不出就用默认。跨家复用模型名（把 dall-e-3 发给通义）只会换来一条模型不存在的报错。
     */
    private fun model(c: Context, provider: String, known: List<String>, def: String): String {
        val p = prefs(c)
        p.getString("model_$provider", "")?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val m = p.getString("model", "")?.trim().orEmpty()
        return if (m.isNotBlank() && known.any { m.startsWith(it, true) }) m else def
    }

    private fun size(c: Context): String = prefs(c).getString("size", "1024x1024")?.trim()?.ifBlank { null } ?: "1024x1024"

    /** 用户把 base 填成了这家的地址就尊重他填的（私有网关/代理），否则用官方地址。 */
    private fun host(c: Context, marker: String, official: String, apiPath: String): String {
        val base = prefs(c).getString("base", "").orEmpty().trim().trimEnd('/')
        if (base.contains(marker, true)) {
            val cut = base.indexOf(apiPath)
            return if (cut > 0) base.substring(0, cut).trimEnd('/') else base
        }
        return official
    }

    // ---- 通义万相（DashScope 异步任务）----

    private suspend fun qwen(c: Context, prompt: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val k = key(c, QWEN)
        if (k.isBlank()) return@withContext null to
            "没有通义(DashScope)的 API key。到「设置 → 文生图」把 key 填成百炼控制台的 API-KEY，" +
            "base 填 https://dashscope.aliyuncs.com，模型填 wanx2.1-t2i-turbo。"
        val root = host(c, "dashscope", "https://dashscope.aliyuncs.com", "/api/v1")
        val m = model(c, QWEN, listOf("wanx", "wan2", "qwen", "flux", "stable"), "wanx2.1-t2i-turbo")
        try {
            val body = JSONObject().apply {
                put("model", m)
                put("input", JSONObject().put("prompt", prompt))
                // 万相的尺寸写法是 1024*1024（不是 x），设置里存的是通用写法，这里转
                put("parameters", JSONObject().put("size", size(c).replace('x', '*')).put("n", 1))
            }.toString()
            val (code, resp) = post("$root/api/v1/services/aigc/text2image/image-synthesis", k, body,
                mapOf("X-DashScope-Async" to "enable"))
            if (code !in 200..299) return@withContext null to "通义 HTTP $code：${resp.take(300)}"
            val taskId = JSONObject(resp).optJSONObject("output")?.optString("task_id").orEmpty()
            if (taskId.isBlank()) return@withContext null to "通义没有返回 task_id：${resp.take(200)}"

            // 轮询：文生图动辄十几秒，出图前 task_status 一直是 PENDING/RUNNING
            var waited = 0L
            val budget = 180_000L
            var lastErr = ""
            while (waited < budget) {
                delay(3000); waited += 3000
                val (pc, pr) = get("$root/api/v1/tasks/$taskId", k)
                if (pc !in 200..299) { lastErr = "HTTP $pc：${pr.take(200)}"; continue }
                val out = JSONObject(pr).optJSONObject("output") ?: continue
                when (out.optString("task_status")) {
                    "SUCCEEDED" -> {
                        val url = out.optJSONArray("results")?.optJSONObject(0)?.optString("url").orEmpty()
                        if (url.isBlank()) return@withContext null to "通义任务成功但没给图片地址：${pr.take(200)}"
                        return@withContext saveRemote(c, url)
                    }
                    "FAILED", "CANCELED", "UNKNOWN" -> return@withContext null to
                        ("通义生成失败：" + out.optString("message").ifBlank { out.optString("code").ifBlank { pr.take(200) } })
                }
            }
            null to ("通义生成超时（等了 ${budget / 1000}s）" + if (lastErr.isNotBlank()) "，最后一次查询：$lastErr" else "")
        } catch (ce: CancellationException) {
            throw ce   // STOP 要停得掉这段轮询
        } catch (e: Exception) {
            null to "通义调用出错：${e.message}"
        }
    }

    // ---- 智谱 CogView（同步，只返回 url）----

    private suspend fun zhipu(c: Context, prompt: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        val k = key(c, ZHIPU)
        if (k.isBlank()) return@withContext null to
            "没有智谱的 API key。到「设置 → 文生图」把 key 填成智谱开放平台的 API Key，" +
            "base 填 https://open.bigmodel.cn，模型填 cogview-3-flash（这个模型免费）。"
        val root = host(c, "bigmodel", "https://open.bigmodel.cn", "/api/paas")
        val m = model(c, ZHIPU, listOf("cogview", "glm"), "cogview-3-flash")
        try {
            val body = JSONObject().apply {
                put("model", m); put("prompt", prompt); put("size", size(c))
                // 刻意不发 response_format：智谱不认这个字段，发了会被拒——这正是照搬 OpenAI 那条走不通的原因
            }.toString()
            val (code, resp) = post("$root/api/paas/v4/images/generations", k, body)
            if (code !in 200..299) return@withContext null to "智谱 HTTP $code：${resp.take(300)}"
            val d = JSONObject(resp).optJSONArray("data")?.optJSONObject(0)
                ?: return@withContext null to "智谱响应里没有图片：${resp.take(200)}"
            val url = d.optString("url").orEmpty()
            val b64 = d.optString("b64_json").orEmpty()
            when {
                url.isNotBlank() -> saveRemote(c, url)
                b64.isNotBlank() -> saveBytes(c, android.util.Base64.decode(b64, android.util.Base64.DEFAULT))
                else -> null to "智谱响应里没有图片地址：${resp.take(200)}"
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            null to "智谱调用出错：${e.message}"
        }
    }

    // ---- 公共 HTTP / 落盘 ----

    private fun post(url: String, key: String, body: String, extra: Map<String, String> = emptyMap()): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; doOutput = true; connectTimeout = 20000; readTimeout = 120000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            extra.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        conn.outputStream.use { it.write(body.toByteArray()) }
        return read(conn)
    }

    private fun get(url: String, key: String): Pair<Int, String> {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"; connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("Authorization", "Bearer $key")
        }
        return read(conn)
    }

    private fun read(conn: HttpURLConnection): Pair<Int, String> = try {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        code to text
    } finally { runCatching { conn.disconnect() } }

    /** 图片地址是临时的（两家都只留一天左右），必须马上抓回本机再交给聊天页渲染。 */
    private fun saveRemote(c: Context, url: String): Pair<String?, String?> = try {
        saveBytes(c, URL(url).openStream().use { it.readBytes() })
    } catch (e: Exception) { null to "图片下载失败：${e.message}" }

    private fun saveBytes(c: Context, bytes: ByteArray): Pair<String?, String?> = try {
        // 与 ImageGenPrefs 同一落点（cacheDir + fileprovider），聊天页的渲染路径不用改
        val f = File(c.cacheDir, "imggen_${System.currentTimeMillis()}.png")
        f.writeBytes(bytes)
        androidx.core.content.FileProvider.getUriForFile(c, "${c.packageName}.fileprovider", f).toString() to null
    } catch (e: Exception) { null to "图片保存失败：${e.message}" }
}
