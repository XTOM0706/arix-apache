package com.arix.tool

import android.content.Context
import android.speech.tts.TextToSpeech
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.roundToInt

class TtsTool(private val context: Context) : Tool {
    override val name = "tts"
    override val description = "文字转语音。将文本朗读出来，支持多种语言。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Read text aloud."

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "text to speak")
            })
            put("language", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("zh", "en", "ja", "ko")))
                put("description", "default zh")
            })
            put("pitch", JSONObject().apply {
                put("type", "number")
                put("description", "pitch 0.5-2.0, default 1.0")
            })
            put("speed", JSONObject().apply {
                put("type", "number")
                put("description", "speed 0.5-2.0, default 1.0")
            })
        })
        put("required", JSONArray(listOf("text")))
    }

    private var tts: TextToSpeech? = null
    private val ttsModelManager = com.arix.stt.TtsModelManager(context)
    @Volatile private var neural: com.arix.stt.NeuralTts? = null

    companion object {
        const val PREFS = "xtom_tts"
        const val KEY_ENGINE = "engine"  // auto / cloud / edge / neural / system
        const val KEY_VOICE = "voice"
        private fun p(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        fun enginePref(context: Context): String = p(context).getString(KEY_ENGINE, "auto") ?: "auto"
        fun setEnginePref(context: Context, engine: String) = p(context).edit().putString(KEY_ENGINE, engine).apply()
        fun voicePref(context: Context): String = p(context).getString(KEY_VOICE, "") ?: ""
        fun setVoicePref(context: Context, v: String) = p(context).edit().putString(KEY_VOICE, v).apply()
        // Minimax 原生 TTS 配置（GroupId/Key/voice_id/model）——它不是 OpenAI 兼容格式，单独存
        fun mmxGroup(c: Context) = p(c).getString("mmx_group", "") ?: ""
        fun mmxKey(c: Context) = p(c).getString("mmx_key", "") ?: ""
        fun mmxVoice(c: Context) = p(c).getString("mmx_voice", "") ?: ""
        fun mmxModel(c: Context) = (p(c).getString("mmx_model", "") ?: "").ifBlank { "speech-02-hd" }
        fun setMinimax(c: Context, group: String, key: String, voice: String, model: String) = p(c).edit()
            .putString("mmx_group", group.trim()).putString("mmx_key", key.trim())
            .putString("mmx_voice", voice.trim()).putString("mmx_model", model.trim()).apply()
        fun mmxConfigured(c: Context) = mmxGroup(c).isNotBlank() && mmxKey(c).isNotBlank()
        // 每角色卡单独绑定音色（存 SharedPreferences，key=voice_card_<id>，免 Room 迁移）
        fun cardVoicePref(context: Context, cardId: Long?): String =
            if (cardId == null || cardId <= 0) "" else (p(context).getString("voice_card_$cardId", "") ?: "")
        fun setCardVoicePref(context: Context, cardId: Long, v: String) =
            p(context).edit().putString("voice_card_$cardId", v.trim()).apply()
        /** 该角色卡绑了音色就用它，否则回退全局音色。 */
        fun effectiveVoice(context: Context, cardId: Long?): String = cardVoicePref(context, cardId).ifBlank { voicePref(context) }
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val text = params.optString("text", "")
        if (text.isBlank()) return ToolResult("请输入要朗读的文本", isError = true)
        val lang = params.optString("language", "zh")
        val pitch = params.optDouble("pitch", 1.0).toFloat().coerceIn(0.5f, 2.0f)
        val speed = params.optDouble("speed", 1.0).toFloat().coerceIn(0.5f, 2.0f)
        val cardId = params.optLong("card_id", -1L).takeIf { it > 0 }   // 当前对话绑定的角色卡 → 用它的专属音色
        return when (speak(text, lang, speed, pitch, cardId = cardId)) {
            "neural" -> ToolResult("正在朗读(离线神经语音): ${text.take(50)}...")
            "cloud" -> ToolResult("正在朗读(云端语音): ${text.take(50)}...")
            "edge" -> ToolResult("正在朗读(Edge 在线): ${text.take(50)}...")
            "system" -> ToolResult("正在朗读(系统语音): ${text.take(50)}...")
            else -> ToolResult("朗读失败（无可用语音引擎/无网络）", isError = true)
        }
    }

    /** 按引擎偏好+优先级朗读，返回实际用的引擎名。auto=离线神经(已下)→云端→Edge→系统。onLog 吐诊断日志。 */
    suspend fun speak(text: String, lang: String = "zh", speed: Float = 1.0f, pitch: Float = 1.0f, onLog: (String) -> Unit = {}, cardId: Long? = null): String {
        if (text.isBlank()) return "fail"
        val spoken = sanitizeForSpeech(text)      // 去 Markdown 语法/emoji，避免被 TTS 逐字读出
        if (spoken.isBlank()) return "fail"
        val pref = enginePref(context)
        onLog("引擎偏好: $pref")

        // 离线神经（已下模型）
        if (pref == "auto" || pref == "neural") {
            if (!ttsModelManager.isReady()) { onLog("离线神经: 未下模型，跳过") }
            else {
                onLog("离线神经: 合成中…")
                val ok = withContext(Dispatchers.IO) {
                    try {
                        if (neural == null) neural = com.arix.stt.NeuralTts.create(ttsModelManager.modelDir(), onLog)
                        if (neural == null) false
                        else { neural!!.speak(spoken, speed); true }
                    } catch (e: Throwable) { onLog("离线神经异常: ${e.message}"); false }
                }
                if (ok) { onLog("离线神经 ✓"); return "neural" }
            }
        }

        // Minimax 原生 TTS（engine=minimax 强制走；auto 时若已配置也走，音质好、参数丰富）
        if (pref == "minimax" || (pref == "auto" && mmxConfigured(context))) {
            val ok = try {
                // 音色：本卡专属(可填 minimax voice_id)优先，否则用全局 minimax voice；不混用 OpenAI 那套音色
                com.arix.cloudapi.MinimaxTts.speak(context, mmxGroup(context), mmxKey(context), cardVoicePref(context, cardId).ifBlank { mmxVoice(context) }, mmxModel(context), spoken, speed, onLog)
            } catch (e: Throwable) { onLog("Minimax异常: ${e.message}"); false }
            if (ok) { onLog("Minimax ✓"); return "minimax" }
        }

        // 云端 OpenAI 兼容 TTS
        if (pref == "auto" || pref == "cloud") {
            val cfg = try { com.arix.app.CloudApiConfigManager(context).getActiveByPurpose("tts") } catch (_: Exception) { null }
            if (cfg == null || cfg.baseUrl.isBlank()) { onLog("云端: 未配置「朗读」用途的模型，跳过") }
            else {
                val ok = try {
                    com.arix.cloudapi.CloudTts.speak(context, cfg.baseUrl, cfg.apiKey, cfg.model, spoken, effectiveVoice(context, cardId), speed, onLog)
                } catch (e: Throwable) { onLog("云端异常: ${e.message}"); false }
                if (ok) { onLog("云端 ✓"); return "cloud" }
            }
        }

        // Edge 在线
        if (pref == "auto" || pref == "edge") {
            val ok = try { com.arix.stt.EdgeTts.speak(context, spoken, rate = speedToRate(speed), onLog = onLog) } catch (e: Throwable) { onLog("Edge异常: ${e.message}"); false }
            if (ok) { onLog("Edge ✓"); return "edge" }
        }

        // 系统 TTS 兜底
        if (pref == "system" || pref == "auto" || pref == "neural" || pref == "edge" || pref == "cloud" || pref == "minimax") {
            onLog("系统语音: 尝试…")
            val ok = systemSpeak(spoken, lang, speed, pitch, onLog)
            if (ok) { onLog("系统语音 ✓"); return "system" }
        }
        onLog("全部失败")
        return "fail"
    }

    /** 朗读前清洗：去掉 Markdown 语法与 emoji，避免 TTS 把 * # ` 、表情逐字读出来。 */
    private fun sanitizeForSpeech(raw: String): String {
        var s = raw
        s = s.replace(Regex("```[\\s\\S]*?```"), " ")                       // 围栏代码块
        s = s.replace(Regex("`([^`]*)`"), "$1")                              // 行内码
        s = s.replace(Regex("!\\[[^\\]]*\\]\\([^)]*\\)"), " ")               // 图片
        s = s.replace(Regex("\\[([^\\]]*)\\]\\([^)]*\\)"), "$1")             // 链接→只留文字
        s = s.replace(Regex("(?m)^#{1,6}\\s*"), "")                          // 标题 #
        s = s.replace(Regex("(?m)^\\s*>\\s?"), "")                           // 引用 >
        s = s.replace(Regex("(?m)^\\s*[-*+]\\s+"), "")                       // 无序列表符
        s = s.replace(Regex("(?m)^\\s*[-*_]{3,}\\s*$"), " ")                 // 分割线
        s = s.replace(Regex("\\*\\*|__|\\*|_|~~"), "")                       // 粗体/斜体/删除线标记
        // emoji 与常见符号区段
        s = s.replace(Regex("[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2190}-\\x{21FF}\\x{2B00}-\\x{2BFF}\\x{FE00}-\\x{FE0F}\\x{1F1E6}-\\x{1F1FF}\\x{2000}-\\x{206F}]"), " ")
        s = s.replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\\n{3,}"), "\n\n")
        return s.trim()
    }

    private fun speedToRate(speed: Float): String {
        val pct = ((speed - 1f) * 100f).roundToInt()
        return if (pct >= 0) "+$pct%" else "$pct%"
    }

    private suspend fun systemSpeak(text: String, lang: String, speed: Float, pitch: Float, onLog: (String) -> Unit): Boolean {
        val locale = when (lang) { "en" -> Locale.US; "ja" -> Locale.JAPAN; "ko" -> Locale.KOREAN; else -> Locale.CHINESE }
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                var resumed = false
                fun done(ok: Boolean) { if (!resumed) { resumed = true; cont.resume(ok) } }
                val uid = "tts_${System.currentTimeMillis()}"
                tts?.shutdown()  // 释放上一次引擎，避免泄露 ServiceConnection
                tts = TextToSpeech(context) { status ->
                    if (status == TextToSpeech.SUCCESS) {
                        val eng = tts?.defaultEngine ?: "?"
                        val langOk = tts?.setLanguage(locale)
                        onLog("系统语音: 引擎=$eng, setLanguage=$langOk (-1缺数据/-2不支持/>=0可用)")
                        tts?.setPitch(pitch)
                        tts?.setSpeechRate(speed)
                        // 等**真的播完**再返回：原来 speak() 一发出去就 done(true)，那只表示「送进队列成功」，
                        // 不表示念完了。语音通话据此判断「AI 说完没有」，不等的话会在 AI 还在说时就开始听下一轮。
                        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                            override fun onStart(utteranceId: String?) {}
                            override fun onDone(utteranceId: String?) { if (utteranceId == uid) done(true) }
                            @Deprecated("旧签名，父类要求实现")
                            override fun onError(utteranceId: String?) { if (utteranceId == uid) { onLog("系统语音: 播放出错"); done(false) } }
                            override fun onStop(utteranceId: String?, interrupted: Boolean) { if (utteranceId == uid) done(true) }
                        })
                        // 放宽：不因 langOk 不支持就放弃，直接尝试朗读，让引擎自己处理
                        val r = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, uid)
                        if (r != TextToSpeech.SUCCESS) { onLog("系统语音: speak 返回 $r (非0=失败)"); done(false) }
                        // 成功时不 done —— 交给 onDone/onStop 回调
                    } else {
                        onLog("系统语音: 引擎初始化失败(status=$status)")
                        done(false)
                    }
                }
                // 打断/挂断：停掉正在念的（onStop 回调会让上面的 done 走掉）
                cont.invokeOnCancellation { runCatching { tts?.stop() } }
            }
        }
    }

    /**
     * 停掉当前这句朗读，但**保留引擎**（区别于 [shutdown] 的销毁）——语音通话里用户每插一次话
     * 就要停一次，每次都销毁重建系统 TTS 引擎太慢，且会丢掉已 bind 的 ServiceConnection。
     *
     * 云端/Minimax/Edge 三个引擎走 MediaPlayer，本就是 suspendCancellableCoroutine +
     * invokeOnCancellation 释放播放器，**取消 speak 所在协程即可停**，这里管不着也不用管。
     * 这里只处理够不到协程取消的两个：系统 TTS 与离线神经的 AudioTrack。
     */
    fun stopSpeaking() {
        runCatching { tts?.stop() }
        runCatching { neural?.stop() }
    }

    fun shutdown() {
        tts?.stop(); tts?.shutdown(); tts = null
        try { neural?.release() } catch (_: Exception) {}
        neural = null
    }
}
