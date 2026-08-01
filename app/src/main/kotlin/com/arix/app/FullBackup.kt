package com.arix.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.arix.data.db.AppDatabase
import com.arix.data.entity.ApiConfigEntity
import kotlinx.coroutines.flow.first
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * 云端一份历史备份的元信息（GitHub / WebDAV / S3 三个后端通用，UI 拿它渲染「历史版本」列表）。
 * 顶层 data class 且不引用任何类内嵌套类型——嵌在某个 object 里会让另外两个后端引用起来很别扭。
 */
data class CloudBackupVersion(
    /** 后端内部标识：GitHub=仓库内路径 / WebDAV=文件名 / S3=对象 key。恢复时原样回传即可。 */
    val id: String,
    /** 展示名（一般就是文件名）。 */
    val label: String,
    /** 备份时间（毫秒）。优先从文件名里的时间戳解析，解析不出来才用服务端 mtime；0=完全未知。 */
    val timestamp: Long,
    /** 字节数，-1=后端没给。 */
    val size: Long,
    /** 是否是口令加密包（按文件名判定，只用于 UI 展示；真正恢复时以内容魔数为准）。 */
    val encrypted: Boolean,
    /** 是否是「固定文件名」的老备份（升级前留下的那份）。这类永不参与自动清理。 */
    val legacy: Boolean,
)

/**
 * 口令相关的失败：没给口令 / 口令不对 / 密文被篡改。
 * 单独一个异常类型，是为了让 UI 能说「口令不对」，而不是把用户引到「备份损坏」这条错误的岔路上去。
 */
class BackupPassphraseException(message: String) : Exception(message)

/**
 * 备份口令加密（可选）。
 *
 * ## 为什么是「可选」而不是强制
 * 备份链路（自动备份 / 实时同步 / 换机导出）在加密功能出现之前就已经在跑了。强制加密意味着：
 * 老用户升级后自动备份会立刻开始产出他自己也打不开的包（口令还没设），换机时手上那份旧明文包也要
 * 面对「新版本认不认」的问题。所以规则是：**设了口令才加密，没设一个字节都不变**，老路径原样保留。
 *
 * ## 方案
 *  - 派生：PBKDF2WithHmacSHA256（JDK/Android 自带，minSdk 26 起可用），迭代数写进包头，
 *    以后想调高不影响解开老包。不自己发明 KDF，也不引第三方（APK 体积敏感）。
 *  - 加密：AES-256/GCM/NoPadding。选 GCM 是因为它自带认证标签：口令错、传输截断、云端被人改过，
 *    解密时一律直接失败，而不是解出一堆乱码再让 zip 解析器去报「备份损坏」。
 *  - 盐 16B、IV 12B（GCM 标准长度）**每次都用 SecureRandom 重新生成**，跟密文放在同一个包里
 *    （明文包头，本来也不需要保密）。
 *
 * ## 容器格式（v1）
 * `"ARIXENC1"(8) | ver(1) | iterations(4, big-endian) | salt(16) | iv(12) | AES-GCM密文+标签(16)`
 * 明文 zip 以 "PK" 开头，与魔数绝不会撞 → 只看前 8 字节就能分辨加密包 / 老明文包。
 *
 * ## 口令存在哪
 * 存本机 app 私有 SharedPreferences。必须存，否则后台自动备份没法加密（总不能半夜弹窗要口令）。
 * 这里防的是「整包明文托管在第三方云上」，不是「本机被 root 后翻私有目录」——后者的话同一个目录里
 * 的 API key、对话库本来就都在，加密口令不会是最薄弱的那一环。
 */
object BackupCrypto {
    private const val PREFS = "xtom_backup_crypto"
    private const val KEY_PASS = "passphrase"

    private val MAGIC = "ARIXENC1".toByteArray(Charsets.US_ASCII)
    private const val VER: Byte = 1
    private const val SALT_LEN = 16
    private const val IV_LEN = 12        // GCM 标准 96bit IV（用别的长度要走更慢的 GHASH 推导，没必要）
    private const val TAG_BITS = 128
    private const val KEY_BITS = 256
    /** PBKDF2 迭代数。手表 CPU 上 12 万轮约几百毫秒~两秒；只在后台 IO 线程跑，且一轮同步只派生一次。 */
    const val ITERATIONS = 120_000
    /** magic(8) + ver(1) + iter(4) + salt(16) + iv(12) */
    const val HEADER_LEN = 8 + 1 + 4 + SALT_LEN + IV_LEN

    private val rnd = SecureRandom()

