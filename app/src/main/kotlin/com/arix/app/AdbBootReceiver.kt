package com.arix.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 开机自启：设备重启后尽早尝试自恢复 Shizuku（无线 ADB 自连回环拉起），让语音唤醒/特权功能不掉线。
 *
 * 监听两个开机广播：
 *  - LOCKED_BOOT_COMPLETED：直连启动（用户还没解锁）就已发出 → 需 receiver 声明 directBootAware=true 才收得到；
 *    越早尝试越好（唤醒服务也想尽快回来）。
 *  - BOOT_COMPLETED：解锁后（或不支持直连启动的设备）发出，兜底再跑一次。
 *
 * 诚实边界：非 root 手表**首次仍需人工配对一次**无线调试；开机自恢复只在「已配对过 + 能开无线调试 + 有 adb 二进制」
 * 时才可能成功（见 AdbKeepAlive 顶部说明）。这里只负责开机时把恢复流程踢起来，成败以 AdbKeepAlive 日志为准。
 */
class AdbBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED -> {
                // ensureAlive 是同步阻塞（adb connect / start.sh），必须挪出主线程；goAsync 争取到额外执行窗口。
                val pending = goAsync()
                val app = context.applicationContext
                // ⚠ 这一行修的是**现存问题**，不只是给开机触发器用的：
                // 本 receiver 是 directBootAware=true，进程可能在**解锁前**就起来——那时 filesDir 读不到，
                // WorkflowTriggers 拿到空的触发器表、挂载签名停在 ""，于是一个监听都没挂；
                // 而 XtomApp.onCreate 那趟不会再跑第二次。解锁后的 BOOT_COMPLETED 是唯一的补挂机会，
                // 在这之前**整个工作流触发系统在重启之后都是哑的**。内部自己丢 IO 协程。
                if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                    runCatching { WorkflowTriggers.onBootCompleted(app) }
                }
                Thread {
                    try {
                        AdbKeepAlive.ensureAlive(app)
                        AdbKeepAlive.scheduleHealthCheck(app)
                    } catch (t: Throwable) {
                        android.util.Log.w("AdbBootReceiver", "开机自恢复异常", t)
                    } finally {
                        try { pending.finish() } catch (_: Throwable) {}
                    }
                }.apply { isDaemon = true; name = "adb-boot-recover" }.start()
            }
        }
    }
}
