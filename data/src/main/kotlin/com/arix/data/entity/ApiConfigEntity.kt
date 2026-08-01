package com.arix.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "api_configs")
data class ApiConfigEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val systemPrompt: String = "",
    val isActive: Boolean = false,
    val purpose: String = "chat",
    val supportsVision: Boolean = false,
    val supportsAudio: Boolean = false,
    val supportsVideo: Boolean = false,
    // 生成参数（null = 不下发，用服务商默认）
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val customHeaders: String? = null   // 自定义请求头，JSON 字符串
)
