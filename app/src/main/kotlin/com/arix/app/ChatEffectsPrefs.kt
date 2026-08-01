package com.arix.app

import android.content.Context

/**
 * 聊天特效开关 —— 触感、"正在输入"、滑动回复、流式渐显、按压回弹。
 *
 * 为什么单开一个 Prefs 而不是并进 [ChatAppearancePrefs]：那个管的是**长什么样**（形状/配色/头像），
 * 这里管的是**怎么动**（动效与反馈）。两者的取舍标准也不同——外观是纯偏好，动效要拿性能和耗电去换，
 * 所以每一项都必须能单独关掉，出了问题能一项一项排除。
 *
 * ⚠ 读取走**进程内缓存**，不是每次都开 SharedPreferences：这些值会在气泡渲染、滚动、每个 token 的
 * 流式路径上被读到，那是全项目最热的几条路径之一，去读盘会直接把列表卡住（本项目为此栽过，
 * 见 [ChatBubbleItem] 里"绝不能在这里调 ChatAppearancePrefs.style()"那段注释）。
 * 写入时同步刷新缓存，所以设置页一改立刻生效，不用重进页面。
 */
object ChatEffectsPrefs {

    private const val PREF = "xtom_chat_effects"

    /** 触感强度档。手表上马达质感差异很大，给个"关"是必须的。 */
    enum class Haptic { OFF, LIGHT, FULL }

    // ---- 进程内缓存（见类注释）。null = 还没加载过。 ----
    @Volatile private var cached: Snapshot? = null

    /** 一次性读全的快照。渲染侧只碰它，不碰 SharedPreferences。 */
    data class Snapshot(
        val haptic: Haptic,
        /** 横向拖气泡触发"回复这条"。 */
        val swipeToReply: Boolean,
        /** 流式正文的下边缘做渐隐遮罩，新字像是"浮出来"的。 */
        val streamReveal: Boolean,
        /** 气泡按下时轻微缩一下（松手回弹）。 */
        val pressBounce: Boolean,
    )

    /** 默认值 = **全开但克制**。这些都是每条消息只发生一次的短动画，不是常驻动画，
     *  不会有"一直申请帧回调"的耗电问题（那种在本项目里一律默认关，见 rememberBreath 的 cycles）。 */
    val DEFAULT = Snapshot(
        haptic = Haptic.LIGHT,
        swipeToReply = true,
        streamReveal = true,
        pressBounce = true,
    )

    fun snapshot(c: Context): Snapshot = cached ?: load(c).also { cached = it }

    private fun load(c: Context): Snapshot {
        val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Snapshot(
            haptic = runCatching { Haptic.valueOf(sp.getString("haptic", DEFAULT.haptic.name)!!) }
                .getOrDefault(DEFAULT.haptic),
            swipeToReply = sp.getBoolean("swipe_to_reply", DEFAULT.swipeToReply),
            streamReveal = sp.getBoolean("stream_reveal", DEFAULT.streamReveal),
            pressBounce = sp.getBoolean("press_bounce", DEFAULT.pressBounce),
        )
    }

    fun save(c: Context, s: Snapshot) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString("haptic", s.haptic.name)
            .putBoolean("swipe_to_reply", s.swipeToReply)
            .putBoolean("stream_reveal", s.streamReveal)
            .putBoolean("press_bounce", s.pressBounce)
            .apply()
        cached = s   // 同步刷缓存：设置页改完立刻生效
    }

    fun reset(c: Context) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        cached = DEFAULT
    }
}
