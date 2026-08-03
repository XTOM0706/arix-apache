package com.arix.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.SystemUpdate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.ui.SettingsHint
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.SettingsToggle
import androidx.compose.material3.LinearProgressIndicator
import com.arix.app.theme.LocalXtomAccents
import com.arix.app.ui.XtomButton
import com.arix.app.ui.topChromeGapHeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ============================================================
// 应用内检查更新 —— 拉 GitHub Releases 的最新版本号跟本机 versionName 比。
//
// 两条硬约束：
//  1. **默认关**。本项目现在是冷启动零联网，这个功能不许破掉——开关关着时一个字节都不发；
//     开着也只在用户点「检查更新」那一下才发一次请求，永远没有自动轮询。
//  2. 仓库现在还是私有的，API 会返回 404。那种情况要如实说「暂时查不到」，
//     不能甩一个 HTTP 404 之类的错误吓人——那不是用户做错了什么。
// ============================================================
object UpdatePrefs {
    private const val PREFS = "arix_update_prefs"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 默认 false：不开就永不联网。 */
    fun enabled(c: Context): Boolean = p(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean("enabled", on).apply()

    fun lastCheck(c: Context): Long = p(c).getLong("last_check", 0L)
    fun setLastCheck(c: Context, ts: Long) = p(c).edit().putLong("last_check", ts).apply()

    /** 用户点了「稍后」的到期时间戳（epoch ms）。到点前不弹窗、不通知。0 = 没设过。 */
    fun postponeUntil(c: Context): Long = p(c).getLong("postpone_until", 0L)
    fun setPostponeUntil(c: Context, ts: Long) = p(c).edit().putLong("postpone_until", ts).apply()

    /** 是否仍处「稍后」冷却期。 */
    fun postponed(c: Context): Boolean = postponeUntil(c) > System.currentTimeMillis()

    /** 清除「稍后」冷却（点了更新/选了新时间/手动进更新页时）。 */
    fun clearPostpone(c: Context) = p(c).edit().remove("postpone_until").apply()
}

object UpdateChecker {
    /** Apache-2.0 精简版自己的仓库：更新从它这里发，不再指向 GPL 满血版。 */
    const val REPO = "XTOM0706/arix-apache"
    /**
     * ⚠️ `/releases/latest` **不返回 prerelease**（我们的开发快照全是 prerelease，
     * 那条路会直接 404），所以主用的是列表接口：按发布时间倒序，第一个就是最新的，
     * **包含** prerelease。
     */
    private const val API_LIST = "https://api.github.com/repos/$REPO/releases?per_page=5"
    const val RELEASES_URL = "https://github.com/$REPO/releases"

    data class Release(
        val tag: String, val name: String, val notes: String, val url: String, val published: String,
        /** 这一版的 APK 直链（release 里名字匹配的那个资产）。没有就为 null —— 那时只能让用户去网页下。 */
        val apkUrl: String? = null,
        /** APK 字节数，用来在按钮上显示「下载并安装（39.9 MB）」。0=不知道。 */
        val apkSize: Long = 0,
        /** 是不是预发布（开发快照）。UI 要标出来，别让人以为这是稳定版。 */
        val prerelease: Boolean = false,
    )

    sealed class Result {
        /** 有新版。 */
        data class NewVersion(val release: Release) : Result()

        /** 已是最新。 */
        data class UpToDate(val current: String) : Result()

        /** 查不到——网络不通、仓库还没公开、还没发过 release，一律走这条，措辞要平静。 */
        data class Unavailable(val why: String) : Result()
    }

    fun currentVersion(c: Context): String = runCatching {
        @Suppress("DEPRECATION")
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "1.0"
    }.getOrDefault("1.0")

    /** tag 的数字段（"v1.2.3-beta" → [1,2,3]；"snapshot-2026-08-01" → [2026,8,1]）。 */
    private fun partsOf(s: String): List<Int> =
        Regex("\\d+").findAll(s).map { it.value.toIntOrNull() ?: 0 }.take(4).toList()

