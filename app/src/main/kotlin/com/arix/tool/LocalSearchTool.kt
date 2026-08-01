package com.arix.tool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * local_search —— 「我这台机器上有没有 xxx」的统一入口。
 *
 * 为什么要有它：本地能搜的东西此前是**散的**——对话得用 manage_chats、记忆得用 memory、
 * 而日记/提醒/角色卡/世界书/功能包**压根没有任何搜索能力**，工作区也只能单层列目录、
 * 单文件读。AI 想回答「我以前是不是记过这个」「那个文件放哪了」，得先猜该问哪个工具，
 * 猜错就直接答"没有"。这里把所有本地源并成一次调用，按相关度铺开。
 *
 * 不重复造轮子：对话与记忆两条线**委派**给已有实现（[ManageChatsTool] 的 search、
 * [com.arix.app.MemoryManager.searchTop]），本文件只补它们没覆盖的源。
 *
 * 全线走 [FuzzyMatch]，所以错字/词序颠倒/漏字都能命中，且遵守项目既有约定：
 * **精确命中永远排在模糊命中前面**（分值区间由 FuzzyMatch 保证）。
 */
class LocalSearchTool(private val context: Context) : Tool {
    override val name = "local_search"
    override val description =
        "在这台设备的本地数据里模糊搜索任何内容：对话记录/长期记忆/工作区文件/日记/提醒/角色卡/世界书/已装功能包。" +
            "支持错字、漏字、词序颠倒。想不起来「以前聊过/记过/存过什么」时优先用它；scope 可缩小范围，默认全找。" +
            "只读不改。搜网上的东西用 web_search。"

    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Fuzzy-search everything stored on this device: chats, long-term memory, workspace files, diary, reminders, character cards, world books, installed packages. Tolerates typos, missing characters and reordered words. Reach for it whenever the user refers to something said, saved or written before; scope narrows it, default searches all. Read-only. For the open web use web_search."

    // 与被委派的 manage_chats 对齐：本工具能读出全部聊天历史，审批等级不能比它低，
    // 否则用户把 manage_chats 设成「始终禁止」后，改调这里就是一条降级绕过的后门。
    override val permissionLevel = AndroidPermissionLevel.ACCESSIBILITY

