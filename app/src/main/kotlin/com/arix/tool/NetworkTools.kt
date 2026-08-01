package com.arix.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

// ============================================================
// 网络诊断箱 net_diag —— ping(连通/延迟)/dns(解析)/http(响应头状态)/port(端口开放)。排查网络问题。
// 纯 Kotlin 原生(Socket/InetAddress/HttpURLConnection)，无需 root/shell、无第三方代码。
// 注：Android 无特权拿不到真 ICMP，"ping" 用 TCP 连接测连通+延迟（更贴近"能不能连上/多快"）。
// ============================================================
class NetDiagTool : Tool {
    override val name = "net_diag"
    override val description = "网络诊断：ping=测连通与延迟(TCP)，dns=解析域名到 IP，http=看响应状态与关键头，port=测端口是否开放。排查连不上/慢/被墙等问题。"

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("ping", "dns", "http", "port")))
                put("description", "ping=连通/延迟, dns=域名解析, http=响应头/状态, port=端口开放测试")
            })
            put("target", JSONObject().apply { put("type", "string"); put("description", "主机名/域名(ping/dns/port)或完整 URL(http)") })
            put("port", JSONObject().apply { put("type", "integer"); put("description", "端口号(action=port 必填；ping 可选，默认 443)") })
        })
        put("required", JSONArray(listOf("action", "target")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = params.optString("action")
        val target = params.optString("target").trim()
        if (target.isBlank()) return@withContext ToolResult("请提供 target", isError = true)
        try {
            when (action) {
                "dns" -> {
                    val host = hostOf(target)
                    val addrs = InetAddress.getAllByName(host)
                    ToolResult("🔍 DNS 解析 $host：\n" + addrs.joinToString("\n") { "  ${it.hostAddress}" })
                }
                "ping" -> {
                    val host = hostOf(target)
                    val port = params.optInt("port", 443)
                    val times = mutableListOf<Long>()
                    var fail = 0
                    val ip = try { InetAddress.getByName(host).hostAddress } catch (_: Exception) { host }
                    repeat(4) {
                        val t = tcpConnectMs(host, port, 3000)
                        if (t < 0) fail++ else times.add(t)
                    }
                    if (times.isEmpty()) ToolResult("📡 $host ($ip) :$port — 4/4 失败（不通/端口关/被墙）", isError = true)
                    else ToolResult("📡 $host ($ip) :$port（TCP 连通测试）\n" +
                        "  成功 ${times.size}/4，丢失 $fail\n" +
                        "  延迟 min ${times.min()}ms / avg ${times.average().toLong()}ms / max ${times.max()}ms")
                }
                "port" -> {
                    val host = hostOf(target)
                    val port = params.optInt("port", -1)
                    if (port !in 1..65535) return@withContext ToolResult("action=port 需要有效 port(1-65535)", isError = true)
                    val t = tcpConnectMs(host, port, 4000)
                    if (t < 0) ToolResult("🚪 $host:$port — 关闭/不可达", isError = true)
                    else ToolResult("🚪 $host:$port — 开放（${t}ms 连上）")
                }
                "http" -> {
                    val url = if (target.startsWith("http://") || target.startsWith("https://")) target else "https://$target"
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 8000; conn.readTimeout = 8000
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("User-Agent", "Arix-NetDiag/1.0")
                    val start = System.currentTimeMillis()
                    val code = conn.responseCode
                    val ms = System.currentTimeMillis() - start
                    val keys = listOf("Server", "Content-Type", "Content-Length", "Location", "Cache-Control", "CF-RAY", "Via", "X-Powered-By")
                    val headers = keys.mapNotNull { k -> conn.getHeaderField(k)?.let { "  $k: $it" } }
                    conn.disconnect()
                    ToolResult("🌐 $url\n  HTTP $code ${conn.responseMessage ?: ""}  (${ms}ms)\n" + headers.joinToString("\n"))
                }
                else -> ToolResult("未知 action：$action", isError = true)
            }
        } catch (e: java.net.UnknownHostException) {
            ToolResult("解析失败：域名不存在或 DNS 不通（$target）", isError = true)
        } catch (e: Exception) {
            ToolResult("诊断失败：${e.message}", isError = true)
        }
    }

    private fun hostOf(s: String): String = try {
        if (s.startsWith("http://") || s.startsWith("https://")) java.net.URI(s).host ?: s else s.substringBefore('/').substringBefore(':')
    } catch (_: Exception) { s }

    private fun tcpConnectMs(host: String, port: Int, timeout: Int): Long {
        return try {
            val start = System.currentTimeMillis()
            Socket().use { it.connect(InetSocketAddress(host, port), timeout) }
            System.currentTimeMillis() - start
        } catch (_: Exception) { -1 }
    }
}

