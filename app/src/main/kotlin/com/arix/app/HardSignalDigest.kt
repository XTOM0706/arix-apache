package com.arix.app

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 硬信号沉淀：把日历/位置/健康/App 使用/通知这些**确定的**信号，定期结晶成记忆。
 *
 * 为什么要做：这些信号我们本来就有工具能取，但一直是现查现用、用完即弃——问一次答一次，
 * 关掉对话就什么都没留下。它们和「模型从对话里抽出来的软信号」是两种东西：
 * 软信号贵（要过一次模型）、会漂（抽错了没人纠），是血肉；硬信号便宜、确定、不会幻觉，
 * 应该当骨架先立起来。骨架立住了，软信号才有地方挂。
 *
 * 三条自我约束（这是后台增强，不是主流程）：
 * - **限频**：20 小时一次，靠 SharedPreferences 时间戳，跨进程重启依然有效；
 * - **固定标题**：每项一个恒定标题走 upsertByTitle → 每次是**更新同一条**，不会越攒越多；
 * - **全程静默**：逐项 try/catch，取不到的项直接跳过，任何失败都不往外抛。
 *
 * 采集一律复用现成的东西（[LocationSignals]/[HealthSignals]/[NotificationStore] 以及
 * app_usage / calendar 两个工具），不重造一遍 ContentResolver 查询。
 */
object HardSignalDigest {
    private const val PREF = "xtom_hard_signal"
    private const val K_LAST = "last_run_at"
    /** 20 小时：比一天略短，这样它不会被「固定在某个时段」钉死，能慢慢转过一天里的各个时间。 */
    private const val MIN_INTERVAL_MS = 20L * 60 * 60 * 1000L
    /** 单条记忆的正文上限，与导入路径 (ImportExport.importMemories) 的 500 字保持一致。 */
    private const val CONTENT_CAP = 500

    // 固定标题：改这里等于「换一条记忆」，老的那条会留在库里变孤儿，非必要别动。
    private val T_PLACE = PromptLang.pick("常在的位置（硬信号）", "Hard-signal location")
    private val T_BODY = PromptLang.pick("身体与作息数据（硬信号）", "Health & schedule data (hard signals)")
    private val T_PHONE = PromptLang.pick("手机使用习惯（硬信号）", "Phone usage habits (hard signals)")
    private val T_SCHEDULE = PromptLang.pick("近期日程（硬信号）", "Upcoming schedule (hard signals)")
    private val T_NOTIF = PromptLang.pick("常来消息的应用（硬信号）", "Apps that message you often (hard signals)")
    private val T_DEVICE = PromptLang.pick("设备与所在时区（硬信号）", "Device & timezone (hard signals)")

