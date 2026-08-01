package com.arix.tool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================
// 内嵌原生推理 —— App 进程内用 llama.cpp 跑本地 GGUF 模型（完全离线、不联网）。
//
// 本文件是【脚手架 + JNI 契约】。真正的推理由原生库 libxtomllm.so 提供（本仓库不含，需另编）：
// 把为 arm64-v8a 编译好的 llama.cpp + 下面这层 JNI 包装 放到 app/src/main/jniLibs/arm64-v8a/libxtomllm.so，
// 再把 .gguf 模型放到 filesDir/models/，即可离线跑。编译约定见仓库 NATIVE-INFERENCE.md。
// 没放 .so 或没模型时诚实报「未装配 + 怎么装」（同 EmbeddedTermux bootstrap 的做法）。
//
// JNI 符号约定（libxtomllm.so 必须导出）：
//   Java_com_arix_tool_NativeInference_nativeInit(String modelPath, int nCtx, int nThreads) -> long handle
//   Java_com_arix_tool_NativeInference_nativeGenerate(long handle, String prompt, int maxTokens, float temperature) -> String
//   Java_com_arix_tool_NativeInference_nativeFree(long handle)
// ============================================================
object NativeInference {
    @Volatile private var libOk = false

    init {
        libOk = try { System.loadLibrary("xtomllm"); true } catch (_: Throwable) { false }
    }

    fun libraryAvailable(): Boolean = libOk

    // —— 原生方法（由 libxtomllm.so 实现；lib 缺失时不要调用）——
    external fun nativeInit(modelPath: String, nCtx: Int, nThreads: Int): Long
    external fun nativeGenerate(handle: Long, prompt: String, maxTokens: Int, temperature: Float): String
    external fun nativeFree(handle: Long)

    @Volatile private var handle = 0L
    @Volatile private var loadedPath = ""

    @Synchronized
    private fun ensureModel(path: String, nCtx: Int, nThreads: Int): Boolean {
        if (!libOk) return false
        if (handle != 0L && loadedPath == path) return true
        if (handle != 0L) { try { nativeFree(handle) } catch (_: Throwable) {}; handle = 0L }
        return try {
            handle = nativeInit(path, nCtx, nThreads)
            loadedPath = if (handle != 0L) path else ""
            handle != 0L
        } catch (_: Throwable) { false }
    }

    /** 离线生成；lib 或模型不可用返回 null。
     *  整体 @Synchronized：llama.cpp context 非线程安全，且必须与 ensureModel 的换模型/free 串行，
     *  否则并发调用会在已 free 的 handle 上生成 → 原生 SIGSEGV(try/catch 拦不住)。 */
    @Synchronized
    fun generate(path: String, prompt: String, maxTokens: Int, temperature: Float): String? {
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
        if (!ensureModel(path, 2048, threads)) return null
        return try { nativeGenerate(handle, prompt, maxTokens, temperature) } catch (_: Throwable) { null }
    }
}

// 本地模型管理：filesDir/models/ 下的 .gguf；活动模型存 prefs。
object LocalModelPrefs {
    private const val PREFS = "xtom_local_model"
    fun dir(c: Context): File = File(c.filesDir, "models").apply { if (!exists()) mkdirs() }
    fun listModels(c: Context): List<File> = dir(c).listFiles { f -> f.extension.equals("gguf", true) }?.sortedBy { it.name } ?: emptyList()
    fun activeName(c: Context): String = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("active", "") ?: ""
    fun setActive(c: Context, name: String) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("active", name).apply()
    fun activeModelFile(c: Context): File? {
        val list = listModels(c); if (list.isEmpty()) return null
        val a = activeName(c)
        return list.firstOrNull { it.name == a } ?: list.first()
    }
}

// local_infer 工具：本地离线模型回答/生成；未装配则给出启用指引。
class LocalInferTool(private val context: Context) : Tool {
    override val name = "local_infer"
    override val description = "用手机本地离线模型(llama.cpp GGUF, 完全不联网)回答或生成文本——隐私/断网场景用。需已装配原生推理库+模型。"

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("prompt", JSONObject().apply { put("type", "string"); put("description", "要问/要生成的内容") })
            put("max_tokens", JSONObject().apply { put("type", "integer"); put("description", "最多生成 token 数，默认 512") })
            put("temperature", JSONObject().apply { put("type", "number"); put("description", "采样温度，默认 0.7") })
        })
        put("required", JSONArray(listOf("prompt")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val prompt = params.optString("prompt", "").trim()
        if (prompt.isBlank()) return@withContext ToolResult("请提供 prompt", isError = true)
        if (!NativeInference.libraryAvailable())
            return@withContext ToolResult(
                "本地原生推理未装配：需把为 arm64-v8a 编译的 llama.cpp JNI 库放到 app/src/main/jniLibs/arm64-v8a/libxtomllm.so（符号约定见仓库 NATIVE-INFERENCE.md），重新打包。", isError = true)
        val model = LocalModelPrefs.activeModelFile(context)
            ?: return@withContext ToolResult(
                "没有本地模型：把 .gguf 模型放到应用私有目录 ${LocalModelPrefs.dir(context).absolutePath}/（如手机上 Termux/文件管理器拷入，或用 http_request 下载到那），再试。", isError = true)
        val out = NativeInference.generate(
            model.absolutePath, prompt,
            params.optInt("max_tokens", 512).coerceIn(1, 4096),
            params.optDouble("temperature", 0.7).toFloat()
        )
        if (out.isNullOrBlank()) ToolResult("本地推理失败（模型加载/生成出错，模型文件可能损坏或内存不足）", isError = true)
        else ToolResult(out.trim() + "\n\n(本地模型 ${model.name} · 离线生成)")
    }
}
