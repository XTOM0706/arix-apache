package com.arix.wake

/**
 * 统一语音服务接口，抽象本地引擎（sherpa-ncnn）和云端识别/合成两种后端。
 *
 * 设计目标：
 * - 语音识别（STT）和语音合成（TTS）合并为同一接口
 * - 识别结果通过回调通知，避免强依赖 Android StateFlow 等框架类型
 * - 纯 Kotlin，无平台特定依赖，可直接用于单元测试
 *
 * 使用模式：
 * ```
 * val speech: SpeechService = SherpaNcnnSpeechProvider(context)
 * speech.onRecognitionResult = { text -> process(text) }
 * speech.startListening()
 * // ... 用户说话完毕 ...
 * speech.stopListening()
 * speech.synthesize("好的，闹钟已设置")
 * speech.release()
 * ```
 */
interface SpeechService {

    /**
     * 语音识别结果回调。
     * 调用方设置此回调以接收 STT 转写的文本。
     */
    var onRecognitionResult: ((text: String) -> Unit)?

    /**
     * 语音识别错误回调。
     */
    var onRecognitionError: ((error: String) -> Unit)?

    /**
     * 开始监听/识别麦克风输入。
     * 识别结果通过 [onRecognitionResult] 回调异步返回。
     */
    suspend fun startListening()

    /**
     * 停止监听/识别。
     * 若 [startListening] 为持续模式，调用此方法结束识别并返回最终结果。
     */
    suspend fun stopListening()

    /**
     * 取消当前识别会话，不返回结果。
     */
    suspend fun cancelListening()

    /**
     * 语音合成，将文本转为语音并播放。
     *
     * @param text 待合成的文本
     */
    suspend fun synthesize(text: String)

    /**
     * 释放引擎资源（停止录音、卸载模型等）。
     */
    fun release()
}
