package com.arix.cloudapi

import com.arix.cloudapi.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini 原生协议（`:streamGenerateContent?alt=sse`）的请求构造与流式解析。
 *
 * 与 OpenAI 那套的形状差异，改之前先读：
 *  1. messages 叫 `contents`，角色只有 **`user` / `model`**（没有 assistant、没有 system、没有 tool）。
 *  2. system 走顶层 `systemInstruction`。
 *  3. 每条消息的内容是 `parts` 数组：`{text}` / `{inlineData}` / `{functionCall}` / `{functionResponse}`。
 *  4. **工具结果靠函数名认领，不靠 id**（`functionResponse.name`）。而我们内部存的是 `toolCallId`，
 *     所以构造请求时必须先从前面的 assistant 消息里建一张 id→name 的表，见 [buildContents]。
 *     这是本文件最容易出错的一处：表没建对，模型收到的工具结果就对不上它问的问题。
 *  5. 生成参数全在 `generationConfig` 里，且 `max_tokens` 叫 `maxOutputTokens`。
 *  6. 模型名在 **URL 里**，不在请求体里。
 *  7. 认证走 `x-goog-api-key`。
 *  8. Gemini 3 系列多轮工具调用要求把 `thoughtSignature` 原样带回（不带就 400）。
 *     我们此前是在 OpenAI 兼容层里抓 `extra_content` 硬接的；原生这条直接读写 part 上的字段。
 *
 * `alt=sse` 之后每片就是一行 `data: {...}`、片间空行分隔，所以 [CloudApiClient] 那个
 * "攒 data 行、空行时处理"的循环不用为它改动。
 */
internal object GeminiProtocol {

    /** part 上的思考签名字段名。存进 `ToolCallMsg.extra` 时也用它作键，回传时原样写回。 */
    private const val SIGNATURE_FIELD = "thoughtSignature"

    // ============================================================
    // 请求
    // ============================================================

