package com.arix.tool

import com.arix.app.MemoryManager
import com.arix.data.entity.MemoryEntity

/**
 * 文档知识库 —— 切块、入库、按文档聚合。
 *
 * ## 为什么这层要存在
 *
 * 在这之前项目里有**两个互相看不见的知识库**：
 *  - `doc_read(into_memory=true)`：把文档按段落切成 ~700 字的块，逐块写进长期记忆，**带语义索引**；
 *  - `rag(add/search)`：另存一份 `filesDir/rag_docs` 下的 txt，检索时**整篇打一个分**，命中只回**前 400 字**。
 *
 * 两个库的坏处不是"重复"，是**互相搜不到**：模型用 `rag add` 存的东西，`doc_read` 检索不到；
 * 反过来也一样。而模型没有任何理由知道该用哪个——它只会随机选一个，然后在另一个里找不到，
 * 于是断言"我没有这份资料"。这正是本项目定过的「工具要 1 用多、数量精简防幻觉」要避免的局面。
 *
 * 另外 `rag` 那条路还重犯了一个项目已经吃过教训的错：**只回前 400 字**。
 * 长文档的答案常常在中段或结尾（同 `ToolOutputStore` 那条"只 take(3000) 等于把尾巴丢了"），
 * 整篇匹配 + 截头部，等于结构性地取不到大部分答案。
 *
 * 所以这里把「切块 + 入库 + 命名约定」抽成一处，两个工具共用同一个库、同一套约定。
 */
object DocChunker {

    /** 单块目标长度（字符）。与 DocReadTool 历史取值一致，改这里两边一起变。 */
    const val CHUNK = 700

    /** 单篇文档最多存多少块。上限保护：别让一本书把记忆库撑爆。 */
    const val MAX_CHUNKS = 60

    /** 文档块在记忆库里的来源标记。检索时靠它把"文档"和"关于用户的记忆"分开。 */
    const val SOURCE = "document"

    /** 块标题前缀。靠它做幂等重导入与按文档聚合，**两个工具必须用同一个**，否则又变成两个库。 */
    fun titlePrefix(docName: String) = "文档《$docName》· 段"

    fun titleOf(docName: String, index: Int, total: Int) = "${titlePrefix(docName)}${index + 1}/$total"

    /** 从块标题反解出文档名；不是文档块则返回 null。 */
    fun docNameOf(title: String): String? {
        if (!title.startsWith("文档《")) return null
        val end = title.indexOf("》")
        return if (end > 3) title.substring(3, end) else null
    }

    /**
     * 按段落边界切块，尽量不切断句：先按空行拆段，超长段再硬切，然后贪心地把相邻小段并到 [CHUNK] 以内。
     */
    fun chunk(text: String): List<String> {
        val clean = text.trim()
        if (clean.isBlank()) return emptyList()
        val paras = clean.split(Regex("\\n{2,}"))
            .flatMap { p -> if (p.length <= CHUNK) listOf(p) else p.chunked(CHUNK) }
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (p in paras) {
            val seg = p.trim(); if (seg.isBlank()) continue
            if (sb.isNotEmpty() && sb.length + seg.length + 1 > CHUNK) { out.add(sb.toString()); sb.setLength(0) }
            if (sb.isNotEmpty()) sb.append('\n')
            sb.append(seg)
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

    /** 入库结果：存了几块、原本几块、是否因为太长被截。 */
    data class Stored(val ok: Int, val total: Int, val truncated: Boolean, val lastError: String?)

    /**
     * 把一篇文档切块存进长期记忆（会自动建语义索引）。
     *
     * 幂等：先删掉同名文档上次导入的全部分段，重复导入不会堆成一堆重复记忆。
     *
     * @param cardId 归属角色卡；null = 通用。文档一般不该跟着某张卡走，但调用方可以指定。
     */
    suspend fun store(mm: MemoryManager, docName: String, text: String, cardId: Long? = null): Stored {
        val all = chunk(text)
        if (all.isEmpty()) return Stored(0, 0, false, "没有可存的文字内容")
        val chunks = all.take(MAX_CHUNKS)
        val prefix = titlePrefix(docName)
        // 幂等清理。用 search(docName) 而不是全表扫：文档名本身就是最强的关键词信号。
        runCatching { mm.search(docName).filter { it.title.startsWith(prefix) }.forEach { mm.delete(it.id) } }
        var ok = 0; var lastErr: String? = null
        val ids = ArrayList<Long>(chunks.size)
        chunks.forEachIndexed { i, ch ->
            try {
                ids.add(mm.add(
                    title = titleOf(docName, i, chunks.size), content = ch,
                    source = SOURCE, importance = 0.4f, characterCardId = cardId, type = "fact",
                ))
                ok++
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c   // STOP 要能中断，别把取消吞成"这一块失败"
            } catch (e: Exception) { lastErr = e.message }
        }
        // ⭐ 相邻块之间连边。不连的话这些块在记忆图里是 N 个**完全孤立**的点。
        //
        // 为什么这很要紧：检索命中的是**一个块**，而答案经常横跨块边界——上面已经解决了
        // 「块内只回前 400 字」，但「块间断裂」是另一半。记忆检索里有一套现成的两跳邻居展开
        // （MemoryManager.expandNeighbors，HOP_MAX=2/NEIGHBOR_MAX=8），它正是用来捞
        // 「字面和语义都没命中、但确实相关」的东西的——可它在一张空图上跑，等于没写。
        // 连上链之后，命中第 i 块会自动把 i±1（两跳内 i±2）一起带进上下文，**检索逻辑一行都不用改**。
        //
        // 只连相邻、不建「文档根节点」：根节点没有正文，是一行空记忆，除了让列表变脏没别的用；
        // 而「顺着结构读到上下文」这个需求，相邻链已经满足了。
        // 边权给 0.9（略低于默认 1.0）：它是结构关系不是语义关系，不该压过真正相关的记忆。
        for (i in 0 until ids.size - 1) {
            try { mm.linkPair(ids[i], ids[i + 1], type = "part_of", weight = 0.9f) }
            catch (c: kotlinx.coroutines.CancellationException) { throw c }
            catch (_: Exception) { /* 连边失败不影响正文已入库，静默 */ }
        }
        return Stored(ok, chunks.size, all.size > MAX_CHUNKS, lastErr)
    }

    /** 一篇已入库文档的概览。 */
    data class DocInfo(val name: String, val chunks: Int, val chars: Int)

    /** 把记忆里的文档块按文档名聚合。 */
    fun summarize(all: List<MemoryEntity>): List<DocInfo> =
        all.asSequence()
            .filter { it.source == SOURCE }
            .mapNotNull { m -> docNameOf(m.title)?.let { it to m } }
            .groupBy({ it.first }, { it.second })
            .map { (name, ms) -> DocInfo(name, ms.size, ms.sumOf { it.content.length }) }
            .sortedBy { it.name }
}
