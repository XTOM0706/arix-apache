package com.arix.app

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.Placeholder
import androidx.compose.ui.text.PlaceholderVerticalAlign
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.TextUnit
// —— 段落文本布局缓存（B 方案：缓存 TextLayoutResult，滚出再滚入不重测字形）——
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.graphics.drawscope.ContentDrawScope

// ============================================================
// 轻量 Markdown 渲染器（无第三方依赖）
// 支持：标题/粗体/斜体/行内码/链接/无序·有序列表/引用/分割线/围栏代码块(带复制)
// ============================================================

private sealed class MdBlock {
    data class Code(val lang: String, val content: String) : MdBlock()
    data class Heading(val level: Int, val text: String) : MdBlock()
    data class Quote(val text: String, val level: Int = 1) : MdBlock()
    // checked: null=普通列表项；true/false=任务列表复选框（- [x] / - [ ]）
    data class Bullet(val text: String, val ordered: Boolean, val marker: String, val checked: Boolean? = null) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Image(val url: String, val alt: String) : MdBlock()
    data class Table(val header: List<String>, val rows: List<List<String>>) : MdBlock()
    data class Math(val latex: String) : MdBlock()
    data class Footnotes(val items: List<Pair<String, String>>) : MdBlock()
    data class DefList(val items: List<Pair<String, List<String>>>) : MdBlock()
    data class Details(val summary: String, val content: String) : MdBlock()
    data class RawSvg(val content: String) : MdBlock()      // 内联 <svg>…</svg> 标记
    data class Iframe(val url: String) : MdBlock()           // <iframe src="…">
    data class Media(val url: String, val video: Boolean) : MdBlock()  // <audio>/<video>
    object Rule : MdBlock()
}

