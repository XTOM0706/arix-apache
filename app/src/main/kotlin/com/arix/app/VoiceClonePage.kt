package com.arix.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.ui.XtomCard
import com.arix.app.ui.XtomField
import com.arix.app.ui.PageScaffold
import com.arix.tool.TtsTool
import com.arix.tool.VoiceCloneTool
import com.arix.tool.VoiceSampleRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App 内声音克隆：录一段/选一段样本音频 → 调 Minimax 或 硅基流动 的克隆 API → 得到 voice_id/uri →
 * 存进 TtsTool 偏好，复用现有 TTS 朗读。逻辑在 [VoiceCloneTool]，本页只做交互。
 */
@Composable
fun VoiceClonePage(
    scope: kotlinx.coroutines.CoroutineScope,
    context: android.content.Context,
    hasAudioPerm: Boolean,
    requestPerm: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme

    var provider by remember { mutableStateOf("minimax") }   // minimax / siliconflow

    // Minimax 字段（预填已配置的原生 TTS 参数）
    var mmxGroup by remember { mutableStateOf(TtsTool.mmxGroup(context)) }
    var mmxKey by remember { mutableStateOf(TtsTool.mmxKey(context)) }
    var mmxModel by remember { mutableStateOf(TtsTool.mmxModel(context)) }
    var desiredId by remember { mutableStateOf("") }

    // 硅基流动：读「朗读」用途配置
    var sfBase by remember { mutableStateOf("") }
    var sfKey by remember { mutableStateOf("") }
    var sfModel by remember { mutableStateOf("FunAudioLLM/CosyVoice2-0.5B") }
    var sfName by remember { mutableStateOf("") }
    var sfText by remember { mutableStateOf("") }
    var sfCfgLabel by remember { mutableStateOf<String?>(tr("检测中…")) }
    LaunchedEffect(Unit) {
        try {
            val cfg = CloudApiConfigManager(context).getActiveByPurpose("tts")
            if (cfg != null && cfg.baseUrl.isNotBlank()) {
                sfBase = cfg.baseUrl; sfKey = cfg.apiKey; sfModel = cfg.model.ifBlank { sfModel }
                sfCfgLabel = "${cfg.name} · ${cfg.model}"
            } else sfCfgLabel = null
        } catch (_: Exception) { sfCfgLabel = null }
    }

    // 样本音频（录音或选文件）
    val recorder = remember { VoiceSampleRecorder(context) }
    var recording by remember { mutableStateOf(false) }
    var recSec by remember { mutableStateOf(0) }
    var sampleFile by remember { mutableStateOf<File?>(null) }
    var sampleLabel by remember { mutableStateOf("") }

    var busy by remember { mutableStateOf(false) }
    var resultId by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    val logs = remember { mutableStateListOf<String>() }
    val addLog: (String) -> Unit = { s -> logs.add(0, s); if (logs.size > 80) logs.removeAt(logs.size - 1) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            val f = withContext(Dispatchers.IO) { VoiceCloneTool.copyUriToCache(context, uri) }
            if (f != null) { sampleFile = f; sampleLabel = tr("已选文件 %s（%dKB）").format(f.name, f.length() / 1024); addLog(sampleLabel) }
            else addLog("读取所选音频失败")
        }
    }

    // 录音计时
    LaunchedEffect(recording) {
        while (recording) { recSec = recorder.durationSec(); kotlinx.coroutines.delay(300) }
    }

    PageScaffold {
        Text(tr("声音克隆"), color = scheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(tr("录一段或选一段清晰人声（建议 10 秒以上、无杂音），上传到克隆服务，拿到专属音色后即可用来朗读。"), color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.padding(bottom = 8.dp))

        // 供应商
        XtomCard {
            Text(tr("克隆服务"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("minimax" to tr("Minimax 原生"), "siliconflow" to tr("硅基流动 CosyVoice")).forEach { (k, label) ->
                    Button(
                        onClick = { provider = k; resultId = null },
                        colors = ButtonDefaults.buttonColors(containerColor = if (provider == k) scheme.primary else scheme.surfaceContainerHighest),
                        modifier = Modifier.heightIn(min = 34.dp), shape = RoundedCornerShape(14.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) { Text(label, color = if (provider == k) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 11.sp) }
                }
            }
            Text(
                if (provider == "minimax") tr("Minimax：你给音色起个名字当 voice_id（≥8 位、字母开头），克隆后走「原生 Minimax」引擎朗读。")
                else tr("硅基流动：复用你在『模型配置』里配好的「朗读」用途（baseUrl/密钥），返回一个 uri 音色，走「云端」引擎朗读。"),
                color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(12.dp))

        // 参数
        XtomCard {
            Text(tr("参数"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            if (provider == "minimax") {
                XtomField(value = mmxGroup, onValueChange = { mmxGroup = it }, label = "GroupId")
                Spacer(Modifier.height(6.dp))
                XtomField(value = mmxKey, onValueChange = { mmxKey = it }, label = "API Key")
                Spacer(Modifier.height(6.dp))
                XtomField(value = mmxModel, onValueChange = { mmxModel = it }, label = tr("模型（如 speech-02-hd）"))
                Spacer(Modifier.height(6.dp))
                XtomField(value = desiredId, onValueChange = { desiredId = it.trim() }, label = tr("音色名 voice_id（≥8 位、字母开头，仅字母/数字/-/_）"))
            } else {
                if (sfCfgLabel != null) Text(tr("朗读配置 ✓：%s").format(sfCfgLabel), color = scheme.primary, fontSize = 11.sp)
                else Text(tr("⚠ 未配置「朗读」用途模型。去『模型配置』新建一个用途=「朗读」的配置（硅基流动 baseUrl=https://api.siliconflow.cn/v1）。"), color = scheme.secondary, fontSize = 11.sp)
                Spacer(Modifier.height(6.dp))
                XtomField(value = sfName, onValueChange = { sfName = it.trim() }, label = tr("音色名 customName"))
                Spacer(Modifier.height(6.dp))
                XtomField(value = sfText, onValueChange = { sfText = it }, label = tr("参考文本（样本音频里说的那句话，必填）"))
            }
        }
        Spacer(Modifier.height(12.dp))

        // 样本音频
        XtomCard {
            Text(tr("样本音频"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = {
                        if (!hasAudioPerm) { requestPerm(); addLog("需要麦克风权限"); return@Button }
                        if (!recording) {
                            if (recorder.start()) { recording = true; sampleLabel = ""; addLog("开始录音…") } else addLog("录音启动失败")
                        } else {
                            recording = false
                            val f = recorder.stop()
                            if (f != null) { sampleFile = f; sampleLabel = tr("已录制 %ds（%dKB）").format(recorder.durationSec(), f.length() / 1024); addLog(sampleLabel) }
                            else addLog("录音为空")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = if (recording) scheme.error else scheme.primary),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp), shape = RoundedCornerShape(14.dp),
                ) { Text(if (recording) tr("停止录音 %ds").format(recSec) else tr("录音"), color = scheme.onPrimary, fontSize = 12.sp) }
                Button(
                    onClick = { picker.launch("audio/*") }, enabled = !recording,
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest),
                    modifier = Modifier.weight(1f).heightIn(min = 40.dp), shape = RoundedCornerShape(14.dp),
                ) { Text(tr("选择音频文件"), color = scheme.onSurfaceVariant, fontSize = 12.sp) }
            }
            if (sampleLabel.isNotBlank()) Text(sampleLabel, color = scheme.primary, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(12.dp))

        // 开始克隆
        Button(
            enabled = !busy && !recording && sampleFile != null,
            onClick = {
                busy = true; resultId = null
                scope.launch {
                    addLog("── 开始克隆(${provider}) ──")
                    val r = if (provider == "minimax")
                        VoiceCloneTool.cloneMinimax(context, mmxGroup, mmxKey, mmxModel, sampleFile!!, desiredId, desiredId, addLog)
                    else
                        VoiceCloneTool.cloneSiliconFlow(context, sfBase, sfKey, sfModel, sampleFile!!, sfName, sfText, addLog)
                    busy = false
                    if (r.ok) { resultId = r.id; addLog("✓ ${r.message}") } else addLog("✗ ${r.message}")
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
            modifier = Modifier.fillMaxWidth().heightIn(min = 46.dp), shape = RoundedCornerShape(14.dp),
        ) { Text(if (busy) tr("克隆中…") else tr("开始克隆"), color = scheme.onPrimary) }

        // 结果 + 设为当前音色
        resultId?.let { id ->
            Spacer(Modifier.height(12.dp))
            XtomCard {
                Text(tr("克隆成功"), color = scheme.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(id, color = scheme.onSurface, fontSize = 11.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(vertical = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            if (provider == "minimax") {
                                TtsTool.setMinimax(context, mmxGroup, mmxKey, id, mmxModel); TtsTool.setEnginePref(context, "minimax")
                            } else { TtsTool.setVoicePref(context, id); TtsTool.setEnginePref(context, "cloud") }
                            addLog("已设为当前音色（引擎已切到 ${if (provider == "minimax") "Minimax" else "云端"}）")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.primary),
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp), shape = RoundedCornerShape(14.dp),
                    ) { Text(tr("设为当前音色"), color = scheme.onPrimary, fontSize = 12.sp) }
                    Button(
                        enabled = !testing,
                        onClick = {
                            testing = true
                            scope.launch {
                                val used = try { TtsTool(context).speak("你好，这是用我的克隆音色朗读的一句话。", onLog = addLog) }
                                catch (e: Exception) { addLog("试听异常: ${e.message}"); "fail" }
                                addLog("试听引擎: $used"); testing = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest),
                        modifier = Modifier.weight(1f).heightIn(min = 40.dp), shape = RoundedCornerShape(14.dp),
                    ) { Text(if (testing) tr("朗读中…") else tr("试听"), color = scheme.onSurfaceVariant, fontSize = 12.sp) }
                }
            }
        }

        // 历史
        val hist = remember(resultId) { VoiceCloneTool.history(context) }
        if (hist.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            XtomCard {
                Text(tr("已克隆音色"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                hist.forEach { v ->
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(v.name, color = scheme.onSurface, fontSize = 12.sp)
                            Text("${v.provider} · ${v.id}", color = scheme.onSurfaceVariant, fontSize = 9.sp, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        TextButton(onClick = {
                            if (v.provider == "minimax") { TtsTool.setMinimax(context, mmxGroup, mmxKey, v.id, mmxModel); TtsTool.setEnginePref(context, "minimax") }
                            else { TtsTool.setVoicePref(context, v.id); TtsTool.setEnginePref(context, "cloud") }
                            addLog("已切换到音色：${v.name}")
                        }) { Text(tr("使用"), color = scheme.primary, fontSize = 11.sp) }
                    }
                }
            }
        }

        // 日志
        Spacer(Modifier.height(12.dp))
        XtomCard {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(tr("日志"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { logs.clear() }) { Text(tr("清除"), color = scheme.onSurfaceVariant, fontSize = 11.sp) }
            }
            Column(Modifier.heightIn(min = 60.dp, max = 240.dp).verticalScroll(rememberScrollState())) {
                if (logs.isEmpty()) Text(tr("克隆过程（上传/HTTP 状态/错误原因）会显示在这里，便于排查。"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                else logs.forEach { Text(it, color = scheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
            }
        }
    }
}
