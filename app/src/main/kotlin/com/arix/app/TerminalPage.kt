package com.arix.app

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.tool.TerminalClient
import com.arix.app.ui.topChromeGapHeight

/**
 * 「终端」入口页：真正的终端是独立「Arix 终端」App 里的 TermActivity
 * （Termux TerminalView + proot：颜色/光标/vim/top/termux-change-repo 全支持，apt 随便装）。
 * 装了就一键拉起它；没装就引导安装。AI 的 linux_exec 走同一 proot 环境（经绑定服务）。
 */
@Composable
fun TerminalPage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    var installed by remember { mutableStateOf(TerminalClient.isInstalled(context)) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf(0f) }   // -1=不确定（源没给 Content-Length）
    var error by remember { mutableStateOf<String?>(null) }
    // 发布仓库还没公开：没配下载基址就只能手动侧载，别给个点了必报错的按钮

    // 从系统安装器/桌面回来时刷新装没装（不然装完还显示"未安装"）
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) installed = TerminalClient.isInstalled(context)
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0E14)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Spacer(Modifier.topChromeGapHeight())
        Text(tr("Arix 终端"), color = Color(0xFFD6DEEB), fontSize = 20.sp)
        Spacer(Modifier.height(14.dp))

        if (installed) {
            Text(
                tr("完整 Linux 终端（proot + 原版 Termux）：颜色/光标、vim/top、termux-change-repo 都能用，apt/pkg 随便装不打架。"),
                color = Color(0xFF7A8699), fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(20.dp))
            Button(
                onClick = {
                    runCatching {
                        val i = Intent().setClassName("com.arix.terminal", "com.arix.terminal.TermActivity")   // 包名=新应用 id，类名仍是 Kotlin 包路径
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(i)
                    }
                },
                modifier = Modifier.fillMaxWidth(0.7f),
            ) { Text(tr("打开终端")) }
        } else {
            Text(
                tr("终端在独立「Arix 终端」App 里（proot + 原版 Termux）。点下面下载安装（约 15MB，国内走镜像加速）；装好回本页即可打开。"),
                color = Color(0xFF7A8699), fontSize = 13.sp, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = {
                    if (busy) return@Button
                    busy = true; error = null; progress = 0f
                    scope.launch {
                        error = com.arix.tool.TerminalInstaller.install(context) { p -> progress = p }
                        busy = false
                        // 系统安装器是另一个界面，回来时刷新安装状态
                        installed = TerminalClient.isInstalled(context)
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(0.7f),
            ) {
                Text(
                    when {
                        !busy -> tr("下载并安装终端")
                        progress < 0f -> tr("下载中…")
                        else -> "${tr("下载中")} ${(progress * 100).toInt()}%"
                    }
                )
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                Text(it, color = Color(0xFFE08A8A), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
        }
    }
}