// <img>/<iframe> 取 src、<img> 取 alt（宽容匹配单双引号）
private val htmlSrcAttr = Regex("\\bsrc\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
private val htmlAltAttr = Regex("\\balt\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)

private fun splitRow(line: String): List<String> =
    line.trim().trim('|').split("|").map { it.trim() }

// 图片：![alt](url "可选标题") —— URL 取到首个空格前，剥掉标题/多余空白，否则 URL 带引号加载失败
private val imageLine = Regex("^!\\[([^\\]]*)\\]\\(\\s*(\\S+?)(?:\\s+\"[^\"]*\")?\\s*\\)$")
private val tableSep = Regex("^\\s*\\|?[\\s:|-]*-[\\s:|-]*\\|?\\s*$")
// 提到文件级，避免 parseMarkdown 主循环里每行每次重新编译（叠加流式重解析会放大掉帧）
private val headingLine = Regex("^#{1,6}\\s")
private val ruleLine = Regex("^(-{3,}|\\*{3,}|_{3,})$")
private val bulletLine = Regex("^[-*+]\\s")
private val orderedLine = Regex("^\\d+\\.\\s")
private val taskItem = Regex("^\\[([ xX])\\]\\s*(.*)$")
// #7 行内 HTML：判定 <...> 是否像标签（避免把 “a < b” 里的 < 当标签），以及取 <a href="...">
private val htmlTagLike = Regex("^/?[a-zA-Z][a-zA-Z0-9]*(?:\\s[^<>]*?)?/?$")
private val hrefRegex = Regex("href\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE)
// #6 脚注定义：整行 [^id]: 说明文字
private val footnoteDef = Regex("^\\[\\^([^\\]]+)\\]:\\s*(.*)$")

// #1 部分模型（DeepSeek/GLM 等）在原生 tool_calls 不生效时，会把工具调用以文本形式吐出
// （<｜｜DSML｜｜tool_calls>…）泄漏到正文。这里在显示/保存前整块剥除，避免满屏乱码。
// 竖线兼容全角 ｜ 与半角 |。
private val dsmlBlockClosed = Regex("<[｜|]{0,2}\\s*DSML[｜|]{0,2}\\s*tool_calls\\s*>[\\s\\S]*?</[｜|]{0,2}\\s*DSML[｜|]{0,2}\\s*tool_calls\\s*>")
/** 未闭合：从起始标记吃到结尾。**只在流式中途能这么干**——见 [stripLeakedToolCalls] 的 streaming 参数。 */
private val dsmlBlockOpen = Regex("<[｜|]{0,2}\\s*DSML[｜|]{0,2}\\s*tool_calls\\s*>[\\s\\S]*$")
private val dsmlStray = Regex("</?[｜|]{0,2}\\s*DSML[｜|]{0,2}[^>]*>")

/**
 * @param streaming 是否是流式中途的半截文本。
 *
 * 这个区分很要紧：未闭合的标记「从起始吃到结尾」在**流式中途**是对的（后面的还没收到），
 * 但对**已收完的最终文本**这么干，就等于模型漏了个不闭合标记、后面写的真答案被整段吞掉
 * → 用户看到空回复。恰恰是爱漏 DSML 的那些模型最常踩。
 * 所以最终文本只剥「有头有尾」的完整块，未闭合的宁可留着让用户看见乱码，也不能吞掉答案。
 */
fun stripLeakedToolCalls(s: String, streaming: Boolean = false): String {
    if (!s.contains("DSML")) return s
    var out = dsmlBlockClosed.replace(s, "")
    if (streaming) out = dsmlBlockOpen.replace(out, "")
    return dsmlStray.replace(out, "").trim()
}

// #9 任意颜色文本：解析 CSS 颜色（#hex / rgb() / 常见色名）。这里的色值来自 AI 标记(<font color>/
// <span style="color:">)，是“数据”而非本 App 的设计配色，故允许字面色值（不违反令牌化设计约束）。
// 文字色：前面加负向断言，避免匹配到 background-color / border-color 里的 “color”
private val cssColorAttr = Regex("(?<![-a-zA-Z])color\\s*[:=]\\s*[\"']?\\s*(#[0-9a-fA-F]{3,8}|rgba?\\([^)]*\\)|[a-zA-Z]+)", RegexOption.IGNORE_CASE)
// 背景色：background / background-color（渐变胶囊/荧光背景）
private val cssBgAttr = Regex("background(?:-color)?\\s*[:=]\\s*[\"']?\\s*(#[0-9a-fA-F]{3,8}|rgba?\\([^)]*\\)|[a-zA-Z]+)", RegexOption.IGNORE_CASE)
private val rgbFunc = Regex("rgba?\\(\\s*(\\d+)\\D+(\\d+)\\D+(\\d+)")
private val namedCssColors = mapOf(
    "red" to Color(0xFFF44336), "green" to Color(0xFF4CAF50), "blue" to Color(0xFF2196F3),
    "yellow" to Color(0xFFFFC107), "orange" to Color(0xFFFF9800), "purple" to Color(0xFF9C27B0),
    "pink" to Color(0xFFE91E63), "cyan" to Color(0xFF00BCD4), "teal" to Color(0xFF009688),
    "brown" to Color(0xFF795548), "gray" to Color(0xFF9E9E9E), "grey" to Color(0xFF9E9E9E),
    "black" to Color(0xFF000000), "white" to Color(0xFFFFFFFF), "magenta" to Color(0xFFFF00FF),
    "lime" to Color(0xFFCDDC39), "indigo" to Color(0xFF3F51B5), "gold" to Color(0xFFFFD700),
    "silver" to Color(0xFFC0C0C0), "navy" to Color(0xFF001F7F), "violet" to Color(0xFFEE82EE),
)
private fun parseCssColor(raw: String): Color? {
    val t = raw.trim().lowercase()
    namedCssColors[t]?.let { return it }
    if (t.startsWith("#")) {
        val h = t.substring(1)
        val hex = when (h.length) { 3 -> h.map { "$it$it" }.joinToString(""); 6, 8 -> h; else -> return null }
        val n = hex.toLongOrNull(16) ?: return null
        return if (hex.length == 8) { val a = n and 0xFF; val rgb = n ushr 8; Color(((a shl 24) or rgb).toInt()) }
               else Color((0xFF000000L or n).toInt())
    }
    rgbFunc.find(t)?.let { m -> return try { Color(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) } catch (_: Exception) { null } }
    return null
}

// #6 解码常见 HTML 实体（&nbsp; &amp; &lt; 等及数字实体），否则会原样显示成 “&nbsp;” 并留大片空白。
private val numericEntity = Regex("&#(x?[0-9a-fA-F]+);")
private val namedEntities = mapOf(
    "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "#39" to "'",
    "mdash" to "—", "ndash" to "–", "hellip" to "…", "middot" to "·", "bull" to "•",
    "copy" to "©", "reg" to "®", "trade" to "™", "times" to "×", "divide" to "÷", "deg" to "°",
    "laquo" to "«", "raquo" to "»", "ldquo" to "“", "rdquo" to "”", "lsquo" to "‘", "rsquo" to "’",
    "larr" to "←", "rarr" to "→", "uarr" to "↑", "darr" to "↓", "check" to "✓", "cross" to "✗",
)
fun decodeHtmlEntities(s: String): String {
    if (!s.contains('&')) return s
    var r = numericEntity.replace(s) { m ->
        val g = m.groupValues[1]
        try {
            val cp = if (g.startsWith("x") || g.startsWith("X")) g.substring(1).toInt(16) else g.toInt()
            if (cp in 1..0x10FFFF) String(Character.toChars(cp)) else m.value
        } catch (_: Exception) { m.value }
    }
    for ((k, v) in namedEntities) r = r.replace("&$k;", v)
    return r
}

// ============================================================
// mhchem \ce{...} → 纯 LaTeX 转换
// JLaTeXMath 默认不带 mhchem 包，直接把 \ce{} 丢给它会报错，故先把化学式转成它能渲染的 LaTeX：
//   数字→下标(H2→H_2)、电荷 ^2-/+→上标、-> <-> <=>→箭头、->[X]→\xrightarrow{X}、
//   状态(s)(l)(g)(aq)、*→\cdot、系数前缀保留。解析不了的尽量原样退回，绝不抛异常。
// ============================================================

// 从 open 处（应指向 '{'）找配对的 '}'，返回其索引；找不到则返回末尾索引。
private fun matchBrace(s: String, open: Int): Int {
    var depth = 0
    var i = open
    while (i < s.length) {
        when (s[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return i } }
        i++
    }
    return s.length - 1
}

// 把 \ce{...} 内部内容转成纯 LaTeX。
private fun mhchemBodyToLatex(body: String): String {
    val s = body
    val n = s.length
    val sb = StringBuilder()
    var i = 0
    // 前一个已输出 token 是否“原子性”（元素/闭括号/上下标块）——决定紧跟的数字是下标还是系数。
    // 必须用显式布尔、别回看 sb 猜：箭头/命令输出的 LaTeX 词末尾是字母(如 \rightarrow 结尾的 w)，
    // 回看会把箭头后的系数(如 2H2 + O2 -> 2H2O 里的 2)误判成挂在箭头上的下标。
    var prevAtomic = false
    // 读取箭头可选标注 [above]([below])
    fun readArrowLabels(): Pair<String?, String?> {
        var above: String? = null; var below: String? = null
        if (i < n && s[i] == '[') { val e = s.indexOf(']', i + 1); if (e > i) { above = s.substring(i + 1, e).trim(); i = e + 1 } }
        if (i < n && s[i] == '[') { val e = s.indexOf(']', i + 1); if (e > i) { below = s.substring(i + 1, e).trim(); i = e + 1 } }
        return above to below
    }
    // 标注含反斜杠(如 \Delta)则原样透传，否则按正体文字处理
    fun labelTex(raw: String): String = if (raw.contains('\\')) raw else "\\mathrm{${raw.replace(" ", "\\,")}}"
    while (i < n) {
        val c = s[i]
        when {
            c == ' ' || c == '\t' -> { if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' '); i++ }
            // —— 箭头（长匹配在前）：箭头后 prevAtomic=false，紧跟数字才会正确判为系数 ——
            s.startsWith("<=>>", i) || s.startsWith("<<=>", i) -> { sb.append(" \\rightleftharpoons "); prevAtomic = false; i += 4 }
            s.startsWith("<=>", i) -> {
                i += 3; val (a, b) = readArrowLabels()
                when {
                    a == null -> sb.append(" \\rightleftharpoons ")
                    b == null -> sb.append(" \\overset{${labelTex(a)}}{\\rightleftharpoons} ")
                    else -> sb.append(" \\underset{${labelTex(b)}}{\\overset{${labelTex(a)}}{\\rightleftharpoons}} ")
                }
                prevAtomic = false
            }
            s.startsWith("<->", i) -> { i += 3; sb.append(" \\leftrightarrow "); prevAtomic = false }
            s.startsWith("->", i) -> {
                i += 2; val (a, b) = readArrowLabels()
                when {
                    a == null -> sb.append(" \\rightarrow ")
                    b == null -> sb.append(" \\xrightarrow{${labelTex(a)}} ")
                    else -> sb.append(" \\xrightarrow[${labelTex(b)}]{${labelTex(a)}} ")
                }
                prevAtomic = false
            }
            s.startsWith("<-", i) -> {
                i += 2; val (a, b) = readArrowLabels()
                when {
                    a == null -> sb.append(" \\leftarrow ")
                    b == null -> sb.append(" \\xleftarrow{${labelTex(a)}} ")
                    else -> sb.append(" \\xleftarrow[${labelTex(b)}]{${labelTex(a)}} ")
                }
                prevAtomic = false
            }
            // —— 状态 (s)(l)(g)(aq)（其余括号当分组）——
            c == '(' && run { val e = s.indexOf(')', i + 1); e > i && s.substring(i + 1, e).trim().lowercase() in setOf("s", "l", "g", "aq") } -> {
                val e = s.indexOf(')', i + 1)
                sb.append("(\\mathrm{${s.substring(i + 1, e).trim().lowercase()}})"); prevAtomic = true; i = e + 1
            }
            // —— * / · → \cdot（水合物点乘等）——
            c == '*' || c == '·' -> { sb.append(" \\cdot "); prevAtomic = false; i++ }
            // —— 显式上标/电荷 ^{...} 或 ^2- ——
            c == '^' -> {
                i++
                if (i < n && s[i] == '{') {
                    val e = matchBrace(s, i)
                    sb.append("^{").append(s.substring(i + 1, e)).append("}"); i = e + 1
                } else {
                    val st = i
                    while (i < n && s[i].isDigit()) i++
                    if (i < n && (s[i] == '+' || s[i] == '-')) i++
                    sb.append("^{").append(if (i > st) s.substring(st, i) else "").append("}")
                }
                prevAtomic = true   // 上标仍属同一原子团
            }
            // —— 数字：紧跟元素/闭括号则下标，否则为系数 ——
            c.isDigit() -> {
                if (prevAtomic) {
                    val st = i; while (i < n && (s[i].isDigit() || s[i] == '.')) i++
                    sb.append("_{").append(s.substring(st, i)).append("}")
                } else {
                    val st = i; while (i < n && (s[i].isDigit() || s[i] == '.' || s[i] == '/')) i++
                    sb.append(s.substring(st, i))
                }
                prevAtomic = false
            }
            // —— +：贴着物种且不接元素/数字=电荷；否则=反应加号 ——
            c == '+' -> {
                val trailing = i > 0 && s[i - 1] != ' ' && prevAtomic && (i + 1 >= n || s[i + 1] == ' ' || s[i + 1] in ")]}")
                if (trailing) { sb.append("^{+}"); i++ }
                else { if (sb.isNotEmpty() && sb.last() != ' ') sb.append(' '); sb.append("+ "); i++ }
                prevAtomic = false
            }
            // —— -：贴着物种且不接元素/数字=负电荷；否则原样（如单键 H-H）——
            c == '-' -> {
                val trailing = i > 0 && s[i - 1] != ' ' && prevAtomic && (i + 1 >= n || s[i + 1] == ' ' || s[i + 1] in ")]}")
                if (trailing) { sb.append("^{-}"); i++ }
                else { sb.append('-'); i++ }
                prevAtomic = false
            }
            // 透传已有 LaTeX 命令（如 \Delta）
            c == '\\' -> { val st = i; i++; while (i < n && s[i].isLetter()) i++; sb.append(s.substring(st, i)); prevAtomic = false }
            else -> { sb.append(c); prevAtomic = c.isLetter() || c == ')' || c == ']' || c == '}'; i++ }
        }
    }
    return sb.toString().trim()
}

// 扫描一段 LaTeX，把其中每个 \ce{...} 替换为转换后的纯 LaTeX；无 \ce 原样返回，异常则退回原串。
private fun convertCeInLatex(latex: String): String {
    if (!latex.contains("\\ce")) return latex
    return try {
        val n = latex.length; val sb = StringBuilder(); var i = 0
        while (i < n) {
            if (latex.startsWith("\\ce{", i)) {
                val e = matchBrace(latex, i + 3)      // '{' 在 i+3
                sb.append(mhchemBodyToLatex(if (e > i + 3) latex.substring(i + 4, e) else ""))
                i = e + 1
            } else { sb.append(latex[i]); i++ }
        }
        sb.toString()
    } catch (_: Throwable) { latex }
}

// ============================================================
// 跨 item 存活的解析缓存（有界 LRU）
// ------------------------------------------------------------
// LazyColumn 会 dispose 滚出屏幕的气泡，MarkdownText 里的 remember(text) 只在**同屏存活期**命中；
// 快速滚动进出，每条气泡都要重跑整条 Markdown 管线（parseMarkdown / inlineAnnotated / highlightCode
// 都是纯函数、无副作用）——这是最普遍的滚动掉帧源。用模块级**有界 LRU** 跨 dispose 缓存结果：
//   · key 用**完整文本(+颜色令牌)**而非 hash——hash 碰撞会永久缓存到错内容，纯函数缓存必须零碰撞；
//   · 容量有界 → 内存可控（AnnotatedString/块表都不大，最坏几 MB）；纯函数结果 → 缓存永远正确；
//   · 主题深浅色切换会改颜色令牌 → 生成新 key、旧的被 LRU 淘汰，不会显示旧色。
// ⚠ 流式态每 tick 文本都变、必然 miss：parse 缓存由 [MarkdownText] 的 streaming 参数旁路，
//   免得一次长回复的上百个中间态把历史气泡的缓存挤光（行内/段落缓存的流式抖动仅限“正在增长的末段”，自愈）。
private fun <K, V> lruCache(max: Int): MutableMap<K, V> =
    java.util.Collections.synchronizedMap(object : LinkedHashMap<K, V>(max, 0.75f, /*accessOrder=*/true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > max
    })

private data class MdInlineKey(val s: String, val base: ULong, val link: ULong, val code: ULong)
private data class MdCodeKey(val code: String, val lang: String, val kw: ULong, val str: ULong, val num: ULong, val com: ULong)

private val mdBlocksCache = lruCache<String, List<MdBlock>>(96)
private val mdInlineCache = lruCache<MdInlineKey, AnnotatedString>(192)
private val mdParaCache = lruCache<MdInlineKey, Pair<AnnotatedString, List<String>>>(128)
private val mdCodeCache = lruCache<MdCodeKey, AnnotatedString>(40)

// ============================================================
// B 方案：段落 TextLayoutResult 缓存 + Canvas 绘制
//
// LazyColumn dispose 掉滚出屏的气泡后，Compose 的 Text 每次重进 composition 都**重新测量字形**——
// 这是渲染文本最贵的一步，而 LRU 只缓存了「解析」缓存不了「布局」。这里把测好的 TextLayoutResult 按
// (源文本+颜色+字号+可用宽度+密度+方向) 缓存跨 dispose；滚回来直接拿现成布局用 Canvas.drawText 画，
// 免掉重复测量。**仅用于无内联公式、无链接的纯段落**（含公式要占位、含链接要保留点击 → 退回 Text）。
// key 用**源字符串**而非 AnnotatedString：String.hashCode 有缓存、查表 O(1) 摊销；AnnotatedString 每次 O(n)。
// ============================================================
// colorsKey 合并 (基色+链接色+代码色)：AnnotatedString 里 span 的颜色都由这三者决定，只用基色当 key
// 会在「换主题只改了代码/链接色、基色没变」时返回旧布局（旧色）。带上 colorsKey 彻底杜绝。
private data class MdLayoutKey(
    val text: String, val colorsKey: Int, val fontSizeVal: Float,
    val maxWidth: Int, val density: Float, val fontScale: Float, val ltr: Boolean,
)
private val mdLayoutCache = lruCache<MdLayoutKey, TextLayoutResult>(80)

private fun measureParaCached(
    measurer: TextMeasurer, keyText: String, colorsKey: Int, annotated: AnnotatedString, style: TextStyle,
    maxWidth: Int, density: Float, fontScale: Float, layoutDir: LayoutDirection,
): TextLayoutResult {
    val key = MdLayoutKey(keyText, colorsKey, style.fontSize.value, maxWidth, density, fontScale, layoutDir == LayoutDirection.Ltr)
    return mdLayoutCache.getOrPut(key) {
        measurer.measure(text = annotated, style = style, constraints = Constraints(maxWidth = maxWidth), layoutDirection = layoutDir)
    }
}

// LayoutModifierNode + DrawModifierNode：measure 阶段拿到父给的真实 maxWidth（无额外 subcompose），
// 测量走缓存，draw 阶段 Canvas 画缓存布局。节点无子内容（挂在空 Spacer 上）。
private class CachedParaNode(
    var keyText: String, var colorsKey: Int, var annotated: AnnotatedString, var style: TextStyle, var measurer: TextMeasurer,
    var density: Float, var fontScale: Float, var layoutDir: LayoutDirection,
) : Modifier.Node(), LayoutModifierNode, DrawModifierNode {
    private var tlr: TextLayoutResult? = null
    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val maxW = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val r = measureParaCached(measurer, keyText, colorsKey, annotated, style, maxW, density, fontScale, layoutDir)
        tlr = r
        val w = r.size.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        val h = r.size.height.coerceIn(constraints.minHeight, constraints.maxHeight)
        val placeable = measurable.measure(Constraints.fixed(0, 0))
        return layout(w, h) { placeable.place(0, 0) }
    }
    override fun ContentDrawScope.draw() { tlr?.let { drawText(it) } }
}

private data class CachedParaElement(
    val keyText: String, val colorsKey: Int, val annotated: AnnotatedString, val style: TextStyle, val measurer: TextMeasurer,
    val density: Float, val fontScale: Float, val layoutDir: LayoutDirection,
) : ModifierNodeElement<CachedParaNode>() {
    override fun create() = CachedParaNode(keyText, colorsKey, annotated, style, measurer, density, fontScale, layoutDir)
    override fun update(node: CachedParaNode) {
        val changed = node.keyText != keyText || node.colorsKey != colorsKey || node.style != style || node.density != density ||
            node.fontScale != fontScale || node.layoutDir != layoutDir || node.measurer !== measurer || node.annotated != annotated
        node.keyText = keyText; node.colorsKey = colorsKey; node.annotated = annotated; node.style = style; node.measurer = measurer
        node.density = density; node.fontScale = fontScale; node.layoutDir = layoutDir
        if (changed) { node.invalidateMeasurement(); node.invalidateDraw() }
    }
}

private fun Modifier.cachedParagraph(
    keyText: String, colorsKey: Int, annotated: AnnotatedString, style: TextStyle, measurer: TextMeasurer,
    density: Float, fontScale: Float, layoutDir: LayoutDirection,
) = this then CachedParaElement(keyText, colorsKey, annotated, style, measurer, density, fontScale, layoutDir)

/** 缓存布局的纯段落（无公式无链接）。样式与 `Text(text, color, fontSize)` 对齐：合并 LocalTextStyle 再叠色/字号。 */
@Composable
private fun CachedParagraph(keyText: String, colorsKey: Int, annotated: AnnotatedString, color: Color, fontSize: TextUnit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val layoutDir = androidx.compose.ui.platform.LocalLayoutDirection.current
    val base = androidx.compose.material3.LocalTextStyle.current
    val style = remember(base, color, fontSize) { base.merge(TextStyle(color = color, fontSize = fontSize)) }
    androidx.compose.foundation.layout.Spacer(
        Modifier.cachedParagraph(keyText, colorsKey, annotated, style, measurer, density.density, density.fontScale, layoutDir)
    )
}

/** parseMarkdown 的缓存壳（传入的应是**已预处理**的文本）。 */
private fun parseMarkdownCached(pre: String): List<MdBlock> =
    mdBlocksCache.getOrPut(pre) { parseMarkdown(pre) }

/** 无公式的行内注解（标题/引用/列表/表格单元等）缓存壳。 */
private fun inlineAnnotatedCached(s: String, base: Color, linkColor: Color, codeColor: Color): AnnotatedString =
    mdInlineCache.getOrPut(MdInlineKey(s, base.value, linkColor.value, codeColor.value)) {
        inlineAnnotated(s, base, linkColor, codeColor)
    }

/** 段落（可能含行内公式）缓存壳：连同按序收集的公式串一起缓存，占位 id 由文本决定、可复用。 */
private fun paragraphAnnotatedCached(text: String, color: Color, linkColor: Color, codeColor: Color): Pair<AnnotatedString, List<String>> =
    mdParaCache.getOrPut(MdInlineKey(text, color.value, linkColor.value, codeColor.value)) {
        val maths = mutableListOf<String>()
        val ann = inlineAnnotated(text, color, linkColor, codeColor, onMath = { latex -> maths.add(latex); "math_${maths.size - 1}" })
        ann to maths.toList()
    }

/** 代码语法高亮缓存壳。 */
private fun highlightCodeCached(code: String, lang: String, kw: Color, str: Color, num: Color, com: Color): AnnotatedString =
    mdCodeCache.getOrPut(MdCodeKey(code, lang, kw.value, str.value, num.value, com.value)) {
        highlightCode(code, lang, kw = kw, str = str, num = num, com = com)
    }

/** 只查缓存、不计算：命中就同步返回（不闪），未命中返回 null → 交给后台异步算（见 CodeBlock）。 */
private fun highlightCodePeek(code: String, lang: String, kw: Color, str: Color, num: Color, com: Color): AnnotatedString? =
    mdCodeCache[MdCodeKey(code, lang, kw.value, str.value, num.value, com.value)]

// 超此长度不高亮（直接纯文本）：大代码块的高亮首次计算无论主/后台都是尖峰，且手表屏上几千字高亮意义不大。仿 RikkaHub 的 4096 上限。
private const val CODE_HIGHLIGHT_MAX = 6000

// 解析前预处理：剥离泄漏的工具调用文本(#1) + 解码 HTML 实体(#6)。streaming=true：流式中途本就拿到半截未闭合标记，
// 显示时该整段藏掉（否则满屏乱码）；落盘那两处用默认 false（保守，绝不吞真答案）。
private fun preprocessMd(text: String): String = decodeHtmlEntities(stripLeakedToolCalls(text, streaming = true))

/**
 * 流式态的 blocks：把 AST 解析挪到**后台线程**，`collectLatest` 让新 token 一到就取消在算的旧帧，
 * 主线程只拿算好的结果渲染——治长回复流式时「每节流 tick 在主线程整段重解析」的掉帧
 * （仿 RikkaHub 的 mapLatest+Dispatchers.Default / Operit 的后台节点缓存，两家都这么做）。
 * 首帧同步解析一次，避免流式气泡刚出现时的空闪；此后每次文本变化都走后台。
 * 流式文本每 tick 都不同，故用无缓存的 parseMarkdown（不入 LRU，免污染历史气泡缓存）。
 */
@Composable
private fun rememberStreamingBlocks(text: String): List<MdBlock> {
    val latest = rememberUpdatedState(text)
    val state = remember { mutableStateOf(parseMarkdown(preprocessMd(text))) }
    LaunchedEffect(Unit) {
        snapshotFlow { latest.value }
            .distinctUntilChanged()
            .collectLatest { t ->
                val parsed = withContext(Dispatchers.Default) { parseMarkdown(preprocessMd(t)) }
                state.value = parsed
            }
    }
    return state.value
}

private fun parseMarkdown(md: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = md.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()
    val footnotes = mutableListOf<Pair<String, String>>()
    fun flushPara() {
        if (para.isNotBlank()) blocks.add(MdBlock.Paragraph(para.toString().trim()))
        para.setLength(0)
    }
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flushPara()
                val lang = trimmed.removePrefix("```").trim()
                val sb = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) { sb.append(lines[i]).append("\n"); i++ }
                blocks.add(MdBlock.Code(lang, sb.toString().trimEnd('\n')))
            }
            // #3 隐藏 <script>/<style> 整块（含 CSS/JS），不泄漏到正文
            trimmed.startsWith("<script", ignoreCase = true) || trimmed.startsWith("<style", ignoreCase = true) -> {
                flushPara()
                val closeTag = if (trimmed.startsWith("<script", ignoreCase = true)) "</script" else "</style"
                while (!lines[i].contains(closeTag, ignoreCase = true) && i + 1 < lines.size) i++
            }
            // 内联 <svg>…</svg>：整块收集交 WebView 渲染
            trimmed.startsWith("<svg", ignoreCase = true) -> {
                flushPara()
                val buf = StringBuilder(line).append("\n")
                while (!buf.contains("</svg", ignoreCase = true) && i + 1 < lines.size) { i++; buf.append(lines[i]).append("\n") }
                blocks.add(MdBlock.RawSvg(buf.toString().trim()))
            }
            // <iframe src="…">：取 src 交 WebView 加载（嵌入网页/视频）
            trimmed.startsWith("<iframe", ignoreCase = true) -> {
                flushPara()
                val buf = StringBuilder(line).append("\n")
                while (!buf.contains("</iframe", ignoreCase = true) && !buf.contains("/>") && i + 1 < lines.size) { i++; buf.append(lines[i]).append("\n") }
                val src = htmlSrcAttr.find(buf.toString())?.groupValues?.get(1)?.trim()
                if (!src.isNullOrBlank()) blocks.add(MdBlock.Iframe(src))
            }
            // <img src="…">（含 data: base64）：当作图片块，走图片管线（Coil→WebView 兜底）
            trimmed.startsWith("<img", ignoreCase = true) -> {
                flushPara()
                val src = htmlSrcAttr.find(trimmed)?.groupValues?.get(1)?.trim()
                val alt = htmlAltAttr.find(trimmed)?.groupValues?.get(1)?.trim() ?: ""
                if (!src.isNullOrBlank()) blocks.add(MdBlock.Image(src, alt))
            }
            // <audio>/<video>（src 可能在标签上或内嵌 <source src>）：WebView 原生播放器
            trimmed.startsWith("<audio", ignoreCase = true) || trimmed.startsWith("<video", ignoreCase = true) -> {
                flushPara()
                val isVideo = trimmed.startsWith("<video", ignoreCase = true)
                val close = if (isVideo) "</video" else "</audio"
                val buf = StringBuilder(line).append("\n")
                while (!buf.contains(close, ignoreCase = true) && !buf.contains("/>") && i + 1 < lines.size) { i++; buf.append(lines[i]).append("\n") }
                val src = htmlSrcAttr.find(buf.toString())?.groupValues?.get(1)?.trim()
                if (!src.isNullOrBlank()) blocks.add(MdBlock.Media(src, isVideo))
            }
            // #3 <details>/<summary> 折叠块：summary 作可点击标题，其余内容折叠（内部再走 Markdown）
            trimmed.startsWith("<details", ignoreCase = true) -> {
                flushPara()
                val buf = StringBuilder(line).append("\n")
                while (!buf.contains("</details", ignoreCase = true) && i + 1 < lines.size) { i++; buf.append(lines[i]).append("\n") }
                val raw = buf.toString()
                val summary = Regex("<summary[^>]*>([\\s\\S]*?)</summary>", RegexOption.IGNORE_CASE).find(raw)?.groupValues?.get(1)?.trim()?.ifBlank { "详情" } ?: "详情"
                val inner = raw
                    .replace(Regex("</?details[^>]*>", RegexOption.IGNORE_CASE), "")
                    .replace(Regex("<summary[^>]*>[\\s\\S]*?</summary>", RegexOption.IGNORE_CASE), "")
                    .trim()
                blocks.add(MdBlock.Details(summary, inner))
            }
            trimmed.startsWith("$$") -> {
                flushPara()
                val first = trimmed.removePrefix("$$")
                if (first.endsWith("$$") && first.length >= 2) {
                    blocks.add(MdBlock.Math(first.removeSuffix("$$").trim()))
                } else {
                    val sb = StringBuilder()
                    if (first.isNotBlank()) sb.append(first).append("\n")
                    i++
                    while (i < lines.size && !lines[i].contains("$$")) { sb.append(lines[i]).append("\n"); i++ }
                    if (i < lines.size) { val last = lines[i].substringBefore("$$"); if (last.isNotBlank()) sb.append(last) }
                    blocks.add(MdBlock.Math(sb.toString().trim()))
                }
            }
            // 独立化学式行 \ce{...}（整行仅一个 \ce{}）→ 块级渲染（可横向滚动）；混排则交给段落走行内
            trimmed.startsWith("\\ce{") && matchBrace(trimmed, 3).let { it in 4 until trimmed.length && trimmed.substring(it + 1).isBlank() } -> {
                flushPara(); blocks.add(MdBlock.Math(trimmed))
            }
            headingLine.containsMatchIn(trimmed) -> {
                flushPara()
                val level = trimmed.takeWhile { it == '#' }.length
                blocks.add(MdBlock.Heading(level, trimmed.drop(level).trim()))
            }
            footnoteDef.matches(trimmed) -> {
                flushPara(); val m = footnoteDef.find(trimmed)!!
                footnotes.add(m.groupValues[1] to m.groupValues[2].trim())
            }
            // 引用（支持多级 >>）：数前导 > 的层级
            trimmed.startsWith(">") -> {
                flushPara()
                var lvl = 0; var rest = trimmed
                while (rest.startsWith(">")) { lvl++; rest = rest.removePrefix(">").trimStart() }
                blocks.add(MdBlock.Quote(rest, lvl.coerceAtMost(5)))
            }
            imageLine.matches(trimmed) -> {
                flushPara(); val m = imageLine.find(trimmed)!!
                blocks.add(MdBlock.Image(m.groupValues[2].trim(), m.groupValues[1]))
            }
            // #8 定义列表：术语行 + 紧随其后的一或多行 ": 释义"
            !trimmed.isBlank() && !trimmed.startsWith(": ") && i + 1 < lines.size && lines[i + 1].trimStart().startsWith(": ") -> {
                flushPara()
                val items = mutableListOf<Pair<String, List<String>>>()
                while (i < lines.size) {
                    val term = lines[i].trim()
                    if (term.isBlank() || term.startsWith(": ")) break
                    if (i + 1 >= lines.size || !lines[i + 1].trimStart().startsWith(": ")) break
                    i++
                    val defs = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(": ")) { defs.add(lines[i].trimStart().removePrefix(": ").trim()); i++ }
                    items.add(term to defs.toList())
                }
                blocks.add(MdBlock.DefList(items))
                continue
            }
            trimmed.contains("|") && i + 1 < lines.size && tableSep.matches(lines[i + 1].trim()) -> {
                flushPara()
                val header = splitRow(trimmed)
                i += 2 // 跳过表头与分隔行
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().contains("|")) { rows.add(splitRow(lines[i])); i++ }
                blocks.add(MdBlock.Table(header, rows))
                continue
            }
            ruleLine.matches(trimmed) -> { flushPara(); blocks.add(MdBlock.Rule) }
            bulletLine.containsMatchIn(trimmed) -> {
                flushPara()
                val body = trimmed.drop(2).trim()
                val task = taskItem.find(body)  // 任务列表 - [ ] / - [x]
                if (task != null) blocks.add(MdBlock.Bullet(task.groupValues[2].trim(), false, "•", checked = task.groupValues[1].trim().equals("x", ignoreCase = true)))
                else blocks.add(MdBlock.Bullet(body, false, "•"))
            }
            orderedLine.containsMatchIn(trimmed) -> {
                flushPara()
                val marker = trimmed.takeWhile { it != ' ' }
                blocks.add(MdBlock.Bullet(trimmed.substringAfter(" ").trim(), true, marker))
            }
            trimmed.isBlank() -> flushPara()
            else -> { if (para.isNotEmpty()) para.append("\n"); para.append(line) }
        }
        i++
    }
    flushPara()
    if (footnotes.isNotEmpty()) blocks.add(MdBlock.Footnotes(footnotes))
    return blocks
}

