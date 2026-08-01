package com.arix.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.arix.tool.ToolPermissionManager

/**
 * 后台时的工具权限框浮层。
 *
 * 为什么要它：AI 用 `app_launch` 打开别的 App 后，Arix 就退到后台了——而接下来的 `ui_control`
 * 恰恰要授权。那个 AlertDialog 属于 MainActivity 的 window，此刻压在别的 App 后面，用户看不见也点不到，
 * 于是整轮生成卡死（框还禁止了点外部关闭）。悬浮窗不受 Activity 启动限制，能盖在别的 App 之上，
 * 是这个场景下唯一能让用户真点到的通道。同 [WakeOverlayHost] 的思路，但那个是唤醒助手专用的全屏浮层。
 *
 * 只在「后台 + 有悬浮窗权限」时出现；前台照常由聊天页自己渲染框（[ToolPermissionDialog]）。
 * 越不过锁屏（TYPE_APPLICATION_OVERLAY 的固有限制），锁屏场景仍旧走超时判拒。
 */
@SuppressLint("StaticFieldLeak")
object PermissionOverlayHost : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

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

    /** 框此刻真的显示在最前面（权限系统据此决定「等不等得起」）。 */
    val isShowing: Boolean get() = view != null

    fun canShow(context: Context): Boolean =
        try { Settings.canDrawOverlays(context) } catch (_: Exception) { false }

    /** 有悬浮窗权限就把权限框弹到别的 App 之上；没权限返回 false（调用方据此走超时判拒）。 */
    fun showIfPossible(context: Context): Boolean {
        val app = context.applicationContext
        if (!canShow(app)) return false
        main.post {
            if (view != null) return@post          // 已显示，别叠加
            try {
                if (!restored) { savedStateController.performRestore(null); restored = true }
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED

                val cv = ComposeView(app).apply {
                    setViewTreeLifecycleOwner(this@PermissionOverlayHost)
                    setViewTreeViewModelStoreOwner(this@PermissionOverlayHost)
                    setViewTreeSavedStateRegistryOwner(this@PermissionOverlayHost)
                    isFocusableInTouchMode = true
                    setContent {
                        com.arix.app.theme.XtomTheme {
                            // 跟着 pending 走；收框由 ToolPermissionManager 在等待结束后调 hide() 负责
                            // （不在这儿调——那是在 composition 里做副作用）。
                            // 用 Panel 不用 Dialog：这儿只有 application context，起不了 Dialog 的窗。
                            val req by ToolPermissionManager.pending.collectAsState()
                            req?.let { ToolPermissionPanel(it) }
                        }
                    }
                }

                val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

                val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                // 跨窗口模糊（Android 12+）：给这个模态框一层磨砂背景，比纯 dim 更能把"背后的事先别管"表达清楚。
                // 只看系统当前是否允许（isCrossWindowBlurEnabled）——这是短暂的模态框，不受"实时模糊"那个省电开关约束；
                // 不支持则优雅退回加深的 dim，不崩。
                val blurOn = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && manager.isCrossWindowBlurEnabled
                // 要可聚焦：框上有按钮要点。铺满整屏让 AlertDialog 自己居中，背后压暗以示这是个模态决定。
                var flags = WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
                    WindowManager.LayoutParams.FLAG_DIM_BEHIND
                if (blurOn) flags = flags or WindowManager.LayoutParams.FLAG_BLUR_BEHIND
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    type,
                    flags,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    dimAmount = if (blurOn) 0.4f else 0.5f
                    if (blurOn && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) blurBehindRadius = 48
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
                        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }

                manager.addView(cv, lp)
                wm = manager
                view = cv
                // ① 权限框在唤醒浮层之上却点不到的修法：唤醒浮层铺满全屏又可聚焦(touch-modal)，会把
                // 落在权限按钮上的触摸整个吃掉。让它「让位」(失焦+不吃触摸)，触摸就穿透到本权限窗口。
                // 收框时(hide)配对 popPassive 恢复。它内部对"当前没显示唤醒浮层"是安全的空操作。
                WakeOverlayHost.pushPassive()
            } catch (_: Exception) {
                // 加窗失败（权限被回收/OEM 拦）→ 保持 view=null，权限系统那边照旧走超时判拒
                view = null; wm = null
            }
        }
        return true
    }

    fun hide() {
        main.post {
            val v = view ?: return@post
            try { wm?.removeView(v) } catch (_: Exception) {}
            view = null
            wm = null
            // 收框：解除唤醒浮层的「让位」，把触摸/焦点还给它（与 showIfPossible 里的 pushPassive 配对）。
            WakeOverlayHost.popPassive()
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }
}
