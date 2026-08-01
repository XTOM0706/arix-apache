package com.arix.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FuzzyMatch]：错字、漏字、词序颠倒仍要命中，完全无关的必须**不**命中。
 *
 * 「不命中」这半边和「命中」一样重要——模糊匹配一旦放宽到什么都能沾边，
 * 检索结果就被噪音淹没，比不做模糊还糟。
 * 纯字符串运算，无 Android 依赖。
 */
class FuzzyMatchTest {

    private val 分值上界 = 1.0f

    // ---------------- normalize / tokens ----------------

    @Test
    fun 全半角与大小写与空白都被折叠() {
        assertEquals("a b c", FuzzyMatch.normalize("  Ａ  B\tc "))
        assertEquals("weather", FuzzyMatch.normalize("ＷｅＡＴＨＥＲ"))
    }

    @Test
    fun 中文按连续汉字段成词西文按空白切() {
        assertEquals(listOf("北京", "weather", "2024"), FuzzyMatch.tokens("北京 weather 2024"))
    }

    @Test
    fun 重复词只保留一次() {
        assertEquals(listOf("天气"), FuzzyMatch.tokens("天气 天气"))
    }

    // ---------------- 精确 / 子串 ----------------

    @Test
    fun 完全相同得满分() {
        assertEquals(FuzzyMatch.EXACT, FuzzyMatch.score("北京天气", "北京天气"), 0f)
    }

    @Test
    fun 全角输入也能对上半角目标() {
        assertEquals(FuzzyMatch.EXACT, FuzzyMatch.score("ＷｅＡＴＨＥＲ", "weather"), 0f)
    }

    @Test
    fun 前缀命中高于中间命中高于模糊命中() {
        val prefix = FuzzyMatch.score("北京", "北京天气预报")
        val middle = FuzzyMatch.score("天气", "北京天气预报")
        val fuzzy = FuzzyMatch.score("北京气", "北京天气预报")

        assertTrue("前缀=$prefix 应高于中间=$middle", prefix > middle)
        assertTrue("中间=$middle 应高于模糊=$fuzzy", middle > fuzzy)
        assertTrue("精确命中永远高于模糊命中", FuzzyMatch.EXACT > prefix)
        assertTrue("模糊命中不该跨过 0.85 这条线：$fuzzy", fuzzy <= 0.85f)
    }

    // ---------------- 容错命中 ----------------

    @Test
    fun 错一个字仍能命中() {
        // 「北京天汽」→「北京天气怎么样」
        val s = FuzzyMatch.score("北京天汽", "北京天气怎么样")
        assertTrue("错字应该还能命中，实际 $s", s >= FuzzyMatch.MIN_SCORE)
        assertTrue("但要低于精确/子串命中，实际 $s", s < 0.90f)
        assertTrue(FuzzyMatch.matches("北京天汽", "北京天气怎么样"))
    }

    @Test
    fun 英文错字与字母颠倒仍能命中() {
        assertTrue(FuzzyMatch.matches("wrokspace", "the workspace root"))
    }

    @Test
    fun 漏一个字仍能命中() {
        val s = FuzzyMatch.score("北京气", "北京天气预报")
        assertTrue("漏字应该还能命中，实际 $s", s >= FuzzyMatch.MIN_SCORE)
        assertTrue(s < 0.90f)
    }

    @Test
    fun 跳字输入靠子序列兜底命中() {
        // 「wkspce」→「workspace」：编辑距离够不着（要删 3 个字符），只能靠子序列
        val s = FuzzyMatch.score("wkspce", "workspace")
        assertTrue("跳字输入应命中，实际 $s", s >= FuzzyMatch.MIN_SCORE)
        assertTrue("但只能是低分兜底，实际 $s", s < 0.5f)
    }

    @Test
    fun 词序颠倒不影响命中() {
        val 正序 = FuzzyMatch.score("北京 天气", "北京今天天气不错")
        val 倒序 = FuzzyMatch.score("天气 北京", "北京今天天气不错")
        assertTrue("倒序也应该高分命中，实际 $倒序", 倒序 >= 0.8f)
        assertEquals("多关键词打分应与词序无关", 正序, 倒序, 0.0001f)
    }

