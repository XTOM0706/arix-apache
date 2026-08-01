/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source.
 */

package com.arix.wake

/**
 * L2 唤醒判决器抽象。见 DESIGN-WAKE.md §3/§5。
 *
 * 让固定短语 KWS 判决器与自定义嵌入原型判决器可切换，二者共用状态机 + L0/L1 前端。
 *
 * 生命周期：[reset] 开窗 → 多次 [accept]/[onSegmentEnd] → [close]。
 * - 流式判决器（KWS）在 [accept] 里每 ~30ms 出分；
 * - 段式判决器（嵌入原型）在 [onSegmentEnd]（VAD 段结束）里比对出分。
 */
interface WakeDetector : AutoCloseable {
    /** 判决器标识（如 "kws:hi_xtom" / "enrollment"）。 */
    val id: String

    /** 新监听窗口开始：清空流式状态。 */
    fun reset()

    /**
     * 喂入一块 16-bit PCM 单声道音频（@ [WakeConfig.sampleRate]）。
     * 流式判决器在此出分；命中返回 [WakeDetection]，否则 null。
     *
     * @param pcm 音频缓冲
     * @param length 有效样本数（可能小于 pcm.size）
     */
    fun accept(pcm: ShortArray, length: Int): WakeDetection?

    /**
     * VAD 标记一段语音结束时调用。
     * 段式判决器（嵌入原型）在此比对出分；流式判决器可忽略并返回 null。
     */
    fun onSegmentEnd(): WakeDetection?
}

/** 一次唤醒命中的结果。 */
data class WakeDetection(
    /** 命中的判决器 [WakeDetector.id]。 */
    val detectorId: String,
    /** 置信度 / 相似度（判决器语义自定，越高越确信）。 */
    val score: Float,
    /** 命中的唤醒短语（KWS 有；自定义模式可空）。 */
    val phrase: String? = null,
)
