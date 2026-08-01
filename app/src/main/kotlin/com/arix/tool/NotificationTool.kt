package com.arix.tool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机通知：读 / 回复 / 按按钮 / 点开 / 清除 / 自己发一条 —— 一个工具全包。
 *
 * 合并自原先的 `read_notifications`(只读) 与 `notification`(只发)。拆成两个的后果是：
 * 「回复通知」这件最常用的事**两个工具都不管**，AI 只好退回 ui_control 去无障碍点屏幕——
 * 而通知那排按钮本来就是可按的，带 RemoteInput 的回复按钮更能直接把文字注入发出去。
 * 拿界面自动化模拟一件系统本就提供 API 的事，既慢又脆，还得让用户开无障碍。
 *
 * 实际操作在 [com.arix.app.XtomNotificationListener]（按 key 查实时通知拿 PendingIntent）。
 * 需用户在系统里授予「通知使用权」。
 */
class NotificationTool(private val context: Context) : Tool {
    override val name = "notification"

    /**
     * action=list 读的是**所有 App 的通知正文**，reply 更是直接以用户身份把话发出去。
     * 见 [AndroidPermissionLevel.PRIVATE]。（[ephemeralResult] 管的是「读到的别落库」，
     * 这一档管的是「读之前先问一声」，两件事都要。）
     */
    override val permissionLevel = AndroidPermissionLevel.PRIVATE

    override val description =
        "手机通知一条龙：action=list 读最近通知(默认)｜reply 直接回复某条(微信/短信等带回复框的通知，文字直接注入发出)｜" +
        "press 按下通知上的按钮(标记已读/接听/稍后提醒…)｜open 点开通知跳到对应App｜dismiss 清除某条｜dismiss_all 清空通知栏｜send 自己发一条通知。" +
        "先 list 拿到每条的 id 和它有哪些按钮，再按 id 操作。" +
        "回复消息/操作通知一律用本工具——通知的按钮是系统正经 API，别用 ui_control 去点屏幕。需通知使用权。"

