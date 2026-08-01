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
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * AI 屏幕操作可视化浮层：在 AI 真正点/滑中了的那个位置画高亮框、点击涟漪、滑动箭头，
 * 并在整段自动化期间给屏幕边缘挂一圈柔光晕，让用户随时知道「AI 正在动我的手机」。
 *
 * ## 最致命的一条：手势瞬间屏幕上不能有我们的窗口
 *
 * 旧实现是「浮层常挂着 + FLAG_NOT_TOUCHABLE」，那个假设是错的：
 * FLAG_NOT_TOUCHABLE 只保证**用户真手指**的触摸能穿透我们这层。而 AI 的手势是
 * AccessibilityService#dispatchGesture / shell `input` 注入的，走的是另一条派发链路；
 * 叠加 Android 12+ 的触摸遮挡策略（FLAG_WINDOW_IS_OBSCURED / touch occlusion），
 * 以及**被操作的目标 App 自己可能设了 filterTouchesWhenObscured**——
 * 只要屏幕上悬着一个来自其它应用的窗口，目标 View 就可能**静默丢弃**这次事件。
 * 失败是静默的、机型相关的、极难复现：可视化会把 AI 控制设备这个功能直接搞失灵，
 * 比没有可视化糟得多。
 *
 * 所以时序是硬约束（[detachForGesture] / [onEvent] / [scheduleHide]）：
 *  1. 注入任何手势**之前**：真正 removeView 把窗口摘掉。
 *     不是 visibility=GONE、不是 alpha=0、不是改 flags —— 那些系统侧窗口都还在，照样构成遮挡。
 *  2. 执行手势。
 *  3. **只有手势成功**才把窗口加回来画高亮。于是「看见高亮 == 这一下真的点中了」，
 *     可视化本身成了可信的状态反馈，而不是「AI 尝试点了这里」。
 *  4. 高亮播完再延后 [HIDE_AFTER_EVENT_MS] 撤窗口，让动画播得完，也让下一个动作到来前窗口已经不在。
 *
 * 正确性优先于性能：宁可多几次窗口增删，也绝不在手势瞬间留着窗口。
 *
 * ## 另外两条不能妥协的
 *  - 动画只能用 withFrameNanos 手推。手表上「动画缩放=0」很常见（省电模式 / A11 默认），
 *    tween 会直接跳终值 = 等于没画。项目踩过这个坑（见 ToolActivityPanel 转圈指示器）。
 *  - 任何失败都静默：没悬浮窗权限、加窗被拒、画崩了……一律当没有可视化，绝不能连累 AI 干活。
 */
