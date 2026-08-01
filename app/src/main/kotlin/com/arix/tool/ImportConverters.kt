package com.arix.tool

import org.json.JSONArray
import org.json.JSONObject

/**
 * 导入格式转换器：把主流第三方格式（酒馆 SillyTavern/TavernAI 角色卡 V1/V2、
 * 世界书/lorebook/world-info、Operit 等）统一「转换成本应用的格式」。
 *
 * 原则（按用户要求）：只在导入时把「他们的」转成「我们的」；导出永远用我们自己的格式，
 * 不去适配别人。所有解析都尽量宽容——认得的字段就取，认不出的就走兜底，绝不丢数据。
 */
object ImportConverters {

    /** 逐层剥离常见外壳，取到真正装字段的对象。 */
    private fun unwrap(o: JSONObject): JSONObject {
        var cur = o
        // 酒馆 V2 是 {spec, data:{...}}；Operit/其它常见 {character:{...}} / {card:{...}} 等
        for (k in listOf("data", "character", "char", "card", "persona", "role", "profile")) {
            val inner = cur.optJSONObject(k) ?: continue
            // 只有当内层确实含有名称/描述类字段才下钻，避免误剥
            if (inner.has("name") || inner.has("description") || inner.has("personality") ||
                inner.has("first_mes") || inner.has("systemPrompt") || inner.has("prompt")) cur = inner
        }
        return cur
    }

    private fun pick(o: JSONObject, vararg keys: String): String {
        for (k in keys) { val v = o.optString(k, ""); if (v.isNotBlank()) return v }
        return ""
    }

    /** 把任意主流角色卡归一化成本应用字段（name/description/characterSetting/openingStatement/…/worldBook）。 */
    fun normalizeCard(raw: JSONObject): JSONObject {
        val o = unwrap(raw)
        val name = pick(o, "name", "char_name", "charName", "title").take(80)
        val description = pick(o, "description", "summary", "intro", "introduction", "creatorcomment", "creator_notes").take(2000)

        // 人设：把人格 / 系统提示 / 场景 / 对话示例都并进来，避免丢失
        val parts = mutableListOf<String>()
        pick(o, "characterSetting", "personality", "persona", "char_persona").let { if (it.isNotBlank()) parts.add(it) }
        pick(o, "systemPrompt", "system_prompt", "system", "prompt").let { if (it.isNotBlank()) parts.add("【系统提示】\n$it") }
        pick(o, "scenario", "world_scenario").let { if (it.isNotBlank()) parts.add("【场景】\n$it") }
        pick(o, "mes_example", "example_dialogue", "exampleDialogue", "examples").let { if (it.isNotBlank()) parts.add("【对话示例】\n$it") }
        val setting = parts.joinToString("\n\n").take(4000)

        val opening = pick(o, "openingStatement", "first_mes", "firstMes", "greeting", "openingLine", "first_message").take(1000)
        val bookEntries = extractCharacterBookEntries(o)
        val worldBook = renderEntries(bookEntries).ifBlank { pick(o, "worldBook", "world_book") }

        return JSONObject().apply {
            put("name", name.ifBlank { "导入角色" })
            put("description", description)
            put("characterSetting", setting)
            put("openingStatement", opening)
            put("tone", pick(o, "tone", "style"))
            put("length", pick(o, "length"))
            put("language", pick(o, "language", "lang"))
            put("worldBook", worldBook)
            // 结构化的世界书条目：每条各自的触发词/深度/顺序/常驻都在里面。worldBook 那个纯文本只是
            // 「必须是一段文本」的老字段的兼容镜像，导入侧应优先用 worldBookEntries 落成逐条的世界书。
            put("worldBookEntries", bookEntries)
        }
    }

    /** 提取角色卡内嵌的 lorebook/character_book/world_info → 扁平文本；无则返回空串。 */
    fun extractCharacterBook(o: JSONObject): String = renderEntries(extractCharacterBookEntries(o))

    /** 提取角色卡内嵌的 lorebook/character_book/world_info → 结构化条目数组；无则返回空数组。 */
    fun extractCharacterBookEntries(o: JSONObject): JSONArray {
        val book = o.optJSONObject("character_book") ?: o.optJSONObject("world_info")
            ?: o.optJSONObject("worldInfo") ?: o.optJSONObject("lorebook") ?: return JSONArray()
        return flattenLorebook(book)
    }

