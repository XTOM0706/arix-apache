package com.arix.app

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashHandler : Thread.UncaughtExceptionHandler {

    private const val TAG = "CrashHandler"
    private const val CRASH_DIR = "crash_reports"
    private const val CRASH_FILE_PREFIX = "crash_"
    private const val MAX_CRASH_FILES = 10
    const val EXTRA_CRASH_REPORT = "crash_report_extra"

    private const val PREFS_NAME = "crash_guard_prefs"
    private const val KEY_CRASH_COUNT = "crash_count"
    private const val KEY_LAST_CRASH_TIME = "last_crash_time"
    private const val MAX_CONSECUTIVE_CRASHES = 3
    private const val CRASH_WINDOW_MS = 60_000L

    private var defaultHandler: Thread.UncaughtExceptionHandler? = null
    private var appContext: Context? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun init(context: Context) {
        appContext = context.applicationContext
        defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(this)
        Log.i(TAG, "崩溃捕获已初始化")
        // 注意：不要在启动 N 秒后无条件把 crash_count 清零。若崩溃稳定发生在清零之后（如恢复状态延迟崩、
        // 后台任务崩），每次重启都会先清零再崩 → count 永远回到 1、永远 ≤ 上限 → 无限闪退。
        // 连续崩溃的衰减改由 uncaughtException 里的时间窗口负责（距上次崩溃 > CRASH_WINDOW 才重新计数）。
    }

    override fun uncaughtException(thread: Thread, ex: Throwable) {
        val ctx = appContext
        if (ctx != null) {
            saveCrashReport(ctx, thread, ex)

            val prefs = getPrefs(ctx)
            val lastTime = prefs.getLong(KEY_LAST_CRASH_TIME, 0L)
            val now = System.currentTimeMillis()
            val count = if (now - lastTime < CRASH_WINDOW_MS) {
                prefs.getInt(KEY_CRASH_COUNT, 0) + 1
            } else {
                1
            }
            prefs.edit()
                .putInt(KEY_CRASH_COUNT, count)
                .putLong(KEY_LAST_CRASH_TIME, now)
                .apply()

            if (count <= MAX_CONSECUTIVE_CRASHES) {
                restartToCrashPage(ctx)
            } else {
                Log.e(TAG, "连续崩溃 ${count} 次，停止自动重启以避免死循环")
            }
        }
        Log.e(TAG, "未捕获异常", ex)

        defaultHandler?.uncaughtException(thread, ex)

        Process.killProcess(Process.myPid())
        System.exit(1)
    }

    private fun getPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun restartToCrashPage(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent(context, MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra(EXTRA_CRASH_REPORT, true)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            try {
                val pendingIntent = PendingIntent.getActivity(
                    context, 0, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarm?.setExact(AlarmManager.RTC, System.currentTimeMillis() + 200, pendingIntent)
            } catch (_: Exception) {}
        }
    }

    private fun saveCrashReport(context: Context, thread: Thread, ex: Throwable) {
        try {
            val crashDir = File(context.filesDir, CRASH_DIR)
            if (!crashDir.exists()) crashDir.mkdirs()

            val sdf = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            val crashFile = File(crashDir, "${CRASH_FILE_PREFIX}${sdf.format(Date())}.txt")
            crashFile.writeText(buildCrashReport(thread, ex))
            Log.i(TAG, "崩溃报告已保存: ${crashFile.absolutePath}")

            cleanupOldReports(crashDir)
        } catch (e: Exception) {
            Log.e(TAG, "保存崩溃报告失败", e)
        }
    }

    private fun buildCrashReport(thread: Thread, ex: Throwable): String {
        val sb = StringBuilder()
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())

        sb.appendLine("========== Arix 崩溃报告 ==========")
        sb.appendLine("时间: ${sdf.format(Date())}")
        sb.appendLine()
        sb.appendLine("--- 设备信息 ---")
        sb.appendLine("品牌: ${Build.BRAND}")
        sb.appendLine("型号: ${Build.MODEL}")
        sb.appendLine("制造商: ${Build.MANUFACTURER}")
        sb.appendLine("Android 版本: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("CPU ABI: ${Build.SUPPORTED_ABIS?.joinToString(", ") ?: "N/A"}")
        sb.appendLine()

        val ctx = appContext
        if (ctx != null) {
            sb.appendLine("--- 应用信息 ---")
            try {
                val pkgInfo = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
                sb.appendLine("包名: ${ctx.packageName}")
                sb.appendLine("版本: ${pkgInfo.versionName} (${PackageInfoCompat.getLongVersionCode(pkgInfo)})")
            } catch (_: Exception) {
                sb.appendLine("包名: ${ctx.packageName}")
            }
            try {
                val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                val memInfo = ActivityManager.MemoryInfo()
                am?.getMemoryInfo(memInfo)
                sb.appendLine("可用内存: ${memInfo.totalMem / 1024 / 1024}MB / ${memInfo.availMem / 1024 / 1024}MB")
                sb.appendLine("内存不足: ${memInfo.lowMemory}")
            } catch (_: Exception) {
                sb.appendLine("内存信息: 获取失败")
            }
            sb.appendLine()
        }

        sb.appendLine("--- 线程信息 ---")
        sb.appendLine("崩溃线程: ${thread.name} (ID=${thread.threadId()}, 优先级=${thread.priority})")
        val allThreads = Thread.getAllStackTraces()
        sb.appendLine("活跃线程数: ${allThreads.size}")
        allThreads.forEach { (t, _) ->
            val state = try { t.state } catch (_: Exception) { "UNKNOWN" }
            sb.appendLine("  [${state}] ${t.name} (ID=${t.threadId()})")
        }
        sb.appendLine()

        sb.appendLine("--- 堆栈信息 ---")
        val sw = StringWriter()
        ex.printStackTrace(PrintWriter(sw))
        sb.appendLine(sw.toString())

        if (ex.cause != null) {
            sb.appendLine()
            sb.appendLine("--- 根因 (Caused by) ---")
            var cause = ex.cause
            while (cause != null) {
                val csw = StringWriter()
                cause.printStackTrace(PrintWriter(csw))
                sb.appendLine(csw.toString())
                cause = cause.cause
                if (cause != null) sb.appendLine("Caused by:")
            }
        }

        sb.appendLine()
        sb.appendLine("========== 报告结束 ==========")
        return sb.toString()
    }

    private fun cleanupOldReports(crashDir: File) {
        try {
            val files = crashDir.listFiles { f -> f.name.startsWith(CRASH_FILE_PREFIX) }
                ?.sortedByDescending { it.lastModified() } ?: return
            if (files.size > MAX_CRASH_FILES) files.drop(MAX_CRASH_FILES).forEach { it.delete() }
        } catch (_: Exception) {}
    }

    fun hasPendingCrash(context: Context): Boolean {
        val crashDir = File(context.filesDir, CRASH_DIR)
        if (!crashDir.exists()) return false
        return crashDir.listFiles { f -> f.name.startsWith(CRASH_FILE_PREFIX) }?.isNotEmpty() == true
    }

    fun getCrashReports(context: Context): List<File> {
        val crashDir = File(context.filesDir, CRASH_DIR)
        if (!crashDir.exists()) return emptyList()
        return crashDir.listFiles { f -> f.name.startsWith(CRASH_FILE_PREFIX) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    fun readCrashReport(file: File): String = try {
        file.readText()
    } catch (_: Exception) {
        "无法读取崩溃报告: 文件可能已损坏"
    }

    fun clearAllReports(context: Context) {
        val crashDir = File(context.filesDir, CRASH_DIR)
        if (crashDir.exists()) crashDir.listFiles()?.forEach { it.delete() }
    }

    fun clearReport(file: File): Boolean = file.delete()
}
