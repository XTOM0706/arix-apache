package com.arix.app

import android.content.Context
import com.arix.tool.FuzzyMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 长期记忆 —— Apache-2.0 精简版：**纯文件存储**，AI 用 memory 工具读写。
 *
 * 相比原版删掉了：Room 库（memory 三表）、记忆图谱（relatedIds/边）、语义向量（embedding）、
 * 冲突消解（superseded 归档）、置信度/断言时间、自动整理（MemoryTidy）与回收站/自动压缩（MemorySalvage）。
 * 保留的核心语义：add / search / update / delete / pin / type / folder / tag，检索用关键词 + 模糊匹配。
 *
 * 存储：`ai_workspace/memory.json`（与文件工具同一目录，二开者/内置方直接可读可改）。
 * 并发：全操作在内存加载后改、原子写回（tmp + rename）。记忆量小（上限 1000 条），够用。
 *
 * ⚠ 迁移说明：旧版 Room 里的记忆不再读取——本版从空库开始。如需保留旧数据，
 * 可在升级前用旧版的「导出」落盘，再在本版导入。
 */
class MemoryManager(context: Context) {

    private val appContext = context.applicationContext

    companion object {
        /** 总条数兜底上限（与 MemoryTool 同源）。 */
        const val MEMORY_MAX_COUNT = 1000
        private const val FILE_NAME = "memory.json"
    }

    // 供 UI 观察的流：文件每次写回后刷新。UI 侧只读不写（写操作走各方法）。
    private val _allMemories = kotlinx.coroutines.flow.MutableStateFlow<List<MemoryEntity>>(emptyList())
    val allMemories = _allMemories.asStateFlow()
    private val _allTags = kotlinx.coroutines.flow.MutableStateFlow<List<String>>(emptyList())
    val allTags = _allTags.asStateFlow()

    init { refresh() }

    private fun refresh() {
        val list = loadAll().map { it.toEntity() }
        _allMemories.value = list.sortedByDescending { it.updatedAt }
        _allTags.value = list.flatMap { it.tags }.distinct().sorted()
    }

    private fun file(): File = File(com.arix.tool.AiWorkspace.root(appContext), FILE_NAME)

    private fun loadAll(): MutableList<JSONObject> {
        val f = file()
        if (!f.exists()) return ArrayList()
        return try {
            val arr = JSONArray(f.readText())
            val out = ArrayList<JSONObject>(arr.length())
            for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
            out
        } catch (_: Exception) { ArrayList() }
    }

    private fun saveAll(list: List<JSONObject>) {
        val f = file()
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$FILE_NAME.tmp")
        tmp.writeText(JSONArray(list).toString())
        if (tmp.renameTo(f)) return
        // rename 失败（如 tmp 与目标同盘但被占用）就回退直接写
        f.writeText(JSONArray(list).toString())
        tmp.delete()
    }

    private fun JSONObject.tagsList(): List<String> =
        optJSONArray("tags")?.let { arr -> (0 until arr.length()).mapNotNull { arr.optString(it) } } ?: emptyList()

    private fun JSONObject.toEntity(): MemoryEntity =
        MemoryEntity(
            id = optLong("id", 0),
            title = optString("title", ""),
            content = optString("content", ""),
            source = optString("source", "user_input"),
            importance = optDouble("importance", 0.5).toFloat(),
            characterCardId = if (has("characterCardId") && !isNull("characterCardId")) optLong("characterCardId") else null,
            type = optString("type", "fact"),
            folder = optString("folder", ""),
            pinned = optBoolean("pinned", false),
            tags = tagsList(),
            createdAt = optLong("createdAt", 0L),
            updatedAt = optLong("updatedAt", 0L),
        )

