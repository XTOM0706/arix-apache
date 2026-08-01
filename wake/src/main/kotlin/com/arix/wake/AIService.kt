package com.arix.wake

/**
 * 统一 AI 服务接口，抽象云端 API 和本地推理两种后端。
 *
 * 上层调用方（如 VoiceInteractionActivity）依赖此接口进行对话，
 * 无需关心具体由哪个后端处理。通过注入不同的实现（CloudAIAdapter /
 * LocalAIAdapter），可在运行时切换云端/本地 LLM。
 *
 * 设计原则：
 * - 输入输出使用 String，避免引入领域模型耦合
 * - 支持单轮文本生成和多轮对话两种核心模式
 * - 接口不含 Android 具体依赖，可被纯 Kotlin 测试
 */
interface AIService {

    /**
     * 单轮文本生成。
     *
     * @param prompt 用户输入的提示词
     * @return 模型生成的文本 (流式场景可改为 Flow<String>)
     */
    suspend fun textGeneration(prompt: String): String

    /**
     * 多轮对话补全。
     *
     * 调用方通过 [messages] 传入完整对话上下文（含 system/assistant/user 角色），
     * 模型基于全文历史生成回复。
     *
     * @param messages 消息历史列表，每项包含 role 和 content
     * @return 模型生成的回复文本
     */
    suspend fun chatCompletion(messages: List<ChatMessage>): String

    /**
     * 释放后端资源（如本地模型所占内存）。
     * 云端实现可为空操作。
     */
    fun release()
}

/**
 * 对话消息。
 */
data class ChatMessage(
    val role: String,   // "system" / "user" / "assistant"
    val content: String,
)
