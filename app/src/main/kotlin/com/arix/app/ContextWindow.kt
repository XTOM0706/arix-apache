package com.arix.app

import android.content.Context
import com.arix.cloudapi.model.ChatMessage
import com.arix.tool.ContextWindowDefaults
import com.arix.tool.TextBudget

/**
 * 上下文窗口感知：每个模型一个可配的 token 上限 + 发送前的用量估算。
 *
 * 窗口大小怎么来：常见模型按名字关键字猜（[ContextWindowDefaults.guess]），用户可在设置里
 * 按模型名手动覆盖（存 SharedPreferences，键按模型名归一化）。token 数复用项目已有的
 * [TextBudget.estimateTokens]（中文≈1字/token 口径），不重新发明一套估算器。
 *
 * 只读用量、不做任何截断/压缩——那是 [ContextCompressor] 的职责，这里只负责「知道离上限还有多远」，
 * 供聊天页显示进度、也供 [ContextCompressor.maybeCompress] 判断是否该提前触发压缩。
 */
object ContextWindowPrefs {
    private const val PREFS = "xtom_context_window"
    private const val KEY_PREFIX = "win_"

    /** 逼近上限的告警占比，与 [ContextWindowDefaults.WARN_RATIO] 保持同一口径。 */
    const val WARN_RATIO = ContextWindowDefaults.WARN_RATIO

    private fun norm(model: String) = model.trim().lowercase()
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 这个模型配的窗口大小（token）：用户覆盖过就用覆盖值，否则按名字猜。 */
    fun windowFor(c: Context, model: String): Int {
        val key = norm(model)
        if (key.isEmpty()) return ContextWindowDefaults.FALLBACK
        val override = prefs(c).getInt(KEY_PREFIX + key, -1)
        return if (override > 0) override else ContextWindowDefaults.guess(key)
    }

    /** 用户手动设置某个模型的窗口大小。tokens<=0 表示清除覆盖、回退到按名字猜的值。 */
    fun setWindow(c: Context, model: String, tokens: Int) {
        val key = norm(model)
        if (key.isEmpty()) return
        val e = prefs(c).edit()
        if (tokens > 0) e.putInt(KEY_PREFIX + key, tokens) else e.remove(KEY_PREFIX + key)
        e.apply()
    }

    /** 这个模型的窗口是否被用户手动覆盖过（设置页用来区分「默认猜测」和「你改过」）。 */
    fun isOverridden(c: Context, model: String): Boolean {
        val key = norm(model)
        return key.isNotEmpty() && prefs(c).contains(KEY_PREFIX + key)
    }

    /** 已用 / 上限的一份可读快照，供聊天页渲染进度、也供触发压缩的判据复用。 */
    data class Usage(
        val estimatedTokens: Int,
        val windowTokens: Int,
        val ratio: Float,
        val nearLimit: Boolean,
    )

    /**
     * 估算「这一轮实际会发出去的」token 数。
     *
     * ⚠ 口径必须传**已经过 [ContextCompressor.forSend] 处理的那份**（摘要替换/旧工具结果裁剪/
     * 老图驱逐/条数上限都算在内），不是原始完整历史——否则这个数字和真正发出去的对不上，
     * 进度条会撒谎。图片走 base64 不计入（那不是按字符线性计费的，硬算会离谱地虚高）。
     */
    fun estimate(c: Context, model: String, sentMsgs: List<ChatMessage>, systemPrompt: String? = null): Usage {
        var tokens = TextBudget.estimateTokens(systemPrompt.orEmpty())
        for (m in sentMsgs) {
            tokens += TextBudget.estimateTokens(m.content)
            m.toolCalls?.forEach { tokens += TextBudget.estimateTokens(it.arguments) }
        }
        val window = windowFor(c, model)
        val ratio = ContextWindowDefaults.ratio(tokens, window)
        return Usage(tokens, window, ratio, ContextWindowDefaults.nearLimit(ratio, WARN_RATIO))
    }

    /** 给 UI 用的紧凑文案，如 "12.3k / 128k"。 */
    fun formatUsage(u: Usage): String =
        "${ContextWindowDefaults.formatTokens(u.estimatedTokens)} / ${ContextWindowDefaults.formatTokens(u.windowTokens)}"
}
