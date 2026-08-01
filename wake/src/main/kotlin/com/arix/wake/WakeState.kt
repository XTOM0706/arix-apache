/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source.
 */

package com.arix.wake

/**
 * 唤醒引擎的运行状态。见 DESIGN-WAKE.md §3.1。
 *
 * 状态机核心思想「别一直听」：IDLE 下麦克风关闭、近零功耗；
 * 仅在门控触发后进入 ARMED 开麦窗口跑级联（L0 能量门 → L1 VAD → L2 判决）。
 */
enum class WakeState {
    /** 麦克风关闭。前台服务存活（通知 + 广播接收器）但不持麦、不推理。近零增量功耗。 */
    IDLE,

    /** 开麦窗口内，跑 L0/L1/L2 级联。窗口超时无命中回 IDLE。 */
    ARMED,

    /** 命中唤醒词，正在拉起 UI / 交给 STT。瞬态，随后回 IDLE。 */
    TRIGGERED,

    /** 持续跑级联，不回 IDLE。仅充电 + 用户显式开启（见 [WakePowerPolicy.ALWAYS_ON_WHEN_CHARGING]）。 */
    ALWAYS_ON,
}
