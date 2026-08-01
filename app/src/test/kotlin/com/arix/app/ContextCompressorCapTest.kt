package com.arix.app

import com.arix.cloudapi.model.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ContextCompressor.capRecentMessages] —— 用户可配的「最多带最近 N 条消息」。
 * 纯函数，只依赖 ChatMessage 数据类，不需要 Context。
 *
 * 它自己不管 tool_calls/tool 配对（那是 [ContextCompressor.sanitizePairing] 的职责），
 * 这里额外验证两者接起来用（takeLast 切断配对 → sanitizePairing 兜底）不会漏成 400。
 */
class ContextCompressorCapTest {

    private fun user(text: String) = ChatMessage("user", text)
    private fun assistant(text: String) = ChatMessage("assistant", text)
    private fun assistantCalls(text: String, vararg ids: String) = ChatMessage(
        "assistant", text,
        toolCalls = ids.map { ChatMessage.ToolCallMsg(id = it, name = "some_tool", arguments = "{}") },
    )
    private fun toolReply(id: String?, text: String = "工具结果") =
        ChatMessage("tool", text, toolCallId = id)

    @Test
    fun maxCount小于等于0时不限原样返回() {
        val msgs = listOf(user("a"), assistant("b"), user("c"))
        assertEquals(msgs, ContextCompressor.capRecentMessages(msgs, 0))
        assertEquals(msgs, ContextCompressor.capRecentMessages(msgs, -1))
    }

    @Test
    fun 条数不超过maxCount时原样返回() {
        val msgs = listOf(user("a"), assistant("b"))
        assertEquals(msgs, ContextCompressor.capRecentMessages(msgs, 5))
    }

    @Test
    fun 超过maxCount时只留最近N条() {
        val msgs = (1..10).map { user("msg$it") }
        val out = ContextCompressor.capRecentMessages(msgs, 3)
        assertEquals(listOf("msg8", "msg9", "msg10"), out.map { it.content })
    }

    @Test
    fun 空列表不炸() {
        assertEquals(emptyList<ChatMessage>(), ContextCompressor.capRecentMessages(emptyList(), 3))
    }

    @Test
    fun 切断tool配对后过sanitizePairing不会留下400风险的孤儿() {
        // 条数上限把切口砍在 assistant(tool_calls) 与它的 tool 回复之间
        val msgs = listOf(
            user("第一问"),
            assistant("答复一"),
            user("第二问"),
            assistantCalls("我查一下", "c1"),   // 切口前
            toolReply("c1"),                     // 切口后——被单独裁进来会变孤儿
            assistant("查到了"),
        )
        val capped = ContextCompressor.capRecentMessages(msgs, 2)   // 只留最后两条：tool回复 + assistant
        assertEquals(listOf("tool", "assistant"), capped.map { it.role })

        val out = ContextCompressor.sanitizePairing(capped)
        assertTrue("孤儿 tool 回复必须被清掉，否则发出去 400", out.none { it.role == "tool" })
        assertEquals(listOf("assistant"), out.map { it.role })
    }

    @Test
    fun 切口不破坏配对时sanitizePairing不误伤() {
        val msgs = listOf(
            user("第一问"),
            assistantCalls("查天气", "c1"),
            toolReply("c1"),
            assistant("北京今天晴"),
        )
        val capped = ContextCompressor.capRecentMessages(msgs, 3)   // 留后三条：完整的一组调用+回复+总结
        val out = ContextCompressor.sanitizePairing(capped)
        assertEquals(capped, out)
    }
}