// 行内注解的记忆化壳：流式时整段每 150ms 重组一次，若逐块都重建 AnnotatedString 就是 O(块数)/帧；
// MdBlock 是 data class，未变的块跨解析 equals 相等 → remember(text/颜色) 命中缓存，只有增长中的末块重建（O(1) 摊销）。
// 只在 @Composable 渲染处用它；纯逻辑处仍直接调 inlineAnnotated。
@Composable
private fun rememberInline(s: String, base: Color, linkColor: Color, codeColor: Color): AnnotatedString =
    remember(s, base, linkColor, codeColor) { inlineAnnotatedCached(s, base, linkColor, codeColor) }

// 行内：**粗** *斜* `码` [文本](url)  裸链接  行内数学 $...$（经 onMath 注册为 InlineTextContent）
// onMath!=null 时把 $...$ 交给回调登记并插入内联占位；为 null 时 $ 原样输出（老行为）。
private fun inlineAnnotated(s: String, base: Color, linkColor: Color, codeColor: Color, onMath: ((String) -> String)? = null): AnnotatedString = buildAnnotatedString {
    var i = 0
    val hlBg = linkColor.copy(alpha = 0.20f)  // #3 高亮底色，从主题色派生（不写死设计色）
    fun findClose(marker: String, from: Int): Int {
        var k = from
        while (k <= s.length - marker.length) { if (s.startsWith(marker, k)) return k; k++ }
        return -1
    }
    while (i < s.length) {
        when {
            // #4 行内数学 $...$：非 $$，两端不贴空格（排除 "$5 and $10" 货币误判），不跨行
            s[i] == '$' && onMath != null && !s.startsWith("$$", i) -> {
                val e = findClose("$", i + 1)
                val latex = if (e > i + 1) s.substring(i + 1, e) else ""
                if (e > i + 1 && s[i + 1] != ' ' && s[e - 1] != ' ' && !latex.contains('\n') && !latex.contains('$')) {
                    appendInlineContent(onMath(latex), "[]")
                    i = e + 1
                } else { append(s[i]); i++ }
            }
            // 行内化学式 \ce{...}：登记为内联公式占位（与 $...$ 同一渲染路径），由 convertCeInLatex 转换
            s.startsWith("\\ce{", i) && onMath != null -> {
                val e = matchBrace(s, i + 3)
                if (e > i + 3 && s[e] == '}') { appendInlineContent(onMath(s.substring(i, e + 1)), "[]"); i = e + 1 }
                else { append(s[i]); i++ }
            }
            s.startsWith("**", i) -> {
                val e = findClose("**", i + 2)
                if (e >= 0) { withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(inlineAnnotated(s.substring(i + 2, e), base, linkColor, codeColor, onMath)) }; i = e + 2 }
                else { append("**"); i += 2 }
            }
            // #5 删除线 ~~文本~~
            s.startsWith("~~", i) -> {
                val e = findClose("~~", i + 2)
                if (e >= 0) { withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { append(inlineAnnotated(s.substring(i + 2, e), base, linkColor, codeColor, onMath)) }; i = e + 2 }
                else { append("~~"); i += 2 }
            }
            // #3 高亮 ==文本==
            s.startsWith("==", i) -> {
                val e = findClose("==", i + 2)
                if (e >= 0) { withStyle(SpanStyle(background = hlBg)) { append(inlineAnnotated(s.substring(i + 2, e), base, linkColor, codeColor, onMath)) }; i = e + 2 }
                else { append("=="); i += 2 }
            }
            (s[i] == '*' || s[i] == '_') && i + 1 < s.length && s[i + 1] != ' ' -> {
                val m = s[i].toString(); val e = findClose(m, i + 1)
                if (e >= 0) { withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(s.substring(i + 1, e)) }; i = e + 1 }
                else { append(s[i]); i++ }
            }
            s[i] == '`' -> {
                val e = findClose("`", i + 1)
                if (e >= 0) { withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)) { append(s.substring(i + 1, e)) }; i = e + 1 }
                else { append('`'); i++ }
            }
            // #6 行内脚注引用 [^label] → 上标小字（定义在消息末尾统一列出）
            s.startsWith("[^", i) -> {
                val close = s.indexOf(']', i + 2)
                val label = if (close > 0) s.substring(i + 2, close) else ""
                if (close > 0 && label.isNotBlank() && !label.contains('[')) {
                    withStyle(SpanStyle(baselineShift = BaselineShift.Superscript, color = linkColor)) { append(label) }
                    i = close + 1
                } else { append(s[i]); i++ }
            }
            s[i] == '[' -> {
                val close = s.indexOf(']', i)
                if (close > 0 && close + 1 < s.length && s[close + 1] == '(') {
                    val urlEnd = s.indexOf(')', close + 2)
                    if (urlEnd > 0) {
                        val label = s.substring(i + 1, close); val url = s.substring(close + 2, urlEnd)
                        withLink(LinkAnnotation.Url(url)) {
                            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(label) }
                        }
                        i = urlEnd + 1
                    } else { append(s[i]); i++ }
                } else { append(s[i]); i++ }
            }
            s.startsWith("http://", i) || s.startsWith("https://", i) -> {
                val end = s.substring(i).indexOfFirst { it == ' ' || it == '\n' }.let { if (it < 0) s.length else i + it }
                val url = s.substring(i, end)
                withLink(LinkAnnotation.Url(url)) {
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(url) }
                }
                i = end
            }
            // #7 行内 HTML 标签：常见标签渲染成对应样式，未知标签仅去掉标签本身保留文字，
            // 不像标签的 < 原样输出（不吞用户文本，如 “a < b”）
            s[i] == '<' -> {
                val gt = s.indexOf('>', i)
                val raw = if (gt > i) s.substring(i + 1, gt).trim() else ""
                if (gt < 0 || !htmlTagLike.matches(raw)) { append(s[i]); i++ }
                else {
                    val closing = raw.startsWith("/")
                    val selfClose = raw.endsWith("/")
                    val name = raw.removePrefix("/").takeWhile { !it.isWhitespace() && it != '/' }.lowercase()
                    val inlineStyle = when (name) {
                        "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
                        "i", "em" -> SpanStyle(fontStyle = FontStyle.Italic)
                        "u", "ins" -> SpanStyle(textDecoration = TextDecoration.Underline)
                        "code", "kbd", "tt" -> SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor)
                        "del", "s", "strike" -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                        // #3 高亮：默认主题高亮底；允许 style 里覆盖背景/文字色
                        "mark" -> {
                            val bg = cssBgAttr.find(raw)?.groupValues?.get(1)?.let { parseCssColor(it) } ?: hlBg
                            val fg = cssColorAttr.find(raw)?.groupValues?.get(1)?.let { parseCssColor(it) }
                            SpanStyle(background = bg, color = fg ?: Color.Unspecified)
                        }
                        // #9 任意颜色 + 背景色（<span style="color:…;background:…">，即“渐变胶囊/荧光背景”）
                        "font", "span" -> {
                            val fg = cssColorAttr.find(raw)?.groupValues?.get(1)?.let { parseCssColor(it) }
                            val bg = cssBgAttr.find(raw)?.groupValues?.get(1)?.let { parseCssColor(it) }
                            if (fg == null && bg == null) null
                            else SpanStyle(color = fg ?: Color.Unspecified, background = bg ?: Color.Unspecified)
                        }
                        else -> null
                    }
                    when {
                        name == "br" -> { append("\n"); i = gt + 1 }
                        inlineStyle != null && !closing -> {
                            val closeTag = "</$name>"
                            val ce = s.indexOf(closeTag, gt + 1, ignoreCase = true)
                            if (ce < 0) { append(s[i]); i++ }  // 无闭合 → 原样输出 <tag>，不误吞
                            else {
                                withStyle(inlineStyle) { append(inlineAnnotated(s.substring(gt + 1, ce), base, linkColor, codeColor)) }
                                i = ce + closeTag.length
                            }
                        }
                        name == "a" && !closing -> {
                            val href = hrefRegex.find(raw)?.groupValues?.get(1) ?: ""
                            val ce = s.indexOf("</a>", gt + 1, ignoreCase = true)
                            if (ce < 0) { append(s[i]); i++ }
                            else {
                                val inner = s.substring(gt + 1, ce)
                                if (href.isNotBlank()) withLink(LinkAnnotation.Url(href)) { withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) { append(inlineAnnotated(inner, base, linkColor, codeColor)) } }
                                else append(inlineAnnotated(inner, base, linkColor, codeColor))
                                i = ce + 4
                            }
                        }
                        // 未知标签 / 落单闭合标签 / 自闭合标签：去掉标签本身，保留内部文字
                        else -> { i = gt + 1 }
                    }
                }
            }
            else -> { append(s[i]); i++ }
        }
    }
}