    /** action=list 的结果是**所有 App 的通知正文**（微信私聊、银行短信、验证码…），一整片隐私，
     *  原样落库就跟着备份上云。见 [SensitiveResultPolicy]。
     *  整个工具标掉、不按 action 区分：落库那一步只有工具名和参数串，要在那儿判「action 缺省=list」
     *  就得复制本工具的默认值逻辑，这边一改那边就悄悄失效。多标掉的只是 reply/press 那几句一次性回执
     *  （「已回复」「已按下」），而漏标一次就是整屏通知明文进备份。 */
    override val ephemeralResult = true

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("list", "reply", "press", "open", "dismiss", "dismiss_all", "send")))
                put("description", "做什么，默认 list")
            })
            put("id", JSONObject().apply { put("type", "integer"); put("description", "通知短号（list 给出）。reply/press/open/dismiss 必填") })
            put("text", JSONObject().apply { put("type", "string"); put("description", "reply 时要回复的内容") })
            put("button", JSONObject().apply { put("type", "string"); put("description", "press 时要按的按钮：按钮名(如「标记为已读」)或序号(0 开始)") })
            put("count", JSONObject().apply { put("type", "integer"); put("description", "list 读最近几条，默认 10") })
            put("title", JSONObject().apply { put("type", "string"); put("description", "send 时的通知标题") })
            put("content", JSONObject().apply { put("type", "string"); put("description", "send 时的通知内容") })
        })
        put("required", JSONArray(listOf<String>()))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = params.optString("action", "list").ifBlank { "list" }

        // send 是往通知栏发自己的通知，用的是 POST_NOTIFICATIONS，跟「通知使用权」是两回事，先岔开
        if (action == "send") return@withContext send(params)

        if (!com.arix.app.NotificationAwarenessPrefs.hasAccess(context))
            return@withContext ToolResult(
                "没有通知使用权，读不到也操作不了手机通知。可以调 request_permission(permission=\"notification_listener\") 让用户授权。",
                isError = true)
        if (!com.arix.app.NotificationAwarenessPrefs.enabled(context))
            return@withContext ToolResult("通知捕获在设置里被暂停了，请用户到「权限」页重新打开「通知感知」。", isError = true)

        val svc = com.arix.app.XtomNotificationListener.instance
            ?: return@withContext ToolResult("通知监听服务还没连上（刚授权的话稍等一下，或让用户重开一次「通知使用权」开关）。", isError = true)

        when (action) {
            "list" -> list(params)
            "dismiss_all" -> svc.dismissAll().toToolResult()
            "reply", "press", "open", "dismiss" -> {
                val item = params.optInt("id", -1).takeIf { it >= 0 }?.let { com.arix.app.NotificationStore.byId(it) }
                    ?: return@withContext ToolResult("请先 action=list 拿到通知的 id，再用它操作（没有 id=${params.optInt("id", -1)} 的通知）", isError = true)
                when (action) {
                    "reply" -> {
                        val text = params.optString("text", "").trim()
                        if (text.isBlank()) return@withContext ToolResult("回复内容不能为空（text）", isError = true)
                        svc.reply(item.key, text).toToolResult()
                    }
                    "press" -> press(svc, item, params.optString("button", ""))
                    "open" -> svc.open(item.key).toToolResult()
                    else -> svc.dismiss(item.key).toToolResult()
                }
            }
            else -> ToolResult("未知 action：$action", isError = true)
        }
    }

    private fun list(params: JSONObject): ToolResult {
        val list = com.arix.app.NotificationStore.recent(params.optInt("count", 10))
        if (list.isEmpty()) return ToolResult("最近没有捕获到通知")
        val svc = com.arix.app.XtomNotificationListener.instance
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return ToolResult(list.joinToString("\n") { item ->
            val body = listOf(item.title, item.text).filter { it.isNotBlank() }.joinToString("：")
            val live = svc?.isLive(item.key) == true
            val sb = StringBuilder("#${item.id} [${fmt.format(item.time)}] ${item.app}｜$body")
            if (!live) sb.append("　(已消失，操作不了)")
            else if (item.actions.isNotEmpty()) {
                // 把按钮摊开给 AI 看：它得知道「有个叫『回复』的按钮能注入文字」才不会想着去点屏幕
                sb.append("\n    按钮：").append(item.actions.joinToString("、") {
                    if (it.hasReply) "「${it.title}」(可直接回复:action=reply)" else "「${it.title}」(序号${it.index})"
                })
            }
            sb.toString()
        } + "\n\n（用 action=reply/press/open/dismiss + id=# 操作上面任意一条）")
    }

    /** press 支持按钮名或序号：模型更愿意写「标记为已读」而不是数 index，两种都收。 */
    private fun press(svc: com.arix.app.XtomNotificationListener, item: com.arix.app.NotifItem, button: String): ToolResult {
        if (item.actions.isEmpty()) return ToolResult("#${item.id} 这条通知没有按钮", isError = true)
        val b = button.trim()
        val idx = when {
            b.isBlank() && item.actions.size == 1 -> item.actions[0].index   // 只有一个按钮就不用问了
            b.isBlank() -> return ToolResult("这条有多个按钮，请指明 button：${item.actions.joinToString("、") { "「${it.title}」" }}", isError = true)
            b.toIntOrNull() != null -> b.toInt()
            else -> item.actions.firstOrNull { it.title.equals(b, true) }?.index
                ?: item.actions.firstOrNull { it.title.contains(b, true) }?.index
                ?: return ToolResult("没有叫「$b」的按钮，这条只有：${item.actions.joinToString("、") { "「${it.title}」" }}", isError = true)
        }
        return svc.press(item.key, idx).toToolResult()
    }

    private var notifId = 1000

    /** 发一条自己的通知（原 notification 工具的能力，原样保留）。 */
    private fun send(params: JSONObject): ToolResult {
        val title = params.optString("title", "")
        val content = params.optString("content", "")
        if (title.isBlank() && content.isBlank()) return ToolResult("send 需要 title 或 content", isError = true)
        // 次级开关（默认关）：连 AI 主动发的这条也按「前台不打扰」拦。拦了要如实告诉 AI，别让它以为发出去了
        if (com.arix.app.NotificationPrefs.suppressedForTool(context))
            return ToolResult("用户设置了「应用在前台时不弹通知」且勾了连工具通知一起拦，这条没有弹出。用户正看着屏幕，直接在回复里说就行。")
        return try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                nm.createNotificationChannel(android.app.NotificationChannel(
                    "arix_tools", "Arix 工具", android.app.NotificationManager.IMPORTANCE_DEFAULT))
            }
            val n = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                android.app.Notification.Builder(context, "arix_tools")
            } else {
                @Suppress("DEPRECATION") android.app.Notification.Builder(context)
            }.setContentTitle(title).setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true).build()
            nm.notify(notifId++, n)
            if (!androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled())
                ToolResult("通知已发出，但系统当前不允许 Arix 发通知，用户看不到。可调 request_permission(permission=\"post_notifications\") 让用户开。")
            else ToolResult("通知已发送")
        } catch (e: Exception) { ToolResult("发送通知失败：${e.message}", isError = true) }
    }
}

/** Listener 那边用 Result 表达成功/失败原因，这里统一翻成 ToolResult——失败原因必须原样带给 AI，它才知道能不能换招。 */
private fun Result<String>.toToolResult(): ToolResult =
    fold({ ToolResult(it) }, { ToolResult(it.message ?: it.toString(), isError = true) })
