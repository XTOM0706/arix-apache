package com.arix.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import com.arix.tool.ShizukuAccess
import java.io.File

/**
 * ADB 保活骨架 —— 无 PC 情况下，让设备自身在**重启后尽量自恢复** Shizuku。
 *
 * 目标：语音唤醒 / 特权功能（appops 持麦、pm/am/settings/input 等）依赖 Shizuku 的 ADB 级 binder；
 * 手表重启后 Shizuku 会掉，非 root 又没法开机自启 → 这里做「无线 ADB 自连回环 → 拉起 Shizuku」的自恢复。
 *
 * ⚠⚠ 诚实边界（不 root 不承诺绝对不掉线，别假装全自动）：
 *  1. **首次配对必须人工一次**：开发者选项 → 无线调试 → 配对，adb pair 输 6 位配对码。这一步没法绕。
 *     配对后设备信任本机，之后才谈得上「自动重连」。
 *  2. 重启后要能自动重开「无线调试」总开关，靠二者其一：
 *     ① Android 13+ 的「可信 Wi-Fi 自动重新开启无线调试」（系统特性，用户在无线调试页勾一次）；
 *     ② 本 App 持有 WRITE_SECURE_SETTINGS 时自己 putInt("adb_wifi_enabled",1)（见 [tryEnableWirelessDebug]）。
 *     WRITE_SECURE_SETTINGS 是签名级、普通侧载拿不到 → 需用户用 adb 引导 `pm grant com.arix.app
 *     android.permission.WRITE_SECURE_SETTINGS` 一次；引导过就长期持有。
 *  3. 设备本地要有一个可执行的 adb 二进制（Arix 内嵌 Termux/终端可打包，或系统自带）。
 *     真正 exec adb 封装在 [runAdb]，**当前留成可插拔桩**，真跑靠真机接。
 *
 * 本文件是「软件骨架」：能编过、流程清晰、每步 try/catch + 日志；标了 device-verify / TODO 的地方
 * 需要真机验证或补实现（尤其 [runAdb] 的实际执行、[discoverPort] 的 mDNS 端口发现）。
 */
object AdbKeepAlive {

    private const val TAG = "AdbKeepAlive"

    /** Shizuku 官方「开机自启」脚本约定路径（Shizuku 管理器会把 start.sh 落在这里，供 adb shell 拉起服务）。 */
    const val SHIZUKU_START_SH = "/storage/emulated/0/Android/data/moe.shizuku.privileged.api/start.sh"

    /** 健康体检的**起始**周期：对标 Shizuku Keeper，60s 看一次 binder，掉了重拉。 */
    private const val HEALTH_INTERVAL_MS = 60_000L
    /** 一直正常时周期翻倍的上限。手表上「每分钟醒一次、永远」是纯亏，而它绝大多数时候都是活的。 */
    private const val HEALTH_MAX_INTERVAL_MS = 15 * 60_000L
    /** 连续这么多次没能恢复就停掉体检：失败原因基本是结构性的（没配对过/没 adb），重试再多也一样。 */
    private const val MAX_CONSECUTIVE_FAILS = 5

    // ── 可插拔的 adb 执行接入点（真机在这里接） ─────────────────────────────
    /** 真机设置：指向设备上可执行的 adb 二进制（内嵌 Termux/终端打包的 adb，或系统 adb）。 */
    @Volatile var adbBinaryPath: String? = null
    /** 或者整体接管 adb 执行（例如把命令丢给内嵌终端 / EmbeddedTermux 同 UID 执行）。设了就优先走它。 */
    @Volatile var runAdbOverride: ((List<String>) -> Pair<Int, String>?)? = null

    /** adb 二进制候选路径（device-verify：真机上实际落点以打包为准）。 */
    private val ADB_CANDIDATES = listOf(
        "/data/data/com.arix.app/files/usr/bin/adb", // 内嵌终端/Termux 前缀
        "/data/local/tmp/adb",
        "/system/bin/adb",
        "/system/xbin/adb",
    )

    // ── 轻量环形日志（供设置页/AI 报家底，不落盘） ───────────────────────────
    private val logBuf = ArrayDeque<String>()
    private const val LOG_CAP = 100
    private fun logLine(msg: String) {
        val line = "[$TAG] $msg"
        android.util.Log.i(TAG, msg)
        synchronized(logBuf) {
            logBuf.addLast(line)
            while (logBuf.size > LOG_CAP) logBuf.removeFirst()
        }
    }
    /** 取最近的自恢复日志（诊断用）。 */
    fun recentLog(): List<String> = synchronized(logBuf) { logBuf.toList() }

