package com.arix.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 唤醒助手上下文组装：给「唤醒后语音对话」拼与主聊天同源的系统提示——环境(时间/电量) + 对话对象 +
 * 状态卡(人格延续) + 相关记忆 + 角色卡人设/背景 + 模型系统提示。让唤醒出来的是「带记忆的他」而非通用 bot。
 *
 * 独立实现、复用现成 manager（CharacterCardManager / MemoryManager / InteractionState / IdentityPrefs），
 * **不触碰 ChatScreen**。P0 仅注入上下文（无工具）；工具接入见后续 P0-5。
 */
object WakeAssistantContext {

    /** 环境上下文（唤醒是零星会话，给时间+电量；健康/天气只报关心点，含充电状态）。 */
    private fun envContext(context: Context): String {
        val timeStr = java.text.SimpleDateFormat("yyyy年M月d日 EEEE HH:mm", java.util.Locale.CHINA)
            .format(java.util.Calendar.getInstance().time)
        val bm = try { context.getSystemService(Context.BATTERY_SERVICE) as? android.os.BatteryManager } catch (_: Exception) { null }
        val level = try { bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1 } catch (_: Exception) { -1 }
        val charging = try { bm?.isCharging ?: false } catch (_: Exception) { false }
        val healthC = try { HealthSignals.concern(context) } catch (_: Exception) { null }
        val weatherC = try { WeatherSignals.concern(context) } catch (_: Exception) { null }
        return buildString {
            append("【环境】现在 ").append(timeStr)
            if (level in 0..20 && !charging) append("，电量只剩 ").append(level).append("%、没在充电")  // 低电没充才提，别报充电
            healthC?.let { append("，").append(it) }
            weatherC?.let { append("，").append(it) }
            append("。")
        }
    }

    /**
     * 拼系统提示。
     * @param cardId       当前绑定角色卡（决定人设 + 记忆归属 + 状态卡）
     * @param convId       当前会话（临时可为 null；影响状态卡按会话层）
     * @param userText     本轮用户话（用于检索相关记忆）
     * @param continuation 会话续接摘要（由调用方在开场时算一次传入，避免每轮重算）
     * @param apiSystemPrompt 模型配置里的系统提示（角色卡实体本身无 systemPrompt 字段）
     */
    suspend fun buildSystemPrompt(
        context: Context,
        cardId: Long?,
        convId: Long?,
        userText: String,
        continuation: String?,
        apiSystemPrompt: String?,
    ): String? = withContext(Dispatchers.IO) {   // 内含健康(可能读 Gadgetbridge SQLite)/记忆/角色卡 DB 读——别在唤醒的主线程跑，防 ANR
        val app = context.applicationContext
        val card = cardId?.let { runCatching { CharacterCardManager(app).getById(it) }.getOrNull() }
        val mem = runCatching { MemoryManager(app).queryRelevant(userText, 5, cardId) }.getOrDefault(emptyList())
        buildString {
            append(envContext(app)).append("\n\n")
            append("（你们现在是语音聊天，你说的话会被念出来。所以自然点、说短点，别用 Markdown 符号、代码块或 emoji，不然会被一个字一个字读出来。）\n\n")
            IdentityPrefs.userName(app).trim().takeIf { it.isNotBlank() && it != "我" }?.let {
                append("【对话对象】").append(it).append("（当前和你对话的人）\n\n")
            }
            runCatching { InteractionState.buildInjection(app, cardId, convId) }.getOrNull()
                ?.let { append(it).append("\n\n") }
            continuation?.takeIf { it.isNotBlank() }?.let { append(it).append("\n\n") }
            if (mem.isNotEmpty()) {
                append("【相关记忆】\n")
                mem.forEach { append("- ").append(it.title).append("：").append(it.content).append("\n") }
                append("\n")
            }
            card?.characterSetting?.takeIf { it.isNotBlank() }?.let { append(it.trim()).append("\n\n") }
            card?.worldBook?.takeIf { it.isNotBlank() }?.let { append("【背景故事】\n").append(it.trim()).append("\n\n") }
            apiSystemPrompt?.takeIf { it.isNotBlank() }?.let { append(it.trim()) }
            append(PromptLang.directive())   // 按界面语言回复（中文时为空）
        }.trim().ifBlank { null }
    }
}
