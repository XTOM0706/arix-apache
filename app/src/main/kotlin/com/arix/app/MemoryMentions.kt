package com.arix.app

import com.arix.data.entity.MemoryEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「提到了但没连」—— 补边来源之一，**完全离线、零 LLM、零网络**。
 *
 * 干的事只有一件：找出「A 的标题/正文里出现了 B 的标题，但两边的 relatedIds 里都没有对方」的配对，
 * 列给用户一键建边。为什么要它：[MemoryManager.expandNeighbors] 是整个检索里唯一能捞出
 * 「字面没命中、语义也没命中但确实相关」的机制，而它跑在一张空图上——图谱连线模式要求在还在跑力模拟、
 * 节点在动的画布上精准点中两个节点，手表上事实上做不到，于是 relatedIds 大面积为空。
 *
 * ⚠ **绝不自动建边**：只列出来问用户（项目既定规矩：拿不准的一律问，绝不悄悄改）。
 * ⚠ **不往 content 里写任何 [[ ]] 之类的语法**：那会同时污染 upsertByTitle 的标题匹配、embedding
 *    的输入文本、以及 0.92 去重余弦。这里只搬「提到即可建边」这个交互，不搬语法。
 *
 * ## 性能设计（不能每次打开详情就全表扫）
 *  1. **数据不额外读库**：扫的是记忆页本来就订阅着的那份 `allMemories` 列表（已在内存里）。
 *  2. **按需触发**：只有用户开着这个开关、并且真的打开了某条记忆的详情时才扫第一次；页面加载不扫。
 *  3. **一次全量、全局缓存**：反向那半边（「谁提到了我」）本来就要扫所有人的正文，
 *     所以按单条扫和全量扫是同一个代价——干脆一次算完所有配对，之后每次打开详情都是查表 O(1)。
 *  4. **索引化匹配**：按标题首字建倒排，扫一遍正文时每个位置只比对以该字开头的候选（近似 Aho-Corasick
 *     的单层根），不是 N×M 次 contains。正文只取前 [SCAN_TEXT_MAX] 字。
 *  5. **失效**：缓存签名 = 条数 + 最大 updatedAt + 标题下限。建边会改 updatedAt，所以连过之后
 *     下次打开详情自然重扫、连过的那对不再出现。
 *  6. **降噪**：标题短于下限的不参与（「工作」「今天」会命中一大片）；命中面超过 [NOISE_MAX] 条的
 *     标题整个丢掉（那不是「提到」，那是个常用词）；文档分块（source=document）两头都不参与——
 *     那是 RAG 的原文碎片，不是事实。
 */
object MemoryMentions {

    /** 每条记忆参与匹配的正文长度上限。标题另计。 */
    private const val SCAN_TEXT_MAX = 600
    /** 参与扫描的记忆条数上限（列表按 updatedAt 倒序，取最近的这些）。手表上不做无上限全表扫。 */
    private const val SUBJECT_MAX = 1200
    /** 一个标题命中超过这么多条记忆 → 它是个常用词不是专名，整个丢掉。 */
    private const val NOISE_MAX = 12
    /** 单条记忆最多列几条建议：列不完的下次再列，一屏塞不下二十几条。 */
    private const val PER_MEMORY_MAX = 20
    /** RAG 文档分块的 source（见 DocChunker.SOURCE）。两头都不参与匹配。 */
    private const val DOC_SOURCE = "document"

    /**
     * 一条待确认的建边建议。
     * @param incoming true = 「它提到了我」；false = 「我提到了它」。方向只用来说人话，建的边是无向的。
     */
    data class Mention(val id: Long, val title: String, val incoming: Boolean)

    private class Cache(val sig: String, val map: Map<Long, List<Mention>>)

    @Volatile private var cache: Cache? = null

    /** 缓存签名：条数 + 最大 updatedAt + 标题下限。任一变化即重扫。 */
    private fun signature(memories: List<MemoryEntity>, minLen: Int): String {
        var maxAt = 0L
        for (m in memories) if (m.updatedAt > maxAt) maxAt = m.updatedAt
        return "${memories.size}:$maxAt:$minLen"
    }

    /** 已经扫过、且结果仍然作数吗（不触发扫描，给「要不要显示徽标」这类零成本查询用）。 */
    fun isFresh(memories: List<MemoryEntity>, minLen: Int): Boolean =
        cache?.sig == signature(memories, minLen)

    /** 丢弃缓存（标题下限改了、或调用方明确知道图变了）。下次 [scan] 会重算。 */
    fun invalidate() { cache = null }

    /**
     * 取某条记忆的建议列表；缓存不作数就先扫一遍（CPU 活，挂在 [Dispatchers.Default] 上）。
     * 返回空表示「扫过了，没有可补的边」——和「还没扫」在 UI 上是两种文案，由调用方用 [isFresh] 区分。
     */
    suspend fun forMemory(memories: List<MemoryEntity>, minLen: Int, id: Long): List<Mention> =
        scan(memories, minLen)[id].orEmpty()

