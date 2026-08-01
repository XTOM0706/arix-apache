package com.arix.tool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

// ============================================================
// 通用定时任务：让 AI 排任意提醒/定时通知（区别于 Proactive/Diary/set_reminder 的固定功能）。
// 一次性任务走 WorkManager 一次性任务；周期任务走 PeriodicWork（最小周期 15 分钟）。
// doze 友好、开机自动保留、无需精确闹钟特殊权限（代价=非精确，可能晚几分钟）。
// 通知渠道复用 Reminders.kt 的 "arix_reminder"，不新建重复渠道。
// ============================================================

private const val SCHEDULE_CHANNEL = "arix_reminder"   // 复用 Reminders.kt 的提醒渠道
private const val SCHEDULE_MIN_PERIOD_SEC = 15L * 60L     // WorkManager 周期下限 15 分钟

/** 一条定时任务的元数据。time=下一次(或首次)触发的绝对毫秒；repeatSeconds>0 表示周期。 */
data class ScheduledTask(
    val id: String,
    val title: String,
    val message: String,
    val atMillis: Long,
    val repeatSeconds: Long,   // 0 = 一次性
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("title", title); put("message", message)
        put("at", atMillis); put("repeat", repeatSeconds)
    }
    companion object {
        fun fromJson(o: JSONObject) = ScheduledTask(
            id = o.getString("id"),
            title = o.optString("title", ""),
            message = o.optString("message", ""),
            atMillis = o.getLong("at"),
            repeatSeconds = o.optLong("repeat", 0L),
        )
    }
}

object ScheduleStore {
    private const val PREF = "xtom_schedule_tasks"
    private const val KEY = "list"

    fun all(c: Context): List<ScheduledTask> = try {
        val arr = JSONArray(p(c).getString(KEY, "[]"))
        (0 until arr.length()).map { ScheduledTask.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    fun get(c: Context, id: String): ScheduledTask? = all(c).firstOrNull { it.id == id }

    @Synchronized fun put(c: Context, t: ScheduledTask) {
        save(c, all(c).filter { it.id != t.id } + t)
    }

    @Synchronized fun remove(c: Context, id: String) = save(c, all(c).filter { it.id != id })

    private fun save(c: Context, list: List<ScheduledTask>) {
        val arr = JSONArray(); list.sortedBy { it.atMillis }.forEach { arr.put(it.toJson()) }
        p(c).edit().putString(KEY, arr.toString()).apply()
    }

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}

object ScheduleScheduler {
    private fun workName(id: String) = "schedule_$id"

    /** 排一次性任务：初始延迟到 atMillis 触发。 */
    fun scheduleOnce(context: Context, t: ScheduledTask) {
        val delay = (t.atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val work = OneTimeWorkRequestBuilder<ScheduleWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("id" to t.id))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(t.id), ExistingWorkPolicy.REPLACE, work)
    }

    /** 排周期任务：首次延迟到 atMillis，之后每 repeatSeconds 秒触发（WorkManager 自动重排）。 */
    fun schedulePeriodic(context: Context, t: ScheduledTask) {
        val delay = (t.atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val req = PeriodicWorkRequestBuilder<ScheduleWorker>(t.repeatSeconds, TimeUnit.SECONDS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("id" to t.id))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(workName(t.id), ExistingPeriodicWorkPolicy.REPLACE, req)
    }

    fun cancel(context: Context, id: String) {
        // 先从 store 移除、再取消任务：即便 worker 正在跑，它也会看到已移除而不复活
        ScheduleStore.remove(context, id)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(id))
    }
}

class ScheduleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try {
            val id = inputData.getString("id") ?: return Result.success()
            val t = ScheduleStore.get(ctx, id) ?: return Result.success()  // 已被取消
            postNotification(ctx, t)
            // 一次性任务触发后从列表清除；周期任务由 WorkManager 自动重排，保留元数据
            if (t.repeatSeconds <= 0L) ScheduleStore.remove(ctx, id)
            return Result.success()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c   // 被停止时不吞，交给 WorkManager 取消语义（对齐项目 STOP 处理）
        } catch (_: Exception) {
            return Result.success()  // 通知失败不重试，避免延迟后重复轰炸
        }
    }

    private fun postNotification(ctx: Context, t: ScheduledTask) {
        // 应用在前台就不弹（只跳过弹这一下；doWork 里一次性任务的清除照常走）
        if (com.arix.app.NotificationPrefs.suppressed(ctx)) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(SCHEDULE_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(SCHEDULE_CHANNEL, "提醒", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "定时提醒到点通知"; enableVibration(true) }
                )
            }
            // 点通知打开 App 主界面
            val open = Intent().apply {
                setClassName(ctx, "com.arix.app.MainActivity")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                ctx, ("xtom_sched_" + t.id).hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = t.message.ifBlank { t.title }
            val n = NotificationCompat.Builder(ctx, SCHEDULE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(t.title.ifBlank { "提醒" })
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                .build()
            nm.notify(("xtom_sched_" + t.id).hashCode(), n)
        } catch (_: Exception) {}
    }
}

/**
 * 通用定时任务工具：让 AI 排任意提醒/定时通知，到点由 App 自己弹通知。
 * set 排任务（一次性或周期）/ list 列出 / cancel 按 id 取消。
 */
