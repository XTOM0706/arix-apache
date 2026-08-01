package com.arix.tool

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 「下载一个 APK 并交给系统安装器」的通用件 —— 终端安装与应用自更新共用这一份。
 *
 * 抽出来的理由不是省代码行数，是**那道签名校验只能有一份**。两处各写一份的话，
 * 迟早有一处忘了校验、或者校验写松了，而那正是整条链路上唯一挡住"下到被掉包的包"的东西。
 *
 * 信任模型很简单：**下载的 APK 必须与本 App 同一张签署证书**。
 * 下载源可能是镜像、可能是用户自填的自建地址、可能走明文 http——这些都不可信；
 * 可信的只有"只有我们签得出这个签名"。所以校验放在**交给安装器之前**，对不上就丢弃。
 *
 * ⚠️ 这也是为什么 release **必须**用正式密钥签：用 Android SDK 那把公开的 debug 密钥签名时，
 * 任何人都能签出"通过这道校验"的包，这个函数就成了摆设。见 app/build.gradle.kts 顶部那段。
 */
object ApkInstaller {

    /** 能不能直接唤起安装器（Android 8+ 要「安装未知应用」授权）。 */
    fun canRequestInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    /** 送用户去开「安装未知应用」。 */
    fun openInstallPermission(ctx: Context) {
        runCatching {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure {
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }

    /**
     * 下载 → 校验签名 → 唤起安装器。返回 null=成功唤起，非 null=给用户看的失败原因。
     *
     * @param fileName 落在 cacheDir 里的文件名。两个调用方要用**不同**的名字，别互相覆盖。
     * @param onProgress 0f~1f；总长未知时给 -1f（UI 该显示成不确定进度而不是卡在 0%）。
     */
    suspend fun downloadAndInstall(
        ctx: Context,
        url: String,
        fileName: String,
        onProgress: (Float) -> Unit = {},
    ): String? {
        // 先要权限：白下几十兆再卡在授权上很蠢
        if (!canRequestInstall(ctx)) {
            openInstallPermission(ctx)
            return "请先允许 Arix「安装未知应用」，然后再点一次。"
        }
        val out = File(ctx.cacheDir, fileName)
        val tmp = File(ctx.cacheDir, "$fileName.part")
        try {
            withContext(Dispatchers.IO) {
                // GitHub 直链走镜像回退（国内直连不稳）；自建地址原样直连
                val conn = CloudMarketplace.openGh(url, readMs = 60_000)
                    ?: throw IllegalStateException("连不上下载源（直连与镜像都失败），换个网络再试")
                try {
                    val total = conn.contentLengthLong
                    conn.inputStream.use { input ->
                        FileOutputStream(tmp).use { fos ->
                            val buf = ByteArray(64 * 1024)
                            var read = 0L
                            var n = input.read(buf)
                            var lastPct = -1
                            while (n >= 0) {
                                fos.write(buf, 0, n); read += n
                                if (total > 0) {
                                    val pct = (read * 100 / total).toInt()
                                    if (pct != lastPct) { lastPct = pct; onProgress(pct / 100f) }
                                } else onProgress(-1f)
                                n = input.read(buf)
                            }
                        }
                    }
                } finally { runCatching { conn.disconnect() } }
                // 下完整了才顶替旧文件：中途断网不会留下一个装不上的半截包
                if (out.exists()) out.delete()
                if (!tmp.renameTo(out)) throw IllegalStateException("写入缓存失败")
            }
        } catch (c: CancellationException) {
            runCatching { tmp.delete() }
            throw c
        } catch (e: Exception) {
            runCatching { tmp.delete() }
            return "下载失败：${e.message}"
        }

        if (!signedLikeSelf(ctx, out)) {
            runCatching { out.delete() }
            return "下载的安装包签名与本应用不一致，已丢弃（下载源可能被篡改）。请改用官方地址。"
        }
        return launchInstaller(ctx, out)
    }

    /** 下载的 APK 是否与本 App 同签名（同一签署证书）。任一侧取不到证书都判否（fail-closed）。 */
    fun signedLikeSelf(ctx: Context, apk: File): Boolean = runCatching {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val self = certDigests(pm.getPackageInfo(ctx.packageName, flags))
        val downloaded = certDigests(pm.getPackageArchiveInfo(apk.absolutePath, flags))
        self.isNotEmpty() && downloaded.isNotEmpty() && self == downloaded
    }.getOrDefault(false)

    /** 取一个 PackageInfo 的签署证书 SHA-256 指纹集合（兼容 P 前后两套 API）。 */
    private fun certDigests(info: PackageInfo?): Set<String> {
        if (info == null) return emptySet()
        @Suppress("DEPRECATION")
        val sigs: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory }
        } else info.signatures
        if (sigs.isNullOrEmpty()) return emptySet()
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.mapNotNull {
            runCatching { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.getOrNull()
        }.toSet()
    }

    /** 唤起系统安装器（主线程）。返回 null=成功唤起，非 null=失败原因。 */
    private suspend fun launchInstaller(ctx: Context, out: File): String? {
        val uri = try {
            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", out)
        } catch (e: Exception) {
            return "生成安装链接失败：${e.message}"
        }
        return withContext(Dispatchers.Main) {
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }.exceptionOrNull()?.let { "唤起安装器失败：${it.message}" }
        }
    }
}
