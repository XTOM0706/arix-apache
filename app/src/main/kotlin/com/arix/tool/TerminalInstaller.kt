package com.arix.tool

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * 「Arix 终端」App 的安装引导：终端 APK **不内置**在主包里（那会让主 APK 白涨 33MB），
 * 点安装时才从 GitHub Release 下载。国内直连 GitHub 不稳，走 [CloudMarketplace.openGh]
 * 的镜像回退（与云端包同一套）。下到 cacheDir 后交给系统安装器——不静默装，那要系统签名。
 *
 * 两个 APK 必须**同签名**，否则主 App 绑不上终端服务（签名级权限 BIND_TERMINAL）。
 */
object TerminalInstaller {
    private const val FILE = "terminal.apk"

    /**
     * 下载入口要不要可点。
     *
     * 现在恒为 true：地址是**运行时**去解析最新 release 的（见 RemoteAssets.terminalApkUrl），
     * 编译期无从判断"有没有地址"。解析不到时由 [install] 返回一句人话，
     * 而不是让按钮一直灰着、用户完全不知道为什么。
     */
    fun canDownload(ctx: Context): Boolean = true

    /** 安卓 8+ 装未知来源要用户单独授权本 App「安装未知应用」。 */
    fun canRequestInstall(ctx: Context): Boolean = ApkInstaller.canRequestInstall(ctx)

    /** 跳到系统设置里给本 App 开「安装未知应用」。 */
    fun openInstallPermission(ctx: Context) = ApkInstaller.openInstallPermission(ctx)

    /**
     * 下载并唤起系统安装器。[onProgress] 给 0..1，拿不到 Content-Length 时给 -1（不确定）。
     * 返回 null=成功唤起，非 null=给用户看的失败原因。
     */
    suspend fun install(ctx: Context, onProgress: (Float) -> Unit = {}): String? {
        val url = RemoteAssets.terminalApkUrl(ctx)
            ?: return "拿不到终端安装包的下载地址（还没发布版本，或网络没通）。" +
                "也可以在设置里填自建下载基址，或手动侧载终端 APK。"
        // 下载/校验/唤起安装器全在 ApkInstaller 里 —— 那道「签名必须与本 App 一致」的校验
        // 只能有一份，两处各写一份迟早有一处写松（见 ApkInstaller 的类注释）。
        return ApkInstaller.downloadAndInstall(ctx, url, FILE, onProgress)
    }
}
