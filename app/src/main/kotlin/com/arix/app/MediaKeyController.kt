package com.arix.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/**
 * 耳机线控 / 蓝牙耳机 / 车机的**媒体键**接管：按一下就开始说话，屏幕不用点、App 不用切。
 * 手表上这条最值钱——抬手按耳机比抬腕点屏可靠得多。
 *
 * ════════ 为什么用 [MediaSession] 而不是 MediaButtonReceiver / MediaSessionCompat ════════
 *
 *  · `AudioManager.registerMediaButtonEventReceiver` + `<action android:name="android.intent.action.MEDIA_BUTTON">`
 *    这条老路 **API 21 起就废弃**了，现代 Android 的媒体键路由器根本不看它（只在没有任何活跃
 *    MediaSession 时才作为兜底），在国产 ROM 上基本等于收不到。
 *  · `MediaSessionCompat` 要 `androidx.media`，**本项目没有这个依赖**，而本次改动不许动 build.gradle.kts。
 *    好在框架版 `android.media.session.MediaSession` 是 API 21 的，本项目 minSdk 26，
 *    我们需要的能力（onMediaButtonEvent 拿原始 KeyEvent）它一样不缺，兼容层白加。
 *  · 于是只剩一条：注册一个自己的 MediaSession，在 [MediaSession.Callback.onMediaButtonEvent] 里
 *    拿到原始 [KeyEvent] 自己判单击/双击/长按。
 *
 * ════════ targetSdk=28 与 Android 13+ 的媒体键路由差异（重要，别指望 targetSdk 挡住） ════════
 *
 * 媒体键路由是**平台行为**，不是 targetSdk 门控的行为变更 —— 我们钉在 28 一点都豁免不到。实际差异：
 *
 *  · Android 12 及以前：媒体键发给「最近一个 setActive(true) 的 MediaSession」。谁后注册谁赢，
 *    我们只要注册就能稳稳拿到键 —— 也正因为这样，**乱注册就是明抢音乐 App 的键**。
 *  · Android 13 (T) 起：路由器优先发给「当前真正在出声的那个播放器」，没有播放器在出声时才回落到
 *    最近活跃的会话。这条变更对我们其实是**利好**：系统自己就先保护了正在放歌的 App。
 *    代价是——正在放歌时我们基本拿不到键（这正是我们想要的），而没在放歌时我们能不能拿到键
 *    取决于"最近活跃"的排序，属于**尽力而为**，不同 ROM 表现不同。
 *  · 我们的 PlaybackState 刻意报 [PlaybackState.STATE_PAUSED] 而**不是** STATE_PLAYING：
 *    我们不是播放器，谎称在播放会让 13+ 的路由器把我们当成"当前播放器"，那才是真抢键。
 *  · 我们**从不申请音频焦点**。申请焦点会直接把用户的音乐暂停/压低音量 —— 那比抢键还严重。
 *
 * ════════ 绝不抢掉音乐播放的媒体键：三道闸 ════════
 *
 *  1. 整个功能**默认关**（见 [FloatingAssistPrefs.DEFAULT]）。
 *  2. 范围默认 [FloatingAssistPrefs.TakeoverScope.WHEN_ACTIVE]：只在本 App 前台 / 助手浮层开着 /
 *     悬浮球面板开着时才注册会话，其余时间**连会话都不存在**，音乐 App 的线控行为零影响。
 *     想要"黑屏也能按"就得自己去开「总是接管媒体键」——这是明确的取舍，文案里写清了。
 *  3. 「正在放歌就让键」（默认开）：即使我们拿到了键，只要 [AudioManager.isMusicActive] 为真，
 *     就**撤销自己的会话并把这次按键原样转发出去**，让真正的播放器收到 —— 用户听歌时按线控
 *     得到的是"暂停音乐"，不是"跳出个助手"。转发用 [AudioManager.dispatchMediaKeyEvent]，
 *     与现有 `MusicControlTool` 同一条路，不新写机制。
 *
 * 另外：**只接管 HEADSETHOOK / PLAY_PAUSE / PLAY / PAUSE 这四个键**。NEXT/PREV/STOP 一律不碰
 * （那些键除了控制音乐没有第二种含义，接管它们纯属添乱）。
 *
 * ════════ 常驻性 ════════
 *
 * MediaSession 活在进程里，进程没了会话就没了。所以 [FloatingAssistPrefs.TakeoverScope.ALWAYS] 档
 * 由 [XtomOverlayService]（dataSync 前台服务）把进程护住。**刻意没有**在清单里登记
 * `android.intent.action.MEDIA_BUTTON` 接收器：那个接收器必须 exported=true 才收得到系统广播，
 * 等于给设备上任意一个 App 开了一个"远程唤起 Arix 助手"的口子，为了"进程死了还能被键唤醒"
 * 这点收益不值得。
 */