// ============================================================
// GitHub 工具 —— 官方 REST API：搜仓库/看仓库/issues/releases/trending/用户。匿名有限流(60/h)，可选 token 提额。
// 纯 HTTP 调 api.github.com，无第三方代码。
// ============================================================
class GithubTool : Tool {
    override val name = "github"
    override val description = "GitHub 查询：search_repos=搜仓库, repo=仓库详情, issues=问题列表, releases=版本, trending=近期热门, user=用户信息。匿名可用(限流)，可传 token 提额。"

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("search_repos", "repo", "issues", "releases", "trending", "user")))
                put("description", "要做的操作")
            })
            put("query", JSONObject().apply { put("type", "string"); put("description", "search_repos/trending 的关键词；user 的用户名") })
            put("repo", JSONObject().apply { put("type", "string"); put("description", "owner/name 形式，如 rikkahub/rikkahub（repo/issues/releases 用）") })
            put("language", JSONObject().apply { put("type", "string"); put("description", "trending 可选：限定语言，如 kotlin") })
            put("token", JSONObject().apply { put("type", "string"); put("description", "可选 GitHub token，提高限额") })
        })
        put("required", JSONArray(listOf("action")))
    }

    private val API = "https://api.github.com"

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = params.optString("action")
        val token = params.optString("token", "").trim()
        try {
            when (action) {
                "search_repos" -> {
                    val q = params.optString("query").trim()
                    if (q.isBlank()) return@withContext ToolResult("请提供 query", isError = true)
                    val o = getJson("$API/search/repositories?q=${enc(q)}&sort=stars&order=desc&per_page=10", token)
                    val items = o?.optJSONArray("items") ?: return@withContext ToolResult("搜索失败或无结果", isError = true)
                    ToolResult("🔎 GitHub 仓库「$q」Top ${items.length()}：\n\n" + (0 until items.length()).joinToString("\n") { i ->
                        val r = items.optJSONObject(i) ?: return@joinToString ""
                        "★${r.optInt("stargazers_count")}  ${r.optString("full_name")}\n   ${r.optString("description").take(120)}\n   ${r.optString("html_url")}"
                    })
                }
                "repo" -> {
                    val repo = params.optString("repo").trim()
                    if (!repo.contains("/")) return@withContext ToolResult("repo 需 owner/name 形式", isError = true)
                    val r = getJson("$API/repos/$repo", token) ?: return@withContext ToolResult("未找到仓库 $repo", isError = true)
                    ToolResult(buildString {
                        append("📦 ${r.optString("full_name")}  ★${r.optInt("stargazers_count")}  🍴${r.optInt("forks_count")}\n")
                        append("${r.optString("description")}\n")
                        r.optString("language").takeIf { it.isNotBlank() }?.let { append("语言: $it  ") }
                        append("issues: ${r.optInt("open_issues_count")}\n")
                        append("更新: ${r.optString("updated_at").take(10)}  主页: ${r.optString("html_url")}\n")
                        r.optString("homepage").takeIf { it.isNotBlank() && it != "null" }?.let { append("站点: $it") }
                    })
                }
                "issues" -> {
                    val repo = params.optString("repo").trim()
                    if (!repo.contains("/")) return@withContext ToolResult("repo 需 owner/name 形式", isError = true)
                    val arr = getArray("$API/repos/$repo/issues?state=open&per_page=10&sort=updated", token)
                        ?: return@withContext ToolResult("获取 issues 失败", isError = true)
                    if (arr.length() == 0) return@withContext ToolResult("$repo 暂无开放 issue")
                    ToolResult("🐛 $repo 开放 issues：\n\n" + (0 until arr.length()).joinToString("\n") { i ->
                        val it = arr.optJSONObject(i) ?: return@joinToString ""
                        "#${it.optInt("number")} ${it.optString("title").take(100)}  💬${it.optInt("comments")}"
                    })
                }
                "releases" -> {
                    val repo = params.optString("repo").trim()
                    if (!repo.contains("/")) return@withContext ToolResult("repo 需 owner/name 形式", isError = true)
                    val arr = getArray("$API/repos/$repo/releases?per_page=5", token)
                        ?: return@withContext ToolResult("获取 releases 失败", isError = true)
                    if (arr.length() == 0) return@withContext ToolResult("$repo 暂无 release")
                    ToolResult("🚀 $repo 最近版本：\n\n" + (0 until arr.length()).joinToString("\n\n") { i ->
                        val it = arr.optJSONObject(i) ?: return@joinToString ""
                        "${it.optString("tag_name")} · ${it.optString("published_at").take(10)}\n${it.optString("body").take(200)}"
                    })
                }
                "trending" -> {
                    // GitHub 无官方 trending API：近 7 天新建、按 star 排序近似
                    val since = java.time.LocalDate.now().minusDays(7).toString()
                    val lang = params.optString("language").trim()
                    val q = "created:>$since" + (if (lang.isNotBlank()) " language:$lang" else "") + (params.optString("query").trim().let { if (it.isNotBlank()) " $it" else "" })
                    val o = getJson("$API/search/repositories?q=${enc(q)}&sort=stars&order=desc&per_page=10", token)
                    val items = o?.optJSONArray("items") ?: return@withContext ToolResult("获取 trending 失败", isError = true)
                    ToolResult("📈 近 7 天热门${if (lang.isNotBlank()) "($lang)" else ""}：\n\n" + (0 until items.length()).joinToString("\n") { i ->
                        val r = items.optJSONObject(i) ?: return@joinToString ""
                        "★${r.optInt("stargazers_count")}  ${r.optString("full_name")}\n   ${r.optString("description").take(100)}"
                    })
                }
                "user" -> {
                    val u = params.optString("query").trim().ifBlank { params.optString("repo").substringBefore('/') }
                    if (u.isBlank()) return@withContext ToolResult("请提供 query=用户名", isError = true)
                    val r = getJson("$API/users/$u", token) ?: return@withContext ToolResult("未找到用户 $u", isError = true)
                    ToolResult("👤 ${r.optString("login")}${r.optString("name").takeIf { it.isNotBlank() && it != "null" }?.let { " ($it)" } ?: ""}\n" +
                        "${r.optString("bio").takeIf { it != "null" } ?: ""}\n" +
                        "仓库 ${r.optInt("public_repos")}  关注者 ${r.optInt("followers")}  ${r.optString("html_url")}")
                }
                else -> ToolResult("未知 action：$action", isError = true)
            }
        } catch (e: Exception) {
            ToolResult("GitHub 请求失败：${e.message}", isError = true)
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getBody(url: String, token: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "Arix/1.0")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer $token")
        if (c.responseCode !in 200..299) { c.disconnect(); null }
        else { val s = c.inputStream.bufferedReader().use { it.readText() }; c.disconnect(); s }
    } catch (_: Exception) { null }

    private fun getJson(url: String, token: String): JSONObject? = getBody(url, token)?.let { try { JSONObject(it) } catch (_: Exception) { null } }
    private fun getArray(url: String, token: String): JSONArray? = getBody(url, token)?.let { try { JSONArray(it) } catch (_: Exception) { null } }
}

