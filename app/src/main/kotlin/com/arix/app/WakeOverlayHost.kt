package com.arix.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
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

/**
 * 唤醒后的 Siri 式浮层：用 [WindowManager] 加一个 TYPE_APPLICATION_OVERLAY 悬浮窗承载
 * Compose UI（[WakeAssistantScreen]），而**不是**启动 Activity。
 *
 * 为什么不用 Activity：后台 startActivity 被国产 OEM「后台弹出界面」限制拦；全屏 intent
 * 通知只在锁屏/息屏时才自动弹。悬浮窗只要授予悬浮窗权限，就能从后台任意亮屏场景可靠弹出，
 * 不受 Activity 启动限制。
 *
 * 单例充当 Lifecycle/SavedState/ViewModelStore 宿主，供 ComposeView 在 window 里跑。
 */
@SuppressLint("StaticFieldLeak")
object WakeOverlayHost : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private var restored = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var view: ComposeView? = null
    @Volatile private var wm: WindowManager? = null
    @Volatile private var lp: WindowManager.LayoutParams? = null

    // 「让位」引用计数：权限框弹出 / ui_control dump 期间请求本窗口不吃触摸、不抢焦点（见 [pushPassive]）。
    // 两个来源可能同时请求，计数归零才恢复。
    private val passiveCount = java.util.concurrent.atomic.AtomicInteger(0)

    val isShowing: Boolean get() = view != null

    /** 跨窗口模糊半径(px)。手表小屏 64px 已足够磨砂又不过糊；改这个值必须真机确认（项目铁律「改玻璃参数必须真机确认」）。 */
    private const val WAKE_BLUR_RADIUS_PX = 64

    /** 在悬浮窗里显示浮层助手。可从任意线程调用（内部切主线程）。 */
    fun show(context: Context) {
        val app = context.applicationContext
        main.post {
            if (view != null) return@post // 已显示，避免重复叠加
            try {
                if (!restored) {
                    savedStateController.performRestore(null)
                    restored = true
                }
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED

                val cv = ComposeView(app).apply {
                    setViewTreeLifecycleOwner(this@WakeOverlayHost)
                    setViewTreeViewModelStoreOwner(this@WakeOverlayHost)
                    setViewTreeSavedStateRegistryOwner(this@WakeOverlayHost)
                    isFocusableInTouchMode = true
                    setContent {
                        com.arix.app.theme.XtomTheme {
                            WakeAssistantScreen(onClose = { hide() }, onOpenChat = {
                                app.startActivity(
                                    Intent(app, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                )
                                hide()
                            })
                        }
                    }
                    // 返回键关闭浮层
                    setOnKeyListener { _, keyCode, event ->
                        if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { hide(); true } else false
                    }
                }

                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

                val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                // 跨窗口模糊（Android 12+）：把浮层背后别的 App 真磨砂。haze/Compose 只能采样自己窗口内的像素，
                // 采不到背后别的 App —— 「浮层里玻璃模糊丢失」的根因；真糊只能靠窗口级 FLAG_BLUR_BEHIND。
                // 三条都成立才开：SDK≥31、用户开了「实时模糊」设置、且系统当前允许跨窗口模糊
                // （isCrossWindowBlurEnabled：开发者选项关模糊 / 低端机 / 省电时为 false）。任一不成立 → 优雅退回仅 dim 压暗，不崩。
                val blurOn = crossBlurAllowed(app, manager)
                // 可聚焦（打字弹输入法）+ 息屏/锁屏也亮起来弹 + LAYOUT_IN_SCREEN 铺满整屏。
                // FLAG_DIM_BEHIND：把窗口背后的桌面统一压暗（桌面仍透出可见，只是变暗提升前景可读），不盖死。
                // SHOW_WHEN_LOCKED 仅在"锁屏唤起"开关打开时加——关掉则不越过锁屏，等解锁后才显示。
                // LAYOUT_IN_SCREEN + LAYOUT_NO_LIMITS：铺满整个屏幕含状态栏/导航栏区，否则 MATCH_PARENT 被系统栏
                // inset 限制，上下各露一条系统栏高度的黑边（内容 fillMaxSize 覆盖全屏，statusBarsPadding 负责避让）。
                var flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND
                if (blurOn) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                if (WakeService.lockScreenWakeEnabled(app))
                    flags = flags or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    flags,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    // 有真模糊时少压点暗，让磨砂更通透；没模糊时靠 dim 兜住可读性
                    dimAmount = if (blurOn) 0.25f else 0.4f
                    if (blurOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blurBehindRadius = WAKE_BLUR_RADIUS_PX
                    // 绘进刘海/挖孔区，避免顶部留黑边
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }

                manager.addView(cv, params)
                wm = manager
                lp = params
                view = cv
                passiveCount.set(0)
                // 尽力隐藏状态栏 + 手势小白条（悬浮窗未必被系统采纳，采纳则更沉浸；不采纳靠不透明底色兜底）
                try {
                    androidx.core.view.ViewCompat.getWindowInsetsController(cv)?.apply {
                        hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                        systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } catch (_: Exception) {}
                com.arix.wake.WakeLog.d("浮层已显示（悬浮窗 overlay）")
            } catch (e: Exception) {
                com.arix.wake.WakeLog.d("浮层显示失败: ${e.message}")
            }
        }
    }

    /** 临时隐藏/恢复浮层内容（拉起系统选择器/相机时用，避免悬浮窗层级遮住系统 UI）。不销毁，只切可见性。 */
    fun setContentVisible(visible: Boolean) {
        main.post { view?.visibility = if (visible) android.view.View.VISIBLE else android.view.View.GONE }
    }

    /**
     * 让唤醒浮层「让位」：临时给窗口加 FLAG_NOT_FOCUSABLE|FLAG_NOT_TOUCHABLE。两处用它：
     *  ① 权限框弹出：本窗口铺满全屏又可聚焦（focusable 即 touch-modal），会把落在权限按钮上的触摸整个吃掉
     *     —— 窗口大半透明，用户看得见权限按钮却点不动。让位后触摸穿透本窗口，落到下面的权限窗口。
     *  ② ui_control dump：可聚焦窗口会成为无障碍的 activeWindow，`svc.dump()` 读的正是它 = 读到我们自己。
     *     让位（失焦）后 activeWindow 交还给背后真正的 App。
     * **不 removeView**：唤醒页承载着正在跑这轮工具的协程（rememberCoroutineScope），摘窗会连它一起销毁、
     * 直接把正在执行的 ui_control 取消掉。引用计数：权限框和 dump 可能各自请求，计数归零才恢复。
     */
    fun pushPassive() { main.post { if (passiveCount.getAndIncrement() == 0) applyPassive(true) } }
    fun popPassive() { main.post { if (passiveCount.decrementAndGet() <= 0) { passiveCount.set(0); applyPassive(false) } } }

    /**
     * 打字模式开关：输入框获得焦点时 `setImeMode(true)`，失焦时 `setImeMode(false)`。窗口没显示时为空操作
     * （Activity / 数字助手会话两条宿主路径调用同样安全）。
     *
     * 本窗口平时带 [WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS]（铺满整屏、消掉上下系统栏黑边），
     * 但该 flag 同时让窗口不受 insets 约束：系统既不给 ime inset、也不按 [WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE]
     * 缩窗口，于是 Compose 的 `WindowInsets.ime` 恒为 0、内容根上的 `imePadding()` 变成空操作 ——
     * 键盘弹起后把输入胶囊整个盖住。这里在打字期间临时摘掉 NO_LIMITS 让 ADJUST_RESIZE / ime inset 重新生效，
     * 收起键盘再加回来：「没有黑边」的观感只在打字这几秒让位，其余时间保持原样。
     */
    fun setImeMode(on: Boolean) {
        main.post {
            val v = view ?: return@post
            val p = lp ?: return@post
            val bit = WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            val want = if (on) p.flags and bit.inv() else p.flags or bit
            if (want == p.flags) return@post
            p.flags = want
            try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
        }
    }

    private fun applyPassive(passive: Boolean) {
        val v = view ?: return
        val p = lp ?: return
        val bits = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        p.flags = if (passive) p.flags or bits else p.flags and bits.inv()
        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    /** 跨窗口模糊是否可用：SDK≥31 且用户开了「实时模糊」设置 且系统当前允许（isCrossWindowBlurEnabled）。 */
    private fun crossBlurAllowed(app: Context, manager: WindowManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return WakeService.bgBlurEnabled(app) && manager.isCrossWindowBlurEnabled
    }

    fun hide() {
        main.post {
            val v = view ?: return@post
            try { wm?.removeView(v) } catch (_: Exception) {}
            view = null
            wm = null
            lp = null
            passiveCount.set(0)
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
            com.arix.wake.WakeLog.d("浮层关闭")
            WakeService.notifyOverlayClosed()
        }
    }
}
