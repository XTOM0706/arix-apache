package com.arix.cloudapi

import java.util.Locale

/**
 * 这个端点讲的是**哪一种**聊天协议。
 *
 * Apache-2.0 精简版：只保留 OpenAI `/chat/completions` 协议（所有兼容端点、自建服务都走它）。
 * Anthropic / Gemini 原生协议已移除（那两家是第三方闭源云服务，不属于「自建可控端点」）。
 * 想接本地/自建的 llama.cpp / Ollama / vLLM 等，一律走 OpenAI 兼容。
 */
enum class ChatProtocol(val displayName: String) {
    /** OpenAI `/chat/completions`。所有兼容端点走这条，也是缺省。 */
    OPENAI("OpenAI 兼容");

    companion object {
        fun detect(baseUrl: String): ChatProtocol = OPENAI

        /** 这个配置最终讲哪种协议：用户显式指定优先，否则按域名认。 */
        fun of(baseUrl: String, model: String): ChatProtocol =
            ApiExtrasStore.protocolOverride(baseUrl, model) ?: detect(baseUrl)

        fun fromString(s: String?): ChatProtocol? =
            values().firstOrNull { it.name.equals(s, true) }
    }
}

/**
 * 一片流式增量，**与协议无关**的中性形态。
 *
 * 为什么不直接复用 `CloudApiClient.Delta`：那个是 private nested，且引用了同样嵌套的
 * `Usage`/`ToolCallChunk`；顶层类型引用类内嵌套类型在这项目里踩过编译坑。所以协议解析器
 * 统一吐这个中性结构，由 [CloudApiClient] 转成它自己那份——转换只有几行，
 * 换来的是三家解析各自独立、互不影响，且 `CloudApiClient.Usage` 的外部引用（3 处）一个不用改。
 */
data class ProtoDelta(
    /** 思考**正文**（给人看的那份）。 */
    val reasoning: String? = null,
    /** 回答正文。 */
    val content: String? = null,
    /** 用量三元组，没有就 null。 */
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    /** 本片里的工具调用增量，按块序号归并（语义与 OpenAI 的 `tool_calls[].index` 一致）。 */
    val toolCalls: Map<Int, ProtoToolCall> = emptyMap(),
    /** OpenAI 口径的终止原因（stop / tool_calls / length / content_filter）。各协议自己映射过来。 */
    val finishReason: String? = null,
    /**
     * 本片里出现的、**必须原样回传**的供应商私有结构（Anthropic 的 thinking 块+签名、
     * Gemini 的 thoughtSignature）。协议自己定键名，自己在构造请求体时写回去——
     * 这一层只负责搬运，不理解内容。
     */
    val extra: Map<String, Any> = emptyMap(),
)

/** 工具调用增量的中性形态。[arguments] 是**片段**，由上层按序号拼接。 */
data class ProtoToolCall(
    val id: String = "",
    val name: String = "",
    val arguments: String = "",
    /** 这一项的私有透传槽（原始 JSON 串），原样收原样回传。 */
    val extra: String? = null,
)