class ScheduleTool(private val context: Context) : Tool {
    override val name = "schedule_task"
    override val description = "排定时提醒/通知，支持一次性与周期。到点由 Arix 自己弹通知(标题+正文)。" +
        "action=set 排一个任务(delaySeconds 相对秒 或 atEpochMillis 绝对时间戳二选一；repeatSeconds>0 则周期重复，周期最小 15 分钟=900 秒)；" +
        "action=list 列出已排任务；action=cancel 按 id 取消。返回任务唯一 id。"

    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Schedule a reminder/notification, one-off or repeating; Arix posts it itself. set=schedule one (delaySeconds or atEpochMillis, pick one; repeatSeconds>0 repeats, minimum 900); list=show scheduled; cancel=by id. Returns the task id."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply { put("type", "string"); put("description", "set (default)/list/cancel") })
            put("title", JSONObject().apply { put("type", "string"); put("description", "notification title; required for set") })
            put("message", JSONObject().apply { put("type", "string"); put("description", "notification body") })
            put("delaySeconds", JSONObject().apply { put("type", "integer"); put("description", "fire after N seconds; or use atEpochMillis") })
            put("atEpochMillis", JSONObject().apply { put("type", "integer"); put("description", "absolute fire time, Unix ms; or use delaySeconds") })
            put("repeatSeconds", JSONObject().apply { put("type", "integer"); put("description", ">0 repeats; minimum 900 (15 min), below that is rejected") })
            put("id", JSONObject().apply { put("type", "string"); put("description", "task id, for cancel") })
        })
        put("required", JSONArray(listOf<String>()))
    }

    private val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())

    private fun repeatCn(sec: Long): String = when {
        sec <= 0L -> "一次"
        sec % 86400L == 0L -> "每${sec / 86400L}天"
        sec % 3600L == 0L -> "每${sec / 3600L}小时"
        sec % 60L == 0L -> "每${sec / 60L}分钟"
        else -> "每${sec}秒"
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            when (params.optString("action", "set").lowercase()) {
                "list" -> {
                    val list = ScheduleStore.all(context)
                    if (list.isEmpty()) return@withContext ToolResult("当前没有定时任务")
                    val nowMs = System.currentTimeMillis()
                    ToolResult(list.joinToString("\n") {
                        // 周期任务的 atMillis 存的是首次触发点，会随时间变成过去；显示时算出下一次真实触发点
                        val next = if (it.repeatSeconds > 0L && it.atMillis <= nowMs) {
                            val period = it.repeatSeconds * 1000L
                            it.atMillis + ((nowMs - it.atMillis) / period + 1) * period
                        } else it.atMillis
                        "· ${it.title.ifBlank { "(无标题)" }}（${fmt.format(next)}，${repeatCn(it.repeatSeconds)}）id=${it.id}"
                    })
                }
                "cancel" -> {
                    val id = params.optString("id", "").ifBlank {
                        return@withContext ToolResult("请提供要取消的任务 id（可先 action=list 查看）", isError = true)
                    }
                    val t = ScheduleStore.get(context, id)
                        ?: return@withContext ToolResult("没有 id=$id 的定时任务", isError = true)
                    ScheduleScheduler.cancel(context, id)
                    ToolResult("已取消定时任务「${t.title.ifBlank { id }}」")
                }
                else -> {  // set
                    val title = params.optString("title", "").ifBlank {
                        return@withContext ToolResult("请提供通知标题(title)", isError = true)
                    }
                    val message = params.optString("message", "")
                    val repeatSeconds = params.optLong("repeatSeconds", 0L).coerceAtLeast(0L)
                    if (repeatSeconds in 1 until SCHEDULE_MIN_PERIOD_SEC) {
                        return@withContext ToolResult(
                            "周期任务最小间隔为 15 分钟（900 秒），当前 repeatSeconds=$repeatSeconds 过小。请改用 >=900 的周期，或用一次性任务。",
                            isError = true,
                        )
                    }

                    val now = System.currentTimeMillis()
                    val atEpoch = params.optLong("atEpochMillis", 0L)
                    val delaySec = params.optLong("delaySeconds", 0L).coerceIn(0L, 3650L * 86400L)   // 上限 ~10 年，防 *1000 溢出
                    var atMillis: Long = when {
                        atEpoch > 0L -> atEpoch
                        delaySec > 0L -> now + delaySec * 1000L
                        // 周期任务允许不给起始时间：首次按一个周期后触发
                        repeatSeconds > 0L -> now + repeatSeconds * 1000L
                        else -> return@withContext ToolResult("请提供 delaySeconds(相对秒) 或 atEpochMillis(绝对时间戳) 其一", isError = true)
                    }
                    if (atMillis <= now) {
                        if (repeatSeconds > 0L) {
                            // 周期任务起始时间落在过去（常见：LLM 传了秒级时间戳）：滚动到下一个未来触发点，
                            // 避免立刻误触发，也让 list 的首次时间正确。
                            val period = repeatSeconds * 1000L
                            atMillis += ((now - atMillis) / period + 1) * period
                        } else {
                            return@withContext ToolResult("触发时间已过，请指定一个未来的时间", isError = true)
                        }
                    }

                    val id = java.util.UUID.randomUUID().toString().take(8)
                    val task = ScheduledTask(id, title, message, atMillis, repeatSeconds)
                    ScheduleStore.put(context, task)
                    if (repeatSeconds > 0L) ScheduleScheduler.schedulePeriodic(context, task)
                    else ScheduleScheduler.scheduleOnce(context, task)

                    val notifOff = !androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()
                    ToolResult(
                        "已排定时任务「$title」：首次 ${fmt.format(atMillis)}，${repeatCn(repeatSeconds)}（id=$id）。到点 Arix 会通知你。" +
                            if (notifOff) "（注意：当前系统未允许 Arix 发通知，请到系统设置里为 Arix 打开通知权限，否则通知弹不出来。）" else ""
                    )
                }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ToolResult("定时任务操作失败: ${e.message}", isError = true)
        }
    }
}
