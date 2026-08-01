package com.arix.data.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    foreignKeys = [
        ForeignKey(
            entity = CharacterCardEntity::class,
            parentColumns = ["id"],
            childColumns = ["characterCardId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = ApiConfigEntity::class,
            parentColumns = ["id"],
            childColumns = ["configId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index("characterCardId"),
        Index("configId")
    ]
)
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val characterCardId: Long? = null,
    val configId: Long? = null,
    val title: String = "新对话",
    val messagesJson: String = "[]",
    val isPinned: Boolean = false,
    val isArchived: Boolean = false,
    // 锁定：挡删除（单条/批量）+ 不参与批量清理类操作；不影响排序/置顶（见 ConversationDao 的 ORDER BY 注释）。
    val isLocked: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val source: String = "chat",   // 对话来源：chat=文字聊天，voice=语音唤醒
    val folder: String = "",       // 文件夹分组名（空=未分组）
    // 会话分支树（消息 fork / 变体）。null=无分支(线性对话)，messagesJson 仍为当前活动路径。
    // 存全量树(节点+父链+activeChild+activeLeaf)，纯增量列，旧读取方/导出/备份不受影响。
    val branchesJson: String? = null
)
