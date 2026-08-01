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
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
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
import com.arix.tool.ImageOcrTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * 悬浮「屏幕取字」按钮：一个可拖动的 [WindowManager] 悬浮窗(TYPE_APPLICATION_OVERLAY)小圆钮。
 * 点一下 → 抓当前屏幕 → 走视觉模型 OCR → 就地弹出可滚动结果卡（可一键复制）。
 *
 * 复用现有能力、不改任何共享文件：
 *  - 悬浮窗宿主样板照搬 [FloatingTtsPlayer]（自身充当 Lifecycle/SavedState/ViewModelStore 宿主）。
 *  - 抓屏走 [ScreenCapture]；识字走现有 [ImageOcrTool]（视觉模型 OCR）。
 *
 * 关键细节：截屏前先把本悬浮窗设为不可见并等 ~300ms，避免把按钮自己拍进截图里；抓完再恢复。
 */
@SuppressLint("StaticFieldLeak")
object FloatingScreenOcrButton : LifecycleOwner, ViewModelStoreOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private var restored = false

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val viewModelStore: ViewModelStore get() = store
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJobMain())

    @Volatile private var view: ComposeView? = null
    @Volatile private var wm: WindowManager? = null
    private var lp: WindowManager.LayoutParams? = null
    @Volatile private var appCtx: Context? = null   // 供 dump 摘窗后原位复原（reattachAfterDump）

    // --- UI 状态（Compose 观察）---
    private var busy by mutableStateOf(false)          // 抓屏+OCR 进行中
    private var hidden by mutableStateOf(false)        // 截屏瞬间隐藏自己，别拍进去
    private var resultText by mutableStateOf<String?>(null)
    private var errorText by mutableStateOf<String?>(null)

    val isShowing: Boolean get() = view != null

    fun hasOverlayPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)

    fun requestOverlayPermission(context: Context) {
        val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(i) } catch (_: Exception) {}
    }

    /** 显示悬浮按钮。无悬浮窗权限则返回 false（调用方可据此去引导授权）。 */
    fun show(context: Context): Boolean {
        val app = context.applicationContext
        if (!hasOverlayPermission(app)) return false
        main.post { ensureWindow(app) }
        return true
    }

    /** 隐藏并移除悬浮按钮。 */
    fun hide() {
        main.post {
            val v = view ?: return@post
            try { wm?.removeView(v) } catch (_: Exception) {}
            view = null; wm = null; lp = null
            resultText = null; errorText = null; busy = false; hidden = false
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

            val cv = ComposeView(app).apply {
                setViewTreeLifecycleOwner(this@FloatingScreenOcrButton)
                setViewTreeViewModelStoreOwner(this@FloatingScreenOcrButton)
                setViewTreeSavedStateRegistryOwner(this@FloatingScreenOcrButton)
                setContent {
                    com.arix.app.theme.XtomTheme {
                        // 截屏瞬间整卡不绘制，避免被拍进截图
                        if (!hidden) OcrOverlay(
                            app = app,
                            onDrag = { dx, dy -> moveBy(dx, dy) },
                            onCapture = { runCapture(app) },
                            onClose = { hide() },
                            onDismissResult = { resultText = null; errorText = null },
                        )
                    }
                }
            }

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            val flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            // 复用已有 lp：dump 摘窗后 reattach 能原位复原（保住用户拖过的位置），新建才落默认位。
            val p = lp ?: WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type, flags, PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.START; x = 24; y = 160 }

            val manager = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            manager.addView(cv, p)
            wm = manager; lp = p; view = cv
        } catch (e: Exception) {
            com.arix.wake.WakeLog.d("悬浮取字按钮显示失败: ${e.message}")
        }
    }

    /**
     * ui_control dump 前临时物理摘窗（照 UiActionOverlay 的做法，不 GONE/alpha）；保留 lp/状态，dump 后 [reattachAfterDump] 原位复原。
     * OCR 钮是不可聚焦独立窗口、本不进无障碍 rootInActiveWindow，摘它属防御性（防 dump 实现将来改用 getWindows）。
     */
    fun detachForDump() {
        main.post {
            val v = view ?: return@post
            try { wm?.removeViewImmediate(v) } catch (_: Exception) {}
            view = null   // 只清 view，保留 lp/wm/appCtx 与 UI 状态
        }
    }

    /** 与 [detachForDump] 配对：dump 结束后把取字钮加回原位（未曾显示过则空操作）。 */
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

    private fun runCapture(app: Context) {
        if (busy) return
        busy = true; resultText = null; errorText = null
        scope.launch {
            // 1) 先隐藏自己并让一帧真正渲染出去，避免把按钮拍进截图
            hidden = true
            delay(300)
            // 2) 抓屏 → OCR（走现有 ImageOcrTool 的视觉模型逻辑）
            val out = try {
                val path = ScreenCapture.captureToFile(app).getOrElse { err ->
                    hidden = false
                    errorText = "截图失败：${err.message}"; busy = false
                    return@launch
                }
                try {
                    ImageOcrTool(app).execute(JSONObject().put("image", path))
                } finally {
                    try { File(path).delete() } catch (_: Exception) {}
                }
            } catch (e: Exception) {
                hidden = false; errorText = "识别失败：${e.message}"; busy = false
                return@launch
            }
            hidden = false
            if (out.isError) errorText = out.content else resultText = out.content
            busy = false
        }
    }

    // ---- UI ----

    @androidx.compose.runtime.Composable
    private fun OcrOverlay(
        app: Context,
        onDrag: (Float, Float) -> Unit,
        onCapture: () -> Unit,
        onClose: () -> Unit,
        onDismissResult: () -> Unit,
    ) {
        val scheme = MaterialTheme.colorScheme
        Column(horizontalAlignment = Alignment.Start) {
            // 结果/错误卡（有内容才显示）
            val text = resultText ?: errorText
            if (text != null) {
                Surface(
                    shape = MaterialTheme.shapes.large,
                    color = scheme.surfaceContainerHigh,
                    tonalElevation = 6.dp, shadowElevation = 8.dp,
                    modifier = Modifier.widthIn(min = 220.dp, max = 300.dp),
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (errorText != null) tr("识别失败") else tr("识别结果"),
                                color = scheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f),
                            )
                            if (resultText != null) {
                                IconButton(onClick = { copyToClipboard(app, resultText!!) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Outlined.ContentCopy, contentDescription = tr("复制"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                                }
                            }
                            IconButton(onClick = onDismissResult, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Outlined.Close, contentDescription = tr("关闭"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                            }
                        }
                        Spacer(Modifier.width(4.dp))
                        Text(
                            text, color = scheme.onSurface, fontSize = 13.sp,
                            modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()),
                        )
                    }
                }
                Spacer(Modifier.width(6.dp))
            }

            // 圆形主按钮（可拖动 / 点按截屏）
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = scheme.primary,
                tonalElevation = 6.dp, shadowElevation = 8.dp,
                modifier = Modifier
                    .padding(top = if (text != null) 8.dp else 0.dp)
                    .size(52.dp)
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag -> change.consume(); onDrag(drag.x, drag.y) }
                    },
            ) {
                Box(contentAlignment = Alignment.Center) {
                    if (busy) {
                        CircularProgressIndicator(color = scheme.onPrimary, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
                    } else {
                        IconButton(onClick = onCapture) {
                            Icon(Icons.Outlined.TextFields, contentDescription = tr("屏幕取字"), tint = scheme.onPrimary, modifier = Modifier.size(26.dp))
                        }
                    }
                }
            }
        }
    }

    private fun copyToClipboard(context: Context, text: String) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("ocr", text))
            Toast.makeText(context, tr("已复制"), Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {}
    }

    // 主线程 SupervisorJob 作用域（避免额外引依赖，内联一个）
    private fun SupervisorJobMain() = kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate
}
