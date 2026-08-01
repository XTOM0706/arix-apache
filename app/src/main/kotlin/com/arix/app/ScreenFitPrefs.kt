package com.arix.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 屏幕适配（圆角适配）：手表圆屏被表圈切掉四角、圆角矩形屏被设备圆角裁切，
 * 这里让用户手调把界面往里收多少（dp），不做自动探测。
 *
 * 三个量：insetH/insetV = 全 App 页面的额外横/纵内缩；floatInset = 悬浮界面
 * （聊天顶栏/输入栏玻璃条、唤醒助手浮层内容）的额外内缩。
 *
 * 改配置即时生效：贴 ThemeConfigStore 的范式——MutableStateFlow 做运行时状态源，
 * 调用方 `version.collectAsState()` 后 `remember(v) { ScreenFitPrefs.insetH(c) }` 即可全 App 重组。
 */
object ScreenFitPrefs {
    private const val PREFS = "xtom_screen_fit"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 变更信号：任一 set 后自增。订阅它的界面即时跟随（同 ThemeConfigStore.config 的用法）。 */
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private fun bump() { _version.value = _version.value + 1 }

    /** 各页面的额外横向内缩（dp）。默认 0，范围 0..48。 */
    fun insetH(c: Context): Float = p(c).getFloat("inset_h", 0f)
    fun setInsetH(c: Context, v: Float) { p(c).edit().putFloat("inset_h", v.coerceIn(0f, MAX)).apply(); bump() }

    /** 各页面的额外纵向内缩（dp）。默认 0，范围 0..48。 */
    fun insetV(c: Context): Float = p(c).getFloat("inset_v", 0f)
    fun setInsetV(c: Context, v: Float) { p(c).edit().putFloat("inset_v", v.coerceIn(0f, MAX)).apply(); bump() }

    /** 悬浮界面（聊天玻璃顶栏/输入栏、唤醒助手浮层）的额外内缩（dp）。默认 0，范围 0..48。 */
    fun floatInset(c: Context): Float = p(c).getFloat("inset_float", 0f)
    fun setFloatInset(c: Context, v: Float) { p(c).edit().putFloat("inset_float", v.coerceIn(0f, MAX)).apply(); bump() }

    /** 滑杆上限（dp）。 */
    const val MAX = 48f

    /** 是否圆屏（手表）。给调用方的便捷判断。 */
    fun isScreenRound(c: Context): Boolean = c.resources.configuration.isScreenRound

    /**
     * 按本机屏幕算出的推荐起点 (insetH, insetV, floatInset)。
     *
     * **只给「推荐值」按钮用——绝不自动写入**：默认值恒为 0，存量用户界面不会某次更新后突然内缩一圈。
     * - 圆屏（手表）：按短边比例，沿用终端模块已真机验过的 5.5%（内容）/ 9%（悬浮件）。
     * - 圆角矩形屏：读设备真实圆角半径 r（API 31+）。矩形内容要避开圆角，几何上需要 r×(1−1/√2)≈0.29r；
     *   取 0.3r，悬浮件贴边更狠取 0.45r。读不到就回落原来的常量。
     */
    fun suggestRound(c: Context): Triple<Float, Float, Float> {
        val dm = c.resources.displayMetrics
        val density = dm.density.takeIf { it > 0f } ?: return Triple(16f, 16f, 12f)
        if (isScreenRound(c)) {
            val shortDp = minOf(dm.widthPixels, dm.heightPixels) / density
            val inset = (shortDp * 0.055f).coerceIn(0f, MAX)
            return Triple(inset, inset, (shortDp * 0.09f).coerceIn(0f, MAX))
        }
        val radiusDp = runCatching {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S) return@runCatching null
            val wm = c.getSystemService(android.view.WindowManager::class.java) ?: return@runCatching null
            wm.currentWindowMetrics.windowInsets
                .getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
                ?.radius?.let { it / density }
        }.getOrNull() ?: return Triple(16f, 16f, 12f)
        return Triple(
            (radiusDp * 0.3f).coerceIn(0f, MAX),
            (radiusDp * 0.3f).coerceIn(0f, MAX),
            (radiusDp * 0.45f).coerceIn(0f, MAX),
        )
    }
}
