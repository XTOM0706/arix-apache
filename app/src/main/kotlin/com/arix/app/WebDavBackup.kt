package com.arix.app

import android.content.Context
import android.util.Base64
import com.arix.data.db.AppDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ============================================================
// WebDAV 云备份 —— 把 FullBackup 的整包 zip 传到你自己的 WebDAV(坚果云/Nextcloud/自建)。
// 与 GitHub 备份并列的另一后端；用 HttpURLConnection + Basic Auth，无新依赖。
// ============================================================
object WebDavBackup {
    private const val PREFS = "xtom_webdav"
    /**
     * 以前的固定文件名（每次覆盖，云端永远只有一份）。**现在只作为恢复时的兜底候选**：
     * 新备份写 `arix-backup-<UTC时间戳>[.enc].zip`，保留最近 N 份（见 [BackupPolicy]）。
     */
    private const val FILE = BackupPolicy.FIXED_NAME
    /** 更名前旧包传上去的文件名。恢复时找不到新名就试它一次，否则老用户的云备份等于凭空消失。 */
    private const val LEGACY_FILE = BackupPolicy.LEGACY_FIXED_NAME

    data class Settings(val url: String, val user: String, val pass: String)
    fun settings(c: Context): Settings {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Settings(p.getString("url", "") ?: "", p.getString("user", "") ?: "", p.getString("pass", "") ?: "")
    }
    fun save(c: Context, url: String, user: String, pass: String) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("url", url.trim()).putString("user", user.trim()).putString("pass", pass).apply()

    fun autoSync(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto", false)
    fun setAutoSync(c: Context, v: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto", v).apply()
    fun intervalHours(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("interval_h", 12).coerceIn(1, 168)
    fun setIntervalHours(c: Context, v: Int) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("interval_h", v.coerceIn(1, 168)).apply()
    private fun lastBackup(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last", 0L)
    private fun setLast(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply()

    private fun fileUrl(s: Settings, name: String = FILE): String {
        val base = s.url.trim().trimEnd('/')
        return "$base/$name"
    }
    private fun conn(s: Settings, method: String, name: String = FILE): HttpURLConnection =
        (URL(fileUrl(s, name)).openConnection() as HttpURLConnection).apply {
            requestMethod = method; connectTimeout = 20000; readTimeout = 60000
            setRequestProperty("Authorization", "Basic " + Base64.encodeToString("${s.user}:${s.pass}".toByteArray(), Base64.NO_WRAP))
            setRequestProperty("User-Agent", "Arix/1.0")
        }

    /** 下载回来的必须是完整备份才允许覆盖恢复——防损坏毁本地数据（加密包由 GCM 标签在解密时兜完整性）。 */
    private fun validZip(b: ByteArray): Boolean = FullBackup.isRestorableBlob(b)

    // ---- 云端多版本：列出 / 清理 ----------------------------------------------------
    // WebDAV 列目录要发 PROPFIND，而 HttpURLConnection 的方法白名单里没有它（会抛 ProtocolException），
    // 底层 OkHttp 其实支持 → 反射改一下 method 字段。万一某些服务器/环境仍然不行，就退回**本地账本**：
    // 每次上传成功都把文件名记在本机 prefs 里，列表与清理照样能工作（只是列不出别的设备传的那几份）。

    private fun localLedger(c: Context): List<String> = try {
        org.json.JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("ledger", "[]") ?: "[]")
            .let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } } }
    } catch (_: Exception) { emptyList() }

    private fun ledgerAdd(c: Context, name: String) {
        val cur = (localLedger(c) + name).distinct().takeLast(100)   // 只留最近 100 条，别让账本无限长
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ledger", org.json.JSONArray(cur).toString()).apply()
    }

    private fun ledgerRemove(c: Context, name: String) {
        val cur = localLedger(c).filter { it != name }
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ledger", org.json.JSONArray(cur).toString()).apply()
    }

    /** HttpURLConnection 的方法白名单不含 PROPFIND；底层实现支持，直接改字段绕过。改不动就返回 false。 */
    private fun forceMethod(c: HttpURLConnection, m: String): Boolean = try {
        c.requestMethod = m; true
    } catch (_: Exception) {
        runCatching {
            val f = HttpURLConnection::class.java.getDeclaredField("method")
            f.isAccessible = true; f.set(c, m)
        }.isSuccess
    }

    private val RE_RESPONSE = Regex("<(?:[A-Za-z0-9]+:)?response[\\s>](.*?)</(?:[A-Za-z0-9]+:)?response>",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private fun tagOf(xml: String, tag: String): String? =
        Regex("<(?:[A-Za-z0-9]+:)?$tag[^>]*>(.*?)</(?:[A-Za-z0-9]+:)?$tag>",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)).find(xml)?.groupValues?.get(1)?.trim()

    private fun httpDate(s: String?): Long = try {
        if (s.isNullOrBlank()) 0L
        else java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US).parse(s)?.time ?: 0L
    } catch (_: Exception) { 0L }

