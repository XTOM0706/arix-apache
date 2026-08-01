package com.arix.tool

import android.content.Context
import com.arix.app.MemoryManager
import org.json.JSONArray
import org.json.JSONObject

// 记忆字段与条数上限（多处复用同一语义）
private const val MEMORY_TITLE_MAX = 80
private const val MEMORY_CONTENT_MAX = 500

/**
 * 总条数**兜底保护**上限——不是预算。记全了才叫长期记忆，卡总量等于逼着模型忘事；
 * 真正花钱的是每轮注入进提示词的常驻块有多大，那个预算在注入侧算，不在这里。
 * 这里只防一件事：写入逻辑出问题或模型失控刷条目，把库撑到影响检索/存储。
 */
internal const val MEMORY_MAX_COUNT = 1000

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
                put("enum", JSONArray(listOf("search", "add", "update", "delete", "pin")))
                put("description", "pin=pin/unpin, pinned is never auto-compacted")
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
            "pin" -> pinMemory(params)
            else -> ToolResult("未知操作: $action，支持 search/add/update/delete/pin", isError = true)
        }
    }

    // 优先用显式传入的卡 id；否则回退到当前活跃会话的角色卡（工具是单例，注册时无卡上下文）。
    private fun effectiveCardId(): Long? = characterCardId ?: ActiveChatContext.characterCardId

    private suspend fun searchMemories(query: String): ToolResult {
        if (query.isBlank()) return ToolResult("请提供搜索关键词")
        val results = memoryManager.queryRelevant(query, 5, effectiveCardId())
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
        val count = memoryManager.count()
        // 这段是**说给模型听**的：手表上没人盯着屏幕，把「请手动整理」抛给用户等于这条记忆直接丢了。
        // 所以给的是它自己能据此动手的东西——现状数字 + 现在还能做什么，让它同一轮里腾出位置再写。
        if (count >= MEMORY_MAX_COUNT) return ToolResult(
            "写入失败：记忆库 $count/$MEMORY_MAX_COUNT 条，已到兜底上限。「$title」没有存进去。\n" +
                "在这一轮里自己腾位置，然后重试 add，别把整理这件事交给用户：\n" +
                "- search 找讲同一件事的几条，把信息并进其中一条 update，再 delete 掉多余的；\n" +
                "- delete 已经做完或已经过期的 todo。",
            isError = true,
        )
        val tags = parseTags(params)
        val type = params.optString("type", "fact").ifBlank { "fact" }
        memoryManager.upsertByTitle(title, content, "ai_tool", importance, effectiveCardId(), tags, type)
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
        memoryManager.update(existing.id, title = title, content = content.ifBlank { null },
            importance = importance, tags = tags)
        return ToolResult("已更新记忆: $title")
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
