package com.arix.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowDown
import androidx.compose.material.icons.outlined.KeyboardDoubleArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

// ============================================================
// 对话内导航：位置胶囊 + 消息定位器
//
// 取代原来那条常驻侧边滚动条（用户嫌它是碍眼的「条子」）。核心取舍：
//  · 不做轨道+滑块：那是在承诺一个「拖着就能精确找到」的手势，而聊天里做不到——
//    消息高矮差极大（一句「好」vs 400 行代码块），按像素的滑块会卡住不动然后突然跳。
//  · 位置按**消息序号**算而非像素：用户真正想知道的是「第 47 条 / 共 300 条」。
//  · 平时完全不存在，只有手指真的在拖列表时才浮现，停手 1.2s 后消失；
//    **只认用户拖动，不认程序滚动**——否则 AI 流式输出时自动滚屏会一直把它召出来。
//  · 真正的导航交给点开的定位器弹窗（能搜、能看预览、能一眼看出长短、点了就跳），
//    这是滑块永远做不到的。
// ============================================================

/** 位置胶囊：贴右缘，拖动列表时才浮现，点击打开定位器。 */
@Composable
fun ChatPositionCapsule(
    listState: LazyListState,
    total: Int,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    if (total <= 5) return
    val scheme = MaterialTheme.colorScheme
    // 只认「用户拖动」这一个来源：程序滚动(流式输出自动跟随/跳转命中)不该召出它
    val dragged by listState.interactionSource.collectIsDraggedAsState()
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(dragged, listState.isScrollInProgress) {
        if (dragged) visible = true
        else if (!listState.isScrollInProgress) { delay(1200); visible = false }
    }

    // 进度 **O(1) 平滑**：首项序号 + 该项内已滚过比例(offset/项高)，除以总条数。**全从 layoutInfo 单快照取**
    // (别混 listState.firstVisibleItem*，跨源不同步会抖)。长消息滚得慢短消息快=每项内部按像素走。
    // ⚠ 别再用「Σ每项高度求真总长」——那是 O(条数)每帧，气泡多(尤其一堆小气泡)拖动就卡（用户点破，已废）。
    // 滚不动了直接给 1，消除尾部空占位 item 导致「到不了底」。
    val progress by remember {
        derivedStateOf {
            val li = listState.layoutInfo
            val vis = li.visibleItemsInfo
            val count = li.totalItemsCount
            if (vis.isEmpty() || count <= 0) 0f
            else if (!listState.canScrollForward) 1f   // 到底=1（保证能到底），跳变交给下面的动画抹平
            else {
                // 分母用「可滚动项数」= 总条数 − 一屏可见项数，O(1) 单快照，接近底部时自然逼近 1。
                val fv = vis.first()
                val scrolledInFirst = (li.viewportStartOffset - fv.offset).coerceAtLeast(0)
                val frac = (scrolledInFirst.toFloat() / fv.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                val denom = (count - vis.size).coerceAtLeast(1)
                ((fv.index + frac) / denom.toFloat()).coerceIn(0f, 1f)
            }
        }
    }
    // 显示值用动画平滑：raw 到底那下会跳到 1（=你说的「瞬移」），可见项数变化也会小跳；
    // 120ms 动画把任何跳变化成滑动 → 圆点永远滑过去、不瞬移。
    val shownProgress by animateFloatAsState(targetValue = progress, animationSpec = tween(120), label = "scrollProgress")

    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        // 入场比退场慢：出现是「给你个选项」，消失是收拾自己
        enter = fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 2 },
        exit = fadeOut(tween(120)) + slideOutHorizontally(tween(120)) { it / 2 },
    ) {
        Box(
            Modifier
                .size(width = 20.dp, height = 58.dp)
                // 左侧圆角大于右侧：读起来像贴着屏幕边缘掖进去的
                .clip(RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp, topEnd = 10.dp, bottomEnd = 10.dp))
                .background(scheme.surfaceVariant.copy(alpha = 0.58f))
                .border(1.dp, scheme.outlineVariant.copy(alpha = 0.12f), RoundedCornerShape(topStart = 14.dp, bottomStart = 14.dp, topEnd = 10.dp, bottomEnd = 10.dp))
                .clickable(onClick = onOpen),
            contentAlignment = Alignment.Center,
        ) {
            // 发丝细轨 + 跑动的小点。轨只有 34dp 而胶囊 58dp——它是个记号，不是等比映射的轨道，
            // 所以没人会误以为能拖。
            androidx.compose.foundation.Canvas(Modifier.size(width = 12.dp, height = 34.dp)) {
                val x = size.width / 2f
                drawLine(
                    color = scheme.outlineVariant.copy(alpha = 0.55f),
                    start = Offset(x, 0f), end = Offset(x, size.height),
                    strokeWidth = 1.5.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
                drawCircle(
                    color = scheme.primary.copy(alpha = 0.92f),
                    radius = 3.dp.toPx(),
                    center = Offset(x, shownProgress * size.height),
                )
            }
        }
    }
}

