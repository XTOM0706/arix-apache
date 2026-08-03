package com.arix.app

import android.content.Context
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.LinearProgressIndicator
import com.arix.app.theme.LocalXtomAccents
import com.arix.app.ui.XtomButton
import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// ============================================================
// 主页更新弹窗 —— 检测到新版本直接在主页弹出，不依赖通知/更新页。
//
// 触发链路：UpdateNotifier.Worker 查到新版本且 App 在前台 → 把 release 塞进 [pending] →
// MainActivity 观察 [pending] 变化弹 [UpdatePromptDialog]。
//
// 三个行为：
//  · 更新：直接复用 ApkInstaller.downloadAndInstall（与更新页同一套下载/签名校验）。
//  · 稍后：记 [UpdatePrefs.postponeUntil]，到点前 Worker/checkNow 都不再弹/通知；
//          也可先选一个时间（明天/3 天后/一周后/两周后）。
//  · 更新内容：release notes 用当前配置的模型 AI 翻译成当前界面语言（自动，可点开时触发）。
// ============================================================
object UpdatePrompt {
    /** 待弹的新版本。非空 = 该弹一次（弹完置空，避免重复）。 */
    val pending = mutableStateOf<UpdateChecker.Release?>(null)

    /** App 是否在前台（UpdateNotifier 据此决定弹窗 or 只发通知）。 */
    @Volatile var foreground = false

    /** 有新版本要弹时调用（App 前台直接弹；后台只发通知由 Worker 自己处理）。 */
    fun offer(release: UpdateChecker.Release) {
        if (pending.value != null) return
        pending.value = release
    }
}

/**
 * 把 release notes 用当前配置的模型翻译成目标语言。返回 null = 没配模型/翻译失败。
 * 失败不阻塞弹窗——notes 原样显示即可。
 */
suspend fun translateReleaseNotes(context: Context, notes: String, toLang: String): String? {
    if (notes.isBlank()) return null
    val cfg = CloudApiConfigManager(context).getActiveByPurpose("chat") ?: return null
    return try {
        val tconf = CloudApiConfig(
            cfg.baseUrl.trimEnd('/'), cfg.apiKey.trim(), cfg.model.trim(),
            cfg.temperature, cfg.topP, cfg.maxTokens,
            cfg.frequencyPenalty, cfg.presencePenalty,
        )
        val langName = I18n.Lang.fromCode(toLang).label
        val sys = "You are a translation engine. Translate the app's release notes below into ${langName}. " +
            "Keep version numbers, technical terms (API, token, proot, etc.) and proper nouns as-is. " +
            "Output only the translation, no explanation, no quotes."
        var acc = ""
        CloudApiClient(tconf).streamChat(
            messages = listOf(ChatMessage("user", notes)),
            systemPrompt = sys,
            enableThinking = 0,
            onReasoningChunk = {},
            onContentChunk = { acc += it },
        )
        acc.trim().ifBlank { null }
    } catch (_: Exception) { null }
}

