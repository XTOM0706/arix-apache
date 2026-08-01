package com.arix.tool.search

import android.content.Context
import com.arix.app.SearchApiPrefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ============================================================
// KeyedEngines —— 键控搜索引擎统一执行框架（RikkaHub 系列：Tavily/Brave/Exa/...）
// 一个 Spec 描述一个引擎的「请求构造 + 响应解析」，共享 HTTP 执行与 prefs 读取。
// 全部只是 web_search 的 engine 选项 + XSEARCHING 引擎池，不新增工具。
// 自研实现：仅调各家公开 API，零编译期依赖，可选可降级、默认全关。
// ============================================================
object KeyedEngines {

    data class Req(val url: String, val method: String = "GET", val headers: Map<String, String> = emptyMap(), val body: String? = null)

    data class Spec(
        val id: String,
        val displayName: String,
        val needsKey: Boolean = true,
        val needsBaseUrl: Boolean = false,     // 自托管（SearXNG/Firecrawl/Ollama 私有部署）
        val defaultBaseUrl: String = "",
        val buildReq: (query: String, max: Int, key: String, baseUrl: String) -> Req,
        val parse: (body: String) -> List<SearchResult>
    )

    // 规格表：由 API 查证结果填充（见 registerSpecs 下方逐个引擎）
    private val specs: LinkedHashMap<String, Spec> = LinkedHashMap()
    fun register(spec: Spec) { specs[spec.id] = spec }
    fun spec(id: String): Spec? = specs[id]
    fun ids(): List<String> = specs.keys.toList()
    fun displayName(id: String): String = specs[id]?.displayName ?: id

    /** 执行某键控引擎搜索；key/baseUrl 从 prefs 读。未配置/失败返回空。 */
    suspend fun search(context: Context, id: String, query: String, max: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        val spec = specs[id] ?: return@withContext emptyList()
        val key = SearchApiPrefs.keyedKey(context, id)
        val base = SearchApiPrefs.keyedBaseUrl(context, id).ifBlank { spec.defaultBaseUrl }
        if (spec.needsKey && key.isBlank()) return@withContext emptyList()
        if (spec.needsBaseUrl && base.isBlank()) return@withContext emptyList()
        val body = httpExec(spec.buildReq(query, max.coerceIn(1, 10), key, base)) ?: return@withContext emptyList()
        try { spec.parse(body).map { it.copy(engine = id) } } catch (_: Exception) { emptyList() }
    }

    /** 已在设置里启用（且必要 key/baseUrl 齐全）的键控引擎 id。 */
    fun enabledIds(context: Context): List<String> = specs.values.filter { s ->
        SearchApiPrefs.keyedEnabled(context, s.id) &&
            (!s.needsKey || SearchApiPrefs.keyedKey(context, s.id).isNotBlank()) &&
            (!s.needsBaseUrl || SearchApiPrefs.keyedBaseUrl(context, s.id).ifBlank { s.defaultBaseUrl }.isNotBlank())
    }.map { it.id }

    /** 包成 SearchEngine 适配器（供 XSEARCHING 引擎池 / web_search 复用无 context 接口）。 */
    fun asEngine(context: Context, id: String): SearchEngine = object : SearchEngine {
        override val name = id
        override val displayName = this@KeyedEngines.displayName(id)
        override val description = "键控引擎 $id"
        override suspend fun search(query: String, maxResults: Int, language: String): List<SearchResult> =
            this@KeyedEngines.search(context, id, query, maxResults)
    }

