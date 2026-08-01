package com.arix.tool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================
// 增强对话工具 — 移植自 Operit
// ============================================================

// 世界书 — 世界设定管理（落库持久化，重启不丢）
class WorldBookTool(private val context: Context) : Tool {
    override val name = "worldbook"
    override val description = "管理世界设定/角色背景/故事设定。用于角色扮演和叙事场景。"

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("set", "get", "list")))
                put("description", "set=设置世界设定, get=获取设定, list=列出所有")
            })
            put("key", JSONObject().apply {
                put("type", "string")
                put("description", "设定名称，如 '世界观'、'角色背景'")
            })
            put("value", JSONObject().apply {
                put("type", "string")
                put("description", "设定内容（action=set时需要）")
            })
        })
        put("required", JSONArray(listOf("action")))
    }

    private val file get() = File(context.filesDir, "worldbook.json")
    private fun load(): JSONObject = try { if (file.exists()) JSONObject(file.readText()) else JSONObject() } catch (_: Exception) { JSONObject() }
    private fun save(obj: JSONObject) { try { file.writeText(obj.toString()) } catch (_: Exception) {} }

    override suspend fun execute(params: JSONObject): ToolResult {
        val action = params.optString("action")
        val entries = load()
        return when (action) {
            "set" -> {
                val key = params.optString("key", "").take(80)
                val value = params.optString("value", "").take(500)
                if (key.isBlank()) return ToolResult("请提供设定名称", isError = true)
                entries.put(key, value); save(entries)
                ToolResult("世界设定已保存: $key")
            }
            "get" -> {
                val key = params.optString("key", "")
                if (entries.has(key)) ToolResult("$key: ${entries.optString(key)}")
                else ToolResult("未找到设定: $key")
            }
            "list" -> {
                if (entries.length() == 0) ToolResult("暂无世界设定")
                else ToolResult(entries.keys().asSequence().joinToString("\n") { "$it: ${entries.optString(it).take(80)}" })
            }
            else -> ToolResult("未知操作", isError = true)
        }
    }
}

// 代码运行器 — 委派命令行环境（见 [LocalLinuxTool]）跑代码。
// 注意：完整解释器随命令行环境迁移到独立终端 App（接入前不可用）；简单 js/数学表达式走离线计算兜底。
class CodeRunnerTool(private val context: Context) : Tool {
    override val name = "code_runner"
    override val description = "执行代码并返回真实输出。装了「Arix 终端」App 时 python/javascript(node)/bash/ruby/php 真跑；未装时简单 js/数学表达式可离线计算。重活(装库/文件操作)用 linux_exec。"

    // language → (脚本扩展名, 解释器命令)
    private val langs = mapOf(
        "python" to ("py" to "python"), "python3" to ("py" to "python"), "py" to ("py" to "python"),
        "javascript" to ("js" to "node"), "js" to ("js" to "node"), "node" to ("js" to "node"),
        "bash" to ("sh" to "bash"), "sh" to ("sh" to "sh"), "shell" to ("sh" to "bash"),
        "ruby" to ("rb" to "ruby"), "rb" to ("rb" to "ruby"),
        "php" to ("php" to "php")
    )

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("code", JSONObject().apply {
                put("type", "string")
                put("description", "要执行的代码")
            })
            put("language", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(langs.keys.toList()))
                put("description", "代码语言，默认 python")
            })
            put("timeout", JSONObject().apply {
                put("type", "integer")
                put("description", "超时秒数，默认 30，最大 600")
            })
        })
        put("required", JSONArray(listOf("code")))
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val code = params.optString("code", "").trim()
        if (code.isBlank()) return ToolResult("请输入要执行的代码", isError = true)
        val langRaw = params.optString("language", "python").lowercase()
        val (ext, interp) = langs[langRaw] ?: ("txt" to langRaw)
        val isJs = langRaw in setOf("javascript", "js", "node")

        // 完整解释器已迁移到独立终端 App；js/数学 → 离线计算兜底（简单表达式）
        if (isJs) {
            val safe = code.replace(Regex("[^0-9+\\-*/().%\\s]"), "")
            if (safe.isNotBlank() && (safe.length == code.length || code.startsWith("Math."))) {
                return CalculatorTool().execute(JSONObject().apply { put("expression", code) })
            }
        }

        // 委派 linux_exec（超时/取消/输出格式化/未装终端的提示都在那）。
        // 脚本**必须在终端那侧落盘**：linux_exec 跑在独立终端 App 的 proot 里，那是另一个 uid、
        // 另一个 HOME，读不到本 App 私有的 ai_workspace——早先写进工作区再用相对路径跑的做法在这必然失败。
        // 故用 heredoc 把代码随命令一起送过去：定界符加纳秒后缀防撞（代码里恰好有同名行会截断脚本），
        // 且用**引号定界符**，heredoc 内不做变量展开，代码里的 $ 反引号原样保留。
        return try {
            val nonce = System.nanoTime()
            val eof = "__XTOM_CODE_${nonce}__"
            val d = "\$" + "{TMPDIR:-/tmp}"
            val f = "\$f"
            val rc = "\$?"
            val cmd = buildString {
                appendLine("f=\"$d/xtom_snippet_$nonce.$ext\"")
                appendLine("cat > \"$f\" <<'$eof'")
                appendLine(code)
                appendLine(eof)
                appendLine("$interp \"$f\"; rc=$rc; rm -f \"$f\"; exit \$rc")
            }
            LocalLinuxTool(context).execute(JSONObject().apply {
                put("command", cmd)
                put("timeout", params.optInt("timeout", 30))
                // 实时输出的抬头写「运行代码」而不是「终端」：用户刚让 AI 跑一段代码，
                // 看见「终端」会以为是别的动作（内部委派是实现细节，不该漏给用户）
                put("stream_name", "运行代码")
            })
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (e: Exception) {
            ToolResult("执行失败: ${e.message}", isError = true)
        }
    }
}

