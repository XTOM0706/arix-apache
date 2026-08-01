package com.arix.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arix.app.ui.XtomCard
import com.arix.tool.ToolActivityBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.arix.app.ui.topChromeGapHeight

// ============================================================
// AI 活动中心 —— 「AI 到底干了什么」的**唯一去处**。
//
// 之前这件事散在三处：聊天页的行为流弹窗（工具调用时间轴）、设置里的「监控 & 风控」、
// 以及「文件改动历史」。用户想核对 AI 的行为时，得先猜该去哪一处翻——
// 这本身就是另一种黑箱。这里把前两者并成一页，并给第三者一个显式入口。
// ============================================================

/**
 * 为什么用**标签页**而不是上下分区：
 * 行为流是个可能有 200 条的长列表 + 自带搜索框和筛选芯片，监控页是一堆统计卡。
 * 在手表这种 ~320dp 高的屏上把两者串成一个纵向滚动，等于「监控永远在两百条记录的下面」，
 * 用户根本滚不到；而且 LazyColumn 套进外层滚动容器还得改成定高，长列表的虚拟化就废了。
 * 两个标签横向一次切换，各自独占整屏高度，是窄屏唯一说得通的分法。
 *
 * @param onOpenRoute 跳到别的设置页（目前只用于「文件改动历史」）。为 null 时不显示该入口，
 *                    这样旧路由复用本页时不会渲染出一个点了没反应的按钮。
 */
@Composable
fun ActivityCenterPage(
    scope: kotlinx.coroutines.CoroutineScope,
    onOpenRoute: ((String) -> Unit)? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    var tab by remember { mutableStateOf(0) }

    // 顶部让位不放在滚动内容里：本页顶部是固定的标签切换条（不跟着滚），
    // 按 topChromeGap 的约定，这种页退化成普通顶部留白即可。
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
        Spacer(Modifier.topChromeGapHeight())

        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            SingleChoiceSegmentedButtonRow(Modifier.weight(1f)) {
                SegmentedButton(
                    selected = tab == 0, onClick = { tab = 0 },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                    icon = {},
                ) { Text(tr("行为流"), style = type.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                SegmentedButton(
                    selected = tab == 1, onClick = { tab = 1 },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    icon = {},
                ) { Text(tr("监控 & 风控"), style = type.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            RunningPulse()
        }

        when (tab) {
            0 -> Column(Modifier.fillMaxSize()) {
                // 顺手放的两个动作：清空这份内存记录、以及去看「文件改动了什么」。
                // 文件改动历史是另一套（落盘、可回滚）的数据，不并进来，但必须给得到——
                // 用户看完「AI 调了 file_write」的下一个问题永远是「那它到底改成了什么」。
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (onOpenRoute != null) {
                        TextButton(onClick = { onOpenRoute("file_history") }) {
                            Icon(Icons.Outlined.History, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(tr("文件改动历史"), style = type.labelMedium, color = scheme.primary, maxLines = 1)
                            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { ToolActivityBus.clear() }) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = tr("清空记录"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    }
                }
                // 时间轴本体与聊天页弹窗是同一份实现（搜索/筛选/展开详情/空态全在里面）
                ToolActivityTimeline(Modifier.fillMaxSize())
            }
            else -> MonitorSection(scope)
        }
    }
}

/**
 * 有工具正在跑时，标签条右侧转一个小闪电。
 *
 * 用 withFrameNanos 自己推角度，不用 infiniteTransition/tween：手表上「系统动画缩放=0」很常见
 * （省电模式/无障碍设置都会置零），那种情况下 Compose 的动画会直接跳到终值 = 完全看不见转动。
 * 凡是「必须一直动」的指示器本项目一律走帧回调（见 ToolActivityTicker 的同款注释）。
 */
@Composable
private fun RunningPulse() {
    val running by ToolActivityBus.running.collectAsState()
    val e = running ?: return
    var angle by remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) angle = (angle + (now - last) / 1_000_000f * 0.22f) % 360f
                last = now
            }
        }
    }
    Icon(
        Icons.Outlined.Bolt, contentDescription = tr("正在执行 %s").format(e.toolName),
        tint = MaterialTheme.colorScheme.tertiary,
        modifier = Modifier.padding(start = 6.dp).size(18.dp).graphicsLayer { rotationZ = angle },
    )
}

// ============================================================
// 监控 & 风控分区（原 MonitorPage 的内容，搬进来做成本页的一个标签）
// ============================================================

