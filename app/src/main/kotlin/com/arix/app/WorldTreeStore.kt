package com.arix.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// 世界书：管理"世界背景"。文件持久化（避免动 Room 迁移）。多个角色卡可绑定同一本世界书。
// 默认（未绑定）= 无世界书。角色卡→世界书 的绑定存 SharedPreferences。
//
// 结构：一本世界书 Tree = 名字/描述 + 若干条目 Entry，**每条 Entry 各有自己的**触发词/正文/注入位/深度/顺序/常驻/启用。
// 为什么必须是「按条」：酒馆(SillyTavern)生态的 lorebook 本来就是按条走的——一张卡带 50 条，每条各有 keys/depth/order/constant，
// 只有被提到的那几条才进上下文。之前我们一本书只有一组设置（= 一条 entry），导进来的 50 条被压成一坨纯文本、
// 变成**无条件常驻**永久占着上下文：既烧 token 又把不相干的设定摊到模型面前。
object WorldTreeStore {

    /**
     * 世界书条目。
     * keywords: 触发词/正则（逗号或换行分隔），为空=不看触发词直接算命中（与老语义一致）。
     * position: system=进系统提示 / user=拼进本轮用户消息。注意 depth>0 时 position 不生效（深度注入优先，与旧行为一致）。
     * depth:    0=不按深度注入；N=插到「倒数第 N 个用户回合」之前（落点算法在 ChatScreen.injectWbDepth，按 user 消息计数）。
     * order:    同一注入位内排序，小的在前。
     * constant: 常驻——不看触发词，永远注入（对齐酒馆的 constant / 蓝灯）。
     */
    data class Entry(
        val id: Long = 0L,
        val name: String = "",              // 条目名/备注，只给人看（对应酒馆的 comment）
        val keywords: String = "",
        val content: String = "",
        val position: String = "system",
        val depth: Int = 0,
        val order: Int = 0,
        val constant: Boolean = false,
        val enabled: Boolean = true,
    )

    /**
     * 一本世界书。
     *
     * content / keywords / position / injectDepth 这四个是**旧版「整本书一组设置」的字段**，条目化之后它们退化成
     * 「兼容镜像」：保存时由 entries 反算写回去。留着不是历史包袱，是为了那些**还按整本书取正文**的调用方
     * （备份导出 GitHubBackup、角色卡页、全局搜索 LocalSearchTool，以及尚未改造的注入路径）继续拿得到东西——
     * 改结构不该让它们读到空。真正的求值一律走 entries / activeEntries()。
     * order 仍是本书在列表里的排序，不属于镜像。
     */
    data class Tree(
        val id: Long,
        val name: String,
        val description: String,
        val content: String,
        val keywords: String = "",
        val position: String = "system",
        val injectDepth: Int = 0,
        val order: Int = 0,
        val entries: List<Entry> = emptyList(),
    )

    private fun file(c: Context) = File(c.filesDir, "world_trees.json")
    private fun readArr(c: Context): JSONArray = try { if (file(c).exists()) JSONArray(file(c).readText()) else JSONArray() } catch (_: Exception) { JSONArray() }
    private fun writeArr(c: Context, arr: JSONArray) { try { file(c).writeText(arr.toString()) } catch (_: Exception) {} }

    // ==================== 序列化 ====================

    /** 单条反序列化。enabled 缺省=true（老/外来数据大多不写这个键，缺了不能当"禁用"）。 */
    private fun JSONObject.toEntry(fallbackId: Long) = Entry(
        id = optLong("id", 0L).let { if (it > 0) it else fallbackId },
        name = optString("name"),
        keywords = optString("keywords"),
        content = optString("content"),
        position = optString("position", "system").ifBlank { "system" },
        depth = optInt("depth", 0),
        order = optInt("order", 0),
        constant = optBoolean("constant", false),
        enabled = optBoolean("enabled", true),
    )

    fun entryToJson(e: Entry): JSONObject = JSONObject()
        .put("id", e.id).put("name", e.name).put("keywords", e.keywords).put("content", e.content)
        .put("position", e.position).put("depth", e.depth).put("order", e.order)
        .put("constant", e.constant).put("enabled", e.enabled)

    fun entriesToJson(list: List<Entry>): JSONArray = JSONArray().also { arr -> list.forEach { arr.put(entryToJson(it)) } }

