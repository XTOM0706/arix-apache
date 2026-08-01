package com.arix.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「我们自己发出去的通知」的打扰偏好。
 *
 * 与 [NotificationAwarenessPrefs]（读别人的通知给 AI 感知）分工相反：那个管**收**，这个管**发**。
 *
 * 核心一条：**应用在前台时不弹通知**。人正看着屏幕，主动消息/提醒/日记/深搜完成本来就会落进
 * 聊天、岛、日记里，再从上面砸下一条横幅纯属重复打扰。抑制的只是「弹这一下」，业务照跑不丢。
 *
 * 不受本开关管的（拦了要么崩要么本就是可见 UI）：前台服务常驻通知（WakeService/ScreenCaptureService，
 * 拦了抛 ForegroundServiceDidNotStartInTimeException）、唤醒全屏兜底、实时胶囊/超级岛。
 *
 * 即时生效：改任意项 [version] 自增，订阅它的设置界面跟随重组（同本文件范式）。
 */
object NotificationPrefs {
    private const val PREFS = "xtom_notification"
    private const val KEY_SUPPRESS_FG = "suppress_in_foreground"
    private const val KEY_SUPPRESS_TOOL = "suppress_tool_in_foreground"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private fun bump() { _version.value = _version.value + 1 }

    /** 应用在前台时不弹通知。**默认开**。 */
    fun suppressInForeground(c: Context): Boolean = p(c).getBoolean(KEY_SUPPRESS_FG, true)
    fun setSuppressInForeground(c: Context, v: Boolean) { p(c).edit().putBoolean(KEY_SUPPRESS_FG, v).apply(); bump() }

    /** 连 AI 用 notification 工具主动发的那条也一起拦。**默认关**——那是 AI 显式要发的，多半有意为之。 */
    fun suppressToolInForeground(c: Context): Boolean = p(c).getBoolean(KEY_SUPPRESS_TOOL, false)
    fun setSuppressToolInForeground(c: Context, v: Boolean) { p(c).edit().putBoolean(KEY_SUPPRESS_TOOL, v).apply(); bump() }

    /**
     * 打扰型通知的统一闸门：在各 `postNotification()/notify()` **函数入口**早退用。
     * 只跳过「发通知」这一步，调用方的状态写入/续期/清理逻辑必须留在函数外照常执行。
     */
    fun suppressed(c: Context): Boolean = AppForeground.isForeground && suppressInForeground(c)

    /** AI 用 notification 工具 send 的那条：要主开关和次级开关都开才拦。 */
    fun suppressedForTool(c: Context): Boolean = suppressed(c) && suppressToolInForeground(c)
}
