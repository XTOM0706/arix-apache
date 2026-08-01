package com.arix.tool

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * send_intent —— 发一条任意 Intent（启动界面）或系统广播。
 *
 * 为什么是**单独一个工具**而不是并进 app_launch：这两件事的危险程度差着一个量级。
 * `app_launch` 是 STANDARD 级、默认自动放行（「打开微信」不该天天问）；而任意 Intent 能带 action/data/extras/
 * component 打到任何一个导出组件上——那是 App 之间的整个攻击面（触发支付页、往别的 App 塞数据、
 * 唤起隐藏功能）。`permissionLevel` 是**按工具**定的，同一个工具里放不下两种等级，所以必须拆开：
 * 本工具 [AndroidPermissionLevel.DEBUGGER]（默认策略 ASK，且**永远不进模型自动审批**——那条只放行 STANDARD 级），
 * 所在功能包 `intent` 默认关。
 *
 * 底线：
 *  - 几个**关机/恢复出厂/擦除**类的系统 action 直接拒，不给用户"点错一次就没了"的机会。
 *  - 拨号走 phone_call、发短信走 send_sms（那两个有自己的确认与记录），这里不接管。
 *  - 未导出的组件普通身份打不动，有 Shizuku 时可以显式 use_shizuku=true 用 `am` 兜底。
 */
class IntentTool(private val context: Context) : Tool {
    override val name = "send_intent"
    override val description = "发送任意 Intent 或系统广播：可带 action / data(URI) / mime / 包名+组件 / category / extras / flags。" +
        "用来触达别的 App 暴露出来的功能（分享、跳转某个页面、通知某个 App 干活）。这是能打到所有 App 的高风险能力，每次都会问过你。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Send an arbitrary Intent (start an activity) or a system broadcast, with action / data URI / mime type / package + component / categories / extras / flags. " +
        "Use it to reach functionality other apps expose that no dedicated tool covers. High risk: the user is asked every time. " +
        "For plain 'open this app' use app_launch, for calls use make_phone_call, for SMS use send_sms — do not reimplement those here."

