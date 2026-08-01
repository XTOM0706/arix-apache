package com.arix.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.theme.LocalXtomAccents
import com.arix.app.ui.XtomCard
import com.arix.app.ui.PageScaffold
import com.arix.tool.TtsTool
import kotlinx.coroutines.launch
import org.json.JSONObject

/** 语音朗读（TTS）：离线神经语音下载 + 试听。与语音识别(STT)分开。 */
@Composable fun TtsPage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    val ttsModelManager = remember { com.arix.stt.TtsModelManager(context) }
    val ttsTool = remember { TtsTool(context) }
    var ready by remember { mutableStateOf(ttsModelManager.isReady()) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var testing by remember { mutableStateOf(false) }
    var engine by remember { mutableStateOf(TtsTool.enginePref(context)) }
    var lastEngine by remember { mutableStateOf("") }
    var voice by remember { mutableStateOf(TtsTool.voicePref(context)) }
    var ttsCfg by remember { mutableStateOf<String?>(tr("检测中…")) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        ttsCfg = try { CloudApiConfigManager(context).getActiveByPurpose("tts")?.let { "${it.name} · ${it.model}" } } catch (_: Exception) { null }
    }
    val logs = remember { mutableStateListOf<String>() }
    val addLog: (String) -> Unit = { s -> logs.add(0, s); if (logs.size > 80) logs.removeAt(logs.size - 1) }

    PageScaffold {
        Text(tr("语音朗读 (TTS)"), color = scheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(tr("AI 把文字读出来。推荐 Edge 在线(免 key、音质好、无需下载)；离线神经需下模型；系统语音靠设备(部分机型无中文语音=没声)。"), color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))

        XtomCard {
            Text(tr("语音引擎"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                listOf("auto" to tr("自动"), "cloud" to tr("云端"), "neural" to tr("离线神经"), "edge" to tr("Edge在线"), "system" to tr("系统")).forEach { (key, label) ->
                    Button(
                        onClick = { engine = key; TtsTool.setEnginePref(context, key) },
                        colors = ButtonDefaults.buttonColors(containerColor = if (engine == key) scheme.primary else scheme.surfaceContainerHighest),
                        modifier = Modifier.height(34.dp), shape = RoundedCornerShape(14.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp),
                    ) { Text(label, color = if (engine == key) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 11.sp) }
                }
            }
            Text(tr("自动=离线神经(已下)→云端→系统。国内推荐「云端」：在模型配置建一个用途=「朗读」的配置(如硅基流动 CosyVoice)，走你能连通的网络。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
            if (engine == "cloud" || engine == "auto") {
                Spacer(Modifier.height(6.dp))
                if (ttsCfg != null) {
                    Text(tr("朗读配置 ✓：%s").format(ttsCfg), color = scheme.primary, fontSize = 11.sp)
                } else {
                    Text(tr("⚠ 未配置「朗读」模型 → 云端不可用。去『模型配置』新建一个配置，用途选「朗读」(如硅基流动 baseUrl=https://api.siliconflow.cn/v1，模型=FunAudioLLM/CosyVoice2-0.5B)。"), color = scheme.secondary, fontSize = 11.sp)
                }
                Spacer(Modifier.height(6.dp))
                com.arix.app.ui.XtomField(value = voice, onValueChange = { voice = it; TtsTool.setVoicePref(context, it) }, label = tr("音色 voice (硅基流动 CosyVoice 必填，如 FunAudioLLM/CosyVoice2-0.5B:alex)"))
            }
        }
        Spacer(Modifier.height(12.dp))

        XtomCard {
            Text(tr("离线神经语音（vits-melo · 中英 · 无 key）"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(ttsModelManager.statusText(), color = if (ready) scheme.primary else scheme.secondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            if (!ready && !downloading) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    downloading = true; progress = tr("准备下载…")
                    scope.launch {
                        addLog("── 下载模型 ──")
                        val r = ttsModelManager.download { p -> progress = p; addLog(p) }
                        downloading = false; ready = ttsModelManager.isReady()
                        progress = if (r.isSuccess) tr("下载完成 ✓") else tr("失败: %s").format(r.exceptionOrNull()?.message)
                        addLog(progress)
                    }
                }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                    Text(tr("下载神经语音模型（约几十 MB）"), color = scheme.onPrimary)
                }
            }
            if (downloading || progress.isNotBlank()) { Spacer(Modifier.height(6.dp)); Text(progress, color = scheme.primary, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
        }

        Spacer(Modifier.height(12.dp))
        XtomCard {
            Text(tr("试听"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Button(enabled = !testing, onClick = {
                testing = true; lastEngine = ""
                scope.launch {
                    addLog("── 试听开始 ──")
                    val used = try { ttsTool.speak("你好，我是 Arix 的语音朗读。", onLog = addLog) } catch (e: Exception) { addLog("试听异常: ${e.message}"); "fail" }
                    lastEngine = used; testing = false
                }
            }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
                Text(if (testing) tr("朗读中…") else tr("试听一句"), color = scheme.onPrimary)
            }
            if (lastEngine.isNotBlank()) {
                val label = when (lastEngine) { "neural" -> tr("离线神经语音 ✓"); "edge" -> tr("Edge 在线 ✓"); "system" -> tr("系统语音 ✓"); else -> tr("失败：无可用引擎/无网络") }
                Text(tr("刚才用了：%s").format(label), color = if (lastEngine == "fail") scheme.error else scheme.primary, fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }

        Spacer(Modifier.height(12.dp))
        XtomCard {
            androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(tr("日志"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { logs.clear() }) { Text(tr("清除"), color = scheme.onSurfaceVariant, fontSize = 11.sp) }
            }
            Column(Modifier.heightIn(min = 80.dp, max = 260.dp).verticalScroll(rememberScrollState())) {
                if (logs.isEmpty()) Text(tr("点「试听」或「下载模型」后，这里显示每一步（含失败原因/HTTP 状态/端点 URL），方便排查。"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                else logs.forEach { Text(it, color = scheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
