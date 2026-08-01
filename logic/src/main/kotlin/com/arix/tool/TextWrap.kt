package com.arix.tool

/**
 * 纯字符串的换行处理。目前只有一件事：给超长单行插换行。
 *
 * 住在 :logic 而不是 :app：它不碰 Context、不碰任何 android.* / androidx.*，
 * 放这儿就能在普通 JVM 上跑单测（不用连设备、不用 Robolectric）。
 * 调用它的 ToolOutputStore 因为要落盘所以搬不动，留在 :app；包名一致，两边互相看得见。
 */
object TextWrap {

    /**
     * 超长单行按 [width] 插换行——**只增不改**：原文一个字符都不动，只在超宽的行内部**插入**换行。
     *
     * 和头尾截断是两种策略，别混：截断是「体积太大，丢掉中间」（搜索结果、文件内容），
     * 软换行是「一行太长，模型读不动」（minified JS、base64、无换行的日志）。
     * 长行的问题不是体积，是没有可锚定的结构——丢掉它反而让模型什么都拿不到。
     *
     * ⚠ 实现上刻意**不用** `lineSequence().joinToString("\n")`：那种写法在真触发换行的分支里会把
     * 原文的 CRLF / 单独的 CR 统统重写成 LF。对 Windows 程序的输出、`\r` 刷进度条的日志来说，
     * 这就是悄悄改了内容——和「不丢内容」的承诺不符，而且改的正是模型要按行定位时的锚点。
     * 改成按「连续的非换行字符段」找超宽段、只往段内部插 LF：原有的换行符原样留着，
     * 新插入的是**新增字符**，不是替换。所以现在 `去掉新插入的换行 == 原文` 只在同一种换行符下成立，
     * 而更强的性质成立：**原文的每个字符都还在，顺序不变**。
     */
    fun softWrap(text: String, width: Int = 2000): String {
        if (text.length < width) return text
        // 「一段」= 两个换行符之间的连续非换行字符。{width+1,} 直接筛出超宽段，省一次全量切分。
        val overlong = Regex("[^\n\r]{${width + 1},}")
        if (!overlong.containsMatchIn(text)) return text
        return overlong.replace(text) { m -> m.value.chunked(width).joinToString("\n") }
    }
}