    // ==================== lorebook / 世界书条目 ====================
    //
    // 以前这里把 N 条 lorebook 压成一段「【keys】正文」的纯文本（所以叫 flatten）。
    // 那等于把每条各自的触发词/深度/顺序/常驻**全部丢掉**：一张带 50 条的酒馆卡导进来，
    // 结果是一坨无条件常驻的文本永久占着上下文。现在改成逐条映射，一条都不合并。
    //
    // 认得的字段（各家写法都收，认不出的走兜底，绝不丢内容）：
    //   entries      数组 / 对象映射（酒馆世界书是对象映射，键是序号；V2 character_book 是数组）
    //   keys/key/keywords/secondary_keys/keysecondary  触发词（数组或逗号分隔的字符串）
    //   content/text/entry/value                        正文
    //   comment/name                                    条目名（只给人看）
    //   insertion_order/insertionOrder/order            顺序
    //   constant                                        常驻（酒馆的蓝灯）
    //   enabled / disable(取反)                          启用
    //   position / extensions.position                  注入位（数字 0~4 或 before_char/after_char/at_depth）
    //   depth / extensions.depth                        深度（position=4/at_depth 时才有意义）
    //   role / extensions.role                          at-depth 的说话人（1=user → 我们的 position=user）

    /** 取字符串数组或「逗号分隔字符串」形式的触发词。 */
    private fun keyList(e: JSONObject, vararg names: String): List<String> {
        val out = mutableListOf<String>()
        for (n in names) {
            when (val v = e.opt(n)) {
                is JSONArray -> for (i in 0 until v.length()) v.optString(i).trim().takeIf { it.isNotBlank() }?.let { out.add(it) }
                is String -> v.split(',', '\n').map { it.trim() }.filter { it.isNotBlank() }.forEach { out.add(it) }
            }
        }
        return out
    }

    /**
     * 酒馆的 key 允许写成 /pattern/flags 的正则字面量。我们的触发词字段本身就支持「当正则试一次」，
     * 但外面裹着的斜杠会让它既不是子串也不是合法意图 —— 剥掉壳留下 pattern，语义才对得上。
     */
    private val reLiteral = Regex("^/(.+)/[gimsuy]*$")
    private fun unwrapRegexKey(k: String): String = reLiteral.find(k)?.groupValues?.get(1) ?: k

    /** 把一条 lorebook entry 里的注入位/深度映射成我们的 (position, depth)。 */
    private fun mapPosition(e: JSONObject, ext: JSONObject?): Pair<String, Int> {
        val posRaw = e.opt("position") ?: ext?.opt("position")
        val depth = intOf(e.opt("depth")) ?: intOf(ext?.opt("depth")) ?: 0
        val role = intOf(e.opt("role")) ?: intOf(ext?.opt("role"))
        // 本应用自己导出的条目 position 直接就是 system/user，原样收下（同一条解析路径也要能读回我们自己的格式）
        if (posRaw is String && (posRaw.equals("system", true) || posRaw.equals("user", true)))
            return posRaw.lowercase() to depth.coerceAtLeast(0)
        // 酒馆世界书的 position 是数字：0=角色定义前 1=角色定义后 2=作者注前 3=作者注后 4=按深度插入；
        // V2 character_book 用字符串 before_char/after_char。只有 4 / at_depth 才是「按深度插到消息之间」，
        // 其余各种「定义前后 / 作者注前后」落到我们这儿都是系统提示——我们没有作者注这个位置，硬编一个反而更假。
        val atDepth = when (posRaw) {
            is Number -> posRaw.toInt() == 4
            is String -> posRaw.equals("at_depth", true) || posRaw == "4"
            else -> false
        }
        if (atDepth) return (if (role == 1) "user" else "system") to depth.coerceAtLeast(1)
        // 没标 at_depth 但明确给了 depth 的（部分导出只写 extensions.depth），照样按深度走——它显然是这个意思
        if (depth > 0) return (if (role == 1) "user" else "system") to depth
        return "system" to 0
    }

    private fun intOf(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.trim().toIntOrNull()
        else -> null
    }

