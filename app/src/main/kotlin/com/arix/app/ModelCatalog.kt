package com.arix.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 拉取服务商可用模型列表：填好 baseUrl+key 后 GET /models（OpenAI 兼容），
 * 返回所有可调用的模型 id，用户直接选、免手填模型名（RikkaHub 式）。
 *
 * 顺带解析价格（有的话）：OpenRouter 这类会在每个模型上带 pricing{prompt,completion}，
 * 据此判定免费；不给价格的服务商（硅基流动/Groq/智谱…）一律 free=null=「不知道」，
 * **不猜、不硬编码免费名单**——硬编码的名单几个月就烂掉。
 */
object ModelCatalog {

    /**
     * 一个模型条目。free 是三态：
     * - true / false：服务商接口给了价格，据此判定（输入+输出单价都是 0 → 免费）
     * - null：这家接口不返回价格，免费与否无从得知；界面必须如实标「未知」
     */
    data class ModelInfo(val id: String, val free: Boolean? = null)

    /** 拉取结果。失败带 HTTP code + 原始信息，由界面翻成人话（401/403 → 提示先填 key）。 */
    sealed class Result {
        data class Ok(val models: List<ModelInfo>) : Result() {
            /** 这家是否给了价格（任一条目带价即算）——决定界面显示「免费/付费」还是「未知」。 */
            val pricingKnown: Boolean get() = models.any { it.free != null }
        }
        /** code=0 表示没拿到 HTTP 响应（网络异常/地址为空），原因看 message。 */
        data class Err(val code: Int, val message: String) : Result()
    }

    private fun endpoint(baseUrl: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return when {
            base.endsWith("/models") -> base
            Regex("/(v\\d+[a-z]*|openai)$").containsMatchIn(base) -> "$base/models"
            else -> "$base/v1/models"
        }
    }

    /**
     * 从模型对象里判定免费：只看**实际给出**的价格字段。
     * - 没有 pricing 对象 → null（不知道，别猜）
     * - prompt/completion 都缺 → null（如只按次计费的模型，判不了）
     * - 给出的那些单价全是 0 → true；解析不出的数值按「非免费」保守处理
     * 只认 prompt/completion：embedding 只有 prompt、没 completion，不能因为缺 completion 就判成付费。
     */
    private fun freeOf(o: JSONObject): Boolean? {
        val p = o.optJSONObject("pricing") ?: return null
        val prices = listOf("prompt", "completion")
            .map { p.opt(it) }
            .filter { it != null && it != JSONObject.NULL }
        if (prices.isEmpty()) return null
        return prices.all { it.toString().trim().toDoubleOrNull() == 0.0 }
    }

    /**
     * 拉取模型（带价格判定）。成功返回 Ok(按名排序)，失败返回 Err(code, 原始信息)。
     * 取消异常照样往外抛（本项目铁律：绝不吞 CancellationException）。
     */
    suspend fun fetchDetailed(baseUrl: String, apiKey: String): Result = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank()) return@withContext Result.Err(0, "base url is blank")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint(baseUrl)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000; readTimeout = 12000
                // 有些服务商密钥池用逗号/换行分隔，取第一个
                apiKey.split(Regex("[,\n]")).map { it.trim() }.firstOrNull { it.isNotBlank() }?.let {
                    setRequestProperty("Authorization", "Bearer $it")
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val resp = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                ?: return@withContext Result.Err(code, "empty response")
            if (code !in 200..299) return@withContext Result.Err(code, resp.take(300))
            val json = JSONObject(resp)
            // OpenAI 兼容：{"data":[{"id":".."}...]}；少数直接 {"models":[..]} 或 {"data":["id"...]}
            val arr = json.optJSONArray("data") ?: json.optJSONArray("models")
                ?: return@withContext Result.Err(code, "no data/models field in response")
            val out = LinkedHashMap<String, ModelInfo>(arr.length())
            for (i in 0 until arr.length()) {
                when (val item = arr.opt(i)) {
                    is JSONObject -> item.optString("id", "").takeIf { it.isNotBlank() }
                        ?.let { id -> out.getOrPut(id) { ModelInfo(id, freeOf(item)) } }
                    is String -> if (item.isNotBlank()) out.getOrPut(item) { ModelInfo(item) }
                }
            }
            Result.Ok(out.values.sortedBy { it.id })
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            Result.Err(0, e.message ?: e.toString())
        } finally {
            conn?.disconnect()
        }
    }

    /** 拉取模型 id 列表（按名排序）；失败/空返回空列表。 */
    suspend fun fetch(baseUrl: String, apiKey: String): List<String> =
        (fetchDetailed(baseUrl, apiKey) as? Result.Ok)?.models?.map { it.id } ?: emptyList()
}
