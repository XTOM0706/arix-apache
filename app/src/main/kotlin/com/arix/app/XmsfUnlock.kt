package com.arix.app

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * 免 root 让 Arix 焦点通知真出 HyperOS 超级岛：**发通知前经 Shizuku 把小米服务框架
 * (com.xiaomi.xmsf) 短暂断网** → 那一刻它拉不到/校验不了云控白名单 → 校验 fail-open 放行
 * → 任意 App 焦点通知都出岛（复刻 HyperBridge 的 Shizuku 方案）。xmsf 只离线 ~1s，MiPush 长连接
 * 会自动重连，实质不丢推送。
 *
 * 断网靠 [XtomPrivilegedService] 反射 IConnectivityManager 防火墙——那要 **Shizuku UserService(binder)**，
 * 现有 [ShizukuMic.exec]（纯 shell）够不到，所以这里单独 [Shizuku.bindUserService] 绑一个特权进程。
 *
 * 对外：
 *  - [ensureBound]  绑好并缓存特权服务；未授权/失败返回 null。
 *  - [cut] / [restore]  断/恢复某包联网（默认 xmsf）。
 *  - [withXmsfNetworkCut]  断网 → 发通知 → ~1s 后自动恢复；**任何一步失败都退化成直接发通知，绝不崩、不影响主流程**。
 *
 * 时序/抖动是 device-verify 项（K70 Pro）：50ms 让防火墙生效再发、~1000ms 后恢复、连发时把恢复往后顺延。
 */
object XmsfUnlock {

    private const val TAG = "XmsfUnlock"
    const val XMSF_PKG = "com.xiaomi.xmsf"

    /** 断网后到发通知的等待：让防火墙规则实际生效。device-verify。 */
    private const val PRE_NOTIFY_DELAY_MS = 50L
    /** 发完通知到恢复联网的等待：够焦点通知过白名单校验即可，越短 MiPush 抖动越小。device-verify。 */
    private const val RESTORE_DELAY_MS = 1000L
    private const val BIND_TIMEOUT_MS = 8000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bindMutex = Mutex()

    @Volatile private var service: IXtomPrivileged? = null
    @Volatile private var connection: ServiceConnection? = null
    private var userArgs: Shizuku.UserServiceArgs? = null

    // 恢复排程：每次 cut 后排一个「~1s 后 restore」，但连发时只让「最后一次」那个真正执行，
    // 前面的排程作废（gen 比对）——保证突发更新期间 xmsf 一直断着、末尾只恢复一次。
    private val restoreGen = AtomicInteger(0)
    @Volatile private var restoreJob: Job? = null

    /** Shizuku 就绪且已授权（binder 活 + 权限）。 */
    private fun shizukuReady(): Boolean = ShizukuMic.hasPermission()

    /**
     * **开机安全兜底**（安全审查项）：上次会话若在「断网 ~1s」窗口内被系统杀/被划掉，或那次 restore 失败，
     * xmsf 会残留断网 → **用户整机小米推送(MiPush)挂到重启、极难自查**。这里在 App 启动时，若超级岛开着，
     * 无条件恢复一次 xmsf 联网（幂等：恢复到正常「允许」态，没断也无害），把上次泄漏的断网自愈掉。
     * 冷启动时 Shizuku binder 可能还没连上，故最多等 ~10s 直到就绪再恢复。best-effort，不抛不崩。
     */
    fun healOnStart(ctx: Context) {
        if (!SuperIslandPrefs.enabled(ctx)) return   // 没开过超级岛=从没断过网，无需兜底
        scope.launch {
            repeat(10) {
                if (shizukuReady()) { runCatching { restore() }; return@launch }
                delay(1000)
            }
        }
    }

