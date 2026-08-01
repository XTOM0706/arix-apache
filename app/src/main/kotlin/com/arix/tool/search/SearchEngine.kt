package com.arix.tool.search

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class SearchResult(
    val title: String,
    val snippet: String,
    val url: String,
    val engine: String
)

interface SearchEngine {
    val name: String
    val displayName: String
    val description: String

    suspend fun search(query: String, maxResults: Int = 5, language: String = "en"): List<SearchResult>
}

object SearchEngines {
    fun all(): List<SearchEngine> = listOf(
        BingEngine,
        BaiduEngine,
        SogouEngine,
        DuckDuckGoEngine,
        GoogleWebEngine,
        GoogleScholarEngine,
        CrossrefEngine,
        AnySearchEngine,  // 可选外部服务，默认关；由 SearchTool 依 SearchApiPrefs 门控
        PerplexicaEngine  // 自托管，默认关；同样门控
    )

    // 默认引擎：必应 + DDG（百度/搜狗已被反爬验证码/antispider 挡死、抓到的是验证码页=静默空结果）。
    // DDG 免 key、不在国内反爬体系里，修好请求方式(POST 表单端点)后是必应之外的天然冗余源。
    // SearchTool 会把用户已启用的键控引擎(Tavily/博查/智谱等，JSON 稳)拼在这些前面。
    fun defaults(): List<SearchEngine> = listOf(BingEngine, DuckDuckGoEngine)

    fun getByName(name: String): SearchEngine? = all().find {
        it.name.equals(name, ignoreCase = true)
    } ?: JsSearchSources.engineByNameCached(name)   // 内置没有 → 兜底查用户 JS 源（需先 cacheContext）

    // —— 用户自定义 JS 搜索源（存于 JsSearchSources，默认全关；见 JsSearchSource.kt）——
    // 已启用的 JS 源作为可选引擎，与内置引擎并列：由 web_search 的 engine=<源名> 选中。
    // getByName 的兜底能让「engine=<源名>」直接命中；这两个方法给上层显式取「全部启用 JS 源」用。
    fun jsSources(context: Context): List<SearchEngine> {
        JsSearchSources.cacheContext(context)
        return JsSearchSources.enabled(context).map { JsSearchSources.asEngine(context, it) }
    }

    fun jsSourceNames(context: Context): List<String> {
        JsSearchSources.cacheContext(context)
        return JsSearchSources.enabled(context).map { it.name }
    }
}

// ============================================================
// HTML → 可读文本（对齐 Operit visit_web：不做脆弱的逐结果正则，
// 直接把页面清洗成文本喂给模型；答案框/天气框等都能被提取到）
// ============================================================
internal fun htmlToText(html: String): String {
    return html
        .replace(Regex("(?is)<script.*?</script>"), " ")
        .replace(Regex("(?is)<style.*?</style>"), " ")
        .replace(Regex("(?is)<noscript.*?</noscript>"), " ")
        .replace(Regex("(?is)<head.*?</head>"), " ")
        .replace(Regex("(?is)<!--.*?-->"), " ")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|li|h[1-6]|tr)>"), "\n")
        .replace(Regex("<[^>]+>"), " ")
        .let { decodeHtmlEntities(it) }
        .replace(Regex("[ \\t\\x0B\\f]+"), " ")
        .replace(Regex("\\n{3,}"), "\n\n")
        .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

internal fun decodeHtmlEntities(s: String): String = s
    .replace("&nbsp;", " ").replace("&ensp;", " ").replace("&emsp;", " ").replace("&thinsp;", " ")
    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&middot;", "·")
    .replace(Regex("&#[xX]([0-9a-fA-F]+);")) { m -> m.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: m.value }
    .replace(Regex("&#(\\d+);")) { m -> m.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: m.value }

internal fun httpGetHtml(rawUrl: String, ua: String, referer: String? = null): String? =
    httpHtml(rawUrl, ua, referer, null)

/**
 * 表单 POST 取 HTML。有些搜索端点（如 html.duckduckgo.com/html/）只认表单提交，GET 会被挡或返回空壳。
 * 与 [httpGetHtml] 共用同一套加固（反爬识别 / 字符集 / identity 编码）。
 */
internal fun httpPostHtml(rawUrl: String, ua: String, form: Map<String, String>, referer: String? = null): String? =
    httpHtml(rawUrl, ua, referer, form)

