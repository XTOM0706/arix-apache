/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source.
 */

package com.arix.wake

/**
 * 窗口内检测工作器：拥有麦克风循环，跑级联并在命中时回调。
 * 见 DESIGN-WAKE.md §3.
 *
 * [WakeController] 只依赖本抽象，不直接接触任何具体检测实现——
 * P2 用 [LegacyWakeWorker]（桥接现有 Operit LGPL 代码）；
 * P3 换成 clean-room 的 WakeAudioPipeline（L0 能量门 + L1 VAD + L2 判决），
 * 无需改动 [WakeController]。
 */
internal interface WakeWorker {
    /**
     * 启动麦克风循环并阻塞（挂起）直到 [stop] 或协程取消。
     * 命中唤醒词时调用 [onWake]；出错时调用 [onError] 并结束。
     */
    suspend fun run(onWake: (WakeDetection) -> Unit, onError: (String) -> Unit)

    /** 请求停止循环（释放麦克风）。 */
    fun stop()
}
