package com.arix.tool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.arix.app.CloudApiConfigManager
import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.model.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * read_document —— 把文档解析成文字喂给模型（对齐 Rikka/Kelivo/Operit 的「PDF/DOCX 解析入模」）。
 * PDF：逐页用 PdfRenderer 渲染成图，交「识图(vision)」模型逐字 OCR + 转 Markdown（也顺带补上市场那个「PDF逐页识图」）。
 * DOCX：纯离线解 zip 里的 word/document.xml。xlsx：best-effort 提取单元格字符串。txt/md/csv/json/代码：直接读。
 */
class DocReadTool(private val context: Context) : Tool {
    override val name = "read_document"
    override val description = "读文档并转成文字：pdf(优先提取内嵌文本层，扫描件才回退逐页识图OCR/需配识图模型)、docx、pptx、xlsx、epub、txt/md/csv/json/log/代码。给文件路径(AI工作区相对路径、绝对路径或 file:// 均可)。用于把附件/本地文档内容读进来回答。into_memory=true 则解析后切块存进长期记忆(带语义索引，供以后检索)，用于把资料/手册长期记住。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Read a document as text: pdf (embedded text layer first, OCR page images only for scans, needs a vision model), docx, pptx, xlsx, epub, txt/md/csv/json/log/code. Path may be workspace-relative, absolute, or file://. Use it to read an attachment or local file before answering. into_memory=true chunks the result into long-term memory instead, for material worth keeping."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("path", JSONObject().apply { put("type", "string"); put("description", "document path") })
            put("max_pages", JSONObject().apply { put("type", "integer"); put("description", "pdf pages to parse, default 15") })
            // 大文本的正确读法是"先定位再精读"，所以按行分段与正则筛行做进本工具（而不是逼模型去开文件工具包）
            put("offset", JSONObject().apply { put("type", "integer"); put("description", "start line, 1-based; text/code/log only") })
            put("limit", JSONObject().apply { put("type", "integer"); put("description", "line count, with offset; text only") })
            put("pattern", JSONObject().apply { put("type", "string"); put("description", "regex; return only matching lines with numbers. Locate in a big file first, then read closely with offset/limit") })
            put("into_memory", JSONObject().apply { put("type", "boolean"); put("description", "chunk into long-term memory (RAG) instead of returning the text") })
        })
        put("required", JSONArray(listOf("path")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val raw = params.optString("path", "").trim()
        if (raw.isBlank()) return@withContext ToolResult("请提供文档路径", isError = true)
        val file = resolve(raw) ?: return@withContext ToolResult("找不到文件：$raw", isError = true)
        if (!file.exists() || !file.isFile) return@withContext ToolResult("文件不存在：${file.absolutePath}", isError = true)
        val ext = file.extension.lowercase()
        val result = try {
            when (ext) {
                "pdf" -> readPdf(file, params.optInt("max_pages", 15).coerceIn(1, 50))
                "docx" -> ToolResult(DocParsers.parseDocx(file).ifBlank { "(空文档或非标准 docx)" }.take(TEXT_CAP))
                "pptx" -> DocParsers.parsePptx(file).let { b -> ToolResult(if (b.isBlank()) "(空演示或非标准 pptx)" else "【PPTX：${file.name}】\n\n$b".take(TEXT_CAP)) }
                "epub" -> DocParsers.parseEpub(file).let { b -> ToolResult(if (b.isBlank()) "(空书或非标准 epub)" else "【EPUB：${file.name}】\n\n$b".take(TEXT_CAP)) }
                "xlsx" -> ToolResult(("【xlsx 单元格文本（粗提取，丢版式）】\n" + zipXmlText(file, "xl/sharedStrings.xml", perParagraph = false)).take(TEXT_CAP))
                "txt", "md", "markdown", "csv", "json", "log", "xml", "html", "htm", "kt", "java", "py", "js", "ts", "c", "cpp", "h", "sh", "yml", "yaml", "toml", "ini", "conf" ->
                    ToolResult(readTextSmart(file, params))
                else -> ToolResult("暂不支持的格式：.$ext（支持 pdf/docx/pptx/xlsx/epub 及常见文本/代码）", isError = true)
            }
        } catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (e: Exception) { ToolResult("解析失败：${e.message}", isError = true) }
        // into_memory：把解析出的正文切块存进长期记忆（带语义索引），供以后 RAG 检索
        if (!params.optBoolean("into_memory", false) || result.isError) return@withContext result
        return@withContext try { saveToMemory(file.nameWithoutExtension, result.content) }
        catch (c: kotlinx.coroutines.CancellationException) { throw c }
        catch (e: Exception) { ToolResult("已解析但存入记忆失败：${e.message}", isError = true) }
    }

    /**
     * 把解析出的正文切块存进长期记忆（带语义索引）。
     *
     * 切块/命名/幂等全部走 [DocChunker] —— 和 `rag` 工具**必须是同一套**，否则两边又会变成
     * 两个互相搜不到的库（这正是 2026-07-29 合并掉的那个问题，见 DocChunker 的类注释）。
     */
    private suspend fun saveToMemory(docName: String, text: String): ToolResult {
        val mm = com.arix.app.MemoryManager(context)
        val r = DocChunker.store(mm, docName, text, cardId = com.arix.tool.ActiveChatContext.characterCardId)
        if (r.total == 0) return ToolResult("文档没有可存的文字内容", isError = true)
        if (r.ok == 0) return ToolResult("解析成功但存入记忆失败：${r.lastError ?: "未知"}", isError = true)
        return ToolResult(
            "已把《$docName》存入长期记忆 ${r.ok}/${r.total} 段（带语义索引，可用 rag search 检索）。" +
                (if (r.ok < r.total) "（${r.total - r.ok} 段失败）" else "") +
                (if (r.truncated) "（文档过长，只存了前 ${DocChunker.MAX_CHUNKS} 段）" else "")
        )
    }

    /** 相对路径→AI 工作区；file://→去前缀；否则当绝对路径。 */
    private fun resolve(p: String): File? = try {
        when {
            p.startsWith("file://") -> File(android.net.Uri.parse(p).path ?: p.removePrefix("file://"))
            p.startsWith("/") -> File(p)
            else -> File(AiWorkspace.root(context), p)
        }
    } catch (_: Exception) { null }

    // ---- PDF：先试内嵌文本层（快/离线），文本层为空或过短（扫描件）才回退逐页识图 OCR ----
    private suspend fun readPdf(file: File, maxPages: Int): ToolResult {
        extractPdfTextLayer(file, maxPages)?.let { return it }   // 有文本层就直接用，跳过视觉 OCR
        return readPdfByVision(file, maxPages)                    // 扫描件/无文本层 → 回退
    }

    /**
     * 用 PdfBox-Android 抽 PDF 内嵌文本层。判定“够用”才返回结果（否则返回 null 让上层回退 OCR）。
     * 任何异常（含未加依赖/加载失败）都静默返回 null，保证扫描件仍能走 OCR。
     */
    private fun extractPdfTextLayer(file: File, maxPages: Int): ToolResult? = try {
        // 幂等初始化，防 Application.onCreate 未调用时字体/资源加载器缺失
        runCatching { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(context) }
        file.inputStream().use { ins ->
            com.tom_roush.pdfbox.pdmodel.PDDocument.load(ins).use { doc ->
                val total = doc.numberOfPages
                val n = minOf(total, maxPages).coerceAtLeast(1)
                val stripper = com.tom_roush.pdfbox.text.PDFTextStripper().apply { startPage = 1; endPage = n }
                val raw = stripper.getText(doc)
                val stripped = raw.replace(Regex("\\s"), "")
                // “有文本层”阈值：去空白后总长 ≥40 且按解析页数摊一个每页下限（扫描件几乎为 0，会走 else 回退）
                if (stripped.length < 40 || stripped.length < n * 12) null
                else ToolResult(
                    ("【PDF：${file.name}，共 $total 页${if (n < total) "，解析前 $n 页" else ""}（文本层直读）】\n\n" + raw.trim()).take(TEXT_CAP)
                )
            }
        }
    } catch (c: kotlinx.coroutines.CancellationException) { throw c }
    catch (e: Throwable) { null }

    // ---- PDF 扫描件回退：逐页渲染成图 → 识图模型 OCR/转 md ----
    private suspend fun readPdfByVision(file: File, maxPages: Int): ToolResult {
        val vcfg = visionConfig() ?: return ToolResult("PDF 没有可提取的文本层（可能是扫描件），回退识图需要「识图(vision)」模型：请在 模型配置 里激活一个视觉模型再试。", isError = true)
        val cache = File(context.cacheDir, "docread").apply { mkdirs() }
        var pfd: ParcelFileDescriptor? = null; var renderer: PdfRenderer? = null
        val client = CloudApiClient(vcfg)
        return try {
            pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = PdfRenderer(pfd)
            val total = renderer.pageCount
            val n = minOf(total, maxPages)
            val sb = StringBuilder("【PDF：${file.name}，共 $total 页${if (n < total) "，解析前 $n 页" else ""}】\n\n")
            var okPages = 0; var lastErr: String? = null   // 每页 OCR 失败会被吞：统计成功页/首个错误，全失败时明确报错而非静默返回空
            for (i in 0 until n) {
                val page = renderer.openPage(i)
                // 800 宽足够 OCR，又把单页位图峰值压到 ~9MB 内（手表内存有限，防 OOM），每页用完即 recycle
                val w = 800; val h = (w.toFloat() / page.width * page.height).toInt().coerceIn(200, 3000)
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
                page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                page.close()
                val img = File(cache, "p${System.nanoTime()}.png")
                img.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 90, it) }
                bmp.recycle()
                var out = ""
                // streamChat 把 images 每一项当 base64 包成 data:image/jpeg;base64,<它> —— 必须传 base64；传路径会得到坏图、OCR 全废。
                val b64 = android.util.Base64.encodeToString(img.readBytes(), android.util.Base64.NO_WRAP)
                val res = try {
                    client.streamChat(
                        messages = listOf(ChatMessage("user", "把这一页文档的所有文字和内容准确转成 Markdown：逐字 OCR，表格用 md 表格，保留标题层级。只输出内容本身，不要寒暄。")),
                        images = listOf(b64), enableThinking = 0, onReasoningChunk = {}, onContentChunk = { out += it },
                    )
                } catch (c: kotlinx.coroutines.CancellationException) { throw c }
                catch (e: Exception) { lastErr = e.message; null }
                runCatching { img.delete() }
                if (res?.error == "已停止") throw kotlinx.coroutines.CancellationException("已停止")   // STOP 要停掉整个多页循环
                if (res?.error != null && out.isBlank()) lastErr = res.error
                if (out.trim().isNotBlank()) okPages++
                sb.append("── 第 ${i + 1} 页 ──\n").append(out.trim().ifBlank { "(本页未识别到内容)" }).append("\n\n")
                if (sb.length > TEXT_CAP) { sb.append("…(已达长度上限，后续页省略)"); break }
            }
            // 全部页都没识别出内容：别静默返回一堆「(本页未识别到内容)」骗 AI 说读过了——明确报错
            if (okPages == 0) return ToolResult(
                "PDF 每一页都没识别出内容" + (lastErr?.let { "（识图模型报错：$it）" } ?: "（可能是空白/纯图扫描件，或识图模型无响应）"),
                isError = true,
            )
            ToolResult(sb.toString().trim())
        } catch (e: Exception) {
            ToolResult("PDF 解析失败：${e.message}", isError = true)
        } finally {
            runCatching { renderer?.close() }; runCatching { pfd?.close() }
        }
    }

    private suspend fun visionConfig(): CloudApiConfig? {
        val m = CloudApiConfigManager(context)
        val e = m.getActiveByPurpose("vision") ?: return null
        return CloudApiConfig(e.baseUrl.trimEnd('/'), e.apiKey.trim(), e.model.trim(), e.temperature, e.topP, e.maxTokens, e.frequencyPenalty, e.presencePenalty)
    }

    /**
     * 文本类的读法分派：**按体量与参数决定给什么**，而不是永远给开头那一截。
     *
     *  - 给了 pattern：全文流式筛行（只回命中行 + 行号），大文件先定位用
     *  - 给了 offset/limit：流式跳到指定行读一段
     *  - 都没给：小文件整份给；大文件给开头一段 + **说清总量和怎么继续读**
     *    （原来只截断不报量，模型不知道自己少看了什么，只会照着开头下结论）
     * 全程流式，不把大文件读进内存。
     */
    private fun readTextSmart(file: File, params: JSONObject): String {
        val pattern = params.optString("pattern", "").trim()
        val offset = params.optInt("offset", 0)
        val limit = params.optInt("limit", 0)
        if (pattern.isNotBlank()) return grepLines(file, pattern)
        if (offset > 0 || limit > 0) return readLineRange(file, offset.coerceAtLeast(1), if (limit > 0) limit.coerceAtMost(2000) else 300)
        val body = readTextBounded(file)
        if (!body.endsWith("…(内容过长，已截断)")) return body
        // 被截断：把"还有多少"和"怎么继续"一并说清
        val total = countLines(file)
        val shown = body.count { it == '\n' } + 1
        return body.removeSuffix("…(内容过长，已截断)") +
            "\n\n[以上是开头 $shown 行，全文共 $total 行。继续读：offset=${shown + 1} 配 limit 分段；" +
            "或 pattern=\"正则\" 只取相关行（大文件先定位再精读）；或 into_memory=true 切块入库后语义检索]"
    }

    /** 流式筛行：只回命中行，带行号。上限保护：最多扫 500 万行、回 200 条命中。 */
    private fun grepLines(file: File, pattern: String): String {
        val rx = runCatching { Regex(pattern) }.getOrNull() ?: return "正则无效：$pattern"
        val hits = ArrayList<String>(64)
        var n = 0; var scanned = 0
        file.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                n++; scanned++
                if (scanned > 5_000_000) break
                if (rx.containsMatchIn(line)) {
                    hits.add("$n\t${line.take(600)}")
                    if (hits.size >= 200) break
                }
            }
        }
        if (hits.isEmpty()) return "没有匹配「$pattern」的行（共扫 $n 行）。换个关键词，或用 offset/limit 分段浏览。"
        return hits.joinToString("\n").take(TEXT_CAP) +
            (if (hits.size >= 200) "\n…(命中过多，只回前 200 条，缩小正则范围)" else "")
    }

    /** 流式读第 offset 行起的 limit 行（1 起），输出仍受 TEXT_CAP 约束。 */
    private fun readLineRange(file: File, offset: Int, limit: Int): String {
        val sb = StringBuilder()
        var n = 0; var taken = 0
        file.bufferedReader().use { r ->
            while (true) {
                val line = r.readLine() ?: break
                n++
                if (n < offset) continue
                sb.append(n).append('\t').append(line).append('\n')
                taken++
                if (taken >= limit || sb.length > TEXT_CAP) break
            }
        }
        if (taken == 0) return "第 $offset 行之后没有内容（文件共 $n 行）"
        return sb.toString().trimEnd() + "\n\n[已给第 $offset–${offset + taken - 1} 行；继续用 offset=${offset + taken}]"
    }

    private fun countLines(file: File): Int {
        var n = 0
        runCatching { file.bufferedReader().use { r -> while (r.readLine() != null) { n++; if (n > 5_000_000) return n } } }
        return n
    }

    /** 有界读取文本：只读到 TEXT_CAP+1 个字符就停，绝不把整个大文件（几百 MB 的 log）读进内存 OOM。
     *  OutOfMemoryError 是 Error 不被上面的 catch(Exception) 捕获，会直接崩 app，故从源头限量。 */
    private fun readTextBounded(file: File): String {
        val buf = CharArray(4096)
        val sb = StringBuilder()
        file.bufferedReader().use { r ->
            while (sb.length <= TEXT_CAP) {
                val n = r.read(buf)
                if (n < 0) break
                sb.append(buf, 0, n)
            }
        }
        return if (sb.length > TEXT_CAP) sb.substring(0, TEXT_CAP) + "\n…(内容过长，已截断)" else sb.toString()
    }

    // ---- zip 里某个 XML 抽文字（docx/xlsx 共用）----
    private fun zipXmlText(file: File, entryName: String, perParagraph: Boolean): String {
        // 走 DocParsers.readEntry：带 40MB 单条目上限，防恶意压缩(zip bomb)的 sharedStrings.xml 解压爆内存崩 app
        val xml = DocParsers.readEntry(file, entryName)?.toString(Charsets.UTF_8) ?: ""
        if (xml.isBlank()) return ""
        var s = xml
        if (perParagraph) s = s.replace(Regex("</w:p>"), "\n")           // docx 段落
        s = s.replace(Regex("<[^>]+>"), "")                             // 去所有标签
        s = s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'")
        return s.replace(Regex("[ \\t]{2,}"), " ").replace(Regex("\\n{3,}"), "\n\n").trim()
    }

    companion object { private const val TEXT_CAP = 20000; private const val CHUNK = 700; private const val MAX_CHUNKS = 60 }
}
