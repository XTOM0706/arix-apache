package com.arix.tool

import android.content.Context
import android.content.Intent
import com.arix.app.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ============================================================
// 跨平台消息网关 message_gateway —— 一个工具多用：把一段消息转发出去。
//   share   = ACTION_SEND 调起目标 App（微信/QQ/TG/邮件…，可指定包名）由用户点发送
//   webhook = POST 到用户配置的 URL（企业微信/飞书/Discord/Slack/自建机器人）自动送达
//   add_webhook / list_webhooks / remove_webhook = 管理 webhook 列表（名字→URL，存自带 prefs）
// 纯 HttpURLConnection + 系统 Intent，无第三方代码；不改共享文件。
// ============================================================

/** webhook 列表（名字→URL）存自带 SharedPreferences，键为 JSON map。 */
object MessageGatewayPrefs {
    private const val P = "xtom_message_gateway"
    private const val K = "webhooks"
    private fun p(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)

    /** 读取全部 webhook：名字 → URL。 */
    fun all(c: Context): Map<String, String> {
        val raw = p(c).getString(K, "") ?: ""
        if (raw.isBlank()) return emptyMap()
        return try {
            val o = JSONObject(raw)
            buildMap { o.keys().forEach { k -> put(k, o.optString(k)) } }
        } catch (_: Exception) { emptyMap() }
    }

    fun url(c: Context, name: String): String? = all(c)[name.trim()]

    fun put(c: Context, name: String, url: String) {
        val o = JSONObject()
        all(c).forEach { (k, v) -> o.put(k, v) }
        o.put(name.trim(), url.trim())
        p(c).edit().putString(K, o.toString()).apply()
    }

    fun remove(c: Context, name: String): Boolean {
        val cur = all(c)
        if (!cur.containsKey(name.trim())) return false
        val o = JSONObject()
        cur.forEach { (k, v) -> if (k != name.trim()) o.put(k, v) }
        p(c).edit().putString(K, o.toString()).apply()
        return true
    }
}

