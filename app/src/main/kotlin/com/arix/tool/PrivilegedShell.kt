package com.arix.tool

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * 特权执行门面 —— Shizuku（ADB 级，免 root）与 root（su）的探测、授权、执行统一收在这儿。
 *
 * 以前这些散在各处：ShellTool 自己拼 `su -c`、自己判断 shizuku、自己写错误话术；
 * ShizukuMic 顶着「Mic」的名字却兼职通用 shell 执行器；授权入口只在唤醒页里躺着。
 * 结果是 AI 想用特权时既问不到「现在有没有」，也没法「去要一个」，只能撞一鼻子灰再放弃。
 */

/** Shizuku：ADB 级特权，免 root。实际执行仍复用 [com.arix.app.ShizukuMic]（那儿的反射执行器已经调好了）。 */
object ShizukuAccess {
    /** Shizuku 装了并且服务在跑（binder 活着）。 */
    fun running(): Boolean = com.arix.app.ShizukuMic.binderAlive()

    /** 已授权给 Arix。 */
    fun granted(): Boolean = com.arix.app.ShizukuMic.hasPermission()

    /** 跑一条命令，未授权返回 null。 */
    fun exec(cmd: String): Pair<Int, String>? = com.arix.app.ShizukuMic.shell(cmd)

    /** Shizuku 管理器的包名（正式版 / Sui 都认这个）。 */
    private const val PKG = "moe.shizuku.privileged.api"

    fun installed(context: Context): Boolean =
        try { context.packageManager.getPackageInfo(PKG, 0); true } catch (_: Exception) { false }

    /**
     * 发起 Shizuku 授权。Shizuku 不是系统设置页，是它自己的一套弹框，所以单独走。
     * 三种没就绪的情形要分开说——「没装」「装了没启动」「启动了没授权」用户要做的事完全不同，
     * 全糊成一句「Shizuku 未就绪」等于没说。
     */
    suspend fun request(context: Context, reason: String): ToolResult {
        if (granted()) return ToolResult("Shizuku 已授权，可以直接用 privilege=shizuku 跑系统命令了。")
        if (!installed(context)) return ToolResult(
            "本机没装 Shizuku。Shizuku 能给 App ADB 级权限（免 root 跑 pm/am/settings/input 等系统命令）。" +
            "请用户从 shizuku.rikka.app 或应用商店装上并按引导启动（无线调试/ADB 各一次），再回来申请授权。" +
            (if (reason.isNotBlank()) "\n需要它做的事：$reason" else ""), isError = true)
        if (!running()) {
            // 装了没跑：把 Shizuku 拉起来，用户在里面点启动最直接
            withContext(Dispatchers.Main) {
                runCatching {
                    context.packageManager.getLaunchIntentForPackage(PKG)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { context.startActivity(it) }
                }
            }
            return ToolResult("Shizuku 装了但没在运行，已帮忙打开它。请用户在 Shizuku 里按引导启动服务（无线调试或连电脑 ADB 各一次），启动后再来申请授权。", isError = true)
        }
        // 跑着但没授权：弹 Shizuku 自己的授权框
        val ok = withContext(Dispatchers.Main) {
            kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                com.arix.app.ShizukuMic.requestPermission { granted ->
                    if (cont.isActive) cont.resume(granted)
                }
            }
        }
        return if (ok) ToolResult("用户已授予 Shizuku 权限。现在可以用 privilege=shizuku 跑系统命令了。")
        else ToolResult("用户拒绝了 Shizuku 授权。别再自动重试，换个不需要特权的做法，或先跟用户说清为什么需要。", isError = true)
    }
}

/** root：su。探测一次就缓存——每次 `su -c id` 都会在有些管理器上弹授权框，反复问很烦人。 */
object RootAccess {
    @Volatile private var cached: Boolean? = null

    /** 本机有 root 且授权给了本 App。首次调用会真的跑一次 `su -c id`（可能弹 root 管理器授权框）。 */
    suspend fun available(): Boolean = cached ?: withContext(Dispatchers.IO) {
        val r = try {
            val p = ProcessBuilder(listOf("su", "-c", "id")).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor() == 0 && out.contains("uid=0")
        } catch (_: Exception) { false }
        cached = r
        r
    }

    /** 有没有 su 这个二进制（不代表授权了）。用来把「没root」和「有root但没给本App授权」分开说。 */
    fun suBinaryExists(): Boolean = listOf(
        "/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/system/sbin/su",
        "/vendor/bin/su", "/data/adb/magisk/su",
    ).any { try { java.io.File(it).exists() } catch (_: Exception) { false } }

    /** 忘掉缓存：用户刚装/刚授权 root 后需要重新探。 */
    fun invalidate() { cached = null }

    /**
     * **只读**已探到的结果：探过且成功才 true，没探过一律 false。
     *
     * 给「顺手问一句现在是什么能力等级」的地方用（权限页每次 ON_RESUME、拼提示词时）——
     * 那些地方绝不能触发真正的 su 探测：有些 root 管理器会当场弹授权框，
     * 用户只是打开个设置页就被弹一脸，是最招人烦的那种交互。
     */
    fun grantedCached(): Boolean = cached == true

    suspend fun request(reason: String): ToolResult {
        if (available()) return ToolResult("已有 root 授权，可以用 privilege=root 了。")
        invalidate()
        if (!suBinaryExists()) return ToolResult(
            "本机没有 root（找不到 su）。别再试 privilege=root 了，改用 privilege=shizuku（免 root 的 ADB 级权限，" +
            "可以先 request_permission(permission=\"shizuku\") 申请）或换个不需要特权的做法。", isError = true)
        return ToolResult(
            "本机有 root，但 Arix 还没拿到授权。已经发起过一次 su 请求——请用户在 root 管理器（Magisk 等）里" +
            "给 Arix 授权后再重试。" + (if (reason.isNotBlank()) "\n需要它做的事：$reason" else ""), isError = true)
    }
}

/** 当前能用的最高特权。给 AI 报家底用：它得知道自己现在到底能干到哪一层，而不是每次都撞一次才知道。 */
enum class Privilege { APP, SHIZUKU, ROOT }

object PrivilegeProbe {
    /** 探当前可用的最高特权（会真跑 su 探测，慢一点但准）。 */
    suspend fun best(): Privilege = when {
        RootAccess.available() -> Privilege.ROOT
        ShizukuAccess.granted() -> Privilege.SHIZUKU
        else -> Privilege.APP
    }

    /** 一句话报家底，给工具的报错信息和 request_permission list 用。 */
    suspend fun describe(context: Context): String = buildString {
        append("当前特权：")
        append(when (best()) {
            Privilege.ROOT -> "root（最高，什么都能跑）"
            Privilege.SHIZUKU -> "Shizuku/ADB 级（能跑 pm/am/settings/input/dumpsys 等系统命令，免 root）"
            Privilege.APP -> "仅应用沙盒（跑不了系统命令）"
        })
        if (!ShizukuAccess.granted()) {
            append("\nShizuku：")
            append(when {
                !ShizukuAccess.installed(context) -> "没装（装上可免 root 拿 ADB 级权限）"
                !ShizukuAccess.running() -> "装了但没启动"
                else -> "在跑但没授权 —— request_permission(permission=\"shizuku\") 可申请"
            })
        }
        if (!RootAccess.suBinaryExists()) append("\nroot：本机没有")
    }
}
