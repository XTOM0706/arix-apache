package com.arix.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import android.os.BatteryManager
import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 主动消息 / 主动性引擎：让 AI 在合适的时机主动找你说一句（陪伴层核心）。
 *
 * - WorkManager 周期任务后台跑（doze 友好、开机自动重排、有网才触发）。到点后做一层门控
 *   （开启 / 非静默时段 / 距上次主动够久 / 有配置模型 / 有最近对话），过了才生成。
 * - 生成复用 [WakeAssistantContext.buildSystemPrompt]（人设+记忆+环境+健康+状态+续接），
 *   让主动发起的是「带记忆的他」而非通用提醒。生成的话追加为 assistant 消息进最近对话，并发通知；
 *   点通知开到那条对话（[MainActivity.EXTRA_OPEN_CONV]）。
 * - **放弃出口**：前面那层门控只知道「时间到了、没在聊」，判断不了「此刻该不该打扰」。模型看得到情境，
 *   所以把最后一票给它——回 `[PASS]` 就整轮作废（不落库、不通知、不记时间，见 [isProactivePass]）。
 * - 默认关。检查间隔 / 静默时段 / 最小间隔可在设置里调。
 */
object ProactivePrefs {
    private const val PREF = "xtom_proactive"
    fun enabled(c: Context) = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()
    // 主动「来电」：主动消息以全屏语音来电方式送达（点接听=唤起语音助手，AI 先开口说这句），借鉴橘瓣 request_voice_call
    fun callMode(c: Context) = p(c).getBoolean("call_mode", false)
    fun setCallMode(c: Context, v: Boolean) = p(c).edit().putBoolean("call_mode", v).apply()
    /** 后台检查间隔（小时）。实际发送频率再由「最小间隔」门控，所以这里可略小。 */
    fun everyHours(c: Context) = p(c).getInt("every_h", 3).coerceIn(1, 24)
    fun setEveryHours(c: Context, v: Int) = p(c).edit().putInt("every_h", v.coerceIn(1, 24)).apply()
    /** 两次主动消息之间的最小间隔（小时）。 */
    fun minGapHours(c: Context) = p(c).getInt("gap_h", 6).coerceIn(1, 72)
    fun setMinGapHours(c: Context, v: Int) = p(c).edit().putInt("gap_h", v.coerceIn(1, 72)).apply()
    /** 静默时段（不打扰）：[start, end) 小时，跨零点按环绕处理。默认 22–8。 */
    fun quietStart(c: Context) = p(c).getInt("quiet_s", 22).coerceIn(0, 23)
    fun quietEnd(c: Context) = p(c).getInt("quiet_e", 8).coerceIn(0, 23)
    fun setQuiet(c: Context, s: Int, e: Int) = p(c).edit().putInt("quiet_s", s.coerceIn(0, 23)).putInt("quiet_e", e.coerceIn(0, 23)).apply()
    fun lastAt(c: Context) = p(c).getLong("last_at", 0L)
    fun setLastAt(c: Context, v: Long) = p(c).edit().putLong("last_at", v).apply()

    fun inQuietHours(c: Context, hour: Int): Boolean {
        val s = quietStart(c); val e = quietEnd(c)
        if (s == e) return false
        return if (s < e) hour in s until e else (hour >= s || hour < e) // 跨零点环绕
    }

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}

object ProactiveScheduler {
    private const val WORK = "xtom_proactive_work"

    fun schedule(context: Context) {
        val hours = ProactivePrefs.everyHours(context).toLong()
        val req = PeriodicWorkRequestBuilder<ProactiveWorker>(hours, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    // 低电量别为了"主动说句话"去跑一次模型往返：这是锦上添花的功能，不是用户在等的结果
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            // ⚠ 全项目唯一会 Result.retry() 的 worker（模型请求失败/异常都 retry），而没设退避策略时
            // WorkManager 默认是 EXPONENTIAL 30s 起——端点挂着的时候会在半小时里重试好几次模型请求。
            // 主动消息不急，退避从 15 分钟起，让"端点挂了"这种事只花一两次尝试就过去。
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        // UPDATE：间隔改了也能就地替换，不会叠多个
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)
    }

    /** 开关变化统一入口。 */
    fun apply(context: Context, enabled: Boolean) {
        ProactivePrefs.setEnabled(context, enabled)
        if (enabled) schedule(context) else cancel(context)
    }
}

class ProactiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try {
            if (!CompanionPrefs.enabled(ctx) || !ProactivePrefs.enabled(ctx)) return Result.success()   // 陪伴包总闸
            val cal = java.util.Calendar.getInstance()
            if (ProactivePrefs.inQuietHours(ctx, cal.get(java.util.Calendar.HOUR_OF_DAY))) return Result.success()
            val now = System.currentTimeMillis()
            // 关心点（健康：久坐/深夜还醒/睡少；天气：有雨/太冷太热）：有则最小间隔减半(下限1h)让关心来得及时；静默时段仍不打扰
            val concern = (try { HealthSignals.concern(ctx) } catch (_: Exception) { null })
                ?: (try { WeatherSignals.concern(ctx) } catch (_: Exception) { null })
            val gapHours = if (concern != null) maxOf(ProactivePrefs.minGapHours(ctx) / 2, 1) else ProactivePrefs.minGapHours(ctx)
            if (now - ProactivePrefs.lastAt(ctx) < gapHours * 3600_000L) return Result.success()

            val cfgMgr = CloudApiConfigManager(ctx)
            val e = cfgMgr.getActiveByPurpose("chat") ?: return Result.success()   // 没配模型就不打扰
            val convMgr = ConversationManager(ctx)
            val conv = convMgr.repo.getMostRecentActive() ?: return Result.success() // 没聊过就不主动
            // 用户近 15 分钟还在聊：别打断活跃对话；也避免与「打开着的对话」并发写库把主动消息/刚发的消息覆盖丢掉
            if (System.currentTimeMillis() - conv.updatedAt < 15 * 60_000L) return Result.success()

            val sys = try {
                WakeAssistantContext.buildSystemPrompt(
                    context = ctx, cardId = conv.characterCardId, convId = conv.id,
                    userText = "", continuation = null, apiSystemPrompt = e.systemPrompt.ifBlank { null },
                )
            } catch (_: Exception) { null }
            // 只给情境，不写任何「别像客服/别像AI」这类元指令——那会把它框成 AI 再叫它别那样(见 prompt-style)。
            // 人设由 system 提示承载，这里只设场景、把观察到的信号当数据给。
            // 场景那句走 PromptLang.pick：中文=默认，英文=省 token。
            val directive = buildString {
                append(PromptLang.pick(
                    "这会儿你自己想起了他，想跟他说句话。结合现在的时间和你记得的事，简短说你想说的。",
                    "Right now you thought of him on your own and want to say a few words to him. Considering the current time and what you remember, briefly say what you want to say."
                ))
                if (concern != null) append(PromptLang.pick("\n你还注意到：$concern", "\nYou also noticed: $concern"))
                collectSignals(ctx).takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                append("\n\n").append(PASS_PROTOCOL)
            }

            val client = CloudApiClient(CloudApiConfig(e.baseUrl.trimEnd('/'), e.apiKey.trim(), e.model.trim()))
            var out = ""
            val r = client.streamChat(
                messages = listOf(ChatMessage("user", directive)),
                systemPrompt = sys, enableThinking = 0,
                onReasoningChunk = {}, onContentChunk = { out += it },
            )
            val msg = out.trim()
            if (r.error != null || msg.isBlank()) return Result.retry()
            // 模型说「现在别打扰」：什么都不做。尤其**不更新 lastAt**——这次压根没打扰过他，
            // 若记了时间，下一个检查点会被最小间隔挡掉，等于模型一次弃权就白白吃掉一整轮机会。
            if (isProactivePass(msg)) {
                android.util.Log.i(TAG, "模型选择不打扰：" + msg.take(80))
                return Result.success()
            }

            // 追加为 assistant 消息进该对话（读-加-存），并记时间
            val history = convMgr.loadMessages(conv.id).toMutableList()
            history.add(ChatMessage("assistant", msg))
            convMgr.saveMessages(conv.id, history)
            // 若该对话有分支树(branchesJson)，同步把新消息提交进树——否则加载以树为准会忽略这条主动消息
            try {
                val treeJson = convMgr.loadBranches(conv.id)
                if (treeJson != null) MessageTree.fromJson(treeJson)?.let { t ->
                    t.commitActivePath(history)
                    convMgr.saveBranches(conv.id, if (t.hasBranches()) t.toJson() else null)
                }
            } catch (_: Exception) {}
            ProactivePrefs.setLastAt(ctx, now)

