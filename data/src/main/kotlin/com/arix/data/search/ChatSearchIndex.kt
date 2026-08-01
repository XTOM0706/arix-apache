package com.arix.data.search

import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import com.arix.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.util.concurrent.ConcurrentHashMap

/**
 * 索引里的一条命中（单条消息级），已带上所属会话的元数据，调用方不必再回查会话表。
 * [raw] 是被索引时截断过的原文，用来抽片段；[ts] 是索引时该会话的 updatedAt
 * （消息本身不存时间戳，见 ConversationManager.saveMessages 落的 JSON 结构）。
 */
data class ChatIndexHit(
    val convId: Long,
    val msgIndex: Int,
    val role: String,
    val ts: Long,
    val raw: String,
    val title: String,
    val updatedAt: Long,
    val isArchived: Boolean
)

/**
 * 跨对话聊天记录的全文索引（SQLite FTS4 + 二元切分）。
 *
 * ## 为什么要有它
 * 「他记得我以前聊过什么」此前的实现是：把每个会话的整份 messagesJson 拉进内存 → 逐条
 * JSON 解析 → 逐条模糊打分。那是 O(全部历史消息) 的活，会话一多，一次搜索就是几百毫秒到几秒的
 * CPU + 几十 MB 的临时对象，手表上尤其难受。索引把它变成一次 MATCH。
 *
 * ## 为什么是 FTS4 而不是 FTS5
 * FTS5 在 Android 自带的 SQLite 里不是每个版本都编进去（老设备缺失时建表直接报错），
 * FTS3/FTS4 则是 Android 一直带的。minSdk 26 要覆盖的机器很杂，选 FTS4 稳。
 *
 * ## 为什么是 bigram（二元切分）而不是真正的中文分词
 * Android 内置 SQLite 没有 jieba / simple 这类中文分词器，要用就得自己打包一份 SQLite
 * （常见是 sqlite-android + 自定义 tokenizer），**包体要多 3~7MB**——这个项目对体积极其敏感
 * （见 PLAN-OPTIMIZATION：为了几 MB 在反复取舍）。而 FTS 自带的 simple tokenizer 把
 * 连续汉字整段当成**一个** token（>=0x80 的字符都算词内字符），「今天天气不错」就是一个词，
 * 搜「天气」永远搜不到——等于中文完全没索引。
 *
 * 所以取「不换依赖能拿到的最好结果」：写入时把连续汉字段切成相邻二字（今天天气 →「今天 天天 天气」），
 * 查询时把查询串用**同一套切法**切开、再拼成 FTS 短语（保持相邻）去 MATCH。
 * 代价是索引比原文大 ~3 倍、且单个汉字的查询天然搜不到（文档里只有二元组，没有一元组）——
 * 后者由 [ChatBigram.phrases] 判定并交回调用方走模糊全扫兜底，不是静默失败。
 *
 * ## 为什么表不是 Room 的 @Entity
 * 这两张表由本文件用裸 SQL 建、裸 SQL 读写（[CREATE_INDEX_SQL] / [CREATE_STATE_SQL]），
 * Room 不认识它们，也就**不会**拿它们去做 schema 校验。虚拟表的 CREATE 语句写法只要和 Room
 * 生成的差一个字（notindexed 的引号、列类型、tokenizer 选项…），Room 就会在打开数据库时
 * 直接抛 IllegalStateException = 启动即崩。索引是个"锦上添花"的能力，绝不值得为它冒
 * 「整个 App 打不开」的风险。代价是多写十几行 Cursor 解析。
 *
 * ## 线程与失败策略
 * 更新一律走内部 IO scope、去抖 1.5s（一轮对话里 persist 会被调好几次），失败只打日志。
 * **搜不到永远比发不出去好**：索引更新绝不能挂在发送链路上，也绝不能让发送失败。
 */
object ChatSearchIndex {

    const val TABLE = "chat_msg_index"
    const val STATE_TABLE = "chat_index_state"

