package com.arix.app

import android.content.Context
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arix.app.ui.SettingsHint
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.XtomButton
import com.arix.app.ui.topChromeGapHeight
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import java.io.File
import kotlin.coroutines.coroutineContext

// ============================================================
// 存储占用 —— 按类别看 App 到底占了多少，并**只**清明确安全的那几类。
//
// 两条硬规矩：
//  1. 扫描全在 IO 线程、可中断（离开页面即停），大目录分片 yield，界面不卡。
//  2. 「能删」和「只看」分得死死的：数据库、对话附件、AI 工作区、知识库都是**用户资产**，
//     这里只显示大小、不给一键删按钮。删它们等于删聊天记录/删 AI 的工作成果。
// ============================================================

/** 一个存储类别。roots 可以是多个目录（如知识库迁移前后两份）。 */
private data class StorageCat(
    val id: String,
    val icon: ImageVector,
    val title: String,
    val desc: String,
    /** 可清理项：为 null 表示只读（只显示大小）。非 null 时是「删什么」的人话描述 + 执行体。 */
    val clear: ClearAction?,
    val roots: (Context) -> List<File>,
)

private data class ClearAction(
    /** 二次确认弹窗里逐条列出的「会删掉什么」。 */
    val whatWillBeDeleted: List<String>,
    val perform: suspend (Context) -> Unit,
)

/** 备份/恢复过程中落在 cacheDir 顶层的中间产物（FullBackup 用 createTempFile 造的那些）。 */
private fun backupTempFiles(c: Context): List<File> {
    val dir = c.cacheDir ?: return emptyList()
    val kids = dir.listFiles() ?: return emptyList()
    return kids.filter { f ->
        f.isFile && (
            f.name.startsWith("xtom_restore") || f.name.startsWith("xtom_backup") ||
                f.name.startsWith("arix-backup") || f.name.endsWith(".zip") || f.name.endsWith(".tmp")
            )
    }
}

private fun categories(): List<StorageCat> = listOf(
    StorageCat(
        id = "db",
        icon = Icons.Outlined.Storage,
        title = tr("数据库"),
        desc = tr("对话、角色卡、记忆、世界书全在这里。只显示大小——删它等于把所有聊天记录和记忆一起抹掉。"),
        clear = null,
        roots = { c ->
            val db = c.getDatabasePath("arix.db")
            listOf(db, File(db.parentFile, "arix.db-wal"), File(db.parentFile, "arix.db-shm"))
        },
    ),
    StorageCat(
        id = "attachments",
        icon = Icons.Outlined.AttachFile,
        title = tr("对话附件"),
        desc = tr("你在聊天里发过的图片和文件。消息还引用着它们，删了历史消息里的附件就打不开了，所以只显示大小。"),
        clear = null,
        roots = { c -> listOf(File(c.filesDir, "chat_attachments")) },
    ),
    StorageCat(
        id = "image_cache",
        icon = Icons.Outlined.Image,
        title = tr("图片缓存"),
        desc = tr("网络图片的本地副本，纯加速用。清掉只是下次重新下载，不丢任何内容。"),
        clear = ClearAction(
            whatWillBeDeleted = listOf(tr("已下载图片的本地缓存副本（下次显示时会自动重新下载）")),
            perform = { c ->
                // 优先走 Coil 自己的清理：它持有打开的缓存句柄，直接删文件会让它的索引与磁盘对不上
                val cleared = runCatching { coil.Coil.imageLoader(c).diskCache?.clear(); true }.getOrDefault(false)
                if (!cleared) File(c.cacheDir, "image_cache").deleteRecursively()
            },
        ),
        roots = { c -> listOf(File(c.cacheDir, "image_cache")) },
    ),
    StorageCat(
        id = "workspace",
        icon = Icons.Outlined.Folder,
        title = tr("AI 工作区"),
        desc = tr("AI 干活时读写的私有目录，还有技能包/插件。里面是它的工作成果，只显示大小，要删请到「文件」页逐个删。"),
        clear = null,
        roots = { c ->
            listOf(
                File(c.filesDir, "ai_workspace"),
                File(c.filesDir, "operit_skills"),
                File(c.filesDir, "operit_packages"),
                File(c.filesDir, "plugins"),
            )
        },
    ),
    StorageCat(
        id = "rag",
        icon = Icons.Outlined.Description,
        title = tr("文档知识库"),
        desc = tr("你导入给 AI 检索的文档原文。删了知识库就查不到了，所以只显示大小。"),
        clear = null,
        roots = { c -> listOf(File(c.filesDir, "rag_docs"), File(c.filesDir, "rag_docs_migrated"), File(c.cacheDir, "docread")) },
    ),
    StorageCat(
        id = "crash",
        icon = Icons.Outlined.Warning,
        title = tr("崩溃报告"),
        desc = tr("崩溃时自动存下的堆栈文本，排查完就没用了。"),
        clear = ClearAction(
            whatWillBeDeleted = listOf(tr("全部历史崩溃报告文本（清掉后「崩溃报告」页会变空）")),
            perform = { c -> CrashHandler.clearAllReports(c) },
        ),
        roots = { c -> listOf(File(c.filesDir, "crash_reports")) },
    ),
    StorageCat(
        id = "backup_temp",
        icon = Icons.Outlined.CleaningServices,
        title = tr("备份临时文件"),
        desc = tr("备份/恢复过程中在缓存目录留下的中间压缩包。正常流程会自己清，异常中断时会留下。"),
        clear = ClearAction(
            whatWillBeDeleted = listOf(tr("缓存目录里的备份中间压缩包（.zip / .tmp 临时文件），不含你已导出到别处的备份")),
            perform = { c -> backupTempFiles(c).forEach { runCatching { it.delete() } } },
        ),
        roots = { c -> backupTempFiles(c) },
    ),
)

