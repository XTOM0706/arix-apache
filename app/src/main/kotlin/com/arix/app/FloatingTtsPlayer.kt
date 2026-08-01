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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DragHandle
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.arix.tool.TtsTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 悬浮 TTS 播放器：一个可拖动的 [WindowManager] SYSTEM_ALERT_WINDOW 悬浮窗（TYPE_APPLICATION_OVERLAY），
 * 承载一张紧凑的 Compose 控制卡，显示朗读状态并提供 上一条/播放·暂停/停止/下一条 控制。
 *
 * 复用现有能力，不改任何共享文件：
 *  - 悬浮窗宿主样板照搬 [WakeOverlayHost]（自身充当 Lifecycle/SavedState/ViewModelStore 宿主，供 ComposeView 在 window 里跑）。
 *  - 朗读一律走 [TtsTool.speak]（内部已做 Markdown/emoji 清洗，并按引擎优先级 离线神经→Minimax→云端→Edge→系统 兜底）。
 *
 * 播放模型：持有一个「消息文本队列」+ 当前索引。播一条会挂起到这条大致播完（TtsTool.speak 的语义），
 * 播完自动前进到下一条，直到队尾。上一条/下一条/停止都先打断当前播放（取消协程 + TtsTool.shutdown）。
 *
 * 已知限制：TtsTool 只暴露 speak/shutdown，没有「seek/暂停续播」。因此「暂停」= 停当前并记住索引，
 * 「继续」= 从当前这条**重头**再播（不是从句子中间续）。这是现有音频层的约束，非本文件可单独解决。
 */
