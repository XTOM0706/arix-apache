package com.arix.tool

import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Html
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.AbsoluteSizeSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * 把 Markdown / HTML 生成 **PDF** 或 **Word(.docx)**，零第三方依赖。
 *
 * 为什么值得自己写：竞品那条路是「装 pandoc 再调它」，在手表上不成立（几十 MB 的工具链 + 要有终端环境）。
 * 而安卓自带 [PdfDocument] 能画 PDF，docx 本身只是个 zip 里塞几个 XML——两样都不需要引任何库。
 * 之前 `file_converter` 只能在文本格式之间倒腾、`read_document` 只读不写，也就是说 AI 写完一份东西
 * 交不出**能发给别人的文件**。
 *
 * 统一走一套中间表示（[DocBlock]）：Markdown 直接解析成块，HTML 先交给系统的 [Html] 转成 Spanned 再拆成块，
 * 于是 PDF 和 docx 两个后端共用同一份结构，不用各写一遍解析。
 */

/** 一段文字里的一小截，带着它的字形。 */
internal data class DocRun(val text: String, val bold: Boolean = false, val italic: Boolean = false, val mono: Boolean = false)

/** 一个段落。[heading] 0=正文，1~6=标题级别。 */
internal data class DocBlock(
    val runs: List<DocRun>,
    val heading: Int = 0,
    val bullet: Boolean = false,
    val quote: Boolean = false,
    val code: Boolean = false,
) {
    val plain: String get() = runs.joinToString("") { it.text }
}

internal object DocExport {

    // ============================================================
    // Markdown → 块
    // ============================================================

    /**
     * 只认最常用的那几样：标题、列表、引用、围栏代码块、粗体/斜体/行内代码/链接。
     * 刻意不做表格和嵌套列表——它们在只有一列文字的 PDF 里本来就排不好，
     * 硬做只会得到一份看着更乱的文件，而不是更完整的文件。
     */
    fun parseMarkdown(md: String): List<DocBlock> {
        val out = mutableListOf<DocBlock>()
        val para = mutableListOf<String>()
        var inCode = false
        val code = mutableListOf<String>()

        fun flushPara() {
            if (para.isEmpty()) return
            out.add(DocBlock(inlineRuns(joinLines(para))))
            para.clear()
        }

        for (raw in md.lines()) {
            val line = raw.trimEnd()
            if (line.trimStart().startsWith("```")) {
                if (inCode) { out.add(DocBlock(listOf(DocRun(code.joinToString("\n"), mono = true)), code = true)); code.clear() }
                else flushPara()
                inCode = !inCode
                continue
            }
            if (inCode) { code.add(raw); continue }

            val t = line.trim()
            when {
                t.isEmpty() -> flushPara()
                // 分隔线：PDF 里画不出线，当成一个空段落，至少把上下文隔开
                t.matches(Regex("^(-{3,}|\\*{3,}|_{3,})$")) -> { flushPara(); out.add(DocBlock(emptyList())) }
                t.startsWith("#") -> {
                    val level = t.takeWhile { it == '#' }.length.coerceIn(1, 6)
                    val text = t.drop(level).trim()
                    if (text.isNotEmpty()) { flushPara(); out.add(DocBlock(inlineRuns(text), heading = level)) }
                    else para.add(t)
                }
                t.startsWith("> ") || t == ">" -> { flushPara(); out.add(DocBlock(inlineRuns(t.removePrefix(">").trim()), quote = true)) }
                Regex("^[-*+]\\s+").containsMatchIn(t) -> { flushPara(); out.add(DocBlock(inlineRuns(t.replaceFirst(Regex("^[-*+]\\s+"), "")), bullet = true)) }
                Regex("^\\d+[.)]\\s+").containsMatchIn(t) -> {
                    flushPara()
                    // 序号保留成正文的一部分：不引 numbering.xml，docx 才不需要多带一个部件
                    out.add(DocBlock(inlineRuns(t), bullet = false))
                }
                else -> para.add(t)
            }
        }
        if (inCode && code.isNotEmpty()) out.add(DocBlock(listOf(DocRun(code.joinToString("\n"), mono = true)), code = true))
        flushPara()
        return out
    }

