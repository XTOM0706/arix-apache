package com.arix.app

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

// ============================================================
// S3 兼容对象存储备份 —— 与 WebDavBackup 对等的另一后端。
// 复用 FullBackup 的整包 zip（Room 库 + filesDir + shared_prefs），只把传输层换成
// AWS Signature V4 签名的 S3 REST API。支持 AWS S3 / MinIO / 阿里云 OSS / 腾讯 COS 等
// 兼容 S3 协议的存储（MinIO 通常需 path-style）。
//
// 纯 java.net.HttpURLConnection + javax.crypto，无 AWS SDK、无新依赖。
// 备份内容与 WebDAV/GitHub 后端完全一致（同一份 FullBackup 快照）。
// ============================================================
object S3Backup {
    private const val PREFS = "xtom_s3"
    /**
     * 以前的固定对象名（每次覆盖，云端永远只有一份）。**现在只作为恢复时的兜底候选**：
     * 新备份写 `arix-backup-<UTC时间戳>[.enc].zip`，保留最近 N 份（见 [BackupPolicy]）。
     */
    private const val FILE = BackupPolicy.FIXED_NAME
    /** 更名前旧包传上去的对象名。恢复找不到新名就试它一次，否则老用户云上的备份等于消失。 */
    private const val LEGACY_FILE = BackupPolicy.LEGACY_FIXED_NAME
    private const val ALGORITHM = "AWS4-HMAC-SHA256"
    private const val SERVICE = "s3"
    // 空 body 的 SHA256（GET / 恢复时用）
    private const val EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    /**
     * S3 连接配置。
     *  - endpoint：带协议的服务端点，如 https://s3.amazonaws.com、https://oss-cn-hangzhou.aliyuncs.com、
     *    自建 MinIO https://minio.example.com:9000。
     *  - region：区域，如 us-east-1；MinIO/自建随意填（如 us-east-1）但不能空。
     *  - bucket：桶名。
     *  - accessKey / secretKey：访问密钥。
     *  - prefix：对象前缀（目录），可空。如 "arix/backups"。
     *  - pathStyle：true = https://endpoint/bucket/key（MinIO/多数自建需要）；
     *               false = 虚拟主机式 https://bucket.endpoint/key（AWS S3 默认）。
     */
    data class S3Config(
        val endpoint: String,
        val region: String,
        val bucket: String,
        val accessKey: String,
        val secretKey: String,
        val prefix: String = "",
        val pathStyle: Boolean = true,
    )

    // ---------- 配置持久化（与 WebDavBackup 同风格，供设置页读写） ----------
    fun settings(c: Context): S3Config {
        val p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return S3Config(
            endpoint = p.getString("endpoint", "") ?: "",
            region = p.getString("region", "us-east-1") ?: "us-east-1",
            bucket = p.getString("bucket", "") ?: "",
            accessKey = p.getString("access_key", "") ?: "",
            secretKey = p.getString("secret_key", "") ?: "",
            prefix = p.getString("prefix", "") ?: "",
            pathStyle = p.getBoolean("path_style", true),
        )
    }

    fun save(c: Context, cfg: S3Config) =
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("endpoint", cfg.endpoint.trim())
            .putString("region", cfg.region.trim().ifBlank { "us-east-1" })
            .putString("bucket", cfg.bucket.trim())
            .putString("access_key", cfg.accessKey.trim())
            .putString("secret_key", cfg.secretKey.trim())
            .putString("prefix", cfg.prefix.trim())
            .putBoolean("path_style", cfg.pathStyle)
            .apply()

