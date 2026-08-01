package com.arix.app

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.model.ChatMessage
import com.arix.data.entity.MemoryEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 自动整理记忆 —— 专门盯 [LessonRecorder] 自动沉淀下来的「教训」，评估它到底有没有用。
 *
 * ## 为什么要有这一层
 * 教训是**唯一一条不用人动手就会自己长**的记忆来源：每次工具被拒/参数写错/回复被截断都写一条。
 * 有斜率的东西同时也有失控风险——只进不出的库，跑上几个月就会攒出一堆「当时踩过一次、
 * 之后再没相关场景」的条目，它们不占内存但**占检索名额**，把真正该被想起的事挤出上下文。
 * 所以要有一个反向的力：定期回头看，没用的清掉。
 *
 * ## 三条硬约束（这是后台维护，不是主流程）
 * - **置顶永不动**：用户手动置顶就是「这条我要」，任何自动逻辑都不该有意见。
 * - **只删判定明确没用的**，拿不准的一律**问用户**（见 [pending]），绝不悄悄删。
 * - **删了能找回**：删除前先落进 [trash]（最近 [TRASH_MAX] 条），用户在记忆页点一下就恢复。
 *   项目里原本没有任何记忆归档/回收站机制，这里就地做一个最小的，别让"自动删"变成不可逆操作。
 *
 * ## 判定顺序：先用便宜的硬信号，判不动才叫模型
 * 一上来就把几十条教训丢给模型判，既慢又贵，而且模型对「这条有没有被用过」根本没有信息——
 * 那是我们自己库里的事实。所以先榨干确定性信号：
 *  1. **被检索命中过**（`lastAccessedAt` 明显晚于 `createdAt`）→ 有用。它真的进过某轮上下文。
 *  2. **重复沉淀过**（`importance` 被 [LessonRecorder] 往上抬过，≥ [REPEAT_IMPORTANCE]）→ 有用。
 *     同一个坑踩了不止一次，正是最该记住的那种。
 *  3. **工具已经不存在了** → 没用。教训是挂在某个工具上的经验，工具都没了，经验无处可用。
 *  4. **同工具同类失败的近似条目** → 合并成一条（留重要度最高的那条）。
 *  5. 剩下的「从没命中过 + 从没重复 + 已经放了 [COLD_DAYS] 天」才送模型判一次：有用 / 没用 / 不确定。
 * 还没冷却到期的新教训一律不碰——刚记下来就说它没用，那是没给它机会。
 *
 * ## 「问用户」为什么走记忆页而不是 [SettingProposalBus]
 * SettingProposalBus 是 AI 申请改**设置**的通道，落地要过 [SettingApplier] 的白名单，而那份白名单
 * 是条安全边界（key/角色卡/模型明确不可申请）。为了让它能"申请删一条记忆"而在白名单上开个口子，
 * 是拿安全边界换一个 UI 位置，不划算。另外两点也不合适：它的 pending 只在内存里（进程一死就没了，
 * 而本模块是后台跑的），以及它的卡片渲染在**当前打开的那个对话**里——用户正在聊别的事，
 * 突然插进来五张"这条教训还要吗"，是打断而不是询问。
 * 记忆页的待确认列表相反：持久化、可攒着批量看、就在记忆内容旁边（能直接点开原文再决定）。
 */
object MemoryTidy {

    private const val PREF = "xtom_memory_tidy"
    private const val K_LAST = "last_run_at"
    private const val K_ENABLED = "auto_enabled"
    private const val K_PENDING = "pending"
    private const val K_TRASH = "trash"
    private const val K_REVIEWED = "reviewed"   // {"<记忆id>": 判定为「有用」的时间戳}

    /** 常规限频：3 天。判据本身（有没有被命中过）是按天累积的，跑得再勤也只是把同样的问题重问一遍。 */
    private const val MIN_INTERVAL_MS = 72L * 60 * 60 * 1000L
    /** 教训攒到这个数就提速到 [BURST_INTERVAL_MS]：说明这段时间在密集踩坑，噪音也在密集产生。 */
    private const val BURST_COUNT = 30
    private const val BURST_INTERVAL_MS = 20L * 60 * 60 * 1000L