    /**
     * FTS 虚拟表。tokens 是唯一被索引的列（bigram 切好的 token 流），其余全部 notindexed：
     * - raw 是给人看的原文，用 simple tokenizer 索引它只会白白再存一份没用的整段中文 token；
     * - convId/msgIndex/ts 是数字回指，被索引会让「搜 2024」这类查询命中一堆时间戳。
     */
    const val CREATE_INDEX_SQL =
        "CREATE VIRTUAL TABLE IF NOT EXISTS $TABLE USING fts4(" +
            "convId, msgIndex, role, ts, tokens, raw, " +
            "notindexed=convId, notindexed=msgIndex, notindexed=role, notindexed=ts, notindexed=raw)"

    /**
     * 索引状态表：记录每个会话「按哪份内容建过索引」。
     * - contentHash 让重复保存（同一份 JSON 被 persist 多次）直接跳过重建；
     * - 有没有这一行 = 这个会话补建过没有，是首次迁移的断点续跑依据。
     */
    const val CREATE_STATE_SQL =
        "CREATE TABLE IF NOT EXISTS $STATE_TABLE (" +
            "convId INTEGER NOT NULL PRIMARY KEY, contentHash TEXT NOT NULL, " +
            "msgCount INTEGER NOT NULL, indexedAt INTEGER NOT NULL)"

    private const val TAG = "ChatSearchIndex"

    /**
     * 单条消息最多索引多少字符。bigram 后 token 流约是原文的 3 倍，不设上限的话
     * 一篇被粘进来的长文档就能顶出几十 KB 索引。原全表扫实现按 4000 字截断打分，这里更保守取 2000：
     * 聊天消息绝大多数远短于此，被截掉的尾巴还有模糊全扫兜底。
     */
    private const val MAX_INDEX_CHARS = 2000

    /** 去抖：一轮对话里 ChatScreen.persist() 会连着调好几次，只对最后一次做事。 */
    private const val DEBOUNCE_MS = 1500L

    /** 补建索引时每条会话之间让一让，别和前台抢 CPU/IO（手表上很敏感）。 */
    private const val BACKFILL_GAP_MS = 30L

    /**
     * 每个会话在 FTS 里预留的 docid 段宽：`docid = convId * DOC_SPAN + 消息序号`。
     * 这样「重建某个会话的索引」可以按 docid 精确删（走 FTS 主索引），
     * 不用 `WHERE convId = ?` 扫全表（convId 是 notindexed 列，那必然是全表扫描）。
     * 代价：单个会话的消息条数按 10 万封顶（超出的不索引，仍有模糊全扫兜底）。
     */
    private const val DOC_SPAN = 100_000L

    @Volatile private var database: AppDatabase? = null
    @Volatile private var tablesReady = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<Long, Job>()
    @Volatile private var backfillJob: Job? = null

    /** 由 [AppDatabase.getInstance] 在建实例时调用。没 attach 过时所有入口静默 no-op。 */
    fun attach(db: AppDatabase) {
        database = db
        tablesReady = false
    }

    /** 由 [AppDatabase.closeInstance] 调用（全量恢复会覆盖 DB 文件，绝不能再拿旧句柄写索引）。 */
    fun detach() {
        pending.values.forEach { it.cancel() }
        pending.clear()
        backfillJob?.cancel()
        backfillJob = null
        database = null
        tablesReady = false
    }

    // ── 写入 ────────────────────────────────────────────────────────────────────────