    override val permissionLevel = AndroidPermissionLevel.DEBUGGER

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("type", JSONObject().apply {
                put("type", "string"); put("enum", JSONArray(listOf("activity", "broadcast")))
                put("description", "activity = start a screen (default); broadcast = send a system broadcast")
            })
            put("action", JSONObject().apply { put("type", "string"); put("description", "Intent action, e.g. android.intent.action.VIEW or a vendor-specific one") })
            put("data", JSONObject().apply { put("type", "string"); put("description", "data URI, e.g. https://..., tel:..., content://...") })
            put("mime_type", JSONObject().apply { put("type", "string"); put("description", "MIME type, e.g. text/plain. Combined with data when both are given") })
            put("package_name", JSONObject().apply { put("type", "string"); put("description", "restrict the intent to this package") })
            put("component", JSONObject().apply { put("type", "string"); put("description", "explicit class name (needs package_name); dot-prefixed short form like .ui.MainActivity also works") })
            put("category", JSONObject().apply { put("type", "string"); put("description", "categories, comma separated, e.g. android.intent.category.DEFAULT") })
            put("extras", JSONObject().apply { put("type", "object"); put("description", "extras as a JSON object. Types are inferred: number, boolean, string, array of strings") })
            put("flags", JSONObject().apply { put("type", "string"); put("description", "flags, comma separated names (NEW_TASK, CLEAR_TOP, SINGLE_TOP, NO_HISTORY, GRANT_READ_URI_PERMISSION) or a raw integer") })
            put("use_shizuku", JSONObject().apply { put("type", "boolean"); put("description", "run it as `am start`/`am broadcast` through Shizuku instead — needed for components that are not exported. Default false") })
        })
        put("required", JSONArray())
    }

    /** 一次点错就回不来的：擦数据、恢复出厂、关机重启。不给这条路。 */
    private val forbidden = setOf(
        "android.intent.action.MASTER_CLEAR",
        "android.intent.action.FACTORY_RESET",
        "com.google.android.setupwizard.EXIT",
        "android.intent.action.REQUEST_SHUTDOWN",
        "android.intent.action.ACTION_REQUEST_SHUTDOWN",
        "android.intent.action.SHUTDOWN",
        "android.intent.action.REBOOT",
        // 直接拨号（无确认界面）：有 make_phone_call 走那条，它会先说清打给谁
        "android.intent.action.CALL",
        "android.intent.action.CALL_PRIVILEGED",
        "android.intent.action.CALL_EMERGENCY",
    )

    override suspend fun execute(params: JSONObject): ToolResult {
        val action = params.optString("action", "").trim()
        if (action.isNotEmpty() && (action in forbidden || action.contains("MASTER_CLEAR", true) || action.contains("FACTORY_RESET", true))) {
            return ToolResult("拒绝发送「$action」：这类 Intent 会擦数据/恢复出厂/关机重启，或绕过应有的确认界面。" +
                "打电话用 make_phone_call、发短信用 send_sms；真要恢复出厂请让用户自己在系统设置里做。", isError = true)
        }
        val isBroadcast = params.optString("type", "activity").equals("broadcast", true)
        val pkg = params.optString("package_name", "").trim()
        // 刻意不叫 component：下面在 Intent().apply{} 里要写 this.component，同名局部变量会让人读岔
        val compCls = params.optString("component", "").trim()
        val data = params.optString("data", "").trim()
        val mime = params.optString("mime_type", "").trim()
        if (action.isBlank() && compCls.isBlank() && pkg.isBlank())
            return ToolResult("至少要给 action，或者 package_name(+component)——不然这条 Intent 没有收件人。", isError = true)

        if (params.optBoolean("use_shizuku", false)) return viaShizuku(params, isBroadcast, action, pkg, compCls, data, mime)

        return try {
            withContext(Dispatchers.Main) {
                val intent = Intent().apply {
                    if (action.isNotBlank()) this.action = action
                    when {
                        data.isNotBlank() && mime.isNotBlank() -> setDataAndType(Uri.parse(data), mime)
                        data.isNotBlank() -> this.data = Uri.parse(data)
                        mime.isNotBlank() -> type = mime
                    }
                    if (pkg.isNotBlank() && compCls.isNotBlank()) {
                        resolveComponent(pkg, compCls)?.let { this.component = it }
                    } else if (pkg.isNotBlank()) {
                        `package` = pkg
                    }
                    params.optString("category", "").split(',').map { it.trim() }.filter { it.isNotBlank() }
                        .forEach { addCategory(it) }
                    putExtras(this, params.opt("extras"))
                    var f = parseFlags(params.optString("flags", ""))
                    // 从非 Activity 上下文起界面**必须**带 NEW_TASK，否则系统直接抛异常。
                    // 模型多半想不到这一条，替它补上——不补的结果是每次都失败，而失败原因跟它想做的事毫无关系。
                    if (!isBroadcast) f = f or Intent.FLAG_ACTIVITY_NEW_TASK
                    if (f != 0) addFlags(f)
                }
                if (isBroadcast) {
                    context.sendBroadcast(intent)
                    ToolResult("已发送广播：${describe(intent)}\n（广播是单向的，系统不会告诉我有没有人收到、收到后做了什么。)")
                } else {
                    context.startActivity(intent)
                    ToolResult("已启动：${describe(intent)}\n（界面已经在前台了，Arix 退到后面。接下来要操作它就用 ui_control。)")
                }
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: android.content.ActivityNotFoundException) {
            ToolResult("没有 App 能处理这条 Intent（action/data/组件对不上）：${e.message}。" +
                "可以先用 app_launch(action=\"search\", package_name=\"…\") 看看目标 App 到底有哪些界面。", isError = true)
        } catch (e: SecurityException) {
            ToolResult("被系统拒绝：${e.message}。多半是目标组件没导出(exported=false)——那种只能加 use_shizuku=true 再试。", isError = true)
        } catch (e: Exception) {
            ToolResult("发送失败：${e.message}", isError = true)
        }
    }

    /** 走 Shizuku 的 `am start` / `am broadcast`：能打到未导出的组件。 */
    private suspend fun viaShizuku(
        params: JSONObject, isBroadcast: Boolean, action: String, pkg: String, component: String, data: String, mime: String,
    ): ToolResult = withContext(Dispatchers.IO) {
        if (!ShizukuAccess.granted()) return@withContext ToolResult(
            "没有 Shizuku 授权，走不了 am 这条路。调 request_permission(permission=\"shizuku\") 申请，或者去掉 use_shizuku 用普通方式发。", isError = true)
        val cmd = buildString {
            append(if (isBroadcast) "am broadcast" else "am start")
            if (action.isNotBlank()) append(" -a ").append(shellQuote(action))
            if (data.isNotBlank()) append(" -d ").append(shellQuote(data))
            if (mime.isNotBlank()) append(" -t ").append(shellQuote(mime))
            params.optString("category", "").split(',').map { it.trim() }.filter { it.isNotBlank() }
                .forEach { append(" -c ").append(shellQuote(it)) }
            when {
                // am 自己认 `pkg/.Cls` 这种简写，不用在这里展开
                pkg.isNotBlank() && component.isNotBlank() -> append(" -n ").append(shellQuote("$pkg/$component"))
                pkg.isNotBlank() -> append(" -p ").append(shellQuote(pkg))
            }
            extrasObject(params.opt("extras"))?.let { ex ->
                ex.keys().forEach { k ->
                    when (val v = ex.opt(k)) {
                        is Boolean -> append(" --ez ").append(shellQuote(k)).append(' ').append(v)
                        is Int -> append(" --ei ").append(shellQuote(k)).append(' ').append(v)
                        is Long -> append(" --el ").append(shellQuote(k)).append(' ').append(v)
                        is Double, is Float -> append(" --ef ").append(shellQuote(k)).append(' ').append(v)
                        else -> append(" --es ").append(shellQuote(k)).append(' ').append(shellQuote(v?.toString() ?: ""))
                    }
                }
            }
        }
        val r = ShizukuAccess.exec(cmd) ?: return@withContext ToolResult("Shizuku 执行失败（服务不在？）", isError = true)
        val out = r.second.trim()
        // am 权限不足/组件不存在时照样 exit 0，只把 Error 打进输出——别只看退出码（同 app_launch 的教训）
        val bad = r.first != 0 || out.contains("Error", true) || out.contains("Exception", true) ||
            out.contains("Permission Denial", true)
        ToolResult((if (bad) "执行失败：" else "已执行(经 Shizuku)：") + cmd + (if (out.isNotBlank()) "\n$out".take(1200) else ""), isError = bad)
    }

    // ---- 组装细节 ----

    private fun resolveComponent(pkg: String, cls: String): ComponentName? =
        runCatching { ComponentName(pkg, if (cls.startsWith(".")) pkg + cls else cls) }.getOrNull()

    /**
     * extras 按 JSON 的原生类型推断（数字→int/long/double、布尔→boolean、字符串数组→String[]、其余→string）。
     * 不做「key:type」那种自定义语法：模型记不住这类小方言，写错了还不报错，静默塞成 string 更难查。
     */
    /** 内联工具调用常把对象序列化成字符串，两条路（普通 Intent / Shizuku am）都得认这种写法。 */
    private fun extrasObject(raw: Any?): JSONObject? = when (raw) {
        is JSONObject -> raw
        is String -> runCatching { JSONObject(raw) }.getOrNull()
        else -> null
    }

    private fun putExtras(intent: Intent, raw: Any?) {
        val obj = extrasObject(raw) ?: return
        obj.keys().forEach { k ->
            when (val v = obj.opt(k)) {
                null, JSONObject.NULL -> {}
                is Boolean -> intent.putExtra(k, v)
                is Int -> intent.putExtra(k, v)
                is Long -> intent.putExtra(k, v)
                is Double -> intent.putExtra(k, v)
                is JSONArray -> intent.putExtra(k, Array(v.length()) { v.opt(it)?.toString() ?: "" })
                else -> intent.putExtra(k, v.toString())
            }
        }
    }

    private fun parseFlags(raw: String): Int {
        if (raw.isBlank()) return 0
        raw.trim().toIntOrNull()?.let { return it }
        var f = 0
        raw.split(',').map { it.trim().uppercase().removePrefix("FLAG_").removePrefix("ACTIVITY_") }.forEach {
            f = f or when (it) {
                "NEW_TASK" -> Intent.FLAG_ACTIVITY_NEW_TASK
                "CLEAR_TOP" -> Intent.FLAG_ACTIVITY_CLEAR_TOP
                "CLEAR_TASK" -> Intent.FLAG_ACTIVITY_CLEAR_TASK
                "SINGLE_TOP" -> Intent.FLAG_ACTIVITY_SINGLE_TOP
                "NO_HISTORY" -> Intent.FLAG_ACTIVITY_NO_HISTORY
                "EXCLUDE_FROM_RECENTS" -> Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                "GRANT_READ_URI_PERMISSION" -> Intent.FLAG_GRANT_READ_URI_PERMISSION
                "GRANT_WRITE_URI_PERMISSION" -> Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                else -> 0
            }
        }
        return f
    }

    private fun describe(i: Intent): String = buildString {
        i.action?.let { append(it) }
        i.component?.let { append(" ").append(it.flattenToShortString()) }
        i.`package`?.let { if (i.component == null) append(" @").append(it) }
        i.data?.let { append(" ").append(it.toString().take(120)) }
        if (isEmpty()) append("(空 Intent)")
    }.trim()

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"
}