/**
 * 滚动跳转器：贴右缘的一列圆钮（跳顶 / 上一条 / 下一条 / 回底）。
 * 只在「最近滚过、手指已松开」时浮现，停手 1.5s 后消失——**全程零逐帧进度计算**（不读 layoutInfo 算比例，
 * 位置全靠点击时现取），比位置胶囊更省。总条数太少（≤5）时不显示。
 */
@Composable
fun ChatScrollJumper(
    listState: LazyListState,
    total: Int,
    modifier: Modifier = Modifier,
) {
    if (total <= 5) return
    val scope = rememberCoroutineScope()
    // 只认「滚动发生过」这个布尔 + 计时器，不算任何进度。停手 1.5s 收起。
    var recent by remember { mutableStateOf(false) }
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) recent = true
        else { delay(1500); recent = false }
    }
    AnimatedVisibility(
        visible = recent && !listState.isScrollInProgress,
        modifier = modifier,
        enter = fadeIn(tween(180)) + slideInHorizontally(tween(180)) { it / 2 },
        exit = fadeOut(tween(140)) + slideOutHorizontally(tween(140)) { it / 2 },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 位置全在点击时现取（firstVisibleItemIndex / totalItemsCount 都在 onClick 协程里读，非组合期），不产生逐帧订阅。
            JumpButton(Icons.Outlined.KeyboardDoubleArrowUp, tr("跳到顶部")) { scope.launch { listState.scrollToItem(0) } }
            JumpButton(Icons.Outlined.KeyboardArrowUp, tr("上一条")) { scope.launch { listState.animateScrollToItem((listState.firstVisibleItemIndex - 1).coerceAtLeast(0)) } }
            JumpButton(Icons.Outlined.KeyboardArrowDown, tr("下一条")) { scope.launch { listState.animateScrollToItem(listState.firstVisibleItemIndex + 1) } }
            JumpButton(Icons.Outlined.KeyboardDoubleArrowDown, tr("回到底部")) { scope.launch { val n = listState.layoutInfo.totalItemsCount; if (n > 0) listState.scrollToItem(n - 1) } }
        }
    }
}

@Composable
private fun JumpButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = scheme.surfaceVariant.copy(alpha = 0.62f),   // 半透明悬浮，跟旧胶囊一个基调
        contentColor = scheme.onSurfaceVariant,
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.14f)),
        modifier = Modifier.size(30.dp),
    ) {
        Icon(icon, contentDescription = desc, modifier = Modifier.padding(6.dp))
    }
}

/** 定位器里一条消息的摘要。 */
data class ChatLocatorEntry(val index: Int, val role: String, val text: String)

/**
 * 消息定位器：搜 + 看 + 跳。
 * 预览条宽度编码消息长度，用 sqrt 压缩——线性的话一个巨型代码块会把其余全压成看不见的细丝。
 */
