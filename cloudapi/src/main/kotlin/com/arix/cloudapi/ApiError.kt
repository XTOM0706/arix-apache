package com.arix.cloudapi

import org.json.JSONObject

/**
 * 把 API 报错说成人话 —— 关键是**把服务器的原话带出来**。
 *
 * 之前这里只给一句 `HTTP 400 Bad Request`，服务器真正说的（存在 errorBody 里）被整个丢掉。
 * 于是「DeepSeek 一直爆 400」查了很久才发现是我们发了它不认识的 `thinking` 字段——
 * 而服务器**第一次**就明说了 `unknown field 'thinking'`，只是这句话从来没到过任何人眼前。
 * 报错的价值全在细节里，剥掉细节的「请求被拒」等于没报。
 */
object ApiError {

    /** 从各家五花八门的错误 JSON 里挖出那句人话。挖不到就返回原始 body（截断）。 */
    fun extractMessage(body: String?): String {
        val b = body?.trim().orEmpty()
        if (b.isBlank()) return ""
        return try {
            val j = JSONObject(b)
            // OpenAI/DeepSeek/Kimi/GLM: {"error":{"message":"..."}}；也有直接 {"message":"..."} 的
            val err = j.opt("error")
            when {
                err is JSONObject -> err.optString("message").ifBlank { err.optString("type") }.ifBlank { err.toString() }
                err is String && err.isNotBlank() -> err
                j.has("message") -> j.optString("message")
                // 阿里百炼: {"code":"...","message":"..."}；已被上面覆盖。兜底给原文
                else -> b
            }.ifBlank { b }
        } catch (_: Exception) { b }   // 不是 JSON（HTML 错误页/网关文本）→ 原样给
    }

    /**
     * 完整的错误描述：状态码 + 这个码通常意味着什么 + **服务器原话** + 当前是谁在服务/什么模型。
     * 给用户看，也给 AI 看——两边都得能据此判断下一步该干嘛。
     */
    fun describe(code: Int, httpMessage: String, body: String?, baseUrl: String, model: String): String {
        val provider = ApiProvider.detect(baseUrl)
        val who = (provider.displayName.ifBlank { hostOf(baseUrl) }) + if (model.isNotBlank()) " · $model" else ""
        val server = extractMessage(body)
        val meaning = when (code) {
            400 -> "请求被服务器拒绝：多半是请求体里有它不认识的字段，或参数值不合法（看下面服务器原话）"
            401 -> "密钥无效或没填对"
            403 -> "密钥没有这个模型/接口的权限，或所在地区被拒"
            404 -> "接口路径不存在：检查 base URL（常见是漏了或多了 /v1），也可能是模型名不存在"
            408 -> "服务器等超时了"
            413 -> "请求太大：上下文或图片超出限制"
            422 -> "参数校验没过（看下面服务器原话）"
            429 -> "太频繁或额度用尽"
            in 500..599 -> "服务端自己出错了，跟你的请求多半无关，稍后重试或换个服务商"
            else -> httpMessage.ifBlank { "请求失败" }
        }
        return buildString {
            append("HTTP $code — $meaning\n")
            append("服务商：$who\n")
            if (server.isNotBlank()) append("服务器原话：").append(server.take(600))
            else append("服务器没给出任何错误说明（响应体是空的）")
        }
    }

    private fun hostOf(baseUrl: String): String =
        try { java.net.URI(baseUrl).host ?: baseUrl } catch (_: Exception) { baseUrl }
}