@Composable
fun MarkdownText(text: String, color: Color, fontSize: TextUnit = 13.sp, streaming: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val linkColor = scheme.primary
    val codeColor = scheme.tertiary
    val muted = scheme.onSurfaceVariant
    val gridColor = scheme.outline
    // 静态气泡：走跨-dispose 的 LRU（滚出再滚入不重解析）。
    // 流式气泡：解析挪后台线程 + collectLatest 取消过期帧，主线程不阻塞（见 rememberStreamingBlocks）。
    val blocks = if (streaming) rememberStreamingBlocks(text)
                 else remember(text) { parseMarkdownCached(preprocessMd(text)) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (b in blocks) {
            when (b) {
                is MdBlock.Code -> when (b.lang.trim().lowercase()) {
                    "svg" -> RenderableBlock("svg", b.content) { SvgView(b.content) }
                    "html", "htm" -> RenderableBlock("html", b.content) { HtmlView(b.content) }
                    "mermaid" -> MermaidView(b.content)
                    else -> CodeBlock(b.lang, b.content)
                }
                is MdBlock.Heading -> Text(
                    rememberInline(b.text, color, linkColor, codeColor), color = color,
                    fontSize = (fontSize.value + (7 - b.level.coerceIn(1, 6)) * 1.6f).sp,
                    fontWeight = FontWeight.Bold
                )
                // 引用：按层级左缩进并叠加竖条（支持多级 >>）
                is MdBlock.Quote -> Row {
                    Spacer(Modifier.width(((b.level - 1) * 10).dp))
                    repeat(b.level) { Surface(color = gridColor, modifier = Modifier.width(3.dp)) { Text(" ", fontSize = fontSize) }; Spacer(Modifier.width(3.dp)) }
                    Spacer(Modifier.width(3.dp))
                    Text(rememberInline(b.text, muted, linkColor, codeColor), color = muted, fontSize = fontSize)
                }
                is MdBlock.Bullet -> Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    // 任务列表用复选框方块+勾；普通列表用圆点/序号
                    if (b.checked != null) {
                        Text(if (b.checked) "☑" else "☐", color = if (b.checked) linkColor else muted, fontSize = fontSize)
                        Spacer(Modifier.width(4.dp))
                    } else {
                        Text("${b.marker.let { if (b.ordered) it else "•" }} ", color = color, fontSize = fontSize, fontWeight = FontWeight.Bold)
                    }
                    val strike = b.checked == true
                    Text(rememberInline(b.text, if (strike) muted else color, linkColor, codeColor), color = if (strike) muted else color, fontSize = fontSize, textDecoration = if (strike) TextDecoration.LineThrough else null)
                }
                is MdBlock.Rule -> HorizontalDivider(color = gridColor)
                is MdBlock.Image -> MarkdownImage(b.url, b.alt, muted, gridColor)
                // 表格：每个单元格带可见边框，形成网格（修复“看不到横线”）
                is MdBlock.Table -> Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Column(Modifier.border(1.dp, gridColor, RoundedCornerShape(6.dp))) {
                        Row {
                            b.header.forEach { cell ->
                                Box(Modifier.border(0.5.dp, gridColor).width(110.dp).padding(horizontal = 6.dp, vertical = 4.dp)) {
                                    Text(rememberInline(cell, color, linkColor, codeColor), color = color, fontSize = (fontSize.value - 1).sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        b.rows.forEach { row ->
                            Row {
                                for (ci in b.header.indices) {
                                    Box(Modifier.border(0.5.dp, gridColor).width(110.dp).padding(horizontal = 6.dp, vertical = 4.dp)) {
                                        Text(rememberInline(row.getOrElse(ci) { "" }, muted, linkColor, codeColor), color = muted, fontSize = (fontSize.value - 1).sp)
                                    }
                                }
                            }
                        }
                    }
                }
                // 关掉公式渲染时退回等宽源码：块级公式在正文里本来就是独立一块，
                // 直接不画会让「$$…$$ 那段话」凭空消失，用户只会以为模型没答完。
                is MdBlock.Math ->
                    if (LocalChatDisplay.current.renderLatex) LatexView(b.latex, color, fontSize.value + 2f)
                    else Text(b.latex, color = muted, fontSize = (fontSize.value - 1).sp, fontFamily = FontFamily.Monospace)
                // #6 脚注定义区：分割线 + 逐条「label. 说明」，小字弱色
                is MdBlock.Footnotes -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    HorizontalDivider(color = gridColor)
                    b.items.forEach { (label, txt) ->
                        Row {
                            Text("$label. ", color = muted, fontSize = (fontSize.value - 2).sp, fontWeight = FontWeight.Bold)
                            Text(rememberInline(txt, muted, linkColor, codeColor), color = muted, fontSize = (fontSize.value - 2).sp)
                        }
                    }
                }
                // #8 定义列表：术语加粗，释义缩进弱色
                is MdBlock.DefList -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    b.items.forEach { (term, defs) ->
                        Text(rememberInline(term, color, linkColor, codeColor), color = color, fontSize = fontSize, fontWeight = FontWeight.Bold)
                        defs.forEach { d ->
                            Row {
                                Spacer(Modifier.width(12.dp))
                                Text(rememberInline(d, muted, linkColor, codeColor), color = muted, fontSize = fontSize)
                            }
                        }
                    }
                }
                // #3 折叠块：点击 summary 展开/收起，带高度动画；内容递归走 Markdown
                is MdBlock.Details -> {
                    var expanded by remember(b) { mutableStateOf(false) }
                    // 不加 animateContentSize：展开大块内容时逐帧重排会卡，改为一次性布局（#4 展开会卡）
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).border(1.dp, gridColor, RoundedCornerShape(8.dp))) {
                        Row(
                            Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        ) {
                            Text(if (expanded) "▾ " else "▸ ", color = linkColor, fontSize = fontSize)
                            Text(rememberInline(b.summary, color, linkColor, codeColor), color = color, fontSize = fontSize, fontWeight = FontWeight.Bold)
                        }
                        // 展开用 AnimatedVisibility（内容一次测量、按 clip 揭示）：有动画且不逐帧重排（#4 不卡）
                        AnimatedVisibility(
                            visible = expanded && b.content.isNotBlank(),
                            enter = expandVertically(animationSpec = tween(200, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + fadeIn(tween(200)),
                            exit = shrinkVertically(animationSpec = tween(180, easing = androidx.compose.animation.core.FastOutSlowInEasing)) + fadeOut(tween(120)),
                        ) {
                            Column(Modifier.padding(start = 8.dp, end = 8.dp, bottom = 6.dp)) {
                                MarkdownText(b.content, color, fontSize)
                            }
                        }
                    }
                }
                is MdBlock.RawSvg -> RenderableBlock("svg", b.content) { SvgView(b.content) }
                is MdBlock.Iframe -> IframeView(b.url, gridColor)
                is MdBlock.Media -> MediaView(b.url, b.video, gridColor)
                is MdBlock.Paragraph -> MarkdownParagraph(b.text, color, fontSize, linkColor, codeColor)
            }
        }
    }
}