@SuppressLint("StaticFieldLeak")
object UiActionOverlay : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private const val PREFS = "ui_action_overlay"
    private const val KEY_ENABLED = "enabled"

    /** 一次动作的高亮时长（毫秒）。够看清，又要短到能在 [HIDE_AFTER_EVENT_MS] 内播完。 */
    private const val ANIM_MS = 450f

    /**
     * 一条成功事件画完后再等这么久才撤窗口。
     * 取值要同时满足两头：> ANIM_MS（动画得播完），又要足够小，
     * 让下一个动作到来时窗口大概率已经自己下去了（真正的保证仍然是 [detachForGesture]）。
     */
    private const val HIDE_AFTER_EVENT_MS = 600L

    /**
     * 最后一次 ui_control 调用之后，还把「AI 正在操作」的光晕留这么久。
     * AI 的一轮自动化是「dump → 想 → 点 → dump」，两次工具调用之间隔着一次模型推理，
     * 光晕要撑过这个间隙才叫「常驻」，否则一闪一闪反而像故障。
     */
    private const val SESSION_IDLE_MS = 3_000L

    // ---- 坐标原点补偿（②）----
    //
    // 结论：本实现**不需要**减状态栏高度，常量恒为 0；理由写在下面，真机若发现整体下偏，
    // 只要把 COMPENSATE_STATUS_BAR 改成 true，一处生效（所有绘制都过 originOffsetY）。
    //
    // 依据：
    //  - 节点矩形来自 getBoundsInScreen，是**含状态栏的屏幕绝对坐标**，原点在物理屏左上角。
    //  - 我们的窗口用 FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS，且 API 30+ 额外调了
    //    setFitInsetsTypes(0)（见 ensureWindow）。这一句才是关键：Android 11 起，旧的 LAYOUT_IN_SCREEN
    //    系列 flag 已被翻译成 fitInsetsTypes，**默认值是「适配所有系统栏」**——窗口 frame 会被
    //    状态栏 inset 顶下去，于是 Canvas 原点落在内容区，画出来整体下偏一个状态栏高度。
    //    参考产品同时用了那两个 flag 却仍要手工减状态栏，几乎可以肯定就是漏了 fitInsetsTypes。
    //    我们显式清零 => 窗口 frame 就是整块屏 => Canvas 原点 == 屏幕 (0,0) == getBoundsInScreen 原点。
    //  - ComposeView 是裸加进 WindowManager 的，fitsSystemWindows 默认 false，自身不吃 inset，
    //    也不会再引入 padding。
    // 手表通常没有状态栏，statusBarHeightPx 取不到资源时优雅退化为 0，不会画歪。
    private const val COMPENSATE_STATUS_BAR = false

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

    /** 「AI 自动化进行中」——驱动常驻光晕。只在主线程读写，给 Compose 当 State 用。 */
    private val sessionState = mutableStateOf(false)

    private val hideRunnable = Runnable { removeWindowNow() }
    private val endSessionRunnable = Runnable {
        sessionState.value = false
        // 会话结束再给一点尾巴，让最后一次高亮/光晕淡完
        main.postDelayed(hideRunnable, HIDE_AFTER_EVENT_MS)
    }

    // ---- 开关（默认开：可视化是这个功能的卖点，要用户主动关）----

    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, v: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, v).apply()
        if (!v) hide()
    }

    fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun requestOverlayPermission(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (_: Exception) {}
    }

    private fun usable(app: Context) = isEnabled(app) && hasOverlayPermission(app)

    // 「开着但没授权」只提示一次/进程，别每次点击都弹（Toast 即便没悬浮窗权限也能显示，特殊窗口）。
    @Volatile private var warnedNoPerm = false
    private fun warnNoPermissionOnce(app: Context) {
        if (warnedNoPerm) return
        warnedNoPerm = true
        main.post {
            try {
                android.widget.Toast.makeText(
                    app,
                    tr("「操作可视化」已开但缺少「显示在其他应用上层」权限，所以看不到点击高亮。到 对话设置 → 操作可视化 重新开一次即可授权。"),
                    android.widget.Toast.LENGTH_LONG,
                ).show()
            } catch (_: Exception) {}
        }
    }

    // ---- 会话（③ 常驻提示）----

    /**
     * ui_control 开始干活了。只置位 + 挂光晕；真正的安全保证在 [detachForGesture]，
     * 光晕窗口和高亮窗口是**同一个**窗口，所以它也自动遵守「手势前必须摘掉」的时序。
     */
    fun beginSession(app: Context) {
        val ctx = app.applicationContext
        if (!usable(ctx)) return
        main.post {
            main.removeCallbacks(endSessionRunnable)
            main.removeCallbacks(hideRunnable)
            sessionState.value = true
            ensureWindow(ctx)
        }
    }

    /**
     * 一次 ui_control 调用结束：此刻**没有手势在飞**，可以安全地把光晕窗口挂回来。
     * 下一个动作来时 [detachForGesture] 会再把它摘掉，所以这里挂回来不会造成遮挡。
     */
    fun resumeSessionIndicator(app: Context) {
        val ctx = app.applicationContext
        if (!usable(ctx)) return
        main.post { if (sessionState.value) ensureWindow(ctx) }
    }

    /** 一轮自动化多半结束了：等一会儿没新动作就熄灭光晕收窗口（下一次 beginSession 会取消它）。 */
    fun endSessionSoon(app: Context) {
        main.post {
            main.removeCallbacks(endSessionRunnable)
            main.postDelayed(endSessionRunnable, SESSION_IDLE_MS)
        }
    }

    // ---- ① 手势前物理摘窗 ----

    /**
     * **注入任何手势之前必须先 await 这个函数。** 见类注释「最致命的一条」。
     *
     * 用 removeViewImmediate 而不是 removeView：后者把销毁排进消息队列，
     * 函数返回时系统侧窗口很可能还在，等于没摘。摘完再多让一帧，等 WMS 那边真的完成移除。
     * 全程吞异常（CancellationException 除外）：摘窗失败也绝不能挡住 AI 的手势。
     */
    suspend fun detachForGesture() {
        try {
            val had = withContext(Dispatchers.Main.immediate) {
                val up = view != null
                main.removeCallbacks(hideRunnable)
                removeWindowNow()
                up
            }
            // 没挂过就不必白等一帧——密集操作时省掉无谓的延迟
            if (had) delay(GESTURE_SETTLE_MS)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            com.arix.wake.WakeLog.d("操作可视化摘窗失败（不影响手势）: ${e.message}")
        }
    }

    /** 摘窗后留给 WMS 真正移除窗口的余量。约一帧多一点。 */
    private const val GESTURE_SETTLE_MS = 24L

    // ---- 事件 ----

    /**
     * 由 [UiActionBus.emit] 调用。
     *
     * [success] 为假就**不画**：看得见高亮就代表真的点中了，这条语义比「画点什么」值钱得多。
     */
    fun onEvent(app: Context, success: Boolean) {
        val ctx = app.applicationContext
        // 「开着却缺悬浮窗权限」是这功能最常见的静默失效：开关默认开，但只有手动拨动开关那次才去要权限，
        // 默认开着又从没拨过的用户永远没授权 → 什么都不画、还以为坏了。这里点一次名，把黑箱变成可诊断。
        if (isEnabled(ctx) && !hasOverlayPermission(ctx)) { warnNoPermissionOnce(ctx); return }
        if (!usable(ctx)) return
        if (!success) {
            // 失败不加窗，但会话还活着——光晕留着，等下一次 resumeSessionIndicator 挂回来
            endSessionSoon(ctx)
            return
        }
        main.post {
            ensureWindow(ctx)
            scheduleHide()
        }
    }

    /** 排「画完就撤」。会话还活着时不撤：光晕要常驻，遮挡问题由 [detachForGesture] 负责。 */
    private fun scheduleHide() {
        main.removeCallbacks(hideRunnable)
        if (!sessionState.value) main.postDelayed(hideRunnable, HIDE_AFTER_EVENT_MS)
    }

    /**
     * 截屏前把浮层收掉，返回「刚才是否真的挂着」。
     *
     * 我们画的高亮框如果被拍进截图，会被当成界面内容喂给识图/OCR——模型会一本正经去"读"那个框。
     * 而 AI 恰恰常在连续操作的中途截图，那正是浮层亮着的时刻。返回值让调用方决定要不要等一帧
     * 渲染出去（没挂着就不必白等）。
     */
    fun suppressForCapture(): Boolean {
        val wasUp = view != null
        hide()
        return wasUp
    }

    fun hide() {
        main.removeCallbacks(hideRunnable)
        main.post { removeWindowNow() }
    }

    // ---- 窗口增删（幂等，只在主线程调用）----

    private fun removeWindowNow() {
        val v = view ?: return
        val manager = wm
        // 先清引用再摘：即使 removeViewImmediate 抛了，状态也是「没窗口」，
        // 下次 ensureWindow 会重新建一个，不会卡在「以为还挂着」而永远不画。
        view = null; wm = null
        try { manager?.removeViewImmediate(v) } catch (_: Exception) {}
        try { lifecycleRegistry.currentState = Lifecycle.State.CREATED } catch (_: Exception) {}
    }

    private fun ensureWindow(app: Context) {
        if (view != null) return
        try {
            if (!restored) { savedStateController.performRestore(null); restored = true }
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED

            val offsetY = originOffsetY(app)
            val cv = ComposeView(app).apply {
                setViewTreeLifecycleOwner(this@UiActionOverlay)
                setViewTreeViewModelStoreOwner(this@UiActionOverlay)
                setViewTreeSavedStateRegistryOwner(this@UiActionOverlay)
                setContent { com.arix.app.theme.XtomTheme { ActionLayer(offsetY) } }
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            // NOT_TOUCHABLE 仍然要留着：它挡不住注入手势（所以才有 detachForGesture），
            // 但能保证窗口万一多活了几十毫秒时，用户自己的手指照样能穿透过去。
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type, flags, PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START; x = 0; y = 0
                // 见 COMPENSATE_STATUS_BAR 处的分析：API 30+ 必须显式清掉 fitInsetsTypes，
                // 否则窗口 frame 会被状态栏顶下去，画出来的框整体下偏。
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) setFitInsetsTypes(0)
            }

            val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.addView(cv, p)
            wm = manager; view = cv
        } catch (e: Exception) {
            // 权限被回收、窗口被系统拒等都可能抛：可视化失败就当没有，不能影响 AI 操作本身
            view = null; wm = null
            com.arix.wake.WakeLog.d("操作可视化浮层显示失败: ${e.message}")
        }
    }

    /**
     * 绘制原点相对屏幕绝对坐标的补偿量（②的唯一出口）。
     * 见 [COMPENSATE_STATUS_BAR] 处的完整推理：正常应为 0；真机若发现整体下偏，改那一个开关即可。
     */
    private fun originOffsetY(context: Context): Float =
        if (COMPENSATE_STATUS_BAR) statusBarHeightPx(context) else 0f

    /** 状态栏高度；手表等没有状态栏（或资源查不到）时优雅退化为 0。 */
    private fun statusBarHeightPx(context: Context): Float = try {
        val id = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        if (id > 0) context.resources.getDimensionPixelSize(id).toFloat() else 0f
    } catch (_: Exception) { 0f }

    // ---- 绘制 ----

    @Composable
    private fun ActionLayer(offsetY: Float) {
        val ev by UiActionBus.state.collectAsState()
        val scheme = MaterialTheme.colorScheme
        val density = LocalDensity.current
        val sessionActive = sessionState.value

        // 手推进度：0→1 走完一次高亮。key 用 seq，同一位置连点两次也会重新播。
        var progress by remember { mutableStateOf(1f) }
        val seq = ev?.seq ?: 0L
        LaunchedEffect(seq) {
            if (seq == 0L) return@LaunchedEffect
            progress = 0f
            var start = 0L
            while (progress < 1f) {
                withFrameNanos { now ->
                    if (start == 0L) start = now
                    progress = ((now - start) / 1_000_000f / ANIM_MS).coerceIn(0f, 1f)
                }
            }
        }

        // 常驻光晕的呼吸相位：同样手推，绝不能用 infiniteTransition（动画缩放=0 时它直接不动）
        var pulse by remember { mutableStateOf(0f) }
        LaunchedEffect(sessionActive) {
            if (!sessionActive) return@LaunchedEffect
            var start = 0L
            while (true) {
                withFrameNanos { now ->
                    if (start == 0L) start = now
                    // 2.2s 一个来回，慢呼吸，不抢注意力
                    val t = ((now - start) / 1_000_000f % 2200f) / 2200f
                    pulse = if (t < 0.5f) t * 2f else (1f - t) * 2f
                }
            }
        }

        val e = ev
        val showHit = e != null && progress < 1f

        Box(Modifier.fillMaxSize()) {
            Canvas(Modifier.fillMaxSize()) {
                // 所有绘制统一走一次平移：屏幕绝对坐标 → Canvas 坐标，补偿量只有这一处
                translate(top = -offsetY) {
                    if (sessionActive) drawSessionGlow(scheme.primary, pulse)
                    if (e == null || progress >= 1f) return@translate

                    // 前 15% 淡入，后 35% 淡出，中间满不透明 —— 手推的曲线，不受系统动画缩放影响
                    val alpha = when {
                        progress < 0.15f -> progress / 0.15f
                        progress > 0.65f -> ((1f - progress) / 0.35f).coerceIn(0f, 1f)
                        else -> 1f
                    }
                    // 到这儿的事件必定是成功的（失败根本不加窗），用 primary；
                    // success 仍然判一下，将来若有别的入口画失败态也不至于配错色。
                    val accent = if (e.success) scheme.primary else scheme.error
                    drawHit(e, accent, alpha, progress)
                }
            }

            // 目标文字标签：「点了『发送』」这种信息比一个框更能消除黑箱感。
            // 字号走排版令牌不硬编码；标签贴在框上方，框太靠顶就改贴下方，免得被挤出屏幕。
            val label = e?.targetLabel.orEmpty()
            val r = e?.rect
            if (showHit && label.isNotBlank() && r != null) {
                val xDp = with(density) { r.left.toFloat().coerceAtLeast(0f).toDp() }
                val aboveY = r.top - offsetY - with(density) { 30.dp.toPx() }
                val yDp = with(density) { (if (aboveY > 0f) aboveY else r.bottom - offsetY).toDp() }
                Surface(
                    color = scheme.primary.copy(alpha = 0.92f),
                    shape = MaterialTheme.shapes.small,
                    modifier = Modifier.offset(x = xDp, y = yDp).widthIn(max = 200.dp),
                ) {
                    Text(
                        label,
                        color = scheme.onPrimary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }

    /** 命中高亮：框 + 按动作类型的表意图形。 */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawHit(
        e: UiActionEvent, accent: Color, alpha: Float, progress: Float,
    ) {
        val r = e.rect
        if (r == null) {
            // back/home/recents/notifications 没有位置：在屏幕底部中央脉冲一下表示「按了系统键」
            val c = Offset(size.width / 2f, size.height * 0.88f)
            drawCircle(accent.copy(alpha = alpha * 0.25f), radius = size.minDimension * 0.06f * (0.6f + progress), center = c)
            drawCircle(accent.copy(alpha = alpha), radius = size.minDimension * 0.02f, center = c)
            return
        }

        val left = r.left.toFloat(); val top = r.top.toFloat()
        val w = r.width().toFloat(); val h = r.height().toFloat()
        val center = Offset(r.centerX().toFloat(), r.centerY().toFloat())

        // 高亮框：轻微向内收，像「框住」的动作而不是凭空出现
        val shrink = (1f - progress) * 8f
        drawRoundRect(
            color = accent.copy(alpha = alpha * 0.18f),
            topLeft = Offset(left - shrink, top - shrink),
            size = Size(w + shrink * 2, h + shrink * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
        )
        drawRoundRect(
            color = accent.copy(alpha = alpha),
            topLeft = Offset(left - shrink, top - shrink),
            size = Size(w + shrink * 2, h + shrink * 2),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(12f, 12f),
            style = Stroke(width = 3f),
        )

        when (e.action) {
            "swipe" -> {
                val ex = e.endX?.toFloat() ?: return
                val ey = e.endY?.toFloat() ?: return
                drawSwipe(center, Offset(ex, ey), accent, alpha, progress)
            }
            "long_press" -> {
                // 长按用「持续收缩的实心圈」表达「按住不放」，和一次性涟漪区分开
                drawCircle(accent.copy(alpha = alpha * 0.35f), radius = (28f - progress * 12f).coerceAtLeast(6f), center = center)
            }
            "set_text" -> {
                // 输入：沿框底部画一条自左向右生长的下划线，像光标走过
                drawLine(
                    accent.copy(alpha = alpha),
                    start = Offset(left, top + h),
                    end = Offset(left + w * progress, top + h),
                    strokeWidth = 4f,
                )
            }
            else -> {
                // tap / click_text：向外扩散的点击涟漪
                val maxR = hypot(w, h) / 2f + 24f
                drawCircle(accent.copy(alpha = alpha * (1f - progress) * 0.5f), radius = maxR * (0.2f + 0.8f * progress), center = center)
                drawCircle(accent.copy(alpha = alpha), radius = 5f, center = center)
            }
        }
    }

    /**
     * ③ 常驻提示：屏幕四边一圈向内渐隐的柔光晕，只要 AI 的自动化在进行中就慢呼吸。
     *
     * 为什么选这个形态：不依赖任何坐标（拿不到元素、走 Shizuku 读不了屏时照样成立），
     * 画四条带渐变的窄带，成本极低；而且它天然沿屏幕边缘走，圆屏方屏都不违和。
     * 挡手势的问题不靠"避开可点区域"来解决——它和高亮共用同一个窗口，
     * 手势前一样会被 detachForGesture 整个摘掉，安全性优先。
     */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSessionGlow(accent: Color, pulse: Float) {
        // 呼吸只在一个很克制的区间里走：常驻的东西太扎眼会变成骚扰
        val a = 0.16f + pulse * 0.20f
        val band = size.minDimension * 0.055f
        val warm = accent.copy(alpha = a)
        val gone = accent.copy(alpha = 0f)
        drawRect(Brush.verticalGradient(listOf(warm, gone), startY = 0f, endY = band), size = Size(size.width, band))
        drawRect(
            Brush.verticalGradient(listOf(gone, warm), startY = size.height - band, endY = size.height),
            topLeft = Offset(0f, size.height - band), size = Size(size.width, band),
        )
        drawRect(Brush.horizontalGradient(listOf(warm, gone), startX = 0f, endX = band), size = Size(band, size.height))
        drawRect(
            Brush.horizontalGradient(listOf(gone, warm), startX = size.width - band, endX = size.width),
            topLeft = Offset(size.width - band, 0f), size = Size(band, size.height),
        )
    }

    /** 滑动：起点到终点的轨迹 + 末端箭头 + 一个沿轨迹跑的光点，方向一目了然。 */
    private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawSwipe(
        from: Offset, to: Offset, accent: Color, alpha: Float, progress: Float,
    ) {
        drawLine(accent.copy(alpha = alpha * 0.45f), from, to, strokeWidth = 6f)
        // 光点跑到终点后停住，让用户来得及看清落点
        val t = (progress / 0.7f).coerceAtMost(1f)
        drawCircle(accent.copy(alpha = alpha), radius = 9f, center = Offset(from.x + (to.x - from.x) * t, from.y + (to.y - from.y) * t))

        val ang = atan2(to.y - from.y, to.x - from.x)
        val len = 26f
        val spread = 0.5f
        drawLine(accent.copy(alpha = alpha), to, Offset(to.x - len * cos(ang - spread), to.y - len * sin(ang - spread)), strokeWidth = 5f)
        drawLine(accent.copy(alpha = alpha), to, Offset(to.x - len * cos(ang + spread), to.y - len * sin(ang + spread)), strokeWidth = 5f)
    }
}
