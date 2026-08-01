package com.arix.tool

/**
 * 当前活跃会话的上下文，供无状态工具单例读取。
 * 记忆工具据此把 AI 新建/检索的记忆归属到「当前会话的角色卡」，而非一律落通用记忆。
 * 由聊天页在加载会话时写入 [characterCardId]。
 */
object ActiveChatContext {
    @Volatile
    var characterCardId: Long? = null

    /** 当前打开的会话 id（供后台异步任务如深度搜索把结果投递回这条对话）。 */
    @Volatile
    var conversationId: Long? = null
}