    fun autoSync(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("auto", false)
    fun setAutoSync(c: Context, v: Boolean) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("auto", v).apply()
    fun intervalHours(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("interval_h", 12).coerceIn(1, 168)
    fun setIntervalHours(c: Context, v: Int) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt("interval_h", v.coerceIn(1, 168)).apply()
    private fun lastBackup(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last", 0L)
    private fun setLast(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last", System.currentTimeMillis()).apply()

    private fun ok(cfg: S3Config): Boolean =
        cfg.endpoint.isNotBlank() && cfg.bucket.isNotBlank() && cfg.accessKey.isNotBlank() && cfg.secretKey.isNotBlank()

    /** 备份对象的 key：prefix 规整后 + 固定文件名。 */
    private fun objectKey(cfg: S3Config, name: String = FILE): String {
        val p = cfg.prefix.trim().trim('/')
        return if (p.isEmpty()) name else "$p/$name"
    }

    // ---------- 公开 API（与 WebDavBackup 对等） ----------

    /**
     * 备份：把 FullBackup 的整包 zip PUT 到 S3（一份带时间戳的新对象），再清理超出保留数的旧版本。
     * @param passphrase null=按设置走（设了备份口令就加密），""=强制明文。
     */
    suspend fun backup(context: Context, cfg: S3Config = settings(context), passphrase: String? = null): String = withContext(Dispatchers.IO) {
        if (!ok(cfg)) return@withContext "请先填 S3 端点 / 桶 / AccessKey / SecretKey"
        try {
            val pass = BackupCrypto.resolve(context, passphrase)
            val bytes = ByteArrayOutputStream().also { FullBackup.exportTo(context, it, pass) }.toByteArray()
            val name = BackupPolicy.newName(pass.isNotEmpty())
            val (code, err) = putObject(cfg, objectKey(cfg, name), bytes)
            if (code in 200..299) {
                setLast(context)
                val pruned = pruneOld(context, cfg)   // 只在上传成功之后清理：先删后传断网就两头空
                "备份成功 · 已上传到 S3（${bytes.size / 1024} KB" + (if (pass.isNotEmpty()) " · 已加密" else "") + "）" +
                    (if (pruned > 0) "，清理 $pruned 份旧版本" else "")
            } else "备份失败（HTTP $code）：${err ?: "检查端点/区域/桶/密钥/path-style/写权限"}"
        } catch (ce: CancellationException) { throw ce }
        catch (e: OutOfMemoryError) { "备份失败：数据太大内存不足" }
        catch (e: Exception) { "备份失败：${e.message}" }
    }

    /**
     * 恢复：GET 备份对象并交给 FullBackup 整体覆盖。成功后调用方应触发重启。
     * @param versionId 取自 [listVersions] 的 id（完整对象 key）；不传 = 用最新一份（保持老行为）。
     * @param passphrase 加密包才用得上；不传 = 用本机保存的备份口令。
     */
    suspend fun restore(
        context: Context,
        cfg: S3Config = settings(context),
        versionId: String? = null,
        passphrase: String? = null,
    ): String = withContext(Dispatchers.IO) {
        if (!ok(cfg)) return@withContext "请先填 S3 端点 / 桶 / AccessKey / SecretKey"
        try {
            // 候选顺序：指定版本 > 最新的带时间戳版本 > 老的固定对象名 > 更名前的固定对象名（后两个是向后兼容）
            val candidates = when {
                versionId != null -> listOf(versionId)
                else -> (listVersions(context, cfg).map { it.id } + objectKey(cfg) + objectKey(cfg, LEGACY_FILE)).distinct()
            }
            var code = 404; var bytes: ByteArray? = null; var err: String? = null
            for (k in candidates) {
                val r = getObject(cfg, k)
                if (r.first == 404) continue
                code = r.first; bytes = r.second; err = r.third; break
            }
            if (code == 404) return@withContext "S3 上还没有备份文件（先备份一次）"
            if (code !in 200..299 || bytes == null) return@withContext "拉取失败（HTTP $code）：${err ?: "检查端点/区域/桶/密钥"}"
            if (!validZip(bytes)) return@withContext "下载的备份无效或不完整，已中止，未改动本地数据。"
            FullBackup.importFrom(context, ByteArrayInputStream(bytes), passphrase)
            setLast(context)
            "恢复成功，正在重启应用…"
        } catch (ce: CancellationException) { throw ce }
        catch (e: BackupPassphraseException) { "恢复失败：${e.message}"   // 已是「口令不对」这类明确话术
        } catch (e: OutOfMemoryError) { "恢复失败：备份数据太大内存不足" }
        catch (e: Exception) { "恢复失败：${e.message}" }
    }

    /**
     * 列出云端历史版本（时间 / 大小 / 是否加密），给 UI 做版本选择。
     * 时间优先取文件名里的时间戳，取不到才用对象的 LastModified。
     */
    suspend fun listVersions(context: Context, cfg: S3Config = settings(context)): List<CloudBackupVersion> = withContext(Dispatchers.IO) {
        if (!ok(cfg)) return@withContext emptyList()
        try {
            val (code, body, _) = listObjects(cfg, cfg.prefix.trim().trim('/'))
            if (code !in 200..299 || body == null) return@withContext emptyList()
            val xml = String(body, Charsets.UTF_8)
            Regex("<Contents>(.*?)</Contents>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
                val one = m.groupValues[1]
                val key = Regex("<Key>(.*?)</Key>", RegexOption.DOT_MATCHES_ALL).find(one)?.groupValues?.get(1)?.trim()
                    ?: return@mapNotNull null
                val size = Regex("<Size>(\\d+)</Size>").find(one)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
                val mtime = iso8601(Regex("<LastModified>(.*?)</LastModified>").find(one)?.groupValues?.get(1))
                // 认领时只看文件名部分；id 换成完整 key（恢复/删除要用整个 key）
                BackupPolicy.describe(key.substringAfterLast('/'), size, mtime)?.copy(id = key)
            }.sortedWith(BackupPolicy.NEWEST_FIRST).toList()
        } catch (ce: CancellationException) { throw ce }
        catch (e: Exception) { emptyList() }
    }

    /** 删掉云端某一份历史版本（id 取自 [listVersions]，是完整对象 key）。 */
    suspend fun deleteVersion(context: Context, id: String, cfg: S3Config = settings(context)): String = withContext(Dispatchers.IO) {
        if (!ok(cfg)) return@withContext "请先填 S3 端点 / 桶 / AccessKey / SecretKey"
        val (code, err) = deleteObject(cfg, id)
        if (code in 200..299 || code == 404) "已删除 $id" else "删除失败（HTTP $code）：${err ?: id}"
    }

    /** 保留最近 N 份，多出来的删掉。固定对象名的老备份不在清理范围内（见 [BackupPolicy.toPrune]）。 */
    private suspend fun pruneOld(context: Context, cfg: S3Config): Int = try {
        var n = 0
        BackupPolicy.toPrune(listVersions(context, cfg), BackupPolicy.keepVersions(context)).forEach {
            val (code, _) = deleteObject(cfg, it.id)
            if (code in 200..299 || code == 404) n++
        }
        n
    } catch (ce: CancellationException) { throw ce } catch (_: Exception) { 0 }

    private fun iso8601(s: String?): Long = try {
        if (s.isNullOrBlank()) 0L
        else SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s.trim().substringBefore('.').trimEnd('Z'))?.time ?: 0L
    } catch (_: Exception) { 0L }

    /** 连通性 / 凭证自检：对备份对象发 HEAD。200/404 都算连通（404 = 桶可达但还没备份过）。 */
    suspend fun test(context: Context, cfg: S3Config = settings(context)): String = withContext(Dispatchers.IO) {
        if (!ok(cfg)) return@withContext "请先填 S3 端点 / 桶 / AccessKey / SecretKey"
        try {
            val (code, err) = headObject(cfg, objectKey(cfg))
            when {
                code == 200 -> "连接正常 · 已有备份对象"
                // 固定对象名早就不写了（改成带时间戳的多版本），HEAD 404 不代表没备份 → 再列一次才说得准
                code == 404 -> listVersions(context, cfg).size.let { if (it > 0) "连接正常 · 云端有 $it 份备份" else "连接正常 · 桶可达（暂无备份）" }
                code == 403 -> "认证失败（HTTP 403）：AccessKey/SecretKey 或权限不对"
                else -> "连接异常（HTTP $code）：${err ?: "检查端点/区域/桶/path-style"}"
            }
        } catch (ce: CancellationException) { throw ce }
        catch (e: Exception) { "连接失败：${e.message}" }
    }

    /** 可选：列出 prefix 下的对象 key（ListObjectsV2）。失败返回空表。 */
    suspend fun listBackups(context: Context, cfg: S3Config = settings(context)): List<String> = withContext(Dispatchers.IO) {
        if (!ok(cfg)) return@withContext emptyList()
        try {
            val (code, body, _) = listObjects(cfg, cfg.prefix.trim().trim('/'))
            if (code !in 200..299 || body == null) return@withContext emptyList()
            val xml = String(body, Charsets.UTF_8)
            Regex("<Key>(.*?)</Key>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
                .map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
        } catch (ce: CancellationException) { throw ce }
        catch (e: Exception) { emptyList() }
    }

    /** 自动备份（与 WebDavBackup 同策略：开关 + 间隔 + 联网）。 */
    suspend fun maybeAutoBackup(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!autoSync(context)) return@withContext false
        val cfg = settings(context)
        if (!ok(cfg)) return@withContext false
        if (System.currentTimeMillis() - lastBackup(context) < intervalHours(context) * 3600_000L) return@withContext false
        if (!isOnline(context)) return@withContext false
        backup(context, cfg).startsWith("备份成功")
    }

    // ---------- S3 REST 请求（AWS SigV4 手写签名） ----------

    /** PUT 对象；返回 (httpCode, 错误摘要?)。用真实 SHA256 payload hash。 */
    private fun putObject(cfg: S3Config, key: String, body: ByteArray): Pair<Int, String?> {
        val payloadHash = sha256Hex(body)
        val conn = signedConn(cfg, "PUT", key, emptyMap(), payloadHash)
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(body.size)
        conn.setRequestProperty("Content-Type", "application/zip")
        conn.outputStream.use { it.write(body) }
        val code = conn.responseCode
        val err = if (code in 200..299) null else readErr(conn)
        runCatching { (if (code in 200..299) conn.inputStream else conn.errorStream)?.close() }
        return code to err
    }

    /** GET 对象；返回 (httpCode, bytes?, 错误摘要?)。 */
    private fun getObject(cfg: S3Config, key: String): Triple<Int, ByteArray?, String?> {
        val conn = signedConn(cfg, "GET", key, emptyMap(), EMPTY_SHA256)
        val code = conn.responseCode
        if (code == 404) { runCatching { conn.errorStream?.close() }; return Triple(404, null, null) }
        if (code !in 200..299) return Triple(code, null, readErr(conn))
        val bytes = conn.inputStream.use { it.readBytes() }
        return Triple(code, bytes, null)
    }

    /** HEAD 对象；返回 (httpCode, 错误摘要?)。 */
    private fun headObject(cfg: S3Config, key: String): Pair<Int, String?> {
        val conn = signedConn(cfg, "HEAD", key, emptyMap(), EMPTY_SHA256)
        val code = conn.responseCode
        val err = if (code in 200..299 || code == 404) null else readErr(conn)
        runCatching { conn.inputStream?.close() }
        runCatching { conn.errorStream?.close() }
        return code to err
    }

    /** DELETE 对象；返回 (httpCode, 错误摘要?)。404 视为已经不在了，调用方按成功处理。 */
    private fun deleteObject(cfg: S3Config, key: String): Pair<Int, String?> {
        val conn = signedConn(cfg, "DELETE", key, emptyMap(), EMPTY_SHA256)
        val code = conn.responseCode
        val err = if (code in 200..299 || code == 404) null else readErr(conn)
        runCatching { conn.inputStream?.close() }
        runCatching { conn.errorStream?.close() }
        return code to err
    }

    /** ListObjectsV2；桶根发 GET ?list-type=2&prefix=…。返回 (code, xmlBytes?, err?)。 */
    private fun listObjects(cfg: S3Config, prefix: String): Triple<Int, ByteArray?, String?> {
        val query = linkedMapOf("list-type" to "2")
        if (prefix.isNotEmpty()) query["prefix"] = prefix
        val conn = signedConn(cfg, "GET", "", query, EMPTY_SHA256)
        val code = conn.responseCode
        if (code !in 200..299) return Triple(code, null, readErr(conn))
        val bytes = conn.inputStream.use { it.readBytes() }
        return Triple(code, bytes, null)
    }

    private fun readErr(conn: HttpURLConnection): String? = try {
        val raw = conn.errorStream?.use { String(it.readBytes(), Charsets.UTF_8) } ?: return null
        // 从 S3 XML 错误里抽 <Message>，抽不到就截断原文
        Regex("<Message>(.*?)</Message>", RegexOption.DOT_MATCHES_ALL).find(raw)?.groupValues?.get(1)?.trim()
            ?: raw.take(200).ifBlank { null }
    } catch (_: Exception) { null }

    /**
     * 构造已带 SigV4 Authorization 头的连接。
     * @param key    对象 key（不含前导 /，list 时可空指桶根）
     * @param query  规范查询参数（未编码的原值）
     * @param payloadHash 十六进制 SHA256，或 "UNSIGNED-PAYLOAD"
     */
    private fun signedConn(
        cfg: S3Config,
        method: String,
        key: String,
        query: Map<String, String>,
        payloadHash: String,
    ): HttpURLConnection {
        val ep = URL(cfg.endpoint.trim().trimEnd('/'))
        val scheme = ep.protocol
        val portPart = if (ep.port != -1 && ep.port != ep.defaultPort) ":${ep.port}" else ""
        val epHost = ep.host + portPart
        val bucket = cfg.bucket.trim()

        // host 头 + 规范 URI（S3 只需 URI-encode 一次，保留 '/'）
        val host: String
        val canonicalUri: String
        when {
            cfg.pathStyle -> {
                host = epHost
                val path = if (key.isEmpty()) "/$bucket/" else "/$bucket/$key"
                canonicalUri = uriEncode(path, encodeSlash = false)
            }
            else -> { // virtual-hosted style: bucket 进域名
                host = "$bucket.$epHost"
                val path = if (key.isEmpty()) "/" else "/$key"
                canonicalUri = uriEncode(path, encodeSlash = false)
            }
        }

        // 时间戳（ISO8601 basic + date）
        val now = Date()
        val amzDate = fmt("yyyyMMdd'T'HHmmss'Z'").format(now)
        val dateStamp = fmt("yyyyMMdd").format(now)

        // 规范查询串：按 key 排序，key/value 均 URI 编码（含 '/'）
        val canonicalQuery = query.entries
            .map { uriEncode(it.key, true) to uriEncode(it.value, true) }
            .sortedBy { it.first }
            .joinToString("&") { "${it.first}=${it.second}" }

        // 签名头：host;x-amz-content-sha256;x-amz-date（已按字母序）
        val canonicalHeaders =
            "host:$host\n" +
                "x-amz-content-sha256:$payloadHash\n" +
                "x-amz-date:$amzDate\n"
        val signedHeaders = "host;x-amz-content-sha256;x-amz-date"

        val canonicalRequest = listOf(
            method,
            canonicalUri,
            canonicalQuery,
            canonicalHeaders,
            signedHeaders,
            payloadHash,
        ).joinToString("\n")

        val scope = "$dateStamp/${cfg.region.trim()}/$SERVICE/aws4_request"
        val stringToSign = listOf(
            ALGORITHM,
            amzDate,
            scope,
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8)),
        ).joinToString("\n")

        val signingKey = signingKey(cfg.secretKey.trim(), dateStamp, cfg.region.trim())
        val signature = hex(hmac(signingKey, stringToSign))
        val authorization =
            "$ALGORITHM Credential=${cfg.accessKey.trim()}/$scope, SignedHeaders=$signedHeaders, Signature=$signature"

        // 组装真实 URL（编码后的 URI，与规范 URI 一致，避免签名不匹配）
        val urlStr = buildString {
            append(scheme).append("://").append(host).append(canonicalUri)
            if (canonicalQuery.isNotEmpty()) append("?").append(canonicalQuery)
        }
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("Host", host)
        conn.setRequestProperty("x-amz-content-sha256", payloadHash)
        conn.setRequestProperty("x-amz-date", amzDate)
        conn.setRequestProperty("Authorization", authorization)
        conn.setRequestProperty("User-Agent", "Arix/1.0")
        return conn
    }

    // ---------- SigV4 加密原语 ----------

    private fun signingKey(secret: String, dateStamp: String, region: String): ByteArray {
        val kDate = hmac(("AWS4$secret").toByteArray(Charsets.UTF_8), dateStamp)
        val kRegion = hmac(kDate, region)
        val kService = hmac(kRegion, SERVICE)
        return hmac(kService, "aws4_request")
    }

    private fun hmac(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun sha256Hex(data: ByteArray): String =
        hex(MessageDigest.getInstance("SHA-256").digest(data))

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append("0123456789abcdef"[v ushr 4])
            sb.append("0123456789abcdef"[v and 0x0F])
        }
        return sb.toString()
    }

    /** AWS 规范 URI 编码：unreserved 直出，其余 %XX 大写；encodeSlash=false 时保留 '/'。 */
    private fun uriEncode(input: String, encodeSlash: Boolean): String {
        val sb = StringBuilder()
        for (b in input.toByteArray(Charsets.UTF_8)) {
            val ch = (b.toInt() and 0xFF).toChar()
            when {
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '-' || ch == '_' || ch == '.' || ch == '~' -> sb.append(ch)
                ch == '/' && !encodeSlash -> sb.append('/')
                else -> {
                    val v = b.toInt() and 0xFF
                    sb.append('%')
                    sb.append("0123456789ABCDEF"[v ushr 4])
                    sb.append("0123456789ABCDEF"[v and 0x0F])
                }
            }
        }
        return sb.toString()
    }

    private fun fmt(pattern: String) =
        SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    // ---------- 通用工具（对齐 WebDavBackup） ----------

    /** 只有完整备份才允许覆盖恢复——防损坏毁本地数据（加密包的完整性由 GCM 标签在解密时兜）。 */
    private fun validZip(b: ByteArray): Boolean = FullBackup.isRestorableBlob(b)

    private fun isOnline(c: Context): Boolean = try {
        val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: Exception) { false }
}
