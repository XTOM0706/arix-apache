package com.arix.data.repository

import com.arix.data.dao.ConversationDao
import com.arix.data.dao.ConversationSummary
import com.arix.data.dao.TagDao
import com.arix.data.entity.ConversationEntity
import com.arix.data.entity.ConversationTagCrossRef
import com.arix.data.entity.TagEntity
import com.arix.data.search.ChatSearchIndex
import kotlinx.coroutines.flow.Flow

class ConversationRepository(
    private val convDao: ConversationDao,
    private val tagDao: TagDao
) {
    // 列表/抽屉用的轻量投影流（不含 messagesJson/branchesJson），大对话也不会撑爆 2MB 游标窗口。
    val activeSummaries: Flow<List<ConversationSummary>> = convDao.getActiveSummaries()
    val archivedSummaries: Flow<List<ConversationSummary>> = convDao.getArchivedSummaries()

    // 需要消息内容的场景（备份/统计/记忆抽取/搜索）：逐条分列拼装，避免整行 SELECT * 破窗。
    suspend fun loadAllActiveFull(): List<ConversationEntity> = convDao.getAllActiveFull()
    suspend fun loadAllArchivedFull(): List<ConversationEntity> = convDao.getAllArchivedFull()

    // 只取某条会话的消息 JSON——分块读，单列即便自身 > 2MB 也不会撑爆游标窗口。列表懒加载用量/花费时用。
    suspend fun getMessagesJson(id: Long): String = convDao.readMessagesJson(id)

    suspend fun searchSummaries(q: String, limit: Int): List<ConversationSummary> =
        convDao.searchSummaries(q, limit)

    // 分列拼装，绕开单行 >2MB 的 CursorWindow 上限（长对话+分支树能到 2MB+）。
    // ⚠ 只要一两个元数据字段就别走这个：它会把 messagesJson/branchesJson 分块读回来。
    // 要角色卡用 [cardIdOf]、要元数据用 [getSummaryById]、要分支树用 [getBranchesJson]。
    suspend fun getById(id: Long): ConversationEntity? = convDao.getByIdAssembled(id)

    /** 只取这条会话绑的角色卡 id（每轮回复收尾都要问一次，别为它拼整份会话）。 */
    suspend fun cardIdOf(id: Long): Long? = convDao.cardIdOf(id)

    /** 只取元数据投影（标题/时间/归档位…），不含两个大列。 */
    suspend fun getSummaryById(id: Long): ConversationSummary? = convDao.getSummaryById(id)

    /** 只取分支树原文（null=线性对话）。分块读，单列超 2MB 也安全。 */
    suspend fun getBranchesJson(id: Long): String? = convDao.readBranchesJson(id)

    suspend fun create(conversation: ConversationEntity): Long =
        convDao.insert(conversation).also { newId ->
            // 空会话也要进队列（写一条 0 命中的索引状态）：索引"是否已覆盖全部会话"是靠状态表判断的，
            // 新建的空会话若一直没有状态行，就会让覆盖率永远差一条，每次搜索都白白退回全表扫描。
            // 导入/恢复则会带着整份消息一起 insert，同样在这里被索引。
            ChatSearchIndex.schedule(newId, conversation.messagesJson, conversation.updatedAt)
        }

    suspend fun update(conversation: ConversationEntity) {
        convDao.update(conversation)
        ChatSearchIndex.schedule(conversation.id, conversation.messagesJson, conversation.updatedAt)
    }

    suspend fun delete(id: Long) {
        convDao.delete(id)
        // 索引里必须同步删掉：留着的话「搜以前聊过什么」会把已删除的对话内容翻出来，
        // 那不只是脏数据，是隐私问题。
        ChatSearchIndex.dropConversations(listOf(id))
    }

    suspend fun deleteMany(ids: List<Long>) {
        convDao.deleteMany(ids)
        ChatSearchIndex.dropConversations(ids)
    }

    suspend fun setPinned(id: Long, pinned: Boolean) = convDao.setPinned(id, pinned)

    suspend fun setArchived(id: Long, archived: Boolean) = convDao.setArchived(id, archived)

    suspend fun setLocked(id: Long, locked: Boolean) = convDao.setLocked(id, locked)

    suspend fun setTitle(id: Long, title: String) = convDao.setTitle(id, title)

    suspend fun setCharacterCard(id: Long, characterCardId: Long) = convDao.setCharacterCard(id, characterCardId)

    suspend fun setFolder(id: Long, folder: String) = convDao.setFolder(id, folder)

    suspend fun setConfig(id: Long, configId: Long) = convDao.setConfig(id, configId)

    /** 解除模型绑定，此后跟随全局激活项（见 [ConversationDao.clearConfig] 的说明）。 */
    suspend fun clearConfig(id: Long) = convDao.clearConfig(id)

    suspend fun getMostRecentActive(): ConversationEntity? = convDao.getMostRecentActive()

    /** 只要「最近那条对话是哪条」的 id——别为这一个 Long 把整份会话拼回来。 */
    suspend fun getMostRecentActiveId(): Long? = convDao.getMostRecentActiveId()

    /**
     * 保存消息的**唯一收口**（ChatScreen/语音/子代理/导入全都经由 ConversationManager 走到这里），
     * 所以全文索引的增量维护也挂在这儿——不必去改 ConversationManager，也不会漏掉任何一条写入路径。
     *
     * [ChatSearchIndex.schedule] 只做「取消上一个 + 入队」，真正的解析/落库在它自己的 IO 协程里
     * 去抖 1.5s 后跑：发送链路一毫秒都不多等，索引更新失败也只打日志——**搜不到比发不出去好**。
     */
    suspend fun saveMessages(id: Long, json: String, updatedAt: Long = System.currentTimeMillis()) {
        convDao.saveMessages(id, json, updatedAt)
        ChatSearchIndex.schedule(id, json, updatedAt)
    }

    suspend fun saveBranches(id: Long, json: String?) = convDao.setBranches(id, json)

    // Tags
    suspend fun getTagsForConversation(conversationId: Long): List<TagEntity> =
        tagDao.getTagsForConversation(conversationId)

    suspend fun addTag(conversationId: Long, tagId: Long) =
        tagDao.addTagToConversation(ConversationTagCrossRef(conversationId, tagId))

    suspend fun removeTag(conversationId: Long, tagId: Long) =
        tagDao.removeTagFromConversation(conversationId, tagId)

    suspend fun clearTags(conversationId: Long) =
        tagDao.clearTagsForConversation(conversationId)

    // Tag CRUD
    val allTags: Flow<List<TagEntity>> = tagDao.getAll()

    suspend fun createTag(name: String, color: String = "#45475A"): Long =
        tagDao.insert(TagEntity(name = name, color = color))

    suspend fun deleteTag(id: Long) = tagDao.delete(id)
}
