package com.arix.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.arix.wake.WakeConfig
import com.arix.wake.WakeDetection
import com.arix.wake.WakeEngine
import com.arix.wake.WakeEngines
import com.arix.wake.WakePowerPolicy
import com.arix.wake.WakeState
import com.arix.wake.WakeTrigger
import java.util.Locale

/**
 * 前台常驻语音唤醒服务。见 DESIGN-WAKE.md §3。
 *
 * 「别一直听」：服务常驻（一条通知 + 门控广播接收器），但**只在窗口内持麦**——
 * 麦克风循环由 [WakeEngine]（P2=状态机 + 委托现有检测器黑盒）管理，IDLE 不持麦。
 * 主门控 = 亮屏 `ACTION_SCREEN_ON`（OS 抬腕点屏，零成本）；充电可切常听；显式呼出兜底。
 * 命中后拉起 MainActivity。
 */
class WakeService : Service() {

    companion object {
        const val CHANNEL_ID = "xtom_wake"
        const val CHANNEL_ID_FS = "xtom_wake_fs" // 全屏 intent 高优先级通道（后台弹浮层）
        const val NOTIF_ID = 4711
        const val NOTIF_FS_ID = 4712
        const val ACTION_START = "com.arix.wake.START"
        const val ACTION_STOP = "com.arix.wake.STOP"
        const val ACTION_TRIGGER = "com.arix.wake.TRIGGER" // 显式呼出（通知动作/快捷方式）
        // 生成保活：AI 生成期间把进程升前台（dataSync 类型，复用本服务已声明的前台类型，零 Manifest 改动），
        // 防 App 退后台/被划最近任务时系统回收 composition-scope 的生成协程；生成结束即撤前台。
        const val ACTION_KEEPALIVE_START = "com.arix.wake.KEEPALIVE_START"
        const val ACTION_KEEPALIVE_STOP = "com.arix.wake.KEEPALIVE_STOP"
        const val EXTRA_WAKE = "wake_triggered"
        const val EXTRA_TEST = "test_mode" // 测试：超长窗口，麦克风持续开，方便反复试

        @Volatile
        var isRunning = false
            private set

        // 供浮层关闭后重新武装用（应用外唤醒→浮层→关掉后仍能再次唤醒）
        @Volatile
        private var engineRef: WakeEngine? = null
        private val rearmHandler = android.os.Handler(android.os.Looper.getMainLooper())

        fun start(context: Context, testMode: Boolean = false) {
            val i = Intent(context, WakeService::class.java).setAction(ACTION_START).putExtra(EXTRA_TEST, testMode)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WakeService::class.java).setAction(ACTION_STOP))
        }

        /**
         * 生成保活：AI 一轮生成开始时调（由 [CapsuleBridge] 在真有必要时触发——已在后台生成/长生成，
         * 见那边策略）。若 wake 未在跑，则以 dataSync 前台把进程护住，不开麦/不开引擎；wake 已在跑则
         * 共用它那条前台通知、什么都不叠。用 startForegroundService 抢在 5s 内 startForeground。
         * 后台起前台被系统拒（12+ 限制）时安静吞掉——尽力而为，绝不崩、不影响生成本身。
         */
        fun startGenerationKeepAlive(context: Context) {
            val i = Intent(context, WakeService::class.java).setAction(ACTION_KEEPALIVE_START)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
                else context.startService(i)
            } catch (e: Exception) {
                com.arix.wake.WakeLog.d("生成保活启动被拒(将靠进程存活兜底): ${e.message}")
            }
        }

        /** 生成结束：撤销生成保活。wake 未在跑则撤前台并退出；wake 在跑则保留（wake 仍需前台）。 */
        fun stopGenerationKeepAlive(context: Context) {
            try {
                context.startService(Intent(context, WakeService::class.java).setAction(ACTION_KEEPALIVE_STOP))
            } catch (_: Exception) { /* 服务可能已不在，忽略 */ }
        }

        /** 浮层关闭后重新武装：浮层占麦时不武装（避免抢麦），关掉后延时开窗，才能再次唤醒。 */
        fun notifyOverlayClosed() {
            val e = engineRef ?: return
            rearmHandler.postDelayed({
                if (isRunning && !AppForeground.isForeground) {
                    com.arix.wake.WakeLog.d("浮层关闭，重新武装唤醒")
                    e.submitTrigger(WakeTrigger.Explicit("overlay-closed"))
                }
            }, 700)
        }

        // ── 省电策略 ────────────────────────────────────────────────────────
        /**
         * 唤醒的省电档。**默认 [POWER_ALWAYS_ON]，与从前的行为逐字一致**——这不是"改默认"，
         * 是把原来写死在代码里的那一档变成用户能选的。
         *
         * 为什么默认仍是常开：`WakeConfig` 里写明，MIUI 这类 OEM 上非系统 App 只有「前台开一次麦、
         * 之后不再重启」才能可靠地后台持麦。窗口模式要反复重开麦，在那些机器上会直接失效——
         * 而"叫不醒"比"费电"严重得多。这条前提**至今没在真机上复核过**，复核成立与否再谈动默认值。
         *
         * 三档的实际含义见 [powerPolicyFor]。
         */
        const val POWER_ALWAYS_ON = "always_on"
        const val POWER_WHEN_CHARGING = "when_charging"
        const val POWER_WINDOWED = "windowed"

        fun powerMode(context: Context): String =
            context.getSharedPreferences("xtom_wake", Context.MODE_PRIVATE).getString("power_mode", POWER_ALWAYS_ON) ?: POWER_ALWAYS_ON
        fun setPowerMode(context: Context, mode: String) =
            context.getSharedPreferences("xtom_wake", Context.MODE_PRIVATE).edit().putString("power_mode", mode).apply()

        /** 低电量（且没插电）时自动降到窗口模式。默认开——常开麦在 15% 电量下会明显加速掉电。 */
        fun lowBatterySaver(context: Context): Boolean =
            context.getSharedPreferences("xtom_wake", Context.MODE_PRIVATE).getBoolean("power_low_saver", true)
        fun setLowBatterySaver(context: Context, on: Boolean) =
            context.getSharedPreferences("xtom_wake", Context.MODE_PRIVATE).edit().putBoolean("power_low_saver", on).apply()

        /** 判"电量低"的阈值（百分比）。与 WorkManager 的 batteryNotLow 大致同量级。 */
        const val LOW_BATTERY_PCT = 15

        // 是否允许后台唤醒（应用外唤醒弹浮层）。默认开。
        fun bgWakeEnabled(context: Context): Boolean =
            context.getSharedPreferences("xtom_wake", Context.MODE_PRIVATE).getBoolean("bg_wake", true)
        fun setBgWake(context: Context, on: Boolean) =
            context.getSharedPreferences("xtom_wake", Context.MODE_PRIVATE).edit().putBoolean("bg_wake", on).apply()

        // 是否允许锁屏页面直接唤起助手（浮层/Activity 越过锁屏显示）。默认开。
        fun lockScreenWakeEnabled(context: Context): Boolean =
            context.getSharedPreferences("xtom_wake", Context.MODE_PRIVATE).getBoolean("lock_wake", true)
        fun setLockScreenWake(context: Context, on: Boolean) =
            context.getSharedPreferences("xtom_wake", Context.MODE_PRIVATE).edit().putBoolean("lock_wake", on).apply()

        // 唤醒助手背景是否实时模糊（毛玻璃）。默认关——实时模糊每帧模糊桌面较耗电/发热，关则仅压暗背景。
        // 默认开：用户期望唤醒后是毛玻璃（这是「唤醒后没模糊」反馈的直接原因——开关藏在设置里、默认还关着）。
        // 系统不支持窗口模糊(supports_background_blur=false / 全局关了 disable_window_blurs)时，FLAG_BLUR_BEHIND
        // 会被静默忽略、只剩 dim 压暗——那是系统能力所限，非本 App 的 bug，见 blurSupportedBySystem()。
        fun bgBlurEnabled(context: Context): Boolean =
            context.getSharedPreferences("xtom_wake", Context.MODE_PRIVATE).getBoolean("bg_blur", true)

        /** 这台设备/系统当前支不支持窗口背景模糊（FLAG_BLUR_BEHIND 是否会真的生效）。 */
        fun blurSupportedBySystem(context: Context): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
            val disabled = android.provider.Settings.Global.getInt(
                context.contentResolver, "disable_window_blurs", 0) == 1
            return !disabled   // supports_background_blur 是只读系统属性，运行时以「全局开关未禁用」为准
        }
        fun setBgBlur(context: Context, on: Boolean) =
            context.getSharedPreferences("xtom_wake", Context.MODE_PRIVATE).edit().putBoolean("bg_blur", on).apply()

        // 唤醒应答语：唤醒后先说一句（如"我在"）。空=不说；"[AI]"=每次让 AI 按人设生成；其他=固定文本。
        fun wakeGreeting(context: Context): String =
            context.getSharedPreferences("xtom_wake", Context.MODE_PRIVATE).getString("greeting", "") ?: ""
        fun setWakeGreeting(context: Context, v: String) =
            context.getSharedPreferences("xtom_wake", Context.MODE_PRIVATE).edit().putString("greeting", v).apply()
    }

    private var engine: WakeEngine? = null
    /** 当前真正跑着的省电档（用户选的档 + 充电/低电量判据算出来的结果），换档时比对用。 */
    private var appliedPolicy: WakePowerPolicy? = null
    private var gateReceiverRegistered = false
    private var micForeground = false // 当前前台是否已含 microphone 类型（后台只能起 dataSync，回前台再升）
    @Volatile private var lastNotifText = "待命中" // 当前前台通知文案，供 reassertForeground 复用同一条通知
    private var keepAliveOverlay: View? = null // Operit 式保活隐形悬浮窗：抬进程重要性防 MIUI 杀
    private var genKeepAlive = false // 生成保活是否激活：wake 未在跑时也要靠它维持前台，两者都停才 stopSelf

    /** 门控广播：亮屏 / 电源接通-断开 → 喂给状态机。屏幕开关广播只能运行时注册。 */
    private val gateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> { ensureMicForeground(); engine?.submitTrigger(WakeTrigger.ScreenOn) }
                Intent.ACTION_POWER_CONNECTED -> { engine?.submitTrigger(WakeTrigger.PowerConnected); maybeReapplyPolicy() }
                Intent.ACTION_POWER_DISCONNECTED -> { engine?.submitTrigger(WakeTrigger.PowerDisconnected); maybeReapplyPolicy() }
                Intent.ACTION_BATTERY_LOW, Intent.ACTION_BATTERY_OKAY -> maybeReapplyPolicy()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopListeningAndSelf()
                return START_NOT_STICKY
            }
            ACTION_KEEPALIVE_START -> { startGenKeepAlive(); return START_STICKY }
            ACTION_KEEPALIVE_STOP -> { stopGenKeepAlive(); return START_STICKY }
            ACTION_TRIGGER -> engine?.submitTrigger(WakeTrigger.Explicit("notification"))
            else -> startListening(intent?.getBooleanExtra(EXTRA_TEST, false) == true)
        }
        ensureMicForeground() // 回前台后把 dataSync 升级到 microphone
        return START_STICKY
    }

    private fun startListening(testMode: Boolean = false) {
        if (isRunning) { reassertForeground(); return }   // 已在跑又被 startForegroundService 拉起：必须补 startForeground 否则崩
        // 前台通知必须尽快启动（麦克风类型），否则系统抛 ForegroundServiceStartNotAllowed
        startForegroundCompat("待命中")
        showKeepAliveOverlay()
        isRunning = true

        val e = WakeEngines.create(applicationContext).apply {
            onStateChanged = { state -> updateNotification(notificationTextFor(state)) }
            onWake = { det -> onWake(det) }
            onError = { msg -> com.arix.wake.WakeLog.d("错误: $msg"); updateNotification("监听出错: $msg") }
        }
        engine = e
        engineRef = e

        registerGateReceiver()
        // 持续常听（ALWAYS_ON）：开启即在前台开麦、之后不再按窗口重启——非系统 App 在 MIUI 上持后台麦的
        // 唯一可行方式（对齐 Operit）。麦克风只在此刻（前台，用户点开关）启动一次。start() 内部即进入常听。
        // 测试模式沿用超长窗口。**默认档仍是 ALWAYS_ON**，只是现在这一档由用户选（见 powerPolicyFor）。
        val policy = powerPolicyFor(this)
        val config = if (testMode) WakeConfig(windowMs = 600_000L)
                     else WakeConfig(powerPolicy = policy)
        appliedPolicy = policy
        e.start(config)

        if (testMode) {
            com.arix.wake.WakeLog.d("测试模式：持续监听 (10 分钟窗口)")
            e.submitTrigger(WakeTrigger.Explicit("test")) // 立即开麦，不等亮屏门控
        } else {
            com.arix.wake.WakeLog.d("持续监听已开启（前台开麦，后台保持不重启）")
        }
    }

    private fun onWake(det: WakeDetection) {
        updateNotification("已唤醒！(相似度 ${String.format(Locale.US, "%.2f", det.score)})")
        // 在应用内不唤醒：主 app 已在前台就不弹助手——但仍重新武装，便于连续测试/再次唤醒
        if (AppForeground.isForeground) {
            com.arix.wake.WakeLog.d("应用在前台，不弹助手（在应用内不唤醒）；稍后重新武装可再次命中")
            scheduleRearm(1200)
            return
        }
        // 后台唤醒开关
        if (!bgWakeEnabled(this)) {
            com.arix.wake.WakeLog.d("后台唤醒已关闭（设置里可开）")
            scheduleRearm(1500)
            return
        }
        // 首选：作为默认数字助手，拉起系统托管的会话浮层（无需悬浮窗权限、不受后台启动限制）
        val vis = XtomVoiceInteractionService.instance
        if (vis != null && XtomVoiceInteractionService.isActiveAssistant(this)) {
            com.arix.wake.WakeLog.d("作为数字助手拉起会话（showSession）")
            rearmHandler.post { vis.launchAssist() }
            return
        }
        // 锁屏(keyguard 锁定)时：悬浮窗 TYPE_APPLICATION_OVERLAY 越不过 keyguard——FLAG_SHOW_WHEN_LOCKED
        // 只对 Activity window 生效，keyguard 层级盖在应用悬浮窗之上，故浮层弹了也被锁屏挡住看不见。
        // 官方唯一可靠的越锁屏机制 = Activity(setShowWhenLocked/setTurnScreenOn) + 全屏 intent 通知拉起，
        // 正是 showOverlayViaActivity() 所做。故锁屏且开了「锁屏唤起」时走它，不走悬浮窗。
        val km = getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        if (km?.isKeyguardLocked == true) {
            if (lockScreenWakeEnabled(this)) {
                com.arix.wake.WakeLog.d("锁屏唤起：走 Activity+全屏通知越锁屏（悬浮窗越不过 keyguard）")
                showOverlayViaActivity()
            } else {
                com.arix.wake.WakeLog.d("锁屏唤起已关闭，等解锁后再唤醒；稍后重新武装")
                scheduleRearm(1500)
            }
            return
        }
        // 未锁屏：弹 Siri 式浮层助手（不跳进主 app，轻量不打断）。浮层关闭后回调重新武装。
        val canOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(this)
        if (canOverlay) {
            // 首选：WindowManager 悬浮窗——后台任意亮屏场景可靠弹出，不受 Activity 启动限制
            WakeOverlayHost.show(this)
        } else {
            // 未授予悬浮窗权限：退回 Activity + 全屏 intent 通知（后台/锁屏可弹，亮屏解锁下多半只出通知）
            com.arix.wake.WakeLog.d("⚠ 未授予悬浮窗权限，退回全屏通知拉起 Activity（建议去唤醒页授权悬浮窗）")
            showOverlayViaActivity()
        }
    }

    /** 退路：先 startActivity（前台/有悬浮窗豁免时可行），再用全屏 intent 通知兜底（后台/锁屏/OEM 限制下可靠）。 */
    private fun showOverlayViaActivity() {
        val i = Intent(this, WakeAssistantActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        try {
            startActivity(i)
        } catch (e: Exception) {
            com.arix.wake.WakeLog.d("直接拉起浮层失败(将靠全屏通知): ${e.message}")
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL_ID_FS) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(CHANNEL_ID_FS, "唤醒弹出", NotificationManager.IMPORTANCE_HIGH)
                    )
                }
            }
            val pi = PendingIntent.getActivity(
                this, 7, i,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(this, CHANNEL_ID_FS)
                .setContentTitle("Arix 已唤醒")
                .setContentText("点击开始语音对话")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java).notify(NOTIF_FS_ID, n)
            // 4 秒后清掉，避免残留（浮层已弹出/用户已忽略）
            rearmHandler.postDelayed({
                try { getSystemService(NotificationManager::class.java).cancel(NOTIF_FS_ID) } catch (_: Exception) {}
            }, 4000)
        } catch (e: Exception) {
            com.arix.wake.WakeLog.d("全屏通知兜底失败: ${e.message}")
        }
    }

    /** 命中后重新武装（不靠亮屏门控）：延时开窗，才能连续多次唤醒。 */
    private fun scheduleRearm(delayMs: Long) {
        rearmHandler.postDelayed({
            if (isRunning) {
                com.arix.wake.WakeLog.d("重新武装唤醒（可再次命中）")
                engine?.submitTrigger(WakeTrigger.Explicit("rearm"))
            }
        }, delayMs)
    }

    private fun notificationTextFor(state: WakeState): String = when (state) {
        WakeState.IDLE -> "待命中（抬腕/亮屏后监听）"
        WakeState.ARMED -> "正在监听唤醒词…"
        WakeState.ALWAYS_ON -> "持续监听中（充电）"
        WakeState.TRIGGERED -> "已唤醒！"
    }

    private fun registerGateReceiver() {
        if (gateReceiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            // 电量高低的两条系统广播：用来做低电量降级/恢复。**用广播不用轮询**——
            // 为了知道电量而定时醒来，本身就是这次要省掉的那种开销。
            addAction(Intent.ACTION_BATTERY_LOW)
            addAction(Intent.ACTION_BATTERY_OKAY)
        }
        ContextCompat.registerReceiver(this, gateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        gateReceiverRegistered = true
    }

    private fun unregisterGateReceiver() {
        if (!gateReceiverRegistered) return
        try {
            unregisterReceiver(gateReceiver)
        } catch (_: Exception) {
        }
        gateReceiverRegistered = false
    }

    private fun isScreenInteractive(): Boolean =
        (getSystemService(PowerManager::class.java))?.isInteractive == true

    private fun isCharging(): Boolean =
        (getSystemService(BatteryManager::class.java))?.isCharging == true

    /** 电量百分比；取不到当成 100（宁可不降级，也别因为读不到电量把唤醒悄悄削弱）。 */
    private fun batteryPct(): Int =
        (getSystemService(BatteryManager::class.java))
            ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 } ?: 100

    /**
     * 当下该用哪一档。三档 + 一条降级规则：
     *  - [POWER_ALWAYS_ON]（默认，与从前行为一致）：一直常听。
     *  - [POWER_WHEN_CHARGING]：插着电常听，拔了电退回窗口模式。
     *  - [POWER_WINDOWED]：只在门控触发后的窗口里开麦，最省电。
     *  - 低电量降级（可关）：电量 ≤ [LOW_BATTERY_PCT] 且没插电时，**任何档都退到窗口模式**。
     *
     * ⚠ 这些判据（[isCharging]/[batteryPct]）项目里早就写好了，只是一直没人调用——本函数就是把它们接上。
     * ⚠ 息屏**故意不作为降级条件**：手表绝大多数时间都是息屏的，息屏就停麦等于把唤醒关了。
     *   亮屏门控本来就是窗口模式内部的事（见 WakeConfig 的门控字段），不该提到这一层。
     */
    private fun powerPolicyFor(context: Context): WakePowerPolicy {
        val charging = isCharging()
        if (lowBatterySaver(context) && !charging && batteryPct() <= LOW_BATTERY_PCT) {
            return WakePowerPolicy.WINDOWED
        }
        return when (powerMode(context)) {
            POWER_WINDOWED -> WakePowerPolicy.WINDOWED
            POWER_WHEN_CHARGING -> if (charging) WakePowerPolicy.ALWAYS_ON else WakePowerPolicy.WINDOWED
            else -> WakePowerPolicy.ALWAYS_ON
        }
    }

    /**
     * 电源/电量变了 → 该换档就换。换档要重启引擎（策略是 `start(config)` 时定的）。
     *
     * ⚠ 已知代价，写在这里免得以后当 bug 查：**从窗口档升回常听时，麦克风是在后台重开的**，
     * 而 MIUI 那类系统上后台重开麦可能拿不到音频（这正是默认档要一直常听的原因）。
     * 所以只有用户主动选了「充电时常听」/开着低电量降级，才会走到这条路上——默认档永远不换档，行为与从前一字不差。
     */
    private fun maybeReapplyPolicy() {
        if (!isRunning) return
        val want = powerPolicyFor(this)
        if (want == appliedPolicy) return
        val e = engine ?: return
        com.arix.wake.WakeLog.d("省电档变化：$appliedPolicy → $want（充电=${isCharging()} 电量=${batteryPct()}%）")
        appliedPolicy = want
        try {
            e.stop()
            e.start(WakeConfig(powerPolicy = want))
        } catch (t: Throwable) {
            com.arix.wake.WakeLog.d("换档失败：${t.message}")
        }
    }

    private fun buildNotification(text: String): Notification {
        lastNotifText = text   // 记住当前通知文案，供 reassertForeground 复用
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "语音唤醒", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, WakeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val listenIntent = PendingIntent.getService(
            this, 2,
            Intent(this, WakeService::class.java).setAction(ACTION_TRIGGER),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Arix 语音唤醒")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(0, "呼出", listenIntent)
            .addAction(0, "停止", stopIntent)
            .build()
    }

    /** Operit 式保活隐形悬浮窗（1×1、不可见/不可点）：持有 overlay 窗口抬高进程可见性/OOM 优先级，
     *  降低 MIUI 后台冻杀概率——被杀后 START_STICKY 只能在后台重启、再也升不回 microphone 类型，
     *  故防杀是后台持麦的关键一环。需悬浮窗权限，无则安静跳过。 */
    private fun showKeepAliveOverlay() {
        if (keepAliveOverlay != null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !android.provider.Settings.canDrawOverlays(this)) {
            com.arix.wake.WakeLog.d("无悬浮窗权限，跳过保活悬浮窗（去唤醒页可授权，防后台被杀）")
            return
        }
        try {
            val wm = getSystemService(WindowManager::class.java) ?: return
            val view = View(this)
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(
                1, 1, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                android.graphics.PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 0 }
            wm.addView(view, params)
            keepAliveOverlay = view
            com.arix.wake.WakeLog.d("保活悬浮窗已挂载（防后台被杀）")
        } catch (e: Exception) {
            com.arix.wake.WakeLog.d("保活悬浮窗挂载失败: ${e.message}")
        }
    }

    private fun hideKeepAliveOverlay() {
        val v = keepAliveOverlay ?: return
        keepAliveOverlay = null
        try { getSystemService(WindowManager::class.java)?.removeView(v) } catch (_: Exception) {}
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 前台启动（对齐 Operit）：base=dataSync（后台也能起、可存活），前台再叠加 microphone 类型。
     * **Android 14+ 规则：microphone 前台类型只能在前台启动/升级**；后台（如被杀后 START_STICKY 重启）
     * 只能以 dataSync 起——**绝不退成「无类型前台」**（那会宣称前台却拿不到后台麦，正是之前的 bug：
     * 有常驻通知但拿不住麦）。回前台后由 [ensureMicForeground] 升级到 microphone。
     */
    /**
     * 已在前台时又被 `startForegroundService` 拉起（KEEPALIVE_START / 重复 start()）：**必须**再调一次
     * `startForeground` 消掉这次 pending 的 fg 义务，否则数秒后系统异步抛
     * ForegroundServiceDidNotStartInTimeException 崩进程（且 try/catch 抓不到）。
     * 复用当前通知与前台类型（mic 已升则 mic|dataSync，否则 dataSync）——别在后台用 microphone 重升(会二次崩)。
     */
    private fun reassertForeground() {
        try {
            val notif = buildNotification(lastNotifText)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val type = if (micForeground)
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                startForeground(NOTIF_ID, notif, type)
            } else startForeground(NOTIF_ID, notif)
        } catch (_: Exception) {}
    }

    private fun startForegroundCompat(text: String) {
        val notif = buildNotification(text)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { startForeground(NOTIF_ID, notif); return }
        val micType = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        val dataType = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        // 14+ microphone 只能前台起；更低版本后台也能起
        val canMic = hasMicPermission() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE || AppForeground.isForeground)
        try {
            if (canMic) {
                startForeground(NOTIF_ID, notif, micType or dataType); micForeground = true
            } else {
                startForeground(NOTIF_ID, notif, dataType); micForeground = false
                com.arix.wake.WakeLog.d("后台/无麦权限：以 dataSync 起前台，回前台再升级 microphone")
            }
        } catch (e: Exception) {
            com.arix.wake.WakeLog.d("前台启动异常，退回 dataSync: ${e.message}"); micForeground = false
            try { startForeground(NOTIF_ID, notif, dataType) } catch (_: Exception) { startForeground(NOTIF_ID, notif) }
        }
    }

    /** 回前台且当前只是 dataSync 时，升级到 microphone（14+ 只能前台升级）。回前台触发时调用。 */
    private fun ensureMicForeground() {
        if (!isRunning || micForeground || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && !AppForeground.isForeground) return
        if (!hasMicPermission()) return
        try {
            startForeground(
                NOTIF_ID, buildNotification(notificationTextFor(engine?.let { WakeState.ARMED } ?: WakeState.IDLE)),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            micForeground = true
            com.arix.wake.WakeLog.d("已升级到 microphone 前台类型")
        } catch (e: Exception) { com.arix.wake.WakeLog.d("升级 microphone 前台失败: ${e.message}") }
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        } catch (_: Exception) {
        }
    }

    /**
     * 起「生成保活」：只在生成期间把进程升前台护住 composition-scope 的生成协程。
     * - wake 已在跑：进程已受前台保护，只标记，共用 wake 那条前台通知——不叠第二条通知。
     * - wake 未在跑：以 **dataSync** 前台类型起（生成不需要麦；microphone 类型 14+ 后台还起不了）。
     *   一个服务只有一条前台通知（NOTIF_ID），这里换成低打扰的「处理中」文案。
     * 后台起前台被系统拒时吞掉，进程本就活着能续跑，只是少了防杀保护——绝不崩、不碰生成流程。
     */
    private fun startGenKeepAlive() {
        genKeepAlive = true
        if (isRunning) {
            reassertForeground()   // 这次 KEEPALIVE_START 经 startForegroundService 拉起，必须补一次 startForeground 否则崩
            com.arix.wake.WakeLog.d("生成保活：wake 已在前台，复调 startForeground 消 pending 义务、共用其通知")
            return
        }
        try {
            val notif = buildKeepAliveNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, notif)
            }
            com.arix.wake.WakeLog.d("生成保活：已以 dataSync 前台护住进程")
        } catch (e: Exception) {
            // 12+ 后台起前台限制等：吞掉，尽力而为。撤掉标记以便下次 stop 不误 stopSelf 掉正在监听的 wake。
            genKeepAlive = false
            com.arix.wake.WakeLog.d("生成保活前台启动失败(靠进程存活兜底): ${e.message}")
        }
    }

    /**
     * 撤「生成保活」：wake 在跑则原样保留（wake 仍需前台）；wake 未在跑则撤前台并退出。
     * 幂等——即使本实例没真升成前台（后台起被拒的空壳服务），也照样把它收掉，不留残壳。
     */
    private fun stopGenKeepAlive() {
        genKeepAlive = false
        if (isRunning) {
            com.arix.wake.WakeLog.d("生成保活撤销：wake 仍在跑，保留前台")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
        com.arix.wake.WakeLog.d("生成保活撤销：无其它前台理由，撤前台退出")
    }

    /** 生成保活用的低打扰前台通知：复用 wake 的 LOW 通道，只留「打开」意图，不带唤醒动作。 */
    private fun buildKeepAliveNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "语音唤醒", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Arix")
            .setContentText("正在处理，请稍候…")
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .build()
    }

    private fun stopListeningAndSelf() {
        isRunning = false
        micForeground = false
        hideKeepAliveOverlay()
        unregisterGateReceiver()
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        engineRef = null
        // 生成保活仍激活时（用户此刻停的是「唤醒」，不是生成）：只关麦/引擎，保留前台护住生成，
        // 前台通知降级为「处理中」文案，别把正在跑的生成协程一起 stopSelf 掉。
        if (genKeepAlive) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    startForeground(NOTIF_ID, buildKeepAliveNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                else startForeground(NOTIF_ID, buildKeepAliveNotification())
            } catch (_: Exception) {}
            com.arix.wake.WakeLog.d("停止唤醒但生成保活仍在：保留前台护住生成")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        isRunning = false
        hideKeepAliveOverlay()
        unregisterGateReceiver()
        try { engine?.stop() } catch (_: Exception) {}
        engine = null
        engineRef = null
        super.onDestroy()
    }
}
