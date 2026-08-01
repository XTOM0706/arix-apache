package com.arix.tool

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.arix.tool.search.htmlToText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

private const val OPEN_PAGE_MAX = 4000
private const val TEXT_JS = "(function(){return document.body?document.body.innerText:''})()"
// 小红书图文提取（借鉴 xhsreader/小红书图文阅读器，作者 RaineIris）：先读 __INITIAL_STATE__ 结构化笔记，取不到退回 og 标签+正文
private const val XHS_JS = "(function(){try{var s=window.__INITIAL_STATE__;var n=null;if(s&&s.note&&s.note.noteDetailMap){var m=s.note.noteDetailMap;var id=s.note.firstNoteId||Object.keys(m)[0];n=m[id]&&m[id].note}if(n){var imgs=(n.imageList||[]).map(function(x){return x.urlDefault||x.url||''}).filter(Boolean);return JSON.stringify({title:n.title||'',desc:n.desc||'',author:(n.user&&n.user.nickname)||'',images:imgs})}}catch(e){}var g=function(p){var el=document.querySelector('meta[property=\"'+p+'\"],meta[name=\"'+p+'\"]');return el?el.content:''};return JSON.stringify({title:g('og:title')||document.title||'',desc:g('og:description')||(document.body?document.body.innerText.slice(0,1500):''),author:'',images:[g('og:image')].filter(Boolean)})})()"
private const val IMAGES_JS = "(function(){var s=new Set();document.querySelectorAll('img,source').forEach(function(i){var u=i.currentSrc||i.src||i.getAttribute('data-src')||i.getAttribute('data-original')||i.getAttribute('data-lazy-src');if(u&&u.indexOf('http')===0)s.add(u)});return Array.from(s).slice(0,40).join('\\n')})()"

// ============================================================
// open_page —— 用 WebView(浏览器内核)本地打开网页并取正文
// 本地优先、不外发第三方；能跑 JS、真实浏览器 UA，比直连 fetch 更少被 403、更能拿动态内容。
// 抓取链：WebView 本地 → 直连兜底 → AnySearch extract(第三方，需启用)。
// 注：WebView 离屏取内容对时序/生命周期敏感，需真机验证实际取到正文。
// ============================================================
class OpenPageTool(private val context: android.content.Context) : Tool {

