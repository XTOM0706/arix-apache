package com.arix.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.arix.tool.ToolActivityBus
import com.arix.tool.ToolPermissionManager
import com.arix.tool.search.XSearchProgress
import com.arix.tool.search.XSearchProgressBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 实时胶囊（灵动岛/超级岛/流体云）与「AI 现在在干嘛」的桥。
 *
 * 把三条现成的中央流翻成「一句人话 + 可选进度」推上岛，让岛成为 AI 行为的展示面：
 *  1. [ToolActivityBus.running] —— 当前正在跑的工具（点击/搜索/读屏/装包…），全项目唯一的工具执行闸埋点，
 *     覆盖 AI / 插件 / 用户脚本 / 外部 MCP 全部调用方。措辞直接复用 [ToolPermissionManager.humanizeIntent]
 *     （就是授权弹窗上那套「点击界面上的「发送」」的人话），不甩 JSON 参数。
 *  2. [XSearchProgressBus] —— 深度搜索多轮进度（第 3/5 轮…、已读 N 源），带进度条上岛。
 *  3. 一轮 AI 生成的开始/结束（由 [com.arix.app.ChatPage] 通过 [onGenerationStart]/[onGenerationEnd] 报）——
 *     没有工具在跑的空档显示「正在思考…」，避免工具与工具之间岛闪掉；生成结束且 App 在后台时发**完成通知**。
 *
 * 生命周期：进程级单飞。[start] 在 [XtomApp.onCreate] 调一次，收集器活到进程结束，不绑任何 Activity，
 * 不会随聊天页销毁而泄漏或被杀。岛能不能真出（超级岛白名单/CO16 放行）是 [LiveCapsuleController] 那层的
 * 真机验点，这里照调即可——它对无岛设备已有普通通知兜底、绝不崩。
 */
object CapsuleBridge {

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()

    @Volatile private var appCtx: Context? = null

    /** 应用内岛当前该显示的内容（null=收起）。供 [com.arix.app.InAppIsland] 组件 collectAsState 订阅。 */
    data class IslandState(
        val operation: String,    // 左：在干嘛（图标旁文字）
        val output: String,       // 右：AI 输出内容
        val progress: Int?,       // 进度（深搜时有；否则 null）
        val iconRes: Int,         // 状态/工具图标资源
        val dismissible: Boolean, // true=完成态
    )
    private val _island = MutableStateFlow<IslandState?>(null)
    /** 应用内岛状态流。系统岛/应用内岛共用这份内容，由 [CapsulePrefs.displayMode] 决定各自显不显。 */
    val island: StateFlow<IslandState?> = _island.asStateFlow()

    /** displayMode 是否包含「系统岛」（system/both）。inapp/off 时不发系统通知/岛。 */
    private fun systemIslandOn(ctx: Context): Boolean =
        CapsulePrefs.displayMode(ctx).let { it == "system" || it == "both" }

    private const val PREVIEW_OP = "预览"

    /** 设置页「预览一下」：在应用内岛上按当前配色/动画演示约 3.5 秒（不触碰系统岛，不影响真实活动状态机）。 */
    fun previewInApp(context: Context) {
        appCtx = appCtx ?: context.applicationContext
        _island.value = IslandState(PREVIEW_OP, tr("流光 / 配色演示"), 66, R.drawable.ic_capsule_done, dismissible = true)
        scope.launch {
            delay(3500)
            // 只有当前仍是这条预览才收（别把期间进来的真实活动清掉）。
            if (_island.value?.operation == PREVIEW_OP) _island.value = null
        }
    }

    // 三个输入源的最新值，render() 按优先级选一个上岛。
    @Volatile private var lastRunning: ToolActivityBus.Entry? = null
    @Volatile private var lastXSearch: XSearchProgress? = null
    @Volatile private var generating: Boolean = false
    // AI 这一轮正在输出的正文（右边显示的内容；长了靠 ticker 跑马灯滚动）。空=还没吐字，显「正在思考…」。
    @Volatile private var lastOutput: String = ""

