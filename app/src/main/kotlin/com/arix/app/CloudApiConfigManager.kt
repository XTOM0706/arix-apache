package com.arix.app

import android.content.Context
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.model.ChatMessage
import com.arix.data.db.AppDatabase
import com.arix.data.entity.ApiConfigEntity
import com.arix.data.repository.ApiConfigRepository
import kotlinx.coroutines.flow.Flow

class CloudApiConfigManager(context: Context) {

    private val dao = AppDatabase.getInstance(context).apiConfigDao()
    private val repo = ApiConfigRepository(dao)
    // 延迟创建：仅做配置 CRUD 的页面（ConfigPage/CharacterCardPage）不会白建一个
    // ConversationManager + ConversationRepository。
    val conversationManager by lazy { ConversationManager(context) }

    val allConfigs: Flow<List<ApiConfigEntity>> = repo.allConfigs

    // --- Conversation persistence (delegated) ---

    suspend fun saveConversation(conversationId: Long, messages: List<ChatMessage>) {
        conversationManager.saveMessages(conversationId, messages)
    }

    suspend fun loadConversation(conversationId: Long): List<ChatMessage> {
        return conversationManager.loadMessages(conversationId)
    }

    suspend fun clearConversation(conversationId: Long) {
        conversationManager.clearMessages(conversationId)
    }

    // --- Config CRUD ---

    suspend fun getActive(): ApiConfigEntity? = repo.getActive()

    suspend fun getActiveByPurpose(purpose: String): ApiConfigEntity? = repo.getActiveByPurpose(purpose)

    suspend fun getActiveConfig(): CloudApiConfig? {
        val active = getActive() ?: return null
        return CloudApiConfig(active.baseUrl, active.apiKey, active.model,
            active.temperature, active.topP, active.maxTokens, active.frequencyPenalty, active.presencePenalty, active.customHeaders)
    }

    /** 按 id 取配置（供「深度研究」等自选研究模型用）；取不到返回 null。不带 customHeaders，与聊天路径一致避免 401。 */
    suspend fun getConfigById(id: Long): CloudApiConfig? {
        val e = dao.getById(id) ?: return null
        return CloudApiConfig(e.baseUrl.trimEnd('/'), e.apiKey.trim(), e.model.trim(),
            e.temperature, e.topP, e.maxTokens, e.frequencyPenalty, e.presencePenalty)
    }

    /**
     * 取"对话"用文本模型（唤醒助手/后台文本对话用）。**严格 = 配置页里激活的那个「对话」模型**，
     * 与主聊天页 `configs.find { isActive && purpose=="chat" }` 逐字一致——用户配好模型后，唤醒直接调用它，
     * 不回退到 reasoning、更不跨到 vision/tts/stt（那些端点 key/地址不接文本对话，误取会 401、也是「乱动」）。
     * 取不到返回 null（调用方提示"去配置页激活一个对话模型"），绝不偷偷换一个别的模型顶上。
     */
    /**
     * 取某个用途（如 "agent"/"translate"）的激活模型；该用途没配就回退到「对话」模型。
     * 给需要独立模型的功能用（子 agent、命令翻译…），都走这里，未配也能干活不至于报错。
     *
     * @param capMaxTokens 出参上限的**兜底值**（元任务用）。这个回退有个隐藏代价：默认安装下
     *   没人配过专用模型，于是"起标题/抽记忆/判审批"这些**出参本来就很短**的活，全在用最贵的主模型上
     *   不封顶地跑。传了这个值就在没有显式 maxTokens 时给它封个顶——
     *   ⚠ 只当兜底，用户在配置里明确设过 maxTokens 的一律以用户的为准。
     *   ⚠ 别定太小：思考型模型的思考 token 也算在 max_tokens 里，卡太死会让它"想完就没额度输出了"。
     *   截断本身有保护（见 `StreamResult.finishReason == "length"`），但那是保护不是目的。
     */
    suspend fun getConfigForPurpose(purpose: String, capMaxTokens: Int? = null): CloudApiConfig? {
        val e = getActiveByPurpose(purpose) ?: return getChatConfig()?.let {
            if (it.maxTokens == null && capMaxTokens != null) it.copy(maxTokens = capMaxTokens) else it
        }
        return CloudApiConfig(e.baseUrl.trimEnd('/'), e.apiKey.trim(), e.model.trim(),
            e.temperature, e.topP, e.maxTokens ?: capMaxTokens, e.frequencyPenalty, e.presencePenalty)
    }

    suspend fun getChatConfig(): CloudApiConfig? {
        val e = getActiveByPurpose("chat") ?: return null
        // 与主聊天发送路径逐字对齐（ChatScreen 建 config 也是这 8 个参数、**不传 customHeaders**）：
        // customHeaders 里若含 Authorization/坏头会覆盖正确 Bearer → 401，主聊天不传所以没事，这里也不传。
        return CloudApiConfig(e.baseUrl.trimEnd('/'), e.apiKey.trim(), e.model.trim(),
            e.temperature, e.topP, e.maxTokens, e.frequencyPenalty, e.presencePenalty)
    }

    suspend fun add(name: String, baseUrl: String, apiKey: String, model: String, systemPrompt: String = "", purpose: String = "chat", supportsVision: Boolean = false, supportsAudio: Boolean = false, supportsVideo: Boolean = false,
                    temperature: Float? = null, topP: Float? = null, maxTokens: Int? = null, frequencyPenalty: Float? = null, presencePenalty: Float? = null, customHeaders: String? = null): Long {
        return repo.add(
            ApiConfigEntity(
                name = name,
                baseUrl = baseUrl.trimEnd('/'),
                apiKey = apiKey.trim(),
                model = model.trim(),
                systemPrompt = systemPrompt,
                isActive = true,
                purpose = purpose,
                supportsVision = supportsVision,
                supportsAudio = supportsAudio,
                supportsVideo = supportsVideo,
                temperature = temperature, topP = topP, maxTokens = maxTokens,
                frequencyPenalty = frequencyPenalty, presencePenalty = presencePenalty, customHeaders = customHeaders
            )
        ).also { newId -> repo.switchTo(newId) }
    }

    // dao.update 是整行替换：purpose / isActive 必须一并带上，否则编辑后用途会被重置成 "chat"、激活态丢失。
    suspend fun update(id: Long, name: String, baseUrl: String, apiKey: String, model: String, systemPrompt: String = "", purpose: String = "chat", isActive: Boolean = false, supportsVision: Boolean = false, supportsAudio: Boolean = false, supportsVideo: Boolean = false,
                       temperature: Float? = null, topP: Float? = null, maxTokens: Int? = null, frequencyPenalty: Float? = null, presencePenalty: Float? = null, customHeaders: String? = null) {
        repo.update(
            ApiConfigEntity(
                id = id,
                name = name,
                baseUrl = baseUrl.trimEnd('/'),
                apiKey = apiKey.trim(),
                model = model.trim(),
                systemPrompt = systemPrompt,
                isActive = isActive,
                purpose = purpose,
                supportsVision = supportsVision,
                supportsAudio = supportsAudio,
                supportsVideo = supportsVideo,
                temperature = temperature, topP = topP, maxTokens = maxTokens,
                frequencyPenalty = frequencyPenalty, presencePenalty = presencePenalty, customHeaders = customHeaders
            )
        )
    }

    suspend fun delete(id: Long) = repo.delete(ApiConfigEntity(id = id, name = "", baseUrl = "", apiKey = "", model = ""))

    suspend fun switchTo(id: Long) = repo.switchTo(id)
}
