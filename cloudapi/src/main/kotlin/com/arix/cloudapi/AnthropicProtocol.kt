package com.arix.cloudapi

import com.arix.cloudapi.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * Anthropic Messages API（原生 `/v1/messages`）的请求构造与流式解析。
 *
 * 和 OpenAI 那套的差别不是"字段名不一样"，是**形状**不一样，逐条列在这里，改之前先读：
 *  1. `system` 是**顶层字符串**，不是 messages 里的一条。发成 `role:"system"` 的消息会被拒。
 *  2. **没有 `role:"tool"`**。工具结果是一条 `role:"user"` 的消息，内容是 `tool_result` 块，
 *     靠 `tool_use_id` 认领。同一轮里多个工具结果要**合并进同一条 user 消息**的 content 数组——
 *     拆成多条会破坏 user/assistant 交替。
 *  3. 模型发起的工具调用是 `tool_use` 块，`input` 是**对象**（OpenAI 那边 `arguments` 是字符串）。
 *  4. `max_tokens` **必填**。没填就得给个默认值，不然直接 400。
 *  5. `temperature` 值域 0~1（OpenAI 是 0~2）。超了会被拒，得夹一下。
 *  6. 开了扩展思考又要调工具时，**上一轮的 thinking 块必须连签名原样带回来**，否则整轮被拒。
 *     这是本文件最容易被改坏的一条，见 [THINKING_KEY] 与 [writeBackThinking]。
 *  7. 认证走 `x-api-key`，不是 `Authorization: Bearer`；另外 `anthropic-version` 必填。
 *
 * 流式事件的类型写在 data 载荷的 `type` 字段里（不是只写在 SSE 的 `event:` 行上），
 * 所以 [CloudApiClient] 那个"攒 data 行、空行时处理"的循环不用为它改动。
 */
internal object AnthropicProtocol {

    /** 必填的 API 版本头。这是 Anthropic 的稳定版本号，不是我们的版本。 */
    private const val VERSION = "2023-06-01"

    /** `max_tokens` 必填时的兜底值。取 4096 是因为它对所有在售 Claude 模型都合法。 */
    private const val DEFAULT_MAX_TOKENS = 4096

    /**
     * 思考块在 `ChatMessage.extra` 里的存放键。
     *
     * 存的是**整个 thinking 块数组的原始 JSON**（含每块的 `signature`），而不是只存签名：
     * 官方要求回传时"连续的 thinking 块序列必须与当初生成的一致"，
     * 只留签名、正文重新拼一遍是对不上的。
     */
    const val THINKING_KEY = "anthropic_thinking"

    // ============================================================
    // 请求
    // ============================================================

