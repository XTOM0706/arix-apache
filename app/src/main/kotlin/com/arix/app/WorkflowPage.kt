package com.arix.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.LinkOff
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Power
import androidx.compose.material.icons.outlined.PowerOff
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.outlined.WifiOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.ui.SettingsHint
import com.arix.app.ui.SettingsRow
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.SettingsSlider
import com.arix.app.ui.SettingsToggle
import com.arix.app.ui.XtomField
import com.arix.tool.WorkflowEngine
import com.arix.tool.WorkflowStore
import com.arix.tool.WorkflowTrigger
import com.arix.tool.WorkflowTriggerType
import kotlinx.coroutines.launch
import com.arix.app.ui.topChromeGapHeight
import kotlin.math.roundToInt

@Composable fun WorkflowPage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    var names by remember { mutableStateOf(WorkflowStore.names(context)) }
    var input by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<String?>(null) }
    var canvasMode by remember { mutableStateOf(false) }               // 列表 ↔ 画布
    var selectedName by remember { mutableStateOf<String?>(null) }      // 画布模式下正在看的工作流
    var triggerOpen by remember { mutableStateOf<String?>(null) }       // 正在编辑触发器的工作流（一次只开一个）
    var triggerRev by remember { mutableStateOf(0) }                    // 触发器改动计数：让行上的摘要重新算
    // 选中名失效时（删了/首次进）自动落到第一个
    LaunchedEffect(names) { if (selectedName == null || selectedName !in names) selectedName = names.firstOrNull() }

    Column(Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.topChromeGapHeight())   // 顶部悬浮玻璃让位：在滚动内容里，内容能从玻璃下滑过去化开
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("工作流"), color = scheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (names.isNotEmpty()) Surface(onClick = { canvasMode = !canvasMode }, shape = RoundedCornerShape(50), color = if (canvasMode) scheme.primary else scheme.surfaceContainerHighest, border = BorderStroke(1.dp, scheme.outlineVariant)) {
                Text(if (canvasMode) tr("列表") else tr("画布"), color = if (canvasMode) scheme.onPrimary else scheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
        Text(tr("把常做的一串操作存成流程，一键跑。新流程一般让 AI 建：跟它说「把 X 然后 Y 存成叫 Z 的工作流」。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))

        com.arix.app.ui.XtomField(value = input, onValueChange = { input = it }, label = tr("运行输入（可选，步骤里用 {{input}} 引用）"),
            modifier = Modifier.fillMaxWidth(), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
        Spacer(Modifier.height(10.dp))

        if (canvasMode) {
            // 画布模式：选一个工作流 → 节点卡 + 连线画布（拖动/缩放/点节点编辑）
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
                names.forEach { n ->
                    Surface(onClick = { selectedName = n }, shape = RoundedCornerShape(50), color = if (selectedName == n) scheme.secondaryContainer else scheme.surfaceContainerHighest, border = BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.padding(end = 6.dp)) {
                        Text(n, color = if (selectedName == n) scheme.onSecondaryContainer else scheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                    }
                }
            }
            val sel = selectedName
            val wf = remember(sel) { sel?.let { WorkflowStore.get(context, it) } }
            if (wf != null) {
                Text(tr("单指拖空白平移 · 双指缩放 · 拖节点移动 · 点节点编辑"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(bottom = 6.dp))
                WorkflowCanvas(wf = wf, onSave = { updated -> WorkflowStore.save(context, updated); names = WorkflowStore.names(context) })
                Spacer(Modifier.height(10.dp))
                Button(enabled = !running, onClick = {
                    running = true; result = "运行「$sel」中…"
                    scope.launch {
                        try {
                            val w = WorkflowStore.get(context, sel!!)
                            result = if (w != null) WorkflowEngine.run(context, w, input) else "没找到工作流"
                        } catch (e: Exception) { result = "运行出错：${e.message}" }
                        finally { running = false }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), shape = RoundedCornerShape(10.dp), modifier = Modifier.height(38.dp)) {
                    Text(tr("运行此工作流"), color = scheme.onPrimary, fontSize = 12.sp)
                }
            } else {
                Text(tr("没找到该工作流。"), color = scheme.onSurfaceVariant, fontSize = 12.sp)
            }
            result?.let {
                Spacer(Modifier.height(12.dp))
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surface).padding(12.dp)) {
                    Text(it, color = scheme.onSurface, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            return@Column
        }

        if (names.isEmpty()) Text(tr("还没有工作流。让 AI 用 workflow 工具建一个。"), color = scheme.onSurfaceVariant, fontSize = 12.sp)
        // 所有行的摘要一次算完：每行各读一次盘的话，编辑触发器时每敲一个字就要把 workflows.json 解析 N 遍
        val summaries = remember(names, triggerRev) { WorkflowTriggers.summaries(context) }
        names.forEach { n ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(scheme.surfaceContainerHighest).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(n, color = scheme.onSurface, fontSize = 14.sp)
                    // 触发器摘要：一眼看出这条是不是会自己跑，不用点进去
                    summaries[n]?.let {
                        Text(it, color = scheme.primary, fontSize = 10.sp, modifier = Modifier.padding(top = 1.dp))
                    }
                }
                Surface(
                    onClick = { triggerOpen = if (triggerOpen == n) null else n },
                    shape = RoundedCornerShape(50),
                    color = if (triggerOpen == n) scheme.secondaryContainer else scheme.surface,
                    modifier = Modifier.padding(end = 6.dp),
                ) {
                    Icon(Icons.Outlined.Bolt, contentDescription = tr("自动触发"), tint = scheme.primary, modifier = Modifier.padding(7.dp).size(18.dp))
                }
                Button(enabled = !running, onClick = {
                    running = true; result = "运行「$n」中…"
                    scope.launch {
                        try {
                            val wf = WorkflowStore.get(context, n)
                            result = if (wf != null) WorkflowEngine.run(context, wf, input) else "没找到工作流"
                        } catch (e: Exception) { result = "运行出错：${e.message}" }
                        finally { running = false }   // 无论如何复位，别让「运行」按钮永久禁用
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), shape = RoundedCornerShape(10.dp), modifier = Modifier.height(34.dp)) {
                    Text(tr("运行"), color = scheme.onPrimary, fontSize = 12.sp)
                }
                TextButton(onClick = { WorkflowStore.delete(context, n); names = WorkflowStore.names(context) }) { Text(tr("删"), color = scheme.error, fontSize = 12.sp) }
            }
            if (triggerOpen == n) TriggerEditor(context, n) { triggerRev++ }
        }

        result?.let {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.surface).padding(12.dp)) {
                Text(it, color = scheme.onSurface, fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** 触发方式的图标。项目规矩：只用 Material vector，不用 emoji。 */
private fun iconOf(t: WorkflowTriggerType): ImageVector = when (t) {
    WorkflowTriggerType.NONE -> Icons.Outlined.Tune
    WorkflowTriggerType.INTERVAL -> Icons.Outlined.Timer
    WorkflowTriggerType.DAILY -> Icons.Outlined.Alarm
    WorkflowTriggerType.CRON -> Icons.Outlined.Event
    WorkflowTriggerType.BOOT -> Icons.Outlined.RestartAlt
    WorkflowTriggerType.SCREEN_ON -> Icons.Outlined.LightMode
    WorkflowTriggerType.SCREEN_OFF -> Icons.Outlined.DarkMode
    WorkflowTriggerType.BATTERY_LOW -> Icons.Outlined.BatteryAlert
    WorkflowTriggerType.POWER_CONNECTED -> Icons.Outlined.Power
    WorkflowTriggerType.POWER_DISCONNECTED -> Icons.Outlined.PowerOff
    WorkflowTriggerType.WIFI_CONNECTED -> Icons.Outlined.Wifi
    WorkflowTriggerType.WIFI_DISCONNECTED -> Icons.Outlined.WifiOff
    WorkflowTriggerType.HEADSET_PLUGGED, WorkflowTriggerType.HEADSET_UNPLUGGED -> Icons.Outlined.Headphones
    WorkflowTriggerType.BT_CONNECTED -> Icons.Outlined.Devices
    WorkflowTriggerType.BT_DISCONNECTED -> Icons.Outlined.LinkOff
    WorkflowTriggerType.GEOFENCE_ENTER -> Icons.Outlined.Place
    WorkflowTriggerType.GEOFENCE_EXIT -> Icons.Outlined.Public
    WorkflowTriggerType.NOTIF_MATCH -> Icons.Outlined.NotificationsActive
    WorkflowTriggerType.APP_LAUNCHED -> Icons.Outlined.Dashboard
    WorkflowTriggerType.APP_CLOSED -> Icons.Outlined.Block
    WorkflowTriggerType.APP_FOREGROUND_LONG -> Icons.Outlined.HourglassEmpty
}

/**
 * 单个工作流的触发器编辑。
 *
 * 每次改动立刻落盘（[WorkflowStore.setTrigger] 里会回调 [WorkflowTriggers] 重挂监听），
 * 所以关掉开关的下一刻系统监听就摘了——手表上「关了还在后台听」是不能接受的。
 * 滑杆只在松手时提交，避免拖动过程中每一帧写一次文件。
 */
@Composable
private fun TriggerEditor(context: android.content.Context, name: String, onChanged: () -> Unit) {
    var t by remember(name) { mutableStateOf(WorkflowStore.trigger(context, name)) }
    var interval by remember(name) { mutableStateOf(t.intervalMin.toFloat()) }
    var gap by remember(name) { mutableStateOf(t.minGapMin.toFloat()) }
    var hourText by remember(name) { mutableStateOf(t.hour.toString()) }
    var minuteText by remember(name) { mutableStateOf(t.minute.toString()) }
    var inputText by remember(name) { mutableStateOf(t.input) }
    // 新增触发器各自的编辑态。全部 remember(name)，换工作流时重新取值。
    var cronText by remember(name) { mutableStateOf(t.cron) }
    var pkgText by remember(name) { mutableStateOf(t.pkg) }
    var patternText by remember(name) { mutableStateOf(t.pattern) }
    var deviceText by remember(name) { mutableStateOf(t.device) }
    var latText by remember(name) { mutableStateOf(if (t.lat == 0.0 && t.lon == 0.0) "" else t.lat.toString()) }
    var lonText by remember(name) { mutableStateOf(if (t.lat == 0.0 && t.lon == 0.0) "" else t.lon.toString()) }
    var radius by remember(name) { mutableStateOf(t.radiusM.toFloat()) }
    var fgMin by remember(name) { mutableStateOf(t.fgMinutes.toFloat()) }
    var locating by remember(name) { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    val uiScope = rememberCoroutineScope()

    // 权限状态在**从系统设置页回来时**重查一次。不这么做用户就会看到「明明刚授权了这里还红着」。
    var permRev by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) permRev++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permRev++ }

    fun apply(next: WorkflowTrigger) {
        t = next
        WorkflowStore.setTrigger(context, name, next)
        onChanged()
    }

    Column(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
        SettingsSection(title = tr("自动触发"), icon = Icons.Outlined.Bolt, translucent = false) {
            SettingsToggle(
                icon = Icons.Outlined.Bolt,
                title = tr("启用自动触发"),
                subtitle = tr("关掉后对应的系统监听立刻摘掉，不再耗电"),
                checked = t.enabled,
                onCheckedChange = { apply(t.copy(enabled = it)) },
                enabled = t.type != WorkflowTriggerType.NONE,
            )

            Text(tr("触发方式"), style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)) {
                WorkflowTriggerType.values().forEach { ty ->
                    val on = ty == t.type
                    Surface(
                        onClick = { apply(t.copy(type = ty, enabled = if (ty == WorkflowTriggerType.NONE) false else t.enabled)) },
                        shape = RoundedCornerShape(50),
                        color = if (on) scheme.primary else scheme.surfaceContainerHighest,
                    ) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(iconOf(ty), contentDescription = null, tint = if (on) scheme.onPrimary else scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(WorkflowTriggers.label(ty), style = MaterialTheme.typography.labelLarge, color = if (on) scheme.onPrimary else scheme.onSurface)
                        }
                    }
                }
            }

            // 权限闸：缺什么、去哪儿给，一律说明白。静默不触发是最难排查的一类「设了但没反应」。
            val pgap = remember(t.type, permRev) { WorkflowTriggers.permissionGap(context, t.type) }
            if (pgap != null) {
                SettingsHint(pgap.what, error = true)
                if (pgap.runtime.isNotEmpty() || pgap.settings != null) {
                    SettingsRow(icon = Icons.Outlined.Shield, title = pgap.how.ifBlank { tr("去授权") }) {
                        if (pgap.runtime.isNotEmpty()) runCatching { permLauncher.launch(pgap.runtime.toTypedArray()) }
                        else pgap.settings?.let { i ->
                            runCatching { context.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        }
                    }
                }
            }
            if (t.enabled && !t.configured) SettingsHint(tr("下面这几项还没填完，这条触发器暂时不会挂上，也不会触发。"), error = true)

            when (t.type) {
                WorkflowTriggerType.INTERVAL -> SettingsSlider(
                    title = tr("间隔"),
                    subtitle = tr("手表上低于 15 分钟没有意义，系统也会把太频繁的后台任务推迟"),
                    icon = Icons.Outlined.Timer,
                    value = interval,
                    range = WorkflowTrigger.MIN_INTERVAL_MIN.toFloat()..720f,
                    unit = tr("分钟"),
                    onValueChange = { interval = it },
                    onValueChangeFinished = { apply(t.copy(intervalMin = interval.roundToInt())) },
                )

                // ---- cron：只解析到分钟。用「下一次」当实时校验，比报语法错误有用得多 ----
                WorkflowTriggerType.CRON -> {
                    XtomField(
                        value = cronText,
                        onValueChange = { cronText = it; apply(t.copy(cron = it)) },
                        label = tr("cron 表达式（分 时 日 月 周）"),
                        placeholder = "0 8,18 * * mon-fri",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        singleLine = true,
                    )
                    val next = remember(cronText, name, t) { WorkflowTriggers.nextRunText(context, name, t.copy(cron = cronText)) }
                    if (cronText.isBlank()) SettingsHint(tr("例：0 8 * * 1-5 = 工作日 8:00；0 8,12,18 * * * = 每天三个时刻；*/30 9-18 * * * = 9 到 18 点每半小时"))
                    else if (next == null) SettingsHint(tr("表达式无效（要 5 段：分 时 日 月 周），或一年内都不会到点"), error = true)
                    else SettingsHint(tr("下一次") + "：" + next)
                    SettingsHint(tr("星期可写 0-6 或 sun-sat（0 和 7 都是周日）。「日」和「周」同时限定时按标准 cron 的「或」算。"))
                }

                WorkflowTriggerType.BOOT -> SettingsHint(tr("设备重启完成后触发一次。同一次开机只会触发一次。"))

                // ---- 地理围栏：交给系统围栏，我们不轮询定位 ----
                WorkflowTriggerType.GEOFENCE_ENTER, WorkflowTriggerType.GEOFENCE_EXIT -> {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        XtomField(
                            value = latText,
                            onValueChange = { s ->
                                latText = s.filter { it.isDigit() || it == '.' || it == '-' }.take(12)
                                latText.toDoubleOrNull()?.let { apply(t.copy(lat = it.coerceIn(-90.0, 90.0))) }
                            },
                            label = tr("纬度"), modifier = Modifier.weight(1f), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), verticalPadding = 8.dp,
                        )
                        Spacer(Modifier.width(6.dp))
                        XtomField(
                            value = lonText,
                            onValueChange = { s ->
                                lonText = s.filter { it.isDigit() || it == '.' || it == '-' }.take(12)
                                lonText.toDoubleOrNull()?.let { apply(t.copy(lon = it.coerceIn(-180.0, 180.0))) }
                            },
                            label = tr("经度"), modifier = Modifier.weight(1f), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), verticalPadding = 8.dp,
                        )
                    }
                    Button(
                        enabled = !locating,
                        onClick = {
                            locating = true
                            uiScope.launch {
                                try {
                                    // LocationSignals.current 内部会等待（最长 8 秒），绝不能在主线程上跑
                                    val loc = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                        LocationSignals.current(context)
                                    }
                                    if (loc != null) {
                                        // 必须钉 Locale.US：跟着系统语言走会写出 "39,904" 这种逗号小数，
                                        // 回头再被输入框 toDouble 解析时直接失败
                                        latText = String.format(java.util.Locale.US, "%.6f", loc.latitude)
                                        lonText = String.format(java.util.Locale.US, "%.6f", loc.longitude)
                                        apply(t.copy(lat = loc.latitude, lon = loc.longitude))
                                    }
                                } catch (e: kotlinx.coroutines.CancellationException) {
                                    throw e                       // 取消必须重抛，别当普通失败吞掉
                                } catch (_: Exception) {
                                } finally { locating = false }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.secondaryContainer),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.padding(horizontal = 4.dp).height(34.dp),
                    ) {
                        Icon(Icons.Outlined.Place, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (locating) tr("定位中…") else tr("用当前位置"), color = scheme.onSecondaryContainer, fontSize = 12.sp)
                    }
                    SettingsSlider(
                        title = tr("半径"),
                        // 说清楚为什么不让调更小：这不是保守，是物理限制
                        subtitle = tr("手表的网络定位误差常年上百米，围栏比误差还小就会在原地反复进出。"),
                        icon = Icons.Outlined.Straighten,
                        value = radius,
                        range = WorkflowTrigger.MIN_GEO_RADIUS_M.toFloat()..2000f,
                        unit = tr("米"),
                        onValueChange = { radius = it },
                        onValueChangeFinished = { apply(t.copy(radiusM = radius.roundToInt())) },
                    )
                    SettingsHint(tr("用的是系统的围栏能力（有硬件围栏的机器直接跑在基带上），我们自己一次定位都不发起，所以常年开着也不明显掉电。"))
                    SettingsHint(tr("刚配好时只会记下你现在在里面还是外面，不当成一次进出；真的走进/走出才触发。"))
                }

                // ---- 通知内容匹配 ----
                WorkflowTriggerType.NOTIF_MATCH -> {
                    XtomField(
                        value = pkgText,
                        onValueChange = { pkgText = it; apply(t.copy(pkg = it.trim())) },
                        label = tr("只看这个 App（包名，可留空）"),
                        placeholder = "com.tencent.mm",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        singleLine = true,
                    )
                    XtomField(
                        value = patternText,
                        onValueChange = { patternText = it; apply(t.copy(pattern = it)) },
                        label = if (t.regex) tr("正则（匹配标题+正文）") else tr("包含这段文字（标题+正文）"),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        singleLine = true,
                    )
                    SettingsToggle(
                        icon = Icons.Outlined.Code,
                        title = tr("按正则匹配"),
                        subtitle = tr("关掉就是普通的「包含」。正则写错时当作不匹配，不会让触发器崩掉。"),
                        checked = t.regex,
                        onCheckedChange = { apply(t.copy(regex = it)) },
                    )
                    // remember 必须无条件求值：放进 && 短路里会让组合槽位错位
                    val badRegex = remember(patternText) { patternText.isNotBlank() && runCatching { Regex(patternText) }.isFailure }
                    if (t.regex && badRegex) SettingsHint(tr("这条正则编译不过，永远不会命中。"), error = true)
                }

                // ---- App 启动 / 关闭 ----
                WorkflowTriggerType.APP_LAUNCHED, WorkflowTriggerType.APP_CLOSED -> {
                    XtomField(
                        value = pkgText,
                        onValueChange = { pkgText = it; apply(t.copy(pkg = it.trim())) },
                        label = tr("App 包名（写一段也行，按包含匹配）"),
                        placeholder = "com.tencent.mm",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        singleLine = true,
                    )
                    SettingsHint(tr("系统不提供「App 切换」的回调，只能取样，所以这里只在**亮屏时**每 30 秒看一次（App 切前台本来也只发生在亮屏时），息屏立刻停。最坏会晚 30 秒。"))
                }

                // ---- App 前台时长 ----
                WorkflowTriggerType.APP_FOREGROUND_LONG -> {
                    XtomField(
                        value = pkgText,
                        onValueChange = { pkgText = it; apply(t.copy(pkg = it.trim())) },
                        label = tr("App 包名（写一段也行，按包含匹配）"),
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        singleLine = true,
                    )
                    SettingsSlider(
                        title = tr("连续前台超过"),
                        subtitle = tr("同一段连续使用里只触发一次；切走再切回来才重新计时。"),
                        icon = Icons.Outlined.HourglassEmpty,
                        value = fgMin,
                        range = 1f..240f,
                        unit = tr("分钟"),
                        onValueChange = { fgMin = it },
                        onValueChangeFinished = { apply(t.copy(fgMinutes = fgMin.roundToInt())) },
                    )
                    SettingsHint(tr("同样只在亮屏时取样：息屏了就不算在前台。"))
                }

                // ---- 蓝牙设备 ----
                WorkflowTriggerType.BT_CONNECTED, WorkflowTriggerType.BT_DISCONNECTED -> {
                    XtomField(
                        value = deviceText,
                        onValueChange = { deviceText = it; apply(t.copy(device = it.trim())) },
                        label = tr("设备名或 MAC（留空=任意设备）"),
                        placeholder = "AirPods",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                        singleLine = true,
                    )
                    SettingsHint(tr("设备名按包含匹配，MAC 要写全。没给蓝牙权限时读不到名字，只能按 MAC 匹配。"))
                }

                WorkflowTriggerType.DAILY -> Row(Modifier.padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(tr("每天"), style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    Spacer(Modifier.width(8.dp))
                    XtomField(
                        value = hourText,
                        onValueChange = { s ->
                            hourText = s.filter { it.isDigit() }.take(2)
                            hourText.toIntOrNull()?.let { apply(t.copy(hour = it.coerceIn(0, 23))) }
                        },
                        modifier = Modifier.widthIn(min = 52.dp, max = 64.dp), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), verticalPadding = 8.dp,
                    )
                    Text(" : ", style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface)
                    XtomField(
                        value = minuteText,
                        onValueChange = { s ->
                            minuteText = s.filter { it.isDigit() }.take(2)
                            minuteText.toIntOrNull()?.let { apply(t.copy(minute = it.coerceIn(0, 59))) }
                        },
                        modifier = Modifier.widthIn(min = 52.dp, max = 64.dp), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), verticalPadding = 8.dp,
                    )
                }
                else -> {}
            }

            if (t.type != WorkflowTriggerType.NONE) {
                SettingsSlider(
                    title = tr("最小触发间隔"),
                    // 说清楚代价：这不是洁癖设置，是直接换算成电和钱的
                    subtitle = tr("这段时间内重复触发只算第一次。开关屏一晚上能来几十次，每次都真跑一遍就是几十次模型调用。"),
                    icon = Icons.Outlined.Tune,
                    value = gap,
                    range = 1f..120f,
                    unit = tr("分钟"),
                    onValueChange = { gap = it },
                    onValueChangeFinished = { apply(t.copy(minGapMin = gap.roundToInt())) },
                )
                XtomField(
                    value = inputText,
                    onValueChange = { inputText = it; apply(t.copy(input = it)) },
                    label = tr("触发时传入（步骤里用 {{input}} 引用）"),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    singleLine = true,
                )
                // 事件类触发器能把「这次是哪条通知/哪个 App/哪台设备」带进工作流，不写出来用户根本猜不到
                WorkflowTriggers.inputVars(t.type)?.let {
                    SettingsHint(tr("这里还能用") + "：" + it + "（" + tr("留空则自动传入一句事件描述") + "）")
                }
                SettingsHint(tr("自动触发在后台运行：需要你点头授权的工具会被判为拒绝，尽量只放不用审批的步骤。"))
                WorkflowTriggers.lastNote(context, name)?.let { SettingsHint(tr("上次触发") + "：" + it) }
            }
        }
    }
}