    /** PROPFIND Depth:1 列目录。不支持 / 失败返回 null（调用方退回本地账本）。 */
    private fun propfind(s: Settings): List<CloudBackupVersion>? = try {
        val c = (URL(s.url.trim().trimEnd('/') + "/").openConnection() as HttpURLConnection)
        c.connectTimeout = 20000; c.readTimeout = 60000
        c.setRequestProperty("Authorization", "Basic " + Base64.encodeToString("${s.user}:${s.pass}".toByteArray(), Base64.NO_WRAP))
        c.setRequestProperty("User-Agent", "Arix/1.0")
        c.setRequestProperty("Depth", "1")
        c.setRequestProperty("Content-Type", "application/xml; charset=utf-8")
        if (!forceMethod(c, "PROPFIND")) null else {
            c.doOutput = true
            c.outputStream.use {
                it.write(("<?xml version=\"1.0\"?><d:propfind xmlns:d=\"DAV:\"><d:prop>" +
                    "<d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>").toByteArray())
            }
            val code = c.responseCode
            if (code !in 200..299) { runCatching { c.errorStream?.close() }; null } else {
                val xml = c.inputStream.use { String(it.readBytes(), Charsets.UTF_8) }
                RE_RESPONSE.findAll(xml).mapNotNull { m ->
                    val body = m.groupValues[1]
                    val href = tagOf(body, "href") ?: return@mapNotNull null
                    val name = runCatching { java.net.URLDecoder.decode(href, "UTF-8") }.getOrDefault(href)
                        .trimEnd('/').substringAfterLast('/')
                    BackupPolicy.describe(name, tagOf(body, "getcontentlength")?.toLongOrNull() ?: -1L,
                        httpDate(tagOf(body, "getlastmodified")))
                }.toList()
            }
        }
    } catch (ce: CancellationException) { throw ce } catch (_: Exception) { null }

    /** 列出云端历史版本（时间 / 大小 / 是否加密），给 UI 做版本选择。 */
    suspend fun listVersions(context: Context): List<CloudBackupVersion> = withContext(Dispatchers.IO) {
        val s = settings(context); if (s.url.isBlank()) return@withContext emptyList()
        val remote = propfind(s)
        val list = if (!remote.isNullOrEmpty()) remote
        else localLedger(context).mapNotNull { BackupPolicy.describe(it, -1L, 0L) }   // 服务器列不出来就用本地账本
        list.sortedWith(BackupPolicy.NEWEST_FIRST)
    }

