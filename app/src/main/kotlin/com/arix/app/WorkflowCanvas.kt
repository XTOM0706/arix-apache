package com.arix.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
// 工作流可视化画布：把 steps 画成「节点卡 + 顺序箭头」的竖向流程图。
// - 主流程：相邻步骤间实线箭头（严格顺序执行，见 WorkflowEngine.runInner）
// - 数据流：args 里的 {{stepN}}/{{last}} 引用另用细虚线标注（{{input}} 无来源节点，略）
// - 手势：单指拖空白=平移画布，单指拖节点=移动该节点，双指=缩放，点节点=编辑弹窗
// - 节点坐标存进该 workflow JSON 的 `_layout`（归一化 0..1），WorkflowStore.save 保留额外键
// 节点卡尺寸固定、只缩放位置（对齐 MemoryGraph 的做法：方屏手表上文字始终清晰可读）。
// ============================================================

// 单个步骤的可编辑模型（tool / args 文本 / onError）。argsText 始终保持为合法 JSON 对象串。
private class StepM(var tool: String, var argsText: String, var onError: String)

private fun parseSteps(wf: JSONObject): List<StepM> {
    val arr = wf.optJSONArray("steps") ?: return emptyList()
    val out = ArrayList<StepM>()
    for (i in 0 until arr.length()) {
        val s = arr.optJSONObject(i) ?: continue
        val argsObj = s.optJSONObject("args") ?: JSONObject()
        val argsPretty = runCatching { argsObj.toString(2) }.getOrNull() ?: argsObj.toString()
        out.add(StepM(s.optString("tool"), argsPretty, s.optString("onError", "stop").ifBlank { "stop" }))
    }
    return out
}

// 从 _layout 读初始坐标；缺失则默认竖向串链。坐标为画布归一化 [0,1]。
private fun loadCoords(wf: JSONObject, n: Int): List<Offset> {
    val layout = wf.optJSONObject("_layout")
    return (0 until n).map { i ->
        val o = layout?.optJSONObject(i.toString())
        // x/y 各自缺失都回退到默认竖向串链坐标——别让缺 key 产出 NaN（NaN 坐标→节点画到屏外、点不中、永久不可见/不可编辑）
        if (o != null) Offset(o.optDouble("x", 0.5).toFloat(), o.optDouble("y", (0.14 + i * 0.16)).toFloat())
        else Offset(0.5f, 0.14f + i * 0.16f)   // 默认：居中竖向串链
    }
}

// args 文本里的 {{var}} 引用 → 来源步骤下标（0 起）。-1 = 无来源（如 {{input}}）。
private fun refSources(argsText: String, selfIndex: Int): List<Int> {
    val out = LinkedHashSet<Int>()
    Regex("""\{\{(\w+)\}\}""").findAll(argsText).forEach { m ->
        val v = m.groupValues[1]
        when {
            v == "last" -> if (selfIndex - 1 >= 0) out.add(selfIndex - 1)
            v.startsWith("step") -> v.removePrefix("step").toIntOrNull()?.let { if (it - 1 in 0 until selfIndex) out.add(it - 1) }
            // input 无来源节点，略
        }
    }
    return out.toList()
}

