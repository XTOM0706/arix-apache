package com.arix.tool

import android.content.Context
import com.arix.app.MemoryManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 本地知识库 —— 存文档、按语义检索。
 *
 * ## 这个工具改过一次，改的是"它其实不是 RAG"
 *
 * 旧实现自己在 `filesDir/rag_docs/` 存一堆 `.txt`，检索时**整篇文档打一个相关度分**，
 * 命中后回**正文前 400 字**。两个结构性问题：
 *  1. **答案取不到**。长文档的答案常在中段或结尾，整篇匹配 + 只回开头，等于系统性地漏掉大部分内容
 *     （同 `ToolOutputStore` 那条"只 take(3000) 把尾巴丢了"的教训）。
 *  2. **和 `doc_read` 各存各的**。`doc_read(into_memory=true)` 早就会把文档切块存进长期记忆并建语义索引，
 *     可它和这里是两个互相看不见的库：`rag add` 存的 `doc_read` 找不到，反过来也一样。模型没法知道
 *     该用哪个，只会随机选一个然后在另一个里扑空、断言"我没有这份资料"。
 *
 * 现在两条路进的是**同一个库**（长期记忆里 `source=document` 的块，见 [DocChunker]），检索走的是
 * 记忆自己那套语义 + 关键词融合排序，返回**命中的那一块的完整原文**而不是文档开头。
 *
 * 没配 embedding 模型时自动回退到关键词/模糊匹配——但落点仍是**块**，不是整篇，所以依然比旧实现准。
 */
class RagTool(private val context: Context) : Tool {

    override val name = "rag"

    override val description =
        "本地知识库：存文档、按意思检索。search=按问题找相关片段（返回命中段落原文）；add=把一段文字存成文档；" +
            "list=看有哪些文档；delete=删掉某篇。要读 PDF/Word/PPT 这类文件请用 doc_read（带 into_memory=true 即可直接入库，和这里是同一个库）。"

