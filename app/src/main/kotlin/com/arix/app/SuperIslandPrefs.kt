package com.arix.app

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 「超级岛显示」开关（需 Shizuku·仅小米 HyperOS）。**默认关**。
 *
 * 开着且 Shizuku 已授权时，[LiveCapsuleController] 在 HyperOS 分支发焦点通知前，会经 [XmsfUnlock]
 * 把小米服务框架短暂断网让云控白名单 fail-open（免 root 真出岛）。关着 = 只发普通焦点通知（多半不出岛）。
 *
 * 即时生效：改配置后 [version] 自增，订阅它的设置界面跟随重组（同 ScreenFitPrefs 范式）。
 */
object SuperIslandPrefs {
    private const val PREFS = "xtom_super_island"
    private const val KEY_ENABLED = "enabled"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    private fun bump() { _version.value = _version.value + 1 }

    /** 是否启用「断网 xmsf 让焦点通知真出岛」。默认 false。 */
    fun enabled(c: Context): Boolean = p(c).getBoolean(KEY_ENABLED, false)

    fun setEnabled(c: Context, v: Boolean) { p(c).edit().putBoolean(KEY_ENABLED, v).apply(); bump() }
}