            // 通知用角色卡名 + 头像，让它像「他」发来的
            val card = conv.characterCardId?.let { runCatching { CharacterCardManager(ctx).getById(it) }.getOrNull() }
            postNotification(ctx, conv.id, card?.name?.ifBlank { null } ?: conv.title.ifBlank { "Arix" }, msg, loadAvatarBitmap(ctx, card?.avatarPath))
            return Result.success()
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce   // 取消不是失败：吞了它 WorkManager 会当成"跑失败"再排一次重试（见记忆 stop-cancellation）
        } catch (_: Exception) {
            return Result.retry()
        }
    }

    /**
     * 把手边**已有**的客观信号拼成几行给模型：它要判断「此刻该不该打扰」，光有时间和记忆是判断不出来的
     * （用户是在开会、在刷手机、还是刚睡下，全在这些信号里）。
     *
     * 三条纪律：
     * - 取不到的项**整段不注入**——写一行"位置：未知"只是让模型多读几个 token 再照样瞎猜；
     * - 只给数据，不替它规定该拿这些数据干什么（见记忆 prompt-inject-data-only）；
     * - 敏感项各自按用户开关/授权门控，绝不绕过；这是后台静默采集，越界的代价比拿不到数据大得多。
     */
    private suspend fun collectSignals(ctx: Context): String = withContext(Dispatchers.IO) {
        val lines = ArrayList<String>(4)

        // 电量：不敏感、无需门控。低电本身就是「别折腾他」的理由之一。
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level in 0..100) lines.add("Battery $level%" + (if (bm?.isCharging == true) ", charging" else ", unplugged"))
        } catch (_: Exception) {}

        // 今日 App 使用：直接跑 app_usage 工具本体，别再抄一遍 UsageStatsManager 的聚合口径。
        // 门控=系统「使用情况访问权限」（用户在权限页显式开的那个），没授权时工具本身也只会回错误。
        try {
            if (com.arix.tool.UsageStatsTool.hasAccess(ctx)) {
                val r = com.arix.tool.UsageStatsTool(ctx).execute(JSONObject().put("days", 1).put("top", 4))
                // 工具输出是多行「总时长+逐个 App」，压成一行：这里只是给模型垫个背景，不值得占四五行
                if (!r.isError) r.content.trim().takeIf { it.isNotBlank() }
                    ?.let { lines.add("Screen time: " + it.replace("\n· ", "; ").replace("\n", "; ")) }
            }
        } catch (ce: kotlinx.coroutines.CancellationException) { throw ce } catch (_: Exception) {}

        // 最近通知：只取「谁+标题」这一层摘要。正文是别人发给他的私信，塞进提示词既越界又没必要——
        // 模型要的只是「他手机上刚刚有事发生」这个事实。双重门控：系统通知使用权 + App 内捕获开关。
        try {
            if (NotificationAwarenessPrefs.hasAccess(ctx) && NotificationAwarenessPrefs.enabled(ctx)) {
                val recent = NotificationStore.recent(4).mapNotNull { n ->
                    val head = n.title.ifBlank { n.text.lineSequence().firstOrNull().orEmpty() }.trim()
                    if (head.isBlank()) null else "${n.app}: ${head.take(24)}"
                }
                if (recent.isNotEmpty()) lines.add("Recent notifications: " + recent.joinToString(" | "))
            }
        } catch (_: Exception) {}

        // 位置：只读系统缓存，**不**主动定位——为了一句主动消息在后台唤醒 GPS 不值当（这功能连
        // 低电量都特意不跑）。缓存必然是旧的，所以强制带上「多久以前」：不标的话模型会拿半天前的
        // 位置说「你还在公司吧」。太旧(>6h)直接不给，反查不出地名也不给（不塞经纬度：噪声+越界）。
        try {
            if (LocationSignals.hasPermission(ctx)) LocationSignals.lastKnown(ctx)?.let { loc ->
                val ageMin = ((System.currentTimeMillis() - loc.time) / 60_000L).coerceAtLeast(0L)
                if (ageMin <= 360L && android.location.Geocoder.isPresent()) {
                    @Suppress("DEPRECATION")
                    val a = runCatching {
                        android.location.Geocoder(ctx, java.util.Locale.getDefault())
                            .getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
                    }.getOrNull()
                    // 只到区县：主动消息用不着门牌号
                    val name = a?.let {
                        listOfNotNull(it.adminArea, it.locality ?: it.subAdminArea, it.subLocality)
                            .filter { s -> s.isNotBlank() }.distinct().joinToString("")
                    }
                    if (!name.isNullOrBlank()) {
                        val ago = if (ageMin < 1L) "just now" else "$ageMin min ago"
                        lines.add("Last known location ($ago): $name")
                    }
                }
            }
        } catch (_: Exception) {}

        if (lines.isEmpty()) "" else "Signals:\n" + lines.joinToString("\n") { "- $it" }
    }

    private fun postNotification(ctx: Context, convId: Long, title: String, text: String, avatar: android.graphics.Bitmap?) {
        // 应用在前台就不弹：消息已经追加进对话了，用户正看着，再砸一条横幅/来电纯属重复打扰
        if (NotificationPrefs.suppressed(ctx)) return
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL, "主动消息", NotificationManager.IMPORTANCE_HIGH)
                        .apply { description = "AI 主动找你时的提醒"; enableVibration(true) }
                )
            }
            // 「来电」模式：全屏语音来电（targetSdk 28 下全屏 Intent 无限制），点接听唤起语音助手、AI 用这条主动消息开口
            if (ProactivePrefs.callMode(ctx)) {
                val call = Intent(ctx, WakeAssistantActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    putExtra(WakeAssistantActivity.EXTRA_OPENING, text)
                }
                val callPi = PendingIntent.getActivity(ctx, convId.toInt(), call, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                val cn = NotificationCompat.Builder(ctx, CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_menu_call)
                    .setContentTitle(title).setContentText("语音来电…")
                    .setAutoCancel(true).setOngoing(false)
                    .setPriority(NotificationCompat.PRIORITY_HIGH).setCategory(NotificationCompat.CATEGORY_CALL)
                    .setFullScreenIntent(callPi, true).setContentIntent(callPi)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .apply { if (avatar != null) setLargeIcon(avatar) }
                    .build()
                nm.notify(NOTIF_ID, cn)
                return
            }
            val open = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_CONV, convId)
            }
            val pi = PendingIntent.getActivity(
                ctx, convId.toInt(), open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_email)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(NotificationCompat.DEFAULT_VIBRATE)
                .apply { if (avatar != null) setLargeIcon(avatar) }
                .build()
            nm.notify(NOTIF_ID, n)   // 固定 id：多条只留最新，不堆通知栏
        } catch (_: Exception) {}
    }

    companion object {
        private const val CHANNEL = "arix_proactive"
        private const val NOTIF_ID = 42001
        private const val TAG = "Proactive"

        /**
         * 放弃出口。给模型看的一律英文（省 token，见记忆 prompt-english-for-model）。
         *
         * 这不是「规定应答方式」的元指令，而是补一条它本来没有的**通道**：在这之前，只要它生成了字，
         * 就一定会被落库并弹通知——半夜、开会、刚聊完、没话找话，全都照发，主动性直接变成骚扰。
         * 只说明出口和后果，不给它列「什么算不合适」的清单：情境判断本来就是它比这段代码强的地方。
         */
        private const val PASS_PROTOCOL =
            "If this is not a good moment to reach out to him, reply [PASS] and nothing else " +
            "(you may add a few words of reason after it). Nothing will be sent and this round is dropped silently. " +
            "Choosing [PASS] is better than sending something ill-timed, repetitive or empty."
    }
}