private fun httpHtml(rawUrl: String, ua: String, referer: String?, postForm: Map<String, String>?): String? {
    return try {
        val conn = URL(rawUrl).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 7000; conn.readTimeout = 7000
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        conn.setRequestProperty("Accept-Encoding", "identity") // 避免 gzip 解码边界问题
        if (referer != null) conn.setRequestProperty("Referer", referer)
        if (postForm != null) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            val body = postForm.entries.joinToString("&") { (k, v) ->
                "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
        // 反爬识别：百度/搜狗返回 200 但重定向到验证码/antispider 页——URL 命中就当失败，别把验证码页喂给解析器当"空结果"
        val finalUrl = conn.url?.toString() ?: ""
        if (finalUrl.contains("wappass.baidu.com") || finalUrl.contains("/antispider") || finalUrl.contains("captcha", ignoreCase = true)) { conn.disconnect(); return null }
        // 依据 Content-Type 决定字符集（百度等可能非 UTF-8）
        val ct = conn.contentType ?: ""
        val charset = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1) ?: "UTF-8"
        val bytes = conn.inputStream.readBytes(); conn.disconnect()
        val cs = try { charset(charset) } catch (_: Exception) { Charsets.UTF_8 }
        val html = String(bytes, cs)
        // 正文命中反爬页也当失败（有的反爬不改 URL 只换页面内容）
        if (html.contains("antispider") || html.contains("wappass") || html.contains("请输入验证码") || html.contains("百度安全验证")) null else html
    } catch (_: Exception) { null }
}

private const val DESKTOP_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

// ============================================================
// Bing 国内版（cn.bing.com）—— 国内可达，结果结构相对稳定
// ============================================================
object BingEngine : SearchEngine {
    override val name = "bing"
    override val displayName = "Bing"
    override val description = "必应搜索（国内可达）"

    override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val html = httpGetHtml("https://cn.bing.com/search?q=$encoded&ensearch=0", DESKTOP_UA)
                ?: return@withContext emptyList()
            val results = mutableListOf<SearchResult>()
            // 结构化解析 b_algo 结果块：标题取 h2>a，摘要取 b_caption
            // 注意 <li class="b_algo" ...> 后面还有属性，class 后不能直接接 ">"
            val block = Regex("(?is)<li class=\"b_algo[^\"]*\"[^>]*>(.*?)</li>")
            for (m in block.findAll(html)) {
                if (results.size >= maxResults) break
                val seg = m.groupValues[1]
                val a = Regex("(?is)<h2[^>]*>.*?<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>").find(seg) ?: continue
                val url = a.groupValues[1]
                if (url.contains("go.microsoft.com") || url.contains("/aclk")) continue // 跳过广告/跳转
                val title = htmlToText(a.groupValues[2]).replace("\n", " ").trim()
                val cap = Regex("(?is)<div class=\"b_caption[^\"]*\">(.*?)</div>").find(seg)?.groupValues?.get(1)
                val snip = htmlToText((cap ?: seg)).replace("\n", " ").trim()
                if (title.length >= 2) results.add(SearchResult(title, snip.take(280), url, name))
            }
            // b_algo 没抓到时的兜底：宽松匹配 h2>a 真实结果标题（不再 dump 整页正文，
            // 避免把“字词/汉语词典/热搜”等噪声框当成结果）
            if (results.isEmpty()) {
                val loose = Regex("(?is)<h2[^>]*>\\s*<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>")
                for (m in loose.findAll(html)) {
                    if (results.size >= maxResults) break
                    val url = m.groupValues[1]
                    if (url.contains("go.microsoft.com") || url.contains("/aclk") || url.contains("bing.com")) continue
                    val title = htmlToText(m.groupValues[2]).replace("\n", " ").trim()
                    val after = html.substring(m.range.last.coerceAtMost(html.length), (m.range.last + 600).coerceAtMost(html.length))
                    val snip = htmlToText(after).replace("\n", " ").trim().take(200)
                    if (title.length >= 2) results.add(SearchResult(title, snip, url, name))
                }
            }
            results
        }
}

// ============================================================
// 百度网页搜索 —— 国内可达；结果 URL 多为跳转链，靠文本提取兜底
// ============================================================
object BaiduEngine : SearchEngine {
    override val name = "baidu"
    override val displayName = "百度"
    override val description = "百度搜索（国内可达，适合中文/本地信息如天气）"

