package com.arix.app

import android.content.Context

// ============================================================
// IdentityPrefs —— 用户/AI 的名称与头像（仿 RikkaHub）
// 存 SharedPreferences，不动 Room 表（铁律：不重构业务/DB）。
// - 用户名称、用户头像：全局一份
// - AI 头像：按角色卡 id 存（AI 名称 = 角色卡名，已在卡数据里，不重复存）
// 头像值是图片的 content:// / file:// URI 字符串，Coil 直接加载。
// ============================================================

object IdentityPrefs {
    private const val PREFS = "identity_prefs"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun userName(c: Context): String = p(c).getString("user_name", "") ?: ""
    fun setUserName(c: Context, v: String) = p(c).edit().putString("user_name", v).apply()

    fun userAvatar(c: Context): String? = p(c).getString("user_avatar", null)
    fun setUserAvatar(c: Context, uri: String?) = p(c).edit().putString("user_avatar", uri).apply()

    fun aiAvatar(c: Context, cardId: Long): String? = p(c).getString("ai_avatar_$cardId", null)
    fun setAiAvatar(c: Context, cardId: Long, uri: String?) = p(c).edit().putString("ai_avatar_$cardId", uri).apply()
}
