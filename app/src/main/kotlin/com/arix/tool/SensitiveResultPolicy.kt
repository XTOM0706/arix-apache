package com.arix.tool

import com.arix.app.tr
import com.arix.cloudapi.model.ChatMessage
import org.json.JSONObject

/**
 * 「敏感工具结果只在当轮可用」的策略表 + 落库前的改写。
 *
 * 背景：短信/通知/剪贴板这三类工具把**正文原样**返回给模型（验证码、私聊内容、刚复制的密码），
 * 这条结果作为 `role="tool"` 消息进消息列表，被 [com.arix.app.ConversationManager.saveMessages]
 * 写进 Room，再被备份功能（GitHub 私仓 / WebDAV / S3）整包传上去。用户让 AI 念一次验证码，
 * 代价是这条验证码永久留在云端备份里——他不会想到这一层，所以得我们替他兜。
 *
 * 做法：这类工具的结果**只在产生它的那一轮**对模型可用（内存里那份原封不动，模型这轮照常干活），
 * 写盘时把那条 tool 消息的 `content` 换成一句占位说明。
 *
 * ⚠ 三条不能碰的底线（写这段时最容易踩的）：
 * 1. **不能删这条消息**，也不能改它的 `toolCallId`。OpenAI 协议要求每个 `tool_call_id` 都有配对的
 *    tool 结果，缺一条下一轮请求直接 400——项目里为收拾这种残缺序列专门写了
 *    [com.arix.app.ContextCompressor.sanitizePairing]。这里只换 `content`，配对天然不变。
 * 2. **不能动内存里那份**。模型这一轮还要拿短信正文去干活（念验证码、按通知内容回复），
 *    落库和送模型是两条路：这里返回的是新 list（`copy` 出来的），调用方原来的 list 一个字没改。
 * 3. **占位符必须说明白"这里原本有东西、现在看不到了"**。悄悄抽走的下场见
 *    [com.arix.app.ContextCompressor.evictOldImages] 那段注释：模型会照着上文的只言片语一本正经地
 *    "回忆"它其实看不到的内容。明说反而会老老实实回一句"我得重新读一次"。
 */
object SensitiveResultPolicy {

    /**
     * 兜底名单：即便这一刻在 [ToolManager] 里查不到这个工具，也按敏感处理。
     *
     * 为什么要有这层冗余（标记明明写在 [Tool.ephemeralResult] 上了）：落库这一步只拿得到**工具名**，
     * 要靠工具表反查实例。工具表是运行期状态（`ConcurrentHashMap`，卸包/装 MCP 都在改它），
     * 万一那一刻查不到（进程刚起还没注册完、包被卸载、名字被 `ext_` 前缀改写过），
     * 反查就返回 null——而这条路上"查不到"的默认结果是**原文照样落盘**，属于 fail-open。
     * 隐私上不能 fail-open，所以这三个内置工具的名字硬钉在这里。
     *
     * ⚠ 新工具优先只改 [Tool.ephemeralResult]（声明跟着实现走，不会忘）；只有内置的、
     * 泄露后果严重的才顺手加进这份名单。
     */
    private val ALWAYS_EPHEMERAL = setOf("read_sms", "notification", "clipboard")

    /** 这个工具名产生的结果该不该被替换。名单命中 或 工具自己声明了 [Tool.ephemeralResult]。 */
    fun isEphemeral(toolName: String): Boolean =
        toolName in ALWAYS_EPHEMERAL || ToolManager.get(toolName)?.ephemeralResult == true

    /**
     * 替换后写进库里的那句话。它有两个读者，都得照顾到：
     * - **模型**（重开 App 续聊时会把它当历史发回去）：得知道"这里原本有内容、现在没了、想要就重调一次"，
     *   否则它会凭上文瞎编（见类注释第 3 条）。工具名带上，它才知道该重调哪个。
     * - **用户**（聊天界面里会显示这条 tool 消息）：所以走 [tr]。
     */
    // ⚠ 这句必须是**一整条字符串字面量**、别拆成 + 拼接：i18n 抽取脚本 (tools/i18n_wrap.py) 认的是
    //   `tr("…")` 里紧跟的单个字面量，拼接串它扫不到，译表里就永远缺这条。
    fun placeholderFor(toolName: String): String =
        tr("（这条结果含隐私内容（短信/通知/剪贴板正文），只在当时那一轮可用，没有保存原文。你现在看不到它了——需要的话重新调一次工具读最新的，不要凭印象复述。）") +
            " [tool: $toolName]"

