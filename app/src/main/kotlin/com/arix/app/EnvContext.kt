package com.arix.app

import android.content.Context
import android.os.BatteryManager

/**
 * 环境上下文·选择性注入：不再每条消息都塞全量时间/电量，只在**变化或值得注意**时才说一句，
 * 避免「时间天天注入反被模型误读成半夜」这类噪声。有状态、按会话比对上一次。
 * - 时间：仅首次 / 换天 / 距上条消息 >20 分钟（新一段对话）才报当前时间。
 * - 电量：仅低电量(≤20%)或充电状态变化才报，并带「充电中 / 拔线」状态。
 * - 健康 / 天气：只在有关心点(concern：久坐/深夜/有雨等)时报，不再routine塞步数/天气。
 * 位置不再routine注入（噪声，需要时走 device_status 工具）。
 */
object EnvContext {
    private data class Snap(val day: String, val lowUnplugged: Boolean)
    private val last = java.util.concurrent.ConcurrentHashMap<Long, Snap>()
    private val lastAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** 健康/天气 concern 检查间隔：聊天连发时不要每条消息都查询 ContentProvider/SQLite。 */
    private const val CONCERN_INTERVAL_MS = 60_000L
    private val lastConcernAt = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    /** 返回本轮该注入的环境串；没什么可说时返回空串。 */
    fun build(context: Context, convId: Long?): String {
        val id = convId ?: -1L
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance()
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(cal.time)
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val level = try { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 } catch (_: Exception) { -1 }
        val charging = try { bm?.isCharging ?: false } catch (_: Exception) { false }
        val prev = last[id]
        val gapMin = (now - (lastAt[id] ?: 0L)) / 60_000L

        val parts = ArrayList<String>(4)
        // 时间：首次 / 换天 / 隔了很久
        if (prev == null || prev.day != day || gapMin >= 20) {
            parts.add("现在 " + java.text.SimpleDateFormat("M月d日 EEEE HH:mm", java.util.Locale.CHINA).format(cal.time))
        }
        // 电量：只在「低电且没在充电」时报一次。充着电=有人管着，不打扰；充电状态变化也不播报。
        val lowUnplugged = level in 0..20 && !charging
        if (lowUnplugged && (prev == null || !prev.lowUnplugged)) parts.add("电量只剩 $level%、没在充电")
        // 健康 / 天气：只报关心点，且每个会话每分钟最多检查一次（这些查询背后可能跨进程/读盘）。
        if (now - (lastConcernAt[id] ?: 0L) >= CONCERN_INTERVAL_MS) {
            lastConcernAt[id] = now
            try { HealthSignals.concern(context)?.let { parts.add(it) } } catch (_: Exception) {}
            try { WeatherSignals.concern(context)?.let { parts.add(it) } } catch (_: Exception) {}
        }

        last[id] = Snap(day, lowUnplugged)
        lastAt[id] = now
        return if (parts.isEmpty()) "" else "【环境】" + parts.joinToString("；") + "。"
    }
}