    /** 冷却期：新教训至少放这么多天才允许被判「没用」。 */
    private const val COLD_DAYS = 30L
    /** 判过「有用」的，这么多天内不再重判（省模型调用，也避免反复骚扰用户）。 */
    private const val REVIEWED_QUIET_DAYS = 60L
    /**
     * `lastAccessedAt` 与 `createdAt` 差多少才算「真被检索命中过」。
     * [MemoryManager.add] 建条时就把 lastAccessedAt 写成 now，所以两者几乎相等 = 从没被想起过。
     */
    private const val HIT_SLACK_MS = 60_000L
    /**
     * 重复沉淀的阈值。[LessonRecorder] 首次写入 0.55，之后每重复一次 +0.1（封顶 0.85）。
     * 取 0.64 而不是 0.65：浮点存取有误差，卡在等号上会让「正好第二次」时有时无。
     */
    private const val REPEAT_IMPORTANCE = 0.64f

    /** 一轮最多送模型判这么多条：手表上一次网络往返的体积要压住，判不完下轮接着来。 */
    private const val MAX_JUDGE = 20
    /** 待确认列表上限：问不过来就等于没问，攒太多用户只会整批忽略。 */
    private const val PENDING_MAX = 20
    /** 回收站保留条数。 */
    private const val TRASH_MAX = 50

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    // ---- 开关 --------------------------------------------------------------

    /** 自动整理是否开启（默认开）。关掉后只剩记忆页里的手动按钮。 */
    fun autoEnabled(c: Context): Boolean = prefs(c).getBoolean(K_ENABLED, true)
    fun setAutoEnabled(c: Context, v: Boolean) {
        prefs(c).edit().putBoolean(K_ENABLED, v).apply()
        if (v) schedule(c) else cancel(c)
    }

    // ---- 待确认 / 回收站（对 UI 暴露成流，改完立刻反映到记忆页） ----------------

    /** 拿不准的教训：列在记忆页等用户拍板，**在用户回答前原条一动不动**。 */
    data class PendingLesson(val id: Long, val title: String, val reasonCode: String, val at: Long)

    /** 被自动整理删掉的原件（可恢复）。cardId 一并留着，恢复后还回原来的角色卡。 */
    data class TrashedLesson(
        val title: String, val content: String, val type: String,
        val importance: Float, val cardId: Long?, val whyCode: String, val at: Long,
    )

    private val _pending = MutableStateFlow<List<PendingLesson>>(emptyList())
    val pending: StateFlow<List<PendingLesson>> = _pending.asStateFlow()
    private val _trash = MutableStateFlow<List<TrashedLesson>>(emptyList())
    val trash: StateFlow<List<TrashedLesson>> = _trash.asStateFlow()

    @Volatile private var loaded = false

    /** 首次读到内存（进程内只做一次）。UI 与后台都可能先碰到，做成幂等。 */
    @Synchronized
    fun ensureLoaded(c: Context) {
        if (loaded) return
        _pending.value = readPending(c)
        _trash.value = readTrash(c)
        loaded = true
    }

    // ---- 后台调度 ----------------------------------------------------------

    private const val WORK = "xtom_memory_tidy_work"

    /**
     * 排一个低频周期任务。真正的限频在 [maybeRun] 里（跨进程的时间戳），这里的周期只是「多久来敲一次门」。
     * KEEP：已经排过就不动，避免每次冷启动都重排把下一次执行推后。
     */
    fun schedule(context: Context) {
        try {
            val req = PeriodicWorkRequestBuilder<MemoryTidyWorker>(12, TimeUnit.HOURS)
                // 电量不低 + 有网。**有网这条是后补的**：这一轮的重头戏是送模型判那批教训，
                // 而限频时间戳是「先写后跑」（防中途失败反复重试）——离线被唤醒的结果是
                // 全表扫几遍、模型请求必失败，然后把这一轮的额度也消耗掉，等于白跳过一个周期。
                // 硬信号那部分不需要网，但它也不急：等有网了一起做，比空跑一轮划算。
                .setConstraints(
                    Constraints.Builder()
                        .setRequiresBatteryNotLow(true)
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniquePeriodicWork(WORK, ExistingPeriodicWorkPolicy.KEEP, req)
        } catch (_: Exception) {}
    }

    fun cancel(context: Context) {
        try { WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK) } catch (_: Exception) {}
    }

