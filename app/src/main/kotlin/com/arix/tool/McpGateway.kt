package com.arix.tool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

// ============================================================
// 第三方 MCP 工具网关
//
// 用户装十个第三方 MCP server，工具面立刻失控：几十上百个工具的完整 JSON Schema 每轮都随请求发出去，
// token 爆掉、模型在一堆近义工具里挑花眼、幻觉飙升。而**这些工具的粒度不是我们能控制的**——
// 第三方 server 爱拆多细就多细，锅却算在我们头上。
//
// 解法不是我们发明的：Operit(search + proxy)、grok-build(search_tool + use_tool) 各自独立收敛到了
// 同一个形状——不把第三方工具直接铺开，而是给模型两个「元工具」：先检索目录，再按名字调用。
// 两个 schema 换掉几十上百个 schema。
//
// **边界（这条最容易做错）**：
//   · **自家工具一个都不进网关**，保持瑞士军刀直连。我们自己的工具是精心合并过的（「工具少而多用」
//     是本项目铁律），本来就没几个、每个都常用，收进网关只是白白多一跳检索。
//   · **只有第三方 MCP 的工具走网关**。判定见 [isThirdParty]。
//
// 本文件只提供能力，不自己接线：注册这两个工具、以及在下发工具表时用 [hiddenToolNames] 把被网关
// 收走的工具挡掉，都由调用方（ToolManager / ChatScreen）做。
// ============================================================
object McpGateway {

    /**
     * 少于这个数就不启用网关。
     *
     * 网关不是白拿的：模型要多花一轮 search_tool（甚至再一轮取完整 schema）才能开始干活。
     * 只有两三个第三方工具时，直接把 schema 发下去反而更省——省下的 token 抵不过多出来的往返。
     * 8 是「一屏候选」的量级：到这个数以上，铺开的 schema 通常已上千 token，且模型开始挑错工具。
     */
    const val GATE_MIN_TOOLS = 8

    /** 一次检索最多返回几条（默认）。手表上下文金贵，候选列表要短到能一眼扫完。 */
    private const val DEFAULT_LIMIT = 8

    /** 模型把 limit 填大也不给超过这个数——检索是给它缩小范围的，不是让它换个姿势把目录全倒出来。 */
    private const val MAX_LIMIT = 20

    /** 参与打分的工具上限。第三方装得再多也只扫这些，免得在手表上做无上限的模糊匹配。 */
    private const val SCAN_CAP = 400

    /** 列表里每条描述截到这个长度：一句话够模型判断「是不是它」，剩下的等它取完整定义时再看。 */
    private const val BRIEF_DESC = 80

    /** 打分时只看描述的前这么多字。后面多半是参数说明/示例，对「这工具干嘛的」没帮助，还拖慢匹配。 */
    private const val SCORE_DESC = 200

    /** 单个工具的完整 schema 输出上限。有些第三方 schema 本身就上千行，倒完等于网关白做。 */
    private const val SCHEMA_CAP = 4000

    private const val PREFS = "xtom_mcp_gateway"
    private const val KEY_MODE = "mode"

    /** 用户开关：auto=按 [GATE_MIN_TOOLS] 自动判定（默认）/ on=总是走网关 / off=从不走。 */
    fun mode(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, "auto") ?: "auto"