    /**
     * lorebook 的 entries 可能是「对象映射」或「数组」，逐条转成本应用的世界书条目。
     * 返回的每个元素字段 = WorldTreeStore.Entry 的 JSON 形状（name/keywords/content/position/depth/order/constant/enabled），
     * 可直接交给 WorldTreeStore.entriesFromJson()。
     */
    fun flattenLorebook(book: JSONObject): JSONArray {
        val entriesRaw = book.opt("entries")
        val src = mutableListOf<JSONObject>()
        when (entriesRaw) {
            is JSONArray -> for (i in 0 until entriesRaw.length()) entriesRaw.optJSONObject(i)?.let { src.add(it) }
            // 酒馆导出的世界书 entries 是对象映射（键是序号字符串）。keys() 的遍历顺序不保证，所以下面统一按
            // order 再排一次，别让条目顺序随 JSON 解析实现漂移。
            is JSONObject -> { val ks = entriesRaw.keys(); while (ks.hasNext()) entriesRaw.optJSONObject(ks.next())?.let { src.add(it) } }
            else -> {} // 没有 entries：可能整个就是一条，下面兜底
        }
        if (src.isEmpty() && book.has("content")) src.add(book)

        val out = JSONArray()
        var idx = 0
        val mapped = src.mapNotNull { e ->
            // 禁用的条目**保留**但标 enabled=false：以前是直接 continue 丢掉，用户在酒馆里只是暂时关掉的东西
            // 一导入就永久消失了。留着他随时能在编辑器里打开。
            val ext = e.optJSONObject("extensions")
            val disabled = e.optBoolean("disable", false) || (e.has("enabled") && !e.optBoolean("enabled", true))
            val content = pick(e, "content", "text", "entry", "value").trim()
            if (content.isBlank()) return@mapNotNull null
            // 主键 + 副键都收进触发词。酒馆的副键本是「二次筛选(AND)」，我们只有一个触发词字段、语义是 OR：
            // 按 OR 收进来会比原卡多触发几次，但不会漏——漏了才是用户直接看得见的「设定没生效」。
            val keys = (keyList(e, "keys", "key", "keywords") + keyList(e, "secondary_keys", "keysecondary"))
                .map { unwrapRegexKey(it) }.distinct()
            val (position, depth) = mapPosition(e, ext)
            val order = intOf(e.opt("insertion_order")) ?: intOf(e.opt("insertionOrder")) ?: intOf(e.opt("order")) ?: idx
            val constant = e.optBoolean("constant", false) || (ext?.optBoolean("constant", false) ?: false)
            idx++
            JSONObject()
                .put("name", pick(e, "comment", "name").trim().take(80))
                // 用换行分隔而不是逗号：触发词本身可能带逗号，用逗号拼会把一条切成两条
                .put("keywords", keys.joinToString("\n"))
                .put("content", content)
                .put("position", position)
                .put("depth", depth)
                .put("order", order)
                // 触发词为空的条目就是常驻——不然它一条都触发不了，等于导进来就是死的
                .put("constant", constant || keys.isEmpty())
                .put("enabled", !disabled)
        }.sortedBy { it.optInt("order", 0) }
        mapped.forEach { out.put(it) }
        return out
    }