    /**
     * 会话内容变了 → 排队重建它的索引。**同步部分只做取消+入队**，真正的解析/落库在后台，
     * 调用方（保存消息的热路径）不会因此多等一毫秒。
     */
    fun schedule(convId: Long, messagesJson: String, updatedAt: Long) {
        if (database == null || convId <= 0) return
        pending.remove(convId)?.cancel()
        // LAZY + 先入表再 start：否则协程可能在 put 之前就跑完，把一个已完成的 Job 留在表里。
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(DEBOUNCE_MS)
                indexConversation(convId, messagesJson, updatedAt)
            } catch (c: CancellationException) {
                throw c   // 取消必须重抛（项目「STOP 停不掉」的老教训）
            } catch (e: Exception) {
                Log.w(TAG, "更新会话 $convId 的搜索索引失败(不影响聊天): ${e.message}")
            }
        }
        pending[convId] = job
        job.invokeOnCompletion { if (pending[convId] === job) pending.remove(convId) }
        job.start()
    }

    /** 删对话时同步清索引，否则搜出来的还是已经删掉的内容（既是脏数据也是隐私问题）。 */
    fun dropConversations(ids: List<Long>) {
        if (database == null || ids.isEmpty()) return
        ids.forEach { pending.remove(it)?.cancel() }
        scope.launch {
            try {
                val sdb = writable() ?: return@launch
                sdb.beginTransaction()
                try {
                    for (id in ids) {
                        deleteRows(sdb, id, readState(sdb, id)?.second)
                        sdb.execSQL("DELETE FROM $STATE_TABLE WHERE convId = ?", arrayOf<Any>(id))
                    }
                    sdb.setTransactionSuccessful()
                } finally {
                    sdb.endTransaction()
                }
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "清理索引失败: ${e.message}")
            }
        }
    }

    /** 立刻重建某个会话的索引（同一份内容重复调用会被 contentHash 短路）。 */
    suspend fun indexConversation(convId: Long, messagesJson: String, updatedAt: Long) =
        withContext(Dispatchers.IO) {
            val sdb = writable() ?: return@withContext
            val hash = fingerprint(messagesJson)
            val prev = readState(sdb, convId)
            if (prev?.first == hash) return@withContext     // 同一份内容重复保存，什么都不用做
            val parsed = parse(messagesJson)
            ensureActive()
            val base = convId * DOC_SPAN
            sdb.beginTransaction()
            try {
                // 旧行数已知就按 docid 逐条精确删（每条 O(log n)）；未知才退回按 convId 全表扫一遍。
                deleteRows(sdb, convId, prev?.second?.let { maxOf(it, parsed.total) })
                val st = sdb.compileStatement(
                    "INSERT INTO $TABLE (docid, convId, msgIndex, role, ts, tokens, raw) VALUES (?, ?, ?, ?, ?, ?, ?)"
                )
                st.use { stmt ->
                    for (r in parsed.rows) {
                        stmt.clearBindings()
                        stmt.bindLong(1, base + r.index)
                        stmt.bindLong(2, convId)
                        stmt.bindLong(3, r.index.toLong())
                        stmt.bindString(4, r.role)
                        stmt.bindLong(5, updatedAt)
                        stmt.bindString(6, r.tokens)
                        stmt.bindString(7, r.raw)
                        stmt.executeInsert()
                    }
                }
                sdb.execSQL(
                    "INSERT OR REPLACE INTO $STATE_TABLE (convId, contentHash, msgCount, indexedAt) VALUES (?, ?, ?, ?)",
                    arrayOf<Any>(convId, hash, parsed.total, System.currentTimeMillis())
                )
                sdb.setTransactionSuccessful()
            } finally {
                sdb.endTransaction()
            }
        }

    // ── 首次迁移（老数据补建） ────────────────────────────────────────────────────────

    /**
     * 后台补建还没索引过的会话。单飞（已在跑就不重复起）、可中断、**逐条分块读**——
     * 绝不能 `SELECT *` 把 messagesJson 整表拉进游标：这个项目撞过 CursorWindow 2MB 上限，
     * 一条大对话就能让整个查询炸 SQLiteBlobTooBigException（曾致启动即崩）。
     */
    fun ensureBackfill() {
        val db = database ?: return
        if (backfillJob?.isActive == true) return
        backfillJob = scope.launch {
            try {
                backfill(db)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                Log.w(TAG, "补建聊天索引中断(下次搜索会再试): ${e.message}")
            }
        }
    }

    private suspend fun backfill(db: AppDatabase) {
        val sdb = writable() ?: return
        // 自愈：会话被别的路径删掉（历史遗留/恢复备份）时留下的孤儿行，在这里一并清掉。
        sdb.execSQL("DELETE FROM $TABLE WHERE convId NOT IN (SELECT id FROM conversations)")
        sdb.execSQL("DELETE FROM $STATE_TABLE WHERE convId NOT IN (SELECT id FROM conversations)")

        val done = indexedIds(sdb)
        val dao = db.conversationDao()
        // 只取 id + updatedAt 的投影，不碰两个大列。
        val targets = dao.getAllIdsForIndex().filter { it.id !in done }
        for (t in targets) {
            currentCoroutineContext().ensureActive()
            // 分块读单列：单条会话的 messagesJson 自身就可能 > 2MB。
            val json = dao.readMessagesJson(t.id)
            indexConversation(t.id, json, t.updatedAt)
            delay(BACKFILL_GAP_MS)
        }
    }

    /** 索引是否已覆盖全部会话。没覆盖全就不能拿索引结果当"全部结果"，得回退全扫。 */
    private fun coverageComplete(sdb: SupportSQLiteDatabase): Boolean {
        var missing = -1L
        sdb.query("SELECT COUNT(*) FROM conversations WHERE id NOT IN (SELECT convId FROM $STATE_TABLE)")
            .use { c -> if (c.moveToFirst()) missing = c.getLong(0) }
        return missing == 0L
    }

    private fun indexedIds(sdb: SupportSQLiteDatabase): Set<Long> {
        val out = HashSet<Long>()
        sdb.query("SELECT convId FROM $STATE_TABLE").use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    // ── 检索 ────────────────────────────────────────────────────────────────────────

    /**
     * 查索引。返回：
     * - `null` = **索引答不了这一问**（还没 attach / 还没补建完 / 查询切不出可用 token / 出错），
     *   调用方必须回退到原来的模糊全扫，否则就是把已有能力弄没了；
     * - 空表 = 索引齐全且确实没有命中；
     * - 非空 = 命中列表，按会话最近更新排序（相关度排序交给调用方，它有 FuzzyMatch）。
     */
    suspend fun search(query: String, includeArchived: Boolean, limit: Int): List<ChatIndexHit>? =
        withContext(Dispatchers.IO) {
            if (database == null || query.isBlank()) return@withContext null
            val phrases = ChatBigram.phrases(query)
            if (phrases.isEmpty()) return@withContext null   // 例如只输了一个汉字：bigram 索引里没有一元组
            try {
                val sdb = writable() ?: return@withContext null
                if (!coverageComplete(sdb)) {
                    ensureBackfill()          // 边补边用：这次先回退全扫，下次就走索引了
                    return@withContext null
                }
                // 先按「全部词都要有」查（精度优先）；一条不中再放宽成 OR（召回兜底）。
                val strict = runMatch(sdb, phrases.joinToString(" "), includeArchived, limit)
                if (strict.isNotEmpty() || phrases.size == 1) return@withContext strict
                runMatch(sdb, phrases.joinToString(" OR "), includeArchived, limit)
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                // 包括用户输入拼出非法 FTS 语法的情况：一律当"索引答不了"，交回退
                Log.w(TAG, "索引检索失败，回退全扫: ${e.message}")
                null
            }
        }

    private fun runMatch(
        sdb: SupportSQLiteDatabase,
        match: String,
        includeArchived: Boolean,
        limit: Int
    ): List<ChatIndexHit> {
        // 不给 FTS 表起别名：MATCH 左边必须是表名/列名，别名写法各版本行为不一致，直接用全名最稳。
        val sql = StringBuilder()
            .append("SELECT $TABLE.convId, $TABLE.msgIndex, $TABLE.role, $TABLE.ts, $TABLE.raw, ")
            .append("c.title, c.updatedAt, c.isArchived ")
            .append("FROM $TABLE JOIN conversations AS c ON c.id = $TABLE.convId ")
            .append("WHERE $TABLE MATCH ? ")
        if (!includeArchived) sql.append("AND c.isArchived = 0 ")
        sql.append("ORDER BY c.updatedAt DESC LIMIT ?")

        val out = ArrayList<ChatIndexHit>()
        sdb.query(sql.toString(), arrayOf<Any>(match, limit)).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ChatIndexHit(
                        convId = c.getLong(0),
                        msgIndex = c.getInt(1),
                        role = c.getString(2) ?: "",
                        ts = c.getLong(3),
                        raw = c.getString(4) ?: "",
                        title = c.getString(5) ?: "",
                        updatedAt = c.getLong(6),
                        isArchived = c.getInt(7) != 0
                    )
                )
            }
        }
        return out
    }

    // ── 内部 ────────────────────────────────────────────────────────────────────────

    private class Row(val index: Int, val role: String, val tokens: String, val raw: String)

    /** [total] 是 JSON 里的消息总条数（含被跳过的 tool/system），它决定这个会话占用的 docid 段有多宽。 */
    private class Parsed(val rows: List<Row>, val total: Int)

    private fun parse(messagesJson: String): Parsed {
        val out = ArrayList<Row>()
        val arr = try { JSONArray(messagesJson) } catch (_: Exception) { return Parsed(out, 0) }
        val total = minOf(arr.length(), DOC_SPAN.toInt())
        for (i in 0 until total) {
            val o = arr.optJSONObject(i) ?: continue
            val role = o.optString("role", "")
            // 工具回显/系统提示不算「聊天记录」，索引进去只会是噪音（与全扫实现口径一致）
            if (role == "tool" || role == "system") continue
            val content = o.optString("content", "").trim()
            if (content.isBlank()) continue
            val raw = if (content.length > MAX_INDEX_CHARS) content.substring(0, MAX_INDEX_CHARS) else content
            val tokens = ChatBigram.tokensOf(raw)
            if (tokens.isBlank()) continue
            out.add(Row(i, role, tokens, raw))
        }
        return Parsed(out, total)
    }

    /** 内容指纹：长度 + hashCode。目的只是「这份 JSON 我建过没有」，不需要抗碰撞的强哈希。 */
    private fun fingerprint(json: String): String = "${json.length}:${json.hashCode()}"

    /** 返回 (内容指纹, 上次索引时这个会话有多少条消息)；没有状态行返回 null。 */
    private fun readState(sdb: SupportSQLiteDatabase, convId: Long): Pair<String, Int>? {
        var out: Pair<String, Int>? = null
        sdb.query("SELECT contentHash, msgCount FROM $STATE_TABLE WHERE convId = ?", arrayOf<Any>(convId)).use { c ->
            if (c.moveToFirst()) out = Pair(c.getString(0) ?: "", c.getInt(1))
        }
        return out
    }

    /**
     * 删掉某会话在索引里的行。
     *
     * [span] = 这个会话曾占用的 docid 段宽（消息条数）。知道它就能按 `docid = ?` 逐条精确删，
     * 每条走 FTS 的 docid 主索引，O(log n)。不知道（第一次建 / 状态行丢了）才退回
     * `WHERE convId = ?`——convId 是 notindexed 列，那是一次**全表扫描**：索引里攒了几万条消息时，
     * 每保存一次消息就把整张 %_content 表读一遍，等于把我们刚从搜索侧干掉的 O(全部消息) 又搬到写入侧。
     */
    private fun deleteRows(sdb: SupportSQLiteDatabase, convId: Long, span: Int?) {
        if (span == null) {
            sdb.execSQL("DELETE FROM $TABLE WHERE convId = ?", arrayOf<Any>(convId))
            return
        }
        if (span <= 0) return
        val base = convId * DOC_SPAN
        sdb.compileStatement("DELETE FROM $TABLE WHERE docid = ?").use { st ->
            for (i in 0 until span) {
                st.clearBindings()
                st.bindLong(1, base + i)
                st.executeUpdateDelete()
            }
        }
    }

    /**
     * 拿可写句柄，并保证两张表存在。
     * Room 不认识这两张表 → 不会替我们建、也不会校验，所以每次开库后第一次用时自己 `IF NOT EXISTS` 兜一下：
     * 迁移漏跑、备份恢复了一份没有索引表的旧库、以后有人改了迁移链……任一情况都不会变成运行时"no such table"。
     */
    private fun writable(): SupportSQLiteDatabase? {
        val db = database ?: return null
        val sdb = db.openHelper.writableDatabase
        if (!tablesReady) {
            sdb.execSQL(CREATE_INDEX_SQL)
            sdb.execSQL(CREATE_STATE_SQL)
            tablesReady = true
        }
        return sdb
    }
}

