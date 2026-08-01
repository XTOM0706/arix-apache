package com.arix.tool

import android.content.Context
import java.io.File

/**
 * 超长工具结果**落盘 + 头尾摘要**，让模型自己去取全文，而不是把几万字塞进上下文。
 *
 * 为什么不是「直接截断」：答案常常在尾部（命令的退出信息、搜索的最后一条、日志的报错行），
 * 只留头等于把最有用的部分丢了；而全量内联又会一条结果吃掉半个上下文窗口。
 * RikkaHub（`filesDir/tool_outputs/<id>.txt` + 预览）和 grok-build（内联只占上下文 3%）
 * 各自独立走到了同一形状：**内联给头尾 + 全文落盘 + 告诉它怎么读**。
 *
 * 落点选 AI 工作区（不是 filesDir 根）：这样模型能用现成的 file_read / linux_exec 去读，
 * 不用为此新开一个工具（工具要少而多用）。
 */
object ToolOutputStore {
    private const val DIR = "tool_outputs"
    /** 内联全文的上限：不超过就原样给（只做长行软换行）。 */
    const val INLINE_CAP = 3000
    private const val HEAD = 1500
    private const val TAIL = 1200
    /**
     * 没有可用读取工具时的头尾预算（各翻倍）。
     *
     * 「落盘 + 给条路径」这套只有在模型真能把路径读回来时才成立；读不了的时候路径就是废字，
     * 唯一还能补救的是**多内联一点**——把省下来的那条指令预算换成实际内容。
     */
    private const val HEAD_NO_READER = HEAD * 2
    private const val TAIL_NO_READER = TAIL * 2
    private const val KEEP_FILES = 40
    private const val READER = "read_document"

    private fun dir(c: Context): File = File(AiWorkspace.root(c), DIR).apply { if (!exists()) mkdirs() }

    /**
     * read_document **此刻真的能被调到吗**。
     *
     * 「默认开着」不等于「现在开着」：用户可以把 doc_read 整包关掉、把这个工具的权限策略设成禁止，
     * 角色卡也能把这个包排除在自己的工具范围外。任何一条不成立，「去读这个文件」就是一条死路——
     * 而模型拿到死路的表现是反复重试、或者干脆编一段它没看到的内容，比一开始就不提更糟。
     *
     * 判据逐条对齐 [ToolManager.getToolsJson] 的下发条件（注册过 / 包已启用 / 本机具备运行前提），
     * 外加权限策略不是 FORBID（策略禁止时 execute 会直接挡下，schema 在也没用）。
     * @param excludedPkgs 本张角色卡不带的功能包（见 CardToolStore.excluded）。调用方能拿到就传，
     *   拿不到就默认空集——宁可少判一种情形，也不为此在工具循环里再查一次卡。
     */
    private fun readerAvailable(excludedPkgs: Set<String>): Boolean = try {
        val t = ToolManager.get(READER)
        t != null &&
            ToolManager.isToolEnabled(READER) &&
            CapabilityProbe.has(t.requires) &&
            (ToolManager.packageOf(READER)?.id?.let { it !in excludedPkgs } ?: true) &&
            ToolPermissionManager.effectiveFor(ToolCaller.Ai, t.name, t.permissionLevel) != ToolPermission.FORBID
    } catch (_: Exception) { true }   // 判不出来就当它可用：宁可多给一句路径提示，也别无谓地砍掉内容预算

    /**
     * 把一条工具结果整成「能进上下文」的样子。
     * @return 喂给模型的文本（可能含头尾摘要 + 全文路径）。
     */
    suspend fun forModel(context: Context, toolName: String, full: String, excludedPkgs: Set<String> = emptySet()): String {
        val wrapped = TextWrap.softWrap(full)
        if (wrapped.length <= INLINE_CAP) return wrapped
        // ⚠ suspend + 切 IO：这三个调用点都在工具循环里（主线程协程），落盘走主线程会掉帧
        val rel = try { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { save(context, toolName, full) } } catch (_: Exception) { null }
        // 读不回来就没必要省这点字：预算翻倍，把「怎么读」的指令换成实际内容
        val canRead = rel != null && readerAvailable(excludedPkgs)
        val head = if (canRead) HEAD else HEAD_NO_READER
        val tail = if (canRead) TAIL else TAIL_NO_READER
        // 翻倍后头尾可能盖满全文（3000<len<=5400），这时中间没东西可省，别写出「省略 -1200 字」
        val omitted = wrapped.length - head - tail
        return buildString {
            if (omitted <= 0) append(wrapped)
            else {
                append(wrapped.take(head))
                append("\n\n…（中间省略 ").append(omitted).append(" 字）…\n\n")
                append(wrapped.takeLast(tail))
            }
            append("\n\n[完整结果约 ").append(TextBudget.estimateTokens(full)).append(" token（").append(full.length).append(" 字）")
            when {
                rel == null -> append("过长，未能落盘")
                canRead -> {
                    // ⚠ 这里必须指向**此刻真能调到**的工具。原来写的是 file_read——它在默认关闭的 file_tools 包里，
                    // 等于给了一条它多半打不开的路径（附件那边踩过同一个坑）。read_document 在默认开的 doc_read 包里，
                    // 且已支持 offset/limit/pattern；但「默认开」也可能被关掉，所以每次现查（见 readerAvailable）。
                    append("已存到工作区 ").append(rel).append("，需要更多就去读它：")
                    append(READER).append("(path=\"").append(rel).append("\", pattern=\"正则\") 先定位、")
                    append(READER).append("(path=\"").append(rel).append("\", offset=行号, limit=行数) 再精读")
                }
                // ⚠ 下面这句是给模型看的：说清「路径存着但你读不了」，免得它拿路径去撞一个调不通的工具。
                else -> {
                    // 包名现取（"文档解析"），别写死——包表改了名字这里会跟着错
                    val pkg = try { ToolManager.packageOf(READER)?.name } catch (_: Exception) { null } ?: "文档解析"
                    append("已存到工作区 ").append(rel)
                    append("，但当前没有可用的文件读取工具，上面这些就是你能看到的全部。")
                    append("要看剩下的部分，让用户在设置里启用「").append(pkg).append("」功能包，或换一张带这个能力的角色卡；")
                    append("在那之前基于已有内容作答，缺什么就说缺什么，别去猜省略掉的部分")
                }
            }
            append("]")
        }
    }

    /** 全文落盘，返回工作区相对路径。 */
    private fun save(context: Context, toolName: String, full: String): String {
        val d = dir(context)
        prune(d)
        val safe = toolName.replace(Regex("[^\\w-]"), "_").take(24).ifBlank { "tool" }
        val f = File(d, "${System.currentTimeMillis()}_$safe.txt")
        f.writeText(full)
        return "$DIR/${f.name}"
    }

    /** 只留最近若干个文件：这是缓存不是资料，攒着只会白占手表的存储。 */
    private fun prune(d: File) {
        val fs = d.listFiles()?.sortedByDescending { it.lastModified() } ?: return
        if (fs.size <= KEEP_FILES) return
        fs.drop(KEEP_FILES).forEach { runCatching { it.delete() } }
    }

    // 软换行（[TextWrap.softWrap]）住在 :logic：它是纯字符串运算，不需要 Context，
    // 放纯 JVM 模块就能直接跑单测——不用连设备，也不用为它拖起整个 Android 测试环境。
    // 包名同为 com.arix.tool，所以这边不用 import，行为也和搬家前一模一样（只增不改）。
}
