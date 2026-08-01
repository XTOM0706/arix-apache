package com.arix.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloseFullscreen
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.arix.app.ui.rememberFrameProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 常驻悬浮球助手：一个能拖的小球浮在任何 App 之上，点开就是「输入框 + 发送 + 最近几条消息」的紧凑面板。
 * 不用喊唤醒词、不用切 App。
 *
 * ════════ 骨架从哪儿来 ════════
 *
 * 加窗 / 拖动 / 权限判断 / 自己充当 Lifecycle·SavedState·ViewModelStore 宿主这套样板，
 * 直接照搬 [FloatingScreenOcrButton]（它已经是一个可拖动的常驻小球，只是干的是取字）。
 * 本文件相对它多出来的只有四件事，其余一行都没重写：
 *
 *  1. **可聚焦切换**。取字钮永远带 `FLAG_NOT_FOCUSABLE`（它不需要打字）。我们要输入框，
 *     所以收起时保持 NOT_FOCUSABLE（触摸能穿到下面的 App），展开时摘掉它让输入法能弹。
 *  2. **松手贴边**。用 [Choreographer] 手推帧，不用 `animate*AsState`——项目铁律：手表上系统
 *     「动画时长缩放」常为 0，标准 tween 会直接跳到终值（见 ui/FrameMotion.kt 的说明）。
 *     窗口位置是 `WindowManager.LayoutParams.x`，不是 Compose 状态，所以这里用 Choreographer
 *     而不是 `rememberFrameFloat`，原理一样（帧回调不受动画缩放影响）。面板内部的动画仍走 FrameMotion。
 *  3. **底部删除区**。收起态的球窗口是 WRAP_CONTENT 的小窗，画不出屏幕底部的删除区；把球窗口撑成全屏
 *     又会把整块屏幕的触摸全吃掉（悬浮窗按窗口矩形吃触摸，Compose 透明不透明无关）。所以删除区是
 *     **第二个只在拖动期间存在的窗口**，带 `FLAG_NOT_TOUCHABLE`（纯视觉，不吃任何触摸）。
 *  4. **面板能全屏**。手表屏幕小，紧凑面板一弹输入法就没地方了，给一个全屏切换。
 *
 * ════════ 发送走哪条链路（不在浮层里另写一套调模型的代码）════════
 *
 * 面板里有两颗键，各自复用一条**现成**的链路：
 *
 *  · **发送（打字）** → [CapsuleActionBridge.submitInput] + 把 MainActivity 拉前台。
 *    这就是实时胶囊「岛内直接回复」用的那条路，逐字照抄 [CapsuleActionReceiver] 的做法：
 *    文本落进保留最后值的 StateFlow，ChatScreen 的收集器接住就按正常聊天发出去 ——
 *    工具循环、记忆注入、角色卡、入库、超级岛全都是聊天页那一套，我们一行模型代码都没写。
 *    ⚠ 代价说清楚：这条路**会把 App 切到前台**（不切的话没人消费 pendingInput，全项目只有
 *    `ChatScreen` 一个收集器）。要"打字也不切 App"需要给 WakeAssistantScreen 加一个预填参数，
 *    那要改现有文件，本次不做（最终回复里给了可选的三行补丁）。
 *
 *  · **麦克风 / 就地对话** → [WakeOverlayHost.show]。它把 `WakeAssistantScreen` 装进
 *    TYPE_APPLICATION_OVERLAY 悬浮窗里，**盖在任何 App 之上、完全不切 App**，
 *    语音 + 打字 + 工具 + TTS 全都在，而且天然是全屏的。
 *    「不用切 App 的完整对话」这条需求实际上是它满足的，小球只是入口。
 *
 * 「最近几条消息」是**只读展示**，从现有会话库里取（最近一条活跃会话的尾部几条），
 * 只在展开的那一刻读一次，收起就丢掉 —— 不订阅、不轮询。
 *
 * ════════ 耗电 ════════
 *
 * 收起时：一个 WRAP_CONTENT 的静态 Surface，没有任何动画循环、没有协程、没有订阅、没有帧回调。
 * 拖动时才有 Choreographer 帧回调，松手即停。展开时才有那一次 DB 读。
 * 唯一常驻的是 [XtomOverlayService] 的一条前台通知（那是"进程别被杀"的代价，不是计算）。
 */