    private fun httpExec(req: Req): String? = try {
        val conn = URL(req.url).openConnection() as HttpURLConnection
        conn.requestMethod = req.method
        conn.connectTimeout = 15000; conn.readTimeout = 30000
        conn.setRequestProperty("User-Agent", "Arix/1.0")
        conn.setRequestProperty("Accept", "application/json")
        req.headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (req.body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(req.body.toByteArray()) }
        }
        if (conn.responseCode !in 200..299) { conn.disconnect(); null }
        else { val s = conn.inputStream.bufferedReader().use { it.readText() }; conn.disconnect(); s }
    } catch (_: Exception) { null }

    // 小工具：从 JSON 数组按字段名安全取结果
    internal fun resultsFrom(arr: JSONArray?, titleKey: String, urlKey: String, snippetKey: String, max: Int): List<SearchResult> {
        if (arr == null) return emptyList()
        val out = ArrayList<SearchResult>()
        for (i in 0 until minOf(arr.length(), max)) {
            val o = arr.optJSONObject(i) ?: continue
            out.add(SearchResult(o.optString(titleKey, ""), o.optString(snippetKey, "").take(400), o.optString(urlKey, ""), ""))
        }
        return out
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun json(vararg pairs: Pair<String, Any>) = JSONObject().apply { pairs.forEach { (k, v) -> put(k, v) } }

    // 各引擎规格（endpoint/auth/请求/解析）均取自官方文档；字段名/嵌套路径按各家实际
    private fun registerSpecs() {
        // Tavily — POST, Bearer, results[]{title,url,content}
        register(Spec("tavily", "Tavily", buildReq = { q, max, key, _ ->
            Req("https://api.tavily.com/search", "POST", mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                json("query" to q, "max_results" to max).toString())
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONArray("results"), "title", "url", "content", 99) }))

        // Brave — GET, X-Subscription-Token, web.results[]{title,url,description}
        register(Spec("brave", "Brave Search", buildReq = { q, max, key, _ ->
            Req("https://api.search.brave.com/res/v1/web/search?q=${enc(q)}&count=$max", "GET", mapOf("X-Subscription-Token" to key))
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONObject("web")?.optJSONArray("results"), "title", "url", "description", 99) }))

        // Exa — POST, x-api-key, results[]{title,url,text}
        register(Spec("exa", "Exa", buildReq = { q, max, key, _ ->
            Req("https://api.exa.ai/search", "POST", mapOf("x-api-key" to key, "Content-Type" to "application/json"),
                JSONObject().apply { put("query", q); put("numResults", max); put("contents", json("text" to true)) }.toString())
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONArray("results"), "title", "url", "text", 99) }))

        // Serper — POST, X-API-KEY, organic[]{title,link,snippet}
        register(Spec("serper", "Serper (Google)", buildReq = { q, max, key, _ ->
            Req("https://google.serper.dev/search", "POST", mapOf("X-API-KEY" to key, "Content-Type" to "application/json"),
                json("q" to q, "num" to max).toString())
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONArray("organic"), "title", "link", "snippet", 99) }))

        // Jina — POST, Bearer, X-Respond-With:no-content 省 token, data[]{title,url,description}
        register(Spec("jina", "Jina Search", buildReq = { q, max, key, _ ->
            Req("https://s.jina.ai/", "POST", mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json", "Accept" to "application/json", "X-Respond-With" to "no-content"),
                json("q" to q, "num" to max).toString())
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONArray("data"), "title", "url", "description", 99) }))

        // Bocha 博查 — POST, Bearer, data.webPages.value[]{name,url,snippet}
        register(Spec("bocha", "博查 Bocha", buildReq = { q, max, key, _ ->
            Req("https://api.bochaai.com/v1/web-search", "POST", mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                json("query" to q, "count" to max).toString())
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONObject("data")?.optJSONObject("webPages")?.optJSONArray("value"), "name", "url", "snippet", 99) }))

        // SearXNG 自托管 — GET, 无 key, results[]{title,url,content}；需 baseUrl 且实例开启 json 输出
        register(Spec("searxng", "SearXNG (自托管)", needsKey = false, needsBaseUrl = true, buildReq = { q, _, _, base ->
            Req("$base/search?q=${enc(q)}&format=json", "GET")
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONArray("results"), "title", "url", "content", 99) }))

        // Firecrawl — POST, Bearer, data.web[]{title,url,description}
        register(Spec("firecrawl", "Firecrawl", defaultBaseUrl = "https://api.firecrawl.dev", buildReq = { q, max, key, base ->
            Req("$base/v2/search", "POST", mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                JSONObject().apply { put("query", q); put("limit", max); put("sources", JSONArray().put(json("type" to "web"))) }.toString())
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONObject("data")?.optJSONArray("web"), "title", "url", "description", 99) }))

        // Tinyfish — GET, X-API-Key, results[]{title,url,snippet}
        register(Spec("tinyfish", "Tinyfish", buildReq = { q, _, key, _ ->
            Req("https://api.search.tinyfish.ai/?query=${enc(q)}", "GET", mapOf("X-API-Key" to key))
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONArray("results"), "title", "url", "snippet", 99) }))

        // Ollama Web Search — POST, Bearer, results[]{title,url,content}
        register(Spec("ollama", "Ollama Web Search", buildReq = { q, max, key, _ ->
            Req("https://ollama.com/api/web_search", "POST", mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                json("query" to q, "max_results" to max).toString())
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONArray("results"), "title", "url", "content", 99) }))

        // Grok Live Search — POST chat/completions + search_parameters；返回 content + citations(纯URL)
        register(Spec("grok", "Grok Live Search", buildReq = { q, max, key, _ ->
            Req("https://api.x.ai/v1/chat/completions", "POST", mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                JSONObject().apply {
                    put("model", "grok-3-latest")
                    put("messages", JSONArray().put(json("role" to "user", "content" to q)))
                    put("search_parameters", JSONObject().apply { put("mode", "on"); put("return_citations", true); put("max_search_results", max) })
                }.toString())
        }, parse = { b ->
            val o = JSONObject(b); val out = ArrayList<SearchResult>()
            val content = o.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
            if (content.isNotBlank()) out.add(SearchResult("Grok 综合回答", content.take(1000), "", ""))
            o.optJSONArray("citations")?.let { c -> for (i in 0 until c.length()) { val u = c.optString(i); if (u.isNotBlank()) out.add(SearchResult(u, "", u, "")) } }
            out
        }))

        // Perplexity Search（专用搜索端点）— POST, Bearer, results[]{title,url,snippet}
        register(Spec("perplexity", "Perplexity Search", buildReq = { q, max, key, _ ->
            Req("https://api.perplexity.ai/search", "POST", mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                json("query" to q, "max_results" to max).toString())
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONArray("results"), "title", "url", "snippet", 99) }))

        // LinkUp — POST, Bearer, depth/outputType 必填, results[]{name,url,content}
        register(Spec("linkup", "LinkUp", buildReq = { q, max, key, _ ->
            Req("https://api.linkup.so/v1/search", "POST", mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                JSONObject().apply { put("q", q); put("depth", "standard"); put("outputType", "searchResults"); put("maxResults", max) }.toString())
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONArray("results"), "name", "url", "content", 99) }))

        // Metaso 秘塔 — POST, Bearer, webpages[]{title,link,snippet}
        register(Spec("metaso", "秘塔 Metaso", buildReq = { q, max, key, _ ->
            Req("https://metaso.cn/api/v1/search", "POST", mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                JSONObject().apply { put("q", q); put("scope", "webpage"); put("size", max) }.toString())
        }, parse = { b -> resultsFrom(JSONObject(b).optJSONArray("webpages"), "title", "link", "snippet", 99) }))

        // 智谱 Web Search（GLM）— POST, Bearer, v4/web_search；返回 search_result[]{title,content,link}
        // 字段名按官方文档：请求 search_engine/search_query；响应数组 search_result，url 字段为 link、正文为 content。
        // 兼容旧/变体字段（data/results、url、snippet）做兜底。
        register(Spec("zhipu", "智谱搜索 GLM", buildReq = { q, max, key, _ ->
            Req("https://open.bigmodel.cn/api/paas/v4/web_search", "POST",
                mapOf("Authorization" to "Bearer $key", "Content-Type" to "application/json"),
                JSONObject().apply { put("search_engine", "search_std"); put("search_query", q); put("count", max) }.toString())
        }, parse = { b ->
            val o = JSONObject(b)
            val arr = o.optJSONArray("search_result") ?: o.optJSONArray("data") ?: o.optJSONArray("results")
            val out = ArrayList<SearchResult>()
            if (arr != null) for (i in 0 until arr.length()) {
                val it = arr.optJSONObject(i) ?: continue
                out.add(SearchResult(
                    it.optString("title", ""),
                    it.optString("content", it.optString("snippet", "")).take(400),
                    it.optString("link", it.optString("url", "")),
                    ""))
            }
            out
        }))
    }

    init { registerSpecs() }
}
