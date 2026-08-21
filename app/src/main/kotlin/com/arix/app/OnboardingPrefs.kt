package com.arix.app

import android.content.Context
import androidx.compose.runtime.mutableStateOf

// ============================================================
// 新手向导的「跑没跑过」标记 + 进程级显示闸门。
//
// 存 SharedPreferences 而不是 Room：向导只是一次性引导，跟业务数据无关，不进 DB。
// 存的是**版本号**不是布尔——以后向导加了必须让老用户也过一遍的步骤（比如新增一项关键权限），
// 把 VERSION 加 1 即可让存量用户再走一次；不想惊动老用户就别动它。
// ============================================================
object OnboardingPrefs {
    private const val PREFS = "xtom_onboarding"
    private const val VERSION = 1

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun done(c: Context): Boolean = p(c).getInt("done_version", 0) >= VERSION
    fun markDone(c: Context) = p(c).edit().putInt("done_version", VERSION).apply()
    fun reset(c: Context) = p(c).edit().remove("done_version").apply()
}

/**
 * 向导的显示闸门。放进程级（不是 Activity 级）是因为：
 * ① 切语言时 XtomTheme 用 key(lang) 重建整棵树，Activity 级 remember 会被清掉、向导中途消失；
 * ② 设置页里的「重新运行向导」要能从 MainScreen 内部把它拉起来。
 *
 * [init] 只在进程内认第一次：之后 Activity 因故重建（分屏/字体缩放等）不会把手动拉起的向导顶掉。
 */
object OnboardingGate {
    val show = mutableStateOf(false)
    private var initialized = false

    fun init(c: Context) {
        if (initialized) return
        initialized = true
        show.value = !OnboardingPrefs.done(c)
    }

    /** 设置页/抽屉点「新手向导」走这里。 */
    fun open() { show.value = true }

    /** 走完或跳过：都算走过（跳过也是用户的决定，不该下次再拦他）。 */
    fun finish(c: Context) {
        OnboardingPrefs.markDone(c)
        show.value = false
    }
}

// ============================================================
// 向导结束后的「真实界面点位引导」标记。
//
// 向导正常走完（留在聊天页）→ arm()：主界面渲染出来后叠一层蒙层，
// 用光圈在真实 UI 上圈出「输入框、抽屉菜单」等关键点位，教用户点哪里能干什么。
// 只有 armed && !done 才显示 → 首次走完与重跑向导都能触发；跳过向导/跳去别的页不触发。
// 蒙层看完或点「跳过引导」→ markDone()。重跑向导时 arm() 会把 done 一起清掉，保证再触发。
// ============================================================
object FirstUseGuidePrefs {
    private const val PREFS = "xtom_first_use_guide"

    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun armed(c: Context): Boolean = p(c).getBoolean("armed", false)
    fun done(c: Context): Boolean = p(c).getBoolean("done", false)
    fun arm(c: Context) = p(c).edit().putBoolean("armed", true).putBoolean("done", false).apply()
    fun markDone(c: Context) = p(c).edit().putBoolean("done", true).apply()
}
