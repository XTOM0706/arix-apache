package com.arix.tool

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.JavascriptInterface
import android.webkit.WebView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ============================================================
// JS 插件运行时 —— 用 WebView 的 JS 引擎跑 Operit/沙盒插件的 main.js（DESIGN.md §9）。
// 取代 OperitCompatTool 的"占位"。插件用 xtom.registerTool 注册工具、xtom.callTool 反调 Arix 原生工具。
//
// 桥（AndroidBridge）：
//   ready()               bootstrap 就绪
//   done(iid, result)     插件工具 handler 完成 → 回填 invoke 的结果
//   callTool(id,n,args)   插件反调原生工具 → 跑 ToolManager 工具 → __resolve 回填 JS
//   log(m)                插件日志
// 线程：WebView 只在主线程碰；工具执行在 IO 协程；invoke 用 CompletableDeferred 等 JS 异步结果。
// 安全：装前扫描（SkillSecurityScan）对 .toolpkg/.js 与 GitHub skill 两条路都做了（DANGER 拒装）；
//   但扫描是启发式正则、可被混淆绕过，不是唯一防线——真正的隔离靠 ToolManager 权限闸（反调原生工具
//   一律经它、按 caller 审批）+ bootstrap 末尾锁死的运行时全局（防同 realm 插件互相截令牌/改调用）。
//   注：多插件仍共用一个 WebView realm，彻底隔离需每插件单开 WebView（见 tokens 字段注释）。
// ============================================================
object JsPluginRuntime {
    @Volatile private var web: WebView? = null
    @Volatile private var ready = false
    private var appContext: Context? = null
    private val plugins = ConcurrentHashMap<String, String>()   // pluginId -> mainJs（跨线程：refresh 在 IO 改、ensureWeb 在 Main 读）
    private val seq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()  // invokeId -> result
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val webMutex = kotlinx.coroutines.sync.Mutex()   // 串行化 WebView 冷启动，防并发建俩
    private const val SEP = "\u0001"   // pluginId 与工具名的分隔符（隔离不同插件的同名工具）

    /**
     * 能力令牌 → pluginId。**插件身份的唯一凭据**。
     *
     * 为什么不让 JS 自己报 id：JS 报什么都行，那不叫身份认证叫填表。令牌由 Kotlin 用
     * SecureRandom 铸出来，注入时封进该插件的闭包里（见 evalPlugin），JS 只能回传它拿到的
     * 那一个；Kotlin 查这张表反解出是谁。插件猜不到别人的令牌。
     *
     * **已知残留风险**：所有插件跑在同一个 WebView realm 里，先注入的插件可以改写
     * window.__mk / AndroidBridge 去截后注入插件的令牌。要根治得给每个插件单开 WebView。
     * 令牌把「零门槛冒充」抬到了「得先注入且主动改全局」，但它不是隔离。
     */
    private val tokens = ConcurrentHashMap<String, String>()      // token -> pluginId
    private val tokenOf = ConcurrentHashMap<String, String>()     // pluginId -> token
    private val trusted = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val rng = java.security.SecureRandom()

    /** 令牌 → 调用者身份。认不出令牌（老插件走 legacy 全局）一律按未署名插件，绝不当 AI。 */
    private fun callerOf(token: String): ToolCaller {
        val pid = tokens[token] ?: return ToolCaller.Plugin("unknown")
        return if (pid in trusted) ToolCaller.UserScript(pid) else ToolCaller.Plugin(pid)
    }

    private fun tokenFor(pluginId: String): String = tokenOf.getOrPut(pluginId) {
        val b = ByteArray(16).also { rng.nextBytes(it) }
        android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
            .also { tokens[it] = pluginId }
    }

    fun init(context: Context) { appContext = context.applicationContext }

    /**
     * 注册/更新一个插件的 main.js（refresh 时对每个 sandbox 包调用）。已就绪则立即注入。
     *
     * [userAuthored] = 这段 JS 是用户自己写的（JS 搜索源），权限上等同 AI；默认 false = 第三方包，
     * 按插件单独审批。**由 Kotlin 调用方指定，不从 pluginId 的字样去猜**——包 id 出自 manifest，
     * 是包作者写的，靠前缀判信任等于让攻击者自己填「我可信」。
     */
    fun register(pluginId: String, mainJs: String, userAuthored: Boolean = false) {
        if (mainJs.isBlank()) return
        plugins[pluginId] = mainJs
        if (userAuthored) trusted.add(pluginId) else trusted.remove(pluginId)
        val w = web
        if (ready && w != null) scope.launch(Dispatchers.Main) { evalPlugin(w, pluginId, mainJs) }
    }

    // 框架包（.toolpkg）源：pkgId -> (全部文件, main 相对路径)。WebView 被回收重建时据此重灌钩子。
    private val fwPackages = ConcurrentHashMap<String, Pair<Map<String, String>, String>>()

    fun clear() {
        plugins.clear(); tokens.clear(); tokenOf.clear(); trusted.clear(); fwPackages.clear(); activeHookTypes.clear(); hookOwners.clear()
        // 同步清 JS 侧运行时状态（钩子/模块缓存），否则 refresh 后钩子翻倍、跑旧模块。WebView 起着才需要。
        val w = web
        if (ready && w != null) scope.launch(Dispatchers.Main) { runCatching { w.evaluateJavascript("if(window.__resetRuntime)window.__resetRuntime(${JSONObject.quote(bootNonce)});", null) } }
    }

