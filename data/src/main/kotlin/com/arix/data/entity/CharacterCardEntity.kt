package com.arix.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "character_cards")
data class CharacterCardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val characterSetting: String = "",
    val worldBook: String = "",   // 世界书 / 背景故事（与人设分开注入）
    val openingStatement: String = "",
    val avatarPath: String = "",
    val attachedTagIds: String = "",
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val tone: String = "",
    val length: String = "",
    val language: String = "",
    val waifuEnabled: Boolean = false,
    val waifuDelayMs: Int = 250
)
