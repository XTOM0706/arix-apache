package com.arix.app

import android.content.Context

// ============================================================
// 角色卡的**工具范围**（per-card，落 SharedPreferences，不动 DB）。
//
// 为什么要有：工具表是按「功能包开没开」全局算的，一张陪伴卡照样带着 shell/文件/工作流的 schema——
// 既白烧 token，又让模型在聊天里动不动想去跑命令（工具面越大幻觉越多）。竞品里 rikkahub 默认只暴露
// 1 个工具、kelivo 0 个，且都是挂在 assistant 上的。这里把「干活 / 陪伴双形态」真正落成实现。
//
// 存的是**排除表**不是允许表：以后新增功能包时，老卡自动带上新能力；存允许表的话新包对所有老卡永远隐身。
// 键按 cardId，参照 CardRoleplayStore / WorldTreeStore / TtsTool.cardVoicePref 的既有做法。
// ============================================================
object CardToolStore {
    private const val PREFS = "card_tools"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 这张卡**不带**的功能包 id。空集 = 不限制（默认，行为与从前一致）。 */
    fun excluded(c: Context, cardId: Long?): Set<String> {
        if (cardId == null) return emptySet()
        val raw = p(c).getString("off_$cardId", "") ?: ""
        return if (raw.isBlank()) emptySet() else raw.split(',').map { it.trim() }.filter { it.isNotBlank() }.toSet()
    }

    fun setExcluded(c: Context, cardId: Long, ids: Set<String>) {
        p(c).edit().putString("off_$cardId", ids.filter { it.isNotBlank() }.sorted().joinToString(",")).apply()
    }

    fun clear(c: Context, cardId: Long) = p(c).edit().remove("off_$cardId").apply()

    /**
     * 两个预设：**干活**与**陪伴**。给的是「这个形态要排除掉的包」。
     *
     * 只列内置包 id（见 createBuiltInPackages）；用户自己装的市场包/插件不在预设里，
     * 由他手动勾——我们没法替他判断一个第三方包属于哪个形态。
     */
    val WORK_EXCLUDES: Set<String> = setOf(
        "diary", "media", "health_measure", "worldbook", "social_share",
    )
    val COMPANION_EXCLUDES: Set<String> = setOf(
        "shell", "local_linux", "code_runner", "file_tools", "file_converter", "csv_export",
        "workflow", "sub_agent", "http_tools", "net_diag", "net_log", "web_server", "mcp_server",
        "settings", "ui_control", "skill", "github", "plugin_creator", "browser", "site_cookies",
        // send_intent 能拉起设备上任何 App 的任何组件——陪伴卡不该有这个。
        // 它虽然是 DEBUGGER 级（默认要问用户），但陪伴场景里连"问"都是多余的打扰。
        "intent",
    )
}
