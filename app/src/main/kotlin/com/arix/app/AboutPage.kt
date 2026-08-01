package com.arix.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VolunteerActivism
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.ui.XtomCard
import com.arix.app.ui.PageScaffold

/**
 * 本仓库（Apache-2.0 精简版）。想要完整功能，请用 GPL 满血版（见下方引导）。
 */
private const val SOURCE_URL = "https://github.com/XTOM0706/arix-apache"

/**
 * 赞助/支持入口。**留空即隐藏为「暂未开放」**，不会显示一个点不开的死链。
 */
private const val SPONSOR_URL = ""

/** 对外联系邮箱（≠ 推 GitHub 用的那个）。写成常量，免得地址散在几处、改一处漏一处。 */
private const val DEVELOPER_EMAIL = "tomrz666@qq.com"


/**
 * 「关于软件」：版本 / 开源许可 / GPL 满血版引导 / 源码地址 / 开源致谢。
 */
@Composable fun AboutPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    val version = remember {
        try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) { "1.0" }
    }

    // 逐项核实的第三方许可（name to license）
    val notices = listOf(
        "ONNX Runtime" to "MIT",
        "sherpa-onnx（语音识别）" to "Apache-2.0",
        "Silero VAD 模型" to "MIT",
        "microWakeWord（唤醒模型/框架）" to "Apache-2.0",
        "TensorFlow Lite / LiteRT" to "Apache-2.0",
        "Jetpack Compose · AndroidX" to "Apache-2.0",
        "Kotlin · Coroutines" to "Apache-2.0",
        "Coil（图片加载）" to "Apache-2.0",
        "OkHttp（网络）" to "Apache-2.0",
    )

    PageScaffold {
        // 应用详细信息：名称 / 代号版本 / 开发者与联系方式，全部并在页首这一块里。
        // 不给开发者单开一张卡——那是「一件事拆成两处说」，用户找联系方式时还得往下翻。
        Text("Arix", color = scheme.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(tr("代号「涅槃」· 版本 %s").format(version), color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
        Row(
            modifier = Modifier.padding(top = 2.dp, bottom = 12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Text("${tr("开发者")} XTOM · ", color = scheme.onSurfaceVariant, fontSize = 12.sp)
            // 点一下直接起邮件客户端。没装邮件 App 时 startActivity 会抛，吞掉就是「点了没反应」，
            // 所以兜底把地址复制到剪贴板并说一声——别让用户以为这行字是死的。
            Text(
                DEVELOPER_EMAIL,
                color = scheme.primary,
                fontSize = 12.sp,
                modifier = Modifier.clickable {
                    val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$DEVELOPER_EMAIL"))
                    try {
                        context.startActivity(mail.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("email", DEVELOPER_EMAIL))
                        android.widget.Toast.makeText(
                            context, tr("没找到邮件应用，地址已复制"), android.widget.Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }

        // 精简版说明 + GPL 满血版引导：这是 Apache-2.0 精简版，完整功能在 GPL 版里。
        XtomCard {
            Text(tr("这是精简版"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(tr("本版为 Apache-2.0 精简版，移除了部分独有能力（超级岛/语音通话/角色扮演/记忆图谱/云端市场等），方便二次开发与内置到其它系统。"), color = scheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            Text(tr("想要完整功能，请改用 GPL 满血版"), color = scheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(tr("满血版保留全部能力，适合日常使用。更新与下载入口见官方仓库。"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
        }

        Spacer(Modifier.height(12.dp))
        XtomCard {
            Text(tr("开源许可"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(tr("本应用采用 Apache License 2.0。你可以自由使用、修改、分发，包括商用；仅需保留版权声明与 NOTICE。"), color = scheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(tr("源码（精简版仓库）"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
            Text(
                SOURCE_URL, color = scheme.primary, fontSize = 12.sp,
                modifier = Modifier.padding(top = 2.dp).clickable {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SOURCE_URL))
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                },
            )
        }

        // 赞助/支持：竞品都有，我们也给一个——但它永远只是「自愿支持」，不挂任何解锁。
        // 链接没填时显示为不可点的「暂未开放」，不给用户一个 404。
        Spacer(Modifier.height(12.dp))
        val sponsorOpen: (() -> Unit)? = if (SPONSOR_URL.isBlank()) null else {
            {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(SPONSOR_URL))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
                Unit
            }
        }
        XtomCard(onClick = sponsorOpen) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.VolunteerActivism,
                    contentDescription = null,
                    tint = scheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(tr("赞助 / 支持"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    Text(
                        if (SPONSOR_URL.isNotBlank()) SPONSOR_URL else tr("暂未开放"),
                        color = if (SPONSOR_URL.isNotBlank()) scheme.primary else scheme.onSurfaceVariant,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(top = 1.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(tr("赞助只是自愿支持开发，不解锁任何功能——所有功能对所有人一直都是全开的。"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
        }

        Spacer(Modifier.height(12.dp))
        Text(tr("开源致谢"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(tr("感谢以下开源项目。各自版权与许可归原作者所有。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        XtomCard {
            notices.forEachIndexed { i, (name, license) ->
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(tr(name), color = scheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Text(license, color = scheme.primary, fontSize = 11.sp)
                }
                if (i < notices.lastIndex) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            }
        }
    }
}