/** Android 上 File.canonicalPath 对符号链接恒等于自身，判不出软链——只能问 lstat。 */
private fun isSymlink(f: File): Boolean = runCatching {
    android.system.OsConstants.S_ISLNK(android.system.Os.lstat(f.absolutePath).st_mode)
}.getOrDefault(false)

/**
 * 递归求目录大小。显式检查取消（离开页面立刻停），每 200 个条目让一次出去，
 * 不霸着 IO 线程也不让「几万个小文件」的目录把扫描卡成一坨。
 */
private suspend fun sizeOf(root: File): Long {
    if (!root.exists()) return 0L
    var total = 0L
    var visited = 0
    val stack = ArrayDeque<File>()
    stack.addLast(root)
    while (stack.isNotEmpty()) {
        coroutineContext.ensureActive()
        val f = stack.removeLast()
        if (isSymlink(f)) continue       // 不跟软链：跟了可能走出私有目录，还可能绕成环
        if (f.isDirectory) f.listFiles()?.forEach { stack.addLast(it) } else total += f.length()
        if (++visited % 200 == 0) yield()
    }
    return total
}

private fun humanSize(bytes: Long): String = when {
    bytes <= 0L -> "0 B"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024 -> String.format("%.1f KB", bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024))
    else -> String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024))
}

