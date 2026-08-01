package com.arix.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.arix.app.theme.LocalXtomAccents
import com.arix.app.ui.PageScaffold

@Composable fun PermissionsPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    // 数字助理的 role 请求框**必须 startActivityForResult 起**：PermissionController 的 RequestRoleActivity
    // 靠 getCallingPackage() 认调用方，普通 startActivity 起它拿到 null → 直接 finish，一声不吭
    // ＝用户看到的「点了没反应」。所以走 launcher，不能用 context.startActivity。
    var roleDenied by remember { mutableStateOf(false) }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        // 实测(API30 模拟器)：助理 role 的请求框**根本不渲染**就返回 CANCELED——ROLE_ASSISTANT 不是
        // 「可请求」的 role，PermissionController 的 RequestRoleActivity 拿到它直接 finish。
        // 所以 CANCELED 不是「用户拒绝」，是「这条路走不通」→ 直接把人送到真正能设的系统设置页，
        // 而不是让他对着一个永远弹不出来的框反复点（这正是「点了没用」的成因）。
        if (r.resultCode != android.app.Activity.RESULT_OK && !AssistantRole.held(context)) {
            roleDenied = true
            AssistantRole.fallbackSettings(context)
        }
    }
    val sdk = Build.VERSION.SDK_INT

    // 从系统设置页返回时刷新授权状态（特殊权限走系统页，回来要重新读一次，否则一直显示"未授权"）
    var tick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            // 顺带把工具表的能力缓存也作废：刚在设置页开完无障碍/使用情况，30s TTL 会让工具表慢半拍
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) { tick++; com.arix.tool.CapabilityProbe.invalidate() }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    fun openAppDetails() = try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {}
    fun start(intent: Intent, fallback: () -> Unit = { openAppDetails() }) = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) { fallback() }
    fun has(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    // 分组：按“这项权限是干嘛用的”归类，让用户能按用途扫读，而不是对着 14 行平铺列表逐条猜
    val GROUP_BASIC = "基础"
    val GROUP_SENSE = "感知与陪伴"
    val GROUP_MEDIA = "文件与媒体"
    val GROUP_ADVANCED = "高级能力"
    val GROUP_OTHER = "其它"
    val groupOrder: List<Pair<String, ImageVector>> = listOf(
        GROUP_BASIC to Icons.Outlined.Mic,
        GROUP_SENSE to Icons.Outlined.Sensors,
        GROUP_MEDIA to Icons.Outlined.Folder,
        GROUP_ADVANCED to Icons.Outlined.Tune,
        GROUP_OTHER to Icons.Outlined.MoreHoriz,
    )

    // special=true → 走系统设置页的特殊权限（所有文件/电池/悬浮窗/系统设置），不是运行时弹窗
    // stateless=true → 这项没有“已授权/未授权”状态（应用详情只是个入口），只显示动作
    data class PermItem(
        val group: String,
        val label: String,
        val desc: String,
        val special: Boolean,
        val checkGranted: () -> Boolean,
        val request: () -> Unit,
        val stateless: Boolean = false,
    )

    val permissions = listOf(
        PermItem(GROUP_BASIC, "录音", "语音唤醒与对话必需", false,
            { has(Manifest.permission.RECORD_AUDIO) },
            { permLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }),
        PermItem(GROUP_MEDIA, "所有文件访问", "AI 文件工具在私有目录外读写文件（Android 11+ 走系统页开启）", true,
            { if (sdk >= Build.VERSION_CODES.R) android.os.Environment.isExternalStorageManager()
              else has(Manifest.permission.WRITE_EXTERNAL_STORAGE) },
            { if (sdk >= Build.VERSION_CODES.R)
                start(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")),
                      fallback = { start(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) })
              else permLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)) }),
        PermItem(GROUP_MEDIA, "相册 / 媒体", "读取图片、视频用于识图与解析", false,
            { has(if (sdk >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) },
            { permLauncher.launch(if (sdk >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO) else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)) }),
        PermItem(GROUP_BASIC, "电池优化豁免", "手表后台唤醒词监听不被系统杀（强烈建议开）", true,
            { (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(context.packageName) },
            { start(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }),
        PermItem(GROUP_ADVANCED, "悬浮窗", "语音助手悬浮入口 / 全屏提醒 / AI 打开别的应用后，授权框才能弹在它上面", true,
            { Settings.canDrawOverlays(context) },
            { start(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }),
        PermItem(GROUP_MEDIA, "相机", "拍照识图", false,
            { has(Manifest.permission.CAMERA) },
            { permLauncher.launch(arrayOf(Manifest.permission.CAMERA)) }),
        PermItem(GROUP_SENSE, "位置", "天气 / 附近信息", false,
            { has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION) },
            { permLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }),
        PermItem(GROUP_SENSE, "通讯录", "按名字拨号 / 发消息", false,
            { has(Manifest.permission.READ_CONTACTS) },
            { permLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }),
        PermItem(GROUP_SENSE, "电话状态", "来电感知", false,
            { has(Manifest.permission.READ_PHONE_STATE) },
            { permLauncher.launch(arrayOf(Manifest.permission.READ_PHONE_STATE)) }),
        PermItem(GROUP_SENSE, "日历", "AI 直接读日程 / 建日程", false,
            { has(Manifest.permission.READ_CALENDAR) && has(Manifest.permission.WRITE_CALENDAR) },
            { permLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }),
        PermItem(GROUP_SENSE, "通知感知", "让 AI 知道手机上收到的通知（微信/短信/日程…）", true,
            { NotificationAwarenessPrefs.hasAccess(context) },
            { start(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }),
        PermItem(GROUP_SENSE, "使用情况访问", "让 AI 知道你用了哪些 App、屏幕时间（体贴提醒用）", true,
            { com.arix.tool.UsageStatsTool.hasAccess(context) },
            { start(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }),
        PermItem(GROUP_ADVANCED, "修改系统设置", "调节亮度 / 音量等", true,
            { try { Settings.System.canWrite(context) } catch (_: Exception) { false } },
            { start(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))) }),
        // 无障碍：AI 的界面自动化(ui_control 点按/滑动/读屏)与技能录制全靠它，没这项那些工具直接不可用
        PermItem(GROUP_ADVANCED, "无障碍（界面自动化）", "让 AI 替你点按钮 / 滑动 / 读屏幕内容，技能录制也用它", true,
            { XtomAccessibilityService.instance != null },
            { start(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
        // 数字助理：走 RoleManager 直接弹系统授权框，不再盲跳设置页让用户自己找。
        // 部分 ROM(精简包/无 GMS)整个助理 role 是空的 → available=false，此时说清楚而不是让用户干点。
        PermItem(GROUP_ADVANCED, "默认数字助理", "设为默认后可用系统助手手势召出（长按主页键等），唤醒命中由系统托管弹会话，更稳、免悬浮窗权限", true,
            { AssistantRole.held(context) },
            {
                val i = AssistantRole.requestIntent(context)
                // 必须经 launcher（startActivityForResult）；别加 FLAG_ACTIVITY_NEW_TASK——
                // 那会让它变成新任务栈，同样拿不到 calling package，白弹一场。
                if (i != null) runCatching { roleLauncher.launch(i) }
                    .onFailure { AssistantRole.fallbackSettings(context) }   // 起不来就退回设置页，别静默
                else AssistantRole.fallbackSettings(context)
            }),
        PermItem(GROUP_OTHER, "应用详情", "手动到系统设置逐项调权限", true,
            { false },
            { openAppDetails() }, stateless = true)
    )

    PageScaffold {
        Text(tr("系统权限管理"), color = scheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(tr("按用途分组；未授权的项右侧有开启入口。部分权限需跳到系统设置里手动开。"), color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))

        // 当前能力等级：把散在各处的"我现在到底能干到哪一层"收成一句话摆在最上面。
        // tick 变化时重算（从系统设置页回来要立刻反映），判定全是只读的、不会弹任何授权框。
        val tier = remember(tick) { CapabilityTier.detect(context) }
        Column(
            modifier = Modifier.fillMaxWidth()
                .background(scheme.surfaceContainerHighest, RoundedCornerShape(14.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tune, null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("L${tier.level} · ${tr(tier.label)}", color = scheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
            Text(tr(tier.unlocked), color = scheme.onSurface, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            tier.nextStep?.let {
                Text(tr("再往上：") + tr(it), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Spacer(Modifier.height(10.dp))

        groupOrder.forEach { (groupName, groupIcon) ->
            val rows = permissions.filter { it.group == groupName }
            if (rows.isEmpty()) return@forEach

            // 分组标题：小图标 + 粗体小标签，都用 primary，坐在卡片“外面”（卡片只装内容）
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp, start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(groupIcon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                Text(tr(groupName), color = scheme.primary, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }

            val cardShape = MaterialTheme.shapes.large
            Column(
                Modifier.fillMaxWidth().clip(cardShape).background(scheme.surfaceContainer)
                    .border(1.dp, scheme.outlineVariant, cardShape)
            ) {
                rows.forEachIndexed { idx, item ->
                    // ON_RESUME 的 tick 变化会让这里重算——从系统设置页回来时状态才不会一直卡在“未授权”
                    val granted = remember(tick, item.label) { try { item.checkGranted() } catch (_: Exception) { false } }
                    // 已授权的行不再可点：点了也只是再跑一次请求，没意义；要改就走「应用详情」
                    val actionable = item.stateless || !granted

                    if (idx > 0) HorizontalDivider(thickness = 0.5.dp, color = scheme.outlineVariant.copy(alpha = 0.4f))

                    var rowMod = Modifier.fillMaxWidth().heightIn(min = 56.dp)
                    if (actionable) rowMod = rowMod.clickable { item.request() }
                    Row(
                        modifier = rowMod.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f).padding(end = 8.dp)) {
                            Text(tr(item.label), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                            // “为什么要这个权限”——绝不设 maxLines：33 种语言下译文比中文长 2~3 倍，
                            // 截断掉的正好是解释理由的那半句。让它自然换行。
                            Text(tr(item.desc), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            if (item.special && !granted && !item.stateless)
                                Text(tr("需到系统设置开启"), color = accents.warning, style = MaterialTheme.typography.labelSmall)
                            // 系统框弹了却没设成（有的 ROM 把助理 role 声明成不可请求，一弹就 CANCELED）→
                            // 给一条手动出路，别让用户对着没反应的行反复点。
                            if (item.label == "默认数字助理" && roleDenied && !granted) {
                                Text(tr(AssistantRole.diagnose(context)), color = accents.warning, style = MaterialTheme.typography.labelSmall)
                                Text(tr("→ 去系统设置手动选"), color = scheme.primary, style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.clickable { AssistantRole.fallbackSettings(context) }.padding(top = 2.dp))
                            }
                        }

                        Column(
                            modifier = Modifier.widthIn(min = 64.dp),
                            horizontalAlignment = Alignment.End,
                            verticalArrangement = Arrangement.spacedBy(3.dp),
                        ) {
                            if (!item.stateless) {
                                // 状态用「形状 + 文字 + 色调」三重编码：只靠颜色（绿点/红点）对色盲用户等于没有。
                                // 勾 vs 叉本身就能区分，颜色只是锦上添花。
                                val stateIcon = if (granted) Icons.Outlined.CheckCircle else Icons.Outlined.RemoveCircleOutline
                                val stateTint = if (granted) accents.success else scheme.onSurfaceVariant
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Icon(stateIcon, contentDescription = null, tint = stateTint, modifier = Modifier.size(13.dp))
                                    Text(if (granted) tr("已授权") else tr("未授权"), color = stateTint, style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            // 未授权 / 纯入口的行给一个看得见的动作提示：整行会跳系统页或弹请求，
                            // 光有状态文字却偷偷可点是“看着像只读、点了却发意图”的意外。
                            if (actionable) {
                                Row(
                                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                        .background(scheme.primary.copy(alpha = 0.12f))
                                        .padding(horizontal = 6.dp, vertical = 3.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                                ) {
                                    Text(
                                        if (item.stateless) tr("打开") else if (item.special) tr("去开启") else tr("授权"),
                                        color = scheme.primary,
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                    )
                                    Icon(Icons.AutoMirrored.Outlined.ArrowForward, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(11.dp))
                                }
                            }
                        }
                    }
                }
            }
        }

        // ---- AI 工具授权策略 ----
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = scheme.surfaceContainerHighest)
        Text(tr("AI 工具授权策略"), color = scheme.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(tr("控制 AI 调用工具是否需要你确认。高危工具（shell/触屏/风控）与碰你个人数据的工具（通讯录/短信/剪贴板…）默认询问。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))

        // 模型自动审批：ASK 时先让一个无上下文的小模型看一眼危不危险，安全的自动放行、可疑的照旧弹给你。
        // 只对**普通级**工具生效；隐私/无障碍/Shizuku/设备管理员/Root 级永远问你本人（见 ToolApprovalJudge）。
        var autoApprove by remember { mutableStateOf(com.arix.app.ToolApprovalJudge.isEnabled(context)) }
        com.arix.app.ui.SettingsToggle(
            icon = Icons.Outlined.Tune,
            title = tr("模型自动审批"),
            subtitle = tr("「每次询问」的工具，先由一个独立的小模型判断这次调用危不危险：明显安全的自动放行，可疑的照旧弹窗问你。只对普通级工具生效，个人数据（隐私级）与高危工具（无障碍/Shizuku/设备管理员/Root）永远问你本人；执行命令、改删文件、替你对外发消息、装扩展这几类也一律不自动放行；判断失败同样回来问你。"),
            checked = autoApprove,
            onCheckedChange = { autoApprove = it; com.arix.app.ToolApprovalJudge.setEnabled(context, it) },
        )
        if (autoApprove) {
            // 只有 AI 和用户自己的脚本才够格走自动审批（见 ToolPermissionManager.askGate）。不写出来的话，
            // 开了开关的人被插件/MCP 弹一脸框，只会以为功能坏了，然后去关掉这个本来有用的开关。
            com.arix.app.ui.SettingsHint(tr("插件与外部 MCP 客户端不适用——不是你写的代码，每次都要你亲自点头。"))
            com.arix.app.ui.SettingsHint(tr("审批模型在「模型配置 → 权限审批」里单独指定，用便宜快的小模型即可；没指定就用对话模型。自动放行的每一次都会记进活动中心，可事后核对。"))
            androidx.compose.material3.TextButton(
                onClick = { ConfigJump.request(com.arix.app.ToolApprovalJudge.PURPOSE) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp),
            ) { Text(tr("去配置审批模型 →"), color = scheme.primary, fontSize = 12.sp) }
        }

        // 调用前教训提示：把「上回这个工具这么干不行」接在**那个工具自己的描述**后面，
        // 于是模型是在决定要不要调它的那一刻读到的（见 ToolManager.withLessonHint）。
        var lessonHints by remember { mutableStateOf(com.arix.tool.ToolManager.lessonHintsEnabled(context)) }
        com.arix.app.ui.SettingsToggle(
            icon = Icons.Outlined.Lightbulb,
            title = tr("调用前提醒踩过的坑"),
            subtitle = tr("某个工具被拒过、参数写错过、内容塞太多被截断过，下次就把这句教训接在这个工具的说明后面——模型在决定要不要用它的那一刻就看得到，而不是等调完了才知道。只对真踩过坑的工具生效，没踩过的不多花一个字。"),
            checked = lessonHints,
            onCheckedChange = { lessonHints = it; com.arix.tool.ToolManager.setLessonHints(context, it) },
        )

        var permTick by remember { mutableStateOf(0) }
        // 主开关：标准级工具默认策略（允许 ↔ 询问）
        val master = remember(permTick) { com.arix.tool.ToolPermissionManager.masterMode() }
        Text(tr("普通工具默认策略（搜索/记忆/时间等标准级；高危工具始终按各自设置）"), color = scheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(vertical = 4.dp))
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            listOf(
                Triple("自动允许", com.arix.tool.ToolPermission.ALLOW, scheme.primary),
                Triple("每次询问", com.arix.tool.ToolPermission.ASK, scheme.secondary),
                Triple("禁止", com.arix.tool.ToolPermission.FORBID, scheme.error),
            ).forEach { (label, mode, col) ->
                Box(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).background(if (master == mode) col else scheme.surfaceContainerHighest).clickable { com.arix.tool.ToolPermissionManager.setMasterMode(mode); permTick++ }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                    Text(tr(label), color = if (master == mode) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = if (master == mode) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }

        // 每个已注册工具的策略（点击循环：跟随默认→允许→询问→禁止→跟随默认）
        val allTools = remember(permTick) { com.arix.tool.ToolManager.getAll().sortedByDescending { it.permissionLevel.ordinal } }

        // ---- 个人数据（隐私级）：单独一组 + 一键全放/全禁 ----
        //
        // 为什么值得单开一组：这几个工具碰的是通讯录/短信/日历/通知/剪贴板/使用情况/整屏截图/身体传感器，
        // 以及「直接拨出去」。它们**不需要**无障碍或 Shizuku，所以从前都落在普通级 = 跟随默认 ALLOW 的主开关
        // = 新装的机器上一次都不问。现在它们是 AndroidPermissionLevel.PRIVATE，默认「每次询问」。
        // 一键两个钮是给两类人各一条快路：信得过的人一次全放（省得被弹八次），谨慎的人一次全禁。
        val privateTools = remember(allTools) {
            allTools.filter { it.permissionLevel == com.arix.tool.AndroidPermissionLevel.PRIVATE }
        }
        if (privateTools.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(tr("个人数据"), color = accents.warning, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("  ${privateTools.size}", color = scheme.onSurfaceVariant, fontSize = 11.sp)
            }
            Text(
                tr("这几个碰的是你本人的东西（通讯录 / 短信 / 日历 / 通知 / 剪贴板 / 使用情况 / 整屏截图 / 身体数据 / 直接拨号）。默认每次询问，且不交给模型自动审批。"),
                color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
            )
            // 这一组当前是不是**整齐**处于某一档（三个都不整齐时一个钮都不高亮）。
            // 一次 prefs 扫描算完，别在 forEach 里逐钮重读——那是每次重组 3×N 次。
            val privateUniform = remember(permTick, privateTools) {
                privateTools.map { com.arix.tool.ToolPermissionManager.override(it.name) }.distinct().singleOrNull()
            }
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple("全部允许", com.arix.tool.ToolPermission.ALLOW, scheme.primary),
                    Triple("全部询问", com.arix.tool.ToolPermission.ASK, scheme.secondary),
                    Triple("全部禁止", com.arix.tool.ToolPermission.FORBID, scheme.error),
                ).forEach { (label, mode, col) ->
                    // 全部落成显式 override（不是「清掉 override 靠默认」）：这一组的默认就是 ASK，
                    // 「全部询问」若做成清 override，用户看到的仍是「跟随默认」，点了像没反应。
                    val allSet = privateUniform == mode
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(10.dp))
                            .background(if (allSet) col else scheme.surfaceContainerHighest)
                            .clickable {
                                privateTools.forEach { com.arix.tool.ToolPermissionManager.setOverride(it.name, mode) }
                                permTick++
                            }.padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tr(label), color = if (allSet) scheme.onPrimary else scheme.onSurfaceVariant,
                            fontSize = 12.sp, fontWeight = if (allSet) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                }
            }
        }

        allTools.forEach { tool ->
            val ov = remember(permTick, tool.name) { com.arix.tool.ToolPermissionManager.override(tool.name) }
            val eff = remember(permTick, tool.name) { com.arix.tool.ToolPermissionManager.effective(tool.name, tool.permissionLevel) }
            Row(Modifier.fillMaxWidth().padding(vertical = 1.5.dp)
                .clip(RoundedCornerShape(12.dp)).background(scheme.surfaceContainerLowest)
                .clickable {
                    val next = when (ov) {
                        null -> com.arix.tool.ToolPermission.ALLOW
                        com.arix.tool.ToolPermission.ALLOW -> com.arix.tool.ToolPermission.ASK
                        com.arix.tool.ToolPermission.ASK -> com.arix.tool.ToolPermission.FORBID
                        com.arix.tool.ToolPermission.FORBID -> null
                    }
                    com.arix.tool.ToolPermissionManager.setOverride(tool.name, next); permTick++
                }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tool.name, color = scheme.onSurface, fontSize = 13.sp)
                        if (tool.permissionLevel != com.arix.tool.AndroidPermissionLevel.STANDARD)
                            Text(tr("  · %s级").format(tool.permissionLevel.label), color = accents.warning, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                    Text(tool.description.take(48), color = scheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (ov == null) Text(tr("跟随默认"), color = scheme.onSurfaceVariant.copy(alpha = 0.7f), fontSize = 9.sp)
                }
                Text(when (eff) {
                    com.arix.tool.ToolPermission.ALLOW -> tr("允许")
                    com.arix.tool.ToolPermission.ASK -> tr("询问")
                    com.arix.tool.ToolPermission.FORBID -> tr("禁止")
                }, color = when (eff) {
                    com.arix.tool.ToolPermission.ALLOW -> scheme.primary
                    com.arix.tool.ToolPermission.ASK -> scheme.secondary
                    com.arix.tool.ToolPermission.FORBID -> scheme.error
                }, fontSize = 12.sp)
            }
        }
        if (allTools.isEmpty()) Text(tr("暂无已注册工具（先在本地包页启用工具包）"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(20.dp))
    }
}

/**
 * i18n 收集锚点 —— 永远不会被调用，只为让 `tools/i18n_wrap.py` 扫得到这些串。
 *
 * 本页的分组名 / 权限名 / 说明 / 策略档位走的是「延迟 tr()」：字面量以中文原串存进
 * [PermItem]、GROUP_* 常量和策略 Triple，渲染时才 `tr(item.label)` / `tr(item.desc)` / `tr(label)`。
 * 收集脚本只认 `tr() 里直接写中文字面量` 这一种写法，扫不到那些裸字面量，重跑一次就会把它们从
 * i18n/i18n_table.json 里删掉——译文还在文件里，key 没了，全体回落中文。
 * ⚠️ 改上面的分组/权限文案，这里必须同步改。
 */
@Suppress("unused")
private fun permissionsI18nKeys() = listOf(
    // 分组
    tr("基础"), tr("感知与陪伴"), tr("文件与媒体"), tr("高级能力"), tr("其它"),
    // 权限名（部分已在别处出现过，这里一并登记，改文案时只看这一处）
    tr("录音"), tr("所有文件访问"), tr("相册 / 媒体"), tr("电池优化豁免"), tr("悬浮窗"),
    tr("相机"), tr("位置"), tr("通讯录"), tr("电话状态"), tr("日历"), tr("通知感知"),
    tr("使用情况访问"), tr("修改系统设置"), tr("无障碍（界面自动化）"), tr("默认数字助理"), tr("应用详情"),
    // 说明
    tr("语音唤醒与对话必需"),
    tr("AI 文件工具在私有目录外读写文件（Android 11+ 走系统页开启）"),
    tr("读取图片、视频用于识图与解析"),
    tr("手表后台唤醒词监听不被系统杀（强烈建议开）"),
    tr("语音助手悬浮入口 / 全屏提醒 / AI 打开别的应用后，授权框才能弹在它上面"),
    tr("拍照识图"),
    tr("天气 / 附近信息"),
    tr("按名字拨号 / 发消息"),
    tr("来电感知"),
    tr("AI 直接读日程 / 建日程"),
    tr("让 AI 知道手机上收到的通知（微信/短信/日程…）"),
    tr("让 AI 知道你用了哪些 App、屏幕时间（体贴提醒用）"),
    tr("调节亮度 / 音量等"),
    tr("让 AI 替你点按钮 / 滑动 / 读屏幕内容，技能录制也用它"),
    tr("设为默认后可用系统助手手势召出（长按主页键等），唤醒命中由系统托管弹会话，更稳、免悬浮窗权限"),
    tr("手动到系统设置逐项调权限"),
    // 工具授权策略三档
    tr("自动允许"), tr("每次询问"), tr("禁止"),
    // 个人数据（隐私级）那一组的一键三档，同样是延迟 tr()
    tr("全部允许"), tr("全部询问"), tr("全部禁止"),
)