    override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val html = httpGetHtml("https://www.baidu.com/s?wd=$encoded", DESKTOP_UA, "https://www.baidu.com/")
                ?: return@withContext emptyList()
            val results = mutableListOf<SearchResult>()
            // 逐条结果：标题 <h3><a>，摘要取标题后一段文本（不再 dump 整页“百度摘要”，
            // 避免把汉语词典/热搜榜等噪声当结果）
            val block = Regex("(?is)<h3[^>]*>\\s*<a[^>]*href=\"(https?://[^\"]+)\"[^>]*>(.*?)</a>")
            for (m in block.findAll(html)) {
                if (results.size >= maxResults) break
                val title = htmlToText(m.groupValues[2]).replace("\n", " ").trim()
                val after = html.substring(m.range.last.coerceAtMost(html.length), (m.range.last + 800).coerceAtMost(html.length))
                val snip = htmlToText(after).replace("\n", " ").trim().take(200)
                if (title.length >= 2) results.add(SearchResult(title, snip, m.groupValues[1], name))
            }
            results
        }
}

// ============================================================
// 搜狗搜索 —— 国内可达，作为必应/百度之外的冗余源
// ============================================================
object SogouEngine : SearchEngine {
    override val name = "sogou"
    override val displayName = "搜狗"
    override val description = "搜狗搜索（国内可达）"

    override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val html = httpGetHtml("https://www.sogou.com/web?query=$encoded", DESKTOP_UA, "https://www.sogou.com/")
                ?: return@withContext emptyList()
            val results = mutableListOf<SearchResult>()
            val block = Regex("(?is)<h3[^>]*>\\s*<a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>")
            for (m in block.findAll(html)) {
                if (results.size >= maxResults) break
                val title = htmlToText(m.groupValues[2]).replace("\n", " ").trim()
                var url = m.groupValues[1]
                if (url.startsWith("/")) url = "https://www.sogou.com$url"
                val after = html.substring(m.range.last.coerceAtMost(html.length), (m.range.last + 800).coerceAtMost(html.length))
                val snip = htmlToText(after).replace("\n", " ").trim().take(200)
                if (title.length >= 2) results.add(SearchResult(title, snip, url, name))
            }
            results
        }
}

// ============================================================
// DuckDuckGo Engine (extracted from WebSearchTool)
// ============================================================
object DuckDuckGoEngine : SearchEngine {
    override val name = "duckduckgo"
    override val displayName = "DuckDuckGo"
    override val description = "匿名网络搜索，隐私保护"

    override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<SearchResult>()
            // html.duckduckgo.com/html/ 是表单端点，必须 POST——此前用 GET 请求 lite 端点，且 UA 是截断的
            // "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"（无版本号，典型 bot 特征）。DDG 不需要 key。
            val html = httpPostHtml("https://html.duckduckgo.com/html/", DESKTOP_UA, mapOf("q" to query, "b" to "", "kl" to ""))
            if (html != null) results.addAll(parseDdgHtml(html, maxResults))

