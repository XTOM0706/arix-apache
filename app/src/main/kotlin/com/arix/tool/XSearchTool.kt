package com.arix.tool

import com.arix.app.CloudApiConfigManager
import com.arix.app.SearchApiPrefs
import com.arix.app.XSearchPrefs
import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.model.ChatMessage
import com.arix.tool.search.AnySearchEngine
import com.arix.tool.search.PerplexicaEngine
import com.arix.tool.search.BingEngine
import com.arix.tool.search.BaiduEngine
import com.arix.tool.search.SogouEngine
import com.arix.tool.search.DuckDuckGoEngine
import com.arix.tool.search.GoogleWebEngine
import com.arix.tool.search.SearchEngine
import com.arix.tool.search.SearchEngines
import com.arix.tool.search.SearchResult
import com.arix.tool.search.XSearchProgress
import com.arix.tool.search.XSearchProgressBus
import com.arix.tool.search.domainOf
import com.arix.tool.search.normalizeUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
// XSEARCHING 1.0 —— 多轮研究循环
// 多引擎并发检索 → LLM 评估是否足够 → 不足则扩展子查询再检索 → LLM 综合成
// 带引用与置信度的结构化报告。复用 SearchEngines / CloudApiClient / 键控引擎。
// 研究模型可在 设置→深度研究 自选（默认跟随激活的对话模型）。
// ============================================================
class XSearchTool(private val context: android.content.Context) : Tool {

