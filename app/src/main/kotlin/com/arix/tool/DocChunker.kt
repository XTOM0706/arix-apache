package com.arix.tool

import com.arix.app.MemoryManager
import com.arix.app.MemoryEntity

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
        chunks.forEachIndexed { i, ch ->
            try {
                mm.add(
                    title = titleOf(docName, i, chunks.size), content = ch,
                    source = SOURCE, importance = 0.4f, characterCardId = cardId, type = "fact",
                )
                ok++
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c   // STOP 要能中断，别把取消吞成"这一块失败"
            } catch (e: Exception) { lastErr = e.message }
        }
        // 相邻块连边（记忆图谱）在 Apache-2.0 精简版已移除：记忆改为纯文件存储，无图可连。
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
