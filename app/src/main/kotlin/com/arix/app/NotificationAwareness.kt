package com.arix.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.app.RemoteInput
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.ArrayDeque

/** 通知上的一个按钮。[hasReply]=这个按钮带输入框（RemoteInput），能直接注入文字回复。 */
data class NotifAction(val index: Int, val title: String, val hasReply: Boolean)

/**
 * 一条被捕获的通知。
 *
 * [key] 是系统给这条通知的唯一标识（sbn.key），**操作通知全靠它**；[id] 是给 AI 用的短号，
 * 因为 key 长得像 `0|com.tencent.mm|1234|null|10086`，让模型原样复述它纯属自找麻烦。
 */
data class NotifItem(
    val id: Int,
    val app: String,
    val pkg: String,
    val title: String,
    val text: String,
    val time: Long,
    val key: String,
    val actions: List<NotifAction>,
    val canOpen: Boolean,
    val clearable: Boolean,
)

/**
 * 通知感知：捕获系统里其它 App 弹出的通知（微信消息、日程、快递…），让 AI 能「知道你手机上发生了啥」，
 * 并且**能正经地操作它们**——按下通知自带的按钮、往回复框里注入文字、点开、清除。
 *
 * 为什么要存 key/actions 而不只是文字：通知那排按钮（回复/标记已读/接听…）本来就是可按的，
 * 带 RemoteInput 的回复按钮更是能直接注入文字发出去。以前这里只留了标题正文，
 * AI 想回消息就只剩「用无障碍去戳屏幕」这条下下策——那是在拿界面自动化模拟一件系统本就提供 API 的事。
 *
 * 读走 [NotificationStore] 的进程内环形缓存（历史，不落盘）；**操作**一律走 [XtomNotificationListener]
 * 的实时 `activeNotifications` 按 key 查——PendingIntent 只在通知还活着时有效，拿缓存里的旧对象去 send 是错的。
 * 需用户在系统里授予「通知使用权」。
 */
object NotificationStore {
    private const val CAP = 60
    private val buf = ArrayDeque<NotifItem>()
    private var nextId = 1

    @Synchronized fun add(app: String, pkg: String, title: String, text: String, time: Long,
                          key: String, actions: List<NotifAction>, canOpen: Boolean, clearable: Boolean): NotifItem {
        // 同一条通知更新（微信同一会话来新消息只是改内容，不是新 key）→ 复用短号，别让 id 表越滚越长
        val existing = buf.firstOrNull { it.key == key }
        val id = existing?.id ?: nextId++
        if (existing != null) buf.remove(existing)
        val item = NotifItem(id, app, pkg, title, text, time, key, actions, canOpen, clearable)
        buf.addFirst(item)
        while (buf.size > CAP) buf.removeLast()
        return item
    }

    @Synchronized fun recent(n: Int): List<NotifItem> = buf.take(n.coerceIn(1, CAP))

    @Synchronized fun byId(id: Int): NotifItem? = buf.firstOrNull { it.id == id }

    @Synchronized fun remove(key: String) { buf.removeAll { it.key == key } }

    @Synchronized fun clear() { buf.clear() }
}

object NotificationAwarenessPrefs {
    private const val PREF = "xtom_notif_aware"
    /** 捕获开关（用户已授系统权限后，仍可在 App 内暂停捕获）。默认开。 */
    fun enabled(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean("enabled", v).apply()

    /** 系统是否已授予「通知使用权」。 */
    fun hasAccess(c: Context): Boolean = try {
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(c).contains(c.packageName)
    } catch (_: Exception) { false }
}

class XtomNotificationListener : NotificationListenerService() {

    companion object {
        /** 连上时的服务实例；通知工具靠它拿实时通知与 PendingIntent。没连上=没授权/系统还没绑。 */
        @Volatile var instance: XtomNotificationListener? = null
            private set
    }

