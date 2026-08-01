package com.arix.app

import com.arix.cloudapi.model.ChatMessage
import org.json.JSONArray
import org.json.JSONObject

/**
 * 会话分支树：支持消息 fork / 回答变体（像 ChatGPT 的 ‹1/3› 切换）。
 *
 * - 每条消息 = 一个节点（[Node.id] 稳定 Long / parentId / activeChildId / msg）。
 * - 根 = 虚拟节点 id=[ROOT]（不含消息）。
 * - 「活动路径」= 从 [activeLeaf] 沿 parentId 回溯到根再反转，即当前线性对话（= messagesJson）。
 * - 同一 parent 下的多个子节点 = 兄弟 = 该位置的变体分支（按 id 升序 = 创建先后）。
 *
 * 关键设计：[commitActivePath] 用「最长未变前缀复用旧节点、分叉处铸新节点」的差分策略，
 * 因此 ChatScreen 的发送 / 流式 / 工具链路都无需改动——重新生成 / 编辑重发照旧「截断+重发」，
 * 分支会在下一次 persist 的差分里自然产生，旧子树自动保留为兄弟。
 *
 * 纯逻辑、无 Android 依赖，可单元测试。
 */
class MessageTree private constructor(
    private val nodes: MutableMap<Long, Node>,
    var activeLeaf: Long,
    private var seq: Long,
) {
    class Node(
        val id: Long,
        var parentId: Long,
        var activeChildId: Long,   // 0=无。切换兄弟时据此「下潜」恢复上次所在子树
        var msg: ChatMessage,
    )

    // ---- 活动路径 ----

    /** 当前活动路径的节点 id（根→叶顺序，不含根）。 */
    fun activePathIds(): List<Long> {
        val out = ArrayList<Long>()
        var cur = activeLeaf
        var guard = 0
        while (cur != ROOT && nodes[cur] != null && guard++ < MAX_DEPTH) {
            out.add(cur)
            cur = nodes[cur]!!.parentId
        }
        out.reverse()
        return out
    }

    /** 当前活动路径的消息（= 应写入 messagesJson 的线性对话）。 */
    fun deriveActivePath(): List<ChatMessage> = activePathIds().mapNotNull { nodes[it]?.msg }

    val size: Int get() = nodes.size

    /** 是否存在任何分支（否则纯线性，可不落 branchesJson）。 */
    fun hasBranches(): Boolean {
        val counts = HashMap<Long, Int>()
        for (n in nodes.values) counts[n.parentId] = (counts[n.parentId] ?: 0) + 1
        return counts.values.any { it > 1 }
    }

    // ---- 差分提交：把「当前线性消息列表」并入树，自动识别分叉 ----

    fun commitActivePath(msgs: List<ChatMessage>) {
        val prev = activePathIds()
        val newIds = ArrayList<Long>(msgs.size)
        var diverged = false
        for (i in msgs.indices) {
            val parentId = if (i == 0) ROOT else newIds[i - 1]
            if (!diverged && i < prev.size) {
                val cand = nodes[prev[i]]
                if (cand != null && cand.parentId == parentId && sameMsg(cand.msg, msgs[i])) {
                    cand.msg = msgs[i]            // 刷新（token/附件等可能补全），结构不变
                    newIds.add(cand.id)
                    continue
                }
            }
            diverged = true                       // 一旦分叉，其后全部铸新节点
            val id = ++seq
            nodes[id] = Node(id, parentId, NONE, msgs[i])
            newIds.add(id)
        }
        // 沿新活动链设置 parent.activeChild（旧兄弟节点的指针一律不动，从而保留分支）
        var p = ROOT
        for (id in newIds) {
            nodes[p]?.activeChildId = id
            p = id
        }
        activeLeaf = newIds.lastOrNull() ?: ROOT
    }

    /** 就地刷新活动路径各节点内容（不改结构、不生成分支）。用于「修改」AI 消息文本。 */
    fun syncActiveContents(msgs: List<ChatMessage>) {
        val ids = activePathIds()
        if (msgs.size != ids.size) { commitActivePath(msgs); return }
        for (i in msgs.indices) nodes[ids[i]]?.msg = msgs[i]
    }

    /** 丢弃全部分支、以当前线性消息重建为一条链。用于删除/回滚等破坏性编辑（分支塌缩）。 */
    fun resetLinear(msgs: List<ChatMessage>) {
        nodes.clear()
        activeLeaf = ROOT
        commitActivePath(msgs)
    }

    // ---- 变体查询 / 切换（按活动路径下标） ----

    /** 活动路径下标 [index] 处消息的变体信息：(第k个, 共n个)；无兄弟返回 null（不显示箭头）。 */
    fun variantInfoAt(index: Int): Pair<Int, Int>? {
        val path = activePathIds()
        if (index !in path.indices) return null
        val node = nodes[path[index]] ?: return null
        val sibs = childrenOf(node.parentId)
        if (sibs.size <= 1) return null
        val k = sibs.indexOf(node.id)
        if (k < 0) return null
        return (k + 1) to sibs.size
    }

    /**
     * 一次性算出活动路径**每个下标**的变体信息（(第k个,共n个) 或 null）。
     *
     * 为什么要有它：[variantInfoAt] 每调一次都 `activePathIds()`(O(路径长)) + `childrenOf()`
     * (`nodes.values.filter().sorted()` = O(全节点数·log))。列表渲染时若**每条气泡各调一次**，
     * 快滑（每条滚进来都是新组合）就是 O(全节点数 × 滚进条数)，纯主线程 CPU，是快滑掉帧的一大来源。
     * 这里全遍历只做**一次**：建「父→已排序子列表」一遍，再按路径 O(1) 取每条的兄弟排名。
     * 调用方按 treeVersion 记忆化，每条气泡 `getOrNull(idx)` 即可。
     */
    fun allVariantInfos(): List<Pair<Int, Int>?> {
        val path = activePathIds()
        if (path.isEmpty()) return emptyList()
        val childrenByParent = HashMap<Long, MutableList<Long>>()
        for (n in nodes.values) childrenByParent.getOrPut(n.parentId) { ArrayList() }.add(n.id)
        for (list in childrenByParent.values) list.sort()
        return path.map { id ->
            val node = nodes[id] ?: return@map null
            val sibs = childrenByParent[node.parentId] ?: return@map null
            if (sibs.size <= 1) return@map null
            val k = sibs.indexOf(id)
            if (k < 0) null else (k + 1) to sibs.size
        }
    }

    /** 把活动路径下标 [index] 处切到相邻兄弟（dir=-1 左 / +1 右），并「下潜」恢复该分支子树。到头不循环。返回是否切换成功。 */
    fun switchAt(index: Int, dir: Int): Boolean {
        val path = activePathIds()
        if (index !in path.indices) return false
        val node = nodes[path[index]] ?: return false
        val sibs = childrenOf(node.parentId)
        if (sibs.size <= 1) return false
        val cur = sibs.indexOf(node.id)
        val next = cur + dir
        if (cur < 0 || next < 0 || next >= sibs.size) return false
        val target = sibs[next]
        nodes[node.parentId]?.activeChildId = target
        // 从 target 沿 activeChild 下潜到叶（恢复上次所在的后续子树）
        var leaf = target
        var guard = 0
        while (guard++ < MAX_DEPTH) {
            val c = nodes[leaf]?.activeChildId ?: NONE
            if (c == NONE || nodes[c] == null || c == leaf) break
            leaf = c
        }
        activeLeaf = leaf
        return true
    }

    private fun childrenOf(parentId: Long): List<Long> =
        nodes.values.asSequence().filter { it.parentId == parentId }.map { it.id }.sorted().toList()

    private fun sameMsg(a: ChatMessage, b: ChatMessage): Boolean =
        a.role == b.role &&
        a.content == b.content &&
        a.toolCallId == b.toolCallId &&
        a.attachments == b.attachments &&
        toolSig(a.toolCalls) == toolSig(b.toolCalls)

    private fun toolSig(tcs: List<ChatMessage.ToolCallMsg>?): String =
        tcs?.joinToString("|") { "${it.id}${it.name}${it.arguments}" } ?: ""

    // ---- 序列化 ----

    fun toJson(): String {
        val arr = JSONArray()
        for (n in nodes.values) {
            arr.put(JSONObject().apply {
                put("id", n.id)
                put("parent", n.parentId)
                put("activeChild", n.activeChildId)
                put("msg", msgToJson(n.msg))
            })
        }
        return JSONObject().apply {
            put("activeLeaf", activeLeaf)
            put("nodes", arr)
        }.toString()
    }

    companion object {
        const val ROOT = 0L
        const val NONE = 0L
        private const val MAX_DEPTH = 100_000

        fun empty(): MessageTree = MessageTree(mutableMapOf(), ROOT, 0L)

        /** 从旧的线性消息列表建树（每条一节点，链式）。 */
        fun fromFlat(msgs: List<ChatMessage>): MessageTree =
            empty().apply { commitActivePath(msgs) }

        /** 反序列化；json 为空 / 解析失败返回 null（调用方回退 fromFlat）。 */
        fun fromJson(json: String?): MessageTree? {
            if (json.isNullOrBlank()) return null
            return try {
                val root = JSONObject(json)
                val arr = root.getJSONArray("nodes")
                val map = HashMap<Long, Node>(arr.length())
                var maxId = 0L
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val id = o.getLong("id")
                    map[id] = Node(
                        id = id,
                        parentId = o.optLong("parent", ROOT),
                        activeChildId = o.optLong("activeChild", NONE),
                        msg = msgFromJson(o.getJSONObject("msg")),
                    )
                    if (id > maxId) maxId = id
                }
                if (map.isEmpty()) return null
                MessageTree(map, root.optLong("activeLeaf", ROOT), maxId)
            } catch (_: Exception) { null }
        }

        private fun msgToJson(msg: ChatMessage): JSONObject = JSONObject().apply {
            put("role", msg.role)
            put("content", msg.content)
            if (!msg.reasoning.isNullOrBlank()) put("reasoning", msg.reasoning)
            // 消息级思考签名同理：切分支后那条 assistant 还要被重新发出去，不带就被上游拒收
            if (msg.extra != null) put("extra", msg.extra)
            msg.toolCalls?.takeIf { it.isNotEmpty() }?.let { tcs ->
                put("toolCalls", JSONArray().apply {
                    // extra=供应商私有扩展（Gemini 3 思考签名），分支树也要存，否则切分支/续聊会 400
                    tcs.forEach { tc -> put(JSONObject().apply { put("id", tc.id); put("name", tc.name); put("arguments", tc.arguments); if (tc.extra != null) put("extra", tc.extra) }) }
                })
            }
            if (msg.toolCallId != null) put("toolCallId", msg.toolCallId)
            msg.attachments?.takeIf { it.isNotEmpty() }?.let { atts ->
                put("attachments", JSONArray().apply { atts.forEach { put(it) } })
            }
            msg.totalTokens?.let {
                put("totalTokens", it)
                put("promptTokens", msg.promptTokens ?: 0)
                put("completionTokens", msg.completionTokens ?: 0)
                if (msg.tokensPerSec != null) put("tokensPerSec", msg.tokensPerSec)
            }
            if (!msg.model.isNullOrBlank()) put("model", msg.model)
        }

        private fun msgFromJson(o: JSONObject): ChatMessage {
            val tcArr = o.optJSONArray("toolCalls")
            return ChatMessage(
                role = o.getString("role"),
                content = o.optString("content", ""),
                reasoning = if (o.has("reasoning") && !o.isNull("reasoning")) o.getString("reasoning") else null,
                extra = if (o.has("extra") && !o.isNull("extra")) o.getString("extra") else null,
                totalTokens = if (o.has("totalTokens")) o.optInt("totalTokens") else null,
                promptTokens = if (o.has("promptTokens")) o.optInt("promptTokens") else null,
                completionTokens = if (o.has("completionTokens")) o.optInt("completionTokens") else null,
                tokensPerSec = if (o.has("tokensPerSec")) o.optDouble("tokensPerSec") else null,
                model = if (o.has("model")) o.optString("model") else null,
                toolCalls = if (tcArr != null && tcArr.length() > 0) {
                    (0 until tcArr.length()).map { j ->
                        val tcj = tcArr.getJSONObject(j)
                        ChatMessage.ToolCallMsg(
                            tcj.getString("id"), tcj.getString("name"), tcj.getString("arguments"),
                            extra = if (tcj.has("extra") && !tcj.isNull("extra")) tcj.getString("extra") else null
                        )
                    }
                } else null,
                toolCallId = if (o.has("toolCallId") && !o.isNull("toolCallId")) o.getString("toolCallId") else null,
                attachments = o.optJSONArray("attachments")?.let { a -> (0 until a.length()).map { a.getString(it) } },
            )
        }
    }
}
