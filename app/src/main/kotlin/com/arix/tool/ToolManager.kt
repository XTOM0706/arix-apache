package com.arix.tool

import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONObject

object ToolManager {
    // 用并发 Map：OperitCompat.refresh() 在后台线程改工具表（卸载/注册市场包），聊天发送线程同时读（getToolsJson/execute）——
    // 原来是普通 HashMap，并发下工具可能「在 schema 里、execute 时却找不到」(报「工具未找到」=用户说的未知工具)，甚至 CME。
    private val tools = java.util.concurrent.ConcurrentHashMap<String, Tool>()

    /** 工具名 → 所属功能包 id。由 [PackageManager.register] 写入，用来回答「这工具所在的包开了没」。 */
    private val toolPkg = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 内置工具名（保留字）。见 [registerBuiltin]。 */
    private val builtinNames = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /**
     * 工具表版本号：**任何**增删（注册/反注册/清空/改包绑定）都 +1。
     *
     * 给需要缓存「按整张工具表算出来的结果」的地方做失效判据（当前用户：[McpGateway.hiddenToolNames]）。
     * 为什么是"表自己发版本号"而不是"缓存方去盯 OperitCompat.refresh"：写这张表的入口不止一个
     * （OperitCompat.refresh 装卸市场包、SkillManager 开关技能、PluginManager 开关插件、启动期内置装配），
     * 盯其中一个就会漏——漏的表现正是最难查的那种「工具装了却半天不生效」。放在唯一的写入面上，一个都跑不掉。
     *
     * 用 AtomicInteger 而不是 `@Volatile var i++`：后台线程（OperitCompat.refresh）与发送线程会同时动它，
     * `i++` 不是原子的，丢一次自增就可能让缓存少失效一轮。
     */
    private val tableVersion = java.util.concurrent.atomic.AtomicInteger(0)
    val version: Int get() = tableVersion.get()

    /**
     * **内置**工具注册。名字同时进保留表，此后第三方顶不掉（见 [register]）。
     * 只该由启动期的内置装配调用：`PackageManager.register` 与 `XtomApp` 里的网关元工具。
     */
    fun registerBuiltin(tool: Tool) { tools[tool.name] = tool; builtinNames.add(tool.name); tableVersion.incrementAndGet() }

    /**
     * **第三方**工具注册（市场包 / 技能包 / JS 插件 / MCP）。返回**实际注册名**——
     * 撞上内置名时不覆盖，改注册成 `ext_<name>`，调用方要拿返回值去做反注册登记。
     *
     * 为什么：这张表是 `tools[name] = tool`，同名直接覆盖；而权限键就是工具名。第三方包只要把自己的
     * 工具叫 `shell`/`file_op`，就能顶掉内置实现**并继承用户早先给那个名字设下的放行策略**——
     * 名字是对面自己报的，等于让它填「我是谁」。MCP 那条早已强制 `mcp_` 前缀（见 [McpTool.name]），
     * 这里把剩下三条来源一并堵上：内置命名空间只有我们能进。
     */
    fun register(tool: Tool): String {
        tableVersion.incrementAndGet()
        if (tool.name !in builtinNames) { tools[tool.name] = tool; return tool.name }
        val safe = "ext_${tool.name}"
        tools[safe] = RenamedTool(safe, tool)
        return safe
    }
    fun unregister(name: String) { tools.remove(name); toolPkg.remove(name); tableVersion.incrementAndGet() }
    fun bindPackage(toolName: String, pkgId: String) { toolPkg[toolName] = pkgId; tableVersion.incrementAndGet() }
    fun get(name: String): Tool? = tools[name]

    /**
     * 全部已注册工具，**含所在包没启用的**。
     *
     * ⚠ 给对内、能看懂「未启用」的调用方用（如权限页要给所有工具配策略）。
     * 对外暴露（MCP）或要报数给用户时，用 [getEnabledTools]——外部客户端既看不到
     * 「未启用」的说明，也调不到 request_permission 去启用，广告给它等于挖坑。
     */
    fun getAll(): List<Tool> = tools.values.toList()

    /** 只要所在包已启用的工具。对外暴露一律用这个。 */
    fun getEnabledTools(): List<Tool> = tools.values.filter { disabledPkgOf(it.name) == null }

    /** 这个工具所在的包启用了吗（给 UI 标注用）。 */
    fun isToolEnabled(name: String): Boolean = disabledPkgOf(name) == null

