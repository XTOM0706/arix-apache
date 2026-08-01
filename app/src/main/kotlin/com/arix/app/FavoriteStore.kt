package com.arix.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 消息收藏（借鉴 RikkaHub/Kelivo 的「收藏」）。轻量：SharedPreferences 存一个 JSON 数组，
 * 收藏夹页面读它。按文本去重、最多 200 条、时间倒序。
 */
object FavoriteStore {
    private const val PREF = "xtom_favorites"
    private const val KEY = "list"
    private const val CAP = 200

    data class Fav(val text: String, val role: String, val ts: Long)

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun add(c: Context, text: String, role: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        val cur = list(c).filter { it.text != t }                 // 去重
        // ts 既是删除依据又是列表 key：同毫秒连加会撞车（LazyColumn 重复 key 崩、remove 连删两条），保证唯一
        var ts = System.currentTimeMillis()
        val used = cur.mapTo(HashSet()) { it.ts }
        while (ts in used) ts++
        val next = (listOf(Fav(t, role, ts)) + cur).take(CAP)
        save(c, next)
        return true
    }

    fun list(c: Context): List<Fav> = try {
        val arr = JSONArray(prefs(c).getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let { Fav(it.optString("text"), it.optString("role", "assistant"), it.optLong("ts")) }
        }
    } catch (_: Exception) { emptyList() }

    fun remove(c: Context, ts: Long) = save(c, list(c).filter { it.ts != ts })
    fun clear(c: Context) = prefs(c).edit().remove(KEY).apply()

    private fun save(c: Context, items: List<Fav>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().apply { put("text", it.text); put("role", it.role); put("ts", it.ts) }) }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }
}
