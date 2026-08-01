package com.arix.app

import android.content.Context

// ============================================================
// 角色卡扮演增强（per-card，落 SharedPreferences，不动 DB）——两件事：
//  1) 对话示例(few-shot)：一段示范对话文本，纯追加进系统提示，教模型说话风格；
//  2) 显示替换规则(对齐酒馆 regex)：显示 AI 回复时按正则替换（**只改显示、不改存储**，
//     所以不破坏消息树/分支/导出；想区分大小写用行内 (?i)）。
// 键按 cardId，参照 WorldTreeStore / TtsTool.cardVoicePref 的 per-card prefs 做法。
// ============================================================
object CardRoleplayStore {
    private const val PREFS = "card_roleplay"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // —— 对话示例（自由文本，仿酒馆 example dialogue 框；用户可自然写「用户：…/角色：…」）——
    fun examplesText(c: Context, cardId: Long?): String = if (cardId == null) "" else p(c).getString("ex_$cardId", "") ?: ""
    fun setExamplesText(c: Context, cardId: Long, text: String) = p(c).edit().putString("ex_$cardId", text.trim()).apply()

    /** 注入用的对话示例块（纯追加进系统提示）。空则空串。 */
    fun exampleBlock(c: Context, cardId: Long?): String {
        val t = examplesText(c, cardId)
        return if (t.isBlank()) "" else "【对话示例】(仅示范说话风格，不是真实对话历史)\n${t.trim()}\n\n"
    }

    // —— 显示替换规则（每行 `find => replace`；find 是正则、replace 可含 $1 组引用、可为空=删除）——
    fun rulesText(c: Context, cardId: Long?): String = if (cardId == null) "" else p(c).getString("rx_$cardId", "") ?: ""
    fun setRulesText(c: Context, cardId: Long, text: String) = p(c).edit().putString("rx_$cardId", text.trim()).apply()

    private fun parseRules(text: String): List<Pair<Regex, String>> = text.lineSequence()
        .map { it.trim() }.filter { it.isNotBlank() }
        .mapNotNull { line ->
            val idx = line.indexOf("=>"); if (idx < 0) return@mapNotNull null
            val find = line.substring(0, idx).trim(); val repl = line.substring(idx + 2).trim()
            if (find.isEmpty()) null else try { Regex(find) to repl } catch (_: Exception) { null }
        }.take(30).toList()

    // 当前对话激活的显示规则（编译好），供渲染层零参数读取；换卡/进对话时刷新
    @Volatile private var activeRules: List<Pair<Regex, String>> = emptyList()

    fun activate(c: Context, cardId: Long?) { activeRules = parseRules(rulesText(c, cardId)) }

    /** 渲染层：对 AI 显示文本做正则替换（不改存储）。用户消息不动。 */
    fun applyDisplay(text: String, isUser: Boolean): String {
        if (isUser) return text
        val rs = activeRules; if (rs.isEmpty()) return text
        if (text.length > 20000) return text   // 超长文本跳过：防用户写的病态正则(灾难性回溯)卡 UI
        var out = text
        for ((rx, repl) in rs) try { out = rx.replace(out, repl) } catch (_: Exception) {}
        return out
    }

    // ============================================================
    // 补齐酒馆 v2 spec 三项，同样走「per-card SharedPreferences，不动 DB」的旁路：
    //  1) 多开场白 alternate_greetings：卡的默认开场白(openingStatement/first_mes)之外的候选，
    //     新开对话时给用户挑一条（或随机）；不挑就还是原来的默认开场白，行为不变。
    //  2) 越狱指令 post_history_instructions：插在对话历史**之后**的系统指令，权重比前置
    //     系统提示更高——ChatScreen 组装发送消息时要单独追加在历史末尾，不能并进 staticSys。
    //  3) 深度提示 depth_prompt：插到对话历史倒数第 N 条位置的一段文本，可指定说话角色。
    // 三项均为空即视为关闭，不需要额外的开关字段。
    // ============================================================