    /** 条目 → 可读纯文本。只给那些「字段必须是一段文本」的老地方用（角色卡的 worldBook 列、整本镜像）。 */
    fun renderEntries(entries: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            if (!e.optBoolean("enabled", true)) continue
            val content = e.optString("content").trim()
            if (content.isBlank()) continue
            val head = e.optString("keywords").split('\n').filter { it.isNotBlank() }.joinToString("、")
                .ifBlank { e.optString("name").trim() }
            if (head.isNotBlank()) sb.append("【").append(head).append("】\n")
            sb.append(content).append("\n\n")
        }
        return sb.toString().trim()
    }

    /**
     * 世界书导入：归一化为 {name, description, content, entries}。支持本应用格式 与 lorebook/world-info。
     * entries 是逐条的结构化条目（每条各有触发词/注入位/深度/顺序/常驻/启用）；content 只是它的纯文本镜像，
     * 给还按整本取正文的老路径用。导入侧应优先读 entries。
     */
    fun normalizeWorldBook(raw: JSONObject): JSONObject {
        // 本应用自己的导出也带 entries（键名就是下面这套），所以同一条路径既能读酒馆的也能读我们自己的
        val entries = if (raw.has("entries") || raw.optJSONObject("character_book") != null)
            flattenLorebook(raw.optJSONObject("character_book") ?: raw) else JSONArray()
        val flat = renderEntries(entries).ifBlank { raw.optString("content", "") }
        return JSONObject().apply {
            put("name", pick(raw, "name", "title").ifBlank { "导入世界书" }.take(80))
            put("description", raw.optString("description", "").take(500))
            put("content", flat)
            put("entries", entries)
        }
    }

    // ==================== 对话导入（Cherry Studio / Chatbox / 通用） ====================
    //
    // 目标：把第三方 AI 客户端导出的对话/消息，统一转成本应用 ImportExport.importConversation
    // 认得的格式：{title, messages:[{role, content, reasoning?}]}。可返回「一个或多个」对话。
    //
    // 各家格式差异大且多数未正式文档化，这里做「尽力而为」的通用识别，认得的取、认不出的兜底、不丢消息：
    //  - Chatbox：整包导出为 {"chat-sessions":[{name, messages:[...]}], "settings":..., "configVersion":N}；
    //    单会话为 {name, messages:[...]}。消息文本：旧版用 content(字符串)，新版用
    //    contentParts:[{type:"text", text:"..."}]。role ∈ system/user/assistant/tool。
    //  - Cherry Studio：导出「话题(topic)」。因其内部把消息正文拆成 message_blocks，用户实际拿到的
    //    导出多为「已内联正文」的形态：OpenAI chat-completions {model, messages:[{role, content}]}，
    //    或 {name/title, messages:[...]}，或话题数组。content 可能是字符串，也可能是分块数组/对象。
    //  - 通用兜底：任何含 messages/conversations/history/chatList 且元素带 role + 文本的结构；
    //    或顶层直接就是一串消息数组 [{role, content}, ...]。
    //
    // 已知局限（假设）：若某导出把消息与正文彻底分表、消息里只留「块 id 引用」而正文在另一处按 id 存，
    // 本转换取不到正文（会跳过空消息）。实践中主流导出都把正文内联，故按内联处理。

    /** 解析第三方导出文本 → 本应用对话对象数组；每个 = {title, messages:[{role, content, reasoning?}]}。 */
    fun normalizeConversations(text: String): JSONArray {
        val out = JSONArray()
        val trimmed = text.trim()
        val root: Any = runCatching {
            if (trimmed.startsWith("[")) JSONArray(trimmed) else JSONObject(trimmed)
        }.getOrElse { return out }

        // 先找所有「会话/话题」候选，逐个转换
        val sessions = mutableListOf<JSONObject>()
        collectSessions(root, sessions)
        for (s in sessions) sessionToConversation(s)?.let { out.put(it) }

        // 兜底：没有会话外壳，顶层本身就是一串消息（数组或 {messages:[...]}）
        if (out.length() == 0) {
            val msgs = extractMessages(root)
            if (msgs.length() > 0) out.put(JSONObject().apply {
                put("title", "导入对话")
                put("messages", msgs)
            })
        }
        return out
    }

    /** 递归收集像「会话」的对象（带 messages/chatList/history）。 */
    private fun collectSessions(node: Any, out: MutableList<JSONObject>) {
        when (node) {
            is JSONArray -> for (i in 0 until node.length()) node.optJSONObject(i)?.let { collectSessions(it, out) }
            is JSONObject -> {
                if (looksLikeSession(node)) out.add(node)
                // 常见的「会话数组」外壳（Chatbox: chat-sessions；Cherry: topics 等）
                for (k in listOf("chat-sessions", "chatSessions", "sessions", "conversations", "topics", "chats", "threads")) {
                    val arr = node.optJSONArray(k) ?: continue
                    for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { if (looksLikeSession(it)) out.add(it) }
                }
            }
        }
    }

    private fun looksLikeSession(o: JSONObject): Boolean =
        o.has("messages") || o.has("chatList") || o.has("history") || o.has("msgs")

    private fun sessionToConversation(s: JSONObject): JSONObject? {
        val msgs = extractMessages(s)
        if (msgs.length() == 0) return null
        val title = pick(s, "name", "title", "topic", "subject", "label").take(120).ifBlank { "导入对话" }
        return JSONObject().apply { put("title", title); put("messages", msgs) }
    }

    /** 从会话对象（或直接是数组）取出并归一化消息列表。 */
    private fun extractMessages(container: Any): JSONArray {
        val arr: JSONArray? = when (container) {
            is JSONArray -> container
            is JSONObject -> container.optJSONArray("messages") ?: container.optJSONArray("chatList")
                ?: container.optJSONArray("history") ?: container.optJSONArray("msgs")
            else -> null
        }
        val out = JSONArray()
        if (arr == null) return out
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            normalizeMessage(m)?.let { out.put(it) }
        }
        return out
    }

    private fun normalizeMessage(m: JSONObject): JSONObject? {
        val role = normalizeRole(pick(m, "role", "sender", "author", "from"))
        val content = extractContent(m)
        val reasoning = pick(m, "reasoning", "reasoningContent", "reasoning_content", "thinking")
        if (content.isBlank() && reasoning.isBlank()) return null // 空消息（如纯图片/无正文块）跳过，不丢有内容的
        return JSONObject().apply {
            put("role", role)
            put("content", content)
            if (reasoning.isNotBlank()) put("reasoning", reasoning)
        }
    }

    private fun normalizeRole(r: String): String = when (r.trim().lowercase()) {
        "user", "human", "me", "you", "prompt" -> "user"
        "assistant", "ai", "bot", "model", "gpt", "response" -> "assistant"
        "system", "developer" -> "system"
        "tool", "function" -> "tool"
        "" -> "user"
        else -> "assistant" // 认不出的非空角色，多为模型侧输出
    }

    /** 取消息正文：兼容字符串 content、多模态/分块数组、以及 content 对象。 */
    private fun extractContent(m: JSONObject): String {
        when (val c = m.opt("content")) {
            is String -> if (c.isNotBlank()) return c.trim()
            is JSONArray -> textFromParts(c)?.let { return it }
            is JSONObject -> pick(c, "text", "content", "value").let { if (it.isNotBlank()) return it.trim() }
        }
        // Chatbox 新版 contentParts；某些导出用 parts
        m.optJSONArray("contentParts")?.let { textFromParts(it)?.let { t -> return t } }
        m.optJSONArray("parts")?.let { textFromParts(it)?.let { t -> return t } }
        // 分块正文（如 Cherry 的 blocks 内联为对象数组时）
        m.optJSONArray("blocks")?.let { b -> if (b.length() > 0 && b.opt(0) is JSONObject) textFromParts(b)?.let { t -> return t } }
        // 其它常见纯文本字段
        return pick(m, "text", "message", "value", "prompt", "response", "body").trim()
    }

    /** 从「部件/分块」数组拼出可读正文：取带 text/content 的文本部件，跳过图片/文件/工具等非正文类型。 */
    private fun textFromParts(parts: JSONArray): String? {
        val skip = setOf("image", "image_url", "img", "file", "attachment", "error", "tool", "tool_use", "tool_call", "tool_result", "citation", "translation")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            when (val p = parts.opt(i)) {
                is String -> if (p.isNotBlank()) { if (sb.isNotEmpty()) sb.append("\n"); sb.append(p) }
                is JSONObject -> {
                    val type = p.optString("type", "").lowercase()
                    if (type in skip) continue
                    val t = pick(p, "text", "content", "value")
                    if (t.isNotBlank()) { if (sb.isNotEmpty()) sb.append("\n"); sb.append(t) }
                }
            }
        }
        return if (sb.isNotEmpty()) sb.toString().trim() else null
    }

    /** 记忆归一化：支持本应用 与 Operit/通用数组。返回本应用记忆对象数组。 */
    fun normalizeMemories(raw: JSONObject): JSONArray {
        val out = JSONArray()
        val arr = raw.optJSONArray("memories") ?: raw.optJSONArray("data")
            ?: raw.optJSONArray("items") ?: raw.optJSONArray("nodes")
        if (arr != null) for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val content = pick(m, "content", "text", "value", "description", "memory", "body")
            if (content.isBlank()) continue
            out.put(JSONObject().apply {
                put("title", pick(m, "title", "name", "label", "key").take(80))
                put("content", content.take(2000))
                put("source", pick(m, "source").ifBlank { "import" })
                put("importance", m.optDouble("importance", m.optDouble("weight", 0.5)))
                if (m.has("characterCardId")) put("characterCardId", m.optLong("characterCardId"))
            })
        }
        return out
    }
}