    // 上一次真正推上岛的内容签名：相同就跳过，别每个 version 心跳都重发一遍同样的通知。
    @Volatile private var lastKey: String = ""

    // 岛更新节流（leading + trailing 合并）：两次上岛至少间隔 MIN_ISLAND_INTERVAL_MS。
    // 主要为「超级岛显示」开着时——每次 show 会经 XmsfUnlock 断一次 xmsf 网，AI 连续调工具时若每次都断
    // 会把 MiPush 抖出问题（研究已警告）。突发更新期间只在窗口末尾用「最新内容」发一次。收岛(idle)不受节流、即时。
    private const val MIN_ISLAND_INTERVAL_MS = 800L
    @Volatile private var lastShowAt = 0L
    private var pendingPush: PendingPush? = null
    private var throttleJob: Job? = null
    private data class PendingPush(val key: String, val title: String, val text: String, val progress: Int?, val iconRes: Int)

    // 结束常驻收尾：活动结束后不立即收岛，先显示「完成」态、按 CapsulePrefs.lingerSeconds 延时收
    // （0=立即收；-1=不自动收、留着让用户手动划走）。lingerJob 是那个延时收的任务；有新活动来会被 emit() 取消。
    private const val DONE_KEY = "__done__"
    private var lingerJob: Job? = null

    // 生成保活：生成期间把进程升前台（复用 WakeService，零 Manifest 改动），防 App 退后台/被划最近任务时
    // 系统回收 composition-scope 的生成协程。策略——只在真有必要时升前台，别每次小回复都闪一下通知：
    //   · 生成开始时 App 已在后台 → 立刻升（后台生成最容易被杀）。
    //   · 生成开始时 App 在前台 → 延时观察 KEEPALIVE_ESCALATE_MS，仍在生成才升（小回复秒回，不触发）。
    // 生成结束一律撤。keepAliveOn 保证起停幂等（onGenerationEnd 可能被调多次：成功一次 + finally 一次）。
    private const val KEEPALIVE_ESCALATE_MS = 3000L
    @Volatile private var keepAliveOn = false
    @Volatile private var keepAliveJob: Job? = null

    private const val DONE_CHANNEL = "xtom_capsule_done"
    private const val DONE_CHANNEL_NAME = "AI 完成通知"
    private const val DONE_NOTIF_ID = 42091