object MediaKeyController {

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var session: MediaSession? = null
    @Volatile private var appCtx: Context? = null
    @Volatile private var lifecycleHooked = false

    /** 我们自己转发出去的那次按键会绕回来一趟，靠它挡住无限回环。 */
    @Volatile private var forwarding = false

    // ── 单击 / 双击 / 长按的判定 ──────────────────────────────────────────
    /** 双击窗口。短了双击难触发，长了单击会明显"迟钝"；320ms 是手表上手感的折中。 */
    private const val MULTI_TAP_WINDOW_MS = 400L
    /** 长按阈值。线控按键的行程比屏幕长按短，600ms 已经明显区别于"按一下"。 */
    private const val LONG_PRESS_MS = 600L

    private var tapCount = 0
    private var longFired = false
    private var downSeen = false
    private val tapRunnable = Runnable { flushTaps() }
    private val longRunnable = Runnable { fireLongPress() }

    val isArmed: Boolean get() = session != null

    /**
     * 进程启动时调一次（[XtomApp.onCreate] 末尾）。只做两件事：记住 application context、
     * 挂一个 Activity 生命周期回调用来驱动 [TakeoverScope.WHEN_ACTIVE] 的上下线。
     *
     * ⚠ 必须排在 `registerActivityLifecycleCallbacks(AppForeground.callbacks)` **之后**：
     * 我们读的是 [AppForeground.isForeground]，回调按注册顺序执行，先注册的先更新。
     *
     * 功能关着时这里等于零开销：不注册会话、不起线程、不申请任何系统资源。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        appCtx = app
        if (!lifecycleHooked) {
            (app as? Application)?.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
                override fun onActivityStarted(activity: Activity) { sync(app) }
                // 转屏 / 页面切换会有一瞬间 0 个 started Activity，立刻撤会掉一次又装一次；
                // 延一拍再判，避开这个抖动。
                override fun onActivityStopped(activity: Activity) { main.postDelayed({ sync(app) }, 400L) }
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            })
            lifecycleHooked = true
        }
        sync(app)
    }

    /**
     * 按当前设置 + 当前前台状态决定「该不该登记媒体会话」，该上就上、该撤就撤。
     * 设置页改完、浮层开关、服务起停都调它；幂等，随便多调。
     */
    fun sync(context: Context) {
        val app = context.applicationContext
        appCtx = app
        val s = FloatingAssistPrefs.snapshot(app)
        val want = s.mediaKeyEnabled && when (s.scope) {
            FloatingAssistPrefs.TakeoverScope.ALWAYS -> true
            FloatingAssistPrefs.TakeoverScope.WHEN_ACTIVE -> isSelfActive()
        }
        main.post { if (want) arm(app) else disarm() }
    }

    /** 「本 App 正在被用」的判据：前台 Activity / 助手浮层 / 悬浮球面板，任一即可。 */
    private fun isSelfActive(): Boolean =
        AppForeground.isForeground || WakeOverlayHost.isShowing || FloatingChatBall.isPanelOpen

    // ── 会话上下线 ───────────────────────────────────────────────────────

    private fun arm(app: Context) {
        if (session != null) { reassert(); return }
        try {
            val ms = MediaSession(app, "ArixMediaKey")
            ms.setCallback(object : MediaSession.Callback() {
                override fun onMediaButtonEvent(mediaButtonIntent: Intent): Boolean =
                    handleMediaButton(mediaButtonIntent)

                // 有些路由器不走 onMediaButtonEvent 而是直接把键映射成传输控制回调。
                // 我们不是播放器，这里只把它当作"来了一次单击"。绝不 super.onPlay()（那是播放语义）。
                override fun onPlay() { onTransportPoke() }
                override fun onPause() { onTransportPoke() }
            }, main)
            ms.setPlaybackState(pausedState())
            ms.isActive = true
            session = ms
            com.arix.wake.WakeLog.d("媒体键已接管（MediaSession 上线）")
        } catch (e: Exception) {
            com.arix.wake.WakeLog.d("媒体键接管失败: ${e.message}")
        }
    }

