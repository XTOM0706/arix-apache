package com.arix.app

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.theme.LocalXtomAccents
import com.arix.app.ui.PageScaffold
import com.arix.tool.PackageManager as XtomPackageManager
import com.arix.tool.PackageDef

@OptIn(ExperimentalFoundationApi::class)
@Composable fun PackagesPage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    val packages = remember { XtomPackageManager.getAllPackages() }
    val enabledMap = remember { mutableStateOf(packages.associate { it.id to XtomPackageManager.isEnabled(it.id) }) }
    var longPressedPkg by remember { mutableStateOf<PackageDef?>(null) }
    val memoryManager = remember { MemoryManager(context) }
    var pkgMemories by remember { mutableStateOf<List<com.arix.data.entity.MemoryEntity>>(emptyList()) }
    // 长按"记忆系统"包时，加载已存储的记忆列表
    LaunchedEffect(longPressedPkg?.id) {
        pkgMemories = if (longPressedPkg?.id == "memory") memoryManager.recent(50) else emptyList()
    }

    PageScaffold {
        Text(tr("包管理"), color = scheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(tr("安装和启用功能包以扩展 AI 能力"), color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))

        val categories = packages.groupBy { it.category }
        for ((cat, pkgs) in categories) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(cat, color = scheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(4.dp))
            pkgs.forEach { pkg ->
                val enabled = enabledMap.value[pkg.id] ?: false
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (enabled) scheme.surfaceContainerHighest else scheme.onPrimary)
                    .combinedClickable(
                        onClick = {
                            if (enabled) XtomPackageManager.disable(pkg.id) else XtomPackageManager.enable(pkg.id)
                            enabledMap.value = packages.associate { it.id to XtomPackageManager.isEnabled(it.id) }
                        },
                        onLongClick = { longPressedPkg = pkg }
                    )
                    .padding(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pkg.name, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                            Text(pkg.description.take(100), color = scheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 2)
                            if (pkg.tools.isNotEmpty()) {
                                Text(tr("提供: %s").format(pkg.tools.joinToString { it.name }), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                            if (pkg.requiresPermissions.isNotEmpty()) {
                                Text(tr("需要权限: %s").format(pkg.requiresPermissions.joinToString()), color = scheme.secondary, fontSize = 10.sp)
                            }
                        }
                        Switch(checked = enabled, onCheckedChange = {
                            if (enabled) XtomPackageManager.disable(pkg.id) else XtomPackageManager.enable(pkg.id)
                            enabledMap.value = packages.associate { it.id to XtomPackageManager.isEnabled(it.id) }
                        }, colors = SwitchDefaults.colors(checkedTrackColor = scheme.primary))
                    }
                }
            }
        }
    }

    if (longPressedPkg != null) {
        val pkg = longPressedPkg!!
        val isEnabled = enabledMap.value[pkg.id] ?: false
        val isBuiltIn = !pkg.id.startsWith("user_") && !pkg.id.startsWith("custom_")
        androidx.compose.material3.AlertDialog(onDismissRequest = { longPressedPkg = null },
            title = {
                Column {
                    Text(pkg.name, color = scheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("ID: ${pkg.id}", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                }
            },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (isBuiltIn) tr("内置") else tr("外部"), color = if (isBuiltIn) scheme.primary else scheme.secondary, fontSize = 10.sp, modifier = Modifier.background(scheme.surfaceContainerHighest, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                    Text(pkg.category, color = scheme.primary, fontSize = 10.sp, modifier = Modifier.background(scheme.surfaceContainerHighest, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                    Text(if (isEnabled) tr("已启用") else tr("已禁用"), color = if (isEnabled) scheme.primary else scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.background(scheme.surfaceContainerHighest, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp))
                }
                Spacer(Modifier.height(6.dp))
                Text(pkg.description, color = scheme.onSurface, fontSize = 13.sp)

                if (pkg.tools.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(tr("提供工具 (%d):").format(pkg.tools.size), color = scheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    pkg.tools.forEach { tool ->
                        Spacer(Modifier.height(2.dp))
                        Text("• ${tool.name}: ${tool.description.take(80)}", color = scheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                } else {
                    Spacer(Modifier.height(4.dp))
                    Text(tr("无注册工具（需额外加载插件）"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                }

                if (pkg.requiresPermissions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(tr("所需权限:"), color = scheme.secondary, fontSize = 11.sp)
                    pkg.requiresPermissions.forEach { Text("  · $it", color = scheme.onSurfaceVariant, fontSize = 10.sp) }
                }

                // 记忆列表（仅记忆系统包）
                if (pkg.id == "memory") {
                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = scheme.surfaceContainerHighest)
                    Text(tr("记忆列表 (%d)").format(pkgMemories.size), color = scheme.tertiary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp))
                    if (pkgMemories.isEmpty()) {
                        Text(tr("暂无记忆。AI 对话中会自动记录用户偏好/事实。"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                    } else {
                        pkgMemories.forEach { mem ->
                            Column(Modifier.fillMaxWidth().padding(vertical = 2.dp).background(scheme.surfaceContainerLowest, RoundedCornerShape(6.dp)).padding(6.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("★".repeat((mem.importance * 3).toInt().coerceIn(0, 3)).ifEmpty { "·" }, color = scheme.secondary, fontSize = 9.sp)
                                    Spacer(Modifier.width(4.dp))
                                    Text(mem.title, color = scheme.onSurface, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                }
                                if (mem.content.isNotBlank()) Text(mem.content.take(60), color = scheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 2)
                            }
                        }
                    }
                }
            }},
            confirmButton = { TextButton(onClick = {
                if (isEnabled) XtomPackageManager.disable(pkg.id) else XtomPackageManager.enable(pkg.id)
                enabledMap.value = packages.associate { it.id to XtomPackageManager.isEnabled(it.id) }
                longPressedPkg = null
            }) { Text(if (isEnabled) tr("禁用") else tr("启用"), color = if (isEnabled) scheme.error else scheme.primary) } },
            dismissButton = { TextButton(onClick = { longPressedPkg = null }) { Text(tr("关闭"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(20.dp))
    }
}
