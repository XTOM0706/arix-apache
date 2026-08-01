package com.arix.tool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
// workflow —— AI 建/跑/管理可复用工作流（一个工具多用）。
// ============================================================
class WorkflowTool(private val context: Context) : Tool {
    override val name = "workflow"
    override val description = "工作流：把一串工具调用存成可复用流程。action=create(name+steps 定义并保存)、run(name+input 运行)、list(列出)、show(看步骤)、delete(删)。steps=数组，每项 {tool:工具名, args:{参数}}，args 里可用 {{input}}(运行输入)/{{last}}(上一步结果)/{{step1}}(第1步结果) 引用；每项可选 onError:continue 出错也继续。"
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("create", "run", "list", "show", "delete"))); put("description", "操作") })
            put("name", JSONObject().apply { put("type", "string"); put("description", "工作流名字") })
            put("steps", JSONObject().apply {
                put("type", "array")
                put("description", "create 时的步骤：每项 {tool, args, onError?}")
                put("items", JSONObject().apply { put("type", "object") })
            })
            put("input", JSONObject().apply { put("type", "string"); put("description", "run 时传给工作流的输入（可在步骤里用 {{input}} 引用）") })
            put("desc", JSONObject().apply { put("type", "string"); put("description", "工作流描述，可选") })
        })
        put("required", JSONArray(listOf("action")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        when (params.optString("action", "")) {
            "create" -> {
                val name = params.optString("name", "").trim()
                val steps = params.optJSONArray("steps")
                if (name.isBlank() || steps == null || steps.length() == 0) return@withContext ToolResult("create 需要 name 和非空 steps", isError = true)
                WorkflowStore.save(context, JSONObject().put("name", name).put("desc", params.optString("desc", "")).put("steps", steps))
                ToolResult("已保存工作流「$name」（${steps.length()} 步）。用 workflow run name=$name 运行。")
            }
            "run" -> {
                val name = params.optString("name", "").trim()
                val wf = WorkflowStore.get(context, name) ?: return@withContext ToolResult("没有工作流「$name」", isError = true)
                ToolResult(WorkflowEngine.run(context, wf, params.optString("input", "")))
            }
            "list" -> {
                val ns = WorkflowStore.names(context)
                if (ns.isEmpty()) ToolResult("还没有工作流") else ToolResult("工作流：\n" + ns.joinToString("\n") { "· $it" })
            }
            "show" -> {
                val wf = WorkflowStore.get(context, params.optString("name", "").trim()) ?: return@withContext ToolResult("没找到", isError = true)
                ToolResult(wf.toString(2))
            }
            "delete" -> {
                val name = params.optString("name", "").trim()
                WorkflowStore.delete(context, name); ToolResult("已删除工作流「$name」")
            }
            else -> ToolResult("未知 action", isError = true)
        }
    }
}
