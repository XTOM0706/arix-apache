package com.arix.tool

import com.arix.app.CloudApiConfigManager
import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// 子 agent 执行进度（供聊天里显示"运行中 done/total"，别让用户以为卡住）
data class SubAgentProgress(val total: Int, val done: Int, val tasks: List<String>)
object SubAgentProgressBus {
    val state = kotlinx.coroutines.flow.MutableStateFlow<SubAgentProgress?>(null)
    fun update(p: SubAgentProgress) { state.value = p }
    fun clear() { state.value = null }
}

// 子 agent 执行期间标记：内部工具(深度搜索等)的进度气泡不再占主对话，只显示子 agent 自己的进度
object SubAgentContext {
    private val depth = java.util.concurrent.atomic.AtomicInteger(0)
    val active: Boolean get() = depth.get() > 0
    fun enter() { depth.incrementAndGet() }
    fun exit() { depth.decrementAndGet() }
}

// ============================================================
// create_agent —— AI 创建子 agent 并行处理独立子任务
// 每个子 agent = 一个独立的工具调用循环（复用 ToolManager 已注册工具），
// 各自跑完后汇总。用来把复杂工作拆成可并行的部分。
// ============================================================
class SubAgentTool(private val context: android.content.Context) : Tool {

    override val name = "create_agent"
    override val description = "创建子 agent 并行处理多个独立子任务（最多12个）。它们在后台跑、不卡当前对话——工具会立即返回，你照常回复用户（告诉他你在处理），全部跑完后汇总会作为一条新消息自动送达。子 agent 隔离运行看不到主对话，需要的背景资料/约束用 context 参数传给它们。适合把复杂活拆成可并行的部分。"

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("tasks", JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply { put("type", "string") })
                put("description", "并行处理的子任务列表，每个字符串是一个独立任务，最多 12 个")
            })
            put("context", JSONObject().apply {
                put("type", "string")
                put("description", "给所有子 agent 的共享背景上下文（可选）：它们隔离运行、看不到主对话，把它们需要知道的背景/资料/约束写这里注入。")
            })
        })
        put("required", JSONArray(listOf("tasks")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val arr = params.optJSONArray("tasks")
        val tasks = if (arr != null) (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { t -> t.isNotBlank() } } else emptyList()
        if (tasks.isEmpty()) return@withContext ToolResult("请提供 tasks（要并行处理的子任务列表）", isError = true)

        val cfgMgr = CloudApiConfigManager(context)
        val config = cfgMgr.getConfigForPurpose("agent") ?: cfgMgr.getActiveConfig()
            ?: return@withContext ToolResult("需要一个模型：请在配置页激活一个对话模型。", isError = true)

        val limited = tasks.take(12)   // 上限提到 12
        val sharedCtx = params.optString("context", "")   // AI 给子 agent 的共享背景
        val convId = ActiveChatContext.conversationId
        val cardId = ActiveChatContext.characterCardId
        // ⚠ 必须**在这里**取调用方：下面 bgScope.launch 是另一个协程，CallerContext 带不过去。
        // 取不到就是 Ai（与从前一致）。见 CallerContext 的说明。
        val caller = currentToolCaller()
        // 后台跑，别卡主对话：立即返回，子 agent 干完后把汇总追加进对话并通知
        bgScope.launch { runAndDeliver(context.applicationContext, limited, config, sharedCtx, convId, cardId, caller) }
        ToolResult("已派 ${limited.size} 个子 agent 后台处理，你可以继续聊别的——它们干完后我把汇总发给你。")
    }

    private suspend fun runAndDeliver(ctx: android.content.Context, tasks: List<String>, config: CloudApiConfig, sharedCtx: String, convId: Long?, cardId: Long?, caller: ToolCaller) {
        val labels = tasks.map { it.take(30) }
        val done = java.util.concurrent.atomic.AtomicInteger(0)
        SubAgentContext.enter()
        SubAgentProgressBus.update(SubAgentProgress(tasks.size, 0, labels))
        val combined = try {
            val results = coroutineScope {
                tasks.mapIndexed { i, t ->
                    async {
                        val r = try { runSubAgent(config, t, sharedCtx.ifBlank { null }, caller = caller) }
                            catch (c: kotlinx.coroutines.CancellationException) { throw c }
                            catch (e: Exception) { "（子任务异常：${e.message}）" }
                        SubAgentProgressBus.update(SubAgentProgress(tasks.size, done.incrementAndGet(), labels))
                        i to r
                    }
                }.awaitAll()
            }.sortedBy { it.first }
            buildString {
                append("【子 agent 汇总 · ${results.size} 个完成】\n\n")
                results.forEach { (i, r) -> append("〔子 agent ${i + 1}〕").append(tasks[i].take(60)).append("\n").append(r.take(2000)).append("\n\n") }
            }.trim()
        } catch (_: Exception) { null } finally { SubAgentProgressBus.clear(); SubAgentContext.exit() }
        if (combined.isNullOrBlank() || convId == null) return
        try {
            deliverMutex.withLock {
                val convMgr = com.arix.app.ConversationManager(ctx)
                val history = convMgr.loadMessages(convId).toMutableList()
                history.add(com.arix.cloudapi.model.ChatMessage("assistant", combined))
                convMgr.saveMessages(convId, history)
                try {
                    val tj = convMgr.loadBranches(convId)
                    if (tj != null) com.arix.app.MessageTree.fromJson(tj)?.let { t -> t.commitActivePath(history); convMgr.saveBranches(convId, if (t.hasBranches()) t.toJson() else null) }
                } catch (_: Exception) {}
            }
            notify(ctx, convId, cardId, tasks.size)
        } catch (_: Exception) {}
    }

    private suspend fun notify(ctx: android.content.Context, convId: Long, cardId: Long?, n: Int) {
        // 应用在前台就不弹：结果已经追加进对话了，用户正看着
        if (com.arix.app.NotificationPrefs.suppressed(ctx)) return
        try {
            val nm = ctx.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val ch = "arix_subagent"
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(ch) == null)
                nm.createNotificationChannel(android.app.NotificationChannel(ch, "子 Agent", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply { description = "后台子 agent 完成通知" })
            val card = cardId?.let { runCatching { com.arix.app.CharacterCardManager(ctx).getById(it) }.getOrNull() }
            val avatar = com.arix.app.loadAvatarBitmap(ctx, card?.avatarPath)
            val open = android.content.Intent(ctx, com.arix.app.MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(com.arix.app.MainActivity.EXTRA_OPEN_CONV, convId)
            }
            val pi = android.app.PendingIntent.getActivity(ctx, (45000 + convId).toInt(), open, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val notif = androidx.core.app.NotificationCompat.Builder(ctx, ch)
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle(card?.name?.ifBlank { null } ?: "子 Agent 完成")
                .setContentText("$n 个子任务处理完了")
                .setAutoCancel(true).setContentIntent(pi)
                .apply { if (avatar != null) setLargeIcon(avatar) }
                .build()
            nm.notify(45000 + convId.toInt(), notif)
        } catch (_: Exception) {}
    }

    // 单个子 agent：独立的工具调用循环（无 UI），最多 5 轮
    private suspend fun runSubAgent(config: CloudApiConfig, task: String, context: String?, maxRounds: Int = 5, caller: ToolCaller = ToolCaller.Ai): String {
        val client = CloudApiClient(config)
        val userContent = if (context.isNullOrBlank()) task else "【背景上下文】\n${context.take(4000)}\n\n【你的任务】\n$task"
        val msgs = mutableListOf(ChatMessage("user", userContent))
        var last = ""
        for (round in 0 until maxRounds) {
            currentCoroutineContext().ensureActive()   // STOP 取消后，本轮开始前就停，别再空跑
            var content = ""
            // 剔除 create_agent（防递归）和 ask_user（子 agent 无 UI，问用户只会干等超时）
            val tools = JSONArray()
            val allTools = ToolManager.getToolsJson()
            for (i in 0 until allTools.length()) {
                val t = allTools.optJSONObject(i)
                val n = t?.optJSONObject("function")?.optString("name")
                // create_agent 防递归；ask_user/todo 无 UI，子 agent 调了也没意义
                if (t != null && n != "create_agent" && n != "ask_user" && n != "todo") tools.put(t)
            }
            val result = client.streamChat(
                msgs.toList(), SUB_SYS, 0, null,
                tools = if (tools.length() > 0) tools else null,
                onReasoningChunk = {}, onContentChunk = { content += it }
            )
            if (result.error != null) return "（子任务出错：${result.error}）"
            last = content.ifEmpty { result.fullContent }
            if (result.toolCalls.isEmpty()) return last
            val toolCallMsgs = result.toolCalls.map { ChatMessage.ToolCallMsg(it.id, it.name, it.arguments, it.extra) }
            msgs.add(ChatMessage("assistant", last, null, null, null, null, null, toolCalls = toolCallMsgs))
            for (tc in result.toolCalls) {
                currentCoroutineContext().ensureActive()   // 每个工具执行前查一次取消
                // caller 沿用外层：子 agent 是替谁跑的，内部这些调用就该按谁的权限走（别一律洗成 AI，见 CallerContext）
                val call = ToolCall(tc.id, tc.name, try { JSONObject(tc.arguments) } catch (_: Exception) { JSONObject() }, caller = caller)
                val r = ToolManager.execute(call)
                // 超长结果落盘 + 只内联头尾（答案常在尾部），同主循环
                // ⚠ 这个函数的形参 context 是 String（子任务上下文），别写成它——要的是 Android Context
                msgs.add(ChatMessage("tool", ToolOutputStore.forModel(this@SubAgentTool.context, tc.name, r.content), toolCallId = tc.id))
            }
        }
        // 轮数用尽：最后一轮可能停在工具调用前，补一次无工具归纳，别返回半成品
        var summary = ""
        try {
            client.streamChat(msgs.toList(), SUB_SYS, 0, null, tools = null, onReasoningChunk = {}, onContentChunk = { summary += it })
        } catch (_: Exception) {}
        return summary.ifBlank { last }
    }

    companion object {
        private val bgScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
        private val deliverMutex = kotlinx.coroutines.sync.Mutex()
        private const val SUB_SYS = "你是一个子 agent，独立完成分配给你的这一个任务。需要时调用工具查资料或执行操作，拿到足够信息后直接给结论，简洁准确，不要复述任务。"
    }
}