// ============================================================
// 弹窗本体
// ============================================================
@Composable
fun UpdatePromptDialog(context: Context, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    val scope = rememberCoroutineScope()
    val curLang = com.arix.app.I18n.lang.collectAsState().value.code

    val rel = UpdatePrompt.pending.value ?: return
    val current = remember { UpdateChecker.currentVersion(context) }

    // 更新内容：默认显示原文；非中文界面给出「翻译」按钮，点击才调用 AI 翻译（用户选择，不自动花 token）。
    // key=rel.tag：pending 被新版本覆盖时译文/翻译中状态随旧版本一起作废，不残留。
    var translated by remember(rel.tag) { mutableStateOf<String?>(null) }
    var translating by remember(rel.tag) { mutableStateOf(false) }
    val showTranslated = translated != null
    fun doTranslate() {
        if (translated != null) { translated = null; return }   // 再点一下切回原文
        if (rel.notes.isBlank()) return
        translating = true
        scope.launch {
            val r = translateReleaseNotes(context, rel.notes, curLang)
            translated = r
            translating = false
        }
    }

    // 下载/安装状态
    var installing by remember { mutableStateOf(false) }
    var installProgress by remember { mutableStateOf(0f) }
    var installError by remember { mutableStateOf<String?>(null) }

    // 「稍后」时间选择
    var showPostponePicker by remember { mutableStateOf(false) }

    // 稍后选项：明天 / 3 天后 / 一周后 / 两周后（当前语言下显示）
    val postponeOptions = remember {
        listOf(
            tr("明天") to 24L * 3600 * 1000,
            tr("3 天后") to 3L * 24 * 3600 * 1000,
            tr("一周后") to 7L * 24 * 3600 * 1000,
            tr("两周后") to 14L * 24 * 3600 * 1000,
        )
    }

    fun doInstall() {
        val apk = rel.apkUrl ?: return
        installing = true; installProgress = 0f; installError = null
        // 用户点「更新」= 这个版本处理过了，装完不再提醒（markNotified）。
        // 注意：notify 后台路径已 mark；前台弹窗这里补 mark，且清掉「稍后」冷却。
        UpdateNotifier.markNotified(context, rel.tag)
        UpdatePrefs.clearPostpone(context)
        scope.launch {
            try {
                val err = com.arix.tool.ApkInstaller.downloadAndInstall(context, apk, "arix-update.apk") { p -> installProgress = p }
                installing = false
                installError = err
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                installing = false
                installError = e.message
            }
        }
    }

    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudDownload, null, tint = accents.info, modifier = Modifier.width(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("发现新版本 %s").format(rel.tag), color = scheme.onSurface, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    String.format(tr("当前 %s → %s%s"), current, rel.tag,
                        if (rel.published.isNotBlank()) "  ${rel.published}" else ""),
                    color = scheme.onSurfaceVariant, fontSize = 11.sp,
                )
                if (rel.prerelease) {
                    Spacer(Modifier.height(4.dp))
                    Text(tr("这是开发快照（prerelease），不是稳定版。"), color = accents.warning, fontSize = 11.sp)
                }
                if (rel.notes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    // 更新说明支持 markdown + 图片（屏幕内渲染，Coil 异步加载即可）
                    MarkdownText(
                        if (translating) tr("翻译中…")
                        else if (showTranslated) (translated ?: rel.notes)
                        else rel.notes,
                        color = scheme.onSurface, fontSize = 12.sp,
                    )
                    // 非中文界面 + 有原文 → 给「翻译」按钮，用户选择要不要翻（不自动花 token）
                    if (curLang != "zh" && rel.notes.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        TextButton(
                            enabled = !translating,
                            onClick = { doTranslate() },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(
                            if (translating) tr("翻译中…")
                            else if (showTranslated) tr("显示原文")
                            else tr("翻译成当前语言"),
                            color = scheme.primary, fontSize = 12.sp,
                        ) }
                    }
                }

                if (installing) {
                    Spacer(Modifier.height(10.dp))
                    if (installProgress < 0f) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(progress = { installProgress }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (installProgress < 0f) tr("下载中…") else String.format(tr("下载中… %d%%"), (installProgress * 100).toInt()),
                        color = scheme.onSurfaceVariant, fontSize = 11.sp,
                    )
                }
                installError?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = scheme.error, fontSize = 11.sp)
                }

                if (showPostponePicker) {
                    Spacer(Modifier.height(10.dp))
                    Text(tr("稍后提醒我："), color = scheme.onSurface, fontSize = 12.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium)
                    Spacer(Modifier.height(4.dp))
                    postponeOptions.forEach { (label, ms) ->
                        TextButton(
                            onClick = {
                                UpdatePrefs.setPostponeUntil(context, System.currentTimeMillis() + ms)
                                onDismiss()
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) { Text(label, color = scheme.primary, fontSize = 13.sp) }
                    }
                    Spacer(Modifier.height(2.dp))
                    TextButton(
                        onClick = {
                            UpdatePrefs.setPostponeUntil(context, System.currentTimeMillis() + 24L * 3600 * 1000)
                            onDismiss()
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(tr("下次打开再说"), color = scheme.onSurfaceVariant, fontSize = 13.sp) }
                }
            }
        },
        confirmButton = {
            if (showPostponePicker) {
                TextButton(onClick = { showPostponePicker = false }) { Text(tr("返回"), color = scheme.onSurfaceVariant) }
            } else {
                TextButton(
                    enabled = rel.apkUrl != null && !installing,
                    onClick = { doInstall() },
                ) { Text(
                    if (rel.apkSize > 0) String.format(tr("更新（%.1f MB）"), rel.apkSize / 1048576.0)
                    else tr("更新"),
                    color = scheme.primary,
                ) }
            }
        },
        dismissButton = {
            if (showPostponePicker) {
                TextButton(onClick = { showPostponePicker = false; onDismiss() }) { Text(tr("取消"), color = scheme.onSurfaceVariant) }
            } else {
                TextButton(onClick = { showPostponePicker = true }) {
                    Icon(Icons.Outlined.Schedule, null, tint = scheme.onSurfaceVariant, modifier = Modifier.width(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("稍后"), color = scheme.onSurfaceVariant)
                }
            }
        },
        containerColor = scheme.surface,
        shape = MaterialTheme.shapes.extraLarge,
    )
}
