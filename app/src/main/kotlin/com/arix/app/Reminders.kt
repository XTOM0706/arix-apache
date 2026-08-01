package com.arix.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * App 内提醒 / 定时任务：Arix 自己到点发通知（区别于 set_reminder 的「跳系统日历」）。
 * AI 可通过 set_reminder 工具增删查；支持一次性与重复（每小时/每天/每周）。
 *
 * 用 WorkManager 一次性任务 + 到点自我重排下一次：doze 友好、**开机自动保留**（无需 BootReceiver），
 * 无需精确闹钟特殊权限。代价=非精确（doze 下可能晚几分钟），对陪伴级提醒可接受。
 */
data class Reminder(
    val id: String,
    val title: String,
    val note: String,
    val atMillis: Long,
    val repeat: String,   // none / hourly / daily / weekly
) {
    fun toJson() = JSONObject().apply {
        put("id", id); put("title", title); put("note", note); put("at", atMillis); put("repeat", repeat)
    }
    companion object {
        fun fromJson(o: JSONObject) = Reminder(
            id = o.getString("id"), title = o.optString("title", ""), note = o.optString("note", ""),
            atMillis = o.getLong("at"), repeat = o.optString("repeat", "none"),
        )
    }
}

object ReminderStore {
    private const val PREF = "xtom_reminders"
    private const val KEY = "list"

    fun all(c: Context): List<Reminder> = try {
        val arr = JSONArray(p(c).getString(KEY, "[]"))
        (0 until arr.length()).map { Reminder.fromJson(arr.getJSONObject(it)) }
    } catch (_: Exception) { emptyList() }

    fun get(c: Context, id: String): Reminder? = all(c).firstOrNull { it.id == id }

    @Synchronized fun put(c: Context, r: Reminder) {
        val list = all(c).filter { it.id != r.id } + r
        save(c, list)
    }

    @Synchronized fun remove(c: Context, id: String) = save(c, all(c).filter { it.id != id })

    /** 原子「仍存在才更新」：id 已被 cancel 移除则返回 false（不复活）。用于重复提醒排下一次前的防竞态检查。 */
    @Synchronized fun renewIfPresent(c: Context, updated: Reminder): Boolean {
        val list = all(c)
        if (list.none { it.id == updated.id }) return false
        save(c, list.filter { it.id != updated.id } + updated)
        return true
    }

    private fun save(c: Context, list: List<Reminder>) {
        val arr = JSONArray(); list.sortedBy { it.atMillis }.forEach { arr.put(it.toJson()) }
        p(c).edit().putString(KEY, arr.toString()).apply()
    }

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}

object ReminderScheduler {
    fun schedule(context: Context, r: Reminder) {
        val delay = (r.atMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        val work = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("id" to r.id))
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(workName(r.id), ExistingWorkPolicy.REPLACE, work)
    }

    fun cancel(context: Context, id: String) {
        // 先从 store 移除、再取消任务：这样即便有 worker 正在跑，它排下一次前的 renewIfPresent 会看到已移除而不复活
        ReminderStore.remove(context, id)
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(workName(id))
    }

    /** 计算重复提醒的下一次触发（追到未来，跳过因关机等错过的多个周期）。none 返回 0。 */
    fun nextAfter(atMillis: Long, repeat: String): Long {
        val step = when (repeat) {
            "hourly" -> 3600_000L
            "daily" -> 86_400_000L
            "weekly" -> 7 * 86_400_000L
            else -> return 0L
        }
        var next = atMillis + step
        val now = System.currentTimeMillis()
        while (next <= now) next += step
        return next
    }

    private fun workName(id: String) = "reminder_$id"
}

class ReminderWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try {
            val id = inputData.getString("id") ?: return Result.success()
            val r = ReminderStore.get(ctx, id) ?: return Result.success()  // 已被取消
            postNotification(ctx, r)
            if (r.repeat != "none") {
                val next = ReminderScheduler.nextAfter(r.atMillis, r.repeat)
                // 排下一次前：未被停止(WorkManager 取消)且 store 里仍在(未被 cancel 移除)才续期，防「取消后又复活」竞态
                if (next > 0L && !isStopped && ReminderStore.renewIfPresent(ctx, r.copy(atMillis = next))) {
                    ReminderScheduler.schedule(ctx, r.copy(atMillis = next))
                } else if (next <= 0L) {
                    ReminderStore.remove(ctx, id)
                }
                // renewIfPresent 返回 false = 已被取消 → 不再排期、不动 store
            } else {
                ReminderStore.remove(ctx, id)
            }
            return Result.success()
        } catch (_: Exception) {
            return Result.success()  // 提醒失败不重试（避免延迟后重复轰炸）
        }
    }

    private fun postNotification(ctx: Context, r: Reminder) {
        // 应用在前台就不弹（只跳过弹这一下；doWork 里的续期/移除照常走）
        if (NotificationPrefs.suppressed(ctx)) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "提醒", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "定时提醒到点通知"; enableVibration(true) }
                )
            }
            val open = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                ctx, r.id.hashCode(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val text = r.note.ifBlank { r.title }
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(r.title.ifBlank { "提醒" })
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                .build()
            nm.notify(r.id.hashCode(), n)
        } catch (_: Exception) {}
    }

    companion object {
        private const val CHANNEL = "arix_reminder"
    }
}
