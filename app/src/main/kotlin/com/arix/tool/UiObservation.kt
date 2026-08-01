package com.arix.tool

import android.content.Context
import android.os.Build
import android.os.SystemClock
import com.arix.app.UiNode
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

// ============================================================
// 观察绑定（observation binding）+ 坐标空间合约（coordinate space contract）
//
// 要解决的是界面自动化里最隐蔽的一类错：**模型看到的屏幕和它动手时的屏幕不是同一块**。
// `dump → tap` 是两次独立的往返，中间隔着一整轮模型推理（常常好几秒）。这期间：
// 动画收尾、页面自己跳走、通知弹出来、列表回弹……坐标还是那个坐标，底下的东西已经换人了。
// 点错的那一下不会报错，它会**成功地点到别的东西上**——这比失败糟得多。
//
// 做法：每次 dump 定格一次「观察」并发一个 id，动作把 id 带回来；服务端拿它比对
// 「还是不是最新那次观察 / 有没有放太久 / 前台 App 换没换 / (x,y) 上还是不是当初那个元素」，
// 对不上就**不执行**，回一条讲清楚该干嘛的结构化错误让模型重新 dump。
//
// 三条自我约束（都是为了「不能把现有能用的流程弄挂」）：
//  1. **渐进式收紧**：不带 observation_id 的调用照旧执行，只在结果里提醒下次带上。
//     现存的技能回放、JS 插件、老对话全都不带 id，一刀切等于把它们全判死。
//  2. **只在能补救时硬拒**：读屏只有无障碍做得到；Shizuku 盲态下 dump 根本跑不了，
//     这时硬拒会把模型逼进「重新观察→观察不了→再拒」的死循环，所以降级成警告放行。
//  3. **过期有逃生口**：模型慢到每轮都超窗口时（推理模型很常见），连续两次因过期被拒后
//     第三次放行并警告，否则同样是死循环。硬上限 [UiObservationStore.HARD_STALE_MS] 兜住最坏情况。
// ============================================================

/**
 * 一次读屏观察的定格。
 *
 * 只存 [UiNode]（Rect + 文字，纯数据），**绝不持有 AccessibilityNodeInfo**——那是有生命周期的
 * 系统句柄，跨轮次留着既可能泄漏也可能读到早已失效的树。
 */
data class UiObservationRecord(
    val id: String,
    val seq: Long,
    /** 单调时钟。用它算年龄而不是 wall clock：用户改系统时间/时区跳变不该让观察凭空过期或永不过期。 */
    val atElapsedMs: Long,
    /** 墙上时间，只用于展示给模型看「这次观察是几点抓的」。 */
    val atWallMs: Long,
    val screenW: Int,
    val screenH: Int,
    val pkg: String?,
    val nodes: List<UiNode>,
) {
    val ageMs: Long get() = SystemClock.elapsedRealtime() - atElapsedMs

    /**
     * 观察当时 (x,y) 落在哪个元素上。取包含该点的**最小**矩形——嵌套布局里最小的那个
     * 才是用户眼中被点的东西（和 UiControlTool.labelAt / UiDangerGuard 用的是同一条口径）。
     */
    fun labelAt(x: Int, y: Int): String =
        nodes.filter { it.bounds.contains(x, y) }
            .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }?.label.orEmpty()
}

/** 最新一次观察的登记处（进程内单例，只留最新一条——「对不上最新观察」就是判据本身）。 */
object UiObservationStore {

    /**
     * 新鲜期。超过就要求重新观察。
     *
     * 8 秒是这么定的：往下，一次「模型收到 dump → 想清楚 → 发出 tap」的正常往返就要 1~5 秒，
     * 定到 3 秒会把大量本来正确的操作误杀成「过期」；往上，界面自己变的典型时间尺度——
     * 转场动画 200~500ms、页面跳转 1 秒内、通知弹出随时——都远在 8 秒以内，定到 30 秒
     * 这道闸就形同虚设。而且过期只是**兜底**：真正抓变化的是 id 比对、前台包比对和
     * 目标元素比对，它们不看时间，所以这里可以取一个偏宽松、不误伤的值。
     */
    const val STALE_MS = 8_000L