    /** 这个工具所属的包（没绑定则 null）。 */
    fun packageOf(name: String): PackageDef? =
        toolPkg[name]?.let { id -> try { PackageManager.getAllPackages().firstOrNull { it.id == id } } catch (_: Exception) { null } }
    fun clear() { tools.clear(); toolPkg.clear(); builtinNames.clear(); tableVersion.incrementAndGet() }

    /**
     * 这个工具所在的功能包没启用吗？没启用则返回该包，否则 null。
     *
     * 取不到状态时**当作已启用**（fail-open）：宁可让工具照跑，也不要凭一次读 prefs 失败
     * 就跟 AI 说「你没这功能」——那正是我们要根治的毛病。
     */
    private fun disabledPkgOf(toolName: String): PackageDef? = try {
        val id = toolPkg[toolName]
        if (id == null || PackageManager.isEnabled(id)) null
        else PackageManager.getAllPackages().firstOrNull { it.id == id }
    } catch (_: Exception) { null }

    /**
     * 喂给模型的工具表：**所有**工具都在，没启用的工具在描述里挑明「你有这能力，只是没开，这样申请」。
     *
     * 以前没启用的工具压根不进这个表，模型看不见 → 以为自己没这能力 → 绕路去用 ui_control 戳屏幕。
     * 让它看见并知道怎么要，比让它猜要强。
     */
    /**
     * 工具表：**只发已启用的工具**的完整 schema。禁用工具原来也全发（每个几百 token 的
     * schema + 一长段「未启用」说明），~一半工具是禁用的 → 纯 token 浪费。改为禁用能力压成
     * 一行名字清单（见 [disabledCapabilitiesNote]）拼进系统提示，模型仍知道「有这能力、需要时申请」。
     */
    /**
     * @param excludePkgs 这一次不下发的功能包 id（角色卡工具范围，见 [com.arix.app.CardToolStore]）。
     *   传参而不是设个全局「当前角色卡」：子agent/唤醒助手/MCP 可能同时在发请求，全局可变状态会串台。
     */
    fun getToolsJson(excludePkgs: Set<String> = emptySet()): JSONArray = JSONArray().apply {
        // 按名字排序后再发：tools 是 ConcurrentHashMap，原来按哈希桶顺序遍历——插件/MCP/市场包一加载就重排，
        // 每次工具数组字节都不同，供应商的工具/前缀缓存永远命中不了。定序=数组逐字节稳定→可缓存（省 token）。
        // 第三方 MCP 工具网关：装了十个 MCP server 时工具面会失控，而那个粒度不是我们能控制的。
        // 开着网关就把第三方工具收进目录（只发 search_tool/use_tool 两个元工具），关着就反过来别发这两个。
        // ⚠ 自家工具永远直连，一个都不进网关——"工具少而多用"是我们自己挑过的，多一跳纯亏。
        val ctx = com.arix.app.XtomApp.appContext
        val gatewayOn = ctx != null && try { McpGateway.enabled(ctx) } catch (_: Exception) { false }
        val hidden = if (gatewayOn && ctx != null) try { McpGateway.hiddenToolNames(ctx) } catch (_: Exception) { emptySet() } else emptySet()
        val gatewayNames = setOf(McpGateway.searchTool.name, McpGateway.useTool.name)
        // 一次算完，别在循环里逐个工具去读 prefs。`hasAnyHint()` 是个 volatile 字段读（免费），
        // 压根没踩过坑时连开关都不用读。
        val hintsOn = com.arix.app.LessonRecorder.hasAnyHint() && lessonHints
        for (t in tools.values.sortedBy { it.name }) {
            if (disabledPkgOf(t.name) != null) continue   // 禁用的不发 schema
            if (excludePkgs.isNotEmpty() && toolPkg[t.name] in excludePkgs) continue   // 本张角色卡不带这个包
            if (!CapabilityProbe.has(t.requires)) continue   // 本机没这个运行前提，发了它也只会调失败
            if (t.name in hidden) continue                    // 已被网关收编的第三方工具
            if (!gatewayOn && t.name in gatewayNames) continue // 网关没启用，别发这两个元工具占位置
            put(if (hintsOn) withLessonHint(t.toOpenAiTool(), t.name) else t.toOpenAiTool())
        }
    }

