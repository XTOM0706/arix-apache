package com.arix.tool

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

// ============================================================
// 生活助手工具（对齐 Operit daily_life，均为免特殊权限的 Android Intent / 只读系统信息）
// 需要 shell 的 toggle_wifi / take_screenshot / toggle_dark_mode 留待权限阶段2
// ============================================================

/**
 * 起系统 Intent。返回 null=成功，非 null=失败原因（给用户和 AI 看）。
 *
 * 原先返回 Boolean 且 `catch (_: Exception)` 把异常整个吞了，调用方只能回一句「无法打开系统X」——
 * 缺权限(SecurityException)、本机没这个 App(ActivityNotFoundException)、Intent 参数不对，
 * 三种截然不同的原因长成同一个样，用户看不出该干嘛、AI 也无从换招。原因必须往外带。
 *
 * ⚠ 后台启动限制（Android 10+）是**静默拦截**：不抛异常，只在 logcat 留一行。所以「没抛异常」
 * 并不等于「起来了」——从定时任务/息屏会话这类后台路径调过来时，硬认成功就会跟用户说
 * 「已直接设好闹钟 07:00」而闹钟根本没设上。后台时先说清楚，别让调用方替它圆谎。
 */
private fun startSystemIntent(context: Context, intent: Intent): String? {
    if (!com.arix.app.AppForeground.isForeground && !canStartFromBackground(context))
        return "Arix 当前在后台，Android 不允许后台 App 拉起系统界面（会被静默拦截）。" +
               "让用户先把 Arix 打开到前台再试。"
    intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
    return try {
        context.startActivity(intent); null
    } catch (e: android.content.ActivityNotFoundException) {
        "本机没有能处理「${intent.action}」的应用（手表/精简系统常见）"
    } catch (e: SecurityException) {
        "系统拒绝：${e.message ?: "缺少权限"}"
    } catch (e: Exception) {
        "${e.javaClass.simpleName}：${e.message ?: "未知错误"}"
    }
}

/**
 * 后台也能拉起界面的例外情况：拿到了「悬浮窗」权限的 App 不受后台启动限制。
 * （这是 Android 官方给的豁免之一，也是本项目唤醒浮层本来就在申请的权限。）
 */
private fun canStartFromBackground(context: Context): Boolean =
    try { android.provider.Settings.canDrawOverlays(context) } catch (_: Exception) { false }

// 设备状态：电量 / 内存 / 存储 / 机型 —— 只读，无需权限
class DeviceStatusTool(private val context: Context) : Tool {
    override val name = "device_status"
    override val description = "获取设备状态：电量、充电状态、内存占用、存储空间、机型与系统版本；有定位权限时附当前位置(经纬度+城市)；开启健康数据后附今日步数/心率。"
    override val llmDescription = "Device state: battery, charging, memory, storage, model and OS version; current location when permitted; today's steps and heart rate when health data is on."
    override val parameters = JSONObject().apply {
        put("type", "object"); put("properties", JSONObject()); put("required", JSONArray())
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
            val stat = StatFs(Environment.getDataDirectory().path)
            fun gb(b: Long) = "%.1f".format(b / 1024.0 / 1024.0 / 1024.0)
            val totalStore = stat.blockCountLong * stat.blockSizeLong
            val availStore = stat.availableBlocksLong * stat.blockSizeLong
            ToolResult(buildString {
                append("🔋 电量: $battery%${if (charging) "（充电中）" else ""}\n")
                append("🧠 内存: 可用 ${gb(mi.availMem)}GB / 共 ${gb(mi.totalMem)}GB${if (mi.lowMemory) "（内存紧张）" else ""}\n")
                append("💾 存储: 可用 ${gb(availStore)}GB / 共 ${gb(totalStore)}GB\n")
                append("📱 机型: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}")
                // 位置：缓存不新鲜时主动定位一次（best-effort 反查城市）。
                // 拿不到时如实说原因——否则 AI 只能瞎猜，实测它一律猜「没授予定位权限」，而真因多半是别的。
                try {
                    val loc = com.arix.app.LocationSignals.current(context, timeoutMs = 6_000L)
                    if (loc != null) {
                        append("\n📍 位置: ${"%.5f".format(loc.latitude)}, ${"%.5f".format(loc.longitude)}")
                        @Suppress("DEPRECATION")
                        runCatching { android.location.Geocoder(context, java.util.Locale.getDefault()).getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull() }.getOrNull()?.let { a ->
                            val place = listOfNotNull(a.adminArea, a.locality, a.subLocality).distinct().joinToString("")
                            if (place.isNotBlank()) append("（$place）")
                        }
                    } else {
                        append("\n📍 位置: 取不到（${com.arix.app.LocationSignals.unavailableReason(context) ?: "定位超时，室内可能收不到信号"}）")
                    }
                } catch (_: Exception) {}
                // 健康数据：开启后附今日步数/心率/血氧/睡眠（读 better health tracker；未开启则不显示）
                try { if (com.arix.app.HealthSignals.isEnabled(context)) append("\n健康: ${com.arix.app.HealthSignals.report(context)}") } catch (_: Exception) {}
            })
        } catch (e: Exception) { ToolResult("获取设备状态失败: ${e.message}", isError = true) }
    }
}

