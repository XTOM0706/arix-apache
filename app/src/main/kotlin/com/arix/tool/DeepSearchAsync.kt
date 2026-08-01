package com.arix.tool

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * 深度搜索异步化：深搜慢，放后台跑，别卡住整个对话。工具立即返回「已在后台研究」，
 * 研究完成后把带引用的报告追加进当前对话，并发通知（用角色卡名+头像）。
 * 研究进度仍走 XSearchProgressBus（聊天里能看到进度条），用户可继续聊别的。
 */
object DeepSearchAsync {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appendMutex = kotlinx.coroutines.sync.Mutex()   // 串行化对话追加，防并发深搜互相覆盖

    fun start(context: Context, query: String, params: JSONObject) {
        val ctx = context.applicationContext
        val convId = ActiveChatContext.conversationId
        val cardId = ActiveChatContext.characterCardId   // start 时捕获，避免完成时用户已切卡→通知显示错卡
        scope.launch {
            val result = try { XSearchTool(ctx).execute(params) } catch (_: Exception) { null }
            val report = result?.content?.takeIf { it.isNotBlank() && result.isError.not() } ?: return@launch
            if (convId == null) return@launch
            try {
                appendMutex.withLock {
                    val convMgr = com.arix.app.ConversationManager(ctx)
                    val history = convMgr.loadMessages(convId).toMutableList()  // 锁内重新读，取最新
                    history.add(com.arix.cloudapi.model.ChatMessage("assistant", "【深度研究完成 · $query】\n$report"))
                    convMgr.saveMessages(convId, history)
                    // 会话树同步（有分支树时，否则加载以树为准会忽略这条）
                    try {
                        val tj = convMgr.loadBranches(convId)
                        if (tj != null) com.arix.app.MessageTree.fromJson(tj)?.let { t ->
                            t.commitActivePath(history); convMgr.saveBranches(convId, if (t.hasBranches()) t.toJson() else null)
                        }
                    } catch (_: Exception) {}
                }
                notify(ctx, convId, cardId, query, report)
            } catch (_: Exception) {}
        }
    }

    private suspend fun notify(ctx: Context, convId: Long, cardId: Long?, query: String, report: String) {
        // 应用在前台就不弹：报告已经追加进对话了，用户正看着
        if (com.arix.app.NotificationPrefs.suppressed(ctx)) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "深度研究", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply { description = "后台深度研究完成通知" })
            }
            val card = cardId?.let { runCatching { com.arix.app.CharacterCardManager(ctx).getById(it) }.getOrNull() }
            val avatar = com.arix.app.loadAvatarBitmap(ctx, card?.avatarPath)
            val open = android.content.Intent(ctx, com.arix.app.MainActivity::class.java).apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(com.arix.app.MainActivity.EXTRA_OPEN_CONV, convId)
            }
            val pi = android.app.PendingIntent.getActivity(ctx, (40000 + convId).toInt(), open,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_search)
                .setContentTitle(card?.name?.ifBlank { null } ?: "深度研究完成")
                .setContentText("「$query」的研究报告好了")
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(report.take(400)))
                .setAutoCancel(true).setContentIntent(pi)
                .apply { if (avatar != null) setLargeIcon(avatar) }
                .build()
            nm.notify(44000 + convId.toInt(), n)
        } catch (_: Exception) {}
    }

    private const val CHANNEL = "arix_deepsearch"
}
