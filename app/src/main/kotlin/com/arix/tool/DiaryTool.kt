package com.arix.tool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 日记工具：让 AI 在对话里按需 写 / 念 / 列 每日日记（与 [com.arix.app.DiaryScheduler] 的每日自动生成互补）。
 * - write：现在就为今天写一篇（需今天有过对话）
 * - read：念日记（可给 date=yyyy-MM-dd，默认最新一篇）
 * - list：列最近的日记日期与开头
 */
class DiaryTool(private val context: Context) : Tool {
    override val name = "diary"
    override val description = "每日日记：write=现在为今天写一篇小结(需今天有过对话)；read=念日记(可给 date=yyyy-MM-dd，默认最新)；list=列最近日记。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Daily diary. write: write today's entry now (needs at least one conversation today). read: read one out, date optional, defaults to the latest. list: recent entries."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply { put("type", "string"); put("description", "write / read (default) / list") })
            put("date", JSONObject().apply { put("type", "string"); put("description", "yyyy-MM-dd, for read") })
        })
        put("required", JSONArray(listOf<String>()))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        when (params.optString("action", "read")) {
            "write" -> {
                val text = com.arix.app.DiaryGenerator.generateToday(context)
                if (text.isNullOrBlank()) ToolResult("今天还没有可写的内容（今天没聊过，或未配置对话模型）")
                else ToolResult("已写下今天的日记：\n$text")
            }
            "list" -> {
                val list = com.arix.app.DiaryStore.all(context)
                if (list.isEmpty()) ToolResult("还没有日记")
                else ToolResult(list.take(14).joinToString("\n") { "· ${it.date}：${it.text.replace("\n", " ").take(30)}…" })
            }
            else -> {
                val date = params.optString("date", "")
                val entry = if (date.isNotBlank()) com.arix.app.DiaryStore.get(context, date)
                else com.arix.app.DiaryStore.all(context).firstOrNull()
                if (entry == null) ToolResult(if (date.isNotBlank()) "没有 $date 的日记" else "还没有日记")
                else ToolResult("${entry.date} 的日记：\n${entry.text}")
            }
        }
    }
}
