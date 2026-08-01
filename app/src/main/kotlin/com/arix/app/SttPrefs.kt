package com.arix.app

import android.content.Context

/**
 * STT(语音转文本)配置——**持久化 + 跨页共享**。此前 SttPage 的配置只在内存(remember)，离页即丢、
 * 别处(唤醒助手/语音输入)读不到；这里落到 SharedPreferences，供全 App 复用同一份 STT 设置。
 *
 * provider：
 *  - "siliconflow" 硅基流动 SenseVoiceSmall —— 免费·中文强·国内直连(**推荐**)
 *  - "groq"        Groq Whisper —— 免费·快·国内需代理
 *  - "custom"      自建 OpenAI 兼容端点(自填 baseUrl/model)
 *  - "local"       离线 sherpa 本地模型
 */
object SttPrefs {
    private const val PREF = "xtom_stt"
    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    fun provider(c: Context): String = p(c).getString("provider", "") ?: ""
    fun setProvider(c: Context, v: String) = p(c).edit().putString("provider", v).apply()
    fun apiKey(c: Context): String = p(c).getString("api_key", "") ?: ""
    fun setApiKey(c: Context, v: String) = p(c).edit().putString("api_key", v.trim()).apply()
    fun customBaseUrl(c: Context): String = p(c).getString("base_url", "https://api.openai.com") ?: "https://api.openai.com"
    fun setCustomBaseUrl(c: Context, v: String) = p(c).edit().putString("base_url", v.trim()).apply()
    fun customModel(c: Context): String = p(c).getString("model", "whisper-1") ?: "whisper-1"
    fun setCustomModel(c: Context, v: String) = p(c).edit().putString("model", v.trim()).apply()
    fun lang(c: Context): String = p(c).getString("lang", "zh") ?: "zh"
    fun setLang(c: Context, v: String) = p(c).edit().putString("lang", v).apply()

    /** 云端 STT 调用参数（baseUrl 交给 WhisperClient 拼 /audio/transcriptions）。 */
    data class Cloud(val baseUrl: String, val apiKey: String, val model: String)

    /** 解析当前 provider 为实际云端调用参数；本地 / 缺 key / 未配置返回 null。预设内置 baseUrl+model。 */
    fun resolveCloud(c: Context): Cloud? {
        val key = apiKey(c)
        return when (provider(c)) {
            "siliconflow" -> if (key.isBlank()) null else Cloud("https://api.siliconflow.cn", key, "FunAudioLLM/SenseVoiceSmall")
            "groq" -> if (key.isBlank()) null else Cloud("https://api.groq.com/openai", key, "whisper-large-v3-turbo")
            "custom" -> if (key.isBlank()) null else Cloud(customBaseUrl(c), key, customModel(c))
            else -> null
        }
    }
}