// #4 图片：带加载中/失败态，避免加载失败时“留一大块空白”。失败显示 URL 便于排查，
// 点击失败态用系统浏览器打开原图。cleartext(http) 已在 manifest 放开。
@Composable
private fun MarkdownImage(url: String, alt: String, muted: Color, gridColor: Color) {
    var showViewer by remember { mutableStateOf(false) }
    // Coil 解不了的格式（典型：SVG / data: base64）→ 回退 WebView 渲染。
    var failed by remember(url) { mutableStateOf(false) }
    val useWeb = remember(url) {
        val bare = url.substringBefore('?').substringBefore('#')
        // SVG / data:base64 / APNG（Coil 不解 APNG 动画，交 WebView 才会动）→ WebView
        bare.endsWith(".svg", ignoreCase = true) || bare.endsWith(".apng", ignoreCase = true) || url.startsWith("data:", ignoreCase = true)
    }
    if (showViewer) ImageViewerDialog(url) { showViewer = false }

    if (useWeb || failed) { ImageWebFallback(url, gridColor); return }

    val imgCtx = LocalContext.current
    // 记忆化 ImageRequest：url 不变就别每次重组都新建 request + UA 字符串（省分配）。
    val imgReq = remember(url, imgCtx) {
        coil.request.ImageRequest.Builder(imgCtx).data(url).crossfade(true)
            .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Mobile Safari/537.36")
            .build()
    }
    coil.compose.SubcomposeAsyncImage(
        // 带浏览器 UA：不少图床对无 UA 的请求返回 403（表现为“无法加载”）
        model = imgReq,
        contentDescription = alt,
        modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp).clip(RoundedCornerShape(10.dp)).clickable { showViewer = true },
        contentScale = androidx.compose.ui.layout.ContentScale.Fit,
        loading = {
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = muted)
                Spacer(Modifier.width(8.dp)); Text(tr("图片加载中…"), color = muted, fontSize = 11.sp)
            }
        },
        // Coil 失败 → 切 WebView 兜底（覆盖 SVG 及其它浏览器可渲染格式）
        error = { androidx.compose.runtime.LaunchedEffect(url) { failed = true } },
    )
}