    /**
     * 拼 URL：`{base}/v1beta/models/{model}:streamGenerateContent?alt=sse`。
     *
     * 末尾 `#` 仍然是"强制原样"的逃生门（与另两个协议一致）。base 已经带版本段就不再补 `/v1beta`，
     * 已经指到 `models/xxx:方法` 就整条不动——中转站常常给的就是那种完整路径。
     */
    fun url(baseUrl: String, model: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.endsWith("#")) return trimmed.dropLast(1)
        val base = trimmed.trimEnd('/')
        if (":streamGenerateContent" in base) return if ("alt=" in base) base else "$base?alt=sse"
        // 模型名允许用户写成 "models/gemini-x"，URL 里已经有 models/ 段了，去重
        val m = model.trim().removePrefix("models/")
        val root = if (Regex("/v\\d+[a-z]*$").containsMatchIn(base)) base else "$base/v1beta"
        return "$root/models/$m:streamGenerateContent?alt=sse"
    }

    /** 认证头。⚠ 不是 `Authorization: Bearer`。 */
    fun headers(key: String): Map<String, String> =
        if (key.isBlank()) emptyMap() else mapOf("x-goog-api-key" to key)

    fun buildBody(
        messages: List<ChatMessage>,
        systemPrompt: String?,
        config: CloudApiConfig,
        enableThinking: Int,
        images: List<String>?,
        tools: JSONArray?,
    ): JSONObject {
        val body = JSONObject()
        body.put("contents", buildContents(messages, images))

        if (!systemPrompt.isNullOrBlank()) {
            body.put(
                "systemInstruction",
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt)))
            )
        }

        val genCfg = JSONObject()
        config.temperature?.let { genCfg.put("temperature", it.toDouble()) }
        config.topP?.let { genCfg.put("topP", it.toDouble()) }
        config.maxTokens?.let { genCfg.put("maxOutputTokens", it) }
        // 思考：只在用户明确要「深度思考」时才发 thinkingConfig。
        // 不发不是"关掉思考"，是"用模型的默认"——发一个不支持 thinkingConfig 的模型是整轮 400，
        // 而默认行为最多是思考没按预期开关。口径与另两个协议一致：拿不准就不发。
        if (enableThinking >= 2) {
            genCfg.put("thinkingConfig", JSONObject().put("includeThoughts", true))
        }
        if (genCfg.length() > 0) body.put("generationConfig", genCfg)

        if (tools != null && tools.length() > 0) {
            val decls = convertTools(tools)
            if (decls.length() > 0) {
                body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", decls)))
                body.put(
                    "toolConfig",
                    JSONObject().put("functionCallingConfig", JSONObject().put("mode", "AUTO"))
                )
            }
        }

        ApiExtrasStore.bodyTemplate(config.baseUrl, config.model)?.let { tpl -> mergeInto(body, tpl) }
        return body
    }

    /**
     * 消息列表 → `contents`。
     *
     * id→name 表是这里的关键（见类注释第 4 条）：先扫一遍所有 assistant 消息的 toolCalls，
     * 建好 `toolCallId → 函数名`，后面遇到工具结果才填得出 `functionResponse.name`。
     * 查不到名字的工具结果**降级成普通 user 文本**——发一个 name 为空的 functionResponse 是 400，
     * 降级成文本至少这一轮还能继续，模型也读得懂内容。
     */
    private fun buildContents(messages: List<ChatMessage>, images: List<String>?): JSONArray {
        val idToName = HashMap<String, String>()
        messages.forEach { m -> m.toolCalls?.forEach { tc -> if (tc.id.isNotBlank()) idToName[tc.id] = tc.name } }

        val out = JSONArray()
        val lastUser = messages.lastOrNull { it.role == "user" }

        messages.forEach { msg ->
            when {
                msg.role == "system" -> { /* 顶层 systemInstruction 已承载 */ }

                msg.toolCallId != null -> {
                    val name = idToName[msg.toolCallId]
                    val parts = JSONArray()
                    if (name.isNullOrBlank()) {
                        parts.put(JSONObject().put("text", msg.content))
                    } else {
                        parts.put(
                            JSONObject().put(
                                "functionResponse",
                                JSONObject()
                                    .put("name", name)
                                    // response 必须是对象；我们的工具结果是文本，包一层 content 字段
                                    .put("response", JSONObject().put("content", msg.content))
                            )
                        )
                    }
                    // 工具结果在 Gemini 这边算 user 侧发言
                    out.put(JSONObject().put("role", "user").put("parts", parts))
                }

                else -> {
                    val parts = JSONArray()
                    if (msg.content.isNotBlank()) parts.put(JSONObject().put("text", msg.content))

                    if (msg.role == "user") {
                        val extraImages = if (msg === lastUser) (images ?: emptyList()) else emptyList()
                        ((msg.images ?: emptyList()) + extraImages).forEach { b64 ->
                            parts.put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject().put("mimeType", "image/jpeg").put("data", b64)
                                )
                            )
                        }
                        msg.audios?.forEach { b64 ->
                            parts.put(
                                JSONObject().put(
                                    "inlineData",
                                    JSONObject().put("mimeType", "audio/wav").put("data", b64)
                                )
                            )
                        }
                    }

                    msg.toolCalls?.forEach { tc ->
                        val call = JSONObject()
                            .put("name", tc.name)
                            .put("args", runCatching { JSONObject(tc.arguments) }.getOrElse { JSONObject() })
                        val part = JSONObject().put("functionCall", call)
                        // 思考签名原样写回（见类注释第 8 条）。它是 **part 级**字段，不在 functionCall 里面。
                        // 脏数据只跳过这一项，不炸整个请求。
                        tc.extra?.let { ex ->
                            runCatching {
                                JSONObject(ex).optString(SIGNATURE_FIELD, "")
                                    .takeIf { it.isNotBlank() }
                                    ?.let { part.put(SIGNATURE_FIELD, it) }
                            }
                        }
                        parts.put(part)
                    }

                    // Gemini 拒空 parts。与 Anthropic 那条同理，补一个空格文本顶住。
                    if (parts.length() == 0) parts.put(JSONObject().put("text", " "))

                    out.put(
                        JSONObject()
                            .put("role", if (msg.role == "assistant") "model" else "user")
                            .put("parts", parts)
                    )
                }
            }
        }
        return out
    }

    /**
     * OpenAI 的 tools → Gemini 的 `functionDeclarations`。
     *
     * ⚠ Gemini 的 schema 方言比 OpenAI 窄：不认 `additionalProperties`、`$schema`、`exclusiveMinimum`
     * 这类 JSON Schema 关键字，塞过去会 400。这里递归清一遍白名单外的键——
     * 我们自己的工具 schema 很朴素（type/properties/required/description/enum/items），清理基本无损。
     */
    private fun convertTools(tools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: continue
            val name = fn.optString("name", "")
            if (name.isBlank()) continue
            val decl = JSONObject()
                .put("name", name)
                .put("description", fn.optString("description", ""))
            val params = fn.optJSONObject("parameters")
            // 无参工具**别发空的 parameters**：Gemini 对 `{type:"object", properties:{}}` 容忍，
            // 但对完全没有 type 的空对象会报 schema 非法。没有参数就整个键不发。
            if (params != null && params.length() > 0) decl.put("parameters", sanitizeSchema(params))
            out.put(decl)
        }
        return out
    }

    /** Gemini 认得的 schema 关键字白名单。递归过滤，别的键丢掉。 */
    private val SCHEMA_KEYS = setOf(
        "type", "format", "description", "nullable", "enum",
        "properties", "required", "items", "minimum", "maximum",
    )

    private fun sanitizeSchema(src: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = src.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k !in SCHEMA_KEYS) continue
            when (val v = src.get(k)) {
                is JSONObject ->
                    // properties 下面每一项本身都是一个 schema，要逐个递归；items 是单个 schema
                    if (k == "properties") {
                        val props = JSONObject()
                        val pk = v.keys()
                        while (pk.hasNext()) {
                            val name = pk.next()
                            (v.opt(name) as? JSONObject)?.let { props.put(name, sanitizeSchema(it)) }
                        }
                        out.put(k, props)
                    } else out.put(k, sanitizeSchema(v))
                else -> out.put(k, v)
            }
        }
        return out
    }

    private fun mergeInto(target: JSONObject, extra: JSONObject) {
        val keys = extra.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = extra.get(k)
            val cur = target.opt(k)
            if (v is JSONObject && cur is JSONObject) mergeInto(cur, v) else target.put(k, v)
        }
    }

    // ============================================================
    // 流式解析
    // ============================================================

    /**
     * 解析一片 SSE 载荷。
     *
     * [indexer] 是**跨片**的工具调用计数器，由调用方持有。为什么需要它：Gemini 的 functionCall
     * 是**一次给全**的（args 直接是对象，不像 OpenAI 那样切成 partial_json 流），而上层是按
     * "同序号则把 arguments 拼接起来"归并的。序号不给唯一值，两次不同的调用就会被拼成一坨烂 JSON。
     */
    fun parse(data: String, indexer: ToolIndexer): ProtoDelta {
        return try {
            val j = JSONObject(data)
            val cand = j.optJSONArray("candidates")?.optJSONObject(0)
            val parts = cand?.optJSONObject("content")?.optJSONArray("parts")

            val text = StringBuilder()
            val thought = StringBuilder()
            val calls = mutableMapOf<Int, ProtoToolCall>()

            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i) ?: continue
                    val fc = p.optJSONObject("functionCall")
                    if (fc != null) {
                        val idx = indexer.next++
                        val sig = p.optString(SIGNATURE_FIELD, "").takeIf { it.isNotBlank() }
                        calls[idx] = ProtoToolCall(
                            // Gemini 不一定给 id。自己造一个稳定的：工具结果回传时我们只用它反查函数名，
                            // 造的 id 只要在本轮内唯一且前后一致就够（见 buildContents 的 idToName）。
                            id = fc.optString("id", "").ifBlank { "gcall_${idx}_${fc.optString("name", "fn")}" },
                            name = fc.optString("name", ""),
                            arguments = (fc.optJSONObject("args") ?: JSONObject()).toString(),
                            extra = sig?.let { JSONObject().put(SIGNATURE_FIELD, it).toString() },
                        )
                        continue
                    }
                    val t = p.optString("text", "")
                    if (t.isEmpty()) continue
                    // thought=true 的 part 是思考正文，不是回答——混进正文里会把思考直接印在气泡上
                    if (p.optBoolean("thought", false)) thought.append(t) else text.append(t)
                }
            }

            val um = j.optJSONObject("usageMetadata")
            val rawFinish = cand?.optString("finishReason", "")?.takeIf { it.isNotBlank() && it != "null" }

            ProtoDelta(
                reasoning = thought.toString().takeIf { it.isNotEmpty() },
                content = text.toString().takeIf { it.isNotEmpty() },
                promptTokens = um?.optInt("promptTokenCount", 0),
                completionTokens = um?.optInt("candidatesTokenCount", 0),
                totalTokens = um?.optInt("totalTokenCount", 0),
                toolCalls = calls,
                // 带着工具调用收尾时，Gemini 照样报 STOP。上层的截断保护按 OpenAI 口径判，
                // 这里替它翻成 tool_calls，否则"要调工具"和"说完了"分不开。
                finishReason = if (calls.isNotEmpty() && (rawFinish == null || rawFinish == "STOP")) "tool_calls"
                else mapFinishReason(rawFinish),
            )
        } catch (_: Exception) {
            ProtoDelta()
        }
    }

    private fun mapFinishReason(s: String?): String? = when (s) {
        null -> null
        "STOP" -> "stop"
        "MAX_TOKENS" -> "length"
        "SAFETY", "RECITATION", "PROHIBITED_CONTENT", "BLOCKLIST" -> "content_filter"
        else -> s.lowercase()
    }

    /** 跨片的工具调用序号发号器。 */
    class ToolIndexer {
        var next = 0
    }
}
