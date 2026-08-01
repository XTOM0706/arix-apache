package com.arix.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 实时胶囊 · 阶段 A（免 Xposed）。
 *
 * 把 Arix 的「状态/回复」推进厂商的动态通知区：
 *  - HyperOS 2/3 超级岛：普通 ongoing 通知 + `miui.focus.*` extras（JSON 手拼 param_v2/protocol=3）。
 *  - ColorOS 16 / Android 16 流体云：Google Live Updates（`Notification.ProgressStyle` +
 *    `setRequestPromotedOngoing(true)`，全程反射调用以兼容 compileSdk < 36）。
 *  - 其它 ROM / 低版本：普通 ongoing 通知兜底（有 title/text/进度即可，绝不崩）。
 *
 * 外观/行为由 [CapsulePrefs] 决定（配色主题、流光动画、是否弹浮窗、结束常驻时长）；
 * 每次 [show] 现场读取，改设置即时生效。调用面：[show] / [dismiss] / [preview]。
 *
 * 真机验证点（别打包票，见 DESIGN-LIVE-CAPSULE.md）：
 *  ① HyperOS 非白名单 App 能否真的「出岛」（靠 SuperIslandPrefs+Shizuku 断 xmsf 放行）。
 *  ② HyperOS 2 vs 3 的 `paramIsland` 字段命名（驼峰 vs 蛇形）。
 *  ③ CO16 promoted 通知是否被 OPPO 二次拦、ProgressStyle 实际呈现。
 *  ④ effectColor 流光 / enableFloat 关闭 / 长文滚动 的真实观感。
 */
object LiveCapsuleController {

    private const val TAG = "LiveCapsule"
    private const val CHANNEL_ID = "xtom_live_capsule"
    private const val CHANNEL_NAME = "实时胶囊"
    /** 固定 id：更新 = 同 id 重 notify；结束 = cancel。 */
    private const val NOTIF_ID = 42090

    /** 岛内/通知小图标兜底——Arix 自己的资源（Material 火花，白色 silhouette）。 */
    private val FALLBACK_SMALL_ICON = R.drawable.ic_capsule_status

    private const val BUSINESS = "xtom_assistant"

    // ---- miui.focus extras keys ----
    private const val K_FOCUS_PARAM = "miui.focus.param"
    private const val K_FOCUS_PICS = "miui.focus.pics"
    private const val K_FOCUS_TICKER = "miui.focus.ticker"
    private const val PIC_NAME = "icon" // pic 基名；实际键名随状态图标变（见 picToken）→ 强制 HyperOS 重读刷新
    private const val K_FOCUS_PIC_PREFIX = "miui.focus.pic_"
    // 岛内动作（展开岛显示）：走 miui.focus.actions 额外 bundle，**不入** notification.actions，故不触发
    // 「有交互→当普通通知不浮岛」的判定。HyperOS 认则展开岛出按钮、不认则忽略，两种都不碎岛。
    private const val K_FOCUS_ACTIONS = "miui.focus.actions"
    private const val K_FOCUS_ACTION_PREFIX = "miui.focus.action_"

    /** pic 键名：随状态图标资源变。同一 pic 名 HyperOS 会缓存首图、后续重发不刷新→图标像卡住不变。 */
    private fun picToken(style: CapsuleStyle, iconBitmap: Bitmap?): String =
        if (iconBitmap != null) PIC_NAME + "_bmp" else PIC_NAME + "_" + style.iconRes

    private enum class Rom { HYPEROS, LIVE_UPDATES, OTHER }

    /**
     * 一次胶囊呈现的视觉/行为参数。由 [CapsulePrefs] 现场组装：颜色主题 + 状态图标 + 流光 + 浮窗 + 是否可划走。
     * @param dismissible true=非 ongoing、用户可手动划走（结束常驻态用）；false=ongoing 常驻不可划（活动进行中用）。
     */
    data class CapsuleStyle(
        val theme: CapsulePrefs.CapsuleTheme,
        val iconRes: Int,
        val animated: Boolean,
        val float: Boolean,
        val dismissible: Boolean,
    )

