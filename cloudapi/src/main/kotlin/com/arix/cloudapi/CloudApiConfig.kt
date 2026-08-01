package com.arix.cloudapi

data class CloudApiConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String = "deepseek-chat",
    // 生成参数（null = 不下发，用服务商默认）
    val temperature: Float? = null,
    val topP: Float? = null,
    val maxTokens: Int? = null,
    val frequencyPenalty: Float? = null,
    val presencePenalty: Float? = null,
    val customHeaders: String? = null   // JSON 字符串，如 {"X-Foo":"bar"}
)