    private fun prefs(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /**
     * 到点就跑一轮硬信号沉淀；没到点直接返回。**调用方不需要判断任何条件**，限频在里面。
     *
     * @param cardId 归属角色卡（null = 全局记忆）。
     */
    suspend fun maybeRun(context: Context, cardId: Long?) {
        try {
            val p = prefs(context)
            val now = System.currentTimeMillis()
            val last = p.getLong(K_LAST, 0L)
            // 时钟回拨（用户改系统时间/跨时区）会让 now-last 变成负数，那种情况也当作「该跑了」
            if (last != 0L && now - last in 0 until MIN_INTERVAL_MS) return
            // 时间戳**先写**：这一轮要碰定位/UsageStats/ContentResolver，不便宜。
            // 万一中途整体失败，宁可等下一个 20 小时，也不要每条消息都重试一遍。
            p.edit().putLong(K_LAST, now).apply()
            withContext(Dispatchers.IO) { collect(context.applicationContext, cardId) }
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce   // 取消不是失败：吞了它，上层的 STOP / 页面退出就停不掉这一轮（见记忆 stop-cancellation）
        } catch (_: Exception) {
            // 后台增强，绝不能影响主流程：其余异常一律静默
        }
    }

    private suspend fun collect(c: Context, cardId: Long?) {
        val mm = MemoryManager(c)
        val stamp = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(java.util.Date())

        // 每项独立 try/catch：一个信号取不到不该拖垮别的（手表上定位失败是家常便饭）。
        // 正文一律带采集时间——记忆是会被隔很久读到的，不标时间模型会把陈数据当成此刻。
        write(mm, T_PLACE, place(c), 0.55f, cardId, stamp)
        write(mm, T_BODY, health(c), 0.5f, cardId, stamp)
        write(mm, T_PHONE, phone(c), 0.5f, cardId, stamp)
        write(mm, T_SCHEDULE, schedule(c), 0.45f, cardId, stamp)
        write(mm, T_NOTIF, notifications(c), 0.4f, cardId, stamp)
        write(mm, T_DEVICE, device(), 0.4f, cardId, stamp)
    }

    /** 有内容才写；空/null 一律跳过——宁可这条记忆停留在上次的值，也不要写一条「暂无数据」进去。 */
    private suspend fun write(mm: MemoryManager, title: String, value: String?, importance: Float, cardId: Long?, stamp: String) {
        val text = value?.trim().orEmpty()
        if (text.isBlank()) return
        try {
            mm.upsertByTitle(
                title = title,
                content = "（$stamp 采集）$text".take(CONTENT_CAP),
                source = "hard_signal",
                importance = importance,
                characterCardId = cardId,
                tags = emptyList(),
                type = "environment",
            )
        } catch (ce: kotlinx.coroutines.CancellationException) { throw ce } catch (_: Exception) {}
    }

    // ---- 各路信号 ----------------------------------------------------------

    /** 位置：走 [LocationSignals]（缓存不新鲜时它会主动定一次），再反查成地名。只留地名，不存经纬度。 */
    private suspend fun place(c: Context): String? = try {
        val loc = LocationSignals.current(c, timeoutMs = 8_000L)
        if (loc == null) null else {
            @Suppress("DEPRECATION")
            val a = if (!android.location.Geocoder.isPresent()) null
            else runCatching {
                android.location.Geocoder(c, Locale.getDefault())
                    .getFromLocation(loc.latitude, loc.longitude, 1)?.firstOrNull()
            }.getOrNull()
            // 只到区县：这条要长期留着，存到门牌号既没必要也不体面
            val name = a?.let {
                listOfNotNull(it.adminArea, it.locality ?: it.subAdminArea, it.subLocality)
                    .filter { s -> s.isNotBlank() }.distinct().joinToString("")
            }
            if (name.isNullOrBlank()) null else "他人在 $name"
        }
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce } catch (_: Exception) { null }

    /** 身体数据：[HealthSignals] 自己管开关与数据源（tracker / Gadgetbridge / 健康连接），没开返回 null。 */
    private fun health(c: Context): String? = try {
        HealthSignals.snapshot(c)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) { null }

    /** App 使用：直接复用 app_usage 工具本体（近 7 天前 6 名），别再抄一遍 UsageStatsManager 的聚合。 */
    private suspend fun phone(c: Context): String? = try {
        if (!com.arix.tool.UsageStatsTool.hasAccess(c)) null
        else {
            val r = com.arix.tool.UsageStatsTool(c)
                .execute(JSONObject().put("days", 7).put("top", 6))
            if (r.isError) null else r.content.takeIf { it.isNotBlank() }
        }
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce } catch (_: Exception) { null }

    /**
     * 日程：复用 calendar 工具的读取（只读，不会写日历）。没权限/没日程都返回 null——
     * 一条只说「什么都没有」的记忆纯占位。
     *
     * 走 [com.arix.tool.CalendarTool.upcoming] 拿**结构**（空表=真没日程），不再靠
     * `content.startsWith("未来")` 猜工具的输出文案（那句一改这里就静默失效，Q23）。
     */
    private fun schedule(c: Context): String? = try {
        if (ContextCompat.checkSelfPermission(c, android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) null
        else com.arix.tool.CalendarTool(c).upcoming(7)?.takeIf { it.isNotEmpty() }?.joinToString("\n")
    } catch (ce: kotlinx.coroutines.CancellationException) { throw ce } catch (_: Exception) { null }

    /**
     * 通知：只统计**哪些 App 在找他**，不留标题正文。
     *
     * 通知内容是别人发给他的私信，沉淀成长期记忆既越界又会过期；「谁常来」才是稳定的画像。
     * [NotificationStore] 是进程内的 60 条环形缓存，取不到（刚启动/没授权）就跳过。
     */
    private fun notifications(c: Context): String? = try {
        if (!NotificationAwarenessPrefs.hasAccess(c) || !NotificationAwarenessPrefs.enabled(c)) null
        else {
            val top = NotificationStore.recent(60)
                .filter { it.app.isNotBlank() }
                .groupingBy { it.app }.eachCount()
                .entries.sortedByDescending { it.value }.take(5)
            if (top.size < 2) null   // 就一两条样本谈不上「常来」，别把偶然当规律
            else PromptLang.pick("最近的通知主要来自：", "Recent notifications mostly from:") + top.joinToString("、") { "${it.key}(${it.value}条)" }
        }
    } catch (_: Exception) { null }

    /** 设备与时区：最稳的一条，也最容易被模型算错（它默认按 UTC 想事）。 */
    private fun device(): String? = try {
        val tz = java.util.TimeZone.getDefault()
        val offsetMin = tz.getOffset(System.currentTimeMillis()) / 60_000
        val sign = if (offsetMin >= 0) "+" else "-"
        val off = "UTC$sign${"%d".format(kotlin.math.abs(offsetMin) / 60)}" +
            (kotlin.math.abs(offsetMin) % 60).let { if (it == 0) "" else ":%02d".format(it) }
        "设备 ${Build.MANUFACTURER} ${Build.MODEL}，Android ${Build.VERSION.RELEASE}；时区 ${tz.id}（$off）"
    } catch (_: Exception) { null }
}