    /**
     * 到点就整理一轮，没到点直接返回 null。**调用方不需要判断任何条件**，限频/开关都在里面。
     * 全程吞异常（除了取消）：这是后台维护，绝不能反过来影响对话或 UI。
     */
    suspend fun maybeRun(context: Context): TidyResult? {
        return try {
            if (!autoEnabled(context)) return null
            val p = prefs(context)
            val now = System.currentTimeMillis()
            val last = p.getLong(K_LAST, 0L)
            // 先用**最短**的那个间隔挡一道：连它都没到就不可能到点，省掉下面一次全表扫描。
            // （时钟回拨——改系统时间/跨时区——会让差值变负，那种情况一律当「该跑了」放过去。）
            if (last != 0L && now - last in 0 until BURST_INTERVAL_MS) return null
            val mm = MemoryManager(context)
            // COUNT(*) 而不是把整表读回来数：这只是「值不值得跑这一轮」的门槛判断，一轮里绝大多数情况都在这里返回
            val lessonCount = withContext(Dispatchers.IO) { mm.countByType("lesson") }
            if (lessonCount == 0) return null
            val interval = if (lessonCount >= BURST_COUNT) BURST_INTERVAL_MS else MIN_INTERVAL_MS
            if (last != 0L && now - last in 0 until interval) return null
            // 时间戳先写：这一轮可能要一次模型往返，中途整体失败宁可等下一个周期，也别每次敲门都重试
            p.edit().putLong(K_LAST, now).apply()
            val cfg = try { CloudApiConfigManager(context).getConfigForPurpose("summary", capMaxTokens = 1024) } catch (_: Exception) { null }
            withContext(Dispatchers.IO) { runOnce(context, cfg) }
        } catch (ce: CancellationException) {
            throw ce   // 取消不是失败：吞了它上层就停不掉这一轮（见记忆 stop-cancellation）
        } catch (_: Exception) {
            null
        }
    }

    // ---- 主流程 ------------------------------------------------------------

    data class TidyResult(
        val scanned: Int,   // 本轮参与评估的教训条数（不含置顶）
        val merged: Int,    // 近似重复被合掉的
        val deleted: Int,   // 判定明确没用、已移入回收站的
        val asked: Int,     // 拿不准、进待确认列表的
        val kept: Int,      // 判定有用、留下的
    )

