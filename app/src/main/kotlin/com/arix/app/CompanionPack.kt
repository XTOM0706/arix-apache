package com.arix.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// ============================================================
// 陪伴包 —— Arix 原生格式的可【下载/导入】配置包（不套 Operit .toolpkg）。
// 装了=解锁并按包配置启用情感陪伴层（主动消息/每日日记/AI主动来电/Waifu）；卸了=还原锁死、零运行时开销。
// 功能代码在 App 里但默认门控关闭(CompanionPrefs)，不装不跑；本包只是「开关+配置」，不含代码、不占运行内存。
//
// 我们自己的 JSON 格式：
// { "xtom_companion_pack": 1, "name":"标准陪伴", "version":"1", "description":"...",
//   "proactive": { "enabled": true, "callMode": false, "everyHours": 3 },
//   "diary":     { "enabled": true, "hour": 21 } }
// ============================================================
object CompanionPack {
    private const val FORMAT_KEY = "xtom_companion_pack"

    /** 解析并安装陪伴包(JSON)。返回提示语；格式不对抛异常。 */
    fun install(context: Context, json: String): String {
        val o = JSONObject(json)
        if (o.optInt(FORMAT_KEY, 0) < 1) throw IllegalArgumentException("不是 Arix 陪伴包（缺 xtom_companion_pack 标记）")
        val name = o.optString("name", "陪伴包"); val ver = o.optString("version", "1")
        // 先标记已装(满足门控)，再套配置+排调度
        CompanionPrefs.install(context, name, ver)
        o.optJSONObject("proactive")?.let { pj ->
            ProactivePrefs.setCallMode(context, pj.optBoolean("callMode", false))
            pj.optInt("everyHours", 0).takeIf { it > 0 }?.let { ProactivePrefs.setEveryHours(context, it) }
            ProactiveScheduler.apply(context, pj.optBoolean("enabled", true))
        }
        o.optJSONObject("diary")?.let { dj ->
            dj.optInt("hour", 0).takeIf { it in 0..23 }?.let { DiaryPrefs.setHour(context, it) }
            DiaryScheduler.apply(context, dj.optBoolean("enabled", true))
        }
        return "已安装陪伴包：$name v$ver"
    }

    /** 卸载：还原锁死 + 撤销调度 + 复位子功能开关（避免重装时静默自动恢复上次开态）。 */
    fun uninstall(context: Context) {
        CompanionPrefs.uninstall(context)
        ProactivePrefs.setEnabled(context, false)
        DiaryPrefs.setEnabled(context, false)
        ProactiveScheduler.cancel(context)
        DiaryScheduler.cancel(context)
    }

    /** 内置「标准陪伴包」——一键安装，免去找文件。 */
    fun builtinDefault(): String = JSONObject().apply {
        put(FORMAT_KEY, 1)
        put("name", "标准陪伴")
        put("version", "1")
        put("description", "主动消息 + 每日日记；AI 会在合适时机主动关心、每天写一篇日记。")
        put("proactive", JSONObject().apply { put("enabled", true); put("callMode", false); put("everyHours", 3) })
        put("diary", JSONObject().apply { put("enabled", true); put("hour", 21) })
    }.toString(2)

    /** 从 URL 下载陪伴包 JSON。失败返回 null。 */
    suspend fun download(url: String): String? = withContext(Dispatchers.IO) {
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 12000; conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", "Arix/1.0")
            if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext null }
            val s = conn.inputStream.bufferedReader().use { it.readText() }; conn.disconnect(); s
        } catch (_: Exception) { null }
    }
}