@Composable
fun WorkflowCanvas(
    wf: JSONObject,
    onSave: (JSONObject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // 本地可编辑状态：只在切换到新的 wf 对象时初始化（拖动/编辑不会触发外层重载 → 视图不跳、平移不丢）
    val steps = remember(wf) { mutableStateListOf<StepM>().apply { addAll(parseSteps(wf)) } }
    val coords = remember(wf) { mutableStateListOf<Offset>().apply { addAll(loadCoords(wf, steps.size)) } }
    var editing by remember(wf) { mutableStateOf<Int?>(null) }

    // 把当前步骤 + 坐标写回一份 wf 副本（保留 desc 等额外键），交给外部持久化。
    fun persist() {
        val copy = try { JSONObject(wf.toString()) } catch (_: Exception) { JSONObject().put("name", wf.optString("name")) }
        val arr = JSONArray()
        steps.forEach { s ->
            val a = try { JSONObject(s.argsText) } catch (_: Exception) { JSONObject() }
            arr.put(JSONObject().put("tool", s.tool).put("args", a).put("onError", s.onError))
        }
        copy.put("steps", arr)
        val layout = JSONObject()
        coords.forEachIndexed { i, c -> layout.put(i.toString(), JSONObject().put("x", c.x).put("y", c.y)) }
        copy.put("_layout", layout)
        onSave(copy)
    }

    if (steps.isEmpty()) {
        Box(modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) {
            Text(tr("这个工作流没有步骤。让 AI 用 workflow 工具往里加几步。"), color = scheme.onSurfaceVariant, fontSize = 12.sp)
        }
        return
    }

    // 卡片文字测量（固定尺寸、随内容变化重测）：第一行=序号+tool，第二行=简参
    val measurer = rememberTextMeasurer()
    val titleStyle = TextStyle(fontSize = 12.sp, color = scheme.onSurface, fontWeight = FontWeight.Bold)
    val subStyle = TextStyle(fontSize = 10.sp, color = scheme.onSurfaceVariant)
    val cards = remember(steps.map { it.tool + "|" + it.argsText }, scheme.onSurface) {
        steps.mapIndexed { i, s ->
            val brief = s.argsText.replace(Regex("\\s+"), " ").trim().removePrefix("{").removeSuffix("}").trim().take(22).ifBlank { "(无参数)" }
            val t = measurer.measure(AnnotatedString("${i + 1}. ${s.tool.ifBlank { "(缺 tool)" }}"), titleStyle)
            val sub = measurer.measure(AnnotatedString(brief), subStyle)
            val w = maxOf(t.size.width, sub.size.width).toFloat() + 24f
            val h = t.size.height + sub.size.height + 18f
            Triple(t, sub, Size(maxOf(w, 110f), maxOf(h, 46f)))
        }
    }
    // {{stepN}}/{{last}} 数据流引用预解析——只随 argsText 变化算一次，别在每帧 DrawScope 里跑正则（拖动/缩放会重画）
    val refs = remember(steps.map { it.argsText }) { steps.mapIndexed { i, s -> refSources(s.argsText, i) } }
    val dash = remember { PathEffect.dashPathEffect(floatArrayOf(9f, 9f), 0f) }

    var scale by remember(wf) { mutableStateOf(1f) }
    var pan by remember(wf) { mutableStateOf(Offset.Zero) }

    // 屏幕坐标换算（gesture 与绘制共用同一套公式）
    fun cx(w: Float, h: Float, i: Int): Float { val base = minOf(w, h); return w / 2f + (coords[i].x - 0.5f) * base * scale + pan.x }
    fun cy(w: Float, h: Float, i: Int): Float { val base = minOf(w, h); return h / 2f + (coords[i].y - 0.5f) * base * scale + pan.y }

    Canvas(
        modifier = modifier.fillMaxWidth().height(360.dp).clipToBounds()
            .background(scheme.surface, RoundedCornerShape(18.dp))
            .pointerInput(steps.size) {
                // 统一手势循环：平移 / 缩放 / 拖节点 / 点节点，一个 loop 处理，避免多探测器抢事件
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    // 命中测试：落在某节点卡矩形内 → 抓住它
                    var grabbed = -1
                    for (i in steps.indices) {
                        val sz = cards[i].third
                        val ccx = cx(w, h, i); val ccy = cy(w, h, i)
                        if (down.position.x in (ccx - sz.width / 2f)..(ccx + sz.width / 2f) &&
                            down.position.y in (ccy - sz.height / 2f)..(ccy + sz.height / 2f)) { grabbed = i; break }
                    }
                    var dragDist = 0f
                    var zoomed = false
                    var movedNode = false
                    val slop = viewConfiguration.touchSlop
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.changes.none { it.pressed }) break
                        val zoom = event.calculateZoom()
                        val panDelta = event.calculatePan()
                        if (zoom != 1f) { zoomed = true; grabbed = -1; scale = (scale * zoom).coerceIn(0.4f, 4f) }
                        dragDist += panDelta.getDistance()
                        // 越过手指抖动阈值才算真拖动（否则点击会被误判为拖动、打不开弹窗）
                        if (panDelta != Offset.Zero && (dragDist > slop || zoomed)) {
                            if (grabbed >= 0) {
                                val base = minOf(w, h) * scale
                                if (base > 1f) { coords[grabbed] = coords[grabbed] + Offset(panDelta.x / base, panDelta.y / base); movedNode = true }
                            } else {
                                pan += panDelta
                            }
                        }
                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                    // 几乎没动 + 抓到节点 → 视为点击，打开编辑
                    if (grabbed >= 0 && !zoomed && dragDist <= slop) editing = grabbed
                    else if (movedNode) persist()   // 拖过节点 → 存新坐标
                }
            }
    ) {
        val w = size.width; val h = size.height
        val n = steps.size
        val sx = FloatArray(n); val sy = FloatArray(n)
        for (i in 0 until n) { sx[i] = cx(w, h, i); sy[i] = cy(w, h, i) }

        // 1) 数据流引用（{{stepN}}/{{last}}）：细虚线（画在底层）
        for (i in 0 until n) {
            refs.getOrElse(i) { emptyList() }.forEach { src ->
                if (src in 0 until n && src != i) {
                    drawLine(scheme.tertiary.copy(alpha = 0.65f), Offset(sx[src], sy[src]), Offset(sx[i], sy[i]), strokeWidth = 1.6f, pathEffect = dash)
                }
            }
        }
        // 2) 主流程顺序箭头：相邻步骤实线 + 箭头（收到目标卡边缘）
        for (i in 0 until n - 1) {
            val a = Offset(sx[i], sy[i]); val b = Offset(sx[i + 1], sy[i + 1])
            val sz = cards[i + 1].third
            val tip = rectBorderPoint(b, a, sz.width / 2f + 3f, sz.height / 2f + 3f)
            drawLine(scheme.primary.copy(alpha = 0.85f), a, tip, strokeWidth = 3f)
            drawArrowHead(tip, a, scheme.primary)
        }
        // 3) 节点卡：底层阴影 + 填充 + 边框 + 两行文字
        for (i in 0 until n) {
            val sz = cards[i].third
            val tlx = sx[i] - sz.width / 2f; val tly = sy[i] - sz.height / 2f
            val corner = CornerRadius(16f, 16f)
            drawRoundRect(scheme.surfaceContainerHighest, topLeft = Offset(tlx, tly + 2f), size = sz, cornerRadius = corner)
            drawRoundRect(scheme.surfaceContainerHigh, topLeft = Offset(tlx, tly), size = sz, cornerRadius = corner)
            val err = steps[i].onError == "continue"
            drawRoundRect(if (err) scheme.tertiary else scheme.primary, topLeft = Offset(tlx, tly), size = sz, cornerRadius = corner, style = Stroke(width = 1.8f))
            drawText(cards[i].first, topLeft = Offset(tlx + 12f, tly + 6f))
            drawText(cards[i].second, topLeft = Offset(tlx + 12f, tly + 6f + cards[i].first.size.height + 4f))
        }
    }

    // 节点编辑弹窗
    editing?.let { idx ->
        if (idx !in steps.indices) { editing = null; return@let }
        val s = steps[idx]
        var tool by remember(idx) { mutableStateOf(s.tool) }
        var argsText by remember(idx) { mutableStateOf(s.argsText) }
        var onErr by remember(idx) { mutableStateOf(s.onError) }
        val argsValid = remember(argsText) { try { JSONObject(argsText); true } catch (_: Exception) { false } }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(tr("编辑第 ") + "${idx + 1}" + tr(" 步"), color = scheme.onSurface, fontSize = 15.sp) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    com.arix.app.ui.XtomField(value = tool, onValueChange = { tool = it },
                        label = tr("工具名"), modifier = Modifier.fillMaxWidth(), singleLine = true,
                        textStyle = TextStyle(fontSize = 12.sp))
                    Spacer(Modifier.height(8.dp))
                    com.arix.app.ui.XtomField(value = argsText, onValueChange = { argsText = it },
                        label = tr("参数 (JSON，可用 {{input}}/{{last}}/{{stepN}})"),
                        modifier = Modifier.fillMaxWidth(), singleLine = false, minLines = 6,
                        textStyle = TextStyle(fontSize = 12.sp))
                    if (!argsValid) Text(tr("参数不是合法 JSON，先修好才能保存"), color = scheme.error, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(tr("出错时"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 4.dp)) {
                        listOf("stop" to tr("停止整个流程"), "continue" to tr("跳过继续下一步")).forEach { (v, label) ->
                            Surface(onClick = { onErr = v }, shape = RoundedCornerShape(50),
                                color = if (onErr == v) scheme.primary else scheme.surfaceContainerHighest,
                                border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
                                modifier = Modifier.padding(end = 6.dp)) {
                                Text(label, color = if (onErr == v) scheme.onPrimary else scheme.onSurface, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = argsValid, onClick = {
                    val pretty = runCatching { JSONObject(argsText).toString(2) }.getOrNull() ?: argsText
                    steps[idx] = StepM(tool.trim(), pretty, onErr)
                    persist(); editing = null
                }) { Text(tr("保存"), color = if (argsValid) scheme.primary else scheme.onSurfaceVariant) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )
    }
}

// 从中心 c 朝 towards 方向，求射线与「以 c 为心、半宽 hw / 半高 hh」矩形边框的交点（箭头收到卡边）
private fun rectBorderPoint(c: Offset, towards: Offset, hw: Float, hh: Float): Offset {
    val dx = towards.x - c.x; val dy = towards.y - c.y
    val len = kotlin.math.hypot(dx, dy).coerceAtLeast(1e-3f)
    val ux = dx / len; val uy = dy / len
    val tx = if (kotlin.math.abs(ux) > 1e-4f) hw / kotlin.math.abs(ux) else Float.MAX_VALUE
    val ty = if (kotlin.math.abs(uy) > 1e-4f) hh / kotlin.math.abs(uy) else Float.MAX_VALUE
    val k = minOf(tx, ty)
    return Offset(c.x + ux * k, c.y + uy * k)
}

// 在 tip 处画箭头（指向从 from→tip 的方向）
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawArrowHead(tip: Offset, from: Offset, color: Color) {
    val dx = tip.x - from.x; val dy = tip.y - from.y
    val ang = kotlin.math.atan2(dy, dx)
    val size = 14f; val spread = 0.5f
    val p1 = Offset(tip.x - size * kotlin.math.cos(ang - spread), tip.y - size * kotlin.math.sin(ang - spread))
    val p2 = Offset(tip.x - size * kotlin.math.cos(ang + spread), tip.y - size * kotlin.math.sin(ang + spread))
    drawLine(color, tip, p1, strokeWidth = 3f)
    drawLine(color, tip, p2, strokeWidth = 3f)
}
