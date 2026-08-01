package com.arix.app

import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray

/**
 * 隐身取页服务 —— **跑在 `:incognito` 进程里**（见 AndroidManifest 的 `android:process`）。
 *
 * 它存在的唯一理由是进程隔离：这个进程的 WebView 用的是自己那份 cookie 库
 * （[IncognitoWeb.bootstrapIfIncognito] 在 Application.onCreate 第一行换的数据目录），
 * 所以既不会带出主进程里用户的登录态，也不会把本次访问留下的痕迹写回去。
 *
 * ⚠ 它**故意不复用** [com.arix.tool.OpenPageTool] 那一套站点特化逻辑（小红书 __INITIAL_STATE__、
 * Telegram 地址归一化、Service Worker 拦截、直连兜底、第三方 extract 兜底）。原因：那些逻辑住在
 * 主进程的工具层、还要读配置和网络客户端，整套搬过来等于让隐身进程也依赖半个 App。
 * 这里只做最普适的一件事：加载页面、跑一段 JS 取文本。调用方要更强的解析，
 * 应该把 JS 表达式通过 [IIncognitoFetch.fetchText] 的 `jsExpr` 传进来。
 */
class IncognitoFetchService : Service() {

    private val main = Handler(Looper.getMainLooper())

    /** 默认取正文：和 OpenPageTool 的 TEXT_JS 同一条表达式（那边是 private，这里不跨进程共享常量）。 */
    private val defaultJs = "(function(){return document.body?document.body.innerText:''})()"

    override fun onBind(intent: Intent?): IBinder = binder

    private val binder = object : IIncognitoFetch.Stub() {

        override fun fetchText(url: String?, timeoutMs: Int, jsExpr: String?, cb: IIncognitoFetchCallback?) {
            val callback = cb ?: return
            val target = url?.trim().orEmpty()
            // 只放 http(s)。别让 file:// / content:// 从这儿变成"读本机任意文件"的入口——
            // 这个进程能被主进程用任意 url 调用，校验必须在**被调方**做。
            if (!target.startsWith("https://", true) && !target.startsWith("http://", true)) {
                safeResult(callback, null, "只支持 http/https 地址")
                return
            }
            main.post { load(target, timeoutMs.coerceIn(3_000, 60_000), jsExpr ?: defaultJs, callback) }
        }

        override fun wipe() {
            main.post {
                runCatching { CookieManager.getInstance().removeAllCookies(null) }
                runCatching { CookieManager.getInstance().flush() }
                runCatching { WebView(this@IncognitoFetchService).clearCache(true) }
                runCatching { android.webkit.WebStorage.getInstance().deleteAllData() }
            }
        }
    }

    /** 主线程加载。WebView 只能在主线程碰（这里的"主线程"是**隐身进程自己的**主线程）。 */
    private fun load(url: String, timeoutMs: Int, jsExpr: String, cb: IIncognitoFetchCallback) {
        var web: WebView? = null
        // 只回一次：超时和 onPageFinished 可能都到，谁先到算谁
        val done = java.util.concurrent.atomic.AtomicBoolean(false)
        var timeoutTask: Runnable? = null

        fun finish(text: String?, error: String?) {
            if (!done.compareAndSet(false, true)) return
            timeoutTask?.let { main.removeCallbacks(it) }
            // 必须先摘 client 再 destroy：destroy 之后再回调会 crash
            web?.let { w -> runCatching { w.stopLoading(); w.webViewClient = WebViewClient(); w.destroy() } }
            web = null
            safeResult(cb, text, error)
        }

        try {
            val w = WebView(this)
            web = w
            w.settings.apply {
                javaScriptEnabled = true
                // 隐身：不留缓存、不留 DOM storage、不存表单。
                // 注意这些**不是**隐身的关键（关键是进程级的 cookie 库隔离），
                // 但既然是一次性取页，留着也没用，白白落盘。
                cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
                domStorageEnabled = false
                databaseEnabled = false
                saveFormData = false
                loadsImagesAutomatically = false   // 只要正文，图片纯浪费流量与内存
                blockNetworkImage = true
            }
            // 三方 cookie 关掉（这一项确实是按 WebView 生效的）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                runCatching { CookieManager.getInstance().setAcceptThirdPartyCookies(w, false) }
            }
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    // 图片/字体/媒体一律不取：一次性取正文用不上，且能明显压低加载时间与内存
                    val u = request.url?.toString()?.lowercase().orEmpty()
                    val blocked = Regex("\\.(png|jpe?g|gif|webp|bmp|ico|svg|woff2?|ttf|otf|mp4|webm|mp3|m4a)(\\?|$)")
                    if (blocked.containsMatchIn(u)) return WebResourceResponse("text/plain", "utf-8", null)
                    return null
                }

                override fun onPageFinished(view: WebView, u: String) {
                    // 等一会儿让 JS 把动态内容渲染出来再取（同 OpenPageTool 的理由）
                    main.postDelayed({
                        if (done.get()) return@postDelayed
                        runCatching {
                            view.evaluateJavascript(jsExpr) { raw ->
                                finish(decodeJs(raw).takeIf { it.isNotBlank() }, null)
                            }
                        }.onFailure { finish(null, "取正文失败：${it.message}") }
                    }, 1200)
                }

                override fun onReceivedError(
                    view: WebView, request: WebResourceRequest, error: android.webkit.WebResourceError,
                ) {
                    // 只认主文档的错误。子资源失败（我们自己还拦掉了一堆图片）不该判整页失败
                    if (request.isForMainFrame) finish(null, "页面加载失败：${error.description}")
                }
            }
            timeoutTask = Runnable { finish(null, "取页超时（${timeoutMs / 1000}s）") }
            main.postDelayed(timeoutTask, timeoutMs.toLong())
            w.loadUrl(url)
        } catch (t: Throwable) {
            finish(null, "隐身取页起不来：${t.message}")
        }
    }

    /** evaluateJavascript 回调给的是 JSON 编码串（带引号/转义），解回纯文本。 */
    private fun decodeJs(raw: String?): String =
        try { JSONArray("[${raw.orEmpty()}]").optString(0, "") } catch (_: Exception) { raw.orEmpty().trim('"') }

    /** 回调对面可能已经死了（主进程被杀/调用方取消），DeadObjectException 不该把这个进程带崩。 */
    private fun safeResult(cb: IIncognitoFetchCallback, text: String?, error: String?) {
        runCatching { cb.onResult(text, error) }
    }
}
