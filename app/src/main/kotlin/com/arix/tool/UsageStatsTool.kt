package com.arix.tool

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 手机使用情况：让 AI 知道你今天/近几天用了哪些 App、各用多久、总屏幕时间——用于体贴提醒（刷太久歇会儿）等。
 * 走 [UsageStatsManager]，需用户在系统里授予「使用情况访问权限」（特殊权限，权限页跳转开启）。只读。
 */
class UsageStatsTool(private val context: Context) : Tool {
    override val name = "app_usage"

    /** 「他几点在用哪个 App、用了多久」是一份很完整的行为画像。见 [AndroidPermissionLevel.PRIVATE]。 */
    override val permissionLevel = AndroidPermissionLevel.PRIVATE

    override val description = "查看手机使用情况：今天/近几天各 App 的前台使用时长与总屏幕时间(降序)。需用户授予「使用情况访问权限」。"
    // 没授权时 execute 第一句就返回错误，schema 发了也只是白撞一次
    override val requires = ToolRequirement.USAGE_STATS
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("days", JSONObject().apply { put("type", "integer"); put("description", "统计最近几天，默认 1(今天)") })
            put("top", JSONObject().apply { put("type", "integer"); put("description", "列出前几名 App，默认 8") })
        })
        put("required", JSONArray(listOf<String>()))
    }

    companion object {
        fun hasAccess(context: Context): Boolean = try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            // checkOpNoThrow：API 19+ 全可用；unsafeCheckOpNoThrow 是 API29 才有，minSdk=26 用它会在旧机 NoSuchMethodError
            @Suppress("DEPRECATION")
            val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            // MODE_DEFAULT=op 未显式设置→回落查实际权限授予（部分 OEM 已授权却停在 default，直接判 ALLOWED 会误报未授）
            if (mode == AppOpsManager.MODE_DEFAULT)
                context.checkCallingOrSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == android.content.pm.PackageManager.PERMISSION_GRANTED
            else mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) { false }
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        if (!hasAccess(context))
            return@withContext ToolResult("没有「使用情况访问权限」，请在「权限」页开启后再试", isError = true)
        try {
            val days = params.optInt("days", 1).coerceIn(1, 30)
            val top = params.optInt("top", 8).coerceIn(1, 30)
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
            val end = System.currentTimeMillis()
            // 多天也从午夜对齐起算，避免最早那天的整桶被计全（queryAndAggregate 不裁剪跨界桶）
            val start = if (days == 1) startOfTodayMillis() else startOfTodayMillis() - (days - 1) * 86_400_000L
            val agg = usm.queryAndAggregateUsageStats(start, end)  // Map<pkg, UsageStats>
            if (agg.isNullOrEmpty()) return@withContext ToolResult("这段时间没有使用记录")
            val pm = context.packageManager
            // 总屏幕时间对「全部 App」求和（不只前 N），否则会偏小/误导
            val total = agg.values.sumOf { it.totalTimeInForeground }
            val rows = agg.values
                .filter { it.totalTimeInForeground >= 60_000L }        // 至少用 1 分钟才列进明细
                .sortedByDescending { it.totalTimeInForeground }
                .take(top)
                .map { u ->
                    val label = try { pm.getApplicationLabel(pm.getApplicationInfo(u.packageName, 0)).toString() } catch (_: Exception) { u.packageName }
                    label to u.totalTimeInForeground
                }
            if (rows.isEmpty() && total < 60_000L) return@withContext ToolResult("这段时间没有明显的 App 使用")
            val scope = if (days == 1) "今天" else "近 $days 天"
            ToolResult(buildString {
                append("$scope 屏幕使用（前台）共 ${fmt(total)}\n")
                rows.forEach { append("· ${it.first}：${fmt(it.second)}\n") }
            }.trim())
        } catch (e: Exception) { ToolResult("读取使用情况失败：${e.message}", isError = true) }
    }

    private fun fmt(ms: Long): String {
        val min = ms / 60_000L
        return if (min >= 60) "${min / 60}小时${min % 60}分" else "${min}分"
    }

    private fun startOfTodayMillis(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