    /**
     * 把这个工具的「上回踩过」挂到**它自己的描述**后面。
     *
     * 为什么挂在这儿，而不是回合开头注入、也不是执行完再说：
     *  · 回合开头那句提醒离「要不要调这个工具」这个决定隔着一整片上下文，而且能不能进上下文
     *    还得看那一轮记忆检索的排名。一轮里模型可能连调好几次工具，越往后它越远。
     *  · 执行完再提示已经晚了——这次调用早发出去了。
     *  · 而工具描述是模型**正在读它、正在决定要不要用它**的那一刻看到的东西，schema 每轮现读，
     *    所以这是唯一能"在动手之前"说上话的位置。
     *
     * 只给**有教训的那几个**工具加，没教训的一个字都不多发（每轮 schema 的 token 是刚压过的）。
     * 拿不到提示、或者结构不是预期形状，就原样返回——这层是增强，绝不能把 schema 弄坏。
     */
    private fun withLessonHint(json: JSONObject, name: String): JSONObject {
        val hint = com.arix.app.LessonRecorder.hintFor(name) ?: return json
        return try {
            val fn = json.optJSONObject("function") ?: return json
            val desc = fn.optString("description", "")
            // Prior failure: 给模型看的，用英文（同 llmDescription 的理由：中文在非中文词表里要交税）
            fn.put("description", (desc + "\nPrior failure with this tool: " + hint).trim())
            json
        } catch (_: Exception) { json }
    }

    /**
     * 「调用前教训提示」总开关。默认**开**——它只对已经踩过坑的工具生效，没踩过就零成本；
     * 而踩过的那些，不提醒就等着它再踩一次。关掉则退回「只靠回合开头的记忆检索」，即加这个之前的样子。
     */
    private const val PREF_TOOLS = "xtom_tool_schema"
    private const val K_LESSON_HINTS = "lesson_hints"

    private val lessonHints: Boolean
        get() = com.arix.app.XtomApp.appContext
            ?.getSharedPreferences(PREF_TOOLS, android.content.Context.MODE_PRIVATE)
            ?.getBoolean(K_LESSON_HINTS, true) ?: true

    fun setLessonHints(context: android.content.Context, on: Boolean) {
        context.getSharedPreferences(PREF_TOOLS, android.content.Context.MODE_PRIVATE)
            .edit().putBoolean(K_LESSON_HINTS, on).apply()
    }

    fun lessonHintsEnabled(context: android.content.Context): Boolean =
        context.getSharedPreferences(PREF_TOOLS, android.content.Context.MODE_PRIVATE)
            .getBoolean(K_LESSON_HINTS, true)

    /**
     * 因**本机不具备运行前提**而没下发 schema 的能力，压成一行（同 [disabledCapabilitiesNote] 的思路）。
     * 让模型知道「这能力存在、现在没条件、这样解锁」，而不是以为自己天生不会。无则空串。
     */
    fun missingCapabilitiesNote(): String {
        val miss = CapabilityProbe.missing()
        if (miss.isEmpty()) return ""
        val byReq = tools.values
            .filter { it.requires in miss && disabledPkgOf(it.name) == null }
            .groupBy({ it.requires!! }, { it.name })
        if (byReq.isEmpty()) return ""
        val lines = byReq.entries.sortedBy { it.key.ordinal }.joinToString("；") { (req, names) ->
            "${req.label}(${names.sorted().joinToString("/")}) —— ${req.howTo}"
        }
        // 顺带把当前能力等级并进这一行（**不新起一行**：这是每轮都发的，能力等级只在"有东西没解锁"时才值得占 token；
        // 全都解锁了就一个字都不说）。三处口径要一致：权限页的等级卡、这里、以及工具失败时的话术。
        val tier = tierNote()
        return "【当前设备还开不了的能力】$tier 这些能力你有，但本机现在不具备运行条件、所以工具表里没给你（别当作做不到，需要就按后面的办法解锁）：$lines"
    }

    /** 一句「现在处在哪一层」。取不到 context 就返回空串（宁可不说，也不能说错）。 */
    private fun tierNote(): String = try {
        val c = com.arix.app.XtomApp.appContext ?: return ""
        val t = com.arix.app.CapabilityTier.detect(c)
        "你当前在 L${t.level}（${t.label}）。"
    } catch (_: Exception) { "" }