// 图片兜底：用 WebView 直接渲染 URL（SVG/PNG/GIF… 凡浏览器可显示的都行）。离线不联外，仅取该图。
@Composable
private fun ImageWebFallback(url: String, gridColor: Color) {
    Box(Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 260.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, gridColor, RoundedCornerShape(10.dp))) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { c ->
                android.webkit.WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                    @android.annotation.SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = false
                    settings.loadWithOverviewMode = true; settings.useWideViewPort = true
                }
            },
            update = { wv ->
                // 只在 url 变了才重新 load：否则每次重组都 loadDataWithBaseURL → 重解析+闪一下（滚动/流式时尤其）。
                if (wv.tag != url) {
                    wv.tag = url
                    val html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                        "<style>html,body{margin:0;padding:0;background:transparent}img,svg{max-width:100%;height:auto;display:block;margin:0 auto}</style></head>" +
                        "<body><img src=\"$url\"></body></html>"
                    wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// 段落渲染：支持行内数学 $...$。把每个公式登记为 InlineTextContent——用 JLaTeXMath 预建
// drawable 量出其内在宽高，换算成相对字号的 em 尺寸作占位，子组件再把同一 drawable 铺满占位，
// 从而让公式随文字排版/换行且尺寸对齐（不破坏 MarkdownText 其余块的普通渲染）。
@Composable
private fun MarkdownParagraph(text: String, color: Color, fontSize: TextUnit, linkColor: Color, codeColor: Color) {
    val fontPx = with(LocalDensity.current) { fontSize.toPx() }
    val colorInt = color.toArgb()
    // 关掉公式渲染时改走**不认 $...$** 的那条老路径（onMath=null → `$` 原样输出），
    // 而不是「照样解析再把公式画成文字」：后者会把 `$5 和 $10` 这类货币误判留在原地，
    // 关了公式还被公式语法咬一口，那这个开关就白给了。
    val renderLatex = LocalChatDisplay.current.renderLatex
    // 构建带占位的 annotated + 收集公式；颜色/字号变化时重建
    val (annotated, maths) = remember(text, color, linkColor, codeColor, renderLatex) {
        if (renderLatex) paragraphAnnotatedCached(text, color, linkColor, codeColor)
        else inlineAnnotatedCached(text, color, linkColor, codeColor) to emptyList<String>()
    }
    if (maths.isEmpty()) {
        // 含链接的段落仍走 Text（保留 LinkAnnotation 点击）；纯文本段落走缓存布局 Canvas（免重复测字形）。
        val hasLinks = remember(annotated) { annotated.getLinkAnnotations(0, annotated.length).isNotEmpty() }
        if (hasLinks) Text(annotated, color = color, fontSize = fontSize)
        else {
            val colorsKey = remember(color, linkColor, codeColor) { java.util.Objects.hash(color, linkColor, codeColor) }
            CachedParagraph(text, colorsKey, annotated, color, fontSize)
        }
        return
    }
    val inlineContent = remember(annotated, fontPx, colorInt) {
        maths.mapIndexed { idx, latex ->
            // 先把 \ce{...} 化学式转成纯 LaTeX（无 \ce 则原样），再交 JLaTeXMath
            val prepared = convertCeInLatex(latex)
            val drawable = try {
                ru.noties.jlatexmath.JLatexMathDrawable.builder(prepared).textSize(fontPx * 1.05f).color(colorInt).build()
            } catch (_: Throwable) { null }
            val wEm = if (drawable != null && fontPx > 0) (drawable.intrinsicWidth / fontPx).coerceIn(0.5f, 30f) else (latex.length * 0.55f).coerceAtLeast(0.5f)
            val hEm = if (drawable != null && fontPx > 0) (drawable.intrinsicHeight / fontPx).coerceIn(0.8f, 6f) else 1.3f
            "math_$idx" to InlineTextContent(
                Placeholder(width = wEm.em, height = hEm.em, placeholderVerticalAlign = PlaceholderVerticalAlign.Center)
            ) {
                if (drawable != null) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { c -> android.widget.ImageView(c) },
                        update = { it.setImageDrawable(drawable); it.adjustViewBounds = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    // 渲染失败 → 退回普通文本（原始源码），不崩不空白
                    Text(latex, color = color, fontSize = fontSize, maxLines = 1)
                }
            }
        }.toMap()
    }
    Text(annotated, color = color, fontSize = fontSize, inlineContent = inlineContent)
}

// 块级数学公式（$$...$$）用 JLaTeXMath 渲染成图像；失败则不显示（不崩溃）。
@Composable
private fun LatexView(latex: String, color: Color, sizeSp: Float) {
    val ctx = LocalContext.current
    val colorInt = color.toArgb()
    // 先转换 \ce{...} 化学式（无 \ce 原样返回），再交 JLaTeXMath；失败则退回普通文本，不崩不空白
    val drawable = remember(latex, colorInt, sizeSp) {
        try {
            ru.noties.jlatexmath.JLatexMathDrawable.builder(convertCeInLatex(latex))
                .textSize(sizeSp * ctx.resources.displayMetrics.scaledDensity)
                .color(colorInt)
                .build()
        } catch (_: Throwable) { null }
    }
    if (drawable == null) {
        Text(latex, color = color, fontSize = (sizeSp - 2f).coerceAtLeast(11f).sp, fontFamily = FontFamily.Monospace)
        return
    }
    androidx.compose.ui.viewinterop.AndroidView(
        factory = { c -> android.widget.ImageView(c) },
        update = { iv -> iv.setImageDrawable(drawable) },
        modifier = Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
    )
}

// ============================================================
// 代码块：语法高亮 + 折叠/预览 + 复制/保存（均自写，无第三方高亮库）
// ============================================================

// 常见语言关键字并集：无语言标签时的回退（跨语言统一分色，不追求逐语言语义精确）
private val codeKeywords = setOf(
    "abstract", "and", "as", "assert", "async", "await", "base", "bool", "boolean", "break", "by",
    "byte", "case", "catch", "char", "class", "const", "constructor", "continue", "data", "def",
    "default", "defer", "del", "delete", "do", "double", "elif", "else", "end", "enum", "export",
    "extends", "extern", "false", "final", "finally", "float", "fn", "for", "foreach", "from", "fun",
    "func", "function", "get", "global", "go", "goto", "if", "impl", "implements", "import", "in",
    "include", "init", "inline", "instanceof", "int", "interface", "internal", "is", "lambda", "let",
    "local", "long", "match", "mod", "module", "mut", "namespace", "native", "new", "nil", "none",
    "not", "null", "object", "of", "open", "operator", "or", "out", "override", "package", "params",
    "pass", "print", "private", "protected", "public", "raise", "readonly", "record", "ref",
    "register", "return", "sealed", "select", "self", "set", "short", "signed", "sizeof", "static",
    "str", "struct", "super", "suspend", "switch", "then", "this", "throw", "throws", "trait",
    "true", "try", "type", "typedef", "typeof", "union", "unsigned", "use", "using", "val", "var",
    "virtual", "void", "volatile", "when", "where", "while", "with", "yield",
)

// 按围栏语言标签选该语言的关键字集，使高亮更贴近该语言语义；未列出的语言/无标签回退上面的并集。
private val kwKotlin = setOf(
    "abstract", "actual", "annotation", "as", "break", "by", "catch", "class", "companion", "const",
    "constructor", "continue", "crossinline", "data", "delegate", "do", "dynamic", "else", "enum",
    "expect", "external", "false", "final", "finally", "for", "fun", "get", "if", "import", "in",
    "infix", "init", "inline", "inner", "interface", "internal", "is", "lateinit", "noinline", "null",
    "object", "open", "operator", "out", "override", "package", "private", "protected", "public",
    "reified", "return", "sealed", "set", "super", "suspend", "tailrec", "this", "throw", "true",
    "try", "typealias", "typeof", "val", "value", "var", "vararg", "when", "where", "while",
)
private val kwJava = setOf(
    "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
    "continue", "default", "do", "double", "else", "enum", "extends", "false", "final", "finally",
    "float", "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long",
    "native", "new", "null", "package", "private", "protected", "public", "record", "return", "sealed",
    "short", "static", "strictfp", "super", "switch", "synchronized", "this", "throw", "throws",
    "transient", "true", "try", "var", "void", "volatile", "while", "yield",
)
private val kwJs = setOf(
    "abstract", "any", "as", "async", "await", "boolean", "break", "case", "catch", "class", "const",
    "continue", "debugger", "declare", "default", "delete", "do", "else", "enum", "export", "extends",
    "false", "finally", "for", "from", "function", "get", "if", "implements", "import", "in",
    "instanceof", "interface", "is", "keyof", "let", "namespace", "never", "new", "null", "number",
    "object", "of", "package", "private", "protected", "public", "readonly", "return", "set", "static",
    "string", "super", "switch", "this", "throw", "true", "try", "type", "typeof", "undefined",
    "unknown", "var", "void", "while", "with", "yield",
)
private val kwPython = setOf(
    "and", "as", "assert", "async", "await", "break", "case", "class", "continue", "def", "del",
    "elif", "else", "except", "False", "finally", "for", "from", "global", "if", "import", "in", "is",
    "lambda", "match", "None", "nonlocal", "not", "or", "pass", "print", "raise", "return", "self",
    "True", "try", "while", "with", "yield",
)
private val kwC = setOf(
    "auto", "bool", "break", "case", "char", "const", "continue", "default", "do", "double", "else",
    "enum", "extern", "false", "float", "for", "goto", "if", "inline", "int", "long", "register",
    "restrict", "return", "short", "signed", "sizeof", "static", "struct", "switch", "true", "typedef",
    "union", "unsigned", "void", "volatile", "while", "NULL",
)
private val kwCpp = kwC + setOf(
    "and", "catch", "class", "constexpr", "const_cast", "decltype", "delete", "dynamic_cast",
    "explicit", "friend", "mutable", "namespace", "new", "noexcept", "not", "nullptr", "operator",
    "or", "override", "private", "protected", "public", "reinterpret_cast", "static_cast", "template",
    "this", "throw", "try", "typename", "using", "virtual",
)
private val kwCSharp = setOf(
    "abstract", "as", "async", "await", "base", "bool", "break", "byte", "case", "catch", "char",
    "checked", "class", "const", "continue", "decimal", "default", "delegate", "do", "double", "else",
    "enum", "event", "explicit", "extern", "false", "finally", "fixed", "float", "for", "foreach",
    "goto", "if", "implicit", "in", "int", "interface", "internal", "is", "lock", "long", "namespace",
    "new", "null", "object", "operator", "out", "override", "params", "private", "protected", "public",
    "readonly", "record", "ref", "return", "sbyte", "sealed", "short", "sizeof", "static", "string",
    "struct", "switch", "this", "throw", "true", "try", "typeof", "uint", "ulong", "unchecked",
    "unsafe", "ushort", "using", "var", "virtual", "void", "volatile", "while",
)
private val kwGo = setOf(
    "append", "bool", "break", "byte", "cap", "case", "chan", "const", "continue", "default", "defer",
    "else", "error", "fallthrough", "false", "float32", "float64", "for", "func", "go", "goto", "if",
    "import", "int", "int16", "int32", "int64", "int8", "interface", "len", "make", "map", "new", "nil",
    "package", "range", "return", "rune", "select", "string", "struct", "switch", "true", "type",
    "uint", "var",
)
private val kwRust = setOf(
    "as", "async", "await", "bool", "break", "char", "const", "continue", "crate", "dyn", "else",
    "enum", "Err", "extern", "f32", "f64", "false", "fn", "for", "i16", "i32", "i64", "i8", "if", "impl",
    "in", "isize", "let", "loop", "match", "mod", "move", "mut", "None", "Ok", "Option", "pub", "ref",
    "Result", "return", "self", "Self", "Some", "static", "str", "String", "struct", "super", "trait",
    "true", "type", "u16", "u32", "u64", "u8", "union", "unsafe", "use", "usize", "Vec", "where", "while",
)
private val kwSwift = setOf(
    "any", "as", "associatedtype", "break", "case", "catch", "class", "continue", "default", "defer",
    "deinit", "do", "else", "enum", "extension", "fallthrough", "false", "fileprivate", "for", "func",
    "guard", "if", "import", "in", "init", "inout", "internal", "is", "let", "nil", "open", "operator",
    "private", "protocol", "public", "repeat", "rethrows", "return", "self", "Self", "some", "static",
    "struct", "subscript", "super", "switch", "throw", "throws", "true", "try", "typealias", "var",
    "where", "while",
)
private val kwRuby = setOf(
    "alias", "and", "attr_accessor", "attr_reader", "attr_writer", "begin", "break", "case", "class",
    "def", "do", "else", "elsif", "end", "ensure", "false", "for", "if", "in", "module", "next", "nil",
    "not", "or", "puts", "redo", "require", "rescue", "retry", "return", "self", "super", "then",
    "true", "unless", "until", "when", "while", "yield",
)
private val kwPhp = setOf(
    "abstract", "and", "array", "as", "break", "callable", "case", "catch", "class", "clone", "const",
    "continue", "declare", "default", "do", "echo", "else", "elseif", "empty", "enum", "extends",
    "false", "final", "finally", "fn", "for", "foreach", "function", "global", "goto", "if",
    "implements", "include", "instanceof", "insteadof", "interface", "isset", "list", "match",
    "namespace", "new", "null", "or", "parent", "print", "private", "protected", "public", "readonly",
    "require", "return", "self", "static", "switch", "throw", "trait", "true", "try", "unset", "use",
    "var", "while", "xor", "yield",
)
private val kwSql = setOf(
    "all", "alter", "and", "as", "asc", "avg", "between", "boolean", "by", "case", "check", "constraint",
    "count", "create", "date", "default", "delete", "desc", "distinct", "drop", "else", "end", "exists",
    "foreign", "from", "full", "group", "having", "in", "index", "inner", "insert", "int", "into", "is",
    "join", "key", "left", "like", "limit", "max", "min", "not", "null", "offset", "on", "or", "order",
    "outer", "primary", "references", "right", "select", "set", "sum", "table", "text", "then",
    "timestamp", "union", "unique", "update", "values", "varchar", "view", "when", "where",
)
// SQL 大小写不敏感：关键字匹配前统一小写化
private val caseInsensitiveKwLangs = setOf("sql")
private val langKeywords: Map<String, Set<String>> = mapOf(
    "kotlin" to kwKotlin, "kt" to kwKotlin, "kts" to kwKotlin,
    "java" to kwJava,
    "javascript" to kwJs, "js" to kwJs, "jsx" to kwJs, "typescript" to kwJs, "ts" to kwJs, "tsx" to kwJs,
    "python" to kwPython, "py" to kwPython,
    "c" to kwC, "h" to kwC,
    "cpp" to kwCpp, "c++" to kwCpp, "cc" to kwCpp, "cxx" to kwCpp, "hpp" to kwCpp,
    "csharp" to kwCSharp, "cs" to kwCSharp, "c#" to kwCSharp,
    "go" to kwGo, "golang" to kwGo,
    "rust" to kwRust, "rs" to kwRust,
    "swift" to kwSwift,
    "ruby" to kwRuby, "rb" to kwRuby,
    "php" to kwPhp,
    "sql" to kwSql,
)
// 传入的 lang 需已 trim + 小写；未知/空标签回退并集 codeKeywords
private fun keywordsFor(lang: String): Set<String> = langKeywords[lang] ?: codeKeywords
private val hashCommentLangs = setOf("python", "py", "ruby", "rb", "sh", "bash", "shell", "zsh", "fish", "yaml", "yml", "toml", "r", "perl", "pl", "makefile", "make", "dockerfile", "docker", "ini", "conf", "cfg", "properties", "env", "gitignore", "nginx")
private val dashCommentLangs = setOf("sql", "lua", "haskell", "hs", "elm", "ada", "vhdl")
private val semiCommentLangs = setOf("lisp", "clojure", "clj", "scheme", "scm", "asm", "nasm")
private val langExt = mapOf(
    "python" to "py", "py" to "py", "javascript" to "js", "js" to "js", "typescript" to "ts", "ts" to "ts",
    "kotlin" to "kt", "kt" to "kt", "java" to "java", "c" to "c", "cpp" to "cpp", "c++" to "cpp",
    "csharp" to "cs", "cs" to "cs", "go" to "go", "rust" to "rs", "rs" to "rs", "swift" to "swift",
    "ruby" to "rb", "rb" to "rb", "php" to "php", "sql" to "sql", "html" to "html", "css" to "css",
    "json" to "json", "yaml" to "yml", "yml" to "yml", "toml" to "toml", "xml" to "xml", "sh" to "sh",
    "bash" to "sh", "shell" to "sh", "markdown" to "md", "md" to "md", "dart" to "dart", "lua" to "lua",
    "scala" to "scala", "r" to "r",
)

// 单遍扫描高亮：识别注释/字符串/数字/关键字/注解，颜色全取主题令牌。
// 对流式未闭合的串/注释安全（扫到结尾即止）。
private fun highlightCode(code: String, lang: String, kw: Color, str: Color, num: Color, com: Color): AnnotatedString = buildAnnotatedString {
    val l = lang.trim().lowercase()
    val hash = l in hashCommentLangs
    val dash = l in dashCommentLangs
    val semi = l in semiCommentLangs
    val slash = !hash && !dash && !semi
    // 按语言标签选关键字集（无/未知标签回退并集）；SQL 等大小写不敏感语言比较前小写化
    val keywords = keywordsFor(l)
    val kwCaseInsensitive = l in caseInsensitiveKwLangs
    val n = code.length
    fun span(from: Int, to: Int, c: Color, italic: Boolean = false) =
        withStyle(SpanStyle(color = c, fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal)) { append(code.substring(from, to)) }
    fun eol(from: Int) = code.indexOf('\n', from).let { if (it < 0) n else it }
    var i = 0
    while (i < n) {
        val c = code[i]
        when {
            hash && c == '#' -> { val e = eol(i); span(i, e, com, true); i = e }
            semi && c == ';' -> { val e = eol(i); span(i, e, com, true); i = e }
            dash && c == '-' && i + 1 < n && code[i + 1] == '-' -> { val e = eol(i); span(i, e, com, true); i = e }
            slash && c == '/' && i + 1 < n && code[i + 1] == '/' -> { val e = eol(i); span(i, e, com, true); i = e }
            slash && c == '/' && i + 1 < n && code[i + 1] == '*' -> { val e = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }; span(i, e, com, true); i = e }
            c == '"' || c == '\'' -> {
                val q = c
                if (i + 2 < n && code[i + 1] == q && code[i + 2] == q) {          // 三引号串（Python/Kotlin raw）
                    val m = "$q$q$q"; val e = code.indexOf(m, i + 3).let { if (it < 0) n else it + 3 }; span(i, e, str); i = e
                } else {
                    var j = i + 1
                    while (j < n) { val cj = code[j]; if (cj == '\\') { j += 2; continue }; if (cj == q || cj == '\n') break; j++ }
                    val e = if (j < n && code[j] == q) j + 1 else j; span(i, e, str); i = e
                }
            }
            c == '`' -> { val e = code.indexOf('`', i + 1).let { if (it < 0) n else it + 1 }; span(i, e, str); i = e }
            c.isDigit() || (c == '.' && i + 1 < n && code[i + 1].isDigit()) -> {
                var j = i + 1; while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                span(i, j, num); i = j
            }
            c.isLetter() || c == '_' || c == '@' -> {
                var j = if (c == '@') i + 1 else i
                while (j < n && (code[j].isLetterOrDigit() || code[j] == '_')) j++
                val word = code.substring(i, j)
                if (c == '@') span(i, j, kw)                                       // 注解 / 装饰器
                else if ((if (kwCaseInsensitive) word.lowercase() else word) in keywords) span(i, j, kw)
                else append(word)                                                  // 普通标识符走 Text 基色
                i = j
            }
            else -> { append(c); i++ }
        }
    }
}

// 保存代码到「下载」：Android 10+ 走 MediaStore（免权限、可见）；更低版本落应用外部目录。
private fun saveCodeToFile(ctx: android.content.Context, lang: String, content: String) {
    val ext = langExt[lang.trim().lowercase()] ?: "txt"
    val name = "xtom_code_${System.currentTimeMillis()}.$ext"
    try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.Downloads.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = ctx.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                android.widget.Toast.makeText(ctx, "已保存到下载：$name", android.widget.Toast.LENGTH_SHORT).show()
                return
            }
        }
        val dir = ctx.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
        val f = java.io.File(dir, name)
        f.writeText(content)
        android.widget.Toast.makeText(ctx, "已保存：${f.absolutePath}", android.widget.Toast.LENGTH_LONG).show()
    } catch (e: Exception) {
        android.widget.Toast.makeText(ctx, "保存失败：${e.message}", android.widget.Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun CodeAction(icon: ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    Icon(
        imageVector = icon, contentDescription = desc, tint = tint,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).padding(6.dp).size(16.dp),
    )
}

// ============================================================
// Mermaid 流程图：自研轻量渲染（graph/flowchart TD|LR 的节点+有向边）
// 只覆盖常见 flowchart；解析不出边时回退成普通代码块。无第三方库。
// ============================================================
private data class MmNode(val id: String, val label: String)
private data class MmEdge(val from: String, val to: String, val label: String)
private class MmGraph(val nodes: List<MmNode>, val edges: List<MmEdge>, val horizontal: Boolean)

private val mmBar = Regex("\\|([^|]*)\\|")
private val mmLabeled = Regex("^(.*?)--\\s*([^>|]+?)\\s*--+>(.*)$")
private val mmArrow = Regex("-\\.->|={2,}>|-{2,}>|-{3,}|-\\.-")
private val mmNodeDef = Regex("^([A-Za-z0-9_]+)\\s*(?:\\[\\[|\\(\\(|\\[|\\(|\\{\\{|\\{)([\\s\\S]*?)(?:\\]\\]|\\)\\)|\\]|\\)|\\}\\}|\\})\\s*$")

// 从一侧文本取「节点 id + 显示标签」；含 [..]/(..)/{..} 时取括号内为标签。
private fun mmParseNode(raw: String): MmNode? {
    val s = raw.trim().trim('"')
    if (s.isBlank()) return null
    mmNodeDef.find(s)?.let { return MmNode(it.groupValues[1], it.groupValues[2].trim().ifBlank { it.groupValues[1] }) }
    val id = s.takeWhile { it.isLetterOrDigit() || it == '_' }
    return if (id.isBlank()) null else MmNode(id, id)
}

private fun parseMermaid(code: String): MmGraph? {
    val rawLines = code.replace("\r\n", "\n").split("\n").map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("%%") }
    if (rawLines.isEmpty()) return null
    var horizontal = false
    var start = 0
    val first = rawLines[0].lowercase()
    if (first.startsWith("graph") || first.startsWith("flowchart")) {
        horizontal = first.contains("lr") || first.contains("rl")
        start = 1
    }
    val nodes = LinkedHashMap<String, MmNode>()
    val edges = mutableListOf<MmEdge>()
    fun node(n: MmNode?) { if (n != null) { val e = nodes[n.id]; if (e == null || (e.label == e.id && n.label != n.id)) nodes[n.id] = n } }
    for (i in start until rawLines.size) {
        var line = rawLines[i]
        if (line.startsWith("subgraph") || line == "end" || line.startsWith("style") || line.startsWith("classDef") || line.startsWith("class ") || line.startsWith("linkStyle")) continue
        var label = ""
        mmBar.find(line)?.let { label = it.groupValues[1].trim(); line = line.replace(it.value, " ") }
        // A -- 文本 --> B 形式
        val labeled = mmLabeled.matchEntire(line)
        if (labeled != null) {
            if (label.isBlank()) label = labeled.groupValues[2].trim()
            val l = mmParseNode(labeled.groupValues[1]); val r = mmParseNode(labeled.groupValues[3])
            node(l); node(r)
            if (l != null && r != null) edges.add(MmEdge(l.id, r.id, label))
            continue
        }
        if (mmArrow.containsMatchIn(line)) {
            val parts = mmArrow.split(line).map { it.trim() }.filter { it.isNotBlank() }
            val chain = parts.mapNotNull { mmParseNode(it) }
            chain.forEach { node(it) }
            for (k in 0 until chain.size - 1) edges.add(MmEdge(chain[k].id, chain[k + 1].id, if (k == 0) label else ""))
        } else {
            node(mmParseNode(line))  // 纯节点定义行
        }
    }
    if (edges.isEmpty()) return null
    return MmGraph(nodes.values.toList(), edges, horizontal)
}