    // ── 对外主入口 ────────────────────────────────────────────────────────
    /**
     * 确保 Shizuku 活着：已活直接返回 true；否则跑一遍自恢复流程，成功返回 true，失败记录日志返回 false。
     * 幂等、可反复调（开机、体检、用户手点都能调）。**同步阻塞**，调用方请放后台线程（BootReceiver 已 goAsync+子线程）。
     */
    fun ensureAlive(ctx: Context): Boolean {
        if (ShizukuAccess.running()) {
            logLine("Shizuku 已在运行，无需恢复")
            return true
        }
        logLine("Shizuku 未运行，尝试自恢复…（注意：首次必须人工配对过一次，否则连不上）")
        return try {
            recover(ctx.applicationContext)
        } catch (e: Throwable) {
            logLine("自恢复异常：${e.message}")
            false
        }
    }

    /** 自恢复主流程：开无线调试 → 发现端口 → 回环连接 → 拉起 Shizuku → 复检。 */
    private fun recover(ctx: Context): Boolean {
        tryEnableWirelessDebug(ctx)          // 尽力开总开关；没权限就跳过（内部提示）
        val port = discoverPort(ctx)
        if (port == null) {
            logLine("没发现无线调试端口，放弃本轮（TODO：mDNS 发现待真机接）")
            return false
        }
        if (!adbConnectLocalhost(port)) {
            logLine("adb connect 127.0.0.1:$port 失败，放弃本轮")
            return false
        }
        startShizuku(port)
        // 拉起后给 binder 一点起来的时间再复检（device-verify：真机上 start.sh 起服务约需数百 ms~数秒）
        val serial = "127.0.0.1:$port"
        repeat(6) {
            if (ShizukuAccess.running()) { logLine("✔ Shizuku 已恢复（经 $serial）"); return true }
            try { Thread.sleep(500) } catch (_: InterruptedException) { return false }
        }
        val ok = ShizukuAccess.running()
        logLine(if (ok) "✔ Shizuku 已恢复" else "拉起 start.sh 后 binder 仍未活，可能 start.sh 缺失/无线调试未真开（device-verify）")
        return ok
    }

    // ── 步骤 1：开无线调试总开关 ───────────────────────────────────────────
    /**
     * 有 WRITE_SECURE_SETTINGS 才 putInt 开无线调试（adb_wifi_enabled=1，顺带 adb_enabled=1）；没有就跳过并提示。
     * @return 真的写成功才 true。没权限/写失败返回 false（不阻断后续——可能靠系统「可信 Wi-Fi 自动重开」已经开了）。
     */
    fun tryEnableWirelessDebug(ctx: Context): Boolean {
        val hasPerm = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
        if (!hasPerm) {
            logLine(
                "无 WRITE_SECURE_SETTINGS，跳过自动开无线调试。" +
                "可引导用户执行一次：adb shell pm grant com.arix.app android.permission.WRITE_SECURE_SETTINGS；" +
                "或依赖 Android 13+『可信 Wi-Fi 自动重新开启无线调试』。"
            )
            return false
        }
        return try {
            val cr = ctx.contentResolver
            Settings.Global.putInt(cr, "adb_enabled", 1)
            // adb_wifi_enabled 是无线调试(Android 11+)的总开关键
            Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
            logLine("已写 adb_wifi_enabled=1（无线调试已请求开启，device-verify：部分 ROM 键名/生效行为可能不同）")
            true
        } catch (e: Throwable) {
            logLine("写 adb_wifi_enabled 失败：${e.message}")
            false
        }
    }