    private fun deleteRemote(s: Settings, name: String): Boolean = try {
        val c = conn(s, "DELETE", name)
        val code = c.responseCode
        runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.close() }
        code in 200..299 || code == 404
    } catch (ce: CancellationException) { throw ce } catch (_: Exception) { false }

    /** 删掉云端某一份历史版本（id 取自 [listVersions]）。 */
    suspend fun deleteVersion(context: Context, id: String): String = withContext(Dispatchers.IO) {
        val s = settings(context); if (s.url.isBlank()) return@withContext "请先填 WebDAV 地址"
        if (deleteRemote(s, id)) { ledgerRemove(context, id); "已删除 $id" } else "删除失败：$id"
    }

    /** 保留最近 N 份，多出来的删掉。只在上传成功之后调用（先删后传 = 断网就两头空）。 */
    private fun pruneOld(context: Context, s: Settings): Int = try {
        val all = propfind(s) ?: localLedger(context).mapNotNull { BackupPolicy.describe(it, -1L, 0L) }
        var n = 0
        BackupPolicy.toPrune(all, BackupPolicy.keepVersions(context)).forEach {
            if (deleteRemote(s, it.id)) { ledgerRemove(context, it.id); n++ }
        }
        n
    } catch (ce: CancellationException) { throw ce } catch (_: Exception) { 0 }

    // ---- 备份 / 恢复 ----------------------------------------------------------------

    /** @param passphrase null=按设置走（设了备份口令就加密），""=强制明文。 */
    suspend fun backup(context: Context, passphrase: String? = null): String = withContext(Dispatchers.IO) {
        val s = settings(context)
        if (s.url.isBlank()) return@withContext "请先填 WebDAV 地址"
        try {
            val pass = BackupCrypto.resolve(context, passphrase)
            val bytes = ByteArrayOutputStream().also { FullBackup.exportTo(context, it, pass) }.toByteArray()
            val name = BackupPolicy.newName(pass.isNotEmpty())
            val c = conn(s, "PUT", name); c.doOutput = true
            c.setRequestProperty("Content-Type", "application/zip")
            c.outputStream.use { it.write(bytes) }
            val code = c.responseCode; runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.close() }
            if (code in 200..299) {
                setLast(context); ledgerAdd(context, name)
                val pruned = pruneOld(context, s)
                "备份成功 · 已上传到 WebDAV（${bytes.size / 1024} KB" + (if (pass.isNotEmpty()) " · 已加密" else "") + "）" +
                    (if (pruned > 0) "，清理 $pruned 份旧版本" else "")
            } else "备份失败（HTTP $code）：检查地址/账号密码/是否可写"
        } catch (ce: CancellationException) { throw ce }
        catch (e: OutOfMemoryError) { "备份失败：数据太大内存不足" }
        catch (e: Exception) { "备份失败：${e.message}" }
    }

    /**
     * @param versionId 取自 [listVersions]；不传 = 用最新一份（保持老行为）。
     * @param passphrase 加密包才用得上；不传 = 用本机保存的备份口令。
     */
    suspend fun restore(context: Context, versionId: String? = null, passphrase: String? = null): String = withContext(Dispatchers.IO) {
        val s = settings(context)
        if (s.url.isBlank()) return@withContext "请先填 WebDAV 地址"
        try {
            // 候选顺序：指定版本 > 最新的带时间戳版本 > 老的固定文件名 > 更名前的固定文件名（后两个是向后兼容）
            val candidates = when {
                versionId != null -> listOf(versionId)
                else -> (listVersions(context).map { it.id } + FILE + LEGACY_FILE).distinct()
            }
            var c: HttpURLConnection? = null
            var code = 404
            for (p in candidates) {
                val t = conn(s, "GET", p); code = t.responseCode
                if (code == 404) { runCatching { t.errorStream?.close() }; continue }
                c = t; break
            }
            if (c == null) return@withContext "WebDAV 上还没有备份文件（先备份一次）"
            if (code !in 200..299) { c.errorStream?.close(); return@withContext "拉取失败（HTTP $code）" }
            val bytes = c.inputStream.use { it.readBytes() }
            if (!validZip(bytes)) return@withContext "下载的备份无效或不完整，已中止，未改动本地数据。"
            FullBackup.importFrom(context, ByteArrayInputStream(bytes), passphrase)
            setLast(context)
            "恢复成功，正在重启应用…"
        } catch (ce: CancellationException) { throw ce }
        catch (e: BackupPassphraseException) { "恢复失败：${e.message}"   // 已是「口令不对」这类明确话术
        } catch (e: Exception) { "恢复失败：${e.message}" }
    }

    private fun isOnline(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { false }

    suspend fun maybeAutoBackup(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!autoSync(context)) return@withContext false
        if (settings(context).url.isBlank()) return@withContext false
        if (System.currentTimeMillis() - lastBackup(context) < intervalHours(context) * 3600_000L) return@withContext false
        if (!isOnline(context)) return@withContext false
        backup(context).startsWith("备份成功")
    }

    // ========================================================
    // 实时同步 —— 数据变更后【防抖】自动整包上传；靠【内容指纹】跳过未变（避免一动就重传整包）。
    // WebDAV 后端只有单个整包 zip，无分块，故实时同步 = 防抖后的整包 backup()，但仅在指纹变化时才真上传。
    // 指纹 = 与整包同源的三处(db 三件套 / filesDir 去 crash|exports / shared_prefs)的 名字|大小|mtime 累加哈希，
    //        免整包导出即可判定「有没有变」，省电（对应需求里的「按文件 mtime 跳过未变」）。
    // 与上面「联网自动备份」共用同一份整包与 last 计时：实时上传本就是一次合法整包，成功即刷新间隔。
    // ========================================================
    private val rtScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var rtJob: Job? = null

    fun realtime(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("realtime", false)
    fun setRealtime(c: Context, v: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("realtime", v).apply()
    fun debounceSeconds(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("rt_debounce_s", 60).coerceIn(5, 3600)
    fun setDebounceSeconds(c: Context, v: Int) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("rt_debounce_s", v.coerceIn(5, 3600)).apply()
    private fun lastFp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_fp", "") ?: ""
    private fun setLastFp(c: Context, v: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("last_fp", v).apply()

    /** 廉价内容指纹：遍历整包会打进去的三处，累加 相对名|大小|mtime，再 SHA-256。跳过我们自己的备份记账 prefs，避免自写自变的死循环。 */
    private fun fingerprint(context: Context): String {
        val app = context.applicationContext
        val sb = StringBuilder()
        try {
            val dbDir = app.getDatabasePath(AppDatabase.DB_NAME).parentFile
            dbDir?.listFiles { f -> f.name.startsWith(AppDatabase.DB_NAME) }?.sortedBy { it.name }?.forEach { f ->
                if (f.isFile) sb.append(f.name).append('|').append(f.length()).append('|').append(f.lastModified()).append('\n')
            }
            val filesDir = app.filesDir
            filesDir?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
                val rel = f.relativeTo(filesDir).path.replace('\\', '/')
                if (rel == "crash" || rel.startsWith("crash/") || rel == "exports" || rel.startsWith("exports/")) return@forEach
                sb.append(rel).append('|').append(f.length()).append('|').append(f.lastModified()).append('\n')
            }
            File(app.filesDir.parentFile, "shared_prefs").listFiles()?.sortedBy { it.name }?.forEach { f ->
                // 跳过备份自身的记账文件（我们每次上传都会写 last/last_fp，若计入指纹会永远「变化」）
                if (!f.isFile || f.name.startsWith("xtom_webdav") || f.name.startsWith("xtom_github_backup")) return@forEach
                sb.append(f.name).append('|').append(f.length()).append('|').append(f.lastModified()).append('\n')
            }
        } catch (_: Exception) { return "" }   // 算不出指纹就返回空 → 视为需要上传（宁可多传不漏传）
        return try {
            java.security.MessageDigest.getInstance("SHA-256").digest(sb.toString().toByteArray()).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { sb.length.toString() }
    }

    /**
     * 数据变更后调用：开了实时同步就【防抖】调度一次整包上传（重复调用重置防抖，末次变更后静默 debounce 秒才传，合批避免频繁上传）。
     * 上传前先比对指纹，未变则跳过、不联网。未开实时 / 未填地址直接忽略；非阻塞。
     */
    fun notifyChanged(context: Context) {
        val app = context.applicationContext
        if (!realtime(app) || settings(app).url.isBlank()) return
        val delayMs = debounceSeconds(app) * 1000L
        rtJob?.cancel()
        rtJob = rtScope.launch {
            try {
                delay(delayMs)
                syncChanged(app)
            } catch (_: CancellationException) { /* 被后续变更重置，正常 */
            } catch (_: Exception) { /* 后台静默 */ }
        }
    }

    /** 指纹变化才整包上传，否则跳过。返回简报，可给「立即同步」按钮调（suspend）。 */
    suspend fun syncChanged(context: Context): String = withContext(Dispatchers.IO) {
        val s = settings(context); if (s.url.isBlank()) return@withContext "未配置 WebDAV 地址"
        if (!isOnline(context)) return@withContext "离线，已跳过"
        val fp = fingerprint(context)
        if (fp.isNotEmpty() && fp == lastFp(context)) return@withContext "无变化，未上传"
        val r = backup(context)                              // 复用现成整包上传（内部 setLast）
        if (r.startsWith("备份成功")) { setLastFp(context, fp); "实时同步完成 · $r" } else r
    }
}
