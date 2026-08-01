package com.arix.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

// ============================================================
// song_profile —— 把一首歌拆成「可读的形状」，让读不了音频的 AI 也能"读"一首歌。
// 借鉴市场「Soundprint 声纹包」(作者 yuyixuanfu) 的思路；轻量版=歌名→网易云抓 曲目信息+歌词，
// 给出标题/歌手/专辑/时长 + 逐段歌词，让 AI 能就这首歌的词意、结构、情绪聊。
// （真·逐音符音高走势需要每首歌的 MIDI，不在此版范围。）
// ============================================================
class SongProfileTool : Tool {
    override val name = "song_profile"
    override val description = "读一首歌：给歌名(可带歌手)，返回曲目信息+完整歌词，让你能就这首歌的词意/结构/情绪与用户聊。适合「这首歌讲什么」「帮我读读这首歌词」这类。"

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("query", JSONObject().apply { put("type", "string"); put("description", "歌名，可带歌手，如「晴天 周杰伦」") })
        })
        put("required", org.json.JSONArray(listOf("query")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val q = params.optString("query", "").trim()
        if (q.isBlank()) return@withContext ToolResult("请提供歌名（可带歌手）", isError = true)
        try {
            val enc = URLEncoder.encode(q, "UTF-8")
            val searchJson = ncm("https://music.163.com/api/search/get?s=$enc&type=1&limit=1")
                ?: return@withContext ToolResult("没搜到这首歌（可换个更准确的歌名/加上歌手）", isError = true)
            val song = JSONObject(searchJson).optJSONObject("result")?.optJSONArray("songs")?.optJSONObject(0)
                ?: return@withContext ToolResult("没搜到「$q」，换个歌名试试")
            val id = song.optLong("id", 0L)
            val name = song.optString("name", q)
            val artists = song.optJSONArray("artists")?.let { a -> (0 until a.length()).joinToString("/") { a.optJSONObject(it)?.optString("name") ?: "" } }?.trim('/') ?: ""
            val album = song.optJSONObject("album")?.optString("name") ?: ""
            val durMs = song.optLong("duration", 0L)
            val dur = if (durMs > 0) String.format(java.util.Locale.US, "%d:%02d", durMs / 60000, (durMs / 1000) % 60) else ""

            // 歌词单独 try：抓不到/解析失败也不该丢掉已拿到的曲目信息
            val lyric = try {
                if (id > 0) ncm("https://music.163.com/api/song/lyric?id=$id&lv=1&kv=1&tv=-1")
                    ?.let { JSONObject(it).optJSONObject("lrc")?.optString("lyric", "") } ?: "" else ""
            } catch (_: Exception) { "" }
            val cleaned = stripLrc(lyric)

            ToolResult(buildString {
                append("🎵 $name")
                if (artists.isNotBlank()) append(" — $artists")
                append("\n")
                val meta = listOfNotNull(album.takeIf { it.isNotBlank() }?.let { "专辑：$it" }, dur.takeIf { it.isNotBlank() }?.let { "时长：$it" })
                if (meta.isNotEmpty()) append(meta.joinToString(" · ")).append("\n")
                append("\n—— 歌词 ——\n")
                append(if (cleaned.isNotBlank()) cleaned.take(3000) else "（这首歌没有可获取的歌词，可能是纯音乐）")
            })
        } catch (e: Exception) { ToolResult("读取失败：${e.message}", isError = true) }
    }

    // 网易云公开接口需带 Referer，否则被拦
    private fun ncm(url: String): String? = try {
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 12000
            setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
            setRequestProperty("Referer", "https://music.163.com")
        }
        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else { c.errorStream?.close(); null }
    } catch (_: Exception) { null }

    // 去掉 LRC 时间戳 [mm:ss.xx]，只留词
    private fun stripLrc(lrc: String): String = lrc
        .replace(Regex("""\[\d{1,2}:\d{2}(?:[.:]\d{1,3})?]"""), "")
        .lines().map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
}
