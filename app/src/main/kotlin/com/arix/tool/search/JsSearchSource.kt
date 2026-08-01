package com.arix.tool.search

import android.content.Context
import com.arix.tool.JsPluginRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
// JsSearchSources —— 用户自定义 JS 搜索源
//
// 用户写一段 JS，Arix 用 WebView 的 JS 引擎（复用 JsPluginRuntime）跑它，
// 拿回结果数组。全部只是 web_search 的 engine 选项，不新增工具、默认全关，
// 拔掉不影响内置引擎与独立构建。
//
// ── JS 契约（用户只写函数体，不需要 register/export）──
//   作用域里已有：
//     query        String   —— 搜索关键词
//     maxResults   Number   —— 期望条数上限
//     xtom.callTool(name, args) -> Promise<string>
//                  —— 反调 Arix 原生工具，绕开 WebView 的 CORS 限制。
//                     典型用法：抓 REST/JSON 走 http_request，抓网页走 open_page。
//     xtom.log(msg)         —— 打日志到 Logcat(tag=JsPlugin)
//   要求：`return` 一个结果数组，元素形如 { title, url, snippet }。
//         可返回数组，或返回 Promise<数组>（异步，如先 await xtom.callTool 再 return）。
//   兼容取字段：title|name、url|link、snippet|content|description。
//
//   例（调某个返回 JSON 的搜索 API）：
//     var raw = xtom.callTool('http_request', {
//       url: 'https://api.example.com/search?q=' + encodeURIComponent(query),
//       headers: JSON.stringify({ Authorization: 'Bearer XXX' })
//     });
//     return Promise.resolve(raw).then(function (body) {
//       var arr = JSON.parse(body).results || [];
//       return arr.slice(0, maxResults).map(function (it) {
//         return { title: it.title, url: it.link, snippet: it.summary };
//       });
//     });
// ============================================================

/** 一个用户 JS 搜索源：源名（唯一，用作 engine 标识）+ JS 代码 + 是否启用。 */
data class JsSearchSourceDef(
    val name: String,
    val js: String,
    val enabled: Boolean
)

object JsSearchSources {
    private const val PREFS = "js_search_sources"
    private const val KEY = "sources"          // 存整份列表的 JSON 数组
    private const val TOOL = "run"             // 包装后注册的工具名
    private const val TIMEOUT_MS = 20000L

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun pid(name: String) = "jssearch:$name"   // JsPluginRuntime 里的插件命名空间，隔离各源

    // getByName 无 context —— 缓存一个 application context 供其解析 JS 源。
    // 任一 context-aware 入口（list/enabled/save/asEngine/SearchEngines.jsSources）都会刷新它。
    @Volatile private var appCtx: Context? = null
    fun cacheContext(context: Context) { appCtx = context.applicationContext }

