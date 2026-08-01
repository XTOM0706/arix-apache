package com.arix.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.arix.tool.ToolActivityBus

// ============================================================
// AI 行为流 —— 「用户能看见 AI 在干什么」的两个界面
//   · [ToolActivityPanel] 可回看的时间轴（聊天页顶栏图标进入）
//   · [ToolActivityTicker] 执行中的实时动作条（浮在输入框上方）
// 数据全部来自 ToolActivityBus（埋点在 ToolManager.execute 一处）。
// ============================================================

/** 耗时的人话。手表上横向像素金贵，别打印 "1234ms" 这种要用户自己换算的数。 */
private fun humanDuration(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

private fun clockOf(t: Long): String =
    java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(t))

/** 结局 → 图标 + 颜色。用 Material 矢量图标（项目铁律：不拿 emoji/字符当图标）。 */
@Composable
private fun statusIcon(s: ToolActivityBus.Status): Pair<androidx.compose.ui.graphics.vector.ImageVector, Color> {
    val scheme = MaterialTheme.colorScheme
    return when (s) {
        ToolActivityBus.Status.OK -> Icons.Outlined.CheckCircle to scheme.primary
        ToolActivityBus.Status.ERROR -> Icons.Outlined.ErrorOutline to scheme.error
        ToolActivityBus.Status.DENIED -> Icons.Outlined.Block to scheme.error
        ToolActivityBus.Status.CANCELLED -> Icons.Outlined.Close to scheme.onSurfaceVariant
        ToolActivityBus.Status.RUNNING -> Icons.Outlined.Bolt to scheme.tertiary
    }
}

private fun statusLabel(s: ToolActivityBus.Status): String = when (s) {
    ToolActivityBus.Status.OK -> tr("成功")
    ToolActivityBus.Status.ERROR -> tr("失败")
    ToolActivityBus.Status.DENIED -> tr("被拒")
    ToolActivityBus.Status.CANCELLED -> tr("已取消")
    ToolActivityBus.Status.RUNNING -> tr("进行中")
}

/**
 * 时间轴主体（搜索 + 结局筛选 + 列表 + 空态）—— 聊天页的全屏 Dialog [ToolActivityPanel] 与
 * 「AI 活动中心」页 都用这一份。
 *
 * 为什么抽出来而不是各写一份：这两处看的是**同一份数据、同一套筛选语义**，复制两份的必然结局是
 * 一边加了筛选条件另一边忘了跟，用户在两个入口看到不一样的东西反而更像黑箱。
 * 筛选词/芯片状态**留在本组件内**：它是「看的时候临时收窄一下」，不该被调用方存起来跨场景带走。
 */
@Composable
fun ToolActivityTimeline(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    // 只在真有人看的时候才订阅/取快照——没人看时总线那边只是自增一个计数器，零成本。
    val version by ToolActivityBus.version.collectAsState()
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf<ToolActivityBus.Status?>(null) }
    // version 变了才重新取一份快照（工具调用是低频事件，这里不会频繁跑）
    val all = remember(version) { ToolActivityBus.snapshot() }
    val shown = remember(all, query, filter) {
        all.filter { e ->
            (filter == null || e.status == filter) &&
                (query.isBlank() || e.toolName.contains(query, true) || e.callerLabel.contains(query, true))
        }
    }

    Column(modifier) {
        // 搜索：按工具名/请求方过滤。用 BasicTextField 上一层胶囊，跟本项目其它搜索框一致。
        Surface(shape = RoundedCornerShape(50), color = scheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Build, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                androidx.compose.foundation.text.BasicTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(scheme.primary),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text(tr("按工具名/请求方筛选"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            inner()
                        }
                    },
                )
            }
        }
        // 结局筛选芯片。横向可滚——窄屏放不下 5 个芯片，硬挤会换行把列表压没。
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val opts: List<Pair<String, ToolActivityBus.Status?>> = listOf(
                tr("全部") to null,
                statusLabel(ToolActivityBus.Status.RUNNING) to ToolActivityBus.Status.RUNNING,
                statusLabel(ToolActivityBus.Status.OK) to ToolActivityBus.Status.OK,
                statusLabel(ToolActivityBus.Status.ERROR) to ToolActivityBus.Status.ERROR,
                statusLabel(ToolActivityBus.Status.DENIED) to ToolActivityBus.Status.DENIED,
                statusLabel(ToolActivityBus.Status.CANCELLED) to ToolActivityBus.Status.CANCELLED,
            )
            opts.forEach { (label, st) ->
                FilterChip(
                    selected = filter == st,
                    onClick = { filter = st },
                    label = { Text(label, style = MaterialTheme.typography.labelMedium) },
                    modifier = Modifier.padding(end = 6.dp),
                )
            }
        }
        if (shown.isEmpty()) {
            TimelineEmptyState(nothingRecordedAtAll = all.isEmpty(), modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(shown, key = { it.seq }, contentType = { it.callerKind }) { e -> ActivityRow(e) }
            }
        }
    }
}