    private fun disarm() {
        val ms = session ?: return
        session = null
        cancelPending()
        try { ms.isActive = false } catch (_: Exception) {}
        try { ms.release() } catch (_: Exception) {}
        com.arix.wake.WakeLog.d("媒体键已交还（MediaSession 下线）")
    }

    /** 让键盘路由器把我们重新排到"最近活跃"的队首（转发让键之后要补一次）。 */
    private fun reassert() {
        val ms = session ?: return
        try { ms.setPlaybackState(pausedState()); ms.isActive = true } catch (_: Exception) {}
    }

    /**
     * 刻意报 PAUSED 而不是 PLAYING：见类注释。actions 只声明播放/暂停这一组，
     * 不声明 SKIP_TO_NEXT/PREVIOUS —— 声明了系统就可能把切歌键也路由过来，那是抢音乐的键。
     */
    private fun pausedState(): PlaybackState = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
        .setState(PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, 0f)
        .build()

    // ── 按键处理 ─────────────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun extractKeyEvent(intent: Intent): KeyEvent? =
        if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        else intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)

    private fun handleMediaButton(intent: Intent): Boolean {
        if (forwarding) return false     // 这条是我们自己转发出去、又绕回来的，别再吃一次
        val app = appCtx ?: return false
        val ke: KeyEvent = extractKeyEvent(intent) ?: return false

        // 只接管这四个。NEXT/PREV/STOP/REWIND… 一律 return false 交还系统——那些键只有"控制音乐"一种含义。
        when (ke.keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE -> Unit
            else -> return false
        }

        val s = FloatingAssistPrefs.snapshot(app)
        // 开关已被关掉却还收到键（sync 漏调）：自愈。**不在回调里直接 release**（那是在会话自己的
        // 回调栈上销毁它），post 到下一轮消息循环再撤。
        if (!s.mediaKeyEnabled) { main.post { disarm() }; return false }

        // 第三道闸：正在出声就把键让回去（见类注释）
        if (s.yieldToMusic && isMusicActive(app)) {
            if (ke.action == KeyEvent.ACTION_UP) yieldKeyToPlayer(app, ke.keyCode)
            cancelPending()
            return true   // 说"我处理了"，免得框架再把它映射成我们自己的 onPlay/onPause
        }

        when (ke.action) {
            KeyEvent.ACTION_DOWN -> {
                if (ke.repeatCount == 0) {
                    downSeen = true; longFired = false
                    main.removeCallbacks(longRunnable)
                    if (s.longPress != FloatingAssistPrefs.KeyAction.NONE) {
                        main.postDelayed(longRunnable, LONG_PRESS_MS)
                    }
                } else if (!longFired && s.longPress != FloatingAssistPrefs.KeyAction.NONE) {
                    // 部分设备用 repeat/isLongPress 表达长按，不等我们的计时器
                    fireLongPress()
                }
            }
            KeyEvent.ACTION_UP -> {
                main.removeCallbacks(longRunnable)
                if (longFired) { longFired = false; downSeen = false; return true }
                // 有些国产 ROM 的线控只发 UP、不发 DOWN。那种设备上长按判不出来（文案里说明），
                // 但单击/双击必须照常可用 —— 所以这里不要求 downSeen。
                downSeen = false
                tapCount++
                main.removeCallbacks(tapRunnable)
                // 双击这一档是 NONE 就没必要等窗口，立刻按单击走，手感不拖
                if (s.doubleTap == FloatingAssistPrefs.KeyAction.NONE) flushTaps()
                else main.postDelayed(tapRunnable, MULTI_TAP_WINDOW_MS)
            }
        }
        return true
    }

    /** 路由器直接给传输控制回调（不给原始 KeyEvent）时的退路：当一次单击。 */
    private fun onTransportPoke() {
        val app = appCtx ?: return
        val s = FloatingAssistPrefs.snapshot(app)
        if (!s.mediaKeyEnabled) return
        if (s.yieldToMusic && isMusicActive(app)) return   // 让键：什么都不做，也不转发（我们没有原始键可转）
        tapCount++
        main.removeCallbacks(tapRunnable)
        if (s.doubleTap == FloatingAssistPrefs.KeyAction.NONE) flushTaps()
        else main.postDelayed(tapRunnable, MULTI_TAP_WINDOW_MS)
    }

    private fun flushTaps() {
        val app = appCtx ?: return
        val n = tapCount
        tapCount = 0
        if (n <= 0) return
        val s = FloatingAssistPrefs.snapshot(app)
        run(app, if (n == 1) s.singleTap else s.doubleTap)
    }

    private fun fireLongPress() {
        val app = appCtx ?: return
        longFired = true
        tapCount = 0
        main.removeCallbacks(tapRunnable)
        run(app, FloatingAssistPrefs.snapshot(app).longPress)
    }

    private fun cancelPending() {
        tapCount = 0; longFired = false; downSeen = false
        main.removeCallbacks(tapRunnable)
        main.removeCallbacks(longRunnable)
    }

    // ── 让键给真正的播放器 ────────────────────────────────────────────────

    private fun isMusicActive(app: Context): Boolean = try {
        (app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.isMusicActive == true
    } catch (_: Exception) { false }

    /**
     * 把这次按键原样交给正在放歌的 App：先把自己的会话撤下去（否则转发出去的键会被我们自己再吃一遍），
     * 转发完隔一拍再装回来。[forwarding] 挡住转发期间的回环。
     *
     * 用 `dispatchMediaKeyEvent` 而不是自己找 MediaController：后者要「通知使用权」，
     * 为了让个键去要一个能读全机通知的权限，代价离谱。
     */
    private fun yieldKeyToPlayer(app: Context, keyCode: Int) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val ms = session
        forwarding = true
        try { ms?.isActive = false } catch (_: Exception) {}
        try {
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        } catch (_: Exception) {}
        // 800ms：够播放器把这次键消化掉，也不至于让"下一次按键"落空
        main.postDelayed({
            forwarding = false
            if (session != null) reassert()
        }, 800L)
    }

    // ── 动作分发（全部复用现成入口，一条新的状态通路都不加）────────────────

    private fun run(app: Context, action: FloatingAssistPrefs.KeyAction) {
        when (action) {
            FloatingAssistPrefs.KeyAction.NONE -> Unit
            FloatingAssistPrefs.KeyAction.VOICE -> launchVoice(app)
            FloatingAssistPrefs.KeyAction.NEW_CHAT -> runCatching {
                app.startActivity(
                    Intent(app, MainActivity::class.java)
                        .setAction(ACTION_NEW_CHAT)
                        .putExtra(XtomWidget.EXTRA_NEW_CHAT, true)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            // 现成的进程级桥，ChatScreen 的收集器接住即 sendJob.cancel()（见 CapsuleActionBridge）
            FloatingAssistPrefs.KeyAction.STOP_GEN -> CapsuleActionBridge.requestStop()
            FloatingAssistPrefs.KeyAction.TOGGLE_BALL -> FloatingChatBall.toggle(app)
        }
    }

    /**
     * 唤起语音助手。两条现成的路，按「在后台能不能可靠弹出来」排序：
     *
     *  1. 有悬浮窗权限 → [WakeOverlayHost]（TYPE_APPLICATION_OVERLAY 承载 WakeAssistantScreen）。
     *     后台 startActivity 会被国产 ROM 的「后台弹出界面」拦；悬浮窗不受该限制，
     *     而且**不把用户当前的 App 切走** —— 按线控的场景多半正开着别的东西。
     *  2. 否则 → [WakeAssistantActivity]（清单里已声明 showWhenLocked/turnScreenOn，锁屏也能起）。
     *     再失败则回落到 MainActivity + `WakeService.EXTRA_WAKE`，和磁贴/小组件同一条路。
     *
     * ⚠ 关于"屏幕都不用亮"：两条路都会把屏幕点亮（浮层带 FLAG_TURN_SCREEN_ON，Activity 带 turnScreenOn）。
     * 真正的"全程黑屏对话"需要一条无 UI 的语音回合，那是另一件事、要动现有文件，这次不做。
     * 本条真正省掉的是「解锁 + 找到 App + 点进去」这三步。
     */
    private fun launchVoice(app: Context) {
        if (FloatingChatBall.hasOverlayPermission(app)) { WakeOverlayHost.show(app); return }
        val ok = runCatching {
            app.startActivity(
                Intent(app, WakeAssistantActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }.isSuccess
        if (!ok) runCatching {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .setAction(ACTION_VOICE)
                    .putExtra(WakeService.EXTRA_WAKE, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    // 私有 action：这里是直接 startActivity（不是 PendingIntent），不存在互相顶掉的问题，
    // 带上只是为了在日志/最近任务里一眼分得出这次前台化是谁触发的。路由仍然只看 extra。
    private const val ACTION_VOICE = "com.arix.app.action.MEDIAKEY_VOICE"
    private const val ACTION_NEW_CHAT = "com.arix.app.action.MEDIAKEY_NEW_CHAT"
}
