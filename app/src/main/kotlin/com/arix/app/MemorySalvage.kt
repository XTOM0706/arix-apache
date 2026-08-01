package com.arix.app

import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.model.ChatMessage
import com.arix.data.entity.MemoryEntity
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.min

/**
 * 「救援」已有对话与记忆——把存量数据接入新系统。见 DESIGN-MEMORY.md、TODO-V1 记忆簇。
 * 治两个由旧不成熟系统留下的病：
 *  A. 记忆爆满 → [compact]：确定性瘦身(同标题去重 + 按分数剪枝到上限，保留置顶)。安全、不调 LLM。
 *  B. 已有记忆但开新对话不像他 → [bootstrapStateCards]：按历史量算 relationship_level +
 *     LLM 隔离总结每角色卡的语气/相处模式 → 回填状态卡(慢层)，让人格延续对老关系追溯生效。
 */
object MemorySalvage {

    private const val TEMP_IMPORTANCE = 0.3f   // 低权阈值：低于此视为「临时」候选
    private const val STALE_DAYS = 14L         // 少调用：超两周没被访问/更新算陈旧

    data class CompactResult(val before: Int, val after: Int) { val removed get() = before - after }
    data class BootstrapResult(val seeded: Int, val skipped: Int)
    data class CardSalvageResult(val cardName: String, val memoriesExtracted: Int, val relationshipLevel: Double)

    /**
     * 按角色「联合救援」：把这张卡的记忆 + 对话一起用——从对话内容抽回记忆(upsert 到本卡) +
     * 回填状态卡。一次拿到「又有记忆、又有状态」。所有 LLM 调用走隔离最小上下文。
     */
    suspend fun salvageCard(
        context: android.content.Context,
        config: CloudApiConfig,
        cardId: Long,
        onProgress: ((String) -> Unit)? = null,
    ): CardSalvageResult {
        val app = context.applicationContext
        val card = CharacterCardManager(app).getById(cardId)
        val name = card?.name ?: "角色"
        val mm = MemoryManager(app)
        val convs = ConversationManager(app).repo.loadAllActiveFull().filter { it.characterCardId == cardId }

        // 1. 从对话内容抽回记忆（隔离调用），upsert 到本卡（按标题去重，不会重复堆）
        var extracted = 0
        val convDigest = buildConvDigest(convs)
        if (convDigest.isNotBlank()) {
            onProgress?.invoke("从「$name」的对话抽取记忆…")
            for (m in extractMemories(config, name, convDigest)) {
                mm.upsertByTitle(m.title, m.content, "salvage", m.importance, cardId, emptyList(), m.type)
                extracted++
            }
        }

        // 2. 回填状态卡（用抽取后更全的记忆 + 对话）
        onProgress?.invoke("回填「$name」状态卡…")
        val mems = mm.byCard(cardId)
        val level = min(1.0, 0.2 + mems.size * 0.04 + convs.size * 0.06)
        val (tone, mode) = summarize(config, name, buildDigest(mems, convs))
        InteractionState.seedSlow(app, cardId, level, tone, mode)

        return CardSalvageResult(name, extracted, level)
    }

    /**
     * AI 语义合并去重（真去重）：把某角色的非置顶记忆里重复/高度相似的合并成一条。见 TODO「AI 合并重要记忆」。
     * ⚠️破坏性：删非置顶原条、写入合并结果；但 LLM 失败/解析失败则**原样不动**(不删)，避免误删。
     * 置顶记忆不参与。输入超 [maxIn] 条只取重要度最高的那批(控制上下文)。
     */
    suspend fun mergeCard(
        context: android.content.Context,
        config: CloudApiConfig,
        cardId: Long,
        maxIn: Int = 80,
        onProgress: ((String) -> Unit)? = null,
    ): CompactResult {
        val app = context.applicationContext
        val mm = MemoryManager(app)
        val name = CharacterCardManager(app).getById(cardId)?.name ?: "角色"
        val before = mm.byCard(cardId).size
        val nonPinned = mm.byCard(cardId).filter { !it.pinned }
            .sortedByDescending { it.importance }.take(maxIn)
        if (nonPinned.size < 2) return CompactResult(before, before)

        onProgress?.invoke("AI 合并「$name」的 ${nonPinned.size} 条记忆…")
        val merged = llmMerge(config, name, nonPinned)
        if (merged.isEmpty()) return CompactResult(before, before) // 失败不删，安全

        nonPinned.forEach { mm.delete(it.id) }
        for (m in merged) mm.upsertByTitle(m.title, m.content, "merged", m.importance, cardId, emptyList(), m.type)
        return CompactResult(before, mm.byCard(cardId).size)
    }

