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
 * [create] = 状态机 [WakeController] + clean-room [WakeAudioPipeline]（L0 能量门 +
 * L1 SileroVad）+ L2 判决器。判决器固定用 clean-room 嵌入原型判决器 [EmbeddingPrototypeDetector]
 * （自定义唤醒词：在端录制 → MFCC 特征原型比对）。KWS tflite 路径（KwsDetector）从未落地
 * （模型没训出来），已删除。
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

    /** 自定义唤醒词：clean-room 嵌入原型判决器（说话人相关；未录过词则无原型不触发）。 */
    private fun detectorFor(context: Context, cfg: WakeConfig): WakeDetector =
        EmbeddingPrototypeDetector(context, cfg.enrollmentSimilarityThreshold)
}
