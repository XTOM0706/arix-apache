package com.arix.app

import android.content.Context

/**
 * 助手人设 —— Apache-2.0 精简版。
 *
 * 角色卡体系移除后，用这个全局偏好保存一句话「人设/说话风格」，
 * 向导的「选角色」预设写入，聊天页的 characterSetting 从这里读。
 */
object AssistantRolePrefs {
    private const val PREF = "arix_assistant_role"
    private const val K_SETTING = "character_setting"

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun characterSetting(c: Context): String = p(c).getString(K_SETTING, "") ?: ""
    fun setCharacterSetting(c: Context, v: String) = p(c).edit().putString(K_SETTING, v.trim()).apply()
}
