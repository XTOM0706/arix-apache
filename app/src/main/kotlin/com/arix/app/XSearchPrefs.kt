package com.arix.app

import android.content.Context

// ============================================================
// XSearchPrefs —— 深度研究(XSEARCHING) 的偏好
// researchConfigId：做子查询扩展/结果综合用的模型；0 = 跟随当前激活的对话模型。
// ============================================================
object XSearchPrefs {
    private const val PREFS = "xsearch_prefs"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun researchConfigId(c: Context): Long = p(c).getLong("research_config_id", 0L)
    fun setResearchConfigId(c: Context, id: Long) = p(c).edit().putLong("research_config_id", id).apply()

    fun defaultRounds(c: Context): Int = p(c).getInt("default_rounds", 3)
    fun setDefaultRounds(c: Context, n: Int) = p(c).edit().putInt("default_rounds", n.coerceIn(1, 5)).apply()
}
