package com.arix.app

import android.content.Context
import com.arix.data.db.AppDatabase
import com.arix.data.entity.MemoryEntity
import com.arix.data.entity.MemoryTagCrossRef
import com.arix.data.entity.MemoryTagEntity
import com.arix.tool.FuzzyMatch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** 记忆关联边的元数据。type: related/causes/part_of…；weight: 关联强度(检索/图谱按它排序、画粗细)。 */
data class MemEdge(val type: String = "related", val weight: Float = 1.0f)

/** relatedMeta 编解码：JSON {"<对端id>":{"t":type,"w":weight}}。缺项即 related/1.0（老数据向后兼容）。 */
object MemEdges {
    fun parse(json: String): MutableMap<Long, MemEdge> {
        val out = HashMap<Long, MemEdge>()
        if (json.isBlank()) return out
        try {
            val o = org.json.JSONObject(json)
            val it = o.keys()
            while (it.hasNext()) {
                val k = it.next(); val id = k.toLongOrNull() ?: continue
                val e = o.optJSONObject(k) ?: continue
                out[id] = MemEdge(e.optString("t", "related").ifBlank { "related" }, e.optDouble("w", 1.0).toFloat())
            }
        } catch (_: Exception) {}
        return out
    }
    fun serialize(map: Map<Long, MemEdge>): String {
        if (map.isEmpty()) return ""
        val o = org.json.JSONObject()
        map.forEach { (id, m) -> o.put(id.toString(), org.json.JSONObject().put("t", m.type).put("w", m.weight.toDouble())) }
        return o.toString()
    }
}

/** 检索打分的中间结果（命中 + 综合分）。分数只在排序/截断时有意义，别当绝对相似度用。 */
data class ScoredMemory(val memory: MemoryEntity, val score: Float)

class MemoryManager(context: Context) {

    private val appContext = context.applicationContext
    private val dao = AppDatabase.getInstance(context).memoryDao()