            // Fallback to DDG Instant Answer API
            if (results.isEmpty()) {
                try {
                    val encoded = URLEncoder.encode(query, "UTF-8")
                    val url = URL("https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1")
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 8000; conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    val json = conn.inputStream.bufferedReader().readText(); conn.disconnect()
                    val obj = JSONObject(json)
                    val abstract = obj.optString("Abstract", "")
                    val heading = obj.optString("Heading", "")
                    val source = obj.optString("AbstractURL", "")
                    if (heading.isNotBlank() && abstract.isNotBlank()) {
                        results.add(SearchResult(heading, abstract, source.ifBlank { "https://duckduckgo.com/?q=$encoded" }, name))
                    }
                    val related = obj.optJSONArray("RelatedTopics")
                    if (related != null) {
                        for (i in 0 until minOf(related.length(), maxResults)) {
                            val topic = related.optJSONObject(i) ?: continue
                            val text = topic.optString("Text", "").replace(Regex("<[^>]*>"), "").take(200)
                            val u = topic.optString("FirstURL", "")
                            if (text.isNotBlank()) results.add(SearchResult(text.take(80), text, u.ifBlank { "https://duckduckgo.com/?q=$encoded" }, name))
                        }
                    }
                } catch (_: Exception) {}
            }
            results
        }

    private fun parseDdgHtml(html: String, max: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val rowPattern = Regex(
            """<h2 class="result__title">[\s\S]*?<a[^>]*href="([^"]+)"[^>]*>([\s\S]+?)</a>[\s\S]*?</h2>[\s\S]*?<a class="result__snippet"[^>]*>([\s\S]+?)</a>"""
        )
        for (match in rowPattern.findAll(html)) {
            if (results.size >= max) break
            val href = match.groupValues[1].trim()
            if (href.contains("y.js")) continue                       // 广告位
            val link = unwrapDdgLink(href) ?: continue
            val title = decodeHtmlEntities(match.groupValues[2].replace(Regex("<[^>]*>"), "")).trim()
            val snippet = decodeHtmlEntities(match.groupValues[3].replace(Regex("<[^>]*>"), "")).trim()
            if (title.isNotBlank()) results.add(SearchResult(title, snippet, link, name))
        }
        if (results.isEmpty()) {
            // 兜底：DDG 改了类名也能捞——直接找跳转链，标题取锚文本
            val alt = Regex("""<a[^>]*href="(//duckduckgo\.com/l/\?uddg=[^"]+)"[^>]*>([\s\S]{2,120}?)</a>""")
            for (match in alt.findAll(html)) {
                if (results.size >= max) break
                val link = unwrapDdgLink(match.groupValues[1].trim()) ?: continue
                val title = decodeHtmlEntities(match.groupValues[2].replace(Regex("<[^>]*>"), "")).trim()
                if (title.isNotBlank()) results.add(SearchResult(title, "", link, name))
            }
        }
        return results
    }

    /**
     * DDG 的 href 是跳转链 `//duckduckgo.com/l/?uddg=<真URL>`，得解出真链。
     * 旧解析器直接把它当结果链接用，而兜底正则又要求 href 以 http 开头 → `//` 开头一条都不匹配，结果被静默全丢。
     */
    private fun unwrapDdgLink(href: String): String? {
        // 先解 HTML 实体：实测直链形如 ...details?id=io.xtomia.app&amp;hl=en-US，不解码链接就是坏的。
        // 对 uddg 跳转链先解实体同样安全——真 URL 在 uddg= 里是百分号编码的，不含裸 &。
        val h = decodeHtmlEntities(href)
        if (!h.contains("uddg=")) return h.takeIf { it.startsWith("http") }
        return try {
            java.net.URLDecoder.decode(h.substringAfter("uddg=").substringBefore("&"), "UTF-8")
                .takeIf { it.startsWith("http") }
        } catch (_: Exception) { null }
    }
}

// ============================================================
// Google Web Engine
// ============================================================
object GoogleWebEngine : SearchEngine {
    override val name = "google"
    override val displayName = "Google 搜索"
    override val description = "Google 网页搜索"

