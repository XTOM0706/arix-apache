package com.arix.tool

import android.content.Context
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * 已装市场技能（SKILL.md）的**按需读取**入口。
 *
 * 为什么有它：系统提示里原来把每个已启用技能的正文整篇拼进去，每轮都发一遍——装 3 个技能 ≈ 每轮固定
 * 多付约 3k token（我们**全量工具 schema 才 9,373 token**），而且超长部分被静默丢弃，模型连"还有别的
 * 技能"都不知道。现在系统提示只留一行行索引（见 [OperitCompat.enabledSkillInjection]），正文由模型
 * 判断"这活儿要用哪个"之后调这里取一次。摊下来：3 个技能约省 85% 的常驻开销，而正文只在真要用时付一次。
 *
 * 正文**已经在内存里**（[OperitCompat.OperitPackage.systemPromptAddition]，扫包时就读进来了），
 * 这里不再碰磁盘，所以没有 IO 线程切换。
 *
 * ⚠ 工具只有一个、带两种用法（列表 / 读正文），不另开 skill_list——本项目要"1 用多、数量精简防幻觉"。
 */
class SkillReadTool(private val context: Context) : Tool {
    override val name = "skill_read"

    // 给人看的（权限页/功能包页）：中文
    override val description = "读取已安装技能(SKILL.md)的完整正文。系统提示里只放技能索引，正文按需读取——省下每轮的常驻 token。不带 id 时列出全部已启用技能。"

    // 给模型看的：英文（每轮随 schema 发出去，中文要交 28~43% 的"中文税"）
    override val llmDescription = "Read the full doc of one installed skill, by the id shown in the [Available skills] index. " +
        "That index only carries one-line summaries, so call this once before acting on a skill to get its actual instructions. " +
        "Omit id to list every enabled skill (use this when the index says more skills exist than it listed). " +
        "Unrelated to the `skill` tool, which records and replays UI actions."

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("id", JSONObject().apply {
                put("type", "string")
                put("description", "Skill id from the [Available skills] index. Omit to list all enabled skills instead.")
            })
        })
        // required 故意为空：不带 id = 列表模式，这是"超限降级"路径的入口，不能被参数校验挡掉。
        put("required", org.json.JSONArray())
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        return try {
            val raw = params.optString("id", "").trim()
            val skills = OperitCompat.enabledSkills(context)
            if (skills.isEmpty()) return ToolResult(
                "No skills installed or all disabled. Nothing to read.", isError = true, failKind = "not_found")

            // 不带 id → 全表。索引超限时模型就是靠这条路把"没列出来的那几个"捞回来，所以这里不设条数上限。
            if (raw.isBlank()) return ToolResult(buildString {
                append("Enabled skills (id | name | summary). Call skill_read(id) to load one:\n")
                skills.forEach { append("- ").append(it.id).append(" | ").append(oneLine(it.name))
                    .append(" | ").append(oneLine(it.description.ifBlank { OperitCompat.skillBody(it).take(200) }).take(180)).append('\n') }
            }.trimEnd())

            val hit = resolve(raw, skills) ?: return ToolResult(
                "No enabled skill matches id \"$raw\". Available ids: " + skills.joinToString(", ") { it.id } +
                    ". (A skill the user turned off is not readable.)",
                isError = true, failKind = "not_found")

            val body = OperitCompat.skillBody(hit)
            if (body.isBlank()) return ToolResult(
                "Skill \"${hit.name}\" has an empty doc.", isError = true, failKind = "not_found")
            val truncated = body.length > OperitCompat.SKILL_BODY_CAP
            val text = body.take(OperitCompat.SKILL_BODY_CAP) + if (truncated) "\n…(truncated)" else ""

            // 技能正文是**第三方内容**（用户从市场/GitHub 装来的），跟抓来的网页同级：里面可能夹带冲模型来的
            // 指令。走项目统一的围栏 UntrustedWeb.fence，口径与 open_page/web_search 一致。
            // 先抹掉正文里的结束标记，否则技能可以自己写一个"【外部内容结束】"来伪造围栏闭合、把后面的内容
            // 冒充成系统在说话。
            ToolResult(UntrustedWeb.fence(text.replace("【外部内容结束】", ""), "技能《${oneLine(hit.name)}》的文档"))
        } catch (e: CancellationException) {
            throw e   // 停止生成要停得掉：绝不能被下面的 Exception 分支吞掉
        } catch (e: Exception) {
            ToolResult("读取技能失败: ${e.message}", isError = true)
        }
    }

    /** id 容错匹配：模型常把 `skill_` 前缀丢了、或直接报技能名。唯一命中才认，避免多个技能时误读成另一个。 */
    private fun resolve(raw: String, skills: List<OperitCompat.OperitPackage>): OperitCompat.OperitPackage? {
        skills.firstOrNull { it.id.equals(raw, true) }?.let { return it }
        skills.filter { it.id.equals("skill_$raw", true) || it.id.removePrefix("skill_").equals(raw, true) }
            .singleOrNull()?.let { return it }
        skills.filter { it.name.equals(raw, true) }.singleOrNull()?.let { return it }
        return skills.filter { it.id.contains(raw, true) || it.name.contains(raw, true) }.singleOrNull()
    }

    /** 第三方文本压成一行：跟系统提示里的索引共用同一个清洗器（安全相关的东西只留一份，别抄第二遍）。 */
    private fun oneLine(s: String): String = OperitCompat.oneLine(s)
}
