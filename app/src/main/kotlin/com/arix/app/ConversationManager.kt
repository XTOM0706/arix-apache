package com.arix.app

import android.content.Context
import com.arix.cloudapi.model.ChatMessage
import com.arix.data.db.AppDatabase
import com.arix.data.repository.ConversationRepository
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class ConversationManager(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    val repo = ConversationRepository(db.conversationDao(), db.tagDao())

    // 自动起标题：后台 fire-and-forget，进程内每会话仅一次（titlingIds 去重）。
    private val titleScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val titlingIds = java.util.Collections.synchronizedSet(HashSet<Long>())

    suspend fun create(
        characterCardId: Long? = null,
        configId: Long? = null,
        title: String = "新对话",
        source: String = "chat"
    ): Long {
        return repo.create(
            com.arix.data.entity.ConversationEntity(
                characterCardId = characterCardId,
                configId = configId,
                title = title,
                source = source
            )
        )
    }

    /**
     * 删对话的**唯一入口**：删数据库行 + 清掉这个对话在工作区留下的原文存档与摘要缓存
     * （见 [ContextCompressor.purgeConversation]）。
     *
     * 为什么不让各处直接调 `repo.delete`：压缩过的早期原文被落盘在 AI 工作区 `archive/` 下，
     * 只删会话行的话，对话在列表里没了、原文还在工作区里、模型还能检索到——这是隐私缺口，
     * 而不是"缓存没清干净"。删除路径有四条（对话列表菜单、批量删、抽屉长按、manage_chats 工具），
     * 分散着各修一遍迟早漏一条，所以收口到这里。
     *
     * 顺序是先删库、再清文件：库行才是"这个对话还在不在"的判据，文件清理失败也不该让删除本身失败。
     *
     * ⚠ 锁定态在这里挡：`isLocked=true` 的会话直接跳过、不动库也不清文件，返回 false。
     * 四条删除路径（对话列表菜单、批量删、抽屉长按、manage_chats 工具）全都收口到这一个函数，
     * 所以只在这一处判断锁定，四条路径全部生效，不必逐一去改调用方。
     * 调用方若不检查返回值（如抽屉长按、manage_chats），效果是"锁定的会话静默不被删除"——
     * 不丢数据，只是那两处暂时不会弹出"已锁定"的说明文案（那两个文件不在本次改动范围内）。
     *
     * @return true=已删除；false=被锁定拦下，未做任何改动。
     */
    suspend fun delete(conversationId: Long): Boolean {
        val summary = repo.getSummaryById(conversationId)
        if (summary?.isLocked == true) return false
        repo.delete(conversationId)
        ContextCompressor.purgeConversation(context, conversationId)
        return true
    }

    /**
     * 批量删除（列表页多选）。逐条走锁定判断，锁定的 id 直接跳过（不参与这次批量操作）。
     * @return 实际被删除的 id 列表（可能少于传入的 [ids]，差集就是被锁定跳过的）。
     */
    suspend fun deleteMany(ids: List<Long>): List<Long> {
        val toDelete = ids.filter { repo.getSummaryById(it)?.isLocked != true }
        if (toDelete.isNotEmpty()) {
            repo.deleteMany(toDelete)
            toDelete.forEach { ContextCompressor.purgeConversation(context, it) }
        }
        return toDelete
    }

    /** 锁定/解锁：锁定后这条会话挡删除（见 [delete]/[deleteMany]），不参与批量清理类操作。持久化在 isLocked 列。 */
    suspend fun setLocked(conversationId: Long, locked: Boolean) = repo.setLocked(conversationId, locked)

    /**
     * 转发一条消息到另一个会话：追加到目标会话消息末尾（走 [saveMessages] 同一条落库口，
     * 敏感结果占位/自动起标题等既有行为都保留）。保留原消息的 role，气泡样式跟着对，
     * 不会把转发来的助手消息也挤成用户气泡。内容前缀一行来源说明，一眼看出是转发来的。
     * 附件引用（file:// 路径）原样带过去。
     *
     * 不进目标会话的分支树（branchesJson）：转发只追加到线性 messagesJson 尾部，
     * 等同于旧代码里"追加消息不进树"的既有行为，不新增分支相关的复杂度。
     *
     * @return true=已追加；false=目标会话不存在。
     */
    suspend fun forwardMessage(
        targetConversationId: Long,
        role: String,
        text: String,
        attachments: List<String>?,
        sourceTitle: String
    ): Boolean {
        if (repo.getSummaryById(targetConversationId) == null) return false
        val existing = loadMessages(targetConversationId)
        val forwarded = ChatMessage(
            role = role,
            content = String.format(tr("[转发自「%s」]\n%s"), sourceTitle.ifBlank { tr("对话") }, text),
            attachments = attachments
        )
        saveMessages(targetConversationId, existing + forwarded)
        return true
    }

    suspend fun saveMessages(conversationId: Long, messages: List<ChatMessage>) {
        // 落库前把敏感工具（短信/通知/剪贴板）的结果正文换成占位说明，见 [com.arix.tool.SensitiveResultPolicy]。
        // 只在**这里**做，不在产生结果的地方做：模型这一轮还要用原文干活（念验证码、照通知内容回复），
        // 内存里那份必须是原文；只有写盘的这一份被改。这是全 App 唯一的对话落库口，所以只堵这一处就够。
        // ⚠ 它只改 content，不删消息、不动 toolCallId——少一条配对的 tool 结果，下一轮请求直接 400
        //   （同样的坑见 ContextCompressor.sanitizePairing 的注释）。
        val persisted = com.arix.tool.SensitiveResultPolicy.redactForPersistence(messages)
        val filtered = persisted.filter { msg ->
            msg.content.isNotBlank() || !msg.reasoning.isNullOrBlank() ||
            !msg.toolCalls.isNullOrEmpty() || msg.toolCallId != null ||
            !msg.attachments.isNullOrEmpty()
        }
        val json = JSONArray().apply {
            filtered.forEach { msg ->
                put(JSONObject().apply {
                    put("role", msg.role)
                    if (msg.content.isNotBlank() || msg.toolCalls == null) put("content", msg.content)
                    if (!msg.reasoning.isNullOrBlank()) put("reasoning", msg.reasoning)
                    // 消息级的供应商私有思考载体（签名/加密思考块）。和上面 reasoning 不是一回事：
                    // reasoning 是给人看的文本，这个是**必须原样回传**的结构体，不存盘则重开 App 续聊会被上游拒收。
                    if (msg.extra != null) put("extra", msg.extra)
                    if (!msg.toolCalls.isNullOrEmpty()) {
                        val tcArr = JSONArray()
                        msg.toolCalls?.forEach { tc ->
                            tcArr.put(JSONObject().apply {
                                put("id", tc.id)
                                put("name", tc.name)
                                put("arguments", tc.arguments)
                                // 供应商私有扩展（Gemini 3 思考签名）也要存盘，否则重开 App 续聊必 400
                                if (tc.extra != null) put("extra", tc.extra)
                            })
                        }
                        put("toolCalls", tcArr)
                    }
                    if (msg.toolCallId != null) put("toolCallId", msg.toolCallId)
                    if (!msg.attachments.isNullOrEmpty()) {
                        val attArr = JSONArray()
                        msg.attachments?.forEach { attArr.put(it) }
                        put("attachments", attArr)
                    }
                    msg.totalTokens?.let {
                        put("totalTokens", it)
                        put("promptTokens", msg.promptTokens ?: 0)
                        put("completionTokens", msg.completionTokens ?: 0)
                        if (msg.tokensPerSec != null) put("tokensPerSec", msg.tokensPerSec)
                    }
                    if (!msg.model.isNullOrBlank()) put("model", msg.model)
                })
            }
        }.toString()
        repo.saveMessages(conversationId, json)
        maybeAutoTitle(conversationId, messages)
    }

    /** 仍是 create 时给的默认标题（「新对话」/「与 X 的对话」）才自动生成；用户手改过就不动。 */
    private fun looksDefaultTitle(t: String): Boolean =
        t.isBlank() || t == "新对话" || (t.startsWith("与 ") && t.endsWith(" 的对话"))

    /**
     * 有了第一问一答，就用「标题模型」给对话起个简短标题（未配标题模型→退回对话模型）。
     * 后台跑、进程内每会话仅一次、失败可下次重试；只覆盖默认标题，绝不改用户手改过的。
     * 放这里而非 ChatScreen，避开「别动 ChatScreen」铁律。
     */
    private fun maybeAutoTitle(conversationId: Long, messages: List<ChatMessage>) {
        val userText = messages.firstOrNull { it.role == "user" && it.content.isNotBlank() }?.content ?: return
        val aiText = messages.firstOrNull { it.role == "assistant" && it.content.isNotBlank() }?.content ?: return
        if (!titlingIds.add(conversationId)) return
        titleScope.launch {
            try {
                val conv = repo.getById(conversationId)
                if (conv == null || !looksDefaultTitle(conv.title)) return@launch
                val cfgMgr = CloudApiConfigManager(context)
                val e = cfgMgr.getActiveByPurpose("title") ?: cfgMgr.getActiveByPurpose("chat")
                    ?: run { titlingIds.remove(conversationId); return@launch }
                val client = com.arix.cloudapi.CloudApiClient(
                    com.arix.cloudapi.CloudApiConfig(e.baseUrl.trimEnd('/'), e.apiKey.trim(), e.model.trim())
                )
                val prompt = listOf(
                    ChatMessage("system", "根据下面这段对话，起一个概括主题的简短标题。要求：不超过12个汉字；只输出标题本身，不要引号、标点、前后缀或解释。"),
                    ChatMessage("user", "用户：${userText.take(600)}\n助手：${aiText.take(600)}"),
                )
                var out = ""
                val r = client.streamChat(prompt, null, 0, null, onReasoningChunk = {}, onContentChunk = { out += it })
                val title = out.trim().replace("\n", " ").trim('"', '「', '」', '“', '”', ' ', '.', '。').take(20)
                if (r.error == null && title.isNotBlank()) {
                    // 生成期间用户可能已手改，二次确认仍是默认标题才写入
                    val cur = repo.getById(conversationId)
                    if (cur != null && looksDefaultTitle(cur.title)) repo.setTitle(conversationId, title)
                } else titlingIds.remove(conversationId)
            } catch (_: Exception) { titlingIds.remove(conversationId) }
        }
    }

    suspend fun loadMessages(conversationId: Long): List<ChatMessage> {
        val conv = repo.getById(conversationId) ?: return emptyList()
        // 大对话的 messagesJson 可达 MB 级，JSONArray 解析别在主线程跑（原来在 LaunchedEffect=主线程里，会冻 UI=「加载慢」）。
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { try {
            val arr = JSONArray(conv.messagesJson)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                val tcArr = obj.optJSONArray("toolCalls")
                ChatMessage(
                    role = obj.getString("role"),
                    content = obj.optString("content", ""),
                    reasoning = if (obj.has("reasoning") && !obj.isNull("reasoning")) obj.getString("reasoning") else null,
                    extra = if (obj.has("extra") && !obj.isNull("extra")) obj.getString("extra") else null,
                    totalTokens = if (obj.has("totalTokens")) obj.optInt("totalTokens") else null,
                    promptTokens = if (obj.has("promptTokens")) obj.optInt("promptTokens") else null,
                    completionTokens = if (obj.has("completionTokens")) obj.optInt("completionTokens") else null,
                    tokensPerSec = if (obj.has("tokensPerSec")) obj.optDouble("tokensPerSec") else null,
                    model = if (obj.has("model")) obj.optString("model") else null,
                    toolCalls = if (tcArr != null && tcArr.length() > 0) {
                        (0 until tcArr.length()).map { j ->
                            val tcj = tcArr.getJSONObject(j)
                            ChatMessage.ToolCallMsg(
                                id = tcj.getString("id"),
                                name = tcj.getString("name"),
                                arguments = tcj.getString("arguments"),
                                extra = if (tcj.has("extra") && !tcj.isNull("extra")) tcj.getString("extra") else null
                            )
                        }
                    } else null,
                    toolCallId = if (obj.has("toolCallId") && !obj.isNull("toolCallId")) obj.getString("toolCallId") else null,
                    attachments = obj.optJSONArray("attachments")?.let { a -> (0 until a.length()).map { a.getString(it) } }
                )
            }
        } catch (e: Exception) {
            emptyList()
        } }
    }

    suspend fun clearMessages(conversationId: Long) {
        repo.saveMessages(conversationId, "[]")
        repo.saveBranches(conversationId, null)
    }

    /** 读会话分支树的原始 JSON（null=线性对话，无分支）。
     *  只读这一列：原来走 getById 会把 messagesJson（可达 MB 级）也一并拼回来，纯浪费。 */
    suspend fun loadBranches(conversationId: Long): String? =
        repo.getBranchesJson(conversationId)

    /** 存会话分支树的原始 JSON（传 null 清空=退回线性）。
     *  ⚠ 和 [saveMessages] 一样要过一遍敏感结果改写：分支树是**第二条落库路径**，
     *  只堵 messagesJson 的话，一旦这个会话有分支（重新生成一次就有），敏感正文照样明文进库、进备份。 */
    suspend fun saveBranches(conversationId: Long, json: String?) =
        repo.saveBranches(conversationId, com.arix.tool.SensitiveResultPolicy.redactBranchesJson(json))
}