    // ---- 口令读写 ----
    fun passphrase(c: Context): String =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PASS, "") ?: ""

    /** 设空串 = 关闭加密（之后的备份恢复成明文；云上已有的加密包不受影响，恢复时仍需口令）。 */
    fun setPassphrase(c: Context, p: String) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_PASS, p).apply()

    fun enabled(c: Context): Boolean = passphrase(c).isNotEmpty()

    /** 口令取值：null=按设置走（设了就加密），""=强制明文，其它=用这个口令。 */
    fun resolve(c: Context, override: String?): String = override ?: passphrase(c)

    // ---- 识别 ----
    fun isEncrypted(b: ByteArray): Boolean {
        if (b.size < HEADER_LEN) return false
        for (i in MAGIC.indices) if (b[i] != MAGIC[i]) return false
        return true
    }

    fun isEncrypted(f: File): Boolean = try {
        f.inputStream().use { ins ->
            val head = ByteArray(MAGIC.size)
            var n = 0
            while (n < head.size) { val r = ins.read(head, n, head.size - n); if (r <= 0) break; n += r }
            n == head.size && head.contentEquals(MAGIC)
        }
    } catch (_: Exception) { false }

    // ---- 原语 ----
    fun newSalt(): ByteArray = ByteArray(SALT_LEN).also { rnd.nextBytes(it) }
    private fun newIv(): ByteArray = ByteArray(IV_LEN).also { rnd.nextBytes(it) }

    fun deriveKey(pass: String, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(pass.toCharArray(), salt, iterations, KEY_BITS)
        val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    private fun header(salt: ByteArray, iv: ByteArray, iterations: Int): ByteArray {
        val h = ByteArray(HEADER_LEN)
        System.arraycopy(MAGIC, 0, h, 0, MAGIC.size)
        h[8] = VER
        h[9] = (iterations ushr 24).toByte(); h[10] = (iterations ushr 16).toByte()
        h[11] = (iterations ushr 8).toByte(); h[12] = iterations.toByte()
        System.arraycopy(salt, 0, h, 13, SALT_LEN)
        System.arraycopy(iv, 0, h, 13 + SALT_LEN, IV_LEN)
        return h
    }

    /**
     * 把 out 包成「写进去就自动加密」的流：先往 out 写明文包头，再返回 CipherOutputStream。
     * ⚠ 调用方必须 close 返回的流（close 会触发 doFinal，把 GCM 认证标签写到末尾）；漏了就等于没有完整性校验。
     * 用流式而不是先整包进内存再加密，是因为手表堆小，整包 zip 本来就已经在内存里过一遍了。
     */
    fun wrapEncrypt(out: OutputStream, pass: String): OutputStream {
        val salt = newSalt(); val iv = newIv()
        val key = deriveKey(pass, salt, ITERATIONS)
        out.write(header(salt, iv, ITERATIONS))
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        return CipherOutputStream(out, c)
    }

    /** 小块数据（分块同步用）：随机盐 + 随机 IV，自成一体的加密包。 */
    fun encrypt(plain: ByteArray, pass: String): ByteArray {
        val salt = newSalt()
        return encryptWith(plain, deriveKey(pass, salt, ITERATIONS), salt, ITERATIONS)
    }

    /**
     * 用【已派生好的】密钥加密——同一轮同步里多个分块共用一次 PBKDF2 的结果，省掉 N-1 次十几万轮的派生。
     * ⚠ salt 可以在一轮里复用（它只影响密钥派生），**IV 绝对不能**：同一 key 下 IV 重复，GCM 会直接
     * 泄露两段明文的异或，还可能让认证密钥被恢复。所以 IV 在这里每次重新随机。
     */
    fun encryptWith(plain: ByteArray, key: SecretKeySpec, salt: ByteArray, iterations: Int): ByteArray {
        val iv = newIv()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        val ct = c.doFinal(plain)
        val h = header(salt, iv, iterations)
        return h + ct
    }

    /** 明文原样返回，加密包则解密（口令错抛 [BackupPassphraseException]）。给「下载回来的字节」用。 */
    fun openBlob(blob: ByteArray, pass: String?): ByteArray =
        if (isEncrypted(blob)) decrypt(blob, pass) else blob

    /**
     * 解密整块字节。GCM 的认证标签在 doFinal 时校验：口令不对 / 内容被改过 / 传输截断，都会在这里失败，
     * 而不会先吐出一堆乱码。**注意**：这里不用 CipherInputStream/CipherOutputStream，
     * 因为 JDK/Android 的实现会把 AEADBadTagException 吞掉（close 时静默丢弃），等于完整性校验白做。
     */
    fun decrypt(blob: ByteArray, pass: String?): ByteArray {
        if (!isEncrypted(blob)) return blob
        if (pass.isNullOrEmpty()) throw BackupPassphraseException(
            "这是一份加密备份。请先在备份设置里填写备份口令，再恢复。"
        )
        if (blob.size <= HEADER_LEN + 16) throw BackupPassphraseException("加密备份不完整（内容太短），已中止。")
        val iterations = readIterations(blob)
        val salt = blob.copyOfRange(13, 13 + SALT_LEN)
        val iv = blob.copyOfRange(13 + SALT_LEN, HEADER_LEN)
        val key = deriveKey(pass, salt, iterations)
        return try {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            c.doFinal(blob, HEADER_LEN, blob.size - HEADER_LEN)
        } catch (e: java.security.GeneralSecurityException) {
            throw BackupPassphraseException(WRONG_PASS)
        }
    }

    /**
     * 流式解密到 dst（大包用，避免整份明文再进一次内存）。
     * 明文先落临时文件，**标签校验通过（doFinal 成功）之后**调用方才允许拿去覆盖数据 —— 顺序很重要：
     * 校验必须发生在删本地库之前，否则口令输错的代价是「本地被清空 + 备份也没恢复上」。
     */
    fun decryptFile(src: File, dst: File, pass: String?) {
        if (pass.isNullOrEmpty()) throw BackupPassphraseException(
            "这是一份加密备份。请先在备份设置里填写备份口令，再恢复。"
        )
        val head = ByteArray(HEADER_LEN)
        src.inputStream().buffered().use { ins ->
            var n = 0
            while (n < HEADER_LEN) { val r = ins.read(head, n, HEADER_LEN - n); if (r <= 0) break; n += r }
            if (n < HEADER_LEN || !isEncrypted(head)) throw BackupPassphraseException("加密备份的包头不完整，已中止。")
            val iterations = readIterations(head)
            val salt = head.copyOfRange(13, 13 + SALT_LEN)
            val iv = head.copyOfRange(13 + SALT_LEN, HEADER_LEN)
            val key = deriveKey(pass, salt, iterations)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            try {
                dst.outputStream().buffered().use { outs ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val r = ins.read(buf)
                        if (r <= 0) break
                        c.update(buf, 0, r)?.let { if (it.isNotEmpty()) outs.write(it) }
                    }
                    c.doFinal()?.let { if (it.isNotEmpty()) outs.write(it) }   // 标签在这里校验
                }
            } catch (e: java.security.GeneralSecurityException) {
                runCatching { dst.delete() }   // 半截明文一律扔掉，别给恢复流程留下可用假象
                throw BackupPassphraseException(WRONG_PASS)
            }
        }
    }

    private fun readIterations(h: ByteArray): Int {
        val v = ((h[9].toInt() and 0xFF) shl 24) or ((h[10].toInt() and 0xFF) shl 16) or
            ((h[11].toInt() and 0xFF) shl 8) or (h[12].toInt() and 0xFF)
        // 包头是攻击者可改的：迭代数被改成 20 亿，解密就变成了一次拒绝服务。夹在合理范围内。
        if (v < 1000 || v > 2_000_000) throw BackupPassphraseException("加密备份的包头异常（迭代参数不合法），已中止。")
        return v
    }

    private const val WRONG_PASS =
        "口令不对（或这份加密备份已损坏 / 被改动过）。已中止，未改动本地数据。请确认备份口令后重试。"
}