@Composable
private fun MermaidView(code: String) {
    val scheme = MaterialTheme.colorScheme
    val g = remember(code) { parseMermaid(code) } ?: run { CodeBlock("mermaid", code); return }
    val density = LocalDensity.current
    // 分层：最长路径深度（对环有界，迭代 |V| 次即止）
    val order = g.nodes.map { it.id }
    val idx = order.withIndex().associate { (i, id) -> id to i }
    val depth = HashMap<String, Int>().apply { order.forEach { put(it, 0) } }
    repeat(order.size) { for (e in g.edges) { val du = depth[e.from] ?: 0; if ((depth[e.to] ?: 0) < du + 1) depth[e.to] = du + 1 } }
    val layers = depth.entries.groupBy({ it.value }, { it.key }).toSortedMap()
        .mapValues { (_, ids) -> ids.sortedBy { idx[it] ?: 0 } }

    val nodeH = with(density) { 40.dp.toPx() }
    val vGap = with(density) { 30.dp.toPx() }
    val hGap = with(density) { 18.dp.toPx() }
    val pad = with(density) { 10.dp.toPx() }
    val textPx = with(density) { 12.sp.toPx() }
    val paint = remember(textPx) { android.graphics.Paint().apply { isAntiAlias = true; textSize = textPx; textAlign = android.graphics.Paint.Align.CENTER } }
    fun disp(s: String) = if (s.length > 14) s.take(13) + "…" else s
    fun nodeW(label: String) = (paint.measureText(disp(label)) + with(density) { 22.dp.toPx() }).coerceAtLeast(with(density) { 48.dp.toPx() })

    // 布局：主轴=深度，交叉轴=层内顺序，居中对齐
    val rects = HashMap<String, androidx.compose.ui.geometry.Rect>()
    val labelOf = g.nodes.associate { it.id to it.label }
    var canvasMain = 0f; var canvasCross = 0f
    layers.forEach { (d, ids) ->
        val sizes = ids.map { nodeW(labelOf[it] ?: it) }
        val crossLen = sizes.sum() + hGap * (ids.size - 1).coerceAtLeast(0)
        canvasCross = maxOf(canvasCross, crossLen)
        canvasMain = maxOf(canvasMain, (d + 1) * nodeH + d * vGap)
        ids.size
    }
    val crossFull = canvasCross + pad * 2
    val mainFull = canvasMain + pad * 2
    // 交叉轴 = 水平方向(vertical 图) / 主轴 = 垂直方向
    val widthPx = if (g.horizontal) mainFull else crossFull
    val heightPx = if (g.horizontal) crossFull else mainFull
    layers.forEach { (d, ids) ->
        val sizes = ids.map { nodeW(labelOf[it] ?: it) }
        val crossLen = sizes.sum() + hGap * (ids.size - 1).coerceAtLeast(0)
        var cross = pad + (canvasCross - crossLen) / 2f
        val main = pad + d * (nodeH + vGap)
        ids.forEachIndexed { k, id ->
            val w = sizes[k]
            val r = if (g.horizontal) androidx.compose.ui.geometry.Rect(main, cross, main + nodeH, cross + w)
                    else androidx.compose.ui.geometry.Rect(cross, main, cross + w, main + nodeH)
            rects[id] = r
            cross += w + hGap
        }
    }

    val wDp = with(density) { widthPx.toDp() }
    val hDp = with(density) { heightPx.toDp() }
    val line = scheme.outline; val nodeFill = scheme.surfaceContainerHighest; val nodeBorder = scheme.primary
    val onNode = scheme.onSurface; val edgeLabelBg = scheme.surface; val muted = scheme.onSurfaceVariant
    val onNodeArgb = onNode.toArgb(); val mutedArgb = muted.toArgb()
    Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
        androidx.compose.foundation.Canvas(modifier = Modifier.size(wDp.coerceAtLeast(1.dp), hDp.coerceAtLeast(1.dp)).padding(2.dp)) {
            // 先画边（在节点下方），有向箭头指向目标
            for (e in g.edges) {
                val a = rects[e.from] ?: continue; val b = rects[e.to] ?: continue
                val start = if (g.horizontal) androidx.compose.ui.geometry.Offset(a.right, a.center.y) else androidx.compose.ui.geometry.Offset(a.center.x, a.bottom)
                val end = if (g.horizontal) androidx.compose.ui.geometry.Offset(b.left, b.center.y) else androidx.compose.ui.geometry.Offset(b.center.x, b.top)
                drawLine(line, start, end, strokeWidth = with(density) { 1.4.dp.toPx() })
                // 箭头
                val ang = kotlin.math.atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
                val ah = with(density) { 7.dp.toPx() }
                val p1 = androidx.compose.ui.geometry.Offset((end.x - ah * kotlin.math.cos(ang - 0.4)).toFloat(), (end.y - ah * kotlin.math.sin(ang - 0.4)).toFloat())
                val p2 = androidx.compose.ui.geometry.Offset((end.x - ah * kotlin.math.cos(ang + 0.4)).toFloat(), (end.y - ah * kotlin.math.sin(ang + 0.4)).toFloat())
                drawLine(line, end, p1, strokeWidth = with(density) { 1.4.dp.toPx() })
                drawLine(line, end, p2, strokeWidth = with(density) { 1.4.dp.toPx() })
                if (e.label.isNotBlank()) drawIntoCanvas { c ->
                    val mx = (start.x + end.x) / 2f; val my = (start.y + end.y) / 2f
                    paint.color = mutedArgb; paint.textSize = textPx * 0.85f
                    c.nativeCanvas.drawText(if (e.label.length > 10) e.label.take(9) + "…" else e.label, mx, my - 3f, paint)
                    paint.textSize = textPx
                }
            }
            // 再画节点
            for (n in g.nodes) {
                val r = rects[n.id] ?: continue
                drawRoundRect(nodeFill, topLeft = r.topLeft, size = r.size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(with(density) { 9.dp.toPx() }))
                drawRoundRect(nodeBorder, topLeft = r.topLeft, size = r.size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(with(density) { 9.dp.toPx() }), style = androidx.compose.ui.graphics.drawscope.Stroke(width = with(density) { 1.2.dp.toPx() }))
                drawIntoCanvas { c ->
                    paint.color = onNodeArgb
                    c.nativeCanvas.drawText(disp(n.label), r.center.x, r.center.y + textPx * 0.35f, paint)
                }
            }
        }
    }
}