    /**
     * 绑定并缓存特权 UserService；已绑且存活则直接返回。未授权/未就绪/超时/失败 → 返回 null（不抛）。
     * suspend；CancellationException 会正常向上传播。
     */
    suspend fun ensureBound(): IXtomPrivileged? {
        service?.let { if (aliveQuiet(it)) return it }
        if (!shizukuReady()) return null
        return try {
            bindMutex.withLock {
                service?.let { if (aliveQuiet(it)) return it }
                bindNow()
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            Log.w(TAG, "ensureBound failed: ${e.message}")
            null
        }
    }

    private fun aliveQuiet(s: IXtomPrivileged): Boolean =
        try { s.asBinder().pingBinder() } catch (_: Throwable) { false }

    private suspend fun bindNow(): IXtomPrivileged? {
        val appCtx = XtomApp.appContext ?: return null
        val args = userArgs ?: Shizuku.UserServiceArgs(
            ComponentName(appCtx.packageName, XtomPrivilegedService::class.java.name)
        ).daemon(true).processNameSuffix("privileged").version(1).also { userArgs = it }

        return withTimeoutOrNull(BIND_TIMEOUT_MS) {
            suspendCancellableCoroutine { cont ->
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                        val svc = if (binder != null && binder.pingBinder())
                            IXtomPrivileged.Stub.asInterface(binder) else null
                        service = svc
                        connection = this
                        if (cont.isActive) cont.resume(svc)
                    }
                    override fun onServiceDisconnected(name: ComponentName?) {
                        // 断连：清缓存，下次 ensureBound 重绑（不泄漏，也不在这儿主动重连）。
                        service = null
                        connection = null
                    }
                }
                try {
                    Shizuku.bindUserService(args, conn)
                } catch (e: Throwable) {
                    Log.w(TAG, "bindUserService threw: ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
                cont.invokeOnCancellation {
                    runCatching { Shizuku.unbindUserService(args, conn, true) }
                }
            }
        }
    }

    /** 设某包联网开关。成功 true；未绑/查不到 uid/失败 false（不抛，CancellationException 除外）。 */
    private suspend fun setNet(pkg: String, enabled: Boolean): Boolean {
        val ctx = XtomApp.appContext ?: return false
        val svc = ensureBound() ?: return false
        val uid = try {
            ctx.packageManager.getPackageUid(pkg, 0)
        } catch (_: Throwable) {
            return false   // 查不到 = 非小米机型 / 没装 xmsf
        }
        return try {
            svc.setPackageNetworkingEnabled(uid, enabled)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            Log.w(TAG, "setNet($pkg,$enabled) failed: ${e.message}")
            service = null   // binder 可能死了：清缓存，下次重绑
            false
        }
    }

    /** 断某包（默认 xmsf）联网。返回是否成功。 */
    suspend fun cut(pkg: String = XMSF_PKG): Boolean = setNet(pkg, false)

    /** 恢复某包（默认 xmsf）联网。 */
    suspend fun restore(pkg: String = XMSF_PKG): Boolean = setNet(pkg, true)

    private fun scheduleRestore(pkg: String) {
        val myGen = restoreGen.incrementAndGet()
        restoreJob?.cancel()
        restoreJob = scope.launch {
            delay(RESTORE_DELAY_MS)
            if (restoreGen.get() == myGen) {
                runCatching { restore(pkg) }
            }
        }
    }

    /**
     * 断网 → 跑 [block]（发焦点通知）→ 排 ~1s 后自动恢复。
     * - Shizuku 没绑上 / 断网失败 → 直接 [block]（退化成普通通知，不崩、不影响主流程）。
     * - 连发时恢复排程顺延到最后一次（见 restoreGen），突发期间 xmsf 一直断着，末尾只恢复一次。
     * - suspend：block 里的 CancellationException 先重抛。
     */
    suspend fun <T> withXmsfNetworkCut(pkg: String = XMSF_PKG, block: suspend () -> T): T {
        var didCut = false
        try {
            // 有 pending restore 先撤，避免它在这次 cut 后把网抢回去。
            restoreJob?.cancel()
            didCut = cut(pkg)
            if (didCut) delay(PRE_NOTIFY_DELAY_MS)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Throwable) {
            Log.w(TAG, "pre-cut failed, sending plainly: ${e.message}")
            didCut = false
        }
        try {
            return block()
        } finally {
            if (didCut) scheduleRestore(pkg)
        }
    }
}
