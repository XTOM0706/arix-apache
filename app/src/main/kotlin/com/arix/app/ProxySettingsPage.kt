package com.arix.app

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.arix.app.ui.SettingsChoiceRow
import com.arix.app.ui.SettingsHint
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.SettingsToggle
import com.arix.app.ui.XtomButton
import com.arix.app.ui.XtomField
import com.arix.app.ui.topChromeGapHeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.URL

// ============================================================
// 网络代理 —— 全 App 出网走用户自建的 HTTP / SOCKS5 代理。
//
// 本文件只负责「配置 + 存 + 自测」。真正让聊天/语音请求走代理，需要 :cloudapi 的
// HttpClientProvider 在 builder 上挂 proxySelector/proxyAuthenticator（见 README 交接说明）——
// :cloudapi 不依赖 :app，所以它读不到这里的 SharedPreferences，必须由 app 侧在启动时推过去。
//
// 默认关：不填不开就完全等价于现在的直连行为，冷启动照样零联网。
// ============================================================
object ProxyPrefs {

    /** 代理协议。SOCKS5 走 TCP 层，HTTP 代理走 CONNECT/明文转发。 */
    enum class Type { HTTP, SOCKS5 }

    data class Config(
        val enabled: Boolean = false,
        val type: Type = Type.HTTP,
        val host: String = "",
        val port: Int = 0,
        val user: String = "",
        val password: String = "",
    ) {
        /** 开着且主机端口填全了才算「能用」——半填的配置不该把出网打断。 */
        val usable: Boolean get() = enabled && host.isNotBlank() && port in 1..65535
        val hasAuth: Boolean get() = user.isNotBlank()
    }

    private const val PREFS = "arix_proxy_prefs"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * 进程内缓存：网络路径上每次请求都要问一次代理配置，不能每次都去读 SharedPreferences。
     * save() 会同步刷新它，所以改完立刻生效。
     */
    @Volatile
    private var cache: Config? = null

    fun get(c: Context): Config {
        cache?.let { return it }
        val sp = p(c)
        val cfg = Config(
            enabled = sp.getBoolean("enabled", false),
            type = if (sp.getString("type", "HTTP") == "SOCKS5") Type.SOCKS5 else Type.HTTP,
            host = sp.getString("host", "") ?: "",
            port = sp.getInt("port", 0),
            user = sp.getString("user", "") ?: "",
            password = sp.getString("password", "") ?: "",
        )
        cache = cfg
        return cfg
    }

    fun save(c: Context, cfg: Config) {
        cache = cfg
        p(c).edit()
            .putBoolean("enabled", cfg.enabled)
            .putString("type", cfg.type.name)
            .putString("host", cfg.host.trim())
            .putInt("port", cfg.port)
            .putString("user", cfg.user.trim())
            .putString("password", cfg.password)
            .apply()
        installAuthenticator(c)
        applyToNetwork(c)   // 改完立刻生效，不用重启（见 applyToNetwork）
    }

    /**
     * 把当前代理配置推给 `:cloudapi`（那个模块不依赖 `:app`，读不到本 Prefs），并让已建连接失效。
     *
     * 启动时调一次、每次 [save] 之后再调一次。少了后面这次，用户改完设置得杀进程才生效——
     * 而"改了没反应"是设置类功能最容易被判死刑的一种表现。
     */
    fun applyToNetwork(c: Context) {
        val cfg = get(c)
        com.arix.cloudapi.NetProxyHolder.proxy = javaProxy(cfg)
        // SOCKS5 的账密没法靠请求头，走 java.net.Authenticator（installAuthenticator 里装）；
        // 只有 HTTP 代理才用 Proxy-Authorization。
        com.arix.cloudapi.NetProxyHolder.basicAuth =
            if (cfg.type == Type.HTTP) basicAuthHeader(cfg) else null
        com.arix.cloudapi.HttpClientProvider.onProxyChanged()
    }

    /** 给 java.net / OkHttp 用的 Proxy 对象；未启用或没填全 → null（= 直连）。 */
    fun javaProxy(c: Context): Proxy? {
        val cfg = get(c)
        if (!cfg.usable) return null
        return javaProxy(cfg)
    }

    fun javaProxy(cfg: Config): Proxy? {
        if (!cfg.usable) return null
        val kind = if (cfg.type == Type.SOCKS5) Proxy.Type.SOCKS else Proxy.Type.HTTP
        // 用 createUnresolved：主机名交给代理去解析，别在本机先做 DNS（本机 DNS 被污染时代理就白配了）
        return runCatching { Proxy(kind, InetSocketAddress.createUnresolved(cfg.host.trim(), cfg.port)) }.getOrNull()
    }

