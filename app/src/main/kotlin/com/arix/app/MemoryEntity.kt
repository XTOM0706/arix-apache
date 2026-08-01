package com.arix.app

/**
 * 长期记忆条目 —— Apache-2.0 精简版纯数据类（非 Room 实体，JSON 文件存储）。
 *
 * 相比原 Room 版删掉了图谱（relatedIds/relatedMeta）、语义向量（embedding）、
 * 断言时间/置信度（assertedAt/confidence）、lastAccessedAt。
 * 保留：title/content/source/importance/characterCardId/type/folder/pinned/tags/createdAt/updatedAt。
 */
data class MemoryEntity(
    val id: Long = 0,
    val title: String,
    val content: String,
    val source: String = "user_input",
    val importance: Float = 0.5f,
    val characterCardId: Long? = null,
    val type: String = "fact",          // preference/fact/event/relation/todo/lesson/environment/convention
    val folder: String = "",            // 用户自建文件夹分组（空=未分组）
    val pinned: Boolean = false,        // 置顶
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
