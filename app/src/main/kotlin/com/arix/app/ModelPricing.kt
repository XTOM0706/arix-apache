package com.arix.app

/**
 * 模型定价（每百万 token，USD，入价→出价），用于像 OpenCode 那样显示花了多少钱。
 * 按 model 名子串匹配；匹配不到返回 null（不显示花费，只显示 token）。价格会变，可按需调。
 */
object ModelPricing {
    private val table: List<Pair<String, Pair<Double, Double>>> = listOf(
        "deepseek-r" to (0.55 to 2.19),
        "deepseek-v4-flash" to (0.10 to 0.30),
        "deepseek" to (0.27 to 1.10),
        "gpt-4o-mini" to (0.15 to 0.60),
        "gpt-4.1-mini" to (0.40 to 1.60),
        "gpt-4.1" to (2.00 to 8.00),
        "gpt-4o" to (2.50 to 10.00),
        "o1-mini" to (1.10 to 4.40),
        "claude-3-5-haiku" to (0.80 to 4.00),
        "claude-3-5-sonnet" to (3.00 to 15.00),
        "claude" to (3.00 to 15.00),
        "gemini-2.0-flash" to (0.10 to 0.40),
        "gemini-1.5-pro" to (1.25 to 5.00),
        "gemini" to (0.10 to 0.40),
        "moonshot" to (2.00 to 2.00),
        "glm-4-flash" to (0.0 to 0.0),
        "glm" to (0.50 to 0.50),
        "qwen" to (0.0 to 0.0),
        "llama" to (0.0 to 0.0),
        "bge" to (0.0 to 0.0),
    )

    fun priceOf(model: String?): Pair<Double, Double>? {
        val m = model?.lowercase()?.trim() ?: return null
        return table.firstOrNull { m.contains(it.first) }?.second
    }

    /** 花费(USD)；模型未知返回 null。 */
    fun costUsd(model: String?, promptTokens: Int, completionTokens: Int): Double? {
        val (inP, outP) = priceOf(model) ?: return null
        return promptTokens / 1_000_000.0 * inP + completionTokens / 1_000_000.0 * outP
    }

    fun fmt(cost: Double): String =
        if (cost <= 0.0) "免费" else if (cost < 0.01) "$" + "%.4f".format(java.util.Locale.US, cost) else "$" + "%.3f".format(java.util.Locale.US, cost)
}
