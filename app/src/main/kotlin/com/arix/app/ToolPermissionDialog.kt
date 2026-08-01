package com.arix.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.arix.app.theme.LocalXtomAccents
import com.arix.tool.AndroidPermissionLevel
import com.arix.tool.ToolCaller
import com.arix.tool.ToolPermissionManager
import kotlinx.coroutines.launch

/**
 * 工具权限请求框。两个外壳、一份内容：
 * - [ToolPermissionDialog]：聊天页用的真 AlertDialog。
 * - [ToolPermissionPanel]：后台悬浮窗（[PermissionOverlayHost]）用的内联面板。
 *
 * 内容各写一份的话，改了一处忘了另一处，用户在后台看到的会是另一个框。
 */
@Composable
fun ToolPermissionDialog(req: ToolPermissionManager.PendingRequest) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        // 必须显式选一个，点框外/按返回一概不算数。
        // 这是**授权**框：手表圆屏上边缘窄、误触率高，一蹭框外就没了——用户根本没看清就把这事「决定」了，
        // 而 AI 那边收到的是「拒绝」，它会以为用户真不同意，转头换招或放弃。默默替用户做决定，两头都错。
        // 想拒绝有「拒绝」按钮，不缺这一条路。
        properties = DialogProperties(
            dismissOnClickOutside = false,
            dismissOnBackPress = false,
        ),
        onDismissRequest = { },   // 上面已禁掉外部关闭；这里留空，绝不替用户默默判「拒绝」
        title = { PermTitle(req) },
        text = { PermBody(req) },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                PermAllowButtons(req)
            }
        },
        dismissButton = {
            Column(horizontalAlignment = Alignment.Start) {
                PermDenyButtons(req)
            }
        },
        containerColor = scheme.surface, shape = MaterialTheme.shapes.extraLarge
    )
}

/**
 * 悬浮窗版：**不能**用 AlertDialog。Compose 的 Dialog 会另开一个 window，而悬浮窗这边只有
 * application context（没有 Activity 的 window token）→ 加窗直接 BadTokenException。所以内容内联画。
 */
@Composable
fun ToolPermissionPanel(req: ToolPermissionManager.PendingRequest) {
    val scheme = MaterialTheme.colorScheme
    Box(modifier = Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = scheme.surface,
            tonalElevation = 6.dp,
            shadowElevation = 6.dp,
            modifier = Modifier.widthIn(max = 400.dp),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                PermTitle(req)
                Spacer(Modifier.height(10.dp))
                // 屏幕小、参数长时也得能看全、能够到按钮
                Column(modifier = Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    PermBody(req)
                }
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(horizontalAlignment = Alignment.Start) { PermDenyButtons(req) }
                    Column(horizontalAlignment = Alignment.End) { PermAllowButtons(req) }
                }
            }
        }
    }
}