    /** 逐段比：a 比 b 新为 true，相等/更旧为 false。 */
    private fun gt(a: List<Int>, b: List<Int>): Boolean {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /**
     * 版本号比较：只取数字段，逐段比，缺位补 0。
     * 比不出来（比如带日期的自定义命名）就返回 0 = 视为「不比当前新」，宁可漏报也不误报。
     */
    fun isNewer(remoteTag: String, current: String): Boolean {
        val a = partsOf(remoteTag)
        val b = partsOf(current)
        if (a.isEmpty() || b.isEmpty()) return false
        return gt(a, b)
    }

    /**
     * 从 `/releases` 的返回里取**最新一条**（数组第一个即最新，含 prerelease），
     * 并挑出名字以 `.apk` 结尾的资产当安装包。
     *
     * 主 App 的包名带日期（`arix-2026-07-30.apk`），所以这里**不按固定名字匹配**，
     * 只要是 apk 且不是终端那个就算 —— 否则每次改包名都要同步改代码。
     */
    private fun parseNewest(body: String): Release? = runCatching {
        val arr = org.json.JSONArray(body)
        if (arr.length() == 0) return null
        // ⚠️ 不能只信 arr[0]：GitHub 的 /releases 列表顺序并不总是「最新在前」
        //（实测 v0.1.0 一直排在 snapshot-2026-08-01 前面），只取第一个会把新版本漏掉、
        // 永远判「已是最新」。改扫全部、挑 tag 数字段最大且带可下载 apk 的那条。
        var best: Release? = null
        var bestParts: List<Int> = emptyList()
        for (j in 0 until arr.length()) {
            val o = arr.optJSONObject(j) ?: continue
            val tag = o.optString("tag_name").ifBlank { o.optString("name") }
            if (tag.isBlank()) continue
            var apkUrl: String? = null
            var apkSize = 0L
            val assets = o.optJSONArray("assets")
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val n = a.optString("name")
                if (!n.endsWith(".apk", true)) continue
                // 终端已经在它自己的仓库里发了，正常情况下这个 release 里不会有它的包；
                // 这一行只是兜底，防止哪次手滑又把终端包传进主 App 的 release 里。
                if (n.contains("terminal", true)) continue
                apkUrl = a.optString("browser_download_url").takeIf { it.isNotBlank() }
                apkSize = a.optLong("size", 0L)
                break
            }
            if (apkUrl == null) continue   // 这条没有可下载的包，跳过
            val tp = partsOf(tag)
            if (tp.isEmpty()) continue
            if (best == null || gt(tp, bestParts)) {
                best = Release(
                    tag = tag,
                    name = o.optString("name").ifBlank { tag },
                    notes = o.optString("body").take(2000),
                    url = o.optString("html_url").ifBlank { RELEASES_URL },
                    published = o.optString("published_at").take(10),
                    apkUrl = apkUrl,
                    apkSize = apkSize,
                    prerelease = o.optBoolean("prerelease", false),
                )
                bestParts = tp
            }
        }
        best
    }.getOrNull()

    /**
     * 取最新 release 里某个**指定名字**的资产直链。给 [com.arix.tool.RemoteAssets] 解析终端包用。
     *
     * 独立于 [check]：那个要比版本号、要给 UI 三态；这里只要一个 URL，拿不到就 null。
     */
    /**
     * 取**某个仓库**最新 release 里第一个满足 [match] 的资产直链。
     *
     * 为什么带 repo 参数、且按谓词而不是固定文件名匹配：终端在**它自己的仓库**里发布、
     * 有自己的版本线（终端 v1.1 / 主 App v0.1.0），资产名也由那边定
     * （实际是 `arix-terminal-1.1-arm64.apk`）。写死仓库或写死文件名，
     * 表现都是"某天突然 404"，而 UI 只会说"下载失败"，极难查。
     */
    suspend fun assetUrl(
        context: Context,
        repo: String = REPO,
        match: (String) -> Boolean,
    ): String? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            val u = URL("https://api.github.com/repos/$repo/releases?per_page=5")
            val proxy = ProxyPrefs.javaProxy(context)
            val c = (if (proxy != null) u.openConnection(proxy) else u.openConnection()) as HttpURLConnection
            conn = c
            c.connectTimeout = 10000; c.readTimeout = 10000
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Arix")
            if (c.responseCode !in 200..299) return@withContext null
            val arr = org.json.JSONArray(c.inputStream.bufferedReader().use { it.readText() })
            if (arr.length() == 0) return@withContext null
            val assets = arr.getJSONObject(0).optJSONArray("assets") ?: return@withContext null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (match(a.optString("name")))
                    return@withContext a.optString("browser_download_url").takeIf { it.isNotBlank() }
            }
            null
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 只在用户手点时调。整段在 IO 线程，可取消。 */
    suspend fun check(context: Context): Result = withContext(Dispatchers.IO) {
        val current = currentVersion(context)
        var conn: HttpURLConnection? = null
        try {
            val url = URL(API_LIST)
            // 代理开着就顺着代理走（跟 App 其余出网保持一致）；没开就是直连
            val proxy = ProxyPrefs.javaProxy(context)
            val c = (if (proxy != null) url.openConnection(proxy) else url.openConnection()) as HttpURLConnection
            conn = c
            c.connectTimeout = 10000
            c.readTimeout = 10000
            c.requestMethod = "GET"
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("User-Agent", "Arix")
            val code = c.responseCode
            if (code == 404 || code == 403 || code == 401) {
                // 私有仓 / 还没发过 release / 触发限流：都不是用户的问题，别报错
                AppLog.d("update", "检查更新：仓库不可访问 code=$code")
                return@withContext Result.Unavailable(tr("暂时查不到更新信息（仓库尚未公开，或还没有发布版本）。"))
            }
            if (code !in 200..299) {
                AppLog.w("update", "检查更新：服务端返回 $code")
                return@withContext Result.Unavailable(tr("暂时查不到更新信息，稍后再试。"))
            }
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val rel = parseNewest(body)
                ?: return@withContext Result.Unavailable(tr("暂时查不到更新信息（还没有发布版本）。"))
            AppLog.d("update", "检查更新完成 tag=${rel.tag}")
            if (isNewer(rel.tag, current)) Result.NewVersion(rel) else Result.UpToDate(current)
        } catch (ce: CancellationException) {
            throw ce   // 取消要原样抛回去，别被下面的 Exception 分支吞掉
        } catch (e: Exception) {
            AppLog.w("update", "检查更新失败 ${e.javaClass.simpleName}")
            Result.Unavailable(tr("暂时查不到更新信息（网络没通）。"))
        } finally {
            runCatching { conn?.disconnect() }
        }
    }
}

