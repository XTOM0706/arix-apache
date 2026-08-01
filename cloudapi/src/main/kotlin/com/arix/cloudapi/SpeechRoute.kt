package com.arix.cloudapi

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.util.Log

/**
 * TTS 的音频路由：普通朗读走**媒体流**，语音通话走**通话流**。
 *
 * ## 为什么必须有这个
 * 通话模式要 barge-in（AI 说话时用户能插嘴打断），就得一边放 AI 的声音、一边开麦听。
 * 麦克风必然收到喇叭放出的 AI 自己的声音 → VAD 判成「用户在说话」→ **AI 自己打断自己**。
 * 唯一的解法是平台的回声消除（AEC）。
 *
 * **但 AEC 不是万能开关**：它消除的是「通话参考流」——系统把正在播的通话音频作为参考信号
 * 喂给 AEC，AEC 才能从麦克风信号里把它减掉。如果 TTS 播在**媒体流**上（本项目原本如此：
 * NeuralTts 写死 USAGE_MEDIA，三个 MediaPlayer 引擎干脆没设 attributes = 默认媒体流），
 * AEC 拿不到参考信号，**消不掉任何东西**。此时 `AcousticEchoCanceler.isAvailable()` 仍然返回
 * true（它只表示「本机有 AEC 实现」），于是代码以为能打断，实际 AI 一开口就自我打断。
 *
 * 所以：录音端用 VOICE_COMMUNICATION 之外，**播放端也必须在同一条通话链路上**，
 * 且要把 AudioManager 切到 MODE_IN_COMMUNICATION。两端对齐 AEC 才有意义。
 *
 * ## 为什么不一律用通话流
 * 通话流会走听筒/降低音量/抢占音频焦点，普通朗读那样做体验很糟。所以只在通话期间切，
 * 通话结束切回来。
 */
object SpeechRoute {

    private const val TAG = "SpeechRoute"

    /** 通话模式期间为 true。TTS 引擎据此决定播到哪条流上。 */
    @Volatile
    var inCall: Boolean = false
        private set

    private var savedMode: Int? = null

    /** 给 TTS 引擎用：当前该用的 AudioAttributes。 */
    fun attributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(if (inCall) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    /** 进入通话：切系统音频模式到通话态，AEC 才有参考流可用。 */
    @Synchronized
    fun enterCall(context: Context) {
        if (inCall) return
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            savedMode = am?.mode
            am?.mode = AudioManager.MODE_IN_COMMUNICATION
            // 手表基本只有外放；不开免提的话通话模式会试图走听筒，声音小到听不见
            @Suppress("DEPRECATION")
            if (am?.isSpeakerphoneOn == false) am.isSpeakerphoneOn = true
            inCall = true
        } catch (e: Exception) {
            Log.w(TAG, "切通话音频模式失败: ${e.message}")
            inCall = false   // 切不过去就别声称在通话——上层据此关掉 barge-in
        }
    }

    /** 退出通话：把系统音频模式还回去。**必须调**，否则整机音频路由会一直卡在通话态。 */
    @Synchronized
    fun exitCall(context: Context) {
        val am = context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        try {
            am?.mode = savedMode ?: AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.w(TAG, "还原音频模式失败: ${e.message}")
        }
        savedMode = null
        inCall = false
    }
}
