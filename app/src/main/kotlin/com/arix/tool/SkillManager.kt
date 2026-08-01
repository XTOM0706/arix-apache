package com.arix.tool

import org.json.JSONArray
import org.json.JSONObject

data class SkillDef(
    val name: String,
    val description: String,
    val category: String = "general",
    val systemPromptAddition: String = "",
    val tools: List<Tool> = emptyList()
)

object SkillManager {
    private val skills = mutableMapOf<String, SkillDef>()
    private var enabledSkills = mutableSetOf<String>()
    /** 技能名 → 它的工具**实际注册名**（撞内置名会被改成 ext_xxx，按 tool.name 反注册会漏）。 */
    private val registeredNames = mutableMapOf<String, List<String>>()

    fun register(skill: SkillDef) {
        skills[skill.name] = skill
    }

    fun enable(name: String): List<Tool> {
        if (enabledSkills.add(name)) {
            val skill = skills[name] ?: return emptyList()
            registeredNames[name] = skill.tools.map { ToolManager.register(it) }
            return skill.tools
        }
        return emptyList()
    }

    fun disable(name: String): List<Tool> {
        if (enabledSkills.remove(name)) {
            val skill = skills[name] ?: return emptyList()
            (registeredNames.remove(name) ?: skill.tools.map { it.name })
                .forEach { ToolManager.unregister(it) }
            return skill.tools
        }
        return emptyList()
    }

    fun isEnabled(name: String): Boolean = name in enabledSkills

    val enabledSystemPrompt: String
        get() = buildString {
            for (name in enabledSkills.sorted()) {
                val skill = skills[name] ?: continue
                if (skill.systemPromptAddition.isNotBlank()) {
                    append(skill.systemPromptAddition).append("\n")
                }
            }
        }

    fun getEnabledSkills(): List<SkillDef> =
        enabledSkills.mapNotNull { skills[it] }

    fun getAllSkills(): List<SkillDef> = skills.values.toList()

    fun toggleSkill(name: String): Boolean {
        return if (isEnabled(name)) {
            disable(name); false
        } else {
            enable(name); true
        }
    }
}

fun createBuiltInSkills(): List<SkillDef> = listOf(
    SkillDef(
        name = "coder",
        description = "编程与代码分析能力",
        category = "technical",
        systemPromptAddition = "你是一个专业的编程助手。分析代码时请给出具体建议和改进方案。回答技术问题时要提供示例代码。"
    ),
    SkillDef(
        name = "translator",
        description = "翻译与多语言支持",
        category = "language",
        systemPromptAddition = "你是一个专业翻译助手。翻译时要保持原文风格和语调，尽可能准确传达原文含义。"
    ),
    SkillDef(
        name = "analyst",
        description = "数据与逻辑分析",
        category = "technical",
        systemPromptAddition = "你是一个数据分析专家。面对问题时要进行系统性的逻辑分析，考虑多个角度，给出结构化的回答。"
    ),
    SkillDef(
        name = "writer",
        description = "写作与内容创作",
        category = "creative",
        systemPromptAddition = "你是一个专业写作助手。创作内容时要注重文笔、结构和可读性。"
    ),
    SkillDef(
        name = "researcher",
        description = "研究与信息检索",
        category = "research",
        systemPromptAddition = "你是一个研究助手。回答问题时优先查找相关信息，提供有据可查的回答，必要时使用搜索工具。"
    )
)
