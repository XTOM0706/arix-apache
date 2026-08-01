package com.arix.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Location
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arix.tool.CronSpec
import com.arix.tool.UsageStatsTool
import com.arix.tool.WorkflowEngine
import com.arix.tool.WorkflowStore
import com.arix.tool.WorkflowTrigger
import com.arix.tool.WorkflowTriggerType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * 一类触发器现在缺的权限。[settings] 是跳系统设置页的 Intent，[runtime] 是要走 requestPermissions 的运行时权限；
 * 两个都空表示「只能说给用户听，App 这边没有能跳的地方」。UI 据此显示「缺什么 + 怎么给」，
 * **绝不允许**权限没给却静默不触发——那是最难排查的一类「设了但没反应」。
 */
data class TriggerPermissionGap(
    val what: String,
    val how: String = "",
    val settings: Intent? = null,
    val runtime: List<String> = emptyList(),
)

// ============================================================
// 工作流自动触发注册中心
//
// 引擎（WorkflowEngine）本来就是完整的，缺的只是「谁来按下运行」。这一层就是那个入口：
// 按已保存工作流里配的触发器，把需要的系统监听挂上，事件来了调 WorkflowEngine.run。
//
// ## 三条设计约束（手表设备，耗电是第一约束）
// 1. **按需挂载**：没有任何工作流用某个触发器，那个监听就一定不挂。BroadcastReceiver 的
//    IntentFilter 是按「当前实际用到的 action 集合」现算的；WiFi 的 NetworkCallback、地理围栏的
//    proximity alert、App 使用采样、定时的 WorkManager 任务同理，用不到就摘掉/取消。
//    全注册再在回调里 if 一下看似省事，代价是每次亮屏/插拔都要唤醒进程一次——这正是这一层最该避免的开销。
//    这条规矩对**新加的地理围栏和 App 前台时长**尤其要紧：它俩最容易退化成常驻轮询。
// 2. **防抖**：开关屏一晚上能来几十次，每次真跑一遍工作流就是几十次大模型调用（烧电烧钱）。
//    每个工作流有自己的最小间隔（[WorkflowTrigger.minGapMin]），窗口内的重复事件直接丢。
//    时间戳落 SharedPreferences 而不是内存——进程被杀重建后不能把防抖窗口一起清零。
// 3. **炸了不连累别人**：每次触发独立 try/catch，一个工作流出错不影响监听器和别的工作流；
//    CancellationException 必须重抛（否则协程取消会被当成普通失败吞掉，见项目 STOP 相关教训）。
//
// ## 四类触发器的取电方式（为什么这么选）
// - **广播类**（亮息屏/电量/电源/耳机/蓝牙 ACL）：系统推事件，零常驻成本。首选。
// - **定时类**（每 N 分钟 / 每天 / cron）：WorkManager 一次性任务 + 跑完重排，唤醒次数 == 实际触发次数。
// - **地理围栏**：走 [LocationManager.addProximityAlert]，也就是**系统侧围栏**（有硬件围栏的机器直接落到
//   modem 上），我们一次定位都不发起。这是唯一能在手表上做到「常年开着还不掉电」的做法。
//   自己按周期粗查是明确否掉的方案：要么周期长到没用（半小时才发现到家），要么周期短到每次都要点亮
//   GPS/网络定位——手表电池扛不住。周期粗查只留作**兜底**，且默认只读别人已经产生的定位缓存（零射频开销），
//   仅在围栏挂载失败时才允许真的去要一次定位（见 [checkGeofences]）。
// - **App 前台**：UsageStatsManager **没有任何回调**，只能取样。所以卡两道：① 只有真的有 App 类触发器
//   才采样；② **只在亮屏时采样**（App 切前台这件事本来就只发生在亮屏时），息屏立刻停。
//   手表一天绝大多数时间是息屏的，这一条把成本压到了「你在看表的那几分钟里每 30 秒读一次系统事件表」。
//
// ## 权限说明（AndroidManifest 一行都没改）
// 屏幕开关/电量/电源/耳机/蓝牙 ACL 走**运行时动态注册**，网络走 registerNetworkCallback，
// 定时走 WorkManager，围栏用已声明的 ACCESS_FINE_LOCATION，App 使用用已声明的 PACKAGE_USAGE_STATS，
// 通知走已有的 NotificationListenerService。用户没给的那几个特殊权限由 [permissionGap] 报给 UI。
// ============================================================
object WorkflowTriggers {

    private const val PREF = "xtom_workflow_triggers"
    private const val WORK_TIMER = "xtom_workflow_timer"
    private const val K_LAST_FIRE = "last_fire:"     // + 工作流名 → 上次触发时间戳
    private const val K_LAST_NOTE = "last_note:"     // + 工作流名 → 上次触发的结果摘要（给设置页显示）
    private const val K_GEO = "geo_in:"              // + 工作流名 → 上次已知的围栏内外状态
    private const val K_BOOT_ID = "boot_id"          // 上次已经报过「开机」的那一次开机（分钟粒度）

    /**
     * 「注册即回调」的静默窗。两个坑：
     * - ACTION_HEADSET_PLUG 是**粘性广播**，注册的一瞬间系统就把当前耳机状态补发一次；
     * - registerNetworkCallback 在已连着 WiFi 时也会立刻回一次 onAvailable。
     * 不挡的话每次冷启动/改配置都会白触发一轮工作流。开头这两秒内的事件一律当「状态回放」丢掉。
     * （围栏没走这条：它的「刚挂上就报当前状态」由 [K_GEO] 的 UNKNOWN 态吃掉，见 [onGeoState]。）
     */
    private const val SETTLE_MS = 2500L

    /** 同时最多跑几个被触发的工作流。手表上并发跑多个 = 一起抢网络和 CPU，还可能撞同一个工具。 */
    private const val MAX_CONCURRENT = 2

    /** App 前台采样周期。只在亮屏时跑；30 秒对「打开了某个 App」「用了超过 N 分钟」都够，再密只是白烧 CPU。 */
    private const val APP_POLL_MS = 30_000L

    /** 通知兜底轮询周期。只在**没有**实时钩子时才跑，见 [onNotificationPosted]。 */
    private const val NOTIF_POLL_MS = 20_000L

    /** 围栏兜底自查周期。系统围栏是主路，这个只是防「围栏没挂上/进程刚被拉起」的补漏，所以敢排得很稀。 */
    private const val GEO_CHECK_MS = 30 * 60_000L

    /** 兜底自查允许用多旧的定位缓存。比这更旧的缓存拿来判进出围栏只会误报。 */
    private const val GEO_CACHE_MAX_MS = 15 * 60_000L

    /** 进程在开机后这段时间内起来，就认为这次启动是「开机」引起的（[AdbBootReceiver] 没接线时的兜底）。 */
    private const val BOOT_FRESH_MS = 5 * 60_000L

    private const val GEO_UNKNOWN = 0
    private const val GEO_INSIDE = 1
    private const val GEO_OUTSIDE = 2