    /** HTTP 代理的 Proxy-Authorization 头值；无账号或非 HTTP 代理返回 null。 */
    fun basicAuthHeader(cfg: Config): String? {
        if (!cfg.hasAuth) return null
        val raw = "${cfg.user.trim()}:${cfg.password}"
        val b64 = android.util.Base64.encodeToString(raw.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        return "Basic $b64"
    }

    /**
     * SOCKS5 的账号密码没有「加个请求头」这种办法——JDK 的 SOCKS 客户端只认全局
     * [java.net.Authenticator]。所以这里装一个只回应「代理」质询的默认认证器。
     *
     * 只在「开了代理且填了账号」时装，且只回 RequestorType.PROXY：绝不把这套凭据
     * 交给目标服务器（那等于把代理密码发给随便什么网站）。
     */
    fun installAuthenticator(c: Context) {
        val cfg = get(c)
        if (!cfg.usable || !cfg.hasAuth) {
            if (installed) { runCatching { java.net.Authenticator.setDefault(null) }; installed = false }
            return
        }
        runCatching {
            java.net.Authenticator.setDefault(object : java.net.Authenticator() {
                override fun getPasswordAuthentication(): PasswordAuthentication? {
                    if (requestorType != RequestorType.PROXY) return null
                    val cur = cache ?: return null
                    if (!cur.usable || !cur.hasAuth) return null
                    return PasswordAuthentication(cur.user.trim(), cur.password.toCharArray())
                }
            })
            installed = true
        }
    }

    @Volatile private var installed = false

    /** 人话摘要，给设置中心的副标题用。 */
    fun summary(c: Context): String {
        val cfg = get(c)
        if (!cfg.usable) return tr("未启用，直连")
        return String.format(tr("%s 代理 · %s:%d"), if (cfg.type == Type.SOCKS5) "SOCKS5" else "HTTP", cfg.host, cfg.port)
    }
}

/** 连通性自测的默认目标：一个只回 204 空响应的地址，几百字节，验通不验内容。 */
private const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

/** 测试结果：成功给耗时与状态码，失败给一句人话。 */
private sealed class ProxyTestResult {
    data class Ok(val code: Int, val ms: Long) : ProxyTestResult()
    data class Fail(val why: String) : ProxyTestResult()
}

private suspend fun runProxyTest(cfg: ProxyPrefs.Config, testUrl: String): ProxyTestResult =
    withContext(Dispatchers.IO) {
        val proxy = ProxyPrefs.javaProxy(cfg)
            ?: return@withContext ProxyTestResult.Fail(tr("主机或端口没填全，先补上再测。"))
        val started = System.currentTimeMillis()
        var conn: HttpURLConnection? = null
        try {
            val url = URL(testUrl.trim())
            val c = url.openConnection(proxy) as HttpURLConnection
            conn = c
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.instanceFollowRedirects = false
            c.requestMethod = "GET"
            // HTTP 代理的账号密码走请求头；SOCKS5 的走 ProxyPrefs 装的全局 Authenticator
            if (cfg.type == ProxyPrefs.Type.HTTP) {
                ProxyPrefs.basicAuthHeader(cfg)?.let { c.setRequestProperty("Proxy-Authorization", it) }
            }
            val code = c.responseCode
            val ms = System.currentTimeMillis() - started
            when (code) {
                407 -> ProxyTestResult.Fail(tr("代理要求认证但账号或密码不对（407）。"))
                in 200..399 -> ProxyTestResult.Ok(code, ms)
                else -> ProxyTestResult.Fail(String.format(tr("代理通了但目标返回 %d。"), code))
            }
        } catch (ce: CancellationException) {
            throw ce   // 取消不是失败，必须原样抛回去
        } catch (e: Exception) {
            val msg = e.message ?: e.javaClass.simpleName
            ProxyTestResult.Fail(
                when {
                    msg.contains("timed out", true) || msg.contains("timeout", true) -> tr("连不上：超时。检查主机/端口，或代理本身是不是没开。")
                    msg.contains("refused", true) -> tr("连不上：对方拒绝连接。端口可能不对，或代理没在监听。")
                    msg.contains("Unable to resolve", true) || msg.contains("UnknownHost", true) -> tr("连不上：主机名解析不了。")
                    else -> String.format(tr("连不上：%s"), msg)
                }
            )
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

// ============================================================
// 页面
// ============================================================
@Composable fun ProxySettingsPage(context: Context) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // 本页设了背景图时卡片必须不透明，否则内容压在照片上读不清
    val opaque = remember { PageBackgroundPrefs.get(context, "proxy") != null }

    var cfg by remember { mutableStateOf(ProxyPrefs.get(context)) }
    var portText by remember { mutableStateOf(if (cfg.port > 0) cfg.port.toString() else "") }
    var testUrl by remember { mutableStateOf(DEFAULT_TEST_URL) }
    var testing by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ProxyTestResult?>(null) }

    fun update(next: ProxyPrefs.Config) {
        cfg = next
        ProxyPrefs.save(context, next)
        result = null   // 配置一动，上次的测试结论就作废了，别让人看着过期的「已连通」
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.topChromeGapHeight())   // 顶部悬浮玻璃让位

        SettingsSection(tr("代理"), Icons.Outlined.Lan, translucent = !opaque) {
            SettingsToggle(
                icon = Icons.Outlined.Public,
                title = tr("启用网络代理"),
                subtitle = tr("开启后 App 的联网请求经你填的代理服务器转发。默认关，关着完全等同于现在的直连。"),
                checked = cfg.enabled,
                onCheckedChange = { update(cfg.copy(enabled = it)) },
            )
            if (cfg.enabled) {
                SettingsChoiceRow(
                    title = tr("协议"),
                    subtitle = tr("HTTP 代理适合大多数场景；SOCKS5 更底层，能转发非 HTTP 流量。"),
                    icon = Icons.Outlined.Router,
                    options = listOf(ProxyPrefs.Type.HTTP to "HTTP", ProxyPrefs.Type.SOCKS5 to "SOCKS5"),
                    selected = cfg.type,
                    onSelect = { update(cfg.copy(type = it)) },
                )
            }
        }

        if (cfg.enabled) {
            SettingsSection(tr("服务器"), Icons.Outlined.Dns, translucent = !opaque) {
                // 手表窄屏：主机和端口各占一行，不并排
                XtomField(
                    value = cfg.host,
                    onValueChange = { update(cfg.copy(host = it.trim())) },
                    label = tr("主机（IP 或域名，如 127.0.0.1）"),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                XtomField(
                    value = portText,
                    onValueChange = { s ->
                        portText = s.filter { it.isDigit() }.take(5)
                        update(cfg.copy(port = portText.toIntOrNull() ?: 0))
                    },
                    label = tr("端口（1-65535）"),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
                if (portText.isNotBlank() && (portText.toIntOrNull() ?: 0) !in 1..65535) {
                    SettingsHint(tr("端口要在 1-65535 之间。"), error = true)
                }
            }

            SettingsSection(tr("认证（可选）"), Icons.Outlined.Password, translucent = !opaque) {
                XtomField(
                    value = cfg.user,
                    onValueChange = { update(cfg.copy(user = it.trim())) },
                    label = tr("用户名（不需要就留空）"),
                    singleLine = true,
                    leading = { androidx.compose.material3.Icon(Icons.Outlined.Person, contentDescription = null, tint = scheme.onSurfaceVariant) },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                XtomField(
                    value = cfg.password,
                    onValueChange = { update(cfg.copy(password = it)) },
                    label = tr("密码"),
                    singleLine = true,
                    password = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
                SettingsHint(tr("账号密码只存在本机，不上传、不进日志。留空即匿名代理。"))
            }

            SettingsSection(tr("测试连通"), Icons.Outlined.NetworkCheck, translucent = !opaque) {
                XtomField(
                    value = testUrl,
                    onValueChange = { testUrl = it },
                    label = tr("测试目标地址"),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    XtomButton(
                        onClick = {
                            testing = true; result = null
                            scope.launch {
                                try {
                                    result = runProxyTest(cfg, testUrl)
                                } catch (ce: CancellationException) {
                                    throw ce
                                } finally {
                                    testing = false
                                }
                            }
                        },
                        enabled = !testing && cfg.usable && testUrl.isNotBlank(),
                    ) { Text(if (testing) tr("测试中…") else tr("测试连通")) }
                    if (testing) {
                        Spacer(Modifier.width(10.dp))
                        CircularProgressIndicator(modifier = Modifier.height(18.dp).width(18.dp), strokeWidth = 2.dp, color = scheme.primary)
                    }
                }
                when (val r = result) {
                    is ProxyTestResult.Ok -> SettingsHint(String.format(tr("已连通（HTTP %d，耗时 %d ms）。"), r.code, r.ms))
                    is ProxyTestResult.Fail -> SettingsHint(r.why, error = true)
                    null -> SettingsHint(tr("这一步只在你点按钮时才发一次请求，平时不会主动联网。"))
                }
            }
        }

        SettingsHint(tr("说明：代理只影响 App 自己发起的网络请求。内嵌浏览器/终端等由系统或各自组件出网的部分不走这里。"))
        Spacer(Modifier.height(24.dp))
    }
}