/**
 * 云端历史版本的命名与保留策略（三个后端共用一套，UI 也只用配一处）。
 *
 * 为什么要有：以前云端只有一个固定文件名，每次备份直接覆盖。一次坏数据被自动同步上去，
 * 上一份好的就永远没了 —— 备份反而成了单点故障。改成带时间戳的多版本 + 保留最近 N 份。
 */
object BackupPolicy {
    private const val PREFS = "xtom_backup_policy"
    const val DEFAULT_KEEP = 5

    /** 固定文件名的老备份（升级前那份）。仍然要能被列出来、能恢复，但**永不**参与自动清理。 */
    const val FIXED_NAME = "arix-backup.zip"
    const val LEGACY_FIXED_NAME = "onyxai-backup.zip"

    private const val STEM = "arix-backup"
    /** 时间戳用 UTC：手表跨时区/改系统时间时，本地时间命名会出现"新包排在老包前面"。 */
    private fun fmt() = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    private val NAME_RE = Regex("^" + STEM + "-(\\d{8}-\\d{6})(\\.enc)?\\.zip$")

    fun keepVersions(c: Context): Int =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt("keep", DEFAULT_KEEP).coerceIn(1, 50)

    fun setKeepVersions(c: Context, v: Int) =
        c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt("keep", v.coerceIn(1, 50)).apply()

    /** 新备份的文件名。加密包多带一个 .enc 段，好让「列版本」不下载内容也能标出是否加密。 */
    fun newName(encrypted: Boolean, at: Long = System.currentTimeMillis()): String =
        "$STEM-${fmt().format(Date(at))}${if (encrypted) ".enc" else ""}.zip"