    /** 围栏 PendingIntent 用的私有广播。工作流名编在 data 的 ssp 里（scheme 见 [SCHEME_WF]）。 */
    private const val ACTION_PROXIMITY = "com.arix.app.action.WORKFLOW_PROXIMITY"
    private const val SCHEME_WF = "xtomwf"

    /** 触发执行不绑任何 UI 生命周期：SupervisorJob 保证一个工作流崩了不带塌整个 scope。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var started = false
    private var eventReceiver: BroadcastReceiver? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    /** 当前已挂上的广播 action 集合 / 触发器签名，用来判断「这次刷新到底需不需要动监听」。 */
    private var mountedActions: Set<String> = emptySet()
    private var mountedSignature: String = ""
    private var wifiMounted = false

    // --- 地理围栏 ---
    private var geoReceiverMounted = false
    private var mountedGeoSig: String = ""
    private val geoPendings = ArrayList<PendingIntent>()
    /** 系统围栏真的挂上了没有。没挂上时兜底自查才允许发起真实定位（见 [checkGeofences]）。 */
    @Volatile private var geoArmed = false

    // --- App 前台采样 ---
    private var appSampling = false
    private var appPollJob: Job? = null
    @Volatile private var fgPkg: String? = null
    @Volatile private var fgSince = 0L
    @Volatile private var fgLongFired = false
    @Volatile private var usageCursor = 0L

    // --- 通知 ---
    private var notifPollJob: Job? = null
    /** 有没有收到过 [onNotificationPosted] 的实时回调。收到过就把兜底轮询彻底停掉（见该方法注释）。 */
    @Volatile private var notifHookAlive = false

    /**
     * 事件路径上的**内存快照**，只当廉价预筛用（真正该不该跑仍以磁盘上的配置为准）。
     * 没有它的话，亮屏期间每 30 秒的一次 App 采样、每条通知，都要去读一次 workflows.json。
     */
    @Volatile private var appTriggers: List<Pair<String, WorkflowTrigger>> = emptyList()
    @Volatile private var notifTriggers: List<Pair<String, WorkflowTrigger>> = emptyList()

    @Volatile private var settleUntil = 0L
    private val running = java.util.Collections.synchronizedSet(HashSet<String>())