    private fun styleFrom(
        ctx: Context,
        iconRes: Int,
        dismissible: Boolean,
        forceFloat: Boolean = false,
    ): CapsuleStyle = CapsuleStyle(
        theme = CapsulePrefs.palette(ctx),
        iconRes = iconRes,
        animated = CapsulePrefs.animated(ctx),
        // 弹浮窗默认关（用户反馈别弹脸上）；只有预览这类显式「就是要看它弹一下」才 forceFloat。
        float = forceFloat || CapsulePrefs.floatPopup(ctx),
        dismissible = dismissible,
    )

    /** 断网+发通知这条异步链路专用 scope（进程级、不绑 Activity）。 */
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun notifyOnce(ctx: Context, notification: Notification) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, notification)
        } catch (_: Throwable) { }
    }

    // ---- 点击 / 动作按钮 ------------------------------------------------------
    // 点击岛 = 打开 App（此前缺 contentIntent，点岛没反应）；两个动作按钮 = 停止 / 岛内直接输入。
    // 超级岛与流体云共用同一套 PendingIntent（超级岛点击走 contentIntent；流体云/普通通知点击同理）。

    /** 点击岛 → 拉起 MainActivity。 */
    private fun openAppIntent(ctx: Context): PendingIntent? = try {
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        PendingIntent.getActivity(
            ctx, 42090, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    } catch (_: Throwable) { null }

    private fun stopPendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 42093,
            Intent(ctx, CapsuleActionReceiver::class.java)
                .setAction(CapsuleActionBridge.ACTION_STOP).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    // RemoteInput 要求 PendingIntent 可变（FLAG_MUTABLE），否则系统不给往里塞输入结果。
    private fun replyPendingIntent(ctx: Context): PendingIntent =
        PendingIntent.getBroadcast(
            ctx, 42094,
            Intent(ctx, CapsuleActionReceiver::class.java)
                .setAction(CapsuleActionBridge.ACTION_REPLY).setPackage(ctx.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )

    // NotificationCompat 版动作（超级岛 / 普通通知兜底用）
    private fun stopAction(ctx: Context): NotificationCompat.Action =
        NotificationCompat.Action.Builder(R.drawable.ic_capsule_stop, "停止", stopPendingIntent(ctx)).build()

    private fun replyAction(ctx: Context): NotificationCompat.Action {
        val ri = RemoteInput.Builder(CapsuleActionBridge.KEY_REPLY_TEXT).setLabel("对 Arix 说…").build()
        return NotificationCompat.Action.Builder(R.drawable.ic_capsule_reply, "输入", replyPendingIntent(ctx))
            .addRemoteInput(ri)
            .setAllowGeneratedReplies(false)
            .build()
    }

    // 原生版动作（流体云 android.app.Notification.Builder 用，API36 分支）
    private fun stopActionNative(ctx: Context): Notification.Action =
        Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_capsule_stop), "停止", stopPendingIntent(ctx)
        ).build()

    private fun replyActionNative(ctx: Context): Notification.Action {
        val ri = android.app.RemoteInput.Builder(CapsuleActionBridge.KEY_REPLY_TEXT).setLabel("对 Arix 说…").build()
        return Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_capsule_reply), "输入", replyPendingIntent(ctx)
        ).addRemoteInput(ri).build()
    }

    /**
     * 显示/更新实时胶囊。同一 [NOTIF_ID] 复用——再次调用即原地更新。
     *
     * @param iconRes    状态矢量图标（听/思考/工具/搜索/完成…）；[iconBitmap] 非空时优先用位图。
     * @param dismissible true=可手动划走（结束常驻态）；false=ongoing（活动进行中）。
     */
    fun show(
        ctx: Context,
        title: String,
        text: String,
        progress: Int? = null,
        iconRes: Int = FALLBACK_SMALL_ICON,
        iconBitmap: Bitmap? = null,
        dismissible: Boolean = false,
    ) {
        showStyled(ctx, title, text, progress, iconBitmap, styleFrom(ctx, iconRes, dismissible))
    }

    /**
     * 预览：按当前设置立刻在岛上演示一下（**允许弹浮窗**，好让用户看清动画），几秒后自动收。
     * 用于设置页「试一下」——即「调用超级岛的动画功能」。
     */
    fun preview(ctx: Context) {
        val style = styleFrom(ctx, R.drawable.ic_capsule_done, dismissible = false, forceFloat = true)
        showStyled(ctx, "Arix", "超级岛预览 · 流光/配色", 66, null, style)
        ioScope.launch {
            try { kotlinx.coroutines.delay(3500) } catch (_: Throwable) {}
            dismiss(ctx.applicationContext)
        }
    }

    private fun showStyled(
        ctx: Context,
        title: String,
        text: String,
        progress: Int?,
        iconBitmap: Bitmap?,
        style: CapsuleStyle,
    ) {
        try {
            ensureChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val clamped = progress?.coerceIn(0, 100)
            val rom = detectRom(ctx)
            val notification: Notification = when (rom) {
                Rom.HYPEROS -> buildHyperOs(ctx, title, text, clamped, iconBitmap, style)
                Rom.LIVE_UPDATES -> buildLiveUpdates(ctx, title, text, clamped, iconBitmap, style)
                    ?: buildFallback(ctx, title, text, clamped, iconBitmap, style)
                Rom.OTHER -> buildFallback(ctx, title, text, clamped, iconBitmap, style)
            }
            // HyperOS 超级岛：开了「超级岛显示」开关且 Shizuku 已授权 → 发这条焦点通知时把 xmsf 短暂断网，
            // 让云控白名单 fail-open 放行（免 root 真出岛）。失败/未绑上会退化成直接发普通通知，绝不崩。
            if (rom == Rom.HYPEROS && SuperIslandPrefs.enabled(ctx) && ShizukuMic.hasPermission()) {
                val appCtx = ctx.applicationContext
                ioScope.launch {
                    try {
                        XmsfUnlock.withXmsfNetworkCut { notifyOnce(appCtx, notification) }
                    } catch (c: kotlinx.coroutines.CancellationException) {
                        throw c
                    } catch (_: Throwable) {
                        notifyOnce(appCtx, notification)
                    }
                }
            } else {
                nm.notify(NOTIF_ID, notification)
            }
        } catch (_: Throwable) {
            try {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(NOTIF_ID, buildFallback(ctx, title, text, progress?.coerceIn(0, 100), iconBitmap, style))
            } catch (_: Throwable) { /* 彻底放弃，但不崩 */ }
        }
    }

    /**
     * 本机是否具备 Android 16 Live Updates（ColorOS 流体云 / 原生实时活动）能力。
     * 仅看系统版本（API≥36）；能不能真出岛还看 [canPromote] 的系统开关。
     */
    fun supportsLiveUpdates(): Boolean = Build.VERSION.SDK_INT >= 36

    /**
     * 系统当前是否允许本应用发「升级版常驻通知」（流体云/实时活动）。
     * 反射 `NotificationManager.canPostPromotedNotifications()`（API 36）。
     * @return true=已允许；false=系统里关着（会被降级成普通通知→不出流体云）；
     *   null=取不到该 API（非 A16 或厂商裁剪）——UI 据此显示「去系统设置确认」而非误报关闭。
     */
    fun canPromote(ctx: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        return try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val m = NotificationManager::class.java.getMethod("canPostPromotedNotifications")
            m.invoke(nm) as? Boolean
        } catch (_: Throwable) { null }
    }

    /** 结束实时胶囊。 */
    fun dismiss(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIF_ID)
        } catch (_: Throwable) { }
    }

    // 通知渠道：IMPORTANCE_HIGH（超级岛/流体云都要求非 MIN），但关声音/震动 + setOnlyAlertOnce → 够高能出岛、不吵。
    private fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Arix 实时状态胶囊（超级岛 / 流体云 / 常驻通知）"
            ch.setShowBadge(false)
            ch.setSound(null, null)
            ch.enableVibration(false)
            ch.enableLights(false)
            nm.createNotificationChannel(ch)
        }
    }

    // 基础 builder：ongoing/可划走 + 状态小图标 + 标题/文本 + （可选）进度条。三个分支共用。
    private fun baseBuilder(
        ctx: Context,
        title: String,
        text: String,
        progress: Int?,
        style: CapsuleStyle,
        withActions: Boolean,
    ): NotificationCompat.Builder {
        val b = NotificationCompat.Builder(ctx, CHANNEL_ID)
            .setSmallIcon(if (style.iconRes != 0) style.iconRes else FALLBACK_SMALL_ICON)
            .setColor(runCatching { Color.parseColor(style.theme.progress) }.getOrDefault(0))
            .setColorized(true)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(!style.dismissible)   // 活动中不可划；结束常驻态可手动划走
            .setAutoCancel(style.dismissible)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
        if (progress != null) b.setProgress(100, progress, false)
        // 点击岛/通知 → 打开 App（修「点击不进软件」）。点击 intent 不影响岛渲染/浮岛，各分支通用。
        openAppIntent(ctx)?.let { b.setContentIntent(it) }
        // 动作按钮（停止/输入）：**超级岛路不加**——RemoteInput 输入框会让 HyperOS 把它当普通可展开通知、
        // 不再浮成岛（症状：抽屉里有岛样式但不出岛）。岛渲染只吃 miui.focus.param，按钮对它无益反有害。
        // 兜底普通通知 / 流体云路才加（那两套不受此限）。
        if (withActions) {
            if (!style.dismissible) b.addAction(stopAction(ctx))
            b.addAction(replyAction(ctx))
        }
        return b
    }

    private fun buildFallback(
        ctx: Context, title: String, text: String, progress: Int?, iconBitmap: Bitmap?, style: CapsuleStyle,
        withActions: Boolean = true,
    ): Notification {
        val b = baseBuilder(ctx, title, text, progress, style, withActions)
        if (iconBitmap != null) b.setLargeIcon(iconBitmap)
        return b.build()
    }

    // HyperOS 超级岛：普通 ongoing 通知 + miui.focus.param(JSON) + miui.focus.pics(本地 Icon)。
    private fun buildHyperOs(
        ctx: Context, title: String, text: String, progress: Int?, iconBitmap: Bitmap?, style: CapsuleStyle,
    ): Notification {
        // 超级岛不带**标准**动作按钮：notification.actions 里有 RemoteInput 会让 HyperOS 拒绝浮岛
        // （见 baseBuilder 说明）。岛内按钮改走下方 miui.focus.actions（不入 notification.actions）。
        // title=operation（在干嘛）、text=output（输出内容）。
        val n = buildFallback(ctx, title, text, progress, iconBitmap, style, withActions = false)
        try {
            val extras: Bundle = n.extras
            val token = picToken(style, iconBitmap)   // pic 键名随状态图标变
            extras.putString(K_FOCUS_PARAM, buildFocusJson(title, text, progress, style, token))
            // ticker 带全文：长文靠 ticker 走跑马灯滚动（大岛内容也给全量，不截断）
            extras.putString(K_FOCUS_TICKER, if (text.isNotBlank()) "$title · $text" else title)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val icon: Icon = if (iconBitmap != null) Icon.createWithBitmap(iconBitmap)
                    else Icon.createWithResource(ctx, if (style.iconRes != 0) style.iconRes else FALLBACK_SMALL_ICON)
                val pics = Bundle()
                // 两种键形都注册（纯往 pics bundle 多加键，动不了岛）：JSON pic 用裸名；不同 HyperOS 有的直接查裸名、有的加前缀。
                pics.putParcelable(K_FOCUS_PIC_PREFIX + token, icon)
                pics.putParcelable(token, icon)
                extras.putBundle(K_FOCUS_PICS, pics)
                // 岛内动作（展开岛出「停止/输入」）：放独立 bundle，不影响浮岛判定。best-effort，字段待真机确认。
                runCatching {
                    val actions = Bundle()
                    if (!style.dismissible) actions.putParcelable(K_FOCUS_ACTION_PREFIX + "stop", stopActionNative(ctx))
                    actions.putParcelable(K_FOCUS_ACTION_PREFIX + "reply", replyActionNative(ctx))
                    extras.putBundle(K_FOCUS_ACTIONS, actions)
                }
            }
        } catch (_: Throwable) { /* 注入失败退化成普通通知 */ }
        return n
    }

    /**
     * 手拼超级岛 `miui.focus.param` JSON。**回退到本机真机验证过能出岛的最简结构**：
     *   外层套 `{"param_v2": …}` 壳 + 岛键驼峰 `paramIsland`（去壳/蛇形都会让本机岛消失，已两次栽）。
     * ⚠ 别再往里加没经真机确认的字段（顶层 picInfo/islandFirstFloat/maxSize/imageTextInfoRight 等曾把岛整没）。
     *   内容(图标/布局/放大)要精修，走 logcat 看 HyperOS 解析日志，别盲加字段。
     * @param title 左/操作文字；@param text 右/输出文字（CapsuleBridge 已按 operation/output 传入）。
     */
    private fun buildFocusJson(title: String, text: String, progress: Int?, style: CapsuleStyle, picToken: String): String {
        val t = style.theme
        val root = JSONObject()
        val p = JSONObject()
        p.put("protocol", 3)
        p.put("business", BUSINESS)
        p.put("ticker", if (text.isNotBlank()) text else title)
        p.put("enableFloat", style.float)   // 默认 false
        p.put("updatable", true)
        if (style.animated) p.put("outEffectColor", t.effect)

        val baseInfo = JSONObject()
        baseInfo.put("type", 1)
        baseInfo.put("title", title)
        baseInfo.put("content", text)
        baseInfo.put("colorTitle", t.title)
        baseInfo.put("colorTitleDark", t.title)
        baseInfo.put("colorContent", t.content)
        baseInfo.put("colorContentDark", t.content)
        p.put("baseInfo", baseInfo)

        if (progress != null) {
            val progressInfo = JSONObject()
            progressInfo.put("progress", progress)
            progressInfo.put("colorProgress", t.progress)
            progressInfo.put("colorProgressEnd", t.progressEnd)
            p.put("progressInfo", progressInfo)
        }

        // 小岛右侧 banner 图标（顶层 picInfo）——给状态图标，避免右侧空/回落。
        p.put("picInfo", JSONObject().put("type", 1).put("pic", picToken))

        val island = JSONObject()
        island.put("islandProperty", 1)
        island.put("islandPriority", 2)
        if (style.animated) island.put("highlightColor", t.effect)

        val smallPic = picInfo(text.ifBlank { title }, style, picToken)
        island.put("smallIslandArea", JSONObject().put("picInfo", smallPic))

        val bigArea = JSONObject()
        bigArea.put("imageTextInfoLeft", JSONObject()
            .put("type", 1)
            .put("picInfo", picInfo(null, style, picToken))
            .put("textInfo", JSONObject().put("title", title).put("content", text)))
        if (progress != null) bigArea.put("progressTextInfo", JSONObject().put("progress", progress))
        island.put("bigIslandArea", bigArea)

        // ★ 岛主体键**必须蛇形 param_island**（据 HyperIsland-ToolKit 源码 @SerialName + 官方示例）：
        //   驼峰 paramIsland 被 HyperOS 忽略→只回落默认岛(App图标/无色，见用户截图)。内部子字段仍驼峰。
        //   岛的「出现」由 param_v2+baseInfo 触发，与本键无关，故改蛇形不会让岛消失、只补上自定义渲染。
        p.put("param_island", island)
        root.put("param_v2", p)
        return root.toString()
    }

    /** 构造一个 PicInfo：pic=本地图标键；开流光带 effectColor。 */
    private fun picInfo(desc: String?, style: CapsuleStyle, picToken: String): JSONObject {
        val j = JSONObject().put("type", 1).put("pic", picToken)
        if (desc != null) j.put("contentDescription", desc)
        if (style.animated) j.put("effectColor", style.theme.effect)
        return j
    }

    // ColorOS 16 / Android 16 流体云：Google Live Updates（全程反射，API36）。
    private fun buildLiveUpdates(
        ctx: Context, title: String, text: String, progress: Int?, iconBitmap: Bitmap?, style: CapsuleStyle,
    ): Notification? {
        if (Build.VERSION.SDK_INT < 36) return null
        return try {
            // ⚠ 流体云(A16 promoted)硬约束：**绝不能 setColorized(true)**，否则通知被系统静默「拒绝晋升」→
            //   流体云根本不出（官方 eligibility 明列 "Must NOT setColorized to TRUE"）。这是「流体云不触发」的真凶。
            //   强调色仍靠 setColor（promoted 通知照吃 accent，只是禁止 colorized 整块填充）。超级岛是另一套、不受此限。
            val builder = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(if (style.iconRes != 0) style.iconRes else FALLBACK_SMALL_ICON)
                .setColor(runCatching { Color.parseColor(style.theme.progress) }.getOrDefault(0))
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(!style.dismissible)
                .setAutoCancel(style.dismissible)
                .setOnlyAlertOnce(true)
            // 点击流体云 → 打开 App（修「点击不进软件」，流体云同吃）。
            openAppIntent(ctx)?.let { builder.setContentIntent(it) }
            // 流体云动作按钮：活动中给「停止」+「输入」，结束态只留「输入」（岛内继续说）。
            if (!style.dismissible) builder.addAction(stopActionNative(ctx))
            builder.addAction(replyActionNative(ctx))
            // 状态栏「胶囊」collapsed chip 上那行精简文字（A16 Live Updates）。缺了它，
            // ColorOS 流体云收起态可能只剩图标、看着像没生效——给一行短文本让 chip 有内容。
            runCatching {
                val short = (text.ifBlank { title }).take(20)
                Notification.Builder::class.java
                    .getMethod("setShortCriticalText", CharSequence::class.java)
                    .invoke(builder, short)
            }
            if (iconBitmap != null) {
                try { builder.setLargeIcon(Icon.createWithBitmap(iconBitmap)) } catch (_: Throwable) {}
            }
            // 进度样式：有真实进度(深搜)→彩色进度段；生成中(progress 空且 ongoing)→主题色「活动」轨，
            // tracker 随每次刷新(AI 吐字)前移，做出流动感（超级岛流光的 A16 等价物，仅流体云侧，不给超级岛塞假进度）；
            // 结束态不挂进度样式。让流体云生成时也有色，而非只剩空 chip。
            val ps = when {
                progress != null -> buildProgressStyleReflect(ctx, progress, iconBitmap, style)
                !style.dismissible -> buildActiveProgressStyleReflect(ctx, iconBitmap, style)
                else -> null
            }
            if (ps != null) {
                val styleCls = Class.forName("android.app.Notification\$Style")
                Notification.Builder::class.java.getMethod("setStyle", styleCls).invoke(builder, ps)
            }
            if (!invokeSetRequestPromotedOngoing(builder)) setPromotedOngoingExtra(builder)
            builder.build()
        } catch (_: Throwable) { null }
    }

    // 生成态「活动轨」tracker 位置计数：每次刷新(AI 吐字)前移一格，制造流动感。非原子无妨（纯视觉）。
    @Volatile private var activeTick = 0

    /**
     * 生成中（无真实百分比）的主题色 ProgressStyle：整条主题强调色轨 + tracker 随刷新前移。
     * `setStyledByProgress(false)` 让它不被当成「填充到 X%」，纯着色 + 流动 tracker（流体云侧的流光等价物）。
     */
    private fun buildActiveProgressStyleReflect(ctx: Context, iconBitmap: Bitmap?, style: CapsuleStyle): Any? {
        return try {
            val psCls = Class.forName("android.app.Notification\$ProgressStyle")
            val ps = psCls.getConstructor().newInstance()
            val intT = Int::class.javaPrimitiveType!!
            val boolT = Boolean::class.javaPrimitiveType!!
            val pos = ((activeTick++).let { it * 14 } % 100)   // 0,14,28,… 循环，每次刷新推进
            runCatching { psCls.getMethod("setStyledByProgress", boolT).invoke(ps, false) }
            runCatching { psCls.getMethod("setProgress", intT).invoke(ps, pos) }
            runCatching {
                val icon = if (iconBitmap != null) Icon.createWithBitmap(iconBitmap)
                    else Icon.createWithResource(ctx, if (style.iconRes != 0) style.iconRes else FALLBACK_SMALL_ICON)
                psCls.getMethod("setProgressTrackerIcon", Icon::class.java).invoke(ps, icon)
            }
            runCatching {
                val segCls = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
                val segCtor = segCls.getConstructor(intT)
                val setColor = segCls.getMethod("setColor", intT)
                val accent = runCatching { Color.parseColor(style.theme.effect) }.getOrDefault(Color.WHITE)
                val segs = arrayListOf<Any>(segCtor.newInstance(100).also { setColor.invoke(it, accent) })
                psCls.getMethod("setProgressSegments", List::class.java).invoke(ps, segs)
            }
            ps
        } catch (_: Throwable) { null }
    }

    /** 反射构造 `Notification.ProgressStyle`（进度段配色用 [style] 主题）。 */
    private fun buildProgressStyleReflect(ctx: Context, progress: Int, iconBitmap: Bitmap?, style: CapsuleStyle): Any? {
        return try {
            val psCls = Class.forName("android.app.Notification\$ProgressStyle")
            val ps = psCls.getConstructor().newInstance()
            val intT = Int::class.javaPrimitiveType!!
            val boolT = Boolean::class.javaPrimitiveType!!
            runCatching { psCls.getMethod("setStyledByProgress", boolT).invoke(ps, false) }
            runCatching { psCls.getMethod("setProgress", intT).invoke(ps, progress) }
            runCatching {
                val icon = if (iconBitmap != null) Icon.createWithBitmap(iconBitmap)
                    else Icon.createWithResource(ctx, if (style.iconRes != 0) style.iconRes else FALLBACK_SMALL_ICON)
                psCls.getMethod("setProgressTrackerIcon", Icon::class.java).invoke(ps, icon)
            }
            runCatching {
                val segCls = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
                val segCtor = segCls.getConstructor(intT)
                val setColor = segCls.getMethod("setColor", intT)
                val segs = ArrayList<Any>()
                val done = progress.coerceIn(0, 100)
                val rest = 100 - done
                val cProgress = runCatching { Color.parseColor(style.theme.progress) }.getOrDefault(Color.WHITE)
                val cTrack = runCatching { Color.parseColor(style.theme.track) }.getOrDefault(0x22FFFFFF)
                if (done > 0) segs.add(segCtor.newInstance(done).also { setColor.invoke(it, cProgress) })
                if (rest > 0) segs.add(segCtor.newInstance(rest).also { setColor.invoke(it, cTrack) })
                if (segs.isEmpty()) segs.add(segCtor.newInstance(100).also { setColor.invoke(it, cTrack) })
                psCls.getMethod("setProgressSegments", List::class.java).invoke(ps, segs)
            }
            ps
        } catch (_: Throwable) { null }
    }

    private fun invokeSetRequestPromotedOngoing(builder: Notification.Builder): Boolean {
        return try {
            val boolT = Boolean::class.javaPrimitiveType!!
            Notification.Builder::class.java.getMethod("setRequestPromotedOngoing", boolT).invoke(builder, true)
            true
        } catch (_: Throwable) { false }
    }

    private fun setPromotedOngoingExtra(builder: Notification.Builder) {
        try {
            val key = runCatching {
                Notification::class.java.getField("EXTRA_REQUEST_PROMOTED_ONGOING").get(null) as String
            }.getOrDefault("android.requestPromotedOngoing")
            val extras = Bundle().apply { putBoolean(key, true) }
            Notification.Builder::class.java.getMethod("addExtras", Bundle::class.java).invoke(builder, extras)
        } catch (_: Throwable) { }
    }

    // ROM 探测：Xiaomi 系 → 超级岛；Android 16 → Live Updates；其它 → 兜底。
    private fun detectRom(ctx: Context): Rom {
        val manufacturer = (Build.MANUFACTURER ?: "").lowercase()
        val brand = (Build.BRAND ?: "").lowercase()
        val isXiaomi = manufacturer.contains("xiaomi") || brand.contains("redmi") ||
            brand.contains("poco") || manufacturer.contains("redmi") ||
            prop("ro.mi.os.version.code").isNotBlank() ||
            prop("ro.mi.os.version.name").isNotBlank() ||
            prop("ro.miui.ui.version.name").isNotBlank()
        if (isXiaomi) return Rom.HYPEROS
        if (Build.VERSION.SDK_INT >= 36) return Rom.LIVE_UPDATES
        return Rom.OTHER
    }

    /** 读系统属性：优先 `android.os.SystemProperties`（反射），失败回落 `getprop`。探不到返回空串。 */
    private fun prop(name: String): String {
        try {
            val cls = Class.forName("android.os.SystemProperties")
            val v = cls.getMethod("get", String::class.java).invoke(null, name) as? String
            if (!v.isNullOrBlank()) return v.trim()
        } catch (_: Throwable) { }
        try {
            val proc = Runtime.getRuntime().exec(arrayOf("getprop", name))
            val v = proc.inputStream.bufferedReader().use { it.readLine() }
            proc.destroy()
            if (!v.isNullOrBlank()) return v.trim()
        } catch (_: Throwable) { }
        return ""
    }
}