@Composable
private fun MonitorSection(scope: kotlinx.coroutines.CoroutineScope) {
    val scheme = MaterialTheme.colorScheme
    val type = MaterialTheme.typography
    var summary by remember { mutableStateOf<com.arix.cloudapi.ApiMonitor.ApiMonitorSummary?>(null) }
    var recentCalls by remember { mutableStateOf<List<com.arix.cloudapi.ApiMonitor.CallRecord>>(emptyList()) }
    var guardLevel by remember { mutableStateOf(com.arix.tool.AIGuardTool.getRiskLevel()) }
    // 清空后要重新取一遍数字，用它触发
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        // 聚合走内存调用日志、非 suspend，赶下后台线程再回填（调用日志变长时不冻 UI）
        val (sum, recent) = withContext(Dispatchers.Default) {
            com.arix.cloudapi.ApiMonitor.getSummary() to com.arix.cloudapi.ApiMonitor.getRecent(20)
        }
        summary = sum
        recentCalls = recent
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val s = summary
        Text(tr("API 调用"), color = scheme.primary, style = type.titleSmall, fontWeight = FontWeight.Bold)
        Text(tr("模型请求走了多少次、烧了多少 token、慢不慢。"), color = scheme.onSurfaceVariant, style = type.labelSmall)
        Spacer(Modifier.height(6.dp))
        XtomCard {
            if (s == null || s.totalCalls == 0) {
                // 与行为流同理：这份统计也是纯内存的，重启归零，得说明白
                Text(tr("本次运行还没有 API 调用"), color = scheme.onSurface, style = type.bodyMedium)
                Text(
                    tr("统计只算当前这次运行，重启后从零开始。"),
                    color = scheme.onSurfaceVariant, style = type.bodySmall, modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                StatLine(tr("总调用"), "${s.totalCalls}")
                StatLine(tr("成功 / 失败"), "${s.successfulCalls} / ${s.totalCalls - s.successfulCalls}")
                StatLine(tr("总 Token"), "${s.totalTokens}")
                StatLine(tr("平均延迟"), "${s.avgLatencyMs}ms")
                if (s.byProvider.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(tr("各厂商"), color = scheme.primary, style = type.labelMedium, fontWeight = FontWeight.Bold)
                    s.byProvider.forEach { (provider, st) ->
                        StatLine(provider, "${st.calls}${tr("次")} · ${st.tokens}t · ${st.avgLatencyMs}ms")
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Shield, contentDescription = null, tint = scheme.secondary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(tr("AI 风控"), color = scheme.secondary, style = type.titleSmall, fontWeight = FontWeight.Bold)
        }
        Text(tr("调严→敏感操作更容易被挡下（更安全）；调松→更少打扰但风险高。"), color = scheme.onSurfaceVariant, style = type.labelSmall)
        Spacer(Modifier.height(6.dp))
        XtomCard {
            val levels = listOf("strict" to tr("严格"), "normal" to tr("普通"), "permissive" to tr("宽松"))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                levels.forEachIndexed { i, (level, label) ->
                    SegmentedButton(
                        selected = guardLevel == level,
                        onClick = {
                            guardLevel = level
                            scope.launch {
                                com.arix.tool.AIGuardTool().execute(
                                    org.json.JSONObject().apply { put("action", "set_level"); put("level", level) }
                                )
                            }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = levels.size),
                        icon = {},
                    ) { Text(label, style = type.labelMedium, maxLines = 1) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when (guardLevel) {
                    "strict" -> tr("严格：所有工具执行前都要你点确认。")
                    "normal" -> tr("普通：高风险工具（shell / 触控 / 亮度 / 音量等）才要确认。")
                    else -> tr("宽松：只拦最危险的（shell）。")
                },
                color = scheme.onSurfaceVariant, style = type.bodySmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr("被拦下的调用会以「被拒」出现在「行为流」标签里，不会悄悄消失。"),
                color = scheme.onSurfaceVariant, style = type.labelSmall,
            )
        }

        if (recentCalls.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("${tr("最近调用")} (${recentCalls.size})", color = scheme.primary, style = type.titleSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            // 倒序：和行为流一致，最新的在最上面。记忆化：只随 recentCalls 变，别每次重组重算切片
            val recent = remember(recentCalls) { recentCalls.asReversed().take(10) }
            recent.forEach { call ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 1.dp)
                        .background(if (call.success) scheme.surfaceContainer else scheme.errorContainer, RoundedCornerShape(8.dp))
                        .padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("${call.provider} · ${call.model}", color = scheme.onSurface, style = type.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${call.totalTokens} tokens · ${call.latencyMs}ms" + (call.errorMsg?.take(40)?.let { " · $it" } ?: ""),
                            color = scheme.onSurfaceVariant, style = type.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = { com.arix.cloudapi.ApiMonitor.clear(); reload++ }) {
                Text(tr("清空记录"), color = scheme.error, style = type.labelLarge)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 「名称 —— 值」一行。比原来那坨等宽字体拼出来的多行文本好读，也不用手算对齐。 */
@Composable
private fun StatLine(name: String, value: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1)
        Spacer(Modifier.weight(1f))
        Text(value, color = scheme.onSurface, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
