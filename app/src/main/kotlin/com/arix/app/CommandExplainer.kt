package com.arix.app

import android.content.Context
import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.model.ChatMessage
import kotlinx.coroutines.flow.MutableStateFlow

// ============================================================
// 从某个功能一键跳到「模型配置」页并自动展开对应用途卡。
// features 调 ConfigJump.request("translate")，MainActivity 观察后切页，ConfigPage 进入时 consume 展开。
// ============================================================
object ConfigJump {
    val target = MutableStateFlow<String?>(null)   // 用途 key，如 "translate"/"agent"
    fun request(purpose: String) { target.value = purpose }
    fun consume(): String? { val v = target.value; target.value = null; return v }
}

// ============================================================
// 命令翻译 —— 执行 shell/代码前那个「这条是干啥」按钮：用独立的「命令翻译」模型 + 独立上下文
// （固定 system、只喂这一条命令，不碰主对话历史）把命令解释成人话。未配翻译模型则回退对话模型。
// ============================================================
object CommandExplainer {
    const val NO_MODEL = "__NO_MODEL__"
    private val SYS = PromptLang.pick(
        "用大白话说清这条命令或代码到底要做什么、会动到什么、有没有风险。别执行它，也别扩写，几句话讲明白就行。",
        "Explain in plain words what this command or code does, what it touches, and whether it is risky. Don't execute it or expand it; just a few sentences."
    )

    suspend fun explain(context: Context, command: String): String {
        val cfg = CloudApiConfigManager(context).getConfigForPurpose("translate", capMaxTokens = 512) ?: return NO_MODEL   // 出参是一条命令/一句解释
        return try {
            var out = ""
            CloudApiClient(cfg).streamChat(
                listOf(ChatMessage("user", command)), SYS, 0, null,
                tools = null, onReasoningChunk = {}, onContentChunk = { out += it }
            )
            out.ifBlank { "（模型没给出解释）" }
        } catch (e: Exception) { "解释失败：${e.message}" }
    }
}
