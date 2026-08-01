package com.arix.cloudapi

import java.util.Locale

/**
 * 这个端点讲的是**哪一种**聊天协议。
 *
 * 在这之前全 App 只会讲一种：OpenAI 的 `/chat/completions`。Anthropic 与 Google 各自都提供了
 * 一层 OpenAI 兼容端点，所以「能用」一直不是问题；问题是兼容层是各家自己按最小公倍数做的，
 * 结果是一批**只有原生协议才有**的东西在兼容层上拿不到或会出错：
 *  · Anthropic：扩展思考的 `thinking` 块与它的 `signature`（开了思考又要调工具时，
 *    上一轮的思考块必须原样带回去，否则整轮被拒）、`cache_control` 提示缓存、
 *    100 万上下文的 beta 头、`tool_result` 里的图片。
 *  · Gemini：`thoughtSignature`（Gemini 3 系列多轮工具调用不带就 400——我们此前是靠
 *    在 OpenAI 兼容层里抓 `extra_content` 硬接的，见 ReasoningPassthrough）、
 *    `systemInstruction`、`thinkingBudget`、内置搜索/代码执行工具。
 *
 * 判定优先级：**用户显式指定 > 按 baseUrl 认**。自动判定只认官方域名，因为它必须在
 * 没有 Context、没读过任何配置的情况下也答得出来（冷启动第一句话就要用），
 * 而中转站/自建代理的域名是猜不出来的——那种情况让用户在配置页里直说。
 *
 * ⚠ 加新协议要同时补三处：[detect]、[CloudApiClient] 里的三个 when 分支
 * （URL / 请求头 / 请求体+解析），以及配置页那个下拉。少补一处的表现是「选了没反应」。
 */
enum class ChatProtocol(val displayName: String) {
    /** OpenAI `/chat/completions`。所有兼容端点走这条，也是缺省。 */
    OPENAI("OpenAI 兼容"),

    /** Anthropic `/v1/messages`（原生）。 */
    ANTHROPIC("Anthropic 原生"),

    /** Google Gemini `:streamGenerateContent`（原生）。 */
    GEMINI("Gemini 原生");

    companion object {
        /**
         * 按 baseUrl 认协议。只认官方域名——**别往这里加中转站的关键字**：
         * 中转站的域名千奇百怪且随时变，认错的代价是整个请求发错格式、一句话都回不了。
         * 中转站请让用户在配置页显式选（[of] 会优先读那个）。
         *
         * 注意 Anthropic 与 Google 各自的 OpenAI 兼容路径要**排除掉**：
         *  · `api.anthropic.com/v1/openai/...`（Anthropic 的 OpenAI 兼容层）
         *  · `generativelanguage.googleapis.com/v1beta/openai/...`（Gemini 的 OpenAI 兼容层）
         * 用户特意填了兼容路径就是要走兼容层，按域名把他改判成原生等于替他做了个他没要的决定。
         */
        fun detect(baseUrl: String): ChatProtocol {
            val url = baseUrl.lowercase(Locale.ROOT)
            if ("/openai" in url) return OPENAI          // 两家的兼容层都在 /openai 段下
            return when {
                "api.anthropic.com" in url -> ANTHROPIC
                "generativelanguage.googleapis.com" in url -> GEMINI
                else -> OPENAI
            }
        }

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