// <audio>/<video>：WebView 原生播放器（controls）。视频按 16:9 宽高比（不留多余黑边），
// 音频一条；color-scheme 跟随 App 深浅主题，控件不再是刺眼白条。
@Composable
private fun MediaView(url: String, video: Boolean, gridColor: Color) {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val sizeMod = if (video) Modifier.fillMaxWidth().aspectRatio(16f / 9f) else Modifier.fillMaxWidth().height(52.dp)
    Box(sizeMod.clip(RoundedCornerShape(10.dp)).border(1.dp, gridColor, RoundedCornerShape(10.dp))) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { c ->
                android.webkit.WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    @android.annotation.SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = true   // 需用户点播放，不自动播
                    settings.domStorageEnabled = true
                }
            },
            update = { wv ->
                // 只在 url/主题变了才重载：否则每次重组都重建播放器（会打断正在播放的音视频）。
                val loadKey = "$url|$dark"
                if (wv.tag != loadKey) {
                    wv.tag = loadKey
                    val cs = if (dark) "dark" else "light"
                    val tag = if (video) "<video controls playsinline style=\"width:100%;height:100%;object-fit:contain;background:transparent\" src=\"$url\"></video>"
                              else "<audio controls style=\"width:100%\" src=\"$url\"></audio>"
                    val html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                        "<meta name=\"color-scheme\" content=\"$cs\">" +
                        "<style>html,body{margin:0;padding:0;background:transparent;color-scheme:$cs}audio,video{color-scheme:$cs}</style></head><body>$tag</body></html>"
                    wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// <iframe src="…">：用 WebView 加载嵌入的网页/视频。需 JS（多数嵌入依赖脚本），
// 内容来自消息数据；仅在用户查看消息时加载。
@Composable
private fun IframeView(url: String, gridColor: Color) {
    Box(Modifier.fillMaxWidth().heightIn(min = 180.dp, max = 320.dp).clip(RoundedCornerShape(10.dp)).border(1.dp, gridColor, RoundedCornerShape(10.dp))) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { c ->
                android.webkit.WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    @android.annotation.SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true; settings.useWideViewPort = true
                    webViewClient = android.webkit.WebViewClient()
                }
            },
            update = { wv -> if (wv.tag != url) { wv.tag = url; wv.loadUrl(url) } },   // url 没变别重新 loadUrl(会重新联网+闪)
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ```svg 代码块：用 WebView 渲染 SVG 矢量图（静态、关 JS、离线自包含，不联网）。
@Composable
private fun SvgView(svg: String) {
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 320.dp)
            .clip(RoundedCornerShape(10.dp)).border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp)),
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { c ->
                android.webkit.WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    isVerticalScrollBarEnabled = false; isHorizontalScrollBarEnabled = false
                    @android.annotation.SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = false
                }
            },
            update = { wv ->
                val body = if (svg.trimStart().startsWith("<svg", ignoreCase = true)) svg
                           else "<svg xmlns=\"http://www.w3.org/2000/svg\">$svg</svg>"
                val html = "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                    "<style>html,body{margin:0;padding:0;background:transparent}svg{max-width:100%;height:auto;display:block;margin:auto}</style></head><body>$body</body></html>"
                wv.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ```html 代码块：用 WebView 渲染 HTML 预览。自包含（baseURL=null 的不透明源，无法读本地文件/联外域），
// 禁文件/内容访问；HTML 常带内联样式与脚本，故开 JS（同 iframe/media，仅查看消息时加载）。
@Composable
private fun HtmlView(html: String) {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    Box(
        Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 400.dp)
            .clip(RoundedCornerShape(10.dp)).border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp)),
    ) {
        androidx.compose.ui.viewinterop.AndroidView(
            factory = { c ->
                android.webkit.WebView(c).apply {
                    setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    @android.annotation.SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.loadWithOverviewMode = true; settings.useWideViewPort = true
                    settings.allowFileAccess = false
                    settings.allowContentAccess = false
                }
            },
            update = { wv ->
                // 只在内容/主题变了才重载：否则每次重组都重新渲染整页(闪、丢滚动位置/表单状态)。
                val loadKey = "$dark:${html.hashCode()}"
                if (wv.tag != loadKey) {
                    wv.tag = loadKey
                    val cs = if (dark) "dark" else "light"
                    val t = html.trimStart()
                    // 已是完整文档则原样加载；否则包一层文档骨架并让 color-scheme 跟随深浅主题
                    val doc = if (t.startsWith("<!doctype", ignoreCase = true) || t.startsWith("<html", ignoreCase = true)) html
                              else "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                                  "<meta name=\"color-scheme\" content=\"$cs\">" +
                                  "<style>html,body{margin:0;padding:8px;background:transparent;color-scheme:$cs}img,svg{max-width:100%;height:auto}</style></head><body>$html</body></html>"
                    wv.loadDataWithBaseURL(null, doc, "text/html", "utf-8", null)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// 可渲染块（SVG/HTML）预览 ↔ 源码切换：默认渲染预览，右上角小图标按钮切到源码代码块，再切回。
@Composable
private fun RenderableBlock(lang: String, content: String, preview: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var showSource by remember(content) { mutableStateOf(false) }
    if (showSource) {
        // 源码视图复用 CodeBlock，标题栏加一个「预览」按钮切回渲染
        CodeBlock(lang, content, headerAction = {
            CodeAction(Icons.Outlined.Visibility, tr("预览"), scheme.primary) { showSource = false }
        })
    } else {
        Box(Modifier.fillMaxWidth()) {
            preview()
            // 悬浮于预览右上角的「查看源码」小按钮
            Surface(
                color = scheme.surfaceContainerHighest.copy(alpha = 0.9f),
                shape = RoundedCornerShape(6.dp),
                modifier = Modifier.align(androidx.compose.ui.Alignment.TopEnd).padding(4.dp),
            ) {
                CodeAction(Icons.Outlined.Code, tr("查看源码"), scheme.primary) { showSource = true }
            }
        }
    }
}

@Composable
private fun CodeBlock(lang: String, content: String, headerAction: (@Composable () -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val accents = com.arix.app.theme.LocalXtomAccents.current
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val lineCount = content.count { it == '\n' } + 1
    val foldable = lineCount > 14
    // 代码块显示细节（换行/自动折叠/行号）。走 static CompositionLocal，代码块在流式长回复里
    // 重组极频繁，读一次 SharedPreferences 就是一次 IO——绝不能在这里读盘。
    val display = LocalChatDisplay.current
    // 自动折叠只对「长到需要折」的块生效；开关本身也当 key，免得在设置页拨了开关预览不跟着变。
    var collapsed by remember(content, display.codeAutoCollapse) { mutableStateOf(display.codeAutoCollapse && foldable) }
    // 语法高亮：keyword=primary / string=success / number=warning / comment=muted（全部主题令牌）
    // ① 超长直接纯文本不高亮 ② 缓存命中同步返回（不闪）③ 未命中→后台线程算、先纯文本占位、算好再换（不阻塞滚动/首帧）。
    val kw = scheme.primary; val strC = accents.success; val numC = accents.warning; val comC = scheme.onSurfaceVariant
    val highlighted = if (content.length > CODE_HIGHLIGHT_MAX) {
        remember(content) { AnnotatedString(content) }
    } else {
        val cached = remember(content, lang, kw, strC, numC, comC) { highlightCodePeek(content, lang, kw, strC, numC, comC) }
        cached ?: androidx.compose.runtime.produceState(remember(content) { AnnotatedString(content) }, content, lang, kw, strC, numC, comC) {
            value = withContext(Dispatchers.Default) { highlightCodeCached(content, lang, kw = kw, str = strC, num = numC, com = comC) }
        }.value
    }
    Surface(color = scheme.surfaceContainerLowest, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 10.dp, end = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                Text("${lang.ifBlank { "code" }} · $lineCount 行", color = scheme.onSurfaceVariant, fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    headerAction?.invoke()
                    if (foldable) CodeAction(if (collapsed) Icons.Outlined.ExpandMore else Icons.Outlined.ExpandLess, if (collapsed) "展开" else "折叠", scheme.onSurfaceVariant) { collapsed = !collapsed }
                    CodeAction(Icons.Outlined.ContentCopy, "复制", scheme.primary) {
                        clipboard.setText(AnnotatedString(content))
                        android.widget.Toast.makeText(ctx, tr("已复制代码"), android.widget.Toast.LENGTH_SHORT).show()
                    }
                    CodeAction(Icons.Outlined.FileDownload, "保存", scheme.primary) { saveCodeToFile(ctx, lang, content) }
                }
            }
            HorizontalDivider(color = scheme.outlineVariant, thickness = 0.5.dp)
            if (collapsed) {
                Row(Modifier.fillMaxWidth().clickable { collapsed = false }.padding(horizontal = 10.dp, vertical = 8.dp)) {
                    Text("已折叠 · 点击展开 $lineCount 行", color = scheme.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                }
            } else {
                // 行号列固定在最左、**不进横向滚动区**：跟着代码一起滚走的行号等于没有行号。
                // ⚠ 行号与「自动换行」同时开时，一条长行会占多个视觉行而行号只有一个，往下会对不齐——
                // 这是纯文本渲染的固有限制（要对齐得逐行测量高度，代价远大于收益），故只在注释里说明，不做。
                Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    if (display.codeLineNumbers) {
                        // 字号/行高/字体必须和正文代码逐项相同，否则两列会越往下错得越开。
                        // 没画竖分隔线：竖线要么得知道内容像素高度（行高是 sp，字体缩放一变就算错），
                        // 要么得给 Row 开 IntrinsicSize（横向滚动区的内在测量不保证支持）——用弱色 + 留白已经够分得清。
                        val gutter = remember(lineCount) { (1..lineCount).joinToString("\n") }
                        Text(
                            gutter, color = scheme.outline, fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace, lineHeight = 17.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                    val code: @Composable () -> Unit = {
                        Text(highlighted, color = scheme.onSurface, fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp)
                    }
                    // 换行开 = 让 Text 自己按可用宽度折行（默认 softWrap）；关 = 老行为，单行到底、横向滚。
                    if (display.codeWrap) Box(Modifier.weight(1f)) { code() }
                    else Row(Modifier.horizontalScroll(rememberScrollState())) { code() }
                }
            }
        }
    }
}