// ============================================================
// 页面
// ============================================================
@Composable fun UpdateCheckPage(context: Context) {
    val accents = LocalXtomAccents.current
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val opaque = remember { PageBackgroundPrefs.get(context, "update") != null }

    val current = remember { UpdateChecker.currentVersion(context) }
    var allowed by remember { mutableStateOf(UpdatePrefs.enabled(context)) }
    var checking by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<UpdateChecker.Result?>(null) }
    var lastCheck by remember { mutableStateOf(UpdatePrefs.lastCheck(context)) }
    val sdf = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    var installing by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf(0f) }
    var installError by remember { mutableStateOf<String?>(null) }

    // 更新说明：默认显示原文；非中文界面给「翻译」按钮，用户选择要不要翻（不自动花 token）
    // key=result：换版本后译文/翻译中状态随旧版本作废，不残留。
    var updTranslated by remember(result) { mutableStateOf<String?>(null) }
    var updTranslating by remember(result) { mutableStateOf(false) }
    val curLang = com.arix.app.I18n.lang.collectAsState().value.code

    // 打开本页就自动查一次（开关开着才查）。和「打开 App 就查」是两处，互不干扰。
    LaunchedEffect(Unit) {
        if (allowed) {
            checking = true; result = null
            try {
                val r = UpdateChecker.check(context)
                result = r
                val now = System.currentTimeMillis()
                UpdatePrefs.setLastCheck(context, now); lastCheck = now
            } catch (ce: CancellationException) {
                throw ce
            } finally {
                checking = false
            }
        }
    }

    fun open(url: String) {
        runCatching {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.topChromeGapHeight())

        SettingsSection(tr("当前版本"), Icons.Outlined.Info, translucent = !opaque) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(String.format(tr("版本 %s"), current), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                    Text(
                        if (lastCheck > 0L) String.format(tr("上次检查：%s"), sdf.format(Date(lastCheck)))
                        else tr("还没检查过"),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
            }
        }

        SettingsHint(tr("本版为 Apache-2.0 精简版，移除了部分能力。想要完整功能，请改用 GPL 满血版（官方仓库 XTOM0706/arix-app）；本版更新走自己的仓库 XTOM0706/arix-apache。"))
        SettingsSection(tr("检查更新"), Icons.Outlined.SystemUpdate, translucent = !opaque) {
            SettingsToggle(
                icon = Icons.Outlined.SystemUpdate,
                title = tr("允许应用内检查更新"),
                subtitle = tr("默认开：每天在联网（不计流量）时自动查一次，发现新版本会发通知提醒，点通知直达本页看更新内容并安装。关掉则永不自动联网，只能点下面的按钮手动查。"),
                checked = allowed,
                onCheckedChange = {
                    allowed = it; UpdatePrefs.setEnabled(context, it); if (!it) result = null
                    // 立刻重排/取消后台任务——不然打开开关要等到下次冷启动才生效，
                    // 关掉开关则会留一个还在偷偷联网的任务。
                    UpdateNotifier.sync(context)
                },
            )
            Spacer(Modifier.height(6.dp))
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                XtomButton(
                    onClick = {
                        checking = true; result = null
                        scope.launch {
                            try {
                                val r = UpdateChecker.check(context)
                                result = r
                                val now = System.currentTimeMillis()
                                UpdatePrefs.setLastCheck(context, now); lastCheck = now
                            } catch (ce: CancellationException) {
                                throw ce
                            } finally {
                                checking = false
                            }
                        }
                    },
                    enabled = allowed && !checking,
                ) {
                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(if (checking) tr("检查中…") else tr("检查更新"))
                }
                if (checking) {
                    Spacer(Modifier.width(10.dp))
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = scheme.primary)
                }
            }
            if (!allowed) SettingsHint(tr("开关关着，这个按钮不会发起任何网络请求。"))
        }

        when (val r = result) {
            is UpdateChecker.Result.NewVersion -> {
                SettingsSection(tr("有新版本"), Icons.Outlined.CloudDownload, translucent = !opaque) {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
                        Text(r.release.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = scheme.primary)
                        Text(
                            String.format(tr("%s → %s%s"), current, r.release.tag, if (r.release.published.isNotBlank()) "  ${r.release.published}" else ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        if (r.release.notes.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            // 更新说明支持 markdown + 图片（屏幕内渲染，Coil 异步加载即可）
                            MarkdownText(
                                if (updTranslating) tr("翻译中…")
                                else (updTranslated ?: r.release.notes),
                                color = scheme.onSurface, fontSize = 13.sp,
                            )
                            // 非中文界面 + 有原文 → 给「翻译」按钮，用户选择要不要翻（不自动花 token）
                            if (curLang != "zh") {
                                Spacer(Modifier.height(4.dp))
                                TextButton(
                                    enabled = !updTranslating,
                                    onClick = {
                                        if (updTranslated != null) { updTranslated = null; return@TextButton }
                                        updTranslating = true
                                        scope.launch {
                                            updTranslated = translateReleaseNotes(context, r.release.notes, curLang)
                                            updTranslating = false
                                        }
                                    },
                                ) { Text(
                                    if (updTranslating) tr("翻译中…")
                                    else if (updTranslated != null) tr("显示原文")
                                    else tr("翻译成当前语言"),
                                    color = scheme.primary, fontSize = 12.sp,
                                ) }
                            }
                        }
                        if (r.release.prerelease) {
                            Spacer(Modifier.height(4.dp))
                            // 标出来：开发快照不是稳定版，别让人以为"更新了就更稳"
                            Text(tr("这是开发快照（prerelease），不是稳定版。"),
                                style = MaterialTheme.typography.bodySmall, color = accents.warning)
                        }
                        Spacer(Modifier.height(8.dp))
                        val apk = r.release.apkUrl
                        if (apk != null) {
                            if (installing) {
                                // 拿不到 Content-Length 时 progress 为 -1 → 显示不确定进度条，
                                // 而不是一个一直卡在 0% 的确定进度条（那会让人以为卡死了）
                                if (installProgress < 0f) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(progress = { installProgress }, modifier = Modifier.fillMaxWidth())
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    if (installProgress < 0f) tr("下载中…")
                                    else String.format(tr("下载中… %d%%"), (installProgress * 100).toInt()),
                                    style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant,
                                )
                            } else {
                                XtomButton(onClick = {
                                    installing = true; installProgress = 0f; installError = null
                                    scope.launch {
                                        val err = com.arix.tool.ApkInstaller.downloadAndInstall(
                                            context, apk, "arix-update.apk",
                                        ) { p -> installProgress = p }
                                        installing = false
                                        installError = err
                                    }
                                }) {
                                    Icon(Icons.Outlined.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (r.release.apkSize > 0)
                                            String.format(tr("下载并安装（%.1f MB）"), r.release.apkSize / 1048576.0)
                                        else tr("下载并安装"),
                                    )
                                }
                            }
                            installError?.let {
                                Spacer(Modifier.height(4.dp))
                                Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.error)
                            }
                            Spacer(Modifier.height(6.dp))
                            // 这句不是免责声明，是说明「凭什么敢直接装」——校验的是签名，不是文件名或来源
                            SettingsHint(tr("下载完会先核对安装包的签名与本应用一致，对不上就直接丢弃、不会交给安装器。最后一步仍由系统安装界面确认，App 不会静默替换自己。"))
                        }
                        Spacer(Modifier.height(4.dp))
                        XtomButton(onClick = { open(r.release.url) }) {
                            Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(tr("打开下载页"))
                        }
                    }
                }
            }
            is UpdateChecker.Result.UpToDate -> SettingsHint(String.format(tr("已是最新版本（%s）。"), r.current))
            is UpdateChecker.Result.Unavailable -> SettingsHint(r.why)
            null -> Unit
        }

        SettingsHint(String.format(tr("版本信息来源：%s 的 Releases 页。"), UpdateChecker.REPO))
        Spacer(Modifier.height(24.dp))
    }
}