@Composable
private fun PermTitle(req: ToolPermissionManager.PendingRequest) {
    val accents = LocalXtomAccents.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 插件在要权限时不能说成「AI 想…」——用户会以为是自己让 AI 干的，糊里糊涂就点了同意
        val isAi = req.caller is ToolCaller.Ai
        Text(if (isAi) tr("AI 想执行一个操作") else tr("有程序想执行一个操作"),
            color = accents.warning, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun PermBody(req: ToolPermissionManager.PendingRequest) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // shell/代码类工具：给一个「这条是干啥」按钮，用独立的「命令翻译」模型解释给用户听
    val canExplain = req.toolName == "shell" || req.toolName == "code_runner"
    var explain by remember(req) { mutableStateOf<String?>(null) }
    var explaining by remember(req) { mutableStateOf(false) }

    // 插件和外部 MCP 客户端的代码不是用户写的，「模型自动审批」不替它们放行（见 ToolPermissionManager.askGate），
    // 所以开着自动审批也照样弹这个框。不说一句，用户只会觉得开关失灵。
    val thirdParty = req.caller is ToolCaller.Plugin || req.caller is ToolCaller.Mcp

    Column {
        // 非 AI 发起时，第一眼就得看到是谁在要——这条比「想干什么」还重要
        if (req.caller !is ToolCaller.Ai) {
            Text(tr("请求方：") + req.caller.label, color = accents.warning, style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = if (thirdParty) 0.dp else 6.dp))
        }
        // 圆屏放不下解释，一句带过就行；想看细节的去权限页
        if (thirdParty) {
            Text(tr("（第三方调用不走自动审批）"), color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(bottom = 6.dp))
        }
        // 主角：人话说明这一步到底想干什么
        Text(req.intent, color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
        // 三档配色：普通级不着色（别把「查个天气」渲染成警报）；隐私级用 warning——它会是最常弹的一档，
        // 每次都刷成 error 红只会让人练出「无脑点允许」的手；真高危（无障碍以上）才用 error。
        Text(
            req.riskNote,
            color = when (req.level) {
                AndroidPermissionLevel.STANDARD -> scheme.onSurfaceVariant
                AndroidPermissionLevel.PRIVATE -> accents.warning
                else -> scheme.error
            },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
        )
        Text(tr("工具：") + "${req.toolName} · ${req.level.label}", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 6.dp))
        if (req.argsPreview.isNotBlank()) Text(req.argsPreview, color = scheme.onSurfaceVariant.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 2.dp))
        if (canExplain) {
            Spacer(Modifier.height(8.dp))
            when {
                explain == CommandExplainer.NO_MODEL -> {
                    Text(tr("还没配「命令翻译」模型"), color = accents.warning, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        ToolPermissionManager.resolve(ToolPermissionManager.Decision.DENY_ONCE)
                        ConfigJump.request("translate")
                    }, contentPadding = PaddingValues(0.dp)) { Text(tr("去配置 →"), color = scheme.primary, style = MaterialTheme.typography.bodySmall) }
                }
                explain != null -> Text(explain!!, color = scheme.onSurface, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
                else -> TextButton(onClick = {
                    explaining = true
                    scope.launch {
                        val r = CommandExplainer.explain(context, req.argsPreview.ifBlank { req.intent })
                        explaining = false; explain = r
                    }
                }, enabled = !explaining, contentPadding = PaddingValues(0.dp)) {
                    Icon(Icons.Outlined.Search, contentDescription = null, tint = accents.info, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(if (explaining) tr("解释中…") else tr("这条命令是干啥的？"), color = accents.info, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/**
 * 「始终」两个按钮只在**调用方身份记得住**时才出现（见 [ToolCaller.canRememberAlways]）：
 * 没设访问令牌的 MCP 客户端，身份只是「远端 IP:端口」，端口是它自己挑的——记下来的授权
 * 日后会被任何顶着同一个 id 的人继承。给个点了不算数的按钮比不给更糟。
 */
@Composable
private fun PermAllowButtons(req: ToolPermissionManager.PendingRequest) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    TextButton(onClick = { ToolPermissionManager.resolve(ToolPermissionManager.Decision.ALLOW_ONCE) }) { Text(tr("本次允许"), color = accents.success) }
    if (req.caller.canRememberAlways)
        TextButton(onClick = { ToolPermissionManager.resolve(ToolPermissionManager.Decision.ALWAYS_ALLOW) }) { Text(tr("始终允许此工具"), color = scheme.primary) }
}

@Composable
private fun PermDenyButtons(req: ToolPermissionManager.PendingRequest) {
    val scheme = MaterialTheme.colorScheme
    TextButton(onClick = { ToolPermissionManager.resolve(ToolPermissionManager.Decision.DENY_ONCE) }) { Text(tr("拒绝"), color = scheme.onSurfaceVariant) }
    if (req.caller.canRememberAlways)
        TextButton(onClick = { ToolPermissionManager.resolve(ToolPermissionManager.Decision.ALWAYS_DENY) }) { Text(tr("始终禁止"), color = scheme.error) }
}
