package com.arix.marketwatch

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

// ============================================================
// 市场/竞品监控 —— 独立小 App。WebView 承载与电脑版 competitor_watch.py --html 一致的仪表盘。
// 拉数据(GitHub API + Operit 市场 CDN) → 拼 HTML → loadDataWithBaseURL。刷新走 JS 桥 Android.refresh()。
// ============================================================
class MainActivity : Activity() {

    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true          // 搜索过滤 + 刷新桥需要
            settings.domStorageEnabled = true
            addJavascriptInterface(Bridge(), "Android")
            // 站内点仓库/包链接 → 用系统浏览器打开，别让本 WebView 导航到远端(否则 Android 桥暴露给远端页)
            webViewClient = object : android.webkit.WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, req: android.webkit.WebResourceRequest): Boolean {
                    val u = req.url ?: return false
                    if (u.scheme == "http" || u.scheme == "https") {
                        try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                        return true
                    }
                    return false
                }
            }
        }
        setContentView(web)
        load()
    }

    inner class Bridge {
        @JavascriptInterface fun refresh() { runOnUiThread { load() } }
    }

    private fun load() {
        web.loadDataWithBaseURL(null, page(LOADING), "text/html", "UTF-8", null)
        thread {
            val body = try { buildBody() } catch (e: Exception) {
                "<div class=\"err\" style=\"padding:20px\">加载失败：${esc(e.message ?: e.toString())}</div>"
            }
            runOnUiThread { if (!isFinishing && !isDestroyed) web.loadDataWithBaseURL(null, page(body), "text/html", "UTF-8", null) }
        }
    }

    // —— 数据抓取 ——
    private fun httpJson(url: String): JSONObject? = httpText(url)?.let {
        try { JSONObject(it) } catch (_: Exception) { null }
    }

    private fun httpText(url: String): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 12000; c.readTimeout = 15000
        c.setRequestProperty("User-Agent", "XtomMarketWatch/1.0")
        c.setRequestProperty("Accept", "application/json")
        if (c.responseCode !in 200..299) { c.disconnect(); null }
        else { val s = c.inputStream.bufferedReader().use { it.readText() }; c.disconnect(); s }
    } catch (_: Exception) { null }

    private fun httpArray(url: String): org.json.JSONArray? = httpText(url)?.let {
        try { org.json.JSONArray(it) } catch (_: Exception) { null }
    }

    // —— 组装页面主体 ——
    private fun buildBody(): String {
        val sb = StringBuilder()
        sb.append("<h1>市场 &amp; 竞品监控 <button class=\"rf\" onclick=\"Android.refresh()\">↻ 刷新</button></h1>")
        sb.append("<div class=\"sub\">数据来自 GitHub API 与 Operit 市场 · 点右上刷新拉最新</div>")

        // 竞品仓库
        sb.append("<h2>竞品仓库</h2><div class=\"grid\">")
        for (r in REPOS) sb.append(repoCard(r[0], r[1], r[2]))
        sb.append("</div>")

        // 全部云端包
        sb.append(packageBrowser())
        return sb.toString()
    }

    private fun repoCard(name: String, repo: String, note: String): String {
        val sb = StringBuilder()
        sb.append("<div class=\"card\"><div class=\"chead\">")
        sb.append("<a class=\"cname\" href=\"https://github.com/$repo\" target=\"_blank\">${esc(name)}</a></div>")
        sb.append("<div class=\"note\">${esc(note)} · <span class=\"repo\">${esc(repo)}</span></div>")
        val rel = httpJson("https://api.github.com/repos/$repo/releases/latest")
        val tag = rel?.optString("tag_name").orEmpty()
        if (tag.isNotBlank()) {
            val url = rel?.optString("html_url").orEmpty()
            val date = rel?.optString("published_at").orEmpty().take(10)
            sb.append("<div class=\"rel\">最新版本 <a href=\"${esc(url)}\" target=\"_blank\">${esc(tag)}</a> · ${esc(date)}</div>")
        }
        val commits = httpArray("https://api.github.com/repos/$repo/commits?per_page=8")
        if (commits != null && commits.length() > 0) {
            sb.append("<ul class=\"commits\">")
            for (i in 0 until minOf(commits.length(), 8)) {
                val c = commits.optJSONObject(i) ?: continue
                val commit = c.optJSONObject("commit")
                val msg = (commit?.optString("message") ?: "").substringBefore("\n").trim()
                val d = commit?.optJSONObject("author")?.optString("date")?.take(10).orEmpty()
                sb.append("<li><span class=\"d\">${esc(d)}</span> ${esc(msg)}</li>")
            }
            sb.append("</ul>")
        } else {
            sb.append("<div class=\"muted\">拉取失败或无提交（GitHub 匿名限流？稍后刷新）</div>")
        }
        sb.append("</div>")
        return sb.toString()
    }

    private fun packageBrowser(): String {
        data class Pkg(val name: String, val type: String, val version: String, val downloads: Int,
                       val author: String, val desc: String, val url: String, val featured: Boolean)
        val byType = LinkedHashMap<String, MutableList<Pkg>>()
        MARKET_TYPES.forEach { byType[it] = mutableListOf() }
        var total = 0
        for (t in MARKET_TYPES) {
            var page = 1; var totalPages = 1; val seen = HashSet<String>()
            while (page <= minOf(60, totalPages)) {
                val data = httpJson("$MARKET_BASE/rank/$t-downloads-page-$page.json") ?: break
                totalPages = data.optInt("totalPages", 1)
                val items = data.optJSONArray("items") ?: break
                if (items.length() == 0) break
                for (i in 0 until items.length()) {
                    val it = items.optJSONObject(i) ?: continue
                    val meta = it.optJSONObject("metadata")
                    val id = it.optString("id").ifBlank { meta?.optString("projectId").orEmpty() }
                    if (id.isBlank() || !seen.add(id)) continue
                    byType[t]!!.add(Pkg(
                        name = it.optString("displayTitle").ifBlank { id },
                        type = meta?.optString("type").orEmpty().ifBlank { t },
                        version = meta?.optString("version").orEmpty().ifBlank { "latest" },
                        downloads = it.optInt("downloads", 0),
                        author = it.optString("authorLogin").ifBlank { meta?.optString("publisherLogin").orEmpty() },
                        desc = it.optString("summaryDescription").trim(),
                        url = it.optJSONObject("issue")?.optString("html_url").orEmpty(),
                        featured = it.optBoolean("featured", false),
                    ))
                    total++
                }
                page++
            }
            byType[t]!!.sortByDescending { it.downloads }
        }
        val sb = StringBuilder()
        sb.append("<h2>全部云端包 · <span id=\"cnt\">$total</span> <input id=\"q\" placeholder=\"搜索 包名/说明/作者…\" oninput=\"filt()\"></h2>")
        sb.append("<div id=\"pkgs\">")
        for (t in MARKET_TYPES) {
            val list = byType[t]!!
            if (list.isEmpty()) continue
            sb.append("<div class=\"pgroup\"><h3>$t · ${list.size}</h3>")
            for (p in list) {
                val s = "${p.name} ${p.desc} ${p.author}".lowercase()
                val star = if (p.featured) "⭐ " else ""
                val name = if (p.url.isNotBlank()) "<a href=\"${esc(p.url)}\" target=\"_blank\">${esc(p.name)}</a>" else esc(p.name)
                val desc = if (p.desc.isNotBlank()) "<div class=\"pk-d\">${esc(p.desc)}</div>" else ""
                sb.append("<div class=\"pkg\" data-s=\"${esc(s)}\"><div class=\"pk-h\"><span class=\"tag t-${esc(p.type)}\">${esc(p.type)}</span> $star$name <span class=\"v\">${esc(p.version)}</span> <span class=\"dl\">↓${p.downloads}</span> <span class=\"muted\">@${esc(p.author)}</span></div>$desc</div>")
            }
            sb.append("</div>")
        }
        sb.append("</div>")
        sb.append(SEARCH_JS)
        return sb.toString()
    }

    private fun esc(s: String?): String = (s ?: "")
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // 包上整页骨架(CSS)
    private fun page(body: String): String = "<!doctype html><html lang=\"zh\"><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"><style>$CSS</style></head><body>$body</body></html>"

    companion object {
        private const val MARKET_BASE = "https://static.operit.app/market-stats"
        private val MARKET_TYPES = listOf("script", "package", "skill", "mcp")
        private val REPOS = listOf(
            listOf("Operit AI", "AAswordman/Operit", "功能超集 / 云市场母体"),
            listOf("RikkaHub", "rikkahub/rikkahub", "聊天客户端标杆"),
            listOf("Kelivo", "Chevey339/kelivo", "Flutter 跨平台"),
            listOf("Hermes", "SelectXn00b/HermesApp", "Operit 外壳 + Agent Loop"),
            listOf("橘瓣 OrangeChat", "sue1231513/orangechat", "RikkaHub fork + 陪伴层"),
        )
        private const val LOADING =
            "<div style=\"padding:40px;text-align:center;color:#888\"><h2 style=\"border:0\">加载中…</h2>" +
            "<div>正在拉取竞品仓库与全部云端包（约 1000+ 个，翻页需十几秒，请稍候）</div></div>"
        private const val SEARCH_JS =
            "<script>function filt(){var q=document.getElementById('q').value.toLowerCase();var n=0;" +
            "document.querySelectorAll('.pkg').forEach(function(e){var m=e.getAttribute('data-s').indexOf(q)>=0;" +
            "e.style.display=m?'':'none';if(m)n++;});document.getElementById('cnt').textContent=n;" +
            "document.querySelectorAll('.pgroup').forEach(function(g){var any=g.querySelectorAll('.pkg:not([style*=\"none\"])').length;g.style.display=any?'':'none';});}</script>"
        private const val CSS = """
:root{--bg:#f6f7f9;--fg:#1b1c1e;--mut:#6b7280;--card:#fff;--line:#e5e7eb;--acc:#2563eb;--new:#16a34a;--upd:#d97706}
@media(prefers-color-scheme:dark){:root{--bg:#0f1113;--fg:#e6e7ea;--mut:#9aa0a6;--card:#191c1f;--line:#2a2e33;--acc:#7aa2ff;--new:#4ade80;--upd:#fbbf24}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);font:15px/1.5 -apple-system,"Microsoft YaHei",sans-serif;padding:14px}
h1{font-size:19px;margin:0 0 2px;display:flex;align-items:center;gap:10px}.sub{color:var(--mut);font-size:12px;margin-bottom:14px}
.rf{margin-left:auto;font:13px/1 inherit;padding:6px 12px;border:1px solid var(--line);border-radius:20px;background:var(--card);color:var(--acc)}
h2{font-size:16px;margin:20px 0 10px;border-bottom:1px solid var(--line);padding-bottom:6px;display:flex;align-items:center;flex-wrap:wrap;gap:8px}
h3{font-size:14px;margin:12px 0 6px;color:var(--mut)}
.grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(300px,1fr));gap:12px}
.card{background:var(--card);border:1px solid var(--line);border-radius:14px;padding:13px}
.cname{font-weight:700;font-size:16px;color:var(--fg);text-decoration:none}
.note{color:var(--mut);font-size:12px;margin:3px 0 8px}.repo{font-family:monospace}
.rel{font-size:13px;margin-bottom:8px}.rel a,.pk-h a,.mkt a{color:var(--acc);text-decoration:none}
ul{margin:0;padding:0;list-style:none}.commits li{font-size:12.5px;padding:3px 0;border-top:1px solid var(--line)}
.commits .d{color:var(--mut);font-family:monospace;font-size:11.5px;margin-right:6px}
.muted{color:var(--mut)}.err{color:#ef4444}
#q{font:13px/1 inherit;padding:6px 11px;border:1px solid var(--line);border-radius:20px;background:var(--card);color:var(--fg);flex:1;min-width:140px}
.pgroup{margin-bottom:14px}.pgroup h3{position:sticky;top:0;background:var(--bg);padding:6px 0}
.pkg{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:9px 12px;margin-bottom:7px}
.pk-h{font-size:13.5px}.pk-h a{font-weight:600}.pk-h .v{font-family:monospace;font-size:11px;color:var(--mut);margin:0 4px}
.pk-d{color:var(--mut);font-size:12.5px;margin-top:4px;white-space:pre-wrap}
.tag{font-size:10.5px;padding:1px 7px;border-radius:6px;margin-right:4px;background:var(--line);color:var(--mut)}
.dl{color:var(--mut);font-size:11.5px}
.t-script{background:rgba(37,99,235,.18);color:var(--acc)}.t-package{background:rgba(22,163,74,.18);color:var(--new)}
.t-skill{background:rgba(217,119,6,.18);color:var(--upd)}.t-mcp{background:rgba(168,85,247,.2);color:#a855f7}
"""
    }
}
