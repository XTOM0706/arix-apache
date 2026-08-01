package com.arix.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.theme.LocalXtomAccents
import kotlin.math.cos
import kotlin.math.sin

/**
 * 唤醒 / 助手召出后的全屏 UI。见 DESIGN-WAKE.md。
 *
 * 仿 Gemini 的流动光球动画，但配色/形状全走 Arix 令牌。作为“助手已召出”的入口：
 * 点「进入对话」直接落进完整聊天页（那里 STT/AI/工具/记忆都已就绪），避免在此重复实现。
 *
 * @param phase   顶部提示词（如“在听…”/“已唤醒”）。
 * @param onEnter 进入完整对话。
 * @param onClose 关闭 overlay。
 */
@Composable
fun WakeAssistantOverlay(
    phase: String = "已唤醒",
    onEnter: () -> Unit,
    onClose: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(scheme.background),
        contentAlignment = Alignment.Center,
    ) {
        // 右上角关闭
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .size(40.dp)
                .clip(CircleShape)
                .background(scheme.surfaceContainerHighest)
                .clickable(onClick = onClose),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = scheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxWidth().padding(24.dp),
        ) {
            AssistantOrb(modifier = Modifier.size(180.dp))
            Spacer(Modifier.height(28.dp))
            Text(phase, color = scheme.onSurface, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Text(tr("Arix 已就绪"), color = scheme.onSurfaceVariant, fontSize = 13.sp)
            Spacer(Modifier.height(32.dp))

            // 进入完整对话（STT/AI/工具都在那里）
            Surface(
                onClick = onEnter,
                shape = RoundedCornerShape(50),
                color = scheme.primaryContainer,
                shadowElevation = 3.dp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.AutoMirrored.Outlined.Chat, contentDescription = null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(tr("进入对话"), color = scheme.onPrimaryContainer, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

/** Gemini 风流动光球：旋转的多色径向渐变叶瓣 + 呼吸缩放 + 柔光晕。全用主题令牌配色。 */
@Composable
private fun AssistantOrb(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    val infinite = rememberInfiniteTransition(label = "orb")
    val angle by infinite.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(7000, easing = LinearEasing)), label = "ang",
    )
    val pulse by infinite.animateFloat(
        initialValue = 0.94f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )

    val lobes = listOf(scheme.primary, scheme.tertiary, accents.info)
    Canvas(modifier = modifier) {
        val c = center
        val baseR = size.minDimension / 2f * pulse

        // 外层柔光晕
        drawCircle(
            brush = Brush.radialGradient(
                listOf(scheme.primary.copy(alpha = 0.22f), Color.Transparent),
                center = c, radius = baseR * 1.5f,
            ),
            radius = baseR * 1.5f, center = c,
        )
        // 三个旋转的彩色叶瓣
        for (i in 0..2) {
            val a = Math.toRadians((angle + i * 120f).toDouble())
            val off = baseR * 0.30f
            val lc = Offset(c.x + (cos(a) * off).toFloat(), c.y + (sin(a) * off).toFloat())
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(lobes[i].copy(alpha = 0.9f), lobes[i].copy(alpha = 0f)),
                    center = lc, radius = baseR * 0.95f,
                ),
                radius = baseR * 0.95f, center = lc,
            )
        }
        // 明亮内核
        drawCircle(
            brush = Brush.radialGradient(
                listOf(Color.White.copy(alpha = 0.85f), scheme.primary.copy(alpha = 0.18f), Color.Transparent),
                center = c, radius = baseR * 0.6f,
            ),
            radius = baseR * 0.62f, center = c,
        )
    }
}
