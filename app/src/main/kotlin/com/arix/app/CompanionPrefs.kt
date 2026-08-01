package com.arix.app

import android.content.Context

// ============================================================
// 陪伴包 —— Arix 原生的可选「情感陪伴」模块总闸（不套 Operit .toolpkg 格式，就是个总开关）。
// 默认关 = 核心是纯数字助手。开了才启用：主动消息引擎 / 每日日记 / AI 主动来电 / Waifu 多气泡人格。
// 各陪伴功能在「真正激活/调度」处再 && 本闸；关掉则调度器不跑、来电不触发、Waifu 不生效、相关设置不露出。
// 角色扮演 few-shot/正则属中性「人设创作」，不归此闸。
// ============================================================
object CompanionPrefs {
    private const val PREFS = "xtom_companion"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // enabled = 是否装了陪伴包（不是普通开关；靠导入/下载陪伴包来解锁）
    fun enabled(c: Context): Boolean = packName(c).isNotBlank()
    fun packName(c: Context): String = p(c).getString("pack_name", "") ?: ""
    fun packVersion(c: Context): String = p(c).getString("pack_version", "") ?: ""

    fun install(c: Context, name: String, version: String) =
        p(c).edit().putString("pack_name", name).putString("pack_version", version).apply()
    fun uninstall(c: Context) = p(c).edit().remove("pack_name").remove("pack_version").apply()
}
