package com.arix.tool

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

/**
 * BrowserAgent —— 参照 browser-use 的「AI 驱动浏览器」：一个**常驻的离屏 WebView 会话**，跨多次工具调用存活。
 * 每步把页面里的**可交互元素编上号**（链接/按钮/输入框/下拉…）+ 可见正文交给 AI；AI 按编号下动作
 * （点击/输入/滚动/回退/导航），执行后回读新状态，如此循环——正是浏览器智能体的核心。
 *
 * 与 open_page 的区别：open_page 是一次性抓正文、抓完即毁；这里是有状态的会话，能连续操作同一个页面
 * （登录、翻页、点进详情、填表提交…）。带上「站点登录」cookie，能访问需登录内容。
 *
 * 线程：WebView 只在主线程碰；动作串行化（一个 Mutex），避免并发操作同一页面互相打架。
 */
object BrowserAgent {
    @Volatile private var web: WebView? = null
    private val mutex = Mutex()
    @Volatile var lastUrl: String = ""
        private set

    // —— 登录态追踪（给 BrowserTool 的写操作事前确认用）——
    // 最高危路径：被注入的网页内容驱动 AI，去操纵一个**已登录**的浏览器提交表单/发帖。判「已登录」要有依据：
    //  1) assist：assist 的语义就是「拉真人登录/过验证后，AI 带着登录态接管」——真人做完 = 本会话确定带登录态。
    //  2) cookie 旁证：该站有像「会话/登录」的 cookie（CookieManager 跨会话持久，上次登录过这次也算）。
    @Volatile var assisted = false
        private set
    // assist 之后、还没被确认过的「首个写操作」——强制确认一次（哪怕用户把写操作设成了「始终允许」），
    // 堵「真人刚登录，AI 立刻被网页内容诱导乱提交」。
    @Volatile var assistWriteUnconfirmed = false
        private set

    /** assist（真人协助登录/过验证）成功后调用：标记本会话带登录态，且下一个写操作强制确认一次。 */
    fun onAssisted() { assisted = true; assistWriteUnconfirmed = true }
    /** 「assist 后首个写操作」已确认过 → 消费掉这个一次性强制标记。 */
    fun consumeAssistWriteConfirm() { assistWriteUnconfirmed = false }

    /** 当前站点大概率带登录态吗：真人在本会话协助登录过，或该站有像「会话/登录」的 cookie。 */
    fun looksLoggedIn(): Boolean = assisted || hasLoginCookie(lastUrl)

