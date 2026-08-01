package com.arix.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * 常驻悬浮球 / 「总是接管媒体键」的**保活壳**。
 *
 * 它自己不做任何计算：起来之后只干两件事 —— 把 [FloatingChatBall] 摆上去、让 [MediaKeyController]
 * 按设置上下线，然后就一直坐在那儿当一条前台通知。手表耗电敏感，所以这里刻意**没有**任何轮询、
 * 定时器、传感器监听或后台线程；球收起时也没有帧回调（见 FloatingChatBall 的耗电那节）。
 *
 * 为什么需要它：
 *  · 悬浮球是 `WindowManager` 里的一个 View，**进程被回收窗口就没了**。侧载 App 在国产 ROM 上
 *    退到后台几分钟就可能被冻杀，没有前台服务的话"常驻悬浮球"根本常驻不了。
 *  · [FloatingAssistPrefs.TakeoverScope.ALWAYS] 档的 MediaSession 同理，活在进程里，进程死会话就死。
 *
 * 前台类型用 **dataSync**（清单里 `FOREGROUND_SERVICE_DATA_SYNC` 权限本来就声明过了，
 * 这次只在清单里新增服务节点、一条已有声明都没动）。不用 microphone：我们不持麦，
 * 那是 [WakeService] 的事，混用会在 Android 14+ 上撞"microphone 只能前台起"的限制。
 *
 * `START_STICKY` + [XtomOverlayBootReceiver]（开机）= 进程被杀/重启后都能自己回来。
 */
class XtomOverlayService : Service() {

    companion object {
        const val CHANNEL_ID = "xtom_overlay"
        const val NOTIF_ID = 4721
        /** 引导授权的通知另用一个 id，别把常驻通知顶掉。 */
        const val NOTIF_PERM_ID = 4722

        const val ACTION_SYNC = "com.arix.overlay.SYNC"
        const val ACTION_STOP = "com.arix.overlay.STOP"

        /** 这条服务到底还需不需要在。两个来源任一成立就得留着。 */
        fun needed(c: Context): Boolean {
            val s = FloatingAssistPrefs.snapshot(c)
            return s.ballEnabled ||
                (s.mediaKeyEnabled && s.scope == FloatingAssistPrefs.TakeoverScope.ALWAYS)
        }

        /**
         * 按当前设置把服务拉起来或撤掉。设置页改完、拖球到删除区、开机都调它；幂等。
         *
         * ⚠ Android 12+ 后台起前台服务会被拒（ForegroundServiceStartNotAllowedException）。
         * 我们的调用点都在前台（设置页 / 悬浮球交互）或 BOOT_COMPLETED（系统豁免），
         * 但仍然吞掉异常：起不来最多是"这次没常驻"，绝不能崩。
         */
        fun sync(context: Context) {
            val app = context.applicationContext
            if (needed(app)) {
                val i = Intent(app, XtomOverlayService::class.java).setAction(ACTION_SYNC)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(i)
                    else app.startService(i)
                } catch (e: Exception) {
                    com.arix.wake.WakeLog.d("悬浮球保活服务启动被拒: ${e.message}")
                }
            } else {
                try { app.startService(Intent(app, XtomOverlayService::class.java).setAction(ACTION_STOP)) } catch (_: Exception) {}
                FloatingChatBall.hide()
                MediaKeyController.sync(app)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 服务可能在任何 Activity 之前就被系统拉起（开机 / START_STICKY 重启），此时 I18n 还没装载过语言。
        // 一次 SharedPreferences 读，通知文案才会跟着 App 内的语言设置走（同 XtomTileService.onStartListening）。
        runCatching { I18n.load(this) }

        if (intent?.action == ACTION_STOP) {
            FloatingChatBall.hide()
            MediaKeyController.sync(this)
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }

        // 被 startForegroundService 拉起就**必须**在 5s 内 startForeground，否则系统异步抛
        // ForegroundServiceDidNotStartInTimeException 崩进程（try/catch 抓不到）。所以无条件先进前台，
        // 真的不需要了再在 apply() 末尾撤下来。
        try { startForegroundCompat() } catch (_: Exception) {}
        applyState()
        return START_STICKY
    }

    private fun applyState() {
        val s = FloatingAssistPrefs.snapshot(this)
        if (s.ballEnabled) {
            // show() 返回 false = 没有悬浮窗权限。**不能静默失败**：落一条能直接点去授权的通知。
            if (!FloatingChatBall.show(this)) postPermissionHint()
        } else {
            FloatingChatBall.hide()
        }
        MediaKeyController.sync(this)
        if (!needed(this)) { stopForegroundCompat(); stopSelf() }
    }

    override fun onDestroy() {
        super.onDestroy()
        // 服务没了窗口留着也没人管（进程随时会被回收），一起收掉，别留一个点不动的球在屏幕上
        FloatingChatBall.hide()
    }

    // ── 通知 ─────────────────────────────────────────────────────────────

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                // IMPORTANCE_LOW：常驻通知不该出声、不该弹横幅，它只是前台服务的门票
                NotificationChannel(CHANNEL_ID, tr("悬浮助手"), NotificationManager.IMPORTANCE_LOW)
            )
        }
    }

    private fun buildNotification(): Notification {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 0x0B01,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 0x0B02,
            Intent(this, XtomOverlayService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(tr("悬浮助手"))
            .setContentText(tr("悬浮球常驻中，随时点开说话"))
            .setSmallIcon(R.drawable.ic_capsule_message)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, tr("关闭"), stop)
            .build()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun stopForegroundCompat() {
        // STOP_FOREGROUND_REMOVE 是 API 24 的常量，本项目 minSdk 26，不需要版本门控
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Exception) {}
    }

    /** 没有悬浮窗权限时的引导（点一下直达系统授权页）。绝不静默失败。 */
    private fun postPermissionHint() {
        ensureChannel()
        val go = PendingIntent.getActivity(
            this, 0x0B03,
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(tr("悬浮球没能显示"))
            .setContentText(tr("缺少悬浮窗权限，点这里去授权"))
            .setSmallIcon(R.drawable.ic_capsule_message)
            .setAutoCancel(true)
            .setContentIntent(go)
            .build()
        try { getSystemService(NotificationManager::class.java).notify(NOTIF_PERM_ID, n) } catch (_: Exception) {}
    }
}

/**
 * 开机把悬浮球放回去 / 把「总是接管媒体键」重新装上。
 *
 * 单开一个接收器而不是并进现有的 [AdbBootReceiver]：那个是 directBootAware 的，
 * 在**解锁前**就会跑（LOCKED_BOOT_COMPLETED），而我们要读的 SharedPreferences 和要加的悬浮窗
 * 在解锁前都拿不到。两件事的时机根本不同，混在一起只会让 ADB 自恢复也跟着变慢。
 */
class XtomOverlayBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val s = FloatingAssistPrefs.snapshot(context)
        val wantBall = s.ballEnabled && s.ballRestoreOnBoot
        val wantMediaAlways = s.mediaKeyEnabled && s.scope == FloatingAssistPrefs.TakeoverScope.ALWAYS
        if (!wantBall && !wantMediaAlways) return
        // 用户关了「开机恢复」但球是开着的：把开关落成关。设置页显示"关"是**诚实的**——
        // 球这次确实没回来，留个 true 反而是骗人（点进设置看着是开的，屏幕上却没有球）。
        if (s.ballEnabled && !s.ballRestoreOnBoot) FloatingAssistPrefs.setBallEnabled(context, false)
        XtomOverlayService.sync(context)
    }
}