    /** 绝对上限：再怎么给逃生口，一分钟前看到的屏幕也不能拿来盲点。 */
    const val HARD_STALE_MS = 60_000L

    /** 连续因过期被拒几次之后放行一次（防「模型天生就慢」造成的死循环）。 */
    private const val MAX_EXPIRY_STREAK = 2

    private val seq = AtomicLong(0)

    /**
     * 进程随机前缀。没有它，App 重启后计数器从 1 重来，历史对话里那条 "obs-3" 会和新
     * 进程的 "obs-3" 撞上——凭据对上了，屏幕却毫无关系，正好绕过整套校验。
     */
    private val nonce: String = "%04x".format(((System.nanoTime() ushr 8).toInt() xor android.os.Process.myPid()) and 0xffff)

    @Volatile
    private var current: UiObservationRecord? = null

    private val expiryStreak = AtomicInteger(0)

    /** 上一次「因过期被拒」发生在什么时候（单调）。用来判断当前这次还算不算同一段连拒。 */
    @Volatile
    private var lastExpiryRejectAt = 0L

    /** 隔了这么久还没再撞上过期，就不算「连着」了——计数必须清掉，否则会跨会话攒到阈值上。 */
    private const val STREAK_WINDOW_MS = 60_000L

    fun latest(): UiObservationRecord? = current

    /** 登记一次新观察，返回它（id 在进程内严格递增）。 */
    fun record(screenW: Int, screenH: Int, pkg: String?, nodes: List<UiNode>): UiObservationRecord {
        val n = seq.incrementAndGet()
        val rec = UiObservationRecord(
            id = "obs-$nonce-$n",
            seq = n,
            atElapsedMs = SystemClock.elapsedRealtime(),
            atWallMs = System.currentTimeMillis(),
            screenW = screenW, screenH = screenH, pkg = pkg, nodes = nodes,
        )
        current = rec
        return rec
    }

    /**
     * 作废当前观察。读屏读了个空（无障碍刚断、界面在切）时调用：
     * 这时我们**确知自己看不见屏幕**，留着上一条旧观察只会让模型拿着一个「有效」的 id 去盲点。
     */
    fun invalidate() { current = null }

    /**
     * id 比对，故意留一点宽容度——和 [ToolArgValidator] 的取向一致：模型/内联工具调用会把
     * 值序列化、加引号、或只回一个序号。这些形变不改变「他指的是哪一次观察」这个事实，
     * 拒了只是浪费一个往返；而真正指错的（序号不同）照样对不上，安全性没有让步。
     */
    fun matches(given: String, rec: UiObservationRecord): Boolean {
        val g = given.trim().trim('"', '\'').lowercase()
        if (g.isEmpty()) return false
        val cur = rec.id.lowercase()
        return g == cur || g == rec.seq.toString() || cur.endsWith("-$g")
    }

    /**
     * 记一次「因过期被拒」；返回 true 表示这次该放行（连拒太多，再拒就是死循环）。
     *
     * 只认**连着发生**的拒绝：隔了一分钟以上的两次过期是两回事，不该攒在一起——
     * 不然计数会跨会话累积，某个新会话里第一次过期就被当成「已经拒过两回」直接放行。
     */
    @Synchronized
    fun noteExpiryAndShouldPassThrough(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - lastExpiryRejectAt > STREAK_WINDOW_MS) expiryStreak.set(0)
        lastExpiryRejectAt = now
        return expiryStreak.incrementAndGet() > MAX_EXPIRY_STREAK
    }

    /** 校验通过就把连拒计数清零——只有「真的成功执行过一次」才算走出死循环。 */
    fun resetExpiryStreak() { expiryStreak.set(0) }
}

/** 屏幕坐标空间：动作吃的是**真实屏幕像素**，这里负责把这件事说成一份可执行的合约。 */
object UiScreenSpace {

    /** 模型看到的截图会被降采样到最长边这个数（与 ImageOcrTool / ChatScreen.fitImageForContext 同一条规则）。 */
    private const val SCREENSHOT_MAX_SIDE = 1600