// 主动测量：触发穿戴设备**现场测**一次心率/血氧（区别于 device_status 读历史缓存）。
// 走 better health tracker 的广播测量 API（GET_DATA_API → DATA_REPLY，见 HealthSignals.measure）。
class HealthMeasureTool(private val context: Context) : Tool {
    override val name = "health_measure"

    /** 心率/血氧是身体数据，而且这是**当场启动传感器**测一次、不是读缓存。见 [AndroidPermissionLevel.PRIVATE]。 */
    override val permissionLevel = AndroidPermissionLevel.PRIVATE

    override val description = "**现场测量**一次并返回读数：心率(heart_rate) 或 血氧(blood_oxygen)。当场启动传感器测、不是读历史。**优先用本机自带传感器**直接测；本机没有该传感器时才回落到手表(需装「better health tracker」并开「传感器 API」)。本机测需「身体传感器」权限；测心率约几秒、血氧约十几秒。"
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("metric", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray().put("heart_rate").put("blood_oxygen"))
                put("description", "现场测哪个：heart_rate=心率，blood_oxygen=血氧")
            })
            put("urgent", JSONObject().apply {
                put("type", "boolean")
                put("description", "true=抢占后台优先立即测；默认 false=尽力测")
            })
        })
        put("required", JSONArray().put("metric"))
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val metric = params.optString("metric", "heart_rate")
        val m = com.arix.app.HealthSignals.measurableMetric(metric)
            ?: return ToolResult("不支持现场测量的指标「$metric」（目前支持 heart_rate / blood_oxygen）", isError = true)
        val (code, unit, label) = m
        val type = if (params.optBoolean("urgent", false)) "IMMEDIATELY" else "NOW"
        val (r, source) = com.arix.app.HealthSignals.measure(context, code, type)
        return r.fold(
            onSuccess = { v -> ToolResult("$label $v $unit（$source·现测）") },
            onFailure = { e -> ToolResult("测${label}失败：${e.message}", isError = true) },
        )
    }
}