    private suspend fun llmMerge(config: CloudApiConfig, cardName: String, mems: List<MemoryEntity>): List<MemItem> {
        return try {
            val arr = JSONArray()
            mems.forEach { arr.put(JSONObject().apply { put("title", it.title); put("content", it.content); put("type", it.type); put("importance", it.importance) }) }
            val prompt = buildString {
                append(PromptLang.pick(
                    "以下是「$cardName」的记忆条目(JSON数组)。把重复或高度相似的合并成一条(内容取并集、去冗余、标题择优)，彼此独立的保留；尽量压缩但别丢有价值的信息。只输出合并后的 JSON 数组，每项 ",
                    "Below are the memory entries for \"$cardName\" (as a JSON array). Merge duplicate or highly similar entries into one (union the content, remove redundancy, keep the best title); keep independent ones as-is; compress as much as possible without losing valuable information. Output only the merged JSON array, each item "
                ))
                // type 枚举必须和 MemoryTool 保持一致：少列一类，模型会把该类记忆改判成 fact，合并一次就丢了分类
                append(PromptLang.pick(
                    "{title, content, type(preference/fact/event/relation/todo/lesson/environment/convention), importance(0到1)}。不要解释。\n",
                    "{title, content, type(preference/fact/event/relation/todo/lesson/environment/convention), importance(0 to 1)}. Do not explain.\n"
                ))
                append(arr.toString())
            }
            var out = ""
            CloudApiClient(config).streamChat(
                messages = listOf(ChatMessage("user", prompt)),
                enableThinking = 0, onReasoningChunk = {}, onContentChunk = { out += it },
            )
            val cleaned = out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val res = JSONArray(cleaned)
            (0 until res.length()).mapNotNull { i ->
                val o = res.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("title").take(80); val c = o.optString("content").take(500)
                if (t.isBlank() || c.isBlank()) null
                else MemItem(t, c, o.optDouble("importance", 0.5).toFloat().coerceIn(0f, 1f), o.optString("type", "fact").ifBlank { "fact" })
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private data class MemItem(val title: String, val content: String, val importance: Float, val type: String)

    /** 该角色对话的近期正文摘要（限体积，防上下文污染/膨胀）。 */
    private fun buildConvDigest(convs: List<com.arix.data.entity.ConversationEntity>): String {
        val sb = StringBuilder()
        outer@ for (c in convs.sortedByDescending { it.updatedAt }.take(8)) {
            try {
                val arr = JSONArray(c.messagesJson)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val role = o.optString("role")
                    if (role != "user" && role != "assistant") continue
                    val content = o.optString("content").trim()
                    if (content.isBlank()) continue
                    sb.append(if (role == "user") "用户：" else "AI：").append(content.take(200)).append("\n")
                    if (sb.length > 2500) break@outer // 控制体积
                }
            } catch (_: Exception) {}
        }
        return sb.toString().trim()
    }

    /** 隔离一次性调用：从对话摘要抽回值得长期记住的记忆。 */
    private suspend fun extractMemories(config: CloudApiConfig, cardName: String, convDigest: String): List<MemItem> {
        return try {
            val prompt = buildString {
                // 抽取范围与 type 枚举都必须和 MemoryTool 一致：救援路径原来只列五类「关于人」的，
                // 于是历史对话里那些「踩过的坑/这台机器的情况/用户定下的规矩」要么被丢掉、要么被硬塞成 fact，
                // 救回来的记忆天生缺三类，跟正常路径写进去的对不上。
                append(PromptLang.pick(
                    "从下面「$cardName」与用户的历史对话中，抽取值得长期记住的信息：既包括关于用户的（偏好、个人事实、发生过的事、人物关系、待办），也包括关于怎么干活的（踩过的坑、这台设备的情况、用户定下的规矩）。",
                    "From the historical conversation between \"$cardName\" and the user below, extract information worth remembering long-term: both about the user (preferences, personal facts, things that happened, relationships, to-dos) and about how to get things done (lessons learned, details about this device, rules the user has set)."
                ))
                append(PromptLang.pick(
                    "只输出 JSON 数组，每项 {title(简短), content, type(preference/fact/event/relation/todo/lesson/environment/convention), importance(0到1)}；没有则输出 []。不要解释。\n对话：\n",
                    "Output only a JSON array, each item {title(brief), content, type(preference/fact/event/relation/todo/lesson/environment/convention), importance(0 to 1)}; output [] if none. Do not explain.\nConversation:\n"
                ))
                append(convDigest)
            }
            var out = ""
            CloudApiClient(config).streamChat(
                messages = listOf(ChatMessage("user", prompt)),
                enableThinking = 0, onReasoningChunk = {}, onContentChunk = { out += it },
            )
            val cleaned = out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val arr = JSONArray(cleaned)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val t = o.optString("title").take(80)
                val c = o.optString("content").take(500)
                if (t.isBlank() || c.isBlank()) null
                else MemItem(t, c, o.optDouble("importance", 0.5).toFloat().coerceIn(0f, 1f), o.optString("type", "fact").ifBlank { "fact" })
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * A. 记忆瘦身（确定性、安全）：同标题去重(保高分/置顶) + 非置顶按分数剪枝到 cap。
     *
     * ⚠ cap 跟着总量上限走，别再写死 200：总量上限已从 100 放到 1000（归档层），
     * 用户在记忆页点一下「整理」就把库剪到 200 的话，等于把刚放开的归档层又砍回去了。
     * 这是用户显式触发的瘦身，比自动压缩激进一档即可（留七成），不该是断崖。
     */
    suspend fun compact(context: android.content.Context, cap: Int = com.arix.tool.MEMORY_MAX_COUNT * 7 / 10): CompactResult {
        val mm = MemoryManager(context)
        val all = mm.all()
        val before = all.size
        val toDelete = HashSet<Long>()

        // 同标题去重：每组保留 置顶优先 + importance 最高，其余非置顶删
        all.groupBy { it.title.trim().lowercase() }.forEach { (_, grp) ->
            if (grp.size > 1) {
                val keeper = grp.maxWithOrNull(compareBy({ it.pinned }, { it.importance })) ?: return@forEach
                grp.filter { it.id != keeper.id && !it.pinned }.forEach { toDelete.add(it.id) }
            }
        }

        // 剪枝到 cap：幸存里非置顶、分数最低(importance→旧)的删到 <= cap
        val survivors = all.filter { it.id !in toDelete }
        if (survivors.size > cap) {
            val overflow = survivors.size - cap
            survivors.filter { !it.pinned }
                .sortedWith(compareBy({ it.importance }, { it.updatedAt }))
                .take(overflow)
                .forEach { toDelete.add(it.id) }
        }

        toDelete.forEach { mm.delete(it) }
        return CompactResult(before, mm.count())
    }

    /**
     * 自动智能压缩（确定性、安全、无 LLM）：记忆逼近上限时由 add 自动触发，替代旧的「满了就报错」死路。
     * 对齐 TODO 记忆簇「删临时(低权少调用) → 取权重均值重设权重」：
     *  ① 同标题去重：保留(置顶优先→重要度高)，存活者权重取该组均值(取权重均值重设权重)，其余非置顶删；
     *  ② 删临时：非置顶 + 低权(importance<[TEMP_IMPORTANCE]) + 陈旧(超 [STALE_DAYS] 没访问)，最差先删，压到 cap 往下一成留缓冲；
     *  ③ 兜底：仍超硬上限则删非置顶综合分最低者到 cap。
     * **置顶记忆永不删；高价值/新近记忆不会因去重被误降权**。AI 语义合并是更重的一步，仍走手动 [mergeCard]。
     * ⚠️[cap] 是**防失控的兜底总量**，不是成本预算——每轮真正花钱的是注入进提示词的常驻块，那个上限在注入侧算，
     * 不在这里；超出注入范围的旧记忆留在库里当归档层，靠检索取回，删它们没有收益只有损失。
     */
    suspend fun autoCompress(context: android.content.Context, cap: Int): CompactResult {
        val mm = MemoryManager(context)
        val all = mm.all()
        val before = all.size
        val now = System.currentTimeMillis()
        val toDelete = HashSet<Long>()
        fun remaining() = all.count { it.id !in toDelete }

        // ① 同标题去重（存活者取组内权重均值）
        all.groupBy { it.title.trim().lowercase() }.forEach { (_, grp) ->
            if (grp.size > 1) {
                val keeper = grp.maxWithOrNull(compareBy({ it.pinned }, { it.importance })) ?: return@forEach
                val mean = grp.map { it.importance }.average().toFloat().coerceIn(0f, 1f)
                if (kotlin.math.abs(mean - keeper.importance) > 0.001f) mm.update(keeper.id, importance = mean)
                grp.filter { it.id != keeper.id && !it.pinned }.forEach { toDelete.add(it.id) }
            }
        }

        // 压到「上限再往下一成」留缓冲：余量按 cap 取比例，不再写死 30——cap 已经是防失控的兜底量级(千级)，
        // 固定 30 的缓冲在那个量级上等于没有，压完立刻又逼近上限、每次 add 都触发一遍压缩。
        val softTarget = (cap - (cap / 10).coerceAtLeast(30)).coerceAtLeast(1)
        // ② 删临时（低权 + 陈旧 + 非置顶）
        if (remaining() > softTarget) {
            val staleBefore = now - STALE_DAYS * 86_400_000L
            all.filter { it.id !in toDelete && !it.pinned && it.importance < TEMP_IMPORTANCE && maxOf(it.lastAccessedAt, it.updatedAt) < staleBefore }
                .sortedWith(compareBy({ it.importance }, { maxOf(it.lastAccessedAt, it.updatedAt) }))
                .forEach { if (remaining() > softTarget) toDelete.add(it.id) }
        }
        // ③ 兜底剪枝到硬上限（绝不删置顶）
        if (remaining() > cap) {
            all.filter { it.id !in toDelete && !it.pinned }
                .sortedWith(compareBy({ it.importance }, { maxOf(it.lastAccessedAt, it.updatedAt) }))
                .forEach { if (remaining() > cap) toDelete.add(it.id) }
        }

        toDelete.forEach { mm.delete(it) }
        return CompactResult(before, mm.count())
    }

    /** B. 从历史回填状态卡（LLM 隔离最小上下文；非破坏）。 */
    suspend fun bootstrapStateCards(
        context: android.content.Context,
        config: CloudApiConfig,
        onProgress: ((String) -> Unit)? = null,
    ): BootstrapResult {
        val app = context.applicationContext
        val cards = CharacterCardManager(app).allCards.first()
        val convs = ConversationManager(app).repo.loadAllActiveFull()
        val mm = MemoryManager(app)

        var seeded = 0
        var skipped = 0
        for (card in cards) {
            val mems = mm.byCard(card.id)
            val cardConvs = convs.filter { it.characterCardId == card.id }
            if (mems.isEmpty() && cardConvs.isEmpty()) { skipped++; continue } // 无历史，跳过

            // 关系熟悉度按历史量估（代码算，非 LLM）
            val level = min(1.0, 0.2 + mems.size * 0.04 + cardConvs.size * 0.06)

            // 语气/相处模式：LLM 隔离总结（无人设/无工具/无历史，只喂摘要）
            val digest = buildDigest(mems, cardConvs)
            val (tone, mode) = summarize(config, card.name, digest)
            InteractionState.seedSlow(app, card.id, level, tone, mode)
            seeded++
            onProgress?.invoke("已回填「${card.name}」")
        }
        return BootstrapResult(seeded, skipped)
    }

    /** 该角色历史的紧凑摘要（控制上下文体积：取高分记忆 + 最近对话标题）。 */
    private fun buildDigest(mems: List<MemoryEntity>, convs: List<com.arix.data.entity.ConversationEntity>): String {
        val sb = StringBuilder()
        mems.sortedByDescending { it.importance }.take(20).forEach {
            sb.append("- ").append(it.title).append("：").append(it.content.take(80)).append("\n")
        }
        val titles = convs.sortedByDescending { it.updatedAt }.take(10).mapNotNull { it.title.takeIf { t -> t.isNotBlank() && t != "新对话" } }
        if (titles.isNotEmpty()) sb.append("最近聊过：").append(titles.joinToString("、")).append("\n")
        return sb.toString().ifBlank { "（无明显历史）" }
    }

    /** 隔离一次性调用 → (tone, interaction_mode)。失败返回空(仅回填 relationship_level)。 */
    private suspend fun summarize(config: CloudApiConfig, cardName: String, digest: String): Pair<String, String> {
        return try {
            val prompt = buildString {
                append(PromptLang.pick(
                    "根据下面「$cardName」与用户的历史片段，概括你们的相处状态。只输出 JSON：{\"tone\":\"当前语气，如 relaxed_technical/warm/playful/serious\",\"interaction_mode\":\"相处模式，一句话内\"}。不要解释。\n历史：\n",
                    "Based on the following history between \"$cardName\" and the user, summarize your relationship state. Output only JSON: {\"tone\":\"current tone, e.g. relaxed_technical/warm/playful/serious\",\"interaction_mode\":\"interaction mode, within one sentence\"}. Do not explain.\nHistory:\n"
                ))
                append(digest)
            }
            var out = ""
            CloudApiClient(config).streamChat(
                messages = listOf(ChatMessage("user", prompt)),
                enableThinking = 0, onReasoningChunk = {}, onContentChunk = { out += it },
            )
            val cleaned = out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val o = JSONObject(cleaned)
            o.optString("tone", "") to o.optString("interaction_mode", "")
        } catch (_: Exception) {
            "" to ""
        }
    }
}