    private fun MemoryEntity.toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("content", content); put("source", source)
        put("importance", importance.toDouble())
        if (characterCardId != null) put("characterCardId", characterCardId) else put("characterCardId", JSONObject.NULL)
        put("type", type); put("folder", folder); put("pinned", pinned)
        put("tags", JSONArray(tags)); put("createdAt", createdAt); put("updatedAt", updatedAt)
    }

    private fun nextId(list: List<JSONObject>): Long =
        (list.maxOfOrNull { it.optLong("id", 0) } ?: 0L) + 1L

    // ============================================================
    // 基础 CRUD
    // ============================================================

    suspend fun search(keyword: String): List<MemoryEntity> {
        val all = loadAll().map { it.toEntity() }
        if (keyword.isBlank()) return all.take(20)
        val exact = all.filter { it.title.contains(keyword) || it.content.contains(keyword) }
        if (exact.size >= 20) return exact.take(20)
        val have = exact.mapTo(HashSet()) { it.id }
        val fuzzy = FuzzyMatch.rankBy(keyword, all.filter { it.id !in have }, 20 - exact.size) { listOf(it.title, it.content.take(200)) }
        return (exact + fuzzy.map { it.item }).sortedByDescending { it.updatedAt }
    }

    suspend fun searchTop(keyword: String, limit: Int = 10): List<MemoryEntity> {
        val all = loadAll().map { it.toEntity() }
        if (keyword.isBlank()) return all.take(limit)
        val exact = all.filter { it.title.contains(keyword) || it.content.contains(keyword) }.take(limit)
        if (exact.size >= limit) return exact
        val have = exact.mapTo(HashSet()) { it.id }
        val fuzzy = FuzzyMatch.rankBy(keyword, all.filter { it.id !in have }, limit - exact.size) { listOf(it.title, it.content.take(200)) }
        return exact + fuzzy.map { it.item }
    }

    suspend fun queryRelevant(userMessage: String, maxResults: Int = 5, cardId: Long? = null): List<MemoryEntity> {
        val all = loadAll().map { it.toEntity() }
        val byCard = if (cardId == null) all else all.filter { it.characterCardId == null || it.characterCardId == cardId }
        if (byCard.isEmpty()) return emptyList()
        val scored = FuzzyMatch.rankBy(userMessage.take(500), byCard, maxResults) { listOf(it.title, it.content.take(200)) }
        // 同分时置顶优先
        return scored.sortedWith(compareByDescending<FuzzyMatch.Scored<MemoryEntity>> { it.item.pinned }.thenByDescending { it.score })
            .take(maxResults).map { it.item }
    }

    suspend fun getById(id: Long): MemoryEntity? = loadAll().firstOrNull { it.optLong("id") == id }?.toEntity()

    suspend fun add(title: String, content: String, source: String = "user_input",
                    importance: Float = 0.5f, characterCardId: Long? = null,
                    tags: List<String> = emptyList(), type: String = "fact",
                    pinned: Boolean = false): Long {
        val list = loadAll()
        if (list.size >= MEMORY_MAX_COUNT) return -1
        val now = System.currentTimeMillis()
        val id = nextId(list)
        list.add(MemoryEntity(
            id = id, title = title, content = content, source = source,
            importance = importance, characterCardId = characterCardId,
            type = type, pinned = pinned, tags = tags.distinct(),
            createdAt = now, updatedAt = now,
        ).toJson())
        saveAll(list)
        refresh()
        return id
    }

    suspend fun update(id: Long, title: String? = null, content: String? = null,
                       importance: Float? = null, characterCardId: Long? = null,
                       tags: List<String>? = null) {
        val list = loadAll()
        val idx = list.indexOfFirst { it.optLong("id") == id }
        if (idx < 0) return
        val e = list[idx].toEntity()
        val updated = e.copy(
            title = title ?: e.title,
            content = content ?: e.content,
            importance = importance ?: e.importance,
            characterCardId = characterCardId ?: e.characterCardId,
            tags = tags ?: e.tags,
            updatedAt = System.currentTimeMillis(),
        )
        list[idx] = updated.toJson()
        saveAll(list)
        refresh()
    }

    suspend fun delete(id: Long) {
        val list = loadAll()
        if (list.removeIf { it.optLong("id") == id }) saveAll(list)
    }

    suspend fun count(): Int = loadAll().size

    suspend fun all(limit: Int = 10000): List<MemoryEntity> =
        loadAll().map { it.toEntity() }.sortedByDescending { it.updatedAt }.take(limit)

    suspend fun recent(limit: Int = 50): List<MemoryEntity> =
        loadAll().map { it.toEntity() }.sortedByDescending { it.updatedAt }.take(limit)

    suspend fun byCard(cardId: Long): List<MemoryEntity> =
        loadAll().map { it.toEntity() }.filter { it.characterCardId == cardId }.sortedByDescending { it.updatedAt }

    suspend fun idByTitle(title: String): Long? = loadAll().firstOrNull { it.optString("title") == title }?.optLong("id")

    suspend fun upsertByTitle(title: String, content: String, source: String = "auto_extract",
                              importance: Float = 0.5f, characterCardId: Long? = null,
                              tags: List<String> = emptyList(), type: String = "fact"): Long {
        val list = loadAll()
        val existing = list.firstOrNull { it.optString("title") == title }
        if (existing != null) {
            val id = existing.optLong("id")
            update(id, title = title, content = content, importance = importance,
                characterCardId = characterCardId, tags = tags)
            return id
        }
        return add(title, content, source, importance, characterCardId, tags, type)
    }

    // ============================================================
    // 属性修改
    // ============================================================

    suspend fun setPinned(id: Long, pinned: Boolean) {
        val list = loadAll()
        val idx = list.indexOfFirst { it.optLong("id") == id }
        if (idx < 0) return
        val e = list[idx].toEntity()
        list[idx] = e.copy(pinned = pinned, updatedAt = System.currentTimeMillis()).toJson()
        saveAll(list)
        refresh()
    }

    suspend fun setType(id: Long, type: String) {
        val list = loadAll()
        val idx = list.indexOfFirst { it.optLong("id") == id }
        if (idx < 0) return
        val e = list[idx].toEntity()
        list[idx] = e.copy(type = type, updatedAt = System.currentTimeMillis()).toJson()
        saveAll(list)
        refresh()
    }

    suspend fun setFolder(id: Long, folder: String) {
        val list = loadAll()
        val idx = list.indexOfFirst { it.optLong("id") == id }
        if (idx < 0) return
        val e = list[idx].toEntity()
        list[idx] = e.copy(folder = folder, updatedAt = System.currentTimeMillis()).toJson()
        saveAll(list)
        refresh()
    }

    suspend fun setCard(id: Long, cardId: Long?) {
        val list = loadAll()
        val idx = list.indexOfFirst { it.optLong("id") == id }
        if (idx < 0) return
        val e = list[idx].toEntity()
        list[idx] = e.copy(characterCardId = cardId, updatedAt = System.currentTimeMillis()).toJson()
        saveAll(list)
        refresh()
    }

    // ============================================================
    // 标签（文件版：直接存每条记录的 tags 数组，不再单独建 tag 表）
    // ============================================================

    suspend fun getTagNames(memoryId: Long): List<String> =
        loadAll().firstOrNull { it.optLong("id") == memoryId }?.toEntity()?.tags ?: emptyList()

    suspend fun allTags(): List<String> =
        loadAll().flatMap { it.toEntity().tags }.distinct().sorted()

    suspend fun idsByTag(tag: String): List<Long> =
        loadAll().filter { tag in it.toEntity().tags }.map { it.optLong("id") }

    suspend fun listFolders(): List<String> =
        loadAll().map { it.toEntity().folder }.filter { it.isNotBlank() }.distinct().sorted()

    suspend fun byFolder(folder: String): List<MemoryEntity> =
        loadAll().map { it.toEntity() }.filter { it.folder == folder }.sortedByDescending { it.updatedAt }

    /** 图谱相关接口（原版有，精简版删掉图谱）——保留空实现避免调用方改动过大。 */
    suspend fun linkRelated(id: Long, ids: List<Long>) { /* 图谱已移除 */ }
}
