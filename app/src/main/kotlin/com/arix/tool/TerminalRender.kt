package com.arix.tool

/**
 * 把原始终端输出（带 \r 进度重画、ANSI 颜色/光标、\e[K 清行）渲染成干净可读文本。
 *
 * 处理：\r=回行首(后续字符覆盖本行)、\n=换行、\e[K/\e[0K=清到行尾、\e[2K=清整行，
 * 其余 CSI/OSC/单字符 ESC 一律剥掉，BEL/BS 丢弃。
 * 不是完整 VT 仿真（无光标上下移动/滚动区），但 apt/pip/npm/git 的进度条会正确收敛成最终态。
 */
object TerminalRender {
    private const val MAX_LINES = 3000

    fun clean(raw: String): String {
        val lines = ArrayList<StringBuilder>()
        lines.add(StringBuilder())
        var col = 0
        var i = 0
        val n = raw.length
        while (i < n) {
            val c = raw[i]
            when {
                c.code == 27 -> {   // ESC
                    if (i + 1 < n && raw[i + 1] == '[') {          // CSI: ESC [ params final(@-~)
                        var j = i + 2
                        while (j < n && raw[j] !in '@'..'~') j++
                        val fin = if (j < n) raw[j] else ' '
                        if (fin == 'K') {                          // 清行
                            val cur = lines[lines.size - 1]
                            val param = raw.substring(i + 2, j)
                            if (param == "2") { cur.setLength(0); col = 0 }
                            else if (col < cur.length) cur.setLength(col)
                        }
                        i = j + 1; continue
                    } else if (i + 1 < n && raw[i + 1] == ']') {   // OSC: ESC ] ... BEL/ST
                        var j = i + 2
                        while (j < n && raw[j].code != 7 && raw[j].code != 27) j++
                        i = if (j < n && raw[j].code == 7) j + 1 else j
                        continue
                    } else { i += 2; continue }                    // ESC + 单字符
                }
                c.code == 7 || c.code == 8 -> {}                   // BEL / BS 丢弃
                c == '\r' -> col = 0
                c == '\n' -> {
                    lines.add(StringBuilder()); col = 0
                    if (lines.size > MAX_LINES) lines.removeAt(0)
                }
                else -> {
                    val cur = lines[lines.size - 1]
                    if (col < cur.length) cur.setCharAt(col, c)
                    else { while (cur.length < col) cur.append(' '); cur.append(c) }
                    col++
                }
            }
            i++
        }
        return lines.joinToString("\n") { it.toString().trimEnd() }
    }
}
