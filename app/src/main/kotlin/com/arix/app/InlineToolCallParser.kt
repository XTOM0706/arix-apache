package com.arix.app

import com.arix.cloudapi.model.ChatMessage
import org.json.JSONObject

/**
 * 内联文本格式工具调用的兜底解析。
 *
 * 有些模型不走原生结构化 `tool_calls`（Nemotron、Hermes 系、部分经第三方端点路由的开源模型），
 * 而是把工具调用当**文本**吐在正文里，例如：
 *   <toolcall><function=volume><parameter=level>100</parameter><parameter=type>notification</parameter></function></toolcall>
 * 或 Hermes/Qwen 式：<tool_call>{"name":"x","arguments":{...}}</tool_call>
 *
 * 聊天工具循环只认原生 `tool_calls` → 这类会漏成可见文本、工具永不执行（用户看到「tool use failed」）。
 * 这里在原生为空时把正文解析成真正的工具调用；[strip] 把这段文本从正文里剥掉，别当文字显示/回灌给模型。
 */
object InlineToolCallParser {

    // <function=NAME> … </function>（可含在外层 <toolcall> 里，也兼容裸 <function=…>）
    private val functionBlock = Regex(
        "<function=([A-Za-z0-9_.\\-]+)\\s*>([\\s\\S]*?)</function\\s*>",
        RegexOption.IGNORE_CASE,
    )
    // <parameter=KEY>VALUE</parameter>
    private val paramItem = Regex(
        "<parameter=([A-Za-z0-9_.\\-]+)\\s*>([\\s\\S]*?)</parameter\\s*>",
        RegexOption.IGNORE_CASE,
    )
    // Hermes/Qwen 式：<tool_call>{json}</tool_call>（本身是明确的调用标记，不易在正文误现）
    private val jsonToolCall = Regex(
        "<tool_call>\\s*([\\s\\S]*?)\\s*</tool_call\\s*>",
        RegexOption.IGNORE_CASE,
    )
    // <toolcall>…</toolcall> 外层包裹：**只认**包在这里面的 <function=…>——别把正文里裸讲解的
    // <function=…> 当真调用执行（模型给用户解释工具语法时会出现，误执行还被 strip 隐藏，很危险）。
    private val toolcallWrap = Regex("<toolcall\\s*>([\\s\\S]*?)</toolcall\\s*>", RegexOption.IGNORE_CASE)
    // 代码块 ```…``` / `…`：里面的工具语法是给人看的示例/复述，先挖空，别误执行。
    private val codeFence = Regex("```[\\s\\S]*?```|`[^`\\n]*`")

    /** 解析正文里的内联工具调用。round 只用来给生成的 id 去重。空则返回空表。 */
    fun parse(content: String, round: Int = 0): List<ChatMessage.ToolCallMsg> {
        if (content.isBlank()) return emptyList()
        val scan = codeFence.replace(content, " ")   // 代码块里的工具语法是示例/复述，挖空免误执行
        val out = ArrayList<ChatMessage.ToolCallMsg>()

        // ① 只认 <toolcall>…</toolcall> 包裹里的 <function=…><parameter=…>（Nemotron 实测就带 <toolcall> 外层）
        toolcallWrap.findAll(scan).forEach { w ->
            functionBlock.findAll(w.groupValues[1]).forEach { m ->
                val name = m.groupValues[1].trim()
                if (name.isBlank()) return@forEach
                val args = JSONObject()
                paramItem.findAll(m.groupValues[2]).forEach { p ->
                    args.put(p.groupValues[1].trim(), coerce(p.groupValues[2].trim()))
                }
                out.add(ChatMessage.ToolCallMsg("inline_${round}_${out.size}", name, args.toString()))
            }
        }
        if (out.isNotEmpty()) return out

        // ② Hermes/Qwen 显式 <tool_call>{json}</tool_call>
        jsonToolCall.findAll(scan).forEach { m ->
            runCatching {
                val o = JSONObject(m.groupValues[1].trim())
                val name = o.optString("name").trim()
                if (name.isBlank()) return@runCatching
                val a = o.opt("arguments")
                val argsStr = when (a) {
                    is JSONObject -> a.toString()
                    is String -> a.ifBlank { "{}" }
                    else -> "{}"
                }
                out.add(ChatMessage.ToolCallMsg("inline_${round}_${out.size}", name, argsStr))
            }
        }
        return out
    }

    /** 把已解析的内联工具调用文本从正文里剥掉（整块 <toolcall>…</toolcall> / <tool_call>…），剩下的才给人看。 */
    fun strip(content: String): String {
        var s = content
        s = toolcallWrap.replace(s, "")
        s = jsonToolCall.replace(s, "")
        return s.trim()
    }

    // 只把 true/false 还原成布尔；数值一律**保留字符串**——避免把 "0123"/长号码/验证码丢前导零或溢出精度
    // （工具侧 JSONObject.getInt/optInt 会自动把 "100" 这种数字串强转，功能不受影响）。
    private fun coerce(v: String): Any = when {
        v.equals("true", ignoreCase = true) -> true
        v.equals("false", ignoreCase = true) -> false
        else -> v
    }
}
