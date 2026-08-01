package com.arix.stt

data class SttResult(
    val text: String,
    val inferenceTimeMs: Long,
    val audioDurationMs: Long
) {
    val rtf: Float get() = if (audioDurationMs > 0) inferenceTimeMs.toFloat() / audioDurationMs else 0f
}
