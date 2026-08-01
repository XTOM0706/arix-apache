package com.arix.app

import com.arix.cloudapi.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextCompressor.trimOldToolResults] / [ContextCompressor.evictOldImages]。
 *
 * 两条都是「省 token 不能省到正在用的数据头上」的护栏：
 *  - 整段对话没堆起来（<16000 字）就一个字都不裁；
 *  - 超阈值才裁，且**头尾都留**（答案常在尾部）；
 *  - 最后一条 user 之后的工具结果 = 当前这一轮，永不裁；
 *  - 老图只抽 base64、必须留一句「别凭印象描述」的占位（悄悄抽走会诱发自信幻觉）。
 */
class ContextCompressorTrimTest {

    /** 单条约 3012 字，足够超过 cap(1200) 触发头尾截断。 */
    private fun bigTool(tag: String) = "起始标记$tag" + "x".repeat(3000) + "结尾标记$tag"

    private fun tool(text: String, id: String) = ChatMessage("tool", text, toolCallId = id)

    // ---------------- trimOldToolResults ----------------

    @Test
    fun 工具结果条数不超过keepRecent时不裁() {
        val msgs = listOf(
            ChatMessage("user", "问题"),
            tool(bigTool("A"), "a"),
            tool(bigTool("B"), "b"),
            tool(bigTool("C"), "c"),
        )
        assertEquals(msgs, ContextCompressor.trimOldToolResults(msgs, keepRecent = 3))
    }

    @Test
    fun 整段对话不到阈值时一个字都不裁() {
        // 5 条工具结果（> keepRecent=3），但全对话总字数 ~10000 < 16000 的触发门槛
        val body = "y".repeat(2000)
        val msgs = listOf(ChatMessage("user", "问题")) +
            (1..5).map { tool(body + it, "t$it") } +
            ChatMessage("user", "追问")
        assertTrue("本用例前提：总字数必须低于触发门槛", msgs.sumOf { it.content.length } < 16000)

        assertEquals(msgs, ContextCompressor.trimOldToolResults(msgs, keepRecent = 3))
    }

    @Test
    fun 超阈值后裁旧工具结果且头尾都留() {
        val msgs = listOf(
            ChatMessage("user", "第一轮问题"),                 // 0  ← 不是最后一条 user
            tool(bigTool("T1"), "t1"),                        // 1  可裁
            tool(bigTool("T2"), "t2"),                        // 2  可裁
            tool(bigTool("T3"), "t3"),                        // 3  可裁
            tool(bigTool("T4"), "t4"),                        // 4  可裁
            tool(bigTool("T5"), "t5"),                        // 5  可裁
            tool(bigTool("T6"), "t6"),                        // 6  最近 3 条之一 → 保
            ChatMessage("user", "第二轮问题"),                 // 7  ← 最后一条 user
            tool(bigTool("T7"), "t7"),                        // 8  当前轮 → 保
            tool(bigTool("T8"), "t8"),                        // 9  当前轮 → 保
        )
        assertTrue("本用例前提：总字数必须越过触发门槛", msgs.sumOf { it.content.length } >= 16000)

        val out = ContextCompressor.trimOldToolResults(msgs, keepRecent = 3)

        assertEquals(msgs.size, out.size)
        assertEquals(msgs.map { it.role }, out.map { it.role })

        // 旧的被裁了
        val trimmed = out[1].content
        assertNotEquals(msgs[1].content, trimmed)
        assertTrue("裁完必须变短", trimmed.length < msgs[1].content.length)
        assertTrue("头要留住", trimmed.startsWith("起始标记T1"))
        assertTrue("尾要留住（答案常在尾部）", trimmed.endsWith("结尾标记T1"))
        assertTrue("要留一句说明", trimmed.contains("已省略"))

        // 最近 keepRecent 条工具结果原样
        assertEquals(msgs[6].content, out[6].content)
        // 当前这一轮（最后一条 user 之后）原样——模型刚取回、正要用的数据
        assertEquals(msgs[8].content, out[8].content)
        assertEquals(msgs[9].content, out[9].content)
        // 非 tool 消息一律不动
        assertEquals(msgs[0].content, out[0].content)
        assertEquals(msgs[7].content, out[7].content)
    }

