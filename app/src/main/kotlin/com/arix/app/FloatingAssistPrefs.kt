package com.arix.app

import android.content.Context

/**
 * 「不进 App 也能用」的两条入口的开关：**耳机/媒体键唤起** 与 **常驻悬浮球**。
 *
 * 为什么单开一个 Prefs 而不是并进 [WakeService] 的那堆唤醒设置：唤醒那套管的是「靠声音把他叫出来」
 * （热词、麦克风窗口、省电档），这里管的是「靠一次物理动作把他叫出来」——按线控、点小球。
 * 两者的失效模式与代价完全不同：唤醒的代价是麦克风与电，这两条的代价是**抢别人的媒体键**和
 * **一直盖在别人 App 上面的一个窗口**，都属于"越界"型风险，必须能单独关、且默认关。
 *
 * ⚠ 读取走**进程内缓存**（同 [ChatEffectsPrefs]）：媒体键快照会在每一次按键事件里被读到
 * （单击/双击判定窗口内可能连读三四次），悬浮球快照会在拖动的每一帧被读到——都不是能去读盘的地方。
 * 写入时同步刷缓存，所以设置页一改立刻生效。
 *
 * ⚠ 球的坐标 [ballX]/[ballY] **不在** [Snapshot] 里：它每次松手都要写一次盘，
 * 混进快照会让"改一个开关"和"挪一下球"共用一条写路径，互相把对方的值写回去。
 */
object FloatingAssistPrefs {

    private const val PREF = "xtom_floating_assist"

    /** 一次按键能绑的动作。NONE = 这一档不接管（按下去等于没按，键照常交给系统）。 */
    enum class KeyAction { NONE, VOICE, NEW_CHAT, STOP_GEN, TOGGLE_BALL }

    /**
     * 媒体键的接管范围。这是本文件里唯一真正危险的设置项，两档的取舍：
     *
     *  - [WHEN_ACTIVE]（默认）：只在「本 App 在前台 / 助手浮层开着 / 悬浮球面板开着」时才登记媒体会话，
     *    其余时间**根本不注册**，音乐 App 的线控行为一个字节都不受影响。代价：戴着耳机、屏幕黑着、
     *    App 在后台时按线控是**没反应**的——而那恰恰是手表上最想要的场景。
     *  - [ALWAYS]：一直登记着。这才是"按一下线控就开始说话"，但意味着**我们和音乐 App 在抢同一个键**。
     *    抢的部分靠 [Snapshot.yieldToMusic] 兜住（正在放歌就把键原样转发回去），
     *    但仍有 ROM 差异，属于用户自己选、自己承担的一档，绝不默认。
     */
    enum class TakeoverScope { WHEN_ACTIVE, ALWAYS }

    /** 一次性读全的快照。按键路径 / 拖动路径只碰它，不碰 SharedPreferences。 */
    data class Snapshot(
        // ── 耳机 / 媒体键 ──
        val mediaKeyEnabled: Boolean,
        val scope: TakeoverScope,
        /**
         * 正在放歌时把键让回去。**默认开，而且强烈建议一直开着**：
         * 用户听歌时按线控是要暂停音乐的，这件事的优先级高于任何 AI 功能。
         * 关掉它 = 听歌时按线控会唤起助手而不是暂停，那是"抢键"的实锤。
         */
        val yieldToMusic: Boolean,
        val singleTap: KeyAction,
        val doubleTap: KeyAction,
        val longPress: KeyAction,

        // ── 悬浮球 ──
        val ballEnabled: Boolean,
        /** 开机后自动把球放回去（进程被杀也靠前台服务 START_STICKY 兜一层）。 */
        val ballRestoreOnBoot: Boolean,
        /** 点开就直接全屏面板。手表屏幕小，紧凑面板容易挤，默认给用户自己选。 */
        val ballFullScreenPanel: Boolean,
    )