    // —— 读写（整份列表读出→改→整份写回；源数量很少，够用）——
    fun list(context: Context): List<JsSearchSourceDef> {
        cacheContext(context)
        val raw = p(context).getString(KEY, "") ?: ""
        if (raw.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val n = o.optString("name", "").trim()
                if (n.isBlank()) return@mapNotNull null
                JsSearchSourceDef(n, o.optString("js", ""), o.optBoolean("enabled", false))
            }
        } catch (_: Exception) { emptyList() }
    }

    /** 已启用且 JS 非空的源。 */
    fun enabled(context: Context): List<JsSearchSourceDef> =
        list(context).filter { it.enabled && it.js.isNotBlank() }

    fun get(context: Context, name: String): JsSearchSourceDef? =
        list(context).find { it.name.equals(name, ignoreCase = true) }

    /** 源名列表（供 web_search 的 engine 枚举展示）。 */
    fun ids(context: Context): List<String> = list(context).map { it.name }

    private fun save(context: Context, sources: List<JsSearchSourceDef>) {
        cacheContext(context)
        val arr = JSONArray()
        sources.forEach { s ->
            arr.put(JSONObject().put("name", s.name).put("js", s.js).put("enabled", s.enabled))
        }
        p(context).edit().putString(KEY, arr.toString()).apply()
    }

    /** 新增/更新一个源（按 name 去重，忽略大小写）。 */
    fun upsert(context: Context, name: String, js: String, enabled: Boolean) {
        val n = name.trim()
        if (n.isBlank()) return
        val rest = list(context).filter { !it.name.equals(n, ignoreCase = true) }
        save(context, rest + JsSearchSourceDef(n, js, enabled))
    }

    fun remove(context: Context, name: String) {
        save(context, list(context).filter { !it.name.equals(name, ignoreCase = true) })
    }

    fun setEnabled(context: Context, name: String, on: Boolean) {
        get(context, name)?.let { upsert(context, it.name, it.js, on) }
    }

    // —— 执行 ——
    /** 包成 SearchEngine 适配器（context 绑进闭包，参照 KeyedEngines.asEngine）。 */
    fun asEngine(context: Context, def: JsSearchSourceDef): SearchEngine {
        cacheContext(context)
        val boundCtx = context.applicationContext
        return object : SearchEngine {
            override val name = def.name
            override val displayName = def.name
            override val description = "用户自定义 JS 搜索源"
            override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> =
                runSource(boundCtx, def, query, maxResults)
        }
    }

    /** 缓存过 context 时，按源名返回启用中的 JS 引擎；否则 null（优雅降级）。 */
    fun engineByNameCached(name: String): SearchEngine? {
        val ctx = appCtx ?: return null
        val def = enabled(ctx).find { it.name.equals(name, ignoreCase = true) } ?: return null
        return asEngine(ctx, def)
    }

    private suspend fun runSource(context: Context, def: JsSearchSourceDef, query: String, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            JsPluginRuntime.init(context)   // 幂等；确保运行时有 context（正常已在 OperitCompat 初始化）
            // 把用户 JS 包成一个注册工具：注入 query/maxResults，用户函数体里 return 结果数组。
            // IIFE 隔离顶层变量；xtom.* 由 JsPluginRuntime 的 bootstrap 提供。
            val wrapped = buildString {
                append("xtom.registerTool(").append(JSONObject.quote(TOOL))
                append(",{},function(__a){var query=__a.query;var maxResults=__a.maxResults;")
                append("return (function(query,maxResults){\n")
                append(def.js)
                append("\n})(query,maxResults);});")
            }
            // userAuthored=true：这段 JS 是用户在搜索源设置里自己敲的，权限上等同 AI，
            // 否则每搜一次都要为它的 http_request 弹一次框。
            JsPluginRuntime.register(pid(def.name), wrapped, userAuthored = true)
            val args = JSONObject().put("query", query).put("maxResults", maxResults)
            val raw = JsPluginRuntime.invoke(pid(def.name), TOOL, args, TIMEOUT_MS)
            parseResults(raw, def.name, maxResults)
        }

    /**
     * 解析 JS 返回：正常是 JSON 数组字符串；也兼容 {results:[...]}/{data:[...]}。
     * 运行时的错误（{error:...}、超时/不可用文案）解析失败 → 空结果（并入其余引擎，不抛）。
     */
    private fun parseResults(raw: String, engineName: String, max: Int): List<SearchResult> {
        val trimmed = raw.trim()
        val arr: JSONArray = try {
            when {
                trimmed.startsWith("[") -> JSONArray(trimmed)
                trimmed.startsWith("{") -> {
                    val o = JSONObject(trimmed)
                    o.optJSONArray("results") ?: o.optJSONArray("data") ?: return emptyList()
                }
                else -> return emptyList()
            }
        } catch (_: Exception) { return emptyList() }

        val out = ArrayList<SearchResult>()
        for (i in 0 until minOf(arr.length(), max)) {
            val o = arr.optJSONObject(i) ?: continue
            val title = o.optString("title", o.optString("name", ""))
            val url = o.optString("url", o.optString("link", ""))
            val snippet = o.optString("snippet", o.optString("content", o.optString("description", ""))).take(400)
            if (title.isNotBlank() || url.isNotBlank()) out.add(SearchResult(title, snippet, url, engineName))
        }
        return out
    }
}
