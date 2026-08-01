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
 * 正版仓库。AGPL §13 要求网络交互的用户也能取得源码，故此地址必须在 UI 里可见可点。
 *
 * 该仓库已公开（2026-07-30），这个地址点开就是源码本体，满足 AGPL 的「源码必须可获取」。
 * ⚠️ 换仓库地址时，LICENSE.md 与 UpdateCheckPage.REPO 里的同一地址要一并改——
 * 那两处一个管署名义务、一个管检查更新，漏掉任一处都是静默失效。
 */
private const val SOURCE_URL = "https://github.com/XTOM0706/arix-app"

/**
 * 赞助/支持入口。**留空即隐藏为「暂未开放」**，不会显示一个点不开的死链。
 * 想开放时把地址填进来即可（爱发电 / Ko-fi / GitHub Sponsors / 收款码页都行）。
 *
 * AGPL 明确允许为软件收费或接受捐赠，所以这个入口本身不与许可冲突；
 * 但它是「自愿支持」，不能变成功能墙——别在这里挂任何解锁/付费才能用的东西。
 */
private const val SPONSOR_URL = ""

/** 对外联系邮箱（≠ 推 GitHub 用的那个）。写成常量，免得地址散在几处、改一处漏一处。 */
private const val DEVELOPER_EMAIL = "tomrz666@qq.com"


/**
 * 「关于软件」：版本 / 开源许可 / 源码地址 / 开源致谢(OSS notices)。见 TODO-V1 发布合规。
 * 致谢的许可信息已逐项核实——JLaTeXMath 为 GPL-2.0「带链接例外」，明文豁免版本兼容问题，
 * 故可与本 AGPL-3.0 应用捆绑（未改其源码）。
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
        "JLaTeXMath-Android（公式渲染）" to "GPL-2.0 带链接例外",
    )

    // 借鉴 Operit 市场作者思路的功能 → 原作者（放「关于」页统一致谢）
    val marketCredits = listOf(
        "情绪状态卡（Moodlet）" to "watersalt0305 · HazeBar by yanjun62",
        "模型分级路由（省钱）" to "model-hierarchy-skill · zscole",
        "各平台热榜（hotnews）" to "lkywylzz · 数据源 vikiboss/60s",
        "语音服务商（Minimax/克隆思路）" to "SUNNYFREE0618 · do-do026 · FrancisVael · YesseniaCQ",
        "Gadgetbridge 手表健康快照" to "42100214lei-design（黎深 & Lei）",
        "小红书图文阅读" to "RaineIris",
        "读一首歌（Soundprint 声纹）" to "yuyixuanfu",
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

        // AGPL §13 要求：通过网络与本程序交互的用户也必须能取得源码。所以源码地址必须
        // 出现在用户看得到的地方，不能只写在仓库的 LICENSE 里。这张卡就是那个「看得到的地方」。
        XtomCard {
            Text(tr("开源许可"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(tr("本应用采用 GNU AGPL v3.0。你可以自由使用、修改、分发，也可以收费或接受捐赠；但修改版必须同样以 AGPL 开源、保留原作者署名并标明「已修改」。"), color = scheme.onSurfaceVariant, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            Text(tr("源码（正版仓库）"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
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
            Spacer(Modifier.height(6.dp))
            Text(tr("「Arix」名称与图标不在本许可授权范围内——可自由分发修改版，但须改名。"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
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

        Spacer(Modifier.height(12.dp))
        Text(tr("借鉴致谢"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
        Text(tr("Arix 下列功能借鉴了 Operit 云端市场作者们的思路，在此一并致谢。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp))
        XtomCard {
            marketCredits.forEachIndexed { i, (feat, who) ->
                Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Text(tr(feat), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    Text(who, color = scheme.primary, fontSize = 11.sp, modifier = Modifier.padding(top = 1.dp))
                }
                if (i < marketCredits.lastIndex) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f))
            }
        }

        Spacer(Modifier.height(12.dp))
        Text(tr("说明：JLaTeXMath-Android 为 GPL-2.0「带链接例外」——其许可明文允许与任意许可的独立模块链接并按自选条款分发，故可在本应用中捆绑使用（不修改其自身源码）。其内置字体分别为 OFL / 公共领域 / Knuth 许可。"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
    }
}
