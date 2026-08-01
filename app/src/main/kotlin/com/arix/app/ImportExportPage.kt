 package com.arix.app
 
 import android.Manifest
 import android.content.pm.PackageManager
 import android.media.AudioFormat
 import android.media.AudioRecord
 import android.media.MediaRecorder
 import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
 import androidx.activity.ComponentActivity
 import androidx.activity.compose.setContent
 import androidx.activity.compose.rememberLauncherForActivityResult
 import androidx.activity.result.contract.ActivityResultContracts
 import androidx.compose.animation.core.animateFloatAsState
 import androidx.compose.foundation.ExperimentalFoundationApi
 import androidx.compose.foundation.background
 import androidx.compose.foundation.clickable
 import androidx.compose.foundation.combinedClickable
 import androidx.compose.foundation.horizontalScroll
 import androidx.compose.foundation.layout.Arrangement
 import androidx.compose.foundation.layout.Box
 import androidx.compose.foundation.layout.Column
 import androidx.compose.foundation.layout.Row
 import androidx.compose.foundation.layout.Spacer
 import androidx.compose.foundation.layout.fillMaxSize
 import androidx.compose.foundation.layout.fillMaxWidth
 import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
 import androidx.compose.foundation.layout.padding
 import androidx.compose.foundation.layout.size
 import androidx.compose.foundation.layout.width
 import androidx.compose.foundation.layout.widthIn
 import androidx.compose.foundation.lazy.LazyColumn
 import androidx.compose.foundation.lazy.items
 import androidx.compose.foundation.lazy.rememberLazyListState
 import androidx.compose.foundation.rememberScrollState
 import androidx.compose.foundation.shape.CircleShape
 import androidx.compose.foundation.shape.RoundedCornerShape
 import androidx.compose.foundation.text.KeyboardOptions
 import androidx.compose.foundation.verticalScroll
 import androidx.compose.material.icons.Icons
 import androidx.compose.material.icons.automirrored.outlined.ArrowBack
 import androidx.compose.material.icons.automirrored.outlined.Chat
 import androidx.compose.material.icons.outlined.Add
 import androidx.compose.material.icons.outlined.CleaningServices
 import androidx.compose.material.icons.outlined.Cloud
 import androidx.compose.material.icons.outlined.Delete
 import androidx.compose.material.icons.outlined.History
 import androidx.compose.material.icons.outlined.Layers
 import androidx.compose.material.icons.outlined.Lock
 import androidx.compose.material.icons.outlined.LockOpen
 import androidx.compose.material.icons.outlined.Menu
 import androidx.compose.material.icons.outlined.Mic
 import androidx.compose.material.icons.outlined.Refresh
 import androidx.compose.material.icons.outlined.Restore
 import androidx.compose.material.icons.outlined.Settings
 import androidx.compose.material.icons.outlined.Visibility
 import androidx.compose.material.icons.outlined.VisibilityOff
 import androidx.compose.material3.AlertDialog
 import androidx.compose.material3.CircularProgressIndicator
 import androidx.compose.material3.Button
 import androidx.compose.material3.ButtonDefaults
 import androidx.compose.material3.Card
 import androidx.compose.material3.CardDefaults
 import androidx.compose.material3.DrawerValue
 import androidx.compose.material3.DropdownMenu
 import androidx.compose.material3.DropdownMenuItem
 import androidx.compose.material3.ExperimentalMaterial3Api
 import androidx.compose.material3.HorizontalDivider
 import androidx.compose.material3.Icon
 import androidx.compose.material3.IconButton
 import androidx.compose.material3.LinearProgressIndicator
 import androidx.compose.material3.MaterialTheme
 import androidx.compose.material3.ModalDrawerSheet
 import androidx.compose.material3.ModalNavigationDrawer
 import androidx.compose.material3.NavigationDrawerItem
 import androidx.compose.material3.NavigationDrawerItemDefaults
 import androidx.compose.material3.OutlinedTextField
 import androidx.compose.material3.OutlinedTextFieldDefaults
 import androidx.compose.material3.Scaffold
 import androidx.compose.material3.Switch
 import androidx.compose.material3.SwitchDefaults
 import androidx.compose.material3.Text
 import androidx.compose.material3.TextButton
 import androidx.compose.material3.TopAppBar
 import androidx.compose.material3.TopAppBarDefaults
 import androidx.compose.material3.rememberDrawerState
 import androidx.compose.runtime.Composable
 import androidx.compose.runtime.DisposableEffect
 import androidx.compose.runtime.Immutable
 import androidx.compose.runtime.LaunchedEffect
 import androidx.compose.runtime.collectAsState
 import androidx.compose.runtime.derivedStateOf
 import androidx.compose.runtime.getValue
 import androidx.compose.runtime.key
 import androidx.compose.runtime.mutableLongStateOf
 import androidx.compose.runtime.mutableStateListOf
 import androidx.compose.runtime.mutableStateOf
 import androidx.compose.runtime.remember
 import androidx.compose.runtime.rememberCoroutineScope
 import androidx.compose.runtime.setValue
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
 import androidx.compose.ui.draw.alpha
 import androidx.compose.ui.draw.clip
 import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.graphics.vector.ImageVector
 import androidx.compose.ui.platform.LocalClipboardManager
 import androidx.compose.ui.platform.LocalContext
 import androidx.compose.ui.text.font.FontFamily
 import androidx.compose.ui.text.font.FontWeight
 import androidx.compose.ui.text.input.PasswordVisualTransformation
 import androidx.compose.ui.unit.dp
 import androidx.compose.ui.unit.sp
 import androidx.core.content.ContextCompat
 import com.arix.cloudapi.CloudApiClient
 import com.arix.cloudapi.CloudApiConfig
 import com.arix.cloudapi.WhisperClient
 import com.arix.cloudapi.model.ChatMessage
 import android.content.Intent
 import androidx.compose.material.icons.outlined.Warning
import com.arix.tool.PackageManager as XtomPackageManager
import com.arix.tool.PackageDef
import com.arix.tool.OperitCompat
import com.arix.tool.ImportExport
import com.arix.tool.ImportConverters
 import com.arix.tool.PluginCreatorTool
