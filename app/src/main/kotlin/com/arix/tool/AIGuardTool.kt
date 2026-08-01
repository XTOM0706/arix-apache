package com.arix.tool

import org.json.JSONObject

class AIGuardTool : Tool {
    override val name = "ai_guard"
    override val description = "AI 操作风险拦截。在执行敏感操作前要求用户确认，可设置全局风险等级。"
    override val permissionLevel = AndroidPermissionLevel.ADMIN  // 风控/敏感操作，默认询问

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", org.json.JSONArray(listOf("set_level", "get_level", "check", "deny", "approve")))
                put("description", "set_level=设置风险等级, get_level=查看, check=检查操作风险, deny/approve=拒绝/批准")
            })
            put("level", JSONObject().apply {
                put("type", "string")
                put("enum", org.json.JSONArray(listOf("strict", "normal", "permissive")))
                put("description", "风险等级: strict=严格(所有操作需确认), normal=普通(高风险需确认), permissive=宽松(仅危险操作拦截)")
            })
            put("tool_name", JSONObject().apply {
                put("type", "string")
                put("description", "要检查的工具名称")
            })
            put("reason", JSONObject().apply {
                put("type", "string")
                put("description", "确认/拒绝的原因")
            })
        })
        put("required", org.json.JSONArray(listOf("action")))
    }

    companion object {
        private var riskLevel: String = "normal"
        private val blockedTools = mutableSetOf<String>()
        private val highRiskTools = setOf("shell", "brightness", "volume", "app_launch", "notification")
        private val dangerousTools = setOf("shell")

        fun shouldBlock(toolName: String): String? {
            return when {
                toolName in blockedTools -> "工具 '$toolName' 已被用户阻止"
                riskLevel == "strict" && toolName !in setOf("web_search", "time_utils", "calculator", "memory", "fetch", "rag") ->
                    "严格模式：工具 '$toolName' 需要确认"
                riskLevel == "normal" && toolName in highRiskTools ->
                    "普通模式：工具 '$toolName' 属于高风险操作"
                riskLevel == "permissive" && toolName in dangerousTools ->
                    "宽松模式：工具 '$toolName' 属于危险操作（如 Shell）"
                else -> null
            }
        }

        fun blockTool(name: String) { blockedTools.add(name) }
        fun unblockTool(name: String) { blockedTools.remove(name) }
        fun isToolBlocked(name: String): Boolean = name in blockedTools
        fun getRiskLevel(): String = riskLevel
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val action = params.optString("action", "get_level")
        return when (action) {
            "set_level" -> {
                riskLevel = params.optString("level", "normal")
                ToolResult("风险等级已设置为: $riskLevel (strict=严格, normal=普通, permissive=宽松)")
            }
            "get_level" -> ToolResult("当前风险等级: $riskLevel | 已阻止工具: ${blockedTools.takeIf { it.isNotEmpty() }?.joinToString() ?: "无"}")
            "check" -> {
                val toolName = params.optString("tool_name", "")
                val reason = shouldBlock(toolName)
                if (reason != null) ToolResult("⚠ 拦截: $reason", isError = true)
                else ToolResult("工具 '$toolName' 安全，可以执行")
            }
            "deny" -> {
                val toolName = params.optString("tool_name", "")
                val reason = params.optString("reason", "用户拒绝")
                blockedTools.add(toolName)
                ToolResult("已阻止工具 '$toolName': $reason")
            }
            "approve" -> {
                val toolName = params.optString("tool_name", "")
                blockedTools.remove(toolName)
                ToolResult("已批准工具 '$toolName'")
            }
            else -> ToolResult("未知操作", isError = true)
        }
    }
}
