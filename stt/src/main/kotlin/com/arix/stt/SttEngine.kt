package com.arix.stt

import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.OnlineZipformer2CtcModelConfig
import java.io.File

class SttEngine(private val modelDir: File, private val langModel: LanguageModel) {

    var isLoaded: Boolean = false
        private set

    private var recognizer: OnlineRecognizer? = null

    data class LoadInfo(val loadTimeMs: Long, val modelSize: Long)

    fun load(): Result<LoadInfo> = try {
        // 关键：sherpa-onnx-jni 依赖 libonnxruntime.so。Android 上 sherpa 的 LibraryUtils 只 loadLibrary("sherpa-onnx-jni")、
        // 不先加载 onnxruntime → jni 找不到 OrtGetApiBase、dlopen 失败抛 UnsatisfiedLinkError（Error 非 Exception）→ 闪退。
        // 对齐 NeuralTts：构造 recognizer 前先显式预载 onnxruntime。
        try { System.loadLibrary("onnxruntime") } catch (_: Throwable) {}
        val modelSize = modelDir.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L
        val startMs = System.currentTimeMillis()

        val modelConfig = when (langModel.modelType) {
            SttModelType.ZIPFORMER2_CTC -> {
                val modelFile = findAnyOnnx(modelDir)
                val ctc = OnlineZipformer2CtcModelConfig.builder()
                    .setModel(modelFile.absolutePath)
                    .build()
                OnlineModelConfig.builder()
                    .setZipformer2Ctc(ctc)
                    .setTokens(File(modelDir, "tokens.txt").absolutePath)
                    .setNumThreads(1).setDebug(false)
                    .build()
            }
            SttModelType.TRANSDUCER -> {
                val transducer = OnlineTransducerModelConfig.builder()
                    .setEncoder(findFile(modelDir, "encoder").absolutePath)
                    .setDecoder(findFile(modelDir, "decoder").absolutePath)
                    .setJoiner(findFile(modelDir, "joiner").absolutePath)
                    .build()
                OnlineModelConfig.builder()
                    .setTransducer(transducer)
                    .setTokens(File(modelDir, "tokens.txt").absolutePath)
                    .setNumThreads(1).setDebug(false)
                    .build()
            }
        }

        val config = OnlineRecognizerConfig.builder()
            .setOnlineModelConfig(modelConfig)
            .setEnableEndpoint(false)
            .build()

        recognizer = OnlineRecognizer(config)
        isLoaded = true
        Result.success(LoadInfo(System.currentTimeMillis() - startMs, modelSize))
    } catch (e: Throwable) {
        // Throwable 而非 Exception：native 库加载失败抛的是 UnsatisfiedLinkError/NoClassDefFoundError（Error），
        // 只 catch Exception 会漏掉 → 整个 app 闪退。接住后回传失败原因给 UI，不崩。
        isLoaded = false
        Result.failure(e)
    }

    fun recognize(samples: FloatArray, sampleRate: Int): Result<SttResult> {
        val rec = recognizer ?: return Result.failure(IllegalStateException("Engine not loaded"))
        return try {
            val startMs = System.currentTimeMillis()
            val stream = rec.createStream()
            // native OnlineStream 必须在所有路径（含异常）释放，否则原生堆内存泄露
            try {
                val chunkSize = (0.1 * sampleRate).toInt()
                var offset = 0

                while (offset < samples.size) {
                    val end = minOf(offset + chunkSize, samples.size)
                    stream.acceptWaveform(samples.copyOfRange(offset, end), sampleRate)
                    while (rec.isReady(stream)) rec.decode(stream)
                    offset = end
                }

                val tail = FloatArray((0.8f * sampleRate).toInt())
                stream.acceptWaveform(tail, sampleRate)
                while (rec.isReady(stream)) rec.decode(stream)

                val text = rec.getResult(stream).getText()

                Result.success(SttResult(
                    text = text,
                    inferenceTimeMs = System.currentTimeMillis() - startMs,
                    audioDurationMs = (samples.size * 1000L) / sampleRate
                ))
            } finally {
                stream.release()
            }
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    fun release() { recognizer?.release(); recognizer = null; isLoaded = false }

    private fun findFile(dir: File, prefix: String): File =
        dir.listFiles()?.find { it.name.startsWith(prefix) && it.name.endsWith(".onnx") }
            ?: throw IllegalStateException("No $prefix*.onnx in $dir")

    private fun findAnyOnnx(dir: File): File =
        dir.listFiles()?.find { it.name.endsWith(".onnx") }
            ?: throw IllegalStateException("No .onnx model found in $dir")
}