    override val llmDescription =
        "Local knowledge base. search: find passages by meaning, returns the matching chunk verbatim. " +
            "add: store a piece of text as a document. list: what is in here. delete: drop a document. " +
            "To ingest a PDF/Word/PPT file use doc_read with into_memory=true — it writes to this same store."

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("search", "add", "list", "delete")))
                put("description", "search / add / list / delete")
            })
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", "what you want to find (action=search); a question works better than keywords")
            })
            put("title", JSONObject().apply {
                put("type", "string")
                put("description", "document name (action=add / delete)")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "document body (action=add)")
            })
            put("limit", JSONObject().apply {
                put("type", "integer")
                put("description", "how many passages to return (action=search, default 5)")
            })
        })
        put("required", JSONArray(listOf("action")))
    }

    private val mm by lazy { MemoryManager(context) }

    override suspend fun execute(params: JSONObject): ToolResult {
        migrateLegacyIfAny()
        return when (val action = params.optString("action", "")) {
            "search" -> search(params.optString("query", ""), params.optInt("limit", 5).coerceIn(1, 15))
            "add" -> add(params.optString("title", ""), params.optString("content", ""))
            "list" -> list()
            "delete" -> delete(params.optString("title", ""))
            else -> ToolResult("未知操作: $action（可用：search / add / list / delete）", isError = true)
        }
    }

    // ------------------------------------------------------------

    private suspend fun search(query: String, limit: Int): ToolResult {
        if (query.isBlank()) return ToolResult("要搜什么？给一句问题比给几个关键词准。", isError = true)
        // 多要一些再过滤：queryRelevant 返回的是**全部**记忆（关于用户的 + 文档块），
        // 这里只要文档块，不多要的话很容易被用户记忆挤光。
        val hits = mm.queryRelevant(query, maxResults = limit * 4)
            .filter { it.source == DocChunker.SOURCE }
            .take(limit)
        // 语义没命中（多半是没配 embedding 模型，或这篇文档还没建索引）→ 退回关键词，落点仍是块
        val fallback = if (hits.isEmpty())
            mm.searchTop(query, limit * 4).filter { it.source == DocChunker.SOURCE }.take(limit)
        else emptyList()
        val use = hits.ifEmpty { fallback }
        if (use.isEmpty()) {
            val docs = DocChunker.summarize(mm.all())
            return ToolResult(
                if (docs.isEmpty()) "知识库是空的。先用 rag add 存点东西，或者 doc_read(path=…, into_memory=true) 导入文件。"
                else "没找到相关内容。库里现有：" + docs.joinToString("、") { it.name } + "。换个说法再试，或者确认这份资料确实导进来过。"
            )
        }
        val sb = StringBuilder("找到 ${use.size} 段相关内容")
        if (fallback.isNotEmpty()) sb.append("（关键词匹配；配上 embedding 模型后可按意思检索，更准）")
        sb.append("：\n\n")
        use.forEachIndexed { i, m ->
            // 标题里已经带了「文档《X》· 段i/n」，直接给模型看得到出处
            sb.append("【${i + 1}】${m.title}\n${m.content}\n\n")
        }
        return ToolResult(sb.toString().trimEnd())
    }

    private suspend fun add(title: String, content: String): ToolResult {
        if (title.isBlank() || content.isBlank()) return ToolResult("标题和内容都要给。", isError = true)
        val r = DocChunker.store(mm, title.trim(), content)
        if (r.ok == 0) return ToolResult("存入失败：${r.lastError ?: "未知原因"}", isError = true)
        return ToolResult(buildString {
            append("已存入《$title》共 ${r.ok}/${r.total} 段，带语义索引可检索。")
            if (r.ok < r.total) append("（${r.total - r.ok} 段失败）")
            if (r.truncated) append("（原文过长，只存了前 ${DocChunker.MAX_CHUNKS} 段）")
        })
    }

    private suspend fun list(): ToolResult {
        val docs = DocChunker.summarize(mm.all())
        if (docs.isEmpty()) return ToolResult("知识库是空的。")
        val sb = StringBuilder("知识库共 ${docs.size} 篇：\n")
        docs.forEach { sb.append("· ${it.name}（${it.chunks} 段，约 ${it.chars} 字）\n") }
        return ToolResult(sb.toString().trimEnd())
    }

    private suspend fun delete(title: String): ToolResult {
        if (title.isBlank()) return ToolResult("要删哪篇？给文档名（可用 list 看）。", isError = true)
        val prefix = DocChunker.titlePrefix(title.trim())
        val victims = mm.search(title.trim()).filter { it.title.startsWith(prefix) }
        if (victims.isEmpty()) return ToolResult("知识库里没有《$title》。", isError = true)
        victims.forEach { mm.delete(it.id) }
        return ToolResult("已删除《$title》（${victims.size} 段）。")
    }

    // ------------------------------------------------------------

    /**
     * 旧版 `rag_docs` 目录下的 txt 一次性搬进统一库。
     *
     * 不能直接丢掉：那是用户真存过的东西。搬完把目录改名而不是删掉——万一搬砸了，原件还在。
     * 只在目录还存在时跑，正常情况下一辈子只跑一次。
     */
    private suspend fun migrateLegacyIfAny() {
        val legacy = File(context.filesDir, "rag_docs")
        if (!legacy.isDirectory) return
        val files = legacy.listFiles { f -> f.extension == "txt" }.orEmpty()
        for (f in files) {
            val text = try { f.readText() } catch (_: Exception) { continue }
            if (text.isBlank()) continue
            // 旧文件名形如「标题_20260729_101500」，把尾巴上的时间戳去掉还原标题
            val name = f.nameWithoutExtension.replace(Regex("_\\d{8}_\\d{6}$"), "")
            runCatching { DocChunker.store(mm, name, text) }
        }
        runCatching { legacy.renameTo(File(context.filesDir, "rag_docs_migrated")) }
    }
}