    /**
     * 整理一轮。[config] 为 null（没配模型/取不到）时只做确定性那几步，**一条都不会误删**。
     * 记忆页的手动按钮也调这里，走的是同一套逻辑——手动和自动的判据必须一致，否则用户点完
     * 看到的结果和后台自己跑出来的对不上，就没法信任它。
     */
    suspend fun runOnce(context: Context, config: CloudApiConfig?, onProgress: ((String) -> Unit)? = null): TidyResult {
        val app = context.applicationContext
        ensureLoaded(app)
        val mm = MemoryManager(app)
        val now = System.currentTimeMillis()

        // 置顶的连看都不看：用户置顶就是最强的「留着」信号，别让它进任何候选集（筛选交给 SQL，别读整表回来 filter）
        val lessons = mm.unpinnedOfType("lesson")
        if (lessons.isEmpty()) return TidyResult(0, 0, 0, 0, 0)

        // ① 近似重复合并（确定性）：同角色卡 + 同工具 + 同失败类别 = 同一条经验的多个副本。
        //    正常路径靠 upsertByTitle 就不会重复，这些副本来自导入/AI 合并改过标题/工具改名。
        //    **只在同一张卡内合并**：跨卡合并会把 A 卡的经验搬进 B 卡（同名跨卡覆盖那个老坑）。
        onProgress?.invoke(tr("查重…"))
        var merged = 0
        val survivors = ArrayList<MemoryEntity>()
        lessons.groupBy { m ->
            val k = parseTitle(m.title)
            if (k == null) "raw:${m.characterCardId}:${m.title.trim().lowercase()}"
            else "k:${m.characterCardId}:${k.first}:${k.second}"
        }.forEach { (_, grp) ->
            if (grp.size <= 1) { survivors.add(grp.first()); return@forEach }
            val keeper = grp.maxWithOrNull(compareBy({ it.importance }, { it.updatedAt })) ?: grp.first()
            survivors.add(keeper)
            grp.filter { it.id != keeper.id }.forEach { dup ->
                archiveAndDelete(app, mm, dup, "dup"); merged++
            }
        }

        // ② 硬信号分流（不花钱）
        val reviewed = readReviewed(app)
        val coldBefore = now - COLD_DAYS * 86_400_000L
        var kept = 0
        var deleted = 0
        val toJudge = ArrayList<MemoryEntity>()
        for (m in survivors) {
            when (verdictByHardSignal(m, reviewed, now, coldBefore)) {
                Hard.USEFUL -> kept++
                Hard.DEAD_TOOL -> { archiveAndDelete(app, mm, m, "dead_tool"); deleted++ }
                Hard.TOO_YOUNG -> Unit          // 还在冷却期，本轮不评价（也不算 kept，免得数字虚高）
                Hard.NEED_JUDGE -> toJudge.add(m)
            }
        }

        // ③ 硬信号判不动的送模型。整轮调用失败 → 原样不动（对齐 MemorySalvage.mergeCard 的「失败不删」）
        var asked = 0
        if (toJudge.isNotEmpty() && config != null) {
            onProgress?.invoke(tr("评估 %d 条教训…").format(minOf(toJudge.size, MAX_JUDGE)))
            val batch = toJudge.sortedBy { it.updatedAt }.take(MAX_JUDGE)   // 最旧的先判：它们证据最全
            val verdicts = llmJudge(config, batch)
            if (verdicts.isNotEmpty()) {
                val nowReviewed = HashMap(reviewed)
                for (m in batch) {
                    when (verdicts[m.id]) {
                        "useless" -> { archiveAndDelete(app, mm, m, "llm_useless"); deleted++ }
                        "useful" -> { nowReviewed[m.id] = now; kept++ }
                        // 不确定、以及模型压根没提到的那些，一律问用户——漏判的默认值必须是「问」，不是「删」
                        else -> { addPending(app, m, "llm_unsure"); asked++ }
                    }
                }
                writeReviewed(app, nowReviewed)
            }
        }

        // ④ 清掉指向已不存在记忆的待确认/复查记录（用户自己在记忆页删过的）
        pruneStaleRefs(app, mm)
        return TidyResult(survivors.size, merged, deleted, asked, kept)
    }

    private enum class Hard { USEFUL, DEAD_TOOL, TOO_YOUNG, NEED_JUDGE }

    /**
     * 硬信号判定。顺序有讲究：**先认「有用」再认「没用」**——一条既被命中过、工具又恰好下线的教训，
     * 该按「被用过」留着（工具可能只是包被关了、以后还会回来），不该按「工具没了」删掉。
     */
    private fun verdictByHardSignal(m: MemoryEntity, reviewed: Map<Long, Long>, now: Long, coldBefore: Long): Hard {
        // 真被检索命中过：它进过某一轮上下文，这是最实的「有用」证据
        if (m.lastAccessedAt - m.createdAt > HIT_SLACK_MS) return Hard.USEFUL
        // 重复沉淀过：同一个坑踩了不止一次
        if (m.importance >= REPEAT_IMPORTANCE) return Hard.USEFUL
        // 已经判过「有用」，安静期内不再重判
        val r = reviewed[m.id] ?: 0L
        if (r > 0L && now - r < REVIEWED_QUIET_DAYS * 86_400_000L) return Hard.USEFUL
        // 冷却期内不动：刚记下来就说它没用，是没给它机会。锚点取「建条」和「上次判为有用」的较晚者。
        // ⚠ 这一条**必须排在「工具已不存在」前面**：市场包/JS 插件的工具是启动后由后台线程异步注册的
        // （OperitCompat.refresh），本模块跑在后台任务里，完全可能赶在注册完成之前查表——那一刻
        // 一个活着的插件工具看起来就像"没了"。压在冷却期后面，这个竞态窗口就从"几秒"变成"三十天"，
        // 中间任何一次正常运行都会先把它判成 TOO_YOUNG 而不是删掉。
        if (maxOf(m.createdAt, r) > coldBefore) return Hard.TOO_YOUNG
        // 工具已经不存在：经验挂在工具上，工具没了这条无处可用
        if (isDeadTool(m)) return Hard.DEAD_TOOL
        return Hard.NEED_JUDGE
    }

