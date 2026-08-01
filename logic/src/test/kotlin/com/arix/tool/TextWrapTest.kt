package com.arix.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextWrap.softWrap]：超长单行插换行，**只增不改**——原文每个字符都还在、顺序不变，
 * 连换行符本身也不动（早先的实现会把 CRLF / 裸 CR 顺手规范化成 LF，见下面两个用例）。
 *
 * 和头尾截断是两种策略，别混：截断是「体积太大，丢掉中间」；软换行是「一行太长模型读不动」，
 * 长行的问题不是体积而是没有可锚定的结构——丢内容反而让模型什么都拿不到。
 * 纯字符串运算，无 Android 依赖（调用方 ToolOutputStore 要 Context，所以它留在 :app）。
 */
class TextWrapTest {

    @Test
    fun 短文本原样返回() {
        val s = "一段很短的工具结果"
        assertSame(s, TextWrap.softWrap(s))
    }

    @Test
    fun 长文本但每行都不超宽时原样返回() {
        val text = (1..100).joinToString("\n") { "第${it}行".padEnd(50, '-') }
        assertTrue("本用例前提：总长要超过 width", text.length > 2000)
        assertSame(text, TextWrap.softWrap(text, width = 2000))
    }

    @Test
    fun 超长单行被插换行且内容一个字不丢() {
        val line = "A".repeat(5000)
        val out = TextWrap.softWrap(line, width = 2000)

        assertEquals("内容一个字都不能丢", line, out.replace("\n", ""))
        assertEquals("5000 / 2000 应切成 3 段", 3, out.lines().size)
        assertTrue("每段都不能超宽", out.lines().all { it.length <= 2000 })
        assertEquals(listOf(2000, 2000, 1000), out.lines().map { it.length })
    }

    @Test
    fun 长短行混排时短行保持原样() {
        val short1 = "== 命令输出开始 =="
        val long = "B".repeat(4500)
        val short2 = "exit code: 0"
        val out = TextWrap.softWrap("$short1\n$long\n$short2", width = 2000)

        val lines = out.lines()
        assertEquals(short1, lines.first())
        assertEquals("结论行（答案常在尾部）必须原样留住", short2, lines.last())
        assertEquals("内容一个字都不能丢", "$short1$long$short2", out.replace("\n", ""))
        assertTrue(lines.all { it.length <= 2000 })
        assertEquals(1 + 3 + 1, lines.size)
    }

    @Test
    fun 恰好等于width的单行不被切() {
        val line = "C".repeat(2000)
        assertEquals(line, TextWrap.softWrap(line, width = 2000))
    }

    @Test
    fun 比width多一个字符的单行会被切() {
        val line = "D".repeat(2001)
        val out = TextWrap.softWrap(line, width = 2000)
        assertEquals(listOf(2000, 1), out.lines().map { it.length })
        assertEquals(line, out.replace("\n", ""))
    }

    @Test
    fun 空串不炸() {
        assertEquals("", TextWrap.softWrap(""))
    }

    /**
     * CRLF 必须原样留着：Windows 程序（adb/终端里的 .exe、日志文件）输出就是 CRLF，
     * 把它改写成 LF 等于悄悄改了工具结果的内容。
     */
    @Test
    fun CRLF不被改写成LF() {
        val long = "F".repeat(4500)
        val text = "第一行\r\n$long\r\n最后一行"
        val out = TextWrap.softWrap(text, width = 2000)

        assertEquals("原有的两处 CRLF 一个都不能少", 2, Regex("\r\n").findAll(out).count())
        // 只把「新插入的 LF」（前面不是 \r 的那些）去掉，应当逐字符还原出原文
        assertEquals("原文每个字符都要还在、顺序不变", text, out.replace(Regex("(?<!\r)\n"), ""))
        assertTrue("超宽段仍然要被切开", out.lines().size > 3)
    }

    /** 裸 CR（进度条一行行刷回行首那种）同理：不该被补成 CRLF，也不该被换成 LF。 */
    @Test
    fun 裸CR不被改写成LF() {
        val long = "G".repeat(3000)
        val text = "进度 10%\r进度 100%\r$long"
        val out = TextWrap.softWrap(text, width = 2000)

        assertEquals("两个裸 \\r 必须原样还在", 2, out.count { it == '\r' })
        assertTrue("不许把裸 \\r 补成 CRLF", !out.contains("\r\n"))
        assertEquals("原文每个字符都要还在、顺序不变", text, out.replace("\n", ""))
    }

    @Test
    fun 默认宽度是2000() {
        val out = TextWrap.softWrap("E".repeat(4001))
        assertEquals(listOf(2000, 2000, 1), out.lines().map { it.length })
    }
}
