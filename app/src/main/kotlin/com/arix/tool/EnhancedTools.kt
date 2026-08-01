package com.arix.tool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// ============================================================
// 增强对话工具 — 移植自 Operit
// ============================================================

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