// 日历提醒/待办：调起系统日历新建事件
class ReminderTool(private val context: Context) : Tool {
    override val name = "set_reminder"
    override val description = "创建/查看/取消提醒。默认在 Arix 内定时，到点由 App 自己发通知，支持重复(每小时/每天/每周)；" +
        "action=list 列出全部提醒、action=cancel 按 id 取消；mode=calendar 则改为调起系统日历由用户保存(不重复、不由 App 通知)。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Create/list/cancel reminders. By default Arix keeps the timer and posts the notification itself, and can repeat (hourly/daily/weekly). mode=calendar instead opens the system calendar for the user to save (no repeat, not posted by the app)."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply { put("type", "string"); put("description", "create (default)/list/cancel") })
            put("title", JSONObject().apply { put("type", "string"); put("description", "title; required for create") })
            put("description", JSONObject().apply { put("type", "string"); put("description", "note/body") })
            put("due_date", JSONObject().apply { put("type", "string"); put("description", "yyyy-MM-dd HH:mm; default 1 hour from now") })
            put("repeat", JSONObject().apply { put("type", "string"); put("description", "none (default)/hourly/daily/weekly") })
            put("mode", JSONObject().apply { put("type", "string"); put("description", "app (default) or calendar") })
            put("id", JSONObject().apply { put("type", "string"); put("description", "reminder id, for cancel") })
        })
        put("required", JSONArray(listOf<String>()))
    }

    private fun repeatCn(r: String) = when (r) { "hourly" -> "每小时"; "daily" -> "每天"; "weekly" -> "每周"; else -> "一次" }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        when (params.optString("action", "create")) {
            "list" -> {
                val list = com.arix.app.ReminderStore.all(context)
                if (list.isEmpty()) return@withContext ToolResult("当前没有 App 内提醒")
                val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
                ToolResult(list.joinToString("\n") { "· ${it.title}（${fmt.format(it.atMillis)}，${repeatCn(it.repeat)}）id=${it.id}" })
            }
            "cancel" -> {
                val id = params.optString("id", "").ifBlank { return@withContext ToolResult("请提供要取消的提醒 id（可先 action=list 查看）", isError = true) }
                val r = com.arix.app.ReminderStore.get(context, id) ?: return@withContext ToolResult("没有 id=$id 的提醒", isError = true)
                com.arix.app.ReminderScheduler.cancel(context, id)
                ToolResult("已取消提醒「${r.title}」")
            }
            else -> {
                val title = params.optString("title", "").ifBlank { return@withContext ToolResult("请提供提醒标题", isError = true) }
                val note = params.optString("description", "")
                var begin = parseTimeOrDefault(params.optString("due_date", ""), 60)
                // 未显式指定 mode 时：AI 直接执行→app 内定时(不用用户确认)；关了→calendar(跳系统日历由用户保存)
                val defaultMode = if (com.arix.app.ConfigModePrefs.directActions(context)) "app" else "calendar"
                if (params.optString("mode", defaultMode) == "calendar") {
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        putExtra(CalendarContract.Events.TITLE, title)
                        if (note.isNotBlank()) putExtra(CalendarContract.Events.DESCRIPTION, note)
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + 60 * 60 * 1000)
                    }
                    val e = startSystemIntent(context, intent)
                    return@withContext if (e == null) ToolResult("已调起日历新建「$title」，请确认保存")
                    else ToolResult("打开系统日历失败：$e", isError = true)
                }
                val repeat = params.optString("repeat", "none").lowercase().let { if (it in setOf("hourly", "daily", "weekly")) it else "none" }
                // 时间已过：重复的滚到下一次，一次性的顺延 1 分钟避免立即触发
                val now = System.currentTimeMillis()
                if (begin <= now) begin = if (repeat != "none") com.arix.app.ReminderScheduler.nextAfter(begin, repeat) else now + 60_000L
                val id = java.util.UUID.randomUUID().toString().take(8)
                val r = com.arix.app.Reminder(id, title, note, begin, repeat)
                com.arix.app.ReminderStore.put(context, r)
                com.arix.app.ReminderScheduler.schedule(context, r)
                val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
                // AI 直接设好、无需用户操作；但通知被系统关了会弹不出——附一句提示
                val notifOff = !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                ToolResult("已设提醒「$title」：${fmt.format(begin)}，${repeatCn(repeat)}（id=$id）。到点 Arix 会通知你。" +
                    if (notifOff) "（注意：当前系统未允许 Arix 发通知，请到系统设置里为 Arix 打开通知权限，否则提醒弹不出来。）" else "")
            }
        }
    }
}