    /**
     * 连上就把当前通知栏里的东西**播种**进 store。
     *
     * 不能只设 instance 就完事：系统在内存紧张、App 更新、Doze 进出时会**例行**解绑重绑 listener，
     * 而 [onListenerDisconnected] 会清空 store。手表息屏一会儿就可能被重绑一次——用户抬腕问
     * 「我刚才有什么消息」，AI 却回「最近没有捕获到通知」，而通知栏里明明堆着一屏。
     * onNotificationPosted 只在**新**通知来时才叫，补不回这一屏。
     */
    override fun onListenerConnected() {
        instance = this
        try {
            if (!NotificationAwarenessPrefs.enabled(applicationContext)) return
            activeNotifications?.sortedBy { it.postTime }?.forEach { onNotificationPosted(it) }
        } catch (_: Exception) {}
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        try {
            if (!NotificationAwarenessPrefs.enabled(applicationContext)) return
            if (n.packageName == packageName) return          // 不记自己
            if (n.isOngoing) return                            // 跳过常驻/前台服务类通知
            val notif = n.notification ?: return
            val ex = notif.extras ?: return
            val title = (ex.getCharSequence(Notification.EXTRA_TITLE)
                ?: ex.getCharSequence(Notification.EXTRA_TITLE_BIG))?.toString()?.trim().orEmpty()
            val text = extractText(ex)
            if (title.isBlank() && text.isBlank()) return
            val app = try {
                val pm = applicationContext.packageManager
                pm.getApplicationLabel(pm.getApplicationInfo(n.packageName, 0)).toString()
            } catch (_: Exception) { n.packageName }
            NotificationStore.add(app, n.packageName, title, text, n.postTime, n.key,
                actionsOf(notif), notif.contentIntent != null, n.isClearable)
        } catch (_: Exception) {}
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 通知没了就从历史里摘掉：留着只会让 AI 拿着过期的 id 去操作，然后收到一句「这条已经不在了」
        sbn?.key?.let { NotificationStore.remove(it) }
    }

    /** 通知那排按钮。带 RemoteInput 的就是「能直接注入文字」的回复按钮。 */
    private fun actionsOf(n: Notification): List<NotifAction> =
        n.actions?.mapIndexed { i, a ->
            NotifAction(i, a.title?.toString().orEmpty(), a.remoteInputs?.isNotEmpty() == true)
        }?.filter { it.title.isNotBlank() } ?: emptyList()

    /** 尽量取全通知正文：消息类(微信/短信 MessagingStyle 逐条)＞多条汇总(inbox lines)＞大文本＞普通文本。 */
    private fun extractText(ex: Bundle): String {
        try {
            val arr = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (arr != null) {
                val msgs = Notification.MessagingStyle.Message.getMessagesFromBundleArray(arr)
                if (msgs.isNotEmpty()) return msgs.joinToString("\n") { m ->
                    @Suppress("DEPRECATION")
                    val who = m.sender?.toString()   // sender 是 API24+；senderPerson 要 API28,minSdk26 旧机 NoSuchMethodError
                    (if (!who.isNullOrBlank()) "$who：" else "") + (m.text?.toString() ?: "")
                }.trim()
            }
        } catch (_: Exception) {}
        ex.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.let { lines ->
            if (lines.isNotEmpty()) return lines.joinToString("\n") { it.toString() }.trim()
        }
        val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        val small = ex.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        return (big ?: small ?: "").trim()
    }

    // ============================================================
    // 操作能力 —— 通知工具调这些。一律按 key 查**实时**通知：PendingIntent 只在通知还活着时有效。
    // ============================================================

    /** 按 key 找当前还活着的通知。找不到=已被用户划掉/被 App 撤回。 */
    private fun liveByKey(key: String): StatusBarNotification? = try {
        activeNotifications?.firstOrNull { it.key == key }
    } catch (_: Exception) { null }   // 未连接时 getActiveNotifications 抛 SecurityException

