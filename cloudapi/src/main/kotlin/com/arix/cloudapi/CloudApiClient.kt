package com.arix.cloudapi

import com.arix.cloudapi.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

class CloudApiClient(private val config: CloudApiConfig) {

    private companion object {
        /** 429 重试的基础退避（乘以第几次尝试）。 */
        const val RETRY_BASE_MS = 600L
        /** 退避封顶：这段等待发生在**发送主路径**上，等太久用户只会觉得"发不出去"。
         *  服务端给的 Retry-After 再长也按这个截断——宁可把限流原话回给用户，也别静默卡住。 */
        const val RETRY_MAX_MS = 2_000L
    }

    // 复用全局共享 OkHttpClient（线程池/连接池全 App 共用），不再每次构造新建。
    private val client = HttpClientProvider.chat

    @Volatile private var activeCall: Call? = null

    data class Usage(val promptTokens: Int, val completionTokens: Int, val totalTokens: Int)

    data class StreamResult(
        val fullContent: String,
        val error: String?,
        val httpCode: Int,
        val errorBody: String?,
        val usage: Usage? = null,
        val toolCalls: List<ToolCallChunk> = emptyList(),
        /** OpenAI 风格终止原因（stop/tool_calls/length/content_filter…）。length 表示被 max_tokens 截断，
         *  此时若还带工具调用，参数极可能是半截的——调用方应据此丢弃、别拿截断参数去执行。 */
        val finishReason: String? = null,
        /** 供应商私有透传槽（**消息级**）：本轮 assistant 消息上「必须原样回传」的私有结构的原始 JSON 串
         *  （思考块/签名/加密推理项）。调用方应把它原样存进 `ChatMessage.extra`，下一轮我们再原样写回。
         *  没收到就是 null=不存不写，与从前行为一致。详见 ReasoningPassthrough。 */
        val extra: String? = null,
    )

    data class ToolCallChunk(
        val id: String,
        val name: String,
        val arguments: String,
        /** 供应商私有透传槽：tool_call 对象里 `extra_content` 的**原始 JSON 串**，原样收、原样回传。
         *  Gemini 3 系列的 OpenAI 兼容层把「思考签名」放在这里（google.thought_signature），
         *  下一轮不带回去就 400（"Function call is missing a thought_signature"）。
         *  存原串而非解析出某个字段，是为了对以后别家的私有扩展也自动生效。 */
        val extra: String? = null
    )

    fun cancel() {
        activeCall?.cancel()
    }

    private val providerType: ApiProvider by lazy { ApiProvider.detect(config.baseUrl) }

    /**
     * 这个端点讲哪种协议（见 [ChatProtocol]）。
     *
     * 只有四处按它分家：URL、认证头、请求体、单片解析。**SSE 那个读取循环三家共用**——
     * Anthropic 把事件类型写在 data 载荷的 `type` 字段里、Gemini 走 `alt=sse` 也是一行一片，
     * 都不需要读 SSE 的 `event:` 行，所以"攒 data 行、空行时处理"这套逻辑照旧成立。
     */
    private val protocol: ChatProtocol by lazy { ChatProtocol.of(config.baseUrl, config.model) }

    suspend fun streamChat(
        messages: List<ChatMessage>,
        systemPrompt: String? = null,
        enableThinking: Int = 0,
        images: List<String>? = null,
        tools: JSONArray? = null,
        onReasoningChunk: ((String) -> Unit)? = null,
        // 工具参数增量：(工具名, 已累积的参数 JSON 串)。用于「文件写入内容边写边显示」——
        // 文件内容是 file_write/file_edit 的调用参数，流式期间就在累积，这里把它实时透出去。
        onToolArgsChunk: ((String, String) -> Unit)? = null,
        onContentChunk: (String) -> Unit
    ): StreamResult = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        val allMessages = mutableListOf<ChatMessage>()
        if (!systemPrompt.isNullOrBlank()) {
            allMessages.add(ChatMessage("system", systemPrompt))
        }
        allMessages.addAll(messages)

