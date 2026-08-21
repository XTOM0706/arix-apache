package com.arix.app

import android.content.Context

object ConfigModePrefs {
    private const val PREFS_NAME = "config_mode"
    private const val KEY_IS_PRO = "is_pro_mode"

    fun isProMode(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_IS_PRO, false)
    }

    fun setProMode(context: Context, pro: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_IS_PRO, pro).apply()
    }

    // --- 输入区开关持久化（跨会话记住）---
    private const val KEY_TOOLS = "tools_enabled"
    private const val KEY_THINKING = "thinking_mode"
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun toolsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_TOOLS, true)
    fun setToolsEnabled(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_TOOLS, v).apply()

    fun thinkingMode(context: Context): Int = prefs(context).getInt(KEY_THINKING, 0)
    fun setThinkingMode(context: Context, v: Int) = prefs(context).edit().putInt(KEY_THINKING, v).apply()

    // 顶栏/输入栏滚动自动隐藏（默认开）
    private const val KEY_AUTOHIDE = "auto_hide_bars"
    fun autoHideBars(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTOHIDE, true)
    fun setAutoHideBars(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_AUTOHIDE, v).apply()

    // 按 Enter 发送（默认关：Enter 换行，符合移动端习惯；开后 Enter 发送、Shift+Enter 换行）
    private const val KEY_ENTER_SEND = "enter_to_send"
    fun enterToSend(context: Context): Boolean = prefs(context).getBoolean(KEY_ENTER_SEND, false)
    fun setEnterToSend(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_ENTER_SEND, v).apply()

    // 对话后自动抽取记忆（与内联工具互补；默认关，开了更全但费 token）；每 N 轮抽取一次
    private const val KEY_AUTO_EXTRACT = "auto_extract_memory"
    private const val KEY_AUTO_EXTRACT_EVERY = "auto_extract_every"
    fun autoExtractMemory(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_EXTRACT, false)
    fun setAutoExtractMemory(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_EXTRACT, v).apply()
    fun autoExtractEvery(context: Context): Int = prefs(context).getInt(KEY_AUTO_EXTRACT_EVERY, 2).coerceIn(1, 10)
    fun setAutoExtractEvery(context: Context, v: Int) = prefs(context).edit().putInt(KEY_AUTO_EXTRACT_EVERY, v.coerceIn(1, 10)).apply()

    // 长对话自动压缩上下文（把早期消息滚动摘要成一段，省 token、不撞上下文上限；默认开）
    private const val KEY_CTX_COMPRESS = "context_compress"
    fun contextCompress(context: Context): Boolean = prefs(context).getBoolean(KEY_CTX_COMPRESS, true)
    fun setContextCompress(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_CTX_COMPRESS, v).apply()

    // 设备操作执行方式：true=AI 直接执行(闹钟/提醒自动设好，不用你确认)；false=只帮你打开对应界面、由你自己确认。默认直接。
    private const val KEY_DIRECT_ACTIONS = "direct_device_actions"
    fun directActions(context: Context): Boolean = prefs(context).getBoolean(KEY_DIRECT_ACTIONS, true)
    fun setDirectActions(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_DIRECT_ACTIONS, v).apply()

    // 工具被用户当场拒绝后，直接停止本轮生成（默认关 = 保持原行为：把「用户拒绝了」当成工具结果回给模型，
    // 由它自己换招或作答）。开了则一拒即收——用户拒绝往往就是「别干了」，而不是「换个说法再来一次」。
    // 只认「他本人刚点的拒绝」；策略禁用/无人应答超时不算（见 ToolResult.userDenied）。
    private const val KEY_DENY_STOPS_TURN = "tool_deny_stops_turn"
    fun toolDenyStopsTurn(context: Context): Boolean = prefs(context).getBoolean(KEY_DENY_STOPS_TURN, false)
    fun setToolDenyStopsTurn(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_DENY_STOPS_TURN, v).apply()

    // 「下一句」建议芯片摆哪：true=收在输入胶囊内部（默认，不遮聊天内容、和输入区是一体的）；
    // false=浮在输入胶囊上方（旧行为，芯片更长更好点，但会压住最后一条消息）。
    // 两种各有取舍，做成偏好而不是替代。
    private const val KEY_SUGGESTION_INLINE = "suggestion_inline"
    fun suggestionInline(context: Context): Boolean = prefs(context).getBoolean(KEY_SUGGESTION_INLINE, true)
    fun setSuggestionInline(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_SUGGESTION_INLINE, v).apply()

    // 工具死循环硬停（默认开，可关）。长工作流里反复调同一个工具/同一组工具是正常干活，
    // 但「完全相同的调用（同名同参）连续 5 次」或「同一组 4 连序列在最近 8 步出现 ≥3 次」
    // 是真打转（同参数重来结果不会变）。开着时中断本轮并让模型基于已有结果作答；
    // 关掉则完全不干预——交给用户 STOP 或模型自己判断。
    private const val KEY_STUCK_GUARD = "tool_stuck_guard"
    fun toolStuckGuard(context: Context): Boolean = prefs(context).getBoolean(KEY_STUCK_GUARD, true)
    fun setToolStuckGuard(context: Context, v: Boolean) = prefs(context).edit().putBoolean(KEY_STUCK_GUARD, v).apply()
}