    /** 供导入侧（ImportConverters/ImportExport）把外来的条目数组转成本应用的 Entry。 */
    fun entriesFromJson(arr: JSONArray?): List<Entry> {
        val out = ArrayList<Entry>()
        if (arr == null) return out
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it.toEntry((i + 1).toLong())) }
        return out
    }

    /**
     * 整本反序列化 + ⭐兼容折叠。
     *
     * 为什么这一步是硬要求：老的 world_trees.json 里**根本没有 entries 这个键**——一本书就是一组
     * content/keywords/position/injectDepth。如果按新结构直接读，老用户升级后每本书都是「零条目」：
     * 列表里书还在、点开正文还在（content 字段没动），但求值时一条都不激活 —— 世界书**静悄悄地失效**，
     * 这种坏法比崩溃还难被发现。所以没有 entries 时，把整本书**折叠成正好一条 entry**，字段一一对应搬过去，
     * 升级后的行为与升级前逐字一致。
     * constant 由「触发词是否为空」还原：老语义就是「触发词留空 = 常驻注入」。
     */
    private fun JSONObject.toTree(): Tree {
        val content = optString("content")
        val keywords = optString("keywords")
        val position = optString("position", "system").ifBlank { "system" }
        val injectDepth = optInt("injectDepth", 0)
        val order = optInt("order", 0)

        val entries = entriesFromJson(optJSONArray("entries")).toMutableList()
        if (entries.isEmpty() && (content.isNotBlank() || keywords.isNotBlank())) {
            entries.add(Entry(
                id = 1L, name = "", keywords = keywords, content = content,
                position = position, depth = injectDepth, order = order,
                constant = keywords.isBlank(), enabled = true,
            ))
        }
        // 镜像正文：老书直接用它自己的 content；新书（有 entries）万一 content 没写，就现拼一份，
        // 免得按整本取正文的老调用方读到空。
        val mirror = if (content.isNotBlank()) content else joinContent(entries.filter { it.enabled })
        return Tree(optLong("id"), optString("name"), optString("description"), mirror, keywords, position, injectDepth, order, entries)
    }

    private fun joinContent(list: List<Entry>) = list.joinToString("\n\n") { it.content.trim() }.trim()

    /**
     * 由 entries 反算旧字段镜像。
     * keywords 的算法要保守：只要有**任何一条**是常驻/无触发词，整本的镜像触发词就得留空（=常驻），
     * 否则还按整本判触发的老调用方会把那条常驻内容一起漏掉。其余情况把各条触发词并起来（并集，宁可多注入不可少）。
     */
    private fun mirrorKeywords(list: List<Entry>): String {
        val on = list.filter { it.enabled && it.content.isNotBlank() }
        if (on.isEmpty()) return ""
        if (on.any { it.constant || it.keywords.isBlank() }) return ""
        return on.joinToString("\n") { it.keywords.trim() }.trim()
    }

    // ==================== 求值（按条） ====================

    /** keywords 为空=常驻；否则任一词(子串,或作为正则)命中 text 才算激活。 */
    fun keywordActive(keywords: String, text: String): Boolean {
        val kw = keywords.trim(); if (kw.isBlank()) return true
        return kw.split('\n', ',').map { it.trim() }.filter { it.isNotBlank() }.any { k ->
            text.contains(k, true) || runCatching { Regex(k, RegexOption.IGNORE_CASE).containsMatchIn(text) }.getOrDefault(false)
        }
    }

    /** 整本判定（老调用方用；按整本的镜像触发词判）。新代码请用 activeEntries/buildInjection。 */
    fun isActive(t: Tree, text: String) = keywordActive(t.keywords, text)

    /** 单条判定：启用 + 有正文 + (常驻 或 触发词命中)。触发词匹配语义与整本时代完全一致，共用 keywordActive。 */
    fun isEntryActive(e: Entry, text: String): Boolean =
        e.enabled && e.content.isNotBlank() && (e.constant || keywordActive(e.keywords, text))

    /**
     * 按条求值：给定「最近对话文本」，返回本轮该注入的条目。
     * 排序：order 升序（sortedBy 是稳定排序，同 order 保持用户在编辑器里排的先后）。
     */
    fun activeEntries(t: Tree?, recentText: String): List<Entry> {
        val tree = t ?: return emptyList()
        return tree.entries.filter { isEntryActive(it, recentText) }.sortedBy { it.order }
    }

    /** 一段按深度注入的文本：插到倒数第 depth 个用户回合之前。 */
    data class DepthChunk(val depth: Int, val text: String)

    /**
     * 求值结果，已按注入位分好组、各组正文拼好，调用方直接用即可。
     * system: 进系统提示的合并文本；user: 拼进本轮用户消息的合并文本；depths: 按深度注入的分段（深的在前）。
     * 三者互不重叠：每条 entry 只会落进其中一个桶。
     */
    data class Injection(val system: String, val user: String, val depths: List<DepthChunk>) {
        val isEmpty: Boolean get() = system.isBlank() && user.isBlank() && depths.isEmpty()
    }

    /**
     * 一次求值出「该注入什么、注到哪」。这是给注入侧（ChatScreen）的唯一入口。
     *
     * @param t          绑定的世界书（null=没绑，返回空）
     * @param recentText 最近对话文本（与旧代码一致：取最近几条消息 + 本轮用户输入拼起来）
     *
     * 分桶规则与旧行为保持一致：depth>0 的条目走深度注入，**压过** position；其余按 position 进系统提示或用户消息。
     */
    fun buildInjection(t: Tree?, recentText: String): Injection {
        val act = activeEntries(t, recentText)
        if (act.isEmpty()) return Injection("", "", emptyList())
        val (deep, shallow) = act.partition { it.depth > 0 }
        return Injection(
            system = joinContent(shallow.filter { it.position != "user" }),
            user = joinContent(shallow.filter { it.position == "user" }),
            // 深的排前面：不同深度落在不同消息上本来互不干扰，但用户回合数不够时会一起落到最早那条 user 上，
            // 这时先插深的、后插浅的，读起来才是「越靠近当下的设定越贴着当前这句话」。
            depths = deep.groupBy { it.depth }.entries.sortedByDescending { it.key }
                .map { (d, l) -> DepthChunk(d, joinContent(l)) },
        )
    }

    // ==================== 读写 ====================

    fun all(c: Context): List<Tree> {
        val arr = readArr(c); val out = ArrayList<Tree>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it.toTree()) }
        // order 升序为主(同深度排序)，其次新的在前(id 降序)——order 让列表/导出/多条注入的先后可控
        return out.sortedWith(compareBy<Tree> { it.order }.thenByDescending { it.id })
    }
    fun get(c: Context, id: Long): Tree? = all(c).firstOrNull { it.id == id }

    /** 便利：取某张角色卡绑定的那本世界书（没绑=null）。 */
    fun forCard(c: Context, cardId: Long?): Tree? =
        cardId?.let { boundTreeId(c, it) }?.let { get(c, it) }

    /**
     * 新建(id<=0)或更新，返回 id。
     *
     * entries == null 表示「老式调用」（导入、AI 生成、角色卡页里顺手建的那本）：这时**不写 entries 键**，
     * 存成老格式，读回来正好走上面的兼容折叠得到一条 —— 与改造前逐字一致，这些调用方一行都不用改。
     * 现存的老式调用方全都是 id=0 新建（ImportExport 两处、CharacterCardPage 两处、本页 AI 生成一处），
     * 所以不存在「老式调用覆盖掉一本已条目化的书」的情况。
     */
    fun save(
        c: Context, id: Long, name: String, description: String, content: String,
        keywords: String = "", position: String = "system", injectDepth: Int = 0, order: Int = 0,
        entries: List<Entry>? = null,
    ): Long {
        val arr = readArr(c)
        val newId = if (id > 0) id else (all(c).maxOfOrNull { it.id } ?: 0L) + 1
        val obj = JSONObject().put("id", newId).put("name", name).put("description", description).put("order", order)
        if (entries == null) {
            obj.put("content", content).put("keywords", keywords).put("position", position).put("injectDepth", injectDepth)
        } else {
            // 重排 id，保证同一本书里条目 id 唯一稳定（UI 的 key 靠它）
            val fixed = entries.mapIndexed { i, e -> if (e.id > 0) e else e.copy(id = (i + 1).toLong()) }
            obj.put("entries", entriesToJson(fixed))
            // 旧字段镜像：让还按整本取正文/判触发的调用方继续正常工作（见 Tree 的注释）。
            // injectDepth 镜像固定写 0：按整本的老路径没法表达「各条各自的深度」，退到系统提示是最安全的落点。
            obj.put("content", joinContent(fixed.filter { it.enabled }))
                .put("keywords", mirrorKeywords(fixed))
                .put("position", "system")
                .put("injectDepth", 0)
        }
        var replaced = false
        for (i in 0 until arr.length()) { if (arr.optJSONObject(i)?.optLong("id") == newId) { arr.put(i, obj); replaced = true; break } }
        if (!replaced) arr.put(obj)
        writeArr(c, arr); return newId
    }

    /** 只改条目、不动名字描述（编辑器里增删改条目走这个）。书不存在则什么都不做，返回 false。 */
    fun saveEntries(c: Context, id: Long, entries: List<Entry>): Boolean {
        val t = get(c, id) ?: return false
        save(c, t.id, t.name, t.description, "", order = t.order, entries = entries)
        return true
    }

    fun delete(c: Context, id: Long) {
        val arr = readArr(c); val out = JSONArray()
        for (i in 0 until arr.length()) { val o = arr.optJSONObject(i); if (o != null && o.optLong("id") != id) out.put(o) }
        writeArr(c, out)
        // 解绑所有引用该书的角色卡
        val p = prefs(c); val e = p.edit()
        p.all.keys.filter { p.getLong(it, -1) == id }.forEach { e.remove(it) }
        e.apply()
    }

    // 角色卡 → 世界书 绑定
    private fun prefs(c: Context) = c.getSharedPreferences("world_tree_bind", Context.MODE_PRIVATE)
    fun boundTreeId(c: Context, cardId: Long): Long? { val v = prefs(c).getLong("card_$cardId", -1L); return if (v > 0) v else null }
    fun bind(c: Context, cardId: Long, treeId: Long?) {
        if (treeId == null || treeId <= 0) prefs(c).edit().remove("card_$cardId").apply()
        else prefs(c).edit().putLong("card_$cardId", treeId).apply()
    }
}
