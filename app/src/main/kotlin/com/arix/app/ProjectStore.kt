package com.arix.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 「项目」(GPT 式 Projects)：把相关对话归组成一个命名项目，并可给项目一条**项目指令**
 * （项目内所有对话发送时自动带上，见 ChatScreen performSend 注入）。
 *
 * 归属复用 [com.arix.data.entity.ConversationEntity.folder]（项目名==文件夹名，不新增 DB 列）：
 * 项目内的对话 = folder 等于项目名的对话。ProjectStore 只额外存「项目名→指令」这层元数据。
 * 因此「文件夹」与「项目」是同一套分组，项目 = 文件夹 + 一条指令 + 一个管理入口。
 */
object ProjectStore {
    private const val PREF = "xtom_projects"
    private const val KEY = "list"

    data class Project(val name: String, val instruction: String, val createdAt: Long)

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun list(c: Context): List<Project> = try {
        val arr = JSONArray(prefs(c).getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i ->
            arr.optJSONObject(i)?.let {
                val n = it.optString("name").trim()
                if (n.isBlank()) null else Project(n, it.optString("instruction"), it.optLong("createdAt"))
            }
        }
    } catch (_: Exception) { emptyList() }

    fun get(c: Context, name: String): Project? = list(c).firstOrNull { it.name == name.trim() }
    fun instructionFor(c: Context, name: String): String = get(c, name)?.instruction?.trim().orEmpty()

    /** 新建或更新项目（名字为键；已存在则改指令、保留 createdAt）。名字空白忽略。 */
    fun upsert(c: Context, name: String, instruction: String, createdAt: Long) {
        val n = name.trim(); if (n.isBlank()) return
        val cur = list(c).filter { it.name != n }
        val born = get(c, n)?.createdAt ?: createdAt
        save(c, (cur + Project(n, instruction.trim(), born)).sortedByDescending { it.createdAt })
    }

    /** 改名：更新 store 里的条目（成员对话的 folder 由调用方一并迁移）。 */
    fun rename(c: Context, old: String, new: String) {
        val o = old.trim(); val nw = new.trim(); if (nw.isBlank() || o == nw) return
        val p = get(c, o) ?: return
        save(c, list(c).filter { it.name != o && it.name != nw } + p.copy(name = nw))
    }

    fun delete(c: Context, name: String) = save(c, list(c).filter { it.name != name.trim() })

    private fun save(c: Context, items: List<Project>) {
        val arr = JSONArray()
        items.forEach { arr.put(JSONObject().apply { put("name", it.name); put("instruction", it.instruction); put("createdAt", it.createdAt) }) }
        prefs(c).edit().putString(KEY, arr.toString()).apply()
    }
}
