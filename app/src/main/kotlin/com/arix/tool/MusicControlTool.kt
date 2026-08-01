package com.arix.tool

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.view.KeyEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * 媒体控制：控制当前正在播放的音乐/视频 App（播放/暂停/切歌/停）+ 媒体音量。
 * 走 [AudioManager] 媒体键分发与音量调节，**免特殊权限**、不挑具体 App（控制系统当前活跃的媒体会话）。
 */
class MusicControlTool(private val context: Context) : Tool {
    override val name = "music_control"
    override val description = "控制媒体播放：now_playing 查看现在在放什么(歌名/歌手/播放态，需通知使用权)；play_pause/play/pause/next/prev/stop 控制当前在放的音乐(切歌/暂停后会回报当前曲目)；vol_up/vol_down 调媒体音量、vol_set 设为 level(0-100)；play_song 按歌名搜网易云并唤起 App 播放。控制免特殊权限。"
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply { put("type", "string"); put("description", "now_playing/play_pause/play/pause/next/prev/stop/vol_up/vol_down/vol_set/play_song") })
            put("level", JSONObject().apply { put("type", "integer"); put("description", "vol_set 时的音量 0-100") })
            put("query", JSONObject().apply { put("type", "string"); put("description", "play_song 时的歌名（可带歌手）") })
        })
        put("required", JSONArray(listOf("action")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return@withContext ToolResult("无法访问音频服务", isError = true)
        val action = params.optString("action", "")
        val keyCode = when (action) {
            "play_pause" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "prev", "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> 0
        }
        try {
            when {
                action == "now_playing" || action == "status" ->
                    ToolResult(nowPlaying() ?: "现在没检测到正在播放的媒体（若确有在放，去 设置→通知使用权 给 Arix 开启后才能读到当前曲目）")
                keyCode != 0 -> {
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
                    am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
                    val cn = when (action) {
                        "play_pause" -> "播放/暂停"; "play" -> "播放"; "pause" -> "暂停"
                        "next" -> "下一首"; "prev", "previous" -> "上一首"; "stop" -> "停止"; else -> action
                    }
                    // 切歌/暂停后媒体会话要一小会儿才更新——稍等再读，把当前曲目回报给用户
                    delay(if (action == "next" || action == "prev" || action == "previous") 600L else 200L)
                    val np = if (action == "stop") null else nowPlaying()
                    ToolResult("已$cn" + (np?.let { "\n$it" } ?: ""))
                }
                action == "vol_up" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI); ToolResult("已调高音量（${volPct(am)}%）") }
                action == "vol_down" -> { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI); ToolResult("已调低音量（${volPct(am)}%）") }
                action == "vol_set" -> {
                    val pct = params.optInt("level", -1)
                    if (pct !in 0..100) return@withContext ToolResult("请提供 0-100 的音量", isError = true)
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(pct / 100f * max), AudioManager.FLAG_SHOW_UI)
                    ToolResult("已把媒体音量设为 $pct%")
                }
                action == "play_song" -> playSong(params.optString("query", "").trim())
                else -> ToolResult("未知操作：$action", isError = true)
            }
        } catch (c: kotlinx.coroutines.CancellationException) { throw c   // STOP 必须真停：delay 挂起点会抛这个，绝不能被下面 catch 吞成「假成功」
        } catch (e: Exception) { ToolResult("媒体控制失败：${e.message}", isError = true) }
    }

    // 搜网易云拿 songId → 唤起网易云 App 直接播放（没装则打开网页版）。借鉴市场「网易云音乐 App 直接播放」。
    private fun playSong(q: String): ToolResult {
        if (q.isBlank()) return ToolResult("请提供歌名（可带歌手）", isError = true)
        val id = ncmSongId(q) ?: return ToolResult("没搜到「$q」，换个歌名试试", isError = true)
        val ncmInstalled = try { context.packageManager.getPackageInfo(NCM_PKG, 0); true } catch (_: Exception) { false }
        return try {
            if (ncmInstalled) {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("orpheus://song/$id/?autoplay=1")).apply {
                    setPackage(NCM_PKG); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                })
                ToolResult("正在用网易云播放：$q")
            } else {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://music.163.com/song?id=$id")).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
                ToolResult("没装网易云 App，已打开网页版：$q")
            }
        } catch (e: Exception) { ToolResult("唤起播放失败：${e.message}", isError = true) }
    }

    private fun ncmSongId(q: String): Long? = try {
        val url = "https://music.163.com/api/search/get?s=${URLEncoder.encode(q, "UTF-8")}&type=1&limit=1"
        val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
            connectTimeout = 10000; readTimeout = 12000
            setRequestProperty("User-Agent", "Mozilla/5.0")
            setRequestProperty("Referer", "https://music.163.com")
        }
        if (c.responseCode !in 200..299) { c.errorStream?.close(); null }
        else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            .optJSONObject("result")?.optJSONArray("songs")?.optJSONObject(0)?.optLong("id", 0L)?.takeIf { it > 0 }
    } catch (_: Exception) { null }

    /** 当前活跃媒体控制器（优先正在播放的那个）。需「通知使用权」，没给权限/读不到都返回 null。 */
    private fun activeController(): MediaController? = try {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val comp = ComponentName(context, com.arix.app.XtomNotificationListener::class.java)
        val list = msm?.getActiveSessions(comp).orEmpty()   // 没通知使用权会抛 SecurityException
        list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
    } catch (_: SecurityException) { null } catch (_: Exception) { null }

    /** 「▶ 正在播放：歌名 - 歌手」/「⏸ 已暂停：…」；读不到（无权限/无会话/无元数据）返回 null。 */
    private fun nowPlaying(): String? {
        val c = activeController() ?: return null
        val md = c.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)?.trim().orEmpty()
        val artist = (md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.trim().orEmpty()
        if (title.isBlank() && artist.isBlank()) return null
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        return buildString {
            append(if (playing) "▶ 正在播放：" else "⏸ 已暂停：")
            append(title.ifBlank { "(未知曲目)" })
            if (artist.isNotBlank()) append(" - ").append(artist)
        }
    }

    private fun volPct(am: AudioManager): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return Math.round(am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max)
    }

    private companion object { const val NCM_PKG = "com.netease.cloudmusic" }
}
