package com.arix.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.arix.data.entity.MemoryEntity
import com.arix.data.entity.MemoryTagCrossRef
import com.arix.data.entity.MemoryTagEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memories WHERE characterCardId = :cardId ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun getTopByCard(cardId: Long, limit: Int = 10): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE (characterCardId = :cardId OR characterCardId IS NULL) AND (title LIKE '%' || :keyword || '%' OR content LIKE '%' || :keyword || '%') ORDER BY characterCardId ASC, importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun searchTopByCard(keyword: String, cardId: Long, limit: Int = 10): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE characterCardId = :cardId ORDER BY updatedAt DESC")
    suspend fun getAllByCard(cardId: Long): List<MemoryEntity>

    @Query("SELECT * FROM memories ORDER BY updatedAt DESC")
    fun getAll(): Flow<List<MemoryEntity>>

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun getTopByImportance(limit: Int = 20): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE title LIKE '%' || :keyword || '%' OR content LIKE '%' || :keyword || '%' ORDER BY updatedAt DESC")
    suspend fun search(keyword: String): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE title LIKE '%' || :keyword || '%' OR content LIKE '%' || :keyword || '%' ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun searchTop(keyword: String, limit: Int = 10): List<MemoryEntity>

    @Query("SELECT * FROM memories WHERE id = :id")
    suspend fun getById(id: Long): MemoryEntity?

    @Query("SELECT * FROM memories WHERE title = :title LIMIT 1")
    suspend fun getByTitle(title: String): MemoryEntity?

    /**
     * 按 (标题, 角色卡) 精确定位——**只按标题查会串卡**：A 卡的「喜欢的音乐」会被 B 卡同名记忆覆盖。
     * cardId 为 null 时只认 characterCardId IS NULL 那条（通用记忆），不会误命中任意角色卡的同名记忆。
     */
    @Query("SELECT * FROM memories WHERE title = :title AND ((:cardId IS NULL AND characterCardId IS NULL) OR characterCardId = :cardId) LIMIT 1")
    suspend fun getByTitleInCard(title: String, cardId: Long?): MemoryEntity?

    /** 同卡范围内已建向量的记忆（写入前语义去重用；比 allList() 少读整表 + 少读没向量的行）。 */
    @Query("SELECT * FROM memories WHERE embedding IS NOT NULL AND ((:cardId IS NULL AND characterCardId IS NULL) OR characterCardId = :cardId)")
    suspend fun embeddedInCard(cardId: Long?): List<MemoryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(memory: MemoryEntity): Long

    @Update
    suspend fun update(memory: MemoryEntity)

    @Query("DELETE FROM memories WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT COUNT(*) FROM memories")
    suspend fun count(): Int

    @Query("SELECT * FROM memories ORDER BY importance DESC, updatedAt DESC")
    suspend fun allList(): List<MemoryEntity>

    @Query("UPDATE memories SET embedding = :embedding WHERE id = :id")
    suspend fun setEmbedding(id: Long, embedding: String?)

    // 只更 lastAccessedAt：避免整行 @Update 把 embedding 大字段一并重写（检索时每条都刷时间）
    @Query("UPDATE memories SET lastAccessedAt = :t WHERE id = :id")
    suspend fun setLastAccessed(id: Long, t: Long)

    /**
     * 批量刷命中时间。**Room 的失效通知是表级的**：一轮检索命中 8 条就写 8 次，
     * 记忆页/抽屉那几个 Flow 订阅者就被连着叫醒 8 次、每次重跑一遍查询。合成一条 UPDATE 只叫醒一次。
     */
    @Query("UPDATE memories SET lastAccessedAt = :t WHERE id IN (:ids)")
    suspend fun setLastAccessedIn(ids: List<Long>, t: Long)

    /**
     * 检索候选（带向量的）。**注意与 [embeddedInCard] 的 cardId 语义不同**，两者不能互相替换：
     * 那个是**写入去重**用的，要求「同一张卡内」，通用记忆(characterCardId IS NULL)在 cardId 非空时**不参与**；
     * 这个是**检索**用的，通用记忆对每张卡都可见——检索路径原本走 `allList()` 再在内存里 filter，
     * 条件正是这一行。照搬 embeddedInCard 会把通用记忆悄悄从语义检索里踢掉。
     */
    @Query("SELECT * FROM memories WHERE embedding IS NOT NULL AND (:cardId IS NULL OR characterCardId IS NULL OR characterCardId = :cardId)")
    suspend fun embeddedForRetrieval(cardId: Long?): List<MemoryEntity>

    /** 检索可见范围内按重要度取前 N（替代「读整表再内存 filter + take」）。cardId 语义同 [embeddedForRetrieval]。 */
    @Query("SELECT * FROM memories WHERE (:cardId IS NULL OR characterCardId IS NULL OR characterCardId = :cardId) ORDER BY importance DESC, updatedAt DESC LIMIT :limit")
    suspend fun topForRetrieval(cardId: Long?, limit: Int): List<MemoryEntity>

    /** 置顶记忆（每轮检索都要无条件注入，原来是读 200 行回来自己挑）。cardId 语义同 [embeddedForRetrieval]。 */
    @Query("SELECT * FROM memories WHERE pinned = 1 AND (:cardId IS NULL OR characterCardId IS NULL OR characterCardId = :cardId)")
    suspend fun pinnedForRetrieval(cardId: Long?): List<MemoryEntity>

    /** 按类型计数（后台整理判「有没有教训」用，别为了数个数把整表读回来）。 */
    @Query("SELECT COUNT(*) FROM memories WHERE type = :type")
    suspend fun countByType(type: String): Int

    /** 某类型里没被置顶的（后台整理的候选集：置顶的连看都不看）。 */
    @Query("SELECT * FROM memories WHERE type = :type AND pinned = 0 ORDER BY updatedAt ASC")
    suspend fun byTypeUnpinned(type: String): List<MemoryEntity>

    /**
     * 某类型的**全部**（含置顶）。用于 [com.arix.app.LessonRecorder] 装载「调用前提示」镜像：
     * 那里不能漏掉置顶项——用户特意置顶的教训恰恰是他最想让 AI 记住的。
     * 按重要度降序，调用方取前几条即可，不用整表排。
     */
    @Query("SELECT * FROM memories WHERE type = :type ORDER BY importance DESC")
    suspend fun byType(type: String): List<MemoryEntity>

    /** 只取 id（清孤儿引用用；整表读回来只为拿一组 id 是纯浪费）。 */
    @Query("SELECT id FROM memories")
    suspend fun allIds(): List<Long>

    // Tags
    @Query("SELECT * FROM memory_tags ORDER BY name ASC")
    fun getAllTags(): Flow<List<MemoryTagEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTag(tag: MemoryTagEntity): Long

    @Query("SELECT * FROM memory_tags WHERE name = :name LIMIT 1")
    suspend fun getTagByName(name: String): MemoryTagEntity?

    @Query("DELETE FROM memory_tags WHERE id = :id")
    suspend fun deleteTag(id: Long)

    // Cross references
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCrossRef(crossRef: MemoryTagCrossRef)

    @Query("DELETE FROM memory_tag_cross_ref WHERE memoryId = :memoryId")
    suspend fun deleteCrossRefsByMemory(memoryId: Long)

    @Query("SELECT m.* FROM memories m INNER JOIN memory_tag_cross_ref cr ON m.id = cr.memoryId WHERE cr.tagId = :tagId ORDER BY m.updatedAt DESC")
    suspend fun getByTagId(tagId: Long): List<MemoryEntity>

    @Query("SELECT t.* FROM memory_tags t INNER JOIN memory_tag_cross_ref cr ON t.id = cr.tagId WHERE cr.memoryId = :memoryId")
    suspend fun getTagsForMemory(memoryId: Long): List<MemoryTagEntity>

    /**
     * 只取 id。按标签筛选时原来走 [getByTagId]，那会把整行**连 `embedding` 那个大字符串**一起拿回来，
     * 而调用方只要一组 id 做过滤——一个标签下几百条记忆就是几百份向量白读白解析。
     */
    @Query("SELECT cr.memoryId FROM memory_tag_cross_ref cr WHERE cr.tagId = :tagId")
    suspend fun idsByTagId(tagId: Long): List<Long>

    /**
     * 某时刻之后新建的记忆（可按角色卡收窄）。
     *
     * 为什么要单开这一条：原来是「按重要度取前 N 行，再在内存里按 createdAt 过滤」的将就写法——
     * 库涨到几千条之后，**当天新增但重要度垫底的记忆会被那个 N 直接切掉**，
     * 而"今天发生了什么"恰恰不该按重要度筛。日记入图靠这条把当天的记忆连起来，漏了就等于没连。
     * `characterCardId IS NULL` 一并放行：通用记忆对每张卡都成立。
     */
    @Query(
        "SELECT * FROM memories WHERE createdAt >= :since " +
            "AND (:cardId IS NULL OR characterCardId IS NULL OR characterCardId = :cardId) " +
            "ORDER BY createdAt DESC LIMIT :limit"
    )
    suspend fun createdSince(since: Long, cardId: Long?, limit: Int): List<MemoryEntity>
}
