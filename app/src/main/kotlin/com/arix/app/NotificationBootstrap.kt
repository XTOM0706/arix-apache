package com.arix.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * 通知权限的正确请求方式 —— 给 **targetSdk < 33** 的应用用的。
 *
 * ## 为什么需要这个东西
 *
 * 本 App 的 `targetSdk` 钉在 28（为了保留旧 SELinux 域，见 `app/build.gradle.kts`）。
 * 而 Android 13 起，`POST_NOTIFICATIONS` 是运行时权限，且平台对两类应用走**两条完全不同的路**：
 *
 *  - `targetSdk >= 33`：自己调 `requestPermissions(POST_NOTIFICATIONS)`，系统弹框。
 *  - `targetSdk < 33`（**我们**）：`requestPermissions` **直接返回拒绝、连框都不弹**。
 *    平台给这类应用留的唯一入口是：**当 App 创建第一个通知渠道时**，系统自己把权限框弹出来。
 *
 * 我们两条路同时堵死了：既在按 targetSdk≥33 的方式请求（必然失败），
 * 所有通知渠道又都是**懒建**的（`CapsuleBridge` / `Reminders` / `ProactiveMessage` / `Diary` /
 * `WakeService` / `ScreenCaptureService` 全是用到时才建）——首次安装一个渠道都不建，
 * 于是系统那条兜底路也永远不会触发。
 *
 * 结果：Android 13+ 全新安装的用户，在向导里点「通知」什么都不会发生，界面永远显示未授权；
 * 提醒、每日日记、主动消息、后台任务完成通知**全部静默失效**，而且他看不出是为什么。
 *
 * 所以：**请求通知权限 = 先建渠道**。这就是这个文件做的事。
 */
object NotificationBootstrap {

    /** 常规通知渠道。没有自己专属渠道的通知都可以落在这里。 */
    const val CHANNEL_GENERAL = "arix_general"

    /**
     * 确保至少有一个通知渠道存在。
     *
     * @return true = 这次**新建**了渠道。对 targetSdk<33 的应用，只有新建的那一次系统才会弹权限框；
     *   返回 false 说明渠道早就在了、系统的机会已经用掉，这时再"请求"是没有用的，
     *   调用方应该改为把用户送去通知设置页（见 [openNotificationSettings]）。
     */
    fun ensureChannel(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false   // O 以下没有渠道概念，也没有通知权限
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
        if (nm.getNotificationChannel(CHANNEL_GENERAL) != null) return false
        return try {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_GENERAL, "常规通知", NotificationManager.IMPORTANCE_DEFAULT).apply {
                    description = "提醒、日记、后台任务完成等一般通知"
                }
            )
            true
        } catch (_: Exception) { false }
    }

    /** 通知权限是否已授予。Android 13 以下没有这个权限，恒 true。 */
    fun granted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    /** 本应用的通知设置页。系统已经弹过一次框（用户拒了或划掉了）之后，这是唯一的补救路。 */
    fun openNotificationSettings(context: Context): Intent =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        else
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:${context.packageName}")))
            // 调用方可能拿的是 application context（设置页里 LocalContext 一般是 Activity，但不保证）。
            // 跳系统设置加 NEW_TASK 是常规做法，没有副作用，能免掉一次 ActivityNotFound 崩溃。
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * 「请求通知权限」的统一入口，各处按需调。
     *
     * @param requestPermission 调用方自己的 ActivityResultLauncher 包一层（targetSdk≥33 时才真正有用；
     *   我们现在是 28，它必然无效，但保留着——哪天 targetSdk 提上去，这里不用改）。
     * @param openSettings 把用户送去通知设置页。
     */
    fun request(context: Context, requestPermission: () -> Unit, openSettings: (Intent) -> Unit) {
        if (granted(context)) return
        // 建渠道这一步才是 targetSdk<33 时真正的"请求"——新建成功即意味着系统会把权限框弹出来
        val freshlyCreated = ensureChannel(context)
        if (freshlyCreated) {
            requestPermission()   // targetSdk 提到 33+ 之后这句才会真的弹框；现在是无害的空转
            return
        }
        // 渠道早就在了 = 系统那一次机会已经用掉，再请求不会有任何反应，直接给一条能走通的路
        openSettings(openNotificationSettings(context))
    }
}