    /** 标题 `教训·<工具>·<失败类别>` → (工具, 类别)；不是这个形状（被 AI 合并改过标题）返回 null。 */
    private fun parseTitle(title: String): Pair<String, String>? {
        if (!title.startsWith("教训·")) return null
        val parts = title.split("·")
        if (parts.size < 3) return null
        val tool = parts[1].trim(); val kind = parts[2].trim()
        return if (tool.isBlank() || kind.isBlank()) null else tool to kind
    }

    /**
     * 这条教训指向的工具是不是已经不存在了。
     *
     * 保守到近乎悲观，因为误判的后果是删记忆：
     *  - 标题解析不出工具名 → 不判死；
     *  - 工具表还没装配好（getAll 为空，冷启动早期）→ 不判死；
     *  - `mcp_` / `ext_` 开头的第三方工具 → 不判死。它们是运行期动态注册的，
     *    此刻查不到很可能只是那个 MCP server 没连上，不代表工具没了。
     */
    private fun isDeadTool(m: MemoryEntity): Boolean {
        val tool = parseTitle(m.title)?.first ?: return false
        if (tool.startsWith("mcp_") || tool.startsWith("ext_")) return false
        return try {
            val all = com.arix.tool.ToolManager.getAll()
            if (all.isEmpty()) false else all.none { it.name == tool }
        } catch (_: Exception) { false }
    }

    // ---- 模型判定 ----------------------------------------------------------

