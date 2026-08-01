package com.arix.stt

import android.content.Context
import android.media.MediaPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * Edge TTS：微软 Edge 的在线 read-aloud 语音合成，**免 key、无需设备语音引擎**、神经网络音质。
 * WebSocket 连微软端点(TrustedClientToken + 时间派生的 Sec-MS-GEC 令牌)，发 SSML，收 MP3，MediaPlayer 播放。
 * 需联网；失败(无网/端点变动)返回 false，调用方退回其它引擎。
 */
object EdgeTts {
    private const val TRUSTED = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val GEC_VERSION = "1-137.0.3296.68"
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36 Edg/137.0.0.0"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private fun secMsGec(): String {
        // Windows file time (100ns since 1601)，向下取整到 5 分钟，SHA256(ticks+token) 大写十六进制
        var ticks = (System.currentTimeMillis() / 1000L + 11_644_473_600L) * 10_000_000L
        ticks -= ticks % 3_000_000_000L
        val digest = MessageDigest.getInstance("SHA-256").digest("$ticks$TRUSTED".toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun ssml(text: String, voice: String, rate: String, pitch: String): String {
        val esc = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
            "<voice name='$voice'><prosody pitch='$pitch' rate='$rate' volume='+0%'>$esc</prosody></voice></speak>"
    }

    /** 合成并播放（阻塞到播完）。成功 true；失败 false（调用方退回其它引擎）。 */
    suspend fun speak(
        context: Context,
        text: String,
        voice: String = "zh-CN-XiaoxiaoNeural",
        rate: String = "+0%",
        pitch: String = "+0Hz",
        onLog: (String) -> Unit = {},
    ): Boolean {
        if (text.isBlank()) return false
        onLog("Edge: 连接 bing 语音端点…")
        val mp3 = try { synth(text, voice, rate, pitch, onLog) } catch (e: Exception) { onLog("Edge 异常: ${e.message}"); null } ?: return false
        if (mp3.isEmpty()) { onLog("Edge: 音频为空"); return false }
        onLog("Edge: 取到音频 ${mp3.size / 1024}KB，播放…")
        return withContext(Dispatchers.IO) { playMp3(context.applicationContext, mp3) }
    }

    private suspend fun synth(text: String, voice: String, rate: String, pitch: String, onLog: (String) -> Unit): ByteArray? =
        suspendCancellableCoroutine { cont ->
            val connId = UUID.randomUUID().toString().replace("-", "")
            val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=$TRUSTED&Sec-MS-GEC=${secMsGec()}&Sec-MS-GEC-Version=$GEC_VERSION&ConnectionId=$connId"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .header("Origin", "chrome-extension://jdiccldimpahghkghgdcpnaabicpnbfch")
                .build()
            val audio = ByteArrayOutputStream()
            var resumed = false
            fun done(result: ByteArray?) { if (!resumed) { resumed = true; cont.resume(result) } }

            val listener = object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    val ts = SimpleDateFormat(
                        "EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US
                    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
                    ws.send(
                        "X-Timestamp:$ts\r\nContent-Type:application/json; charset=utf-8\r\nPath:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                    )
                    val reqId = UUID.randomUUID().toString().replace("-", "")
                    ws.send(
                        "X-RequestId:$reqId\r\nContent-Type:application/ssml+xml\r\nX-Timestamp:$ts\r\nPath:ssml\r\n\r\n" +
                            ssml(text, voice, rate, pitch)
                    )
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    if (text.contains("Path:turn.end")) { ws.close(1000, null); done(audio.toByteArray()) }
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    val b = bytes.toByteArray()
                    if (b.size < 2) return
                    val headerLen = ((b[0].toInt() and 0xFF) shl 8) or (b[1].toInt() and 0xFF)
                    val start = 2 + headerLen
                    if (start < b.size) audio.write(b, start, b.size - start)
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) { onLog("Edge 连接失败: ${t.message}${response?.let { " (HTTP ${it.code})" } ?: ""}"); done(null) }
                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    done(if (audio.size() > 0) audio.toByteArray() else null)
                }
            }
            val ws = client.newWebSocket(req, listener)
            cont.invokeOnCancellation { try { ws.cancel() } catch (_: Exception) {} }
        }

    private suspend fun playMp3(context: Context, mp3: ByteArray): Boolean =
        suspendCancellableCoroutine { cont ->
            try {
                val f = File(context.cacheDir, "edge_tts_${System.currentTimeMillis()}.mp3")
                f.writeBytes(mp3)
                val mp = MediaPlayer()
            // 通话期间必须播到通话流：AEC 只能消除通话参考流，播在媒体流上它消不掉
            // → AI 的声音会被自己的麦克风收回去、当成用户插话 → 自我打断。
            runCatching { mp.setAudioAttributes(com.arix.cloudapi.SpeechRoute.attributes()) }
                var resumed = false
                fun finish(ok: Boolean) {
                    if (!resumed) { resumed = true; try { mp.release() } catch (_: Exception) {}; f.delete(); cont.resume(ok) }
                }
                mp.setDataSource(f.absolutePath)
                mp.setOnCompletionListener { finish(true) }
                mp.setOnErrorListener { _, _, _ -> finish(false); true }
                mp.setOnPreparedListener { it.start() }
                mp.prepareAsync()
                cont.invokeOnCancellation { try { mp.release() } catch (_: Exception) {}; f.delete() }
            } catch (_: Exception) {
                cont.resume(false)
            }
        }
}
