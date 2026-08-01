package com.arix.tool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
// UserScriptStore —— 用户脚本(油猴式)本地存储
// open_page 用 WebView 打开网页时，把「匹配当前网址」的用户脚本在页面加载后注入执行。
// 存储：单条 SharedPreferences 记录一个 JSON 数组，纯本地、不外发。
// 匹配：glob(通配 * ?，整串匹配) 或 regex(正则，命中即算)。
// 安全：向任意网页注入用户 JS 属高危能力——默认不预置任何脚本，全部由用户自行添加/开启。
// ============================================================
data class UserScript(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val matchType: String,   // "glob" | "regex"
    val pattern: String,
    val code: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("enabled", enabled)
        put("matchType", matchType)
        put("pattern", pattern)
        put("code", code)
    }

    /** 该脚本是否匹配给定网址。glob 走整串匹配(用 * / ? 覆盖)，regex 走命中即算。 */
    fun matches(url: String): Boolean = try {
        if (pattern.isBlank()) false
        else if (matchType == "regex") Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(url)
        else globToRegex(pattern).matches(url)
    } catch (_: Exception) { false }

    companion object {
        fun fromJson(o: JSONObject): UserScript = UserScript(
            id = o.optString("id"),
            name = o.optString("name"),
            enabled = o.optBoolean("enabled", true),
            matchType = o.optString("matchType", "glob"),
            pattern = o.optString("pattern"),
            code = o.optString("code")
        )

        // 通配符 → 正则：* = 任意串，? = 任意单字符，其余元字符转义；整串(matchEntire)匹配，大小写不敏感。
        private fun globToRegex(glob: String): Regex {
            val sb = StringBuilder()
            for (c in glob) when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                else -> if (c.isLetterOrDigit()) sb.append(c) else sb.append('\\').append(c)
            }
            return Regex(sb.toString(), RegexOption.IGNORE_CASE)
        }
    }
}

object UserScriptStore {
    private const val PREFS = "user_script_prefs"
    private const val KEY = "scripts"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAll(c: Context): List<UserScript> = try {
        val arr = JSONArray(p(c).getString(KEY, "[]") ?: "[]")
        (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i)?.let { UserScript.fromJson(it) } }
    } catch (_: Exception) { emptyList() }

    fun saveAll(c: Context, list: List<UserScript>) {
        val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
        p(c).edit().putString(KEY, arr.toString()).apply()
    }

    /** 新增或按 id 覆盖更新。 */
    fun upsert(c: Context, s: UserScript) {
        val list = getAll(c).toMutableList()
        val idx = list.indexOfFirst { it.id == s.id }
        if (idx >= 0) list[idx] = s else list.add(s)
        saveAll(c, list)
    }

    fun delete(c: Context, id: String) = saveAll(c, getAll(c).filterNot { it.id == id })

    fun newId(): String = "us_" + System.currentTimeMillis() + "_" + (0..9999).random()

    /** 当前网址匹配到的、已启用的脚本(按存储顺序)。 */
    fun matching(c: Context, url: String): List<UserScript> =
        getAll(c).filter { it.enabled && it.matches(url) }

    /**
     * 生成注入用 JS：把每个匹配脚本各自包进 try/catch 的 IIFE(彼此隔离、一个报错不影响其它与后续取正文)。
     * 没有匹配脚本时返回 null，调用方即跳过注入。
     */
    fun injectionJs(c: Context, url: String): String? {
        val scripts = matching(c, url)
        if (scripts.isEmpty()) return null
        return scripts.joinToString("\n") { ";(function(){try{\n${it.code}\n}catch(e){}})();" }
    }
}