    // ── 步骤 2：发现无线调试的连接端口 ─────────────────────────────────────
    /**
     * 发现无线调试「连接端口」（非配对端口）。真机上该端口每次开无线调试都随机（30000~49999 常见）。
     *
     * 正解是 mDNS 解析服务类型 `_adb-tls-connect._tcp`（无线调试连接）/`_adb-tls-pairing._tcp`（配对）。
     * 这里**留桩**：先给一个可选的小端口白名单做回环快扫兜底，默认空 → 返回 null。
     * TODO(device-verify)：用 NsdManager.discoverServices("_adb-tls-connect._tcp", PROTOCOL_DNS_SD, …)
     *   解析出 host=127.0.0.1 的 port；或读系统属性/日志。mDNS 是异步回调，接的时候要把本函数改成挂起/带超时等待。
     */
    fun discoverPort(ctx: Context): Int? {
        // TODO(device-verify)：接 NsdManager mDNS `_adb-tls-connect._tcp` → 返回其 port。
        // 兜底：对少量候选端口做回环连通性快扫（默认空集；真机可注入已知端口）。
        val hit = scanLocalPorts(candidatePorts)
        if (hit != null) logLine("回环快扫命中端口 $hit（兜底路径；正式请接 mDNS）")
        else logLine("discoverPort 桩：未接 mDNS 且候选端口为空 → null（TODO 真机接 NsdManager）")
        return hit
    }

    /** 可注入的候选端口（真机若已知无线调试端口可填这里做兜底；默认空）。 */
    @Volatile var candidatePorts: List<Int> = emptyList()

    /** 对候选端口做 127.0.0.1 回环 TCP 连通性快扫，返回第一个能连上的。纯连通性判断，非真正的 adb 握手。 */
    private fun scanLocalPorts(ports: List<Int>): Int? {
        for (p in ports) {
            try {
                java.net.Socket().use { s ->
                    s.connect(java.net.InetSocketAddress("127.0.0.1", p), 300)
                    if (s.isConnected) return p
                }
            } catch (_: Throwable) { /* 端口没开，继续 */ }
        }
        return null
    }

    // ── 步骤 3：回环连接 ──────────────────────────────────────────────────
    /** `adb connect 127.0.0.1:port`。成功（输出含 connected/already）返回 true。 */
    fun adbConnectLocalhost(port: Int): Boolean {
        val r = runAdb("connect", "127.0.0.1:$port")
        if (r == null) { logLine("adbConnect：runAdb 不可用（见 runAdb 桩说明）"); return false }
        val (code, out) = r
        val ok = code == 0 && (out.contains("connected", true) || out.contains("already", true))
        logLine("adb connect 127.0.0.1:$port → [$code] ${out.trim()}")
        return ok
    }

    // ── 步骤 4：拉起 Shizuku ──────────────────────────────────────────────
    /**
     * 经回环 adb 跑 Shizuku 的 start.sh 起服务：`adb -s 127.0.0.1:port shell sh <SHIZUKU_START_SH>`。
     * @return runAdb 退出码 0 视为已下发（不代表 binder 一定起来，复检交给 [recover]）。
     */
    fun startShizuku(port: Int): Boolean {
        val serial = "127.0.0.1:$port"
        val r = runAdb("-s", serial, "shell", "sh", SHIZUKU_START_SH)
        if (r == null) { logLine("startShizuku：runAdb 不可用"); return false }
        val (code, out) = r
        logLine("adb -s $serial shell sh $SHIZUKU_START_SH → [$code] ${out.trim()}")
        return code == 0
    }

    // ── adb 实际执行（可插拔桩，真机在此接） ───────────────────────────────
    /**
     * 跑一条 adb 命令，返回 (退出码, stdout+stderr)；不可用返回 null。
     *
     * 优先级：[runAdbOverride]（例如交给内嵌终端/EmbeddedTermux 同 UID 执行）→ [adbBinaryPath] / 候选路径直接 ProcessBuilder。
     * targetSdk=28 落在旧 SELinux 域(untrusted_app_28)，保留「execve 自己数据目录里的二进制」权限 → 直接 exec 私有目录 adb 可行。
     *
     * TODO(device-verify)：
     *  - 真机需先有 adb 二进制（内嵌终端打包 / 系统自带），并已完成过一次 `adb pair`（adbkey 落在 adb 的 $HOME/.android）；
     *  - 无 HOME/adbkey 时 adb 会现生成新 key，但设备侧未信任该 key → connect 会被拒，仍需人工配对一次；
     *  - 若走内嵌终端，请通过 [runAdbOverride] 注入其执行通道，别依赖这里的裸 ProcessBuilder。
     */
    fun runAdb(vararg args: String): Pair<Int, String>? {
        runAdbOverride?.let { return try { it(args.toList()) } catch (e: Throwable) { logLine("runAdbOverride 异常：${e.message}"); null } }
        val bin = resolveAdb()
        if (bin == null) {
            logLine("runAdb 桩：设备上找不到可执行 adb（候选=$ADB_CANDIDATES）。真机请设 adbBinaryPath 或 runAdbOverride。")
            return null
        }
        return try {
            val proc = ProcessBuilder(listOf(bin, *args)).redirectErrorStream(true).start()
            val out = proc.inputStream.bufferedReader().readText()
            val code = proc.waitFor()
            code to out
        } catch (e: Throwable) {
            logLine("runAdb 执行失败（$bin ${args.joinToString(" ")}）：${e.message}")
            null
        }
    }

