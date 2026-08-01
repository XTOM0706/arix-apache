package com.arix.tool

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import android.content.pm.PackageManager as AndroidPm

/**
 * request_permission —— 工具被权限挡住时，**主动去要**，而不是回一句「我没权限」就完事。
 *
 * 三类都收：
 *  1) 运行时权限（录音/相机/位置…）→ 直接弹系统授权框；
 *  2) 特殊权限（通知使用权/无障碍/悬浮窗/使用情况/所有文件/电池豁免/改系统设置/Shizuku）→
 *     系统不给弹框，只能跳到对应设置页；跳完在这儿轮询等用户开完，开好了当场返回成功；
 *  3) `package:<功能包id>` → 功能包没启用（工具还在工具表里但调不动）时，请用户开启。
 *
 * 为什么要它：AI 看得见工具、却在缺权限时只会干等或者绕路（跑去用 ui_control 戳屏幕）。
 * 给它一条正经的「申请授权」通道，缺什么要什么，要到了原样重试原来的工具。
 */
class PermissionTool(private val context: Context) : Tool {

    override val name = "request_permission"
    override val description =
        "工具因为缺权限、或功能包没启用而用不了时，用它发起授权请求：运行时权限直接弹系统授权框；" +
        "特殊权限(通知使用权/无障碍/悬浮窗/使用情况/所有文件/电池豁免/改系统设置/Shizuku)跳到对应系统页面等用户开；" +
        "permission=\"package:<功能包id>\" 请用户启用某个功能包。permission=\"list\" 列出所有可申请项与当前状态。" +
        "授权成功后原样重试刚才失败的工具。别因为缺权限就说自己做不到——先来这里要。"

    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Ask for what a blocked tool needs. Runtime permissions pop the system dialog; special ones (notification access, accessibility, overlay, usage stats, all-files, battery, write settings, Shizuku) open the matching system page for the user. permission=\"package:<id>\" asks the user to enable a feature package. permission=\"list\" shows everything requestable and its current state. Retry the failed tool unchanged once granted — never tell the user you cannot do something when the reason is a missing permission."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("permission", JSONObject().apply {
                put("type", "string")
                put("description", "runtime: audio/camera/location/contacts/phone/call_phone/sms/calendar/storage/post_notifications; " +
                    "special: notification_listener/accessibility/overlay/usage_stats/all_files/battery/write_settings/shizuku; " +
                    "package:<id>; or list")
            })
            put("reason", JSONObject().apply { put("type", "string"); put("description", "why, in plain words — shown to the user") })
        })
        put("required", JSONArray(listOf("permission")))
    }

    // ---------- 运行时权限：能直接弹框的 ----------
    private val runtime: Map<String, Pair<String, Array<String>>> = mapOf(
        "audio" to ("录音" to arrayOf(Manifest.permission.RECORD_AUDIO)),
        "camera" to ("相机" to arrayOf(Manifest.permission.CAMERA)),
        "location" to ("位置" to arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
        "contacts" to ("通讯录" to arrayOf(Manifest.permission.READ_CONTACTS)),
        "phone" to ("电话状态" to arrayOf(Manifest.permission.READ_PHONE_STATE)),
        "call_phone" to ("直接拨号" to arrayOf(Manifest.permission.CALL_PHONE)),
        "sms" to ("读短信" to arrayOf(Manifest.permission.READ_SMS)),
        "calendar" to ("日历" to arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)),
        "storage" to ("存储" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
            else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)),
        "post_notifications" to ("发通知" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            arrayOf(Manifest.permission.POST_NOTIFICATIONS) else emptyArray()),
    )

    // ---------- 特殊权限：系统不给弹框，只能跳设置页——除非有 Shizuku ----------
    /**
     * [shizukuCmds]：有 Shizuku 时用来**直接授权**的命令。
     *
     * 这是 Shizuku 在这儿最大的价值：不是拿它执行业务，是拿它把权限一条命令开掉。
     * 手表 ROM 上「使用情况访问」「通知使用权」这些设置页经常压根不存在、或小圆屏上根本点不到，
     * 那时这是唯一出路；有大屏的机器上也省得用户在设置里翻半天。
     */
    private class Special(
        val label: String,
        val granted: (Context) -> Boolean,
        val page: (Context) -> Intent?,
        val shizukuCmds: (Context) -> List<String> = { emptyList() },
    )

    private val special: Map<String, Special> = mapOf(
        "notification_listener" to Special("通知使用权（读/回复手机通知）",
            { com.arix.app.NotificationAwarenessPrefs.hasAccess(it) },
            { Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS) },
            { listOf("cmd notification allow_listener ${it.packageName}/${it.packageName}.XtomNotificationListener") }),
        "accessibility" to Special("无障碍（界面自动化）",
            { com.arix.app.XtomAccessibilityService.instance != null },
            { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            { c ->
                // 无障碍没有 allow 子命令，只能往 secure 设置的列表里**追加**——直接覆盖会把用户
                // 已开的其它无障碍服务（读屏、输入法等）全关掉，那是在帮倒忙。
                val me = "${c.packageName}/${c.packageName}.XtomAccessibilityService"
                val cur = ShizukuAccess.exec("settings get secure enabled_accessibility_services")
                    ?.second?.trim().orEmpty().let { if (it == "null") "" else it }
                val merged = if (cur.isBlank()) me else if (cur.split(':').contains(me)) cur else "$cur:$me"
                listOf("settings put secure enabled_accessibility_services $merged",
                       "settings put secure accessibility_enabled 1")
            }),
        "overlay" to Special("悬浮窗",
            { Settings.canDrawOverlays(it) },
            { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${it.packageName}")) },
            { listOf("appops set ${it.packageName} SYSTEM_ALERT_WINDOW allow") }),
        "usage_stats" to Special("使用情况访问",
            { UsageStatsTool.hasAccess(it) },
            { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) },
            { listOf("appops set ${it.packageName} GET_USAGE_STATS allow") }),
        "all_files" to Special("所有文件访问",
            { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.os.Environment.isExternalStorageManager()
              else ContextCompat.checkSelfPermission(it, Manifest.permission.WRITE_EXTERNAL_STORAGE) == AndroidPm.PERMISSION_GRANTED },
            { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${it.packageName}")) else null },
            { listOf("appops set ${it.packageName} MANAGE_EXTERNAL_STORAGE allow") }),
        "battery" to Special("电池优化豁免",
            { (it.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(it.packageName) },
            { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${it.packageName}")) },
            { listOf("dumpsys deviceidle whitelist +${it.packageName}") }),
        "write_settings" to Special("修改系统设置（亮度/音量）",
            { try { Settings.System.canWrite(it) } catch (_: Exception) { false } },
            { Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${it.packageName}")) },
            { listOf("appops set ${it.packageName} WRITE_SETTINGS allow") }),
        "shizuku" to Special("Shizuku（ADB 级特权，免 root 跑系统命令）",
            { ShizukuAccess.granted() },
            { null }),   // Shizuku 自成一套：见下面单独处理
    )

    /**
     * 有 Shizuku 就直接把权限开掉，省得支使用户去设置页里翻。
     * 返回 true=开成功。**以实际授权状态为准**（重新 check 一遍），不信命令的退出码——
     * 有些 ROM 的 appops 明明返回 0 却没真生效，信它就会跟用户说「开好了」而其实没有。
     */
    private fun tryShizukuGrant(sp: Special): Boolean {
        if (!ShizukuAccess.granted()) return false
        val cmds = sp.shizukuCmds(context)
        if (cmds.isEmpty()) return false
        cmds.forEach { ShizukuAccess.exec(it) }
        return sp.granted(context)
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val what = params.optString("permission", "").trim()
        val reason = params.optString("reason", "").trim()
        if (what.isBlank() || what == "list") return list()

        // ① 功能包没启用
        if (what.startsWith("package:")) return requestPackage(what.removePrefix("package:").trim(), reason)

        // ② Shizuku 自成一套授权流程（不是系统设置页）
        if (what == "shizuku") return ShizukuAccess.request(context, reason)

        // ③ 特殊权限：能用 Shizuku 就直接开掉，否则跳系统页 + 等用户开
        special[what]?.let { sp ->
            if (sp.granted(context)) return ToolResult("「${sp.label}」已经是开着的，直接重试刚才的工具即可。")
            if (tryShizukuGrant(sp)) return ToolResult("已用 Shizuku 直接开好「${sp.label}」，没麻烦用户。现在可以重试刚才的工具了。")
            val page = sp.page(context)
                ?: return ToolResult("本机没有「${sp.label}」的设置入口，也没有 Shizuku 可用来直接开。" +
                    "可以先 request_permission(permission=\"shizuku\") 装上 Shizuku，之后这类权限我就能一条命令开掉。", isError = true)
            val opened = withContext(Dispatchers.Main) {
                try { context.startActivity(page.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
            }
            if (!opened) return ToolResult("打不开「${sp.label}」的设置页（本机可能没这个入口）。", isError = true)
            return waitFor(sp.label) { sp.granted(context) }
        }

        // ④ 运行时权限：直接弹框
        val rt = runtime[what] ?: return ToolResult(
            "没有「$what」这个可申请项。用 permission=\"list\" 看看有哪些。", isError = true)
        val (label, perms) = rt
        if (perms.isEmpty()) return ToolResult("「$label」在这个系统版本上不需要单独申请。")
        fun hasAll() = perms.all { ContextCompat.checkSelfPermission(context, it) == AndroidPm.PERMISSION_GRANTED }
        if (hasAll()) return ToolResult("「$label」权限已经有了，直接重试刚才的工具即可。")

        // 有 Shizuku 就 pm grant 直接给。这条尤其救命：语音助手常态在后台，
        // 后台压根弹不出运行时授权框（下面 getTopActivity 必然为 null），没有 Shizuku 时那就是死路。
        if (ShizukuAccess.granted()) {
            perms.forEach { ShizukuAccess.exec("pm grant ${context.packageName} $it") }
            if (hasAll()) return ToolResult("已用 Shizuku 直接授予「$label」，没麻烦用户。现在可以重试刚才的工具了。")
        }

        val activity = getTopActivity()
            ?: return ToolResult("现在弹不出授权框（App 不在前台，后台没法弹系统授权框）。" +
                "让用户打开 Arix 后再试；或到 系统设置→应用→Arix→权限 里开「$label」；" +
                "或者装上 Shizuku（request_permission(permission=\"shizuku\")），之后我在后台也能直接授权。", isError = true)
        // 通知权限是个特例：本 App targetSdk=28，Android 13+ 对这类应用**不弹框、直接算拒绝**，
        // 下面那句 requestPermissions 对它是空转。真正的入口是"创建第一个通知渠道"，见 NotificationBootstrap。
        if (what == "post_notifications") {
            com.arix.app.NotificationBootstrap.ensureChannel(context)
            if (hasAll()) return ToolResult("「$label」已授予。现在可以重试刚才的工具了。")
        }
        withContext(Dispatchers.Main) { ActivityCompat.requestPermissions(activity, perms, 100) }
        return waitFor(label) { perms.all { p -> ContextCompat.checkSelfPermission(context, p) == AndroidPm.PERMISSION_GRANTED } }
    }

    /**
     * 等用户在弹框/设置页里点完。轮询而不是回调：系统弹框与设置页的返回路径各不相同，
     * 轮询「实际授权状态」是唯一对所有路径都成立的判据。delay 可取消 → STOP 能停。
     */
    private suspend fun waitFor(label: String, granted: () -> Boolean): ToolResult {
        repeat(120) {                       // 最多等 60s，用户在表上点得慢
            delay(500)
            if (granted()) return ToolResult("用户已授予「$label」。现在可以重试刚才的工具了。")
        }
        return ToolResult("已经把「$label」的授权界面打开了，但用户还没开（等了 60 秒）。" +
            "先别重试，跟用户说一句需要这个权限、为什么需要，等他开好再继续。", isError = true)
    }

    /** 功能包没启用：工具在表里但调不动。这不是系统权限，是 App 内的开关。 */
    private fun requestPackage(id: String, reason: String): ToolResult {
        val pkg = PackageManager.getAllPackages().firstOrNull { it.id == id }
            ?: return ToolResult("没有 id=$id 这个功能包。", isError = true)
        if (PackageManager.isEnabled(id)) return ToolResult("功能包「${pkg.name}」已经开着了，直接重试刚才的工具即可。")
        PackageManager.proposeEnable(id, reason)
        return ToolResult("已向用户发出启用「${pkg.name}」的申请（${pkg.description}）。" +
            "用户同意后这个包里的工具才能用；先跟用户说一句你需要它做什么，别干等。", isError = true)
    }

    private fun list(): ToolResult = buildString {
        if (ShizukuAccess.granted())
            append("✔ Shizuku 已授权：下面大部分权限我能**一条命令直接开**，不用麻烦用户去设置页。\n\n")
        else
            append("· Shizuku 没授权。装上并授权后（request_permission(permission=\"shizuku\")），" +
                "下面大部分权限我就能直接开、不用支使用户翻设置页——手表上那些设置页经常压根找不到。\n\n")
        append("可申请的运行时权限（直接弹框）：\n")
        runtime.forEach { (k, v) ->
            val on = v.second.isEmpty() || v.second.all { ContextCompat.checkSelfPermission(context, it) == AndroidPm.PERMISSION_GRANTED }
            append("· $k —— ${v.first}　${if (on) "✓已有" else "✗没有"}\n")
        }
        append("\n特殊权限（跳系统页让用户开）：\n")
        special.forEach { (k, v) ->
            append("· $k —— ${v.label}　${if (v.granted(context)) "✓已开" else "✗没开"}\n")
        }
        val off = PackageManager.getAllPackages().filter { !PackageManager.isEnabled(it.id) }
        if (off.isNotEmpty()) {
            append("\n没启用的功能包（用 package:<id> 申请启用）：\n")
            off.forEach { append("· package:${it.id} —— ${it.name}：${it.description}\n") }
        }
    }.let { ToolResult(it.trim()) }
}