    /** 已处理过的通知指纹。实时钩子和兜底轮询可能同时看到同一条，靠它去重。 */
    private val notifSeen = object : LinkedHashMap<String, Boolean>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 120
    }

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---------------- 生命周期 ----------------

    /** App 启动时调一次：按已保存的工作流把该挂的监听挂上。没有任何触发器时**什么都不做**。 */
    fun init(context: Context) {
        started = true
        refresh(context)
        // 没接 AdbBootReceiver 时的兜底：进程在开机后几分钟内被拉起，那这次启动就是开机引起的。
        maybeFireBoot(context, requireFresh = true)
    }

    /**
     * 开机广播的接线点（由 [AdbBootReceiver] 在 ACTION_BOOT_COMPLETED 分支里调一行）。
     *
     * 为什么非要这一行不可：开机时先到的是 LOCKED_BOOT_COMPLETED，那会儿**用户还没解锁**，
     * filesDir（凭据加密区）读不到，[WorkflowStore] 只会读出一个空列表 → 那一轮什么监听都挂不上，
     * 而 XtomApp.onCreate 不会再跑第二遍。解锁后的 BOOT_COMPLETED 是唯一能把监听补挂回来的时机。
     * 不接这一行的话：开机类触发器靠 [init] 的兜底仍能工作，但**整套自动触发在重启后可能全哑**。
     */
    fun onBootCompleted(context: Context) {
        started = true
        val c = context.applicationContext
        // 调用方是 BroadcastReceiver.onReceive（主线程）：这里要读 workflows.json，一律丢 IO 协程。
        scope.launch {
            try {
                refresh(c)
                maybeFireBoot(c, requireFresh = false)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    /**
     * 工作流增删改后由 [WorkflowStore] 回调。init 之前是空操作（避免后台线程抢在初始化前挂监听）。
     *
     * **必须丢后台**：调用链是「UI 上敲一个字 → setTrigger → save（读+写 workflows.json）→ 这里」，
     * 而 [refresh] 自己还要再读一遍文件，编辑 cron/围栏坐标时更是每个字符都会走到
     * unregisterReceiver/registerReceiver + removeProximityAlert/addProximityAlert + WorkManager 排期。
     * 这一整串跑在主线程上，在手表上就是肉眼可见的卡顿甚至 ANR。
     * 并发安全：[refresh] 是 @Synchronized，且**在锁内重新读盘**，所以多次编辑排队执行后最终状态一定是最新的那份。
     */
    fun onStoreChanged(context: Context) {
        if (!started) return
        val c = context.applicationContext
        scope.launch {
            try { refresh(c) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {}
        }
    }

    /**
     * 重新算一遍「现在需要挂哪些监听」，只在真正变化时动手。
     * 输入框那种改动（触发输入/最小间隔/通知关键词）不影响挂载，靠 [signatureOf] 过滤掉，
     * 避免每敲一个字就重注册一遍监听。
     */
    @Synchronized
    fun refresh(context: Context) {
        val c = context.applicationContext
        val active = try { WorkflowStore.activeTriggers(c) } catch (_: Exception) { emptyList() }

        // 内存快照先更新：它不影响挂载，但要和磁盘保持同步（哪怕签名没变，关键词/包名可能改了）
        appTriggers = active.filter { it.second.type.isApp }
        notifTriggers = active.filter { it.second.type == WorkflowTriggerType.NOTIF_MATCH }

        val sig = signatureOf(active)
        if (sig == mountedSignature) return
        mountedSignature = sig

        val types = active.map { it.second.type }.toSet()

        // --- 1. 广播类：现算 action 集合，空了就整个摘掉 ---
        val actions = LinkedHashSet<String>()
        if (WorkflowTriggerType.SCREEN_ON in types) actions.add(Intent.ACTION_SCREEN_ON)
        if (WorkflowTriggerType.SCREEN_OFF in types) actions.add(Intent.ACTION_SCREEN_OFF)
        if (WorkflowTriggerType.BATTERY_LOW in types) actions.add(Intent.ACTION_BATTERY_LOW)
        if (WorkflowTriggerType.POWER_CONNECTED in types) actions.add(Intent.ACTION_POWER_CONNECTED)
        if (WorkflowTriggerType.POWER_DISCONNECTED in types) actions.add(Intent.ACTION_POWER_DISCONNECTED)
        // 插和拔是同一条广播，靠 extra "state" 区分（0=拔出 1=插入）
        if (WorkflowTriggerType.HEADSET_PLUGGED in types || WorkflowTriggerType.HEADSET_UNPLUGGED in types) {
            actions.add(Intent.ACTION_HEADSET_PLUG)
        }
        if (WorkflowTriggerType.BT_CONNECTED in types) actions.add(BluetoothDevice.ACTION_ACL_CONNECTED)
        if (WorkflowTriggerType.BT_DISCONNECTED in types) actions.add(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        // App 采样要跟着屏幕走（息屏就没有 App 切换），所以顺带把亮息屏广播挂上——
        // 这两条广播本来就是系统推的，比自己起个定时器去问「屏幕亮着没」便宜得多。
        val needApp = types.any { it.isApp }
        if (needApp) { actions.add(Intent.ACTION_SCREEN_ON); actions.add(Intent.ACTION_SCREEN_OFF) }
        if (actions != mountedActions) remountReceiver(c, actions)

        // --- 2. 网络类：只有真有 WiFi 触发器才注册 NetworkCallback ---
        val needWifi = WorkflowTriggerType.WIFI_CONNECTED in types || WorkflowTriggerType.WIFI_DISCONNECTED in types
        if (needWifi != wifiMounted) if (needWifi) mountNetwork(c) else unmountNetwork(c)

        // --- 3. 地理围栏：圆心/半径变了才重挂（否则编辑框每敲一下都要重新注册系统围栏） ---
        val geo = active.filter { it.second.type.isGeo }
        val geoSig = geo.sortedBy { it.first }.joinToString("|") { (n, t) -> "$n@${t.lat},${t.lon},${t.radiusM}" }
        // geo.isNotEmpty() && !geoArmed：上次因为「定位权限还没给」而没挂上，这次（用户授权后回来）要补挂
        if (geoSig != mountedGeoSig || (geo.isNotEmpty() && !geoArmed)) { mountedGeoSig = geoSig; remountGeofences(c, geo) }

        // --- 4. App 前台采样：没人用就一个采样都不做 ---
        setAppSampling(c, needApp)

        // --- 5. 通知匹配：有实时钩子就不轮询，没有才起兜底轮询 ---
        setNotifWatching(c, notifTriggers.isNotEmpty())

        // --- 6. 定时类：给「最近的一次到点」排一个一次性任务 ---
        val timed = active.filter { it.second.type.isTimed }
        // 新出现的定时触发器先落一个锚点，否则 nextDueAt 没有起算点，
        // 每次冷启动都会把「每 N 分钟」的下一次往后推，永远轮不到跑。
        val p = prefs(c)
        timed.forEach { (name, _) -> if (!p.contains(K_LAST_FIRE + name)) p.edit().putLong(K_LAST_FIRE + name, System.currentTimeMillis()).apply() }
        rescheduleTimer(c)
    }

    /**
     * 只把「影响挂不挂/什么时候挂」的字段算进签名。
     * input/minGap/关键词/包名/蓝牙设备名都是**触发那一刻**才读的，改它们不需要重注册；
     * 而 cron 和围栏圆心半径直接决定排期与系统围栏，必须算进来。
     */
    private fun signatureOf(active: List<Pair<String, WorkflowTrigger>>): String =
        active.sortedBy { it.first }.joinToString("|") { (n, t) ->
            "$n:${t.type.id}:${t.intervalMin}:${t.hour}:${t.minute}:${t.cron}:${t.lat},${t.lon},${t.radiusM}"
        }

    // ---------------- 广播监听 ----------------

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val c = context?.applicationContext ?: return
            val action = intent?.action ?: return
            // ⚠ onReceive 跑在主线程且必须马上返回：这里只做纯内存判断，一切读文件的活儿都在 fireAsync 里丢协程。
            val type = when (action) {
                Intent.ACTION_SCREEN_ON -> { onScreenChanged(c, true); WorkflowTriggerType.SCREEN_ON }
                Intent.ACTION_SCREEN_OFF -> { onScreenChanged(c, false); WorkflowTriggerType.SCREEN_OFF }
                Intent.ACTION_BATTERY_LOW -> WorkflowTriggerType.BATTERY_LOW
                Intent.ACTION_POWER_CONNECTED -> WorkflowTriggerType.POWER_CONNECTED
                Intent.ACTION_POWER_DISCONNECTED -> WorkflowTriggerType.POWER_DISCONNECTED
                Intent.ACTION_HEADSET_PLUG -> {
                    // 粘性广播：刚注册时补发的那一条不是「用户刚插上」，丢掉（见 SETTLE_MS）
                    if (System.currentTimeMillis() < settleUntil) return
                    if (intent.getIntExtra("state", 0) == 1) WorkflowTriggerType.HEADSET_PLUGGED else WorkflowTriggerType.HEADSET_UNPLUGGED
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> { fireBt(c, WorkflowTriggerType.BT_CONNECTED, intent); return }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> { fireBt(c, WorkflowTriggerType.BT_DISCONNECTED, intent); return }
                else -> return
            }
            fireAsync(c, type)
        }
    }

    private fun remountReceiver(c: Context, actions: Set<String>) {
        // 先摘干净再挂：filter 不能改，只能重注册
        eventReceiver?.let { r -> try { c.unregisterReceiver(r) } catch (_: Exception) {} }
        eventReceiver = null
        mountedActions = actions
        if (actions.isEmpty()) return   // 没人用 → 一个监听都不留
        try {
            val filter = IntentFilter().apply { actions.forEach { addAction(it) } }
            settleUntil = System.currentTimeMillis() + SETTLE_MS
            ContextCompat.registerReceiver(c, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
            eventReceiver = receiver
        } catch (_: Exception) {
            mountedActions = emptySet()
            // 签名在 refresh 里已经先提交了：这儿不一并作废的话，下一次 refresh 会在「签名没变」那关直接返回，
            // 于是这次挂失败的广播监听再也没机会重挂——事件类触发器一直哑到用户下次去改配置。
            mountedSignature = ""
        }
    }

    // ---------------- 蓝牙 ----------------

    /**
     * 指定蓝牙设备连上/断开。设备名留空 = 任意设备。
     *
     * 名字在 API 31+ 需要 BLUETOOTH_CONNECT（清单已声明，但要用户授权）；没授权时 [BluetoothDevice.getName]
     * 抛 SecurityException，此时退回 MAC 地址匹配，而不是整条事件丢掉。
     */
    @SuppressLint("MissingPermission")
    private fun fireBt(c: Context, type: WorkflowTriggerType, intent: Intent) {
        @Suppress("DEPRECATION")
        val dev = try { intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) } catch (_: Exception) { null }
        val addr = try { dev?.address.orEmpty() } catch (_: Exception) { "" }
        val name = try { dev?.name.orEmpty() } catch (_: Exception) { "" }
        val shown = name.ifBlank { addr }
        fireAsync(c, type, mapOf("device" to shown, "address" to addr, "default" to shown)) { _, t ->
            t.device.isBlank() || name.contains(t.device, true) || addr.equals(t.device.trim(), true)
        }
    }

    // ---------------- 网络监听 ----------------

    private fun mountNetwork(c: Context) {
        try {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    // 注册时若已连着 WiFi，系统会立刻回一次——那是状态回放不是「刚连上」
                    if (System.currentTimeMillis() < settleUntil) return
                    fireAsync(c, WorkflowTriggerType.WIFI_CONNECTED)
                }
                override fun onLost(network: Network) = fireAsync(c, WorkflowTriggerType.WIFI_DISCONNECTED)
            }
            settleUntil = maxOf(settleUntil, System.currentTimeMillis() + SETTLE_MS)
            cm.registerNetworkCallback(req, cb)
            netCallback = cb
            wifiMounted = true
        } catch (_: Exception) { wifiMounted = false }
    }

    private fun unmountNetwork(c: Context) {
        val cb = netCallback ?: run { wifiMounted = false; return }
        try {
            val cm = c.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            cm?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        netCallback = null
        wifiMounted = false
    }

    // ---------------- 地理围栏 ----------------

    private val geoReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val c = context?.applicationContext ?: return
            if (intent?.action != ACTION_PROXIMITY) return
            val name = intent.data?.schemeSpecificPart ?: return
            val entering = intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, true)
            scope.launch {   // onReceive 里不做文件 IO
                val t = try { WorkflowStore.trigger(c, name) } catch (_: Exception) { return@launch }
                try { onGeoState(c, name, t, entering) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) {}
            }
        }
    }

    /**
     * 围栏事件的 PendingIntent。
     *
     * ⚠ **必须 MUTABLE**：系统报围栏时是 `pendingIntent.send(ctx, 0, fillInIntent)`，进/出的那个
     * [LocationManager.KEY_PROXIMITY_ENTERING] 是塞在 fillIn 里的——IMMUTABLE 会把 fillIn 整个丢掉，
     * 结果就是收得到广播却永远分不清是进还是出。
     * 可变带来的重定向风险用 setPackage 钉死在本 App 内，且这个 PendingIntent 只交给系统 LocationManager。
     * 工作流名编在 data 的 ssp 里（不能放 extra：extra 同样会被 IMMUTABLE/fillIn 规则搅乱）。
     */
    private fun geoPendingIntent(c: Context, name: String): PendingIntent {
        val i = Intent(ACTION_PROXIMITY)
            .setPackage(c.packageName)
            .setData(Uri.fromParts(SCHEME_WF, name, null))
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(c, name.hashCode(), i, flags)
    }

    @SuppressLint("MissingPermission")
    private fun remountGeofences(c: Context, geo: List<Pair<String, WorkflowTrigger>>) {
        val lm = c.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        // 先全摘：围栏是按 PendingIntent 认的，改了圆心必须先撤旧的，否则老围栏会一直留在系统里报事件
        geoPendings.forEach { pi -> try { lm?.removeProximityAlert(pi) } catch (_: Exception) {}; try { pi.cancel() } catch (_: Exception) {} }
        geoPendings.clear()
        geoArmed = false
        if (geo.isEmpty()) {
            if (geoReceiverMounted) { try { c.unregisterReceiver(geoReceiver) } catch (_: Exception) {} ; geoReceiverMounted = false }
            return
        }
        // 权限没给就一个围栏都不挂（挂了也不会报）。UI 那边由 permissionGap 明确告诉用户缺什么。
        if (lm == null || !LocationSignals.hasPermission(c)) return
        if (!geoReceiverMounted) {
            try {
                // 单独一个 filter：带 dataScheme 的 filter 会让「没有 data 的 intent」全都匹配不上，
                // 不能和亮息屏那套共用一个 receiver。
                val f = IntentFilter(ACTION_PROXIMITY).apply { addDataScheme(SCHEME_WF) }
                ContextCompat.registerReceiver(c, geoReceiver, f, ContextCompat.RECEIVER_NOT_EXPORTED)
                geoReceiverMounted = true
            } catch (_: Exception) { return }
        }
        geo.forEach { (name, t) ->
            try {
                val pi = geoPendingIntent(c, name)
                // expiration = -1：永不过期。围栏本身在系统侧维护，我们这边零轮询。
                lm.addProximityAlert(t.lat, t.lon, t.radiusM.toFloat(), -1L, pi)
                geoPendings.add(pi)
                geoArmed = true
            } catch (_: Exception) {}
        }
    }

    /**
     * 围栏内外状态变化的统一入口（系统围栏和兜底自查都汇到这儿，靠落盘的状态互相去重）。
     * 第一次拿到状态（UNKNOWN → 有）只记录不触发：那是「刚挂上时的状态回放」，不是用户刚走到这儿。
     */
    private suspend fun onGeoState(c: Context, name: String, t: WorkflowTrigger, inside: Boolean) {
        if (!t.active || !t.type.isGeo) return
        val state = if (inside) GEO_INSIDE else GEO_OUTSIDE
        val p = prefs(c)
        val prev = p.getInt(K_GEO + name, GEO_UNKNOWN)
        if (prev == state) return
        p.edit().putInt(K_GEO + name, state).apply()
        if (prev == GEO_UNKNOWN) return
        val want = if (inside) WorkflowTriggerType.GEOFENCE_ENTER else WorkflowTriggerType.GEOFENCE_EXIT
        if (t.type != want) return
        val where = "%.5f, %.5f".format(t.lat, t.lon)
        fireMatching(c, want, mapOf("place" to where, "default" to (if (inside) "到达 " else "离开 ") + where)) { n, _ -> n == name }
    }

    /**
     * 围栏兜底自查（挂在已有的 WorkManager 排期上，不额外起闹钟）。
     *
     * 系统围栏正常挂上时：**只读别人已经产生的定位缓存**，一次射频都不开——纯粹是防「进程刚被拉起、
     * 围栏事件在进程死的那段时间发生过」这种漏报。缓存太旧就直接放弃，宁可漏也不点亮 GPS。
     * 只有围栏压根没挂上（设备不支持/权限被回收）时，才退化成每 30 分钟真的要一次定位。
     */
    private suspend fun checkGeofences(c: Context) {
        val geo = try { WorkflowStore.activeTriggers(c).filter { it.second.type.isGeo } } catch (_: Exception) { return }
        if (geo.isEmpty() || !LocationSignals.hasPermission(c)) return
        val loc: Location? =
            if (geoArmed) LocationSignals.lastKnown(c)?.takeIf { System.currentTimeMillis() - it.time < GEO_CACHE_MAX_MS }
            else LocationSignals.current(c)
        if (loc == null) return
        for ((name, t) in geo) {
            try {
                val d = FloatArray(1)
                Location.distanceBetween(loc.latitude, loc.longitude, t.lat, t.lon, d)
                // 迟滞：已经在里面时按 1.2 倍半径才算出去，免得定位抖动在边界上来回刷触发
                val prev = prefs(c).getInt(K_GEO + name, GEO_UNKNOWN)
                val inside = if (prev == GEO_INSIDE) d[0] <= t.radiusM * 1.2f else d[0] <= t.radiusM.toFloat()
                onGeoState(c, name, t, inside)
            } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        }
    }

    // ---------------- App 前台（UsageStatsManager 采样） ----------------

    private fun screenOn(c: Context): Boolean = try {
        (c.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
    } catch (_: Exception) { true }

    private fun setAppSampling(c: Context, want: Boolean) {
        if (want == appSampling) return
        appSampling = want
        if (!want) { stopAppPoll(); return }
        if (screenOn(c)) startAppPoll(c)   // 息屏就先不起，等亮屏广播
    }

    private fun onScreenChanged(c: Context, on: Boolean) {
        if (!appSampling) return
        if (on) startAppPoll(c) else stopAppPoll()
    }

    private fun startAppPoll(c: Context) {
        if (!appSampling || appPollJob?.isActive == true) return
        appPollJob = scope.launch {
            // 第一轮是**播种**：只把「现在前台是谁」记下来，不当成刚打开。
            // 不这么做的话每次亮屏都会把当前 App 误报成「刚启动」。
            usageCursor = 0L
            try { pollUsage(c, seed = true) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            while (isActive) {
                delay(APP_POLL_MS)
                try { pollUsage(c, seed = false) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
        }
    }

    private fun stopAppPoll() {
        appPollJob?.cancel()
        appPollJob = null
        fgPkg = null
        fgSince = 0L
        fgLongFired = false
        usageCursor = 0L
    }

    // MOVE_TO_FOREGROUND/BACKGROUND 在 API29 起改叫 ACTIVITY_RESUMED/PAUSED（值相同）。
    // minSdk 是 26，用旧名才在全部机型上都编得过、跑得通，所以这里主动吃掉 deprecation。
    @Suppress("DEPRECATION")
    private suspend fun pollUsage(c: Context, seed: Boolean) {
        if (!UsageStatsTool.hasAccess(c)) return          // 没授权：不采样、不报错，UI 那边已经在喊了
        val usm = c.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return
        val now = System.currentTimeMillis()
        val begin = if (usageCursor > 0) usageCursor else now - 60_000L
        val events = try { usm.queryEvents(begin, now) } catch (_: Exception) { null } ?: return
        usageCursor = now
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            try { events.getNextEvent(e) } catch (_: Exception) { break }
            when (e.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> switchFg(c, e.packageName, e.timeStamp, seed)
                UsageEvents.Event.MOVE_TO_BACKGROUND -> if (e.packageName == fgPkg) switchFg(c, null, e.timeStamp, seed)
            }
        }
        // 前台时长：一段前台里只报一次，别每 30 秒报一次
        val p = fgPkg
        if (!seed && p != null && !fgLongFired && fgSince > 0) {
            val mins = (now - fgSince) / 60_000L
            val hit = appTriggers.any { (_, t) ->
                t.type == WorkflowTriggerType.APP_FOREGROUND_LONG && pkgMatches(t.pkg, p) && mins >= t.fgMinutes
            }
            if (hit) {
                fgLongFired = true
                fireMatching(c, WorkflowTriggerType.APP_FOREGROUND_LONG, appVars(c, p, mins)) { _, t ->
                    pkgMatches(t.pkg, p) && mins >= t.fgMinutes
                }
            }
        }
    }

    /** 前台 App 换人。[seed] 时只更新状态不触发（见 [startAppPoll]）。 */
    private suspend fun switchFg(c: Context, newPkg: String?, ts: Long, seed: Boolean) {
        val old = fgPkg
        if (old == newPkg) return
        // 有的 ROM 不发 MOVE_TO_BACKGROUND，只发下一个 App 的 MOVE_TO_FOREGROUND，
        // 所以「换人」本身就要当成上一个 App 的退出，不能只等 BACKGROUND 事件。
        if (!seed && old != null && appTriggers.any { it.second.type == WorkflowTriggerType.APP_CLOSED && pkgMatches(it.second.pkg, old) }) {
            fireMatching(c, WorkflowTriggerType.APP_CLOSED, appVars(c, old, null)) { _, t -> pkgMatches(t.pkg, old) }
        }
        fgPkg = newPkg
        fgSince = ts
        fgLongFired = false
        if (!seed && newPkg != null && appTriggers.any { it.second.type == WorkflowTriggerType.APP_LAUNCHED && pkgMatches(it.second.pkg, newPkg) }) {
            fireMatching(c, WorkflowTriggerType.APP_LAUNCHED, appVars(c, newPkg, null)) { _, t -> pkgMatches(t.pkg, newPkg) }
        }
    }

    /** 用户可以只写一段包名（比如 "wechat"），所以是 contains 而不是全等。空 = 不匹配（配置没填完不该乱触发）。 */
    private fun pkgMatches(want: String, actual: String): Boolean =
        want.isNotBlank() && (actual.equals(want.trim(), true) || actual.contains(want.trim(), true))

    private fun appVars(c: Context, pkg: String, mins: Long?): Map<String, String> {
        val label = try {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        return mapOf(
            "pkg" to pkg,
            "app" to label,
            "minutes" to (mins?.toString() ?: ""),
            "default" to if (mins != null) "$label 已经在前台 $mins 分钟" else label,
        )
    }

    // ---------------- 通知内容匹配 ----------------

    /**
     * 实时通知钩子。**给 [XtomNotificationListener] 调**（那个文件不归这一层改，
     * 需要在它的 onNotificationPosted 末尾补一行，见文件末尾的接线说明）。
     *
     * 一旦被调过一次，兜底轮询就永久停掉——两条路同时开着是纯浪费。没接线时也不会哑：
     * 走 [startNotifPoll] 的兜底轮询，代价是最多 20 秒延迟。
     */
    fun onNotificationPosted(context: Context, pkg: String, title: String, text: String, key: String = "") {
        if (!started) return
        notifHookAlive = true
        stopNotifPoll()
        if (notifTriggers.isEmpty()) return                 // 没人订阅通知 → 直接返回，不碰磁盘
        val c = context.applicationContext
        if (!markNotifSeen(fingerprint(key.ifBlank { pkg }, title, text))) return
        dispatchNotif(c, pkg, title, text)
    }

    private fun fingerprint(key: String, title: String, text: String) = key + "|" + title.hashCode() + "|" + text.hashCode()

    private fun markNotifSeen(fp: String): Boolean = synchronized(notifSeen) { notifSeen.put(fp, true) == null }

    private fun dispatchNotif(c: Context, pkg: String, title: String, text: String) {
        val app = try {
            val pm = c.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        val body = listOf(title, text).filter { it.isNotBlank() }.joinToString("：")
        fireAsync(
            c, WorkflowTriggerType.NOTIF_MATCH,
            mapOf("title" to title, "text" to text, "app" to app, "pkg" to pkg, "default" to "[$app] $body"),
        ) { _, t -> notifMatches(t, pkg, title, text) }
    }

    /** 包名过滤（可选）+ 关键词/正则命中标题与正文。正则写错就当不匹配，绝不让一个坏正则把触发层拖崩。 */
    private fun notifMatches(t: WorkflowTrigger, pkg: String, title: String, text: String): Boolean {
        if (t.pkg.isNotBlank() && !pkg.contains(t.pkg.trim(), true)) return false
        val pat = t.pattern.trim()
        if (pat.isBlank()) return t.pkg.isNotBlank()      // 只按包名筛
        val hay = title + "\n" + text
        return if (t.regex) {
            try { Regex(pat, RegexOption.IGNORE_CASE).containsMatchIn(hay) } catch (_: Exception) { false }
        } else hay.contains(pat, true)
    }

    private fun setNotifWatching(c: Context, want: Boolean) {
        if (!want) { stopNotifPoll(); return }
        startNotifPoll(c)
    }

    /**
     * 兜底轮询：只在 [onNotificationPosted] 从没被调过（＝没接线）时才跑，且只在有通知触发器时才跑。
     *
     * 为什么敢轮询：读的是 [NotificationStore] 的**进程内环形缓存**，不碰磁盘也不碰系统服务；
     * 而且这是纯 delay 循环，不设闹钟、不持 wakelock——息屏进 doze 后它自己就跟着停摆，
     * 不会把手表叫醒。代价只是「通知到触发」最多慢 20 秒。
     */
    private fun startNotifPoll(c: Context) {
        if (notifHookAlive || notifPollJob?.isActive == true) return
        notifPollJob = scope.launch {
            // 播种：把当前缓存里已有的通知全标记成已见，别一开机就把历史通知全触发一遍
            try { NotificationStore.recent(60).forEach { markNotifSeen(fingerprint(it.key, it.title, it.text)) } } catch (_: Exception) {}
            while (isActive) {
                delay(NOTIF_POLL_MS)
                if (notifHookAlive) return@launch
                try {
                    // 从旧到新处理，保证多条一起来时触发顺序跟收到的顺序一致
                    if (notifTriggers.isNotEmpty()) NotificationStore.recent(20).asReversed().forEach { n ->
                        if (markNotifSeen(fingerprint(n.key, n.title, n.text))) dispatchNotif(c, n.pkg, n.title, n.text)
                    }
                } catch (e: CancellationException) { throw e } catch (_: Exception) {}
            }
        }
    }

    private fun stopNotifPoll() { notifPollJob?.cancel(); notifPollJob = null }

    // ---------------- 开机 ----------------

    /**
     * 报一次「开机」。同一次开机只报一次：用 `墙上时间 - 开机以来的毫秒数` 当这次开机的身份，
     * 落盘去重。NTP 校时会让这个值漂几分钟，所以比较时留 5 分钟容差（宁可漏报一次也不能重复触发）。
     *
     * [requireFresh]=true 是给 [init] 用的兜底路径：只有进程在开机后几分钟内被拉起才算数，
     * 否则「用户中午手动打开 App」也会被当成开机。
     *
     * ⚠ 没有开机触发器时**不消耗**这次开机的身份：直连启动（未解锁）阶段读不到工作流文件，
     * 那时若把身份消耗掉，解锁后真正该触发的那一次就被吃了。
     */
    private fun maybeFireBoot(context: Context, requireFresh: Boolean) {
        try {
            val c = context.applicationContext
            val elapsed = SystemClock.elapsedRealtime()
            if (requireFresh && elapsed > BOOT_FRESH_MS) return
            val has = try { WorkflowStore.activeTriggers(c).any { it.second.type == WorkflowTriggerType.BOOT } } catch (_: Exception) { false }
            if (!has) return
            val id = (System.currentTimeMillis() - elapsed) / 60_000L
            val p = prefs(c)
            val prev = p.getLong(K_BOOT_ID, 0L)
            if (prev != 0L && kotlin.math.abs(id - prev) < 5) return
            p.edit().putLong(K_BOOT_ID, id).apply()
            fireAsync(c, WorkflowTriggerType.BOOT, mapOf("default" to "开机"))
        } catch (_: Exception) {}
    }

    // ---------------- 定时 ----------------

    /**
     * 只排**最近的那一次**，跑完再排下一次。
     *
     * 为什么不用 PeriodicWork：周期任务最小 15 分钟，意味着为了「每天 8 点」这种一天一次的需求，
     * 一天要白白唤醒 96 次去问「到点没」。一次性任务 + 跑完重排，唤醒次数正好等于实际触发次数。
     * WorkManager 自己扛重启后的恢复。围栏的兜底自查也搭这趟车，不另起闹钟。
     */
    private fun rescheduleTimer(c: Context) {
        try {
            val wm = WorkManager.getInstance(c)
            val now = System.currentTimeMillis()
            val active = WorkflowStore.activeTriggers(c)
            val dues = active.filter { it.second.type.isTimed }.mapNotNull { (n, t) -> nextDueAt(t, lastFire(c, n), now) }
            // 有围栏就顺带排一个稀疏的自查点（不是围栏的主路，只是补漏，见 checkGeofences）
            val geoDue = if (active.any { it.second.type.isGeo }) listOf(now + GEO_CHECK_MS) else emptyList()
            val next = (dues + geoDue).minOrNull()
            if (next == null) { wm.cancelUniqueWork(WORK_TIMER); return }   // 没人用 → 不留任务
            val delay = (next - now).coerceAtLeast(60_000L)                 // 至少 1 分钟，防抖动式自触发
            val req = OneTimeWorkRequestBuilder<WorkflowTriggerWorker>()
                .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                // 低电量时不跑：定时工作流几乎都不是急事，而在手表上电量低时跑一轮大模型是最贵的选择。
                // 电量回来后 WorkManager 会接着跑，到点没跑的那次在下一次 doWork 里按「已过期」补上。
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            wm.enqueueUniqueWork(WORK_TIMER, ExistingWorkPolicy.REPLACE, req)
        } catch (_: Exception) {}
    }

    /**
     * 下一次该跑的时刻；非定时类返回 null。**全项目算期点只有这一处**，加新的时间表达式只改这里。
     */
    internal fun nextDueAt(t: WorkflowTrigger, lastFire: Long, now: Long): Long? = when (t.type) {
        WorkflowTriggerType.INTERVAL -> {
            val step = t.intervalMin.coerceAtLeast(WorkflowTrigger.MIN_INTERVAL_MIN) * 60_000L
            (if (lastFire > 0) lastFire else now) + step
        }
        WorkflowTriggerType.DAILY -> {
            val today = Calendar.getInstance().apply {
                timeInMillis = now
                set(Calendar.HOUR_OF_DAY, t.hour); set(Calendar.MINUTE, t.minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            // 今天这个点还没到 → 就是它；已经过了但今天还没跑过（比如刚开机）→ 立刻补跑；否则明天同一时刻
            if (today > now) today else if (lastFire < today) now else today + 24 * 60 * 60 * 1000L
        }
        WorkflowTriggerType.CRON -> {
            // 从上次触发往后算：进程停了几天再起来，这里会算出一个已过去的时刻 → 当场补跑一次
            // （和 DAILY 的补跑口径一致），而不是把这几天漏的全部补齐。
            CronSpec.parse(t.cron)?.nextAfter(if (lastFire > 0) lastFire else now)
        }
        else -> null
    }

    /** Worker 回调：把到点的定时工作流**内联**跑掉（不能 launch 完就返回，否则进程可能被立刻回收），再排下一次。 */
    internal suspend fun runDueTimed(c: Context) {
        val now = System.currentTimeMillis()
        val due = try {
            WorkflowStore.activeTriggers(c)
                .filter { it.second.type.isTimed }
                .filter { (n, t) -> (nextDueAt(t, lastFire(c, n), now) ?: Long.MAX_VALUE) <= now + 60_000L }
        } catch (_: Exception) { emptyList() }
        for ((name, t) in due) {
            if (!gateAndMark(c, name, t, now)) continue
            try {
                runOne(c, name, t, emptyMap())
            } catch (e: CancellationException) {
                throw e                      // 取消必须往上抛，别当成普通失败吞掉
            } catch (_: Exception) {
            } finally {
                running.remove(name)
            }
        }
        try { checkGeofences(c) } catch (e: CancellationException) { throw e } catch (_: Exception) {}
        rescheduleTimer(c)
    }

    // ---------------- 触发执行 ----------------

    /**
     * 事件类触发：整段丢后台协程。
     *
     * 广播的 onReceive 跑在主线程且必须马上返回（超时直接 ANR），而门禁判断要读工作流文件，
     * 所以连「有没有人订阅这个事件」都不在 onReceive 里算——一律进 IO。
     */
    private fun fireAsync(
        c: Context,
        type: WorkflowTriggerType,
        vars: Map<String, String> = emptyMap(),
        match: (String, WorkflowTrigger) -> Boolean = { _, _ -> true },
    ) {
        scope.launch { fireMatching(c, type, vars, match) }
    }

    /**
     * 同一个事件命中多个工作流时**串行**跑：手表上并行开几路模型请求只会互相拖慢。
     * [match] 是这一类触发器自己的细筛（哪个包名/哪台蓝牙设备/通知里有没有那个词）。
     */
    private suspend fun fireMatching(
        c: Context,
        type: WorkflowTriggerType,
        vars: Map<String, String>,
        match: (String, WorkflowTrigger) -> Boolean,
    ) {
        val now = System.currentTimeMillis()
        val matched = try {
            WorkflowStore.activeTriggers(c).filter { it.second.type == type && match(it.first, it.second) }
        } catch (_: Exception) { return }
        for ((name, t) in matched) {
            if (!gateAndMark(c, name, t, now)) continue
            try {
                runOne(c, name, t, vars)
            } catch (e: CancellationException) {
                throw e                  // 取消必须重抛
            } catch (e: Exception) {
                // 一个工作流炸了只记一笔，监听器和其它工作流照常
                note(c, name, "触发执行出错：${e.message}")
                // 同时进运行日志：自动触发是在用户没看屏幕时发生的，note 只在工作流页里看得到，
                // 而用户察觉异常的第一反应是"翻日志"，不是"去工作流页逐个点开看备注"。
                AppLog.e("Workflow", "触发「$name」执行出错", e)
            } finally {
                running.remove(name)
            }
        }
    }

    /**
     * 触发门禁：防抖窗口 + 并发上限 + 同名重入。通过则**立刻记下触发时间**并占位。
     *
     * 时间戳先写后跑（不是跑完再写）：万一这轮整个炸了或进程被杀，也不会因为「没写成功」
     * 而在下一个事件立刻重来一遍——失控重试比漏跑一次贵得多。
     */
    private fun gateAndMark(c: Context, name: String, t: WorkflowTrigger, now: Long): Boolean {
        val gap = t.minGapMin.coerceAtLeast(1) * 60_000L
        if (now - lastFire(c, name) < gap) return false          // 防抖：窗口内只认第一次
        if (running.size >= MAX_CONCURRENT) return false
        if (!running.add(name)) return false                     // 上一轮还没跑完，这轮不排队直接丢
        prefs(c).edit().putLong(K_LAST_FIRE + name, now).apply()
        return true
    }

    /**
     * 触发输入里可以写 `{{title}}`/`{{app}}`/`{{pkg}}`/`{{device}}` 这类事件变量，在这儿先替换掉，
     * 再把结果当 `{{input}}` 交给引擎。没填触发输入时给一句默认描述，免得事件类工作流拿到空输入。
     */
    private suspend fun runOne(c: Context, name: String, t: WorkflowTrigger, vars: Map<String, String>) {
        val wf = WorkflowStore.get(c, name) ?: return
        val input = if (t.input.isBlank()) vars["default"].orEmpty()
        else Regex("""\{\{(\w+)\}\}""").replace(t.input) { m -> vars[m.groupValues[1]] ?: m.value }
        val r = WorkflowEngine.run(c, wf, input)
        note(c, name, r.lineSequence().lastOrNull { it.isNotBlank() } ?: "已运行")
    }

    private fun lastFire(c: Context, name: String): Long = try { prefs(c).getLong(K_LAST_FIRE + name, 0L) } catch (_: Exception) { 0L }

    private fun note(c: Context, name: String, text: String) {
        try { prefs(c).edit().putString(K_LAST_NOTE + name, text.take(160)).apply() } catch (_: Exception) {}
    }

    // ---------------- 给 UI 用 ----------------

    fun label(t: WorkflowTriggerType): String = when (t) {
        WorkflowTriggerType.NONE -> tr("不自动触发")
        WorkflowTriggerType.INTERVAL -> tr("每隔一段时间")
        WorkflowTriggerType.DAILY -> tr("每天定点")
        WorkflowTriggerType.CRON -> tr("cron 表达式")
        WorkflowTriggerType.BOOT -> tr("开机后")
        WorkflowTriggerType.SCREEN_ON -> tr("亮屏时")
        WorkflowTriggerType.SCREEN_OFF -> tr("息屏时")
        WorkflowTriggerType.BATTERY_LOW -> tr("电量过低")
        WorkflowTriggerType.POWER_CONNECTED -> tr("接上充电")
        WorkflowTriggerType.POWER_DISCONNECTED -> tr("拔掉充电")
        WorkflowTriggerType.WIFI_CONNECTED -> tr("连上 WiFi")
        WorkflowTriggerType.WIFI_DISCONNECTED -> tr("断开 WiFi")
        WorkflowTriggerType.HEADSET_PLUGGED -> tr("插入耳机")
        WorkflowTriggerType.HEADSET_UNPLUGGED -> tr("拔出耳机")
        WorkflowTriggerType.BT_CONNECTED -> tr("蓝牙连上")
        WorkflowTriggerType.BT_DISCONNECTED -> tr("蓝牙断开")
        WorkflowTriggerType.GEOFENCE_ENTER -> tr("到达某地")
        WorkflowTriggerType.GEOFENCE_EXIT -> tr("离开某地")
        WorkflowTriggerType.NOTIF_MATCH -> tr("通知命中")
        WorkflowTriggerType.APP_LAUNCHED -> tr("打开某个 App")
        WorkflowTriggerType.APP_CLOSED -> tr("退出某个 App")
        WorkflowTriggerType.APP_FOREGROUND_LONG -> tr("某个 App 用太久")
    }

    /**
     * 列表页所有行的摘要，**一次读盘**算完。
     *
     * 列表里每行各查一次的话，workflows.json 会被完整解析 N 遍；而编辑触发器时每敲一个字
     * 都会让整列表失效重算一轮——行数一多就是主线程上肉眼可见的卡。
     */
    fun summaries(context: Context): Map<String, String> {
        val arr = try { WorkflowStore.all(context) } catch (_: Exception) { return emptyMap() }
        val out = LinkedHashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name").takeIf { it.isNotBlank() } ?: continue
            summaryOf(WorkflowTrigger.from(o.optJSONObject("trigger")))?.let { out[n] = it }
        }
        return out
    }

    /** 一行的摘要；没配触发器返回 null。 */
    private fun summaryOf(t: WorkflowTrigger): String? {
        if (t.type == WorkflowTriggerType.NONE) return null
        val what = when (t.type) {
            WorkflowTriggerType.INTERVAL -> tr("每") + " ${t.intervalMin} " + tr("分钟")
            WorkflowTriggerType.DAILY -> tr("每天") + " %02d:%02d".format(t.hour, t.minute)
            WorkflowTriggerType.CRON -> tr("cron") + " " + t.cron
            WorkflowTriggerType.GEOFENCE_ENTER, WorkflowTriggerType.GEOFENCE_EXIT ->
                label(t.type) + " %.4f,%.4f ".format(t.lat, t.lon) + "${t.radiusM}m"
            WorkflowTriggerType.NOTIF_MATCH ->
                label(t.type) + "：" + listOf(t.pkg, t.pattern).filter { it.isNotBlank() }.joinToString(" / ")
            WorkflowTriggerType.APP_LAUNCHED, WorkflowTriggerType.APP_CLOSED -> label(t.type) + "：" + t.pkg
            WorkflowTriggerType.APP_FOREGROUND_LONG -> t.pkg + " " + tr("超过") + " ${t.fgMinutes} " + tr("分钟")
            WorkflowTriggerType.BT_CONNECTED, WorkflowTriggerType.BT_DISCONNECTED ->
                label(t.type) + (if (t.device.isBlank()) "（" + tr("任意设备") + "）" else "：" + t.device)
            else -> label(t.type)
        }
        return when {
            !t.enabled -> what + "（" + tr("已关闭") + "）"
            !t.configured -> what + "（" + tr("没配全，不会触发") + "）"
            else -> what
        }
    }

    /** 上次自动触发的结果摘要（设置页展示用）。 */
    fun lastNote(context: Context, name: String): String? = try {
        val p = prefs(context)
        val at = p.getLong(K_LAST_FIRE + name, 0L)
        val note = p.getString(K_LAST_NOTE + name, null)
        if (at <= 0L || note.isNullOrBlank()) null
        else android.text.format.DateFormat.format("MM-dd HH:mm", at).toString() + " · " + note
    } catch (_: Exception) { null }

    /** 下一次预计触发时间（定时/cron 类）。cron 编辑框拿它当「表达式到底是不是我想的那个意思」的实时验证。 */
    fun nextRunText(context: Context, name: String, t: WorkflowTrigger): String? = try {
        val now = System.currentTimeMillis()
        nextDueAt(t, lastFire(context, name), now)?.let {
            android.text.format.DateFormat.format("MM-dd HH:mm", maxOf(it, now)).toString()
        }
    } catch (_: Exception) { null }

    /**
     * 这类触发器现在缺什么权限，以及去哪儿给。返回 null = 不缺。
     *
     * 权限没给却静默不触发是最糟的体验（用户会以为是功能坏了），所以每一类都必须能说清楚。
     */
    fun permissionGap(c: Context, type: WorkflowTriggerType): TriggerPermissionGap? = try {
        when {
            type.isGeo -> when {
                !LocationSignals.hasPermission(c) -> TriggerPermissionGap(
                    tr("没有定位权限，围栏不会触发"), tr("去授权"),
                    runtime = listOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION),
                )
                LocationSignals.unavailableReason(c) != null -> TriggerPermissionGap(
                    tr("系统定位总开关没打开，围栏不会触发"), tr("去打开"),
                    settings = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS),
                )
                else -> null
            }
            type == WorkflowTriggerType.NOTIF_MATCH -> when {
                !NotificationAwarenessPrefs.hasAccess(c) -> TriggerPermissionGap(
                    tr("没有「通知使用权」，读不到通知"), tr("去开启"),
                    settings = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS),
                )
                !NotificationAwarenessPrefs.enabled(c) -> TriggerPermissionGap(
                    tr("通知感知在 App 内被关掉了，去「权限」页把它打开，否则收不到通知"),
                )
                else -> null
            }
            type.isApp -> if (!UsageStatsTool.hasAccess(c)) TriggerPermissionGap(
                tr("没有「使用情况访问」权限，看不到你在用哪个 App"), tr("去开启"),
                settings = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
            ) else null
            type.isBt -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ContextCompat.checkSelfPermission(c, android.Manifest.permission.BLUETOOTH_CONNECT) != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) TriggerPermissionGap(
                tr("没有蓝牙权限，认不出是哪台设备（只能按 MAC 地址匹配）"), tr("去授权"),
                runtime = listOf(android.Manifest.permission.BLUETOOTH_CONNECT),
            ) else null
            else -> null
        }
    } catch (_: Exception) { null }

    /** 触发输入里能用的事件变量，按类型给。UI 直接展示，省得用户去猜。 */
    fun inputVars(type: WorkflowTriggerType): String? = when {
        type == WorkflowTriggerType.NOTIF_MATCH -> "{{title}} {{text}} {{app}} {{pkg}}"
        type.isApp -> "{{app}} {{pkg}}" + (if (type == WorkflowTriggerType.APP_FOREGROUND_LONG) " {{minutes}}" else "")
        type.isBt -> "{{device}} {{address}}"
        type.isGeo -> "{{place}}"
        else -> null
    }
}

/**
 * 定时触发的执行外壳：真正的「到点没/该不该跑」判断都在 [WorkflowTriggers.runDueTimed] 里。
 * 失败不重试——下一次排期自然会来，重试只是在手表上白耗一次电。
 */
class WorkflowTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try {
            WorkflowTriggers.runDueTimed(applicationContext)
        } catch (e: CancellationException) {
            throw e                          // 系统撤销任务时的取消，必须重抛
        } catch (_: Exception) {
        }
        return Result.success()
    }
}