    /**
     * 认领一个云端文件名：是我们的备份就返回元信息，不是（用户自己放的别的文件）就返回 null。
     * @param serverTs 后端给的 mtime（毫秒），没有就传 0。仅在文件名里没有时间戳时用。
     */
    fun describe(name: String, size: Long, serverTs: Long): CloudBackupVersion? {
        val m = NAME_RE.find(name)
        if (m != null) {
            val ts = parseTs(m.groupValues[1])
            return CloudBackupVersion(name, name, if (ts > 0) ts else serverTs, size, m.groupValues[2] == ".enc", false)
        }
        if (name == FIXED_NAME || name == LEGACY_FIXED_NAME)
            return CloudBackupVersion(name, name, serverTs, size, false, true)
        return null
    }

    private fun parseTs(s: String): Long = try { fmt().parse(s)?.time ?: 0L } catch (_: Exception) { 0L }

    /** 新→旧。时间戳一样（同一秒内两次备份）时按名字兜底，保证顺序稳定。 */
    val NEWEST_FIRST: Comparator<CloudBackupVersion> =
        compareByDescending<CloudBackupVersion> { it.timestamp }.thenByDescending { it.id }

    /**
     * 算出该删哪些：只在【带时间戳的】版本里留最近 keep 份。
     * 固定文件名的老备份被排除在外——那可能是用户手上唯一一份升级前的数据，不该被我们悄悄删掉。
     */
    fun toPrune(all: List<CloudBackupVersion>, keep: Int): List<CloudBackupVersion> =
        all.filter { !it.legacy }.sortedWith(NEWEST_FIRST).drop(keep.coerceAtLeast(1))
}

/**
 * 全量数据备份 / 恢复：把整个 Room 数据库（arix.db + -wal + -shm）与 App 私有 filesDir（附件 / 头像 /
 * 唤醒原型 / 技能包等）打成**一个 .zip**。恢复 = 关库 → 清旧库 → 解包覆盖 → 重启进程重新打开。
 * 用途：换机 / 彻底卸载重装前后不丢数据。
 *
 * ⚠ 这是「同 App 精确快照」，恢复会**整体覆盖**现有数据（非合并）。跨版本恢复由 Room 迁移兜底。
 */
object FullBackup {

    private const val DB_PREFIX = "db/"
    private const val FILES_PREFIX = "files/"
    private const val PREFS_PREFIX = "prefs/"   // SharedPreferences：设置/开关都在这，必须一起备份
    private const val MARKER_ENTRY = "xtom_backup_marker"   // 来源标记（见 exportTo）

    // ── 旧包（更名前的 OnyxAI / com.onyxai.app）导出的备份 ──────────────────────
    // 2026-07-28 更名后包名与一堆内部名字全变了。**不做映射的话恢复会"看起来成功、实际全丢"**：
    //  · 库文件叫 onyxai.db，原样解出来没人打开（新库名是 arix.db）→ 对话/记忆/角色卡全空；
    //  · prefs 文件叫 onyx_*.xml / onyxai_*.xml，新代码读的是 xtom_* / arix_* → 所有设置回默认；
    //  · 来源标记不认 → 被当成"外来包"，模型端点密钥与工具权限策略直接跳过（见下面的安全 #1）。
    // 映射规则与当初改名逐字一致（onyxai→arix 先、onyx→xtom 后），所以不用逐个文件列名单。
    private const val LEGACY_MARKER = "onyx_backup_marker"
    private const val LEGACY_DB_NAME = "onyxai.db"
    private const val LEGACY_PKG = "com.onyxai.app"
    private const val CURRENT_PKG = "com.arix.app"
    /** 市场包/技能包的清单文件名（在 filesDir 里），更名时一并改了；不映射的话装过的包全认不出来。 */
    private const val LEGACY_PKG_MANIFEST = "onyx.json"
    private const val CURRENT_PKG_MANIFEST = "xtom.json"
    // 恢复外来（无标记）备份时**不覆盖**的敏感 prefs 文件——它们是权限提权/数据外传的直接目标。
    // xtom_tool_permissions=工具放行策略（翻成 ALLOW=给 AI 静默 shell）。API 端点/密钥在 Room 库里，
    // 库是整文件覆盖没法只挑一张表，那条只能靠恢复前的用户确认兜（见 importFrom 的诚实返回）。
    // xtom_backup_crypto.xml=备份口令。外来包别想改它：否则一份伪造备份就能把你之后所有的云备份
    // 换成攻击者知道的口令来加密（更糟的是换成你不知道的口令，等于以后自己也打不开自己的备份）。
    private val SENSITIVE_PREFS = listOf("xtom_tool_permissions.xml", "xtom_backup_crypto.xml")

