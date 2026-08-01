/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source.
 */

package com.arix.wake

/**
 * 唤醒引擎门面：app 侧（WakeService / WakeTool / WakePage）通过它驱动唤醒，
 * 无需关心状态机 / 门控 / 级联细节。见 DESIGN-WAKE.md §3。
 *
 * 具体实现 `WakeController` 在 P2 落地（状态机 + 传感器门控 + 窗口内级联）；
 * 本接口是 P1 的 clean-room 骨架，供后续阶段实现与 app 侧接线。
 */
interface WakeEngine {
    /** 当前状态。 */
    val currentState: WakeState

    /**
     * 启动引擎：进入 IDLE，按 [config] 的策略注册门控（亮屏/电源/传感器）。幂等。
     */
    fun start(config: WakeConfig = WakeConfig())

    /**
     * 提交一个触发源。引擎按当前策略与状态决定是否开麦、开窗口还是切 ALWAYS_ON。
     */
    fun submitTrigger(trigger: WakeTrigger)

    /** 停止引擎，释放麦克风与资源，回到未运行。 */
    fun stop()

    /** 状态变化回调（供前台服务更新通知等）。 */
    var onStateChanged: ((WakeState) -> Unit)?

    /** 命中唤醒词回调。 */
    var onWake: ((WakeDetection) -> Unit)?

    /** 错误回调。 */
    var onError: ((String) -> Unit)?
}