        // OpenAI 兼容那条路的请求体构造。抽成局部函数只为让下面那个 when 读得懂——
        // 一行逻辑都没改，纯粹是把原来直着写的这一大段收进来，好让另两个协议在同一层并列。
        fun buildOpenAiBody(): JSONObject {
        val messagesJson = JSONArray()
        allMessages.forEach { msg ->
            val msgObj = JSONObject()
            msgObj.put("role", msg.role)

            // ⚠ 残缺 tool 消息直接跳过：role=="tool" 但 toolCallId 为 null 的历史脏数据（老版本落库时
            // 没写 toolCallId），拼进请求体会发成 `{"role":"tool","content":...}` 而缺 tool_call_id，
            // DeepSeek 等严格端点直接 400（"Duplicate value for 'tool_call_id' of null"）。
            // 正常路径每条 tool 结果都有配对 id（ChatScreen 用 tc.id 生成），null/空串 的只会是脏数据，
            // 模型拿它没意义（没有可配对的 assistant.tool_calls），跳过不损失。
            // ⚠ 必须是 isNullOrBlank：老数据的 toolCallId 反序列化成空串 ""（不是 null），
            // 只判 null 会漏放 → 发给服务商 `tool_call_id:""` → 照样 400。
            if (msg.role == "tool" && msg.toolCallId.isNullOrBlank()) return@forEach

            val effectiveImages = if (msg.role == "user") {
                (msg.images ?: emptyList()) + if (msg == allMessages.lastOrNull { it.role == "user" }) (images ?: emptyList()) else emptyList()
            } else emptyList()
            val hasAudios = !msg.audios.isNullOrEmpty()
            val hasMultiModal = effectiveImages.isNotEmpty() || hasAudios

            if (hasMultiModal) {
                val contentArray = JSONArray()
                if (msg.content.isNotBlank()) {
                    contentArray.put(JSONObject().apply {
                        put("type", "text")
                        put("text", msg.content)
                    })
                }
                effectiveImages.forEach { b64 ->
                    contentArray.put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$b64"))
                    })
                }
                msg.audios?.forEach { b64 ->
                    contentArray.put(JSONObject().apply {
                        put("type", "input_audio")
                        put("input_audio", JSONObject().put("data", b64).put("format", "wav"))
                    })
                }
                msgObj.put("content", contentArray)
            } else if (!msg.toolCallId.isNullOrBlank()) {
                msgObj.put("content", msg.content)
                msgObj.put("tool_call_id", msg.toolCallId)
            } else if (!msg.toolCalls.isNullOrEmpty()) {
                // content 为空也要把键发出去（发 null）：省略整个键会让 Mistral 这类严格端点 400。
                // 「先想再调工具」的推理模型这轮常常没有正文，正是最容易撞上的场景。
                if (msg.content.isNotEmpty()) msgObj.put("content", msg.content)
                else msgObj.put("content", JSONObject.NULL)
                val tcArray = JSONArray()
                msg.toolCalls.forEach { tc ->
                    // ⚠ 残缺的 tool_call 直接跳过：name 为空的历史脏数据（老版本落库时 name 字段
                    // 缺失/为空）重发出去，模型会照着它复刻一个 name="" 的调用 → 执行时
                    // ToolManager 查不到 → 「工具未找到」。正常调用 name 必非空，跳过不损失。
                    if (tc.name.isBlank()) return@forEach
                    tcArray.put(JSONObject().apply {
                        put("id", tc.id)
                        put("type", "function")
                        put("function", JSONObject().apply {
                            put("name", tc.name)
                            put("arguments", tc.arguments)
                        })
                        // 原样写回供应商私有扩展：Gemini 3 系列多轮工具调用**必须**带回思考签名，
                        // 否则第二轮 400（"Function call is missing a thought_signature"）。
                        // 脏数据只跳过这一项，不能炸掉整个请求。
                        tc.extra?.let { ex ->
                            try { put("extra_content", JSONObject(ex)) } catch (_: Exception) {}
                        }
                    })
                }
                // ⚠ 全被跳过（历史 tool_calls 全是空 name）时别发空 tool_calls 数组——服务商不认空数组。
                // 这条 assistant 消息退化成普通消息（content 已有），tool_calls 键省略即可。
                if (tcArray.length() > 0) msgObj.put("tool_calls", tcArray)
            } else {
                msgObj.put("content", msg.content)
            }
            // 原样写回**消息级**供应商私有扩展（思考块/签名/加密推理项）。放在分支链之后，
            // 四种消息形态（多模态/工具结果/带工具调用/纯文本）都覆盖得到；只补目标里还没有的键，
            // 不会覆盖上面刚拼好的 content/tool_calls。脏数据只跳过这一项，不炸整个请求。
            msg.extra?.let { ex -> ReasoningPassthrough.writeBack(msgObj, ex, config.baseUrl) }
            // ⚠ DeepSeek（含 opencode.ai 托管的 deepseek-v4-flash-free）要求 thinking mode 下把
            // `reasoning_content` **原样回传**给 API，缺了直接 400
            // （"The reasoning_content in the thinking mode must be passed back to the API"）。
            // 老数据/透传槽缺失时，assistant 消息带 reasoning 思考文本 → 在这里兜底补上该字段，
            // 保证回传（哪怕 reasoning 本身来自旧版本没存 extra 的消息）。
            if (msg.role == "assistant" && msg.reasoning.isNullOrBlank() == false &&
                msgObj.has("reasoning_content") == false) {
                msgObj.put("reasoning_content", msg.reasoning)
            }
            messagesJson.put(msgObj)
        }

        val bodyJson = JSONObject().apply {
            put("model", config.model)
            put("messages", messagesJson)
            put("stream", true)

            if (tools != null && tools.length() > 0) {
                put("tools", tools)
                put("tool_choice", "auto")
            }

            // 生成参数（仅在用户设置了值时下发）
            config.temperature?.let { put("temperature", it.toDouble()) }
            config.topP?.let { put("top_p", it.toDouble()) }
            config.maxTokens?.let { put("max_tokens", it) }
            config.frequencyPenalty?.let { put("frequency_penalty", it.toDouble()) }
            config.presencePenalty?.let { put("presence_penalty", it.toDouble()) }

            // ── 思考字段 ──
            // 只在**这个 provider×model 确认支持**时才发（见 ApiProvider.supportsThinkingParam 的说明）。
            // 早先这里按厂商无条件下发、连思考关掉时也发，把 DeepSeek/Kimi/GLM/Qwen 全打成 400；
            // OpenRouter 反而因为没被识别、落进 OTHER 拿到了干净 body，成了唯一能用的——
            // 判反了方向。发错字段的代价是整个请求 400、一句话都回不了，比思考没关掉严重得多，
            // 所以拿不准时一律不发。
            if (ApiProvider.supportsThinkingParam(providerType, config.model)) {
                when (providerType) {
                    // 这几家默认开思考，要显式发 disabled 才关得掉（"无论如何都思考" bug）
                    ApiProvider.KIMI, ApiProvider.GLM -> put("thinking", JSONObject().apply {
                        put("type", if (enableThinking >= 2) "enabled" else "disabled")
                    })
                    // Qwen 同理：qwen3 默认开启，需显式 false；AI自选(1)不强制开
                    ApiProvider.QWEN -> put("enable_thinking", enableThinking >= 2)
                    // 这两家不发就是默认，只在用户明确要深度思考时才发
                    ApiProvider.OPENAI, ApiProvider.MISTRAL -> if (enableThinking >= 2) put("reasoning_effort", "medium")
                    else -> {}
                }
            }
        }

        // ── 供应商内置联网搜索透传（item ④）──
        // 仅对参数明确、低风险的供应商自动注入；未知供应商请改用下方「自定义请求体模板」，
        // 以免把不认识的顶层字段发给严格校验的服务器而 400。
        if (ApiExtrasStore.webSearch(config.baseUrl, config.model)) {
            when (providerType) {
                ApiProvider.QWEN -> bodyJson.put("enable_search", true)   // 通义/百炼 兼容模式官方开关
                ApiProvider.GLM -> {                                       // 智谱 GLM：web_search 作为一个 tool 传入
                    val t = bodyJson.optJSONArray("tools") ?: JSONArray().also { bodyJson.put("tools", it) }
                    t.put(JSONObject().apply {
                        put("type", "web_search")
                        put("web_search", JSONObject().put("enable", true).put("search_result", true))
                    })
                }
                else -> { /* 其它供应商无统一开关，交给自定义请求体模板 */ }
            }
        }

        // ── 自定义请求体模板（item ①）──
        // 把用户按 baseUrl+model 存的额外 JSON 合并进请求体：顶层键合并/覆盖，
        // 嵌套 JSONObject 递归浅合并（便于覆写 thinking 等嵌套字段）。
        ApiExtrasStore.bodyTemplate(config.baseUrl, config.model)?.let { tpl -> mergeJson(bodyJson, tpl) }
        return bodyJson
        }

        // 原生协议的 system：顶层字段只有一个位置，所以把「传进来的 systemPrompt」和
        // 「消息列表里夹着的 system 消息」拼成一份。不能只取前者——对话中途插的 system 消息
        // （世界书、状态卡那类）就会被静默丢掉，而两家原生协议压根没有 system 这个角色可发。
        val mergedSystem = (
            listOfNotNull(systemPrompt?.takeIf { it.isNotBlank() }) +
                messages.filter { it.role == "system" }.map { it.content }
            ).filter { it.isNotBlank() }.joinToString("\n\n")

        val bodyJson = buildOpenAiBody()

        val requestBody = bodyJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType())

        // 灵活拼接 endpoint，兼容各厂商不同路径：
        //  · 末尾带 # → 强制原样（去掉 #），给完整自定义 URL 用
        //  · 已以 /chat/completions 结尾 → 原样
        //  · 已含版本段（/v1 /v4 /v1beta.../openai/compatible-mode/v1 等）→ 只补 /chat/completions
        //  · 否则按 OpenAI 惯例补 /v1/chat/completions（保持对旧配置的向后兼容）
        val trimmed = config.baseUrl.trim()
        val url = when {
            trimmed.endsWith("#") -> trimmed.dropLast(1)
            else -> {
                val base = trimmed.trimEnd('/')
                when {
                    base.endsWith("/chat/completions") -> base
                    Regex("/(v\\d+[a-z]*|openai)$").containsMatchIn(base) -> "$base/chat/completions"
                    else -> "$base/v1/chat/completions"
                }
            }
        }
        // 密钥池容错：多 key 时最多试几枚，遇 401/403/429 摘除坏 key、换下一枚重试（失败发生在流式开始前，重试安全）。
        val keyCount = if (config.apiKey.isNotBlank()) KeyPool.keysOf(config.apiKey).size else 0
        val maxAttempts = if (keyCount > 1) minOf(keyCount, 3) else 1

        fun buildRequest(usedKey: String): Request = Request.Builder()
            .url(url)
            .post(requestBody)
            .apply {
                // OpenAI 兼容端点认 `Authorization: Bearer`。无 Key 则一个都不发。
                if (usedKey.isNotBlank()) header("Authorization", "Bearer $usedKey")
            }
            .header("Content-Type", "application/json; charset=utf-8")
            .apply {
                // 自定义请求头（覆盖默认）
                config.customHeaders?.takeIf { it.isNotBlank() }?.let { h ->
                    try { val ho = org.json.JSONObject(h); ho.keys().forEach { k -> header(k, ho.optString(k)) } } catch (_: Exception) {}
                }
            }
            .build()

        // ⭐ 停止要**立刻**停：SSE 的接收是 `reader.readLine()`——一个**阻塞** socket 读。
        // 阻塞 IO 不理会协程取消，所以光 `sendJob.cancel()` 只能把 isActive 置 false，
        // 线程还卡在 readLine 里等下一个字节；模型正好在慢慢想（或者干脆不再发分片）时，
        // 用户按了停止要等到下一片数据到达、最坏等满 readTimeout(120s) 才真的停。
        // 唯一能立刻打断阻塞读的是把底层 socket 关掉 = `call.cancel()`。
        // 这里把「协程被取消」直接接到「取消 OkHttp Call」上：一处挂钩覆盖本方法所有的 call，
        // 包括密钥池重试换的那几枚（activeCall 每次都会被更新）。
        // 用 invokeOnCompletion 而不是让调用方去调 cancel()：调用方（ChatScreen）只有 sendJob，
        // 拿不到当前正在跑的是哪个 CloudApiClient 实例——之前 `cancel()` 写了却全项目没人调，就是这个原因。
        val cancelHook = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            ?.invokeOnCompletion { cause -> if (cause != null) runCatching { activeCall?.cancel() } }
        // ⚠ 必须声明在 try **外面**：网络层异常（连不上/超时/流中断）是在 catch 里处理的，
        // 而 try 内的局部变量在 catch 作用域里看不见——放里面就编不过（刚踩过一次）。
        // 留这一份是为了让密钥池知道「刚才出事的是哪一枚 key」。
        var lastUsedKey = ""
        try {
            var attempt = 0
            var response: okhttp3.Response
            while (true) {
                attempt++
                val usedKey = if (config.apiKey.isNotBlank()) KeyPool.next(config.apiKey) else ""  // 密钥池：轮换取健康 key
                lastUsedKey = usedKey
                val call = client.newCall(buildRequest(usedKey))
                activeCall = call
                // 挂钩可能在 activeCall 赋值**之前**就已经跑完了（取消发生在两句之间）——那样这枚 call
                // 谁也不会去取消它。补一次自检：已经取消了就别发出去。
                if (!kotlinx.coroutines.currentCoroutineContext().isActive) { call.cancel() }
                response = call.execute()
                if (usedKey.isNotBlank()) KeyPool.reportResult(usedKey, response.code)  // 记录本 key 的健康度（冷却/清除）
                if (response.isSuccessful) break
                val code = response.code
                if (attempt < maxAttempts && (code == 401 || code == 403 || code == 429)) {
                    // 429 要等一下再换：401/403 是「这枚 key 坏了」，立刻换下一枚就对；
                    // 而 429 是**限流**——多把 key 常常挂在同一个账号/同一份配额下，甚至 KeyPool 在
                    // 全员冷却时会返回"最快恢复的那枚"（还在冷却里）。不等就换 = 对着限流器连打三下，
                    // 是最坏的一种重试。优先听服务端的 Retry-After，没有就退避一小段（封顶，别把发送卡住）。
                    val waitMs = if (code == 429) {
                        val retryAfter = response.header("Retry-After")?.trim()?.toLongOrNull()?.times(1000L)
                        (retryAfter ?: (RETRY_BASE_MS * attempt)).coerceIn(0L, RETRY_MAX_MS)
                    } else 0L
                    response.close()
                    if (waitMs > 0) kotlinx.coroutines.delay(waitMs)   // 可取消：STOP 时不会被这段拖住
                    continue   // 坏 key 已冷却，换下一枚
                }
                val errBody = response.body?.string() ?: ""
                // 把服务器原话补记进这枚 key 的健康档案（只记原因，计数在 reportResult 里已经算过）。
                // 配置页据此能显示「这枚为什么坏了」，而不是干巴巴一个状态码。
                if (usedKey.isNotBlank()) KeyPool.noteFailureReason(usedKey, errBody)
                response.close()
                activeCall = null
                // 把服务器原话带进 error 本身：errorBody 从前只存不看（ChatScreen 只读 error），
                // 于是「unknown field 'thinking'」这种服务器第一次就说明白的话，从来没到过任何人眼前。
                return@withContext StreamResult(
                    fullContent = "",
                    error = ApiError.describe(response.code, response.message, errBody, config.baseUrl, config.model),
                    httpCode = response.code, errorBody = errBody
                )
            }

            val body = response.body ?: run { response.close(); activeCall = null; return@withContext StreamResult(
                fullContent = "", error = "Empty response body", httpCode = response.code, errorBody = null
            ) }

            val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
            val fullContent = StringBuilder()
            val toolCallMap = mutableMapOf<Int, ToolCallChunk>()
            var currentData = StringBuilder()
            var usage: Usage? = null
            var finishReason: String? = null
            // 消息级私有透传字段的累积器：SSE 会把同一个思考块切成多片，边收边按块 index 归并
            val extraAcc = mutableMapOf<String, Any>()

            try {
                var line: String? = reader.readLine()
                while (line != null && isActive) {
                    when {
                        line.isNullOrEmpty() -> {
                            if (currentData.isNotEmpty()) {
                                val data = currentData.toString().trim()
                                currentData = StringBuilder()
                                if (data != "[DONE]") {
                                    val d = parseDelta(data)
                                    val (reasoning, content, u, toolDeltas, fr) = d
                                    ReasoningPassthrough.merge(extraAcc, d.extra)
                                    if (fr != null) finishReason = fr
                                    if (reasoning != null) {
                                        fullContent.append(reasoning)
                                        onReasoningChunk?.invoke(reasoning)
                                    }
                                    if (content != null) {
                                        fullContent.append(content)
                                        onContentChunk(content)
                                    }
                                    toolDeltas.forEach { (idx, chunk) ->
                                        val existing = toolCallMap[idx]
                                        if (existing != null) {
                                            toolCallMap[idx] = existing.copy(
                                                // ⚠ org.json 把 JSON null 解析成字符串 "null"，而 DeepSeek 流式
                                                // tool_calls 的**后续片**里 id/name 经常是 null（只带 arguments 增量）。
                                                // 若用 ifBlank，非空的 "null" 会把第一片的正确 id/name 覆盖掉 →
                                                // 最终 name="null" → ToolManager 查不到 → 「工具未找到:null」。
                                                // 这里把空串、纯 "null" 字符串都视为无效，保留已有值。
                                                id = if (chunk.id.isNullOrBlank() || chunk.id == "null") existing.id else chunk.id,
                                                name = if (chunk.name.isNullOrBlank() || chunk.name == "null") existing.name else chunk.name,
                                                arguments = existing.arguments + chunk.arguments,
                                                extra = chunk.extra ?: existing.extra   // 后到的非空才覆盖，别把已收到的签名抹掉
                                            )
                                        } else {
                                            toolCallMap[idx] = chunk
                                        }
                                        toolCallMap[idx]?.takeIf { it.name.isNotBlank() && it.name != "null" }?.let { onToolArgsChunk?.invoke(it.name, it.arguments) }
                                    }
                                    usage = mergeUsage(usage, u)
                                }
                            }
                        }
                        line.startsWith("data: ") -> currentData.append(line.removePrefix("data: "))
                        line.startsWith("data:") -> currentData.append(line.removePrefix("data:").trimStart())
                        else -> {}
                    }
                    line = if (isActive) reader.readLine() else null
                }

                if (currentData.isNotEmpty() && isActive) {
                    val data = currentData.toString().trim()
                    if (data != "[DONE]") {
                        val d = parseDelta(data)
                        val (reasoning, content, u, toolDeltas, fr) = d
                        ReasoningPassthrough.merge(extraAcc, d.extra)
                        if (fr != null) finishReason = fr
                        if (reasoning != null) { fullContent.append(reasoning); onReasoningChunk?.invoke(reasoning) }
                        if (content != null) { fullContent.append(content); onContentChunk(content) }
                        toolDeltas.forEach { (idx, chunk) ->
                            val existing = toolCallMap[idx]
                            if (existing != null) {
                                toolCallMap[idx] = existing.copy(
                                    // ⚠ 同主循环：org.json 把 JSON null 解析成字符串 "null"，DeepSeek 流式后续片
                                    // 的 id/name 常为 null，ifBlank 挡不住非空的 "null" → 覆盖正确值 → 工具未找到:null。
                                    id = if (chunk.id.isNullOrBlank() || chunk.id == "null") existing.id else chunk.id,
                                    name = if (chunk.name.isNullOrBlank() || chunk.name == "null") existing.name else chunk.name,
                                    arguments = existing.arguments + chunk.arguments,
                                    extra = chunk.extra ?: existing.extra   // 后到的非空才覆盖，别把已收到的签名抹掉
                                )
                            } else {
                                toolCallMap[idx] = chunk
                            }
                            toolCallMap[idx]?.takeIf { it.name.isNotBlank() }?.let { onToolArgsChunk?.invoke(it.name, it.arguments) }
                        }
                        usage = mergeUsage(usage, u)
                    }
                }
            } finally {
                reader.close()
                response.close()
                activeCall = null
            }

            StreamResult(
                fullContent = fullContent.toString(),
                error = null, httpCode = response.code, errorBody = null, usage = usage,
                // 滤掉只带签名没带 function 的空壳条目（见 parseDelta），别让它变成幽灵工具调用
                toolCalls = toolCallMap.values.filter { it.name.isNotBlank() && it.name != "null" }, finishReason = finishReason,
                // 本轮消息级私有透传字段归档成原始 JSON 串；一个都没收到就是 null（不存不写）。
                extra = ReasoningPassthrough.finish(extraAcc, config.baseUrl)
            ).also { ApiMonitor.record(config.baseUrl, config.model, usage?.promptTokens ?: 0, usage?.completionTokens ?: 0, usage?.totalTokens ?: 0, System.currentTimeMillis() - startTime, success = true, null) }
        } catch (e: Exception) {
            activeCall = null
            val msg = e.message ?: "Unknown error"
            val isCancelled = msg.contains("Canceled", true) || msg.contains("cancel", true)
            // 网络层失败（连不上/超时/流中断）也记进这枚 key 的档案。
            // ⚠ 必须先排掉取消：用户按 STOP 走的也是这条路，把它算成失败的话
            // 「越常按停止、好 key 越容易被禁用」。也别包 runCatching——那会吞掉重抛的 CancellationException。
            if (!isCancelled && lastUsedKey.isNotBlank()) KeyPool.reportNetworkFailure(lastUsedKey, e)
            StreamResult(
                fullContent = "", error = if (isCancelled) "已停止" else msg,
                httpCode = -1, errorBody = e.toString()
            ).also { ApiMonitor.record(config.baseUrl, config.model, 0, 0, 0, System.currentTimeMillis() - startTime, success = false, msg) }
        } finally {
            // 挂钩必须摘：Job 是**整个 sendJob**，一轮工具循环里 streamChat 会被调很多次，
            // 不摘的话每轮都往同一个 Job 上挂一个，长任务下越堆越多（而且它们都持有 client 引用）。
            cancelHook?.dispose()
        }
    }

    /** 深度浅合并：extra 的每个键并入 target；两侧同为 JSONObject 时递归合并，否则 extra 覆盖。 */
    private fun mergeJson(target: JSONObject, extra: JSONObject) {
        val keys = extra.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = extra.get(k)
            val cur = target.opt(k)
            if (v is JSONObject && cur is JSONObject) mergeJson(cur, v) else target.put(k, v)
        }
    }

    // ============================================================
    // 账户余额 / 额度查询（item ②）
    // 对有公开余额端点的 OpenAI 兼容供应商发一次 GET；其余返回「不支持」。
    // 走全局共享 client，用密钥池取一枚 key。仅在配置页手动点按钮时调用。
    // ============================================================
    suspend fun queryBalance(): String = withContext(Dispatchers.IO) {
        val key = if (config.apiKey.isNotBlank()) KeyPool.next(config.apiKey) else ""
        if (key.isBlank()) return@withContext "需要 API Key 才能查询余额"
        val ep = balanceEndpoint(config.baseUrl) ?: return@withContext "该服务商暂不支持余额查询（可在其官网后台查看）"
        val (url, kind) = ep
        try {
            val req = Request.Builder().url(url).get().header("Authorization", "Bearer $key").build()
            client.newCall(req).execute().use { resp ->
                val bodyStr = resp.body?.string() ?: ""
                if (!resp.isSuccessful) return@use "查询失败：HTTP ${resp.code}${if (bodyStr.isNotBlank()) " " + bodyStr.take(120) else ""}"
                parseBalance(kind, bodyStr)
            }
        } catch (e: Exception) {
            "查询失败：${e.message}"
        }
    }

    private fun originOf(baseUrl: String): String = try {
        val u = java.net.URI(baseUrl.trim().trimEnd('/'))
        val port = if (u.port > 0) ":${u.port}" else ""
        "${u.scheme}://${u.host}$port"
    } catch (_: Exception) { baseUrl.trim().trimEnd('/') }

    /** (查询 URL, 解析类型)；无公开端点返回 null。 */
    private fun balanceEndpoint(baseUrl: String): Pair<String, String>? {
        val origin = originOf(baseUrl)
        return when (providerType) {
            ApiProvider.DEEPSEEK -> "$origin/user/balance" to "deepseek"
            ApiProvider.KIMI -> "$origin/v1/users/me/balance" to "moonshot"
            else -> {
                val u = baseUrl.lowercase()
                when {
                    "siliconflow" in u -> "https://api.siliconflow.cn/v1/user/info" to "siliconflow"
                    "openrouter" in u -> "https://openrouter.ai/api/v1/credits" to "openrouter"
                    else -> null
                }
            }
        }
    }

    private fun parseBalance(kind: String, body: String): String = try {
        val j = JSONObject(body)
        when (kind) {
            "deepseek" -> {
                val infos = j.optJSONArray("balance_infos")
                if (infos != null && infos.length() > 0) {
                    val b = infos.getJSONObject(0)
                    "余额 ${b.optString("total_balance")} ${b.optString("currency")}"
                } else "余额信息为空"
            }
            "moonshot" -> {
                val d = j.optJSONObject("data")
                "可用余额 ${d?.opt("available_balance")}"
            }
            "siliconflow" -> {
                val d = j.optJSONObject("data")
                val bal = d?.optString("totalBalance", "") ?: ""
                "余额 ${bal.ifBlank { d?.optString("balance") ?: "?" }}"
            }
            "openrouter" -> {
                val d = j.optJSONObject("data")
                val total = d?.optDouble("total_credits", Double.NaN) ?: Double.NaN
                val used = d?.optDouble("total_usage", Double.NaN) ?: Double.NaN
                if (!total.isNaN() && !used.isNaN()) "剩余额度 %.4f（已用 %.4f）".format(total - used, used)
                else body.take(200)
            }
            else -> body.take(200)
        }
    } catch (_: Exception) { body.take(200) }

    /**
     * 用量按「非零才覆盖」合并，不是后到的整个盖掉前面的。
     *
     * 为什么必须这样：Anthropic 把 input_tokens 放在 `message_start`、output_tokens 放在
     * 流末尾的 `message_delta`，是**两片**。照原来 `if (u != null) usage = u` 的写法，
     * 后一片会把 promptTokens 抹成 0——表现就是「花费统计只算出一半」。
     * OpenAI 那条路一轮只发一次 usage，合并等于覆盖，行为不变。
     */
    private fun mergeUsage(old: Usage?, new: Usage?): Usage? {
        if (new == null) return old
        if (old == null) return new
        val p = if (new.promptTokens > 0) new.promptTokens else old.promptTokens
        val c = if (new.completionTokens > 0) new.completionTokens else old.completionTokens
        val t = when {
            new.totalTokens > 0 -> new.totalTokens
            old.totalTokens > 0 -> old.totalTokens
            else -> p + c   // 两家原生协议都不一定给总数，自己加
        }
        return Usage(p, c, t)
    }

    /** 中性形态 → 本类自己那份 Delta。只是搬字段，顺手把三个 token 数拼成 [Usage]。 */
    private fun ProtoDelta.toDelta(): Delta {
        val u = if (promptTokens != null || completionTokens != null || totalTokens != null)
            Usage(promptTokens ?: 0, completionTokens ?: 0, totalTokens ?: 0) else null
        return Delta(
            reasoning = reasoning,
            content = content,
            usage = u,
            toolCalls = toolCalls.mapValues { (_, c) -> ToolCallChunk(c.id, c.name, c.arguments, c.extra) },
            finishReason = finishReason,
            extra = extra,
        )
    }

    /**
     * 解析一片 SSE 载荷。三种协议在这里分家，[anthropicThinking] / [geminiIndexer]
     * 是各自需要的跨片累积器（另一种协议下那个参数一直是空的，不占事）。
     */
    private fun parseDelta(
        data: String,
    ): Delta = parseOpenAiDelta(data)

    private fun parseOpenAiDelta(data: String): Delta {
        return try {
            val json = JSONObject(data)
            val choices = json.optJSONArray("choices") ?: return Delta(null, null, null, emptyMap(), null)
            if (choices.length() == 0) return Delta(null, null, null, emptyMap(), null)
            val choice = choices.getJSONObject(0)
            val finishReason = choice.optString("finish_reason", "").takeIf { it.isNotBlank() && it != "null" }
            val delta = choice.optJSONObject("delta")
            val rawContent = delta?.optString("content", "")
            // 思考**正文**（给人看的那份）有两个键，只读、不回传：
            //  · `reasoning_content` —— 国内几家（DeepSeek/Kimi/GLM/Qwen）
            //  · `reasoning`         —— 经 OpenRouter 转发时的字段（字符串）。以前只读前者，
            //    于是走 OpenRouter 的推理模型侧栏思考恒为空——不是没回传，是我们没接。
            // 用 opt(...) as? String 取第二个：某些端点会把 `reasoning` 发成对象/数组（结构化思考块），
            // 那是回传凭证不是正文，交给 ReasoningPassthrough，别在这儿当文本拼进去。
            // 两个都有时以 reasoning_content 为准（同一份文本，拼两遍就是重复）。
            val reasoning = delta?.optString("reasoning_content", "")?.takeIf { it.isNotEmpty() && it != "null" }
                ?: (delta?.opt("reasoning") as? String)?.takeIf { it.isNotEmpty() && it != "null" }
            val content = rawContent?.takeIf { it.isNotEmpty() && it != "null" }

            val toolCallDeltas = mutableMapOf<Int, ToolCallChunk>()
            delta?.optJSONArray("tool_calls")?.let { tcs ->
                for (j in 0 until tcs.length()) {
                    val tc = tcs.optJSONObject(j) ?: continue
                    val idx = tc.optInt("index", j)
                    val fn = tc.optJSONObject("function")
                    // 供应商私有扩展原样收下（Gemini 3 的思考签名在这里），别做白名单点名取字段
                    val extra = tc.optJSONObject("extra_content")?.toString()
                    // 只带 extra_content 不带 function 的收尾块不能整块跳过，否则签名丢了下轮 400；
                    // 名字为空的条目在流末尾统一滤掉（见 toolCalls = ...filter），不会造出幽灵工具调用。
                    if (fn == null && extra == null) continue
                    toolCallDeltas[idx] = ToolCallChunk(
                        id = tc.optString("id", ""),
                        name = fn?.optString("name", "") ?: "",
                        arguments = fn?.optString("arguments", "") ?: "",
                        extra = extra
                    )
                }
            }

            val usageJson = json.optJSONObject("usage")
            val usage = if (usageJson != null) {
                Usage(
                    usageJson.optInt("prompt_tokens", 0),
                    usageJson.optInt("completion_tokens", 0),
                    usageJson.optInt("total_tokens", 0)
                )
            } else null
            // 消息级私有扩展原样收下（思考块/签名/加密推理项），和上面 tool_call 级的 extra_content 同一个道理：
            // 白名单里的键收原始值，不点名取子字段、不做规范化。没有就是空 map。
            val msgExtra = delta?.let { ReasoningPassthrough.capture(it) } ?: emptyMap()
            Delta(reasoning, content, usage, toolCallDeltas, finishReason, msgExtra)
        } catch (e: Exception) {
            Delta(null, null, null, emptyMap(), null)
        }
    }

    // 类内定义：引用了嵌套的 Usage/ToolCallChunk，放类外顶层会 Unresolved（Quad 是泛型才没这问题）。
    private data class Delta(
        val reasoning: String?,
        val content: String?,
        val usage: Usage?,
        val toolCalls: Map<Int, ToolCallChunk>,
        val finishReason: String?,
        /** 本片里出现的消息级私有透传字段（原始值，未解析）。放在最后：上面两处解构只取前 5 个，
         *  这一项单独按名字取，不动既有解构写法。 */
        val extra: Map<String, Any> = emptyMap(),
    )
}