    /**
     * **任何**备份都不许覆盖的 prefs——连自己导出的可信包也不行。
     *
     * 与 [SENSITIVE_PREFS] 的区别：那份挡的是「外来包别想提权」，可信包照样覆盖；
     * 这份挡的是「这是本机的凭据，不是可回滚的用户内容」。
     *
     * 备份口令必须进这一档，否则有个静默且危险的场景：
     * 用户恢复一份**升级前的明文老备份**（那是自己的包，trusted=true）→ 包里那份空的
     * `xtom_backup_crypto` 覆盖掉他现在设的口令 → **加密就此静默关闭**，之后每一次自动备份
     * 都明文传到第三方 WebDAV/S3，而他不会收到任何提示。
     * 口令的语义是「我这台机器用什么开锁」，跟"恢复到某个时间点的数据"是两回事。
     */
    private val NEVER_OVERWRITE_PREFS = listOf("xtom_backup_crypto.xml")
    // 不入包：崩溃日志、既有单项导出目录（无意义且占体积）
    private val FILES_SKIP = listOf("crash", "exports")

    /**
     * 恢复结果：trusted=有本 App 来源标记；skippedSensitive=因外来而跳过了权限策略等敏感项；
     * apiConfigPreserved=外来包恢复时保住了用户原有的 LLM 端点/密钥（api_configs 表未被备份覆盖）。
     */
    data class ImportResult(val trusted: Boolean, val skippedSensitive: Boolean, val apiConfigPreserved: Boolean = false)

    private fun prefsDir(app: Context): File = File(app.filesDir.parentFile, "shared_prefs")

    /** 旧备份里的**库文件名** → 新的（`onyxai.db` / `-wal` / `-shm` 三件套按前缀换）。 */
    private fun legacyDbName(name: String): String =
        if (name.startsWith(LEGACY_DB_NAME)) AppDatabase.DB_NAME + name.removePrefix(LEGACY_DB_NAME) else name

    /** 旧备份里的 **prefs 文件名** → 新的。与当初改名同一条规则，顺序不能反。 */
    private fun legacyPrefsName(name: String): String =
        name.replace("onyxai", "arix").replace("onyx", "xtom")

    /** 旧备份里 filesDir 下要改名的：只有市场包/技能包的清单。其余用户文件一律不动（可能就叫这个名字）。 */
    private fun legacyFileRel(rel: String): String =
        if (rel == LEGACY_PKG_MANIFEST || rel.endsWith("/$LEGACY_PKG_MANIFEST"))
            rel.dropLast(LEGACY_PKG_MANIFEST.length) + CURRENT_PKG_MANIFEST else rel

    /**
     * 恢复完旧包备份后，修数据**内容**里跟着旧名字走的三类东西。改名只换了容器的名字，
     * 里面存的字符串还是旧的，不修的话表现是「东西都在、点开全是坏的」：
     *  1. **绝对路径带旧包名**：聊天附件存的是 `file:///data/user/0/com.onyxai.app/files/...`，
     *     文件本身已经随备份还原到新包目录下了，只是路径对不上 → 图片/附件全裂。
     *  2. **JS 插件/搜索源里的 API 名**：用户写的 JS 调的是 `onyx.callTool(...)`，新运行时注入的对象叫 `xtom`。
     *  3. **胶囊主题 id**：旧值 `onyx`，新主题表里没有这个 id，会静默回落到第一个主题。
     */
    private fun patchLegacyContents(app: Context, filesDir: File?, prefsDir: File) {
        fun patchText(f: File, fn: (String) -> String) = runCatching {
            val s = f.readText(); val t = fn(s); if (t != s) f.writeText(t)
        }
        // prefs：路径 + JS API 名（XML 是纯文本，直接替换；键名本身不含这些串，不会误伤）
        prefsDir.listFiles()?.forEach { f ->
            if (!f.isFile || !f.name.endsWith(".xml")) return@forEach
            patchText(f) { s ->
                var t = s.replace("/$LEGACY_PKG/", "/$CURRENT_PKG/")
                    .replace("onyx.callTool(", "xtom.callTool(")
                    .replace("onyx.registerTool(", "xtom.registerTool(")
                // 胶囊主题 id 是整值匹配，只在那份 prefs 里动，别拿去改别处的自由文本
                if (f.name == "xtom_capsule.xml") t = t.replace(">onyx<", ">xtom<")
                t
            }
        }
        // filesDir：JS 插件源码 + 包清单里的路径
        filesDir?.walkTopDown()?.filter { it.isFile && (it.extension == "js" || it.name == CURRENT_PKG_MANIFEST) }
            ?.forEach { f ->
                patchText(f) { s ->
                    s.replace("onyx.callTool(", "xtom.callTool(")
                        .replace("onyx.registerTool(", "xtom.registerTool(")
                        .replace("/$LEGACY_PKG/", "/$CURRENT_PKG/")
                }
            }
        // 库里的文本列：附件/头像存的是绝对路径。用带斜杠的完整形式匹配，避免把正文里提到包名的普通文字也改了。
        runCatching {
            val db = AppDatabase.getInstance(app).openHelper.writableDatabase
            val from = "/$LEGACY_PKG/"; val to = "/$CURRENT_PKG/"
            listOf(
                "conversations" to listOf("messagesJson", "branchesJson"),
                "character_cards" to listOf("avatarPath"),
                "memories" to listOf("content"),
            ).forEach { (table, cols) ->
                cols.forEach { c ->
                    runCatching {
                        db.execSQL("UPDATE $table SET $c = REPLACE($c, ?, ?) WHERE $c LIKE ?", arrayOf(from, to, "%$from%"))
                    }
                }
            }
        }
        AppDatabase.closeInstance()   // 让重启后从磁盘干净重开
    }