    /**
     * 角色卡工具范围之外的包，压成一行告诉模型「这些这张卡不带」。
     * 不说的话它会以为自己没这本事、跑去用别的工具硬凑（正是 [disabledCapabilitiesNote] 要治的毛病）。
     */
    fun cardScopeNote(excludePkgs: Set<String>): String {
        if (excludePkgs.isEmpty()) return ""
        val names = try {
            PackageManager.getAllPackages().filter { it.id in excludePkgs && PackageManager.isEnabled(it.id) }.map { it.name }
        } catch (_: Exception) { return "" }
        if (names.isEmpty()) return ""
        return "【本角色不使用的能力】当前角色卡把这些能力放在了范围之外：${names.joinToString("、")}。" +
            "用户要你做这些事时，直说这张卡不带这个能力、建议他换张卡或去角色卡设置里放开，别绕路硬凑。"
    }

    /**
     * 禁用能力的一行清单：告诉模型「这些能力存在但用户没开，需要时用 request_permission 申请」。
     * 拼进系统提示，替代原来「每个禁用工具都发完整 schema + 说明」的高开销做法。无禁用则返回空串。
     */
    fun disabledCapabilitiesNote(): String {
        val byPkg = tools.values.mapNotNull { t -> disabledPkgOf(t.name)?.let { it to t.name } }
            .groupBy({ it.first }, { it.second })
        if (byPkg.isEmpty()) return ""
        // ⚠ 排序：这段每轮都发，而 tools 是 ConcurrentHashMap——不排序的话，MCP 连上/技能开关改一次表，
        // 遍历顺序就变，这一段连同它后面的所有内容一起掉出供应商的前缀缓存。
        // 同样的理由 getToolsJson 早就 sortedBy 了并写明「定序=可缓存」，这里当时漏了。
        val lines = byPkg.entries.sortedBy { it.key.id }.joinToString("；") { (pkg, names) ->
            val sorted = names.sorted()
            // 包里只有一个工具、且名字就是包 id 时（map/browser/life_query… 大半都是这样），
            // 只印一次，别写成 `地图导航:map(map)`。
            if (sorted.size == 1 && sorted[0] == pkg.id) "${pkg.name}:${pkg.id}"
            else "${pkg.name}:${pkg.id}(${sorted.joinToString("/")})"
        }
        // 申请方式**只说一遍**：原来是每个包后面都跟一句
        // `申请: request_permission(permission="package:<id>")`，三十几个包就重复三十几遍同一件事，
        // 每轮固定多花几百 token。同一件事在工具 schema 里本来也已经说过一次了。
        return "【未启用的能力】你还有这些能力，只是所在功能包用户没开（别当作做不到）：$lines。" +
            "要用哪个就先申请：request_permission(permission=\"package:上面冒号后的 id\")，用户同意后即可调用。"
    }

