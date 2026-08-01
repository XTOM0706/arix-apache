package com.arix.tool

import java.io.File
import java.util.zip.ZipInputStream

/**
 * DocParsers —— 纯离线（java.util.zip 解 zip + 正则/简单 XML 抽文本）的 Office/EPUB 解析工具集。
 * 供 DocReadTool 调用：DOCX(word/document.xml)、PPTX(ppt/slides/slideN.xml)、EPUB(container→OPF→spine→xhtml)。
 * 除 PDF 文本层外全部不依赖第三方库。
 */
object DocParsers {

    // 单个 zip 条目最大解 ~40MB，防手表 OOM（xml 抽文字够用）
    private const val ENTRY_CAP = 40L * 1024 * 1024

    /** 读取 zip 里指定名字的条目为字节；不存在返回 null。顺序扫描一次。 */
    fun readEntry(file: File, name: String): ByteArray? = readEntries(file, setOf(name))[name]

    /**
     * 一次顺序扫描把 zip 里“想要的”条目读进内存。
     * @param wanted 精确名集合；为 null 时用 predicate 过滤。
     */
    fun readEntries(file: File, wanted: Set<String>? = null, predicate: ((String) -> Boolean)? = null): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        file.inputStream().use { fis ->
            ZipInputStream(fis).use { zis ->
                var e = zis.nextEntry
                while (e != null) {
                    val n = e.name
                    val take = when {
                        wanted != null -> n in wanted
                        predicate != null -> predicate(n)
                        else -> true
                    }
                    if (take && !e.isDirectory) {
                        val bytes = readBounded(zis)
                        if (bytes != null) out[n] = bytes
                    }
                    zis.closeEntry()
                    e = zis.nextEntry
                }
            }
        }
        return out
    }

    private fun readBounded(zis: ZipInputStream): ByteArray? {
        val buf = ByteArray(8192)
        val bos = java.io.ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val n = zis.read(buf)
            if (n < 0) break
            total += n
            if (total > ENTRY_CAP) return bos.toByteArray()   // 超限就截断，别撑爆内存
            bos.write(buf, 0, n)
        }
        return bos.toByteArray()
    }

    // ---------------- DOCX ----------------

    /** word/document.xml：<w:p> 段落转换行，<w:tab/>→制表，<w:br/>→换行，抽 <w:t> 文字。 */
    fun parseDocx(file: File): String {
        val xml = readEntry(file, "word/document.xml")?.toString(Charsets.UTF_8) ?: return ""
        var s = xml
        s = s.replace(Regex("<w:tab\\b[^>]*/?>"), "\t")
        s = s.replace(Regex("<w:br\\b[^>]*/?>"), "\n")
        s = s.replace(Regex("</w:p>"), "\n")               // 段落边界
        s = stripTags(s)
        return normalizeWs(decodeEntities(s))
    }

    // ---------------- PPTX ----------------

    /** 按数字顺序读 ppt/slides/slideN.xml，逐张抽 <a:t> 文字，加「幻灯片 N」小标题。 */
    fun parsePptx(file: File): String {
        val slideRe = Regex("^ppt/slides/slide(\\d+)\\.xml$")
        val slides = readEntries(file) { slideRe.matches(it) }
            .toList()
            .sortedBy { slideRe.find(it.first)!!.groupValues[1].toInt() }
        if (slides.isEmpty()) return ""
        val sb = StringBuilder()
        slides.forEachIndexed { idx, (_, bytes) ->
            val xml = bytes.toString(Charsets.UTF_8)
            // 按 <a:p> 段落拆行，段内所有 run 的 <a:t> 拼在一起
            val paras = xml.split("</a:p>").mapNotNull { p ->
                val line = Regex("<a:t>(.*?)</a:t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(p).joinToString("") { it.groupValues[1] }
                val text = normalizeWs(decodeEntities(line)).trim()
                text.ifBlank { null }
            }
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append("幻灯片 ${idx + 1}\n")
            sb.append(if (paras.isEmpty()) "(本页无文字)" else paras.joinToString("\n"))
        }
        return sb.toString().trim()
    }

    // ---------------- EPUB ----------------

    /** container.xml→OPF 路径→spine 顺序→依次读 xhtml 去标签抽正文，章节空行分隔。 */
    fun parseEpub(file: File): String {
        val container = readEntry(file, "META-INF/container.xml")?.toString(Charsets.UTF_8) ?: return ""
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1) ?: return ""
        val opf = readEntry(file, opfPath)?.toString(Charsets.UTF_8) ?: return ""
        val baseDir = opfPath.substringBeforeLast('/', "")

        // manifest: id -> href
        val idToHref = HashMap<String, String>()
        Regex("<item\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).findAll(opf).forEach { m ->
            val tag = m.value
            val id = Regex("\\bid=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
            val href = Regex("\\bhref=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)
            if (id != null && href != null) idToHref[id] = href
        }
        // spine: itemref idref 顺序
        val spineRefs = Regex("<itemref\\b[^>]*idref=\"([^\"]+)\"[^>]*>", RegexOption.DOT_MATCHES_ALL)
            .findAll(opf).map { it.groupValues[1] }.toList()
        if (spineRefs.isEmpty()) return ""

        // 需要的 xhtml 全路径
        val targets = LinkedHashMap<String, String>() // fullPath -> href(用于顺序)
        spineRefs.forEach { ref ->
            val href = idToHref[ref] ?: return@forEach
            // OPF 里的 href 是 URL 编码的(空格→%20、CJK→%XX)，而 zip 条目名是字面量 → 必须先解码，否则查不到条目、整章静默丢失
            val decoded = try { java.net.URLDecoder.decode(hrefNoFragment(href).replace("+", "%2B"), "UTF-8") } catch (_: Exception) { hrefNoFragment(href) }
            val full = joinPath(baseDir, decoded)
            targets[full] = href
        }
        if (targets.isEmpty()) return ""
        val docs = readEntries(file, wanted = targets.keys)

        val sb = StringBuilder()
        for (full in targets.keys) {
            val bytes = docs[full] ?: continue
            val text = htmlToText(bytes.toString(Charsets.UTF_8))
            if (text.isBlank()) continue
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(text)
        }
        return sb.toString().trim()
    }

    // ---------------- 通用 HTML/XML → 文本 ----------------

    /** 去掉 script/style，块级标签转换行，其余标签删除，解码实体，规整空白。 */
    fun htmlToText(html: String): String {
        var s = html
        s = s.replace(Regex("<script\\b[^>]*>.*?</script>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        s = s.replace(Regex("<style\\b[^>]*>.*?</style>", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)), " ")
        s = s.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), " ")
        // 块级/换行标签 → 换行
        s = s.replace(Regex("<br\\b[^>]*/?>", RegexOption.IGNORE_CASE), "\n")
        s = s.replace(Regex("</(p|div|h[1-6]|li|tr|section|article|header|footer|blockquote|pre)>", RegexOption.IGNORE_CASE), "\n")
        s = stripTags(s)
        return normalizeWs(decodeEntities(s))
    }

    private fun stripTags(s: String): String = s.replace(Regex("<[^>]+>"), "")

    /** 解码常见命名实体 + 数字实体(&#123; / &#x1F;)。 */
    fun decodeEntities(s: String): String {
        var r = s
        if (r.indexOf('&') < 0) return r
        r = Regex("&#x([0-9a-fA-F]+);").replace(r) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt(16))) }.getOrDefault(m.value)
        }
        r = Regex("&#(\\d+);").replace(r) { m ->
            runCatching { String(Character.toChars(m.groupValues[1].toInt())) }.getOrDefault(m.value)
        }
        return r.replace("&nbsp;", " ")
            .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&apos;", "'")
    }

    /** 合并多余空格/空行，去行尾空白。 */
    fun normalizeWs(s: String): String =
        s.replace("\r\n", "\n").replace('\r', '\n')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex(" *\\n *"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

    // ---------------- 路径工具 ----------------

    private fun hrefNoFragment(href: String): String = href.substringBefore('#')

    /** 把相对 href 拼到 OPF 所在目录并归一化 ./ 与 ../。 */
    private fun joinPath(baseDir: String, href: String): String {
        val h = href.trim()
        if (h.startsWith("/")) return h.trimStart('/')
        val parts = ArrayList<String>()
        if (baseDir.isNotEmpty()) parts.addAll(baseDir.split('/'))
        for (seg in h.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }
}