    /**
     * 导出到 out（调用方负责开/关流）。返回打包的文件数。
     *
     * @param passphrase null=按设置走（用户设了备份口令就加密，没设保持原样明文）；
     *                   ""=强制明文；其它=用这个口令加密。
     * 加密是可选的：没设口令时走的是和以前一模一样的代码路径，老的备份/恢复流程一个字节都不变。
     */
    fun exportTo(context: Context, out: OutputStream, passphrase: String? = null): Int {
        val pass = BackupCrypto.resolve(context.applicationContext, passphrase)
        if (pass.isEmpty()) return exportZipTo(context, out)
        // 加密路径：包头（明文）→ 其后全部走 GCM。exportZipTo 里的 ZipOutputStream.use 关流时会连带
        // 关掉这个 CipherOutputStream，触发 doFinal 把认证标签写到末尾——少了它整包就没有完整性校验。
        val cipherOut = BackupCrypto.wrapEncrypt(out, pass)
        return exportZipTo(context, cipherOut)
    }

    /** 真正打 zip 的那段（明文）。加密与否只影响外面套没套一层 cipher 流。 */
    private fun exportZipTo(context: Context, out: OutputStream): Int {
        val app = context.applicationContext
        // 先 checkpoint WAL，让 .db 文件自洽
        runCatching {
            AppDatabase.getInstance(app).openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }
        val dbDir = app.getDatabasePath(AppDatabase.DB_NAME).parentFile
        val filesDir = app.filesDir
        var count = 0
        ZipOutputStream(out.buffered()).use { zip ->
            // 来源标记：证明这份备份是 Arix 自己导出的。恢复会整包覆盖 shared_prefs（含工具权限策略）
            // 和数据库（含 LLM base_url/key）——一个手工拼的「配置包」就能把危险工具翻成「始终允许」、
            // 把对话偷偷转发到攻击者服务器。带标记的包才自动恢复权限/密钥这些敏感项；没标记的（外来/篡改）
            // 只恢复非敏感数据。老练的攻击者能伪造标记，所以这是纵深防御的一层，真正的闸是恢复前的用户确认。
            zip.putNextEntry(ZipEntry(MARKER_ENTRY))
            zip.write(("arix-backup\n" + System.currentTimeMillis()).toByteArray()); zip.closeEntry(); count++
            // 数据库三件套（.db / -wal / -shm）
            dbDir?.listFiles { f -> f.name.startsWith(AppDatabase.DB_NAME) }?.forEach { f ->
                if (f.isFile) { zipFile(zip, f, DB_PREFIX + f.name); count++ }
            }
            // filesDir 递归
            filesDir?.walkTopDown()?.filter { it.isFile }?.forEach { f ->
                val rel = f.relativeTo(filesDir).path.replace('\\', '/')
                if (FILES_SKIP.any { rel == it || rel.startsWith("$it/") }) return@forEach
                zipFile(zip, f, FILES_PREFIX + rel); count++
            }
            // shared_prefs 全目录（所有设置/开关；与 databases/files 同级）
            prefsDir(app).listFiles()?.forEach { f ->
                if (f.isFile) { zipFile(zip, f, PREFS_PREFIX + f.name); count++ }
            }
        }
        return count
    }