// 文件转换 — 格式转换
class FileConverterTool(private val context: Context) : Tool {
    override val name = "file_converter"
    override val description = "格式转换。文本类(json/csv/markdown/html/text)：input=内容直接转。" +
        "生成文档(pdf/docx)：input=Markdown 或 HTML 正文，出一份带标题/列表/粗体的文件存到系统「下载」目录，用户能直接打开或发出去。" +
        "图片类(png/jpg/webp)：input=工作目录里的图片路径、output=输出路径，按 to 重新编码。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Convert formats. Text (json/csv/markdown/html/text): input is the content itself. " +
        "Documents: to=pdf or to=docx turns Markdown (or HTML) in `input` into a real PDF / Word file saved to the user's Downloads folder — " +
        "use this whenever they want a document they can open, print or send, not just text in the chat. Headings, lists, bold/italic, quotes and code blocks are kept. " +
        "Images (png/jpg/webp): input is a workspace image path, output the destination path."

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("input", JSONObject().apply {
                put("type", "string")
                put("description", "text/document conversion = the content itself; image conversion = a workspace image path")
            })
            put("from", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("json", "csv", "markdown", "html", "text", "png", "jpg", "webp")))
                put("description", "source format")
            })
            put("to", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("json", "csv", "markdown", "html", "text", "pdf", "docx", "png", "jpg", "webp")))
                put("description", "target format")
            })
            put("output", JSONObject().apply {
                put("type", "string")
                put("description", "image conversion: destination path in the workspace. pdf/docx: the file name to save under Downloads, e.g. 会议纪要.pdf")
            })
        })
        put("required", JSONArray(listOf("input", "from", "to")))
    }

    private val imageFormats = setOf("png", "jpg", "webp")
    private val docFormats = setOf("pdf", "docx")

    /** 整幅解码的像素上限（≈16MB ARGB_8888）。超过就按 2 的幂降采样，手表堆撑不住原图。 */
    private val MAX_DECODE_PIXELS = 4L * 1024 * 1024

    override suspend fun execute(params: JSONObject): ToolResult {
        val input = params.optString("input", "")
        val from = params.optString("from", "text")
        val to = params.optString("to", "text")
        if (input.isBlank()) return ToolResult("请输入要转换的内容/路径", isError = true)
        // 生成文档：写成真文件落到「下载」目录（AI 写完的东西要能被用户拿走，留在沙盒里等于没交付）
        if (to in docFormats) return exportDocument(input, from, to, params.optString("output", ""))
        // 图片格式：走工作目录 Bitmap 重编码
        if (from in imageFormats || to in imageFormats) return convertImage(input, to, params.optString("output", ""))
        return try {
            when (from to to) {
                "json" to "markdown" -> ToolResult(jsonToMarkdown(input))
                "markdown" to "html" -> ToolResult(input.replace(Regex("^# (.+)", RegexOption.MULTILINE), "<h1>$1</h1>")
                    .replace(Regex("^## (.+)", RegexOption.MULTILINE), "<h2>$1</h2>")
                    .replace(Regex("^### (.+)", RegexOption.MULTILINE), "<h3>$1</h3>")
                    .replace(Regex("\\*\\*(.+?)\\*\\*"), "<b>$1</b>")
                    .replace(Regex("\\*(.+?)\\*"), "<i>$1</i>")
                    .replace(Regex("^- (.+)", RegexOption.MULTILINE), "<li>$1</li>"))
                "text" to "markdown" -> ToolResult(input)
                else -> ToolResult("$from → $to 转换需要AI协助。请描述你的需求。\n输入: ${input.take(200)}")
            }
        } catch (e: Exception) {
            ToolResult("转换失败: ${e.message}", isError = true)
        }
    }

    /**
     * Markdown/HTML → PDF 或 docx，零依赖（见 [DocExport]）。落到系统「下载」目录：
     * 生成一份用户打不开的文件毫无意义，而 AI 的私有工作区他就是打不开。
     */
    private suspend fun exportDocument(input: String, from: String, to: String, output: String): ToolResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val blocks = if (from == "html") DocExport.parseHtml(input) else DocExport.parseMarkdown(input)
                if (blocks.isEmpty()) return@withContext ToolResult("没有可写进文档的内容", isError = true)
                // 文件名：给了就用，没给就拿第一个标题当名字——「未命名.pdf」躺在下载列表里没人认得出是什么
                val base = output.ifBlank { blocks.firstOrNull { it.heading > 0 }?.plain?.take(40) ?: "文档" }
                val name = DownloadsSink.sanitize(if (base.endsWith(".$to", true)) base else "$base.$to", "文档.$to")
                val mime = if (to == "pdf") "application/pdf"
                           else "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                val where = DownloadsSink.save(context, name, mime) { out ->
                    if (to == "pdf") DocExport.writePdf(blocks, out) else out.write(DocExport.buildDocx(blocks))
                } ?: return@withContext ToolResult("保存失败：写不进下载目录", isError = true)
                ToolResult("已生成 ${to.uppercase()}：$where（${blocks.size} 段）。用户在「下载」里就能打开或转发。")
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (e: Throwable) {
                ToolResult("生成 ${to.uppercase()} 失败：${e.message}", isError = true)
            }
        }

    /**
     * ⚠ 必须自己 `withContext(Dispatchers.IO)`：本文件同类的另外四个工具都切了，只有这个漏了，
     * 于是整幅解码 + 重编码（几十到几百毫秒起）跑在调用方线程上，而工具循环的调用方是主线程。
     * `ToolManager` 那句 `withContext(CallerContext(...))` **只换 context element、不换 dispatcher**，靠不住。
     */
    @Suppress("DEPRECATION")
    private suspend fun convertImage(inputPath: String, to: String, outPath: String): ToolResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (to !in imageFormats) return@withContext ToolResult("图片只能转成图片格式(png/jpg/webp)，to=$to 不对", isError = true)
        val src = AiWorkspace.resolve(context, inputPath) ?: return@withContext AiWorkspace.deny()
        if (!src.exists()) return@withContext ToolResult("图片不存在: ${src.name}", isError = true)
        val dst = AiWorkspace.resolve(context, outPath.ifBlank { "${src.nameWithoutExtension}.$to" }) ?: return@withContext AiWorkspace.deny()
        try {
            // 先只读尺寸再定采样率：手表堆很小，一张手机拍的 4000×3000 直接整幅解码就是 48MB ARGB，
            // 原来是**先 OOM 再在 catch 里道歉**。缩过的会在结果里说明——悄悄改分辨率比失败更糟。
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(src.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext ToolResult("解码图片失败（不是有效图片？）", isError = true)
            var sample = 1
            while (bounds.outWidth.toLong() * bounds.outHeight / (sample.toLong() * sample) > MAX_DECODE_PIXELS) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeFile(src.absolutePath, opts)
                ?: return@withContext ToolResult("解码图片失败（不是有效图片？）", isError = true)
            val fmt = when (to) {
                "jpg" -> android.graphics.Bitmap.CompressFormat.JPEG
                "webp" -> android.graphics.Bitmap.CompressFormat.WEBP
                else -> android.graphics.Bitmap.CompressFormat.PNG
            }
            dst.parentFile?.mkdirs()
            val ok = dst.outputStream().use { bmp.compress(fmt, 92, it) }
            val w = bmp.width; val h = bmp.height
            bmp.recycle()
            if (!ok) { dst.delete(); return@withContext ToolResult("编码失败（该设备可能不支持 $to）", isError = true) }
            val note = if (sample > 1) "，原图 ${bounds.outWidth}×${bounds.outHeight} 太大，已缩到 ${w}×${h}" else ""
            ToolResult("已转换图片 → ${dst.name}（${dst.length() / 1024}KB$note）")
        } catch (e: Throwable) { ToolResult("图片转换失败（可能图片太大内存不足）: ${e.message}", isError = true) }
    }

    private fun jsonToMarkdown(json: String): String {
        try {
            val obj = JSONObject(json)
            val sb = StringBuilder()
            for (key in obj.keys()) {
                sb.append("**$key**: ${obj.optString(key, obj.get(key).toString())}\n\n")
            }
            return sb.toString()
        } catch (_: Exception) {
            return json
        }
    }
}