    // 只匹配 cookie **名**（=号左边），别拿整串去 contains——否则某个 cookie 的值里恰好含 "auth" 就误判。
    private val LOGIN_COOKIE_HINTS = listOf(
        "session", "sid", "ssid", "token", "auth", "login", "passport",
        "uid", "userid", "user_id", "jwt", "account", "logged", "remember", "sso",
    )
    private fun hasLoginCookie(url: String): Boolean {
        if (url.isBlank()) return false
        return try {
            val raw = android.webkit.CookieManager.getInstance().getCookie(url) ?: return false
            raw.split(";").any { pair ->
                val name = pair.substringBefore("=").trim().lowercase()
                name.isNotEmpty() && LOGIN_COOKIE_HINTS.any { name.contains(it) }
            }
        } catch (_: Exception) { false }
    }

    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Default)
    @Volatile private var lastUsed = 0L
    @Volatile private var idleJob: kotlinx.coroutines.Job? = null
    @Volatile private var docStartOk = false   // 是否用 addDocumentStartJavaScript 注入隐身脚本（否则 onPageStarted 兜底）

    /** 记一次活动并确保有个空闲看守：5 分钟不动就自动关会话，别让离屏 WebView 常驻泄漏。 */
    private fun touch() {
        lastUsed = System.currentTimeMillis()
        if (idleJob?.isActive != true) idleJob = scope.launch {
            while (isActive) {
                delay(60_000)
                if (web != null && System.currentTimeMillis() - lastUsed > 300_000) { close(); break }
            }
        }
    }

    // 视觉判定尽量不依赖 getBoundingClientRect（离屏 WebView 布局不稳）：主要靠 computed display/visibility +
    // offsetParent（display:none 的祖先会让它为 null）。列出**所有**可交互元素（不限当前视口），AI 按编号点即可。
    private const val STATE_JS = """
(function(){
  try{
    var sel='a[href],button,input:not([type=hidden]),textarea,select,[role=button],[role=link],[role=tab],[role=menuitem],[role=checkbox],[onclick],[contenteditable=true],[tabindex]:not([tabindex="-1"])';
    var seen=new Set(); var out=[]; var idx=0; var cands=[];
    document.querySelectorAll(sel).forEach(function(e){
      if(seen.has(e))return; seen.add(e);
      var st=window.getComputedStyle(e);
      if(st.display==='none'||st.visibility==='hidden'||st.opacity==='0'||e.disabled)return;
      // 布局是否出来了（离屏未 attach 的 WebView 有些机型 offsetParent 恒 null）——先只留"看得出布局"的
      var laid=(e.offsetParent!==null||e.getClientRects().length>0||st.position==='fixed'||st.position==='sticky');
      cands.push({e:e,laid:laid});
    });
    var use=cands.filter(function(c){return c.laid;});
    if(use.length===0)use=cands;   // 整页布局都没出来（离屏没布局）→ 退而列全部可交互元素，别返回空表
    use.forEach(function(c){
      var e=c.e; e.setAttribute('data-xtom-idx',idx);
      var tag=e.tagName.toLowerCase();
      var label=((e.innerText||e.value||e.getAttribute('aria-label')||e.getAttribute('placeholder')||e.getAttribute('title')||e.getAttribute('alt')||e.name||'')+'').replace(/\s+/g,' ').trim().slice(0,90);
      var type=(tag==='input')?(e.getAttribute('type')||'text'):tag;
      var o={i:idx,tag:tag,type:type,text:label};
      if(tag==='a'){o.href=(e.href||'').slice(0,200);}
      out.push(o); idx++;
    });
    var bt=document.body?document.body.innerText:'';
    bt=bt.replace(/[ \t]+/g,' ').replace(/\n{3,}/g,'\n\n').trim().slice(0,3500);
    return JSON.stringify({url:location.href,title:(document.title||'').slice(0,150),count:out.length,elements:out.slice(0,120),text:bt});
  }catch(e){return JSON.stringify({url:location.href,title:'',elements:[],text:'',err:String(e)});}
})()
"""

    const val UA = "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Mobile Safari/537.36"

    // 隐身脚本（cloak）：在页面任何脚本之前跑，抹掉「这是自动化 WebView」的破绽，伪装成真实 Chrome。
    // 参考 puppeteer-stealth 的常见补丁。里面**不能有 $**（Kotlin 原始字符串会插值）——已确认没有。
    private const val STEALTH_JS = """
(function(){ try{
  Object.defineProperty(navigator,'webdriver',{get:function(){return false;},configurable:true});
  if(!window.chrome){window.chrome={runtime:{},loadTimes:function(){},csi:function(){},app:{isInstalled:false}};}
  try{Object.defineProperty(navigator,'plugins',{get:function(){return [1,2,3,4,5].map(function(i){return {name:'Plugin '+i,filename:'internal-'+i,description:''};});}});}catch(e){}
  try{Object.defineProperty(navigator,'mimeTypes',{get:function(){return [{type:'application/pdf'},{type:'application/x-google-chrome-pdf'}];}});}catch(e){}
  try{Object.defineProperty(navigator,'languages',{get:function(){return ['zh-CN','zh','en-US','en'];}});}catch(e){}
  try{Object.defineProperty(navigator,'platform',{get:function(){return 'Linux armv8l';}});}catch(e){}
  try{Object.defineProperty(navigator,'vendor',{get:function(){return 'Google Inc.';}});}catch(e){}
  try{Object.defineProperty(navigator,'hardwareConcurrency',{get:function(){return 8;}});}catch(e){}
  try{Object.defineProperty(navigator,'deviceMemory',{get:function(){return 8;}});}catch(e){}
  try{Object.defineProperty(navigator,'maxTouchPoints',{get:function(){return 5;}});}catch(e){}
  try{ var op=navigator.permissions&&navigator.permissions.query; if(op){navigator.permissions.query=function(p){ return (p&&p.name==='notifications')?Promise.resolve({state:(window.Notification&&Notification.permission)||'default'}):op.call(navigator.permissions,p); }; } }catch(e){}
  try{ var gp=WebGLRenderingContext.prototype.getParameter; WebGLRenderingContext.prototype.getParameter=function(p){ if(p===37445)return 'Qualcomm'; if(p===37446)return 'Adreno (TM) 730'; return gp.call(this,p); }; }catch(e){}
  try{ if(window.WebGL2RenderingContext){ var g2=WebGL2RenderingContext.prototype.getParameter; WebGL2RenderingContext.prototype.getParameter=function(p){ if(p===37445)return 'Qualcomm'; if(p===37446)return 'Adreno (TM) 730'; return g2.call(this,p); }; } }catch(e){}
  ['cdc_adoQpoasnfa76pfcZLmcfl_Array','cdc_adoQpoasnfa76pfcZLmcfl_Promise','cdc_adoQpoasnfa76pfcZLmcfl_Symbol','__webdriver_evaluate','__selenium_evaluate','__webdriver_script_function','__driver_evaluate','__webdriver_script_func','_Selenium_IDE_Recorder','__fxdriver_evaluate','__driver_unwrapped','__webdriver_unwrapped'].forEach(function(k){try{delete window[k];delete document[k];}catch(e){}});
}catch(e){} })();
"""

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWeb(context: Context): WebView {
        val wv = WebView(context.applicationContext)
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        runCatching { android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true) }
        wv.settings.javaScriptEnabled = true
        wv.settings.domStorageEnabled = true
        // ⚠ 安全加固：targetSdk=28 下 allowFileAccess/allowContentAccess **默认是 true**
        // （false 那个默认值只对 targetSdk≥30 生效）。这个 WebView 是**模型自主导航**的：
        // 它去哪个页面由 AI 决定，而 AI 的判断可能被搜索结果里的注入内容牵着走。
        // 不关的话，一个恶意/被注入的页面能用脚本读 file:///data/data/... 与我们自己的 ContentProvider。
        // 与 BrowserPage.kt / MarkdownText.kt 同一口径。
        wv.settings.allowFileAccess = false
        wv.settings.allowContentAccess = false
        wv.settings.allowFileAccessFromFileURLs = false
        wv.settings.allowUniversalAccessFromFileURLs = false
        wv.settings.databaseEnabled = false            // WebSQL 已废弃、干活用不上；关掉少一处站点持久写盘面（登录态走 cookie，不受影响）
        disableServiceWorkers()                        // 拦掉 Service Worker 网络请求：别让被注入的页面注册常驻 SW 持久重跑脚本
        wv.settings.userAgentString = UA
        wv.settings.loadsImagesAutomatically = false   // 离屏、只要结构/文本，省流量省内存
        wv.settings.javaScriptCanOpenWindowsAutomatically = false
        // 隐身（cloak）：文档里任何脚本跑之前就注入反检测补丁，把 WebView 伪装成真实 Chrome，
        // 绕开 navigator.webdriver / 缺 window.chrome / WebGL 指纹 / chromedriver 残留等自动化检测。
        docStartOk = runCatching {
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                androidx.webkit.WebViewCompat.addDocumentStartJavaScript(wv, STEALTH_JS, setOf("*")); true
            } else false
        }.getOrDefault(false)
        wv.webViewClient = stealthClient()             // 页内导航都留在本 WebView + 兜底注入隐身脚本
        // 给离屏 WebView 一个视口尺寸并强制测量/布局，否则未 attach 到窗口时 innerWidth/offsetParent 等布局信息不可用，
        // 页面按 0 宽渲染、可交互元素判定全废。测量成手机竖屏尺寸。
        runCatching {
            val w = android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY)
            val h = android.view.View.MeasureSpec.makeMeasureSpec(2160, android.view.View.MeasureSpec.EXACTLY)
            wv.measure(w, h); wv.layout(0, 0, 1080, 2160)
        }
        return wv
    }

    // Service Worker 收敛：SW 一旦注册就能常驻、离屏拦网络、缓存重跑脚本；常驻会话也不需要它，全局拦掉其网络请求。
    // ServiceWorkerController 进程级全局、重复设置幂等；旧内核不支持则静默跳过。不碰 cookie，登录态照常。
    private fun disableServiceWorkers() {
        runCatching {
            if (!androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
            val swc = androidx.webkit.ServiceWorkerControllerCompat.getInstance()
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SERVICE_WORKER_BLOCK_NETWORK_LOADS))
                swc.serviceWorkerWebSettings.setBlockNetworkLoads(true)
            if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) {
                swc.setServiceWorkerClient(object : androidx.webkit.ServiceWorkerClientCompat() {
                    override fun shouldInterceptRequest(request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse =
                        android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                })
            }
        }
    }

    /** 页内导航留本 WebView；DOCUMENT_START_SCRIPT 不支持时用 onPageStarted 兜底注入隐身脚本（略晚，聊胜于无）。 */
    private fun stealthClient(): WebViewClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            if (!docStartOk) view.evaluateJavascript(STEALTH_JS, null)
        }
    }

    /** evaluateJavascript 回调是 JSON 编码串，解码回原文。 */
    private fun decode(raw: String): String = try { JSONArray("[$raw]").optString(0, "") } catch (_: Exception) { raw.trim('"') }

    /** 主线程执行 [pre]（可空），等 [waitMs] 让页面反应，再抽取状态 JSON。 */
    private suspend fun runAndReadState(context: Context, waitMs: Long, pre: ((WebView) -> Unit)?): String =
        withContext(Dispatchers.Main) {
            touch()
            val wv = web ?: buildWeb(context).also { web = it }
            pre?.invoke(wv)
            withTimeoutOrNull(25000) {
                suspendCancellableCoroutine<String> { cont ->
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!cont.isActive) return@postDelayed
                        wv.evaluateJavascript(STATE_JS) { raw ->
                            if (cont.isActive) cont.resume(decode(raw))
                        }
                    }, waitMs)
                }
            } ?: "{\"err\":\"超时\"}"
        }

    /** 导航到 URL：等 onPageFinished（+缓冲）后读状态；带超时兜底。 */
    suspend fun navigate(context: Context, url: String): String = mutex.withLock {
        // SSRF 前置闸门：常驻会话同样别被注入内容诱导去打本机/局域网/云元数据。拒绝走 err 字段，让 formatState 显示为操作出错。
        withContext(Dispatchers.IO) { WebGuard.check(url) }?.let {
            return@withLock JSONObject().put("err", "拒绝导航：$it（不访问内网/本机/云元数据地址）").toString()
        }
        lastUrl = url; touch()
        withContext(Dispatchers.Main) {
            val wv = web ?: buildWeb(context).also { web = it }
            withTimeoutOrNull(30000) {
                suspendCancellableCoroutine<String> { cont ->
                    var fired = false
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                            if (!docStartOk) view.evaluateJavascript(STEALTH_JS, null)
                        }
                        override fun onPageFinished(view: WebView, u: String) {
                            if (fired) return; fired = true
                            Handler(Looper.getMainLooper()).postDelayed({
                                if (!cont.isActive) return@postDelayed
                                view.evaluateJavascript(STATE_JS) { raw -> if (cont.isActive) cont.resume(decode(raw)) }
                            }, 1300)
                        }
                    }
                    wv.loadUrl(url)
                }
            } ?: "{\"err\":\"页面加载超时\"}"
        }
    }

    suspend fun state(context: Context): String = mutex.withLock { runAndReadState(context, 250, null) }

    suspend fun click(context: Context, idx: Int): String = mutex.withLock {
        runAndReadState(context, 1500) { wv ->
            wv.evaluateJavascript(
                "(function(){var e=document.querySelector('[data-xtom-idx=\"$idx\"]');if(e){e.scrollIntoView({block:'center'});e.click();return 'ok';}return 'no';})()",
                null
            )
        }
    }

    suspend fun type(context: Context, idx: Int, text: String, enter: Boolean): String = mutex.withLock {
        val t = JSONObject.quote(text)
        runAndReadState(context, if (enter) 1500 else 400) { wv ->
            wv.evaluateJavascript(
                "(function(){var e=document.querySelector('[data-xtom-idx=\"$idx\"]');if(!e)return 'no';e.focus();" +
                    "if(e.isContentEditable){e.innerText=$t;}else{e.value=$t;}" +
                    "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));" +
                    (if (enter) "var f=e.form;e.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));if(f&&f.requestSubmit){try{f.requestSubmit();}catch(x){f.submit&&f.submit();}}" else "") +
                    "return 'ok';})()",
                null
            )
        }
    }

    suspend fun scroll(context: Context, down: Boolean): String = mutex.withLock {
        runAndReadState(context, 500) { wv ->
            wv.evaluateJavascript("window.scrollBy(0, ${if (down) "" else "-"}Math.round((window.innerHeight||600)*0.85));", null)
        }
    }

    suspend fun back(context: Context): String = mutex.withLock {
        runAndReadState(context, 1300) { wv -> if (wv.canGoBack()) wv.goBack() }
    }

    /** 关会话：走 mutex 串行化，绝不与在途动作并发销毁（否则在途 evaluateJavascript 撞已销毁 WebView 崩溃）。 */
    suspend fun close() = mutex.withLock {
        val w = web; web = null; lastUrl = ""
        assisted = false; assistWriteUnconfirmed = false   // 会话结束 = 登录态上下文清零
        idleJob?.cancel(); idleJob = null
        withContext(Dispatchers.Main) { runCatching { w?.destroy() } }
    }

    /** 把状态 JSON 渲染成给 AI 看的紧凑文本：URL/标题 + 编号元素表 + 可见正文。 */
    fun formatState(json: String): String {
        return try {
            val o = JSONObject(json)
            o.optString("err").takeIf { it.isNotBlank() }?.let { return "浏览器操作出错：$it" }
            val sb = StringBuilder()
            sb.append("URL: ").append(o.optString("url")).append('\n')
            o.optString("title").takeIf { it.isNotBlank() }?.let { sb.append("标题: ").append(it).append('\n') }
            val els = o.optJSONArray("elements") ?: JSONArray()
            // 区分措辞：编号元素表是「给你按用户任务操作」用的（照点/照填别怕）；页面文字才是不可信外部数据（见下方围栏）。
            sb.append("\n可交互元素（编号是给你按用户任务操作用的：可点/可填；不是外部数据）:\n")
            if (els.length() == 0) sb.append("（本页没抽到可交互元素）\n")
            for (i in 0 until els.length()) {
                val e = els.optJSONObject(i) ?: continue
                sb.append('[').append(e.optInt("i")).append("] ").append(e.optString("type"))
                e.optString("text").takeIf { it.isNotBlank() }?.let { sb.append(" \"").append(it).append('"') }
                sb.append('\n')
            }
            val total = o.optInt("count", els.length())
            if (total > els.length()) sb.append("…(还有 ").append(total - els.length()).append(" 个未列，可滚动查看)\n")
            // 页面文字内容 = 不可信外部数据，可能夹带冲你来的指令：只当资料读、别执行（元素表不套围栏，否则 AI 不敢点）。
            o.optString("text").takeIf { it.isNotBlank() }?.let { sb.append('\n').append(UntrustedWeb.fence(it, "网页正文")) }
            sb.toString()
        } catch (_: Exception) { "浏览器状态解析失败" }
    }
}
