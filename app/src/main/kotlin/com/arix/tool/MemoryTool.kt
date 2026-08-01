package com.arix.tool

import android.content.Context
import com.arix.app.MemoryManager
import com.arix.app.MemorySalvage
import org.json.JSONArray
import org.json.JSONObject

// 记忆字段与条数上限（多处复用同一语义）
private const val MEMORY_TITLE_MAX = 80
private const val MEMORY_CONTENT_MAX = 500

/**
 * 总条数**兜底保护**上限——不是预算。记全了才叫长期记忆，卡总量等于逼着模型忘事；
 * 真正花钱的是每轮注入进提示词的常驻块有多大，那个预算在注入侧算，不在这里。
 * 这里只防一件事：写入逻辑出问题或模型失控刷条目，把库撑到影响检索/存储。
 * 超出这个量级的旧条目沉在归档层，靠检索(search/向量)取回，不占每轮注入。
 */
// internal 而非 private：MemorySalvage 的手动瘦身目标要跟着它走，写死两个数迟早对不上
internal const val MEMORY_MAX_COUNT = 1000

/** 逼近上限的余量：还剩这么多位置就先自动压缩，别等真写不进去才处理（按上限的一成取）。 */
private const val MEMORY_COMPRESS_HEADROOM = MEMORY_MAX_COUNT / 10

class MemoryTool(private val memoryManager: MemoryManager, private val characterCardId: Long? = null, private val appContext: Context? = null) : Tool {