    /**
     * 同一段里的换行要不要补空格：英文之间要（不然 word 会粘成 wordword），中文之间不要（会多出难看的空隙）。
     * 判据只看接缝两侧那一个字符，够用且不会误伤。
     */
    private fun joinLines(lines: List<String>): String = buildString {
        lines.forEachIndexed { i, l ->
            if (i == 0) { append(l); return@forEachIndexed }
            val prev = lastOrNull()
            val next = l.firstOrNull()
            if (prev != null && next != null && !isCjk(prev) && !isCjk(next)) append(' ')
            append(l)
        }
    }

    private fun isCjk(c: Char): Boolean = c.code in 0x2E80..0x9FFF || c.code in 0xF900..0xFAFF || c.code in 0xFF00..0xFFEF

    private val INLINE = Regex(
        """\*\*(.+?)\*\*|__(.+?)__|`([^`]+)`|\[([^\]]*)\]\(([^)\s]+)[^)]*\)|\*([^*\n]+)\*|_([^_\n]+)_"""
    )

    private fun inlineRuns(text: String): List<DocRun> {
        if (text.isEmpty()) return emptyList()
        val runs = mutableListOf<DocRun>()
        var last = 0
        for (m in INLINE.findAll(text)) {
            if (m.range.first > last) runs.add(DocRun(text.substring(last, m.range.first)))
            val g = m.groupValues
            when {
                g[1].isNotEmpty() -> runs.add(DocRun(g[1], bold = true))
                g[2].isNotEmpty() -> runs.add(DocRun(g[2], bold = true))
                g[3].isNotEmpty() -> runs.add(DocRun(g[3], mono = true))
                g[5].isNotEmpty() -> {
                    // 链接：纸上点不动，把地址跟在后面才有用
                    runs.add(DocRun(g[4].ifEmpty { g[5] }))
                    if (g[4].isNotEmpty()) runs.add(DocRun("（${g[5]}）"))
                }
                g[6].isNotEmpty() -> runs.add(DocRun(g[6], italic = true))
                g[7].isNotEmpty() -> runs.add(DocRun(g[7], italic = true))
            }
            last = m.range.last + 1
        }
        if (last < text.length) runs.add(DocRun(text.substring(last)))
        return runs.filter { it.text.isNotEmpty() }
    }

    // ============================================================
    // HTML → 块（借系统的 Html 解析，再从 Spanned 上把字形读回来）
    // ============================================================

    @Suppress("DEPRECATION")
    fun parseHtml(html: String): List<DocBlock> {
        val sp: Spanned = try {
            Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
        } catch (_: Throwable) { return parseMarkdown(html) }
        val out = mutableListOf<DocBlock>()
        val text = sp.toString()
        var start = 0
        while (start <= text.length) {
            val nl = text.indexOf('\n', start).let { if (it < 0) text.length else it }
            if (nl > start) {
                val runs = mutableListOf<DocRun>()
                var i = start
                var heading = 0
                while (i < nl) {
                    val next = sp.nextSpanTransition(i, nl, Any::class.java)
                    var bold = false; var italic = false; var mono = false
                    sp.getSpans(i, next, Any::class.java).forEach { s ->
                        when (s) {
                            is StyleSpan -> {
                                if (s.style == Typeface.BOLD || s.style == Typeface.BOLD_ITALIC) bold = true
                                if (s.style == Typeface.ITALIC || s.style == Typeface.BOLD_ITALIC) italic = true
                            }
                            is TypefaceSpan -> if (s.family == "monospace") mono = true
                            // Html 把 h1~h6 转成 相对字号 + 粗体，反过来按字号把标题级别认回来
                            is RelativeSizeSpan -> heading = when {
                                s.sizeChange >= 1.45f -> 1
                                s.sizeChange >= 1.35f -> 2
                                s.sizeChange >= 1.25f -> 3
                                s.sizeChange > 1.0f -> 4
                                else -> heading
                            }
                        }
                    }
                    runs.add(DocRun(text.substring(i, next), bold, italic, mono))
                    i = next
                }
                out.add(DocBlock(runs.filter { it.text.isNotEmpty() }, heading = heading))
            }
            if (nl >= text.length) break
            start = nl + 1
        }
        return out
    }

