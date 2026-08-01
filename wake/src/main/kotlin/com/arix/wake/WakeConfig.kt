/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source.
 */

package com.arix.wake

/**
 * 唤醒功耗策略。见 DESIGN-WAKE.md §3.1 表。
 * 决定门控如何开麦：省电只在窗口内，充电可持续。
 */
enum class WakePowerPolicy {
    /** 省电窗口（默认）：仅门控触发后的 N 秒窗口开麦（电池 & 充电）。 */
    WINDOWED,

    /** 插电常听：电池时窗口，充电时进入 ALWAYS_ON 持续级联。 */
    ALWAYS_ON_WHEN_CHARGING,

    /**
     * 持续常听：开启即在前台开麦、**永不按窗口释放**，一直读到关闭。
     * 这是 MIUI 等 OEM 上**非系统 App 唯一能可靠持后台麦**的方式——麦克风只在前台启动一次、之后不再
     * 重启（对齐 Operit continuousMode）；避免了「后台新开麦被静音」。代价=持续耗电，见 DESIGN-WAKE。
     * （系统应用身份下可改回省电 WINDOWED + HOTWORD 特权源。）
     */
    ALWAYS_ON,

    /** 仅显式：只物理键 / 快捷方式呼出，从不自动开麦。 */
    EXPLICIT_ONLY,

    /** 关闭。 */
    OFF,
}

/**
 * L2 判决器选择。见 DESIGN-WAKE.md §5（走 (B) 二者兼得）。
 * 两种判决器共用状态机 + L0/L1 前端，经 [WakeDetector] 接口切换。
 */
enum class WakeDetectorMode {
    /** 默认「Hi Arix」固定短语 KWS（microWakeWord，Apache-2.0）。 */
    KWS,

    /** 自定义唤醒词：在端录制 → 语音嵌入原型匹配。 */
    CUSTOM_ENROLLMENT,
}

/**
 * 唤醒引擎配置。见 DESIGN-WAKE.md。
 * 默认值 = 省电窗口 + KWS 主路径。阈值/时长为工程初值，P7 需目标表实测调优。
 */
data class WakeConfig(
    val powerPolicy: WakePowerPolicy = WakePowerPolicy.WINDOWED,
    val detectorMode: WakeDetectorMode = WakeDetectorMode.KWS,

    // 门控窗口（§3.1）
    /** 门控触发后开麦窗口时长。 */
    val windowMs: Long = 6000L,
    /** 亮屏门控（默认主门控，零成本）。 */
    val enableScreenOnGate: Boolean = true,
    /** 加速度计抬腕增强档，默认关（依赖机型）。 */
    val enableMotionGate: Boolean = false,

    // 音频捕获（L0 大缓冲，§3.2）
    val sampleRate: Int = 16000,
    /** 大缓冲批处理后睡，替代现状 32ms 小帧的频繁唤醒。 */
    val captureBufferMs: Long = 100L,
    /** 安静环境降 8kHz 采样省电。 */
    val quietDownsample8k: Boolean = true,

    // L0 能量门（无 NN）
    val minRms: Float = 0.003f,
    val zeroCrossRateMax: Float = 0.35f,

    // L1 VAD 二次确认
    val vadEnabled: Boolean = true,

    // L2 判决阈值（各判决器细节在其实现里）
    val kwsThreshold: Float = 0.5f,
    val enrollmentSimilarityThreshold: Float = 0.85f, // 0.80→0.85 收紧：配合长度门降误触发（太高会漏，实测可调）
)
