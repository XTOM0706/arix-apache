package com.arix.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 后台查更新 + **主动告诉用户**。
 *
 * 只有「检查更新」开关打开时才排这个任务；关掉就取消，一个字节都不发
 * （本项目的底线是**冷启动零联网**，这个功能不许破掉）。
 *
 * ## 几条刻意的克制
 *
 * · **一天一次，且要求联网 + 不计流量的网络**。查更新不是紧急的事，没必要在移动数据上做。
 * · **同一个版本只提醒一次**。提醒过 `v0.2.0` 之后就记下来；用户不升级也不再烦他，
 *   直到出现更新的版本为止。反复弹同一条是"通知疲劳"最常见的成因——
 *   最后的结果是他把整个通知渠道关掉，连真正重要的消息也收不到了。
 * · 点通知开到 App（暂时只能到首页，见 notify() 里的说明）。下载与安装都在检查更新页内完成，不跳浏览器。
 */
object UpdateNotifier {

    private const val WORK = "arix_update_check"
    private const val WORK_ONCE = "arix_update_check_once"
    private const val CHANNEL = "update"
    private const val NOTIF_ID = 9021
    private const val KEY_NOTIFIED_TAG = "notified_tag"

    /**
     * 按开关状态排上/取消周期任务。开关一变就调，冷启动时也调一次（幂等）。
     *
     * KEEP 而不是 REPLACE：REPLACE 会把下一次执行时间重置，冷启动频繁的话就永远轮不到执行。
     */
    fun sync(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (!UpdatePrefs.enabled(ctx)) {
            wm.cancelUniqueWork(WORK)
            return
        }
        wm.enqueueUniquePeriodicWork(
            WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<Worker>(1, TimeUnit.DAYS)
                .setConstraints(
                    Constraints.Builder()
                        // UNMETERED：别在用户的移动数据上查更新。查不到就等下一次，没什么损失。
                        .setRequiredNetworkType(NetworkType.UNMETERED)
                        .build()
                )
                .build(),
        )
    }

    /** 打开 App 时调：立即后台查一次更新，有新版本就通知（同版本只提醒一次，不会反复弹）。
     *  开关关着就不排；Worker 内再挡一道，防止开关关了之后残留的任务偷偷联网。 */
    fun checkNow(ctx: Context) {
        val wm = WorkManager.getInstance(ctx.applicationContext)
        if (!UpdatePrefs.enabled(ctx)) return
        wm.enqueueUniqueWork(
            WORK_ONCE,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<Worker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )
    }

    /** 这个 tag 提醒过没有。提醒过就别再烦他。 */
    private fun alreadyNotified(ctx: Context, tag: String): Boolean =
        ctx.getSharedPreferences("arix_update_prefs", Context.MODE_PRIVATE)
            .getString(KEY_NOTIFIED_TAG, null) == tag

    /** 记为「这个版本已经提醒/处理过」，以后不再弹。弹窗里点「更新」或关闭时由页面调。 */
    fun markNotified(ctx: Context, tag: String) {
        ctx.getSharedPreferences("arix_update_prefs", Context.MODE_PRIVATE)
            .edit().putString(KEY_NOTIFIED_TAG, tag).apply()
    }

    class Worker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            val ctx = applicationContext
            // 任务排上之后用户又关了开关：这里再挡一道，别让残留的任务偷偷联网
            if (!UpdatePrefs.enabled(ctx)) return Result.success()
            return try {
                when (val r = UpdateChecker.check(ctx)) {
                    is UpdateChecker.Result.NewVersion -> {
                        UpdatePrefs.setLastCheck(ctx, System.currentTimeMillis())
                        val tag = r.release.tag
                        // 用户「稍后」冷却期内：不弹窗不通知，等下一次周期/下次打开再试。
                        // alreadyNotified 只在「真正提醒过」（后台通知 / 弹窗被解决）时置位；
                        // 用户点「稍后」只设 postponeUntil、不动 alreadyNotified → 冷却结束还能再提醒。
                        if (!UpdatePrefs.postponed(ctx) && !alreadyNotified(ctx, tag)) {
                            if (UpdatePrompt.foreground) {
                                UpdatePrompt.offer(r.release)
                            } else {
                                notify(ctx, r.release)
                                markNotified(ctx, tag)
                            }
                        }
                        Result.success()
                    }
                    is UpdateChecker.Result.UpToDate -> {
                        UpdatePrefs.setLastCheck(ctx, System.currentTimeMillis())
                        Result.success()
                    }
                    // 查不到（网络/限流/还没发版）：**不是失败**，也不该重试重排。等下一个周期。
                    is UpdateChecker.Result.Unavailable -> Result.success()
                }
            } catch (e: Exception) {
                AppLog.w("update", "后台查更新出错 ${e.javaClass.simpleName}")
                Result.success()
            }
        }

        private fun notify(ctx: Context, rel: UpdateChecker.Release) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            // 渠道懒建。IMPORTANCE_LOW：不响不震，静静躺在通知栏里——
            // 「有新版本」永远不值得打断用户正在做的事。
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                runCatching {
                    nm.createNotificationChannel(
                        android.app.NotificationChannel(
                            CHANNEL, tr("应用更新"), NotificationManager.IMPORTANCE_LOW,
                        ).apply { description = tr("有新版本时提醒一次") }
                    )
                }
            }
            // 点通知直达「检查更新」页（EXTRA_OPEN_PAGE 在 MainActivity 的 onNewIntent/onCreate 都读，
            // Compose 侧据此 navTo 到 update 页），下载与安装都在那页完成，不跳浏览器。
            val intent = Intent(ctx, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_PAGE, "update")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            val pi = android.app.PendingIntent.getActivity(
                ctx, NOTIF_ID, intent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
            val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(String.format(tr("Arix 有新版本：%s"), rel.tag))
                .setContentText(
                    if (rel.prerelease) tr("开发快照。到「设置 → 检查更新」里看更新内容并安装")
                    else tr("到「设置 → 检查更新」里看更新内容并安装")
                )
                .setStyle(
                    androidx.core.app.NotificationCompat.BigTextStyle()
                        .bigText(rel.notes.take(400).ifBlank { rel.name })
                )
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            runCatching { nm.notify(NOTIF_ID, n) }
        }
    }
}
