package com.arix.app

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.arix.app.ui.topChromeGapHeight

// ============================================================
// 站点登录 —— WebView 里登录某站，cookie 存进全局 CookieManager；
// open_page(WebView 渲染)用同一 CookieManager，之后访问该站需登录的内容自动带 cookie。
// ============================================================
@SuppressLint("SetJavaScriptEnabled")
@Composable fun SiteLoginPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    var input by remember { mutableStateOf("") }
    var target by remember { mutableStateOf<String?>(null) }

    fun open(u: String) { target = if (u.startsWith("http")) u else "https://$u" }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // 顶部悬浮玻璃让位。这页整体不滚(WebView 自己滚)，普通留白即可
        Spacer(Modifier.topChromeGapHeight())
        Text(tr("站点登录"), color = scheme.onSurface, fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        Text(tr("在下面登录某个网站一次，cookie 会存下来。之后 AI 用 open_page/深度搜索访问这个站需登录的内容（微博/小红书/B站/知乎等）就能进去。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(vertical = 6.dp))
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            com.arix.app.ui.XtomField(value = input, onValueChange = { input = it }, placeholder = tr("网址，如 weibo.com"), singleLine = true, textStyle = TextStyle(fontSize = 13.sp), modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Button(onClick = { if (input.isNotBlank()) open(input) }, enabled = input.isNotBlank(), shape = MaterialTheme.shapes.large, modifier = Modifier.height(48.dp)) { Text(tr("打开"), fontSize = 13.sp) }
        }
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("微博" to "weibo.com", "小红书" to "xiaohongshu.com", "B站" to "bilibili.com", "知乎" to "zhihu.com", "抖音" to "douyin.com").forEach { (label, host) ->
                Surface(onClick = { open(host) }, shape = RoundedCornerShape(50), color = scheme.surfaceContainerHighest) {
                    Text(label, color = scheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
        }
        target?.let { u ->
            AndroidView(
                factory = { ctx ->
                    CookieManager.getInstance().setAcceptCookie(true)
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        // ⚠ 安全加固：targetSdk=28 下这两个默认 true（false 只对 targetSdk≥30 生效）。
                        // 这里加载的是任意站点的登录页，且用户会在上面输入真实凭据——恰恰最不该让页面
                        // 有能力去读 file:///data/data/... 和本应用的 ContentProvider。
                        // 与 BrowserPage.kt / BrowserAgent.kt / OpenPageTool.kt 同一口径。
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.allowFileAccessFromFileURLs = false
                        settings.allowUniversalAccessFromFileURLs = false
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
                        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : WebViewClient() {
                            // 非 http(s) scheme（weibo:// / intent:// / market://…）交给系统，别让 WebView 报错卡死登录
                            override fun shouldOverrideUrlLoading(view: WebView, request: android.webkit.WebResourceRequest): Boolean {
                                val t = request.url.toString()
                                if (!t.startsWith("http")) {
                                    try { view.context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, request.url).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
                                    return true
                                }
                                return false
                            }
                            override fun onPageFinished(view: WebView, url: String) { CookieManager.getInstance().flush() }
                        }
                    }
                },
                // 只在目标 URL 变化时加载，避免每次重组 reload 打断登录
                update = { web -> if (web.tag != u) { web.tag = u; web.loadUrl(u) } },
                onRelease = { it.stopLoading(); it.loadUrl("about:blank"); it.removeAllViews(); it.destroy() },
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
            Text(tr("登录完成后直接返回即可，cookie 已保存。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
        }
    }
}
