/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source.
 */

package com.arix.wake

/**
 * 使唤醒引擎进入监听（ARMED / ALWAYS_ON）的触发源。见 DESIGN-WAKE.md §3.1。
 *
 * 主门控走近乎零功耗的系统事件（亮屏），显式呼出兜底。
 * [label] 供日志/通知使用（不用 data object，兼容各 Kotlin 版本）。
 */
sealed interface WakeTrigger {
    val label: String

    /** 亮屏广播 ACTION_SCREEN_ON —— 默认主门控，零成本（OS 抬腕点屏）。 */
    object ScreenOn : WakeTrigger {
        override val label = "screen_on"
    }

    /** 接通电源 —— 依策略可切到 ALWAYS_ON（床头/桌面免抬腕）。 */
    object PowerConnected : WakeTrigger {
        override val label = "power_connected"
    }

    /** 断开电源 —— 退出 ALWAYS_ON，回省电窗口。 */
    object PowerDisconnected : WakeTrigger {
        override val label = "power_disconnected"
    }

    /** 显式呼出（物理键 / 快捷方式 / 通知动作 / AI 工具 / UI 测试）。永远可用的兜底。 */
    data class Explicit(val source: String) : WakeTrigger {
        override val label = "explicit:$source"
    }
}
