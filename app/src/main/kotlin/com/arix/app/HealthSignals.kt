package com.arix.app

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.resume

/**
 * 健康数据：读取「better health tracker」App（`com.ailife.betterhealth`）暴露的健康数据，
 * 随「环境上下文」注入，让 AI 能自然地关心你的身体状态。
 *
 * 为什么不裸读 SensorManager：那家 App 专门做了各国产手表的传感器适配（步数/心率/血氧/血压/睡眠分期），
 * 我们直接读它的 ContentProvider 即可，省去逐机型适配，且能拿到睡眠/血氧这些裸传感器给不了的。
 *
 * 两条读取通道（都走 [android.content.ContentResolver]）：
 * - **v2（推荐）**：authority `com.ailife.betterhealth.health.secure`，读权限
 *   `com.ailife.betterhealth.permission.READ_DATA`（dangerous，运行时由系统弹窗授权，Shizuku 式）。
 * - **v1（兜底）**：authority `com.ailife.betterhealth.healthprovider`，读权限 `...READ_HEALTH_DATA`
 *   （normal，装了自动授予；但需用户在 tracker 里打开 ContentProvider 开关）。
 * 先试 v2，拿不到再试 v1；tracker 未装 / 未授权 / 无数据都优雅降级（不注入）。
 *
 * 只读、只给客观数字，不规定 AI 如何应答（见记忆 prompt-inject-data-only）。默认关，设置里开。
 */
object HealthSignals {
    const val TRACKER_PKG = "com.ailife.betterhealth"
    const val READ_PERM = "com.ailife.betterhealth.permission.READ_DATA"           // v2 dangerous
    const val READ_PERM_LEGACY = "com.ailife.betterhealth.READ_HEALTH_DATA"        // v1 normal

    private const val PREF = "xtom_health"
    private const val K_ENABLED = "enabled"
    private const val K_GB_PATH = "gb_db_path"     // Gadgetbridge 自动导出 SQLite 库路径（第二健康源）
    private const val HR_FRESH_MS = 60 * 60_000L   // 心率读数 60 分钟内才算「当前」

    // Gadgetbridge「数据库自动导出」默认落点（用户没手填路径时按序探测；文件名就叫 Gadgetbridge，无扩展名）
    private val GB_DEFAULTS = listOf(
        "/storage/emulated/0/Documents/Gadgetbridge/Gadgetbridge",
        "/storage/emulated/0/Gadgetbridge/Gadgetbridge",
        "/storage/emulated/0/Download/Gadgetbridge",
    )

    private const val AUTH_V2 = "com.ailife.betterhealth.health.secure"
    private const val AUTH_V1 = "com.ailife.betterhealth.healthprovider"
    // 先 v2 后 v1
    private val AUTHORITIES = listOf(AUTH_V2, AUTH_V1)