// ============================================================
// site_cookies —— 读取你在「设置→站点登录」登录过的网站的本地 cookie（含登录态 token），
// 供调用该站需鉴权的接口时复用（配合 http_request/open_page）。只读你指定的域名，未登录则空。
// cookie 存在全局 WebView CookieManager（SiteLoginPage 登录时写入）。默认关、属敏感(含登录凭据)。
// ============================================================
class SiteCookiesTool : Tool {
    override val name = "site_cookies"
    override val description = "读取你已在『站点登录』登录过的网站的 cookie（含登录 token），用于调用该站需登录的接口。传 domain（如 weibo.com / zhihu.com）；没登录过则返回空。⚠含登录凭据，谨慎使用。"
    override val permissionLevel = AndroidPermissionLevel.DEBUGGER   // 含登录凭据，默认询问

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("domain", JSONObject().apply { put("type", "string"); put("description", "域名，如 weibo.com、zhihu.com、bilibili.com（也可传完整 URL）") })
        })
        put("required", JSONArray(listOf("domain")))
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val raw = params.optString("domain").trim()
        if (raw.isBlank()) return ToolResult("请提供 domain", isError = true)
        val url = if (raw.startsWith("http")) raw else "https://$raw"
        val host = try { java.net.URI(url).host ?: raw } catch (_: Exception) { raw }
        return try {
            val cookie = android.webkit.CookieManager.getInstance().getCookie(url)
            if (cookie.isNullOrBlank())
                ToolResult("未找到 $host 的 cookie —— 你可能还没登录过。去『设置→站点登录』登录一次再试。")
            else {
                // 标出疑似 token/会话字段，方便按需取用
                val names = cookie.split(";").mapNotNull { it.substringBefore('=').trim().takeIf { n -> n.isNotBlank() } }
                val tokenLike = names.filter { it.lowercase().let { l -> "token" in l || "session" in l || "auth" in l || "sid" in l || "sso" in l || "sub" in l } }
                ToolResult(buildString {
                    append("🔑 $host 的 cookie（含登录态，勿外泄）：\n").append(cookie)
                    if (tokenLike.isNotEmpty()) append("\n\n疑似登录/会话字段：").append(tokenLike.joinToString(", "))
                })
            }
        } catch (e: Exception) {
            ToolResult("读取 cookie 失败：${e.message}", isError = true)
        }
    }
}
