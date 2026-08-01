package com.arix.app

import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.annotation.Keep
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Shizuku UserService 的特权实现。**跑在 Shizuku 拉起的独立进程里**（身份 = shell uid 2000 或 root），
 * 不是 App 进程——所以这里发的 binder 事务带的是 ADB 级身份，能过系统对 MANAGE_NETWORK_POLICY 之类的鉴权。
 *
 * 唯一职责：反射 `IConnectivityManager` 的隐藏防火墙 API，把某 uid（小米服务框架 com.xiaomi.xmsf）
 * 短暂断网 → HyperOS 焦点通知的云控白名单校验连不上、fail-open 放行（复刻 HyperBridge 的 Shizuku 方案）。
 *
 * ⚠ device-verify（K70 Pro / HyperOS，见 DESIGN-LIVE-CAPSULE.md）：
 *   · 防火墙 chain 号（用 FIREWALL_CHAIN_OEM_DENY_3=9，Android 13+；不同 ROM/版本可能不认，已留多签名兜底）。
 *   · `setFirewallChainEnabled`/`setUidFirewallRule` 的确切签名（AOSP 标准签名，异厂可能改；按 名称+参数个数 解析）。
 *   · rule DENY=2 / DEFAULT=0（OEM_DENY 是默认放行的黑名单链：DENY 拦、DEFAULT 放）。
 *
 * 类必须有无参构造、且不被裁剪（@Keep；本项目 minify 关着，仍加以防万一）——Shizuku 按类名反射实例化。
 */
@Keep
class XtomPrivilegedService : IXtomPrivileged.Stub() {

    companion object {
        private const val TAG = "XtomPrivileged"
        private const val OP_TIMEOUT_MS = 3000L

        // 防火墙链：FIREWALL_CHAIN_OEM_DENY_3 = 9（Android 13+）。K70 Pro(A13+) 满足。
        // 这是「默认放行的黑名单链」：启用链 + 给目标 uid 下 DENY 规则 = 只断这一个 uid，别的不受影响。
        private const val FIREWALL_CHAIN_OEM_DENY_3 = 9
        private const val RULE_DEFAULT = 0   // 落到链默认（放行）→ 用于恢复
        private const val RULE_DENY = 2      // 拦截 → 用于断网

        // 单独工作线程跑反射：binder 事务别压在 Shizuku 的 binder 线程上。
        private val workerThread: HandlerThread by lazy {
            HandlerThread("XtomPrivilegedWorker").apply { start() }
        }
        private val workerHandler: Handler by lazy { Handler(workerThread.looper) }
    }

    override fun setPackageNetworkingEnabled(uid: Int, enabled: Boolean): Boolean {
        return try {
            val resultRef = AtomicReference<Result<Boolean>?>(null)
            val latch = CountDownLatch(1)
            workerHandler.post {
                val r = runCatching {
                    val cm = connectivityManager()
                    // 先确保链启用（幂等），再给该 uid 下规则。
                    callResilient(cm, "setFirewallChainEnabled", FIREWALL_CHAIN_OEM_DENY_3, true)
                    val rule = if (enabled) RULE_DEFAULT else RULE_DENY
                    callResilient(cm, "setUidFirewallRule", FIREWALL_CHAIN_OEM_DENY_3, uid, rule)
                    Log.d(TAG, "firewall ok: uid=$uid enabled=$enabled (rule=$rule)")
                    true
                }
                resultRef.set(r)
                latch.countDown()
            }
            if (!latch.await(OP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "timeout for uid=$uid enabled=$enabled")
                return false
            }
            val res = resultRef.get() ?: return false
            res.exceptionOrNull()?.let { e ->
                Log.e(TAG, "failed uid=$uid enabled=$enabled: ${e.javaClass.name}: ${e.message}")
                (e.cause ?: (e as? InvocationTargetException)?.targetException)?.let {
                    Log.e(TAG, "  cause: ${it.javaClass.name}: ${it.message}")
                }
            }
            res.getOrDefault(false)
        } catch (e: Throwable) {
            // 绝对兜底：特权进程里任何崩溃都吞掉，别把 Shizuku 进程带崩。
            Log.e(TAG, "critical: ${e.message}")
            false
        }
    }

    /** ServiceManager.getService("connectivity") → IConnectivityManager$Stub.asInterface。 */
    private fun connectivityManager(): Any {
        val sm = Class.forName("android.os.ServiceManager")
        val binder = sm.getMethod("getService", String::class.java).invoke(null, "connectivity") as? IBinder
            ?: throw RuntimeException("connectivity service not found")
        val stub = Class.forName("android.net.IConnectivityManager\$Stub")
        return stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
            ?: throw RuntimeException("IConnectivityManager.asInterface returned null")
    }

    /**
     * 按「方法名 + 参数个数」解析并调用——不同 Android 版本/厂商 ROM 里这些隐藏 API 的确切签名可能变，
     * 用精确 getMethod 反而容易 NoSuchMethod。int/boolean 装箱按目标形参类型强制对齐。
     * TODO(device-verify): 若 K70 Pro 上按名找不到，可能被改名/加了参数，需 dump framework.jar 核对。
     */
    private fun callResilient(target: Any, name: String, vararg args: Any) {
        val method = target.javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: throw NoSuchMethodException("$name/${args.size} not on ${target.javaClass.name}")
        method.isAccessible = true
        val coerced = Array(args.size) { i ->
            val pt = method.parameterTypes[i]
            val a = args[i]
            when {
                pt == Int::class.javaPrimitiveType && a is Boolean -> if (a) 1 else 0
                pt == Boolean::class.javaPrimitiveType && a is Number -> a.toInt() != 0
                else -> a
            }
        }
        method.invoke(target, *coerced)
    }
}