    /** 往通知的回复框注入文字并发出。这是微信/短信那类「直接回复」按钮背后的正经 API。 */
    fun reply(key: String, text: String): Result<String> {
        val sbn = liveByKey(key) ?: return Result.failure(IllegalStateException("这条通知已经不在了（被划掉或已撤回）"))
        val action = sbn.notification?.actions?.firstOrNull { it.remoteInputs?.isNotEmpty() == true }
            ?: return Result.failure(IllegalStateException("这条通知没有「直接回复」按钮"))
        val inputs = action.remoteInputs ?: return Result.failure(IllegalStateException("这条通知没有可注入的输入框"))
        return try {
            val intent = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
            RemoteInput.addResultsToIntent(inputs, intent, results)
            // 标明「这是用户手打的自由文本」：不标的话部分 App 会把这条回复当成非用户输入丢掉。API 28+ 才有。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                RemoteInput.setResultsSource(intent, RemoteInput.SOURCE_FREE_FORM_INPUT)
            action.actionIntent.send(applicationContext, 0, intent)
            Result.success("已通过「${action.title}」回复：$text")
        } catch (e: PendingIntent.CanceledException) {
            Result.failure(IllegalStateException("回复通道已失效（通知被撤回）"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 按下通知上第 [index] 个按钮（不带输入框的那种：标记已读/接听/稍后提醒…）。 */
    fun press(key: String, index: Int): Result<String> {
        val sbn = liveByKey(key) ?: return Result.failure(IllegalStateException("这条通知已经不在了（被划掉或已撤回）"))
        val actions = sbn.notification?.actions
        if (actions.isNullOrEmpty()) return Result.failure(IllegalStateException("这条通知没有按钮"))
        val a = actions.getOrNull(index)
            ?: return Result.failure(IllegalStateException("没有第 $index 个按钮（这条只有 ${actions.size} 个）"))
        if (a.remoteInputs?.isNotEmpty() == true)
            return Result.failure(IllegalStateException("「${a.title}」是回复按钮，请用 action=reply 并给出 text"))
        return try {
            a.actionIntent.send()
            Result.success("已按下「${a.title}」")
        } catch (e: PendingIntent.CanceledException) {
            Result.failure(IllegalStateException("这个按钮已失效（通知被撤回）"))
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 点开通知本体（等于用户点了这条通知，跳到对应 App 界面）。 */
    fun open(key: String): Result<String> {
        val sbn = liveByKey(key) ?: return Result.failure(IllegalStateException("这条通知已经不在了（被划掉或已撤回）"))
        val ci = sbn.notification?.contentIntent
            ?: return Result.failure(IllegalStateException("这条通知点不开（没有 contentIntent）"))
        return try {
            ci.send()
            if (sbn.notification.flags and Notification.FLAG_AUTO_CANCEL != 0) cancelNotification(key)
            Result.success("已点开这条通知")
        } catch (e: PendingIntent.CanceledException) {
            Result.failure(IllegalStateException("这条通知已失效"))
        } catch (e: Exception) { Result.failure(e) }
    }

    /** 清除一条通知。 */
    fun dismiss(key: String): Result<String> = try {
        cancelNotification(key)
        NotificationStore.remove(key)
        Result.success("已清除这条通知")
    } catch (e: Exception) { Result.failure(e) }

    /** 清除全部可清除的通知。 */
    fun dismissAll(): Result<String> = try {
        cancelAllNotifications()
        Result.success("已清空通知栏")
    } catch (e: Exception) { Result.failure(e) }

    /** 这条通知现在还在不在（决定 list 里要不要标「可操作」）。 */
    fun isLive(key: String): Boolean = liveByKey(key) != null

    // 撤权 / 服务断开时清空缓存，别把历史通知留在内存里
    override fun onListenerDisconnected() { instance = null; NotificationStore.clear() }
}