    override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<SearchResult>()
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("https://www.google.com/search?q=$encoded&hl=$language&num=${maxResults.coerceIn(1, 20)}&pws=0")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 7000; conn.readTimeout = 7000
                conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36")
                conn.setRequestProperty("Accept", "text/html")
                conn.setRequestProperty("Accept-Language", "$language,en;q=0.9")
                val html = conn.inputStream.bufferedReader().readText(); conn.disconnect()
                results.addAll(parseGoogleHtml(html, maxResults))
            } catch (_: Exception) {}
            results
        }

    private fun parseGoogleHtml(html: String, max: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        // Google search result snippets typically contain h3 > a for title, and a div with class containing "BNeawe" for snippet
        val snippetPattern = Regex("""<a[^>]*href="(/url\?q=)?(https?://[^"&]*)[^"]*"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val matches = snippetPattern.findAll(html).toList()
        var count = 0
        for (match in matches) {
            if (count >= max) break
            val rawUrl = match.groupValues[2].replace("&amp;", "&").trim()
            val title = match.groupValues[3].replace(Regex("<[^>]*>"), "").trim()
            if (title.isBlank() || title.length < 3 || rawUrl.startsWith("/")) continue
            // Try to find snippet near this result
            val snippet = try {
                val idx = match.range.last
                val nearby = html.substring(idx, minOf(idx + 800, html.length))
                val snippetMatch = Regex("""<span[^>]*>(.{30,250})</span>""").find(nearby)
                snippetMatch?.groupValues?.get(1)?.replace(Regex("<[^>]*>"), "")?.trim() ?: ""
            } catch (_: Exception) { "" }
            results.add(SearchResult(title, snippet, rawUrl, name))
            count++
        }
        return results
    }
}

// ============================================================
// Google Scholar Engine
// ============================================================
object GoogleScholarEngine : SearchEngine {
    override val name = "scholar"
    override val displayName = "Google 学术"
    override val description = "Google Scholar 学术论文搜索"

    private val mirrors = listOf("https://xs.cntpj.com/scholar")

    override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<SearchResult>()
            val encoded = URLEncoder.encode(query, "UTF-8")
            // Try mirror first (less likely to be blocked)
            val urls = listOf(
                "https://scholar.google.com/scholar?q=$encoded&hl=$language&num=${maxResults.coerceIn(1, 10)}&as_sdt=0,5",
                "${mirrors[0]}?q=$encoded&hl=$language&num=${maxResults.coerceIn(1, 10)}"
            )
            for (searchUrl in urls) {
                try {
                    val url = URL(searchUrl)
                    val conn = url.openConnection() as HttpURLConnection
                    conn.connectTimeout = 7000; conn.readTimeout = 7000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    conn.setRequestProperty("Accept", "text/html")
                    val html = conn.inputStream.bufferedReader().readText(); conn.disconnect()
                    results.addAll(parseScholarHtml(html, maxResults))
                    if (results.isNotEmpty()) break
                } catch (_: Exception) { continue }
            }
            results
        }

    private fun parseScholarHtml(html: String, max: Int): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        // Parse gs_rt (title) and gs_rs (snippet) from Google Scholar
        val entryPattern = Regex("""<h3[^>]*class="[^"]*gs_rt[^"]*"[^>]*>.*?<a[^>]*href="([^"]*)"[^>]*>(.*?)</a>.*?</h3>.*?<div[^>]*class="[^"]*gs_rs[^"]*"[^>]*>(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
        entryPattern.findAll(html).take(max).forEach { match ->
            val link = match.groupValues[1].trim()
            val title = match.groupValues[2].replace(Regex("<[^>]*>"), "").trim()
            val snippet = match.groupValues[3].replace(Regex("<[^>]*>"), "").trim().take(300)
            if (title.isNotBlank()) results.add(SearchResult(title, snippet, link, name))
        }
        return results
    }
}

// ============================================================
// Crossref Academic Engine
// ============================================================
object CrossrefEngine : SearchEngine {
    override val name = "crossref"
    override val displayName = "CrossRef 学术"
    override val description = "CrossRef 学术论文数据库，支持 DOI/关键词/作者/标题/ISSN 搜索"

    private const val API_BASE = "https://api.crossref.org"

    override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val results = mutableListOf<SearchResult>()
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val url = URL("$API_BASE/works?query=$encoded&rows=${maxResults.coerceIn(1, 20)}&sort=relevance")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 7000; conn.readTimeout = 7000
                conn.setRequestProperty("User-Agent", "Arix/1.0 (mailto:xtom@example.com)")
                conn.setRequestProperty("Accept", "application/json")
                val json = conn.inputStream.bufferedReader().readText(); conn.disconnect()
                val obj = JSONObject(json)
                val items = obj.optJSONObject("message")?.optJSONArray("items") ?: return@withContext results
                for (i in 0 until minOf(items.length(), maxResults)) {
                    val work = items.optJSONObject(i) ?: continue
                    val titles = work.optJSONArray("title")
                    val title = titles?.optString(0, "") ?: ""
                    val abstract = work.optString("abstract", "").take(300)
                    val doi = work.optString("DOI", "")
                    val publisher = work.optString("publisher", "")
                    val year = work.optJSONObject("published-print")?.optJSONArray("date-parts")?.optJSONArray(0)?.optInt(0, 0) ?: 0
                    val type = work.optString("type", "")
                    val authors = work.optJSONArray("author")?.let { arr ->
                        (0 until minOf(arr.length(), 3)).map { j ->
                            val author = arr.optJSONObject(j) ?: return@let ""
                            "${author.optString("given", "")} ${author.optString("family", "")}".trim()
                        }.filter { it.isNotBlank() }.joinToString(", ")
                    } ?: ""
                    if (title.isNotBlank()) {
                        val snippet = buildString {
                            if (authors.isNotBlank()) append("作者: $authors")
                            if (year > 0) append(" · $year")
                            if (publisher.isNotBlank()) append(" · $publisher")
                            if (type.isNotBlank()) append(" · [$type]")
                            if (abstract.isNotBlank()) append("\n${abstract.take(200)}")
                            if (doi.isNotBlank()) append("\nDOI: $doi")
                        }
                        results.add(SearchResult(
                            title = title,
                            snippet = snippet,
                            url = if (doi.isNotBlank()) "https://doi.org/$doi" else "$API_BASE/works/$encoded",
                            engine = name
                        ))
                    }
                }
            } catch (_: Exception) {}
            results
        }

    suspend fun searchByDoi(doi: String): SearchResult? = withContext(Dispatchers.IO) {
        try {
            val url = URL("$API_BASE/works/${URLEncoder.encode(doi, "UTF-8")}")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 7000; conn.readTimeout = 7000
            conn.setRequestProperty("User-Agent", "Arix/1.0 (mailto:xtom@example.com)")
            conn.setRequestProperty("Accept", "application/json")
            val json = conn.inputStream.bufferedReader().readText(); conn.disconnect()
            val msg = JSONObject(json).optJSONObject("message") ?: return@withContext null
            val title = msg.optJSONArray("title")?.optString(0, "") ?: ""
            val publisher = msg.optString("publisher", "")
            val abstract = msg.optString("abstract", "").take(500)
            SearchResult(title, "DOI: $doi · $publisher\n$abstract", "https://doi.org/$doi", name)
        } catch (_: Exception) { null }
    }
}