    /**
     * 拼 URL。规则与 OpenAI 那条平行：末尾 `#` 强制原样；已经指到 `/messages` 就不动；
     * 含版本段只补 `/messages`；否则按官方惯例补 `/v1/messages`。
     */
    fun url(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.endsWith("#")) return trimmed.dropLast(1)
        val base = trimmed.trimEnd('/')
        return when {
            base.endsWith("/messages") -> base
            Regex("/v\\d+[a-z]*$").containsMatchIn(base) -> "$base/messages"
            else -> "$base/v1/messages"
        }
    }

    /**
     * 认证与版本头。
     *
     * ⚠ 这里**不发** `Authorization: Bearer`：Anthropic 认 `x-api-key`。有些中转站两个都吃，
     * 但官方端点只认前者，发错的表现是 401。
     */
    fun headers(key: String): Map<String, String> = buildMap {
        if (key.isNotBlank()) put("x-api-key", key)
        put("anthropic-version", VERSION)
    }

    /**
     * 构造请求体。
     *
     * [images] 是"本轮新挂的图"，与 OpenAI 那条一致地并到**最后一条 user 消息**上。
     */
    fun buildBody(
        messages: List<ChatMessage>,
        systemPrompt: String?,
        config: CloudApiConfig,
        enableThinking: Int,
        images: List<String>?,
        tools: JSONArray?,
    ): JSONObject {
        val body = JSONObject()
        body.put("model", config.model)
        body.put("stream", true)
        // 必填项，见类注释第 4 条
        body.put("max_tokens", config.maxTokens ?: DEFAULT_MAX_TOKENS)
        if (!systemPrompt.isNullOrBlank()) body.put("system", systemPrompt)

        // 值域比 OpenAI 窄，夹一下而不是原样转发（见类注释第 5 条）
        config.temperature?.let { body.put("temperature", it.toDouble().coerceIn(0.0, 1.0)) }
        config.topP?.let { body.put("top_p", it.toDouble().coerceIn(0.0, 1.0)) }

        body.put("messages", buildMessages(messages, images))

        if (tools != null && tools.length() > 0) {
            body.put("tools", convertTools(tools))
            body.put("tool_choice", JSONObject().put("type", "auto"))
        }

        // 扩展思考：只在用户明确要「深度思考」时开（enableThinking>=2），与 OpenAI 那条的口径一致。
        // budget_tokens 必须**小于** max_tokens，否则 400；取一半并给个下限（官方最小 1024）。
        if (enableThinking >= 2) {
            val maxTok = body.optInt("max_tokens", DEFAULT_MAX_TOKENS)
            val budget = (maxTok / 2).coerceAtLeast(1024)
            if (budget < maxTok) {
                body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", budget))
            }
        }

        ApiExtrasStore.bodyTemplate(config.baseUrl, config.model)?.let { tpl -> mergeInto(body, tpl) }
        return body
    }

    /**
     * 把我们的消息列表翻成 Anthropic 的 messages。
     *
     * 三件必须在这里完成的事：
     *  · 丢掉 `role:"system"`（顶层 `system` 已经承载了它）
     *  · 把 `role:"tool"` 的结果翻成 user 消息里的 `tool_result` 块，**连续的合并成一条**
     *  · assistant 消息若带着上一轮的 thinking 块，原样放回 content 最前面
     */
    private fun buildMessages(messages: List<ChatMessage>, images: List<String>?): JSONArray {
        val out = JSONArray()
        val lastUser = messages.lastOrNull { it.role == "user" }

        // 待合并的 tool_result 块：连续的工具结果攒在一起，遇到别的角色才落成一条 user 消息
        var pendingToolResults: JSONArray? = null
        fun flushToolResults() {
            pendingToolResults?.let { blocks ->
                out.put(JSONObject().put("role", "user").put("content", blocks))
            }
            pendingToolResults = null
        }

        messages.forEach { msg ->
            when {
                msg.role == "system" -> { /* 顶层 system 已承载，丢掉 */ }

                msg.toolCallId != null -> {
                    // 工具结果。content 给字符串就够了（Anthropic 也接受块数组，但我们的结果就是文本）
                    val blocks = pendingToolResults ?: JSONArray().also { pendingToolResults = it }
                    blocks.put(
                        JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", msg.toolCallId)
                            .put("content", msg.content)
                    )
                }

                else -> {
                    flushToolResults()
                    val content = JSONArray()

                    // 思考块必须排在这条 assistant 消息**最前面**、且在任何 tool_use 之前（见类注释第 6 条）
                    if (msg.role == "assistant") writeBackThinking(content, msg.extra)

                    if (msg.content.isNotBlank()) {
                        content.put(JSONObject().put("type", "text").put("text", msg.content))
                    }

                    if (msg.role == "user") {
                        val extraImages = if (msg === lastUser) (images ?: emptyList()) else emptyList()
                        ((msg.images ?: emptyList()) + extraImages).forEach { b64 ->
                            content.put(
                                JSONObject().put("type", "image").put(
                                    "source",
                                    JSONObject()
                                        .put("type", "base64")
                                        .put("media_type", "image/jpeg")
                                        .put("data", b64)
                                )
                            )
                        }
                        // ⚠ Anthropic 的 messages 不吃音频块。我们的音频输入只有 OpenAI 兼容层那条路支持，
                        // 这里静默丢掉而不是发出去——发一个它不认的块是整轮 400，丢掉只是这一轮听不到音频。
                    }

                    msg.toolCalls?.forEach { tc ->
                        content.put(
                            JSONObject()
                                .put("type", "tool_use")
                                .put("id", tc.id)
                                .put("name", tc.name)
                                // OpenAI 那边 arguments 是字符串，这边 input 是对象。空/脏一律给空对象：
                                // 发一个非法的 input 是 400，发空对象最多是这次调用参数不全。
                                .put("input", runCatching { JSONObject(tc.arguments) }.getOrElse { JSONObject() })
                        )
                    }

                    // 整条都空的消息不能发（Anthropic 拒空 content 数组）。补一个空格文本块顶住——
                    // 「先想再调工具」的模型这轮常常正文为空，正是最容易撞上的场景。
                    if (content.length() == 0) content.put(JSONObject().put("type", "text").put("text", " "))

                    out.put(
                        JSONObject()
                            .put("role", if (msg.role == "assistant") "assistant" else "user")
                            .put("content", content)
                    )
                }
            }
        }
        flushToolResults()
        return out
    }

    /** 把 [ChatMessage.extra] 里存着的上一轮 thinking 块原样放回 content 开头。脏数据只跳过，不炸请求。 */
    private fun writeBackThinking(content: JSONArray, extra: String?) {
        val raw = extra ?: return
        try {
            val blocks = JSONObject(raw).optJSONArray(THINKING_KEY) ?: return
            for (i in 0 until blocks.length()) blocks.optJSONObject(i)?.let { content.put(it) }
        } catch (_: Exception) {}
    }

    /**
     * OpenAI 的 tools 数组翻成 Anthropic 的。
     *
     * `{type:"function", function:{name, description, parameters}}` → `{name, description, input_schema}`。
     * 认不出形状的条目**跳过**而不是原样塞过去：塞过去是整轮 400。
     */
    private fun convertTools(tools: JSONArray): JSONArray {
        val out = JSONArray()
        for (i in 0 until tools.length()) {
            val t = tools.optJSONObject(i) ?: continue
            val fn = t.optJSONObject("function") ?: continue
            val name = fn.optString("name", "")
            if (name.isBlank()) continue
            out.put(
                JSONObject()
                    .put("name", name)
                    .put("description", fn.optString("description", ""))
                    .put("input_schema", fn.optJSONObject("parameters") ?: JSONObject().put("type", "object"))
            )
        }
        return out
    }

    /** 与 CloudApiClient.mergeJson 同规则的浅合并，复制一份是为了让本文件不依赖它的私有方法。 */
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
     * 事件序列大致是：`message_start` → (`content_block_start` → `content_block_delta`* →
     * `content_block_stop`)* → `message_delta` → `message_stop`，中间夹 `ping`。
     *
     * [thinkingAcc] 是**跨片**的思考块累积器（key = 块序号），由调用方持有：
     * 思考正文和签名分在不同的事件里到，必须攒到 `content_block_stop` 才拼得出完整的一块。
     */
    fun parse(data: String, thinkingAcc: MutableMap<Int, ThinkingBlock>): ProtoDelta {
        return try {
            val j = JSONObject(data)
            when (j.optString("type")) {
                "message_start" -> {
                    val u = j.optJSONObject("message")?.optJSONObject("usage")
                    // 这里只有 input_tokens 是确定的；output 要等 message_delta
                    ProtoDelta(promptTokens = u?.optInt("input_tokens", 0))
                }

                "content_block_start" -> {
                    val idx = j.optInt("index", 0)
                    val block = j.optJSONObject("content_block") ?: return ProtoDelta()
                    when (block.optString("type")) {
                        "tool_use" -> ProtoDelta(
                            toolCalls = mapOf(
                                idx to ProtoToolCall(
                                    id = block.optString("id", ""),
                                    name = block.optString("name", ""),
                                    arguments = "",
                                )
                            )
                        )
                        "thinking" -> {
                            thinkingAcc[idx] = ThinkingBlock()
                            ProtoDelta()
                        }
                        else -> ProtoDelta()
                    }
                }

                "content_block_delta" -> {
                    val idx = j.optInt("index", 0)
                    val d = j.optJSONObject("delta") ?: return ProtoDelta()
                    when (d.optString("type")) {
                        "text_delta" -> ProtoDelta(content = d.optString("text", "").takeIf { it.isNotEmpty() })
                        "thinking_delta" -> {
                            val t = d.optString("thinking", "")
                            if (t.isNotEmpty()) thinkingAcc.getOrPut(idx) { ThinkingBlock() }.thinking.append(t)
                            ProtoDelta(reasoning = t.takeIf { it.isNotEmpty() })
                        }
                        "signature_delta" -> {
                            // 签名只到这一次，且**不是**给人看的正文——绝不能拼进 reasoning
                            val s = d.optString("signature", "")
                            if (s.isNotEmpty()) thinkingAcc.getOrPut(idx) { ThinkingBlock() }.signature.append(s)
                            ProtoDelta()
                        }
                        "input_json_delta" -> ProtoDelta(
                            toolCalls = mapOf(idx to ProtoToolCall(arguments = d.optString("partial_json", "")))
                        )
                        else -> ProtoDelta()
                    }
                }

                "message_delta" -> {
                    val stop = j.optJSONObject("delta")?.optString("stop_reason", "")
                    val u = j.optJSONObject("usage")
                    ProtoDelta(
                        completionTokens = u?.optInt("output_tokens", 0),
                        finishReason = mapStopReason(stop),
                    )
                }

                // ping / content_block_stop / message_stop：没有要取的东西
                else -> ProtoDelta()
            }
        } catch (_: Exception) {
            ProtoDelta()
        }
    }

    /** Anthropic 的 stop_reason → OpenAI 口径，让上层那套 finishReason 判断（截断保护等）照旧生效。 */
    private fun mapStopReason(s: String?): String? = when (s) {
        null, "", "null" -> null
        "end_turn", "stop_sequence" -> "stop"
        "tool_use" -> "tool_calls"
        "max_tokens" -> "length"
        "refusal" -> "content_filter"
        else -> s
    }

    /** 一个还在攒的 thinking 块。正文和签名分别到，收完才拼成回传用的块。 */
    class ThinkingBlock {
        val thinking = StringBuilder()
        val signature = StringBuilder()
    }

    /**
     * 把攒完的思考块归档成 `ChatMessage.extra` 的 JSON 串（没有就 null=不存不写）。
     *
     * 只收**签名齐的**块：没签名的块回传过去会被拒，留着反而让下一轮整体失败。
     */
    fun finishThinking(acc: Map<Int, ThinkingBlock>): String? {
        if (acc.isEmpty()) return null
        val arr = JSONArray()
        acc.toSortedMap().forEach { (_, b) ->
            val sig = b.signature.toString()
            if (sig.isBlank()) return@forEach
            arr.put(
                JSONObject()
                    .put("type", "thinking")
                    .put("thinking", b.thinking.toString())
                    .put("signature", sig)
            )
        }
        if (arr.length() == 0) return null
        return JSONObject().put(THINKING_KEY, arr).toString()
    }
}