    override val name = "open_page"
    override val description = "取网页内容：默认(format=auto)用浏览器内核(WebView)打开、能跑 JS、真实浏览器 UA，最能拿到动态内容、最少被 403，返回清洗后的正文；已知返回 JSON/API 或要原始 HTML 时设 format=json / raw(直连、更快)。mode=images/video 取页面图片/视频直链。抓取网页正文/接口都用它。"

    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Fetch a web page. Default (format=auto) opens it in a real browser engine — runs JS, real UA, best at dynamic content and least likely to get 403 — and returns cleaned article text. Set format=json or raw (direct, faster) when you know it is an API or you need the HTML. mode=images/video returns direct media links. Use this for any page or endpoint."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("url", JSONObject().apply { put("type", "string"); put("description", "page URL") })
            put("format", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("auto", "raw", "json"))); put("description", "auto=browser-rendered article text (default, avoids 403); raw=direct HTML; json=direct + parse JSON") })
            put("mode", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("text", "images", "video"))); put("description", "text (default); images=all image links on the page; video=info/cover + playable link (Bilibili via official API; YouTube info + page link; others scrape <video>/.mp4/.m3u8)") })
            put("incognito", JSONObject().apply { put("type", "boolean"); put("description", "Fetch in an isolated process with its own cookie jar: sends none of the user's logins and leaves no trace. Use it for pages that need no login — shared conversation links, public profiles, anything the user should not be identified on. Costs a little more time and cannot reach login-walled content.") })
        })
        put("required", JSONArray(listOf("url")))
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val urlStr = params.optString("url", "").trim()
            .let { if (it.isNotBlank() && !it.startsWith("http")) "https://$it" else it }
            .let { normalizeTelegram(it) }   // t.me/x → t.me/s/x（否则只是「在App打开」空落地页）
        if (urlStr.isBlank()) return ToolResult("请提供URL", isError = true)
        // SSRF 前置闸门：免 root 也能让被注入的 AI 去打本机/局域网/云元数据，这里逐地址判私网后再放行。
        withContext(Dispatchers.IO) { WebGuard.check(urlStr) }?.let {
            return ToolResult("拒绝打开：$it（open_page 不访问内网/本机/云元数据地址）", isError = true)
        }

        // ── 隐身：整条另走一路，**不能**掉进下面那套兜底链 ──
        //
        // 为什么必须早退而不是加个开关往下传：下面那条链的第 2 级是 `withCookies = true`
        //（带站点登录 cookie 重试），第 4 级是把 URL 交给第三方 AnySearch 去抓。
        // 这两级在隐身语义下都是**反着的**——一个把用户的登录态送出去，一个把他在看什么告诉第三方。
        // 一旦允许往下掉，"隐身"就变成了"大概率隐身"，那比不提供这个选项更糟。
        //
        // 隐身只有两级：独立进程的 WebView（真隐身）→ 直连（不带 cookie 罐，仍然匿名）。
        // 拿不到就老实说拿不到。
        if (params.optBoolean("incognito", false)) {
            return when (val r = com.arix.app.IncognitoFetch.text(context, urlStr)) {
                is com.arix.app.IncognitoFetch.Result.Text ->
                    ToolResult(UntrustedWeb.fence(r.text.take(OPEN_PAGE_MAX), "隐身抓取"))
                // 做不到就明说，**绝不**偷偷用普通方式顶上去：模型据此才能决定
                //「换个不需要隐身的做法」还是「问用户要不要用普通方式」。
                com.arix.app.IncognitoFetch.Result.Unsupported -> ToolResult(
                    "This device cannot do real incognito (needs Android 9+). Not falling back silently — " +
                        "either ask the user whether a normal fetch is acceptable (it may carry their login), " +
                        "or take an approach that needs no page fetch.",
                    isError = true,
                )
                is com.arix.app.IncognitoFetch.Result.Failed -> {
                    // 直连兜底：HttpURLConnection 不带 cookie 罐，仍然是匿名访问，没破坏隐身语义
                    val direct = withContext(Dispatchers.IO) { directFetch(urlStr) }
                    if (!direct.isNullOrBlank()) ToolResult(UntrustedWeb.fence(direct.take(OPEN_PAGE_MAX), "隐身抓取(直连)"))
                    else ToolResult("隐身取页没成功：${r.reason}", isError = true)
                }
            }
        }

        // 小红书图文：结构化取 标题/正文/图片（反爬狠，走 WebView 内核）
        val host = try { java.net.URI(urlStr).host ?: "" } catch (_: Exception) { "" }
        if (host.contains("xiaohongshu") || host.contains("xhslink")) {
            formatXhs(renderWithWebView(urlStr, XHS_JS))?.let { return ToolResult(UntrustedWeb.fence(it, "小红书笔记")) }
            // 匿名取不到 → 带登录 cookie 再试（小红书常需登录）
            formatXhs(renderWithWebView(urlStr, XHS_JS, withCookies = true))?.let { return ToolResult(UntrustedWeb.fence(it, "小红书笔记")) }
            // 仍取不到就继续走下面的通用流程
        }

        // images 模式：WebView 渲染后抓所有图片直链（破 JS 懒加载）
        if (params.optString("mode", "text") == "images") {
            val imgs = renderWithWebView(urlStr, IMAGES_JS)
            return if (imgs.isNullOrBlank()) ToolResult("未在页面取到图片(可能需登录或强反爬)", isError = true)
                else ToolResult("🖼️ 页面图片直链：\n" + imgs.lines().filter { it.isNotBlank() }.mapIndexed { i, u -> "${i + 1}. $u" }.joinToString("\n"))
        }

        // video 模式：yt-dlp 提取视频直链/元数据/字幕
        if (params.optString("mode", "text") == "video") return extractVideo(urlStr)

        // raw/json：直连取原始内容（API/结构化数据场景）
        when (params.optString("format", "auto")) {
            "raw" -> withContext(Dispatchers.IO) { directRaw(urlStr) }?.let { return ToolResult(UntrustedWeb.fence(it.take(OPEN_PAGE_MAX))) }
            "json" -> withContext(Dispatchers.IO) { directRaw(urlStr) }?.let {
                val pretty = try { JSONObject(it).toString(2) } catch (_: Exception) { it }
                return ToolResult(UntrustedWeb.fence(pretty.take(OPEN_PAGE_MAX)))
            }
        }

        // 1) 本地 WebView 优先（先**匿名**抓，不带 cookie）
        renderWithWebView(urlStr)?.takeIf { it.isNotBlank() }?.let { return ToolResult(UntrustedWeb.fence(it.take(OPEN_PAGE_MAX))) }
        // 1b) 匿名抓不到 → 带「站点登录」cookie 再试一次（登录墙内容才动用登录态）
        renderWithWebView(urlStr, withCookies = true)?.takeIf { it.isNotBlank() }?.let { return ToolResult(UntrustedWeb.fence(it.take(OPEN_PAGE_MAX))) }
        // 2) 直连兜底
        withContext(Dispatchers.IO) { directFetch(urlStr) }?.takeIf { it.isNotBlank() }?.let { return ToolResult(UntrustedWeb.fence(it.take(OPEN_PAGE_MAX))) }
        // 3) AnySearch extract 兜底（第三方，需启用）
        if (com.arix.app.SearchApiPrefs.anySearchEnabled(context)) {
            com.arix.tool.search.AnySearchEngine.extract(com.arix.app.SearchApiPrefs.anySearchKey(context), urlStr)
                ?.takeIf { it.isNotBlank() }?.let { return ToolResult(UntrustedWeb.fence("[AnySearch 浏览器式抓取]\n" + it.take(OPEN_PAGE_MAX))) }
        }
        return ToolResult("打开网页失败（本地渲染/直连/兜底均未取到内容）：$urlStr", isError = true)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun renderWithWebView(url: String, jsExpr: String = TEXT_JS, withCookies: Boolean = false): String? = withContext(Dispatchers.Main) {
        withTimeoutOrNull(20000) {
            suspendCancellableCoroutine<String?> { cont ->
                var wv: WebView? = null
                try {
                    wv = WebView(context)
                    // 默认**匿名抓取，不带 cookie**（别把「站点登录」态暴露给任意页面）；
                    // 只有匿名抓不到时，才由上层带 withCookies=true 重试（登录墙内容才需要登录态）。
                    android.webkit.CookieManager.getInstance().setAcceptCookie(withCookies)
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, withCookies)
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    // ⚠ 安全加固：targetSdk=28 下这两个默认 true（false 只对 targetSdk≥30 生效）。
                    // 打开哪个网址由模型决定，恶意页可借此读 file:///data/data/... 与本应用的 ContentProvider。
                    // 与 BrowserPage.kt / BrowserAgent.kt 同一口径。
                    wv.settings.allowFileAccess = false
                    wv.settings.allowContentAccess = false
                    wv.settings.allowFileAccessFromFileURLs = false
                    wv.settings.allowUniversalAccessFromFileURLs = false
                    wv.settings.databaseEnabled = false   // 一次性抓正文不需要 WebSQL 持久库，关掉少一处站点写盘/追踪面
                    disableServiceWorkers()               // 拦掉 Service Worker 网络请求：别让页面注册常驻 SW 持久跑注入代码
                    wv.settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                    wv.webViewClient = object : WebViewClient() {
                        private var done = false
                        override fun onPageFinished(view: WebView, u: String) {
                            if (done) return
                            // 等一点让 JS 把动态内容渲染出来，再取 innerText
                            // 离屏 WebView 未 attach，view.postDelayed 的 Runnable 永不执行；必须用绑主 Looper 的 Handler
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                if (done) return@postDelayed
                                done = true
                                // 取正文前，先注入匹配当前网址的「用户脚本」(油猴式)——每个脚本各自 try/catch 隔离
                                val extract = {
                                    view.evaluateJavascript(jsExpr) { raw ->
                                        val text = decodeJs(raw)
                                        if (cont.isActive) cont.resume(text.ifBlank { null })
                                        try { view.destroy() } catch (_: Exception) {}
                                    }
                                }
                                val userJs = try { UserScriptStore.injectionJs(context, u) } catch (_: Exception) { null }
                                if (userJs != null) view.evaluateJavascript(userJs) { extract() } else extract()
                            }, 1200)
                        }
                    }
                    cont.invokeOnCancellation { try { wv?.destroy() } catch (_: Exception) {} }
                    wv.loadUrl(url)
                } catch (_: Exception) {
                    if (cont.isActive) cont.resume(null)
                    try { wv?.destroy() } catch (_: Exception) {}
                }
            }
        }
    }

    // Service Worker 收敛：SW 注册后能常驻、离屏拦网络、缓存并重跑脚本——一次性抓正文不需要，全局拦掉它的网络请求。
    // ServiceWorkerController 是进程级全局，重复设置幂等无害；失败(旧 WebView 内核不支持)静默跳过。
    private fun disableServiceWorkers() {
        runCatching {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
            val swc = ServiceWorkerControllerCompat.getInstance()
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS))
                swc.serviceWorkerWebSettings.setBlockNetworkLoads(true)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
                swc.setServiceWorkerClient(object : ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse =
                        WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                })
            }
        }
    }

    // Telegram：t.me/<频道>[/<id>] 直接打开只是「在 App 打开」的空落地页；公开频道/帖子的服务端渲染
    // 预览在 t.me/s/<频道>[/<id>]（含正文/图片），WebView 与直连都能读到。改写成预览版即可打开。
    private fun normalizeTelegram(url: String): String = try {
        val u = java.net.URI(url)
        val host = u.host ?: ""
        if (host != "t.me" && host != "telegram.me") url
        else {
            val path = (u.path ?: "").trim('/')
            val first = path.substringBefore('/')
            // 已是预览、邀请链接(+/joinchat)、或功能路径 不改
            if (path.isBlank() || first == "s" || first.startsWith("+") ||
                first in setOf("joinchat", "share", "proxy", "socks", "addstickers", "setlanguage", "login", "iv", "bg")) url
            else "https://t.me/s/$path" + (u.rawQuery?.let { "?$it" } ?: "")
        }
    } catch (_: Exception) { url }

    // evaluateJavascript 回调是 JSON 编码字符串（带引号/转义），解码回纯文本
    private fun decodeJs(raw: String): String = try { JSONArray("[$raw]").optString(0, "") } catch (_: Exception) { raw.trim('"') }

    // 小红书结构化结果 → 可读文本；标题+正文都空视为失败(返回 null 走通用兜底)
    private fun formatXhs(json: String?): String? {
        if (json.isNullOrBlank()) return null
        return try {
            val o = JSONObject(json)
            val title = o.optString("title").trim()
            val desc = o.optString("desc").trim()
            val author = o.optString("author").trim()
            if (title.isBlank() && desc.isBlank()) return null
            val imgs = o.optJSONArray("images")
            buildString {
                append("📕 ").append(title.ifBlank { "(无标题)" })
                if (author.isNotBlank()) append("  · @").append(author)
                if (desc.isNotBlank()) append("\n\n").append(desc.take(OPEN_PAGE_MAX))
                if (imgs != null && imgs.length() > 0) {
                    append("\n\n🖼️ 图片：\n")
                    append((0 until minOf(imgs.length(), 12)).joinToString("\n") { "${it + 1}. ${imgs.optString(it)}" })
                }
            }
        } catch (_: Exception) { null }
    }

    // 视频提取：走各站公开端点——VideoMeta 拿信息+封面(+B站 WBI playurl 直链)，
    // 其它站兜底从页面 HTML 抓 <video>/<source>/.mp4/.m3u8 媒体直链。不再用 yt-dlp。
    private suspend fun extractVideo(url: String): ToolResult = withContext(Dispatchers.IO) {
        val meta = com.arix.tool.search.VideoMeta.fetch(url)
        val generic = if (meta == null || !meta.contains("直链")) genericMediaLinks(url) else null
        if (meta == null && generic == null) return@withContext ToolResult("视频提取失败（该站没有公开可解析的直链，可打开页面链接观看，或在 设置→站点登录 登录后重试）", isError = true)
        ToolResult(buildString { meta?.let { append(it) }; generic?.let { append(it) } })
    }

    // 通用兜底：直连取页面 HTML，正则抓媒体直链（<video>/<source> src、.mp4/.m3u8/.webm）。
    private fun genericMediaLinks(url: String): String? = try {
        val html = directRaw(url) ?: ""
        val links = LinkedHashSet<String>()
        Regex("""<(?:video|source)[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(html).forEach { links.add(it.groupValues[1]) }
        Regex("""https?://[^"'\s<>]+\.(?:mp4|m3u8|webm)(?:\?[^"'\s<>]*)?""", RegexOption.IGNORE_CASE).findAll(html).forEach { links.add(it.value) }
        if (links.isEmpty()) null
        else "媒体直链：\n" + links.take(5).mapIndexed { i, u -> "${i + 1}. ${if (u.startsWith("http")) u else java.net.URL(java.net.URL(url), u).toString()}" }.joinToString("\n") + "\n"
    } catch (_: Exception) { null }

    // 直连取原始内容（不清洗），供 format=raw/json
    private fun directRaw(url: String): String? = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Arix/1.0)")
        if (conn.responseCode !in 200..299) { conn.disconnect(); null }
        else {
            val ct = conn.contentType ?: ""
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1) ?: "UTF-8"
            val bytes = conn.inputStream.readBytes(); conn.disconnect()
            String(bytes, try { charset(cs) } catch (_: Exception) { Charsets.UTF_8 })
        }
    } catch (_: Exception) { null }

    private fun directFetch(url: String): String? = try {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 10000; conn.readTimeout = 15000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (compatible; Arix/1.0)")
        if (conn.responseCode !in 200..299) { conn.disconnect(); null }
        else {
            val ct = conn.contentType ?: ""
            val cs = Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(ct)?.groupValues?.get(1) ?: "UTF-8"
            val bytes = conn.inputStream.readBytes(); conn.disconnect()
            htmlToText(String(bytes, try { charset(cs) } catch (_: Exception) { Charsets.UTF_8 }))
        }
    } catch (_: Exception) { null }
}