    /**
     * 真实屏幕像素尺寸（含系统栏区域）——无障碍手势注入用的就是这个坐标系，
     * 所以不能用 displayMetrics 那份「应用可用区域」去凑。
     */
    fun size(context: Context): Pair<Int, Int> {
        try {
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            if (wm != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    val b = wm.currentWindowMetrics.bounds
                    if (b.width() > 0 && b.height() > 0) return b.width() to b.height()
                } else {
                    @Suppress("DEPRECATION")
                    val p = android.graphics.Point().also { wm.defaultDisplay.getRealSize(it) }
                    if (p.x > 0 && p.y > 0) return p.x to p.y
                }
            }
        } catch (_: Exception) {
            // 拿不到就退到 displayMetrics：数字可能小一圈（不含系统栏），但有个数总比没有强——
            // 这份声明的价值在于给模型一个换算基准，宁可略有出入也不要缺席。
        }
        val dm = context.resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    fun density(context: Context): Float = try { context.resources.displayMetrics.density } catch (_: Exception) { 0f }

    /** 截图被降采样的倍数（2 的幂，和项目里两处解码逻辑同规则）。1 = 没缩。 */
    private fun screenshotSample(w: Int, h: Int): Int {
        var sample = 1
        val maxSide = maxOf(w, h)
        while (maxSide > 0 && maxSide / sample > SCREENSHOT_MAX_SIDE && sample < 64) sample *= 2
        return sample
    }

    /**
     * 观察结果的抬头：**观察凭据 + 坐标空间合约**。全英文——这段每次 dump 都要发给模型。
     *
     * 为什么非写不可：截图路径上模型看到的图是被降采样过的（最长边 ≤1600），它据此读出来的
     * 像素坐标和 tap 真正吃的屏幕坐标**是两个空间**，此前项目里一处声明都没有。
     * 现在多数流程走 click_text 绕开了坐标才没大面积炸，但那是运气不是设计。
     */
    fun contract(context: Context, rec: UiObservationRecord?): String {
        val (w, h) = if (rec != null && rec.screenW > 0) rec.screenW to rec.screenH else size(context)
        val d = density(context)
        val sample = screenshotSample(w, h)
        val sb = StringBuilder()
        if (rec != null) {
            sb.append("[observation] id=").append(rec.id)
                .append(" captured_at=").append(rec.atWallMs)
                .append(" (").append(clock(rec.atWallMs)).append(")")
                .append(" freshness_window=").append(UiObservationStore.STALE_MS).append("ms\n")
        }
        sb.append("[coordinate_space] screen_px | screen=").append(w).append('x').append(h)
        if (d > 0f) sb.append(" | density=").append(d)
        sb.append(" | origin=top-left | valid x:0..").append((w - 1).coerceAtLeast(0))
            .append(" y:0..").append((h - 1).coerceAtLeast(0)).append('\n')
        sb.append("tap/long_press/swipe x,y,x2,y2 are raw screen pixels in this space, and the coordinates listed below are already in it - pass them through unchanged.\n")
        if (sample > 1) {
            sb.append("Screenshots you are shown are downsampled (longest side <= ").append(SCREENSHOT_MAX_SIDE)
                .append("), expect about ").append(w / sample).append('x').append(h / sample)
                .append(", i.e. screen_px = image_px * ").append(sample)
                .append(". Verify against the real image size: screen_x = img_x * ").append(w)
                .append(" / img_width, screen_y = img_y * ").append(h)
                .append(" / img_height. Never pass image pixels as x,y.\n")
        } else {
            sb.append("If you read coordinates off a screenshot image instead, convert first: screen_x = img_x * ")
                .append(w).append(" / img_width, screen_y = img_y * ").append(h)
                .append(" / img_height. Never pass image pixels as x,y.\n")
        }
        if (rec != null) {
            sb.append("Pass observation_id=\"").append(rec.id)
                .append("\" with your next tap/long_press/swipe/set_text so it can be checked against this screen; if it is refused as stale, dump again before acting. click_text needs no id.\n")
        }
        return sb.toString()
    }

    private fun clock(wallMs: Long): String = try {
        java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(wallMs))
    } catch (_: Exception) { "" }
}

