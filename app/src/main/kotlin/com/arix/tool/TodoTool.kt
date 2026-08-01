package com.arix.tool

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
// todo —— AI 把多步任务拆成清单，边做边更新进度给用户看（仿 Claude Code 的任务清单）。
// 每次调用传完整清单，整体刷新界面上的 live 卡片。TodoBus 是全局单例，ChatScreen 观察渲染。
// ============================================================
data class TodoItem(val content: String, val status: String)   // pending 待办 / doing 进行中 / done 完成

object TodoBus {
    val state = MutableStateFlow<List<TodoItem>>(emptyList())
    fun set(items: List<TodoItem>) { state.value = items }
    fun clear() { state.value = emptyList() }
}

class TodoTool : Tool {

    override val name = "todo"
    override val description = "把一个多步骤任务拆成清单，边做边更新进度给用户看。每次调用传完整清单，每项带状态：pending 待办、doing 进行中、done 完成。开工前先列计划，每完成一步就再调一次刷新状态。清单传空数组就收起。适合活儿有好几步、值得让用户看到进展的时候。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Break a multi-step job into a checklist the user can watch. Pass the whole list every call, each item with status pending/doing/done. List the plan before starting, then call again after each step. An empty array hides it. Use it when the work has several steps worth showing."

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("todos", JSONObject().apply {
                put("type", "array")
                put("description", "the whole list, in execution order")
                put("items", JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("content", JSONObject().apply { put("type", "string"); put("description", "what this step does, one line") })
                        put("status", JSONObject().apply {
                            put("type", "string")
                            put("enum", JSONArray(listOf("pending", "doing", "done")))
                            put("description", "pending / doing / done")
                        })
                    })
                    put("required", JSONArray(listOf("content", "status")))
                })
            })
        })
        put("required", JSONArray(listOf("todos")))
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val arr = params.optJSONArray("todos") ?: JSONArray()
        val items = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val c = o.optString("content").trim()
            if (c.isBlank()) return@mapNotNull null
            val s = o.optString("status", "pending").lowercase().let { if (it in setOf("pending", "doing", "done")) it else "pending" }
            TodoItem(c, s)
        }
        TodoBus.set(items)
        if (items.isEmpty()) return ToolResult("清单已清空")
        val done = items.count { it.status == "done" }
        return ToolResult("清单已更新：$done/${items.size} 完成")
    }
}
