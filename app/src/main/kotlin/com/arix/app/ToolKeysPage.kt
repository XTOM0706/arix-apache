package com.arix.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.arix.app.ui.topChromeGapHeight

// 工具密钥：Home Assistant 的 Key，及 App 内提醒管理
@Composable fun ToolKeysPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    // HA：工具的描述与报错一直说「也可在设置里填」，但那个界面从来没实现过（HaPrefs 全仓零引用），
    // 于是唯一的配置路子是开口让 AI 调 action=config——等于逼用户把长效 token 念给模型听。补上。
    var haBase by remember { mutableStateOf(com.arix.tool.HaPrefs.baseUrl(context)) }
    var haToken by remember { mutableStateOf(com.arix.tool.HaPrefs.token(context)) }
    var reminders by remember { mutableStateOf(ReminderStore.all(context)) }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.topChromeGapHeight())   // 顶部悬浮玻璃让位：在滚动内容里，内容能从玻璃下滑过去化开
        // Home Assistant：基址 + 长效访问令牌。token 是长期有效的家庭网关凭证，用 password 遮起来。
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = scheme.surface), shape = MaterialTheme.shapes.extraLarge, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(tr("Home Assistant（智能家居）"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(tr("填了 home_assistant 工具才能列设备、查状态、开关灯/空调等。地址填 HA 的访问地址（如 http://192.168.1.10:8123）；令牌在 HA 的「个人资料 → 长效访问令牌」里创建。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
                com.arix.app.ui.XtomField(value = haBase, onValueChange = { haBase = it; com.arix.tool.HaPrefs.set(context, it, haToken) }, label = tr("HA 地址"), modifier = Modifier.fillMaxWidth(), textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                Spacer(Modifier.height(6.dp))
                com.arix.app.ui.XtomField(value = haToken, onValueChange = { haToken = it; com.arix.tool.HaPrefs.set(context, haBase, it) }, label = tr("长效访问令牌"), modifier = Modifier.fillMaxWidth(), password = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp))
                if (haBase.isNotBlank() && haToken.isNotBlank())
                    Text(tr("✔ 已配置，可以让 AI 控制家里的设备了"), color = scheme.primary, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 提醒管理：查看/取消 App 内定时提醒（原本只能让 AI 查/删）
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = scheme.surface), shape = MaterialTheme.shapes.extraLarge, elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(tr("提醒管理"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Text(tr("查看/取消 AI 帮你设的 App 内定时提醒（含循环提醒）。"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                    androidx.compose.material3.TextButton(onClick = { reminders = ReminderStore.all(context) }) { Text(tr("刷新"), fontSize = 12.sp) }
                }
                if (reminders.isEmpty()) Text(tr("暂无提醒"), color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
                else {
                    val rfmt = remember { java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.getDefault()) }
                    reminders.forEach { r ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(r.title, color = scheme.onSurface, fontSize = 13.sp)
                                val rep = when (r.repeat) { "hourly" -> "每小时"; "daily" -> "每天"; "weekly" -> "每周"; else -> "一次" }
                                Text("${rfmt.format(r.atMillis)} · $rep", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                            }
                            androidx.compose.material3.TextButton(onClick = { ReminderScheduler.cancel(context, r.id); reminders = ReminderStore.all(context) }) { Text(tr("取消"), color = scheme.error, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}
