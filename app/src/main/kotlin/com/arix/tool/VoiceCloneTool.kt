package com.arix.tool

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import com.arix.cloudapi.HttpClientProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * App 内声音克隆（自包含逻辑层）。
 *
 * 两条上游通道，都是「上传一段样本音频 → 拿到一个可复用的 voice_id」，得到的 id 直接喂给已有 TTS：
 *   - Minimax 原生（api.minimax.chat，与 [MinimaxTts] 同域同账号）：
 *       1) POST /v1/files/upload?GroupId=<gid>  multipart(purpose=voice_clone, file) → file_id
 *       2) POST /v1/voice_clone?GroupId=<gid>   json{file_id, voice_id:<你自取的名字>} → 成功
 *       voice_id 由调用方自取（≥8 位、字母开头、仅字母/数字/-/_），克隆后存进 TtsTool 的 mmx_voice 即可用。
 *       注意：新克隆的 voice_id 若 7 天内未被任何一次 TTS 使用会被回收，用户实际朗读一次即长期保留。
 *   - 硅基流动 SiliconFlow（OpenAI 兼容，app 云端 TTS 已推荐它的 CosyVoice）：
 *       POST <baseUrl>/uploads/audio/voice  multipart(model, customName, text=参考文本, file) → {uri:"speech:..."}
 *       返回的 uri 即音色 id，直接作为 CloudTts 的 voice 参数（存进 TtsTool 的 voice 偏好）。
 *
 * 本类不实现 [Tool]、不注册进包管理器——它是给 app 内「声音克隆」页面调用的纯逻辑，避免动别人的注册表。
 */
object VoiceCloneTool {

    const val PREFS = "xtom_voice_clone"
    private const val KEY_HISTORY = "history"

    /** 一条克隆记录，供页面回显 + 一键设为当前音色。 */
    data class ClonedVoice(val provider: String, val id: String, val name: String, val ts: Long)

    /** 克隆结果：ok=成功；id=可用的 voice_id / uri；message=给用户看的提示。 */
    data class CloneResult(val ok: Boolean, val id: String?, val message: String)

    // ---- Minimax 原生克隆 ----

    /**
     * @param desiredVoiceId 你自取的音色名（≥8 位、字母开头、仅字母/数字/-/_，不与已有重复）。
     */
    suspend fun cloneMinimax(
        context: Context,
        groupId: String,
        apiKey: String,
        model: String,
        sample: File,
        desiredVoiceId: String,
        displayName: String,
        onLog: (String) -> Unit = {},
    ): CloneResult = withContext(Dispatchers.IO) {
        if (groupId.isBlank() || apiKey.isBlank()) return@withContext CloneResult(false, null, "缺 GroupId / Key")
        validateMinimaxVoiceId(desiredVoiceId)?.let { return@withContext CloneResult(false, null, it) }
        if (!sample.exists() || sample.length() == 0L) return@withContext CloneResult(false, null, "样本音频为空")
        val client = HttpClientProvider.stt

        onLog("① 上传样本(${sample.length() / 1024}KB)…")
        val fileId = try { mmxUpload(client, groupId, apiKey, sample, onLog) } catch (e: Exception) {
            onLog("上传异常: ${e.message}"); return@withContext CloneResult(false, null, "上传异常: ${e.message}")
        } ?: return@withContext CloneResult(false, null, "上传失败（见日志）")
        onLog("拿到 file_id=$fileId")

        onLog("② 提交克隆 voice_id=$desiredVoiceId …")
        val url = "https://api.minimax.chat/v1/voice_clone?GroupId=${groupId.trim()}"
        val body = JSONObject().apply {
            put("file_id", fileId)
            put("voice_id", desiredVoiceId)
            put("model", model.ifBlank { "speech-02-hd" })
        }.toString().toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url(url).post(body)
            .header("Authorization", "Bearer ${apiKey.trim()}")
            .header("Content-Type", "application/json").build()
        try {
            client.newCall(req).execute().use { resp ->
                val txt = resp.body?.string()
                if (!resp.isSuccessful || txt.isNullOrBlank()) {
                    onLog("克隆 HTTP ${resp.code}: ${txt?.take(200)}")
                    return@withContext CloneResult(false, null, "克隆失败 HTTP ${resp.code}")
                }
                val json = try { JSONObject(txt) } catch (_: Exception) { null }
                val br = json?.optJSONObject("base_resp")
                val code = br?.optInt("status_code", -1) ?: -1
                if (code != 0) {
                    val msg = br?.optString("status_msg") ?: "错误码 $code"
                    onLog("克隆失败: $msg"); return@withContext CloneResult(false, null, "克隆失败: $msg")
                }
                onLog("克隆成功 ✓ voice_id=$desiredVoiceId")
                saveHistory(context, "minimax", desiredVoiceId, displayName.ifBlank { desiredVoiceId })
                CloneResult(true, desiredVoiceId, "克隆成功，voice_id 已可用")
            }
        } catch (e: Exception) {
            onLog("克隆异常: ${e.message}"); CloneResult(false, null, "克隆异常: ${e.message}")
        }
    }