    // —— 多开场白 ——
    // 用 "\n---\n" 做条目分隔（而不是单个换行）：一条开场白本身可能是多段落文本，逐行分隔会把它切碎。
    private const val GREETING_SEP = "\n---\n"

    /** 读取本卡的候选开场白（不含默认开场白本身）。 */
    fun alternateGreetings(c: Context, cardId: Long?): List<String> {
        if (cardId == null) return emptyList()
        val raw = p(c).getString("ag_$cardId", "") ?: ""
        if (raw.isBlank()) return emptyList()
        return raw.split(GREETING_SEP).map { it.trim() }.filter { it.isNotBlank() }
    }
    fun setAlternateGreetings(c: Context, cardId: Long, list: List<String>) {
        val cleaned = list.map { it.trim() }.filter { it.isNotBlank() }
        p(c).edit().putString("ag_$cardId", cleaned.joinToString(GREETING_SEP)).apply()
    }
    /** 编辑器用：候选开场白拼成一段可编辑文本，条目间用 --- 分隔行。 */
    fun alternateGreetingsText(c: Context, cardId: Long?): String = alternateGreetings(c, cardId).joinToString(GREETING_SEP)
    fun setAlternateGreetingsText(c: Context, cardId: Long, text: String) =
        setAlternateGreetings(c, cardId, text.split(GREETING_SEP).map { it.trim() }.filter { it.isNotBlank() })

    /** 新开对话可选的开场白全集：卡的默认开场白排第一，后面接候选；空串不进列表、重复去掉。 */
    fun greetingOptions(c: Context, cardId: Long?, defaultGreeting: String): List<String> =
        (listOf(defaultGreeting.trim()) + alternateGreetings(c, cardId)).filter { it.isNotBlank() }.distinct()

    // —— 越狱指令 post_history_instructions ——
    fun postHistoryInstructions(c: Context, cardId: Long?): String = if (cardId == null) "" else p(c).getString("phi_$cardId", "") ?: ""
    fun setPostHistoryInstructions(c: Context, cardId: Long, text: String) = p(c).edit().putString("phi_$cardId", text.trim()).apply()

    // —— 深度提示 depth_prompt ——
    data class DepthPrompt(val depth: Int, val role: String, val text: String)

    /** 本卡的深度提示；text 为空视为未配置，返回 null（调用方直接判空即可，不用另查开关）。 */
    fun depthPrompt(c: Context, cardId: Long?): DepthPrompt? {
        if (cardId == null) return null
        val text = p(c).getString("dp_text_$cardId", "") ?: ""
        if (text.isBlank()) return null
        val depth = p(c).getInt("dp_depth_$cardId", 4)   // 4 是酒馆的常见默认深度
        val role = p(c).getString("dp_role_$cardId", "system")?.takeIf { it in ROLES } ?: "system"
        return DepthPrompt(depth.coerceAtLeast(0), role, text)
    }
    fun setDepthPrompt(c: Context, cardId: Long, depth: Int, role: String, text: String) {
        p(c).edit()
            .putInt("dp_depth_$cardId", depth.coerceAtLeast(0))
            .putString("dp_role_$cardId", role.takeIf { it in ROLES } ?: "system")
            .putString("dp_text_$cardId", text.trim())
            .apply()
    }
    // 编辑器用：depth 单独存取（0 也是合法值，不能用「空=未设」来判断，所以分开给个默认值访问器）
    fun depthPromptDepth(c: Context, cardId: Long?): Int = if (cardId == null) 4 else p(c).getInt("dp_depth_$cardId", 4)
    fun depthPromptRole(c: Context, cardId: Long?): String = (if (cardId == null) null else p(c).getString("dp_role_$cardId", "system"))?.takeIf { it in ROLES } ?: "system"
    fun depthPromptText(c: Context, cardId: Long?): String = if (cardId == null) "" else p(c).getString("dp_text_$cardId", "") ?: ""

    private val ROLES = setOf("system", "user", "assistant")
}
