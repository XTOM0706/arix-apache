package com.arix.cloudapi

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * 「思考链 / 签名」**消息级**原样透传。
 *
 * 起因：Gemini 3 的思考签名藏在 tool_call 的 `extra_content` 里，不原样带回下一轮就 400
 * （"Function call is missing a thought_signature"）。那条路已经修好（见 CloudApiClient
 * 里 ToolCallChunk.extra 的收/写）。但**签名并不只挂在 tool_call 上**：另有一类供应商把
 * 「思考块 + 签名」挂在 assistant 消息自己身上（经 OpenRouter 转发的 Anthropic 思考模型最典型），
 * 同样要求多轮里整块回传，否则续不上思考、或直接报签名校验失败。
 *
 * ChatMessage 早有的 `reasoning: String?` 帮不上忙——那是**给人看的文本**，
 * 签名/加密块在它之外，拼回去也不是原来的结构。所以另开一个和 ToolCallMsg.extra 同形状的
 * 原始 JSON 槽：`ChatMessage.extra`。
 *
 * ## 设计原则：只做「不丢」，不做「理解」
 * 这些字段是各家私有的，形状不一样、还会变，我们不可能穷举。因此这里**不解析语义、不规范化、
 * 不做供应商映射表**，只做三件事：
 *   1. 收：服务器在 delta 里给了 [PASSTHROUGH_KEYS] 中的键，就把**原始值**收下；没给=什么都不存。
 *   2. 拼：SSE 会把同一个思考块切成多片，按块自带的 index 归并（见 [mergeArray]）。
 *   3. 写：下一轮把这些键**原样**写回 assistant 消息。老数据没有该键 = 不写 = 与从前行为完全一致。
 * 键名白名单只收**确有依据**的那几个（下面逐条注明来源），拿不准的宁可不收——
 * 发一个人家不认识的顶层字段，代价是整个请求 400、一句话都回不了（这个项目被 `thinking`
 * 参数以同样方式打过一次，见 ApiProvider.supportsThinkingParam 的说明）。
 *
 * ## 为什么还要记一个端点标记
 * 本 App 支持随时切模型配置接着聊。若上一轮是 A 家产的私有字段，下一轮换到 B 家，
 * 把 A 的字段发给 B 就是「发人家没有的字段」——正是上面那类 400 的成因。
 * 所以存的时候顺手记下产出它的端点主机名（[META_ENDPOINT]，写回时剥掉、绝不发给服务器），
 * 换了家就静默跳过。没有这个标记的（老数据/外部导入）按宽松处理，照发。
 */
object ReasoningPassthrough {

    /**
     * 我们自己的元数据键：产出这份私有数据的端点主机名。下划线前缀避免撞上供应商的真字段，
     * 写回时会被剥掉，**不会出现在请求体里**。
     */
    private const val META_ENDPOINT = "_xtom_ep"

    /**
     * 单条消息透传数据的体量上限。会话是整条 messagesJson 存进 Room 的，这个项目刚被
     * CursorWindow 2MB 崩过一次（列表 SELECT * 直接打不开 App）。思考块里可能带着完整
     * 思维链正文，几轮下来能把行撑爆。超限就整个丢掉：最坏结果是这一轮上游拒收签名（看得见、能重试），
     * 比把会话行撑到打不开整个 App 轻得多。
     */
    private const val MAX_CHARS = 128 * 1024

    /**
     * 「必须原样回传」的消息级字段名白名单。只列有依据的：
     *  · `reasoning_details` —— OpenRouter 的统一思考载体，官方明确要求在多轮/工具调用中把它
     *    带回去，才能保住上游（尤其 Anthropic 思考模型）的签名块。它内部按 type 区分
     *    text/summary/encrypted，我们一律不看内容。
     *  · `thinking_blocks`   —— 一些 OpenAI 兼容代理层（LiteLLM 一类）承载 Anthropic
     *    thinking / redacted_thinking 块（自带 signature / data）的字段名，同样要求回传。
     *  · `extra_content`     —— Gemini 兼容层的私有扩展槽在消息级的对应物（tool_call 级那份
     *    已经在 CloudApiClient 里单独处理）。收到才写，没收到就不写。
     *  · `reasoning_content` —— DeepSeek（含 opencode.ai 托管的 deepseek-v4-flash-free）的思考
     *    正文。⚠ 2026-08 实测：**新版 DeepSeek 要求把它回传**，不回传直接 400
     *    （"The reasoning_content in the thinking mode must be passed back to the API"）。
     *    早期 DeepSeek 曾规定「放进输入会 400」，那是旧版本行为；现在以实测为准回传。
     *    它同时作为「给人看的文本」进 ChatMessage.reasoning，与透传槽各存各的，互不冲突。
     *    ⚠ 回传只对**同一家端点**生效（writeBack 按 META_ENDPOINT 校验主机名），
     *    换到别的供应商不会把 DeepSeek 的字段发过去。
     */
    private val PASSTHROUGH_KEYS = setOf("reasoning_details", "thinking_blocks", "extra_content", "reasoning_content")