    private fun mmxUpload(client: okhttp3.OkHttpClient, groupId: String, apiKey: String, file: File, onLog: (String) -> Unit): Long? {
        val url = "https://api.minimax.chat/v1/files/upload?GroupId=${groupId.trim()}"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("purpose", "voice_clone")
            .addFormDataPart("file", file.name, file.asRequestBody(guessMedia(file.name)))
            .build()
        val req = Request.Builder().url(url).post(body)
            .header("Authorization", "Bearer ${apiKey.trim()}").build()  // multipart 边界由 OkHttp 设，勿手写 Content-Type
        client.newCall(req).execute().use { resp ->
            val txt = resp.body?.string()
            if (!resp.isSuccessful || txt.isNullOrBlank()) { onLog("上传 HTTP ${resp.code}: ${txt?.take(200)}"); return null }
            val json = try { JSONObject(txt) } catch (_: Exception) { onLog("上传返回非 JSON"); return null }
            val br = json.optJSONObject("base_resp")
            val code = br?.optInt("status_code", -1) ?: -1
            if (code != 0) { onLog("上传失败: ${br?.optString("status_msg") ?: "错误码 $code"}"); return null }
            val fid = json.optJSONObject("file")?.optLong("file_id", -1L) ?: -1L
            return if (fid > 0) fid else { onLog("未取得 file_id"); null }
        }
    }

    /** Minimax voice_id 规则校验，返回错误文案，null=合法。 */
    fun validateMinimaxVoiceId(id: String): String? = when {
        id.length < 8 -> "voice_id 至少 8 位"
        !id.first().isLetter() -> "voice_id 必须以英文字母开头"
        !id.all { it.isLetterOrDigit() || it == '-' || it == '_' } -> "voice_id 只能含字母/数字/-/_"
        else -> null
    }

    // ---- 硅基流动克隆（OpenAI 兼容 uri） ----

    /**
     * @param baseUrl 硅基流动 baseUrl（如 https://api.siliconflow.cn/v1），取自「朗读」用途的模型配置。
     * @param text    参考音频对应的文字（必填，硅基流动要求）。
     */
    suspend fun cloneSiliconFlow(
        context: Context,
        baseUrl: String,
        apiKey: String,
        model: String,
        sample: File,
        customName: String,
        text: String,
        onLog: (String) -> Unit = {},
    ): CloneResult = withContext(Dispatchers.IO) {
        if (baseUrl.isBlank() || apiKey.isBlank()) return@withContext CloneResult(false, null, "缺 baseUrl / Key（先在模型配置里配好「朗读」用途）")
        if (customName.isBlank()) return@withContext CloneResult(false, null, "请填音色名 customName")
        if (text.isBlank()) return@withContext CloneResult(false, null, "请填参考音频对应的文字")
        if (!sample.exists() || sample.length() == 0L) return@withContext CloneResult(false, null, "样本音频为空")

        val url = baseUrl.trim().trimEnd('/') + "/uploads/audio/voice"
        onLog("上传并克隆 → $url（${sample.length() / 1024}KB）…")
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("model", model.ifBlank { "FunAudioLLM/CosyVoice2-0.5B" })
            .addFormDataPart("customName", customName)
            .addFormDataPart("text", text)
            .addFormDataPart("file", sample.name, sample.asRequestBody(guessMedia(sample.name)))
            .build()
        val req = Request.Builder().url(url).post(body)
            .header("Authorization", "Bearer ${apiKey.trim()}").build()
        try {
            HttpClientProvider.stt.newCall(req).execute().use { resp ->
                val txt = resp.body?.string()
                if (!resp.isSuccessful || txt.isNullOrBlank()) {
                    onLog("HTTP ${resp.code}: ${txt?.take(200)}")
                    return@withContext CloneResult(false, null, "克隆失败 HTTP ${resp.code}")
                }
                val uri = try { JSONObject(txt).optString("uri", "") } catch (_: Exception) { "" }
                if (uri.isBlank()) { onLog("返回无 uri: ${txt.take(200)}"); return@withContext CloneResult(false, null, "未取得音色 uri") }
                onLog("克隆成功 ✓ $uri")
                saveHistory(context, "siliconflow", uri, customName)
                CloneResult(true, uri, "克隆成功，音色 uri 已可用")
            }
        } catch (e: Exception) {
            onLog("克隆异常: ${e.message}"); CloneResult(false, null, "克隆异常: ${e.message}")
        }
    }

