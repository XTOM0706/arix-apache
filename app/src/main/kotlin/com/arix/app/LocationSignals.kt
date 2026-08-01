package com.arix.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 取位统一入口。
 *
 * 此前全项目三处取位（device_status / 地图附近搜索 / 天气）都只调 [LocationManager.getLastKnownLocation]，
 * 那只是读系统缓存：别的 App 最近定位过就有值，否则返回 null——「概率性拿不到位置」就是这么来的，
 * 与定位权限无关（AI 因此常把原因误报成「没授权」）。这里在缓存不新鲜时**主动请求一次定位**。
 *
 * 与 [HealthSignals]/[WeatherSignals] 同结构：object + best-effort，取不到就返回 null，绝不抛。
 */
object LocationSignals {
    /** 这个岁数以内的缓存定位直接用，不再请求（省电；位置在两分钟内变化不大）。 */
    private const val FRESH_MS = 2 * 60_000L
    private const val DEFAULT_TIMEOUT_MS = 8_000L

    /** 请求顺序：NETWORK 最快且室内可用，GPS 精确但室内常年拿不到——两个一起请求，谁先回用谁。 */
    private val ACTIVE_PROVIDERS = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
    private val ALL_PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)

    fun hasPermission(c: Context) =
        ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(c, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun lm(c: Context) = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private fun enabled(lm: LocationManager) =
        ALL_PROVIDERS.any { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }

    /**
     * 拿不到位置的原因，给工具如实回报用——别让 AI 自己猜（它猜「没授权」，而真因多半是别的）。
     * 能取到位置时返回 null。
     */
    fun unavailableReason(c: Context): String? {
        if (!hasPermission(c)) return "没有定位权限"
        val lm = lm(c) ?: return "系统没有定位服务"
        if (!enabled(lm)) return "系统定位总开关没打开"
        return null
    }

    /** 纯读系统缓存，不等待。给「有就用、没有就算了」的场景。 */
    @SuppressLint("MissingPermission")
    fun lastKnown(c: Context): Location? {
        if (!hasPermission(c)) return null
        val lm = lm(c) ?: return null
        return ALL_PROVIDERS.mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }.maxByOrNull { it.time }
    }

    /**
     * 当前位置：缓存新鲜就直接用，否则主动请求一次定位（[timeoutMs] 内没回就退回旧缓存，哪怕陈旧）。
     * 必须在后台线程/协程调用——内部会等待。
     */
    suspend fun current(c: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Location? {
        if (!hasPermission(c)) return null
        val lm = lm(c) ?: return null
        val cached = lastKnown(c)
        if (cached != null && System.currentTimeMillis() - cached.time < FRESH_MS) return cached
        if (!enabled(lm)) return cached          // 总开关关着，请求也是白等
        return withTimeoutOrNull(timeoutMs) { requestOnce(lm) } ?: cached
    }

    /** [current] 的阻塞版，给还不是 suspend 的同步调用方（如 WeatherSignals.refresh）。绝不可在主线程调。 */
    fun currentBlocking(c: Context, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Location? =
        if (Looper.myLooper() == Looper.getMainLooper()) lastKnown(c)   // 主线程只读缓存，不等，免 ANR
        else runBlocking { current(c, timeoutMs) }

    /** 向所有可用 provider 各挂一个监听，谁先给出定位就用谁，随即全部摘掉。 */
    @SuppressLint("MissingPermission")
    private suspend fun requestOnce(lm: LocationManager): Location? = suspendCancellableCoroutine { cont ->
        val listeners = mutableListOf<LocationListener>()
        fun cleanup() {
            listeners.forEach { runCatching { lm.removeUpdates(it) } }
            listeners.clear()
        }
        val providers = ACTIVE_PROVIDERS.filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) { cont.resume(null); return@suspendCancellableCoroutine }
        cont.invokeOnCancellation { cleanup() }
        providers.forEach { p ->
            val l = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    // 多个 provider 可能都回；只认第一个，之后 isActive 为 false 直接忽略。
                    synchronized(listeners) {
                        if (cont.isActive) { cleanup(); cont.resume(location) }
                    }
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                @Deprecated("旧 API 抽象方法，必须实现", ReplaceWith(""))
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            synchronized(listeners) { listeners += l }
            // 回调投递到主 looper：本方法常在 IO 线程调用，那儿没有 Looper。
            runCatching { lm.requestLocationUpdates(p, 0L, 0f, l, Looper.getMainLooper()) }
                .onFailure { synchronized(listeners) { listeners -= l } }
        }
        // 所有 provider 都注册失败（比如权限被系统回收）→ 别干等到超时。
        synchronized(listeners) { if (listeners.isEmpty() && cont.isActive) cont.resume(null) }
    }
}
