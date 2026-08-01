package com.arix.app

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

// GitHub 账号登录卡：已登录=显示头像+用户名+退出；未登录=登录(粘贴 PAT)。登录成功后全局 token 生效（备份/市场/工具都用）。
@Composable
fun GitHubLoginCard(context: Context, onChanged: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var account by remember { mutableStateOf(GitHubAccount.account(context)) }
    var showLogin by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var err by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(scheme.surface).padding(12.dp),
    ) {
        val acc = account
        if (acc != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                coil.compose.AsyncImage(
                    model = acc.avatarUrl, contentDescription = null,
                    modifier = Modifier.size(40.dp).clip(CircleShape).background(scheme.surfaceContainerHighest),
                )
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(acc.name.ifBlank { acc.login }, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1)
                    Text("@" + acc.login + " · " + tr("已登录 GitHub"), color = scheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1)
                }
                TextButton(onClick = { GitHubAccount.logout(context); account = null; onChanged() }) {
                    Text(tr("退出登录"), color = scheme.error, fontSize = 12.sp)
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                    Text("GH", color = scheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("未登录 GitHub"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(tr("登录后备份 / 市场 / gh 工具共用，并抬高 API 限流"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                }
                Button(onClick = { err = null; tokenInput = ""; showLogin = true }, shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.primary, contentColor = scheme.onPrimary)) {
                    Text(tr("登录"), fontSize = 12.sp)
                }
            }
        }
    }

    if (showLogin) {
        AlertDialog(
            onDismissRequest = { if (!busy) showLogin = false },
            title = { Text(tr("登录 GitHub"), color = scheme.onSurface) },
            text = {
                Column {
                    Text(tr("粘贴一个有 repo 权限的 Personal Access Token（github.com/settings/tokens）。仅存本机、走你自己的账号。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
                    com.arix.app.ui.XtomField(value = tokenInput, onValueChange = { tokenInput = it }, modifier = Modifier.fillMaxWidth(),
                        label = "Personal Access Token", singleLine = true, password = true,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                    if (err != null) { Spacer(Modifier.height(6.dp)); Text(err!!, color = scheme.error, fontSize = 11.sp) }
                }
            },
            confirmButton = {
                TextButton(enabled = !busy && tokenInput.isNotBlank(), onClick = {
                    busy = true; err = null
                    scope.launch {
                        val r = GitHubAccount.login(context, tokenInput)
                        busy = false
                        r.onSuccess { account = it; showLogin = false; onChanged() }
                            .onFailure { err = it.message ?: tr("登录失败") }
                    }
                }) { Text(if (busy) tr("验证中…") else tr("登录"), color = scheme.primary) }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { showLogin = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(20.dp),
        )
    }
}