    /** 各作用域的机器名 → 给人看的名字。顺序即输出顺序（对话/记忆最常命中，排前面）。 */
    private val scopes = linkedMapOf(
        "chats" to "对话",
        "memory" to "记忆",
        "files" to "工作区文件",
        "reminders" to "提醒",
        "packages" to "功能包",
    )

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("query", JSONObject().apply {
                put("type", "string"); put("description", "what to look for; fuzzy match")
            })
            put("scope", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("all") + scopes.keys.toList()))
                put("description", "one category, default all; comma-separated works too, e.g. \"memory,diary\"")
            })
            put("limit", JSONObject().apply {
                put("type", "integer"); put("description", "hits per category, default 5, max 15")
            })
        })
        put("required", JSONArray(listOf("query")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val q = params.optString("query", "").trim()
        if (q.isBlank()) return@withContext ToolResult("请提供 query（要找的内容）", isError = true)
        val limit = params.optInt("limit", 5).coerceIn(1, 15)
        val want = parseScope(params.optString("scope", "all"))

        val sections = ArrayList<String>()
        val failed = ArrayList<String>()
        // 任一源自己出错不该拖垮整次搜索——本地源多且杂（文件权限/JSON 损坏/DB 忙），
        // 逐源兜住异常，坏的那类记下来、其余照常返回。
        // **不能用 runCatching**：它 catch 的是 Throwable，会把 CancellationException 一并咽掉，
        // 于是用户按了停止、这里还会把剩下 7 个源老老实实跑完（项目里「STOP 停不掉」的老坑）。
        val blocked = ArrayList<String>()
        for (s in want) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            // 每个源都要尊重用户对那类数据的既有禁令：把 memory 设成「始终禁止」是冲着
            // 「别让 AI 翻我的长期记忆」去的，不能从 local_search 这条侧门整包读出来。
            // 只挡 FORBID（硬禁令），不挡 ASK——多源搜索里逐源弹确认是灾难级体验，而 FORBID 是明确的"永不"。
            // chats 在 searchChats 内部单独过了完整闸（含 ASK），这里跳过它不重复。
            if (s != "chats" && sourceForbidden(s)) { blocked.add(scopes[s] ?: s); continue }
            val body = try {
                when (s) {
                    "chats" -> searchChats(q, limit)
                    "memory" -> searchMemory(q, limit)
                    "files" -> searchFiles(q, limit)
                    "reminders" -> searchReminders(q, limit)
                    "packages" -> searchPackages(q, limit)
                    else -> null
                }
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c
            } catch (_: Exception) {
                failed.add(scopes[s] ?: s); null
            }
            if (!body.isNullOrBlank()) sections.add("【${scopes[s]}】\n$body")
        }

        if (sections.isEmpty()) {
            // 全被禁的情况要单独说，别混成「没有内容」
            if (blocked.isNotEmpty() && failed.isEmpty())
                return@withContext ToolResult("${blocked.joinToString("/")} 已被用户设为禁止访问；没有可搜索的源。")
            val where = if (want.size == scopes.size) "本地" else want.joinToString("/") { scopes[it] ?: it }
            // 「读取失败」和「确实没有」必须分开说：混为一谈会让 AI 拿着一次 DB 忙的结果
            // 对用户下断言「你没记过这个」。
            val msg = if (failed.isEmpty()) "${where}没有找到「$q」相关的内容。"
            else "${where}没有找到「$q」——但 ${failed.joinToString("/")} 这几类本次读取失败，结论可能不全。"
            return@withContext ToolResult(msg)
        }
        val joined = buildString {
            append(sections.joinToString("\n\n").trim())
            if (failed.isNotEmpty()) append("\n\n（${failed.joinToString("/")} 读取失败，结果可能不全）")
            // 诚实告知被禁的源：不说的话 AI 会以为那几类"没有内容"，而不是"用户不让看"
            if (blocked.isNotEmpty()) append("\n\n（${blocked.joinToString("/")} 已被用户设为禁止访问，未搜索）")
        }
        // 总量闸：8 个源 × limit 条，最坏能吐出十几 KB，一次工具返回就吃掉手表上下文的一大块
        ToolResult(
            if (joined.length > MAX_OUTPUT_CHARS)
                joined.take(MAX_OUTPUT_CHARS) + "\n\n…结果过多已截断，用 scope 缩小范围或调小 limit"
            else joined
        )
    }

    /**
     * 这个源背后的数据，用户是不是已经明确禁止 AI 碰了。
     * 映射到承载该数据的工具/功能包：工具被设为 FORBID、或其功能包被关，都算禁止。
     * cards/packages 没有对应的读取工具、也不含密钥，不设门槛。
     */
    private suspend fun sourceForbidden(scope: String): Boolean {
        val toolName = when (scope) {
            "memory" -> "memory"
            "reminders" -> "set_reminder"
            "files" -> "file_read"
            else -> null
        } ?: return false
        // FORBID 是硬禁令（ASK 不在这里挡，见调用处说明）
        if (ToolPermissionManager.effectiveFor(currentToolCaller(), toolName,
                AndroidPermissionLevel.STANDARD) == ToolPermission.FORBID) return true
        val pkg = when (scope) { "memory" -> "memory"; "files" -> "file_tools"; else -> null }
        return pkg != null && !PackageManager.isEnabled(pkg)
    }

    /** scope 支持 all / 单个 / 逗号分隔多个；无法识别的名字直接忽略，不报错打断搜索。 */
    private fun parseScope(raw: String): List<String> {
        val v = raw.trim().lowercase()
        if (v.isBlank() || v == "all") return scopes.keys.toList()
        val picked = v.split(',', '，', ' ').map { it.trim() }.filter { it in scopes.keys }
        return picked.ifEmpty { scopes.keys.toList() }
    }

    // ── 对话：委派给 manage_chats（它已实现标题+消息双打分、片段提取、归档合并） ──────────
    private suspend fun searchChats(q: String, limit: Int): String? {
        // 用户在功能包里关掉了对话管理，就不该从这条侧门把聊天记录读出来
        if (!PackageManager.isEnabled("manage_chats")) return null
        val args = JSONObject().apply { put("action", "search"); put("query", q); put("limit", limit) }
        val mc = ManageChatsTool(context)
        // **必须再过一次权限闸**：闸是按 tool.name 记策略的，用户把 manage_chats 设成「始终禁止/每次询问」
        // 是冲着「别让 AI 翻我的聊天记录」去的。只把本工具的 permissionLevel 抬到同级并不够——
        // 那只保证审批**等级**一样，不代表尊重他对那个工具下的**具体策略**，从这条侧门照样能全量读出来。
        // 直接 tool.execute() 绕过 ToolManager.execute 就是绕过全项目唯一的闸。
        if (!ToolPermissionManager.check(mc, args, currentToolCaller())) return null
        val r = mc.execute(args)
        // 它「没搜到」时返回的是正常文案而非 error，这里统一收敛成 null（不占版面）
        // 用共享常量而不是抄一份字面量：这句是 manage_chats 的返回文案，抄一份的话
        // 那边一改（或哪天被 tr() 包了），这里就静默失效——"没搜到"会被当成有结果塞给模型。
        if (r.isError || r.content.startsWith(ManageChatsTool.NO_CHAT_HITS)) return null
        return r.content
    }

    // ── 记忆：走 searchTop（LIKE 精确 + Fuzzy 补齐），不用 queryRelevant ─────────────────
    // queryRelevant 会调 embedding/LLM，是「给这轮对话找相关记忆」用的；这里是用户明确要搜，
    // 关键词命中才是他要的，别让语义近邻把精确结果挤下去，也别为一次搜索付模型调用的钱。
    private suspend fun searchMemory(q: String, limit: Int): String? {
        val hits = com.arix.app.MemoryManager(context).searchTop(q, limit)
        if (hits.isEmpty()) return null
        return hits.joinToString("\n") { m ->
            val pin = if (m.pinned) " 📌" else ""
            "· [${m.type}] ${m.title}$pin — ${snippet(m.content, q)}"
        }
    }

    // ── 工作区文件：文件名 + 内容双搜，递归 ──────────────────────────────────────────────
    private suspend fun searchFiles(q: String, limit: Int): String? {
        val root = AiWorkspace.root(context)
        val out = ArrayList<Pair<Float, String>>()
        var scanned = 0
        var truncated = false
        // 用迭代器而不是 forEach+return@forEach：后者只是跳过当前项，序列仍会把整棵树走完，
        // 工作区被塞了两万个小文件时就是一万九千次白跑的 stat（手表上是秒级空转）。
        val it = root.walkTopDown().maxDepth(MAX_DEPTH).filter { f -> f.isFile }.iterator()
        while (it.hasNext()) {
            if (scanned >= MAX_FILES) { truncated = true; break }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val f = it.next()
            scanned++
            val rel = f.relativeTo(root).path.replace(File.separatorChar, '/')
            val nameScore = FuzzyMatch.bestScore(q, f.name, rel)
            var line: String? = null
            var contentScore = 0f
            // 只读文本类：二进制(图片/模型/压缩包)读了也只是噪音。
            // 不信 length()——FIFO/字符设备/proc 类节点长度报 0 会骗过大小闸，readText 能一路读到把堆吃满
            // （内嵌 Termux 与本 App 同 UID、同一个 filesDir，造得出这种东西）。一律有界读。
            if (looksTextual(f)) {
                val text = readBounded(f)
                if (text != null) {
                    contentScore = chunkedScore(q, text)
                    if (contentScore > 0f) line = firstMatchingLine(text, q)
                }
            }
            val best = maxOf(nameScore, contentScore)
            if (best > 0f) {
                val tag = if (nameScore >= contentScore) "文件名命中" else "内容命中"
                out.add(best to "· $rel  [$tag]" + (line?.let { "\n    $it" } ?: ""))
            }
        }
        // 一个都没命中但扫描被截断时，绝不能返回 null 让上层说成「没有」——
        // 目标文件排在第 401 个的话，AI 会斩钉截铁告诉用户「工作区没这个文件」。
        if (out.isEmpty()) return if (truncated) "（工作区文件过多，只扫了前 $MAX_FILES 个，未命中；请缩小范围或用 file_list 定位）" else null
        out.sortByDescending { it.first }
        val sb = StringBuilder(out.take(limit).joinToString("\n") { it.second })
        if (out.size > limit) sb.append("\n  …另有 ${out.size - limit} 个文件命中，用 file_read 看全文")
        if (truncated) sb.append("\n  （工作区文件过多，只扫了前 $MAX_FILES 个）")
        return sb.toString()
    }

    /** 有界读：读满上限即停，不看 length()，不把整个文件吞进内存。 */
    private fun readBounded(f: File): String? = try {
        val buf = CharArray(4096)
        val sb = StringBuilder()
        f.bufferedReader().use { r ->
            while (sb.length < MAX_CONTENT_CHARS) {
                val n = r.read(buf)
                if (n <= 0) break
                sb.append(buf, 0, n)
            }
        }
        sb.toString()
    } catch (_: Throwable) { null }   // 读坏文件不该让整次搜索失败

    /**
     * 分块打分。FuzzyMatch 的近似匹配对超长目标是**静默失效**的（编辑距离 2000 字符封顶、
     * 子序列 200 字符封顶），整篇文章直接丢进去打分，等于只剩精确子串——
     * 那就违背了工具描述里承诺的「支持错字漏字」。切块后每块都在近似匹配的射程内。
     */
    private fun chunkedScore(q: String, text: String): Float {
        if (text.length <= CHUNK) return FuzzyMatch.score(q, text)
        var best = 0f
        var i = 0
        while (i < text.length) {
            val end = (i + CHUNK).coerceAtMost(text.length)
            best = maxOf(best, FuzzyMatch.score(q, text.substring(i, end)))
            if (best >= 0.9f) return best   // 已经是精确级命中，再切下去没意义
            i = end
        }
        return best
    }

    private fun looksTextual(f: File): Boolean =
        f.extension.lowercase() in setOf(
            "txt", "md", "json", "xml", "csv", "log", "yml", "yaml", "ini", "conf", "properties",
            "kt", "java", "js", "ts", "py", "sh", "html", "css", "sql", "c", "cpp", "h", "rs", "go", "toml",
        ) || f.extension.isBlank()

    /** 命中所在的那一行——比从文件开头截 200 字有用得多。 */
    private fun firstMatchingLine(text: String, q: String): String? {
        val toks = FuzzyMatch.tokens(q)
        val line = text.lineSequence().take(MAX_SCAN_LINES)
            .firstOrNull { l -> toks.any { l.contains(it, ignoreCase = true) } } ?: return null
        return line.trim().take(160)
    }

    // ── 提醒 / 功能包 ─────────────────────────────────────────────────────────────
    private fun searchReminders(q: String, limit: Int): String? {
        val hits = FuzzyMatch.rankBy(q, com.arix.app.ReminderStore.all(context), limit) { listOf(it.title, it.note) }
        if (hits.isEmpty()) return null
        return hits.joinToString("\n") { s ->
            val r = s.item
            val rep = if (r.repeat != "none") "（${r.repeat} 重复）" else ""
            "· ${r.title}$rep ${fmtTime(r.atMillis)}" + (if (r.note.isNotBlank()) " — ${snippet(r.note, q)}" else "")
        }
    }

    private fun searchPackages(q: String, limit: Int): String? {
        val pkgs = PackageManager.getAllPackages()
        val hits = FuzzyMatch.rankBy(q, pkgs, limit) {
            listOf(it.name, it.description, it.category) + it.tools.map { t -> t.name }
        }
        if (hits.isEmpty()) return null
        return hits.joinToString("\n") { s ->
            val p = s.item
            // 标出未启用：getAllPackages 含用户关掉的包，不标的话 AI 会以为搜到就能用，
            // 真去调才被包禁用闸挡下，白跑一轮
            val off = if (PackageManager.isEnabled(p.id)) "" else "（未启用）"
            "· ${p.name}$off（${p.category}）— ${p.description.take(60)}｜工具：${p.tools.joinToString("/") { it.name }}"
        }
    }

    // ── 公共小工具 ────────────────────────────────────────────────────────────────────
    /** 片段取命中词周边而非从头截，否则长正文里命中的那句根本露不出来。 */
    private fun snippet(text: String, query: String, radius: Int = 40): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= radius * 3) return flat
        val lc = flat.lowercase()
        val idx = FuzzyMatch.tokens(query).map { lc.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (idx - radius).coerceAtLeast(0)
        val end = (idx + radius * 2).coerceAtMost(flat.length)
        return (if (start > 0) "…" else "") + flat.substring(start, end) + (if (end < flat.length) "…" else "")
    }

    private fun fmtTime(ts: Long): String =
        java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

    private companion object {
        const val MAX_DEPTH = 8
        const val MAX_FILES = 400            // 工作区可能被 AI 塞进成千上万个小文件，扫描量要有上限
        const val MAX_CONTENT_CHARS = 64 * 1024
        const val CHUNK = 1500               // 落在 FuzzyMatch 近似匹配的射程内（它 2000 字符封顶）
        const val MAX_SCAN_LINES = 2000
        const val MAX_OUTPUT_CHARS = 6000    // 一次工具返回的总量闸，别一口吃掉手表的上下文
    }
}
