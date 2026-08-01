package com.arix.app

import android.content.Context

/**
 * 失败教训自动入记忆 —— 这是整套记忆改造里**唯一有斜率的那条**。
 *
 * 其余改动都是一次性把水位抬高：注入得更准、检索得更好、压缩得更省。只有这一条会让系统
 * **用得越久越好用**：每一次工具被拒、参数写错、回复被截断，本来都是产生完就扔掉的信号，
 * 现在沉淀成一条 `lesson`，下次同样的场景它在上下文里就已经知道「上回这么干不行」。
 *
 * 对手靠几十万人各修一个坑变强；我们只有一个用户，那就让他这一台机器上的每次失败都留下痕迹。
 *
 * 三条设计取舍：
 *  - **确定性写入，不调模型**：失败信号本身就是结构化的（哪个工具、哪类失败），没必要为了写一句话
 *    再花一次 LLM 往返——那既慢又可能把事实写歪。
 *  - **同标题即同一条**：靠 upsert 天然去重，重复发生只是把重要度往上抬，不会攒出一堆近似条目。
 *  - **默认写入、事后可撤销**：不做"每条都等用户审"的晋升阶梯（卡点是审查量不是技术），
 *    写进去的东西在记忆页里看得见、删得掉。
 */
object LessonRecorder {

    enum class Kind(val label: String) {
        DENIED("工具被拒绝"),
        BAD_ARGS("参数不合法"),
        TRUNCATED("回复被截断"),
        PKG_OFF("功能包未启用"),
    }

    /** [com.arix.tool.ToolResult.failKind] → 教训类别；不认识的（或成功）返回 null = 不记。 */
    fun kindOf(failKind: String?): Kind? = when (failKind) {
        "denied" -> Kind.DENIED
        "bad_args" -> Kind.BAD_ARGS
        "pkg_off" -> Kind.PKG_OFF
        else -> null   // not_found 不记：那多半是模型一次拼错名字，记下来只会变成噪音
    }

    /** 每条教训的重要度上限：它是经验不是核心事实，别让它把真正重要的记忆挤出检索。 */
    private const val IMPORTANCE_CAP = 0.85f

    // ============================================================
    // 「调用前」提示 —— 把某个工具的教训挂到**它自己的 schema 描述**上
    //
    // 为什么要有这一层：教训本来只能靠回合开始时的记忆检索被捞出来。可那时它离"要不要调这个工具"
    // 这个决定还隔着一整片上下文，而且检索排名不保证它一定进得去。一轮里模型可能连调好几次工具，
    // 越往后，回合开头那句提醒越远。
    //
    // 真正该出现的位置是**它正在读那个工具的定义、决定要不要调它的那一刻**。所以做法是：
    // 构造 schema 时，只给"有教训的那几个工具"的描述后面接一句。有教训的工具是少数，
    // 没教训的一个字都不多发——每轮 schema 的 token 是刚压过的，不能白花。
    //
    // ⚠ 这里必须**同步、不读盘**：getToolsJson 每轮都跑，读 SharedPreferences/DB 会拖住发送路径。
    // 所以维护一份进程内镜像 [hints]，由 [record] 写入时顺手更新、[warmUp] 启动时装一次。
    // ============================================================

    /** tool → 一句给模型看的教训提示。写少读多，用不可变 map 整体替换，读侧不用加锁。 */
    @Volatile
    private var hints: Map<String, String> = emptyMap()

    /** 单个工具最多带一句、且截短：这段是每轮都发的，长了就成了固定税。 */
    private const val HINT_CAP = 150

    /**
     * 这个工具当前有没有「上回踩过」的提示。没有返回 null（调用方一个字都不加）。
     * 同步、无 IO —— 见上方说明。
     */
    fun hintFor(tool: String): String? = hints[tool]

    /** 有没有任何教训。给调用方一个提前退出的判据，省掉整趟遍历。 */
    fun hasAnyHint(): Boolean = hints.isNotEmpty()

    /**
     * 从记忆里把已有的教训装进内存镜像。启动时调一次即可；失败就当没有（这层是增强，不能反过来碍事）。
     *
     * 只认标题形如 `教训·<工具>·<类别>` 的条目——那正是 [record] 写进去的格式，
     * 所以这里解析的是我们自己刚写下的东西，不是猜别人的数据。
     */
    suspend fun warmUp(context: Context) {
        try {
            val mm = MemoryManager(context)
            val next = HashMap<String, String>()
            // 只挑教训类型，按更新时间从新到旧 → 同一个工具第一次遇到的就是最近那条。
            mm.all().filter { it.type == "lesson" }.forEach { m ->
                val parts = m.title.split("·")
                if (parts.size < 3 || parts[0] != "教训") return@forEach
                val tool = parts[1].takeIf { it.isNotBlank() } ?: return@forEach
                if (!next.containsKey(tool)) next[tool] = m.content.take(HINT_CAP)
            }
            hints = next
        } catch (_: Exception) {}
    }

    /** [record] 成功写库后顺手更新镜像，不用等下次启动。 */
    private fun noteHint(tool: String, content: String) {
        hints = hints + (tool to content.take(HINT_CAP))
    }

    /**
     * 记一条教训。[detail] 是当时的具体情形（会被截断，只取能说明问题的那点）。
     * 全程吞异常：这是后台增强，绝不能反过来影响对话本身。
     */
    suspend fun record(context: Context, cardId: Long?, kind: Kind, tool: String, detail: String = "") {
        try {
            val mm = MemoryManager(context)
            val title = "教训·$tool·${kind.label}".take(80)
            // 重复发生就往上抬一档：同一个坑踩得越多，越该在检索里靠前
            val prev = mm.idByTitle(title)?.let { mm.getById(it) }
            val importance = ((prev?.importance ?: 0.45f) + 0.1f).coerceAtMost(IMPORTANCE_CAP)
            val content = buildString {
                append(advice(kind, tool))
                if (detail.isNotBlank()) append(" 当时的情形：").append(detail.take(160).replace('\n', ' '))
            }.take(500)
            mm.upsertByTitle(title, content, "lesson", importance, cardId, emptyList(), "lesson")
            // 顺手更新「调用前提示」镜像，不用等下次启动才生效——刚踩的坑，下一轮就该看得见
            noteHint(tool, content)
        } catch (_: Exception) {}
    }

    /**
     * 教训正文。⚠ 这是**给模型看的**，写"下次该怎么办"，不是写"发生了什么错误"——
     * 只描述故障的记忆，模型读了也不知道该改什么。
     */
    private fun advice(kind: Kind, tool: String): String = when (kind) {
        Kind.DENIED -> "用户拒绝过「$tool」。再遇到需要它的场景，先说清为什么需要、要拿它做什么，或者直接换不需要授权的做法，别默默重试。"
        Kind.BAD_ARGS -> "调「$tool」时参数不合法被挡下过。用之前按工具定义核一遍必填项与取值范围，别凭印象拼参数。"
        Kind.TRUNCATED -> "调「$tool」时因为一次塞的内容太多、回复在长度上限处被截断。这类活分几轮做，单次调用别带大段内容。"
        Kind.PKG_OFF -> "「$tool」所在的功能包没启用过。需要它时先申请启用，别绕路用别的工具硬凑。"
    }
}
