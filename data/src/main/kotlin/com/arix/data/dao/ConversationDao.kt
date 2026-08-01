package com.arix.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.arix.data.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

// 单列分块读的每块字符数（见下方 readMessagesJson/readBranchesJson）。
private const val COL_CHUNK_CHARS = 256 * 1024

// 会话「轻量投影」：只含元数据，绝不含 messagesJson/branchesJson。
// 单条会话这两大列合计可轻松超过 Android CursorWindow 的 2MB 硬上限（一条长对话 messages 1MB + 分支树 1MB），
// 只要列表用 `SELECT *` 把它们全拉进游标窗口，一读就 SQLiteBlobTooBigException（Row too big）→ 启动即崩、循环重启。
// 列表/抽屉/分组等只需要元数据的地方一律走这个投影，任何大对话都不会撑爆窗口。
data class ConversationSummary(
    val id: Long,
    val characterCardId: Long?,
    val configId: Long?,
    val title: String,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
    val source: String,
    val folder: String,
    val isLocked: Boolean
)

// 补建搜索索引用的最小投影：只要 id + 更新时间。
// 同样绝不能用 `SELECT *`——补建要遍历**全部**会话，一条超大对话就能把整个查询窗口撑爆。
data class ConversationIndexRow(val id: Long, val updatedAt: Long)

@Dao
interface ConversationDao {

    // 轻量投影流：列表/抽屉走这里，永不拉大列。
    // ⚠ isLocked 只挡删除/批量清理，不改排序：锁定的会话不天然置顶，想置顶靠现有的 isPinned（可与锁定并用）。
    @Query("SELECT id, characterCardId, configId, title, isPinned, isArchived, createdAt, updatedAt, source, folder, isLocked FROM conversations WHERE isArchived = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun getActiveSummaries(): Flow<List<ConversationSummary>>

    @Query("SELECT id, characterCardId, configId, title, isPinned, isArchived, createdAt, updatedAt, source, folder, isLocked FROM conversations WHERE isArchived = 1 ORDER BY updatedAt DESC")
    fun getArchivedSummaries(): Flow<List<ConversationSummary>>

    @Query("SELECT id, characterCardId, configId, title, isPinned, isArchived, createdAt, updatedAt, source, folder, isLocked FROM conversations WHERE id = :id")
    suspend fun getSummaryById(id: Long): ConversationSummary?

    // 全文索引补建的遍历清单（新→旧，先补最近的对话，用户最可能搜的就是它们）。
    @Query("SELECT id, updatedAt FROM conversations ORDER BY updatedAt DESC")
    suspend fun getAllIdsForIndex(): List<ConversationIndexRow>

    // ── 单列「分块读」：单条会话的 messagesJson 或 branchesJson 自身就可能 > 2MB
    //    （超长纯文本对话，或消息里内联的大 base64 图片）。此时哪怕只 SELECT 这一列，整列一次进单个
    //    游标窗口仍会 SQLiteBlobTooBigException。故用 SQLite substr() 按块取、Kotlin 端拼接，
    //    任何大小的单列都稳在窗口内。每块字符数保守取 256K：即便全是 4 字节 UTF-8 字符（emoji），
    //    单块也 ≤1MB，远低于 2MB 窗口。length()/substr() 均在 SQLite 内部按「字符」计数，多字节安全。
    @Query("SELECT length(messagesJson) FROM conversations WHERE id = :id")
    suspend fun messagesJsonLength(id: Long): Int?

    @Query("SELECT substr(messagesJson, :start, :count) FROM conversations WHERE id = :id")
    suspend fun messagesJsonChunk(id: Long, start: Int, count: Int): String?

    @Query("SELECT length(branchesJson) FROM conversations WHERE id = :id")
    suspend fun branchesJsonLength(id: Long): Int?

    @Query("SELECT substr(branchesJson, :start, :count) FROM conversations WHERE id = :id")
    suspend fun branchesJsonChunk(id: Long, start: Int, count: Int): String?

    // 分块拼装整列：小列一块读完、超大列多块拼接。行不存在或列为 NULL 时 length() 返回 null。
    suspend fun readMessagesJson(id: Long): String {
        val len = messagesJsonLength(id) ?: return "[]"
        if (len <= 0) return "[]"
        val sb = StringBuilder(len)
        var start = 1  // SQLite substr 起点从 1 开始
        while (start <= len) {
            val part = messagesJsonChunk(id, start, COL_CHUNK_CHARS)
            if (part.isNullOrEmpty()) break
            sb.append(part)
            start += COL_CHUNK_CHARS
        }
        return if (sb.isEmpty()) "[]" else sb.toString()
    }

    // branchesJson 可为 NULL（线性对话）：length() 返回 null → 直接返回 null，读取方语义不变。
    suspend fun readBranchesJson(id: Long): String? {
        val len = branchesJsonLength(id) ?: return null
        if (len <= 0) return null
        val sb = StringBuilder(len)
        var start = 1
        while (start <= len) {
            val part = branchesJsonChunk(id, start, COL_CHUNK_CHARS)
            if (part.isNullOrEmpty()) break
            sb.append(part)
            start += COL_CHUNK_CHARS
        }
        return if (sb.isEmpty()) null else sb.toString()
    }

    // 只取角色卡 id。**别为了这一个字段走 getByIdAssembled**：那个会把 messagesJson 整列
    // （可达 MB 级，还要分块多次查询）连同分支树一起拼回来，而调用方只看一眼 characterCardId。
    // 这条在每轮助手回复收尾都会走到，是热路径。
    @Query("SELECT characterCardId FROM conversations WHERE id = :id")
    suspend fun cardIdOf(id: Long): Long?

    // 按 id 分列拼装完整实体：绕开「整行 SELECT * 进单个游标窗口」的 2MB 上限。
    // messagesJson / branchesJson 各自单独、且分块读取（readMessagesJson/readBranchesJson），
    // 即便某一列自身 > 2MB 也不会撑爆窗口——彻底消除单列上限，而非只赌单列 < 2MB。
    suspend fun getByIdAssembled(id: Long): ConversationEntity? {
        val s = getSummaryById(id) ?: return null
        return ConversationEntity(
            id = s.id,
            characterCardId = s.characterCardId,
            configId = s.configId,
            title = s.title,
            messagesJson = readMessagesJson(id),
            isPinned = s.isPinned,
            isArchived = s.isArchived,
            createdAt = s.createdAt,
            updatedAt = s.updatedAt,
            source = s.source,
            folder = s.folder,
            branchesJson = readBranchesJson(id),
            isLocked = s.isLocked
        )
    }

    // 全量加载完整实体（备份/统计/工具需要消息内容时用）：先取轻量投影列表，再逐条分列拼装。
    // 比 `SELECT *` 慢（每条多两次单列查询），但不会因某条超大对话把整个查询窗口撑爆。
    suspend fun getAllActiveFull(): List<ConversationEntity> =
        getActiveSummaries().first().mapNotNull { getByIdAssembled(it.id) }

    suspend fun getAllArchivedFull(): List<ConversationEntity> =
        getArchivedSummaries().first().mapNotNull { getByIdAssembled(it.id) }

    // 内容/标题搜索：LIKE 在 SQLite 内部求值，只把小列投影进窗口（不物化 messagesJson），故不会崩。
    // 命中后如需片段，另按 id 调 readMessagesJson 分块取，只对少数命中项付代价。
    @Query("SELECT id, characterCardId, configId, title, isPinned, isArchived, createdAt, updatedAt, source, folder, isLocked FROM conversations WHERE title LIKE '%' || :q || '%' OR messagesJson LIKE '%' || :q || '%' ORDER BY isArchived ASC, updatedAt DESC LIMIT :limit")
    suspend fun searchSummaries(q: String, limit: Int): List<ConversationSummary>

    // 注意：不要再加 `SELECT *` 的整表/整行会话查询。单条会话的 messagesJson / branchesJson 各自都可能超 2MB
    // CursorWindow 上限，一 SELECT * 就 SQLiteBlobTooBigException（曾致启动崩溃）。列表用投影(getActiveSummaries)、
    // 整实体用 getByIdAssembled、单列用 readMessagesJson/readBranchesJson（分块）。
    // 已删除历史遗留且无任何调用方的 getByCharacterCard(SELECT *)——它是同类破窗隐患。

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity): Long

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM conversations WHERE id IN (:ids)")
    suspend fun deleteMany(ids: List<Long>)

