/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source.
 */

package com.arix.wake

/**
 * 唤醒诊断日志：管线/状态机/判决器往这里写，UI(WakePage) 挂监听实时显示。
 * 唤醒在前台服务里跑，之前是黑盒——说了词不响看不到原因。有了它能看到：谁触发、开麦、
 * 检测到语音、相似度 vs 阈值、命中/未命中。
 */
object WakeLog {

    @Volatile
    var listener: ((String) -> Unit)? = null

    private val buffer = ArrayDeque<String>()

    @Synchronized
    fun d(msg: String) {
        buffer.addFirst(msg)
        while (buffer.size > 120) buffer.removeLast()
        try {
            listener?.invoke(msg)
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun recent(): List<String> = buffer.toList()

    @Synchronized
    fun clear() = buffer.clear()
}
