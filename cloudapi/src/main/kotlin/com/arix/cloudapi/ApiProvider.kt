package com.arix.cloudapi

import java.util.Locale

/**
 * 统一的供应商识别。此前 CloudApiClient（思考参数分支）与 ApiMonitor（统计展示名）
 * 各自维护一份 baseUrl 关键字表，新增供应商要改两处且容易漂移。这里合并为单一来源。
 *
 * 判定顺序保持与原 CloudApiClient 一致（openai 在 groq 之前）——注意 Groq 的
 * OpenAI 兼容端点是 `api.groq.com/openai/v1`（字符串含 "openai"），因此会被识别为
 * OPENAI，这是既有行为，故意保留，勿改动顺序。
 */
enum class ApiProvider(val displayName: String) {
    DEEPSEEK("DeepSeek"),
    KIMI("Kimi"),
    GLM("GLM"),
    QWEN("Qwen"),
    MISTRAL("Mistral"),
    OPENAI("OpenAI"),
    GROQ("Groq"),
    XAI("xAI"),
    OPENROUTER("OpenRouter"),
    OTHER("");

    companion object {
        fun detect(baseUrl: String): ApiProvider {
            val url = baseUrl.lowercase(Locale.ROOT)
            return when {
                // OpenRouter 必须排在最前：它的 model 名长得像 "deepseek/deepseek-chat"、
                // "openai/gpt-4o"，baseUrl 又是聚合站——按厂商关键字匹配会把它误判成上游厂商，
                // 于是吃到一堆上游专有的思考字段。（它此前是靠"哪个关键字都不含"落进 OTHER 才侥幸没事的。）
                "openrouter" in url -> OPENROUTER
                "deepseek" in url -> DEEPSEEK
                "moonshot" in url || "kimi" in url -> KIMI
                "bigmodel" in url || "zhipu" in url -> GLM
                "aliyuncs" in url || "dashscope" in url -> QWEN
                "mistral" in url -> MISTRAL
                "openai" in url -> OPENAI
                "groq" in url && "x.ai" !in url -> GROQ
                "x.ai" in url -> XAI
                else -> OTHER
            }
        }

        /**
         * 这个 provider×model 到底认不认思考字段。
         *
         * 之所以要按**模型名**而不只按厂商判：思考字段是各家给**特定模型**加的，不是厂商级开关。
         * 早先的代码在 `when(provider)` 里对每个认得出的厂商**无条件**下发 `thinking`/`enable_thinking`
         * （连关掉思考时也发），结果 DeepSeek/Kimi/GLM/Qwen 全线 400 —— 而 OpenRouter 因为
         * 压根没有 detect 分支、落进 OTHER 被排除在注入之外，成了唯一还能用的那个。
         * 判反了方向：不是别家太严，是我们发了人家没有的字段。
         *
         * 原则：**只在确认支持时才发**。拿不准就不发——不发最多是思考行为没按预期开关，
         * 发错则是整个请求 400、一句话都回不了。
         */
        fun supportsThinkingParam(provider: ApiProvider, model: String): Boolean {
            val m = model.lowercase(Locale.ROOT)
            return when (provider) {
                // GLM：thinking:{type} 是 GLM-4.5 起的新参数；GLM-4/4-Flash 等旧模型不认
                GLM -> Regex("""glm-(4\.[5-9]|[5-9])""").containsMatchIn(m) || "glm-z1" in m
                // Qwen：enable_thinking 只有 qwen3 系混合推理模型接受；qwen-plus/max/turbo 旧模型会 400
                QWEN -> m.startsWith("qwen3") || "qwen3-" in m
                // Kimi：思考由模型名承载（kimi-thinking-preview / k2-thinking），非思考模型不认这个字段
                KIMI -> "thinking" in m
                // OpenAI：reasoning_effort 只有 o 系 / gpt-5 系推理模型收；发给 gpt-4o/4.1 是 400
                OPENAI -> Regex("""(^|/)(o[1-9]|gpt-5)""").containsMatchIn(m)
                // Mistral：只有 magistral 系推理模型
                MISTRAL -> "magistral" in m
                // DeepSeek：思考由模型名承载（deepseek-reasoner），OpenAI 兼容端点没有 thinking 字段。
                // 它走严格反序列化，未知字段直接 400 —— 这正是「DeepSeek 一直爆 400」的根因。
                DEEPSEEK -> false
                // 聚合/未知：字段透传给上游，我们无从担保上游认不认，一律不发
                OPENROUTER, GROQ, XAI, OTHER -> false
            }
        }
    }
}