    /**
     * 让便宜模型判一批教训还值不值得留。返回 id → useful/useless/unsure；整体失败返回空表（=本轮不动任何条目）。
     *
     * 喂进去的只有**我们库里的客观数据**（正文、放了多久、命中过没有、重复沉淀过几次），
     * 不写"你是记忆管家"这类角色设定，也不规定它拿什么语气回话——这是一次判断，不是一次对话。
     */
    private suspend fun llmJudge(config: CloudApiConfig, mems: List<MemoryEntity>): Map<Long, String> {
        return try {
            val now = System.currentTimeMillis()
            val arr = JSONArray()
            mems.forEach { m ->
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("title", m.title)
                    put("content", m.content.take(300))
                    put("存放天数", ((now - m.createdAt) / 86_400_000L))
                    put("被检索命中过", m.lastAccessedAt - m.createdAt > HIT_SLACK_MS)
                    put("重复沉淀次数", repeatTimes(m.importance))
                })
            }
            val prompt = buildString {
                append(PromptLang.pick(
                    "下面是助手在工具调用失败后自动记下的经验条目（JSON 数组），每条附带它在库里的客观数据。" +
                        "逐条判断现在还值不值得留在长期记忆里：说的约束或错误仍然可能再遇到就是 useful；" +
                        "描述的情形已经不成立、或者内容太笼统对下次行动给不出任何指导就是 useless；" +
                        "给出的数据不足以下判断就是 unsure。" +
                        "只输出 JSON 数组，每项 {id, verdict}，verdict 取 useful/useless/unsure。不要解释。\n",
                    "Below are experience entries the assistant recorded automatically after a tool call failed (a JSON array), each with its objective data from the store." +
                        "For each entry, decide whether it is still worth keeping in long-term memory: if the constraint or error it describes could still be encountered again, it is useful;" +
                        "if the situation it describes no longer holds, or the content is too vague to give any guidance for the next action, it is useless;" +
                        "if the data given is insufficient to decide, it is unsure." +
                        "Output only a JSON array, each item {id, verdict}, with verdict one of useful/useless/unsure. No explanation.\n"
                ))
                append(arr.toString())
            }
            var out = ""
            CloudApiClient(config).streamChat(
                messages = listOf(ChatMessage("user", prompt)),
                enableThinking = 0, onReasoningChunk = {}, onContentChunk = { out += it },
            )
            val cleaned = out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            val res = JSONArray(cleaned)
            val map = HashMap<Long, String>()
            for (i in 0 until res.length()) {
                val o = res.optJSONObject(i) ?: continue
                val id = o.optLong("id", -1L); if (id < 0) continue
                val v = o.optString("verdict").trim().lowercase()
                // 不认识的判词按 unsure 处理：只有明确说 useless 才允许删
                map[id] = if (v == "useful" || v == "useless") v else "unsure"
            }
            map
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            emptyMap()
        }
    }

    /** 由 importance 反推重复沉淀次数（[LessonRecorder] 首写 0.55、每重复一次 +0.1、封顶 0.85）。 */
    // ⚠ 必须四舍五入不能 toInt()：(0.65f-0.45f)/0.1f 在浮点里是 1.9999…，截断会把「重复过一次」算成 0
    private fun repeatTimes(importance: Float): Int =
        (Math.round((importance - 0.45f) / 0.1f) - 1).coerceAtLeast(0)

    // ---- 用户拍板 ----------------------------------------------------------

    /** 用户说「留着」：出待确认列表，并记一次复查时间（安静期内不再问）。 */
    suspend fun resolveKeep(context: Context, id: Long) {
        val app = context.applicationContext
        removePending(app, id)
        val r = HashMap(readReviewed(app)); r[id] = System.currentTimeMillis(); writeReviewed(app, r)
    }

    /** 用户说「删掉」：走同一条归档路径（仍然能从回收站恢复）。 */
    suspend fun resolveDelete(context: Context, id: Long) {
        val app = context.applicationContext
        val mm = MemoryManager(app)
        mm.getById(id)?.let { archiveAndDelete(app, mm, it, "user_deleted") }
        removePending(app, id)
    }

    /** 从回收站恢复一条。source 用 `tidy_restore`：它不在语义去重白名单里，恢复路径不该被去重悄悄并掉。 */
    suspend fun restore(context: Context, index: Int) {
        val app = context.applicationContext
        val list = _trash.value
        val item = list.getOrNull(index) ?: return
        try {
            MemoryManager(app).add(
                title = item.title, content = item.content, source = "tidy_restore",
                importance = item.importance, characterCardId = item.cardId, type = item.type,
            )
        } catch (ce: CancellationException) { throw ce } catch (_: Exception) { return }
        val rest = list.toMutableList().also { it.removeAt(index) }
        _trash.value = rest
        writeTrash(app, rest)
    }

    fun clearTrash(context: Context) {
        _trash.value = emptyList()
        prefs(context).edit().remove(K_TRASH).apply()
    }

    // ---- 存取 --------------------------------------------------------------

    /** 删除前先归档。任何自动删除都必须经这里——「删了能找回」是这套逻辑敢自动跑的前提。 */
    private suspend fun archiveAndDelete(app: Context, mm: MemoryManager, m: MemoryEntity, whyCode: String) {
        val item = TrashedLesson(m.title, m.content, m.type, m.importance, m.characterCardId, whyCode, System.currentTimeMillis())
        val list = (listOf(item) + _trash.value).take(TRASH_MAX)
        _trash.value = list
        writeTrash(app, list)
        removePending(app, m.id)
        try { mm.delete(m.id) } catch (ce: CancellationException) { throw ce } catch (_: Exception) {}
    }

    private fun addPending(app: Context, m: MemoryEntity, reasonCode: String) {
        val cur = _pending.value
        if (cur.any { it.id == m.id }) return
        val list = (cur + PendingLesson(m.id, m.title, reasonCode, System.currentTimeMillis())).take(PENDING_MAX)
        _pending.value = list
        writePending(app, list)
    }

    private fun removePending(app: Context, id: Long) {
        val list = _pending.value.filter { it.id != id }
        if (list.size == _pending.value.size) return
        _pending.value = list
        writePending(app, list)
    }

    /** 用户可能直接在记忆页删了某条教训：待确认/复查表里指向它的记录成了孤儿，顺手清掉。 */
    private suspend fun pruneStaleRefs(app: Context, mm: MemoryManager) {
        try {
            val alive = mm.allIds().toHashSet()   // 只要 id，别把整表连正文一起读回来
            val p = _pending.value.filter { it.id in alive }
            if (p.size != _pending.value.size) { _pending.value = p; writePending(app, p) }
            val r = readReviewed(app).filterKeys { it in alive }
            writeReviewed(app, r)
        } catch (ce: CancellationException) { throw ce } catch (_: Exception) {}
    }

    private fun readPending(c: Context): List<PendingLesson> = try {
        val arr = JSONArray(prefs(c).getString(K_PENDING, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            PendingLesson(o.optLong("id"), o.optString("title"), o.optString("why", "cold"), o.optLong("at"))
        }
    } catch (_: Exception) { emptyList() }

    private fun writePending(c: Context, list: List<PendingLesson>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("id", it.id).put("title", it.title).put("why", it.reasonCode).put("at", it.at)) }
            prefs(c).edit().putString(K_PENDING, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun readTrash(c: Context): List<TrashedLesson> = try {
        val arr = JSONArray(prefs(c).getString(K_TRASH, "[]"))
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            TrashedLesson(
                o.optString("title"), o.optString("content"), o.optString("type", "lesson"),
                o.optDouble("importance", 0.5).toFloat(),
                if (o.isNull("card")) null else o.optLong("card"),
                o.optString("why", ""), o.optLong("at"),
            )
        }
    } catch (_: Exception) { emptyList() }

    private fun writeTrash(c: Context, list: List<TrashedLesson>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().apply {
                    put("title", it.title); put("content", it.content); put("type", it.type)
                    put("importance", it.importance.toDouble())
                    if (it.cardId != null) put("card", it.cardId)
                    put("why", it.whyCode); put("at", it.at)
                })
            }
            prefs(c).edit().putString(K_TRASH, arr.toString()).apply()
        } catch (_: Exception) {}
    }

    private fun readReviewed(c: Context): Map<Long, Long> = try {
        val o = JSONObject(prefs(c).getString(K_REVIEWED, "{}"))
        val map = HashMap<Long, Long>()
        val it = o.keys()
        while (it.hasNext()) { val k = it.next(); k.toLongOrNull()?.let { id -> map[id] = o.optLong(k) } }
        map
    } catch (_: Exception) { emptyMap() }

    private fun writeReviewed(c: Context, map: Map<Long, Long>) {
        try {
            val o = JSONObject()
            map.forEach { (id, t) -> o.put(id.toString(), t) }
            prefs(c).edit().putString(K_REVIEWED, o.toString()).apply()
        } catch (_: Exception) {}
    }

    // ---- 给 UI 的文案（reason/why 存的是码，展示时才翻译，换语言不用改历史数据） ----

    fun reasonText(code: String): String = when (code) {
        "llm_unsure" -> tr("放了很久没被想起过，判不准还有没有用")
        "cold" -> tr("放了很久没被想起过")
        else -> tr("需要你确认")
    }

    fun whyText(code: String): String = when (code) {
        "dup" -> tr("与同工具同类的另一条重复")
        "dead_tool" -> tr("对应的工具已不存在")
        "llm_useless" -> tr("评估为已无参考价值")
        "user_deleted" -> tr("你确认删除")
        else -> tr("整理")
    }
}

/** 周期任务外壳：真正的限频/开关判断都在 [MemoryTidy.maybeRun] 里，这里只负责按点敲门。 */
class MemoryTidyWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        try { MemoryTidy.maybeRun(applicationContext) } catch (_: Exception) {}
        return Result.success()   // 整理失败不重试：下一个周期自然会再来，重试只会白耗电
    }
}
