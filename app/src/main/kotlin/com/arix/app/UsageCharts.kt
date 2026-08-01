package com.arix.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// 使用统计图表：折线(趋势) / 横向柱状(按模型) / 环形饼图(占比)。
// 纯 Compose Canvas，无第三方图表库。主题取色，深浅自适应。
// ============================================================

/** 给不同模型/分片分配区分色：主色系里旋转色相，稳定可复现。 */
fun chartPalette(scheme: androidx.compose.material3.ColorScheme, n: Int): List<Color> {
    if (n <= 0) return emptyList()
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(scheme.primary.toArgb(), hsv)
    return List(n) { i ->
        val h = (hsv[0] + i * (360f / n.coerceAtLeast(3)) * 0.62f) % 360f
        Color(android.graphics.Color.HSVToColor(floatArrayOf(h, (hsv[1] * 0.7f + 0.3f).coerceIn(0.35f, 0.9f), (hsv[2] * 0.85f + 0.15f).coerceIn(0.55f, 0.95f))))
    }
}

/** 折线趋势图（带面积渐隐）。points 从旧到新。 */
@Composable
fun TrendLineChart(points: List<Float>, line: Color, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 88.dp) {
    Canvas(modifier = modifier.fillMaxWidth().height(height)) {
        if (points.size < 2) return@Canvas
        val maxV = (points.maxOrNull() ?: 1f).coerceAtLeast(1f)
        val w = size.width; val h = size.height
        val stepX = w / (points.size - 1)
        fun x(i: Int) = i * stepX
        fun y(v: Float) = h - (v / maxV) * (h * 0.86f) - h * 0.07f
        val linePath = Path(); val areaPath = Path()
        points.forEachIndexed { i, v ->
            val px = x(i); val py = y(v)
            if (i == 0) { linePath.moveTo(px, py); areaPath.moveTo(px, h); areaPath.lineTo(px, py) }
            else { linePath.lineTo(px, py); areaPath.lineTo(px, py) }
        }
        areaPath.lineTo(x(points.size - 1), h); areaPath.close()
        drawPath(areaPath, brush = androidx.compose.ui.graphics.Brush.verticalGradient(listOf(line.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(linePath, color = line, style = Stroke(width = 2.5.dp.toPx()))
        // 末点小圆点
        drawCircle(color = line, radius = 3.dp.toPx(), center = Offset(x(points.size - 1), y(points.last())))
    }
}

/** 横向柱状图：每行 标签 + 柱 + 数值。适合「按模型」这种标签长的场景，不挤。 */
@Composable
fun BarRow(label: String, value: Float, maxValue: Float, valueText: String, color: Color, scheme: androidx.compose.material3.ColorScheme) {
    Column(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = scheme.onSurface, fontSize = 11.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(valueText, color = scheme.onSurfaceVariant, fontSize = 10.sp)
        }
        Spacer(Modifier.height(3.dp))
        Box(Modifier.fillMaxWidth().height(7.dp).clip(RoundedCornerShape(4.dp)).background(scheme.surfaceContainerHighest)) {
            val frac = if (maxValue > 0f) (value / maxValue).coerceIn(0f, 1f) else 0f
            Box(Modifier.fillMaxWidth(frac).height(7.dp).clip(RoundedCornerShape(4.dp)).background(color))
        }
    }
}

/** 环形饼图（donut）+ 图例。slices: (标签, 值, 颜色)。中心显示总量文案。 */
@Composable
fun DonutChart(slices: List<Triple<String, Float, Color>>, centerLabel: String, centerValue: String, scheme: androidx.compose.material3.ColorScheme, modifier: Modifier = Modifier) {
    val total = slices.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(1e-3f)
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(108.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(108.dp)) {
                var start = -90f
                val stroke = 16.dp.toPx()
                val inset = stroke / 2f
                slices.forEach { (_, v, c) ->
                    val sweep = v / total * 360f
                    drawArc(color = c, startAngle = start, sweepAngle = sweep - 1.5f, useCenter = false,
                        topLeft = Offset(inset, inset), size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke))
                    start += sweep
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerValue, color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text(centerLabel, color = scheme.onSurfaceVariant, fontSize = 9.sp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            slices.take(6).forEach { (label, v, c) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(c))
                    Spacer(Modifier.width(6.dp))
                    Text(label, color = scheme.onSurface, fontSize = 10.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${(v / total * 100).toInt()}%", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                }
            }
        }
    }
}
