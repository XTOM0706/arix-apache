package com.arix.app

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.InputStream

/**
 * 用 Shizuku（ADB 级特权，无需 root）增强麦克风占有：
 * `appops set <pkg> RECORD_AUDIO allow` 把录音 appop 强制为「始终允许」，绕过后台「仅使用时」
 * 限制 → 退到后台也能持续持麦不被系统掐（解决「麦克风没法一直拿着」）。
 *
 * 只做「后台持续持麦」这一件事，用常规归属的 MIC 源。命令通过 Shizuku 以 shell UID 执行。
 * ⚠ 不再尝试授 CAPTURE_AUDIO_HOTWORD/切 HOTWORD 源：那是低归属特权源，官改包上小米「敏感权限检测」
 * 会判为可疑并回收麦克风（表现为「不显示占用 → 检测到后掐权限」），得不偿失。
 */
object ShizukuMic {
    private const val REQ = 8710
    const val ADB_HINT = "adb shell appops set com.arix.app RECORD_AUDIO allow"

    /** Shizuku 服务是否在运行（已启动且 binder 存活）。 */
    fun binderAlive(): Boolean = try { Shizuku.pingBinder() } catch (_: Throwable) { false }

    fun hasPermission(): Boolean = try {
        binderAlive() && !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    } catch (_: Throwable) { false }

    /** 请求 Shizuku 授权；结果异步回调（主线程需自行切换）。 */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        try {
            if (hasPermission()) { onResult(true); return }
            if (!binderAlive()) { onResult(false); return }
            val listener = object : Shizuku.OnRequestPermissionResultListener {
                override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                    if (requestCode == REQ) {
                        Shizuku.removeRequestPermissionResultListener(this)
                        onResult(grantResult == PackageManager.PERMISSION_GRANTED)
                    }
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            Shizuku.requestPermission(REQ)
        } catch (_: Throwable) { onResult(false) }
    }

    /** 对外通用特权执行器：以 shell(ADB/Shizuku) UID 跑一条命令，返回 (退出码, 输出+错误)；未授权返回 null。
     *  供 ShellTool 等在 DEBUGGER 级免 root 跑 pm/am/settings/input 等系统命令。 */
    fun shell(cmd: String): Pair<Int, String>? = if (hasPermission()) exec(cmd) else null

    /** 以 shell 执行一条命令，返回 (退出码, 输出+错误)。用反射调 Shizuku.newProcess，兼容不同版本。 */
    private fun exec(cmd: String): Pair<Int, String> = try {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val proc = m.invoke(null, arrayOf("sh", "-c", cmd), null, null)!!
        val cls = proc.javaClass
        // 两条流各自后台排空，避免「读完 stdout 再读 stderr」在子进程往 stderr 写满管道(~64KB)时死锁
        val outS = cls.getMethod("getInputStream").invoke(proc) as InputStream
        val errS = cls.getMethod("getErrorStream").invoke(proc) as InputStream
        val outSb = StringBuilder(); val errSb = StringBuilder()
        val t1 = Thread { try { outS.bufferedReader().forEachLine { synchronized(outSb) { outSb.appendLine(it) } } } catch (_: Throwable) {} }.apply { isDaemon = true; start() }
        val t2 = Thread { try { errS.bufferedReader().forEachLine { synchronized(errSb) { errSb.appendLine(it) } } } catch (_: Throwable) {} }.apply { isDaemon = true; start() }
        val code = cls.getMethod("waitFor").invoke(proc) as Int
        t1.join(500); t2.join(500)
        code to (synchronized(outSb) { outSb.toString() } + synchronized(errSb) { errSb.toString() }).trim()
    } catch (e: Throwable) {
        -1 to (e.message ?: "exec 失败")
    }

    /** 增强麦克风：把录音 appop 强制为「始终允许」，让后台窗口内也能持续持麦（常规 MIC 源，诚实归属）。 */
    fun elevateMic(pkg: String, onLog: (String) -> Unit): Boolean {
        if (!binderAlive()) { onLog("Shizuku 未运行：先安装并启动 Shizuku"); return false }
        if (!hasPermission()) { onLog("Shizuku 未授权：先点「Shizuku 授权」"); return false }
        val cmds = listOf(
            "appops set $pkg RECORD_AUDIO allow",
            "appops set $pkg PHONE_CALL_MICROPHONE allow",
        )
        var okAny = false
        for (c in cmds) {
            val (code, o) = exec(c)
            onLog("[$code] $c${if (o.isNotBlank()) " → $o" else ""}")
            if (code == 0) okAny = true
        }
        onLog(if (okAny) "✔ 麦克风已增强：后台可持续持麦（重启唤醒生效）" else "增强失败，检查 Shizuku")
        return okAny
    }
}
