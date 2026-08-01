package com.arix.data.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "memories",
    indices = [Index(value = ["title"]), Index(value = ["updatedAt"]), Index(value = ["characterCardId"])]
)
data class MemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val source: String = "user_input",
    val importance: Float = 0.5f,
    val characterCardId: Long? = null,
    val type: String = "fact",          // preference/fact/event/relation/todo
    val folder: String = "",            // 用户自建文件夹分组（空=未分组）
    val pinned: Boolean = false,        // 置顶
    val lastAccessedAt: Long = 0,       // 最近被检索/命中的时间（用于时效排序）
    val relatedIds: String = "",        // 轻量图谱：相关记忆 id，逗号分隔（哪些边存在，源真值，老代码/图谱都读它）
    val relatedMeta: String = "",       // 边的元数据：JSON {"<对端id>":{"t":"related","w":1.0}}；缺项=related/1.0（向后兼容旧数据）
    val embedding: String? = null,      // 语义向量(JSON float 数组)，null=未建索引；配了 embedding 模型才有，语义检索用
    /**
     * 这条事实**被断言的时间**——不是写库时间。
     *
     * 和 createdAt/updatedAt 是三回事：「他去年换了工作」今天才说出来，createdAt=今天、assertedAt=去年。
     * 没有这个字段，「旧事实被新事实推翻」就无从判断（只能比谁写得晚，而写得晚的可能说的是更早的事）。
     * 默认 0 = 未知，一律回退用 createdAt，老数据行为不变（读取一律走 `MemoryManager.assertedAtOf`）。
     *
     * **谁刷新它**（规则在 MemoryManager，别在别处另立一套）：
     *  - 刷新：模型经 memory 工具 add/update、对话自动抽取、硬信号、教训沉淀、用户在记忆页新建——
     *    这些都是「此刻把这件事说出来」，且内容确实变了才算（同一条硬信号原样再写一遍不是重新断言）；
     *    语义去重命中后换成新说法同样刷新：同一件事又被说了一遍，说明它此刻仍然成立。
     *  - 不刷新：改错别字/调重要度/改标签（记忆页手动编辑走这条）、导入/备份恢复/回收站还原/AI 合并——
     *    还原的是历史断言，盖成今天等于凭空造出一条最新消息去推翻真正新的那条。
     */
    val assertedAt: Long = 0,
    /**
     * 置信度 0~1。用户亲口说的≈1.0，模型从对话里抽的≈0.6，硬信号(日历/位置/健康)≈0.9。
     * 检索打分与冲突消解都要用它；默认 0.8 = 不区分（等同从前）。
     */
    val confidence: Float = 0.8f,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "memory_tags",
    indices = [Index(value = ["name"], unique = true)]
)
data class MemoryTagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(
    tableName = "memory_tag_cross_ref",
    primaryKeys = ["memoryId", "tagId"]
)
data class MemoryTagCrossRef(
    val memoryId: Long,
    val tagId: Long
)