    private fun evalPlugin(w: WebView, pluginId: String, js: String) {
        // 把该插件专属的 xtom 对象（内含它的 pid 与能力令牌）作为**闭包参数**传进去，
        // 而不是让它读全局 window.xtom——全局的谁都能读，闭包的只有它自己有。
        // 老插件里直接写 window.xtom.xxx 的仍能跑：那条路见 BOOTSTRAP 的 legacy 全局，
        // 只是拿不到令牌，权限上按「未署名插件」处理。
        val pidQ = JSONObject.quote(pluginId)
        val xtom = "window.__mk($pidQ,${JSONObject.quote(tokenFor(pluginId))})"
        // window.xtom 同时重绑到本插件的**无令牌**版本：老插件写 window.xtom.registerTool 的
        // 仍能注册进自己的命名空间（不然工具名对不上，invoke 找不到 handler）；而它的 callTool
        // 无令牌 → 权限上按未署名插件走。想要按包记权限，插件得用闭包参数里的 xtom。
        val wrapped = "window.xtom=window.__mk($pidQ,'');" +
            "try{(function(xtom){\n$js\n})($xtom);}catch(e){AndroidBridge.log('plugin $pluginId error: '+e);}"
        w.evaluateJavascript(wrapped, null)
    }

    /**
     * 执行 **Operit 传统脚本包** 的一个工具。与 xtom.registerTool 那套不同：Operit 包是 CommonJS 模块，
     * 工具 = 模块的 `exports.<fn>(params)`，结果走 `complete(v)` 或返回值（Promise 则 await）。
     * moduleId 用来在 JS 侧缓存已实例化的模块；source 每次都带（WebView 被回收重建时也能重新加载）。
     */
    suspend fun invokeOperit(pkgId: String, moduleId: String, source: String, fn: String, params: JSONObject, timeoutMs: Long = 60000): String {
        if (!ensureWeb()) return "插件运行时不可用（WebView 初始化失败）"
        val iid = "i" + seq.getAndIncrement()
        val def = CompletableDeferred<String>()
        pending[iid] = def
        withContext(Dispatchers.Main) {
            web?.evaluateJavascript(
                // 【安全 #6】铸/取本包能力令牌一并传入，让包里的 toolCall/Tools 带真身份反调（而非硬编码空令牌）
                "__invokeOperit(${JSONObject.quote(iid)},${JSONObject.quote(pkgId)},${JSONObject.quote(moduleId)},${JSONObject.quote(source)},${JSONObject.quote(fn)},${JSONObject.quote(params.toString())},${JSONObject.quote(tokenFor(pkgId))})",
                null
            )
        }
        val r = withTimeoutOrNull(timeoutMs) { def.await() }
        pending.remove(iid)
        return r ?: "插件工具执行超时"
    }

    // 已注册的框架钩子类型（Kotlin 侧由包 JS 里 register<Type> 的字样扫出）。调用方据此跳过"没人注册"的钩子，
    // 免每条消息都往 JS 空跑一趟。
    val activeHookTypes = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    /**
     * 钩子类型 → 注册它的框架包 pkgId 集合（pkgId 形如 "toolpkg_<id>"）。
     * 与 [activeHookTypes] 同源：OperitCompat 扫描 .toolpkg 时一起填，[clear] 一起清。
     * 用来对**敏感钩子**（改写系统提示 / 拦截代答）按「是哪个包注册的」逐包门控——
     * 光知道「有人注册了这类钩子」不够，得知道是谁，才能只放行用户信任的那个包。
     */
    val hookOwners = ConcurrentHashMap<String, MutableSet<String>>()

    /** 加载一个 .toolpkg 框架包：把它的全部 JS 文件喂进去，跑 main 的 registerToolPkg() 捕获钩子。 */
    suspend fun loadFrameworkPkg(pkgId: String, files: Map<String, String>, mainRel: String): Boolean {
        fwPackages[pkgId] = files to mainRel   // 记下以便 WebView 重建后重灌
        if (!ensureWeb()) return false
        val filesObj = JSONObject(); files.forEach { (k, v) -> filesObj.put(k, v) }
        val iid = "i" + seq.getAndIncrement()
        val def = CompletableDeferred<String>()
        pending[iid] = def
        withContext(Dispatchers.Main) {
            web?.evaluateJavascript(
                "__loadFrameworkPkg(${JSONObject.quote(iid)},${JSONObject.quote(pkgId)},${JSONObject.quote(filesObj.toString())},${JSONObject.quote(mainRel)},${JSONObject.quote(tokenFor(pkgId))},${JSONObject.quote(bootNonce)})",
                null
            )
        }
        val r = withTimeoutOrNull(15000) { def.await() }
        pending.remove(iid)
        return r == "true"
    }

    /**
     * 触发某类框架钩子。mode: "fire"/"first"/"collect"/"chainString:<field>"。返回 JS 的 JSON 结果串（超时/无则 "null"）。
     *
     * [allowPkgs]：只放行这些 pkgId 注册的钩子（按 h.pkgId 过滤）。null = 不限制（原行为）；
     * 空集 = 一个都不跑。用于敏感钩子（系统提示改写 / 消息拦截代答）的逐包门控——
     * 上层已按用户授权算出获准的包，这里把没获准的包在 JS 侧滤掉。
     */
    suspend fun fireHook(type: String, eventJson: String, mode: String, arg: String? = null, timeoutMs: Long = 15000, allowPkgs: Set<String>? = null): String {
        if (!ready || web == null) return "null"   // 运行时没起来就别等（钩子是可选增强，不该拖慢主链路）
        val allowJson = allowPkgs?.let { org.json.JSONArray(it.toList()).toString() }
        val iid = "i" + seq.getAndIncrement()
        val def = CompletableDeferred<String>()
        pending[iid] = def
        withContext(Dispatchers.Main) {
            web?.evaluateJavascript(
                "__fireHook(${JSONObject.quote(iid)},${JSONObject.quote(type)},${JSONObject.quote(eventJson)},${JSONObject.quote(mode)},${if (arg == null) "null" else JSONObject.quote(arg)},${if (allowJson == null) "null" else JSONObject.quote(allowJson)})",
                null
            )
        }
        val r = withTimeoutOrNull(timeoutMs) { def.await() }
        pending.remove(iid)
        return r ?: "null"
    }

