package com.arix.app

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 第三健康源：Health Connect（健康连接）。统一读小米/vivo/Keep/Google Fit/三星等**往健康连接写入**的
 * 步数/心率/睡眠——一个集成覆盖多家 App（华为手表已由 Gadgetbridge 源覆盖，不重复）。
 *
 * 设计：Health Connect 的 API 全是 suspend，而 [HealthSignals] 的读取是同步的（注入在发送路径上按需读）。
 * 所以这里用「后台刷新 + 内存缓存」桥接：[ensureFresh] 在后台协程里拉数据写进缓存（非阻塞、任意线程可调），
 * [HealthSignals] 只同步读缓存 → 绝不 runBlocking、绝不 ANR。缓存 45 分钟内有效。
 */
object HealthConnectSource {

    val permissions: Set<String> = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshing = java.util.concurrent.atomic.AtomicBoolean(false)
    @Volatile private var lastAttempt = 0L      // 按「尝试时间」限流：未授权时也别每次都发 binder 拉一遍
    private const val REFRESH_TTL = 120_000L    // 两次刷新间最短间隔
    private const val STALE_MS = 45 * 60_000L   // 缓存超过 45 分钟就当没有

    @Volatile private var cSteps: Int? = null
    @Volatile private var cHr: Pair<Int, Long>? = null   // (bpm, epochSec)
    @Volatile private var cSleepMin: Int? = null
    @Volatile private var cOxy: Int? = null              // 血氧 %
    @Volatile private var cAt: Long = 0L

    /** 健康连接是否可用（装了/系统内置且版本 OK）。 */
    fun available(ctx: Context): Boolean = try {
        HealthConnectClient.getSdkStatus(ctx) == HealthConnectClient.SDK_AVAILABLE
    } catch (_: Throwable) { false }

    suspend fun hasPermissions(ctx: Context): Boolean = try {
        if (!available(ctx)) false
        else HealthConnectClient.getOrCreate(ctx).permissionController.getGrantedPermissions().containsAll(permissions)
    } catch (_: Throwable) { false }

    /** 给设置页请求权限用的 ActivityResult 契约。 */
    fun permissionContract() = PermissionController.createRequestPermissionResultContract()

    /** 非阻塞：缓存过期就后台拉一次。任意线程可调（含主线程），不会卡。 */
    fun ensureFresh(ctx: Context) {
        val now = System.currentTimeMillis()
        if (now - lastAttempt < REFRESH_TTL) return          // 限流看「上次尝试」，成功与否都算，避免未授权时狂发 binder
        if (!refreshing.compareAndSet(false, true)) return    // 原子占位，防两个调用方同时开刷
        lastAttempt = now
        val app = ctx.applicationContext
        scope.launch { try { refresh(app) } catch (_: Throwable) {} finally { refreshing.set(false) } }
    }

    /** 真正拉数据（suspend，后台跑）。无权限/不可用则静默返回，不动缓存。 */
    suspend fun refresh(ctx: Context) {
        if (!available(ctx)) return
        val client = try { HealthConnectClient.getOrCreate(ctx) } catch (_: Throwable) { return }
        if (!client.permissionController.getGrantedPermissions().containsAll(permissions)) return

        val now = Instant.now()
        val zone = ZoneId.systemDefault()
        val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant()

        cSteps = readAgg(client, StepsRecord.COUNT_TOTAL, dayStart, now)?.toInt()?.takeIf { it > 0 } ?: cSteps
        cHr = try {
            val r = client.readRecords(ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(now.minusSeconds(3 * 3600), now), ascendingOrder = false, pageSize = 20))
            r.records.flatMap { it.samples }.maxByOrNull { it.time }?.let { it.beatsPerMinute.toInt() to it.time.epochSecond } ?: cHr
        } catch (_: Throwable) { cHr }
        cSleepMin = try {
            val r = client.readRecords(ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(now.minusSeconds(24 * 3600), now), ascendingOrder = false, pageSize = 30))
            r.records.sumOf { Duration.between(it.startTime, it.endTime).toMinutes() }.toInt().takeIf { it > 0 } ?: cSleepMin
        } catch (_: Throwable) { cSleepMin }
        cOxy = try {
            val r = client.readRecords(ReadRecordsRequest(OxygenSaturationRecord::class, TimeRangeFilter.between(now.minusSeconds(6 * 3600), now), ascendingOrder = false, pageSize = 1))
            r.records.firstOrNull()?.percentage?.value?.toInt()?.takeIf { it in 1..100 } ?: cOxy
        } catch (_: Throwable) { cOxy }
        cAt = System.currentTimeMillis()
    }

    private suspend fun readAgg(client: HealthConnectClient, metric: AggregateMetric<Long>, from: Instant, to: Instant): Long? = try {
        client.aggregate(AggregateRequest(setOf(metric), TimeRangeFilter.between(from, to)))[metric]
    } catch (_: Throwable) { null }

    private fun fresh(): Boolean = cAt > 0 && System.currentTimeMillis() - cAt < STALE_MS
    fun cachedSteps(): Int? = if (fresh()) cSteps else null
    fun cachedHeartRate(): Pair<Int, Long>? = if (fresh()) cHr else null
    fun cachedSleepMinutes(): Int? = if (fresh()) cSleepMin else null
    fun cachedOxygen(): Int? = if (fresh()) cOxy else null
}
