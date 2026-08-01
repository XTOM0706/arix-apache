package com.arix.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ============================================================
// GitHub 账号（全局登录）—— 一处登录，备份 / 市场 / gh 工具都用这个 token（还能把 API 限流从 60/时抬到 5000/时）。
// 现阶段：PAT 登录（粘贴 token → 调 /user 验证 → 存本机 + 显示身份）。之后可加 OAuth 设备流（用户给 client_id 后）。
// token 只存本机 prefs；纯 HttpURLConnection，无第三方依赖。
// ============================================================
object GitHubAccount {
    private const val PREFS = "xtom_github"
    private const val API = "https://api.github.com"

    data class Account(val token: String, val login: String, val avatarUrl: String, val name: String)

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 全局 token（未登录=空）。备份/市场/工具统一读它。 */
    fun token(c: Context): String = prefs(c).getString("token", "") ?: ""
    fun isLoggedIn(c: Context): Boolean = token(c).isNotBlank()

    fun account(c: Context): Account? {
        val t = token(c); if (t.isBlank()) return null
        val p = prefs(c)
        return Account(t, p.getString("login", "") ?: "", p.getString("avatar", "") ?: "", p.getString("name", "") ?: "")
    }

    /** PAT 登录：验证 token 并抓取身份。成功存本机并返回 Account；失败返回带原因的 Result。 */
    suspend fun login(c: Context, rawToken: String): Result<Account> = withContext(Dispatchers.IO) {
        val token = rawToken.trim()
        if (token.isBlank()) return@withContext Result.failure(IllegalArgumentException("token 为空"))
        runCatching {
            val conn = (URL("$API/user").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Authorization", "Bearer $token")
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                setRequestProperty("User-Agent", "Arix")
                connectTimeout = 15000; readTimeout = 15000
            }
            val code = conn.responseCode
            if (code == 401) throw IllegalStateException("token 无效或已过期（401）")
            if (code != 200) throw IllegalStateException("GitHub 返回 $code：" + (conn.errorStream?.bufferedReader()?.readText()?.take(120) ?: ""))
            val o = JSONObject(conn.inputStream.bufferedReader().readText())
            val acc = Account(token, o.optString("login"), o.optString("avatar_url"), o.optString("name").ifBlank { o.optString("login") })
            prefs(c).edit()
                .putString("token", token).putString("login", acc.login)
                .putString("avatar", acc.avatarUrl).putString("name", acc.name).apply()
            acc
        }
    }

    fun logout(c: Context) {
        prefs(c).edit().remove("token").remove("login").remove("avatar").remove("name").apply()
    }
}
