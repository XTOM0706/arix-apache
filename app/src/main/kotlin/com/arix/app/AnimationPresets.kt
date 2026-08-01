package com.arix.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween

object AnimationPresets {
    val drawerSlide = tween<Float>(durationMillis = 200, easing = FastOutSlowInEasing)
    val messageFadeIn = tween<Float>(durationMillis = 100, easing = LinearEasing)
    val cursorFadeOut = tween<Float>(durationMillis = 150, easing = LinearEasing)
    val menuPopup = spring<Float>(dampingRatio = 0.7f, stiffness = 300f)

    val cursorBlink = infiniteRepeatable<Float>(
        animation = keyframes {
            durationMillis = 900
            1f at 0
            1f at 600
            0.2f at 900
        },
        repeatMode = RepeatMode.Restart
    )
}