/**
 * 这条回复是不是「放弃」。
 *
 * 判定刻意宽容：模型交出来的可能是 `[PASS]`、`[pass] 他这会儿在忙`、`【PASS】`、外面还裹一层引号或
 * 代码围栏。判严了就等于这个出口时灵时不灵——而漏判的代价是发一条不合时宜的消息，误判的代价只是
 * 这一轮不说话（且 lastAt 没动，下一轮照常轮到），两边不对称，所以宁可宽。
 */
internal fun isProactivePass(raw: String): Boolean {
    var s = raw.trim().trim('`').trim()
    // 全角括号折成半角一起认：中文输入法环境下模型打出【】的概率不低
    s = s.replace('【', '[').replace('】', ']').replace('（', '(').replace('）', ')')
    s = s.trim('"', '\'', '“', '”', '「', '」', '『', '』', ' ', '\n', '\t')
    if (s.equals("PASS", ignoreCase = true)) return true
    // \b 收尾，"passing/passed" 这类正常词开头的消息不会被当成放弃
    return Regex("^[\\[(]?\\s*PASS\\b", RegexOption.IGNORE_CASE).containsMatchIn(s)
}

/** 从头像 URI(content/file/绝对路径) 加载 Bitmap 作通知大图标；采样到 ~192px 防大图 OOM；http/空/失败返回 null。 */
internal fun loadAvatarBitmap(ctx: Context, uri: String?): android.graphics.Bitmap? {
    if (uri.isNullOrBlank()) return null
    // 先量尺寸算采样率，避免整幅多兆像素图直接解码 OOM（OOM 是 Error 不被 catch(Exception) 捕获）
    fun open() = if (uri.startsWith("content://") || uri.startsWith("file://")) ctx.contentResolver.openInputStream(android.net.Uri.parse(uri))
        else if (uri.startsWith("/")) java.io.File(uri).inputStream() else null
    return try {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open()?.use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        var sample = 1
        val target = 192
        while (bounds.outWidth / (sample * 2) >= target || bounds.outHeight / (sample * 2) >= target) sample *= 2
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        open()?.use { android.graphics.BitmapFactory.decodeStream(it, null, opts) }
    } catch (_: Throwable) { null }   // Throwable 兜住 OOM
}
