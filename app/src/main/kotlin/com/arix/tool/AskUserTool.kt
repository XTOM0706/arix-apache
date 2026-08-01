package com.arix.tool

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
// ask_user —— AI 拿不准用户想要什么时，先问清楚再动手。
// 给一句问题 + 几个可能的方向，用户点一个或自己打字回，答案回传给 AI 继续。
// 走 CompletableDeferred + StateFlow：ChatScreen 观察 pending 弹提问卡，用户回应后 resolve。
// ============================================================
data class AskUserRequest(
    val question: String,
    val options: List<String>,
    val deferred: CompletableDeferred<String>
)

object AskUserBus {
    val pending = MutableStateFlow<AskUserRequest?>(null)
    /** 用户点了某个选项或自己打了字。 */
    fun answer(text: String) {
        pending.value?.deferred?.complete(text)
        pending.value = null
    }
    /** 用户跳过不答。 */
    fun skip() {
        pending.value?.deferred?.complete("")
        pending.value = null
    }
}

class AskUserTool : Tool {

    override val name = "ask_user"
    override val description = "拿不准用户想要什么就先问，别猜着做。给一句问题，再给几个可能的方向让他挑，也可以让他自己说。等他回答了再继续。需求含糊、有好几种理解、或者要用户拍板方向的时候用它。"

    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Ask before guessing. Give one question plus a few directions to pick from; the user can also answer freely. Wait for the answer, then continue. Use it when the request is vague, reads more than one way, or the direction is the user's call."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("question", JSONObject().apply {
                put("type", "string")
                put("description", "be specific about what you are unsure of")
            })
            put("options", JSONObject().apply {
                put("type", "array")
                put("items", JSONObject().apply { put("type", "string") })
                put("description", "choices to pick from; leave empty for a free answer")
            })
        })
        put("required", JSONArray(listOf("question")))
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        val question = params.optString("question").trim()
        if (question.isBlank()) return ToolResult("需要 question（要问用户什么）", isError = true)
        val optArr = params.optJSONArray("options")
        val options = if (optArr != null)
            (0 until optArr.length()).mapNotNull { optArr.optString(it).takeIf { s -> s.isNotBlank() } }.take(6)
        else emptyList()

        val d = CompletableDeferred<String>()
        AskUserBus.pending.value = AskUserRequest(question, options, d)
        return try {
            val answer = withTimeoutOrNull(120_000) { d.await() }   // 2 分钟没回应就放行 AI 自行判断，别把一轮(尤其语音)卡太久
            when {
                answer == null -> ToolResult("用户暂时没回答。按你现在最合理的理解继续，别再空等。")
                answer.isBlank() -> ToolResult("用户跳过了这个问题。按你的判断继续。")
                else -> ToolResult("用户回答：$answer")
            }
        } finally {
            if (AskUserBus.pending.value?.deferred === d) AskUserBus.pending.value = null
        }
    }
}
