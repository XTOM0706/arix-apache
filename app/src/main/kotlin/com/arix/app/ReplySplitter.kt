package com.arix.app

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 把一条 AI 回复拆成几条**连着弹出来**的小气泡。
 *
 * 项目里本来就有这个思路的实现：[WaifuProcessor.flowSentences]（陪伴模式，按句号/问号/换行断句）。
 * 所以这里**不另起一套**——[Mode.SENTENCE] 直接转调它，一个字都没重写；这个件只多做一件事：
 * [Mode.LINE]，**按行**拆（用户设置项「按行拆成多条气泡」要的就是行，不是句）。
 *
 * ⚠ 纯渲染层。落库的仍是**完整的一条** assistant 消息（调用方把整段 content 放进 conversationMsgs），
 * 这里产出的碎片只进 chatBubbles。改这里不会改历史、不会改发给模型的上下文。
 */
object ReplySplitter {

    enum class Mode {
        /** 按句断（陪伴/waifu 的老行为，走 [WaifuProcessor]）。 */
        SENTENCE,
        /** 按行断（新开关）。 */
        LINE,
    }

    /** 拆完的每一段依次 emit，中间隔 [delayMs]。段数 ≤1 时就是原样一条，观感与不拆相同。 */
    fun flow(fullText: String, mode: Mode, delayMs: Int): Flow<String> = when (mode) {
        Mode.SENTENCE -> WaifuProcessor.flowSentences(fullText, delayMs)
        Mode.LINE -> flowLines(fullText, delayMs)
    }

    private fun flowLines(fullText: String, delayMs: Int): Flow<String> = flow {
        val parts = splitLines(fullText)
        parts.forEachIndexed { i, p ->
            emit(p)
            if (i < parts.lastIndex && delayMs > 0) delay(delayMs.toLong())
        }
    }

    /**
     * 按行切段，但**围栏代码块整块不拆**。
     *
     * 逐行拆是这个功能的字面意思，可是把 ```kotlin … ``` 拆成一行一个气泡，代码高亮、缩进、语言标记
     * 会全部作废，用户看到的是十几个只有一行字的碎片——那不是"聊天感"，是把回答毁了。所以：
     *  · 围栏内（含 ``` 那两行本身）一律攒进同一段；
     *  · 空行 = 段落分隔，收一段（列表/表格这类连续行因此会连在一起，不会被逐行崩开）；
     *  · 其余每一行各成一段。
     * 拆不出东西（整段是空白）时原样返回整段，绝不 emit 空气泡。
     */
    internal fun splitLines(fullText: String): List<String> {
        val out = ArrayList<String>()
        val buf = StringBuilder()
        var inFence = false
        fun flush() {
            val s = buf.toString().trim()
            if (s.isNotEmpty()) out.add(s)
            buf.setLength(0)
        }
        for (line in fullText.lines()) {
            val isFenceLine = line.trimStart().startsWith("```")
            if (isFenceLine) {
                // 开围栏前先把攒着的普通行收掉，免得代码块和上一行黏成一段
                if (!inFence) flush()
                inFence = !inFence
                buf.append(line).append('\n')
                if (!inFence) flush()   // 刚闭合：整块代码单独成一段
                continue
            }
            if (inFence) { buf.append(line).append('\n'); continue }
            if (line.isBlank()) { flush(); continue }
            flush()
            out.add(line.trim())
        }
        flush()
        return if (out.isEmpty()) listOf(fullText) else out
    }
}
