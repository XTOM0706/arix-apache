package com.arix.tool

import android.content.Context
import com.arix.app.ConversationManager
import com.arix.data.dao.ConversationDao
import com.arix.data.entity.ConversationEntity
import com.arix.data.search.ChatSearchIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
// 管理对话 —— 一个工具多用(list/read/rename/delete/archive/pin)。删/归档等敏感操作靠权限等级触发确认。
// ============================================================
class ManageChatsTool(private val context: Context) : Tool {
    override val name = "manage_chats"
    override val description = "管理对话历史：action=search 跨所有对话搜聊天记录（找「以前聊过什么」用这个，返回对话 id/标题/时间/命中片段，再用 read 看全文）；list 列最近对话；read 读某对话内容；rename 改名；delete 删除；archive 归档；pin 置顶。用 id 或 match(标题)定位对话。"
    override val permissionLevel = AndroidPermissionLevel.ACCESSIBILITY
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("search", "list", "read", "rename", "delete", "archive", "pin"))); put("description", "操作") })
            put("id", JSONObject().apply { put("type", "integer"); put("description", "对话 id（精确定位）") })
            put("match", JSONObject().apply { put("type", "string"); put("description", "按标题定位对话（id 未给时用，支持模糊）") })
            put("query", JSONObject().apply { put("type", "string"); put("description", "search 时要搜的内容（支持错字/词序颠倒的模糊匹配）") })
            put("limit", JSONObject().apply { put("type", "integer"); put("description", "search 时最多返回几个对话，默认 6，上限 15") })
            put("include_archived", JSONObject().apply { put("type", "boolean"); put("description", "search 是否含已归档对话，默认 true") })
            put("title", JSONObject().apply { put("type", "string"); put("description", "rename 时的新标题") })
            put("value", JSONObject().apply { put("type", "boolean"); put("description", "archive/pin 时：true=开, false=关；默认 true") })
        })
        put("required", JSONArray(listOf("action")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val db = com.arix.data.db.AppDatabase.getInstance(context)
        val dao = db.conversationDao()
        val cm = com.arix.app.ConversationManager(context)
        // 标题匹配只需轻量投影（不拉 messagesJson，防大对话破 2MB 游标窗口）；命中后按 id 分列拼装完整实体。
        suspend fun resolve(): com.arix.data.entity.ConversationEntity? {
            val id = params.opt("id")?.toString()?.toLongOrNull()
            if (id != null) return dao.getByIdAssembled(id)
            val m = params.optString("match", "").trim()
            if (m.isBlank()) return null
            val list = dao.getActiveSummaries().first() + dao.getArchivedSummaries().first()
            // 精确子串优先；记错标题时才退到模糊，避免模糊结果抢走本该命中的那个
            val hit = list.firstOrNull { it.title.contains(m, true) }
                ?: FuzzyMatch.rank(m, list, 1) { it.title }.firstOrNull()?.item
            return hit?.let { dao.getByIdAssembled(it.id) }
        }
        try {
            when (params.optString("action", "list")) {
                "search" -> searchChats(dao, params)
                "list" -> {
                    val list = dao.getActiveSummaries().first().take(20)
                    if (list.isEmpty()) return@withContext ToolResult("没有对话")
                    // 条数按 id 单列取 messagesJson（不整表拉大列破窗）。map 是 inline，可在其 lambda 内调挂起函数；
                    // joinToString 的 transform 不可内联挂起，故先 map 出行、再拼接。
                    val lines = list.map { c ->
                        val n = runCatching { org.json.JSONArray(dao.readMessagesJson(c.id)).length() }.getOrDefault(0)
                        "#${c.id} ${c.title}  ($n 条)${if (c.isPinned) " 📌" else ""}"
                    }
                    ToolResult(lines.joinToString("\n"))
                }
                "read" -> {
                    val c = resolve() ?: return@withContext ToolResult("没找到对话", isError = true)
                    val msgs = cm.loadMessages(c.id).takeLast(30)
                    ToolResult("【${c.title}】\n" + msgs.joinToString("\n") { "${it.role}: ${it.content.take(300)}" })
                }
                "rename" -> {
                    val c = resolve() ?: return@withContext ToolResult("没找到对话", isError = true)
                    val t = params.optString("title", "").trim()
                    if (t.isBlank()) return@withContext ToolResult("请提供新标题 title", isError = true)
                    dao.setTitle(c.id, t.take(40)); ToolResult("已改名：${c.title} → $t")
                }
                "delete" -> {
                    val c = resolve() ?: return@withContext ToolResult("没找到对话", isError = true)
                    // 走 ConversationManager.delete 而不是 dao.delete：后者只删库行，被压缩掉的原文
                    // 还留在工作区 archive/ 里，模型转头就能把"已删除"的对话读回来
                    // delete 现在会返回「到底删没删」——会话被用户锁定时它不删。
                    // 必须看这个返回值：照旧无条件回「已删除」等于对模型撒谎，它会拿这句去回复用户，
                    // 而那条会话还在。回话里说清是锁定挡的，模型才知道下一步该请用户解锁而不是重试。
                    if (cm.delete(c.id)) ToolResult("已删除对话：${c.title}")
                    else ToolResult("没删：对话「${c.title}」被用户锁定了。要删得先请用户在会话列表里解锁。", isError = true)
                }
                "archive" -> {
                    val c = resolve() ?: return@withContext ToolResult("没找到对话", isError = true)
                    val v = params.optBoolean("value", true); dao.setArchived(c.id, v); ToolResult(if (v) "已归档：${c.title}" else "已取消归档：${c.title}")
                }
                "pin" -> {
                    val c = resolve() ?: return@withContext ToolResult("没找到对话", isError = true)
                    val v = params.optBoolean("value", true); dao.setPinned(c.id, v); ToolResult(if (v) "已置顶：${c.title}" else "已取消置顶：${c.title}")
                }
                else -> ToolResult("未知 action", isError = true)
            }
        // 取消必须重抛：Kotlin 的 CancellationException 继承自 Exception，被这里吞掉的话
        // 用户按了停止、跨对话搜索仍会把所有会话扫完（见项目「STOP 停不掉」的既有教训）
        } catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (e: Exception) { ToolResult("操作失败: ${e.message}", isError = true) }
    }

    companion object {
        /**
         * 「一条都没搜到」的返回前缀。抽成常量是因为 [LocalSearchTool] 要靠它把「没搜到」
         * 从正常结果里筛掉——两边各写一份字面量的话，哪天这句文案被改写或被 tr() 包了，
         * 那边的判断就会**静默失效**（"没搜到"会被当成有结果塞给模型），而且编译器一声不吭。
         * 常量共享至少让改动只发生在一处，改了两边一起变。
         */
        const val NO_CHAT_HITS = "所有对话里都没搜到"

        /** 一次从索引里取多少条消息命中。取够多再在内存里按相关度重排/聚合到会话，够用且封顶。 */
        private const val INDEX_FETCH = 300

        /**
         * 索引命中的最低分。FTS 命中就是命中，但 FuzzyMatch 的切词口径和 bigram 不完全一样
         * （比如跨标点的命中），偶尔会给 0 分——那会让已经查到的结果被自己丢掉。
         * 所以只拿 FuzzyMatch 做**排序**，命中与否以索引为准，用这个下限兜住。
         */
        private const val INDEX_HIT_FLOOR = 0.35f
    }

    private class ChatHit(val role: String, val snippet: String, val score: Float)
    private class ChatMatch(
        val convId: Long,
        val title: String,
        val updatedAt: Long,
        val isArchived: Boolean,
        val score: Float,
        val hits: List<ChatHit>,
        val titleHit: Boolean
    )

    /**
     * 跨对话搜聊天记录。
     *
     * 两条路：优先查 FTS 全文索引（[ChatSearchIndex]，单条消息级 + 中文 bigram），
     * 索引答不了或一条没中时，回退到原来的「全量拉取 + 逐条模糊打分」。
     * 回退必须保留：索引是精确/近似子串级的，[FuzzyMatch] 能容错字、漏字、词序颠倒，
     * 那是工具描述里对用户承诺过的能力，不能因为加了索引就悄悄没了。
     *
     * 结果严格控量（会话数上限 + 每会话片段上限 + 片段截断），把「还有多少」讲清楚让 AI 自己决定要不要 read。
     */
    private suspend fun searchChats(dao: com.arix.data.dao.ConversationDao, params: JSONObject): ToolResult {
        val q = params.optString("query", "").ifBlank { params.optString("match", "") }.trim()
        if (q.isBlank()) return ToolResult("请提供 query（要搜的内容）", isError = true)
        val limit = params.optInt("limit", 6).coerceIn(1, 15)
        val includeArchived = params.optBoolean("include_archived", true)

        // null = 索引还没建好/这条查询索引答不了（如只输了一个汉字）；空 = 索引齐全但确实没命中。
        // 两种都往下走全扫兜底。
        val indexed = com.arix.data.search.ChatSearchIndex.search(q, includeArchived, INDEX_FETCH)
        val matches =
            if (!indexed.isNullOrEmpty()) matchesFromIndex(dao, q, indexed, includeArchived)
            else matchesByFullScan(dao, q, includeArchived)

        if (matches.isEmpty()) return ToolResult(NO_CHAT_HITS + "「$q」")

        val sorted = matches.sortedWith(compareByDescending<ChatMatch> { it.score }.thenByDescending { it.updatedAt })
        val show = sorted.take(limit)
        val totalHits = sorted.sumOf { it.hits.size }
        val sb = StringBuilder("搜「$q」：${sorted.size} 个对话命中，共 $totalHits 条消息")
        if (sorted.size > limit) sb.append("（只列前 $limit 个）")
        sb.append("\n")
        for (m in show) {
            sb.append("\n#${m.convId} 【${m.title}】 ${relTime(m.updatedAt)}")
            if (m.isArchived) sb.append(" [已归档]")
            if (m.titleHit) sb.append(" [标题命中]")
            sb.append("  ${m.hits.size} 条\n")
            m.hits.take(3).forEach { h ->
                sb.append("  ${if (h.role == "user") "我" else "AI"}: ${h.snippet}\n")
            }
            if (m.hits.size > 3) sb.append("  …该对话还有 ${m.hits.size - 3} 条命中，用 action=read id=${m.convId} 看全文\n")
        }
        return ToolResult(sb.toString().trim())
    }

    /**
     * 索引路径：把消息级命中聚合回会话。
     * 索引里只有消息正文，**标题命中要另外补**——所以这里仍会拉一次会话列表，但走的是轻量投影
     * （只有元数据，不含 messagesJson），代价与"全量解析每条消息"完全不是一个量级。
     */
    private suspend fun matchesFromIndex(
        dao: com.arix.data.dao.ConversationDao,
        q: String,
        hits: List<com.arix.data.search.ChatIndexHit>,
        includeArchived: Boolean
    ): List<ChatMatch> {
        val byConv = LinkedHashMap<Long, ArrayList<ChatHit>>()
        val meta = HashMap<Long, com.arix.data.search.ChatIndexHit>()
        for (h in hits) {
            val s = maxOf(FuzzyMatch.score(q, h.raw), INDEX_HIT_FLOOR)
            byConv.getOrPut(h.convId) { ArrayList() }.add(ChatHit(h.role, snippetAround(h.raw, q), s))
            if (!meta.containsKey(h.convId)) meta[h.convId] = h
        }

        val summaries = dao.getActiveSummaries().first() +
            (if (includeArchived) dao.getArchivedSummaries().first() else emptyList())
        val byId = summaries.associateBy { it.id }
        val titleScores = HashMap<Long, Float>()
        for (s in summaries) {
            val ts = FuzzyMatch.score(q, s.title)
            if (ts > 0f) titleScores[s.id] = ts
        }

        val out = ArrayList<ChatMatch>()
        for (id in byConv.keys + titleScores.keys) {
            val hs = (byConv[id] ?: emptyList<ChatHit>()).sortedByDescending { it.score }
            val tScore = titleScores[id] ?: 0f
            val best = maxOf(tScore, hs.maxOfOrNull { it.score } ?: 0f)
            if (best <= 0f) continue
            // 元数据优先用轻量投影（最新）；投影里没有（会话刚被删但索引还没清完）就用索引里那份快照
            val s = byId[id]
            val m = meta[id]
            val title = s?.title ?: m?.title ?: continue
            val updatedAt = s?.updatedAt ?: m?.updatedAt ?: 0L
            val archived = s?.isArchived ?: m?.isArchived ?: false
            if (!includeArchived && archived) continue
            out.add(ChatMatch(id, title, updatedAt, archived, best, hs, tScore > 0f))
        }
        return out
    }

    /**
     * 回退路径：原来的全量扫描。逐条分列拼装完整实体（避免整表 SELECT * 因某条超大对话破 2MB 窗口），
     * 再逐条 JSON 解析 + 模糊打分。慢，但**能力最全**（容错字/漏字/词序），且不依赖索引状态。
     */
    private suspend fun matchesByFullScan(
        dao: com.arix.data.dao.ConversationDao,
        q: String,
        includeArchived: Boolean
    ): List<ChatMatch> {
        val convs = if (includeArchived) dao.getAllActiveFull() + dao.getAllArchivedFull()
        else dao.getAllActiveFull()

        val matches = ArrayList<ChatMatch>()
        for (c in convs) {
            // 这条路可能要扫几百个会话，用户按停止就得立刻停（项目「STOP 停不掉」的老教训）
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val hits = ArrayList<ChatHit>()
            runCatching {
                val arr = JSONArray(c.messagesJson)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val role = o.optString("role", "")
                    // 工具回显/系统提示不算「聊天记录」，命中只会是噪音
                    if (role == "tool" || role == "system") continue
                    // 偶有整篇文档被粘进消息，截断打分把最坏情况的扫描量兜住（手表上全量归一化太贵）
                    val content = o.optString("content", "").take(4000)
                    if (content.isBlank()) continue
                    val s = FuzzyMatch.score(q, content)
                    if (s > 0f) hits.add(ChatHit(role, snippetAround(content, q), s))
                }
            }
            val titleScore = FuzzyMatch.score(q, c.title)
            val best = maxOf(titleScore, hits.maxOfOrNull { it.score } ?: 0f)
            if (best > 0f) matches.add(
                ChatMatch(c.id, c.title, c.updatedAt, c.isArchived, best, hits.sortedByDescending { it.score }, titleScore > 0f)
            )
        }
        return matches
    }

    /** 片段取命中词周边而非开头，否则长消息里命中的那句根本露不出来。 */
    private fun snippetAround(content: String, query: String, radius: Int = 50): String {
        val flat = content.replace(Regex("\\s+"), " ")
        val lc = flat.lowercase()
        val idx = FuzzyMatch.tokens(query).map { lc.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + radius * 2).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
    }

    private fun relTime(ts: Long): String {
        val d = (System.currentTimeMillis() - ts) / 1000
        return when {
            d < 3600 -> "${(d / 60).coerceAtLeast(1)} 分钟前"
            d < 86400 -> "${d / 3600} 小时前"
            d < 86400 * 30 -> "${d / 86400} 天前"
            else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date(ts))
        }
    }
}
