package com.arix.tool

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/**
 * 日历：AI 直接读日程 / 建日程（读走 CalendarContract，建默认直接写；「AI 直接执行操作」关或没日历权限则跳系统日历由用户确认）。
 * list=列出接下来几天的日程；add=建一条。合并读+写于一个工具。
 */
class CalendarTool(private val context: Context) : Tool {
    override val name = "calendar"

    /** 日程本身就是「我什么时候在哪见谁」。见 [AndroidPermissionLevel.PRIVATE]。 */
    override val permissionLevel = AndroidPermissionLevel.PRIVATE

    override val description = "日程：list=列出接下来几天(默认7天)的日历事件；add=新建一条日程(有日历权限且「AI 直接执行操作」开时直接建好，否则调起系统日历由用户确认)。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Calendar. list: events in the next few days (default 7). add: create one — done directly when calendar permission and direct-action are on, otherwise the system calendar opens for the user to confirm."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply { put("type", "string"); put("description", "list (default)/add") })
            put("days", JSONObject().apply { put("type", "integer"); put("description", "days ahead for list, default 7") })
            put("title", JSONObject().apply { put("type", "string"); put("description", "title for add") })
            put("begin", JSONObject().apply { put("type", "string"); put("description", "start, yyyy-MM-dd HH:mm") })
            put("end", JSONObject().apply { put("type", "string"); put("description", "end; default start + 1h") })
            put("location", JSONObject().apply { put("type", "string"); put("description", "place") })
        })
        put("required", JSONArray(listOf<String>()))
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    private fun parseTime(s: String, defaultMinutesFromNow: Int): Long {
        if (s.isNotBlank()) for (f in listOf("yyyy-MM-dd HH:mm", "yyyy-MM-dd'T'HH:mm", "yyyy/MM/dd HH:mm"))
            try { return SimpleDateFormat(f, Locale.getDefault()).parse(s)!!.time } catch (_: Exception) {}
        return Calendar.getInstance().apply { add(Calendar.MINUTE, defaultMinutesFromNow) }.timeInMillis
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        when (params.optString("action", "list")) {
            "add" -> add(params)
            else -> list(params)
        }
    }

    private fun list(params: JSONObject): ToolResult {
        val days = params.optInt("days", 7).coerceIn(1, 60)
        val rows = try {
            upcoming(days) ?: return ToolResult("没有日历读取权限，请在「权限」页给 Arix 授予日历权限", isError = true)
        } catch (e: Exception) { return ToolResult("读取日历失败：${e.message}", isError = true) }
        return if (rows.isEmpty()) ToolResult("未来 $days 天没有日程") else ToolResult(rows.joinToString("\n"))
    }

    /**
     * 未来 [days] 天的日程行（已排版好的一行一条）。**没权限返回 null，没日程返回空表**——
     * 两者要分得开：调用方得知道「问不到」和「确实没有」不是一回事。读失败照抛，别吞成「没日程」。
     *
     * 抽成公开函数是给 [com.arix.app.HardSignalDigest] 用的：它原来靠 `content.startsWith("未来")`
     * 判「没日程」，`list` 那句文案一改就静默失效（Q23）。让它拿结构、别猜文案。
     */
    fun upcoming(days: Int = 7): List<String>? {
        if (!has(android.Manifest.permission.READ_CALENDAR)) return null
        return run {
            val begin = System.currentTimeMillis()
            val end = begin + days * 86_400_000L
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
                .let { ContentUris.appendId(it, begin); ContentUris.appendId(it, end); it.build() }
            val proj = arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.EVENT_LOCATION, CalendarContract.Instances.ALL_DAY)
            val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
            val rows = ArrayList<String>()
            context.contentResolver.query(uri, proj, null, null, CalendarContract.Instances.BEGIN + " ASC")?.use { c ->
                while (c.moveToNext() && rows.size < 50) {
                    val title = c.getString(0)?.ifBlank { "(无标题)" } ?: "(无标题)"
                    val b = c.getLong(1); val allDay = c.getInt(4) == 1
                    val loc = c.getString(3)
                    val time = if (allDay) SimpleDateFormat("M月d日", Locale.getDefault()).format(b) + " 全天" else fmt.format(b)
                    rows.add("· $time $title" + if (!loc.isNullOrBlank()) "（$loc）" else "")
                }
            }
            rows
        }
    }

    private fun add(params: JSONObject): ToolResult {
        val title = params.optString("title", "").ifBlank { return ToolResult("请提供日程标题", isError = true) }
        val begin = parseTime(params.optString("begin", ""), 60)
        var end = params.optString("end", "").let { if (it.isNotBlank()) parseTime(it, 60) else begin + 3_600_000L }
        if (end <= begin) end = begin + 3_600_000L   // 防 end 传坏/早于 begin → 零/负时长事件
        val loc = params.optString("location", "")
        val direct = com.arix.app.ConfigModePrefs.directActions(context)

        // 直接建：需 WRITE 权限 + 找到可写日历
        if (direct && has(android.Manifest.permission.WRITE_CALENDAR)) {
            try {
                val calId = writableCalendarId()
                if (calId != null) {
                    val values = ContentValues().apply {
                        put(CalendarContract.Events.CALENDAR_ID, calId)
                        put(CalendarContract.Events.TITLE, title)
                        put(CalendarContract.Events.DTSTART, begin)
                        put(CalendarContract.Events.DTEND, end)
                        if (loc.isNotBlank()) put(CalendarContract.Events.EVENT_LOCATION, loc)
                        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                    }
                    val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
                    if (uri != null) {
                        val fmt = SimpleDateFormat("M月d日 HH:mm", Locale.getDefault())
                        return ToolResult("已建日程「$title」：${fmt.format(begin)}" + if (loc.isNotBlank()) "，$loc" else "")
                    }
                }
            } catch (_: Exception) { /* 落到下面跳系统日历兜底 */ }
        }
        // 兜底/未开直接执行：跳系统日历新建界面由用户确认
        return try {
            val intent = Intent(Intent.ACTION_INSERT).apply {
                data = CalendarContract.Events.CONTENT_URI
                putExtra(CalendarContract.Events.TITLE, title)
                if (loc.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, loc)
                putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin)
                putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            ToolResult("已调起系统日历新建「$title」，请确认保存")
        } catch (e: Exception) { ToolResult("无法建日程：${e.message}", isError = true) }
    }

    /** 找一个可写、可见的日历 id（优先主日历）。 */
    private fun writableCalendarId(): Long? = try {
        val proj = arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.VISIBLE)
        var best: Long? = null; var bestPrimary = false
        context.contentResolver.query(CalendarContract.Calendars.CONTENT_URI, proj, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0); val access = c.getInt(1); val primary = c.getInt(2) == 1; val visible = c.getInt(3) == 1
                if (!visible || access < CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR) continue
                if (best == null || (primary && !bestPrimary)) { best = id; bestPrimary = primary }
            }
        }
        best
    } catch (_: Exception) { null }
}
