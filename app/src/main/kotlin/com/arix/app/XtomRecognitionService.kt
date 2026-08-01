package com.arix.app

import android.content.Intent
import android.speech.RecognitionService
import android.speech.SpeechRecognizer

/**
 * 最小 RecognitionService 桩：VoiceInteractionService 的 voice_interaction 元数据要求声明一个
 * recognitionService 才能注册为助手。Arix 的语音识别走云端 Whisper，不用系统识别器，故此桩
 * 仅用于满足注册，实际返回「不支持」。
 */
class XtomRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        try { listener?.error(SpeechRecognizer.ERROR_CLIENT) } catch (_: Exception) {}
    }

    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}