    fun isEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(K_ENABLED, false)
    fun setEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(K_ENABLED, on).apply(); invalidate() }

    /** tracker 是否已安装。 */
    fun isTrackerInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(TRACKER_PKG, 0); true
    } catch (_: Exception) { false }

    /** 是否已授予 v2 运行时读权限（v1 为 normal，装了即有，不在此判定）。 */
    fun hasReadPerm(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, READ_PERM) == PackageManager.PERMISSION_GRANTED

    // ============================================================
    // 主动测量：触发穿戴设备**现场测**一次（心率/血氧），而非读历史（见 tracker 的 api/APIReceiver）。
    //   请求：广播 GET_DATA_API 给 APIReceiver，extra DATA=HR/OXYGEN/…、TYPE=NOW/IMMEDIATELY/AUTO/…
    //   回复：广播 DATA_REPLY，extra RESULT=读数、ERROR=NONE/BUSY/HARDWARE/FALL(未佩戴)/PERMISSION_DENIED/WRONG_EXTRA。
    //   门控：tracker 里的「传感器 API」开关（发送方无需权限）。
    // ============================================================
    const val ACTION_GET_DATA = "com.ailife.betterhealth.GET_DATA_API"
    const val ACTION_DATA_REPLY = "com.ailife.betterhealth.DATA_REPLY"
    private const val API_RECEIVER = "com.ailife.betterhealth.api.APIReceiver"

    /** 可现场测量的指标 → tracker 的 DATA 值 + 单位/中文名。 */
    fun measurableMetric(name: String): Triple<String, String, String>? = when (name.lowercase()) {
        "heart_rate", "heartrate", "hr", "心率" -> Triple("HR", "bpm", "心率")
        "blood_oxygen", "oxygen", "spo2", "血氧" -> Triple("OXYGEN", "%", "血氧")
        else -> null
    }

    /**
     * 现场测量（**本机传感器优先，手表回落**）：先直接读本机身体传感器；本机没有该传感器才广播给手表(tracker)。
     * 返回 (读数结果, 来源说明)。dataCode = "HR"/"OXYGEN"。
     */
    suspend fun measure(ctx: Context, dataCode: String, type: String = "NOW", timeoutMs: Long = 45_000L): Pair<Result<Int>, String> {
        // ① 本机自带传感器（心率/血氧）——直接读，最快最准。measureLocalSensor 返 null = 本机没这个传感器。
        measureLocalSensor(ctx, dataCode, timeoutMs)?.let { return it to "本机传感器" }
        // ② 本机无该传感器 → 回落到手表（better health tracker 广播 API）
        return measureViaTracker(ctx, dataCode, type, timeoutMs) to "手表"
    }

    /**
     * 直接读**本机**身体传感器现测。本机无此传感器 → 返回 **null**（交给调用方回落手表）。
     * 心率走标准 `TYPE_HEART_RATE`(需 BODY_SENSORS 权限)；血氧无公开类型，按厂商传感器名/类型串探测(多数机型没有→null)。
     */
    suspend fun measureLocalSensor(ctx: Context, dataCode: String, timeoutMs: Long = 25_000L): Result<Int>? {
        val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return null
        val sensor = when (dataCode) {
            "HR" -> sm.getDefaultSensor(Sensor.TYPE_HEART_RATE)
            "OXYGEN" -> sm.getSensorList(Sensor.TYPE_ALL).firstOrNull {
                val s = (runCatching { it.stringType }.getOrNull() ?: "") + " " + (it.name ?: "")
                s.contains("spo2", true) || s.contains("oxygen", true) || s.contains("血氧")
            }
            else -> null
        } ?: return null   // 本机没有该传感器 → null，让调用方回落手表
        if (dataCode == "HR" && ContextCompat.checkSelfPermission(ctx, "android.permission.BODY_SENSORS") != PackageManager.PERMISSION_GRANTED)
            return Result.failure(SecurityException("需要「身体传感器」权限：在系统设置里授予 Arix 后再测"))
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result<Int>> { cont ->
                lateinit var listener: SensorEventListener
                listener = object : SensorEventListener {
                    override fun onSensorChanged(e: SensorEvent) {
                        if (!cont.isActive) return
                        // 没接触/不可靠的帧忽略，继续等有效读数（手机端 HR 要贴住传感器）
                        if (e.accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT || e.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE) return
                        val v = e.values.firstOrNull()?.toInt() ?: return
                        if (v > 0) { runCatching { sm.unregisterListener(listener) }; cont.resume(Result.success(v)) }
                    }
                    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
                }
                cont.invokeOnCancellation { runCatching { sm.unregisterListener(listener) } }
                if (!sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL))
                    cont.resume(Result.failure(IllegalStateException("本机传感器注册失败")))
            }
        } ?: Result.failure(IllegalStateException("本机传感器测量超时（贴紧传感器/戴好后再试）"))
    }

    /**
     * 回落路径：广播给手表(better health tracker)现测。成功返回读数(Int)，失败返回带原因的异常。
     * @param type "NOW"(尽力测) / "IMMEDIATELY"(抢占后台优先测) / "AUTO"(有缓存先给)
     */
    suspend fun measureViaTracker(ctx: Context, dataCode: String, type: String = "NOW", timeoutMs: Long = 45_000L): Result<Int> {
        if (!isTrackerInstalled(ctx)) return Result.failure(IllegalStateException("本机无该传感器，且未安装「better health tracker」回落测量"))
        val app = ctx.applicationContext
        val result = withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<Result<Int>> { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        if (intent == null || !cont.isActive) return
                        // 若回复带了 DATA，只认与本次请求同指标的（防串到后台连续测的回复）
                        intent.getStringExtra("DATA")?.let { if (it.isNotBlank() && it != dataCode) return }
                        val err = intent.getStringExtra("ERROR") ?: "NONE"
                        if (err != "NONE") { cont.resume(Result.failure(IllegalStateException(errText(err)))); return }
                        // 只认带 RESULT 的**最终稳定值**（onStable）；测量过程中的 onInstant 帧只带 RESULT_INSTANT，
                        // 忽略它、继续等——否则第一个过程帧就把协程当成「没读数」提前结束了。
                        if (!intent.hasExtra("RESULT")) return
                        val v = intent.getIntExtra("RESULT", Int.MIN_VALUE)
                            .takeIf { it != Int.MIN_VALUE } ?: intent.getStringExtra("RESULT")?.trim()?.toIntOrNull()
                        if (v == null || v <= 0) { cont.resume(Result.failure(IllegalStateException("未取到有效读数"))); return }
                        cont.resume(Result.success(v))
                    }
                }
                // API 33+ 收外部 App 广播必须显式声明可导出（androidx 会按版本填 flag）
                ContextCompat.registerReceiver(app, receiver, IntentFilter(ACTION_DATA_REPLY), ContextCompat.RECEIVER_EXPORTED)
                cont.invokeOnCancellation { runCatching { app.unregisterReceiver(receiver) } }
                // 显式指到 APIReceiver：manifest 注册的接收器在 API 26+ 收不到隐式广播，必须指定组件
                runCatching {
                    app.sendBroadcast(Intent(ACTION_GET_DATA).apply {
                        component = ComponentName(TRACKER_PKG, API_RECEIVER)
                        putExtra("DATA", dataCode); putExtra("TYPE", type)
                    })
                }.onFailure { if (cont.isActive) cont.resume(Result.failure(it)) }
            }
        }
        return result ?: Result.failure(IllegalStateException("测量超时（未佩戴/设备离线/未开启传感器 API？）"))
    }

    private fun errText(code: String): String = when (code) {
        "BUSY" -> "设备忙（正在测别的），稍后再试"
        "HARDWARE" -> "传感器硬件不可用"
        "FALL" -> "未检测到佩戴，戴好手表再测"
        "PERMISSION_DENIED" -> "被拒：请在 tracker 里开启「传感器 API」"
        "WRONG_EXTRA" -> "请求参数不对"
        else -> "测量失败（$code）"
    }

    // ---- 单条读取（返回一行游标的指定列） ----

    private fun readInt(ctx: Context, path: String, col: String): Int? {
        for (auth in AUTHORITIES) {
            try {
                ctx.contentResolver.query(Uri.parse("content://$auth/$path"), null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex(col)
                        if (idx >= 0) return c.getInt(idx)
                    }
                }
            } catch (_: SecurityException) {
                // v2 未授权 → 试下一个 authority(v1)
            } catch (_: Exception) {
                // provider 不存在 / tracker 未装 → 试下一个
            }
        }
        return null
    }

    private fun readHeartRate(ctx: Context): Pair<Int, Long>? {
        for (auth in AUTHORITIES) {
            try {
                ctx.contentResolver.query(Uri.parse("content://$auth/last/heartrate"), null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val hr = c.getColumnIndex("heartrate").takeIf { it >= 0 }?.let { c.getInt(it) } ?: 0
                        val unix = c.getColumnIndex("unix").takeIf { it >= 0 }?.let { c.getLong(it) } ?: 0L
                        if (hr > 0) return hr to unix
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {}
        }
        return gbHeartRate(ctx) ?: HealthConnectSource.cachedHeartRate()   // better-health 无 → Gadgetbridge → Health Connect
    }

    private fun todaySteps(ctx: Context): Int? = (readInt(ctx, "today/steps", "total_steps")?.takeIf { it >= 0 }) ?: gbSteps(ctx) ?: HealthConnectSource.cachedSteps()
    private fun lastOxygen(ctx: Context): Int? = readInt(ctx, "last/oxygen", "bloodoxygen")?.takeIf { it in 1..100 } ?: HealthConnectSource.cachedOxygen()

    /** 今晚睡眠总时长（分钟），无则 null。sleep/<当日起点秒>。 */
    private fun sleepMinutes(ctx: Context): Int? {
        val dayStartSec = startOfTodaySec()
        for (auth in AUTHORITIES) {
            try {
                ctx.contentResolver.query(Uri.parse("content://$auth/sleep/$dayStartSec"), null, null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        val idx = c.getColumnIndex("total_sec")
                        if (idx >= 0) { val s = c.getLong(idx); if (s > 0) return (s / 60L).toInt() }
                    }
                }
            } catch (_: SecurityException) {
            } catch (_: Exception) {}
        }
        return gbSleepMinutes(ctx) ?: HealthConnectSource.cachedSleepMinutes()   // better-health 无 → Gadgetbridge → Health Connect
    }

    // ---- 对外 ----

    // snapshot 短缓存：注入在发送时(可能主线程)调用，避免每条消息都跨进程 query（值本身分钟级才变）
    @Volatile private var snapCache: String? = null
    @Volatile private var snapAt: Long = 0L
    private const val SNAP_TTL_MS = 60_000L

    /** 注入用：简洁数据串（如「今日步数 8234，心率 72 bpm，血氧 98%」）；无可用数据返回 null。带 60s 缓存。 */
    fun snapshot(ctx: Context): String? {
        if (!isEnabled(ctx)) return null
        HealthConnectSource.ensureFresh(ctx)   // 非阻塞：过期就后台拉一次健康连接，写进缓存供下次读
        val now = System.currentTimeMillis()
        if (now - snapAt < SNAP_TTL_MS) return snapCache
        val parts = ArrayList<String>(3)
        todaySteps(ctx)?.let { if (it > 0) parts.add("今日步数 $it") }
        readHeartRate(ctx)?.let { (hr, unix) ->
            if (unix <= 0L || System.currentTimeMillis() - unix * 1000L <= HR_FRESH_MS) parts.add("心率 $hr bpm")
        }
        lastOxygen(ctx)?.let { parts.add("血氧 $it%") }
        return parts.joinToString("，").ifBlank { null }.also { snapCache = it; snapAt = now }
    }

    /** 开关变化时清缓存，让预览/注入立即反映。 */
    fun invalidate() { snapAt = 0L; snapCache = null }

    /** 工具 / 设置用：完整状态说明（含未开启 / 未装 / 未授权 / 无数据 + 睡眠）。 */
    fun report(ctx: Context): String {
        if (!isEnabled(ctx)) return "健康数据未开启（可在设置 → 健康数据打开）"
        HealthConnectSource.ensureFresh(ctx)
        // 三个源都没有才算「没接上」：better-health 未装 且 Gadgetbridge 导出库找不到 且 健康连接不可用
        if (!isTrackerInstalled(ctx) && gbDbFile(ctx) == null && !HealthConnectSource.available(ctx))
            return "未检测到健康数据源：装「better health tracker」后台记录、或在 Gadgetbridge 开「数据库自动导出」、或用系统「健康连接」(小米/vivo/Keep 等往它写数据)后在下方授权"
        val parts = ArrayList<String>(4)
        todaySteps(ctx)?.let { parts.add("今日步数 $it") }
        readHeartRate(ctx)?.let { (hr, unix) ->
            val age = if (unix > 0L) (System.currentTimeMillis() - unix * 1000L) / 60000L else -1L
            parts.add(if (age in 0..600) "心率 $hr bpm（${age}分钟前）" else "心率 $hr bpm")
        }
        lastOxygen(ctx)?.let { parts.add("血氧 $it%") }
        sleepMinutes(ctx)?.let { parts.add("昨晚睡眠 ${it / 60}小时${it % 60}分") }
        if (parts.isEmpty()) {
            return if (hasReadPerm(ctx)) "已连接但暂无读数（确认 tracker 正在后台记录；或在其设置里开启数据开关）"
            else "未授予读取权限（在设置 → 健康数据里授权，或安装/打开 tracker）"
        }
        return parts.joinToString("；")
    }

    /**
     * 「关心点」：据当前健康数据+时间，给一句**客观观察**（供主动消息/日记参考），无则 null。
     * 只描述事实、不替 AI 规定怎么关心（见 prompt-inject-data-only）。按严重度取第一个命中。
     */
    fun concern(ctx: Context): String? {
        if (!isEnabled(ctx)) return null
        HealthConnectSource.ensureFresh(ctx)
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        // 深夜还醒着（近 30 分钟内还有心率读数）
        readHeartRate(ctx)?.let { (hr, unix) ->
            if ((hour >= 23 || hour < 5) && unix > 0L && System.currentTimeMillis() - unix * 1000L <= 30 * 60_000L)
                return "现在 ${hour} 点了，他好像还醒着（刚测到心率 $hr bpm）"
        }
        // 早上：昨晚睡得少
        if (hour in 6..11) sleepMinutes(ctx)?.let { if (it in 1 until 300) return "他昨晚只睡了约 ${it / 60} 小时 ${it % 60} 分" }
        // 下午/晚上：今天步数很少（久坐）
        if (hour >= 14) todaySteps(ctx)?.let { if (it in 0 until 1500) return "今天他只走了 $it 步，久坐了大半天" }
        return null
    }

    // ============================================================
    // 第二健康源：Gadgetbridge 自动导出的 SQLite 库（借鉴「华为手表健康快照读取器」，作者 42100214lei-design）
    // 只读打开、按设备表名 introspect、当天求和步数/取最近心率/RAW_KIND 判睡眠。
    // 仅当 better-health-tracker 那路取不到时兜底；查不到/库不存在都优雅降级。
    // ============================================================
    fun gadgetbridgePath(ctx: Context): String = prefs(ctx).getString(K_GB_PATH, "") ?: ""
    fun setGadgetbridgePath(ctx: Context, p: String) { prefs(ctx).edit().putString(K_GB_PATH, p.trim()).apply(); invalidate() }

    private fun gbDbFile(ctx: Context): String? {
        gadgetbridgePath(ctx).takeIf { it.isNotBlank() && File(it).exists() }?.let { return it }
        return GB_DEFAULTS.firstOrNull { File(it).exists() }
    }

    private inline fun <T> withGbDb(ctx: Context, block: (SQLiteDatabase) -> T?): T? {
        val path = gbDbFile(ctx) ?: return null
        var db: SQLiteDatabase? = null
        return try {
            db = SQLiteDatabase.openDatabase(path, null, SQLiteDatabase.OPEN_READONLY)
            block(db)
        } catch (_: Exception) { null } finally { try { db?.close() } catch (_: Exception) {} }
    }

    /** 选「最近有数据」的活动样本表（各设备表名不同：HUAWEI_/HUAMI_EXTENDED_/MI_BAND_..._ACTIVITY_SAMPLE）。 */
    private fun gbSampleTable(db: SQLiteDatabase): String? {
        val tables = try {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE '%ACTIVITY_SAMPLE'", null).use { c ->
                val l = ArrayList<String>(); while (c.moveToNext()) l.add(c.getString(0)); l
            }
        } catch (_: Exception) { return null }
        // 取 TIMESTAMP 最大的那张表（多设备时选最近在用的）
        return tables.maxByOrNull { t ->
            try { db.rawQuery("SELECT MAX(TIMESTAMP) FROM \"$t\"", null).use { if (it.moveToFirst()) it.getLong(0) else 0L } } catch (_: Exception) { 0L }
        }
    }

    private fun gbLong(db: SQLiteDatabase, sql: String, args: Array<String>? = null): Long? = try {
        db.rawQuery(sql, args).use { if (it.moveToFirst() && !it.isNull(0)) it.getLong(0) else null }
    } catch (_: Exception) { null }

    private fun gbSteps(ctx: Context): Int? = withGbDb(ctx) { db ->
        val t = gbSampleTable(db) ?: return@withGbDb null
        val from = startOfTodaySec()
        gbLong(db, "SELECT SUM(STEPS) FROM \"$t\" WHERE TIMESTAMP>=?", arrayOf(from.toString()))?.toInt()?.takeIf { it > 0 }
    }

    private fun gbHeartRate(ctx: Context): Pair<Int, Long>? = withGbDb(ctx) { db ->
        val t = gbSampleTable(db) ?: return@withGbDb null
        try {
            db.rawQuery("SELECT HEART_RATE, TIMESTAMP FROM \"$t\" WHERE HEART_RATE NOT IN (0,255,-1) ORDER BY TIMESTAMP DESC LIMIT 1", null).use { c ->
                if (c.moveToFirst()) { val hr = c.getInt(0); if (hr in 1..250) hr to c.getLong(1) else null } else null
            }
        } catch (_: Exception) { null }
    }

    private fun gbSleepMinutes(ctx: Context): Int? = withGbDb(ctx) { db ->
        val t = gbSampleTable(db) ?: return@withGbDb null
        // 近 24h 内、RAW_KIND 属睡眠档的样本数≈睡眠分钟（每样本约 1 分钟）
        val from = (System.currentTimeMillis() / 1000L) - 86_400L
        gbLong(db, "SELECT COUNT(*) FROM \"$t\" WHERE TIMESTAMP>=? AND RAW_KIND IN (1,2,3,4,112,121,122,123)", arrayOf(from.toString()))?.toInt()?.takeIf { it > 0 }
    }

    private fun startOfTodaySec(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0); cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0); cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis / 1000L
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}
