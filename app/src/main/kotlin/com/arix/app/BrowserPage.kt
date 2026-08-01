package com.arix.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arix.app.ui.XtomField

/**
 * 应用内嵌浏览器页。地址栏 + 前进/后退/刷新/停止 + 进度条 + WebView（不外跳系统浏览器），
 * 底部可「复制本页正文」（供粘给 AI）与「复制/分享网址」。全部配色走 MaterialTheme 令牌，
 * WebView 在 onDispose 里 destroy 防泄漏。
 *
 * MainActivity 以一个全屏页方式调用：`"browser" -> BrowserPage(onBack = { currentPage = "chat" })`。
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserPage(
    onBack: () -> Unit,
    initialUrl: String = "https://www.bing.com",
) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    val focus = LocalFocusManager.current

    // 输入框里显示的地址（用户可编辑）；真正加载走 webView.loadUrl
    var addressText by remember { mutableStateOf(initialUrl) }
    // 当前页真实 URL（onPageStarted 回填），用于复制/分享
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var canGoBack by remember { mutableStateOf(false) }
    var canGoForward by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }

    // 单一 WebView 实例，跟随 composition 生命周期
    val webView = remember {
        WebView(context).apply {
            @Suppress("DEPRECATION")
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                builtInZoomControls = true
                displayZoomControls = false
                // 允许 https 站点内的混合内容按需加载（多数现代站点为 https）
                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                // 安全加固：targetSdk=28 下 allowFileAccess/allowContentAccess 默认 true，恶意 http 页可诱导加载 file:// / content:// → 显式关闭
                allowFileAccess = false
                allowContentAccess = false
                allowFileAccessFromFileURLs = false
                allowUniversalAccessFromFileURLs = false
            }
            webViewClient = object : WebViewClient() {
                // 页内跳转全部留在本 WebView，不外跳系统浏览器
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    // 一律交给 WebView 原生处理：http(s) 内部导航会保留 POST 表单 body（自己 loadUrl 会降级成 GET 丢 body）；
                    // 非 http(s)(tel/mailto/intent/market 等)WebView 不识别即静默忽略，不主动 startActivity，避免 intent:// 重定向攻击面。
                    return false
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    loading = true
                    url?.let { currentUrl = it; addressText = it }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    loading = false
                    url?.let { currentUrl = it; addressText = it }
                    canGoBack = view?.canGoBack() == true
                    canGoForward = view?.canGoForward() == true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progress = newProgress
                    loading = newProgress in 1..99
                }
            }
            loadUrl(initialUrl)
        }
    }

    // 规范化并加载用户输入（自动补 https://）
    fun load(raw: String) {
        val q = raw.trim()
        if (q.isEmpty()) return
        val u = when {
            q.startsWith("http://") || q.startsWith("https://") -> q
            // 含空格或没有点号 → 当搜索词走必应搜索；否则当域名补 https://
            q.contains(' ') || !q.contains('.') -> "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(q, "UTF-8")
            else -> "https://$q"
        }
        focus.clearFocus()
        webView.loadUrl(u)
    }

    fun copy(label: String, text: String, toast: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
    }

    // 系统返回键：优先在网页历史内后退，无历史再退出本页
    BackHandler {
        if (webView.canGoBack()) webView.goBack() else onBack()
    }

    // 页面销毁时释放 WebView，防内存泄漏
    DisposableEffect(Unit) {
        onDispose {
            webView.stopLoading()
            (webView.parent as? android.view.ViewGroup)?.removeView(webView)
            webView.destroy()
        }
    }

    Column(Modifier.fillMaxSize().background(scheme.background)) {
        // ---- 顶部地址栏 + 导航按钮 ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = { if (webView.canGoBack()) webView.goBack() else onBack() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("后退"),
                    tint = if (canGoBack) scheme.onSurface else scheme.onSurfaceVariant)
            }
            IconButton(enabled = canGoForward, onClick = { webView.goForward() }) {
                Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = tr("前进"),
                    tint = if (canGoForward) scheme.onSurface else scheme.onSurfaceVariant)
            }
            XtomField(
                value = addressText,
                onValueChange = { addressText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = tr("输入网址或搜索…"),
                textStyle = TextStyle(fontSize = 13.sp, color = scheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { load(addressText) }),
            )
            // 加载中显示「停止」，否则显示「刷新」
            if (loading) {
                IconButton(onClick = { webView.stopLoading() }) {
                    Icon(Icons.Outlined.Close, contentDescription = tr("停止"), tint = scheme.onSurface)
                }
            } else {
                IconButton(onClick = { webView.reload() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = tr("刷新"), tint = scheme.onSurface)
                }
            }
        }

        // ---- 顶部细进度条（加载中才显示）----
        AnimatedVisibility(visible = loading) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = scheme.primary,
                trackColor = scheme.surfaceContainerHighest,
            )
        }

        // ---- 中间 WebView ----
        AndroidView(
            factory = { webView },
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )

        // ---- 底部工具条：复制正文 / 复制网址 / 分享网址 ----
        Row(
            Modifier.fillMaxWidth().background(scheme.surfaceContainerLow).padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            BottomAction(Icons.Outlined.ContentCopy, tr("复制正文")) {
                // 取正文纯文本，供用户粘给 AI
                webView.evaluateJavascript("(function(){return document.body?document.body.innerText:''})();") { raw ->
                    // evaluateJavascript 返回的是 JSON 字符串字面量，去掉首尾引号并反转义
                    val text = decodeJsString(raw)
                    if (text.isBlank()) {
                        Toast.makeText(context, tr("未取到正文"), Toast.LENGTH_SHORT).show()
                    } else {
                        copy("page_text", text, tr("已复制本页正文"))
                    }
                }
            }
            BottomAction(Icons.Outlined.ContentCopy, tr("复制网址")) {
                copy("url", currentUrl, tr("已复制网址"))
            }
            BottomAction(Icons.Outlined.Share, tr("分享网址")) {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, currentUrl)
                }
                context.startActivity(Intent.createChooser(send, tr("分享网址")))
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier.weight(1f).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconButton(onClick = onClick) { Icon(icon, contentDescription = label, tint = scheme.onSurface) }
        Text(label, color = scheme.onSurfaceVariant, fontSize = 10.sp)
    }
    Spacer(Modifier.width(0.dp))
}

/** evaluateJavascript 回调给的是 JSON 编码字符串（带引号、\n、\uXXXX 等），这里解码回原文。 */
private fun decodeJsString(raw: String?): String {
    if (raw.isNullOrBlank() || raw == "null") return ""
    return try {
        // 用 JSONTokener 稳妥解码字符串字面量
        val v = org.json.JSONTokener(raw).nextValue()
        v?.toString() ?: ""
    } catch (_: Exception) {
        raw.trim('"').replace("\\n", "\n").replace("\\t", "\t").replace("\\\"", "\"").replace("\\\\", "\\")
    }
}
