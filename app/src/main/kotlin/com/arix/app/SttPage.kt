package com.arix.app

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.theme.LocalXtomAccents
import com.arix.app.ui.XtomCard
import com.arix.app.ui.XtomField
import com.arix.app.ui.PageScaffold
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.WhisperClient
import com.arix.stt.SttEngine
import com.arix.stt.SttModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable fun SttPage(addLog: (LogEntry) -> Unit, ts: () -> String, scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context, hasAudioPerm: Boolean, requestPerm: () -> Unit) {
    val modelManager = remember { SttModelManager(context) }
    var engine by remember { mutableStateOf<SttEngine?>(null) }; var engineLang by remember { mutableStateOf("") }
    var modelReady by remember { mutableStateOf(modelManager.isModelReady("zh")) }
    var isPreparing by remember { mutableStateOf(false) }; var prepProgress by remember { mutableStateOf("") }; var downloadingLang by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }; var isRecognizing by remember { mutableStateOf(false) }; var recordingSeconds by remember { mutableLongStateOf(0L) }
    // 从持久化 SttPrefs 载入（跨页共享，唤醒助手/语音输入复用同一份）
    var sttProvider by remember { mutableStateOf(SttPrefs.provider(context)) }; var sttLang by remember { mutableStateOf(SttPrefs.lang(context)) }
    var whisperApiKey by remember { mutableStateOf(SttPrefs.apiKey(context)) }; var customBaseUrl by remember { mutableStateOf(SttPrefs.customBaseUrl(context)) }
    var customModel by remember { mutableStateOf(SttPrefs.customModel(context)) }
    var sttResult by remember { mutableStateOf("") }

    LaunchedEffect(sttProvider, sttLang) { if (sttProvider == "local" && sttLang == "zh" && SttModelManager.modelForLang("zh")?.bundled == true && !modelManager.isModelReady("zh") && !isPreparing && downloadingLang.isEmpty()) { isPreparing = true; val result = modelManager.copyBundledModel("zh") { progress -> prepProgress = progress }; isPreparing = false; if (result.isSuccess) modelReady = true } }
    DisposableEffect(Unit) { onDispose { engine?.release() } }
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current

    PageScaffold {
        XtomCard {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { Text(tr("引擎: "), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Button(onClick = { sttProvider = "siliconflow"; SttPrefs.setProvider(context, "siliconflow") }, colors = ButtonDefaults.buttonColors(containerColor = if (sttProvider == "siliconflow") scheme.primary else scheme.surfaceContainerHighest), modifier = Modifier.heightIn(min = 30.dp), shape = RoundedCornerShape(14.dp)) { Text(tr("硅基流动(免费)"), color = if (sttProvider == "siliconflow") scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 11.sp) }; Spacer(modifier = Modifier.width(3.dp))
                Button(onClick = { sttProvider = "groq"; SttPrefs.setProvider(context, "groq") }, colors = ButtonDefaults.buttonColors(containerColor = if (sttProvider == "groq") scheme.primary else scheme.surfaceContainerHighest), modifier = Modifier.heightIn(min = 30.dp), shape = RoundedCornerShape(14.dp)) { Text(tr("Groq(免费)"), color = if (sttProvider == "groq") scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 11.sp) }; Spacer(modifier = Modifier.width(3.dp))
                Button(onClick = { sttProvider = "custom"; SttPrefs.setProvider(context, "custom") }, colors = ButtonDefaults.buttonColors(containerColor = if (sttProvider == "custom") scheme.primary else scheme.surfaceContainerHighest), modifier = Modifier.heightIn(min = 30.dp), shape = RoundedCornerShape(14.dp)) { Text(tr("自建API"), color = if (sttProvider == "custom") scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 11.sp) }; Spacer(modifier = Modifier.width(3.dp))
                Button(onClick = { sttProvider = "local"; SttPrefs.setProvider(context, "local") }, colors = ButtonDefaults.buttonColors(containerColor = if (sttProvider == "local") scheme.primary else scheme.surfaceContainerHighest), modifier = Modifier.heightIn(min = 30.dp), shape = RoundedCornerShape(14.dp)) { Text(tr("本地"), color = if (sttProvider == "local") scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 11.sp) } }
        }
        Spacer(modifier = Modifier.height(8.dp))
        XtomCard {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) { Text(tr("语言: "), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                listOf("zh" to tr("中文"),"en" to "English","mix" to tr("中+英"),"pt" to "Português","vi" to "Tiếng Việt","ar" to "العربية","id" to "Indonesia","ja" to "日本語","ru" to "Русский","th" to "ภาษาไทย").forEach { (code, label) ->
                    Button(onClick = { sttLang = code; SttPrefs.setLang(context, code) }, colors = ButtonDefaults.buttonColors(containerColor = if (sttLang == code) scheme.primary else scheme.surfaceContainerHighest), modifier = Modifier.heightIn(min = 30.dp), shape = RoundedCornerShape(14.dp)) { Text(label, color = if (sttLang == code) scheme.onPrimary else scheme.onSurfaceVariant, fontSize = 11.sp) }; Spacer(modifier = Modifier.width(3.dp)) } }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (sttProvider == "siliconflow" || sttProvider == "groq" || sttProvider == "custom") { XtomCard {
            when (sttProvider) {
                "siliconflow" -> { Text(tr("硅基流动 SenseVoiceSmall (免费·中文强·国内直连)"), color = scheme.primary, fontSize = 13.sp); Text(tr("siliconflow.cn 注册 → API 密钥；免费模型 FunAudioLLM/SenseVoiceSmall"), color = scheme.onSurfaceVariant, fontSize = 11.sp) }
                "groq" -> { Text(tr("Groq Whisper (免费·快·国内需代理)"), color = scheme.primary, fontSize = 13.sp); Text(tr("console.groq.com → API Keys → 创建"), color = scheme.onSurfaceVariant, fontSize = 11.sp) }
                else -> { Text(tr("自定义 Whisper API (OpenAI 兼容)"), color = scheme.secondary, fontSize = 13.sp); Spacer(modifier = Modifier.height(4.dp))
                    XtomField(value = customBaseUrl, onValueChange = { customBaseUrl = it; SttPrefs.setCustomBaseUrl(context, it) }, label = "API Base URL", modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Spacer(modifier = Modifier.height(6.dp)); XtomField(value = customModel, onValueChange = { customModel = it; SttPrefs.setCustomModel(context, it) }, label = tr("模型 (如 whisper-1)"), modifier = Modifier.fillMaxWidth(), singleLine = true) }
            }
            Spacer(modifier = Modifier.height(6.dp)); XtomField(value = whisperApiKey, onValueChange = { whisperApiKey = it; SttPrefs.setApiKey(context, it) }, label = "API Key", modifier = Modifier.fillMaxWidth(), singleLine = true, password = true) }; Spacer(modifier = Modifier.height(8.dp)) }
        if (sttProvider == "local") { val curLangModel = SttModelManager.modelForLang(sttLang)!!; val curReady = modelManager.isModelReady(sttLang); val isBundled = curLangModel.bundled; XtomCard {
            Text(tr("本地模型"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(4.dp)); Text(modelManager.modelStatusText(sttLang), color = if (curReady) scheme.primary else scheme.secondary, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            if (isPreparing || downloadingLang.isNotEmpty()) { Spacer(modifier = Modifier.height(4.dp)); LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = scheme.primary); Text(prepProgress, color = scheme.onSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace) }
            if (!isBundled && !curReady && downloadingLang.isEmpty() && !isPreparing) { Spacer(modifier = Modifier.height(8.dp)); Button(onClick = { downloadingLang = sttLang; scope.launch { val result = modelManager.downloadModel(sttLang) { p -> prepProgress = p }; downloadingLang = ""; if (result.isSuccess) modelReady = true } }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(tr("下载 %s 模型").format(curLangModel.label), color = scheme.onPrimary) } }
            if (curReady && (engine == null || engineLang != sttLang)) { Spacer(modifier = Modifier.height(8.dp)); Button(onClick = { scope.launch { val eng = SttEngine(modelManager.modelDir(sttLang), curLangModel); val result = withContext(Dispatchers.IO) { eng.load() }; if (result.isSuccess) { engine?.release(); engine = eng; engineLang = sttLang; modelReady = true } } }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(tr("加载模型"), color = scheme.onPrimary) } }
        }; Spacer(modifier = Modifier.height(8.dp)) }
        val curReady = modelManager.isModelReady(sttLang); val canRecord = when (sttProvider) { "local" -> hasAudioPerm && curReady && engine != null && !isPreparing && downloadingLang.isEmpty(); "groq" -> hasAudioPerm && whisperApiKey.isNotBlank(); else -> hasAudioPerm && whisperApiKey.isNotBlank() }
        XtomCard {
            Text(tr("语音录制"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(4.dp))
            if (!hasAudioPerm) { Button(onClick = requestPerm, colors = ButtonDefaults.buttonColors(containerColor = scheme.secondary), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) { Text(tr("授予录音权限"), color = scheme.onPrimary) } }
            else if (!canRecord) { Text(when { sttProvider == "local" && !curReady -> tr("请先下载并加载模型"); sttProvider == "local" && engine == null -> tr("请先加载模型"); sttProvider == "local" && isPreparing -> tr("正在准备模型..."); sttProvider == "local" && downloadingLang.isNotEmpty() -> tr("正在下载模型..."); else -> tr("请填写 API Key") }, color = scheme.onSurfaceVariant, fontSize = 13.sp) }
            else { Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { if (isRecording) return@Button; isRecording = true; val startedAt = System.currentTimeMillis(); scope.launch(Dispatchers.IO) { val sampleRate = 16000; val bufferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(sampleRate / 10); val recorder = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize * 2); if (recorder.state != AudioRecord.STATE_INITIALIZED) { withContext(Dispatchers.Main) { isRecording = false }; return@launch }; val samples = mutableListOf<Float>(); val shortBuf = ShortArray(bufferSize); try { recorder.startRecording(); while (isActive && isRecording) { val read = recorder.read(shortBuf, 0, shortBuf.size); if (read > 0) for (i in 0 until read) samples.add(shortBuf[i] / 32768f); val elapsed = (System.currentTimeMillis() - startedAt) / 1000; if (elapsed != recordingSeconds) withContext(Dispatchers.Main) { recordingSeconds = elapsed }; if (elapsed >= 15) break } } finally { try { recorder.stop() } catch (_: Exception) {}; recorder.release() }; withContext(Dispatchers.Main) { isRecording = false; isRecognizing = true }; val cloud = SttPrefs.resolveCloud(context); if (cloud != null) { val wConfig = CloudApiConfig(cloud.baseUrl, cloud.apiKey, cloud.model); val whisperClient = WhisperClient(wConfig); val result = whisperClient.transcribe(samples.toFloatArray(), sampleRate, sttLang); withContext(Dispatchers.Main) { isRecognizing = false; if (result.error != null) sttResult = tr("错误: %s").format(result.error) else sttResult = result.text } } else { val eng = engine ?: return@launch; val result = eng.recognize(samples.toFloatArray(), sampleRate); withContext(Dispatchers.Main) { isRecognizing = false; if (result.isSuccess) sttResult = result.getOrThrow().text else sttResult = tr("错误: %s").format(result.exceptionOrNull()?.message) } } } }, enabled = canRecord && !isRecognizing, colors = ButtonDefaults.buttonColors(containerColor = scheme.error), shape = RoundedCornerShape(14.dp)) { Text(if (isRecognizing) tr("识别中...") else tr("开始录音"), color = scheme.onPrimary) }
                if (isRecording) { Spacer(modifier = Modifier.width(8.dp)); Button(onClick = { isRecording = false }, colors = ButtonDefaults.buttonColors(containerColor = scheme.secondary), shape = RoundedCornerShape(14.dp)) { Text(tr("停止"), color = scheme.onPrimary) } }
            }
            if (isRecording) { Spacer(modifier = Modifier.height(4.dp)); Text(tr("录音中: %ds").format(recordingSeconds), color = scheme.error, fontSize = 13.sp, fontFamily = FontFamily.Monospace) } }
        }
        if (sttResult.isNotEmpty()) { Spacer(modifier = Modifier.height(8.dp)); XtomCard { Text(tr("识别结果"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold); Spacer(modifier = Modifier.height(4.dp)); Text(sttResult, color = scheme.onSurface, fontSize = 15.sp); Spacer(modifier = Modifier.height(6.dp)); Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) { TextButton(onClick = { sttResult = "" }) { Text(tr("清除"), color = scheme.onSurfaceVariant, fontSize = 11.sp) } } } }
    }
} // end SttPage
