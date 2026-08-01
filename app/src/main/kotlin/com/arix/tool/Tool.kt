package com.arix.tool

import org.json.JSONObject

data class ToolResult(
    val content: String,
    val isError: Boolean = false,
    /**
     * 这一次是**用户本人当场点了「拒绝」**才没跑（不是策略禁用、也不是没人应答超时）。
     *
     * 上层据此实现「工具被拒绝后停止本轮」：只有「他刚刚亲手拒的」才算数——策略禁用是他很久以前设的、
     * 超时是压根没人在场，把这两种也当成「他拒绝了」会莫名其妙地掐断生成。默认 false，老调用点行为不变。
     */
    val userDenied: Boolean = false,
    /**
     * 这次失败**属于哪一类**（`denied` / `bad_args` / `pkg_off` / `not_found`），成功时 null。
     *
     * 有它才能把失败沉淀成教训（见 [com.arix.app.LessonRecorder]）：上层原本只能去匹配错误
     * 文案里的字眼来猜是哪种失败——那种判断一改文案就悄悄失效，而且永远不知道自己失效了。
     */
    val failKind: String? = null,
)

/**
 * 谁在调这个工具。
 *
 * 之所以需要：权限策略此前只按工具名存（"shell" → FORBID），**不区分调用方**。
 * 但「AI 要跑 shell」和「某个第三方插件要跑 shell」是完全不同的两件事，用户对它们的
 * 信任度也不同。要做每包权限，就得先能回答「谁在调」。
 */
sealed class ToolCaller {
    /** 主对话 AI / 子agent / 工作流——用户直接驱动的。 */
    data object Ai : ToolCaller()

    /** 第三方插件的 JS。[pkgId] 是包 id，权限按它单独记。 */
    data class Plugin(val pkgId: String) : ToolCaller()

    /**
     * 用户自己写的脚本（如 JS 搜索源）。跟 [Ai] 同一套信任与存储键——
     * 那段代码就是用户敲的，没道理反过来问用户「你的脚本想联网，准吗」。
     * 单列一类只为弹窗能说实话（说"AI"是骗人的）。
     */
    data class UserScript(val name: String) : ToolCaller()

    /**
     * 外部 MCP 客户端。
     *
     * [verified] = 这个身份**拿得出凭据**吗。设了访问令牌时 [clientId] 取令牌指纹（只有持令牌的人是它）；
     * 没设令牌时 [clientId] 只能取「远端 IP:端口」，而源端口是客户端自己挑的——本机任一 App 绑同一个
     * 源端口再连过来，就顶着同一个身份、继承用户上次对它点的「始终允许」。
     * 所以未验证的 MCP 调用方**不许记住授权**（见 [canRememberAlways]）：每次都得当面点头。
     */
    data class Mcp(val clientId: String, val verified: Boolean = false) : ToolCaller()

    /** 给用户看的一句话："AI" / "插件「xxx」"。审批弹窗要说清是谁在要权限。 */
    val label: String get() = when (this) {
        is Ai -> "AI"
        is Plugin -> "插件「$pkgId」"
        is UserScript -> "你的脚本「$name」"
        is Mcp -> "外部客户端「$clientId」"
    }

    /**
     * 这个调用方的「始终允许/始终禁止」能不能**落盘**。
     *
     * 身份伪造得了就不能——记住的其实是「谁碰巧顶着这个 id」，不是他当初点头放行的那个客户端。
     * 当前只有「没设令牌的 MCP 客户端」落在这一档；弹窗据此不显示两个「始终」按钮
     * （[com.arix.app.ToolPermissionDialog]），[ToolPermissionManager.resolve] 也再兜一次底。
     */
    val canRememberAlways: Boolean get() = this !is Mcp || verified

    /** 权限存储的命名空间前缀。AI 保持无前缀 = 兼容已有的 SharedPreferences 键，老用户设置不丢。 */
    val prefix: String get() = when (this) {
        is Ai, is UserScript -> ""   // 同一份信任，同一批键
        is Plugin -> "plugin:$pkgId:"
        is Mcp -> "mcp:$clientId:"
    }
}