    @Query("UPDATE conversations SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE conversations SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    // 锁定态持久化列。delete/deleteMany 本身不查这一列（DAO 层只管物理删除），
    // 挡删除的判断收在 ConversationManager（应用层唯一删除入口），见该文件注释。
    @Query("UPDATE conversations SET isLocked = :locked WHERE id = :id")
    suspend fun setLocked(id: Long, locked: Boolean)

    @Query("UPDATE conversations SET title = :title WHERE id = :id")
    suspend fun setTitle(id: Long, title: String)

    @Query("UPDATE conversations SET characterCardId = :characterCardId WHERE id = :id")
    suspend fun setCharacterCard(id: Long, characterCardId: Long)

    @Query("UPDATE conversations SET folder = :folder WHERE id = :id")
    suspend fun setFolder(id: Long, folder: String)

    @Query("UPDATE conversations SET messagesJson = :json, updatedAt = :updatedAt WHERE id = :id")
    suspend fun saveMessages(id: Long, json: String, updatedAt: Long)

    @Query("UPDATE conversations SET configId = :configId WHERE id = :id")
    suspend fun setConfig(id: Long, configId: Long)

    /**
     * 解除本会话的模型绑定 → 此后一直跟随全局激活项。
     *
     * [setConfig] 收的是非空 Long，表达不了"不绑定"，于是「仅本对话 / 全局」那个选择器只能
     * 把两边写成同一个 id——看着一致，但**此后全局再换模型，这个会话不会跟**，
     * 而用户选的明明是"全局"。差别只有在他下次换模型时才暴露，最难查。
     */
    @Query("UPDATE conversations SET configId = NULL WHERE id = :id")
    suspend fun clearConfig(id: Long)

    @Query("UPDATE conversations SET branchesJson = :json WHERE id = :id")
    suspend fun setBranches(id: Long, json: String?)

    // 分列拼装：最近一条活跃对话可能正是那条超 2MB 的大对话，直接 SELECT * 单行也会撑爆窗口。
    @Query("SELECT id FROM conversations WHERE isArchived = 0 ORDER BY updatedAt DESC LIMIT 1")
    suspend fun getMostRecentActiveId(): Long?

    suspend fun getMostRecentActive(): ConversationEntity? =
        getMostRecentActiveId()?.let { getByIdAssembled(it) }
}