@SuppressLint("StaticFieldLeak", "ClickableViewAccessibility")
object FloatingChatBall : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private var restored = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile private var view: ComposeView? = null
    @Volatile private var trashView: ComposeView? = null
    @Volatile private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    @Volatile private var appCtx: Context? = null

    /** 球的直径（dp）。手表上再小就点不准了，再大就挡内容。 */
    private const val BALL_DP = 48
    /** 删除区高度（dp）。球心落进这一条就算"拖到删除区"。 */
    private const val TRASH_DP = 108

    // --- UI 状态（Compose 观察）---
    private var expanded by mutableStateOf(false)
    private var fullScreen by mutableStateOf(false)
    private var dragging by mutableStateOf(false)
    private var overTrash by mutableStateOf(false)
    private var input by mutableStateOf("")
    private var loadingRecent by mutableStateOf(false)
    private val recent = mutableStateListOf<Pair<Boolean, String>>()   // (是不是我说的, 文本)

    val isShowing: Boolean get() = view != null

    /** 供 [MediaKeyController] 判断「本 App 正在被用」——面板开着算在用。 */
    val isPanelOpen: Boolean get() = view != null && expanded

    // ── 权限（照抄现有浮层的判断写法，绝不静默失败）──────────────────────

    fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun requestOverlayPermission(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (_: Exception) {}
    }

    // ── 生命周期 ─────────────────────────────────────────────────────────

    /** 显示悬浮球。**无悬浮窗权限返回 false**，调用方据此去引导授权（设置页会弹去授权的行）。 */
    fun show(context: Context): Boolean {
        val app = context.applicationContext
        if (!hasOverlayPermission(app)) return false
        main.post { ensureWindow(app) }
        return true
    }

    fun hide() {
        main.post {
            removeTrash()
            val v = view ?: return@post
            try { wm?.removeView(v) } catch (_: Exception) {}
            view = null; wm = null; lp = null
            expanded = false; fullScreen = false; dragging = false; overTrash = false
            input = ""; recent.clear(); loadingRecent = false
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    fun toggle(context: Context): Boolean = if (isShowing) { hide(); false } else show(context)

    private fun ensureWindow(app: Context) {
        if (view != null) return
        try {
            appCtx = app.applicationContext
            if (!restored) { savedStateController.performRestore(null); restored = true }
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            fullScreen = FloatingAssistPrefs.snapshot(app).ballFullScreenPanel

            val cv = ComposeView(app).apply {
                setViewTreeLifecycleOwner(this@FloatingChatBall)
                setViewTreeViewModelStoreOwner(this@FloatingChatBall)
                setViewTreeSavedStateRegistryOwner(this@FloatingChatBall)
                setContent {
                    com.arix.app.theme.XtomTheme {
                        if (expanded) Panel(app) else Ball(app)
                    }
                }
                // 展开态是可聚焦窗口：返回键要能收起面板（不然只能点关闭钮）
                setOnKeyListener { _, keyCode, event ->
                    if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && expanded) {
                        collapse(); true
                    } else false
                }
                // 展开态带 FLAG_WATCH_OUTSIDE_TOUCH：点面板外面收起。
                // ACTION_OUTSIDE 只在触摸落在窗口矩形之外时才来，不会和 Compose 的手势打架（其余一律 return false）。
                setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_OUTSIDE && expanded) { collapse(); true } else false
                }
            }

            val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val p = collapsedParams(app)
            manager.addView(cv, p)
            wm = manager; lp = p; view = cv
        } catch (e: Exception) {
            com.arix.wake.WakeLog.d("悬浮球显示失败: ${e.message}")
        }
    }

    // ── 窗口参数：收起 / 紧凑面板 / 全屏面板 ───────────────────────────────

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(app: Context, v: Int): Int = (v * app.resources.displayMetrics.density).roundToInt()

    /**
     * 收起态：小窗 + `FLAG_NOT_FOCUSABLE`（不抢输入法）+ `FLAG_NOT_TOUCH_MODAL`
     * （窗口外的触摸照常落到下面的 App —— 少了这条，小球会把整块屏幕锁死）。
     */
    private fun collapsedParams(app: Context): WindowManager.LayoutParams {
        val dm = app.resources.displayMetrics
        val ball = dp(app, BALL_DP)
        val savedX = FloatingAssistPrefs.ballX(app)
        val savedY = FloatingAssistPrefs.ballY(app)
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (savedX >= 0) savedX.coerceIn(0, (dm.widthPixels - ball).coerceAtLeast(0)) else (dm.widthPixels - ball)
            y = if (savedY >= 0) savedY.coerceIn(0, (dm.heightPixels - ball).coerceAtLeast(0)) else (dm.heightPixels / 3)
        }
    }

    /** 展开态：可聚焦（摘掉 NOT_FOCUSABLE 输入法才弹得出来）+ ADJUST_RESIZE + 点外面收起。 */
    private fun applyExpandedParams(app: Context) {
        val p = lp ?: return; val v = view ?: return
        p.flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        if (fullScreen) {
            p.width = WindowManager.LayoutParams.MATCH_PARENT
            p.height = WindowManager.LayoutParams.MATCH_PARENT
            p.gravity = Gravity.TOP or Gravity.START
            p.x = 0; p.y = 0
        } else {
            p.width = WindowManager.LayoutParams.MATCH_PARENT
            p.height = WindowManager.LayoutParams.WRAP_CONTENT
            p.gravity = Gravity.BOTTOM or Gravity.START
            p.x = 0; p.y = 0
        }
        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    private fun expand(app: Context) {
        if (expanded) return
        expanded = true
        applyExpandedParams(app)
        loadRecent(app)
    }

    private fun collapse() {
        if (!expanded) return
        val app = appCtx ?: return
        expanded = false
        input = ""
        recent.clear()          // 收起就把内容丢掉：不留残留、不占内存
        loadingRecent = false
        val p = lp ?: return; val v = view ?: return
        val fresh = collapsedParams(app)
        p.flags = fresh.flags; p.width = fresh.width; p.height = fresh.height
        p.gravity = fresh.gravity; p.x = fresh.x; p.y = fresh.y
        p.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED
        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    private fun setFullScreen(app: Context, on: Boolean) {
        fullScreen = on
        FloatingAssistPrefs.save(app, FloatingAssistPrefs.snapshot(app).copy(ballFullScreenPanel = on))
        if (expanded) applyExpandedParams(app)
    }

    // ── 拖动 / 贴边 / 删除区 ──────────────────────────────────────────────

    private fun beginDrag(app: Context) {
        dragging = true
        overTrash = false
        addTrash(app)
    }

    private fun moveBy(app: Context, dx: Float, dy: Float) {
        val p = lp ?: return; val v = view ?: return
        val dm = app.resources.displayMetrics
        val ball = dp(app, BALL_DP)
        p.x = (p.x + dx.roundToInt()).coerceIn(-ball / 3, dm.widthPixels - ball * 2 / 3)
        p.y = (p.y + dy.roundToInt()).coerceIn(0, (dm.heightPixels - ball).coerceAtLeast(0))
        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
        overTrash = inTrash(app, p)
    }

    private fun inTrash(app: Context, p: WindowManager.LayoutParams): Boolean {
        val dm = app.resources.displayMetrics
        val ball = dp(app, BALL_DP)
        val centerY = p.y + ball / 2
        val centerX = p.x + ball / 2
        // 只认屏幕正中那一段的底部：贴着左右边往下拖是"挪到角落"，不该被当成删除
        val midBand = abs(centerX - dm.widthPixels / 2) < dm.widthPixels / 3
        return midBand && centerY > dm.heightPixels - dp(app, TRASH_DP)
    }

    private fun endDrag(app: Context) {
        dragging = false
        removeTrash()
        val p = lp ?: return
        if (overTrash) {
            overTrash = false
            // 拖进删除区 = 用户明确不要它了：不只是这次关掉，开关也一并落下去，
            // 否则重启/开机自恢复又把它放回来 —— 那是"关不掉"，用户会以为坏了。
            FloatingAssistPrefs.setBallEnabled(app, false)
            hide()
            XtomOverlayService.sync(app)
            return
        }
        // 松手贴边：贴到左右哪一边由球心在屏幕的哪半边决定
        val dm = app.resources.displayMetrics
        val ball = dp(app, BALL_DP)
        val target = if (p.x + ball / 2 < dm.widthPixels / 2) 0 else (dm.widthPixels - ball)
        animateX(app, target)
    }

    /**
     * 贴边动画：[Choreographer] 手推帧。**不能用 tween/animateFloatAsState** ——
     * 手表上系统动画缩放常为 0，那会直接跳到终值（项目里为此栽过多次，见 ui/FrameMotion.kt）。
     * 帧回调不吃动画缩放，和 FrameMotion 是同一个原理，只是作用对象是窗口参数不是 Compose 状态。
     * 动画期间不做任何别的事，跑完即停（不常驻帧回调）。
     */
    private fun animateX(app: Context, toX: Int) {
        val p0 = lp ?: return
        val fromX = p0.x
        if (fromX == toX) { saveSpot(app); return }
        val durationMs = 220f
        val choreographer = Choreographer.getInstance()
        var start = 0L
        choreographer.postFrameCallback(object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val p = lp; val v = view
                if (p == null || v == null || dragging || expanded) return   // 被打断就地停
                if (start == 0L) start = frameTimeNanos
                val t = ((frameTimeNanos - start) / 1_000_000f / durationMs).coerceIn(0f, 1f)
                val e = 1f - (1f - t) * (1f - t) * (1f - t)   // easeOutCubic
                p.x = (fromX + (toX - fromX) * e).roundToInt()
                try { wm?.updateViewLayout(v, p) } catch (_: Exception) { return }
                if (t < 1f) choreographer.postFrameCallback(this)
                else { p.x = toX; try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}; saveSpot(app) }
            }
        })
    }

    private fun saveSpot(app: Context) {
        val p = lp ?: return
        FloatingAssistPrefs.saveBallSpot(app, p.x, p.y)
    }

    /** 删除区：**独立的、只在拖动期间存在的**窗口，FLAG_NOT_TOUCHABLE = 纯视觉、不吃任何触摸。 */
    private fun addTrash(app: Context) {
        if (trashView != null) return
        try {
            val cv = ComposeView(app).apply {
                setViewTreeLifecycleOwner(this@FloatingChatBall)
                setViewTreeViewModelStoreOwner(this@FloatingChatBall)
                setViewTreeSavedStateRegistryOwner(this@FloatingChatBall)
                setContent { com.arix.app.theme.XtomTheme { TrashZone() } }
            }
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                dp(app, TRASH_DP),
                overlayType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL }
            wm?.addView(cv, p)
            trashView = cv
        } catch (_: Exception) {}
    }

    private fun removeTrash() {
        val t = trashView ?: return
        trashView = null
        try { wm?.removeView(t) } catch (_: Exception) {}
    }

    // ── 发送链路（见类注释：两条都是现成的，浮层里没有一行调模型的代码）──────

    /**
     * 打字发送：文本进 [CapsuleActionBridge]（保留最后值的 StateFlow），再把 MainActivity 拉前台
     * 让 ChatScreen 的收集器接走 —— 与实时胶囊「岛内直接回复」完全同一条路。
     */
    private fun sendTyped(app: Context, text: String) {
        val t = text.trim()
        if (t.isBlank()) return
        CapsuleActionBridge.submitInput(t)
        runCatching {
            app.startActivity(
                Intent(app, MainActivity::class.java)
                    .setAction(ACTION_BALL_SEND)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
        collapse()
    }

    /** 就地对话：拉起唤醒助手浮层（盖在当前 App 之上，不切走），语音/打字/工具/TTS 全在那边。 */
    private fun openAssistant(app: Context) {
        collapse()
        WakeOverlayHost.show(app)
    }

    /** 展开时读一次「最近几条」。只读展示，不订阅、不轮询；收起即丢。 */
    private fun loadRecent(app: Context) {
        if (loadingRecent) return
        loadingRecent = true
        recent.clear()
        scope.launch {
            try {
                val rows: List<Pair<Boolean, String>> = withContext(Dispatchers.IO) {
                    val mgr = ConversationManager(app)
                    val id = mgr.repo.getMostRecentActiveId() ?: return@withContext emptyList()
                    mgr.loadMessages(id)
                        .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
                        .takeLast(4)
                        .map { (it.role == "user") to it.content.trim().take(160) }
                }
                if (expanded) { recent.clear(); recent.addAll(rows) }
            } catch (c: CancellationException) {
                throw c              // 别吞取消：面板关了就该停
            } catch (_: Exception) {
                // 读不出来就空着，不打扰用户 —— 这只是个便利展示，不是功能主体
            } finally {
                loadingRecent = false
            }
        }
    }

    // ══════════════════ UI ══════════════════

    @Composable
    private fun Ball(app: Context) {
        val scheme = MaterialTheme.colorScheme
        Surface(
            shape = CircleShape,
            color = if (overTrash) scheme.error else scheme.primary,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier
                .size(BALL_DP.dp)
                .graphicsLayer { alpha = if (dragging) 0.85f else 1f }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { beginDrag(app) },
                        onDrag = { change, drag -> change.consume(); moveBy(app, drag.x, drag.y) },
                        onDragEnd = { endDrag(app) },
                        onDragCancel = { endDrag(app) },
                    )
                }
                .pointerInput(Unit) {
                    // 点开面板；长按也直接进面板（长按主要用来起拖，拖不动就当点开，别让用户白按一下）
                    detectTapGestures(onTap = { expand(app) }, onLongPress = { expand(app) })
                },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    if (overTrash) Icons.Outlined.DeleteOutline else Icons.Outlined.SmartToy,
                    contentDescription = tr("助手悬浮球"),
                    tint = if (overTrash) scheme.onError else scheme.onPrimary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }

    @Composable
    private fun TrashZone() {
        val scheme = MaterialTheme.colorScheme
        // 入场用帧驱动进度（手表上动画缩放=0 时 tween 不动，见 FrameMotion）
        val p = rememberFrameProgress(key = Unit, durationMs = 180)
        Box(
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = p.value },
            contentAlignment = Alignment.BottomCenter,
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                color = (if (overTrash) scheme.error else scheme.surfaceContainerHighest).copy(alpha = 0.92f),
                modifier = Modifier.fillMaxWidth().height(TRASH_DP.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxSize().padding(bottom = 18.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Outlined.DeleteOutline, contentDescription = null,
                        tint = if (overTrash) scheme.onError else scheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (overTrash) tr("松手关闭悬浮球") else tr("拖到这里关闭"),
                        color = if (overTrash) scheme.onError else scheme.onSurfaceVariant,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }

    @Composable
    private fun Panel(app: Context) {
        val scheme = MaterialTheme.colorScheme
        val p = rememberFrameProgress(key = fullScreen, durationMs = 220)
        Surface(
            shape = if (fullScreen) RoundedCornerShape(0.dp) else RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp),
            color = scheme.surface,
            border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
            tonalElevation = 6.dp,
            shadowElevation = 12.dp,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fullScreen) Modifier.fillMaxSize() else Modifier)
                .graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 40f },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (fullScreen) Modifier.fillMaxSize().statusBarsPadding() else Modifier)
                    .imePadding()
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                // 顶部：标题 + 全屏切换 + 关闭
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.SmartToy, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        tr("快速对话"), color = scheme.onSurface, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f),
                    )
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).clickable { setFullScreen(app, !fullScreen) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            if (fullScreen) Icons.Outlined.CloseFullscreen else Icons.Outlined.OpenInFull,
                            contentDescription = if (fullScreen) tr("退出全屏") else tr("全屏"),
                            tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
                        )
                    }
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).clickable { collapse() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Outlined.Close, tr("收起"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }

                Spacer(Modifier.height(6.dp))

                // 最近几条（只读）。空着就什么都不占，手表上寸土寸金。
                Box(
                    modifier = if (fullScreen) Modifier.fillMaxWidth().weight(1f)
                    else Modifier.fillMaxWidth().heightIn(max = 168.dp)
                ) {
                    if (loadingRecent && recent.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(40.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = scheme.primary, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        }
                    } else {
                        Column(
                            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.Bottom),
                        ) {
                            recent.forEach { (mine, text) -> RecentLine(mine, text) }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                // 输入胶囊（视觉对齐 WakeAssistantActivity 的 WakeInputCapsule）
                Surface(
                    color = scheme.surfaceContainerHigh,
                    shape = RoundedCornerShape(24.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.6f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        BasicTextField(
                            value = input,
                            onValueChange = { input = it },
                            modifier = Modifier.weight(1f).padding(horizontal = 10.dp).heightIn(min = 20.dp, max = 96.dp),
                            textStyle = TextStyle(color = scheme.onSurface, fontSize = 15.sp),
                            cursorBrush = SolidColor(scheme.primary),
                            maxLines = 4,
                            decorationBox = { inner ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (input.isEmpty()) Text(tr("说点什么…"), color = scheme.onSurfaceVariant, fontSize = 15.sp)
                                    inner()
                                }
                            },
                        )
                        // 麦克风 = 就地对话（唤醒助手浮层，不切 App）
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape)
                                .background(scheme.surfaceContainerHighest)
                                .clickable { openAssistant(app) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Outlined.Mic, tr("就地语音对话"), tint = scheme.primary, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        // 发送 = 送进主聊天（会把 App 拉到前台，见类注释）
                        Box(
                            modifier = Modifier.size(36.dp).clip(CircleShape)
                                .background(if (input.isBlank()) scheme.surfaceContainerHighest else scheme.primary)
                                .clickable { sendTyped(app, input) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.Send, tr("发送"),
                                tint = if (input.isBlank()) scheme.onSurfaceVariant else scheme.onPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun RecentLine(mine: Boolean, text: String) {
        val scheme = MaterialTheme.colorScheme
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (mine) scheme.primary.copy(alpha = 0.16f) else scheme.surfaceContainerHigh,
            ) {
                Text(
                    text,
                    color = scheme.onSurface, fontSize = 12.sp, maxLines = 3,
                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                )
            }
        }
    }

    /** 私有 action：不影响路由（MainActivity 是 singleTask、只看 extra），纯粹是为了在日志/最近任务里
     *  一眼分得出这次前台化是谁触发的。 */
    private const val ACTION_BALL_SEND = "com.arix.app.action.BALL_SEND"
}