// 闹钟：默认 AI 直接设好（SKIP_UI）；「AI 直接执行操作」关了则调起闹钟界面由用户确认
class AlarmTool(private val context: Context) : Tool {
    override val name = "set_alarm"
    override val description = "设置系统闹钟。默认 AI 直接设好、不弹界面；若用户在设置里关了「AI 直接执行操作」，则改为调起闹钟界面由用户确认。"
    override val llmDescription = "Set a system alarm. Set directly by default; if the user turned off direct actions, the clock app opens for them to confirm."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("hour", JSONObject().apply { put("type", "integer"); put("description", "0-23") })
            put("minute", JSONObject().apply { put("type", "integer"); put("description", "0-59") })
            put("message", JSONObject().apply { put("type", "string"); put("description", "label") })
        })
        put("required", JSONArray(listOf("hour", "minute")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val hour = params.optInt("hour", -1); val minute = params.optInt("minute", -1)
        if (hour !in 0..23 || minute !in 0..59) return@withContext ToolResult("请提供有效的时/分", isError = true)
        val direct = com.arix.app.ConfigModePrefs.directActions(context)
        val msg = params.optString("message", "闹钟")
        fun alarmIntent(skipUi: Boolean) = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_MESSAGE, msg)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)   // true=直接建、不弹界面
        }
        val err = startSystemIntent(context, alarmIntent(direct))
        if (err == null)
            return@withContext ToolResult(if (direct) "已直接设好闹钟 %02d:%02d".format(hour, minute)
                                          else "已调起闹钟设置 %02d:%02d，请确认".format(hour, minute))
        // SKIP_UI 直接建闹钟是 SET_ALARM 权限保护的；某些 ROM/手表时钟即使给了权限也只认「弹界面」这条路。
        // 与其把失败甩给用户，不如降级成弹时钟界面让他自己点一下——闹钟设上了才是目的。
        if (direct) {
            val err2 = startSystemIntent(context, alarmIntent(false))
            if (err2 == null) return@withContext ToolResult("直接设闹钟被系统拒了（$err），已改为调起时钟界面 %02d:%02d，请点一下确认。".format(hour, minute))
        }
        ToolResult("设闹钟失败：$err", isError = true)
    }
}

// 短信：调起短信编辑（smsto，不会自动发送）
class SmsTool(private val context: Context) : Tool {
    override val name = "send_sms"
    override val description = "发短信。调起短信编辑界面并预填内容，由用户点发送（不会自动发送）。"
    override val llmDescription = "Open the SMS composer prefilled; the user presses send. Never sends by itself."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("phone_number", JSONObject().apply { put("type", "string"); put("description", "recipient number") })
            put("message", JSONObject().apply { put("type", "string"); put("description", "message body") })
        })
        put("required", JSONArray(listOf("phone_number", "message")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val num = params.optString("phone_number", "").ifBlank { return@withContext ToolResult("请提供号码", isError = true) }
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$num")).apply {
            putExtra("sms_body", params.optString("message", ""))
        }
        val e = startSystemIntent(context, intent)
        if (e == null) ToolResult("已调起短信编辑（发往 $num），请确认发送")
        else ToolResult("打开短信应用失败：$e", isError = true)
    }
}

// 短信读取：读收件箱最近短信（验证码/通知等）。需 READ_SMS 权限；含隐私、默认关。
class ReadSmsTool(private val context: Context) : Tool {
    override val name = "read_sms"

    /** 短信正文含验证码/银行流水。见 [AndroidPermissionLevel.PRIVATE]：读之前先问一声，
     *  与下面的 [ephemeralResult]（读到的别落库）是两道互补的闸，缺一道都不够。 */
    override val permissionLevel = AndroidPermissionLevel.PRIVATE

    override val description = "读取收到的短信（如验证码、通知）。默认读收件箱最近几条，可按发件人过滤。⚠短信含验证码等隐私、需短信读取权限。"

    /** 结果就是短信正文（验证码、银行流水、私人内容），落库=进备份=上云。见 [SensitiveResultPolicy]。
     *  丢历史的代价很小：短信随时能重读一次，而验证码本来也只在几分钟内有意义。 */
    override val ephemeralResult = true

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("count", JSONObject().apply { put("type", "integer"); put("description", "读取条数，默认 10，最大 50") })
            put("sender", JSONObject().apply { put("type", "string"); put("description", "只看某发件人(号码或名称含此串)，可选") })
        })
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        if (context.checkSelfPermission(android.Manifest.permission.READ_SMS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return@withContext ToolResult("没有短信读取权限：请到 系统设置→应用→Arix→权限 授予「短信」后重试。", isError = true)
        val count = params.optInt("count", 10).coerceIn(1, 50)
        val sender = params.optString("sender", "").trim()
        val fmt = java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault())
        try {
            val cur = context.contentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("address", "body", "date"), null, null, "date DESC"
            ) ?: return@withContext ToolResult("读取失败（无法访问短信）", isError = true)
            val out = StringBuilder()
            var n = 0
            cur.use {
                val ai = it.getColumnIndex("address"); val bi = it.getColumnIndex("body"); val di = it.getColumnIndex("date")
                while (it.moveToNext() && n < count) {
                    val addr = if (ai >= 0) it.getString(ai) ?: "" else ""
                    val body = if (bi >= 0) it.getString(bi) ?: "" else ""
                    if (sender.isNotBlank() && !addr.contains(sender, true)) continue
                    val ts = if (di >= 0) it.getLong(di) else 0L
                    out.append("[${fmt.format(java.util.Date(ts))}] $addr: ${body.replace("\n", " ").take(200)}\n")
                    n++
                }
            }
            if (n == 0) ToolResult(if (sender.isNotBlank()) "没有来自「$sender」的短信" else "收件箱为空")
            else ToolResult("📨 最近 $n 条短信：\n" + out.toString().trim())
        } catch (e: Exception) {
            ToolResult("读取短信失败：${e.message}", isError = true)
        }
    }
}