    suspend fun execute(call: ToolCall): ToolResult {
        // 工具名为空/null 的历史脏数据（模型从残缺历史复刻出的空名调用）：直接给明确反馈，
        // 别报「工具未找到: null」这种让用户和模型都摸不着头脑的文案。
        if (call.name.isBlank()) {
            com.arix.app.AppLog.e("Tool", "收到工具名为空的调用 arguments=${call.arguments}")
            return ToolResult("工具名称为空：这通常来自历史对话里一条残缺的工具调用记录（老版本数据），" +
                "这一条没有可执行的内容。请忽略它，或基于现有信息继续作答。", isError = true, failKind = "not_found")
        }
        // 兼容模型漏了市场包工具的 op_ 前缀 / 按短名叫（op_pkg_tool ←→ pkg_tool ←→ tool）：唯一匹配才认，避免误解析。
        val tool = tools[call.name] ?: run {
            val cands = tools.keys.filter { it == "op_" + call.name || it.endsWith("_" + call.name) }
            if (cands.size == 1) tools[cands[0]] else null
        } ?: return ToolResult(
            "这个工具调用不对：没有叫「${call.name}」的工具。可能是工具名拼错了、或这个工具当前不可用（所在功能包没开/本机不支持）。" +
                "对照工具列表里的准确名字重新调用；参数我已经看到（${call.arguments}），名字对上了就能执行。",
            isError = true, failKind = "not_found")
            .also { logStillborn(call, ToolActivityBus.Status.ERROR, it.content) }
        // 包没开就别真跑——但要说清「不是你没这能力，是没开」，并给出申请的路。
        // **用 tool.name 而不是 call.name**：上面的短名 fallback 会把 `translate` 解析到真实工具
        // `op_x_translate`，可 disabledPkgOf 若查原始的 `call.name`（短名）就查不到那条包绑定 →
        // fail-open 当成已启用 → 用户明明禁了这个包，AI/插件/MCP 用短名一叫就绕过了。以解析后的真名为准。
        disabledPkgOf(tool.name)?.let { off ->
            return ToolResult("「${tool.name}」所在的功能包「${off.name}」还没启用，所以用不了。" +
                "调 request_permission(permission=\"package:${off.id}\", reason=\"…\") 向用户申请启用，同意后再重试。", isError = true, failKind = "pkg_off")
                .also { logStillborn(call, ToolActivityBus.Status.DENIED, it.content) }
        }
        // 参数预校验：按该工具自己的 JSON Schema 挡住模型幻觉出的坏参数（缺必填/枚举外/结构类型不符），
        // 免得坏参数被 optInt/optString 静默兜成默认值做出错的操作（tap 到 (0,0)、方向乱走）。刻意宽容，
        // 只挡高置信违规、放行字符串化标量（见 ToolArgValidator）。放在权限闸**之前**——坏调用不该还去弹窗问用户。
        ToolArgValidator.validate(tool.parameters, call.arguments)?.let { why ->
            return ToolResult("工具「${tool.name}」参数不合法：$why。请按工具定义修正参数后重试。", isError = true, failKind = "bad_args")
                .also { logStillborn(call, ToolActivityBus.Status.ERROR, it.content) }
        }
        // 网关不能变成越权通道：`use_tool` 内部会用默认的 ToolCaller.Ai 去调第三方工具
        // （Tool.execute 拿不到调用方身份），于是插件/外部 MCP 客户端只要能调 use_tool，
        // 就等于拿到了 AI 级信任去碰所有第三方工具，把 `plugin:<id>:` 那套权限命名空间整个绕过。
        // 在这里按调用方直接挡住是最小且确定的修法（网关本来就是给主对话 AI 用的）。
        if (tool.name == McpGateway.useTool.name && call.caller !is ToolCaller.Ai) {
            return ToolResult("「${tool.name}」只允许主对话 AI 使用。${call.caller.label}要调第三方工具，请直接按工具名调用，走你自己那份权限策略。",
                isError = true, failKind = "denied")
                .also { logStillborn(call, ToolActivityBus.Status.DENIED, it.content) }
        }
        // 权限闸门：按用户策略（允许/询问/禁止）在执行前拦截。
        // **这是全项目唯一的闸**——任何绕过这里直接 tool.execute() 的路径都等于没有权限系统
        // （插件 JS 曾经就是这么绕的，见 JsPluginRuntime.callTool 的注释）。
        // 回给模型的话必须按「为什么没过」分开写（见 DenyReason）：原来三种情形共用一句
        // 「被权限策略拒绝」，模型分不清是被人拒了还是没人在场，只会换个说法一遍遍重试。
        // ⚠ 下面这几句是**给模型看的**，不要 tr()。
        val gate = ToolPermissionManager.checkGate(tool, call.arguments, call.caller)
        if (gate is ToolGate.Denied) {
            val why = when (gate.reason) {
                DenyReason.USER_DENIED ->
                    "用户拒绝了你使用「${tool.name}」这个工具。不要重试该工具，也不要换个说法再调一次；" +
                        "改用别的办法完成，或者直接告诉用户你需要什么、为什么需要。"
                DenyReason.POLICY_FORBID ->
                    "「${tool.name}」被用户的权限策略设为禁止（${tool.permissionLevel.label}级，${call.caller.label}未获授权），" +
                        "本次没有执行，再调一次结果一样。换别的办法做，或者告诉用户可以到「权限」设置里改这条策略。"
                DenyReason.NO_RESPONSE ->
                    "「${tool.name}」需要用户确认，但等了一分钟没人应答（授权提示当时不在用户眼前），本次没有执行。" +
                        "这不等于他不同意：可以先把不需要授权的部分做完，或者等他回来时再问一次。"
                DenyReason.UNAVAILABLE ->
                    "「${tool.name}」需要用户确认，但当前场景问不到用户，本次没有执行。请改用别的办法，或稍后再试。"
            }
            return ToolResult(why, isError = true, userDenied = gate.reason == DenyReason.USER_DENIED,
                // 只有「他本人当场拒的」才值得沉淀成教训：策略禁用是很久以前设的、超时是压根没人在场，
                // 把这两种也记成"用户拒绝过"会让模型对着一条并不存在的态度反复退让。
                failKind = if (gate.reason == DenyReason.USER_DENIED) "denied" else null)
                .also { logStillborn(call, ToolActivityBus.Status.DENIED, it.content) }
        }
        // 行为流埋点：全项目就这一处工具执行闸，埋在这里等于覆盖 AI/插件/用户脚本/MCP 全部调用方，
        // 不用去每个工具里加（见 ToolActivityBus）。begin 放在权限闸**之后**——ASK 策略会在闸里挂起
        // 等用户点确认，搁在闸前就会把「等审批」显示成「正在执行」。
        val act = ToolActivityBus.begin(call)
        return try {
            // 把调用方放进协程上下文：子agent/工作流/本地搜索这类"工具内部再调工具"的路径，
            // 内层要能读回真正的调用方，否则插件借外层工具一跳就洗成了 AI 身份（见 CallerContext）。
            kotlinx.coroutines.withContext(CallerContext(call.caller)) { tool.execute(call.arguments) }.also {
                ToolActivityBus.finish(act, if (it.isError) ToolActivityBus.Status.ERROR else ToolActivityBus.Status.OK, it.content)
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            // 别把取消当异常吞掉——否则 STOP 停不掉正在跑的工具（深度搜索/子agent）。
            // 但「取消」有两种，得分开：外层真被取消（用户 STOP）才该往上抛；
            // 若只是**这个工具内部**的取消（withTimeout 超时、工具里某个 async 子协程被取消），
            // 外层协程还活着，往上抛就会把整轮对话静默掐死。ensureActive() 正好只在前者抛。
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            ToolActivityBus.finish(act, ToolActivityBus.Status.ERROR, "工具内部超时/取消")
            ToolResult("「${tool.name}」执行中被内部超时或子任务取消打断了（不是用户喊停）：${c.message ?: "无更多信息"}。" +
                "可以重试一次；反复如此就换个不依赖它的做法。", isError = true, failKind = "cancelled")
        } catch (e: Exception) {
            // 进运行日志：工具炸了是用户最常问"它刚才怎么了"的场景，而这条路径原来只把消息回给模型、
            // 用户侧什么痕迹都不留（工具卡只显示一句异常，看不到是哪个工具、什么参数形态）。
            com.arix.app.AppLog.e("Tool", "${tool.name} 执行异常", e)
            ToolResult("工具执行异常: ${e.message}", isError = true)
                .also { ToolActivityBus.finish(act, ToolActivityBus.Status.ERROR, it.content) }
        }
    }

    /**
     * 记一条「压根没跑起来」的调用（没找到工具/包没开/被权限拒）。
     * 这些恰恰是最该让用户看见的——AI 想干某事却被挡下，不留痕的话用户只会觉得「它怎么没反应」。
     */
    private fun logStillborn(call: ToolCall, status: ToolActivityBus.Status, msg: String) {
        ToolActivityBus.finish(ToolActivityBus.begin(call), status, msg)
    }

    fun parseToolCalls(text: String): List<ToolCall> {
        val calls = mutableListOf<ToolCall>()
        val regex = Regex("""<tool_call>\s*\{.*?"name"\s*:\s*"(\w+)".*?"arguments"\s*:\s*(\{.*?})\s*\}\s*</tool_call>""", RegexOption.DOT_MATCHES_ALL)
        regex.findAll(text).forEach { match ->
            try {
                val name = match.groupValues[1]
                val argsStr = match.groupValues[2]
                val args = JSONObject(argsStr)
                calls.add(ToolCall(id = name + System.currentTimeMillis(), name = name, arguments = args))
            } catch (_: Exception) {}
        }
        return calls
    }
}

/**
 * 改名壳：第三方工具撞上内置名时套一层，只换 [name]，其余原样委派（见 [ToolManager.register]）。
 * 不改原对象是因为名字在 [Tool] 里是只读属性，而且原对象可能被它自己的包持有着复用。
 */
private class RenamedTool(override val name: String, private val delegate: Tool) : Tool {
    override val description: String get() = delegate.description
    override val llmDescription: String get() = delegate.llmDescription
    override val parameters: JSONObject get() = delegate.parameters
    override val permissionLevel get() = delegate.permissionLevel
    override val requires get() = delegate.requires
    // ⚠ 这一条不转发的话，被改名的工具（第三方撞内置名 → ext_*）会退回默认 false，
    // 于是「结果不落库」的标记在改名那一刻**静默丢失**——最不该 fail-open 的正是隐私这一项。
    override val ephemeralResult get() = delegate.ephemeralResult
    override suspend fun execute(params: JSONObject): ToolResult = delegate.execute(params)
}