    // ---- 选取的音频文件 → 缓存文件（保留扩展名，供 multipart 上传） ----

    /** 把 content:// 音频拷进 cacheDir，返回文件（含合理扩展名）。失败返回 null。 */
    fun copyUriToCache(context: Context, uri: Uri): File? = try {
        val ext = when (context.contentResolver.getType(uri)) {
            "audio/mpeg" -> "mp3"; "audio/mp4", "audio/x-m4a" -> "m4a"
            "audio/wav", "audio/x-wav" -> "wav"; "audio/opus", "audio/ogg" -> "opus"; else -> "mp3"
        }
        val f = File(context.cacheDir, "voice_clone_pick.$ext")
        context.contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
        if (f.length() > 0) f else null
    } catch (_: Exception) { null }

    private fun guessMedia(name: String) = when {
        name.endsWith(".mp3", true) -> "audio/mpeg"
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".wav", true) -> "audio/wav"
        name.endsWith(".opus", true) || name.endsWith(".ogg", true) -> "audio/ogg"
        else -> "application/octet-stream"
    }.toMediaType()

    // ---- 克隆历史（存自有 prefs，不碰别人的表） ----

    fun history(context: Context): List<ClonedVoice> = try {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_HISTORY, "[]") ?: "[]"
        val arr = JSONArray(raw)
        (0 until arr.length()).map { arr.getJSONObject(it) }.map {
            ClonedVoice(it.optString("provider"), it.optString("id"), it.optString("name"), it.optLong("ts"))
        }
    } catch (_: Exception) { emptyList() }

    private fun saveHistory(context: Context, provider: String, id: String, name: String) {
        try {
            val cur = history(context).filterNot { it.id == id }
            val arr = JSONArray()
            arr.put(JSONObject().apply { put("provider", provider); put("id", id); put("name", name); put("ts", System.currentTimeMillis()) })
            cur.take(19).forEach { arr.put(JSONObject().apply { put("provider", it.provider); put("id", it.id); put("name", it.name); put("ts", it.ts) }) }
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_HISTORY, arr.toString()).apply()
        } catch (_: Exception) {}
    }
}

/**
 * 自包含麦克风录音器：AudioRecord → 内存 PCM16 → cacheDir 里的 WAV 文件。
 * 手动 start/stop（克隆样本一般要 10s 以上），最长约 5 分钟自动截断。需 RECORD_AUDIO（清单已声明）。
 */
class VoiceSampleRecorder(context: Context) {

    private companion object { const val SR = 16000 }

    private val appContext = context.applicationContext
    @Volatile private var recording = false
    @Volatile private var count = 0
    private var thread: Thread? = null
    private val samples = ArrayList<Short>()

    val isRecording: Boolean get() = recording
    /** 已录时长（秒），供 UI 计时显示。 */
    fun durationSec(): Int = count / SR

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (recording) return true
        val minBuf = AudioRecord.getMinBufferSize(SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuf <= 0) return false
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SR,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuf, 4096),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return false }
        synchronized(samples) { samples.clear() }
        count = 0; recording = true
        rec.startRecording()
        thread = Thread {
            val buf = ShortArray(1024)
            try {
                while (recording) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n > 0) {
                        synchronized(samples) { for (i in 0 until n) samples.add(buf[i]) }
                        count += n
                        if (count > SR * 300) { recording = false; break }   // ~5min 上限
                    }
                }
            } finally {
                try { rec.stop() } catch (_: Exception) {}
                try { rec.release() } catch (_: Exception) {}
            }
        }.also { it.start() }
        return true
    }

    /** 停止并写 WAV，返回文件；无有效音频返回 null。 */
    fun stop(): File? {
        if (!recording && count == 0) return null
        recording = false
        try { thread?.join(2000) } catch (_: Exception) {}
        thread = null
        val snap = synchronized(samples) { samples.toShortArray() }
        if (snap.isEmpty()) return null
        return writeWav(snap)
    }

    fun cancel() {
        recording = false
        try { thread?.join(1000) } catch (_: Exception) {}
        thread = null
        synchronized(samples) { samples.clear() }
        count = 0
    }

    private fun writeWav(pcm: ShortArray): File {
        val f = File(appContext.cacheDir, "voice_clone_sample.wav")
        val dataLen = pcm.size * 2
        val bb = ByteBuffer.allocate(44 + dataLen).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1); bb.putShort(1)          // PCM, mono
        bb.putInt(SR); bb.putInt(SR * 2); bb.putShort(2); bb.putShort(16)                    // byteRate, blockAlign, bits
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in pcm) bb.putShort(s)
        f.writeBytes(bb.array())
        return f
    }
}
