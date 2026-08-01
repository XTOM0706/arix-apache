package com.arix.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.model.ChatMessage
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 每日日记 / 小结（陪伴层）：AI 以自己的口吻，为「今天和你聊过的内容」写一小段日记。
 * - 到点由 WorkManager 自动生成一篇并通知（时间可配，默认 21:00）。
 * - AI 也能在对话里按需 写/念/列 日记（diary 工具）。
 * 复用 [WakeAssistantContext.buildSystemPrompt] 拿人设+记忆，让日记是「他」写的，不是流水账。
 */
data class DiaryEntry(val date: String, val text: String, val createdAt: Long) {
    fun toJson() = JSONObject().apply { put("date", date); put("text", text); put("createdAt", createdAt) }
    companion object {
        fun fromJson(o: JSONObject) = DiaryEntry(o.getString("date"), o.optString("text", ""), o.optLong("createdAt", 0L))
    }
}

object DiaryStore {
    private const val PREF = "xtom_diary"; private const val KEY = "entries"
    fun all(c: Context): List<DiaryEntry> = try {
        val arr = JSONArray(p(c).getString(KEY, "[]"))
        // 逐条容错：单条坏了不拖垮整表（否则 all() 返空→下次 put 会把历史全抹掉）
        (0 until arr.length()).mapNotNull { i -> runCatching { DiaryEntry.fromJson(arr.getJSONObject(i)) }.getOrNull() }
            .sortedByDescending { it.date }
    } catch (_: Exception) { emptyList() }
    fun get(c: Context, date: String): DiaryEntry? = all(c).firstOrNull { it.date == date }
    @Synchronized fun put(c: Context, e: DiaryEntry) {
        val list = all(c).filter { it.date != e.date } + e
        val arr = JSONArray(); list.sortedBy { it.date }.forEach { arr.put(it.toJson()) }
        p(c).edit().putString(KEY, arr.toString()).apply()
    }
    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}

object DiaryPrefs {
    private const val PREF = "xtom_diary_cfg"
    fun enabled(c: Context) = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()
    fun hour(c: Context) = p(c).getInt("hour", 21).coerceIn(0, 23)
    fun setHour(c: Context, v: Int) = p(c).edit().putInt("hour", v.coerceIn(0, 23)).apply()
    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
}

object DiaryGenerator {
    /** 生成今天的日记（有当天对话才写），存库。返回日记文本；无素材/失败返回 null。 */
    suspend fun generateToday(context: Context): String? {
        val ctx = context.applicationContext
        val cfgMgr = CloudApiConfigManager(ctx)
        val e = cfgMgr.getActiveByPurpose("chat") ?: return null
        val convMgr = ConversationManager(ctx)
        val todayStart = startOfTodayMillis()
        val convs = try { convMgr.repo.activeSummaries.first() } catch (_: Exception) { emptyList() }
            .filter { it.updatedAt >= todayStart }
        if (convs.isEmpty()) return null

        // 汇总今天聊过的内容（截断，避免超长）
        val material = buildString {
            var budget = 4000
            for (conv in convs.sortedByDescending { it.updatedAt }) {
                if (budget <= 0) break
                val msgs = runCatching { convMgr.loadMessages(conv.id) }.getOrDefault(emptyList())
                    .filter { (it.role == "user" || it.role == "assistant") && it.content.isNotBlank() }
                if (msgs.isEmpty()) continue
                append("〖").append(conv.title.ifBlank { "对话" }).append("〗\n")
                for (m in msgs.takeLast(20)) {
                    if (budget <= 0) break
                    val who = if (m.role == "user") "他" else "我"
                    val line = "$who：${m.content.take(200)}\n"
                    append(line); budget -= line.length
                }
                append("\n")
            }
        }.trim()
        if (material.isBlank()) return null

        val card = convs.firstOrNull()?.characterCardId
        val sys = try {
            WakeAssistantContext.buildSystemPrompt(ctx, card, null, userText = "今天的日记", continuation = null, apiSystemPrompt = e.systemPrompt.ifBlank { null })
        } catch (_: Exception) { null }
        // 只给任务与素材，不写「别像报告/要温暖」这类口号元指令——口吻由 system 人设承载(见 prompt-style)。
        val directive = PromptLang.pick("下面是今天你和他聊过的。以你自己的口吻，为今天写一小段日记，第一人称、几句话，写你记得的和在意的。", "Here's what you and them talked about today. In your own voice, write a short diary entry for today — first person, a few sentences, about what you remember and what mattered to you.") + "\n\n" + PromptLang.pick("【今天】\n", "[Today]\n") + "$material"

        val client = CloudApiClient(CloudApiConfig(e.baseUrl.trimEnd('/'), e.apiKey.trim(), e.model.trim()))
        var out = ""
        val r = client.streamChat(messages = listOf(ChatMessage("user", directive)), systemPrompt = sys, enableThinking = 0, onReasoningChunk = {}, onContentChunk = { out += it })
        val text = out.trim()
        if (r.error != null || text.isBlank()) return null
        DiaryStore.put(ctx, DiaryEntry(todayDate(), text, System.currentTimeMillis()))
        try { intoMemoryGraph(ctx, todayDate(), text, card) }
        catch (ce: kotlinx.coroutines.CancellationException) { throw ce }   // 取消不是失败，吞了上层就停不掉
        catch (_: Exception) {}   // 入记忆失败不能连累日记本身——日记已经写好存下了
        return text
    }