    private fun resolveAdb(): String? =
        adbBinaryPath?.takeIf { runCatching { File(it).canExecute() }.getOrDefault(false) }
            ?: ADB_CANDIDATES.firstOrNull { runCatching { File(it).canExecute() }.getOrDefault(false) }

    // ── 周期体检（对标 Shizuku Keeper） ───────────────────────────────────
    private var healthThread: HandlerThread? = null
    private var healthHandler: Handler? = null
    @Volatile private var healthRunning = false

    /**
     * 启动周期体检：每 [HEALTH_INTERVAL_MS] 检查一次 binder，掉了就 [ensureAlive] 重拉。幂等（重复调不叠加）。
     * 用专属 HandlerThread + 单一自重投 Runnable，持有 applicationContext，不泄漏 Activity。
     *
     * 注意（device-verify）：体检跑在本 App 进程里，进程被系统杀掉后体检也停。要真正长活，宜配合前台服务
     * （WakeService 已是 microphone|dataSync 前台服务）承载本循环，或用 WorkManager 周期任务兜底。此处先给进程内实现。
     */
    @Synchronized
    fun scheduleHealthCheck(ctx: Context) {
        if (healthRunning) return
        val app = ctx.applicationContext
        val ht = HandlerThread("adb-keepalive-health").apply { start() }
        val h = Handler(ht.looper)
        healthThread = ht
        healthHandler = h
        healthRunning = true
        logLine("周期体检已启动（${HEALTH_INTERVAL_MS / 1000}s 起，一直正常就逐步拉长到 ${HEALTH_MAX_INTERVAL_MS / 60000}min）")
        var interval = HEALTH_INTERVAL_MS
        var fails = 0
        val tick = object : Runnable {
            override fun run() {
                try {
                    if (ShizukuAccess.running()) {
                        // 一直好着就把周期翻倍（封顶 [HEALTH_MAX_INTERVAL_MS]）：原来是**从开机起每 60s 醒一次、永远**，
                        // 而绝大多数时候它就是活的——这是本模块唯一的常驻耗电项，而它什么也没做。
                        // 掉线的检测延迟变长不要紧：真正要用特权时 ensureAlive 是**当场按需**调的，不靠体检兜底。
                        interval = (interval * 2).coerceAtMost(HEALTH_MAX_INTERVAL_MS)
                        fails = 0
                    } else {
                        logLine("体检：binder 掉了，重拉")
                        if (ensureAlive(app)) {
                            interval = HEALTH_INTERVAL_MS   // 刚出过事，回到密集观察
                            fails = 0
                        } else {
                            fails++
                            interval = HEALTH_INTERVAL_MS
                        }
                    }
                } catch (e: Throwable) {
                    logLine("体检异常：${e.message}")
                    fails++
                } finally {
                    // 连着拉不起来就别再拉了：自恢复失败基本是「没人工配对过 / 没有可执行 adb」这类**结构性**原因，
                    // 每分钟重试一次只是在耗电重复同一个失败。停下来等下次开机或用户手动触发。
                    if (fails >= MAX_CONSECUTIVE_FAILS) {
                        logLine("体检：连续 $fails 次没能恢复 Shizuku，停掉周期体检（需要人工配对一次；重启或手动重新启用可再来）")
                        cancelHealthCheck()
                    } else if (healthRunning) h.postDelayed(this, interval)
                }
            }
        }
        h.postDelayed(tick, interval)
    }

    /** 停体检并回收 HandlerThread（别泄漏）。 */
    @Synchronized
    fun cancelHealthCheck() {
        healthRunning = false
        healthHandler?.removeCallbacksAndMessages(null)
        healthThread?.quitSafely()
        healthHandler = null
        healthThread = null
        logLine("周期体检已停止")
    }
}