@SuppressLint("StaticFieldLeak")
object FloatingTtsPlayer : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

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
    @Volatile private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    @Volatile private var appCtx: Context? = null   // 供 dump 摘窗后原位复原（reattachAfterDump）

    // 自己独占一个 TtsTool 实例，shutdown 不会波及聊天页那一个。
    @Volatile private var ttsRef: TtsTool? = null
    private fun tts(app: Context): TtsTool = ttsRef ?: TtsTool(app.applicationContext).also { ttsRef = it }

    // --- 播放状态（Compose 观察）---
    private var playlist: List<String> = emptyList()
    private var cardId: Long? = null
    private var index by mutableIntStateOf(0)
    private var isPlaying by mutableStateOf(false)
    private var engineLabel by mutableStateOf("")
    private var playJob: Job? = null

    val isShowing: Boolean get() = view != null

    // ---- 悬浮窗权限 ----

    /** 是否已授予「显示在其它应用上层」(SYSTEM_ALERT_WINDOW) 权限。M 以下恒 true。 */
    fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    /** 跳到系统「悬浮窗授权」设置页（未授权时引导用户）。context 最好是 Activity，否则加 NEW_TASK。 */
    fun requestOverlayPermission(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (_: Exception) {}
    }

    /**
     * 显示悬浮播放器并从 [startIndex] 开始朗读 [messages]。可任意线程调用。
     * 若无悬浮窗权限则不弹（返回 false），调用方可据此去引导授权。
     */
    fun show(context: Context, messages: List<String>, startIndex: Int = 0, cardId: Long? = null): Boolean {
        val app = context.applicationContext
        if (!hasOverlayPermission(app)) return false
        if (messages.isEmpty()) return false
        this.playlist = messages
        this.cardId = cardId
        main.post {
            ensureWindow(app)
            playFrom(app, startIndex)
        }
        return true
    }

    private fun ensureWindow(app: Context) {
        if (view != null) return
        try {
            appCtx = app.applicationContext
            if (!restored) { savedStateController.performRestore(null); restored = true }
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED

            val cv = ComposeView(app).apply {
                setViewTreeLifecycleOwner(this@FloatingTtsPlayer)
                setViewTreeViewModelStoreOwner(this@FloatingTtsPlayer)
                setViewTreeSavedStateRegistryOwner(this@FloatingTtsPlayer)
                setContent {
                    com.arix.app.theme.XtomTheme {
                        PlayerCard(
                            onDrag = { dx, dy -> moveBy(dx, dy) },
                            onPrev = { prev(app) },
                            onToggle = { toggle(app) },
                            onStop = { hide() },
                            onNext = { next(app) },
                        )
                    }
                }
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            // 不抢焦点(不弹输入法/不吞返回键)、不模态(卡片外的触摸透传给下层应用)。
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            // 复用已有 lp：dump 摘窗后 reattach 能原位复原（保住用户拖过的位置），新建才落默认位。
            val p = lp ?: WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                flags,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 24
                y = 120
            }

            val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.addView(cv, p)
            wm = manager
            lp = p
            view = cv
        } catch (e: Exception) {
            com.arix.wake.WakeLog.d("悬浮播放器显示失败: ${e.message}")
        }
    }

    /**
     * ui_control dump 前临时物理摘窗（照 UiActionOverlay 的做法，不 GONE/alpha）；**不打断朗读**——
     * 只清 view，playJob/播放状态都在 object 上，[reattachAfterDump] 会用原 lp 原位加回、UI 自行重建。
     * 播放器是不可聚焦独立窗口、本不进无障碍 rootInActiveWindow，摘它属防御性。
     */
    fun detachForDump() {
        main.post {
            val v = view ?: return@post
            try { wm?.removeViewImmediate(v) } catch (_: Exception) {}
            view = null   // 保留 lp/wm/appCtx/playlist/playJob，绝不 stopPlayback
        }
    }

    /** 与 [detachForDump] 配对：dump 结束后把播放器加回原位（未曾显示过则空操作）。 */
    fun reattachAfterDump() {
        main.post {
            val app = appCtx ?: return@post
            if (view == null && lp != null) ensureWindow(app)
        }
    }

    private fun moveBy(dx: Float, dy: Float) {
        val p = lp ?: return; val v = view ?: return
        p.x += dx.roundToInt(); p.y += dy.roundToInt()
        try { wm?.updateViewLayout(v, p) } catch (_: Exception) {}
    }

    // ---- 播放控制 ----

    private fun playFrom(app: Context, start: Int) {
        stopPlayback(app)
        index = start.coerceIn(0, playlist.lastIndex)
        isPlaying = true
        playJob = scope.launch {
            while (isActive && index <= playlist.lastIndex) {
                val cur = index
                val text = playlist[cur]
                val r = try {
                    tts(app).speak(text, cardId = cardId, onLog = { msg -> engineLabel = shortEngine(msg) })
                } catch (c: kotlinx.coroutines.CancellationException) {
                    throw c
                } catch (_: Throwable) { "fail" }
                if (!isActive) break
                if (index != cur) continue          // 中途被 prev/next 改了索引：安全兜底
                if (r == "fail" || index >= playlist.lastIndex) { isPlaying = false; break }
                index++                              // 自动前进到下一条
            }
            isPlaying = false
        }
    }

    /** 打断当前朗读：取消协程（会触发 MediaPlayer 引擎的 invokeOnCancellation 释放）+ shutdown 停系统/神经引擎。 */
    private fun stopPlayback(app: Context) {
        playJob?.cancel(); playJob = null
        try { ttsRef?.shutdown() } catch (_: Exception) {}
        isPlaying = false
    }

    private fun toggle(app: Context) {
        if (isPlaying) { stopPlayback(app) }          // 暂停：停当前，保留索引
        else playFrom(app, index)                     // 继续：从当前这条重头播（限制见类注释）
    }

    private fun prev(app: Context) = playFrom(app, (index - 1).coerceAtLeast(0))
    private fun next(app: Context) {
        if (index < playlist.lastIndex) playFrom(app, index + 1)
    }

    /** onLog 里挑出引擎名做角标；拿不到就返回原串首段。 */
    private fun shortEngine(msg: String): String = when {
        msg.contains("神经") -> tr("离线语音")
        msg.contains("Minimax", true) -> "Minimax"
        msg.contains("云端") -> tr("云端语音")
        msg.contains("Edge", true) -> "Edge"
        msg.contains("系统语音") -> tr("系统语音")
        else -> engineLabel
    }

    /** 关闭悬浮窗并停止朗读。可任意线程调用。 */
    fun hide() {
        main.post {
            val app = view?.context?.applicationContext
            if (app != null) stopPlayback(app) else { playJob?.cancel(); playJob = null; try { ttsRef?.shutdown() } catch (_: Exception) {} }
            val v = view ?: return@post
            try { wm?.removeView(v) } catch (_: Exception) {}
            view = null; wm = null; lp = null
            lifecycleRegistry.currentState = Lifecycle.State.CREATED
        }
    }

    // ---- UI ----

    @androidx.compose.runtime.Composable
    private fun PlayerCard(
        onDrag: (Float, Float) -> Unit,
        onPrev: () -> Unit,
        onToggle: () -> Unit,
        onStop: () -> Unit,
        onNext: () -> Unit,
    ) {
        val scheme = MaterialTheme.colorScheme
        val total = playlist.size
        val cur = (index + 1).coerceIn(1, total.coerceAtLeast(1))
        Surface(
            shape = MaterialTheme.shapes.large,
            color = scheme.surfaceContainerHigh,
            tonalElevation = 6.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(min = 200.dp, max = 300.dp),
        ) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                // 顶部：拖动手柄 + 进度 + 关闭
                Row(
                    Modifier.fillMaxWidth().pointerInput(Unit) {
                        detectDragGestures { change, drag -> change.consume(); onDrag(drag.x, drag.y) }
                    },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.DragHandle, contentDescription = tr("拖动"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${tr("朗读")} $cur/$total" + if (engineLabel.isNotBlank()) " · $engineLabel" else "",
                        color = scheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    IconButton(onClick = onStop, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Outlined.Close, contentDescription = tr("停止"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                // 当前朗读文本预览
                val preview = playlist.getOrNull(index).orEmpty()
                if (preview.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        preview, color = scheme.onSurface, fontSize = 13.sp,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                // 控制条：上一条 / 播放·暂停 / 停止 / 下一条
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onPrev) {
                        Icon(Icons.Outlined.SkipPrevious, contentDescription = tr("上一条"), tint = scheme.onSurface)
                    }
                    IconButton(onClick = onToggle) {
                        Icon(
                            if (isPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                            contentDescription = if (isPlaying) tr("暂停") else tr("播放"),
                            tint = scheme.primary,
                            modifier = Modifier.size(30.dp),
                        )
                    }
                    IconButton(onClick = onStop) {
                        Icon(Icons.Outlined.Stop, contentDescription = tr("停止"), tint = scheme.onSurface)
                    }
                    IconButton(onClick = onNext) {
                        Icon(Icons.Outlined.SkipNext, contentDescription = tr("下一条"), tint = scheme.onSurface)
                    }
                }
            }
        }
    }
}