/**
 * 「现在是谁在调工具」——挂在协程上下文里，穿过 `Tool.execute(params)` 这层拿不到调用方的接口。
 *
 * 为什么需要：`create_agent` / `workflow` / `local_search` 这类工具内部会**再去调别的工具**，
 * 而 `Tool.execute` 的签名里没有调用方，于是内层一律吃默认值 `ToolCaller.Ai`。后果是一条实打实的提权链：
 * 插件或外部 MCP 客户端只要让外层工具过一次闸（点一次「始终允许」就永久），
 * 其内部派生的任意调用就全部以 **AI 身份 + AI 的权限键空间**执行，`plugin:<id>:` 那套命名空间形同虚设。
 *
 * 修法是让身份**跟着协程走**：[ToolManager] 执行工具时把调用方放进上下文，内层构造 [ToolCall] 时读回来。
 * 读不到就退回 `Ai`（与从前一致），所以没接的调用点行为不变。
 */
class CallerContext(val caller: ToolCaller) :
    kotlin.coroutines.AbstractCoroutineContextElement(Key) {
    companion object Key : kotlin.coroutines.CoroutineContext.Key<CallerContext>
}

/** 当前协程里的调用方；没有则 [ToolCaller.Ai]。内层工具再调工具时用它，别再硬编码 Ai。 */
suspend fun currentToolCaller(): ToolCaller =
    kotlin.coroutines.coroutineContext[CallerContext]?.caller ?: ToolCaller.Ai

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JSONObject,
    /** 默认 Ai：现有调用点(ChatScreen/SubAgent/Workflow/McpServer/WakeAssistant)不用改就仍是原行为。 */
    val caller: ToolCaller = ToolCaller.Ai,
)

interface Tool {
    val name: String

    /** 给**人**看的一句话（权限页、功能包页、本地搜索都在显示它）。中文，跟着 UI 语言走。 */
    val description: String

    /**
     * 给**模型**看的描述。默认就是 [description]；**热门工具覆写成英文**。
     *
     * 为什么分成两个：这段话每轮都随 schema 发出去，而中文在非中文语料训出来的词表里要交"中文税"——
     * 实测同一批我们自己的句子，o200k(GPT-4o/5 系) 0.62 token/字、cl100k(GPT-4 系) 0.85 token/字，
     * 换成等义英文分别省 14% / 36%；Claude、Gemini 这些同理。只有 DeepSeek/Qwen/GLM 这类
     * 中文语料训的词表免税——那几家换英文也基本打平，**不亏**。所以模型侧一律英文。
     *
     * ⚠ 别把 [description] 直接改成英文：它会出现在权限页/功能包页上，那是给用户看的。
     * ⚠ 参数里的 `description` 不用分家——那些只发给模型、UI 一个字都不显示，直接写英文即可。
     */
    val llmDescription: String get() = description

    val parameters: JSONObject
    /** 该工具所需的系统特权等级。默认普通级；危险工具应覆写为更高等级。 */
    val permissionLevel: AndroidPermissionLevel get() = AndroidPermissionLevel.STANDARD

    /**
     * 运行前提：本机**不具备就必然跑不起来**的能力（见 [ToolRequirement]）。默认无前提。
     *
     * 不具备时这个工具的 schema 不会下发给模型（省 token + 免得它调了必失败），只在系统提示里
     * 留一行「有这能力、当前没条件、这样解锁」。⚠ 有降级路径的工具别标。
     */
    val requires: ToolRequirement? get() = null

    /**
     * 这个工具的**结果**不该写进持久对话记录——只在产生它的那一轮里对模型可用。
     *
     * 为什么要有：`read_sms` 把短信正文（含验证码）、`notification` 把通知正文、`clipboard` 把剪贴板
     * 原样返回，这些内容作为一条 `role="tool"` 的消息进消息列表 → [com.arix.app.ConversationManager.saveMessages]
     * → Room → 再被 GitHub/WebDAV/S3 备份**上云**。也就是说用户的验证码短信会以明文躺在他的备份仓库里，
     * 而他当初只是想让 AI 帮忙念一下验证码。标了这个的工具，落库时那条 tool 消息的 `content` 会被换成
     * 一句占位说明（见 [SensitiveResultPolicy]）；**内存里当轮那份不动**，模型这一轮照常拿原文干活。
     *
     * ⚠ 别乱标。标了 = 这个工具的历史结果重开 App 后就没了，模型会"失忆"。只标那种
     * 「正文本身就是隐私、且随时能重新读一次」的读取型工具；一般工具的结果是对话事实，必须留着。
     */
    val ephemeralResult: Boolean get() = false

    suspend fun execute(params: JSONObject): ToolResult

    fun toOpenAiTool(): JSONObject = JSONObject().apply {
        put("type", "function")
        put("function", JSONObject().apply {
            put("name", name)
            put("description", llmDescription)   // 模型侧用英文那份（没覆写就等于 description）
            put("parameters", parameters)
        })
    }
}