    /**
     * 落库前改写：把敏感工具产生的那条 tool 消息的 `content` 换成占位说明，其余原样。
     *
     * 怎么知道某条 tool 消息是哪个工具产生的：`ChatMessage` 里 tool 消息只带 `toolCallId`，
     * 工具名在**上一条 assistant** 的 `toolCalls[].id/name` 里，所以先扫一遍建 id→name 的索引。
     * 索引里查不到（那条 assistant 已被裁掉/压缩掉）就保守不改——认不出是谁产的，
     * 就没资格断言它敏感；这种残片本来也会被 `sanitizePairing` 在发送前丢掉。
     *
     * 返回新 list（没命中就返回入参本身，普通对话零开销）。**绝不原地改**——入参就是内存里
     * 那份还要发给模型的消息列表。
     */
    fun redactForPersistence(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.none { it.role == "tool" }) return messages   // 绝大多数对话根本没有工具消息
        val nameById = HashMap<String, String>()
        messages.forEach { m -> m.toolCalls?.forEach { nameById[it.id] = it.name } }
        var hit = false
        val out = messages.map { m ->
            val name = if (m.role == "tool") m.toolCallId?.let { nameById[it] } else null
            if (name != null && isEphemeral(name) && m.content.isNotBlank()) {
                hit = true
                // 只换 content：role / toolCallId / 位置全不动 → tool_call 配对不会被破坏
                m.copy(content = placeholderFor(name))
            } else m
        }
        return if (hit) out else messages
    }

    /**
     * 分支树那条落库路径的同款改写。
     *
     * ⚠ 少了这一半，上面那一半基本等于白做：会话有分支时（**重新生成一次就会产生分支**，
     * 所以这是常态不是特例），完整消息**另有一份**序列化在 `branchesJson` 里，经
     * [com.arix.app.ConversationManager.saveBranches] 原样写进 Room、原样进备份。
     * 只堵 messagesJson 而不堵这里，敏感正文照样上云。
     *
     * 树的形状（见 `MessageTree.toJson` / `msgToJson`）：
     * `{ "nodes":[ { …, "msg":{ "role", "content", "toolCalls":[{"id","name",…}], "toolCallId" } } ] }`
     *
     * 这里在 **JSON 层**改而不是反序列化成对象再改：`msgToJson` 存了 extra/思考签名/usage 等一串字段，
     * 走一遍对象往返任何一个字段没接住都会导致切分支后被上游拒收（项目里为思考签名踩过这个坑）。
     * 只定点改 `content` 一个键，其余原封不动，是这里唯一安全的做法。
     *
     * @return 改写后的 JSON；入参为 null/解析失败/没有命中时**原样返回**（宁可不改，不能改坏）。
     */
    fun redactBranchesJson(json: String?): String? {
        if (json.isNullOrBlank()) return json
        return try {
            val root = JSONObject(json)
            val nodes = root.optJSONArray("nodes") ?: return json
            // 第一趟：从所有带 toolCalls 的节点收 id → 工具名。
            // 必须先扫全树再改：分支树里 assistant 和它的 tool 结果**不保证相邻**，也不保证顺序在前。
            val idToName = HashMap<String, String>()
            for (i in 0 until nodes.length()) {
                val msg = nodes.optJSONObject(i)?.optJSONObject("msg") ?: continue
                val tcs = msg.optJSONArray("toolCalls") ?: continue
                for (j in 0 until tcs.length()) {
                    val tc = tcs.optJSONObject(j) ?: continue
                    val id = tc.optString("id", ""); val name = tc.optString("name", "")
                    if (id.isNotBlank() && name.isNotBlank()) idToName[id] = name
                }
            }
            if (idToName.isEmpty()) return json
            // 第二趟：命中的 tool 节点只换 content
            var hit = false
            for (i in 0 until nodes.length()) {
                val msg = nodes.optJSONObject(i)?.optJSONObject("msg") ?: continue
                if (msg.optString("role") != "tool") continue
                val callId = msg.optString("toolCallId", "").takeIf { it.isNotBlank() } ?: continue
                val name = idToName[callId] ?: continue   // 认不出是谁产的就不断言它敏感（同 saveMessages 那侧）
                if (!isEphemeral(name)) continue
                msg.put("content", placeholderFor(name))
                hit = true
            }
            if (hit) root.toString() else json
        } catch (_: Exception) { json }
    }
}