/**
 * 空态。**必须说清「不落盘」**：这份记录是纯内存环形缓存，重启 App 就空了；
 * 不解释的话用户回头来看见一片空白，第一反应是「记录功能坏了」而不是「本来就不留」。
 */
@Composable
private fun TimelineEmptyState(nothingRecordedAtAll: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Icon(Icons.Outlined.Timeline, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(36.dp))
            Spacer(Modifier.width(8.dp))
            if (nothingRecordedAtAll) {
                Text(tr("这一趟还没有工具调用"), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                Text(
                    tr("AI 一调用工具，这里就会实时出现：工具名、谁在调、参数、返回、耗时。\n记录只存在内存里、不写盘——App 重启后会清空，看到空白不是坏了。"),
                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            } else {
                Text(tr("没有匹配的记录"), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                Text(
                    tr("换个关键词，或把结局筛选切回「全部」。"),
                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * AI 行为流面板：一条可回看的时间轴，每次工具调用一条（工具名/请求方/参数/返回/耗时/结局）。
 *
 * 走全屏 Dialog 而不是 ModalBottomSheet：手表屏幕矮，半屏 sheet 只能露两三条，
 * 这个面板的价值恰恰在「一次看完刚才那一串」。
 *
 * 与「AI 活动中心」页并存而不是被它取代：聊天中点动作条要的是**就地看一眼再回来**，
 * 被迫跳页会把当前对话挤出视线。两者共用 [ToolActivityTimeline]，不存在两份实现。
 */
@Composable
fun ToolActivityPanel(onClose: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
                // 标题行
                Row(
                    Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Outlined.Timeline, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(tr("AI 行为流"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = scheme.onSurface)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { ToolActivityBus.clear() }) {
                        Icon(Icons.Outlined.DeleteSweep, contentDescription = tr("清空记录"), tint = scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Outlined.Close, contentDescription = tr("关闭"), tint = scheme.onSurfaceVariant)
                    }
                }
                ToolActivityTimeline(Modifier.fillMaxSize())
            }
        }
    }
}

/** 时间轴的一条。默认收起（只一行摘要），点开才渲染完整参数/返回——300 条全展开在手表上必卡。 */
@Composable
private fun ActivityRow(e: ToolActivityBus.Entry) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var expanded by remember(e.seq) { mutableStateOf(false) }
    val (icon, color) = statusIcon(e.status)

    Surface(
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = statusLabel(e.status), tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    e.toolName, color = scheme.onSurface, style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.weight(1f))
                // 耗时：进行中的没有终值，别显示 0ms 骗人
                if (!e.isRunning) {
                    Text(humanDuration(e.durationMs), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                    Spacer(Modifier.width(6.dp))
                }
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) tr("收起") else tr("展开"),
                    tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp),
                )
            }
            // 第二行：谁在调 + 什么时候 + 参数一行摘要
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${e.callerLabel} · ${clockOf(e.startedAt)}", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            if (!expanded && e.argsPreview.isNotBlank()) {
                Text(
                    e.argsPreview, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
                exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(120)),
            ) {
                Column(Modifier.padding(top = 6.dp)) {
                    DetailBlock(tr("参数"), e.argsJson.ifBlank { e.argsPreview }, clipboard)
                    val res = e.result + if (e.resultTruncated) "\n…（已截断）" else ""
                    DetailBlock(tr("返回"), res.ifBlank { tr("(无输出)") }, clipboard)
                    if (e.conversationId != null) {
                        Text(tr("所属对话 #%s").format(e.conversationId.toString()), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
        }
    }
}

/** 参数/返回的详情块：定高上限内滚动（长输出别把整条撑成一屏），右上角一键复制。 */
@Composable
private fun DetailBlock(title: String, body: String, clipboard: androidx.compose.ui.platform.ClipboardManager) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = scheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Outlined.ContentCopy, contentDescription = tr("复制"), tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp).clickable { clipboard.setText(AnnotatedString(body)) },
            )
        }
        Box(
            Modifier.fillMaxWidth().padding(top = 2.dp)
                .clip(MaterialTheme.shapes.small).background(scheme.surfaceContainerHighest)
                // 上限而非定高：短内容不撑空白，长内容才滚
                .heightIn(max = 200.dp).verticalScroll(rememberScrollState()).padding(8.dp),
        ) {
            SelectionContainer {
                Text(body, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

// ============================================================
// 实时动作条
// ============================================================

/**
 * 工具执行时浮在输入框上方的一条「当前在干什么」，跑完即消失。
 *
 * 数据来自 [ToolActivityBus.running]（谁在跑、参数摘要）+ ToolStreamBus（长跑工具的输出末尾），
 * 两者都是现成的流，这里只做展示，不新造状态。连续多轮工具调用时 running 会依次换值，
 * 文案跟着切——这正是「AI 一口气调了五个工具」时用户最缺的那点反馈。
 *
 * 点它展开 [ToolActivityPanel]：这条只显示「此刻这一个」，而用户看到它时想问的往往是
 * 「刚才那一串都干了什么」——就近给出口，省得去翻菜单。
 */
@Composable
fun ToolActivityTicker(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val running by ToolActivityBus.running.collectAsState()
    val stream by com.arix.tool.ToolStreamBus.state.collectAsState()

    AnimatedVisibility(
        visible = running != null,
        modifier = modifier,
        enter = expandVertically(tween(200, easing = FastOutSlowInEasing)) + fadeIn(tween(200)),
        exit = shrinkVertically(tween(180, easing = FastOutSlowInEasing)) + fadeOut(tween(140)),
    ) {
        val e = running ?: return@AnimatedVisibility
        // 转圈用 withFrameNanos 自己推进度，不用 infiniteTransition/animate*AsState：
        // 系统「动画缩放=0」（A11 设置或省电模式常开）下 Compose 的 tween 会直接跳到终值 = 看不见动画。
        // 这个坑本项目踩过（见唤醒流光），凡是「必须一直转」的指示器都走帧回调。
        var angle by remember { mutableStateOf(0f) }
        LaunchedEffect(Unit) {
            var last = 0L
            while (true) {
                androidx.compose.runtime.withFrameNanos { now ->
                    if (last != 0L) angle = (angle + (now - last) / 1_000_000f * 0.22f) % 360f
                    last = now
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(50),
            color = scheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        ) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Bolt, contentDescription = null, tint = scheme.tertiary,
                    modifier = Modifier.size(15.dp).graphicsLayer { rotationZ = angle },
                )
                Spacer(Modifier.width(7.dp))
                // 「正在执行 shell: npm install…」。参数摘要接在工具名后面，没有就只显示工具名。
                val detail = if (stream.name == e.toolName && stream.output.isNotBlank())
                    stream.output.takeLast(60).replace('\n', ' ')   // 长跑工具有实时输出时，显示更有信息量的输出末尾
                else e.argsPreview
                Text(
                    if (detail.isBlank()) tr("正在执行 %s").format(e.toolName)
                    else tr("正在执行 %s: %s").format(e.toolName, detail),
                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
