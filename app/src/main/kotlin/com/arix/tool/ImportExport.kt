package com.arix.tool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.arix.app.CharacterCardManager
import com.arix.app.ConversationManager
import com.arix.app.MemoryManager
import com.arix.cloudapi.model.ChatMessage
import com.arix.data.entity.CharacterCardEntity
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

    /**
     * 角色卡导出——**只导角色卡本体，不连带捆绑别的东西**。
     *
     * 导的只有**这张卡是谁**：名字、描述、人设、开场白、语气/长度/语言这些。其余一概不带：
     *  - 工具范围（[com.arix.app.CardToolStore]）、对话示例 / 显示替换规则
     *    （[com.arix.app.CardRoleplayStore]）——按 cardId 存在别处的旁路数据；
     *  - **世界书**——用户 2026-07-28 定：世界书本来就不该跟着角色卡，也不是一张卡必须有的东西。
     *    卡实体上那一列还在（早先建的卡还用着），但导出不带它、导入也不往里写
     *    （外来卡内嵌的 lorebook 改成落一条独立世界书再绑到卡上，见 [importCard]）。
     *
     * 为什么要拆：卡是拿来**分享**的。把这些一起塞进去，「发一张卡」就变成了发一个捆绑包——
     * 对方连带被改掉「这张卡能动哪些工具」这种跟人设无关的东西，他还看不见。
     * 这几样想单独搬走各有各的入口（工具范围/扮演增强在导入导出中心，世界书在世界树页），
     * 换机整搬则走全量备份。
     *
     * 头像不导：avatarPath 是本机文件路径，搬到别的设备只会指向一个不存在的文件。
     */
    suspend fun exportCharacterCard(card: CharacterCardEntity): String = JSONObject().apply {
        put("name", card.name)
        put("description", card.description)
        put("characterSetting", card.characterSetting)
        put("openingStatement", card.openingStatement)
        put("tone", card.tone)
        put("length", card.length)
        put("language", card.language)
        put("waifuEnabled", card.waifuEnabled)
        put("waifuDelayMs", card.waifuDelayMs)
        put("isDefault", card.isDefault)
        put("version", 1)
        put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
    }.toString(2)

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
            put("characterCardId", conv.characterCardId ?: JSONObject.NULL)
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

    // ==================== Import ====================

    /** 支持我们自己的、以及酒馆 V1/V2 / Operit / 通用角色卡——统一转成我们的格式再落库。 */
    suspend fun importCharacterCard(json: String, context: Context): String {
        val raw = JSONObject(json)
        // 整批数组：{cards:[...]} 或顶层就是数组文本
        val batch = raw.optJSONArray("cards")
        if (batch != null) {
            var n = 0
            for (i in 0 until batch.length()) { batch.optJSONObject(i)?.let { importCard(it, context); n++ } }
            return "已导入 $n 张角色卡"
        }
        return importCard(raw, context)
    }

    private suspend fun importCard(raw: JSONObject, context: Context): String {
        val obj = ImportConverters.normalizeCard(raw)
        val cm = CharacterCardManager(context)
        val id = cm.create(
            name = obj.optString("name", "导入角色").take(80),
            description = obj.optString("description", "").take(2000),
            characterSetting = obj.optString("characterSetting", "").take(4000),
            openingStatement = obj.optString("openingStatement", "").take(1000),
            tone = obj.optString("tone", ""), length = obj.optString("length", ""),
            language = obj.optString("language", ""),
            // 世界书**不进卡**（用户 2026-07-28 定：世界书本来就不该跟着角色卡，也不是一张卡必须有的东西）。
            // 卡上那一列留着只为兼容早先建的卡，新导入的一律走下面的独立世界书 + 绑定。
            worldBook = ""
        )
        // 外来卡里内嵌的世界书（酒馆 character_book / lorebook，或我们自己老版本导出的 worldBook 键）
        // **不丢、但也不塞进卡**：落成一条独立世界书，再绑到这张新卡上。
        // 这样它是个看得见、能单独改、能解绑的东西，而不是长在人设里、分享卡片时被顺手带走的暗数据。
        val book = obj.optString("worldBook", "").trim()
        // 结构化条目优先：酒馆卡内嵌的 lorebook 是逐条带触发词/深度/顺序的，
        // 只落纯文本等于把这些全扔了（见 saveWorldBook 同款注释）。纯文本仍留着当兜底。
        val bookEntries = com.arix.app.WorldTreeStore.entriesFromJson(obj.optJSONArray("worldBookEntries"))
        if (book.isNotBlank() || bookEntries.isNotEmpty()) runCatching {
            val name = obj.optString("name", "导入角色").take(80)
            val treeId = com.arix.app.WorldTreeStore.save(
                context, 0L, "$name 的世界书", "随角色卡「$name」一起导入", book,
                entries = bookEntries.ifEmpty { null })
            com.arix.app.WorldTreeStore.bind(context, id, treeId)
        }
        // 注：老版本（捆绑期）导出的卡文件里可能还带着 toolExcludes / roleplayExamples /
        // displayRules 三个键。现在一律**不读**——卡文件不再是旁路数据的载体，读了就等于
        // 允许一张外来卡悄悄改本机的工具范围。多出来的键交给 JSONObject 自然忽略，不报错、
        // 不影响这张卡的其它字段，老文件照样能导进来。
        return "已导入角色卡: ${obj.optString("name", "未知")} (ID: $id)" +
            if (book.isNotBlank()) "，内嵌世界书已单独存为一条并绑定到这张卡" else ""
    }

    // ==================== 角色卡的旁路数据：各自独立导出 / 导入 ====================
    //
    // 这里两组数据都**不是**角色卡实体的字段，是按 cardId 落在 SharedPreferences 的另一套东西：
    //   - 工具范围（这张卡不带哪些功能包，[com.arix.app.CardToolStore]）
    //   - 扮演增强（对话示例 few-shot + 显示替换规则，[com.arix.app.CardRoleplayStore]）
    // 所以它们不进角色卡文件，而是各走各的入口（导入导出中心里各一个）。
    //
    // 关联用**卡名**而不是 cardId：cardId 是自增主键，导出文件里的 id 换台设备就没有意义。
    // 同名卡只认第一张——重名本来就分不出来，宁可少写一张也别写到错的卡上。
    // 本机没有的卡名一律跳过（不会替你凭空建卡），并在结果里报出跳过了几条。

    private suspend fun allCards(context: Context) =
        com.arix.data.db.AppDatabase.getInstance(context).characterCardDao().getAll().first()

    /** 卡名 → 卡。重名只留**第一张**（associateBy 会留最后一张，正好相反，所以手写）。 */
    private suspend fun cardsByName(context: Context): Map<String, CharacterCardEntity> {
        val m = LinkedHashMap<String, CharacterCardEntity>()
        allCards(context).forEach { if (!m.containsKey(it.name)) m[it.name] = it }
        return m
    }

    /** 导出「角色卡工具范围」全量（只导设过范围的卡；没设过=不限制，没什么可导的）。 */
    suspend fun exportCardToolScopes(context: Context): String {
        val arr = JSONArray()
        allCards(context).forEach { c ->
            val ex = com.arix.app.CardToolStore.excluded(context, c.id)
            if (ex.isNotEmpty()) arr.put(JSONObject().apply {
                put("card", c.name)
                put("toolExcludes", JSONArray(ex.sorted()))
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("cardToolScopes", arr)
            put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        }.toString(2)
    }

    /** 导入「角色卡工具范围」：按卡名回填到本机同名卡上。 */
    suspend fun importCardToolScopes(json: String, context: Context): String {
        val arr = JSONObject(json).optJSONArray("cardToolScopes") ?: return "未识别到工具范围数据"
        val byName = cardsByName(context)
        var ok = 0; var skipped = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val card = byName[o.optString("card", "")]
            if (card == null) { skipped++; continue }
            val ids = o.optJSONArray("toolExcludes")?.let { a ->
                (0 until a.length()).mapNotNull { a.optString(it, "").trim().ifBlank { null } }.toSet()
            } ?: emptySet()
            // 空集也照写：「这张卡不排除任何包」是一个有效状态，不是"没数据"
            com.arix.app.CardToolStore.setExcluded(context, card.id, ids)
            ok++
        }
        return "已导入 $ok 条工具范围" + if (skipped > 0) "（$skipped 条找不到同名角色卡，已跳过）" else ""
    }

    /** 导出「角色卡扮演增强」全量（对话示例 + 显示替换规则；两样都空的卡不占位）。 */
    suspend fun exportCardRoleplay(context: Context): String {
        val arr = JSONArray()
        allCards(context).forEach { c ->
            val ex = com.arix.app.CardRoleplayStore.examplesText(context, c.id)
            val rx = com.arix.app.CardRoleplayStore.rulesText(context, c.id)
            if (ex.isNotBlank() || rx.isNotBlank()) arr.put(JSONObject().apply {
                put("card", c.name)
                if (ex.isNotBlank()) put("roleplayExamples", ex)
                if (rx.isNotBlank()) put("displayRules", rx)
            })
        }
        return JSONObject().apply {
            put("version", 1)
            put("cardRoleplay", arr)
            put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
        }.toString(2)
    }

    /** 导入「角色卡扮演增强」：按卡名回填。缺哪个键就不动哪一项（不拿空串覆盖本机已有的）。 */
    suspend fun importCardRoleplay(json: String, context: Context): String {
        val arr = JSONObject(json).optJSONArray("cardRoleplay") ?: return "未识别到扮演增强数据"
        val byName = cardsByName(context)
        var ok = 0; var skipped = 0
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val card = byName[o.optString("card", "")]
            if (card == null) { skipped++; continue }
            var touched = false
            o.optString("roleplayExamples", "").takeIf { it.isNotBlank() }?.let {
                com.arix.app.CardRoleplayStore.setExamplesText(context, card.id, it); touched = true
            }
            o.optString("displayRules", "").takeIf { it.isNotBlank() }?.let {
                com.arix.app.CardRoleplayStore.setRulesText(context, card.id, it); touched = true
            }
            if (touched) ok++
        }
        return "已导入 $ok 条扮演增强" + if (skipped > 0) "（$skipped 条找不到同名角色卡，已跳过）" else ""
    }

    /** 世界书导出：我们自己的格式。 */
    suspend fun exportWorldBook(name: String, description: String, content: String): String = JSONObject().apply {
        put("name", name); put("description", description); put("content", content)
        put("version", 1)
        put("exportedAt", SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
    }.toString(2)

    /** 世界书导入：支持我们的、整批数组、以及 lorebook/world-info（酒馆等）——转成我们的世界书。 */
    suspend fun importWorldBook(json: String, context: Context): String {
        val raw = JSONObject(json)
        val batch = raw.optJSONArray("worldBooks")
        if (batch != null) {
            var n = 0
            for (i in 0 until batch.length()) { batch.optJSONObject(i)?.let { saveWorldBook(it, context); n++ } }
            return "已导入 $n 本世界书"
        }
        return saveWorldBook(raw, context)
    }

    private fun saveWorldBook(raw: JSONObject, context: Context): String {
        val obj = ImportConverters.normalizeWorldBook(raw)
        val name = obj.optString("name", "导入世界书")
        // ⚠ 必须把 entries 逐条传进去：不传的话整本会被折叠成**一条**，
        // 那 50 条 lorebook 各自的触发词/深度/顺序就全丢了，变成一坨无条件常驻文本永久占上下文。
        // `.ifEmpty { null }` 不能省——传空 List 会存出一本零条目的空书（世界书静悄悄失效，
        // 比崩溃还难发现）；传 null 才会走老格式折叠这条兼容路。
        com.arix.app.WorldTreeStore.save(
            context, 0L, name, obj.optString("description", ""), obj.optString("content", ""),
            entries = com.arix.app.WorldTreeStore.entriesFromJson(obj.optJSONArray("entries")).ifEmpty { null },
        )
        return "已导入世界书: $name"
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
        val cardId = if (obj.has("characterCardId") && !obj.isNull("characterCardId")) obj.optLong("characterCardId") else null
        val convId = convMgr.create(characterCardId = cardId, title = obj.optString("title", "导入对话"))
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