// ============================================================
// 智谱 Web Search（GLM）已并入 KeyedEngines（id=zhipu），复用其密钥存储/设置 UI/
// 深度搜索池与门控——不再需要独立的空实现引擎。见 KeyedEngines.kt。
// ============================================================

// ============================================================
// AnySearch —— 统一搜索基础设施（MCP 协议, api.anysearch.com/mcp）
// 自研实现：仅通过其公开 MCP API 做 HTTP 调用，不引入其任何源码/库、不进 APK，
// 零编译期依赖；可选、可降级（挂了/限流其余引擎照常），拔掉不影响独立构建。
// 匿名可用（低速率），填 key 提速。查询会发送到第三方 —— 由上层门控与隐私告知。
// ============================================================
object AnySearchEngine : SearchEngine {
    override val name = "anysearch"
    override val displayName = "AnySearch (统一API)"
    override val description = "AnySearch 统一搜索(MCP)，匿名可用、填 key 提速；需在设置启用"

    private const val MCP_URL = "https://api.anysearch.com/mcp"

    // 由 SearchTool 在调用前从 prefs 注入；空 = 匿名
    @Volatile var injectedKey: String = ""

    override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> =
        searchWithKey(injectedKey, query, maxResults)

    /** MCP tools/call → search；apiKey 空 = 匿名。 */
    suspend fun searchWithKey(apiKey: String, query: String, maxResults: Int = 5): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val md = callMcp(apiKey, "search", JSONObject().apply {
                put("query", query)
                put("max_results", maxResults.coerceIn(1, 10))
            }) ?: return@withContext emptyList()
            parseSearchMarkdown(md, maxResults)
        }

    /** MCP tools/call → extract：服务端浏览器式抓取，返回 markdown 正文（给 FetchTool 防 403 兜底）。 */
    suspend fun extract(apiKey: String, url: String): String? =
        withContext(Dispatchers.IO) { callMcp(apiKey, "extract", JSONObject().apply { put("url", url) }) }

    // —— MCP JSON-RPC 无状态调用；取 result.content[*].type=="text" 文本拼接 ——
    private fun callMcp(apiKey: String, toolName: String, args: JSONObject): String? {
        return try {
            val conn = URL(MCP_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15000; conn.readTimeout = 30000
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            conn.setRequestProperty("User-Agent", "Arix/1.0")
            if (apiKey.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.doOutput = true
            val body = JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", 1); put("method", "tools/call")
                put("params", JSONObject().apply { put("name", toolName); put("arguments", args) })
            }.toString()
            conn.outputStream.use { it.write(body.toByteArray()) }
            if (conn.responseCode !in 200..299) { conn.disconnect(); return null }
            val raw = conn.inputStream.bufferedReader().use { it.readText() }; conn.disconnect()
            // 无状态返回纯 JSON；若为 SSE(text/event-stream) 则拼接 data: 行
            val jsonText = if (raw.trimStart().startsWith("{")) raw
                else raw.lineSequence().filter { it.startsWith("data:") }.joinToString("") { it.removePrefix("data:").trim() }
            val result = JSONObject(jsonText).optJSONObject("result") ?: return null
            val content = result.optJSONArray("content") ?: return null
            buildString {
                for (i in 0 until content.length()) {
                    val block = content.optJSONObject(i) ?: continue
                    if (block.optString("type") == "text") append(block.optString("text")).append("\n")
                }
            }.trim().ifBlank { null }
        } catch (_: Exception) { null }
    }

    // 解析 search 返回的 markdown：形如「### N. 标题 / - **URL**: xxx / 正文…」
    private fun parseSearchMarkdown(md: String, maxResults: Int): List<SearchResult> {
        val out = mutableListOf<SearchResult>()
        val blocks = md.split(Regex("(?m)^###\\s+\\d+\\.\\s+")).drop(1)
        for (b in blocks) {
            val title = b.lineSequence().firstOrNull()?.trim().orEmpty()
            val url = Regex("(?im)^-\\s*\\*\\*URL\\*\\*:\\s*(\\S+)").find(b)?.groupValues?.get(1).orEmpty()
            val snippet = b.lines().drop(1)
                .filterNot { it.trimStart().startsWith("- **URL**") }
                .joinToString(" ") { it.removePrefix("-").trim() }
                .trim().take(400)
            if (title.isNotBlank()) out.add(SearchResult(title, snippet, url, name))
            if (out.size >= maxResults) break
        }
        // 解析不到条目（格式变动）就把整段作单条兜底，信息仍到达模型
        if (out.isEmpty() && md.isNotBlank()) out.add(SearchResult("AnySearch 结果", md.take(1500), "", name))
        return out
    }
}