    @Test
    fun 只命中一半关键词的分数低于全命中() {
        val 全中 = FuzzyMatch.score("北京 天气", "北京今天天气不错")
        val 半中 = FuzzyMatch.score("北京 汇率", "北京今天天气不错")
        assertTrue("半命中=$半中 应低于全命中=$全中", 半中 < 全中)
        assertTrue("但半命中仍应算命中：$半中", 半中 >= FuzzyMatch.MIN_SCORE)
    }

    // ---------------- 不该命中的 ----------------

    @Test
    fun 完全无关的不命中() {
        assertEquals(0f, FuzzyMatch.score("量子力学", "北京今天天气怎么样"), 0f)
        assertFalse(FuzzyMatch.matches("量子力学", "北京今天天气怎么样"))
    }

    @Test
    fun 两字以内的词不容错() {
        // 否则「天气」会命中「天天向上」这类完全无关项
        assertEquals(0f, FuzzyMatch.score("天气", "天天向上"), 0f)
    }

    @Test
    fun 空查询或空目标不命中() {
        assertEquals(0f, FuzzyMatch.score("", "北京天气"), 0f)
        assertEquals(0f, FuzzyMatch.score("   ", "北京天气"), 0f)
        assertEquals(0f, FuzzyMatch.score("北京天气", ""), 0f)
    }

    // ---------------- bestScore / rank ----------------

    @Test
    fun 多字段取最高分不被长字段稀释() {
        val 标题 = "会议纪要"
        val 正文 = "今天讨论了很多事，其中提到了北京天气以及别的话题".repeat(3)
        val best = FuzzyMatch.bestScore("北京天气", 标题, 正文)
        assertTrue("多字段应取到正文里的高分命中，实际 $best", best >= 0.85f)
        assertTrue(best <= 分值上界)
        assertTrue("单看标题应该不命中", FuzzyMatch.score("北京天气", 标题) < FuzzyMatch.MIN_SCORE)
    }

    @Test
    fun rank按分排序并过滤不命中的() {
        val items = listOf("北京天气", "上海天气预报", "量子力学导论")
        val out = FuzzyMatch.rank("天气", items) { it }

        assertEquals("无关项要被过滤掉", 2, out.size)
        assertTrue("必须按分降序", out[0].score >= out[1].score)
        assertTrue(out.map { it.item }.containsAll(listOf("北京天气", "上海天气预报")))
    }

    @Test
    fun rank的limit生效() {
        val items = listOf("北京天气", "上海天气", "广州天气")
        assertEquals(1, FuzzyMatch.rank("天气", items, limit = 1) { it }.size)
        assertEquals(0, FuzzyMatch.rank("天气", items, limit = 0) { it }.size)
    }

    @Test
    fun rank空查询返回空() {
        assertTrue(FuzzyMatch.rank("", listOf("北京天气")) { it }.isEmpty())
    }

    private data class Note(val title: String, val body: String)

    @Test
    fun rankBy多字段取最高分() {
        val items = listOf(
            Note("会议纪要", "提到了北京天气"),
            Note("购物清单", "牛奶鸡蛋"),
        )
        val out = FuzzyMatch.rankBy("北京天气", items) { listOf(it.title, it.body) }

        assertEquals(1, out.size)
        assertEquals("会议纪要", out[0].item.title)
    }

    @Test
    fun 所有分值都落在0到1之间() {
        val queries = listOf("北京天气", "北京天汽", "wkspce", "量子力学", "天气 北京")
        val targets = listOf("北京天气", "北京今天天气不错", "workspace", "毫不相干的一段文字")
        for (q in queries) for (t in targets) {
            val s = FuzzyMatch.score(q, t)
            assertTrue("score($q, $t)=$s 越界", s >= 0f && s <= 分值上界)
        }
    }
}