    override val name = "memory"
    override val description = "长期记忆库。既记关于用户的信息（偏好、个人事实、决定、人物关系、待办），也记关于怎么干活的知识（踩过的坑、这台设备的情况、用户定下的规矩）——两类都值得长期记住，遇到就主动调用 add 记下（无需先征求同意）；需要过往信息时用 search 检索。用 type 分类。"
    // 模型侧走英文（见 Tool.llmDescription）：语义与上面那句一致，只是不交中文税、也不啰嗦。
    override val llmDescription = "Long-term memory. Stores facts about the user (preferences, personal facts, decisions, relationships, todos) and knowledge about working here (pitfalls hit, this device's setup, rules the user set). Both are worth keeping: when one comes up, call add yourself without asking first. Call search when you need past context."

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("search", "add", "update", "delete", "link", "pin")))
                put("description", "link=connect this memory to existing ones (memory graph); pin=pin/unpin, pinned is never auto-compacted")
            })
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", "keywords; required for search")
            })
            put("title", JSONObject().apply {
                put("type", "string")
                put("description", "title; required for add/update")
            })
            put("content", JSONObject().apply {
                put("type", "string")
                put("description", "body; required for add/update")
            })
            put("importance", JSONObject().apply {
                put("type", "number")
                put("description", "0.0-1.0; default 0.5, 0.8 important, 1.0 core")
            })
            put("tags", JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply { put("type", "string") })
                put("description", "optional, for grouping and retrieval")
            })
            put("type", JSONObject().apply {
                put("type", "string")
                // 前五类都是「关于人」的，装不下「关于怎么干活」的知识：踩过的坑、这台设备的客观情况、
                // 用户定下的规矩，以前只能硬塞进 fact，检索时和个人事实混成一锅。后三类补的就是这层。
                put("enum", JSONArray(listOf("preference", "fact", "event", "relation", "todo", "lesson", "environment", "convention")))
                put("description", "preference=what the user wants or likes; fact=stable fact about the user or their situation; " +
                    "event=something that happened, has a time; relation=how two people relate; todo=not done yet; " +
                    "lesson=tried and failed, pitfall to avoid next time; " +
                    "environment=device, system, accounts, paths, what is installed — objective state of this machine; " +
                    "convention=rules, wording, naming and workflow the user set. Default fact")
            })
            put("related", JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply { put("type", "string") })
                put("description", "titles of existing memories to link this one to")
            })
            put("pinned", JSONObject().apply {
                put("type", "boolean")
                put("description", "for action=pin: true=pin, false=unpin")
            })
        })
        put("required", JSONArray(listOf("action")))
    }

    private fun parseTags(params: JSONObject): List<String> =
        params.optJSONArray("tags")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        } ?: emptyList()

    override suspend fun execute(params: JSONObject): ToolResult {
        val action = params.optString("action", "")
        return when (action) {
            "search" -> searchMemories(params.optString("query", ""))
            "add" -> addMemory(params)
            "update" -> updateMemory(params)
            "delete" -> deleteMemory(params)
            "link" -> linkMemory(params)
            "pin" -> pinMemory(params)
            else -> ToolResult("未知操作: $action，支持 search/add/update/delete/link/pin", isError = true)
        }
    }

    // 优先用显式传入的卡 id；否则回退到当前活跃会话的角色卡（工具是单例，注册时无卡上下文）。
    private fun effectiveCardId(): Long? = characterCardId ?: ActiveChatContext.characterCardId

    private suspend fun searchMemories(query: String): ToolResult {
        if (query.isBlank()) return ToolResult("请提供搜索关键词")
        // 这里开语义兜底：是模型自己决定要查记忆，愿意为这次查询多等一次往返；
        // 而每轮自动注入那条路（MemoryInjection）**不开**——它被 1500ms 超时包着，结果多半白花。
        val results = memoryManager.queryRelevant(query, 5, effectiveCardId(), allowLlmFallback = true)
        if (results.isEmpty()) return ToolResult("未找到相关记忆")
        val sb = StringBuilder("找到 ${results.size} 条相关记忆:\n")
        results.forEachIndexed { i, mem ->
            sb.append("${i + 1}. ${mem.title} (重要度: ${"%.1f".format(mem.importance)})\n")
            sb.append("   ${mem.content}\n")
        }
        return ToolResult(sb.toString())
    }

    private suspend fun addMemory(params: JSONObject): ToolResult {
        val title = params.optString("title", "").take(MEMORY_TITLE_MAX)
        val content = params.optString("content", "").take(MEMORY_CONTENT_MAX)
        if (title.isBlank() || content.isBlank()) return ToolResult("标题和内容不能为空", isError = true)
        val importance = params.optDouble("importance", 0.5).toFloat().coerceIn(0f, 1f)
        // 逼近上限 → 自动智能压缩(确定性、安全)：去重+删临时，替代旧的「满了直接报错」死路
        val ctx = appContext
        if (ctx != null && memoryManager.count() >= MEMORY_MAX_COUNT - MEMORY_COMPRESS_HEADROOM) {
            runCatching { MemorySalvage.autoCompress(ctx, MEMORY_MAX_COUNT) }
        }
        val count = memoryManager.count()
        // 这段是**说给模型听**的：手表上没人盯着屏幕，把「请手动整理」抛给用户等于这条记忆直接丢了。
        // 所以给的是它自己能据此动手的东西——现状数字 + 已经做过什么 + 现在还能做什么，让它同一轮里腾出位置再写。
        if (count >= MEMORY_MAX_COUNT) return ToolResult(
            "写入失败：记忆库 $count/$MEMORY_MAX_COUNT 条，已到兜底上限。自动压缩(同标题去重、清理低权重且长期没被用到的条目)刚跑过，仍然满，" +
                "剩下的都是置顶或高重要度条目，代码不会替你删。「$title」没有存进去。\n" +
                "在这一轮里自己腾位置，然后重试 add，别把整理这件事交给用户：\n" +
                "- search 找讲同一件事的几条，把信息并进其中一条 update，再 delete 掉多余的；\n" +
                "- delete 已经做完或已经过期的 todo；\n" +
                "- 对不再重要的条目 update 一个更低的 importance，下次自动压缩就能清掉它。",
            isError = true,
        )
        val tags = parseTags(params)
        val type = params.optString("type", "fact").ifBlank { "fact" }
        val id = memoryManager.upsertByTitle(title, content, "ai_tool", importance, effectiveCardId(), tags, type)
        // 轻量图谱：把 related 标题解析成 id 关联起来
        val relatedTitles = params.optJSONArray("related")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList()
        if (relatedTitles.isNotEmpty()) { val ids = relatedTitles.mapNotNull { memoryManager.idByTitle(it) }; if (ids.isNotEmpty()) memoryManager.linkRelated(id, ids) }
        return ToolResult("已记录记忆: $title${if (tags.isNotEmpty()) "  [${tags.joinToString(",")}]" else ""}")
    }

    private suspend fun updateMemory(params: JSONObject): ToolResult {
        val title = params.optString("title", "").take(MEMORY_TITLE_MAX)
        val content = params.optString("content", "").take(MEMORY_CONTENT_MAX)
        if (title.isBlank()) return ToolResult("标题不能为空", isError = true)
        val importance = params.optDouble("importance", -1.0).let { if (it < 0) null else it.toFloat().coerceIn(0f, 1f) }
        val existing = memoryManager.search(title).firstOrNull()
        if (existing == null) return ToolResult("未找到记忆: $title，请先用add创建", isError = true)
        val tags = parseTags(params).ifEmpty { null }
        // reassert：模型改一条记忆的正文，基本都是因为**情况变了**（换了工作/口味变了），不是在校对错别字——
        // 它手上没有原文可对，也没有理由为了措辞去调工具。所以这条路径算「重新断言」，刷新 assertedAt。
        // 用户在记忆页里手改属于订正，那条路径走 update 的默认值(false)，不刷新。
        memoryManager.update(existing.id, title = title, content = content.ifBlank { null },
            importance = importance, tags = tags, reassert = true)
        return ToolResult("已更新记忆: $title")
    }

    private suspend fun linkMemory(params: JSONObject): ToolResult {
        val title = params.optString("title", "").take(MEMORY_TITLE_MAX)
        if (title.isBlank()) return ToolResult("请提供要关联的记忆标题", isError = true)
        val id = memoryManager.idByTitle(title) ?: memoryManager.search(title).firstOrNull()?.id
            ?: return ToolResult("未找到记忆: $title", isError = true)
        val relatedTitles = params.optJSONArray("related")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } } } ?: emptyList()
        val ids = relatedTitles.mapNotNull { memoryManager.idByTitle(it) ?: memoryManager.search(it).firstOrNull()?.id }.filter { it != id }
        if (ids.isEmpty()) return ToolResult("没找到可关联的相关记忆（related 里的标题都不存在）", isError = true)
        memoryManager.linkRelated(id, ids)
        return ToolResult("已把「$title」与 ${ids.size} 条记忆关联")
    }

    private suspend fun pinMemory(params: JSONObject): ToolResult {
        val title = params.optString("title", "").take(MEMORY_TITLE_MAX)
        if (title.isBlank()) return ToolResult("请提供记忆标题", isError = true)
        val existing = memoryManager.search(title).firstOrNull() ?: return ToolResult("未找到记忆: $title", isError = true)
        val pinned = params.optBoolean("pinned", true)
        memoryManager.setPinned(existing.id, pinned)
        return ToolResult(if (pinned) "已置顶「$title」（不会被自动清理）" else "已取消置顶「$title」")
    }

    private suspend fun deleteMemory(params: JSONObject): ToolResult {
        val title = params.optString("title", "").take(MEMORY_TITLE_MAX)
        if (title.isBlank()) return ToolResult("请提供要删除的记忆标题", isError = true)
        val existing = memoryManager.search(title).firstOrNull()
        if (existing == null) return ToolResult("未找到记忆: $title", isError = true)
        memoryManager.delete(existing.id)
        return ToolResult("已删除记忆: $title")
    }
}
