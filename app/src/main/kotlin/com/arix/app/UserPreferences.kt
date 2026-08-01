package com.arix.app

import android.content.Context

object UserPreferences {
    private const val PREFS = "user_prefs"

    fun getTone(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("tone", "") ?: ""
    fun setTone(ctx: Context, v: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("tone", v).apply()

    fun getLength(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("length", "") ?: ""
    fun setLength(ctx: Context, v: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("length", v).apply()

    fun getLanguage(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("language", "") ?: ""
    fun setLanguage(ctx: Context, v: String) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("language", v).apply()

    fun buildPrompt(ctx: Context): String {
        val parts = mutableListOf<String>()
        val tone = getTone(ctx); if (tone.isNotBlank()) parts.add("语气风格：$tone")
        val len = getLength(ctx); if (len.isNotBlank()) parts.add("回复长度：$len")
        val lang = getLanguage(ctx); if (lang.isNotBlank()) parts.add("语言习惯：$lang")
        return if (parts.isNotEmpty()) "【用户偏好设置】\n${parts.joinToString("\n")}\n\n" else ""
    }
}
