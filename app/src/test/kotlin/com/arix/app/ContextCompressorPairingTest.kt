package com.arix.app

import com.arix.cloudapi.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextCompressor.sanitizePairing] —— 踩过的坑：assistant 带 tool_calls 却缺配对的 tool 回复，
 * 或者 tool 回复找不到归属，请求发出去直接 400。删消息 / 中途打断都会留下这种残缺序列。
 *
 * 纯函数，只依赖 ChatMessage 数据类，不需要 Context / Room / 网络。
 */
class ContextCompressorPairingTest {

    private fun user(text: String) = ChatMessage("user", text)
    private fun assistant(text: String) = ChatMessage("assistant", text)
    private fun assistantCalls(text: String, vararg ids: String) = ChatMessage(
        "assistant", text,
        toolCalls = ids.map { ChatMessage.ToolCallMsg(id = it, name = "some_tool", arguments = "{}") },
    )
    private fun toolReply(id: String?, text: String = "工具结果") =
        ChatMessage("tool", text, toolCallId = id)

    @Test
    fun 空列表不炸也不改动() {
        assertEquals(emptyList<ChatMessage>(), ContextCompressor.sanitizePairing(emptyList()))
    }

    @Test
    fun 没有工具调用的普通对话原样返回() {
        val msgs = listOf(user("你好"), assistant("你好呀"), user("再见"))
        assertEquals(msgs, ContextCompressor.sanitizePairing(msgs))
    }

    @Test
    fun 正常配对不受影响() {
        val msgs = listOf(
            user("查一下天气"),
            assistantCalls("我查一下", "c1", "c2"),
            toolReply("c1"),
            toolReply("c2"),
            assistant("北京今天晴"),
        )
        assertEquals(msgs, ContextCompressor.sanitizePairing(msgs))
    }

    @Test
    fun 悬空的assistant消息应被剔除() {
        // c2 的 tool 回复缺失（中途被打断/被删）——整条 assistant 都不能发，否则 400
        val msgs = listOf(
            user("查一下天气"),
            assistantCalls("我查一下", "c1", "c2"),
            toolReply("c1"),
            user("算了，说点别的"),
        )
        val out = ContextCompressor.sanitizePairing(msgs)

        assertEquals(listOf("user", "user"), out.map { it.role })
        assertTrue("带 tool_calls 的 assistant 必须被剔除", out.none { !it.toolCalls.isNullOrEmpty() })
        // 被剔除的 assistant 的那条 tool 回复也不能留下，否则它变成新的孤儿
        assertTrue("配套的 tool 回复也要一起剔除", out.none { it.role == "tool" })
    }

    @Test
    fun 孤儿tool回复应被剔除() {
        val msgs = listOf(
            user("查一下天气"),
            assistantCalls("我查一下", "c1"),
            toolReply("c1", "晴"),
            toolReply("c99", "没人认领的结果"),
            assistant("北京今天晴"),
        )
        val out = ContextCompressor.sanitizePairing(msgs)

        assertEquals(4, out.size)
        assertEquals(listOf("c1"), out.filter { it.role == "tool" }.map { it.toolCallId })
        assertTrue("正常配对的那条要留下", out.any { it.role == "tool" && it.content == "晴" })
    }

    @Test
    fun 缺toolCallId的tool回复应被剔除() {
        val msgs = listOf(
            user("查一下"),
            assistantCalls("我查一下", "c1"),
            toolReply("c1"),
            toolReply(null, "没有 id 的结果"),
        )
        val out = ContextCompressor.sanitizePairing(msgs)

        assertEquals(3, out.size)
        assertTrue(out.none { it.content == "没有 id 的结果" })
    }

    @Test
    fun 多个悬空assistant全部剔除且不误伤正常轮() {
        val msgs = listOf(
            user("第一问"),
            assistantCalls("调A", "a1"),           // a1 没有回复 → 悬空
            assistantCalls("调B", "b1"),           // b1 有回复 → 正常
            toolReply("b1"),
            assistant("答复"),
            user("第二问"),
            assistantCalls("调C", "c1", "c2"),      // c2 缺回复 → 悬空
            toolReply("c1"),
        )
        val out = ContextCompressor.sanitizePairing(msgs)

        val keptCallIds = out.flatMap { it.toolCalls.orEmpty() }.map { it.id }
        assertEquals(listOf("b1"), keptCallIds)
        assertEquals(listOf("b1"), out.filter { it.role == "tool" }.map { it.toolCallId })
        // 普通消息一条都不能丢
        assertEquals(listOf("第一问", "答复", "第二问"), out.filter { it.toolCalls.isNullOrEmpty() && it.role != "tool" }.map { it.content })
    }

    /**
     * 回归：**整段没有任何 assistant 带 tool_calls，但有孤儿 tool 消息**时也必须清掉。
     * 这是"用户删掉了那条 assistant、只剩 tool 回复"的形态，正是最容易造出来的 400。
     * 早退条件写成只看 tool_calls 时，这条会红。
     */
    @Test
    fun 没有任何toolCalls时的孤儿tool也要剔除() {
        val msgs = listOf(
            ChatMessage("user", "在吗"),
            ChatMessage("tool", "某个工具的结果", toolCallId = "orphan-1"),
            ChatMessage("assistant", "在的"),
        )
        val out = ContextCompressor.sanitizePairing(msgs)
        assertEquals(2, out.size)
        assertTrue(out.none { it.role == "tool" })
    }
}