// ============================================================
// Perplexica —— 自托管开源 AI 搜索（{baseUrl}/api/search）
// 自研 HTTP 调用，零编译期依赖；可选可降级。用户填自己实例的 baseUrl 与模型。
// ============================================================
object PerplexicaEngine : SearchEngine {
    override val name = "perplexica"
    override val displayName = "Perplexica (自托管)"
    override val description = "自托管 Perplexica AI 搜索；需在设置填 baseUrl 与 chat/embedding 模型"

    @Volatile var baseUrl: String = ""
    @Volatile var chatProvider: String = ""
    @Volatile var chatModel: String = ""
    @Volatile var embedProvider: String = ""
    @Volatile var embedModel: String = ""

    override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> =
        withContext(Dispatchers.IO) {
            if (baseUrl.isBlank()) return@withContext emptyList()
            try {
                val conn = URL("$baseUrl/api/search").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 15000; conn.readTimeout = 45000
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", "Arix/1.0")
                conn.doOutput = true
                val body = JSONObject().apply {
                    put("chatModel", JSONObject().apply { put("providerId", chatProvider); put("key", chatModel) })
                    put("embeddingModel", JSONObject().apply { put("providerId", embedProvider); put("key", embedModel) })
                    put("optimizationMode", "balanced")
                    put("sources", JSONArray(listOf("web")))
                    put("query", query)
                    put("stream", false)
                }.toString()
                conn.outputStream.use { it.write(body.toByteArray()) }
                if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext emptyList() }
                val json = conn.inputStream.bufferedReader().use { it.readText() }; conn.disconnect()
                val obj = JSONObject(json)
                val results = mutableListOf<SearchResult>()
                // message：Perplexica 的综合回答（含引用），最有价值，单列首条
                val message = obj.optString("message", "")
                if (message.isNotBlank()) results.add(SearchResult("Perplexica 综合回答", message.take(1500), "", name))
                obj.optJSONArray("sources")?.let { sources ->
                    for (i in 0 until minOf(sources.length(), maxResults)) {
                        val src = sources.optJSONObject(i) ?: continue
                        val meta = src.optJSONObject("metadata")
                        results.add(SearchResult(
                            title = meta?.optString("title", "").orEmpty(),
                            snippet = src.optString("pageContent", src.optString("content", "")).take(300),
                            url = meta?.optString("url", "").orEmpty(),
                            engine = name
                        ))
                    }
                }
                results
            } catch (_: Exception) { emptyList() }
        }
}
