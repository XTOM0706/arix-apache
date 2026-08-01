package com.arix.tool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.arix.app.ConversationManager
import com.arix.app.MemoryManager
import com.arix.cloudapi.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ImportExport {

    // ==================== Export ====================

    suspend fun exportConfig(apiConfig: com.arix.data.entity.ApiConfigEntity): String = JSONObject().apply {
        put("name", apiConfig.name)
        put("baseUrl", apiConfig.baseUrl)
        if (apiConfig.apiKey.isNotBlank()) put("apiKey", apiConfig.apiKey)
        put("model", apiConfig.model)
        put("purpose", apiConfig.purpose)
        put("isActive", apiConfig.isActive)
        put("supportsVision", apiConfig.supportsVision)
        put("supportsAudio", apiConfig.supportsAudio)
        put("supportsVideo", apiConfig.supportsVideo)
        put("systemPrompt", apiConfig.systemPrompt)
        put("version", 1)
        put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
    }.toString(2)

    suspend fun exportConversation(convId: Long, context: Context): String? {
        val conv = ConversationManager(context).repo.getById(convId) ?: return null
        val msgs = ConversationManager(context).loadMessages(convId)
        return JSONObject().apply {
            put("title", conv.title)
            put("createdAt", conv.createdAt)
            put("updatedAt", conv.updatedAt)
            put("version", 1)
            val arr = JSONArray()
            msgs.forEach { m ->
                arr.put(JSONObject().apply {
                    put("role", m.role)
                    put("content", m.content)
                    if (m.reasoning != null) put("reasoning", m.reasoning)
                    // extra=供应商私有扩展（Gemini 3 思考签名），一并导出才能导回来接着聊
                    if (m.toolCalls != null) { val tcArr = JSONArray(); m.toolCalls?.forEach { tc -> tcArr.put(JSONObject().apply { put("id",tc.id); put("name",tc.name); put("arguments",tc.arguments); if (tc.extra != null) put("extra", tc.extra) }) }; put("tool_calls", tcArr) }
                    if (m.toolCallId != null) put("tool_call_id", m.toolCallId)
                })
            }
            put("messages", arr)
            put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        }.toString(2)
    }

    private fun roleLabel(role: String): String = when (role) {
        "user" -> "用户"; "assistant" -> "助手"; "system" -> "系统"; "tool" -> "工具"; else -> role
    }

    // 把对话「转化」成通用可读 Markdown（不改内部格式，纯导出转换；任意编辑器/竞品可读）。
    suspend fun exportConversationMarkdown(convId: Long, context: Context): String? {
        val cm = ConversationManager(context)
        val conv = cm.repo.getById(convId) ?: return null
        val msgs = cm.loadMessages(convId)
        val sb = StringBuilder()
        sb.append("# ").append(conv.title.ifBlank { "对话" }).append("\n\n")
        sb.append("- 导出：Arix · ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())).append("\n")
        sb.append("- 消息数：").append(msgs.size).append("\n\n---\n")
        msgs.forEach { m ->
            sb.append("\n**").append(roleLabel(m.role)).append("**\n\n")
            m.reasoning?.takeIf { it.isNotBlank() }?.let { sb.append("> 思考：").append(it.trim().replace("\n", "\n> ")).append("\n\n") }
            if (m.content.isNotBlank()) sb.append(m.content.trim()).append("\n")
            m.toolCalls?.forEach { tc -> sb.append("\n`工具调用 ").append(tc.name).append("` ").append(tc.arguments).append("\n") }
        }
        return sb.toString().trimEnd() + "\n"
    }

    // 转化成纯文本 TXT。
    suspend fun exportConversationText(convId: Long, context: Context): String? {
        val cm = ConversationManager(context)
        val conv = cm.repo.getById(convId) ?: return null
        val msgs = cm.loadMessages(convId)
        val sb = StringBuilder()
        sb.append(conv.title.ifBlank { "对话" }).append("\n")
        sb.append("导出：Arix · ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())).append("\n")
        sb.append("消息数：").append(msgs.size).append("\n")
        msgs.forEach { m ->
            sb.append("\n────────  [").append(roleLabel(m.role)).append("]  ────────\n")
            m.reasoning?.takeIf { it.isNotBlank() }?.let { sb.append("（思考）").append(it.trim()).append("\n\n") }
            if (m.content.isNotBlank()) sb.append(m.content.trim()).append("\n")
            m.toolCalls?.forEach { tc -> sb.append("[工具调用 ").append(tc.name).append("] ").append(tc.arguments).append("\n") }
        }
        return sb.toString().trimEnd() + "\n"
    }

    suspend fun exportMemories(context: Context, cardId: Long? = null): String {
        val mm = MemoryManager(context)
        // ⚠ 原来按卡导出走的是 `queryRelevant("", 1000, cardId)`，而 queryRelevant 见到空串**直接返回空列表**
        // ——也就是说「按角色卡导出记忆」一直导出的是零条，而且不报错，看着像"这张卡本来就没记忆"。
        // 导出要的是"全都拿来"，本就不该走相关性检索这条路。
        val memories = if (cardId != null) mm.byCard(cardId) else mm.all()
        val arr = JSONArray()
        memories.forEach { m ->
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("title", m.title)
                put("content", m.content)
                put("source", m.source)
                put("importance", m.importance.toDouble())
                if (m.characterCardId != null) put("characterCardId", m.characterCardId)
                put("createdAt", m.createdAt)
                put("updatedAt", m.updatedAt)
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("memories", arr)
            put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        }.toString(2)
    }

    suspend fun exportPackage(context: Context, type: String, name: String): String? {
        val dir = File(context.filesDir, "operit_${type}s")
        when (type) {
            "skill" -> {
                val f = File(dir, "$name/SKILL.md")
                return if (f.exists()) f.readText() else null
            }
            "sandbox" -> {
                val f = File(dir, "$name/manifest.json")
                return if (f.exists()) f.readText() else null
            }
            "mcp" -> {
                val f = File(dir, "$name.json")
                return if (f.exists()) f.readText() else null
            }
        }
        return null
    }

    suspend fun importConfig(json: String, context: Context): String {
        val root = JSONObject(json)
        val batch = root.optJSONArray("configs")
        if (batch != null) {
            var n = 0
            for (i in 0 until batch.length()) { batch.optJSONObject(i)?.let { importSingleConfig(it, context); n++ } }
            return "已导入 $n 个配置"
        }
        return importSingleConfig(root, context)
    }

    private suspend fun importSingleConfig(obj: JSONObject, context: Context): String {
        val db = com.arix.data.db.AppDatabase.getInstance(context)
        fun s(vararg ks: String): String { for (k in ks) { val v = obj.optString(k, ""); if (v.isNotBlank()) return v }; return "" }
        val entity = com.arix.data.entity.ApiConfigEntity(
            name = s("name", "label").ifBlank { "导入配置" },
            baseUrl = s("baseUrl", "base_url", "apiBase", "api_base", "url", "endpoint"),
            apiKey = s("apiKey", "api_key", "key", "token"),
            model = s("model", "modelName", "model_name"),
            purpose = obj.optString("purpose", "chat"),
            isActive = false,
            supportsVision = obj.optBoolean("supportsVision", false),
            supportsAudio = obj.optBoolean("supportsAudio", false),
            supportsVideo = obj.optBoolean("supportsVideo", false),
            systemPrompt = obj.optString("systemPrompt", "")
        )
        db.apiConfigDao().insert(entity)
        return "已导入配置: ${entity.name}"
    }

    suspend fun importConversation(json: String, context: Context): String {
        val obj = JSONObject(json)
        val convMgr = ConversationManager(context)
        val convId = convMgr.create(title = obj.optString("title", "导入对话"))
        val msgs = mutableListOf<ChatMessage>()
        val arr = obj.optJSONArray("messages") ?: return "已导入对话(无消息)"
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val tcArr = m.optJSONArray("tool_calls")
            msgs.add(ChatMessage(
                role = m.getString("role"),
                content = m.optString("content", ""),
                reasoning = if (m.has("reasoning")) m.optString("reasoning") else null,
                toolCalls = if (tcArr != null && tcArr.length() > 0) (0 until tcArr.length()).map { j ->
                    val tc = tcArr.getJSONObject(j)
                    ChatMessage.ToolCallMsg(
                        tc.getString("id"), tc.getString("name"), tc.getString("arguments"),
                        // 供应商私有扩展（如 Gemini 3 思考签名）；外来/老文件没有该字段就是 null
                        extra = if (tc.has("extra") && !tc.isNull("extra")) tc.getString("extra") else null
                    )
                } else null,
                toolCallId = if (m.has("tool_call_id")) m.optString("tool_call_id") else null
            ))
        }
        convMgr.saveMessages(convId, msgs)
        return "已导入对话: ${obj.optString("title", "未知")} (${msgs.size}条消息)"
    }

    /** 支持我们的、以及 Operit/通用记忆数组——统一转换后落库。 */
    suspend fun importMemories(json: String, context: Context): String {
        val arr = ImportConverters.normalizeMemories(JSONObject(json))
        if (arr.length() == 0) return "无有效的记忆数据"
        val mm = MemoryManager(context)
        var count = 0
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            mm.upsertByTitle(
                title = m.optString("title", "").take(80),
                content = m.optString("content", "").take(500),
                source = m.optString("source", "import"),
                importance = m.optDouble("importance", 0.5).toFloat().coerceIn(0f, 1f),
                characterCardId = if (m.has("characterCardId")) m.optLong("characterCardId") else null
            )
            count++
        }
        return "已导入 $count 条记忆"
    }

    suspend fun importPackage(context: Context, type: String, name: String, content: String): String {
        val dir = File(context.filesDir, "operit_${type}s")
        if (!dir.exists()) dir.mkdirs()
        when (type) {
            "skill" -> { File(dir, name).also { it.mkdirs() }; File(dir, "$name/SKILL.md").writeText(content) }
            "sandbox" -> { File(dir, name).also { it.mkdirs() }; File(dir, "$name/manifest.json").writeText(content) }
            "mcp" -> { File(dir, "$name.json").writeText(content) }
        }
        return "已导入${when(type){"skill"->"Skill";"sandbox"->"沙盒包";else->"MCP"}}: $name"
    }

    fun copyToClipboard(context: Context, text: String, label: String = "Arix导出") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
    }

    fun saveToFile(context: Context, text: String, filename: String): File {
        val dir = File(context.getExternalFilesDir(null), "exports")
        if (!dir.exists()) dir.mkdirs()
        val f = File(dir, filename)
        f.writeText(text)
        return f
    }

    fun shareFile(context: Context, file: File, mime: String = "application/json") {
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply { type = mime; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        context.startActivity(Intent.createChooser(intent, "导出"))
    }
}
