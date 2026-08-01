package com.arix.app

import androidx.compose.foundation.background
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.arix.app.ui.topChromeGapHeight

// ============================================================
// 使用统计 / 热力图 —— 对话/消息/Token 总量 + 近 17 周活跃热力图（按对话创建日分桶）。
// 只读本地数据，纯统计，不动任何核心流程。
// ============================================================
@Composable fun UsageStatsPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    var loading by remember { mutableStateOf(true) }
    var convCount by remember { mutableStateOf(0) }
    var msgCount by remember { mutableStateOf(0) }
    var tokenSum by remember { mutableStateOf(0L) }
    var activeDays by remember { mutableStateOf(0) }
    var heat by remember { mutableStateOf<Map<Long, Int>>(emptyMap()) }

    val tzOffset = remember { java.util.TimeZone.getDefault().rawOffset.toLong() }
    fun epochDay(ms: Long) = (ms + tzOffset) / 86_400_000L

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val db = AppDatabase.getInstance(context)
                val convs = db.conversationDao().getActiveSummaries().first() + db.conversationDao().getArchivedSummaries().first()
                val cm = ConversationManager(context)
                var msgs = 0; var toks = 0L
                val byDay = HashMap<Long, Int>()
                convs.forEach { c ->
                    val list = runCatching { cm.loadMessages(c.id) }.getOrDefault(emptyList())
                    msgs += list.size
                    toks += list.sumOf { (it.totalTokens ?: 0).toLong() }
                    val d = epochDay(c.createdAt)
                    byDay[d] = (byDay[d] ?: 0) + 1
                }
                convCount = convs.size; msgCount = msgs; tokenSum = toks
                activeDays = byDay.size; heat = byDay
            } catch (_: Exception) {}
            loading = false
        }
    }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.topChromeGapHeight())   // 顶部悬浮玻璃让位：在滚动内容里，内容能从玻璃下滑过去化开
        Text(tr("使用统计"), color = scheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(tr("本地统计，仅你自己可见。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 14.dp))

        if (loading) {
            Row(Modifier.fillMaxWidth().padding(top = 40.dp), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() }
            return@Column
        }

        // 总量卡
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("对话", convCount.toString(), Modifier.weight(1f), scheme)
            StatCard("消息", msgCount.toString(), Modifier.weight(1f), scheme)
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatCard("Token 总量", fmt(tokenSum), Modifier.weight(1f), scheme)
            StatCard("活跃天数", activeDays.toString(), Modifier.weight(1f), scheme)
        }

        Spacer(Modifier.height(22.dp))
        Text(tr("活跃热力图（近 17 周 · 按对话创建）"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
        val today = epochDay(System.currentTimeMillis())
        val maxCount = (heat.values.maxOrNull() ?: 1).coerceAtLeast(1)
        // 17 列 × 7 行，最右列到今天
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            for (week in 0 until 17) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (dow in 0 until 7) {
                        val day = today - (16 - week) * 7 - (6 - dow)
                        val cnt = heat[day] ?: 0
                        val alpha = if (cnt == 0) 0.06f else 0.25f + 0.75f * (cnt.toFloat() / maxCount)
                        Box(Modifier.size(14.dp).clip(RoundedCornerShape(3.dp)).background(scheme.primary.copy(alpha = alpha)))
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(tr("少"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
            listOf(0.06f, 0.35f, 0.6f, 0.85f, 1f).forEach { a -> Box(Modifier.size(12.dp).clip(RoundedCornerShape(3.dp)).background(scheme.primary.copy(alpha = a))) }
            Text(tr("多"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
        }

        // 折线趋势：近 30 天每日对话数（从热力图同源数据取时间序列）
        Spacer(Modifier.height(22.dp))
        Text(tr("近 30 天对话趋势"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 6.dp))
        val trend = remember(heat) { (0 until 30).map { (heat[today - 29 + it] ?: 0).toFloat() } }
        TrendLineChart(points = trend, line = scheme.primary)

        // 按模型 · 用量与花费（持久化累计；花费按 ModelPricing 估，未知价的显 —）
        val modelUsage = remember { com.arix.cloudapi.ApiMonitor.modelUsage() }
        if (modelUsage.isNotEmpty()) {
            val palette = remember(modelUsage) { chartPalette(scheme, modelUsage.size) }
            val totalCost = modelUsage.sumOf { u -> com.arix.app.ModelPricing.priceOf(u.model)?.let { (i, o) -> it2Cost(u.inTokens, u.outTokens, i, o) } ?: 0.0 }
            val totalTokens = modelUsage.sumOf { it.inTokens + it.outTokens }

            // 环形饼图：各模型 token 占比 + 中心总量
            Spacer(Modifier.height(24.dp))
            Text(tr("Token 占比 · 按模型"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 8.dp))
            val slices = remember(modelUsage) { modelUsage.mapIndexed { i, u -> Triple(u.model, (u.inTokens + u.outTokens).toFloat(), palette[i]) } }
            DonutChart(slices = slices, centerLabel = tr("总 Token"), centerValue = fmt(totalTokens), scheme = scheme)

            // 横向柱状：各模型 token 用量（柱）+ 调用次数/花费（数值）
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(tr("按模型 · 用量与花费"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Text(tr("累计约 ") + com.arix.app.ModelPricing.fmt(totalCost), color = scheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            val maxTok = modelUsage.maxOf { (it.inTokens + it.outTokens).toFloat() }.coerceAtLeast(1f)
            modelUsage.forEachIndexed { i, u ->
                val price = com.arix.app.ModelPricing.priceOf(u.model)
                val cost = price?.let { (a, b) -> it2Cost(u.inTokens, u.outTokens, a, b) }
                val costText = if (cost != null) com.arix.app.ModelPricing.fmt(cost) else tr("价格未知")
                BarRow(
                    label = u.model,
                    value = (u.inTokens + u.outTokens).toFloat(),
                    maxValue = maxTok,
                    valueText = tr("%d 次 · %s token · %s").format(u.calls, fmt(u.inTokens + u.outTokens), costText),
                    color = palette[i],
                    scheme = scheme,
                )
            }
            Text(tr("花费为按公开价格的估算，仅供参考；免费/未知价模型不计入。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(24.dp))
    }
}

// tokens×单价(每百万) → USD
private fun it2Cost(inTok: Long, outTok: Long, inPrice: Double, outPrice: Double): Double =
    inTok / 1_000_000.0 * inPrice + outTok / 1_000_000.0 * outPrice

@Composable private fun StatCard(label: String, value: String, modifier: Modifier, scheme: androidx.compose.material3.ColorScheme) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(scheme.surfaceContainerHighest).padding(vertical = 16.dp, horizontal = 14.dp)) {
        Text(value, color = scheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(label, color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

private fun fmt(n: Long): String = when {
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}