/**
 * 中文二元切分（bigram）+ 西文按词切。**写入与查询必须用同一套切法**，否则索引里存的和查的对不上。
 *
 * 切法：
 * - 连续汉字/假名/谚文段：相邻两字一个 token，「今天天气」→「今天 天天 天气」；整段只有一个字时就存那一个字。
 * - 连续西文字母/数字：整词一个 token（西文本来就有空格，二元切分只会让索引白白膨胀、精度还更差）。
 * - 其余字符（标点/空白/emoji）：只当分隔符。
 * - 全角统一转半角、一律小写，让「ＡＢＣ」「abc」可比（与 FuzzyMatch.normalize 口径一致）。
 */
object ChatBigram {

    private fun isCjk(c: Char): Boolean =
        c.code in 0x3400..0x9FFF ||   // CJK 统一表意（含扩展 A）
            c.code in 0xF900..0xFAFF ||   // 兼容表意
            c.code in 0x3040..0x30FF ||   // 日文假名
            c.code in 0xAC00..0xD7AF      // 谚文音节

    private fun norm(ch: Char): Char = when {
        ch.code == 0x3000 -> ' '                                                  // 全角空格
        ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar().lowercaseChar()  // 全角 ASCII → 半角
        else -> ch.lowercaseChar()
    }

