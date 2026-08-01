package com.arix.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TextBudget]：字符数不是成本、token 才是。这里钉住三件事——
 *  ① 中文/英文/混排的量级关系（同样 120 个字符，中文 token 约是英文的 3 倍）；
 *  ② takeByTokens 退到行边界、且不超预算；
 *  ③ outline 按扩展名给不同形状的结构预览。
 * 纯字符串运算，无 Android 依赖。
 */
class TextBudgetTest {

    // ---------------- estimateTokens ----------------

    @Test
    fun 空串是0个token() {
        assertEquals(0, TextBudget.estimateTokens(""))
    }

    @Test
    fun 非空串至少1个token() {
        assertTrue(TextBudget.estimateTokens("a") >= 1)
        assertTrue(TextBudget.estimateTokens("好") >= 1)
    }

    @Test
    fun 英文按4字符1token() {
        // 120 / 4.0 = 30，再 +1 的保守余量
        assertEquals(31, TextBudget.estimateTokens("a".repeat(120)))
    }

    @Test
    fun 中文按1点2字符1token() {
        val n = TextBudget.estimateTokens("汉".repeat(120))
        assertTrue("120 个汉字应在 100 token 上下，实际 $n", n in 99..102)
    }

    @Test
    fun 同样字符数中文远贵于英文() {
        val cjk = TextBudget.estimateTokens("汉".repeat(120))
        val en = TextBudget.estimateTokens("a".repeat(120))
        assertTrue("中文应至少是英文的两倍：cjk=$cjk en=$en", cjk > en * 2)
    }

    @Test
    fun 混排落在中英之间() {
        val cjk = TextBudget.estimateTokens("汉".repeat(120))
        val en = TextBudget.estimateTokens("a".repeat(120))
        val mixed = TextBudget.estimateTokens("汉".repeat(60) + "a".repeat(60))
        assertTrue("混排=$mixed 应大于纯英文=$en", mixed > en)
        assertTrue("混排=$mixed 应小于纯中文=$cjk", mixed < cjk)
    }

    @Test
    fun 全角标点也算CJK量级() {
        // 全角逗号在 0xFF00..0xFFEF 区间，按 1.2 字符/token 算；半角逗号按 4 字符/token
        val full = TextBudget.estimateTokens("，".repeat(120))
        val half = TextBudget.estimateTokens(",".repeat(120))
        assertTrue("全角=$full 应远大于半角=$half", full > half * 2)
    }

    @Test
    fun 文本变长token单调不减() {
        val s = "混合 text 内容 mixed 一段。"
        val one = TextBudget.estimateTokens(s)
        val four = TextBudget.estimateTokens(s.repeat(4))
        assertTrue("四倍长度的 token 必须更多：$one → $four", four > one)
    }

    // ---------------- takeByTokens ----------------

    @Test
    fun 预算够时原样返回() {
        val s = "短短一段话"
        assertSame(s, TextBudget.takeByTokens(s, 1000))
    }

    @Test
    fun 超预算时切在行边界且不超预算() {
        // 100 行，每行 20 个 ASCII 字符
        val lines = (0 until 100).map { "L" + it.toString().padStart(3, '0') + "x".repeat(16) }
        val text = lines.joinToString("\n")
        assertTrue(lines.all { it.length == 20 })

        val out = TextBudget.takeByTokens(text, 100)

        assertTrue("必须是原文前缀", text.startsWith(out))
        assertTrue("必须真的截短了", out.length < text.length)
        assertTrue("不能超预算，实际 ${TextBudget.estimateTokens(out)}", TextBudget.estimateTokens(out) <= 100)
        assertFalse("不该留下悬空的换行", out.endsWith("\n"))
        // 每一行都必须完整——半行日志/半行代码对模型没用，还会被当成完整内容
        assertTrue(
            "切出来的每行都应是完整的 20 字符行：${out.lines().map { it.length }}",
            out.lines().all { it.length == 20 },
        )
    }

    @Test
    fun 整段没有换行时退化为按字符截断() {
        val text = "x".repeat(1000)
        val out = TextBudget.takeByTokens(text, 10)
        assertTrue(out.isNotEmpty())
        assertTrue("必须是原文前缀", text.startsWith(out))
        assertTrue("必须真的截短了", out.length < text.length)
        assertTrue("不能超预算，实际 ${TextBudget.estimateTokens(out)}", TextBudget.estimateTokens(out) <= 10)
    }

    // ---------------- outline ----------------

    @Test
    fun markdown取标题结构() {
        val md = """
            # 一级标题
            正文一段正文一段
            ## 二级标题
            另一段正文
            ### 三级标题
        """.trimIndent()
        val out = TextBudget.outline(md, "DESIGN.md")

        assertTrue(out.startsWith("标题结构："))
        assertTrue(out.contains("# 一级标题"))
        assertTrue(out.contains("## 二级标题"))
        assertTrue(out.contains("### 三级标题"))
        assertFalse("正文不该进目录", out.contains("正文一段正文一段"))
    }

    @Test
    fun markdown扩展名大小写不敏感() {
        val out = TextBudget.outline("# 标题\n正文", "README.MD")
        assertTrue(out.startsWith("标题结构："))
    }

    @Test
    fun 没有标题的markdown回退成前几行() {
        val md = "第一行\n第二行\n第三行"
        val out = TextBudget.outline(md, "notes.md")
        assertEquals(md, out)
    }

    @Test
    fun csv取表头与前几行() {
        val rows = listOf("列A,列B,列C") + (1..12).map { "行$it-1,行$it-2,行$it-3" }
        val out = TextBudget.outline(rows.joinToString("\n"), "data.csv")

        assertTrue(out.startsWith("表头与前几行："))
        assertTrue("表头必须在", out.contains("列A,列B,列C"))
        assertTrue("前几行也要有", out.contains("行5-1"))
        assertFalse("csv 最多给 6 行，第 7 行之后不该出现", out.contains("行6-1"))
    }

    @Test
    fun 其他扩展名给开头若干行() {
        val text = (1..50).joinToString("\n") { "第${it}行" }
        val out = TextBudget.outline(text, "server.log", maxLines = 5)

        assertTrue(out.startsWith("开头 5 行："))
        assertTrue(out.contains("第1行"))
        assertTrue(out.contains("第5行"))
        assertFalse(out.contains("第6行"))
    }
}
