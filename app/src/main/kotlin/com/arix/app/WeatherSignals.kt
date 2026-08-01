package com.arix.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 天气感知：据本机定位取当前天气 + 今日降水概率，随「环境上下文」注入，让 AI 能自然地体贴（带伞/加衣）。
 * 用 open-meteo（免费、免 key、按经纬度）。默认关；开且已授定位才注入。只给客观数据，不替 AI 规定应答（inject-data-only）。
 * 与 [HealthSignals] 同结构：60 分钟缓存（注入在发送路径、可能主线程），开关变化清缓存。
 */
object WeatherSignals {
    private const val PREF = "xtom_weather"
    private const val K_ENABLED = "enabled"
    private const val TTL_MS = 60 * 60_000L   // 天气变化慢，缓存 1 小时

    @Volatile private var cache: String? = null
    @Volatile private var concernCache: String? = null
    @Volatile private var at = 0L

    fun isEnabled(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(K_ENABLED, false)
    fun setEnabled(c: Context, v: Boolean) { c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(K_ENABLED, v).apply(); invalidate() }
    fun invalidate() { at = 0L; cache = null; concernCache = null }

    private fun hasLocation(c: Context) =
        ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun onMain() = android.os.Looper.myLooper() == android.os.Looper.getMainLooper()

    /** 注入用简洁天气串（如「多云 22°C，今日降水概率 60%」）；未开/无定位/取不到返回 null。带缓存。 */
    fun snapshot(c: Context): String? {
        if (!isEnabled(c) || !hasLocation(c)) return null
        // 只在后台线程刷新（refresh 可能持锁做网络）；主线程只读 volatile 缓存，绝不碰锁 → 免 ANR。靠 ChatScreen 后台预热填充
        if (!onMain()) refresh(c)
        return cache
    }

    /** 主动关心点：今日大概率有雨 / 气温偏冷偏热时给一句客观观察；无则 null。 */
    fun concern(c: Context): String? {
        if (!isEnabled(c) || !hasLocation(c)) return null
        if (!onMain()) refresh(c)
        return concernCache
    }

    @Synchronized
    private fun refresh(c: Context) {
        val now = System.currentTimeMillis()
        if (now - at < TTL_MS && cache != null) return
        // 缓存不新鲜就主动定位一次（本方法只在后台线程跑，见 snapshot/concern 的 onMain 守卫）。
        // 天气对精度不敏感，给 5s 就够；等不到会退回旧缓存定位。见 LocationSignals。
        val loc = try { LocationSignals.currentBlocking(c, timeoutMs = 5_000L) } catch (_: Exception) { null } ?: return
        var conn: HttpURLConnection? = null
        try {
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${"%.3f".format(java.util.Locale.US, loc.latitude)}&longitude=${"%.3f".format(java.util.Locale.US, loc.longitude)}" +
                "&current=temperature_2m,weather_code,precipitation&daily=precipitation_probability_max,temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1"
            conn = (URL(url).openConnection() as HttpURLConnection).apply { connectTimeout = 4000; readTimeout = 6000 }
            if (conn.responseCode !in 200..299) return
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val cur = json.optJSONObject("current") ?: return
            val temp = cur.optDouble("temperature_2m", Double.NaN)
            val code = cur.optInt("weather_code", -1)
            val daily = json.optJSONObject("daily")
            val pop = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, -1) ?: -1
            val tmax = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN) ?: Double.NaN
            val tmin = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN) ?: Double.NaN
            val desc = codeCn(code)
            cache = buildString {
                if (desc.isNotBlank()) append(desc)
                if (!temp.isNaN()) { if (isNotEmpty()) append(" "); append("${temp.toInt()}°C") }
                if (pop in 0..100) append("，今日降水概率 ").append(pop).append("%")
            }.ifBlank { null }
            // 关心点：大概率有雨 / 明显偏冷偏热
            concernCache = when {
                pop in 60..100 -> "今天大概率有雨（降水概率 $pop%）"
                code in intArrayOf(51,53,55,61,63,65,80,81,82,95,96,99).toList() -> "现在在下雨"
                !tmax.isNaN() && tmax >= 35 -> "今天很热（最高约 ${tmax.toInt()}°C）"
                !tmin.isNaN() && tmin <= 2 -> "今天很冷（最低约 ${tmin.toInt()}°C）"
                else -> null
            }
            at = now
        } catch (_: Exception) {
        } finally { conn?.disconnect() }
    }

    /** WMO weather_code → 中文（简）。 */
    private fun codeCn(code: Int): String = when (code) {
        0 -> "晴"; 1, 2 -> "多云"; 3 -> "阴"
        45, 48 -> "雾"
        51, 53, 55, 56, 57 -> "小雨"
        61, 63, 65, 66, 67 -> "雨"
        71, 73, 75, 77 -> "雪"
        80, 81, 82 -> "阵雨"
        85, 86 -> "阵雪"
        95, 96, 99 -> "雷雨"
        else -> ""
    }
}