    fun setMode(context: Context, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode).apply()
    }

    /**
     * [enabled] 与 [hiddenToolNames] 的共用缓存：(工具表版本, 网关 mode) → (要不要走网关, 被收编的工具名)。
     * 两个结果本来就出自同一次扫描，分开算等于每轮多扫一遍工具表。
     */
    @Volatile private var gateCache: Triple<Int, String, Pair<Boolean, Set<String>>>? = null

    /**
     * 扫一次工具表，同时给出「该不该走网关」和「哪些工具被收编」，带缓存。
     *
     * 这两个判断每轮 `getToolsJson` 都要用（[ToolManager.getToolsJson] 先问 enabled 再问 hiddenToolNames），
     * 而每问一次就要过一遍整张工具表。缓存的**失效判据只有两条，少一条就会出现「工具装了却半天不生效」**：
     *   · [ToolManager.version]——工具表任何增删都会 +1。写这张表的入口不止一个（装卸市场包/MCP 的
     *     `OperitCompat.refresh`、技能开关、插件开关、启动期内置装配），所以盯**版本号**而不是盯
     *     `refresh` 这一个入口，才不会漏掉别的写入方。
     *   · [mode]——用户在设置里切 auto/on/off。工具表一个字没变，但结论是反的。
     * 功能包开关（PackageManager）刻意没进判据：第三方 MCP 工具**从不 bindPackage**
     * （只有 `PackageManager.register` 绑，而它只绑内置包的工具），包开关改不动这个集合；
     * 退一步说 `bindPackage` 本身也会让版本号 +1，仍然安全。
     *
     * 出异常时返回「不启用」并**不写缓存**：一次偶发失败不该被钉在缓存里等下次工具表变动才化解。
     */
    private fun gate(context: Context): Pair<Boolean, Set<String>> {
        val ver = ToolManager.version
        val md = try { mode(context) } catch (_: Exception) { return false to emptySet() }
        gateCache?.let { if (it.first == ver && it.second == md) return it.third }
        val fresh = try {
            val third = thirdPartyTools()
            val on = when (md) {
                "on" -> third.isNotEmpty()
                "off" -> false
                else -> third.size >= GATE_MIN_TOOLS
            }
            val hidden: Set<String> = if (on) third.mapTo(HashSet()) { it.name } else emptySet()
            on to hidden
        } catch (_: Exception) { return false to emptySet() }
        // 不加锁：并发下最坏是两个线程各算一遍再各写一次，同一版本算出来的结果本就相同，写谁都对。
        gateCache = Triple(ver, md, fresh)
        return fresh
    }

    /**
     * 现在该不该走网关。
     *
     * 取不到状态/出异常时按「不启用」处理：网关是省 token 的优化，判断失败时退回原来的直连行为，
     * 最坏也就是多花点 token；反过来误判成启用，模型会突然找不到本来看得见的工具。
     */
    fun enabled(context: Context): Boolean = gate(context).first

    /**
     * 「这个工具是第三方 MCP 来的吗」——**按类型判，不按名字前缀判**。
     *
     * 名字前缀在本项目里是不可靠的：两条 MCP 接入路径的命名根本不一致——
     *   · stdio 路径 [StdioMcpClient.listTools] 起名 `mcp_<原名>`（StdioMcpClient.kt:187）
     *   · McpTool.discoverTools 也起 `mcp_<原名>`（McpTool.kt:84）
     *   · 但 OperitCompat 实际走的 HTTP 路径 [McPToolKt.discoverMcpTools] **用原名、没有前缀**
     *     （OperitCompat.kt:561）
     * 按 `mcp_` 前缀过滤会整条漏掉最常用的 HTTP 接入；反过来 `mcp_server`（我们自家把工具暴露出去的
     * 内置工具）名字带前缀却绝不该被收编。只有类型是准的。
     */
    private fun isThirdParty(t: Tool): Boolean = t is McpTool || t is StdioMcpTool

    /**
     * 当前所有来自第三方 MCP 的工具（按名字排序，让检索输出稳定可缓存）。
     *
     * 用 getEnabledTools 而不是 getAll：所在功能包被用户关掉的工具不该出现在目录里——
     * 目录是「你现在能用的」，列出用不了的只会诱导模型去调然后吃一个拒绝。
     */
    fun thirdPartyTools(): List<Tool> =
        ToolManager.getEnabledTools().filter { isThirdParty(it) }.sortedBy { it.name }

    /**
     * 网关启用时**不该再下发完整 schema** 的工具名。给下发工具表的地方（ToolManager.getToolsJson /
     * ChatScreen）做排除用——网关的意义全在这一步，不挡掉的话两个元工具只是白加了两个 schema。
     * 未启用时返回空集合，行为与没有网关时完全一致。缓存与失效判据见 [gate]。
     */
    fun hiddenToolNames(context: Context): Set<String> = gate(context).second

    /** 注册进 ToolManager 的两个元工具。 */
    val searchTool: Tool = SearchTool()
    val useTool: Tool = UseTool()

    // ---- 内部：目录渲染 ----

    /** 去掉发现阶段贴上的 `[MCP]` / `[MCP:http://...]` 标记。目录本身就叫「第三方工具」，这个标记纯占字数。 */
    private val MCP_TAG = Regex("^\\s*\\[MCP[^\\]]*\\]\\s*")

    private fun cleanDesc(t: Tool): String = t.llmDescription.replace(MCP_TAG, "").trim()   // 目录是发给模型的，用模型侧那份

    /** 该工具的必填参数名。列表里只给这个——有没有必填、必填几个，是模型判断「是不是它」的关键信息。 */
    private fun requiredNames(t: Tool): List<String> {
        val arr = t.parameters.optJSONArray("required") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, "").takeIf { s -> s.isNotBlank() } }
    }

    /** 一条候选压成一行：名字 + 一句话 + 必填参数名。**完整 JSON Schema 不进这里**——那正是要省的东西。 */
    private fun briefLine(t: Tool): String = buildString {
        append(t.name)
        val d = cleanDesc(t)
        if (d.isNotBlank()) append(" — ").append(d.take(BRIEF_DESC).replace('\n', ' '))
        val req = requiredNames(t)
        if (req.isNotEmpty()) append(" — 必填 ").append(req.joinToString("、"))
    }

    // ============================================================
    // search_tool：先检索目录
    // ============================================================
    private class SearchTool : Tool {
        override val name = "search_tool"
        override val description =
            "检索本机接入的第三方 MCP 工具目录。这些工具数量多、完整定义没有随工具表发给你，需要时来这里查：" +
                "给 query 按用途检索，得到候选工具的名字、一句话说明和必填参数名；" +
                "给 name 得到那一个工具的完整参数定义（要填 arguments 时用）。两个都不给就列出目录开头。" +
                "拿到名字后用 use_tool 调用。"

        override val parameters = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("query", JSONObject().apply {
                    put("type", "string")
                    put("description", "要找的能力，按用途描述而不是猜名字")
                })
                put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "工具名。给了它就返回这一个工具的完整参数定义，忽略 query")
                })
                put("limit", JSONObject().apply {
                    put("type", "number")
                    put("description", "返回几条候选，默认 $DEFAULT_LIMIT，最多 $MAX_LIMIT")
                })
            })
            // 刻意都不设必填：检索(query)、查定义(name)、列目录(都不给) 是三种合法用法，
            // 设了必填会让其中两种被参数校验挡在门外。缺参数的语义在 execute 里自己处理。
            put("required", JSONArray())
        }

        override suspend fun execute(params: JSONObject): ToolResult {
            val all = thirdPartyTools()
            if (all.isEmpty()) return ToolResult(EMPTY_CATALOG, isError = true)

            // 分支一：按名字取完整定义。
            // 之所以做成独立参数而不是 detail=true：detail 挂在检索上意味着一次返回 N 份完整 schema，
            // 那就把刚省下的 token 又原样吐回去了。「一次只展开一个」才是有界的——模型先用 query 缩到
            // 一两个候选，再对确定要调的那个取定义，总开销恒定在「一份 schema」。
            val want = params.optString("name", "").trim()
            if (want.isNotBlank()) {
                val t = resolve(want, all) ?: return ToolResult(notFound(want, all), isError = true)
                return ToolResult(detailOf(t))
            }

            val limit = params.optInt("limit", DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
            val pool = if (all.size > SCAN_CAP) all.take(SCAN_CAP) else all
            val q = params.optString("query", "").trim()

            // 分支二：没给 query = 列目录开头。不报错——模型第一次接触这个目录时，「先看看有什么」
            // 是合理的一步，回一句「请给 query」只会让它多花一轮。
            if (q.isBlank()) {
                return ToolResult(buildString {
                    append("第三方工具共 ").append(all.size).append(" 个，前 ").append(minOf(limit, pool.size)).append(" 个：\n")
                    pool.take(limit).forEach { append(briefLine(it)).append('\n') }
                    append(FOOTER)
                })
            }

            // 分支三：模糊检索。用全项目共用的 FuzzyMatch（分级短路 + 长文本跳过近似匹配，手表上能跑），
            // 名字和描述分开打分取高——描述通常远长于名字，混在一起会把名字的精确命中稀释掉。
            val hits = FuzzyMatch.rankBy(q, pool, limit) { t ->
                listOf(t.name, cleanDesc(t).take(SCORE_DESC))
            }
            if (hits.isEmpty()) {
                return ToolResult(
                    "第三方工具里没有和「$q」沾边的（共 ${all.size} 个）。换个说法再查一次，" +
                        "或者不给 query 直接看目录里都有什么；确认没有的话就别硬凑，用别的办法或告诉用户缺这个能力。"
                )
            }
            return ToolResult(buildString {
                append("匹配「").append(q).append("」的第三方工具（共 ").append(all.size).append(" 个，列出 ")
                    .append(hits.size).append(" 个）：\n")
                hits.forEach { append(briefLine(it.item)).append('\n') }
                append(FOOTER)
            })
        }
    }

    // ============================================================
    // use_tool：再按名字调用
    // ============================================================
    private class UseTool : Tool {
        override val name = "use_tool"
        override val description =
            "调用一个第三方 MCP 工具。name 是 search_tool 给出的工具名，arguments 是按它的参数定义填好的 JSON 对象。" +
                "不确定名字、或不知道参数怎么填，先用 search_tool 查。" +
                "权限审批、参数校验、执行记录都和直接调用工具时完全一样。"

        override val parameters = JSONObject().apply {
            put("type", "object")
            put("properties", JSONObject().apply {
                put("name", JSONObject().apply {
                    put("type", "string")
                    put("description", "要调用的第三方工具名")
                })
                put("arguments", JSONObject().apply {
                    put("type", "object")
                    put("description", "传给该工具的参数对象，键名按它自己的参数定义；无参数时给空对象")
                })
            })
            put("required", JSONArray().put("name"))
        }

        override suspend fun execute(params: JSONObject): ToolResult {
            val all = thirdPartyTools()
            if (all.isEmpty()) return ToolResult(EMPTY_CATALOG, isError = true)

            val want = params.optString("name", "").trim()
            if (want.isBlank()) return ToolResult(
                "use_tool 缺 name。先用 search_tool 按用途检索，拿到工具名再调。", isError = true
            )

            // 只在第三方目录里解析名字。这同时挡掉三件事：自我递归（use_tool 调 use_tool）、
            // 拿 use_tool 当后门去调自家工具（绕开角色卡工具范围/包开关那套下发过滤）、
            // 以及模型顺手把内置工具名塞进来。解析不到就是解析不到，不做任何跨类别兜底。
            val tool = resolve(want, all) ?: return ToolResult(notFound(want, all), isError = true)

            // 参数：模型有时把对象序列化成字符串再传（各家模型都有这毛病），两种都收。
            val args = readArgs(params.opt("arguments"))
                ?: return ToolResult(
                    "arguments 不是有效的 JSON 对象。它要的是该工具参数定义里那些键组成的对象；" +
                        "不确定填什么就先 search_tool(name=\"${tool.name}\") 看完整定义。", isError = true
                )

            // **必须走 ToolManager.execute**：那是全项目唯一的权限闸（见 ToolManager.execute 的注释），
            // 直接 tool.execute() 等于第三方工具从此没有权限系统——网关反而成了绕过权限的后门。
            // 顺带也复用了它的参数预校验和 ToolActivityBus 埋点：用户在活动中心会看到 use_tool 和真实
            // 工具两条记录，这是想要的——他该知道 AI 到底调了哪个第三方工具，而不只是看到一个 use_tool。
            return ToolManager.execute(ToolCall(
                id = "gw_${System.currentTimeMillis()}",
                name = tool.name,
                arguments = args,
            ))
        }

        private fun readArgs(raw: Any?): JSONObject? = when (raw) {
            null, JSONObject.NULL -> JSONObject()
            is JSONObject -> raw
            is String -> if (raw.isBlank()) JSONObject() else try { JSONObject(raw) } catch (_: Exception) { null }
            else -> null
        }
    }

    // ---- 内部：名字解析与错误文案 ----

    private const val FOOTER =
        "取完整参数定义：search_tool(name=…)；调用：use_tool(name=…, arguments={…})"

    private const val EMPTY_CATALOG =
        "本机没有接入任何第三方 MCP 工具，这个目录是空的。别在这里绕，用你工具表里已有的工具，" +
            "或者告诉用户需要他先去扩展页接一个 MCP 服务。"

    /**
     * 把模型给的名字解析到目录里的工具。
     *
     * 会做的兜底：忽略大小写；补/去 `mcp_` 前缀（两条接入路径的前缀不一致，模型记混很正常，
     * 见 [isThirdParty] 的注释）；最后才用模糊匹配，且**只认唯一高分命中**——
     * 名字解析错了会直接调错工具（可能有副作用），宁可回一句「没找到、去检索」也不猜。
     */
    private fun resolve(want: String, pool: List<Tool>): Tool? {
        pool.firstOrNull { it.name == want }?.let { return it }
        pool.firstOrNull { it.name.equals(want, ignoreCase = true) }?.let { return it }
        val alt = if (want.startsWith("mcp_")) want.removePrefix("mcp_") else "mcp_$want"
        pool.firstOrNull { it.name.equals(alt, ignoreCase = true) }?.let { return it }
        val hits = FuzzyMatch.rank(want, pool, limit = 2, minScore = 0.90f) { it.name }
        return if (hits.size == 1) hits[0].item else null
    }

    /** 名字对不上时的回话：给相近候选 + 指明下一步该干什么，而不是干巴巴一句「未找到」。 */
    private fun notFound(want: String, pool: List<Tool>): String {
        val near = FuzzyMatch.rank(want, pool, limit = 3) { it.name }.map { it.item.name }
        return buildString {
            append("第三方工具里没有「").append(want).append("」")
            if (near.isNotEmpty()) append("。相近的有：").append(near.joinToString("、"))
            append("。先用 search_tool 按用途检索确认真实名字（工具名由第三方服务自己定，猜不出来），再调 use_tool。")
        }
    }

    /** 单个工具的完整定义。schema 原样给——模型要靠它填 arguments，删减等于让它猜。超长才截断。 */
    private fun detailOf(t: Tool): String {
        val schema = try { t.parameters.toString(2) } catch (_: Exception) { t.parameters.toString() }
        return buildString {
            append(t.name).append('\n')
            cleanDesc(t).takeIf { it.isNotBlank() }?.let { append(it).append('\n') }
            append("参数定义：\n")
            if (schema.length > SCHEMA_CAP) {
                append(schema.take(SCHEMA_CAP)).append("\n…（定义过长已截断，按上面已给出的字段填；缺的字段调用后按报错补）")
            } else {
                append(schema)
            }
            append('\n').append("调用：use_tool(name=\"").append(t.name).append("\", arguments={…})")
        }
    }
}