import com.arix.tool.TtsTool
import com.arix.tool.ShellTool
import com.arix.app.ui.topChromeGapHeight
import com.arix.app.ui.SettingsChoiceRow
import com.arix.app.ui.SettingsHint
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.SettingsSlider
 import java.io.File
 import com.arix.stt.LanguageModel
 import com.arix.stt.SttEngine
 import com.arix.stt.SttModelManager
 import kotlinx.coroutines.CancellationException
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.delay
 import kotlinx.coroutines.isActive
 import kotlinx.coroutines.launch
 import kotlinx.coroutines.withContext
 import org.json.JSONObject
 import java.text.SimpleDateFormat
 import java.util.Date
 import java.util.Locale
 import kotlin.math.roundToInt

@Composable fun ImportExportPage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    val accents = com.arix.app.theme.LocalXtomAccents.current
    var resultMsg by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf(false) }
    // GitHub 私有仓库备份
    val ghInit = remember { GitHubBackup.settings(context) }
    var ghToken by remember { mutableStateOf(ghInit.token) }
    var ghRepo by remember { mutableStateOf(ghInit.repo) }
    var ghBranch by remember { mutableStateOf(ghInit.branch) }
    var ghBusy by remember { mutableStateOf(false) }
    var confirmGhRestore by remember { mutableStateOf(false) }
    // WebDAV 备份
    val wdInit = remember { WebDavBackup.settings(context) }
    var wdUrl by remember { mutableStateOf(wdInit.url) }
    var wdUser by remember { mutableStateOf(wdInit.user) }
    var wdPass by remember { mutableStateOf(wdInit.pass) }
    var wdAuto by remember { mutableStateOf(WebDavBackup.autoSync(context)) }
    var wdBusy by remember { mutableStateOf(false) }
    var confirmWdRestore by remember { mutableStateOf(false) }
    // S3 兼容对象存储备份（AWS S3 / MinIO / Cloudflare R2 / 阿里云 OSS-S3 等）
    val s3Init = remember { S3Backup.settings(context) }
    var s3Endpoint by remember { mutableStateOf(s3Init.endpoint) }
    var s3Region by remember { mutableStateOf(s3Init.region) }
    var s3Bucket by remember { mutableStateOf(s3Init.bucket) }
    var s3Ak by remember { mutableStateOf(s3Init.accessKey) }
    var s3Sk by remember { mutableStateOf(s3Init.secretKey) }
    var s3Prefix by remember { mutableStateOf(s3Init.prefix) }
    var s3PathStyle by remember { mutableStateOf(s3Init.pathStyle) }
    var s3Auto by remember { mutableStateOf(S3Backup.autoSync(context)) }
    var s3Busy by remember { mutableStateOf(false) }
    var confirmS3Restore by remember { mutableStateOf(false) }
    val saveS3: () -> Unit = { S3Backup.save(context, S3Backup.S3Config(s3Endpoint, s3Region, s3Bucket, s3Ak, s3Sk, s3Prefix, s3PathStyle)) }
    var ghAutoSync by remember { mutableStateOf(GitHubBackup.autoSync(context)) }
    var ghRealtime by remember { mutableStateOf(GitHubBackup.realtime(context)) }
    var wdRealtime by remember { mutableStateOf(WebDavBackup.realtime(context)) }
    var ghInterval by remember { mutableStateOf(GitHubBackup.intervalHours(context)) }
    var ghShowBlocks by remember { mutableStateOf(false) }
    val ghSelected = remember { mutableStateListOf<String>() }
    var ghCompare by remember { mutableStateOf<List<GitHubBackup.BlockCompare>>(emptyList()) }

    // ===== 备份加密（口令）/ 云端保留份数 / 云端历史版本 =====
    // 后端（BackupCrypto / BackupPolicy / 三家 listVersions）早就写好了，但一直没有入口：
    // 没入口 = 口令永远设不上 = 加密永远不启用，也看不到云端到底存了几份。
    val opaque = remember { PageBackgroundPrefs.get(context, "import") != null }
    var backupPass by remember { mutableStateOf(BackupCrypto.passphrase(context)) }
    var passVisible by remember { mutableStateOf(false) }
    // 「已保存的口令是否非空」单独存一份：输入框里的 backupPass 是还没落盘的草稿，
    // 拿它判断加密开没开会在用户打字时就变；也别在重组里反复读 SharedPreferences。
    var passSaved by remember { mutableStateOf(BackupCrypto.enabled(context)) }
    var keepVer by remember { mutableStateOf(BackupPolicy.keepVersions(context).toFloat()) }
    // 版本列表三态：verLoading=正在读 / verLoaded=读完了（读完才敢说"云端没有"）/ 两者都不是=还没点过刷新。
    // 没有"转圈转到死"的状态：读完一定会把 verLoading 落回 false（finally 里落）。
    var verBackend by remember { mutableStateOf("github") }
    var verList by remember { mutableStateOf<List<CloudBackupVersion>>(emptyList()) }
    var verLoading by remember { mutableStateOf(false) }
    var verLoaded by remember { mutableStateOf(false) }
    var verBusy by remember { mutableStateOf(false) }
    var confirmVerRestore by remember { mutableStateOf<CloudBackupVersion?>(null) }
    var confirmVerDelete by remember { mutableStateOf<CloudBackupVersion?>(null) }
    var confirmPurgePlain by remember { mutableStateOf(false) }
    // 口令追问：hint = 触发它的那条失败串（决定弹窗里说哪句话）；retry = 拿到口令后原地重试，
    // 为 null 时只能把口令存下来、让用户再点一次（页面里原有的几条恢复路径就是这种）。
    var passAskHint by remember { mutableStateOf<String?>(null) }
    var passAskRetry by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var passAskInput by remember { mutableStateOf("") }
    var passAskVisible by remember { mutableStateOf(false) }

    // 是不是「口令问题」导致的恢复失败。三家云端返回的是中文原串（"恢复失败：口令不对…" /
    // "恢复失败：这是一份加密备份…"）；本地导入那条前缀走了 tr()，换语言后就不是"恢复失败："开头了
    // —— 所以前缀和 contains 两种都认（这两句话只可能来自 BackupPassphraseException，不会误伤）。
    fun needsPassphrase(m: String): Boolean =
        m.startsWith("恢复失败：口令不对") || m.startsWith("恢复失败：这是一份加密备份") ||
            m.contains("口令不对") || m.contains("这是一份加密备份")

    fun askPassphrase(hint: String, retry: ((String) -> Unit)?) {
        passAskInput = ""; passAskVisible = false; passAskHint = hint; passAskRetry = retry
    }

    // 本地 .zip 恢复。pass=null 走本机保存的口令；加密包遇到口令问题会弹口令框，输对了带着 uri 原地重试
    // （换新机恢复加密包时本机根本没存过口令，不给重试就只能重新走一遍文件选择器）。
    fun importLocal(uri: Uri, pass: String?) {
        scope.launch(Dispatchers.IO) {
            try {
                val r = context.contentResolver.openInputStream(uri)!!.use { FullBackup.importFrom(context, it, pass) }
                // 外来备份跳过了权限策略时如实告知——否则用户以为原样恢复了，其实敏感项被保护性跳过
                withContext(Dispatchers.Main) {
                    resultMsg = when {
                        // 外来包：既跳过了工具权限，又保住了原有 LLM 端点/密钥——都要如实告知，并提示复核模型配置
                        r.apiConfigPreserved && r.skippedSensitive ->
                            tr("恢复成功（这份备份非本机导出：出于安全未覆盖工具权限，也保留了你原有的模型端点/密钥，请复核模型配置），正在重启…")
                        r.apiConfigPreserved ->
                            tr("恢复成功（这份备份非本机导出：已保留你原有的模型端点/密钥，请复核模型配置），正在重启…")
                        r.skippedSensitive ->
                            tr("恢复成功（这份备份非本机导出，出于安全未覆盖工具权限设置），正在重启…")
                        else -> tr("恢复成功，正在重启应用…")
                    }
                }
                delay(1200); FullBackup.triggerRestart(context)
            } catch (ce: CancellationException) { throw ce
            } catch (e: BackupPassphraseException) {
                // 口令类失败一律发生在动本地数据之前，本机数据没被碰过 → 可以放心让用户换个口令重来
                withContext(Dispatchers.Main) {
                    val m = tr("恢复失败") + ": ${e.message}"
                    askPassphrase(m) { p -> importLocal(uri, p) }
                    resultMsg = m
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { resultMsg = tr("恢复失败") + ": ${e.message}" }
            }
        }
    }

    // 读云端历史版本。三家后端读不到都返回空表（不抛），所以"空"要在文案里说清是「没有 或 没读到」。
    fun loadVersions() {
        verLoading = true; verLoaded = false
        scope.launch {
            try {
                verList = when (verBackend) {
                    // GitHub 的 token/仓库/分支是本页输入框里的现值，先落盘再读，否则读的是上次保存的配置
                    "github" -> { GitHubBackup.save(context, ghToken, ghRepo, ghBranch); GitHubBackup.listVersions(context) }
                    "webdav" -> WebDavBackup.listVersions(context)
                    else -> { saveS3(); S3Backup.listVersions(context) }
                }
                verLoaded = true
            } catch (ce: CancellationException) { throw ce
            } catch (e: Exception) { verList = emptyList(); verLoaded = true
            } finally { verLoading = false }   // 无论成败/取消都要落回，不然就是转圈转到死
        }
    }

    // 按版本恢复。⚠ S3 的第二个位置参数是 cfg，versionId 必须具名传。
    fun startVersionRestore(v: CloudBackupVersion, pass: String?) {
        verBusy = true; resultMsg = tr("正在按版本恢复…")
        scope.launch {
            val msg = try {
                when (verBackend) {
                    "github" -> { GitHubBackup.save(context, ghToken, ghRepo, ghBranch); GitHubBackup.restore(context, versionId = v.id, passphrase = pass) }
                    "webdav" -> WebDavBackup.restore(context, versionId = v.id, passphrase = pass)
                    else -> { saveS3(); S3Backup.restore(context, versionId = v.id, passphrase = pass) }
                }
            } catch (ce: CancellationException) { throw ce
            } catch (e: Exception) { tr("恢复失败") + ": ${e.message}" }
            verBusy = false
            // 先挂上重试再赋 resultMsg：下面那个 LaunchedEffect 看到已经有弹窗了就不会再开一个没有重试的
            if (needsPassphrase(msg)) askPassphrase(msg) { p -> startVersionRestore(v, p) }
            resultMsg = msg
            if (msg.startsWith("恢复成功")) { delay(900); FullBackup.triggerRestart(context) }
        }
    }

    fun removeVersion(v: CloudBackupVersion) {
        verBusy = true; resultMsg = tr("正在删除云端备份…")
        scope.launch {
            resultMsg = try {
                when (verBackend) {
                    "github" -> GitHubBackup.deleteVersion(context, v.id)
                    "webdav" -> WebDavBackup.deleteVersion(context, v.id)
                    else -> S3Backup.deleteVersion(context, v.id)
                }
            } catch (ce: CancellationException) { throw ce
            } catch (e: Exception) { tr("删除失败") + ": ${e.message}" }
            verBusy = false
            loadVersions()
        }
    }

    // 清理云端明文旧备份：开加密之前上传的那些包不会自动消失（保留策略只按份数删最旧的），
    // 加了密但明文还躺在云上 = 白加密，所以给个一键清理。
    fun purgePlainVersions() {
        val targets = verList.filter { !it.encrypted }
        verBusy = true; resultMsg = tr("正在清理云端明文备份…")
        scope.launch {
            var okN = 0; var failN = 0
            try {
                for (v in targets) {
                    val m = when (verBackend) {
                        "github" -> GitHubBackup.deleteVersion(context, v.id)
                        "webdav" -> WebDavBackup.deleteVersion(context, v.id)
                        else -> S3Backup.deleteVersion(context, v.id)
                    }
                    if (m.startsWith("已删除")) okN++ else failN++
                }
            } catch (ce: CancellationException) { throw ce
            } catch (e: Exception) { failN++ }
            verBusy = false
            resultMsg = String.format(tr("已清理 %s 份明文备份，%s 份未能删除"), okN, failN)
            loadVersions()
        }
    }

    // 页面里原有的四条恢复路径（本地 / GitHub / WebDAV / S3）不动它们的代码，
    // 统一在这里认结果串：只要是口令问题就把口令框弹出来。已经有弹窗时不重复开。
    LaunchedEffect(resultMsg) {
        if (resultMsg.isNotBlank() && passAskHint == null && needsPassphrase(resultMsg)) askPassphrase(resultMsg, null)
    }

    // 全量备份：把 DB + filesDir 打成一个 .zip 保存到用户选的位置
    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) scope.launch(Dispatchers.IO) {
            val msg = runCatching {
                val n = context.contentResolver.openOutputStream(uri)!!.use { FullBackup.exportTo(context, it) }
                // tr() 的 key 里不能出现 $：i18n_wrap.py 收集 tr("…") 时会跳过模板串，
                // 写成模板这条 key 下次重跑脚本就没了。带变量的一律 String.format。
                String.format(tr("已导出全部数据（%s 个文件）到所选备份文件"), n)
            }.getOrElse { tr("备份失败") + ": ${it.message}" }
            withContext(Dispatchers.Main) { resultMsg = msg }
        }
    }
    // 全量恢复：从 .zip 覆盖恢复，成功后重启。实现挪进了上面的 importLocal(uri, pass)——
    // 加密包口令输错时要拿着同一个 uri 原地重试，逻辑只能有一份。
    val openBackup = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importLocal(uri, null)
    }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.topChromeGapHeight())   // 顶部悬浮玻璃让位：在滚动内容里，内容能从玻璃下滑过去化开
        Text(tr("导入导出中心"), color = scheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(tr("角色卡 / 世界书 / 记忆 / 配置 各自页面已内置导入导出（单项+整页）。此处集中处理对话与功能包，导入自动识别并转换主流格式（酒馆 SillyTavern / Operit / Cherry Studio / Chatbox 等）。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(bottom = 12.dp))

        // ===== 全量备份 / 恢复（换机、彻底卸载重装前后保数据）=====
        Text(tr("全量备份 / 恢复"), color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(tr("一键把「所有数据」（角色卡 / 世界书 / 记忆 / 对话 / 配置 / 附件 / 唤醒原型 等全部数据库与文件）打包成单个 .zip 备份文件；恢复时整体覆盖并自动重启。用于换机或彻底卸载重装。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val name = "arix_backup_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}.zip"
                runCatching { createBackup.launch(name) }.onFailure { resultMsg = tr("无法打开文件选择器") }
            }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                Text(tr("备份全部数据"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Button(onClick = { confirmRestore = true },
                colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest, contentColor = scheme.error), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                Text(tr("从备份恢复"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }

        // ===== 备份加密（口令）与云端保留份数 —— 对下面所有备份方式都生效 =====
        Spacer(Modifier.height(14.dp))
        SettingsSection(tr("备份加密与保留份数"), Icons.Outlined.Lock, translucent = !opaque) {
            SettingsHint(tr("设了口令后，之后的每一份备份（本地 .zip 与 GitHub / WebDAV / S3）都用 AES-256 加密再上传；留空 = 不加密，一切照旧。云上已有的加密包不受影响，恢复时仍要原来的口令。"))
            com.arix.app.ui.XtomField(
                value = backupPass, onValueChange = { backupPass = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                label = tr("备份口令（留空 = 不加密）"),
                singleLine = true,
                // 默认遮点，但一定要能看明文：手表小键盘打错了自己根本不知道，等发现时备份已经打不开了
                password = !passVisible,
                trailing = {
                    IconButton(onClick = { passVisible = !passVisible }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            if (passVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                            contentDescription = tr("显示或隐藏口令"), tint = scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                },
            )
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Warning, contentDescription = null, tint = scheme.error, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    tr("口令只存在这台设备上，没有任何找回途径。忘了口令，云端和手上那份加密备份就永远打不开了——换机前请先把口令自己抄下来。"),
                    color = scheme.error, style = MaterialTheme.typography.bodySmall,
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    // 前后空格在手表键盘上看不见，留着等于给自己埋一个"口令明明对却解不开"的坑
                    val p = backupPass.trim()
                    backupPass = p
                    BackupCrypto.setPassphrase(context, p)
                    passSaved = p.isNotEmpty()
                    resultMsg = if (p.isEmpty()) tr("已关闭备份加密：之后的备份不再加密。云端已有的加密包仍然需要原来的口令才能恢复。")
                    else tr("已保存备份口令：之后的每一份备份都会加密。口令只存本机、无法找回，请自己记牢。")
                }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Text(tr("保存口令"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
            SettingsHint(if (passSaved) tr("当前状态：备份加密已开启") else tr("当前状态：备份未加密（备份包在云端是明文，谁拿到都能打开）"))
            SettingsSlider(
                title = tr("云端保留份数"),
                subtitle = tr("每次云备份都新建一份带时间戳的文件，超出份数的最旧几份自动删掉。升级前那份固定文件名的老备份永远不参与自动清理。"),
                value = keepVer, range = 1f..50f,
                onValueChange = { keepVer = it },
                onValueChangeFinished = { BackupPolicy.setKeepVersions(context, keepVer.roundToInt()) },
                unit = tr("份"), icon = Icons.Outlined.Layers,
            )
        }

        // ===== GitHub 私有仓库云备份 =====
        Spacer(Modifier.height(14.dp))
        Text(tr("GitHub 私有仓库备份（云端）"), color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(tr("把整包备份推到你自己的 GitHub 私有仓库，换机/重装直接拉回。仓库自动建为 private、Token 只存本机、走你自己的账号，隐私自己掌控。需要一个有 repo 权限的 Personal Access Token（github.com/settings/tokens）。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        com.arix.app.ui.XtomField(value = ghToken, onValueChange = { ghToken = it }, modifier = Modifier.fillMaxWidth(),
            label = "Personal Access Token", singleLine = true,
            password = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.arix.app.ui.XtomField(value = ghRepo, onValueChange = { ghRepo = it }, modifier = Modifier.weight(1.6f),
                label = tr("仓库名"), singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
            com.arix.app.ui.XtomField(value = ghBranch, onValueChange = { ghBranch = it }, modifier = Modifier.weight(1f),
                label = tr("分支"), singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !ghBusy && ghToken.isNotBlank(), onClick = {
                GitHubBackup.save(context, ghToken, ghRepo, ghBranch); ghBusy = true; resultMsg = tr("正在备份到 GitHub…")
                scope.launch { resultMsg = GitHubBackup.backup(context); ghBusy = false }
            }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                Text(if (ghBusy) tr("处理中…") else tr("备份到 GitHub"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
            Button(enabled = !ghBusy && ghToken.isNotBlank(), onClick = { GitHubBackup.save(context, ghToken, ghRepo, ghBranch); confirmGhRestore = true },
                colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest, contentColor = scheme.error), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                Text(tr("从 GitHub 恢复"), fontSize = 13.sp, fontWeight = FontWeight.Medium)
            }
        }
        Text(tr("上面是整包（云端整体覆盖本地，含头像/附件等文件）。下面可只备份/恢复某几块：恢复=合并进本地、按名字去重不翻倍；分块是文本数据，头像/附件仍需整包备份。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))

        // 联网自动备份
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("联网自动备份"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(tr("联网且距上次备份超过间隔，打开 App 时自动整包备份一次"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
            }
            androidx.compose.material3.Switch(checked = ghAutoSync, onCheckedChange = { ghAutoSync = it; GitHubBackup.setAutoSync(context, it) })
        }
        if (ghAutoSync) Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(String.format(tr("间隔：每 %s 小时"), ghInterval), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = { ghInterval = (ghInterval - 2).coerceAtLeast(1); GitHubBackup.setIntervalHours(context, ghInterval) }) { Text(tr("－"), fontSize = 16.sp) }
            TextButton(onClick = { ghInterval = (ghInterval + 2).coerceAtMost(168); GitHubBackup.setIntervalHours(context, ghInterval) }) { Text(tr("＋"), fontSize = 16.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("变更后实时增量同步"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(tr("数据变化后自动只上传变化的分块（省流量、近实时；打开 App 时先同步一次）"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
            }
            androidx.compose.material3.Switch(checked = ghRealtime, onCheckedChange = { ghRealtime = it; GitHubBackup.setRealtime(context, it) })
        }

        // 分块备份 / 对比
        TextButton(onClick = {
            ghShowBlocks = !ghShowBlocks
            if (ghShowBlocks && ghToken.isNotBlank()) { GitHubBackup.save(context, ghToken, ghRepo, ghBranch); ghBusy = true; resultMsg = tr("对比中…"); scope.launch { ghCompare = GitHubBackup.compareBlocks(context); ghBusy = false; resultMsg = "" } }
        }) { Text(if (ghShowBlocks) tr("收起分块 / 对比 ▲") else tr("分块备份 / 对比 ▼"), color = scheme.primary, fontSize = 12.sp) }
        if (ghShowBlocks) {
            GitHubBackup.BLOCKS.forEach { (b, lbl) ->
                val cmp = ghCompare.firstOrNull { it.block == b }
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = b in ghSelected, onCheckedChange = { on -> if (on) ghSelected.add(b) else ghSelected.remove(b) })
                    Column(Modifier.weight(1f)) {
                        Text(lbl, color = scheme.onSurface, fontSize = 13.sp)
                        if (cmp != null) {
                            val cloud = if (cmp.cloudExists) String.format(tr("云端 %s条/%sKB"), cmp.cloudCount, cmp.cloudSize / 1024) else tr("云端 无")
                            Text(String.format(tr("本地 %s条/%sKB"), cmp.localCount, cmp.localSize / 1024) + " · " + cloud, color = scheme.onSurfaceVariant, fontSize = 10.sp)
                            val hint = buildList {
                                if (cmp.cloudExists) {
                                    if (cmp.cloudCount > cmp.localCount) add(tr("云端更多")) else if (cmp.localCount > cmp.cloudCount) add(tr("本地更多"))
                                    if (cmp.cloudSize > cmp.localSize) add(tr("云端更大")) else if (cmp.localSize > cmp.cloudSize) add(tr("本地更大"))
                                }
                            }
                            if (hint.isNotEmpty()) Text(hint.joinToString(" · "), color = scheme.primary, fontSize = 9.sp)
                            else if (cmp.cloudExists) Text(tr("一致"), color = scheme.onSurfaceVariant, fontSize = 9.sp)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !ghBusy && ghSelected.isNotEmpty(), onClick = {
                    ghBusy = true; resultMsg = tr("备份分块中…"); val sel = ghSelected.toSet()
                    scope.launch { resultMsg = GitHubBackup.backupBlocks(context, sel); ghCompare = GitHubBackup.compareBlocks(context); ghBusy = false }
                }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Text(tr("备份选中"), fontSize = 12.sp)
                }
                Button(enabled = !ghBusy && ghSelected.isNotEmpty(), onClick = {
                    ghBusy = true; resultMsg = tr("合并恢复中…"); val sel = ghSelected.toSet()
                    scope.launch { resultMsg = GitHubBackup.restoreBlocks(context, sel); ghBusy = false }
                }, colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest, contentColor = scheme.onSurface), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Text(tr("恢复选中(合并)"), fontSize = 12.sp)
                }
            }
        }
        // ===== WebDAV 云备份 =====
        Spacer(Modifier.height(14.dp))
        Text(tr("WebDAV 备份（坚果云 / Nextcloud / 自建）"), color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(tr("把整包备份传到你自己的 WebDAV。地址填到目录（以 / 结尾），会存 arix-backup.zip。坚果云需用「应用密码」。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        com.arix.app.ui.XtomField(value = wdUrl, onValueChange = { wdUrl = it; WebDavBackup.save(context, it, wdUser, wdPass) }, label = tr("WebDAV 地址（目录）"), singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.arix.app.ui.XtomField(value = wdUser, onValueChange = { wdUser = it; WebDavBackup.save(context, wdUrl, it, wdPass) }, label = tr("账号"), singleLine = true, modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
            com.arix.app.ui.XtomField(value = wdPass, onValueChange = { wdPass = it; WebDavBackup.save(context, wdUrl, wdUser, it) }, label = tr("密码/应用密码"), singleLine = true, password = true, modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !wdBusy && wdUrl.isNotBlank(), onClick = { wdBusy = true; resultMsg = tr("上传 WebDAV 中…"); scope.launch { resultMsg = WebDavBackup.backup(context); wdBusy = false } },
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text(if (wdBusy) tr("处理中…") else tr("备份到 WebDAV"), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            Button(enabled = !wdBusy && wdUrl.isNotBlank(), onClick = { confirmWdRestore = true },
                colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest, contentColor = scheme.error), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text(tr("从 WebDAV 恢复"), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("联网自动备份到 WebDAV"), color = scheme.onSurface, fontSize = 12.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = wdAuto, onCheckedChange = { wdAuto = it; WebDavBackup.setAutoSync(context, it) })
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("变更后实时同步（内容变化才整包上传）"), color = scheme.onSurface, fontSize = 12.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = wdRealtime, onCheckedChange = { wdRealtime = it; WebDavBackup.setRealtime(context, it) })
        }
        // ===== S3 兼容对象存储备份 =====
        Spacer(Modifier.height(14.dp))
        Text(tr("S3 备份（AWS S3 / MinIO / R2 / OSS）"), color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(tr("把整包备份传到 S3 兼容对象存储。填 Endpoint / 桶 / AccessKey / SecretKey。自建 MinIO 一般需开「Path-Style」。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        com.arix.app.ui.XtomField(value = s3Endpoint, onValueChange = { s3Endpoint = it; saveS3() }, label = tr("Endpoint（含 https://）"), singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.arix.app.ui.XtomField(value = s3Bucket, onValueChange = { s3Bucket = it; saveS3() }, label = tr("桶 Bucket"), singleLine = true, modifier = Modifier.weight(1.4f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
            com.arix.app.ui.XtomField(value = s3Region, onValueChange = { s3Region = it; saveS3() }, label = tr("区域 Region"), singleLine = true, modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.arix.app.ui.XtomField(value = s3Ak, onValueChange = { s3Ak = it; saveS3() }, label = tr("AccessKey"), singleLine = true, modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
            com.arix.app.ui.XtomField(value = s3Sk, onValueChange = { s3Sk = it; saveS3() }, label = tr("SecretKey"), singleLine = true, password = true, modifier = Modifier.weight(1f), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
        }
        Spacer(Modifier.height(6.dp))
        com.arix.app.ui.XtomField(value = s3Prefix, onValueChange = { s3Prefix = it; saveS3() }, label = tr("对象前缀 / 目录（可选，如 arix/backups）"), singleLine = true, modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Path-Style 寻址（MinIO/自建多需开）"), color = scheme.onSurface, fontSize = 12.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = s3PathStyle, onCheckedChange = { s3PathStyle = it; saveS3() })
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !s3Busy && s3Endpoint.isNotBlank() && s3Bucket.isNotBlank(), onClick = { saveS3(); s3Busy = true; resultMsg = tr("上传 S3 中…"); scope.launch { resultMsg = S3Backup.backup(context); s3Busy = false } },
                colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text(if (s3Busy) tr("处理中…") else tr("备份到 S3"), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
            Button(enabled = !s3Busy && s3Endpoint.isNotBlank() && s3Bucket.isNotBlank(), onClick = { saveS3(); confirmS3Restore = true },
                colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest, contentColor = scheme.error), shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) { Text(tr("从 S3 恢复"), fontSize = 13.sp, fontWeight = FontWeight.Medium) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
            TextButton(onClick = { saveS3(); s3Busy = true; resultMsg = tr("测试 S3 连接…"); scope.launch { resultMsg = S3Backup.test(context); s3Busy = false } }, enabled = !s3Busy && s3Endpoint.isNotBlank() && s3Bucket.isNotBlank()) { Text(tr("测试连接"), color = scheme.primary, fontSize = 12.sp) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("联网自动备份到 S3"), color = scheme.onSurface, fontSize = 12.sp, modifier = Modifier.weight(1f))
            androidx.compose.material3.Switch(checked = s3Auto, onCheckedChange = { s3Auto = it; S3Backup.setAutoSync(context, it) })
        }

        // ===== 云端历史版本（按版本恢复 / 删除）=====
        Spacer(Modifier.height(14.dp))
        SettingsSection(tr("云端历史版本"), Icons.Outlined.History, translucent = !opaque) {
            SettingsHint(tr("上面那些「从 XX 恢复」用的都是最新一份。这里能看到云端存着的每一份备份，挑其中任意一份恢复或删掉——最新那份被坏数据覆盖时，就靠往回翻。"))
            SettingsChoiceRow(
                title = tr("云端"),
                options = listOf("github" to "GitHub", "webdav" to "WebDAV", "s3" to "S3"),
                selected = verBackend,
                onSelect = { verBackend = it; verList = emptyList(); verLoaded = false },
                icon = Icons.Outlined.Cloud,
            )
            val verConfigured = when (verBackend) {
                "github" -> ghToken.isNotBlank()
                "webdav" -> wdUrl.isNotBlank()
                else -> s3Endpoint.isNotBlank() && s3Bucket.isNotBlank()
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = verConfigured && !verLoading && !verBusy, onClick = { loadVersions() },
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest, contentColor = scheme.onSurface),
                    shape = RoundedCornerShape(12.dp), modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("刷新版本列表"), fontSize = 12.sp)
                }
            }
            when {
                !verConfigured -> SettingsHint(tr("先把上面这个云端的配置填好（Token / 地址 / 桶与密钥），再来刷新。"))
                verLoading -> Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = scheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(tr("正在读取云端版本…"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
                !verLoaded -> SettingsHint(tr("点上面「刷新版本列表」看看云端存了哪几份。"))
                verList.isEmpty() -> SettingsHint(tr("没读到任何备份。可能是云端还没有备份（先备份一次），也可能是配置或网络有问题——上面「测试连接」能帮你分清是哪种。"), error = true)
                else -> verList.forEach { v ->
                    key(v.id) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(v.label, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                                Text(
                                    versionTimeText(v.timestamp) + " · " + versionSizeText(v.size),
                                    color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                                )
                                Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    // 加密与否直接标出来：不然「我开了加密」和「云上那份到底加没加密」永远对不上号
                                    if (v.encrypted) VersionBadge(tr("已加密"), Icons.Outlined.Lock, scheme.primary)
                                    else VersionBadge(tr("明文"), Icons.Outlined.LockOpen, scheme.error)
                                    if (v.legacy) VersionBadge(tr("老格式"), null, scheme.onSurfaceVariant)
                                }
                            }
                            IconButton(onClick = { confirmVerRestore = v }, enabled = !verBusy, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Restore, contentDescription = tr("恢复这一份"), tint = scheme.primary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { confirmVerDelete = v }, enabled = !verBusy, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Outlined.Delete, contentDescription = tr("删除这一份"), tint = scheme.error, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            val plainCount = if (verLoaded) verList.count { !it.encrypted } else 0
            if (plainCount > 0) {
                HorizontalDivider(Modifier.padding(vertical = 6.dp), color = scheme.outlineVariant)
                SettingsHint(tr("开加密之前上传的明文包不会自己消失（保留策略只按份数删最旧的）。明文还躺在云上，等于加密白加。"))
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { confirmPurgePlain = true }, enabled = !verBusy) {
                        Icon(Icons.Outlined.CleaningServices, contentDescription = null, tint = scheme.error, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(String.format(tr("清理云端明文旧备份（%s 份）"), plainCount), color = scheme.error, fontSize = 12.sp)
                    }
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 14.dp), color = scheme.outlineVariant)

        // 通用粘贴导入区
        Text(tr("粘贴 JSON 导入"), color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        com.arix.app.ui.XtomField(value = importText, onValueChange = { importText = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
            placeholder = tr("在此粘贴 JSON 数据…"), singleLine = false, minLines = 5,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 11.sp, fontFamily = FontFamily.Monospace))
        Spacer(Modifier.height(6.dp))
        Text(tr("按类型导入："), color = scheme.onSurfaceVariant, fontSize = 11.sp)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // 中文原串当 key、渲染时才 tr(v)（见下方 Text(tr(v))）——别在这里包 tr()，
            // 那样存进去的就是译文，再 tr() 一次自然查不到。
            listOf("config" to "配置", "conversation" to "对话", "cherry" to "Cherry/Chatbox对话", "memory" to "记忆", "skill" to "Skill", "sandbox" to "沙盒", "mcp" to "MCP").forEach { (k, v) ->
                Button(onClick = {
                    if (importText.isBlank()) { resultMsg = tr("请先粘贴数据"); return@Button }
                    scope.launch {
                        try {
                            resultMsg = when (k) {
                                "config" -> ImportExport.importConfig(importText, context)
                                "conversation" -> ImportExport.importConversation(importText, context)
                                "cherry" -> {
                                    // 第三方对话（Cherry Studio / Chatbox 等）：先归一化成本应用格式，可能是多段对话，逐个落库
                                    val convs = ImportConverters.normalizeConversations(importText)
                                    if (convs.length() == 0) tr("未识别到可导入的对话（支持 Cherry Studio / Chatbox 等导出）")
                                    else {
                                        var n = 0; var total = 0
                                        for (i in 0 until convs.length()) {
                                            val conv = convs.getJSONObject(i)
                                            ImportExport.importConversation(conv.toString(), context)
                                            n++; total += conv.optJSONArray("messages")?.length() ?: 0
                                        }
                                        tr("已导入 ") + n + tr(" 段对话，共 ") + total + tr(" 条消息（Cherry/Chatbox）")
                                    }
                                }
                                "memory" -> ImportExport.importMemories(importText, context)
                                else -> ImportExport.importPackage(context, k, "imported_${System.currentTimeMillis()}", importText)
                            }
                        } catch (e: Exception) { resultMsg = tr("导入失败") + ": ${e.message}" }
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest, contentColor = scheme.onSurface), shape = RoundedCornerShape(12.dp), modifier = Modifier.height(30.dp)) {
                    Text(tr(v), fontSize = 11.sp)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(tr("导出记忆（全部）"), color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        Text(tr("对话与配置请在各自页面导出具体条目。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        com.arix.app.ui.ImportExportButtons(
            context = context, scope = scope, fileBaseName = "memories_all",
            produceJson = { ImportExport.exportMemories(context, null) },
            onResult = { msg -> resultMsg = msg },
        )

        if (resultMsg.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = scheme.surface), shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
                // 结果是红是绿靠关键词判断。resultMsg 有两个来源：本页（已 tr，可能是任何语言）
                // 和 GitHubBackup/WebDavBackup/S3Backup（仍是中文原串）。两边都得认，所以中文关键词
                // 和当前语言的译法都比一遍；ignoreCase 是因为多数语言的 "failed" 会带大小写差异。
                val failed = listOf("失败", "错误", tr("失败"), tr("错误")).any { resultMsg.contains(it, ignoreCase = true) }
                Text(resultMsg, modifier = Modifier.padding(12.dp), color = if (failed) scheme.error else scheme.primary, fontSize = 12.sp)
            }
        }

        if (confirmRestore) AlertDialog(
            onDismissRequest = { confirmRestore = false },
            title = { Text(tr("从备份恢复？"), color = scheme.onSurface, fontSize = 15.sp) },
            text = { Text(tr("将用备份文件【整体覆盖】当前所有数据（角色卡 / 记忆 / 对话 / 配置 / 附件 等），当前数据会被替换且不可撤销；恢复完成后应用自动重启。建议先「备份全部数据」留一份当前快照。"), color = scheme.onSurfaceVariant, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmRestore = false
                    runCatching { openBackup.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) }.onFailure { resultMsg = tr("无法打开文件选择器") }
                }) { Text(tr("选择备份并覆盖"), color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )

        if (confirmGhRestore) AlertDialog(
            onDismissRequest = { confirmGhRestore = false },
            title = { Text(tr("从 GitHub 恢复？"), color = scheme.onSurface, fontSize = 15.sp) },
            text = { Text(tr("将从你的私有仓库拉取最新备份并【整体覆盖】当前所有数据，不可撤销；恢复完成后自动重启。建议先备份一次当前数据。"), color = scheme.onSurfaceVariant, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmGhRestore = false; ghBusy = true; resultMsg = tr("正在从 GitHub 恢复…")
                    scope.launch {
                        val msg = GitHubBackup.restore(context); resultMsg = msg; ghBusy = false
                        if (msg.startsWith("恢复成功")) { kotlinx.coroutines.delay(900); FullBackup.triggerRestart(context) }
                    }
                }) { Text(tr("拉取并覆盖"), color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmGhRestore = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )

        if (confirmWdRestore) AlertDialog(
            onDismissRequest = { confirmWdRestore = false },
            title = { Text(tr("从 WebDAV 恢复？"), color = scheme.onSurface, fontSize = 15.sp) },
            text = { Text(tr("将从 WebDAV 拉取备份并【整体覆盖】当前所有数据，不可撤销；恢复完成后自动重启。建议先备份一次。"), color = scheme.onSurfaceVariant, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmWdRestore = false; wdBusy = true; resultMsg = tr("正在从 WebDAV 恢复…")
                    scope.launch {
                        val msg = WebDavBackup.restore(context); resultMsg = msg; wdBusy = false
                        if (msg.startsWith("恢复成功")) { kotlinx.coroutines.delay(900); FullBackup.triggerRestart(context) }
                    }
                }) { Text(tr("拉取并覆盖"), color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmWdRestore = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )
        if (confirmS3Restore) AlertDialog(
            onDismissRequest = { confirmS3Restore = false },
            title = { Text(tr("从 S3 恢复？"), color = scheme.onSurface, fontSize = 15.sp) },
            text = { Text(tr("将从 S3 拉取备份并【整体覆盖】当前所有数据，不可撤销；恢复完成后自动重启。建议先备份一次。"), color = scheme.onSurfaceVariant, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmS3Restore = false; s3Busy = true; resultMsg = tr("正在从 S3 恢复…")
                    scope.launch {
                        val msg = S3Backup.restore(context); resultMsg = msg; s3Busy = false
                        if (msg.startsWith("恢复成功")) { kotlinx.coroutines.delay(900); FullBackup.triggerRestart(context) }
                    }
                }) { Text(tr("拉取并覆盖"), color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmS3Restore = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )

        // ===== 按版本恢复的二次确认：跟上面几条恢复走同一套话术（整体覆盖 + 不可撤销 + 自动重启）=====
        val verToRestore = confirmVerRestore
        if (verToRestore != null) AlertDialog(
            onDismissRequest = { confirmVerRestore = null },
            title = { Text(tr("恢复这一份备份？"), color = scheme.onSurface, fontSize = 15.sp) },
            text = {
                Column {
                    Text(
                        tr("将用云端这一份备份【整体覆盖】本机现有的所有数据（角色卡 / 记忆 / 对话 / 配置 / 附件 等），本机现有数据会被替换且不可撤销；恢复完成后应用自动重启。建议先「备份全部数据」留一份当前快照。"),
                        color = scheme.onSurfaceVariant, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        String.format(tr("选中的版本：%s（%s）"), verToRestore.label, versionTimeText(verToRestore.timestamp)),
                        color = scheme.onSurface, fontSize = 12.sp,
                    )
                    if (verToRestore.encrypted && !passSaved) {
                        Spacer(Modifier.height(6.dp))
                        Text(tr("这是一份加密备份，而本机还没设口令——恢复时会问你要口令。"), color = scheme.error, fontSize = 11.sp)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmVerRestore = null
                    startVersionRestore(verToRestore, null)
                }) { Text(tr("拉取并覆盖"), color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmVerRestore = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )

        val verToDelete = confirmVerDelete
        if (verToDelete != null) AlertDialog(
            onDismissRequest = { confirmVerDelete = null },
            title = { Text(tr("删除这一份云端备份？"), color = scheme.onSurface, fontSize = 15.sp) },
            text = {
                Text(
                    String.format(tr("将从云端删除 %s，删掉就找不回来了。只影响云端，本机数据不受影响。"), verToDelete.label),
                    color = scheme.onSurfaceVariant, fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmVerDelete = null; removeVersion(verToDelete) }) { Text(tr("删除"), color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmVerDelete = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )

        if (confirmPurgePlain) AlertDialog(
            onDismissRequest = { confirmPurgePlain = false },
            title = { Text(tr("清理云端明文旧备份？"), color = scheme.onSurface, fontSize = 15.sp) },
            text = {
                Text(
                    String.format(tr("将把这个云端上所有【未加密】的备份删掉（共 %s 份），其中也包括升级前那份固定文件名的老备份。删掉就找不回来了，只影响云端，本机数据不受影响。如果你手上没有别的备份，请先「备份全部数据」存一份到本地。"), verList.count { !it.encrypted }),
                    color = scheme.onSurfaceVariant, fontSize = 12.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = { confirmPurgePlain = false; purgePlainVersions() }) { Text(tr("全部删除"), color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmPurgePlain = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )

        // ===== 恢复时的口令追问 =====
        // 口令类失败都发生在动本地数据之前（后端保证），所以这里可以放心说"本机数据没被动过"。
        val askHint = passAskHint
        if (askHint != null) AlertDialog(
            onDismissRequest = { passAskHint = null; passAskRetry = null },
            title = { Text(tr("需要备份口令"), color = scheme.onSurface, fontSize = 15.sp) },
            text = {
                Column {
                    Text(
                        if (askHint.contains("口令不对")) tr("口令不对，或者这份加密备份被改动过。本机数据一个字节都没动，换个口令再试一次。")
                        else tr("这是一份加密备份，需要当初设置的备份口令才能打开。本机数据一个字节都没动。"),
                        color = scheme.onSurfaceVariant, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(8.dp))
                    com.arix.app.ui.XtomField(
                        value = passAskInput, onValueChange = { passAskInput = it },
                        modifier = Modifier.fillMaxWidth(), label = tr("备份口令"), singleLine = true,
                        password = !passAskVisible,
                        trailing = {
                            IconButton(onClick = { passAskVisible = !passAskVisible }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    if (passAskVisible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                                    contentDescription = tr("显示或隐藏口令"), tint = scheme.onSurfaceVariant,
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        },
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (passAskRetry != null) tr("只用来打开这一份备份，不会改掉你本机保存的备份口令。")
                        else tr("会存成本机的备份口令（之后的备份也用它加密），然后再点一次恢复。"),
                        color = scheme.onSurfaceVariant, fontSize = 10.sp,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = passAskInput.isNotEmpty(), onClick = {
                    val p = passAskInput.trim()
                    val retry = passAskRetry
                    passAskHint = null; passAskRetry = null
                    if (retry != null) {
                        // 能原地重试就不落盘：这份老备份的口令未必是用户现在想用的那个，
                        // 悄悄改掉本机口令 = 之后的自动备份全用错口令加密。
                        retry(p)
                    } else {
                        // 原有那几条恢复路径没法带口令重来，只能先存下来让用户再点一次
                        BackupCrypto.setPassphrase(context, p); backupPass = p; passSaved = p.isNotEmpty()
                        resultMsg = tr("口令已保存，请再点一次恢复。")
                    }
                }) { Text(if (passAskRetry != null) tr("用这个口令重试") else tr("保存口令"), color = scheme.primary) }
            },
            dismissButton = { TextButton(onClick = { passAskHint = null; passAskRetry = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )
    }
}

/** 版本行的时间。timestamp=0 表示后端连 mtime 都没给（WebDAV 退回本地账本时会这样）。 */
private fun versionTimeText(ts: Long): String =
    if (ts <= 0L) tr("时间未知") else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))

/** 版本行的大小。size=-1 表示后端没给。 */
private fun versionSizeText(size: Long): String =
    if (size < 0L) tr("大小未知") else "${size / 1024} KB"

/** 版本行上的小标记（加密 / 明文 / 老格式）。 */
@Composable
private fun VersionBadge(text: String, icon: ImageVector?, color: Color) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(10.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, color = color, fontSize = 9.sp)
    }
}

/**
 * i18n 收集锚点 —— 永远不会被调用，只为让 `tools/i18n_wrap.py` 扫得到这些串。
 *
 * 「按类型导入」那排按钮的文案走的是「延迟 tr()」：中文原串跟 key 配成对存在列表里，
 * 渲染时才 tr(v)。收集脚本只认 tr() 里直接写中文字面量这一种写法，扫不到那种用法，
 * 重跑一次就会把它们从 i18n/i18n_table.json 里删掉。
 * ⚠️ 改上面那排按钮的文案，这里必须同步改。
 */
@Suppress("unused")
private fun importTypeI18nKeys() = listOf(
    tr("角色卡"), tr("世界书"), tr("配置"), tr("对话"), tr("Cherry/Chatbox对话"), tr("记忆"), tr("沙盒"),
)

