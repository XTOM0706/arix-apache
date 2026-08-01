package com.arix.stt

import android.content.Context
import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

enum class SttModelType { TRANSDUCER, ZIPFORMER2_CTC }

data class LanguageModel(
    val lang: String,
    val label: String,
    val modelName: String,
    val downloadUrl: String,
    val modelFiles: List<String>,
    val bundled: Boolean = false,
    val assetSubdir: String = "",
    val modelType: SttModelType = SttModelType.TRANSDUCER,
    val sha256: String? = null,
    val hfFiles: List<String>? = null,  // HuggingFace individual filenames; if set, takes priority over tar.bz2 download
    val modelRoot: String? = null      // shared model directory; if set, modelDir returns this root for all langs using it
)

class SttModelManager(private val context: Context) {

    companion object {
        val LANGUAGES = listOf(
            // 中文模型 26MB，曾随包携带（assets/models/zipformer）——现改为**按需下载**：
            // 它是第三方产物（k2-fsa/sherpa-onnx，Apache-2.0），没道理让每个用户都先扛 26MB 安装包，
            // 何况云端识别才是默认引擎。assetSubdir 保留：assets 里真放了就仍走 copyBundledModel。
            LanguageModel(
                lang = "zh", label = "中文", bundled = false,
                modelName = "sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01",
                downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-small-ctc-zh-int8-2025-04-01.tar.bz2",
                modelFiles = listOf("model.int8.onnx", "tokens.txt"),
                assetSubdir = "models/zipformer",
                modelType = SttModelType.ZIPFORMER2_CTC
            ),
            LanguageModel(
                lang = "en", label = "English",
                modelName = "sherpa-onnx-streaming-zipformer-en-2023-06-26",
                downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2",
                modelFiles = listOf("encoder", "decoder", "joiner", "tokens.txt")
            ),
            LanguageModel(
                lang = "pt", label = "Português",
                modelName = "pt/kroko_64l",
                downloadUrl = "https://huggingface.co/hudaiapa88/sherpa-stt-onnx/resolve/main/pt/kroko_64l",
                modelFiles = listOf("encoder", "decoder", "joiner", "tokens.txt"),
                sha256 = null,
                hfFiles = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt")
            ),
            // --- 8-language shared model (ar/en/id/ja/ru/th/vi/zh) ---
            LanguageModel(
                lang = "vi", label = "Tiếng Việt",
                modelName = "streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
                downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main",
                modelFiles = listOf("encoder", "decoder", "joiner", "tokens.txt"),
                sha256 = null,
                hfFiles = listOf("encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "decoder-epoch-75-avg-11-chunk-16-left-128.onnx", "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "tokens.txt"),
                modelRoot = "multi"
            ),
            LanguageModel(
                lang = "ar", label = "العربية",
                modelName = "streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
                downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main",
                modelFiles = listOf("encoder", "decoder", "joiner", "tokens.txt"),
                sha256 = null,
                hfFiles = listOf("encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "decoder-epoch-75-avg-11-chunk-16-left-128.onnx", "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "tokens.txt"),
                modelRoot = "multi"
            ),
            LanguageModel(
                lang = "id", label = "Indonesia",
                modelName = "streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
                downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main",
                modelFiles = listOf("encoder", "decoder", "joiner", "tokens.txt"),
                sha256 = null,
                hfFiles = listOf("encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "decoder-epoch-75-avg-11-chunk-16-left-128.onnx", "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "tokens.txt"),
                modelRoot = "multi"
            ),
            LanguageModel(
                lang = "ja", label = "日本語",
                modelName = "streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
                downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main",
                modelFiles = listOf("encoder", "decoder", "joiner", "tokens.txt"),
                sha256 = null,
                hfFiles = listOf("encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "decoder-epoch-75-avg-11-chunk-16-left-128.onnx", "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "tokens.txt"),
                modelRoot = "multi"
            ),
            LanguageModel(
                lang = "ru", label = "Русский",
                modelName = "streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
                downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main",
                modelFiles = listOf("encoder", "decoder", "joiner", "tokens.txt"),
                sha256 = null,
                hfFiles = listOf("encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "decoder-epoch-75-avg-11-chunk-16-left-128.onnx", "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "tokens.txt"),
                modelRoot = "multi"
            ),
            LanguageModel(
                lang = "th", label = "ภาษาไทย",
                modelName = "streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10",
                downloadUrl = "https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-ar_en_id_ja_ru_th_vi_zh-2025-02-10/resolve/main",
                modelFiles = listOf("encoder", "decoder", "joiner", "tokens.txt"),
                sha256 = null,
                hfFiles = listOf("encoder-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "decoder-epoch-75-avg-11-chunk-16-left-128.onnx", "joiner-epoch-75-avg-11-chunk-16-left-128.int8.onnx", "tokens.txt"),
                modelRoot = "multi"
            ),
            LanguageModel(
                lang = "mix", label = "混合(中+英)",
                modelName = "sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20",
                downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2",
                modelFiles = listOf("encoder", "decoder", "joiner", "tokens.txt")
            )
        )

        fun modelForLang(lang: String) = LANGUAGES.find { it.lang == lang }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(600, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun modelDir(lang: String): File {
        val info = modelForLang(lang)
        val root = info?.modelRoot ?: lang
        return File(context.filesDir, "models/$root").also { it.mkdirs() }
    }

    fun isModelReady(lang: String): Boolean {
        val info = modelForLang(lang) ?: return false
        val dir = modelDir(lang)
        val files = dir.listFiles() ?: emptyArray()
        return info.modelFiles.all { pattern ->
            files.any { f -> f.isFile && f.length() > 0 && matchesPattern(f.name, pattern) }
        }
    }

    fun modelStatusText(lang: String): String {
        val info = modelForLang(lang) ?: return "?"
        val dir = modelDir(lang)
        val files = dir.listFiles() ?: emptyArray()
        val missing = info.modelFiles.filter { pattern ->
            files.none { f -> f.isFile && matchesPattern(f.name, pattern) }
        }
        if (missing.isNotEmpty()) return "未下载 (${info.modelName})"
        val total = files.filter { it.isFile }.sumOf { it.length() }
        return "就绪 (${formatSize(total)})"
    }

    suspend fun copyBundledModel(lang: String, onProgress: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val info = modelForLang(lang) ?: return@withContext Result.failure(Exception("Unknown lang: $lang"))
                val dir = modelDir(lang)
                dir.mkdirs()

                for (file in info.modelFiles) {
                    val dest = File(dir, file)
                    if (!dest.exists()) {
                        onProgress("复制 $file...")
                        copyAsset("${info.assetSubdir}/$file", dest)
                    }
                }
                val total = info.modelFiles.sumOf { File(dir, it).length() }
                onProgress("就绪 (${formatSize(total)})")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun downloadModel(lang: String, onProgress: (String) -> Unit): Result<Unit> =
        withContext(Dispatchers.IO) {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
            try {
                val info = modelForLang(lang) ?: return@withContext Result.failure(Exception("Unknown lang"))
                val dir = modelDir(lang)
                dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }

                // HuggingFace individual file download (no tar.bz2)
                val hfFiles = info.hfFiles
                if (hfFiles != null) {
                    val baseUrl = info.downloadUrl.trimEnd('/')
                    var downloaded = 0
                    for (filename in hfFiles) {
                        val url = "$baseUrl/$filename"
                        val dest = File(dir, filename)
                        onProgress("下载: $filename ($downloaded/${hfFiles.size})")
                        downloadSingleFile(url, dest) { pct -> onProgress("$filename: ${pct}%") }
                        downloaded++
                        onProgress("$filename 完成 (${formatSize(dest.length())})")
                    }

                    val files = dir.listFiles() ?: emptyArray()
                    val missing = info.modelFiles.filter { pattern ->
                        files.none { f -> f.isFile && f.length() > 0 && matchesPattern(f.name, pattern) }
                    }
                    if (missing.isNotEmpty()) {
                        return@withContext Result.failure(
                            Exception("模型文件缺失: ${missing.joinToString(", ") { patternDisplay(it) }}")
                        )
                    }
                    val total = files.filter { it.isFile }.sumOf { it.length() }
                    onProgress("就绪 (${formatSize(total)})")
                    return@withContext Result.success(Unit)
                }

                // Original tar.bz2 flow
                val archiveFile = File(context.cacheDir, "model-${lang}.tar.bz2")
                val urls = listOf(info.downloadUrl) + ghMirrors(info.downloadUrl) + mirrorUrls(info.modelName)
                val actualHash = downloadFile(urls, archiveFile) { pct, source -> onProgress("[${source}] 下载: ${pct}%") }

                onProgress("SHA256: $actualHash")

                val expected = info.sha256
                if (expected != null) {
                    if (!expected.equals(actualHash, ignoreCase = true)) {
                        archiveFile.delete()
                        return@withContext Result.failure(Exception("哈希校验失败!\n预期: ${expected.take(16)}...\n实际: ${actualHash.take(16)}..."))
                    }
                    onProgress("哈希校验通过 ✓")
                }

                val needed = info.modelFiles.toMutableSet()
                onProgress("解压中...")
                val extracted = extractTarBz2Selective(archiveFile, dir, needed, info.modelType) { name, idx, total ->
                    onProgress("解压: $name ($idx/$total)")
                }
                onProgress("解压完成 ($extracted 个文件)")
                archiveFile.delete()

                val files = dir.listFiles() ?: emptyArray()
                val missing = info.modelFiles.filter { pattern ->
                    files.none { f -> f.isFile && f.length() > 0 && matchesPattern(f.name, pattern) }
                }
                if (missing.isNotEmpty()) {
                    val foundList = files.filter { it.isFile }.joinToString("\n") { "  ${it.name} (${formatSize(it.length())})" }
                    dir.listFiles()?.forEach { it.delete() }
                    return@withContext Result.failure(
                        Exception("模型文件缺失 (${missing.size}/${info.modelFiles.size}):\n${missing.joinToString("\n") { "  ${patternDisplay(it)}" }}\n实际找到的文件:\n${foundList.ifEmpty { "  (无)" }}")
                    )
                }

                val total = files.filter { it.isFile }.sumOf { it.length() }
                onProgress("就绪 (${formatSize(total)})")
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    private fun downloadSingleFile(url: String, dest: File, onProgress: (Int) -> Unit) {
        val request = Request.Builder().url(url).build()
        val response = client.newCall(request).execute()
        val code = response.code
        if (code != 200) throw RuntimeException("HTTP $code for $url")
        val body = response.body ?: throw RuntimeException("Empty body for $url")
        val contentLen = body.contentLength()
        var read = 0L
        var lastPct = -5
        body.byteStream().use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    output.write(buf, 0, n)
                    read += n
                    if (contentLen > 0) {
                        val pct = ((read * 100) / contentLen).toInt()
                        if (pct - lastPct >= 5 || pct >= 100) { onProgress(pct); lastPct = pct }
                    }
                }
            }
        }
        response.close()
    }

    private fun mirrorUrls(modelName: String): List<String> = listOf(
        "https://hf-mirror.com/csukuangfj/$modelName/resolve/main/$modelName.tar.bz2",
        "https://huggingface.co/csukuangfj/$modelName/resolve/main/$modelName.tar.bz2"
    )

    /** GitHub 直连在国内常年不通：给 release 直链再套一层加速前缀（与云端包用的是同一批镜像）。 */
    private fun ghMirrors(url: String): List<String> =
        if (!url.contains("github.com")) emptyList()
        else listOf("https://ghfast.top/", "https://ghproxy.net/", "https://gh.llkk.cc/", "https://hub.gitmirror.com/")
            .map { it + url }

    private fun downloadFile(urls: List<String>, dest: File, onProgress: (Int, String) -> Unit): String {
        var lastError: Exception? = null
        for ((idx, url) in urls.withIndex()) {
            try {
                val sourceLabel = when {
                    idx == 0 -> "GitHub"
                    url.contains("hf-mirror") -> "hf-mirror"
                    url.contains("huggingface") -> "HuggingFace"
                    else -> "mirror"
                }

                val downloaded = if (dest.exists()) dest.length() else 0L
                val reqBuilder = Request.Builder().url(url)
                if (downloaded > 0) reqBuilder.header("Range", "bytes=$downloaded-")

                val response = client.newCall(reqBuilder.build()).execute()
                val code = response.code
                val resume = code == 206

                if (code != 200 && code != 206) throw RuntimeException("HTTP $code")
                val body = response.body ?: throw RuntimeException("Empty body")

                val contentLen = body.contentLength()
                val total = if (resume && contentLen > 0) downloaded + contentLen
                    else contentLen.coerceAtLeast(downloaded)

                val fos = FileOutputStream(dest, resume)
                val digest = MessageDigest.getInstance("SHA-256")
                if (resume) {
                    dest.inputStream().use { input ->
                        val buf = ByteArray(65536)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) digest.update(buf, 0, n)
                    }
                }
                var read = if (resume) downloaded else 0L
                var lastPct = if (total > 0) ((read * 100) / total).toInt() - 5 else -5

                body.byteStream().use { input ->
                    fos.use { output ->
                        val buf = ByteArray(65536)
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = ((read * 100) / total).toInt()
                                if (pct - lastPct >= 5 || pct >= 100) {
                                    onProgress(pct, sourceLabel)
                                    lastPct = pct
                                }
                            }
                        }
                    }
                }
                return digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                lastError = e
                if (idx < urls.size - 1) {
                    dest.delete()
                    Thread.sleep(500)
                }
            }
        }
        throw lastError ?: RuntimeException("All mirrors exhausted")
    }

    private fun extractTarBz2Selective(
        archive: File,
        destDir: File,
        neededPatterns: MutableSet<String>,
        modelType: SttModelType,
        onEntry: (name: String, index: Int, total: Int) -> Unit
    ): Int {
        var count = 0
        val totalNeeded = neededPatterns.size
        val bufSize = 512 * 1024

        BZip2CompressorInputStream(archive.inputStream().buffered(bufSize)).use { bzIn ->
            TarArchiveInputStream(bzIn).use { tarIn ->
                val readBuf = ByteArray(bufSize)
                var entry = tarIn.nextEntry
                while (entry != null) {
                    if (neededPatterns.isEmpty()) break

                    val entryName = File(entry.name).name
                    if (entry.isDirectory) {
                        // Directories have no data — just advance
                        entry = tarIn.nextEntry
                        continue
                    }

                    val matchingPattern = neededPatterns.firstOrNull { matchesPattern(entryName, it) }
                    if (matchingPattern != null) {
                        val outFile = File(destDir, entryName)
                        outFile.parentFile?.mkdirs()
                        outFile.outputStream().buffered(bufSize).use { output ->
                            var n: Int
                            while (tarIn.read(readBuf).also { n = it } != -1) {
                                output.write(readBuf, 0, n)
                            }
                        }
                        count++
                        neededPatterns.remove(matchingPattern)
                        onEntry(entryName, count, totalNeeded)
                    } else {
                        // Not a needed file — drain to advance past this entry
                        val skipBuf = ByteArray(bufSize)
                        var skipped: Int
                        while (tarIn.read(skipBuf).also { skipped = it } != -1) { }
                    }
                    entry = tarIn.nextEntry
                }
            }
        }
        return count
    }

    private fun matchesPattern(filename: String, pattern: String): Boolean {
        return if (pattern.isEmpty()) {
            filename.endsWith(".onnx")
        } else if (pattern.contains(".")) {
            filename == pattern
        } else {
            filename.startsWith(pattern) && filename.endsWith(".onnx")
        }
    }

    private fun patternDisplay(pattern: String): String =
        if (pattern.isEmpty()) "*.onnx" else pattern

    private fun copyAsset(assetPath: String, dest: File) {
        context.assets.open(assetPath).use { input ->
            FileOutputStream(dest).use { output ->
                val buf = ByteArray(65536)
                var n: Int
                while (input.read(buf).also { n = it } != -1) output.write(buf, 0, n)
            }
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes.toDouble() / (1024 * 1024))} MB"
    }
}
