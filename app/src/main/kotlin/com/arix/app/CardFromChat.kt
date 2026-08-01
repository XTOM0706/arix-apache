package com.arix.app

import android.content.Context
import com.arix.cloudapi.model.ChatMessage
import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.CloudApiConfig
import org.json.JSONObject

/**
 * 从一段「用户在其它 AI 里的公开聊天」提炼出角色卡 + 记忆。
 * 场景：用户在别的 AI 上养了个喜欢的角色/对话，把对话文本贴进来 → 这里让模型提炼出这个角色的人设、开场白、
 * 背景，做成 Arix 角色卡；顺带把对话里值得长期记住的、关于用户的事实抽成记忆，绑到这张新卡上。
 */
object CardFromChat {
    data class Result(val ok: Boolean, val cardId: Long, val name: String, val memories: Int, val message: String)

    suspend fun generate(context: Context, config: CloudApiConfig, chatText: String, onProgress: ((String) -> Unit)? = null): Result {
        val text = chatText.trim()
        if (text.length < 20) return Result(false, 0, "", 0, "对话内容太短，贴多一点")
        onProgress?.invoke("分析对话，提炼角色…")
        // 提示词：只给情境和要什么，不塞例子、不写人机味的元指令（见项目提示词风格约定）。
        val prompt = PromptLang.pick(
            buildString {
                append("下面是用户在另一个 AI 上的一段对话。请据此提炼出对话里那个 AI 角色，做成一张角色卡。\n")
                append("仔细看它的人设、性格、说话风格、背景设定，尽量还原。再从对话里挑出值得长期记住的、关于用户本人的事实（喜好/身份/在意的事/称呼等）。\n")
                append("只输出一个 JSON，键如下：\n")
                append("name（角色名，简短）\n")
                append("description（一句话简介）\n")
                append("characterSetting（人设正文：性格、背景、说话风格、行为习惯，写得具体可用，第二人称写给这个角色）\n")
                append("openingStatement（这个角色的开场白，符合它的语气）\n")
                append("tone（语气，几个词）\n")
                append("worldBook（背景/世界观设定，没有就留空）\n")
                append("memories（数组，每条一句关于用户本人的事实；没有就空数组）\n\n")
                append("对话内容：\n")
            },
            buildString {
                append("Here is a conversation between a user and another AI. Extract the AI character from the conversation and turn it into a character card.\n")
                append("Closely examine its persona, personality, speaking style, and background setting, and reconstruct them as faithfully as possible. Then pick out facts about the user worth remembering long-term (preferences / identity / things they care about / how they are addressed, etc.).\n")
                append("Output only one JSON with the following keys:\n")
                append("name (character name, short)\n")
                append("description (one-sentence summary)\n")
                append("characterSetting (persona body: personality, background, speaking style, habits, written concretely and usable, written in second person to this character)\n")
                append("openingStatement (this character's opening line, matching its tone)\n")
                append("tone (tone, a few words)\n")
                append("worldBook (background/worldbuilding, leave empty if none)\n")
                append("memories (array, each item one sentence of a fact about the user; empty array if none)\n\n")
                append("Conversation content:\n")
            },
        ) + text.take(12000)
        var out = ""
        try {
            CloudApiClient(config).streamChat(
                messages = listOf(ChatMessage("user", prompt)),
                enableThinking = 0, onReasoningChunk = {}, onContentChunk = { out += it },
            )
        } catch (e: kotlinx.coroutines.CancellationException) { throw e
        } catch (e: Exception) { return Result(false, 0, "", 0, "生成失败：${e.message}") }

        val json = extractJson(out) ?: return Result(false, 0, "", 0, "没能从模型输出里解析出角色卡，换个模型或再试一次")
        val name = json.optString("name").trim().ifBlank { "导入的角色" }
        onProgress?.invoke("创建角色卡…")
        val cardId = try {
            CharacterCardManager(context).create(
                name = name,
                description = json.optString("description").trim(),
                characterSetting = json.optString("characterSetting").trim(),
                openingStatement = json.optString("openingStatement").trim(),
                tone = json.optString("tone").trim(),
                worldBook = json.optString("worldBook").trim(),
            )
        } catch (e: Exception) { return Result(false, 0, "", 0, "创建角色卡失败：${e.message}") }

        var memN = 0
        runCatching {
            val mem = MemoryManager(context)
            json.optJSONArray("memories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val t = arr.optString(i).trim()
                    if (t.isNotBlank()) { mem.add(title = t.take(24), content = t, source = "auto_extract", characterCardId = cardId, type = "fact"); memN++ }
                }
            }
        }
        onProgress?.invoke("完成")
        return Result(true, cardId, name, memN, "已生成角色卡「$name」" + if (memN > 0) "，并存入 $memN 条记忆" else "")
    }

    /** 从可能夹着解释/代码块的模型输出里抠出第一个平衡的 JSON 对象。 */
    private fun extractJson(s: String): JSONObject? {
        runCatching { return JSONObject(s.trim()) }
        val start = s.indexOf('{'); if (start < 0) return null
        var depth = 0; var inStr = false; var esc = false
        for (i in start until s.length) {
            val c = s[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false }
            else when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) return runCatching { JSONObject(s.substring(start, i + 1)) }.getOrNull() } }
        }
        return null
    }
}