    /** 进程启动时调一次（幂等）。订阅中央流，开始驱动实时胶囊。 */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appCtx = context.applicationContext
        // 当前工具动作（含后台唤醒助手/子agent 调的工具，conversationId 为 null 也照显）。
        scope.launch {
            ToolActivityBus.running.collect { entry ->
                lastRunning = entry
                render()
            }
        }
        // 深度搜索多轮进度。
        scope.launch {
            XSearchProgressBus.state.collect { p ->
                lastXSearch = p
                render()
            }
        }
    }

    /** 一轮 AI 生成开始：进入「思考中」态，工具跑起来后会被工具动作覆盖。 */
    fun onGenerationStart() {
        generating = true
        lastOutput = ""
        render()
        scheduleKeepAlive()
    }

    /**
     * AI 正文流式增量：把「岛右边显示的输出内容」更新成最新正文。ChatScreen 每来一块正文调一次。
     * 只在没有工具/深搜在跑的纯生成态才真正上岛（那两者优先级更高，见 [render]）。
     * 上岛频率由 [push] 的 800ms 节流 + 粗粒度 key 双重兜住——超级岛开启时每次 show 会断一次 xmsf，
     * 别每 token 都发（见 push 注释）。
     */
    fun onOutputText(text: String) {
        if (!generating) return
        lastOutput = text
        if (lastRunning == null && lastXSearch == null) render()
    }

    /** 按策略起生成保活（见 keepAliveOn 处注释）。 */
    private fun scheduleKeepAlive() {
        val ctx = appCtx ?: return
        if (keepAliveOn) return
        if (!AppForeground.isForeground || WakeService.isRunning) {
            // 已在后台生成 → 立刻升前台护住；wake 已在前台 → 立刻标记（免费搭车其通知，
            // 且防 wake 在生成头几秒被停时误杀生成），两种情况都不再等延时。
            startKeepAlive(ctx)
            return
        }
        // 前台生成：延时观察，仍在生成才升（避免小回复闪通知）。旧的观察任务先取消。
        keepAliveJob?.cancel()
        keepAliveJob = scope.launch {
            delay(KEEPALIVE_ESCALATE_MS)
            if (generating && !keepAliveOn) startKeepAlive(appCtx ?: return@launch)
        }
    }

    private fun startKeepAlive(ctx: Context) {
        if (keepAliveOn) return
        keepAliveOn = true
        runCatching { WakeService.startGenerationKeepAlive(ctx) }
    }

    private fun stopKeepAlive() {
        keepAliveJob?.cancel()
        keepAliveJob = null
        if (!keepAliveOn) return
        keepAliveOn = false
        val ctx = appCtx ?: return
        runCatching { WakeService.stopGenerationKeepAlive(ctx) }
    }

    /**
     * 一轮 AI 生成结束。清「思考中」态、按需收岛；若成功且 App 在后台，发一条完成通知
     * （前台不打扰——岛/聊天已可见）。STOP 取消发生在前台，[ok] 传 false 或后台判据自然不触发。
     *
     * @param convId  点通知回到的对话（可空）。
     * @param ok      是否正常产出结果（出错/取消传 false，不发「已完成」）。
     * @param summary 结果摘要（回复正文头部），作为通知正文。
     */
    fun onGenerationEnd(convId: Long?, ok: Boolean, summary: String?) {
        generating = false
        lastOutput = ""
        render()
        stopKeepAlive()   // 生成结束必撤前台（幂等：成功一次 + finally 一次都安全）
        if (ok && !AppForeground.isForeground && !summary.isNullOrBlank()) {
            postDone(convId, summary)
        }
    }

    // 按优先级把「当前该显示什么」推上岛：深搜进度 > 当前工具动作 > 思考中 > 空闲（收岛）。
    private fun render() {
        val ctx = appCtx ?: return
        synchronized(lock) {
            val xs = lastXSearch
            val run = lastRunning
            when {
                xs != null -> {
                    val pct = if (xs.maxRounds > 0) (xs.round * 100 / xs.maxRounds).coerceIn(0, 100) else null
                    val q = xs.queries.firstOrNull()?.takeIf { it.isNotBlank() }
                    val title = if (xs.maxRounds > 0) tr("深度研究 · 第 %s/%s 轮").format(xs.round, xs.maxRounds) else tr("深度研究进行中")
                    val text = buildString {
                        append(xs.phase.ifBlank { tr("研究中") })
                        if (q != null) append("：“").append(q.take(20)).append("”")
                        if (xs.sourceCount > 0) append(tr(" · 已读 %s 源").format(xs.sourceCount))
                    }
                    push("xs:${xs.round}/${xs.maxRounds}:${xs.phase}:${xs.sourceCount}", ctx, title, text, pct, R.drawable.ic_capsule_search)
                }
                run != null -> {
                    val args = run.argsRef ?: JSONObject()
                    val phrase = runCatching { ToolPermissionManager.humanizeIntent(run.toolName, args) }
                        .getOrDefault("").ifBlank { tr("调用工具 %s").format(run.toolName) }
                    val who = if (run.callerLabel.isBlank() || run.callerLabel == "AI") "" else "${run.callerLabel} · "
                    // 左=图标+操作(在干嘛)；右=输出(工具执行阶段无 AI 正文，留空)。
                    push("run:${run.seq}:${run.toolName}", ctx, who + tr("正在%s").format(phrase), "", null, iconForTool(run.toolName))
                }
                generating -> {
                    val out = lastOutput.trim()
                    if (out.isNotBlank()) {
                        // 左=「回复中」；右=AI 正在输出的正文（取最新尾巴，像实时吐字；完整文本靠岛内 ticker 滚动）。
                        // key 每约 24 字变一次 → 配合 push 的 800ms 节流，突发流式期间也就 ~1 次/秒上岛，不抖 MiPush。
                        push("gen:${out.length / 24}", ctx, tr("回复中"), out.takeLast(160), null, R.drawable.ic_capsule_status)
                    } else {
                        push("gen", ctx, tr("正在思考…"), "", null, R.drawable.ic_capsule_status)
                    }
                }
                else -> enterIdle(ctx)
            }
        }
    }

    // 工具名 → 岛上「对应图标」。按类别关键词匹配（工具无 category 字段，靠名字最稳）；长尾走通用扳手 ic_capsule_tool。
    // 顺序=特异性优先（先 search，再 file/image…），避免「local_search 撞进 search」这类被前面的宽泛规则截胡。
    private fun iconForTool(name: String): Int {
        val n = name.lowercase()
        val engines = setOf("bing", "baidu", "sogou", "duckduckgo", "google", "scholar", "crossref", "anysearch", "perplexica")
        return when {
            n.contains("search") || n == "rag" || n in engines -> R.drawable.ic_capsule_search
            n.startsWith("file") || n == "make_directory" || n == "read_document" || n == "export_csv" || n == "file_converter" -> R.drawable.ic_capsule_file
            n.contains("image") || n == "generate_image" || n == "take_photo" -> R.drawable.ic_capsule_image
            n == "shell" || n.contains("linux") || n == "code_runner" || n == "local_infer" -> R.drawable.ic_capsule_terminal
            n == "ui_control" || n.contains("screen") -> R.drawable.ic_capsule_screen
            n == "fetch" || n == "open_page" || n == "browser" || n == "http_request" || n == "web_server" ||
                n == "github" || n == "site_cookies" || n.startsWith("net") || n == "home_assistant" -> R.drawable.ic_capsule_web
            n == "map" || n == "train_tickets" || n == "life_query" -> R.drawable.ic_capsule_location
            n.contains("weather") -> R.drawable.ic_capsule_weather
            n == "music_control" || n == "song_profile" || n == "tts" -> R.drawable.ic_capsule_music
            n == "calendar" || n.contains("reminder") || n.contains("alarm") || n.contains("schedule") ||
                n == "todo" || n == "diary" || n == "time" -> R.drawable.ic_capsule_calendar
            n.contains("sms") || n == "make_phone_call" || n == "contacts" || n == "message_gateway" ||
                n == "social_share" || n == "notification" -> R.drawable.ic_capsule_message
            n == "memory" || n == "worldbook" -> R.drawable.ic_capsule_memory
            n == "device_status" || n == "brightness" || n == "torch" || n == "wifi_info" || n == "volume" ||
                n == "app_launch" || n == "clipboard" || n == "app_usage" || n == "settings" ||
                n == "bluetooth" || n == "calculator" -> R.drawable.ic_capsule_device
            else -> R.drawable.ic_capsule_tool
        }
    }

    // 节流上岛：距上次 ≥MIN_ISLAND_INTERVAL_MS 立刻发；否则记下最新内容、排一个 trailing 在窗口末尾统一发一次。
    // 调用方 render() 已持有 lock；trailing 协程回来时也走 lock（可重入/跨线程串行）。
    private fun push(key: String, ctx: Context, title: String, text: String, progress: Int?, iconRes: Int) {
        if (key == lastKey) return
        val now = android.os.SystemClock.elapsedRealtime()
        val since = now - lastShowAt
        if (since >= MIN_ISLAND_INTERVAL_MS) {
            emit(ctx, key, title, text, progress, iconRes, now)
        } else {
            pendingPush = PendingPush(key, title, text, progress, iconRes)
            if (throttleJob?.isActive != true) {
                throttleJob = scope.launch {
                    delay(MIN_ISLAND_INTERVAL_MS - since)
                    synchronized(lock) {
                        val p = pendingPush ?: return@synchronized
                        pendingPush = null
                        emit(appCtx ?: return@synchronized, p.key, p.title, p.text, p.progress, p.iconRes,
                            android.os.SystemClock.elapsedRealtime())
                    }
                }
            }
        }
    }

    private fun emit(ctx: Context, key: String, title: String, text: String, progress: Int?, iconRes: Int, at: Long) {
        if (key == lastKey) return
        lingerJob?.cancel(); lingerJob = null   // 有新活动 → 取消上一轮的「延时收岛」
        lastKey = key
        lastShowAt = at
        // 应用内岛：始终更新状态流（InAppIsland 组件按 displayMode 决定显不显）。
        _island.value = IslandState(title, text, progress, iconRes, dismissible = false)
        // 系统岛/通知：仅在 displayMode 含「系统」时发（inapp/off 不打扰通知栏）。
        if (systemIslandOn(ctx)) LiveCapsuleController.show(ctx, title, text, progress, iconRes = iconRes)
    }

    /**
     * 转入空闲：不立即收岛，先显示「完成」态常驻一小段（[CapsulePrefs.lingerSeconds]），到点再收。
     *  · 0  = 立即收（老行为）
     *  · >0 = 显示「完成」态、N 秒后自动收；期间可手动划走
     *  · -1 = 显示「完成」态、不自动收，留着让用户手动划走
     * 已在「完成」态（lastKey==DONE_KEY）则不重复触发，交给 lingerJob / 下次活动。
     */
    private fun enterIdle(ctx: Context) {
        throttleJob?.cancel(); throttleJob = null; pendingPush = null
        if (lastKey == "" || lastKey == DONE_KEY) return   // 本就空闲 / 已在收尾
        val linger = CapsulePrefs.lingerSeconds(ctx)
        if (linger == 0) {
            lingerJob?.cancel(); lingerJob = null
            lastKey = ""
            _island.value = null
            if (systemIslandOn(ctx)) LiveCapsuleController.dismiss(ctx)
            return
        }
        // 显示可手动划走的「完成」态
        lastKey = DONE_KEY
        lastShowAt = android.os.SystemClock.elapsedRealtime()
        _island.value = IslandState(tr("完成"), "", null, R.drawable.ic_capsule_done, dismissible = true)
        if (systemIslandOn(ctx)) LiveCapsuleController.show(ctx, tr("完成"), "", null, iconRes = R.drawable.ic_capsule_done, dismissible = true)
        lingerJob?.cancel()
        lingerJob = if (linger > 0) {
            scope.launch {
                delay(linger * 1000L)
                synchronized(lock) {
                    if (lastKey == DONE_KEY) {   // 期间没有新活动才收
                        lastKey = ""
                        _island.value = null
                        val c = appCtx ?: return@synchronized
                        if (systemIslandOn(c)) LiveCapsuleController.dismiss(c)
                    }
                }
            }
        } else null   // -1：不排收尾，留着手动划走
    }

    // App 在后台时的「一轮生成完成」通知：普通可消除通知，点开回到对话。与深搜的后台完成通知分渠道/分 id。
    private fun postDone(convId: Long?, summary: String) {
        val ctx = appCtx ?: return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(DONE_CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(DONE_CHANNEL, DONE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT)
                        .apply { description = tr("Arix 在后台完成回复/任务时的提醒") }
                )
            }
            val pi = buildOpenIntent(ctx, convId)
            val body = summary.trim()
            val n = NotificationCompat.Builder(ctx, DONE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle(tr("Arix 回复好了"))
                .setContentText(body.take(80))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(400)))
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .apply { if (pi != null) setContentIntent(pi) }
                .build()
            nm.notify(DONE_NOTIF_ID, n)
        } catch (_: Throwable) { /* 通知失败绝不影响主流程 */ }
    }

    private fun buildOpenIntent(ctx: Context, convId: Long?): PendingIntent? = try {
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (convId != null && convId > 0) putExtra(MainActivity.EXTRA_OPEN_CONV, convId)
        }
        PendingIntent.getActivity(
            ctx, 42091, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    } catch (_: Throwable) { null }
}