    /**
     * 默认值 = **两个功能都关**。
     *
     * 这不是保守，是这两条的失败代价决定的：媒体键开着就是在和音乐 App 抢键，
     * 悬浮球开着就是一个永远盖在别人界面上的窗口 + 一条常驻前台通知。
     * 两者都属于"用户没主动要就不该发生"的那一类，和自动朗读同级（见 AutoReadPrefs 的注释）。
     *
     * 各档动作的默认绑定按「误触代价从小到大」排：单击最容易误触 → 给唤起（最无害，关掉就行）；
     * 双击 → 新对话；长按（最难误触）→ 停止当前生成（唯一会中断正在进行的事的动作）。
     */
    val DEFAULT = Snapshot(
        mediaKeyEnabled = false,
        scope = TakeoverScope.WHEN_ACTIVE,
        yieldToMusic = true,
        singleTap = KeyAction.VOICE,
        doubleTap = KeyAction.NEW_CHAT,
        longPress = KeyAction.STOP_GEN,
        ballEnabled = false,
        ballRestoreOnBoot = true,
        ballFullScreenPanel = false,
    )

    // ---- 进程内缓存（见类注释）。null = 还没加载过。 ----
    @Volatile private var cached: Snapshot? = null

    fun snapshot(c: Context): Snapshot = cached ?: load(c).also { cached = it }

    private fun load(c: Context): Snapshot {
        val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        fun action(key: String, def: KeyAction): KeyAction =
            runCatching { KeyAction.valueOf(sp.getString(key, def.name)!!) }.getOrDefault(def)
        return Snapshot(
            mediaKeyEnabled = sp.getBoolean("media_key_enabled", DEFAULT.mediaKeyEnabled),
            scope = runCatching { TakeoverScope.valueOf(sp.getString("scope", DEFAULT.scope.name)!!) }
                .getOrDefault(DEFAULT.scope),
            yieldToMusic = sp.getBoolean("yield_to_music", DEFAULT.yieldToMusic),
            singleTap = action("single_tap", DEFAULT.singleTap),
            doubleTap = action("double_tap", DEFAULT.doubleTap),
            longPress = action("long_press", DEFAULT.longPress),
            ballEnabled = sp.getBoolean("ball_enabled", DEFAULT.ballEnabled),
            ballRestoreOnBoot = sp.getBoolean("ball_restore_on_boot", DEFAULT.ballRestoreOnBoot),
            ballFullScreenPanel = sp.getBoolean("ball_fullscreen_panel", DEFAULT.ballFullScreenPanel),
        )
    }

    fun save(c: Context, s: Snapshot) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("media_key_enabled", s.mediaKeyEnabled)
            .putString("scope", s.scope.name)
            .putBoolean("yield_to_music", s.yieldToMusic)
            .putString("single_tap", s.singleTap.name)
            .putString("double_tap", s.doubleTap.name)
            .putString("long_press", s.longPress.name)
            .putBoolean("ball_enabled", s.ballEnabled)
            .putBoolean("ball_restore_on_boot", s.ballRestoreOnBoot)
            .putBoolean("ball_fullscreen_panel", s.ballFullScreenPanel)
            .apply()
        cached = s   // 同步刷缓存：设置页改完立刻生效
    }

    fun reset(c: Context) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        cached = DEFAULT
    }

    /** 只改「悬浮球开着没」这一位，不动别的（拖到删除区关球、服务里自愈时用）。 */
    fun setBallEnabled(c: Context, on: Boolean) {
        save(c, snapshot(c).copy(ballEnabled = on))
    }

    // ── 悬浮球的落点（见类注释：故意不进快照）──────────────────────────────

    /** -1 = 还没拖过，由调用方落默认位。 */
    fun ballX(c: Context): Int = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("ball_x", -1)
    fun ballY(c: Context): Int = c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getInt("ball_y", -1)

    fun saveBallSpot(c: Context, x: Int, y: Int) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putInt("ball_x", x).putInt("ball_y", y).apply()
    }
}
