package com.arix.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.theme.LocalXtomAccents
import androidx.compose.foundation.clickable
import androidx.compose.material3.RadioButton
import com.arix.app.ui.XtomCard
import com.arix.app.ui.PageScaffold
import com.arix.wake.WakeEnrollment
import com.arix.wake.WakeLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable fun WakePage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    var wakeStatus by remember { mutableStateOf(if (WakeService.isRunning) tr("监听中") else tr("未启动")) }
    var isListening by remember { mutableStateOf(WakeService.isRunning) }
    var templates by remember { mutableStateOf(listOf<com.arix.wake.WakeTemplate>()) }
    var enrolling by remember { mutableStateOf(false) }
    var pendingName by remember { mutableStateOf("") }
    var renamingId by remember { mutableStateOf<String?>(null) }
    var logMessages by remember { mutableStateOf(listOf<String>()) }
    var bgWake by remember { mutableStateOf(WakeService.bgWakeEnabled(context)) }
    var powerMode by remember { mutableStateOf(WakeService.powerMode(context)) }
    var lowSaver by remember { mutableStateOf(WakeService.lowBatterySaver(context)) }
    var lockWake by remember { mutableStateOf(WakeService.lockScreenWakeEnabled(context)) }
    var bgBlur by remember { mutableStateOf(WakeService.bgBlurEnabled(context)) }
    var greeting by remember { mutableStateOf(WakeService.wakeGreeting(context)) }
    val enrollment = remember { WakeEnrollment(context) }

    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }

    fun addLog(msg: String) { logMessages = listOf("[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $msg") + logMessages.take(49) }
    // 按钮的一次性结果：日志之外再弹一下——页面很长，光写进日志卡的话在别处点按钮看不到反馈。
    // 整体回主线程，后台回调（Shizuku/录入）直接调也安全。
    fun report(msg: String) = mainHandler.post {
        addLog(msg)
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    // 数字助理角色：RoleManager 的系统授权框走 StartActivityForResult。
    // 系统可能直接 RESULT_CANCELED（用户拒了，或该 ROM 把 role 声明成不可请求），故按结果如实回报，不假设成功。
    var assistantHeld by remember { mutableStateOf(AssistantRole.held(context)) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        assistantHeld = AssistantRole.held(context)   // 以实际持有状态为准，别信 resultCode
        report(if (assistantHeld) tr("已设为默认数字助理") else tr("没有设成（你取消了，或这台设备不允许）"))
    }

    // 通知权限（Android 13+）：没授的话前台服务照跑，但常驻保活通知不显示。开启唤醒时按需申请。
    val notifPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        report(if (granted) tr("通知权限已授予：语音唤醒常驻通知会显示在通知栏（保活用，不弹窗）")
        else tr("通知权限被拒：常驻通知不显示（服务仍在跑；可到系统设置手动开启通知）"))
    }
    fun ensureNotifPermission() {
        // targetSdk=28 下裸 requestPermissions 在 Android 13+ 不弹框、直接算拒绝——
        // 唤醒服务的常驻通知因此不显示，用户以为服务没起来。真正的入口见 NotificationBootstrap。
        com.arix.app.NotificationBootstrap.request(
            context,
            requestPermission = {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU)
                    notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            },
            openSettings = { runCatching { context.startActivity(it) } },
        )
    }

    fun refreshTemplates() { templates = enrollment.templates.list() }
    LaunchedEffect(isListening) { refreshTemplates() }

    // 实时唤醒诊断：管线/状态机/判决器的日志 → 主线程更新到列表
    DisposableEffect(Unit) {
        WakeLog.recent().asReversed().forEach { addLog("· $it") }
        WakeLog.listener = { msg -> mainHandler.post { logMessages = listOf("· $msg") + logMessages.take(79) } }
        onDispose { WakeLog.listener = null }
    }

    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    PageScaffold {
        Text(tr("语音唤醒"), color = scheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(tr("录入个人唤醒词后，在后台持续监听语音唤醒"), color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))

        // Status card
        XtomCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Mic, null, tint = if (isListening) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(tr(if (isListening) "唤醒状态: 监听中" else "唤醒状态: 已停止"), color = if (isListening) scheme.primary else scheme.onSurfaceVariant, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
            Spacer(Modifier.height(4.dp))
            val on = templates.count { it.enabled }
            Text(
                if (templates.isEmpty()) tr("还没录唤醒词") else tr("唤醒词模板: %s 条，启用 %s 条").format(templates.size, on),
                color = scheme.onSurfaceVariant, fontSize = 12.sp,
            )
        }

        Spacer(Modifier.height(12.dp))

        // 日志紧跟状态卡：录入进度/唤醒诊断都往这里流，放页面底部的话操作时看不见。
        Text(tr("日志"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        XtomCard {
            Column(Modifier.heightIn(min = 120.dp, max = 200.dp).verticalScroll(rememberScrollState())) {
                if (logMessages.isEmpty()) Text(tr("暂无日志"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                else logMessages.forEach { Text(it, color = scheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 唤醒词模板：可存多条——同一个词在不同环境/语气各录一条能明显提高命中率，也可以录多个不同的词。
        // 判决时拿所有「启用中」的逐条比，命中任意一条即唤醒。
        Text(tr("唤醒词模板"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(tr("可录多条：同一个唤醒词在安静/嘈杂、平静/急促下各录一条，命中率更稳；也能录不同的唤醒词。关掉的模板不参与判决。"),
            color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        if (templates.isEmpty()) {
            XtomCard { Text(tr("还没有模板，点下面「录入新唤醒词」"), color = scheme.onSurfaceVariant, fontSize = 12.sp) }
        } else {
            templates.forEach { t ->
                XtomCard {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text(t.name, color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            Text(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(t.createdAt)), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                        }
                        Switch(checked = t.enabled, onCheckedChange = { on ->
                            enrollment.templates.setEnabled(t.id, on); refreshTemplates()   // 判决器靠 WakeTemplateStore.version 自己发现变更并重载
                            // 判决器缓存了原型，改完要让唤醒服务重新加载，否则本次开关不生效
                            if (!on && enrollment.templates.enabled().isEmpty() && isListening) {
                                WakeService.stop(context); isListening = false; wakeStatus = tr("已停止")
                                report(tr("已关掉最后一条启用的模板，语音唤醒同时停止"))
                            }
                        })
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        androidx.compose.material3.TextButton(onClick = { renamingId = t.id; pendingName = t.name }) {
                            Text(tr("重命名"), color = scheme.primary, fontSize = 12.sp)
                        }
                        androidx.compose.material3.TextButton(onClick = {
                            enrollment.templates.delete(t.id); refreshTemplates()
                            if (enrollment.templates.enabled().isEmpty() && isListening) {
                                WakeService.stop(context); isListening = false; wakeStatus = tr("已停止")
                            }
                            report(tr("已删除模板「%s」").format(t.name))
                        }) { Text(tr("删除"), color = scheme.error, fontSize = 12.sp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(enabled = !enrolling, onClick = {
            enrolling = true
            report(tr("开始录入（对准麦克风，把同一个唤醒词说 3 遍）..."))
            scope.launch {
                val id = enrollment.enroll(3, onProgress = { msg -> addLog(msg) })
                withContext(Dispatchers.Main) {
                    enrolling = false
                    refreshTemplates()
                    if (id != null) {
                        wakeStatus = tr("已录入唤醒词")
                        renamingId = id                      // 录完立刻让用户起名
                        pendingName = templates.lastOrNull()?.name ?: ""
                        report(tr("录入完成，给它起个名字"))
                    } else report(tr("录入失败：请在安静环境重试"))
                }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Text(if (enrolling) tr("录入中…") else tr("录入新唤醒词"), color = scheme.onPrimary, fontWeight = FontWeight.Bold)
        }

        if (templates.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = {
                if (isListening) { WakeService.stop(context); isListening = false }
                enrollment.clearPrototype()
                refreshTemplates()
                wakeStatus = tr("未录入")
                report(tr("已删除全部唤醒词模板"))
            }, colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest, contentColor = scheme.error), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Text(tr("删除全部模板"), fontWeight = FontWeight.Medium)
            }
        }

        // 命名/重命名：录完立刻弹（先存后改名，中途取消也不丢刚录的，只是留着自动名）
        renamingId?.let { rid ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { renamingId = null },
                title = { Text(tr("给这条唤醒词起个名"), color = scheme.onSurface) },
                text = {
                    Column {
                        com.arix.app.ui.XtomField(value = pendingName, onValueChange = { pendingName = it },
                            placeholder = tr("如：安静时 / 嘈杂时"), modifier = Modifier.fillMaxWidth())
                        Text(tr("名字只是给你自己认的，不影响识别。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = {
                        enrollment.templates.rename(rid, pendingName); refreshTemplates(); renamingId = null
                    }) { Text(tr("保存"), color = scheme.primary) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { renamingId = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) }
                },
                containerColor = scheme.surface, shape = MaterialTheme.shapes.extraLarge,
            )
        }

        Spacer(Modifier.height(8.dp))
        // 一个开关：开启/关闭语音唤醒。命中后走数字助手会话（设为默认助手时由系统托管弹出）。
        Button(onClick = {
            if (isListening) {
                WakeService.stop(context)
                isListening = false; wakeStatus = tr("已停止")
                report(tr("语音唤醒已关闭"))
            } else {
                if (!enrollment.hasPrototype()) { report(tr("请先录入唤醒词")); return@Button }
                try {
                    ensureNotifPermission()
                    WakeService.start(context)
                    isListening = true; wakeStatus = tr("监听中")
                    report(tr("语音唤醒已开启（后台常驻），命中后走数字助手会话"))
                    if (!XtomVoiceInteractionService.isActiveAssistant(context))
                        addLog(tr("提示：把 Arix 设为默认助手，命中后由系统托管弹出会话（更稳、免悬浮窗权限）"))
                } catch (e: Exception) {
                    report(tr("开启失败: %s").format(e.message))
                }
            }
        }, colors = ButtonDefaults.buttonColors(containerColor = if (isListening) scheme.error else scheme.primary), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Text(if (isListening) tr("关闭语音唤醒") else tr("开启语音唤醒"), color = scheme.onPrimary, fontWeight = FontWeight.Bold, fontSize = 15.sp)
        }

        Spacer(Modifier.height(16.dp))
        // ── 省电策略 ───────────────────────────────────────────────────────
        // 这三档一直存在于 WakeConfig 里，只是服务侧写死了「常听」这一档、够不着。这里把它接出来。
        // 默认仍是常听：改档要下次开启唤醒才生效（策略在引擎 start 时定），文案里说清楚。
        Text(tr("耗电策略"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(
            tr("常听最灵，也最耗电（麦克风一直开着）。窗口模式只在抬腕亮屏等时机短暂开麦，省电但可能漏听。改完下次开启唤醒时生效。"),
            color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
        )
        listOf(
            WakeService.POWER_ALWAYS_ON to tr("一直听（默认，最灵）"),
            WakeService.POWER_WHEN_CHARGING to tr("充电时才一直听，拔电自动省电"),
            WakeService.POWER_WINDOWED to tr("只在窗口内听（最省电，可能漏听）"),
        ).forEach { (mode, label) ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { powerMode = mode; WakeService.setPowerMode(context, mode) },
            ) {
                RadioButton(selected = powerMode == mode, onClick = { powerMode = mode; WakeService.setPowerMode(context, mode) })
                Text(label, color = scheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("电量低于 %s%% 且没插电时自动省电").format(WakeService.LOW_BATTERY_PCT), color = scheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = lowSaver, onCheckedChange = { lowSaver = it; WakeService.setLowBatterySaver(context, it) })
        }
        if (powerMode != WakeService.POWER_ALWAYS_ON || lowSaver) {
            Text(
                tr("注意：部分系统（如 MIUI）对非系统应用限制严格，麦克风一旦在后台重开可能拿不到声音——切换到省电档后如果叫不醒，把这里改回「一直听」。"),
                color = accents.warning, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        // 系统助手：把 Arix 设为默认数字助手（抬腕/助手手势召出）
        Text(tr("系统助手"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(tr("「手动唤醒」= 不靠语音/模型，直接召出助手界面（唤醒词模型未就绪时也能用）。设为默认助手后还可用系统助手手势（长按主页键 / 部分机型电源键）召出。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        // 后台唤醒 + 悬浮窗权限
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("后台唤醒（应用外唤醒弹浮层）"), color = scheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = bgWake, onCheckedChange = { bgWake = it; WakeService.setBgWake(context, it) })
        }
        // 锁屏直接唤起：让浮层/助手界面越过锁屏显示（语音对话在锁屏上即可用，不解锁）
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("锁屏页面直接唤起助手"), color = scheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = lockWake, onCheckedChange = { lockWake = it; WakeService.setLockScreenWake(context, it) })
        }
        // 助手背景实时模糊（毛玻璃）——默认开；较耗电/发热，关则仅压暗背景
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("助手背景实时模糊（毛玻璃，较耗电；关则仅压暗）"), color = scheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = bgBlur, onCheckedChange = { bgBlur = it; WakeService.setBgBlur(context, it) })
        }
        // 开了却不模糊≠App 坏了：窗口模糊得系统支持。这台不支持时说清楚，免得用户以为是 bug（正是本次反馈）。
        if (bgBlur && !WakeService.blurSupportedBySystem(context)) {
            Text(tr("这台系统未开启窗口模糊，实时模糊不会生效（只压暗）；在系统「开发者选项」或 ROM 设置里打开窗口模糊后即可。"),
                color = accents.warning, fontSize = 11.sp, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
        }
        // 唤醒应答语：唤醒后先说一句"我在"之类（固定/自定义/AI 生成）
        Text(tr("唤醒应答语（留空=不说）"), color = scheme.onSurface, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
        com.arix.app.ui.XtomField(value = if (greeting == "[AI]") "" else greeting, onValueChange = { greeting = it; WakeService.setWakeGreeting(context, it) }, placeholder = tr("如：我在 / 在呢，怎么了"), modifier = Modifier.fillMaxWidth())
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(tr("改用 AI 按人设生成应答"), color = scheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = greeting == "[AI]", onCheckedChange = { on -> greeting = if (on) "[AI]" else ""; WakeService.setWakeGreeting(context, greeting) })
        }
        Button(onClick = {
            try {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}"))
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                report(tr("请在系统设置里给 Arix 开启「显示在其他应用上层/悬浮窗」"))
            } catch (_: Exception) { report(tr("无法打开悬浮窗权限设置")) }
        }, colors = ButtonDefaults.buttonColors(containerColor = scheme.secondary), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(tr("授予悬浮窗权限（后台弹浮层必需）"), color = scheme.onPrimary, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        // 助理角色的实况诊断：有些 ROM（精简包/无 GMS）整个助理 role 是空的，系统列表里一个候选都没有
        // （连 Google 自己的都没有）。这种机器上任何 App 都设不上，与其让用户对着没反应的按钮反复点，
        // 不如直接说清楚。
        Text(tr(AssistantRole.diagnose(context)), color = if (assistantHeld) accents.success else scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !assistantHeld,
                onClick = {
                    // 优先走 RoleManager 直接弹系统授权框；拿不到（不支持 role / 版本低）才退回设置页让用户手动找
                    val intent = AssistantRole.requestIntent(context)
                    if (intent != null) roleLauncher.launch(intent)
                    else if (!AssistantRole.available(context)) report(tr("这台设备不支持数字助理角色——任何应用都设不上，不是 Arix 的问题。唤醒仍可用（走悬浮窗）。"))
                    else report(if (AssistantRole.fallbackSettings(context)) tr("已打开系统设置，请在「默认应用/数字助手」里选 Arix") else tr("无法打开系统设置"))
                },
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f),
            ) {
                Text(if (assistantHeld) tr("已是默认助手") else tr("设为默认助手"), color = scheme.onPrimary, fontSize = 13.sp)
            }
            Button(onClick = {
                try {
                    context.startActivity(
                        android.content.Intent(context, MainActivity::class.java)
                            .putExtra(WakeService.EXTRA_WAKE, true)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                } catch (_: Exception) {}
            }, colors = ButtonDefaults.buttonColors(containerColor = scheme.secondary), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                Text(tr("手动唤醒"), color = scheme.onSecondary, fontSize = 13.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        // 麦克风增强（可选）：Shizuku ADB 级把录音 appop 强制允许 → 后台持续持麦
        Text(tr("麦克风增强（可选 · Shizuku）"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(tr("默认麦克风走常规「仅使用时」，后台可能被系统掐。用 Shizuku（ADB 级、免 root）把录音权限强制为「始终允许」→ 退到后台也持续持麦。监听时状态栏出现麦克风绿点属正常（诚实归属，避免被小米敏感权限检测判为可疑而回收）。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!ShizukuMic.binderAlive()) { report(tr("Shizuku 未运行：请先安装并启动 Shizuku 应用")); return@Button }
                ShizukuMic.requestPermission { ok -> report(if (ok) tr("Shizuku 已授权") else tr("Shizuku 授权被拒/失败")) }
            }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                Text(tr("Shizuku 授权"), color = scheme.onPrimary, fontSize = 13.sp)
            }
            Button(onClick = {
                report(tr("正在增强麦克风…"))
                scope.launch(Dispatchers.IO) {
                    // "[0] appops set …" 是命令原文回显，只进日志；其余是给人看的结论，弹出来。
                    ShizukuMic.elevateMic(context.packageName) { m ->
                        if (m.startsWith("[")) mainHandler.post { addLog(m) } else report(m)
                    }
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), shape = RoundedCornerShape(14.dp), modifier = Modifier.weight(1f)) {
                Text(tr("增强麦克风"), color = scheme.onPrimary, fontSize = 13.sp)
            }
        }
        Button(onClick = {
            try {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("adb", ShizukuMic.ADB_HINT))
                addLog("已复制 adb 命令：${ShizukuMic.ADB_HINT}（无 Shizuku 时用电脑 adb 或本会话 ! 前缀执行）")
            } catch (_: Exception) { addLog("adb 命令：${ShizukuMic.ADB_HINT}") }
        }, colors = ButtonDefaults.buttonColors(containerColor = scheme.secondary), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(tr("复制 adb 命令（无 Shizuku 时用）"), color = scheme.onSecondary, fontSize = 12.sp)
        }

        Spacer(Modifier.height(16.dp))
        Text(tr("使用说明"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(tr("1. 点击「录入唤醒词」在安静环境说出唤醒词3次\n2. 点击「开始监听」启动后台语音检测\n3. 检测到唤醒词时自动激活对话"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
