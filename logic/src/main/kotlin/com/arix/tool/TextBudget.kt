package com.arix.tool

/**
 * 文本的 token 估算与按预算取用。
 *
 * 口径与 `tools/token_cost.py` 保持一致（中文 1.2 字符/token、其余 4 字符/token）——不是真 tokenize，
 * 但同一把尺子量下来横向可比，足够回答「这段东西塞不塞得进去」这个唯一要回答的问题。
 *
 * 存在的理由：以前处处写 `take(8000)` 这种拍脑袋的字符上限。**字符数不是成本，token 才是**：
 * 8000 个汉字 ≈ 6700 token，8000 个字符的英文日志才 2000 token，同一个数字对两者的意义差三倍。
 * 更要紧的是，「多大」应该决定「怎么读」（整份给 / 给一段 / 只给目录让它自己搜），
 * 而不是一律砍到同一刀口——砍完模型不知道自己少看了什么，只会当作看全了。
 */
object TextBudget {

    /** 粗估 token 数。宁可高估（高估只是保守一点，低估会撑爆上下文）。 */
    fun estimateTokens(s: String): Int {
        if (s.isEmpty()) return 0
        var cjk = 0
        for (ch in s) {
            val c = ch.code
            // CJK 统一表意文字 + 扩展A + 兼容 + 全角标点/假名/谚文：这些一个字≈一个 token 量级
            if (c in 0x2E80..0x9FFF || c in 0xA960..0xA97F || c in 0xAC00..0xD7FF ||
                c in 0xF900..0xFAFF || c in 0xFE30..0xFE4F || c in 0xFF00..0xFFEF) cjk++
        }
        val other = s.length - cjk
        return (cjk / 1.2 + other / 4.0).toInt() + 1
    }

    /** 按 token 预算截取开头，并**退到行边界**（半行代码/半行日志对模型没用，还容易被当成完整内容）。 */
    fun takeByTokens(s: String, tokens: Int): String {
        if (estimateTokens(s) <= tokens) return s
        val perChar = estimateTokens(s).toDouble() / s.length      // 这段文本自己的平均密度
        val chars = (tokens / perChar).toInt().coerceIn(1, s.length)
        val cut = s.take(chars)
        val nl = cut.lastIndexOf('\n')
        return if (nl > chars / 2) cut.substring(0, nl) else cut
    }

    /**
     * 结构预览：不给正文时，给模型一份「这文件长什么样」——
     * Markdown 取标题层级、CSV 取表头、其余取前几行。让它知道该往哪儿搜，而不是盲问。
     */
    fun outline(text: String, name: String, maxLines: Int = 30): String {
        val lines = text.lineSequence().take(4000).toList()
        val lower = name.lowercase()
        return when {
            lower.endsWith(".md") || lower.endsWith(".markdown") -> {
                val heads = lines.filter { it.trimStart().startsWith("#") }.take(maxLines)
                if (heads.isNotEmpty()) "标题结构：\n" + heads.joinToString("\n") else lines.take(maxLines).joinToString("\n")
            }
            lower.endsWith(".csv") || lower.endsWith(".tsv") ->
                "表头与前几行：\n" + lines.take(minOf(maxLines, 6)).joinToString("\n")
            else -> "开头 ${minOf(maxLines, lines.size)} 行：\n" + lines.take(maxLines).joinToString("\n")
        }
    }
}