    /** 调用某插件工具；pluginId+originalName 定位到该插件注册的 handler（避免跨插件同名冲突）。 */
    suspend fun invoke(pluginId: String, originalName: String, params: JSONObject, timeoutMs: Long = 30000): String {
        if (!ensureWeb()) return "插件运行时不可用（WebView 初始化失败）"
        val iid = "i" + seq.getAndIncrement()
        val key = pluginId + SEP + originalName
        val def = CompletableDeferred<String>()
        pending[iid] = def
        withContext(Dispatchers.Main) {
            web?.evaluateJavascript(
                "__invoke(${JSONObject.quote(iid)},${JSONObject.quote(key)},${JSONObject.quote(params.toString())})",
                null
            )
        }
        val r = withTimeoutOrNull(timeoutMs) { def.await() }
        pending.remove(iid)
        return r ?: "插件工具执行超时"
    }

    fun isPluginLoaded(): Boolean = plugins.isNotEmpty()

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface", "AddJavascriptInterface")
    private suspend fun ensureWeb(): Boolean = webMutex.withLock {
      withContext(Dispatchers.Main) {
        if (ready && web != null) return@withContext true
        val ctx = appContext ?: return@withContext false
        val readyDef = CompletableDeferred<Boolean>()
        try {
            val wv = WebView(ctx)
            wv.settings.javaScriptEnabled = true
            wv.settings.domStorageEnabled = true
            // ⚠ 安全加固：targetSdk=28 下这两个默认 true（false 只对 targetSdk≥30 生效）。
            // 这个 WebView 跑的是**第三方插件 JS**。插件本来受权限系统约束（调工具要过闸），
            // 但 file:// / content:// 是绕开工具层直接读盘的旁路——权限系统在那条路上根本不在场。
            // 与 BrowserPage.kt / BrowserAgent.kt / OpenPageTool.kt 同一口径。
            wv.settings.allowFileAccess = false
            wv.settings.allowContentAccess = false
            wv.settings.allowFileAccessFromFileURLs = false
            wv.settings.allowUniversalAccessFromFileURLs = false
            // 渲染进程被系统回收(低内存)→重置状态让下次 ensureWeb 重建，否则每次调用都空等到超时
            wv.webViewClient = object : android.webkit.WebViewClient() {
                override fun onRenderProcessGone(view: WebView?, detail: android.webkit.RenderProcessGoneDetail?): Boolean {
                    ready = false
                    try { web?.destroy() } catch (_: Exception) {}
                    web = null
                    pending.values.forEach { it.complete("插件运行时已重置（WebView 渲染进程被回收），请重试") }
                    pending.clear()
                    return true
                }
            }
            wv.addJavascriptInterface(object {
                @JavascriptInterface fun ready() { ready = true; if (!readyDef.isCompleted) readyDef.complete(true) }
                @JavascriptInterface fun done(iid: String, result: String) { pending[iid]?.complete(result) }
                @JavascriptInterface fun log(m: String) { android.util.Log.d("JsPlugin", m) }
                // 框架包的每包配置（Operit 的 getEnv / Tools.SoftwareSettings.writeEnvironmentVariable）→ 落每包 SharedPreferences。
                @JavascriptInterface fun envGet(pkgId: String, key: String): String =
                    appContext?.getSharedPreferences("xtom_toolpkg_env_" + pkgId.replace(Regex("[^a-zA-Z0-9_]"), "_"), Context.MODE_PRIVATE)?.getString(key, "") ?: ""
                @JavascriptInterface fun envSet(pkgId: String, key: String, value: String) {
                    appContext?.getSharedPreferences("xtom_toolpkg_env_" + pkgId.replace(Regex("[^a-zA-Z0-9_]"), "_"), Context.MODE_PRIVATE)?.edit()?.putString(key, value)?.apply()
                }
                // 插件反调 Arix 原生工具。
                //
                // **必须走 ToolManager.execute**，不能 ToolManager.get(name).execute(args)——
                // 权限闸只长在 execute 里。这里以前正是直接 get+execute，等于插件 JS 可以
                // 无弹窗跑 shell、无视用户设的「始终禁止」。别再图省事绕回去。
                @JavascriptInterface fun callTool(id: String, name: String, argsJson: String, tok: String) {
                    scope.launch {
                        val res = try {
                            // 令牌反解身份；解不出（老插件走 legacy 全局）就归为未署名插件，
                            // 仍然按插件策略审批，绝不当成 AI——AI 是用户直接驱动的，插件不是。
                            val out = ToolManager.execute(
                                ToolCall(id = id, name = name,
                                    arguments = JSONObject(argsJson.ifBlank { "{}" }),
                                    caller = callerOf(tok))
                            )
                            if (out.isError) errJson(out.content) else out.content
                        } catch (e: Exception) { errJson(e.message ?: "callTool 失败") }
                        withContext(Dispatchers.Main) {
                            web?.evaluateJavascript("__resolve(${JSONObject.quote(id)},${JSONObject.quote(res)})", null)
                        }
                    }
                }
            }, "AndroidBridge")
            web = wv
            wv.loadDataWithBaseURL(null, BOOTSTRAP, "text/html", "UTF-8", null)
        } catch (_: Exception) {
            return@withContext false
        }
        val ok = withTimeoutOrNull(6000) { readyDef.await() } ?: false
        if (ok) {
            // 就绪后把已注册插件全部注入（含 WebView 被回收后重建的情形）
            web?.let { w -> plugins.forEach { (id, js) -> evalPlugin(w, id, js) } }
            // 框架包同理重灌（钩子存在 JS 里，WebView 一重建就没了）。fire-and-forget，iid 用固定占位。
            web?.let { w -> fwPackages.forEach { (id, pf) ->
                val fo = JSONObject(); pf.first.forEach { (k, v) -> fo.put(k, v) }
                w.evaluateJavascript("__loadFrameworkPkg(${JSONObject.quote("reinit")},${JSONObject.quote(id)},${JSONObject.quote(fo.toString())},${JSONObject.quote(pf.second)},${JSONObject.quote(tokenFor(id))},${JSONObject.quote(bootNonce)})", null)
            } }
        }
        ok
      }
    }