class MessageGatewayTool(private val context: Context) : Tool {
    override val name = "message_gateway"
    override val description = tr("跨平台消息网关（一个工具多用）：把一段消息转发出去。channel=share 调起目标 App(微信/QQ/Telegram/邮件…，target 指定或直接给包名)由用户点发送；channel=webhook 把消息 POST 到已配置的机器人 URL(企业微信/飞书/Discord/Slack/自建)自动送达，webhook 传配置里的名字。管理：add_webhook 加一条(name+url)、list_webhooks 看已配、remove_webhook 删一条。webhook URL 只存本机，不上传。")

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("channel", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("share", "webhook", "add_webhook", "list_webhooks", "remove_webhook")))
                put("description", tr("share=调起 App 分享；webhook=POST 到机器人 URL；add_webhook/list_webhooks/remove_webhook=管理 webhook 列表"))
            })
            put("message", JSONObject().apply {
                put("type", "string")
                put("description", tr("要转发的消息正文（share / webhook 必填）"))
            })
            put("target", JSONObject().apply {
                put("type", "string")
                put("description", tr("share 的目标：wechat/moments/qq/telegram/email/system(系统分享面板)，或直接给 App 包名如 org.telegram.messenger。留空=system 面板"))
            })
            put("subject", JSONObject().apply {
                put("type", "string")
                put("description", tr("share 的标题：邮件主题 / 部分 App 会用作标题（可选）"))
            })
            put("to", JSONObject().apply {
                put("type", "string")
                put("description", tr("share=email 时的收件邮箱（可选）"))
            })
            put("webhook", JSONObject().apply {
                put("type", "string")
                put("description", tr("channel=webhook 时，要发往的 webhook 名字（先用 add_webhook 配好；list_webhooks 可查）"))
            })
            put("name", JSONObject().apply {
                put("type", "string")
                put("description", tr("add_webhook/remove_webhook 用：webhook 的名字，如『工作群』『我的Discord』"))
            })
            put("url", JSONObject().apply {
                put("type", "string")
                put("description", tr("add_webhook 用：webhook 的完整 URL（企业微信/飞书/Discord/Slack 机器人地址或自建端点）"))
            })
            put("format", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("auto", "wecom", "feishu", "discord", "slack", "text")))
                put("description", tr("channel=webhook 的载荷格式，默认 auto 按 URL 自动判断；text=纯文本 POST；其余按对应平台的 JSON 结构"))
            })
        })
        put("required", JSONArray(listOf("channel")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        when (params.optString("channel").trim()) {
            "add_webhook" -> {
                val n = params.optString("name").trim()
                val u = params.optString("url").trim()
                if (n.isBlank() || u.isBlank())
                    return@withContext ToolResult(tr("请提供 name 和 url。如 name=工作群，url=企业微信机器人地址。"), isError = true)
                if (!u.startsWith("http://") && !u.startsWith("https://"))
                    return@withContext ToolResult(tr("url 需以 http:// 或 https:// 开头。"), isError = true)
                MessageGatewayPrefs.put(context, n, u)
                ToolResult(tr("已保存 webhook「") + n + tr("」。之后 channel=webhook、webhook=") + n + tr(" 即可发送。"))
            }
            "list_webhooks" -> {
                val m = MessageGatewayPrefs.all(context)
                if (m.isEmpty()) return@withContext ToolResult(tr("还没有配置任何 webhook。用 channel=add_webhook 加一条（name + url）。"))
                // 只显示名字与脱敏后的 URL，避免把完整密钥回显到对话里
                ToolResult(tr("已配置的 webhook：\n") + m.entries.joinToString("\n") { "• ${it.key} → ${maskUrl(it.value)}" })
            }
            "remove_webhook" -> {
                val n = params.optString("name").trim()
                if (n.isBlank()) return@withContext ToolResult(tr("请提供要删除的 webhook name。"), isError = true)
                if (MessageGatewayPrefs.remove(context, n)) ToolResult(tr("已删除 webhook「") + n + "」")
                else ToolResult(tr("没有名为「") + n + tr("」的 webhook。"), isError = true)
            }
            "share" -> doShare(params)
            "webhook" -> doWebhook(params)
            else -> ToolResult(tr("未知 channel。可选：share / webhook / add_webhook / list_webhooks / remove_webhook。"), isError = true)
        }
    }

    // ---- 通道①：ACTION_SEND 调起目标 App（由用户点发送，不会自动发送）----
    private fun doShare(params: JSONObject): ToolResult {
        val message = params.optString("message").trim()
        if (message.isBlank()) return ToolResult(tr("请提供 message 消息内容。"), isError = true)
        val target = params.optString("target").trim().ifBlank { "system" }
        val subject = params.optString("subject").trim()
        return try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, message)
                if (subject.isNotBlank()) putExtra(Intent.EXTRA_SUBJECT, subject)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            val label: String
            when (target.lowercase()) {
                "system", "chooser" -> {
                    val chooser = Intent.createChooser(intent, subject.ifBlank { tr("分享到…") })
                    chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(chooser); label = tr("系统分享面板")
                }
                "wechat", "weixin" -> { intent.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareImgUI"); context.startActivity(intent); label = tr("微信") }
                "moments", "wechat_moments" -> {
                    intent.setClassName("com.tencent.mm", "com.tencent.mm.ui.tools.ShareToTimeLineUI")
                    intent.putExtra("Kdescription", message)
                    context.startActivity(intent); label = tr("微信朋友圈")
                }
                "qq" -> { intent.setClassName("com.tencent.mobileqq", "com.tencent.mobileqq.activity.JumpActivity"); context.startActivity(intent); label = tr("QQ") }
                "telegram", "tg" -> { intent.setPackage("org.telegram.messenger"); context.startActivity(intent); label = "Telegram" }
                "email", "mail" -> {
                    intent.setPackage(null)
                    val to = params.optString("to").trim()
                    if (to.isNotBlank()) intent.putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                    context.startActivity(Intent.createChooser(intent, tr("发送邮件")).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK })
                    label = tr("邮件")
                }
                else -> { intent.setPackage(target); context.startActivity(intent); label = target } // 直接给包名
            }
            ToolResult(tr("已调起 ") + label + tr(" 分享，请在弹出的界面里点发送。"))
        } catch (e: Exception) {
            ToolResult(tr("调起分享失败（目标未安装或不支持文本分享）：") + (e.message ?: target) + tr("。可改用 target=system 走系统分享面板。"), isError = true)
        }
    }

    // ---- 通道②：POST 到已配置的 webhook（自动送达）----
    private fun doWebhook(params: JSONObject): ToolResult {
        val message = params.optString("message").trim()
        if (message.isBlank()) return ToolResult(tr("请提供 message 消息内容。"), isError = true)
        val whName = params.optString("webhook").trim()
        if (whName.isBlank()) return ToolResult(tr("请用 webhook 参数指定要发往的名字（先 add_webhook 配好，list_webhooks 可查）。"), isError = true)
        val url = MessageGatewayPrefs.url(context, whName)
            ?: return ToolResult(tr("没有名为「") + whName + tr("」的 webhook。请先用 channel=add_webhook 配置，或 list_webhooks 查看已配。"), isError = true)

        val fmt = params.optString("format").trim().ifBlank { "auto" }.lowercase()
        val resolved = if (fmt == "auto") detectFormat(url) else fmt
        val (body, contentType) = buildPayload(resolved, message)
        return try {
            val (code, resp) = httpPost(url, body, contentType)
            if (code in 200..299) ToolResult(tr("已通过 webhook「") + whName + tr("」发送成功。"))
            else ToolResult(tr("webhook「") + whName + tr("」发送失败，服务返回 HTTP ") + code + (if (resp.isNotBlank()) "：" + resp.take(200) else "") + tr("。请检查 URL / 机器人是否有效，或用 format 指定正确的载荷格式。"), isError = true)
        } catch (e: Exception) {
            ToolResult(tr("webhook「") + whName + tr("」发送异常：") + (e.message ?: e.toString()), isError = true)
        }
    }

    /** 按 URL 主机名猜测机器人平台的载荷格式。 */
    private fun detectFormat(url: String): String {
        val u = url.lowercase()
        return when {
            u.contains("qyapi.weixin.qq.com") -> "wecom"
            u.contains("open.feishu.cn") || u.contains("open.larksuite.com") -> "feishu"
            u.contains("discord.com") || u.contains("discordapp.com") -> "discord"
            u.contains("hooks.slack.com") -> "slack"
            else -> "text"
        }
    }

    /** 生成各平台机器人的 JSON 载荷；text=纯文本。返回 (body, contentType)。 */
    private fun buildPayload(format: String, message: String): Pair<String, String> = when (format) {
        "wecom" -> JSONObject().apply { put("msgtype", "text"); put("text", JSONObject().apply { put("content", message) }) }.toString() to "application/json"
        "feishu" -> JSONObject().apply { put("msg_type", "text"); put("content", JSONObject().apply { put("text", message) }) }.toString() to "application/json"
        "discord" -> JSONObject().apply { put("content", message) }.toString() to "application/json"
        "slack" -> JSONObject().apply { put("text", message) }.toString() to "application/json"
        else -> message to "text/plain; charset=utf-8"
    }

    private fun httpPost(url: String, body: String, contentType: String): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = "POST"
            c.connectTimeout = 10000; c.readTimeout = 15000
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.setRequestProperty("User-Agent", "Arix-MessageGateway/1.0")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            val stream = if (code in 200..299) c.inputStream else c.errorStream
            val resp = stream?.bufferedReader()?.use { it.readText() } ?: ""
            code to resp
        } finally { c.disconnect() }
    }

    /** 脱敏回显 URL：保留 host + 路径开头，隐去 key/token 段，避免泄露到对话历史。 */
    private fun maskUrl(url: String): String = try {
        val u = URL(url)
        val host = u.host
        val path = u.path.take(12)
        "${u.protocol}://$host$path…"
    } catch (_: Exception) { url.take(24) + "…" }
}