    companion object {
        // 模糊补齐的候选上限/目标条数：手表上不做全表模糊，按重要度取头部当候选就够
        private const val FUZZY_CAND_MAX = 200
        private const val FUZZY_FILL_TO = 20
        // 分离作用域：embed-on-add 后台跑，不把网络往返串进 add()/update()（否则自动抽取多条会串行卡几秒）。
        // ⚠ 但要限并发：一次自动抽取可能一口气写 N 条记忆，每条都 launch 一次 embedding（冲突消解还会再叫模型），
        // 不限的话就是 N 个请求同时飞——手表上既费电又容易撞限流。限到 2：既不串行拖长，也不并发风暴。
        private val embedScope = kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2)
        )
        // 熔断状态放 companion（进程级）：MemoryManager 每次调用点都新建实例，放实例上会失效
        @Volatile private var embedDownUntil = 0L

        // ---- 写入去重 ----
        /** 余弦 ≥ 此值视为「同一条事实的不同说法」，合并而非新增。0.92 是保守值：宁可漏合并，也不能把两件事糊成一条。 */
        private const val DEDUP_COSINE = 0.92f
        /**
         * 「本次新产生」的来源。这批路径有两个共同性质，所以共用一份名单：
         *  1. **可以做语义去重**——import/backup/salvage 是在还原既有事实，去重会把用户原本分开的两条悄悄并掉（数据丢失）；
         *  2. **写进来的内容是此刻被断言的**，[MemoryEntity.assertedAt] 盖当前时间戳。还原路径断言的是历史上的事，
         *     盖成今天等于凭空把一条陈年旧事变成最新消息，冲突消解会拿它去推翻真正新的那条。
         */
        private val FRESH_SOURCES = setOf("auto_extract", "ai_tool", "lesson", "hard_signal", "user_input")

        // ---- 冲突消解（新事实推翻旧事实） ----
        /**
         * 余弦落在 [CONFLICT_COSINE, DEDUP_COSINE) 这一段 = 「在说同一件事，但不是同一句话」——
         * 可能是改口（换了公司），也可能只是补充（又多了一个爱好），向量分不出这两者，才值得叫一次模型判。
         * 低于这个值当成两件不相干的事、根本不问：问得越宽误归档越多，而归档是会让记忆"消失"的操作。
         */
        private const val CONFLICT_COSINE = 0.80f
        /** 一次最多拿几条旧条目去问：手表上一次网络往返的体积要压住，同一件事的旧说法本来也不该有很多条。 */
        private const val CONFLICT_CAND_MAX = 3
        /** 归档边（存在 relatedMeta 里）：旧→新 = 我被它取代；新→旧 = 我取代了它。 */
        const val EDGE_SUPERSEDED_BY = "superseded_by"
        const val EDGE_SUPERSEDES = "supersedes"
        /**
         * 归档时的降权系数与降到底的下限。**归档不删数据**，只是让旧说法别再压着新事实。
         * 下限取 0.31 是为了别掉进 [MemorySalvage.autoCompress] 的「低权(<0.3) + 陈旧 = 临时条目」删除区——
         * 归档变成"两周后静默删除"就违背了这套机制的前提。
         */
        private const val SUPERSEDE_DEMOTE = 0.5f
        private const val SUPERSEDE_FLOOR = 0.31f

        // ---- 时效（assertedAt 参与打分） ----
        /**
         * **只有这两类会随时间掉分**：它们天生带时限——event 说的是已经发生过的某一次，todo 是还没做的某件事，
         * 放得越久越可能已经翻篇或已经做完。其余六类（preference/fact/relation/lesson/environment/convention）
         * 是「除非被推翻否则一直成立」的，换代靠冲突消解，不该因为放得久就掉分：三年前说的口味今天照样是口味，
         * 一律按时间衰减等于把长期记忆做成短期记忆。
         */
        private val PERISHABLE_TYPES = setOf("event", "todo")
        /** 断言至今 30 天，时效因子降到 0.5（1/(1+ageDays/30)）。 */
        private const val DECAY_HALFLIFE_DAYS = 30.0
        /** 衰减最多只吃掉三成分数：过期的事也可能正是这次要问的（"上回那个会到底是哪天"）。 */
        private const val DECAY_MAX_CUT = 0.3f
        /**
         * 同类事实同分时让**新断言的**排前面。量级远小于任何真实的相关度差 → 只裁决平局，
         * 不会变成"越旧越靠后"的全局衰减（那条是上面 PERISHABLE_TYPES 才有的待遇）。
         */
        private const val FRESH_TIEBREAK = 0.02f

        // ---- 检索打分 ----
        /** 角色卡精确匹配的硬加权：同卡的事实比通用记忆更该被想起。 */
        private const val CARD_BOOST = 1.25f
        /** 置顶加成远大于任何组合分上限(≈1.25)，保证置顶恒排前，但同为置顶的仍按相关度内部排序。 */
        private const val PIN_BONUS = 10f
        /** 没配向量模型时 LLM 挑出来的命中：没有可比的余弦，按「中等语义相关」计入。 */
        private const val LLM_PICK_VEC = 0.75f
        /** LLM 挑记忆的出参封顶：要的只是个 id 数组。给够思考型模型一点余量，但不让它写小作文。 */
        private const val LLM_PICK_MAX_TOKENS = 256
        /** 图遍历：两跳、每跳衰减 0.6、邻居总数上限 8——手表上下文寸土寸金，展开太多等于没展开。 */
        private const val HOP_MAX = 2
        private const val HOP_DECAY = 0.6f
        private const val NEIGHBOR_MAX = 8

        /**
         * 这条事实**是什么时候被断言的**。0 = 未知（老数据、还原路径），回退用建条时间，行为与从前一致。
         * 判定时效/冲突一律走这里，别直接读 assertedAt，否则老数据会被当成"1970 年断言的"永远垫底。
         */
        fun assertedAtOf(m: MemoryEntity): Long = if (m.assertedAt > 0L) m.assertedAt else m.createdAt

        /**
         * 这条记忆是否已被**更新的说法取代**（旧→新那条 superseded_by 边就是标记本身）。
         * 归档 ≠ 删除：它不再参与检索/注入，但记忆页照常列出来，可以一键取消归档。
         * 先用 contains 挡一道，绝大多数记忆的 relatedMeta 里根本没有这个词，省掉一次 JSON 解析。
         */
        fun isSuperseded(m: MemoryEntity): Boolean =
            m.relatedMeta.contains(EDGE_SUPERSEDED_BY) &&
                MemEdges.parse(m.relatedMeta).values.any { it.type == EDGE_SUPERSEDED_BY }
    }

    /** 取 embedding 模型配置 (baseUrl, apiKey, model)；没配「向量记忆」用途返回 null（=不走语义、回退关键词）。 */
    private suspend fun embeddingConfig(): Triple<String, String, String>? {
        val e = try { CloudApiConfigManager(appContext).getActiveByPurpose("embedding") } catch (_: Exception) { null } ?: return null
        return if (e.baseUrl.isBlank() || e.model.isBlank()) null else Triple(e.baseUrl, e.apiKey, e.model)
    }

    /** 尽力为某条记忆算并存向量（配了 embedding 模型才做，失败静默）。 */
    private suspend fun maybeEmbed(id: Long, text: String) {
        try {
            val cfg = embeddingConfig() ?: return
            val v = EmbeddingClient.embed(cfg.first, cfg.second, cfg.third, text) ?: return
            dao.setEmbedding(id, EmbeddingClient.serialize(v))
        } catch (_: Exception) {}
    }

    /**
     * 为缺向量 **或维度不匹配**（换过 embedding 模型）的记忆批量建/重建索引（分块）。
     * 先探当前模型维度，据此判定哪些要重算——修「换模型后旧向量永久失效」。返回建好条数；未配模型 -1，探测失败 -2。
     */
    suspend fun backfillEmbeddings(progress: ((done: Int, total: Int) -> Unit)? = null): Int {
        val cfg = embeddingConfig() ?: return -1
        val probe = EmbeddingClient.embed(cfg.first, cfg.second, cfg.third, "维度探测") ?: return -2
        val dim = probe.size
        val todo = dao.allList().filter { val e = EmbeddingClient.deserialize(it.embedding); e == null || e.size != dim }
        if (todo.isEmpty()) return 0
        var done = 0
        todo.chunked(16).forEach { chunk ->
            val vs = EmbeddingClient.embedBatch(cfg.first, cfg.second, cfg.third, chunk.map { "${it.title}：${it.content}" })
            if (vs.size == chunk.size) chunk.forEachIndexed { i, m ->
                dao.setEmbedding(m.id, EmbeddingClient.serialize(vs[i])); done++
            }
            progress?.invoke(done, todo.size)
        }
        return done
    }

    val allMemories: Flow<List<MemoryEntity>> = dao.getAll()
    val allTags: Flow<List<MemoryTagEntity>> = dao.getAllTags()

    /**
     * LIKE 精确命中在前，模糊命中(错字/词序/缺字)追加在后 —— 顺序即优先级，调用方取 first() 拿到的
     * 仍是精确结果，行为向后兼容。只有精确没搜够才扫候选，正常路径不加开销。
     */
    suspend fun search(keyword: String): List<MemoryEntity> {
        val exact = dao.search(keyword)
        if (keyword.isBlank() || exact.size >= FUZZY_FILL_TO) return exact
        val have = exact.mapTo(HashSet()) { it.id }
        val cands = dao.getTopByImportance(FUZZY_CAND_MAX).filter { it.id !in have }
        val fuzzy = FuzzyMatch.rankBy(keyword, cands, FUZZY_FILL_TO - exact.size) { listOf(it.title, it.content.take(200)) }
        return exact + fuzzy.map { it.item }
    }

    suspend fun searchTop(keyword: String, limit: Int = 10): List<MemoryEntity> {
        val exact = dao.searchTop(keyword, limit)
        if (keyword.isBlank() || exact.size >= limit) return exact
        val have = exact.mapTo(HashSet()) { it.id }
        val cands = dao.getTopByImportance(FUZZY_CAND_MAX).filter { it.id !in have }
        val fuzzy = FuzzyMatch.rankBy(keyword, cands, limit - exact.size) { listOf(it.title, it.content.take(200)) }
        return exact + fuzzy.map { it.item }
    }

    suspend fun getById(id: Long): MemoryEntity? = dao.getById(id)

    /**
     * 建一条新记忆。**这是个笨原语**：不去重、不做冲突消解——恢复/导入/回收站还原都走它，
     * 那些路径要的就是"原样放回去"，任何自动合并/归档在那里都是数据丢失。要去重/换代走 [upsertByTitle]。
     *
     * @param assertedAt 这条事实被断言的时间。不传 = 按 [source] 判：[FRESH_SOURCES] 里的是**此刻**说出来的，
     *   盖当前时间；还原/导入类来源断言时间不可知，留 0（读取方回退 createdAt）。调用方知道真实断言时间（如从
     *   聊天记录里抽的历史事实）就显式传进来。
     */
    suspend fun add(title: String, content: String, source: String = "user_input",
                     importance: Float = 0.5f, characterCardId: Long? = null,
                     tags: List<String> = emptyList(), type: String = "fact",
                     pinned: Boolean = false, relatedIds: String = "",
                     assertedAt: Long? = null): Long {
        val now = System.currentTimeMillis()
        val id = dao.insert(MemoryEntity(
            title = title, content = content, source = source,
            importance = importance, characterCardId = characterCardId,
            type = type, pinned = pinned, lastAccessedAt = now, relatedIds = relatedIds,
            assertedAt = assertedAt ?: if (source in FRESH_SOURCES) now else 0L,
            createdAt = now, updatedAt = now
        ))
        for (tag in tags) {
            val tagId = getOrCreateTag(tag)
            dao.insertCrossRef(MemoryTagCrossRef(memoryId = id, tagId = tagId))
        }
        embedScope.launch { maybeEmbed(id, "$title：$content") }
        return id
    }

    /**
     * 改一条已有记忆。默认**不动 assertedAt**——这条路径是「订正」：把错字改对、把话说通顺，
     * 说的还是同一个时候断言的那件事，刷新断言时间等于凭空让一条旧事实变新，冲突消解会拿它去推翻真正新的。
     *
     * @param reassert 只有当这次修改代表**事实本身变了**（"他换工作了"）时才传 true：断言时间跟着刷新。
     *   注意还要内容真的变了才算，只改重要度/标签不是重新断言。
     */
    suspend fun update(id: Long, title: String? = null, content: String? = null,
                        importance: Float? = null, characterCardId: Long? = null,
                        tags: List<String>? = null, reassert: Boolean = false) {
        val existing = dao.getById(id) ?: return
        val now = System.currentTimeMillis()
        val contentChanged = content != null && content != existing.content
        val updated = existing.copy(
            title = title ?: existing.title,
            content = content ?: existing.content,
            importance = importance ?: existing.importance,
            characterCardId = characterCardId ?: existing.characterCardId,
            assertedAt = if (reassert && contentChanged) now else existing.assertedAt,
            updatedAt = now
        )
        dao.update(updated)
        if (tags != null) {
            dao.deleteCrossRefsByMemory(id)
            for (tag in tags) {
                val tagId = getOrCreateTag(tag)
                dao.insertCrossRef(MemoryTagCrossRef(memoryId = id, tagId = tagId))
            }
        }
        // 内容变了就重算向量（后台，不阻塞 update）
        if (title != null || content != null) embedScope.launch { maybeEmbed(id, "${updated.title}：${updated.content}") }
    }

    suspend fun delete(id: Long) {
        dao.deleteCrossRefsByMemory(id)
        dao.deleteById(id)
    }

    suspend fun getTagsForMemory(memoryId: Long): List<MemoryTagEntity> =
        dao.getTagsForMemory(memoryId)

    suspend fun getTagNames(memoryId: Long): List<String> =
        dao.getTagsForMemory(memoryId).map { it.name }

    private suspend fun getOrCreateTag(name: String): Long {
        val existing = dao.getTagByName(name)
        return existing?.id ?: dao.insertTag(MemoryTagEntity(name = name))
    }

    suspend fun count(): Int = dao.count()

    suspend fun setPinned(id: Long, pinned: Boolean) {
        val m = dao.getById(id) ?: return; dao.update(m.copy(pinned = pinned, updatedAt = System.currentTimeMillis()))
    }
    suspend fun setType(id: Long, type: String) {
        val m = dao.getById(id) ?: return; dao.update(m.copy(type = type, updatedAt = System.currentTimeMillis()))
    }
    // 轻量图谱：把 relatedIds 合并进该记忆（去重）
    suspend fun linkRelated(id: Long, ids: List<Long>) {
        val m = dao.getById(id) ?: return
        val cur = m.relatedIds.split(",").mapNotNull { it.trim().toLongOrNull() }
        val merged = (cur + ids.filter { it != id }).distinct()
        dao.update(m.copy(relatedIds = merged.joinToString(",")))
    }
    private suspend fun addRel(from: Long, to: Long, type: String, weight: Float) {
        val m = dao.getById(from) ?: return
        val cur = m.relatedIds.split(",").mapNotNull { it.trim().toLongOrNull() }.toMutableList()
        val meta = MemEdges.parse(m.relatedMeta)
        if (to !in cur) cur.add(to)
        meta[to] = MemEdge(type, weight)   // 建边即写元数据；已存在则更新 type/weight（供编辑）
        dao.update(m.copy(relatedIds = cur.joinToString(","), relatedMeta = MemEdges.serialize(meta), updatedAt = System.currentTimeMillis()))
    }
    private suspend fun removeRel(from: Long, to: Long) {
        val m = dao.getById(from) ?: return
        val cur = m.relatedIds.split(",").mapNotNull { it.trim().toLongOrNull() }
        val meta = MemEdges.parse(m.relatedMeta); val hadMeta = meta.remove(to) != null
        if (to in cur || hadMeta) dao.update(m.copy(relatedIds = (cur - to).joinToString(","), relatedMeta = MemEdges.serialize(meta), updatedAt = System.currentTimeMillis()))
    }
    /** 图谱里建立/断开两条记忆的关联——对称写两侧，删除也删两侧，边稳定不受先点谁影响。type/weight 已存在则更新（=编辑边）。 */
    suspend fun linkPair(a: Long, b: Long, type: String = "related", weight: Float = 1.0f) { if (a == b) return; addRel(a, b, type, weight); addRel(b, a, type, weight) }
    suspend fun unlinkPair(a: Long, b: Long) { if (a == b) return; removeRel(a, b); removeRel(b, a) }
    /** a、b 当前是否已关联（任一侧记着即算）。 */
    suspend fun isLinked(a: Long, b: Long): Boolean {
        val m = dao.getById(a) ?: return false
        return b in m.relatedIds.split(",").mapNotNull { it.trim().toLongOrNull() }
    }
    /** 读某条边的 type/weight（缺则 related/1.0）。 */
    suspend fun edgeMeta(a: Long, b: Long): MemEdge {
        val m = dao.getById(a) ?: return MemEdge()
        return MemEdges.parse(m.relatedMeta)[b] ?: MemEdge()
    }

    /**
     * 某条记忆的全部关联边（对端实体 + 边的 type/weight），供记忆详情的「关联」面板列出来。
     *
     * 边是双向写的（见 [linkPair]），所以只读自己这一侧就够。**归档边一并返回**：
     * `superseded_by` 是「谁把我这条顶掉了」的唯一可见处，藏起来等于自动归档没法被看见、也没法被撤。
     * 引用不到实体的 id（对端已删）直接跳过，顺手让面板不显示孤儿。
     */
    suspend fun relatedOf(id: Long): List<Pair<MemoryEntity, MemEdge>> {
        val m = dao.getById(id) ?: return emptyList()
        val meta = MemEdges.parse(m.relatedMeta)
        return m.relatedIds.split(",").mapNotNull { it.trim().toLongOrNull() }.distinct()
            .mapNotNull { rid -> dao.getById(rid)?.let { it to (meta[rid] ?: MemEdge()) } }
    }

    /** 某个标签下的记忆 id（标签筛选用；只要 id，别把整行连 embedding 一起拿回来比对）。 */
    // 只查 id：原来走 getByTagId 会把整行连 embedding 那个大字符串一起拿回来，只为取一组 id
    suspend fun idsByTag(tagId: Long): List<Long> = dao.idsByTagId(tagId)

    /**
     * 某时刻之后新建的记忆（日记入图用：把当天的日记和当天新增的事实连起来）。
     *
     * ⚠ 现在是「按重要度取前 [scanLimit] 行再按 createdAt 过滤」——因为 DAO 里没有按 createdAt 的查询，
     * 而这条路径一天只跑一次（日记 Worker）。库大到几千条时会漏掉重要度垫底的那些；
     * 真要修得加一个 `SELECT id FROM memories WHERE createdAt >= :t` 的 DAO 方法（见交接说明）。
     */
    /**
     * 某时刻之后新建的记忆。
     *
     * 走 SQL 的 `createdAt >= ?` 直查，**不再是**「按重要度取前 1000 行再在内存里过滤」——
     * 那个写法在库涨到几千条后会把"当天新增但重要度垫底"的记忆整批切掉，
     * 而日记入图正是靠这条把当天的记忆连起来，漏了就等于没连。
     * `scanLimit` 保留只为兼容旧调用点，现在不再有意义。
     */
    suspend fun createdSince(since: Long, cardId: Long?, limit: Int = 40, @Suppress("UNUSED_PARAMETER") scanLimit: Int = 1000): List<MemoryEntity> =
        dao.createdSince(since, cardId, limit)

    suspend fun idByTitle(title: String): Long? = dao.getByTitle(title)?.id
    /** 按 (标题, 角色卡) 精确定位——日记入图要幂等重写「今天这一条」，全表按标题查会串卡。 */
    suspend fun findByTitleInCard(title: String, cardId: Long?): MemoryEntity? = dao.getByTitleInCard(title, cardId)
    // 综合分：置顶>普通；再按 重要度 × 时效(最近访问/更新越近越高) 排序
    private fun score(m: MemoryEntity, now: Long): Double {
        val pin = if (m.pinned) 1000.0 else 0.0
        val ageDays = ((now - maxOf(m.lastAccessedAt, m.updatedAt)).coerceAtLeast(0)) / 86400000.0
        val recency = 1.0 / (1.0 + ageDays / 30.0)   // 30 天半衰感
        return pin + m.importance * (0.5 + 0.5 * recency)
    }

    /** 按重要性取最近记忆，用于包详情等只读展示 */
    suspend fun recent(limit: Int = 50): List<MemoryEntity> = dao.getTopByImportance(limit)

    /** 全量快照（救援/整理用）。 */
    suspend fun all(limit: Int = 10000): List<MemoryEntity> = dao.getTopByImportance(limit)

    /** 某类型有多少条（后台整理用来判「值不值得跑这一轮」，别为了数个数走 [all]）。 */
    suspend fun countByType(type: String): Int = dao.countByType(type)

    /** 某类型里没置顶的那些（后台整理的候选集）。置顶的一律不进候选，这是硬约束。 */
    suspend fun unpinnedOfType(type: String): List<MemoryEntity> = dao.byTypeUnpinned(type)

    /** 某类型的全部（**含置顶**），按重要度降序。见 [com.arix.data.dao.MemoryDao.byType]。 */
    suspend fun ofType(type: String): List<MemoryEntity> = dao.byType(type)

    /** 只要 id 集合（清孤儿引用用）。 */
    suspend fun allIds(): List<Long> = dao.allIds()

    /** 某角色卡的全部记忆（救援/回填用）。 */
    suspend fun byCard(cardId: Long): List<MemoryEntity> = dao.getAllByCard(cardId)

    /** 直接改归属角色卡（多选归档用；cardId=null 移回通用）。 */
    suspend fun setCard(id: Long, cardId: Long?) {
        val m = dao.getById(id) ?: return
        dao.update(m.copy(characterCardId = cardId, updatedAt = System.currentTimeMillis()))
    }

    /** 设置/清空某条记忆的文件夹（多选归入用；folder 空串="" 移回未分组）。 */
    suspend fun setFolder(id: Long, folder: String) {
        val m = dao.getById(id) ?: return
        dao.update(m.copy(folder = folder.trim(), updatedAt = System.currentTimeMillis()))
    }

    /** 列出所有非空 folder 名（去重、按名排序），供筛选/归入下拉。 */
    suspend fun listFolders(): List<String> =
        dao.allList().map { it.folder.trim() }.filter { it.isNotBlank() }.distinct().sorted()

    /** 取某文件夹下的记忆（folder 空串="" 取未分组）。 */
    suspend fun byFolder(folder: String): List<MemoryEntity> =
        dao.allList().filter { it.folder.trim() == folder.trim() }

    /**
     * 写入去重两道闸：**同卡同标题** → 合并；标题没撞上再看 **语义** → 余弦 ≥0.92 也算同一条。
     *
     * 归属修复：以前按标题全表查（`getByTitle`），于是 A 卡的「喜欢的音乐」会被 B 卡的同名记忆覆盖，
     * 两张卡的事实互相串。现在只认同卡的那条；只找到别的卡的同名记忆时**新建**，不合并。
     */
    suspend fun upsertByTitle(title: String, content: String, source: String = "auto_extract",
                               importance: Float = 0.5f, characterCardId: Long? = null,
                               tags: List<String> = emptyList(), type: String = "fact"): Long {
        val now = System.currentTimeMillis()
        // 「此刻被断言」的只有新产生的那批来源；还原/导入是在放回历史，不刷断言时间（见 [FRESH_SOURCES]）
        val fresh = source in FRESH_SOURCES
        val existing = dao.getByTitleInCard(title, characterCardId)
        if (existing != null) {
            val newImportance = maxOf(existing.importance, importance)
            // 去重合并：同卡同标题则更新内容/重要度/类型，不新增（卡归属已精确匹配，不再需要 ?: 兜底）
            // assertedAt：**内容真的变了才刷新**。同一条硬信号/教训反复写进来，内容一模一样时刷新它没有意义
            // （那只是又存了一次，不是又断言了一次）；内容变了则是同一个标题下换了新说法，等于此刻重新断言。
            val reasserted = if (fresh && content != existing.content) now else existing.assertedAt
            dao.update(existing.copy(content = content, importance = newImportance, type = type,
                assertedAt = reasserted, updatedAt = now))
            if (tags.isNotEmpty()) { dao.deleteCrossRefsByMemory(existing.id); for (t in tags) dao.insertCrossRef(MemoryTagCrossRef(existing.id, getOrCreateTag(t))) }
            embedScope.launch { maybeEmbed(existing.id, "${existing.title}：$content") }
            return existing.id
        }
        // 标题没撞上：换个说法（「爱喝美式」vs「咖啡口味」）就会存成两条，靠语义再拦一道。
        //
        // ⚠ 只对**新产生**的记忆去重，恢复/导入/整理一律跳过：那些路径是在**还原既有事实**，
        // 两条本来就分开存着的相似记忆，不该在恢复备份时被悄悄并成一条——那是数据丢失，
        // 而且用户永远不会知道丢了什么。宁可恢复后多两条近似的，也不能少一条。
        // 一次向量比对同时拿两样东西：该合并的那条(dup) + 「同题但不是同一句话」的旧条目(rivals，冲突消解候选)。
        // 分成两次调用会把同一段文本 embed 两遍——每写一条记忆多一次网络往返，在手表上不划算。
        val scan = if (fresh) scanSimilar(title, content, characterCardId) else DupScan(null, emptyList())
        val dup = scan.dup
        if (dup != null) {
            // 标题保留旧的：标题是人看的锚点，内容以新说法为准；重要度取大的，避免降级
            // assertedAt 刷新：语义命中意味着同一件事**又被说了一遍**，说明它此刻仍然成立——
            // 这正是「新断言压过旧断言」要的信号，也是这个字段唯一能自然长出来的地方。
            val reasserted = if (content != dup.content) now else dup.assertedAt
            dao.update(dup.copy(content = content, importance = maxOf(dup.importance, importance),
                assertedAt = reasserted, updatedAt = now))
            if (tags.isNotEmpty()) { dao.deleteCrossRefsByMemory(dup.id); for (t in tags) dao.insertCrossRef(MemoryTagCrossRef(dup.id, getOrCreateTag(t))) }
            embedScope.launch { maybeEmbed(dup.id, "${dup.title}：$content") }
            return dup.id
        }
        val id = add(title, content, source, importance, characterCardId, tags, type)
        // 真新建了一条才需要冲突消解：上面两条合并分支是**就地改写**同一行，旧说法已经不存在了。
        // 放后台跑：还要一次模型往返，而调用方（工具循环/自动抽取）都在等写入返回。
        if (scan.rivals.isNotEmpty()) embedScope.launch { resolveConflicts(id, title, content, scan.rivals) }
        return id
    }

    /**
     * 冲突消解：新写进来的一条，去**同一张角色卡**里找「在说同一件事、但说法对不上」的旧条目，让新的赢。
     *
     * 判定分两级，先便宜后花钱：向量先把「同题不同话」的候选圈出来（[CONFLICT_COSINE] ~ [DEDUP_COSINE]），
     * 圈不出来就到此为止；圈出来了才叫一次模型判「新的成立之后旧的还成不成立」。
     * **判不出来一律什么都不做**——没配模型、端点抽风、解析失败、模型说不确定，全都保持原样。
     *
     * 旧条目**不删**：打一条 superseded_by 边 + 降权（见 [markSuperseded]）。检索/注入不再取它，
     * 记忆页照常看得见，可以一键取消归档。自动逻辑敢动数据的前提就是它动过的每一下都看得见、都能撤。
     */
    private suspend fun resolveConflicts(newId: Long, title: String, content: String, rivals: List<MemoryEntity>) {
        try {
            val chat = CloudApiConfigManager(appContext).getConfigForPurpose("summary", capMaxTokens = 1024) ?: return   // 抽记忆：出参是短 JSON
            val superseded = llmSupersededIds(chat, title, content, rivals)
            rivals.filter { it.id in superseded }.forEach { markSuperseded(it, newId) }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce   // 取消不是失败：吞了它上层就停不掉（见记忆 stop-cancellation）
        } catch (_: Exception) {}
    }

    /**
     * 让模型判：接受新条目之后，哪几条旧条目已经不成立了。返回被推翻的 id 集合；整轮失败返回空集（=不动任何条目）。
     * 喂进去的只有库里的原文，不写角色设定、不规定语气——这是一次判断，不是一次对话。
     */
    private suspend fun llmSupersededIds(config: com.arix.cloudapi.CloudApiConfig, title: String,
                                         content: String, olds: List<MemoryEntity>): Set<Long> {
        return try {
            val arr = org.json.JSONArray()
            olds.forEach { arr.put(org.json.JSONObject().put("id", it.id).put("title", it.title).put("content", it.content.take(300))) }
            val prompt = buildString {
                append(PromptLang.pick("同一个人的长期记忆库里刚写入一条新条目，下面是它和几条已有条目。", "A new entry was just written into a person's long-term memory store. Below are it and a few existing entries."))
                append(PromptLang.pick("逐条判断：新条目为真之后，旧条目说的是不是已经不成立了——同一项属性换了值、状态被改写、原来的说法被否定，算不成立；", "Judge each one: once the new entry is true, does the old entry no longer hold — the same attribute changed value, the state was rewritten, or the earlier statement was contradicted; that counts as superseded;"))
                append(PromptLang.pick("补充细节、并列的另一件事、只是措辞不同，都不算。拿不准判 false。", "added detail, a separate matter, or mere rephrasing does not count. If unsure, set false."))
                append(PromptLang.pick("只输出 JSON 数组，每项 {id, superseded}，superseded 取 true/false。不要解释。\n", "Output only a JSON array, each item {id, superseded} with superseded being true/false. No explanation.\n"))
                append(PromptLang.pick("新条目：$title：${content.take(300)}\n已有条目：", "New entry: $title: ${content.take(300)}\nExisting entries: "))
                append(arr.toString())
            }
            var out = ""
            com.arix.cloudapi.CloudApiClient(config).streamChat(
                messages = listOf(com.arix.cloudapi.model.ChatMessage("user", prompt)),
                enableThinking = 0, onReasoningChunk = {}, onContentChunk = { out += it },
            )
            val cleaned = out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val res = org.json.JSONArray(cleaned)
            val ids = HashSet<Long>()
            for (i in 0 until res.length()) {
                val o = res.optJSONObject(i) ?: continue
                // 只认明确的 true：漏读、写别的词、给了没问过的 id，一律当"没被推翻"
                if (!o.optBoolean("superseded", false)) continue
                val id = o.optLong("id", -1L)
                if (id >= 0) ids.add(id)
            }
            ids
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (_: Exception) { emptySet() }
    }

    /** 归档一条被推翻的旧记忆：打双向边（旧→新 superseded_by / 新→旧 supersedes）+ 降权到 [SUPERSEDE_FLOOR] 以上。 */
    private suspend fun markSuperseded(old: MemoryEntity, newId: Long) {
        addRel(old.id, newId, EDGE_SUPERSEDED_BY, 1.0f)
        addRel(newId, old.id, EDGE_SUPERSEDES, 1.0f)
        val cur = dao.getById(old.id) ?: return   // addRel 已经改过这一行，重读拿最新的再改重要度
        val demoted = if (cur.importance <= SUPERSEDE_FLOOR) cur.importance
        else (cur.importance * SUPERSEDE_DEMOTE).coerceAtLeast(SUPERSEDE_FLOOR)
        if (demoted != cur.importance) dao.update(cur.copy(importance = demoted, updatedAt = System.currentTimeMillis()))
    }

    /**
     * 取消归档：撤掉「被取代」标记，这条重新参与检索。两侧的边一起撤，别留半条。
     * 重要度不还原——降权后用户可能自己又调过，猜一个数还回去比留着现状更糟（记忆页里能直接改）。
     */
    suspend fun unarchiveSuperseded(id: Long) {
        val m = dao.getById(id) ?: return
        val newer = MemEdges.parse(m.relatedMeta).filterValues { it.type == EDGE_SUPERSEDED_BY }.keys
        if (newer.isEmpty()) return
        newer.forEach { removeRel(id, it); removeRel(it, id) }
    }

    /** 一次向量比对的两种产物：[dup]=该就地合并的那条（≥DEDUP_COSINE）；[rivals]=同题不同话、要判有没有被推翻的旧条目。 */
    private data class DupScan(val dup: MemoryEntity?, val rivals: List<MemoryEntity>)

    /**
     * 给待写内容算一次向量，和**同卡**已有向量的记忆比余弦，按相似度分两段用：
     *  - ≥ [DEDUP_COSINE]：同一条事实的不同说法 → 合并，不新增；
     *  - [CONFLICT_COSINE, DEDUP_COSINE)：在说同一件事但不是同一句话 → 可能是改口，交给冲突消解判。
     *
     * 走的是同一条熔断状态（embedding 端点挂了就直接跳过），取不到向量/没配模型一律返回空走原逻辑——
     * 去重失败最多多存一条、漏一次换代，绝不能因此写不进去。调用方本就在后台协程里，这里直接 await 不会卡 UI。
     */
    private suspend fun scanSimilar(title: String, content: String, cardId: Long?): DupScan {
        val empty = DupScan(null, emptyList())
        return try {
            val cfg = embeddingConfig() ?: return empty
            if (System.currentTimeMillis() < embedDownUntil) return empty
            val qv = EmbeddingClient.embed(cfg.first, cfg.second, cfg.third, "$title：$content".take(1000)) ?: return empty
            val cands = dao.embeddedInCard(cardId)
            if (cands.isEmpty()) return empty
            // 反序列化 + 余弦是 CPU 活，挪到 Default 线程（cosine 对维度不一致自己返回 -1，换过模型的旧向量天然不参与）
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val scored = cands.mapNotNull { m -> EmbeddingClient.deserialize(m.embedding)?.let { m to EmbeddingClient.cosine(qv, it) } }
                val dup = scored.filter { it.second >= DEDUP_COSINE }.maxByOrNull { it.second }?.first
                if (dup != null) return@withContext DupScan(dup, emptyList())   // 要合并就没有"换代"这回事
                // 置顶的不参与换代：用户置顶就是「这条我要」，自动归档不该有意见。已归档的也不再重复判。
                val rivals = scored.filter { it.second >= CONFLICT_COSINE && !it.first.pinned && !isSuperseded(it.first) }
                    .sortedByDescending { it.second }.take(CONFLICT_CAND_MAX).map { it.first }
                DupScan(null, rivals)
            }
        } catch (_: Exception) { empty }
    }

    /**
     * 取与本轮消息相关的记忆。**返回类型与前三个参数是对外契约，别改**（Wake 上下文、MemoryTool、导出都在调）。
     *
     * 内部已从「三段拼接谁在前谁赢」换成打分合并，见 [rankRelevant]。输出条数仍是 maxResults + 3（邻居/置顶的余量）。
     *
     * @param allowLlmFallback 允不允许在**没配 embedding 模型**时用 [llmPickHits] 做语义兜底。
     *   **默认 false，因为它是一次完整的对话模型往返**（上千 prompt token，钱当场就付）。
     *   只有「用户/模型显式来搜记忆」这种能等、也值得等的路径才该开——发送主路径一律不开：
     *   那条路上它被 [MemoryInjection] 的 1500ms 超时包着，跑不完就丢弃，**等于每轮白花一次钱**。
     *   关掉它并不等于没有兜底：字面命中(FuzzyMatch)、图两跳展开、置顶注入这三条都不花钱，照常工作。
     */
    suspend fun queryRelevant(userMessage: String, maxResults: Int = 5,
                               characterCardId: Long? = null,
                               allowLlmFallback: Boolean = false): List<MemoryEntity> {
        if (userMessage.isBlank()) return emptyList()
        val now = System.currentTimeMillis()
        val out = rankRelevant(userMessage, maxResults, characterCardId, now, allowLlmFallback)
            .take(maxResults + 3).map { it.memory }
        // 一条 UPDATE 顶原来的 N 条：Room 的失效是**表级**的，每次 setLastAccessed 都会让记忆页
        // 那几个订阅者重跑一遍查询——8 条命中就是 8 次全表重扫。
        if (out.isNotEmpty()) dao.setLastAccessedIn(out.map { it.id }, now)   // 只更时间，不重写 embedding 大字段
        return out
    }

    // relevantPack() 已删（2026-07-28，Q18）：它和 [MemoryInjection.build] 是同一件事的两份实现——
    // 检索都走 [rankRelevant]（两跳邻居也在里面），只是排版规则各写一版。留着的那份是活的，
    // 而且预算规则更全（单条 160 字上限、置顶最多占 40%、1500ms 超时兜底），这份从来没有调用点。
    // 两套拼装并存迟早不一致，删掉冗余的那份。要注入记忆一律走 MemoryInjection。

    /**
     * 打分合并（替代原来的「语义 + LLM挑 + 关键词」三段拼接）。
     *
     * 综合分 = `max(kw, 0.3*kw + 0.7*vec)`：**要点是保护字面命中**——纯加权和会让「用户明明说了这个词」
     * 的精确命中被一堆语义泛相关淹掉；取 max 后，字面分单独就能托住排名，语义只能锦上添花。
     * 再叠三个乘/加权：同卡 ×1.25、置信度温和参与、置顶恒排前。
     */
    private suspend fun rankRelevant(userMessage: String, maxResults: Int, cardId: Long?, now: Long,
                                     allowLlmFallback: Boolean = false): List<ScoredMemory> {
        val pool = LinkedHashMap<Long, MemoryEntity>()   // 用 Linked 保序：HashMap 迭代序不定会让同分结果每次不一样
        val vec = HashMap<Long, Float>()
        val kw = HashMap<Long, Float>()

        // 候选放宽到 2 倍：最终是打分决胜，多几条参与竞争不会挤掉字面命中，反而少漏
        val wide = (maxResults * 2).coerceAtLeast(6)

        semanticScored(userMessage, wide, cardId).forEach { (m, s) ->
            pool[m.id] = m; vec[m.id] = s.coerceIn(0f, 1f)
        }
        // 没配 embedding 模型时的语义兜底：用对话模型从候选里挑（无向量可比，按中等语义分计入）。
        // ⚠ 默认不走：这是一次真的模型往返，而调用方多半是发送主路径（外面还包着 1500ms 超时），
        //   跑不完就被取消——**token 已经发出去、钱照付，结果却被丢弃**。见 [queryRelevant] 的参数说明。
        if (allowLlmFallback && vec.isEmpty() && embeddingConfig() == null) {
            llmPickHits(userMessage, maxResults, cardId).forEach { m ->
                pool[m.id] = m; vec[m.id] = LLM_PICK_VEC
            }
        }

        val kwOrdered = keywordHits(userMessage, wide, cardId, now)
        val q = userMessage.take(200)
        // FuzzyMatch 打分是 O(词长×内容长) 的 CPU 活，条数虽少也别在调用方线程上做
        val kwScores = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val n = kwOrdered.size.coerceAtLeast(1)
            kwOrdered.mapIndexed { i, m ->
                // 字面强度以 FuzzyMatch 绝对分为主（精确子串 ≥0.90、模糊 ≤0.85），名次只做同分微调。
                // lit==0 的是 keywordHits 在「消息里没有可用关键词」时按重要度兜底返回的那批——
                // 它们并不是字面命中，只给很低的底分，不能让它们冲到前面冒充命中。
                val lit = FuzzyMatch.bestScore(q, m.title, m.content.take(200))
                val rank = 1f - i.toFloat() / n
                val s = if (lit > 0f) lit * 0.85f + 0.15f * rank else 0.2f * rank
                m.id to s
            }
        }
        kwOrdered.forEach { pool[it.id] = it }
        kwScores.forEach { (id, s) -> kw[id] = maxOf(kw[id] ?: 0f, s) }

        // 基础分（未加权），邻居传播也从这里出发，避免把卡加权/置顶加成一路复利下去
        val base = LinkedHashMap<Long, Float>()
        for (id in pool.keys) {
            val m = pool[id] ?: continue
            // 已被新说法取代的：不进检索、也不当邻居展开的种子。用户手动置顶过的除外——
            // 置顶是比自动归档更强的显式意愿，不该被一条自动打上的边盖掉。
            if (isSuperseded(m) && !m.pinned) continue
            val k = kw[id] ?: 0f
            val v = vec[id] ?: 0f
            base[id] = maxOf(k, 0.3f * k + 0.7f * v)
        }

        // 图遍历：从最强的几条命中出发两跳展开，把「相关但字面/语义都没直接命中」的邻居捞进来
        val seeds = base.entries.sortedByDescending { it.value }.take(maxResults)
            .mapNotNull { e -> pool[e.key]?.let { it to e.value } }
        expandNeighbors(seeds, pool.keys.toSet(), cardId, NEIGHBOR_MAX).forEach { (m, s) ->
            pool[m.id] = m; base[m.id] = maxOf(base[m.id] ?: 0f, s)
        }

        // 置顶记忆无条件进池（哪怕本轮一条都没命中）——置顶就是用户说的"每次都要看见"。
        // 交给 SQL 挑：原来是把重要度前 200 行整行(含 embedding 大字段)读回来，在内存里挑出那几条置顶的。
        dao.pinnedForRetrieval(cardId).forEach { m ->
            pool[m.id] = m; if (m.id !in base) base[m.id] = 0f
        }

        return base.entries.mapNotNull { (id, b) ->
            val m = pool[id] ?: return@mapNotNull null
            var s = b
            if (cardId != null && m.characterCardId == cardId) s *= CARD_BOOST
            // 置信度温和参与：低置信(模型从对话里抽的)不至于被一票否决，只是排在同分的硬信号之后
            s *= 0.7f + 0.3f * m.confidence.coerceIn(0f, 1f)
            // 时效：**只压会过期的那两类**（见 PERISHABLE_TYPES），最多吃掉三成分数；
            // 其余类别一分不减——偏好和长期事实放三年也还是成立的，靠冲突消解换代，不靠放久了自己掉下去。
            val fresh = freshness(m, now)
            if (m.type in PERISHABLE_TYPES) s *= (1f - DECAY_MAX_CUT) + DECAY_MAX_CUT * fresh
            // 平局裁决：同类同分时新断言的排前面。量级 0.02，够翻转"几乎一样"的两条，不够动真实的相关度差。
            s += FRESH_TIEBREAK * fresh
            if (m.pinned) s += PIN_BONUS
            ScoredMemory(m, s)
        }.sortedByDescending { it.score }
    }

    /** 断言至今的新鲜度 1→0（[DECAY_HALFLIFE_DAYS] 天降到 0.5）。锚点必须是**断言时间**不是写库时间：
     *  「他去年换的工作」今天才录进来，新的是这条记录不是这件事，按写库时间算等于让陈年旧事冒充最新消息。 */
    private fun freshness(m: MemoryEntity, now: Long): Float {
        val ageDays = ((now - assertedAtOf(m)).coerceAtLeast(0L)) / 86_400_000.0
        return (1.0 / (1.0 + ageDays / DECAY_HALFLIFE_DAYS)).toFloat()
    }

    /**
     * 沿 relatedIds/relatedMeta 做 BFS 两跳展开（原来只展开一跳）。
     * 每跳乘 [HOP_DECAY] 再乘边权重：两跳外的东西天然排在直接命中之后，不会喧宾夺主。
     * 多条路径到同一邻居取最大分；exclude 里的（已在命中池）不重复展开；总数封顶 limit。
     */
    private suspend fun expandNeighbors(seeds: List<Pair<MemoryEntity, Float>>, exclude: Set<Long>,
                                        cardId: Long?, limit: Int): List<Pair<MemoryEntity, Float>> {
        if (seeds.isEmpty() || limit <= 0) return emptyList()
        val best = HashMap<Long, Float>()
        val cache = HashMap<Long, MemoryEntity>()
        val seen = HashSet<Long>(exclude)
        seeds.forEach { seen.add(it.first.id) }
        var frontier = seeds
        repeat(HOP_MAX) {
            if (frontier.isEmpty()) return@repeat
            val next = LinkedHashMap<Long, Pair<MemoryEntity, Float>>()
            for ((m, s) in frontier) {
                val meta = MemEdges.parse(m.relatedMeta)
                for (rid in m.relatedIds.split(",").mapNotNull { it.trim().toLongOrNull() }) {
                    if (rid in seen) continue
                    val w = (meta[rid]?.weight ?: 1.0f).coerceIn(0f, 1f)
                    val ns = s * HOP_DECAY * w
                    if (ns <= 0f) continue
                    val nb = cache[rid] ?: dao.getById(rid)?.also { cache[rid] = it } ?: continue
                    // 邻居也要守角色卡边界，别让 A 卡的记忆顺着边爬进 B 卡的上下文
                    if (!(cardId == null || nb.characterCardId == null || nb.characterCardId == cardId)) continue
                    // ⚠ 归档的旧说法必须在这里也挡一道：新条目和被它取代的旧条目之间**本来就有一条边**
                    // (supersedes)，不挡的话每次命中新的都会顺着这条边把旧的一起捞回上下文，等于白归档。
                    if (isSuperseded(nb) && !nb.pinned) continue
                    if (ns > (best[rid] ?: 0f)) { best[rid] = ns; next[rid] = nb to ns }
                }
            }
            next.keys.forEach { seen.add(it) }
            frontier = next.values.toList()
        }
        return best.entries.sortedByDescending { it.value }.take(limit)
            .mapNotNull { e -> cache[e.key]?.let { it to e.value } }
    }

    // 熔断：embedding 端点连续失败后一段时间内直接跳过语义（避免每轮都等超时）；状态见 companion.embedDownUntil
    // 返回带余弦分的命中：融合改打分后，调用方需要分数本身，不能只拿顺序
    private suspend fun semanticScored(userMessage: String, maxResults: Int, cardId: Long?): List<Pair<MemoryEntity, Float>> {
        val cfg = embeddingConfig() ?: return emptyList()
        if (System.currentTimeMillis() < embedDownUntil) return emptyList()
        val qv = EmbeddingClient.embed(cfg.first, cfg.second, cfg.third, userMessage.take(500))
        if (qv == null) { embedDownUntil = System.currentTimeMillis() + 60_000L; return emptyList() }
        embedDownUntil = 0L
        // 交给 SQL 过滤：原来 allList() 是**整表 SELECT ***（含 embedding 大字段、没有 LIMIT），
        // 读回来再在内存里 filter 掉别的卡、以及没建向量的那些行。条件与原来那句 filter 逐字等价。
        val cands = dao.embeddedForRetrieval(cardId)
        // 反序列化 + 余弦是 CPU 活，挪到 Default 线程，别卡调用方
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            cands.mapNotNull { m -> EmbeddingClient.deserialize(m.embedding)?.let { m to EmbeddingClient.cosine(qv, it) } }
                .filter { it.second > 0.25f }
                .sortedByDescending { it.second }
                .take(maxResults)
        }
    }

    /**
     * 无向量模型时的语义兜底：把候选记忆列给模型，让它挑相关的 id。隔离调用(无人设/历史)。
     *
     * **只在调用方显式允许时才会走到这里**（见 [queryRelevant] 的 allowLlmFallback）——它是一次真的模型往返。
     * 两处成本控制：① 走 `summary` 用途（用户配了便宜的小模型就用它，没配才回退到主对话模型）；
     * ② 出参只是个 id 数组，给 `max_tokens` 封顶，免得某些模型在这种"只要 JSON"的问题上长篇解释。
     */
    private suspend fun llmPickHits(userMessage: String, maxResults: Int, cardId: Long?): List<MemoryEntity> {
        return try {
            val cfg = CloudApiConfigManager(appContext).getConfigForPurpose("summary") ?: return emptyList()
            val cands = dao.topForRetrieval(cardId, 50)
            if (cands.size < 5) return emptyList()  // 记忆太少，关键词已够，不值当调 LLM
            val list = cands.joinToString("\n") { "[${it.id}] ${it.title}：${it.content.take(80)}" }
            val prompt = PromptLang.pick(
                "下面是一些记忆条目。挑出与「${userMessage.take(200)}」相关的，只输出相关条目 id 组成的 JSON 数组(如 [3,7])，没有则输出 []：\n$list",
                "Below are some memory entries. Pick the ones relevant to \"${userMessage.take(200)}\", and output only a JSON array of their ids (e.g. [3,7]); if none, output [].\n$list"
            )
            val client = com.arix.cloudapi.CloudApiClient(cfg.copy(maxTokens = cfg.maxTokens ?: LLM_PICK_MAX_TOKENS))
            var out = ""
            client.streamChat(listOf(com.arix.cloudapi.model.ChatMessage("user", prompt)), null, 0, null, onReasoningChunk = {}, onContentChunk = { out += it })
            val block = Regex("\\[[\\d,\\s]*]").find(out)?.value ?: return emptyList()
            val ids = Regex("\\d+").findAll(block).mapNotNull { it.value.toLongOrNull() }.toSet()
            cands.filter { it.id in ids }.take(maxResults)
        } catch (_: Exception) { emptyList() }
    }

    private suspend fun keywordHits(userMessage: String, maxResults: Int, cardId: Long?, now: Long): List<MemoryEntity> {
        val keywords = userMessage.take(200).split(Regex("[，,。.！!？?\\s]+")).filter { it.length >= 2 }
        if (keywords.isEmpty()) {
            return if (cardId != null) dao.getTopByCard(cardId, maxResults) else dao.getTopByImportance(maxResults)
        }
        val allResults = mutableListOf<MemoryEntity>()
        for (kw in keywords) {
            if (cardId != null) allResults.addAll(dao.searchTopByCard(kw, cardId, 3))
            else allResults.addAll(dao.searchTop(kw, 3))
        }
        val exact = allResults.distinctBy { it.id }.sortedByDescending { score(it, now) }
        if (exact.size >= maxResults) return exact.take(maxResults)
        // LIKE 是纯子串，用户打错一个字整条记忆就搜不出来。不够时用模糊补齐，排在精确之后
        val have = exact.mapTo(HashSet()) { it.id }
        val cands = dao.getTopByImportance(FUZZY_CAND_MAX).filter {
            it.id !in have && (cardId == null || it.characterCardId == null || it.characterCardId == cardId)
        }
        val fuzzy = FuzzyMatch.rankBy(userMessage.take(200), cands, maxResults - exact.size) {
            listOf(it.title, it.content.take(200))
        }
        return exact + fuzzy.map { it.item }
    }

    private suspend fun allMemoriesSync(): List<MemoryEntity> = dao.getTopByImportance(200)
}
