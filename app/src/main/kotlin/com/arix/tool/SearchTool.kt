package com.arix.tool

import com.arix.tool.search.BaiduEngine
import com.arix.tool.search.BingEngine
import com.arix.tool.search.CrossrefEngine
import com.arix.tool.search.DuckDuckGoEngine
import com.arix.tool.search.GoogleScholarEngine
import com.arix.tool.search.GoogleWebEngine
import com.arix.tool.search.SearchEngine
import com.arix.tool.search.SearchEngines
import com.arix.tool.search.SogouEngine
import com.arix.tool.search.SearchResult
import com.arix.tool.search.AnySearchEngine
import com.arix.tool.search.PerplexicaEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class SearchTool(private val context: android.content.Context) : Tool {
    override val name = "web_search"
    // "别用 deep" 原来在这段描述里说了三遍（描述一遍、deep 参数一遍、depth 参数一遍）。说一遍就够。
    override val description = "联网搜索获取实时信息（新闻、天气、事实、观点、社区讨论等）。默认快搜、秒回，绝大多数问题都用默认。只有用户明确要求彻底调研/多源交叉验证时才加 depth=deep（多轮、慢，产出带引用的综合报告）。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Web search for current info: news, weather, facts, opinions, forum threads. Default is a fast search and answers almost everything. Only add depth=deep when the user explicitly asks for thorough research or cross-checking sources (multi-round, slow, produces a cited report). type=image finds pictures, type=video finds videos."

    /**
     * ⚠ 这里是 `get()` 不是 `val`：引擎清单要按**本机此刻真的能用哪些**来发。
     *
     * 原来是把十几个键控引擎（tavily/brave/exa/serper/…）无条件写死进 enum 和描述里，
     * 而它们没填 key 就一个都用不了——每轮白发约 195 token，还顺带制造幻觉：
     * 模型看见清单里有 tavily 就真去 `engine="tavily"` 调，然后拿回一句"没配置"。
     * 现在只发默认那几个 + 用户真正启用并配好的，没启用的连名字都不出现。
     * （工具表本来就每轮重建一次，这里跟着算一遍不新增数量级开销。）
     */
    override val parameters: JSONObject get() = JSONObject().apply {
        val keyed = try { com.arix.tool.search.KeyedEngines.enabledIds(context) } catch (_: Exception) { emptyList() }
        val anySearchOn = try { com.arix.app.SearchApiPrefs.anySearchEnabled(context) } catch (_: Exception) { false }
        val perplexicaOn = try {
            com.arix.app.SearchApiPrefs.perplexicaEnabled(context) &&
                com.arix.app.SearchApiPrefs.perplexicaBaseUrl(context).isNotBlank()
        } catch (_: Exception) { false }
        put("type", "object")
        put("properties", JSONObject().apply {
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", "search keywords")
            })
            put("engine", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(
                    listOf("default", "bing", "baidu", "sogou", "google", "scholar", "crossref", "duckduckgo") +
                        (if (anySearchOn) listOf("anysearch") else emptyList()) +
                        (if (perplexicaOn) listOf("perplexica") else emptyList()) +
                        keyed + listOf("all")
                ))
                put("description", buildString {
                    append("default (omit) = Bing+Baidu+Sogou, recommended; baidu is best for Chinese; google/duckduckgo often unreachable in mainland China; scholar/crossref for papers")
                    if (anySearchOn) append("; anysearch = unified search, query goes to a third party")
                    if (perplexicaOn) append("; perplexica = self-hosted AI search")
                    if (keyed.isNotEmpty()) append("; ").append(keyed.joinToString("/"))
                    append("; all = query every engine.")
                })
            })
            put("num_results", JSONObject().apply {
                put("type", "integer")
                put("description", "result count, default 5, max 20")
            })
            put("language", JSONObject().apply {
                put("type", "string")
                put("description", "zh or en, default zh")
            })
            put("doi", JSONObject().apply {
                put("type", "string")
                put("description", "exact DOI lookup via CrossRef")
            })
            // ⚠ `deep`(boolean) 已从 schema 里去掉——它和 `depth` 是**同一个开关的两份**，
            // 一起发等于把同一件事说两遍(约 43 token/轮)，还让模型纠结该用哪个。
            // execute() 仍然照收 deep=true（老对话/插件里可能带着），只是不再主动告诉模型有这个参数。
            put("max_rounds", JSONObject().apply {
                put("type", "integer")
                put("description", "max rounds when depth=deep; default 2, cap 4")
            })
            put("depth", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("quick", "deep")))
                put("description", "quick (default); deep = multi-round research, slow, only when the user asks to dig in")
            })
            put("site", JSONObject().apply {
                put("type", "string")
                put("description", "restrict to one domain, e.g. zhihu.com; empty = whole web; works with deep too")
            })
            put("type", JSONObject().apply {
                put("type", "string")
                put("description", "web (default)")
            })
        })
        put("required", JSONArray(listOf("query")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val mediaType = params.optString("type", "web")
        val rawQuery = params.optString("query", "").trim()

        // 默认快搜(quick)直连、秒回；只有显式 depth=deep(或 deep=true)才走多轮 XSEARCHING 深度研究。
        // 之前默认 auto 会动不动就多轮深搜——又慢又占用整个对话，改为深搜必须明确指定。
        val depth = params.optString("depth", if (params.optBoolean("deep", false)) "deep" else "quick")
        val hasDoi = params.optString("doi", "").isNotBlank()
        if (!hasDoi && depth == "deep") {
            params.put("depth", "deep")   // 显式传给 XSearchTool，否则它读 optString(depth,auto) 会当 auto 提前快返回
            // 深搜慢，放后台跑、别卡住整个对话：立即返回，研究完成后把报告追加进对话并通知
            val q = params.optString("query", "")
            DeepSearchAsync.start(context, q, params)
            return@withContext ToolResult("已在后台开始深度研究「$q」，你可以继续聊别的——查完我把带引用的报告发给你。")
        }
        val query0 = params.optString("query", "")
        val site = params.optString("site", "").trim()
        val query = if (site.isNotBlank() && query0.isNotBlank()) "$query0 site:$site" else query0
        val doi = params.optString("doi", "")
        val engineName = params.optString("engine", "default")
        val numResults = params.optInt("num_results", 5).coerceIn(1, 20)
        val language = params.optString("language", "zh")

        // DOI exact search
        if (doi.isNotBlank()) {
            val result = CrossrefEngine.searchByDoi(doi)
            return@withContext if (result != null) {
                ToolResult(formatResults(listOf(result), "CrossRef DOI"))
            } else {
                ToolResult("未找到 DOI: $doi 对应的文献", isError = true)
            }
        }

        if (query.isBlank()) return@withContext ToolResult("请输入搜索关键词", isError = true)

        // AnySearch：可选外部服务，默认关；未启用直接拒绝，查询绝不外发到第三方
        if (engineName.equals("anysearch", true) && !com.arix.app.SearchApiPrefs.anySearchEnabled(context)) {
            return@withContext ToolResult("AnySearch 未启用：请在 设置→联网搜索 中开启（注意：开启后查询会发送到第三方 anysearch.com）", isError = true)
        }
        // Perplexica：自托管，未启用或未填 baseUrl 则拒绝
        if (engineName.equals("perplexica", true) &&
            (!com.arix.app.SearchApiPrefs.perplexicaEnabled(context) || com.arix.app.SearchApiPrefs.perplexicaBaseUrl(context).isBlank())) {
            return@withContext ToolResult("Perplexica 未配置：请在 设置→联网搜索 中启用并填写 baseUrl 与 chat/embedding 模型", isError = true)
        }
        // 键控引擎(zhipu/tavily/brave/…)：未在设置启用或未填 key/baseUrl 则明确提示，避免静默空结果
        com.arix.tool.search.KeyedEngines.spec(engineName.lowercase())?.let { spec ->
            if (!com.arix.tool.search.KeyedEngines.enabledIds(context).contains(spec.id)) {
                return@withContext ToolResult(
                    "搜索引擎「${spec.displayName}」未启用：请在 设置→联网搜索 中开启并填写 API key" + (if (spec.needsBaseUrl) "/baseUrl" else ""),
                    isError = true)
            }
        }

        val engines: List<SearchEngine> = when (engineName.lowercase()) {
            "default", "" -> {
                // 优先用户已启用的键控引擎(JSON 稳、无反爬)，再回退必应；百度/搜狗被反爬挡死不进默认主链
                val keyed = com.arix.tool.search.KeyedEngines.enabledIds(context).map { com.arix.tool.search.KeyedEngines.asEngine(context, it) }
                if (keyed.isNotEmpty()) keyed + SearchEngines.defaults() else SearchEngines.defaults()
            }
            "bing" -> listOf(BingEngine)
            "baidu" -> listOf(BaiduEngine)
            "sogou" -> listOf(SogouEngine)
            "google" -> listOf(GoogleWebEngine)
            "scholar" -> listOf(GoogleScholarEngine)
            "crossref" -> listOf(CrossrefEngine)
            "duckduckgo" -> listOf(DuckDuckGoEngine)
            "anysearch" -> { AnySearchEngine.injectedKey = com.arix.app.SearchApiPrefs.anySearchKey(context); listOf(AnySearchEngine) }
            "perplexica" -> {
                val sp = com.arix.app.SearchApiPrefs
                PerplexicaEngine.baseUrl = sp.perplexicaBaseUrl(context)
                PerplexicaEngine.chatProvider = sp.perplexicaChatProvider(context); PerplexicaEngine.chatModel = sp.perplexicaChatModel(context)
                PerplexicaEngine.embedProvider = sp.perplexicaEmbedProvider(context); PerplexicaEngine.embedModel = sp.perplexicaEmbedModel(context)
                listOf(PerplexicaEngine)
            }
            "all" -> listOf(BingEngine, BaiduEngine, GoogleWebEngine, CrossrefEngine, DuckDuckGoEngine)
            else -> {
                val kid = engineName.lowercase()
                if (com.arix.tool.search.KeyedEngines.spec(kid) != null) listOf(com.arix.tool.search.KeyedEngines.asEngine(context, kid))
                else com.arix.tool.search.JsSearchSources.get(context, engineName)?.takeIf { it.enabled }?.let { listOf(com.arix.tool.search.JsSearchSources.asEngine(context, it)) }
                ?: SearchEngines.getByName(engineName)?.let { listOf(it) } ?: SearchEngines.defaults()
            }
        }

        if (engines.isEmpty()) return@withContext ToolResult("未知搜索引擎: $engineName", isError = true)

        val allResults = mutableListOf<SearchResult>()
        for (engine in engines) {
            try {
                val results = engine.search(query, numResults, language)
                allResults.addAll(results)
            } catch (_: Exception) {}
        }

        if (allResults.isEmpty()) {
            return@withContext ToolResult("搜索没拿到结果。免key的必应有时被限流、百度/搜狗已被反爬挡死。建议在『设置→联网搜索』配一个稳定的键控引擎（博查/智谱/Tavily 等，多有免费额度），或换关键词/检查网络。", isError = true)
        }

        val engineNames = engines.joinToString(", ") { it.displayName }
        ToolResult(formatResults(allResults.distinctBy { it.url }.take(numResults * 2), engineNames))
    }

    private fun formatResults(results: List<SearchResult>, source: String): String {
        val body = buildString {
            append("🔍 搜索来源: $source | 结果数: ${results.size}\n\n")
            results.forEachIndexed { i, r ->
                append("${i + 1}. ${r.title}\n")
                if (r.snippet.isNotBlank()) append("   ${r.snippet.take(250)}\n")
                if (r.url.isNotBlank()) append("   🔗 ${r.url}\n")
                if (r.engine != source) append("   📡 来源: ${r.engine}\n")
                append("\n")
            }
        }
        // 搜索结果的标题/摘要是各引擎从网页抓来的外部文本，同属不可信数据（可能夹带注入指令）→ 回模型前套围栏。
        return UntrustedWeb.fence(body, "网页搜索结果")
    }
}