    // ============================================================
    // 块 → PDF
    // ============================================================

    private const val PAGE_W = 595      // A4 @72dpi，PdfDocument 的单位就是点
    private const val PAGE_H = 842
    private const val MARGIN = 48
    private const val BODY_PT = 11f

    fun writePdf(blocks: List<DocBlock>, out: OutputStream) {
        val paint = TextPaint().apply {
            isAntiAlias = true
            textSize = BODY_PT
            color = android.graphics.Color.BLACK
            typeface = Typeface.DEFAULT
        }
        val spanned = blocksToSpanned(blocks)
        val width = PAGE_W - 2 * MARGIN
        val contentH = PAGE_H - 2 * MARGIN
        val layout = StaticLayout.Builder.obtain(spanned, 0, spanned.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(2f, 1.25f)
            .setIncludePad(false)
            .build()

        val doc = PdfDocument()
        try {
            var line = 0
            var pageNo = 1
            if (layout.lineCount == 0) {
                // 空文档也要出一页，不然生成的 PDF 打不开
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, 1).create())
                doc.finishPage(page)
            }
            while (line < layout.lineCount) {
                val top = layout.getLineTop(line)
                var end = line
                while (end < layout.lineCount && layout.getLineBottom(end) - top <= contentH) end++
                if (end == line) end = line + 1   // 单行比一整页还高（超大标题）：硬塞一行，否则死循环
                val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_W, PAGE_H, pageNo++).create())
                val canvas = page.canvas
                canvas.save()
                canvas.translate(MARGIN.toFloat(), MARGIN.toFloat() - top)
                canvas.clipRect(0, top, width, top + contentH)
                layout.draw(canvas)
                canvas.restore()
                doc.finishPage(page)
                line = end
            }
            doc.writeTo(out)
        } finally { doc.close() }
    }

    private fun blocksToSpanned(blocks: List<DocBlock>): Spanned {
        val sb = SpannableStringBuilder()
        blocks.forEachIndexed { idx, b ->
            val blockStart = sb.length
            if (b.bullet) sb.append("• ")
            b.runs.forEach { r ->
                val s = sb.length
                sb.append(r.text)
                val style = when {
                    r.bold && r.italic -> Typeface.BOLD_ITALIC
                    r.bold -> Typeface.BOLD
                    r.italic -> Typeface.ITALIC
                    else -> -1
                }
                if (style >= 0) sb.setSpan(StyleSpan(style), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (r.mono) sb.setSpan(TypefaceSpan("monospace"), s, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            val blockEnd = sb.length
            if (blockEnd > blockStart) {
                if (b.heading in 1..6) {
                    val size = when (b.heading) { 1 -> 19f; 2 -> 16f; 3 -> 14f; 4 -> 12.5f; else -> BODY_PT + 0.5f }
                    sb.setSpan(AbsoluteSizeSpan(size.toInt(), false), blockStart, blockEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                    sb.setSpan(StyleSpan(Typeface.BOLD), blockStart, blockEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (b.quote || b.bullet || b.code) {
                    sb.setSpan(LeadingMarginSpan.Standard(if (b.bullet) 14 else 20), blockStart, blockEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                if (b.code) sb.setSpan(TypefaceSpan("monospace"), blockStart, blockEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            // 段与段之间空一行；连着的列表项只换行不空行，否则清单会被抻得又长又散
            val tight = b.bullet && blocks.getOrNull(idx + 1)?.bullet == true
            if (idx < blocks.lastIndex) sb.append(if (tight) "\n" else "\n\n")
        }
        return sb
    }

    // ============================================================
    // 块 → docx
    //
    // 最小可用的 OOXML 包：三个部件就够 Word/WPS/Google Docs 正常打开。
    // 刻意**只用直接格式**（w:rPr 里写粗体/字号），不引 styles.xml —— 少一个部件就少一处能写错的地方，
    // 而 w:pStyle 指向一个不存在的样式在部分阅读器上就是彻底丢格式。
    // ============================================================

    fun buildDocx(blocks: List<DocBlock>): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { zip ->
            // [Content_Types].xml 必须是第一个条目：部分阅读器就是按这个顺序找的
            zip.entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
</Types>""")
            zip.entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/>
</Relationships>""")
            zip.entry("word/document.xml", documentXml(blocks))
        }
        return bos.toByteArray()
    }

    private fun ZipOutputStream.entry(name: String, content: String) {
        putNextEntry(ZipEntry(name))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun documentXml(blocks: List<DocBlock>): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>""").append('\n')
        append("""<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>""")
        blocks.forEach { b -> append(paragraphXml(b)) }
        // A4 纵向 + 常规页边距（单位是 twip：1 磅 = 20 twip）
        append("""<w:sectPr><w:pgSz w:w="11906" w:h="16838"/><w:pgMar w:top="1134" w:right="1134" w:bottom="1134" w:left="1134"/></w:sectPr>""")
        append("</w:body></w:document>")
    }

    private fun paragraphXml(b: DocBlock): String {
        // 字号单位是半磅：正文 11pt → 22
        val halfPt = when (b.heading) { 1 -> 32; 2 -> 28; 3 -> 26; 4 -> 24; 5, 6 -> 23; else -> 22 }
        val headingBold = b.heading in 1..6
        val indent = if (b.quote || b.code) """<w:ind w:left="480"/>""" else ""
        val spacing = if (headingBold) """<w:spacing w:before="240" w:after="120"/>""" else """<w:spacing w:after="120"/>"""
        val pPr = "<w:pPr>$spacing$indent</w:pPr>"

        val runs = if (b.bullet) listOf(DocRun("• ")) + b.runs else b.runs
        if (runs.isEmpty()) return "<w:p>$pPr</w:p>"

        val body = runs.joinToString("") { r ->
            val rPr = buildString {
                append("<w:rPr>")
                if (r.bold || headingBold) append("<w:b/>")
                if (r.italic) append("<w:i/>")
                if (r.mono || b.code) append("""<w:rFonts w:ascii="Consolas" w:hAnsi="Consolas" w:cs="Consolas"/>""")
                append("""<w:sz w:val="$halfPt"/><w:szCs w:val="$halfPt"/>""")
                append("</w:rPr>")
            }
            // 段内换行（代码块常有）要拆成 w:br，直接塞 \n 到 w:t 里 Word 会当成一个空格
            val pieces = r.text.split("\n")
            pieces.mapIndexed { i, piece ->
                (if (i > 0) "<w:r>$rPr<w:br/></w:r>" else "") +
                    if (piece.isEmpty()) "" else """<w:r>$rPr<w:t xml:space="preserve">${esc(piece)}</w:t></w:r>"""
            }.joinToString("")
        }
        return "<w:p>$pPr$body</w:p>"
    }

    /** XML 转义 + 丢掉 XML 1.0 压根不允许出现的控制字符（工具输出里常混进来，留着整份文件都打不开）。 */
    private fun esc(s: String): String = buildString(s.length + 16) {
        s.forEach { c ->
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' -> append("    ")
                c.code < 0x20 -> {}
                else -> append(c)
            }
        }
    }
}
