/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source.
 */

package com.arix.wake

import android.content.Context

/**
 * 唤醒引擎工厂：app 侧的唯一入口，隐藏具体实现。见 DESIGN-WAKE.md §3。
 *
 * P3：[create] = 状态机 [WakeController] + clean-room [WakeAudioPipeline]（L0 能量门 +
 * L1 SileroVad）+ L2 判决器。判决器目前两种模式都用过渡 [LegacyDtwDetector]；
 * P4 起 KWS 模式换成 microWakeWord 的 KwsDetector（仅改本工厂的 detectorFactory）。
 */
object WakeEngines {

    fun create(context: Context): WakeEngine {
        val app = context.applicationContext
        return WakeController(workerFactory = { cfg ->
            WakeAudioPipeline(
                context = app,
                config = cfg,
                detectorFactory = { detectorFor(app, cfg) },
            )
        })
    }

    /** 「Hi Arix」默认 KWS 模型资源路径（用户 microwakeword.com 训练后放入）。 */
    private const val KWS_MODEL_ASSET = "models/hi_xtom.tflite"

    private fun detectorFor(context: Context, cfg: WakeConfig): WakeDetector = when (cfg.detectorMode) {
        // KWS 主路径：模型就绪用 microWakeWord；缺失则回退 clean-room 嵌入原型判决器
        // （用户若录过自定义词即可用；否则无原型时不触发）。
        WakeDetectorMode.KWS ->
            // ⚠ 这里必须兜 Throwable，不能只兜 Exception：tflite 运行时是 compileOnly（不打进包，
            // 见 wake/build.gradle.kts），**碰到 KwsDetector 这个类本身**就会抛 NoClassDefFoundError —— 那是 Error。
            // 兜不住的话唤醒会在建判决器时直接崩，而不是回退到嵌入原型判决器。
            runCatching { KwsDetector.create(context, KWS_MODEL_ASSET, "Hi Arix", cfg.kwsThreshold) }.getOrNull()
                ?: EmbeddingPrototypeDetector(context, cfg.enrollmentSimilarityThreshold)
        // 自定义唤醒词：clean-room 嵌入原型判决器（说话人相关）。
        WakeDetectorMode.CUSTOM_ENROLLMENT ->
            EmbeddingPrototypeDetector(context, cfg.enrollmentSimilarityThreshold)
    }
}