    /** 日记落进记忆库时用的 source。不在 [MemoryManager] 的 FRESH_SOURCES 里：断言时间由这里显式给当天零点。 */
    const val MEMORY_SOURCE = "diary"
    /** 一篇日记最多和当天多少条记忆连边。日记天然是个枢纽节点，不封顶会把两跳展开淹掉。 */
    private const val LINK_MAX = 30

    /**
     * 日记入图：把当天这篇日记同时落一条记忆，并与**当天新增的记忆**批量连 `part_of` 边。
     *
     * 为什么是这条边而不是别的：同一天发生的事就是同一天——这是整套关联里**唯一天然正确、
     * 不用判断**的边，不需要模型、不需要用户确认。补完之后 [MemoryManager] 的两跳展开才真有东西可跳，
     * 顺带解锁「上周三我们聊了什么」这类靠时间锚定的检索。
     *
     * ⚠ 标题写死中文不走 `tr()`：这是**存进库的数据**，不是界面文字。翻译它等于用户一换语言就多出一条
     * 同日期的新记忆（标题是 upsert 的匹配键，也是 embedding 的输入文本）。
     */
    private suspend fun intoMemoryGraph(ctx: Context, date: String, text: String, cardId: Long?) {
        val mm = MemoryManager(ctx)
        val title = "日记 $date"
        val dayStart = startOfTodayMillis()
        // 同一天重跑（补跑/手动叫日记工具）要**改写**那一条，不是再加一条
        val existing = mm.all().firstOrNull { it.title == title && it.characterCardId == cardId }
        if (existing != null) {
            mm.update(existing.id, title = title, content = text)   // 订正路径
        } else {
            mm.add(
                title = title, content = text, source = MEMORY_SOURCE, importance = 0.45f,
                characterCardId = cardId, type = "event",
            )
        }
    }

    fun notify(context: Context, text: String, cardName: String? = null, avatar: android.graphics.Bitmap? = null) {
        // 应用在前台就不弹：日记已入库（DiaryStore），设置页「翻看历史日记」里就能看到
        if (NotificationPrefs.suppressed(context)) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (android.os.Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "每日日记", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "每天的日记小结" })
            }
            val open = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP }
            val pi = PendingIntent.getActivity(context, 43001, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_edit)
                .setContentTitle(cardName?.ifBlank { null } ?: "今天的日记")
                .setSubText("日记")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true).setContentIntent(pi)
                .apply { if (avatar != null) setLargeIcon(avatar) }
                .build()
            nm.notify(43001, n)
        } catch (_: Exception) {}
    }

    fun todayDate(): String = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    private fun startOfTodayMillis(): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, 0); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    private const val CHANNEL = "arix_diary"
}

object DiaryScheduler {
    private const val WORK = "xtom_diary_work"
    /**
     * @param replace true=替换(设置改时间/开启/worker 续期用)；false=KEEP(启动时用，别打断正在跑/已排的任务，
     *   否则到点正生成日记时打开 App 会 REPLACE 掉运行中的 worker、当天日记被跳到明天)。
     */
    fun schedule(context: Context, replace: Boolean = true) {
        val delay = (nextRunMillis(DiaryPrefs.hour(context)) - System.currentTimeMillis()).coerceAtLeast(0L)
        // 写日记必然要一次模型往返：没网就等有网再跑（WorkManager 会自己补跑），
        // 而不是到点空跑一遍、读一堆会话、请求失败。⚠ 只给日记加，**提醒/定时任务不加**——
        // 那两个是用户指定时刻要发生的事，为它们加网络约束会把"到点提醒"变成"有网才提醒"。
        val req = OneTimeWorkRequestBuilder<DiaryWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        val policy = if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK, policy, req)
    }
    fun cancel(context: Context) = WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK).let {}
    fun apply(context: Context, enabled: Boolean) { DiaryPrefs.setEnabled(context, enabled); if (enabled) schedule(context) else cancel(context) }

    /** 下一个 hour:00 的绝对时刻（已过则明天）。 */
    private fun nextRunMillis(hour: Int): Long {
        val c = java.util.Calendar.getInstance()
        c.set(java.util.Calendar.HOUR_OF_DAY, hour); c.set(java.util.Calendar.MINUTE, 0); c.set(java.util.Calendar.SECOND, 0); c.set(java.util.Calendar.MILLISECOND, 0)
        if (c.timeInMillis <= System.currentTimeMillis()) c.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return c.timeInMillis
    }
    fun workName() = WORK
}

class DiaryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        try {
            if (CompanionPrefs.enabled(ctx) && DiaryPrefs.enabled(ctx)) {   // 陪伴包总闸
                val text = DiaryGenerator.generateToday(ctx)
                if (text != null) {
                    // 只要卡：取 id 再取卡，别把整份会话（messagesJson 可达 MB 级）拼回来
                    val card = runCatching {
                        val r = ConversationManager(ctx).repo
                        r.getMostRecentActiveId()?.let { cid -> r.cardIdOf(cid) }?.let { CharacterCardManager(ctx).getById(it) }
                    }.getOrNull()
                    DiaryGenerator.notify(ctx, text, card?.name, loadAvatarBitmap(ctx, card?.avatarPath))
                }
                // 排明天：只要仍开启就续期（放在生成之后，失败也续，明天再试）
                if (DiaryPrefs.enabled(ctx)) DiaryScheduler.schedule(ctx)
            }
            return Result.success()
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c   // 被取消(禁用/REPLACE/doze)时不吞、不在此续期，交给取消方
        } catch (_: Exception) {
            if (CompanionPrefs.enabled(ctx) && DiaryPrefs.enabled(ctx)) DiaryScheduler.schedule(ctx)   // 陪伴包总闸(异常续期路径也要挡)
            return Result.success()
        }
    }
}