// ============================================================
// 页面
// ============================================================
@Composable fun StorageUsagePage(context: Context) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val opaque = remember { PageBackgroundPrefs.get(context, "storage") != null }
    val cats = remember { categories() }

    // id -> 字节数；没有 key 表示这一类还没扫完
    val sizes = remember { mutableStateMapOf<String, Long>() }
    var scanTick by remember { mutableStateOf(0) }
    var scanning by remember { mutableStateOf(true) }
    var pendingClear by remember { mutableStateOf<StorageCat?>(null) }
    var busyId by remember { mutableStateOf<String?>(null) }

    // 扫描：整段在 IO 线程；LaunchedEffect 随页面销毁/重扫自动取消，sizeOf 里的 ensureActive 会立刻停。
    // 逐类回写，用户看着数字一类一类填上来，不是干等一个总数。
    LaunchedEffect(scanTick) {
        scanning = true
        sizes.clear()
        try {
            for (cat in cats) {
                val n = withContext(Dispatchers.IO) { cat.roots(context).sumOf { sizeOf(it) } }
                sizes[cat.id] = n
            }
        } catch (ce: CancellationException) {
            throw ce
        } finally {
            scanning = false
        }
    }

    val total = cats.sumOf { sizes[it.id] ?: 0L }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.topChromeGapHeight())

        SettingsSection(tr("总计"), Icons.Outlined.DataUsage, translucent = !opaque) {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (scanning) tr("正在统计…") else humanSize(total),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = scheme.primary,
                    )
                    Text(
                        if (scanning) String.format(tr("已完成 %d / %d 类"), sizes.size, cats.size) else tr("已统计全部类别"),
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(8.dp))
                XtomButton(onClick = { if (!scanning) scanTick++ }, enabled = !scanning) {
                    Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(tr("重新扫描"))
                }
            }
            if (scanning) {
                LinearProgressIndicator(
                    progress = { if (cats.isEmpty()) 0f else sizes.size.toFloat() / cats.size },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }

        cats.forEach { cat ->
            SettingsSection(cat.title, cat.icon, translucent = !opaque) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                        val v = sizes[cat.id]
                        Text(
                            if (v == null) tr("扫描中…") else humanSize(v),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            color = if (v == null) scheme.onSurfaceVariant else scheme.onSurface,
                        )
                        Text(cat.desc, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                    }
                    if (cat.clear != null) {
                        if (busyId == cat.id) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = scheme.primary)
                        } else {
                            TextButton(
                                onClick = { pendingClear = cat },
                                enabled = (sizes[cat.id] ?: 0L) > 0L && busyId == null,
                            ) {
                                Icon(Icons.Outlined.Delete, contentDescription = null, tint = scheme.error, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text(tr("清理"), color = scheme.error, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    } else {
                        // 只读类别给个锁标，明确「这里没有删除按钮不是漏做的」
                        Icon(Icons.Outlined.Lock, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
                if (cat.clear == null) SettingsHint(tr("这一类是你的资料，本页不提供清理。"))
            }
        }

        SettingsHint(tr("提示：只有缓存、崩溃报告、备份临时文件给了清理按钮——它们删掉不丢任何内容。其余类别只统计大小。"))
        Spacer(Modifier.height(24.dp))
    }

    // 二次确认：把「删什么」逐条写清楚，不写一句「确定清理吗」了事
    pendingClear?.let { cat ->
        val act = cat.clear
        AlertDialog(
            onDismissRequest = { pendingClear = null },
            title = { Text(String.format(tr("清理「%s」"), cat.title), color = scheme.onSurface) },
            text = {
                Column {
                    Text(tr("将要删除："), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    act?.whatWillBeDeleted?.forEach {
                        Text("· $it", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        String.format(tr("可释放约 %s。此操作不可撤销。"), humanSize(sizes[cat.id] ?: 0L)),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingClear = null
                    if (act != null) {
                        busyId = cat.id
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { act.perform(context) }
                                val n = withContext(Dispatchers.IO) { cat.roots(context).sumOf { sizeOf(it) } }
                                sizes[cat.id] = n
                            } catch (ce: CancellationException) {
                                throw ce
                            } catch (_: Exception) {
                                // 删不动就保持原大小，不弹错误吓人；重扫一次即可看到真实情况
                            } finally {
                                busyId = null
                            }
                        }
                    }
                }) { Text(tr("清理"), color = scheme.error) }
            },
            dismissButton = { TextButton(onClick = { pendingClear = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface,
            shape = RoundedCornerShape(24.dp),
        )
    }
}
