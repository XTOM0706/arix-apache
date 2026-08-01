package com.arix.app.theme

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.tween

// ============================================================
// XtomMotion —— 动效令牌（DESIGN.md §3）
// 快速、线性、可打断（Telegram 丝滑省电），不用弹簧物理。
// 时长由 ThemeConfig.motion 决定；组件从这里取 spec，全局统一。
// （现有 AnimationPresets 仍保留，供旧代码用；新组件走 XtomMotion。）
// ============================================================

object XtomMotion {
    fun durationMs(speed: MotionSpeed): Int = when (speed) {
        MotionSpeed.FAST -> 130
        MotionSpeed.STANDARD -> 240
    }

    fun <T> tween(speed: MotionSpeed, easing: Easing = LinearEasing): TweenSpec<T> =
        tween(durationMillis = durationMs(speed), easing = easing)
}
