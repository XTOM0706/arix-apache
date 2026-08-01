package com.arix.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 实时胶囊（超级岛/流体云）的**外观与行为**偏好。
 *
 * 与 [SuperIslandPrefs]（免 root「真出岛」总开关，仅小米）分工：那个管「能不能出岛」，
 * 这个管「出岛后长什么样、留多久」——配色主题、流光动画、是否弹浮窗、结束后常驻时长。
 *
 * 即时生效：改任意项 [version] 自增，订阅它的设置界面跟随重组（同 [SuperIslandPrefs] 范式）。
 */
object CapsulePrefs {
    private const val PREFS = "xtom_capsule"
    private const val KEY_LINGER = "linger_seconds"
    private const val KEY_ANIMATED = "animated"
    private const val KEY_FLOAT = "float_popup"
    private const val KEY_THEME = "color_theme"
    private const val KEY_DISPLAY = "display_mode"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private fun bump() { _version.value = _version.value + 1 }

    /** 结束后常驻秒数：**默认 5**；0=立即收；-1=不自动收、必须手动划走。 */
    fun lingerSeconds(c: Context): Int = p(c).getInt(KEY_LINGER, 5)
    fun setLingerSeconds(c: Context, v: Int) { p(c).edit().putInt(KEY_LINGER, v).apply(); bump() }

    /** 流光/过渡动画（超级岛 effectColor + 岛入场过渡）。默认开。 */
    fun animated(c: Context): Boolean = p(c).getBoolean(KEY_ANIMATED, true)
    fun setAnimated(c: Context, v: Boolean) { p(c).edit().putBoolean(KEY_ANIMATED, v).apply(); bump() }

    /** 是否允许「弹浮窗大岛」（enableFloat）。**默认关**——用户反馈「别动不动放大成一坨弹脸上」。
     *  关：只在状态栏胶囊/小岛里更新，不抢屏。开：重要状态会浮窗弹出。 */
    fun floatPopup(c: Context): Boolean = p(c).getBoolean(KEY_FLOAT, false)
    fun setFloatPopup(c: Context, v: Boolean) { p(c).edit().putBoolean(KEY_FLOAT, v).apply(); bump() }

    /** 配色主题 id（见 [themes]）。默认 xtom（紫→青流光，与 App 主色一致）。 */
    fun theme(c: Context): String = p(c).getString(KEY_THEME, "xtom") ?: "xtom"
    fun setTheme(c: Context, id: String) { p(c).edit().putString(KEY_THEME, id).apply(); bump() }

    /**
     * 岛显示位置：`system`=系统超级岛/流体云 · `inapp`=应用内岛（自绘，全机型一致、效果可控）·
     * `both`=两个都显 · `off`=都不显。**默认 system**。
     * 注：系统岛的**配色/流光/自定义图标**受 HyperOS `canCustomFocus` 云端鉴权门禁——第三方 App 不在授权名单，
     * 系统会收下定制再剥掉、只留默认样式（logcat 实证 authResult 无本包）。要出定制效果只能靠 Xposed 解锁
     * （1812z/HyperIsland 模块）。不想装 Xposed 又想要效果，就把这项切到 `inapp`（自绘岛，效果全可控）。
     */
    fun displayMode(c: Context): String = p(c).getString(KEY_DISPLAY, "system") ?: "system"
    fun setDisplayMode(c: Context, v: String) { p(c).edit().putString(KEY_DISPLAY, v).apply(); bump() }

    /** 解析当前主题的调色板。 */
    fun palette(c: Context): CapsuleTheme = themes.firstOrNull { it.id == theme(c) } ?: themes.first()

    /**
     * 一套胶囊配色。全是 `#AARRGGBB`/`#RRGGBB` 十六进制串（miui.focus JSON 直接吃字符串，
     * 流体云侧 [android.graphics.Color.parseColor] 解析进度段）。
     */
    data class CapsuleTheme(
        val id: String,
        val label: String,
        val title: String,        // 标题文字色
        val content: String,      // 正文文字色
        val progress: String,     // 进度起色
        val progressEnd: String,  // 进度终色（渐变）
        val track: String,        // 进度轨道（未完成段）
        val effect: String,       // 流光/高亮色（effectColor）
    )

    /** 可选主题。顺序即设置里的展示顺序。 */
    val themes: List<CapsuleTheme> = listOf(
        CapsuleTheme("xtom", "紫青", "#FFFFFF", "#B0FFFFFF", "#7C5CFF", "#00E0FF", "#22FFFFFF", "#7C5CFF"),
        CapsuleTheme("ocean", "海蓝", "#FFFFFF", "#B0FFFFFF", "#2E7DFF", "#00C6FF", "#22FFFFFF", "#2E7DFF"),
        CapsuleTheme("sunset", "落日", "#FFFFFF", "#B0FFFFFF", "#FF8A3D", "#FFD24D", "#22FFFFFF", "#FF8A3D"),
        CapsuleTheme("forest", "森绿", "#FFFFFF", "#B0FFFFFF", "#3DDC84", "#B6FF9E", "#22FFFFFF", "#3DDC84"),
        CapsuleTheme("mono", "纯白", "#FFFFFF", "#C0FFFFFF", "#FFFFFF", "#C0FFFFFF", "#22FFFFFF", "#FFFFFF"),
    )
}