    /**
     * 从 input 恢复（整体覆盖）。成功后应调用 [triggerRestart]。调用方负责开/关流。
     * 返回 [ImportResult]：外来（无标记）包会跳过权限策略等敏感 prefs，并在结果里标出来让 UI 提醒用户。
     *
     * @param passphrase null=用本机保存的备份口令；其它=用这个口令。**只有加密包才会用到**，
     *                   明文包（含所有升级前的老备份）完全不受影响。
     * @throws BackupPassphraseException 是加密包但没口令 / 口令不对 / 包被改动过。
     *         抛出发生在**动本地数据之前**，本地数据保持原样。
     */
    fun importFrom(context: Context, input: InputStream, passphrase: String? = null): ImportResult {
        val app = context.applicationContext
        // ZipInputStream 只能单遍读，而「有没有来源标记」要在开始覆盖**之前**知道 → 先落临时文件读两遍。
        // 不能信任条目顺序去只读第一条：攻击者可以把标记挪走。
        val raw = File.createTempFile("xtom_restore", ".zip", app.cacheDir)
        var tmp = raw
        var decrypted: File? = null
        try {
            input.buffered().use { ins -> raw.outputStream().use { ins.copyTo(it) } }
            // 加密包：先完整解密 + 校验 GCM 标签，通过了才继续。这一步刻意放在最前面——
            // 后面 AppDatabase.closeInstance()/删库文件都是不可逆的，口令错必须在那之前就失败掉。
            if (BackupCrypto.isEncrypted(raw)) {
                val dec = File.createTempFile("xtom_restore_p", ".zip", app.cacheDir)
                decrypted = dec
                BackupCrypto.decryptFile(raw, dec, BackupCrypto.resolve(app, passphrase))
                tmp = dec
            }
            // 旧包（OnyxAI）导出的备份：标记名不同，但同样是"我们自己导出的"，一样可信——
            // 不认它就会把用户自己的整包数据当成外来配置包，跳过密钥与权限策略（等于白恢复）。
            val legacy = zipHasEntry(tmp, LEGACY_MARKER)
            val trusted = zipHasEntry(tmp, MARKER_ENTRY) || legacy

            // 【安全 #1】外来（无标记）备份恢复前，先抓住用户当前自己的 LLM 端点配置。
            // 数据库是整文件覆盖，没法只挑 api_configs 一张表；一份伪装的「配置包」恢复后会把 baseUrl/apiKey/systemPrompt
            // 换成攻击者的服务器，之后所有对话明文外泄。对策(方案 a)：外来包恢复后把 api_configs 表恢复成恢复前用户自己的内容，
            // 其余数据（对话/角色卡/记忆）仍从备份来。当前没有任何配置（如全新安装）则无可保护，放行（此时已有外来警告兜底）。
            val preservedConfigs: List<ApiConfigEntity> = if (!trusted) {
                try {
                    kotlinx.coroutines.runBlocking { AppDatabase.getInstance(app).apiConfigDao().getAll().first() }
                } catch (c: kotlinx.coroutines.CancellationException) { throw c
                } catch (_: Exception) { emptyList() }
            } else emptyList()

            AppDatabase.closeInstance() // 释放 DB 文件占用，否则覆盖失败
            val dbDir = app.getDatabasePath(AppDatabase.DB_NAME).parentFile!!.apply { mkdirs() }
            val filesDir = app.filesDir
            val prefsDir = prefsDir(app).apply { mkdirs() }
            dbDir.listFiles { f -> f.name.startsWith(AppDatabase.DB_NAME) }?.forEach { it.delete() }
            var skippedSensitive = false
            ZipInputStream(tmp.inputStream().buffered()).use { zip ->
                var e: ZipEntry? = zip.nextEntry
                while (e != null) {
                    if (!e.isDirectory && e.name != MARKER_ENTRY && e.name != LEGACY_MARKER) {
                        // 旧包备份：文件名要按更名规则映射后再落盘（见 legacyDbName/legacyPrefsName）。
                        // 新包备份一个字都不动——映射只在 legacy 时启用，免得误伤用户自己叫这名字的文件。
                        val prefsName = e.name.removePrefix(PREFS_PREFIX).let { if (legacy) legacyPrefsName(it) else it }
                        val target = when {
                            e.name.startsWith(DB_PREFIX) -> File(dbDir, e.name.removePrefix(DB_PREFIX)
                                .let { if (legacy) legacyDbName(it) else it })
                            e.name.startsWith(FILES_PREFIX) -> File(filesDir, e.name.removePrefix(FILES_PREFIX)
                                .let { if (legacy) legacyFileRel(it) else it })
                            e.name.startsWith(PREFS_PREFIX) -> File(prefsDir, prefsName)
                            else -> null
                        }
                        // 外来包：跳过敏感 prefs，别让「配置包」翻掉工具权限
                        // ⚠ 判据用**映射后**的名字：旧包里叫 onyx_tool_permissions.xml，不映射就漏过这道闸
                        val sensitive = e.name.startsWith(PREFS_PREFIX) && SENSITIVE_PREFS.contains(prefsName)
                        // 本机凭据：可信包也不许覆盖（见 NEVER_OVERWRITE_PREFS 的说明）
                        val neverOverwrite = e.name.startsWith(PREFS_PREFIX) && NEVER_OVERWRITE_PREFS.contains(prefsName)
                        if (neverOverwrite) {
                            // 不计进 skippedSensitive：那个标记是给用户看的「外来包被拦了」提示，
                            // 而这里是每次恢复都会发生的正常行为，报出来只会让人以为备份有问题。
                        } else if (!trusted && sensitive) {
                            skippedSensitive = true
                        } else if (target != null && isSafeChild(target, dbDir, filesDir, prefsDir)) {
                            target.parentFile?.mkdirs()
                            target.outputStream().use { zip.copyTo(it) }
                        }
                    }
                    zip.closeEntry(); e = zip.nextEntry
                }
            }
            // 旧包备份：文件名映射只解决了"容器"，里面存的旧包名绝对路径 / JS API 名 / 主题 id 还得再修一遍
            if (legacy) patchLegacyContents(app, filesDir, prefsDir)
            // 【安全 #1】把恢复前抓到的用户端点写回，覆盖备份带进来的 api_configs（保端点/密钥/systemPrompt 不被外来包改掉）。
            // 重开被恢复的库 → 删掉备份带来的配置 → 换回用户自己的（含原 id/激活态）→ 关掉让重启后从磁盘干净重开。
            var apiConfigPreserved = false
            if (!trusted && preservedConfigs.isNotEmpty()) {
                try {
                    kotlinx.coroutines.runBlocking {
                        val dao = AppDatabase.getInstance(app).apiConfigDao()
                        dao.getAll().first().forEach { dao.delete(it) }
                        preservedConfigs.forEach { dao.insert(it) }
                    }
                    apiConfigPreserved = true
                } catch (c: kotlinx.coroutines.CancellationException) { throw c
                } catch (_: Exception) { apiConfigPreserved = false }
                AppDatabase.closeInstance()
            }
            return ImportResult(trusted, skippedSensitive, apiConfigPreserved)
        } finally {
            runCatching { raw.delete() }
            runCatching { decrypted?.delete() }   // 解出来的明文临时包用完立刻删，别在 cache 里留一份裸数据
        }
    }