/**
 * 动作侧的校验。给 [UiControlTool] 用，逻辑单独放这里是因为它有自己的一套规则要长期调
 * （阈值、宽容度、哪些动作受管），塞进工具的 when 分支里会变成翻不动的 if 丛林。
 */
object UiObservationBinding {

    /**
     * 受观察绑定管的动作。
     *
     * **click_text 故意不在里面**：它按文字找目标，压根不吃坐标——界面变了它顶多找不到，
     * 不会点错人。给它加约束纯属给一条本来就安全、而且是现在主力在走的路添堵。
     * back/home/recents/notifications 同理：系统键和屏幕内容无关。
     */
    private val BOUND_ACTIONS = setOf("tap", "long_press", "swipe", "set_text")

    /**
     * 失败归类。故意**不用** "bad_args"：那会被 [com.arix.app.LessonRecorder] 记成一条长期教训，
     * 而「观察过期了，重新 dump 一下」是协议里正常的一次重来，不是模型犯的错，
     * 记下来只会往记忆里灌噪音。用一个它不认识的类别 = 有结构可判、但不沉淀。
     */
    private const val FAIL_KIND = "stale_observation"

    private const val NOT_EXECUTED = "NOT EXECUTED (stale observation)."
    private const val REDUMP = " Call ui_control(action=\"dump\") again, then repeat this action with the observation_id from that newest dump. Do not reuse the old coordinates."

    /** 预检结论：[reject] 非空 = 不执行；[hint] 非空 = 照常执行，但把这句话贴到结果末尾提醒模型。 */
    data class Check(val reject: ToolResult? = null, val hint: String = "")

    /** 读参数里的观察 id。顺手认一下驼峰写法——模型偶尔会写成 observationId，为这个多跑一轮不值。 */
    fun idOf(params: JSONObject): String {
        val raw = params.optString("observation_id", "").ifBlank { params.optString("observationId", "") }
        return raw.trim().trim('"', '\'')
    }