    /**
     * 会被 SSE 切碎、需要拼接的文本类字段名。除此之外的字符串（id/signature/data/format/type…）
     * 都当作「一次到达的完整标量」，后到的非空值覆盖——拼接它们会直接毁掉签名。
     * `reasoning_content` 是 DeepSeek 的思考正文，流式会切成多片，必须拼接成完整才回传。
     */
    private val TEXT_KEYS = setOf("text", "summary", "thinking", "reasoning", "content", "reasoning_content")

    /** 从一个 delta 里挑出白名单字段的**原始值**。绝大多数分片一个都没有，早退不产生任何分配。 */
    fun capture(delta: JSONObject): Map<String, Any> {
        if (PASSTHROUGH_KEYS.none { delta.has(it) }) return emptyMap()
        val out = LinkedHashMap<String, Any>()
        for (k in PASSTHROUGH_KEYS) {
            val v = delta.opt(k) ?: continue
            if (v === JSONObject.NULL) continue
            if (v is JSONArray && v.length() == 0) continue          // 空数组没有信息量，别占地方
            out[k] = v
        }
        return out
    }

    /** 把新分片并进累积器。同名键按类型归并，其余后来者覆盖。 */
    fun merge(acc: MutableMap<String, Any>, incoming: Map<String, Any>) {
        if (incoming.isEmpty()) return
        for ((k, v) in incoming) {
            val old = acc[k]
            acc[k] = when {
                old is JSONArray && v is JSONArray -> mergeArray(old, v)
                old is JSONObject && v is JSONObject -> mergeObject(old, v)
                else -> v
            }
        }
    }

    /**
     * 归档成可持久化的原始 JSON 串（附带端点标记）。没内容或超限返回 null——
     * null 的语义就是「不写该键」，与从前行为完全一致。
     */
    fun finish(acc: Map<String, Any>, baseUrl: String): String? {
        if (acc.isEmpty()) return null
        return try {
            val o = JSONObject()
            acc.forEach { (k, v) -> o.put(k, v) }
            o.put(META_ENDPOINT, endpointTag(baseUrl))
            val s = o.toString()
            if (s.length > MAX_CHARS) null else s
        } catch (_: Exception) {
            null                                                     // 脏数据只丢这一项，不影响本轮结果
        }
    }

    /**
     * 把存下来的私有字段原样写回请求里的这条消息。
     * 只写目标对象**还没有**的键：绝不让透传数据覆盖我们自己拼的 role/content/tool_calls。
     * 任何异常都只跳过这一项——回传失败最多是上游少了点上下文，炸掉整个请求则是一句话都回不了。
     */
    fun writeBack(target: JSONObject, raw: String, baseUrl: String) {
        try {
            val o = JSONObject(raw)
            val ep = o.optString(META_ENDPOINT, "")
            if (ep.isNotEmpty() && ep != endpointTag(baseUrl)) return  // 换供应商了，别把上家的私有字段发给下家
            val keys = o.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                if (k == META_ENDPOINT) continue                       // 我们自己的标记，不外发
                if (target.has(k)) continue
                val v = o.opt(k) ?: continue
                target.put(k, v)
            }
        } catch (_: Exception) {}
    }

    /** 端点指纹：只取主机名。同一家换模型仍算同一端点（签名照发），换家才拦。 */
    private fun endpointTag(baseUrl: String): String = try {
        java.net.URI(baseUrl.trim().trimEnd('/')).host?.lowercase(Locale.ROOT).orEmpty()
    } catch (_: Exception) {
        ""
    }

    /**
     * 数组归并：思考块在流里是按 `index` 标识的，同 index 的分片属于同一块，要合成一块；
     * 没有 index 的退化成按下标对齐。**不是**简单首尾相接——那样会得到一串半截块，
     * 回传出去比不回传更糟。
     */
    private fun mergeArray(old: JSONArray, new: JSONArray): JSONArray {
        val byIndex = LinkedHashMap<String, JSONObject>()
        val plain = mutableListOf<Any>()                            // 非对象元素（没见过，但不能丢）
        fun absorb(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val item = arr.opt(i) ?: continue
                if (item !is JSONObject) { plain.add(item); continue }
                val key = if (item.has("index")) "i" + item.opt("index") else "p$i"
                val cur = byIndex[key]
                byIndex[key] = if (cur == null) item else mergeObject(cur, item)
            }
        }
        absorb(old); absorb(new)
        val out = JSONArray()
        byIndex.values.forEach { out.put(it) }
        plain.forEach { out.put(it) }
        return out
    }

    /** 对象归并：文本类字段拼接（被 SSE 切碎的），其余后到的非空值覆盖，嵌套结构递归。 */
    private fun mergeObject(target: JSONObject, src: JSONObject): JSONObject {
        val keys = src.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = src.opt(k) ?: continue
            val old = target.opt(k)
            when {
                old == null || old === JSONObject.NULL -> target.put(k, v)
                old is String && v is String ->
                    if (k in TEXT_KEYS) target.put(k, old + v)
                    else if (v.isNotEmpty()) target.put(k, v)
                old is JSONObject && v is JSONObject -> mergeObject(old, v)
                old is JSONArray && v is JSONArray -> target.put(k, mergeArray(old, v))
                else -> target.put(k, v)
            }
        }
        return target
    }
}