    override val name = "deep_search"
    override val description = "深度研究(多轮)：自动多引擎检索→评估→按需扩展子查询再检索→综合，返回带引用[n]与置信度的结构化报告。用于复杂、需多源交叉验证的问题；比 web_search 更慢更全。简单查询请用 web_search。"

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("query", JSONObject().apply { put("type", "string"); put("description", "研究问题") })
            put("max_rounds", JSONObject().apply { put("type", "integer"); put("description", "最大检索轮数上限(默认3,上限5)；实际轮数由 AI 评估驱动——够就提前停、不够继续挖") })
            put("language", JSONObject().apply { put("type", "string"); put("description", "zh/en，默认zh") })
            put("site", JSONObject().apply { put("type", "string"); put("description", "限定在该域名内搜索(如 zhihu.com / arxiv.org)，留空=全网") })
            put("depth", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("quick", "auto", "deep"))); put("description", "quick=只快搜返回结果(最快); auto=默认，够就快返回、不够自动多轮深入并综合; deep=强制多轮深入+综合报告(最全)") })
        })
        put("required", JSONArray(listOf("query")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
      try {
        val query0 = params.optString("query", "").trim()
        if (query0.isBlank()) return@withContext ToolResult("请提供研究问题", isError = true)
        val site = params.optString("site", "").trim()
        val query = if (site.isNotBlank()) "$query0 site:$site" else query0
        val maxRounds = params.optInt("max_rounds", XSearchPrefs.defaultRounds(context)).coerceIn(1, 5)
        val language = params.optString("language", "zh")
        val depth = params.optString("depth", "auto")
        val researchLike = isResearchLike(query0)

        // 研究模型：自选优先，否则跟随激活对话模型。config 可空——quick/够即返回不需模型
        val cfgMgr = CloudApiConfigManager(context)
        val rid = XSearchPrefs.researchConfigId(context)
        val config = (if (rid > 0) cfgMgr.getConfigById(rid) else null) ?: cfgMgr.getChatConfig() ?: cfgMgr.getActiveConfig()

        // ★ 检索前 query 改写 + 实体消歧（治"瑞安航空出瑞安市"）：加类别/别名锚点，歧义时并行验证另一含义
        var effectiveQuery = query
        var altQuery: String? = null
        if (config != null && site.isBlank()) {
            XSearchProgressBus.update(XSearchProgress("理解查询", 0, maxRounds, listOf(query0), 0, emptyList()))
            rewriteQuery(config, query0)?.let { effectiveQuery = it.first; altQuery = it.second }
        }

        val engines = buildEngines()
        val collected = LinkedHashMap<String, SearchResult>() // key(url/引擎+标题) → 去重
        // 检索查询：改写后主查询 + 可选歧义候选 + UGC 社区定向
        var queries = buildList {
            add(effectiveQuery)
            altQuery?.let { add(it) }
            if (site.isBlank() && SearchApiPrefs.ugcEnabled(context)) add(ugcTargetedQuery(query0))
        }
        val trace = StringBuilder()
        var quickReturn = false

        for (round in 1..maxRounds) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()   // STOP 取消后，下一轮开始前就停，别再空跑一整轮抓取
            XSearchProgressBus.update(XSearchProgress("搜索中", round, maxRounds, queries, collected.size, recentOf(collected)))
            // 所有 (查询×引擎) 并发；每个引擎完成即实时更新进度（synchronized 保护 collected）
            val pairs = queries.flatMap { q -> engines.map { q to it } }
            coroutineScope {
                pairs.map { (q, e) ->
                    async {
                        val rs = try { e.search(q, 10, language) } catch (_: Exception) { emptyList() }
                        synchronized(collected) {
                            for (r in rs) {
                                // 归一化 URL 去重（跨引擎、跨 http/https/www/末尾斜杠 视为同一页）；无 URL 用标题兜底
                                val key = normalizeUrl(r.url).ifBlank { "t:${r.title.trim().lowercase()}" }
                                if (key.isNotBlank() && key != "t:" && !collected.containsKey(key)) collected[key] = r
                            }
                            XSearchProgressBus.update(XSearchProgress("搜索中 · ${e.displayName}", round, maxRounds, queries, collected.size, recentOf(collected)))
                        }
                    }
                }.awaitAll()
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()   // 抓取轮结束即检查 STOP，别再进评估/综合
            trace.append("第${round}轮 · 查询[").append(queries.joinToString(" | ")).append("] → 累计 ${collected.size} 源\n")
            // 融合：第一轮后按 depth 决定快返回（默认 auto：结果够且非研究型→快，≈web_search 速度）
            if (round == 1 && (depth == "quick" || (depth == "auto" && collected.size >= 8 && !researchLike))) { quickReturn = true; break }
            if (round == maxRounds) break
            if (config == null) break  // 没研究模型无法 LLM 深入，用已收集结果直接返回

            XSearchProgressBus.update(XSearchProgress("评估中", round, maxRounds, queries, collected.size, recentOf(collected)))
            // LLM 评估是否足够 + 生成补充子查询
            val ctxSummary = collected.values.take(16).joinToString("\n") { "· ${it.title} — ${it.snippet.take(120)}" }
            val evalPrompt = buildString {
                append("研究问题：").append(query).append("\n已收集来源：\n").append(ctxSummary)
                append("\n已搜过：").append(queries.joinToString(" | "))
                append("\n\n这些信息够不够全面回答问题。只输出 JSON：够了 {\"enough\":true}；")
                append("不够 {\"enough\":false,\"subqueries\":[...]}，最多 3 个。子查询要能带来新来源而非换个说法重搜——换关键词、换平台、换角度，去挖已搜过没覆盖到的部分。")
            }
            val eval = try { JSONObject(llmOnce(config, evalPrompt).cleanJson()) } catch (_: Exception) { JSONObject() }
            if (eval.optBoolean("enough", false)) { trace.append("评估：信息已足够，提前结束。\n"); break }
            val subs = eval.optJSONArray("subqueries")
            val next = mutableListOf<String>()
            if (subs != null) for (i in 0 until minOf(subs.length(), 3)) subs.optString(i).trim().takeIf { it.isNotBlank() }?.let { next.add(it) }
            if (next.isEmpty()) break
            queries = next
        }

        if (collected.isEmpty()) return@withContext ToolResult("未检索到任何来源，请换个问题或检查网络连接。", isError = true)

        // 快返回（速度）或无研究模型：直接给结果摘要，不综合
        if (quickReturn || config == null) return@withContext ToolResult(quickResultText(collected, trace))

        XSearchProgressBus.update(XSearchProgress("综合中", maxRounds, maxRounds, queries, collected.size, recentOf(collected)))
        // 综合报告：带编号引用 + 置信度
        val allSources = collected.values.toList()
        // embedding 重排（可配端点）：砍离题、去重、按相似度取 topK；未配置则原样
        val sources = com.arix.tool.search.EmbeddingRerank.rerank(context, query0, allSources)
        val sourceBlock = sources.take(40).mapIndexed { i, r -> "[${i + 1}] ${r.title}\n${r.snippet.take(200)}\n${r.url}" }.joinToString("\n\n")
        val sumPrompt = buildString {
            append("研究问题：").append(query).append("\n\n来源（编号即引用号）：\n").append(sourceBlock)
            append("\n\n请基于以上来源写一份结构化研究报告：分点、准确、在关键结论后用 [n] 标注引用来源；")
            append("只用来源支持的信息，不臆测或编造；结尾单独一行给出「置信度：0~1 的小数」及一句简短理由。用")
            append(if (language == "en") "英文" else "中文").append("输出。")
        }
        val report = llmOnce(config, sumPrompt)

        val out = buildString {
            append(report.trim())
            append("\n\n📄 引用来源（").append(sources.size).append(" 个，编号与报告 [n] 对应）\n")
            sources.forEachIndexed { i, r -> append("[${i + 1}] ").append(r.title.ifBlank { r.url }); if (r.url.isNotBlank()) append("\n    ").append(r.url); append("\n") }
            if (allSources.size > sources.size) append("\n（另检索到 ").append(allSources.size - sources.size).append(" 个相关性较低的来源，已略）\n")
            append("\n🔎 检索轨迹\n").append(trace)
        }
        ToolResult(out)
      } finally {
        XSearchProgressBus.clear()
      }
    }

    private fun recentOf(m: Map<String, SearchResult>): List<String> =
        m.values.toList().takeLast(4).map { "${it.title.take(28).ifBlank { "(无标题)" }} · ${domainOf(it.url)}" }

    // 研究型查询信号：命中则 auto 模式直接走深度（多轮+综合）
    private fun isResearchLike(q: String): Boolean {
        val signals = listOf("对比", "vs", "为什么", "为何", "如何", "怎么", "怎样", "最新", "进展", "综述", "研究", "深入", "详细",
            "全面", "优缺点", "区别", "评测", "攻略", "原理", "趋势", "分析", "利弊", "compare", "why", "how", "latest", "review", "research")
        return q.length > 24 || signals.any { q.contains(it, ignoreCase = true) }
    }

    // UGC 盲区突破：一条覆盖多社区平台的定向查询（Bing/Google 支持 site: OR）
    private fun ugcTargetedQuery(q: String): String =
        "$q (site:zhihu.com OR site:xiaohongshu.com OR site:bilibili.com OR site:tieba.baidu.com OR site:douban.com OR site:reddit.com OR site:stackoverflow.com OR site:quora.com)"

    // 快速返回：结果列表摘要（web_search 风格，不调综合 LLM），用于 depth=quick / auto够 / 无研究模型
    private fun quickResultText(collected: Map<String, SearchResult>, trace: StringBuilder): String {
        val sources = collected.values.toList()
        return buildString {
            append("🔍 快速搜索 · ${sources.size} 条结果\n\n")
            sources.take(20).forEachIndexed { i, r ->
                append("${i + 1}. ").append(r.title.ifBlank { r.url }).append("\n")
                if (r.snippet.isNotBlank()) append("   ").append(r.snippet.take(200)).append("\n")
                if (r.url.isNotBlank()) append("   ").append(r.url).append("\n")
                append("\n")
            }
            append("（如需更全面的多源交叉综合，可加 depth=deep）")
        }
    }

    // 检索引擎：通用网页多源(国内三源+境外，境外不可达会 try/catch 静默跳过)；
    // 学术源(Scholar/Crossref)不默认纳入；启用的键控引擎(AnySearch/Perplexica)一并注入。
    private fun buildEngines(): List<SearchEngine> {
        val list = mutableListOf<SearchEngine>(BingEngine, BaiduEngine, SogouEngine, DuckDuckGoEngine, GoogleWebEngine)
        if (SearchApiPrefs.anySearchEnabled(context)) {
            AnySearchEngine.injectedKey = SearchApiPrefs.anySearchKey(context)
            list.add(AnySearchEngine)
        }
        if (SearchApiPrefs.perplexicaEnabled(context) && SearchApiPrefs.perplexicaBaseUrl(context).isNotBlank()) {
            PerplexicaEngine.baseUrl = SearchApiPrefs.perplexicaBaseUrl(context)
            PerplexicaEngine.chatProvider = SearchApiPrefs.perplexicaChatProvider(context)
            PerplexicaEngine.chatModel = SearchApiPrefs.perplexicaChatModel(context)
            PerplexicaEngine.embedProvider = SearchApiPrefs.perplexicaEmbedProvider(context)
            PerplexicaEngine.embedModel = SearchApiPrefs.perplexicaEmbedModel(context)
            list.add(PerplexicaEngine)
        }
        // 已启用的键控引擎(Tavily/Brave/Exa/... RikkaHub 系列)一并纳入研究引擎池
        com.arix.tool.search.KeyedEngines.enabledIds(context).forEach { list.add(com.arix.tool.search.KeyedEngines.asEngine(context, it)) }
        return list
    }

    // ★ 检索前 query 改写 + 实体消歧（Perplexica classifier 思路）：给多义词加类别/别名锚点，可选并行验证另一含义
    private suspend fun rewriteQuery(config: CloudApiConfig, q: String): Pair<String, String?>? {
        val prompt = "为网页检索优化下面的查询词，只输出 JSON：\n" +
            "{\"query\":\"补全指代和省略；对含义模糊的实体补上限定词——所属领域、通用名或英文原名，让它明确指向用户要找的目标\"," +
            "\"alt\":\"这个词若还有另一个常见含义，给出指向那个含义的检索词，否则留空\"}\n查询：" + q
        return try {
            val o = JSONObject(llmOnce(config, prompt).cleanJson())
            val query = o.optString("query", "").ifBlank { q }
            val alt = o.optString("alt", "").takeIf { it.isNotBlank() && !it.equals(query, true) }
            Pair(query, alt)
        } catch (_: Exception) { null }
    }

    private suspend fun llmOnce(config: CloudApiConfig, prompt: String): String {
        var out = ""
        CloudApiClient(config).streamChat(
            messages = listOf(ChatMessage("user", prompt)),
            enableThinking = 0, onReasoningChunk = {}, onContentChunk = { out += it },
        )
        return out.trim()
    }

    private fun String.cleanJson() = trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
}
