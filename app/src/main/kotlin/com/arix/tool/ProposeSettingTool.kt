package com.arix.tool

import com.arix.app.SettingApplier
import com.arix.app.SettingProposalBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// AI 申请改设置：只提交申请，用户在对话里点同意才由 app 生效。AI 无法直接改。
class ProposeSettingTool(private val context: android.content.Context) : Tool {
    override val name = "propose_setting_change"
    override val description = "向用户申请修改一项 app 设置。你不能直接改设置，这个工具只提交申请，用户在对话里点同意后才由 app 生效。target 只能是下面列出的安全项；API key、角色卡、模型配置不允许改。开关类 value 用 true/false。"
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("target", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(SettingApplier.targets().map { it.first }))
                put("description", "要改的设置：" + SettingApplier.targets().joinToString("；") { "${it.first}=${it.second}" })
            })
            put("value", JSONObject().apply { put("type", "string"); put("description", "新值，开关类用 true/false") })
            put("reason", JSONObject().apply { put("type", "string"); put("description", "为什么要改，给用户看") })
        })
        put("required", JSONArray(listOf("target", "value", "reason")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val target = params.optString("target", "")
        if (!SettingApplier.isAllowed(target)) return@withContext ToolResult("不允许修改「$target」：不在白名单（key/角色卡/模型等受保护项不可申请）。", isError = true)
        val value = params.optString("value", "")
        val reason = params.optString("reason", "")
        val old = SettingApplier.current(context, target) ?: ""
        SettingProposalBus.add(target, SettingApplier.label(target), value, old, reason)
        ToolResult("已提交修改申请：「${SettingApplier.label(target)}」 $old → $value。等用户在对话里点同意才会生效，你无法直接改。")
    }
}