    /**
     * 动作执行前的粗检：id 对不对得上最新观察、放没放过期、前台 App 换没换。
     *
     * ⚠ 调用点必须在 [UiDangerGuard.guard] **之前**：一来本来就不该执行的一步没必要先去打扰
     * 用户点确认；二来确认弹窗是挂起等人的，用户想上几十秒，那段时间不该算进观察的新鲜期里。
     */
    fun precheck(
        context: Context,
        svc: com.arix.app.XtomAccessibilityService?,
        action: String,
        params: JSONObject,
    ): Check {
        if (action !in BOUND_ACTIONS) return Check()
        val given = idOf(params)

        // 向后兼容的核心：没带 id 一律照旧执行，只提醒。技能回放、JS 插件、老对话都不带 id，
        // 在这里硬拒等于一次性把它们全弄挂——渐进式收紧的意思就是先让模型学会带，再谈拒。
        if (given.isBlank()) return Check(hint = HINT_MISSING)

        // 只有无障碍这条路读得了屏 = 只有它 dump 得动。Shizuku 盲态下让模型「重新观察」是句
        // 它执行不了的指令，硬拒 = 死循环，所以降级成警告放行。
        val canReobserve = svc != null

        val rec = UiObservationStore.latest()
            ?: return outcome(canReobserve, "No screen observation is on record for id \"$given\" (the app may have restarted, or the last dump could not read the screen).")

        if (!UiObservationStore.matches(given, rec)) {
            return outcome(canReobserve, "observation_id \"$given\" is not the current observation (latest is \"${rec.id}\"); the screen has been re-read since you looked at it.")
        }

        val age = rec.ageMs
        if (age > UiObservationStore.STALE_MS) {
            // 逃生口：连着被拒太多次说明模型的往返本身就慢过窗口，再拒下去它永远做不成事。
            if (age <= UiObservationStore.HARD_STALE_MS && UiObservationStore.noteExpiryAndShouldPassThrough()) {
                UiObservationStore.resetExpiryStreak()
                return Check(hint = "[observation] Ran anyway on an expired observation (${rec.id}, ${age}ms old, window ${UiObservationStore.STALE_MS}ms) because re-observing twice in a row still did not beat the window. The screen may have changed under you - check the result and dump again before the next step.")
            }
            return outcome(canReobserve, "Observation \"${rec.id}\" is ${age}ms old, past the ${UiObservationStore.STALE_MS}ms freshness window; the screen may have moved on (animation finished, auto-navigation, popup).")
        }

        // 前台 App 换了，那套坐标就不再指向任何东西了。
        // 读到我们自己包名时不判：让位/摘窗时序里 activeWindow 可能短暂是 Arix 自己的浮层，
        // 拿它当「App 变了」会造出一批莫名其妙的拒绝。
        val nowPkg = try { svc?.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        if (!rec.pkg.isNullOrBlank() && !nowPkg.isNullOrBlank() &&
            nowPkg != rec.pkg && nowPkg != context.packageName
        ) {
            return outcome(
                canReobserve,
                "The foreground app changed since observation \"${rec.id}\" (you observed ${rec.pkg}, the screen now belongs to $nowPkg); those coordinates no longer point at anything you saw.",
            )
        }

        UiObservationStore.resetExpiryStreak()
        return Check()
    }

    /**
     * 目标身份校验（只对坐标点按）：观察里 (x,y) 上是哪个元素，现在还是不是它。
     *
     * 这是整套机制里最实的一道——时间和包名都可能没变，元素却因为列表刷新/弹窗插入
     * 挪了位置。成本是零：`actualLabel` 是调用方为了画高亮框**本来就要取**的那一个。
     */
    fun verifyTarget(params: JSONObject, x: Int, y: Int, actualLabel: String): ToolResult? {
        val given = idOf(params)
        if (given.isBlank()) return null                       // 没带 id 就不加这道约束（向后兼容）
        val rec = UiObservationStore.latest() ?: return null    // 粗检已经处理过这些情况，这里不重复判
        if (!UiObservationStore.matches(given, rec)) return null
        val expected = rec.labelAt(x, y)
        if (!drifted(expected, actualLabel)) return null
        return ToolResult(
            "$NOT_EXECUTED ($x,$y) no longer holds what you observed: observation \"${rec.id}\" had \"$expected\" there, the screen now has \"$actualLabel\". The layout moved after you looked.$REDUMP" +
                " When the target has stable visible text, prefer action=\"click_text\" - it locates the element by text and does not depend on coordinates.",
            isError = true, failKind = FAIL_KIND,
        )
    }

    /**
     * 把提醒贴到结果末尾。只贴，不改 isError / failKind——这是一句给模型的旁注，
     * 不该让一次成功的操作看起来像失败。
     */
    fun withHint(result: ToolResult, hint: String): ToolResult =
        if (hint.isBlank()) result else result.copy(content = result.content.trimEnd() + "\n" + hint)

    /** 能重新观察就硬拒；不能（无障碍没开、只有 Shizuku）就降级成警告放行，别造死循环。 */
    private fun outcome(canReobserve: Boolean, reason: String): Check =
        if (canReobserve) Check(reject = ToolResult("$NOT_EXECUTED $reason$REDUMP", isError = true, failKind = FAIL_KIND))
        else Check(hint = "[observation] Unverified: $reason It ran anyway because reading the screen needs the accessibility service and that is off, so re-observing is impossible - check the outcome instead of assuming it worked.")

    private val HINT_MISSING =
        "[observation] This action carried no observation_id, so it could not be checked against the screen you saw; if the screen had changed, it just acted on the wrong thing. " +
            "Next time call ui_control(action=\"dump\") first and pass the observation_id it returns."

    /**
     * 两个标签算不算「变了人」。刻意保守，只在**双方都有文字且明显不是一回事**时才判变——
     * 时钟、未读数、进度这类文字每秒都在动，判太严会把一堆本来正确的点击拒掉，
     * 那比漏判更糟：模型会开始不信任这条通道，转而绕开它。
     */
    private fun drifted(expected: String, actual: String): Boolean {
        val e = expected.trim()
        val a = actual.trim()
        if (e.isEmpty() || a.isEmpty()) return false
        if (e.equals(a, ignoreCase = true)) return false
        return !e.contains(a, ignoreCase = true) && !a.contains(e, ignoreCase = true)
    }
}