    /** 切成空格分隔的 token 流，直接存进 FTS 的 tokens 列（simple tokenizer 按空格切，正好还原我们的切法）。 */
    fun tokensOf(text: String): String {
        val sb = StringBuilder(text.length * 3)
        val word = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = norm(text[i])
            when {
                isCjk(c) -> {
                    if (word.isNotEmpty()) { sb.append(word).append(' '); word.clear() }
                    val seg = StringBuilder()
                    while (i < text.length) {
                        val cc = norm(text[i])
                        if (!isCjk(cc)) break
                        seg.append(cc)
                        i++
                    }
                    if (seg.length == 1) sb.append(seg).append(' ')
                    else for (k in 0 until seg.length - 1) sb.append(seg[k]).append(seg[k + 1]).append(' ')
                }
                c.isLetterOrDigit() -> { word.append(c); i++ }
                else -> { if (word.isNotEmpty()) { sb.append(word).append(' '); word.clear() }; i++ }
            }
        }
        if (word.isNotEmpty()) sb.append(word).append(' ')
        return sb.toString().trim()
    }

    private val SPLIT = Regex("[\\s,，、;；]+")

    /**
     * 把查询串拆成若干 FTS 短语（各自加引号）。段内保持相邻（短语=保序相邻，等价于"原文里连着出现"），
     * 段之间由调用方决定用 AND 还是 OR。
     *
     * 返回空表 = **这条查询索引答不了**，调用方要回退模糊全扫。目前只有一种情况：
     * 查询切完只剩单个汉字。文档里存的是二元组，「猫」在索引里根本不存在（存的是「小猫」「猫咪」），
     * 硬 MATCH 只会永远返回空 → 那是"搜不到"而不是"没有"，必须交给全扫去答。
     */
    fun phrases(query: String): List<String> {
        val out = ArrayList<String>()
        for (seg in query.trim().split(SPLIT)) {
            if (seg.isBlank()) continue
            val toks = tokensOf(seg).split(' ').filter { it.isNotBlank() }
            if (toks.isEmpty()) continue
            if (toks.size == 1 && toks[0].length == 1 && isCjk(toks[0][0])) continue
            // token 只可能由字母/数字/汉字组成（其余字符在切分时已被丢弃），不会有引号需要转义
            out.add("\"" + toks.joinToString(" ") + "\"")
        }
        return out
    }
}