@Composable
fun ChatLocatorDialog(
    entries: List<ChatLocatorEntry>,
    currentIndex: Int,
    onJump: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    val q = query.trim()
    val filtered = remember(q, entries) {
        if (q.isBlank()) entries else {
            // 记不清原话时按大意也能翻到；精确命中仍在前，保持「搜什么跳什么」的直觉
            val exact = entries.filter { it.text.contains(q, ignoreCase = true) }
            exact + com.arix.tool.FuzzyMatch.rank(q, entries - exact.toSet(), 30) { it.text }.map { it.item }
        }
    }
    val maxLen = remember(entries) { (entries.maxOfOrNull { it.text.length } ?: 1).coerceAtLeast(1) }
    val listState = rememberLazyListState()
    // 打开时让当前消息落在靠上但不贴顶的位置：上面留两条做上下文，比顶在边上好找
    LaunchedEffect(Unit) {
        val at = filtered.indexOfFirst { it.index == currentIndex }
        if (at >= 0) listState.scrollToItem((at - 2).coerceAtLeast(0))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(tr("跳到消息"), color = scheme.onSurface)
                if (entries.isNotEmpty())
                    Text("${tr("当前")} ${currentIndex + 1}/${entries.size}", color = scheme.onSurfaceVariant, fontSize = 11.sp)
            }
        },
        text = {
            Column {
                com.arix.app.ui.XtomField(
                    value = query, onValueChange = { query = it },
                    placeholder = tr("搜索本对话"), modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    if (q.isBlank()) tr("点一条跳过去") else "${filtered.size} ${tr("条结果")}",
                    color = scheme.onSurfaceVariant, fontSize = 11.sp,
                )
                Spacer(Modifier.height(4.dp))
                LazyColumn(state = listState, modifier = Modifier.heightIn(max = 320.dp)) {
                    itemsIndexed(filtered, key = { _, e -> e.index }) { _, e ->
                        val isCur = e.index == currentIndex
                        Row(
                            Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isCur) scheme.secondaryContainer else scheme.surfaceContainerLow)
                                .then(if (isCur) Modifier.border(1.dp, scheme.primary, RoundedCornerShape(12.dp)) else Modifier)
                                .clickable { onJump(e.index); onDismiss() }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.width(46.dp)) {
                                Text("${e.index + 1}", color = scheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Text(roleLabel(e.role), color = scheme.onSurfaceVariant, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            // 预览条：底色宽度=消息长度(sqrt 压缩, 最短也留 18% 让短消息看得见)，文字压在上面
                            val frac = (sqrt(e.text.length.toFloat() / maxLen)).coerceIn(0.18f, 1f)
                            Box(Modifier.weight(1f)) {
                                Box(
                                    Modifier.fillMaxWidth(frac).height(22.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(roleTint(e.role, scheme))
                                )
                                Text(
                                    previewAround(e.text, q),
                                    color = scheme.onSurface, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("关闭"), color = scheme.primary) } },
        containerColor = scheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

@Composable private fun roleLabel(role: String): String = when (role) {
    "user" -> tr("我")
    "assistant" -> tr("AI")
    "tool" -> tr("工具")
    else -> role
}

private fun roleTint(role: String, scheme: androidx.compose.material3.ColorScheme) = when (role) {
    "user" -> scheme.primary.copy(alpha = 0.22f)
    "assistant" -> scheme.tertiary.copy(alpha = 0.22f)
    else -> scheme.surfaceContainerHighest
}

/** 命中时把预览窗口挪到命中处附近，而不是永远只给开头——搜到的东西得能在预览里看见。 */
private fun previewAround(text: String, q: String): String {
    val flat = text.replace("\n", " ").trim()
    if (q.isBlank()) return flat.take(72)
    val at = flat.indexOf(q, ignoreCase = true)
    if (at < 0) return flat.take(72)
    val from = (at - 24).coerceAtLeast(0)
    val to = (from + 72).coerceAtMost(flat.length)
    return (if (from > 0) "…" else "") + flat.substring(from, to) + (if (to < flat.length) "…" else "")
}
