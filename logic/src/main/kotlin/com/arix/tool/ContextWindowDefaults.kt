package com.arix.tool

/**
 * 上下文窗口大小的默认猜测表 + 占比计算。纯字符串/数字运算，无 Android 依赖。
 *
 * 存在的理由：以前发请求前完全不知道自己塞了多少 token、离模型的上下文上限还有多远——
 * 撞上限只能等接口报错。这里给「猜多大」和「算占比」两件事一个可测的落点；
 * 真正的 token 估算复用 [TextBudget.estimateTokens]，这里不重新发明。
 *
 * 猜测表按模型名关键字匹配，不追求逐版本精确（服务商经常不声不响地把同名模型窗口调大），
 * 够用来做「离上限还有多远」的进度条和触发压缩的判据；用户可在设置里按模型名手动覆盖。
 */
object ContextWindowDefaults {

    /** 一个都猜不出时的保守默认——多数国产/自建模型的常见基线。 */
    const val FALLBACK = 32_000

    /** 逼近上限的告警占比：到这个比例就该提醒用户、也是触发已有压缩逻辑的判据之一。 */
    const val WARN_RATIO = 0.85f

    // 顺序即优先级：越靠前越先匹配，把更具体的关键字写在前面，避免被短的通用词提前吃掉
    // （比如 "gpt-4.1" 得排在 "gpt-4" 前面，否则永远匹配到 "gpt-4" 那条 8k 的窄窗口）。
    private val RULES: List<Pair<String, Int>> = listOf(
        "gpt-4.1" to 1_000_000,
        "gpt-4o" to 128_000,
        "gpt-4-turbo" to 128_000,
        "gpt-4" to 8_192,
        "gpt-3.5" to 16_000,
        "o1" to 128_000,
        "o3" to 128_000,
        "gemini-2" to 1_000_000,
        "gemini-1.5" to 1_000_000,
        "gemini" to 1_000_000,
        "claude-3-5" to 200_000,
        "claude-3.5" to 200_000,
        "claude-3-7" to 200_000,
        "claude-opus-4" to 200_000,
        "claude-sonnet-4" to 200_000,
        "claude" to 200_000,
        "deepseek" to 64_000,
        "qwen2.5" to 131_072,
        "qwen" to 32_000,
        "glm-4" to 128_000,
        "glm" to 32_000,
        "moonshot" to 128_000,
        "kimi" to 128_000,
        "llama-3.1" to 128_000,
        "llama-3.2" to 128_000,
        "llama" to 8_192,
        "mistral" to 32_000,
        "mixtral" to 32_000,
        "grok" to 128_000,
        "yi-" to 200_000,
        "ernie" to 128_000,
        "hunyuan" to 256_000,
        "doubao" to 128_000,
        "spark" to 128_000,
    )

    /** 按模型名（大小写不敏感、掐首尾空白）猜窗口大小；一个都不命中时回退 [FALLBACK]。 */
    fun guess(model: String): Int {
        val m = model.trim().lowercase()
        if (m.isEmpty()) return FALLBACK
        for ((key, size) in RULES) if (m.contains(key)) return size
        return FALLBACK
    }

    /** 已用 / 窗口，limit<=0（未知窗口）时按 0 算，避免除零。 */
    fun ratio(used: Int, limit: Int): Float =
        if (limit <= 0) 0f else used.toFloat() / limit

    /** 占比是否已经逼近告警线（默认 [WARN_RATIO]）。 */
    fun nearLimit(ratio: Float, threshold: Float = WARN_RATIO): Boolean = ratio >= threshold

    /** 给 UI 用的紧凑数字文案，如 "12.3k"/"1.0M"；不带单位换算之外的语义，具体怎么呈现由调用方决定。 */
    fun formatTokens(n: Int): String = when {
        n >= 1_000_000 -> String.format("%.1fM", n / 1_000_000.0)
        n >= 1_000 -> String.format("%.1fk", n / 1_000.0)
        else -> n.toString()
    }
}
