package com.arix.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.ui.SettingsHint
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.SettingsToggle
import com.arix.tool.UserScript
import com.arix.tool.UserScriptStore
import com.arix.app.ui.topChromeGapHeight

// 用户脚本(油猴式)管理页：增删改「匹配网址→注入 JS」的脚本，供 open_page 用 WebView 打开匹配网址时注入。
@Composable fun UserScriptPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    // 本页设了背景图时卡片必须不透明，否则内容压在照片上读不清
    val opaque = remember { PageBackgroundPrefs.get(context, "user_scripts") != null }
    var scripts by remember { mutableStateOf(UserScriptStore.getAll(context)) }
    var editing by remember { mutableStateOf<UserScript?>(null) }
    fun reload() { scripts = UserScriptStore.getAll(context) }

    Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.topChromeGapHeight())   // 顶部悬浮玻璃让位：在滚动内容里，内容能从玻璃下滑过去化开
        SettingsSection(tr("用户脚本（油猴式）"), Icons.Outlined.Code, translucent = !opaque) {
            SettingsHint(tr("给指定网址配 JS 脚本，open_page 用 WebView 打开匹配网址时自动注入执行（去广告/展开正文/破懒加载等）。脚本在目标页面上下文运行、能力强，只加你信任的、匹配尽量收窄。"))
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { editing = UserScript(UserScriptStore.newId(), "", true, "glob", "*://*/*", "") },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.padding(start = 4.dp),
            ) {
                Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                Text(tr("新建脚本"))
            }
        }
        SettingsSection(tr("已装脚本"), Icons.Outlined.Code, translucent = !opaque) {
            if (scripts.isEmpty()) SettingsHint(tr("还没有用户脚本。"))
            scripts.forEach { s ->
                SettingsToggle(
                    icon = Icons.Outlined.Code,
                    title = s.name.ifBlank { tr("未命名") },
                    subtitle = "${s.matchType} · ${s.pattern}",
                    checked = s.enabled,
                    onCheckedChange = { UserScriptStore.upsert(context, s.copy(enabled = it)); reload() },
                    trailing = {
                        IconButton(onClick = { editing = s }) {
                            Icon(Icons.Outlined.Edit, contentDescription = tr("编辑"), tint = scheme.primary)
                        }
                    },
                )
            }
        }
    }

    editing?.let { sc ->
        var name by remember(sc.id) { mutableStateOf(sc.name) }
        var matchType by remember(sc.id) { mutableStateOf(sc.matchType) }
        var pattern by remember(sc.id) { mutableStateOf(sc.pattern) }
        var code by remember(sc.id) { mutableStateOf(sc.code) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(tr("编辑用户脚本"), color = scheme.onSurface, fontSize = 15.sp) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                com.arix.app.ui.XtomField(value = name, onValueChange = { name = it }, label = tr("名称"), singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    listOf("glob" to tr("通配"), "regex" to tr("正则")).forEach { (t, l) ->
                        Surface(onClick = { matchType = t }, shape = RoundedCornerShape(50), color = if (matchType == t) scheme.primary else scheme.surfaceContainerHighest, modifier = Modifier.padding(end = 6.dp)) {
                            Text(l, color = if (matchType == t) scheme.onPrimary else scheme.onSurface, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                com.arix.app.ui.XtomField(value = pattern, onValueChange = { pattern = it }, label = tr("匹配网址（通配 *://*.example.com/* 或正则）"), singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                com.arix.app.ui.XtomField(value = code, onValueChange = { code = it }, label = tr("JS 代码"), singleLine = false, minLines = 6, modifier = Modifier.fillMaxWidth())
            } },
            confirmButton = { TextButton(onClick = { UserScriptStore.upsert(context, sc.copy(name = name.trim(), matchType = matchType, pattern = pattern.trim(), code = code)); editing = null; reload() }) { Text(tr("保存"), color = scheme.primary) } },
            dismissButton = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(onClick = { UserScriptStore.delete(context, sc.id); editing = null; reload() }) { Text(tr("删除"), color = scheme.error) }
                TextButton(onClick = { editing = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) }
            } },
            containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
        )
    }
}