    /** 全量扫描 + 缓存。同签名直接返回缓存，不重算。 */
    suspend fun scan(memories: List<MemoryEntity>, minLen: Int): Map<Long, List<Mention>> {
        val sig = signature(memories, minLen)
        cache?.let { if (it.sig == sig) return it.map }
        val map = withContext(Dispatchers.Default) { compute(memories, minLen) }
        cache = Cache(sig, map)
        return map
    }

    // ---- 实现 ----

    private class Cand(val id: Long, val norm: String, val cardId: Long?)

    /** 从 [s] 的 [at] 位置起取两个字符拼成分桶键。调用方保证 at+1 在界内（标题长度 ≥ minLen ≥ 2）。 */
    private fun prefixKey(s: String, at: Int): Int = (s[at].code shl 16) or s[at + 1].code

    private fun compute(all: List<MemoryEntity>, minLen: Int): Map<Long, List<Mention>> {
        val subjects = all.take(SUBJECT_MAX).filter { it.source != DOC_SOURCE }
        if (subjects.size < 2) return emptyMap()

        // 标题倒排：**前两个字**（不是首字）→ 以它开头的候选标题。短标题/文档块不入表。
        // 用两个字做桶：中文标题里「我」「今」这种首字撞得厉害，只按首字分桶会退化成每个位置都
        // 比一大串候选；两个字一分，绝大多数桶只剩个位数。
        val byPrefix = HashMap<Int, ArrayList<Cand>>()
        for (m in subjects) {
            val t = m.title.trim()
            if (t.length < minLen) continue
            val norm = t.lowercase()
            byPrefix.getOrPut(prefixKey(norm, 0)) { ArrayList() }.add(Cand(m.id, norm, m.characterCardId))
        }
        if (byPrefix.isEmpty()) return emptyMap()

        // 已有的边（无向）：任一侧记着就算连过，不再建议。
        val linked = HashSet<Long>()
        // 无向对的键：小 id 放高 32 位、大 id 放低 32 位。行 id 是自增的，远不到 2^32，不会撞。
        fun key(a: Long, b: Long) = (minOf(a, b) shl 32) or (maxOf(a, b) and 0xFFFFFFFFL)
        for (m in subjects) {
            for (r in m.relatedIds.split(",")) {
                val rid = r.trim().toLongOrNull() ?: continue
                linked.add(key(m.id, rid))
            }
        }

        // 一遍扫：subject 的「标题 + 正文前 N 字」里出现了谁的标题
        val forward = HashMap<Long, LinkedHashSet<Long>>()      // 我提到了谁
        val hitCount = HashMap<Long, Int>()                     // 每个标题的命中面（降噪用）
        for (s in subjects) {
            val text = (s.title + "\n" + s.content.take(SCAN_TEXT_MAX)).lowercase()
            val n = text.length
            var i = 0
            while (i < n - 1) {
                val cands = byPrefix[prefixKey(text, i)]
                if (cands != null) {
                    for (c in cands) {
                        if (c.id == s.id) continue
                        // 角色卡边界：expandNeighbors 展开时就守这条线，建议阶段先守住，
                        // 别建出一条只会在检索时被丢弃的边。
                        if (!(c.cardId == null || s.characterCardId == null || c.cardId == s.characterCardId)) continue
                        val len = c.norm.length
                        if (i + len > n) continue
                        if (!text.regionMatches(i, c.norm, 0, len)) continue
                        if (forward.getOrPut(s.id) { LinkedHashSet() }.add(c.id)) {
                            hitCount[c.id] = (hitCount[c.id] ?: 0) + 1
                        }
                    }
                }
                i++
            }
        }
        if (forward.isEmpty()) return emptyMap()

        // 降噪：命中面过大的标题不是「被提到」，是个常用词
        val noisy = hitCount.filterValues { it > NOISE_MAX }.keys
        val titleOf = HashMap<Long, String>(subjects.size * 2)
        subjects.forEach { titleOf[it.id] = it.title }

        val out = HashMap<Long, ArrayList<Mention>>()
        for ((from, tos) in forward) {
            for (to in tos) {
                if (to in noisy) continue
                if (key(from, to) in linked) continue
                val fromTitle = titleOf[from] ?: continue
                val toTitle = titleOf[to] ?: continue
                // 正向：from 的详情里显示「我提到了 to」
                out.getOrPut(from) { ArrayList() }.let { if (it.none { x -> x.id == to }) it.add(Mention(to, toTitle, incoming = false)) }
                // 反向：to 的详情里显示「from 提到了我」
                out.getOrPut(to) { ArrayList() }.let { if (it.none { x -> x.id == from }) it.add(Mention(from, fromTitle, incoming = true)) }
            }
        }
        return out.mapValues { (_, v) -> v.take(PER_MEMORY_MAX) }
    }
}
