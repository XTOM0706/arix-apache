package com.arix.app

import android.app.Application
import android.os.Build
import android.webkit.WebView

/**
 * 隐身浏览的**进程隔离**地基。
 *
 * 为什么必须靠独立进程：`CookieManager` 是**进程级**单例，同一个进程里的所有 WebView 共用一份
 * cookie 库。能按 WebView 关掉的只有三方 cookie、缓存、DOM storage；**已经存在的第一方 cookie
 * 照样会被发出去**——而那恰恰是隐身要防的那件事（带着用户的登录态去访问一个第三方页面）。
 * `CookieManager.setAcceptCookie(false)` 是全局开关，且只管"存不存"、不管"发不发"，
 * 还会波及同时在跑的其他 WebView。所以「同进程近似隐身」在这件事上是做不到的，只能换进程。
 *
 * 隔离靠 [WebView.setDataDirectorySuffix]：给这个进程自己的 cookie 库 / 缓存 / DOM storage。
 * 这个 API 有两条铁律，违反任何一条都是崩：
 *  1. **一个进程只能调一次**，且必须在**任何 WebView 被创建之前**。所以只能在 Application.onCreate 最前面调。
 *  2. **API 28 起才有**。而本项目 `minSdk = 26` —— 在 26/27 上不但没有这个 API，
 *     「两个进程同时用 WebView 且共用同一份数据目录」本身就会直接抛
 *     `Using WebView from more than one process at once with the same data directory is not supported`。
 *     所以 26/27 上**绝不能**起这个进程，[supported] 为假时调用方必须走降级，并且**要在界面上说实话**，
 *     不能把降级也叫「隐身」。
 */
object IncognitoWeb {

    /** WebView 数据目录后缀。换它等于换一整套 cookie 库（老的那套就成了孤儿，别随便改）。 */
    private const val DATA_SUFFIX = "incognito"

    /** 进程名后缀，与 AndroidManifest 里 `android:process=":incognito"` 必须一致。 */
    const val PROCESS_SUFFIX = ":incognito"

    /** 本机能不能做真隐身。假 = 只能降级，且不许对用户称之为隐身。 */
    val supported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

    /**
     * 当前进程是不是隐身进程。
     *
     * 不用 `Application.getProcessName()`：那也是 API 28 才有的，而这个判断在 26/27 上同样要能答
     * （那时虽然不该有隐身进程，但判断本身不能瞎猜——猜错就会在主进程里跳过主初始化，整个 App 空转）。
     * 读 `/proc/self/cmdline` 是从 API 1 就稳定可用的做法。
     */
    fun isIncognitoProcess(app: Application): Boolean {
        val name = currentProcessName(app) ?: return false
        return name.endsWith(PROCESS_SUFFIX)
    }

    private fun currentProcessName(app: Application): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { return Application.getProcessName() }
        }
        return runCatching {
            java.io.File("/proc/self/cmdline").readBytes()
                .takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8).trim()
        }.getOrNull()?.takeIf { it.isNotBlank() }
            // 兜底：靠 ActivityManager 列进程。最后一招，某些 ROM 上 cmdline 读不到
            ?: runCatching {
                val pid = android.os.Process.myPid()
                val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                am.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
            }.getOrNull()
    }

    /**
     * 在 `Application.onCreate` 的**第一行**调。
     *
     * @return true = 当前就是隐身进程，**调用方必须立刻 return**、跳过所有主进程初始化
     *         （注册工具、起 MCP server、建通知渠道、补 DB 索引…那些在第二个进程里重跑一遍
     *         不只是浪费，MCP server 还会抢同一个端口、通知渠道会重复建）。
     */
    fun bootstrapIfIncognito(app: Application): Boolean {
        if (!isIncognitoProcess(app)) return false
        // 只有隐身进程才改数据目录；主进程必须保持默认目录，否则用户原有的登录态全部"消失"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix(DATA_SUFFIX) }
        }
        return true
    }
}