// 拨号：默认 AI 直接拨出（ACTION_CALL，需 CALL_PHONE）；「AI 直接执行操作」关了则只填进拨号盘由用户按
class PhoneCallTool(private val context: Context) : Tool {
    override val name = "make_phone_call"

    /**
     * 隐私级。这个工具在「AI 直接执行操作」开着且有 CALL_PHONE 时走 `ACTION_CALL`——**真的拨出去**，
     * 不像 [SmsTool]/[TakePhotoTool] 那样还有一步系统界面让用户自己按。电话一通就收不回来，
     * 且对面是个真人。见 [AndroidPermissionLevel.PRIVATE]。
     */
    override val permissionLevel = AndroidPermissionLevel.PRIVATE

    override val description = "打电话。默认 AI 直接拨出；若用户关了「AI 直接执行操作」或没给拨号权限，则只把号码填进拨号盘由用户点拨号。"
    override val llmDescription = "Place a call. Dials directly by default; without direct actions or the call permission it only fills the dialer for the user to press."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("phone_number", JSONObject().apply { put("type", "string"); put("description", "number") })
        })
        put("required", JSONArray(listOf("phone_number")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val num = params.optString("phone_number", "").ifBlank { return@withContext ToolResult("请提供号码", isError = true) }
        val direct = com.arix.app.ConfigModePrefs.directActions(context)
        val canCall = androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CALL_PHONE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

        // ACTION_DIAL 只是把号码填进拨号盘、还得用户自己按拨号键——那不叫「打电话」。
        // 真拨出要 ACTION_CALL + CALL_PHONE。没权限时别假装成功，说清楚并给出申请的路。
        if (direct && canCall) {
            val e = startSystemIntent(context, Intent(Intent.ACTION_CALL, Uri.parse("tel:$num")))
            if (e == null) return@withContext ToolResult("已拨出：$num")
            return@withContext ToolResult("直接拨号失败：$e", isError = true)
        }

        val e = startSystemIntent(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$num")))
        if (e != null) return@withContext ToolResult("打开拨号盘失败：$e", isError = true)
        ToolResult("已把 $num 填进拨号盘，需要用户自己按拨号键。" + when {
            !canCall -> "想让我直接拨出的话，需要拨号权限：可调 request_permission(permission=\"call_phone\", reason=\"…\") 申请。"
            else -> "（「AI 直接执行操作」当前是关的，所以没有直接拨出。）"
        })
    }
}

// 拍照：调起相机
class TakePhotoTool(private val context: Context) : Tool {
    override val name = "take_photo"
    override val description = "打开相机拍照。"
    override val llmDescription = "Open the camera to take a photo."
    override val parameters = JSONObject().apply {
        put("type", "object"); put("properties", JSONObject()); put("required", JSONArray())
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val e = startSystemIntent(context, intent)
        if (e == null) ToolResult("已打开相机")
        else ToolResult("打开相机失败：$e", isError = true)
    }
}

private fun parseTimeOrDefault(s: String, defaultMinutesFromNow: Int): Long {
    if (s.isNotBlank()) {
        for (fmt in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy/MM/dd HH:mm")) {
            try { return SimpleDateFormat(fmt, Locale.getDefault()).parse(s)!!.time } catch (_: Exception) {}
        }
    }
    return Calendar.getInstance().apply { add(Calendar.MINUTE, defaultMinutesFromNow) }.timeInMillis
}