    /**
     * 下载回来的字节「像不像一份能拿去覆盖恢复的备份」——三个云后端共用，删本地数据前的最后一道闸。
     *  · 明文包：必须能走完全部 zip 条目（截断/被劫持会在中途抛异常）；
     *  · 加密包：这里只验包头，真正的完整性由 GCM 认证标签在解密时兜（比 zip 走一遍更强）。
     */
    fun isRestorableBlob(b: ByteArray): Boolean {
        if (BackupCrypto.isEncrypted(b)) return b.size > BackupCrypto.HEADER_LEN + 16
        if (b.size < 100 || b[0] != 0x50.toByte() || b[1] != 0x4B.toByte()) return false   // "PK" 头
        return try {
            ZipInputStream(b.inputStream().buffered()).use { z ->
                var n = 0; var e = z.nextEntry
                while (e != null) { n++; e = z.nextEntry }
                n > 0
            }
        } catch (_: Exception) { false }
    }

    /** 不落地地扫一遍 zip 看某条目在不在。 */
    private fun zipHasEntry(file: File, name: String): Boolean = try {
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var e = zip.nextEntry
            while (e != null) { if (e.name == name) return true; zip.closeEntry(); e = zip.nextEntry }
        }
        false
    } catch (_: Exception) { false }

    /** zip 路径穿越防护：解出的目标必须落在允许的根目录内。 */
    private fun isSafeChild(target: File, vararg roots: File): Boolean {
        val canon = target.canonicalPath
        return roots.any { canon == it.canonicalPath || canon.startsWith(it.canonicalPath + File.separator) }
    }

    private fun zipFile(zip: ZipOutputStream, f: File, entryName: String) {
        zip.putNextEntry(ZipEntry(entryName))
        f.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    /** 恢复后重启进程，让 Room 从恢复后的 DB 重新打开。 */
    fun triggerRestart(context: Context) {
        val app = context.applicationContext
        app.packageManager.getLaunchIntentForPackage(app.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?.let { launch ->
                val pi = PendingIntent.getActivity(
                    app, 0, launch,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT
                )
                runCatching {
                    app.getSystemService(AlarmManager::class.java)
                        ?.set(AlarmManager.RTC, System.currentTimeMillis() + 400, pi)
                }
            }
        android.os.Process.killProcess(android.os.Process.myPid())
    }
}
