package com.arix.stt

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 神经 TTS 模型管理：下载 + 解压 sherpa-onnx 的离线 TTS 模型(vits-melo-tts-zh_en，中英双语)。
 * 无 key、离线。运行时(sherpa native + Java 绑定)已随 stt 模块打入，此处只管模型文件。
 */
class TtsModelManager(private val context: Context) {

    companion object {
        const val MODEL_NAME = "vits-melo-tts-zh_en"
        // hf-mirror 国内可达，放首位（与 SttModelManager 同策略）；GitHub/官方 HF 作兜底（国内可能被墙）。
        private val URLS = listOf(
            "https://hf-mirror.com/csukuangfj/vits-melo-tts-zh_en/resolve/main/vits-melo-tts-zh_en.tar.bz2",
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-melo-tts-zh_en.tar.bz2",
            "https://huggingface.co/csukuangfj/vits-melo-tts-zh_en/resolve/main/vits-melo-tts-zh_en.tar.bz2",
        )
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    private val root = File(context.filesDir, "tts")
    fun modelDir(): File = File(root, MODEL_NAME)

    fun isReady(): Boolean {
        val d = modelDir()
        return File(d, "model.onnx").let { it.exists() && it.length() > 0 } && File(d, "tokens.txt").exists()
    }

    fun statusText(): String = if (isReady()) "已就绪（离线神经语音）" else "未下载（用系统语音）"

    suspend fun download(onProgress: (String) -> Unit): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            root.mkdirs()
            val archive = File(context.cacheDir, "$MODEL_NAME.tar.bz2")
            var ok = false
            var lastErr = ""
            for ((idx, url) in URLS.withIndex()) {
                val src = when { url.contains("hf-mirror") -> "hf-mirror"; url.contains("github") -> "github"; else -> "hf" }
                try {
                    onProgress("连接源 $src (${idx + 1}/${URLS.size})…")
                    client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        if (resp.code != 200) { lastErr = "$src HTTP ${resp.code}"; return@use }
                        val body = resp.body ?: run { lastErr = "$src 空响应"; return@use }
                        val total = body.contentLength()
                        var read = 0L; var lastPct = -5; var lastMb = -1L
                        body.byteStream().use { input ->
                            FileOutputStream(archive).use { out ->
                                val buf = ByteArray(65536); var n: Int
                                while (input.read(buf).also { n = it } != -1) {
                                    out.write(buf, 0, n); read += n
                                    if (total > 0) {
                                        val pct = (read * 100 / total).toInt()
                                        if (pct >= lastPct + 2) { lastPct = pct; onProgress("[$src] 下载: $pct% (${read / 1048576}MB)") }
                                    } else {
                                        val mb = read / 1048576
                                        if (mb > lastMb) { lastMb = mb; onProgress("[$src] 已下载 ${mb}MB…") }
                                    }
                                }
                            }
                        }
                        ok = archive.length() > 0
                    }
                    if (ok) break
                } catch (e: Exception) { lastErr = "$src: ${e.message ?: "下载失败"}" }
            }
            if (!ok) return@withContext Result.failure(Exception("下载失败: $lastErr"))

            onProgress("解压中…")
            extract(archive, root)
            archive.delete()
            if (isReady()) Result.success(Unit) else Result.failure(Exception("模型文件不完整"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extract(archive: File, destRoot: File) {
        BZip2CompressorInputStream(archive.inputStream().buffered(1 shl 16)).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var entry = tar.nextTarEntry
                while (entry != null) {
                    val out = File(destRoot, entry.name)
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { tar.copyTo(it) }
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
    }
}
