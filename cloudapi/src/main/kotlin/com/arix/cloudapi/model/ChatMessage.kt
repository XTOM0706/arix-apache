package com.arix.cloudapi.model

data class ChatMessage(
    val role: String,
    val content: String,
    val reasoning: String? = null,
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val totalTokens: Int? = null,
    val tokensPerSec: Double? = null,
    val model: String? = null,             // 产出这条消息时用的模型（用于按当时模型算花费，而非当前激活模型）
    val images: List<String>? = null,
    val audios: List<String>? = null,
    val attachments: List<String>? = null, // 持久化的附件引用(file:// 路径)，用于刷新后仍显示缩略图
    val toolCalls: List<ToolCallMsg>? = null,
    val toolCallId: String? = null,
    /** 供应商私有透传槽（**消息级**）：本条 assistant 消息上「必须原样回传」的私有结构的原始 JSON 串
     *  ——思考块/签名/加密推理项（如经 OpenRouter 转发时的 reasoning_details）。
     *  与上面 `reasoning` 的区别：那是**给人看的文本**，这是**给服务器回传的结构**，两码事。
     *  收到什么原样存、回传时原样写回，不解析不规范化（见 ReasoningPassthrough）。
     *  加在参数表最后且有默认值：老会话反序列化不出这个字段 = null = 不写该键，与从前行为一致。 */
    val extra: String? = null
) {
    data class ToolCallMsg(
        val id: String,
        val name: String,
        val arguments: String,
        /** 供应商私有透传槽：tool_call 的 `extra_content` 原始 JSON 串（如 Gemini 3 的思考签名）。
         *  收到什么原样存、回传时原样写回；老数据没有=null=不写该键（与从前行为一致）。 */
        val extra: String? = null
    )
}
