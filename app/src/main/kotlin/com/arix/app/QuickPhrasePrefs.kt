package com.arix.app

import android.content.Context

// 快捷短语：聊天输入区一键插入常用语。存为 \n 分隔（短语内不含换行）。
object QuickPhrasePrefs {
    private const val PREFS = "quick_phrases"
    private const val KEY = "phrases"
    private val DEFAULT = listOf("请继续", "总结一下要点", "用更简单的话解释", "换一种说法", "帮我润色这段文字")

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(ctx: Context): List<String> {
        val raw = prefs(ctx).getString(KEY, null) ?: return DEFAULT
        return raw.split("\n").map { it.trim() }.filter { it.isNotBlank() }
    }

    fun set(ctx: Context, list: List<String>) =
        prefs(ctx).edit().putString(KEY, list.joinToString("\n") { it.replace("\n", " ").trim() }.trim()).apply()

    fun add(ctx: Context, phrase: String) {
        val p = phrase.replace("\n", " ").trim()
        if (p.isBlank()) return
        val cur = get(ctx).toMutableList()
        if (!cur.contains(p)) { cur.add(p); set(ctx, cur) }
    }

    fun removeAt(ctx: Context, index: Int) {
        val cur = get(ctx).toMutableList()
        if (index in cur.indices) { cur.removeAt(index); set(ctx, cur) }
    }
}
