package com.arix.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextWindowDefaults]：按模型名猜上下文窗口大小 + 占比计算。
 * 纯数字/字符串运算，无 Android 依赖。
 */
class ContextWindowDefaultsTest {

    // ---------------- guess ----------------

    @Test
    fun 空字符串回退默认值() {
        assertEquals(ContextWindowDefaults.FALLBACK, ContextWindowDefaults.guess(""))
        assertEquals(ContextWindowDefaults.FALLBACK, ContextWindowDefaults.guess("   "))
    }

    @Test
    fun 完全陌生的模型名回退默认值() {
        assertEquals(ContextWindowDefaults.FALLBACK, ContextWindowDefaults.guess("some-unknown-model-v9"))
    }

    @Test
    fun 大小写不敏感() {
        assertEquals(ContextWindowDefaults.guess("claude-3-5-sonnet"), ContextWindowDefaults.guess("CLAUDE-3-5-SONNET"))
    }

    @Test
    fun 常见模型给出合理窗口() {
        assertEquals(200_000, ContextWindowDefaults.guess("claude-3-5-sonnet-20241022"))
        assertEquals(128_000, ContextWindowDefaults.guess("gpt-4o-mini"))
        assertEquals(1_000_000, ContextWindowDefaults.guess("gemini-1.5-pro"))
        assertEquals(64_000, ContextWindowDefaults.guess("deepseek-chat"))
        assertEquals(131_072, ContextWindowDefaults.guess("qwen2.5-72b-instruct"))
    }

    @Test
    fun 更具体的关键字优先于通用词() {
        // gpt-4.1 不该被短的 "gpt-4" 规则提前吃掉
        assertEquals(1_000_000, ContextWindowDefaults.guess("gpt-4.1-mini"))
        assertEquals(8_192, ContextWindowDefaults.guess("gpt-4-0613"))
    }

    // ---------------- ratio / nearLimit ----------------

    @Test
    fun 占比按已用除以窗口() {
        assertEquals(0.5f, ContextWindowDefaults.ratio(50_000, 100_000), 0.0001f)
    }

    @Test
    fun 窗口未知时占比按0算不炸() {
        assertEquals(0f, ContextWindowDefaults.ratio(50_000, 0))
        assertEquals(0f, ContextWindowDefaults.ratio(50_000, -1))
    }

    @Test
    fun 超过告警线判定逼近上限() {
        assertTrue(ContextWindowDefaults.nearLimit(0.9f))
        assertTrue(ContextWindowDefaults.nearLimit(ContextWindowDefaults.WARN_RATIO))
        assertFalse(ContextWindowDefaults.nearLimit(0.5f))
    }

    @Test
    fun 可自定义告警阈值() {
        assertTrue(ContextWindowDefaults.nearLimit(0.6f, threshold = 0.5f))
        assertFalse(ContextWindowDefaults.nearLimit(0.4f, threshold = 0.5f))
    }

    // ---------------- formatTokens ----------------

    @Test
    fun 小数字原样显示() {
        assertEquals("999", ContextWindowDefaults.formatTokens(999))
        assertEquals("0", ContextWindowDefaults.formatTokens(0))
    }

    @Test
    fun 千级带k单位() {
        assertEquals("12.3k", ContextWindowDefaults.formatTokens(12_345))
    }

    @Test
    fun 百万级带M单位() {
        assertEquals("1.5M", ContextWindowDefaults.formatTokens(1_500_000))
    }
}
