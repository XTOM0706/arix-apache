package com.arix.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Book
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.arix.app.ui.SettingsChoiceRow
import com.arix.app.ui.SettingsHint
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.SettingsToggle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.arix.app.ui.topChromeGapHeight

// 陪伴设置：交互状态层、健康/天气/通知等感知、陪伴包与主动消息/日记
@Composable fun CompanionSettingsPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    // 本页设了背景图时卡片必须不透明，否则内容压在照片上读不清
    val opaque = remember { PageBackgroundPrefs.get(context, "companion_settings") != null }
    var stateCardOn by remember { mutableStateOf(InteractionState.isEnabled(context)) }
    var healthOn by remember { mutableStateOf(HealthSignals.isEnabled(context)) }
    var gbPath by remember { mutableStateOf(HealthSignals.gadgetbridgePath(context)) }
    val hcAvailable = remember { HealthConnectSource.available(context) }   // 只查一次，别每次重组都发 binder
    var weatherOn by remember { mutableStateOf(WeatherSignals.isEnabled(context)) }
    var healthTick by remember { mutableStateOf(0) }   // 授权/开关变化后刷新预览
    val healthPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        HealthSignals.invalidate(); healthTick++
    }
    // 身体传感器（BODY_SENSORS）：给「现场测量」工具直接读本机心率传感器用（区别于上面的读历史授权）
    val bodySensorsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { healthTick++ }
    // Health Connect（健康连接）授权：契约收/发一组健康权限；授权后失效缓存并刷新预览
    val hcLauncher = rememberLauncherForActivityResult(HealthConnectSource.permissionContract()) {
        HealthSignals.invalidate(); healthTick++
    }
    var proactiveOn by remember { mutableStateOf(ProactivePrefs.enabled(context)) }
    var proEvery by remember { mutableStateOf(ProactivePrefs.everyHours(context)) }
    var proGap by remember { mutableStateOf(ProactivePrefs.minGapHours(context)) }
    var proQs by remember { mutableStateOf(ProactivePrefs.quietStart(context)) }
    var proQe by remember { mutableStateOf(ProactivePrefs.quietEnd(context)) }
    var proCall by remember { mutableStateOf(ProactivePrefs.callMode(context)) }
    var diaryOn by remember { mutableStateOf(DiaryPrefs.enabled(context)) }
    var diaryHour by remember { mutableStateOf(DiaryPrefs.hour(context)) }
    // 陪伴包：导入/下载解锁的情感陪伴模块
    var companionPack by remember { mutableStateOf(CompanionPrefs.packName(context)) }
    var showCompanionUrl by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun applyCompanion(json: String) {
        val msg = try {
            val m = com.arix.app.CompanionPack.install(context, json)
            companionPack = CompanionPrefs.packName(context)
            proactiveOn = ProactivePrefs.enabled(context); proCall = ProactivePrefs.callMode(context); diaryOn = DiaryPrefs.enabled(context)
            m
        } catch (e: Exception) { companionPack = CompanionPrefs.packName(context); tr("陪伴包无效：") + (e.message ?: "") }
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }
    val companionImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val json = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { try { context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() } } catch (_: Exception) { null } }
            if (json != null) applyCompanion(json) else android.widget.Toast.makeText(context, tr("读取失败"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    // 陪伴数据管理：日记翻阅 / 通知感知开关+查看
    var showDiaryList by remember { mutableStateOf(false) }
    var notifCaptureOn by remember { mutableStateOf(NotificationAwarenessPrefs.enabled(context)) }
    var showNotifList by remember { mutableStateOf(false) }
    // 通知打扰：我们自己发出去的通知在前台时闭不闭嘴
    var suppressFgNotif by remember { mutableStateOf(NotificationPrefs.suppressInForeground(context)) }
    var suppressToolNotif by remember { mutableStateOf(NotificationPrefs.suppressToolInForeground(context)) }
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    // 翻看历史日记
    if (showDiaryList) {
        val entries = remember { DiaryStore.all(context) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showDiaryList = false },
            title = { Text(tr("历史日记"), color = scheme.onSurface) },
            text = {
                if (entries.isEmpty()) Text(tr("还没有日记"), color = scheme.onSurfaceVariant, fontSize = 13.sp)
                else Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    entries.forEach { e ->
                        Text(e.date, color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
                        Text(e.text, color = scheme.onSurface, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showDiaryList = false }) { Text(tr("关闭"), color = scheme.primary) } },
            containerColor = scheme.surface, shape = MaterialTheme.shapes.extraLarge,
        )
    }
    // 查看最近捕获的通知
    if (showNotifList) {
        val items = remember { NotificationStore.recent(40) }
        val nfmt = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showNotifList = false },
            title = { Text(tr("最近通知"), color = scheme.onSurface) },
            text = {
                if (items.isEmpty()) Text(tr("最近没有捕获到通知（需已授通知使用权且未暂停）"), color = scheme.onSurfaceVariant, fontSize = 13.sp)
                else Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    items.forEach { it0 ->
                        val body = listOf(it0.title, it0.text).filter { s -> s.isNotBlank() }.joinToString("：")
                        Text("[${nfmt.format(it0.time)}] ${it0.app}", color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                        Text(body, color = scheme.onSurface, fontSize = 13.sp)
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showNotifList = false }) { Text(tr("关闭"), color = scheme.primary) } },
            containerColor = scheme.surface, shape = MaterialTheme.shapes.extraLarge,
        )
    }
    // 陪伴包：从链接下载
    if (showCompanionUrl) {
        var curl by remember { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showCompanionUrl = false },
            title = { Text(tr("从链接下载陪伴包"), color = scheme.onSurface) },
            text = { com.arix.app.ui.XtomField(value = curl, onValueChange = { curl = it }, label = tr("陪伴包 JSON 链接"), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp), modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = {
                val u = curl.trim(); showCompanionUrl = false
                if (u.isNotBlank()) scope.launch {
                    val j = com.arix.app.CompanionPack.download(u)
                    if (j != null) applyCompanion(j) else android.widget.Toast.makeText(context, tr("下载失败（检查链接/网络）"), android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Text(tr("下载安装"), color = scheme.primary) } },
            dismissButton = { TextButton(onClick = { showCompanionUrl = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.topChromeGapHeight())   // 顶部悬浮玻璃让位：在滚动内容里，内容能从玻璃下滑过去化开
        // 交互状态层（状态卡·实验）：跨新对话保持相处状态，修「开新对话他不像他」
        SettingsSection(tr("交互状态层"), Icons.Outlined.AutoAwesome, translucent = !opaque) {
            SettingsToggle(
                icon = Icons.Outlined.AutoAwesome,
                title = tr("交互状态层（状态卡·实验）"),
                subtitle = tr("每轮增量维护一张小状态卡(关系/语气/话题/待决问题)，挂在角色卡上；开新对话注入为「上次快照·先验」，让 AI 更像上次的他。默认关，每轮多一次轻量后台调用。"),
                checked = stateCardOn,
                onCheckedChange = { stateCardOn = it; InteractionState.setEnabled(context, it) },
            )
        }
        // 感知：健康 / 天气 / 通知——注入上下文让 AI 能关心你
        SettingsSection(tr("感知"), Icons.Outlined.Sensors, translucent = !opaque) {
            // 健康数据：读「better health tracker」App 的步数/心率/血氧/睡眠
            SettingsToggle(
                icon = Icons.Outlined.MonitorHeart,
                title = tr("健康数据"),
                subtitle = tr("读取「better health tracker」App 记录的步数/心率/血氧/睡眠，随消息注入让 AI 能关心你的状态。它已适配各国产手表传感器，需先装好并让它后台记录。"),
                checked = healthOn,
                onCheckedChange = { on ->
                    healthOn = on
                    HealthSignals.setEnabled(context, on)
                    if (on && HealthSignals.isTrackerInstalled(context) && !HealthSignals.hasReadPerm(context)) {
                        healthPermLauncher.launch(arrayOf(HealthSignals.READ_PERM))
                    }
                    healthTick++
                },
            )
            if (healthOn) {
                Spacer(Modifier.height(4.dp))
                val installed = remember(healthTick) { HealthSignals.isTrackerInstalled(context) }
                // report() 可能对另一 App 的 provider 做多次跨进程 query——放后台 IO，别卡设置页主线程
                var preview by remember { mutableStateOf(tr("读取中…")) }
                LaunchedEffect(healthTick) { preview = withContext(Dispatchers.IO) { runCatching { HealthSignals.report(context) }.getOrDefault(tr("读取失败")) } }
                SettingsHint(tr("当前：") + preview)
                Row(modifier = Modifier.padding(top = 6.dp, start = 4.dp)) {
                    androidx.compose.material3.OutlinedButton(onClick = { healthTick++ }) { Text(tr("刷新"), style = MaterialTheme.typography.labelLarge) }
                    if (installed && !HealthSignals.hasReadPerm(context)) {
                        Spacer(Modifier.width(8.dp))
                        androidx.compose.material3.OutlinedButton(onClick = { healthPermLauncher.launch(arrayOf(HealthSignals.READ_PERM)) }) { Text(tr("授权读取"), style = MaterialTheme.typography.labelLarge) }
                    }
                    if (!installed) {
                        Spacer(Modifier.width(8.dp))
                        Text(tr("未检测到 tracker App"), color = scheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
                // 第二健康源：Gadgetbridge 自动导出的 SQLite 库（步数/心率/睡眠）；空=自动探测常见路径
                com.arix.app.ui.XtomField(value = gbPath, onValueChange = { gbPath = it; HealthSignals.setGadgetbridgePath(context, it); healthTick++ }, label = tr("Gadgetbridge 导出库路径（选填，空=自动探测）"), modifier = Modifier.fillMaxWidth().padding(top = 6.dp, start = 4.dp, end = 4.dp))
                // 第三健康源：Health Connect（健康连接）——小米/vivo/Keep 等往它写数据后在此授权
                if (hcAvailable) {
                    Row(modifier = Modifier.padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.OutlinedButton(onClick = { hcLauncher.launch(HealthConnectSource.permissions) }) { Text(tr("授权健康连接"), style = MaterialTheme.typography.labelLarge) }
                        Spacer(Modifier.width(8.dp))
                        Text(tr("统一读 小米/vivo/Keep 等写入健康连接的数据"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
                    }
                }
                // 现场测量：「health_measure」工具**直接读本机身体传感器**(心率)需 BODY_SENSORS；本机无该传感器会自动回落手表。
                val bodyGranted = remember(healthTick) { androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.BODY_SENSORS) == android.content.pm.PackageManager.PERMISSION_GRANTED }
                Row(modifier = Modifier.padding(top = 6.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!bodyGranted) {
                        androidx.compose.material3.OutlinedButton(onClick = { bodySensorsLauncher.launch(android.Manifest.permission.BODY_SENSORS) }) { Text(tr("授权身体传感器"), style = MaterialTheme.typography.labelLarge) }
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(tr(if (bodyGranted) "已可直接用本机传感器现场测心率" else "「现场测量」工具直接读本机心率传感器需此权限；本机没传感器会回落手表"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.align(Alignment.CenterVertically))
                }
                Spacer(Modifier.height(4.dp))
            }
            // 天气感知：注入当前天气/降水，AI 能体贴提醒（带伞/加衣）
            SettingsToggle(
                icon = Icons.Outlined.WbSunny,
                title = tr("天气感知"),
                subtitle = tr("按本机定位取当前天气与降水概率，随消息注入让 AI 能体贴提醒（带伞/加衣）。用 open-meteo 免费接口、免 key；需定位权限。"),
                checked = weatherOn,
                onCheckedChange = { weatherOn = it; WeatherSignals.setEnabled(context, it) },
            )
            // 通知感知：捕获暂停开关 + 查看最近捕获
            SettingsToggle(
                icon = Icons.Outlined.Notifications,
                title = tr("通知感知"),
                subtitle = tr("已在系统里授予「通知使用权」后，可在此暂停捕获、或查看 AI 能看到的最近通知。授权入口在「权限」页。"),
                checked = notifCaptureOn,
                onCheckedChange = { notifCaptureOn = it; NotificationAwarenessPrefs.setEnabled(context, it) },
            )
            androidx.compose.material3.OutlinedButton(onClick = { showNotifList = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.padding(top = 6.dp, start = 4.dp)) { Text(tr("查看最近捕获的通知"), style = MaterialTheme.typography.labelLarge) }
        }
        // 通知打扰：上面「通知感知」管**读**别人的通知，这一节管**发**我们自己的通知
        SettingsSection(tr("通知打扰"), Icons.Outlined.NotificationsOff, translucent = !opaque) {
            SettingsToggle(
                icon = Icons.Outlined.NotificationsOff,
                title = tr("应用在前台时不弹通知"),
                subtitle = tr("你正开着 Arix 时，主动消息 / 提醒 / 定时任务 / 每日日记 / 深度研究与子 Agent 完成不再弹横幅——内容照样进对话、日记和岛里，一条不丢。默认开。"),
                checked = suppressFgNotif,
                onCheckedChange = { suppressFgNotif = it; NotificationPrefs.setSuppressInForeground(context, it) },
            )
            if (suppressFgNotif) {
                SettingsToggle(
                    icon = Icons.Outlined.AutoAwesome,
                    title = tr("连 AI 主动发的通知也拦"),
                    subtitle = tr("AI 用通知工具显式发的那条默认照发（多半是它有意提醒你）。打开则前台时一并拦下，并如实告诉 AI 没弹出。"),
                    checked = suppressToolNotif,
                    onCheckedChange = { suppressToolNotif = it; NotificationPrefs.setSuppressToolInForeground(context, it) },
                )
            }
            SettingsHint(tr("语音唤醒的全屏提示、前台服务常驻通知与实时胶囊不受此开关影响。"))
        }
        // 陪伴包：导入/下载解锁情感陪伴层（主动消息/日记/来电/Waifu）；不装=锁死、零后台开销
        SettingsSection(tr("陪伴包"), Icons.Outlined.Favorite, translucent = !opaque) {
            SettingsHint(tr("情感陪伴层（主动消息 / 每日日记 / AI 主动来电 / Waifu）默认锁着、不占后台。导入或下载一个陪伴包来解锁并配置；卸载即还原。"))
            if (companionPack.isNotBlank()) {
                Text(tr("已安装：") + companionPack + " v" + CompanionPrefs.packVersion(context), color = scheme.primary, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
                TextButton(onClick = { CompanionPack.uninstall(context); companionPack = ""; proactiveOn = false; diaryOn = false; android.widget.Toast.makeText(context, tr("已卸载陪伴包"), android.widget.Toast.LENGTH_SHORT).show() }) { Text(tr("卸载陪伴包"), color = scheme.error, style = MaterialTheme.typography.labelLarge) }
            } else {
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    TextButton(onClick = { applyCompanion(CompanionPack.builtinDefault()) }) { Text(tr("安装内置「标准陪伴」"), color = scheme.primary, style = MaterialTheme.typography.labelLarge) }
                    TextButton(onClick = { runCatching { companionImport.launch(arrayOf("application/json", "*/*")) } }) { Text(tr("从文件导入"), color = scheme.primary, style = MaterialTheme.typography.labelLarge) }
                    TextButton(onClick = { showCompanionUrl = true }) { Text(tr("从链接下载"), color = scheme.primary, style = MaterialTheme.typography.labelLarge) }
                }
            }
        }
        // 主动消息：AI 按时机主动找你说一句（陪伴层），点通知开到对话
        SettingsSection(tr("主动消息"), Icons.Outlined.NotificationsActive, translucent = !opaque) {
            SettingsToggle(
                icon = Icons.Outlined.NotificationsActive,
                title = tr("主动消息（AI 主动找你）"),
                subtitle = tr("AI 会在合适的时机主动发一句（带人设/记忆/当前时间与健康），点通知直接进对话。默认关，静默时段不打扰、每次主动之间留最小间隔。需已配置对话模型且有过对话。"),
                checked = proactiveOn,
                enabled = companionPack.isNotBlank(),
                onCheckedChange = { on ->
                    proactiveOn = on
                    ProactiveScheduler.apply(context, on)
                    // targetSdk=28 下裸 requestPermissions 不弹框（见 NotificationBootstrap）；
                    // 开了主动消息却收不到通知的话，这个功能等于没开。
                    if (on) NotificationBootstrap.request(
                        context,
                        requestPermission = { if (android.os.Build.VERSION.SDK_INT >= 33) notifPermLauncher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) },
                        openSettings = { runCatching { context.startActivity(it) } },
                    )
                },
            )
            if (proactiveOn) {
                SettingsToggle(
                    icon = Icons.Outlined.Phone,
                    title = tr("以「来电」方式"),
                    subtitle = tr("点接听唤起语音，AI 先开口"),
                    checked = proCall,
                    onCheckedChange = { proCall = it; ProactivePrefs.setCallMode(context, it) },
                )
                SettingsChoiceRow(
                    title = tr("检查间隔"),
                    options = listOf(1, 2, 3, 6).map { it to "${it}h" },
                    selected = proEvery,
                    onSelect = { proEvery = it; ProactivePrefs.setEveryHours(context, it); ProactiveScheduler.schedule(context) },
                )
                SettingsChoiceRow(
                    title = tr("最小间隔"),
                    options = listOf(3, 6, 12, 24).map { it to "${it}h" },
                    selected = proGap,
                    onSelect = { proGap = it; ProactivePrefs.setMinGapHours(context, it) },
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp)) {
                    Text(tr("静默时段"), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                    com.arix.app.ui.XtomField(value = proQs.toString(), onValueChange = { v -> v.toIntOrNull()?.takeIf { it in 0..23 }?.let { proQs = it; ProactivePrefs.setQuiet(context, it, proQe) } }, modifier = Modifier.widthIn(min = 64.dp), label = tr("起"))
                    Text("—", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 6.dp))
                    com.arix.app.ui.XtomField(value = proQe.toString(), onValueChange = { v -> v.toIntOrNull()?.takeIf { it in 0..23 }?.let { proQe = it; ProactivePrefs.setQuiet(context, proQs, it) } }, modifier = Modifier.widthIn(min = 64.dp), label = tr("止"))
                }
                SettingsHint("${tr("静默")} $proQs:00–$proQe:00 " + tr("不打扰；间隔为后台检查频率，实际发送再受最小间隔约束。"))
            }
        }
        // 每日日记：定时让 AI 为当天写一段小结
        SettingsSection(tr("每日日记"), Icons.Outlined.Book, translucent = !opaque) {
            SettingsToggle(
                icon = Icons.Outlined.Book,
                title = tr("每日日记"),
                subtitle = tr("每天到点，AI 以自己的口吻为当天和你聊过的内容写一小段日记并通知（需当天有过对话）。也可在对话里让它 写/念/列 日记。"),
                checked = diaryOn,
                enabled = companionPack.isNotBlank(),
                onCheckedChange = { on ->
                    diaryOn = on
                    DiaryScheduler.apply(context, on)
                    if (on) NotificationBootstrap.request(   // 同上：日记写好了要能通知到人
                        context,
                        requestPermission = { if (android.os.Build.VERSION.SDK_INT >= 33) notifPermLauncher.launch(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS)) },
                        openSettings = { runCatching { context.startActivity(it) } },
                    )
                },
            )
            if (diaryOn) {
                SettingsChoiceRow(
                    title = tr("生成时间"),
                    options = listOf(18, 21, 22, 23).map { it to "${it}:00" },
                    selected = diaryHour,
                    onSelect = { diaryHour = it; DiaryPrefs.setHour(context, it); DiaryScheduler.schedule(context) },
                )
            }
            androidx.compose.material3.OutlinedButton(onClick = { showDiaryList = true }, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp), modifier = Modifier.padding(top = 6.dp, start = 4.dp)) { Text(tr("翻看历史日记"), style = MaterialTheme.typography.labelLarge) }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