    @Test
    fun 当前这一轮的工具结果永不裁() {
        // 所有工具结果都在最后一条 user 之后 = 全属于当前轮，即便总量已越过门槛也一条都不裁
        val msgs = listOf(ChatMessage("user", "这一轮的问题")) +
            (1..8).map { tool(bigTool("T$it"), "t$it") }
        assertTrue(msgs.sumOf { it.content.length } >= 16000)

        assertEquals(msgs, ContextCompressor.trimOldToolResults(msgs, keepRecent = 3))
    }

    @Test
    fun 短于cap的旧工具结果不动() {
        val short = "很短的结果"
        val msgs = listOf(
            ChatMessage("user", "第一轮"),
            tool(short, "s1"),
            tool(bigTool("A"), "a"),
            tool(bigTool("B"), "b"),
            tool(bigTool("C"), "c"),
            tool(bigTool("D"), "d"),
            tool(bigTool("E"), "e"),
            tool(bigTool("F"), "f"),
            ChatMessage("user", "第二轮"),
        )
        assertTrue(msgs.sumOf { it.content.length } >= 16000)

        val out = ContextCompressor.trimOldToolResults(msgs, keepRecent = 3)
        assertEquals(short, out[1].content)
        assertNotEquals(msgs[2].content, out[2].content)   // 长的确实被裁了，说明门槛已过
    }

    // ---------------- evictOldImages ----------------

    private fun userWithImages(text: String, vararg imgs: String) =
        ChatMessage("user", text, images = imgs.toList())

    @Test
    fun 没有图片时原样返回() {
        val msgs = listOf(ChatMessage("user", "你好"), ChatMessage("assistant", "你好"))
        assertEquals(msgs, ContextCompressor.evictOldImages(msgs))
    }

    @Test
    fun 带图回合不超过keepTurns时原样返回() {
        val msgs = listOf(
            userWithImages("看图一", "b64-A"),
            ChatMessage("assistant", "好的"),
            userWithImages("看图二", "b64-B"),
        )
        assertEquals(msgs, ContextCompressor.evictOldImages(msgs, keepTurns = 2))
    }

    @Test
    fun 老图被抽掉base64并留下占位文案() {
        val msgs = listOf(
            userWithImages("看看这两张", "b64-A1", "b64-A2"),   // 0 ← 老图，驱逐
            ChatMessage("assistant", "看到了"),                 // 1
            userWithImages("再看这张", "b64-B1"),               // 2 ← 保
            ChatMessage("assistant", "嗯"),                     // 3
            userWithImages("最后这张", "b64-C1"),               // 4 ← 保
        )
        val out = ContextCompressor.evictOldImages(msgs, keepTurns = 2)

        assertEquals(msgs.size, out.size)
        assertNull("老图的 base64 必须被抽掉", out[0].images)
        assertTrue("原文要保留", out[0].content.startsWith("看看这两张"))
        assertTrue("要说清原来有几张", out[0].content.contains("原本有 2 张图片"))
        assertTrue(
            "必须明说别凭印象描述——悄悄抽走会诱发模型自信幻觉",
            out[0].content.contains("不要凭印象"),
        )

        // 最近 keepTurns 个带图回合保留原图
        assertEquals(listOf("b64-B1"), out[2].images)
        assertEquals("再看这张", out[2].content)
        assertEquals(listOf("b64-C1"), out[4].images)
        assertEquals("最后这张", out[4].content)
        // 不带图的消息一律不动
        assertEquals(msgs[1], out[1])
        assertEquals(msgs[3], out[3])
    }

    @Test
    fun keepTurns为1时只留最后一个带图回合() {
        val msgs = listOf(
            userWithImages("图一", "b64-A"),
            userWithImages("图二", "b64-B"),
            userWithImages("图三", "b64-C"),
        )
        val out = ContextCompressor.evictOldImages(msgs, keepTurns = 1)

        assertNull(out[0].images)
        assertNull(out[1].images)
        assertEquals(listOf("b64-C"), out[2].images)
        assertTrue(out[0].content.contains("原本有 1 张图片"))
    }
}