    private fun errJson(msg: String) = JSONObject().put("error", msg).toString()

    // 引导口令：每进程随机。嵌进 bootstrap 闭包私有变量（window.__bn 捕获后即删），插件读不到、猜不到；
    // Kotlin 每次调 __loadFrameworkPkg/__resetRuntime 都带上它，JS 侧核对相符才认——杜绝插件自己触发
    // 「以别的包名 + 自带文件加载」来把恶意钩子登记成受信包（第三轮红队 #A 根治的一环）。
    private val bootNonce: String = ByteArray(18).let { rng.nextBytes(it); android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE) }

    // 非 const（运行时 val）：这样才能引用后面声明的 const OPERIT_JS（const 引用后声明的 const 会报「must be initialized」）。
    private val BOOTSTRAP =
        "<!doctype html><html><head><meta charset=\"utf-8\"></head><body><script>\n" +
        "window.__t={};window.__p={};window.__s=1;\n" +
        // 造一个绑定到某插件的 xtom：pid 与令牌封在闭包里，注入时当参数交给插件。
        "window.__mk=function(pid,tok){return{\n" +
        " registerTool:function(n,m,h){window.__t[pid+'\\u0001'+n]={m:m||{},h:h};},\n" +
        " callTool:function(n,a){return new Promise(function(res){var id='c'+(window.__s++);window.__p[id]=res;AndroidBridge.callTool(id,n,JSON.stringify(a||{}),tok);});},\n" +
        " log:function(m){AndroidBridge.log(String(m));}\n" +
        "};};\n" +
        // window.xtom 由 evalPlugin 在注入每个插件前重新绑定（见那里），此处只兜底占位。
        "window.xtom=window.__mk('','');\n" +
        "window.__resolve=function(id,r){var f=window.__p[id];if(f){delete window.__p[id];f(r);}};\n" +
        "window.__invoke=function(iid,n,aj){try{var t=window.__t[n];if(!t){AndroidBridge.done(iid,JSON.stringify({error:'no plugin tool '+n}));return;}\n" +
        " var a=JSON.parse(aj||'{}');Promise.resolve(t.h(a)).then(function(r){AndroidBridge.done(iid,(typeof r==='string')?r:JSON.stringify(r));}).catch(function(e){AndroidBridge.done(iid,JSON.stringify({error:String(e)}));});}\n" +
        " catch(e){AndroidBridge.done(iid,JSON.stringify({error:String(e)}));}};\n" +
        OPERIT_JS +
        // 把引导口令交给闭包（OPERIT_FW_JS 的 IIFE 会捕获它并立刻 delete window.__bn）。必须在 OPERIT_FW_JS 之前、任何插件注入之前。
        "window.__bn=" + JSONObject.quote(bootNonce) + ";\n" +
        OPERIT_FW_JS +
        // 锁死运行时全局。**这是同 realm 令牌窃取/MITM 的关键防线**：所有插件共用一个 WebView，
        // 先注入的插件如果能把 window.__mk / AndroidBridge / __resolve 换成自己的包装版，就能截下
        // 后注入插件（尤其 userAuthored 的可信搜索源）的能力令牌、或改写别人的工具调用与返回。
        // 这里在**任何插件注入之前**（插件在 readyDef 完成后才注入）把这些绑定设为不可写+不可配置，
        // 覆写在非严格模式静默失败、严格模式抛错，原函数始终生效。根治仍需每插件单开 WebView，
        // 但锁死全局把「先注入即可劫持」这条实际可打通的链堵死了。
        "(function(){var lock=function(n,v){try{if(v!==undefined&&v!==null)Object.defineProperty(window,n,{value:v,writable:false,configurable:false});}catch(e){}};\n" +
        // 【安全 #3】除了原先的桥/入口函数，再把「纯查表」的运行时数据结构锁成不可替换：
        //   __p(promise 回调表)/__t(工具 handler 表)——恶意插件 window.__p=new Proxy(...) 能截/改所有插件的工具返回值，堵掉；
        //   __hooks(钩子表)/__om(模块 exports 缓存)/__pkgFiles/__pkgMods(框架包文件/模块缓存)——同理防替换整表做 MITM；
        //   __mkToolCall/__mkTools(令牌绑定工厂)/complete/log——防替换这些拿去偷令牌或伪造完成回调。
        // 锁的是**绑定不可替换**（不能 window.__p=别的对象），对象内容仍可增删键（__p[id]=cb 照常работает）。
        // **故意不锁**：__s(每次 ++ 的计数器)、__curPkg/__hookPkg(每次加载框架包都要重新赋值的当前包指针)——
        //   它们是会被重新赋值的运行时状态，锁绑定会让 ++/=赋值静默失败而弄坏功能；它们也不是查表面，价值低。
        // loadOperitModule：由**已锁**的 __invokeOperit 以 tokenFor(pkgId) 真令牌调用，自己却是可写全局——不锁的话先注入的
        //   恶意包覆写它就能截下别包被调用时传入的真令牌、掌控其模块 exports（第三轮红队 #B）。补进锁列表堵死。
        // 注：__hooks / __mkRequire / __hpush 现已关进 OPERIT_FW_JS 的 IIFE 闭包（不在 window 上），插件根本够不到，无需再锁（#A 根治）。
        "['__mk','__resolve','__invoke','AndroidBridge','__invokeOperit','__loadFrameworkPkg','__resetRuntime','__fireHook','toolCall','Tools','getEnv','__p','__t','__om','__pkgFiles','__pkgMods','__mkToolCall','__mkTools','loadOperitModule','complete','log'].forEach(function(n){lock(n,window[n]);});\n" +
        "try{Object.freeze(Object.prototype);}catch(e){}})();\n" +
        "AndroidBridge.ready();\n" +
        "</script></body></html>"

    // Operit 传统脚本包运行时（CommonJS：exports.<fn>(params) + complete()/返回值）。用原始字符串免转义地狱；
    // 里面**不能有 $**（Kotlin 会当模板插值）——已确认没有。工具的 host 调用走 Tools 门面/toolCall → Arix 原生工具。
    private const val OPERIT_JS = """
window.__om={};              // moduleId -> exports（模块只实例化一次）
window.__ac=null;            // 当前调用的运行时（complete 等每次 invoke 绑定）
var __g=window;
__g.complete=function(v){var a=window.__ac;if(a&&a.complete)a.complete(v);};
__g.done=__g.complete;
__g.emit=function(){};__g.update=function(){};__g.sendIntermediateResult=function(){};__g.delta=function(){};
__g.log=function(){try{AndroidBridge.log(Array.prototype.map.call(arguments,String).join(' '));}catch(e){}};
__g.console={log:__g.log,info:__g.log,warn:__g.log,error:__g.log};
__g.getEnv=function(k){try{return AndroidBridge.envGet(window.__hookPkg||window.__curPkg||'',String(k));}catch(e){return '';}};
__g.getState=function(){return null;};__g.getLang=function(){return 'zh';};
__g.getCallerName=function(){return '';};__g.getChatId=function(){return '';};__g.getCallerCardId=function(){return '';};__g.getPluginConfigDir=function(){return '';};
// 【安全 #6】每包铸一个绑定其能力令牌的 toolCall：令牌封在闭包里（不落任何全局可读表，别包偷不到），
// callTool 带上真令牌 → Kotlin 反解出是哪个包 → 每包权限身份独立（不再全归 Plugin(unknown)）。
__g.__mkToolCall=function(tok){return function(name,args){return new Promise(function(res){var id='c'+(window.__s++);
  window.__p[id]=function(r){try{res(JSON.parse(r));}catch(e){res(r);}};
  AndroidBridge.callTool(id,name,JSON.stringify(args||{}),tok||'');});};};
__g.toolCall=__g.__mkToolCall('');   // 全局默认无令牌：OkHttp 门面/直接 window.toolCall 走这条，仍归 Plugin(unknown)
// Tools 门面做成工厂：给定 toolCall 造一套 Tools，这样每包能拿到用自己令牌绑的那套。
__g.__mkTools=function(T){ return {
  Files:{read:function(p){return T('file_read',{path:p});},readPart:function(p){return T('file_read',{path:p});},
    write:function(p,c){return T('file_write',{path:p,content:c});},append:function(p,c){return T('file_write',{path:p,content:c,append:true});},
    list:function(p){return T('file_list',{path:p});},deleteFile:function(p){return T('file_delete',{path:p});},
    exists:function(p){return T('file_exists',{path:p});},move:function(a,b){return T('file_move',{from:a,to:b});},
    copy:function(a,b){return T('file_copy',{from:a,to:b});},mkdir:function(p){return T('make_directory',{path:p});},
    zip:function(s,d){return T('file_archive',{path:s,dest:d});},unzip:function(s,d){return T('file_archive',{path:s,dest:d,extract:true});}},
  Net:{httpGet:function(u,h){return T('http_request',{url:u,method:'GET',headers:JSON.stringify(h||{})});},
    httpPost:function(u,b,h){return T('http_request',{url:u,method:'POST',body:(typeof b==='string')?b:JSON.stringify(b),headers:JSON.stringify(h||{})});},
    request:function(o){return T('http_request',o||{});},visit:function(u){return T('open_page',{url:u});}},
  System:{shell:function(c){return T('shell',{command:c});},terminal:function(c){return T('linux_exec',{command:c});},
    sleep:function(ms){return new Promise(function(r){setTimeout(r,ms||0);});},
    getDeviceInfo:function(){return T('device_status',{});},toast:function(m){__g.log(m);return Promise.resolve({success:true});},
    notify:function(t,m){return T('notification',{title:t,message:m});}},
  UI:{clickElement:function(o){return T('ui_control',o||{});},tap:function(x,y){return T('ui_control',{action:'tap',x:x,y:y});}},
  Chat:{}, Workflow:{},
  SoftwareSettings:{ writeEnvironmentVariable:function(k,v){try{AndroidBridge.envSet(window.__hookPkg||window.__curPkg||'',String(k),(v==null)?'':String(v));}catch(e){} return Promise.resolve({success:true});},
    readEnvironmentVariable:function(k){try{return Promise.resolve(AndroidBridge.envGet(window.__hookPkg||window.__curPkg||'',String(k)));}catch(e){return Promise.resolve('');}} }
 }; };
__g.Tools=__g.__mkTools(__g.toolCall);
// OkHttp 门面（Operit 包常用它发请求）：链式构建 → execute() 走 Arix 原生 http_request（避开 WebView CORS）。
(function(){var T=__g.toolCall;
 function mkResp(raw){ var s=(typeof raw==='string')?raw:JSON.stringify(raw); var code=0,body=s;
   var m=s.match(/^HTTP (\d+)\n?([\s\S]*)/); if(m){code=parseInt(m[1],10);body=m[2];}
   return { statusCode:code, code:code, isSuccessful:function(){return code>=200&&code<300;},
     text:body, content:body, body:body, raw:{url:''},
     json:function(){try{return JSON.parse(body);}catch(e){return null;}},
     bodyAsBase64:function(){try{return (typeof btoa!=='undefined')?btoa(unescape(encodeURIComponent(body))):'';}catch(e){return '';}} };
 }
 function mkReq(){ var st={url:'',method:'GET',headers:{},body:null}; var r={
   url:function(u){st.url=u;return r;}, method:function(mm){st.method=(mm||'GET').toUpperCase();return r;},
   header:function(k,v){st.headers[k]=v;return r;}, addHeader:function(k,v){st.headers[k]=v;return r;},
   headers:function(o){for(var k in (o||{}))st.headers[k]=o[k];return r;},
   body:function(b){st.body=(typeof b==='string')?b:JSON.stringify(b);return r;},
   json:function(b){st.headers['Content-Type']='application/json';st.body=(typeof b==='string')?b:JSON.stringify(b);return r;},
   build:function(){ return { execute:function(){ return T('http_request',{url:st.url,method:st.method,headers:JSON.stringify(st.headers),body:st.body}).then(mkResp); } }; }
 }; return r; }
 var client={ newRequest:mkReq, newCall:mkReq };
 window.OkHttp={ newClient:function(){return client;}, client:client, newRequest:mkReq, newBuilder:function(){return {build:function(){return client;}};} };
 window.OkHttpClient=function(){return client;}; window.OkHttpClientBuilder=function(){return {build:function(){return client;}};};
})();
// 其它包可能在 load 期引用的全局：给非崩溃的 stub，让模块能加载（不实现全部功能，缺的调用返回空/报错但不炸整包）。
window.CryptoJS=window.CryptoJS||{MD5:function(){return{toString:function(){return '';}};},SHA256:function(){return{toString:function(){return '';}};},enc:{Utf8:{},Hex:{},Base64:{}},AES:{encrypt:function(){return{toString:function(){return '';}};},decrypt:function(){return{toString:function(){return '';}};}}};
window._=window._||new Proxy({},{get:function(){return function(){return undefined;};}});
window.pako=window.pako||{inflate:function(x){return x;},deflate:function(x){return x;},ungzip:function(x){return x;}};
window.Jimp=window.Jimp||{}; window.UINode=window.UINode||{}; window.Java=window.Java||{}; window.Android=window.Android||{};
window.Intent=window.Intent||{}; window.dataUtils=window.dataUtils||{}; window.ToolPkg=window.ToolPkg||{};
__g.require=function(n){ if(n==='lodash')return (window._||{}); if(n==='crypto-js')return window.CryptoJS; if(n==='pako')return window.pako; return {}; };
__g.loadOperitModule=function(mid,src,tok){ if(window.__om[mid])return window.__om[mid];
  var T=__g.__mkToolCall(tok||''); var TT=__g.__mkTools(T);   // 【安全 #6】把本包令牌绑定的 toolCall/Tools 作闭包参数注入
  var module={exports:{}};
  try{ var f=new Function('module','exports','require','complete','done','log','console','toolCall','Tools',
     'getEnv','getState','getLang','getCallerName','getChatId','getCallerCardId','getPluginConfigDir','emit','update','sendIntermediateResult',
     src);
    f(module,module.exports,__g.require,__g.complete,__g.done,__g.log,__g.console,T,TT,
      __g.getEnv,__g.getState,__g.getLang,__g.getCallerName,__g.getChatId,__g.getCallerCardId,__g.getPluginConfigDir,__g.emit,__g.update,__g.sendIntermediateResult);
  }catch(e){AndroidBridge.log('operit mod '+mid+' load: '+e);}
  window.__om[mid]=module.exports;return module.exports;
};
__g.__invokeOperit=function(iid,pkgId,mid,src,fn,aj,tok){ try{
   window.__hookPkg=pkgId; window.__curPkg=pkgId;   // 每包 env 命名空间（getEnv/writeEnv 落到本包）
   var ex=__g.loadOperitModule(mid,src,tok); var f=(ex&&ex[fn])||window[fn];
   if(typeof f!=='function'){AndroidBridge.done(iid,JSON.stringify({error:'包里没有导出函数 '+fn}));return;}
   var params={};try{params=JSON.parse(aj||'{}');}catch(e){}
   var fired=false; function fin(v){if(fired)return;fired=true;AndroidBridge.done(iid,(typeof v==='string')?v:JSON.stringify(v));}
   window.__ac={complete:fin,emit:function(){},update:function(){},sendIntermediateResult:function(){},log:__g.log};
   var r=f(params);
   if(r&&typeof r.then==='function'){r.then(function(v){if(v!==undefined)fin(v);}).catch(function(e){fin({error:String(e)});});}
   else if(r!==undefined){fin(r);}
 }catch(e){AndroidBridge.done(iid,JSON.stringify({error:String(e)}));}
};
"""

    // Operit .toolpkg **框架包**运行时：跑 main.js 的 registerToolPkg()，把 ToolPkg.register* 钩子存成活函数引用
    // （Arix 的 WebView 是常驻单上下文，可直接存活 fn，免 Operit 那套"按导出名重取"的持久化）。多文件 CommonJS require。
    // 只接可落地的文本类钩子；compose-DSL 的 UI 屏幕(uiRoute/toolboxUi/desktopWidget)只登记不渲染。
    private const val OPERIT_FW_JS = """
(function(){
// 【第三轮红队 #A 根治·闭包私有化】钩子表 HOOKS 与「当前加载包指针」loadingPkg 关进本闭包（不挂 window），
// 插件 JS 一律够不到：既不能往钩子表直接塞伪造 pkgId 的条目、也改不了属主指针、更不能不带口令触发加载。
// 钩子属主 pkgId 只由（带引导口令的）__loadFrameworkPkg 在加载期写进 loadingPkg，__hpush 据此打标并冻死——
// 不再读可写的 window.__curPkg。这样「冒充受信包 id 绕过门控」的三条路（改 __curPkg / 直接 push 钩子表 / 覆写 filter）全断。
var HOOKS={systemPromptCompose:[],promptInput:[],promptHistory:[],promptFinalize:[],toolPromptCompose:[],messageProcessing:[],xmlRender:[],inputMenuToggle:[],appLifecycle:[],toolLifecycle:[],chatInput:[],uiRoute:[],navigationEntry:[],toolboxUi:[],desktopWidget:[],aiProvider:[]};
var loadingPkg=null;   // 仅加载期非空 = 允许注册钩子的窗口；插件够不到，改不了
var BOOT_NONCE=window.__bn; try{delete window.__bn;}catch(e){}   // Kotlin 现发的引导口令：捕获进闭包后立刻从 window 抹掉，插件读不到也猜不到
window.__pkgFiles={}; window.__pkgMods={}; window.__curPkg=null;   // 这几个给 require/env 用，保持在 window（env 命名空间是另一条线）
// refresh 前调：清空钩子(防翻倍)+模块缓存(防跑旧模块)。带口令才认，防插件调它清空所有钩子做 DoS。
// 原地清空（HOOKS[k]=[] / delete 键）不换绑定，好让 __om/__pkgFiles/__pkgMods 仍能在 bootstrap 末尾锁成不可替换。
window.__resetRuntime=function(nonce){ if(nonce!==BOOT_NONCE)return;
  for(var k in HOOKS){HOOKS[k]=[];}
  for(var k in window.__om){delete window.__om[k];}
  for(var k in window.__pkgFiles){delete window.__pkgFiles[k];}
  for(var k in window.__pkgMods){delete window.__pkgMods[k];}
  window.__curPkg=null; window.__hookPkg=null; };
function __norm(p){var a=(p||'').split('/');var o=[];for(var i=0;i<a.length;i++){var s=a[i];if(s===''||s==='.')continue;if(s==='..')o.pop();else o.push(s);}return o.join('/');}
function __dir(p){var i=p.lastIndexOf('/');return i<0?'':p.substring(0,i);}
function __resolveFile(pkgId,fromDir,req){var files=window.__pkgFiles[pkgId]||{};var base=(req.charAt(0)==='.')?__norm(fromDir+'/'+req):__norm(req);
  var c=[base,base+'.js',base+'.json',base+'/index.js'];for(var i=0;i<c.length;i++){var k=__norm(c[i]);if(files[k]!=null)return k;}return null;}
// __mkRequire 也关在闭包里（不再挂 window）：插件因此无法覆写它去截别包被调用时传入的真令牌（第三轮红队 #B 的更彻底版）。
function __mkRequire(pkgId,fromDir,tok){var T=window.__mkToolCall(tok||''); var TT=window.__mkTools(T);   // 【安全 #6】框架包模块也拿本包令牌绑定的 toolCall/Tools
 return function(req){var key=__resolveFile(pkgId,fromDir,req);
  if(key==null){if(req==='lodash')return window._;if(req==='crypto-js')return window.CryptoJS;if(req==='pako')return window.pako;return {};}
  var cache=window.__pkgMods[pkgId]||(window.__pkgMods[pkgId]={});if(cache[key])return cache[key].exports;
  var module={exports:{}};cache[key]=module;var src=window.__pkgFiles[pkgId][key];
  if(key.slice(-5)==='.json'){try{module.exports=JSON.parse(src);}catch(e){}return module.exports;}
  try{var f=new Function('module','exports','require','ToolPkg','complete','log','console','toolCall','Tools','getEnv','getState','getLang','getChatId',src);
    f(module,module.exports,__mkRequire(pkgId,__dir(key),tok),window.ToolPkg,window.complete,window.log,window.console,T,TT,window.getEnv,window.getState,window.getLang,window.getChatId);
  }catch(e){AndroidBridge.log('fw require '+key+': '+e);}
  return module.exports;};}
// 注册钩子：只在框架包加载期（loadingPkg 非空）允许，属主取可信的 loadingPkg 并 defineProperty 冻死不可改。
// 注意：这要求注册是**同步**发生在 registerToolPkg 里（Operit 的做法）；异步/延迟注册会因 loadingPkg 已归零而被丢弃——安全侧取舍。
function __hpush(type,def,extra){ if(loadingPkg==null)return;
  var fn=(def&&(def.function||def.action||def.handler)); if(typeof fn!=='function')return;
  var h={id:def&&def.id,fn:fn}; if(extra)for(var k in extra)h[k]=extra[k];
  try{Object.defineProperty(h,'pkgId',{value:loadingPkg,writable:false,configurable:false,enumerable:true});}catch(e){h.pkgId=loadingPkg;}
  (HOOKS[type]||(HOOKS[type]=[])).push(h); }
window.ToolPkg={
  registerSystemPromptComposeHook:function(d){__hpush('systemPromptCompose',d);},
  registerPromptInputHook:function(d){__hpush('promptInput',d);},
  registerPromptHistoryHook:function(d){__hpush('promptHistory',d);},
  registerPromptEstimateHistoryHook:function(d){__hpush('promptHistory',d);},
  registerPromptFinalizeHook:function(d){__hpush('promptFinalize',d);},
  registerPromptEstimateFinalizeHook:function(d){__hpush('promptFinalize',d);},
  registerToolPromptComposeHook:function(d){__hpush('toolPromptCompose',d);},
  registerSystemPromptComposeHook2:function(d){__hpush('systemPromptCompose',d);},
  registerMessageProcessingPlugin:function(d){__hpush('messageProcessing',d);},
  registerXmlRenderPlugin:function(d){__hpush('xmlRender',d,{tag:String(d&&d.tag||'').toLowerCase()});},
  registerInputMenuTogglePlugin:function(d){__hpush('inputMenuToggle',d);},
  registerAppLifecycleHook:function(d){__hpush('appLifecycle',d,{event:d&&d.event});},
  registerToolLifecycleHook:function(d){__hpush('toolLifecycle',d);},
  registerChatInputHook:function(d){__hpush('chatInput',d);},
  registerUiRoute:function(d){__hpush('uiRoute',d);},
  registerNavigationEntry:function(d){__hpush('navigationEntry',d);},
  registerToolboxUiModule:function(d){__hpush('toolboxUi',d);},
  registerDesktopWidget:function(d){__hpush('desktopWidget',d);},
  registerAiProvider:function(d){__hpush('aiProvider',d);},
  readResource:function(){return null;}, getConfigDir:function(){return '';}, ipc:{send:function(){},on:function(){}}
};
// 只认 Kotlin 带来的引导口令：杜绝插件自己 call __loadFrameworkPkg 以别的包名 + 自带文件加载、把恶意钩子登记成受信包。
window.__loadFrameworkPkg=function(iid,pkgId,filesJson,mainRel,tok,nonce){ var ok=false;
  if(nonce!==BOOT_NONCE){ try{AndroidBridge.done(iid,'false');}catch(e){} return; }
  try{
    var files=JSON.parse(filesJson); window.__pkgFiles[pkgId]=files; window.__pkgMods[pkgId]={};
    loadingPkg=pkgId; window.__curPkg=pkgId;
    var ex=__mkRequire(pkgId,'',tok||'')(mainRel);
    if(ex&&typeof ex.registerToolPkg==='function'){ ex.registerToolPkg({toolPkgId:pkgId,__operit_ui_package_name:pkgId,__operit_registration_mode:true}); }
    ok=true;
  }catch(e){AndroidBridge.log('loadFrameworkPkg '+pkgId+': '+e);}
  loadingPkg=null; window.__curPkg=null;   // 加载窗口关闭：此后任何注册都被 __hpush 拒
  AndroidBridge.done(iid, ok?'true':'false'); };
// 触发钩子。mode: fire(仅执行,按 event 过滤) / first(按 tag 找首个非空) / collect(收集全部) / chainString:<field>(把 eventPayload[field] 依次穿过)
window.__call=function(h,ev){ window.__hookPkg=h.pkgId; try{return h.fn(ev);}finally{ } };
window.__fireHook=function(iid,type,eventJson,mode,arg,allowJson){ try{
  var src=HOOKS[type]||[]; var hooks=[];
  // 逐包门控：allowJson 非空时只保留获准 pkgId 的钩子。**手写循环**、不使用 Array.prototype.filter（否则插件覆写 filter 即可让过滤失效，第三轮红队）；
  // aset 用无原型对象 + own 判定，杜绝 'constructor'/'__proto__' 走原型链恒真值（#3）；解析失败/无 pkgId 一律剔除(fail-closed)。
  if(allowJson){ var aset=Object.create(null),ok=false; try{var al=JSON.parse(allowJson);for(var ai=0;ai<al.length;ai++){if(typeof al[ai]==='string')aset[al[ai]]=1;}ok=true;}catch(e){}
    if(ok){for(var hi=0;hi<src.length;hi++){var hh=src[hi];if(hh&&typeof hh.pkgId==='string'&&aset[hh.pkgId]===1)hooks.push(hh);}} }
  else { for(var hj=0;hj<src.length;hj++)hooks.push(src[hj]); }
  var ev; try{ev=JSON.parse(eventJson);}catch(e){ev={};} if(!ev.eventPayload)ev.eventPayload={};
  if(mode==='fire'){ var ps=[]; for(var i=0;i<hooks.length;i++){var h=hooks[i];if(arg&&h.event&&h.event!==arg)continue;try{ps.push(Promise.resolve(window.__call(h,ev)));}catch(e){}} Promise.all(ps).then(function(){AndroidBridge.done(iid,'{}');},function(){AndroidBridge.done(iid,'{}');}); return; }
  if(mode==='first'){ (async function(){ for(var i=0;i<hooks.length;i++){var h=hooks[i];if(arg&&h.tag&&h.tag!==String(arg).toLowerCase())continue;try{var r=await Promise.resolve(window.__call(h,ev));if(r!=null){AndroidBridge.done(iid,(typeof r==='string')?r:JSON.stringify(r));return;}}catch(e){}} AndroidBridge.done(iid,'null'); })(); return; }
  if(mode==='firstMatched'){ (async function(){ for(var i=0;i<hooks.length;i++){var h=hooks[i];try{var r=await Promise.resolve(window.__call(h,ev));if(r===false||r==null)continue;if(typeof r==='object'&&r.matched===false)continue;AndroidBridge.done(iid,(typeof r==='string')?r:JSON.stringify(r));return;}catch(e){}} AndroidBridge.done(iid,'null'); })(); return; }
  if(mode==='collect'){ (async function(){ var out=[]; for(var i=0;i<hooks.length;i++){try{var r=await Promise.resolve(window.__call(hooks[i],ev));if(r!=null)out.push(r);}catch(e){}} AndroidBridge.done(iid,JSON.stringify(out)); })(); return; }
  if(mode.indexOf('chainString:')===0){ var field=mode.substring(12); (async function(){ var cur=(ev.eventPayload[field]!=null)?String(ev.eventPayload[field]):''; for(var i=0;i<hooks.length;i++){var h=hooks[i];try{ev.eventPayload[field]=cur;var r=await Promise.resolve(window.__call(h,ev));if(typeof r==='string')cur=r;else if(r&&typeof r==='object'&&typeof r[field]==='string')cur=r[field];}catch(e){}} AndroidBridge.done(iid,JSON.stringify({value:cur})); })(); return; }
  AndroidBridge.done(iid,'null');
 }catch(e){AndroidBridge.done(iid,'null');} };
})();
"""
}
