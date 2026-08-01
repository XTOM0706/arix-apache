package com.arix.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 应用内「灵动岛」——顶部居中悬浮的自绘胶囊，显示 AI 当前活动：左=状态/工具图标+操作，右=输出内容。
 *
 * 与系统超级岛/流体云共用 [CapsuleBridge.island] 这份状态，但**完全自绘**：配色/流光走 [CapsulePrefs] 主题，
 * 所有机型表现一致、效果全可控，不受厂商私有协议摆布。由 [CapsulePrefs.displayMode]（inapp/both）决定是否挂载。
 *
 * 无布局占位（挂在最外层 Box 的 TopCenter，overlay 于所有页面之上）；进/出滑动+淡入淡出。
 */
@Composable
fun InAppIsland(
    state: CapsuleBridge.IslandState?,
    palette: CapsulePrefs.CapsuleTheme,
    animated: Boolean,
    modifier: Modifier = Modifier,
) {
    // 保留最后一帧内容，好让「收起」动画期间仍有东西可画（state 已变 null）。
    var shown by remember { mutableStateOf(state) }
    LaunchedEffect(state) { if (state != null) shown = state }

    AnimatedVisibility(
        visible = state != null,
        modifier = modifier,
        enter = slideInVertically { -it } + fadeIn(),
        exit = slideOutVertically { -it } + fadeOut(),
    ) {
        val s = state ?: shown ?: return@AnimatedVisibility
        IslandPill(s, palette, animated)
    }
}

@Composable
private fun IslandPill(
    s: CapsuleBridge.IslandState,
    palette: CapsulePrefs.CapsuleTheme,
    animated: Boolean,
) {
    val titleColor = parseColor(palette.title, Color.White)
    val contentColor = parseColor(palette.content, Color(0xB0FFFFFF))
    val effect = parseColor(palette.effect, Color(0xFF7C5CFF))

    // 流光：一条高亮带沿胶囊从左到右循环横扫（关动画则不画）。
    val sweep = if (animated) {
        val tr = rememberInfiniteTransition(label = "island-sweep")
        tr.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
            label = "sweep",
        ).value
    } else 0f

    Row(
        modifier = Modifier
            .shadow(10.dp, RoundedCornerShape(percent = 50))
            .clip(RoundedCornerShape(percent = 50))
            .background(Color(0xF20C0C0F))   // 近黑半透，贴「灵动岛」质感
            .then(
                if (animated) Modifier.drawWithContent {
                    drawContent()
                    val w = size.width
                    val band = w * 0.55f
                    val x = -band + sweep * (w + band)   // 从左侧外滑到右侧外
                    drawRect(
                        brush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                0.5f to effect.copy(alpha = 0.30f),
                                1f to Color.Transparent,
                            ),
                            startX = x,
                            endX = x + band,
                        ),
                    )
                } else Modifier,
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .widthIn(max = 340.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(s.iconRes),
            contentDescription = null,
            tint = effect,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(7.dp))
        // 左：操作（在干嘛），最多占一半、超长省略。
        Text(
            text = s.operation,
            color = titleColor,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 168.dp),
        )
        if (s.output.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            // 右：AI 输出内容，吃剩余空间、超长省略。
            Text(
                text = s.output,
                color = contentColor,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (s.progress != null) {
            Spacer(Modifier.width(8.dp))
            Text(text = "${s.progress}%", color = effect, fontSize = 11.sp, maxLines = 1)
        }
    }
}

private fun parseColor(hex: String, fallback: Color): Color =
    runCatching { Color(android.graphics.Color.parseColor(hex)) }.getOrDefault(fallback)
