package com.arix.app

import android.content.Context

/**
 * 记忆功能开关 —— 补边（提到了但没连）、关联面板、日记入图、标签 UI、局部图谱。
 *
 * 为什么单开一个 Prefs 而不是塞进别处：这几项管的是**记忆图谱怎么长出来**，而不是页面长什么样。
 * 它们的取舍标准也不同——补边扫描要花 CPU、日记入图要往记忆库写行，两者都得能单独关掉，
 * 出问题能一项一项排除；纯展示的那几项（关联面板/标签/局部图谱）不花后台成本，可以默认开着。
 *
 * ⚠ 读取走**进程内缓存**（仿 [ChatEffectsPrefs]）：记忆页是长列表，这些值会在编辑弹窗、
 * 筛选行、图谱着色这些每次重组都要过的路径上被读到，去读盘会直接卡列表。写入时同步刷缓存，
 * 设置页一改立刻生效，不用重进页面。
 *
 * **默认值取「不改变现有行为」那侧**：会跑扫描的（[mentions]）、会往库里写行的（[diaryToGraph]）
 * 一律默认关；只在用户主动打开的弹窗里读已有数据、不写不扫的默认开——那几项不开等于白做，
 * 而且「谁把我这条顶掉了」本来就该看得见。
 */
object MemoryFeaturePrefs {

    private const val PREF = "xtom_memory_features"

    /** 一次性读全的快照。UI 侧只碰它，不碰 SharedPreferences。 */
    data class Snapshot(
        /** 「提到了但没连」：扫正文/标题里出现的其它记忆标题，列出来让用户一键建边。**会跑扫描 → 默认关**。 */
        val mentions: Boolean,
        /** 参与匹配的标题最小长度。太短的标题（「工作」「今天」）会命中一大片噪声。 */
        val mentionMinLen: Int,
        /** 记忆详情里的「关联 / 被谁提到」面板：读已有的边，不写不扫。 */
        val backlinks: Boolean,
        /** 日记同时落一条记忆（type=event、source=diary）并与当天新增记忆连边。**会写库 → 默认关**。 */
        val diaryToGraph: Boolean,
        /** 标签 UI：编辑弹窗的标签行 + 筛选行的标签芯片。数据本来就在库里，只是从来没显示过。 */
        val tagUi: Boolean,
        /** 局部图谱：编辑弹窗的「看关系」按钮，只画这条记忆两跳内的邻居。 */
        val localGraph: Boolean,
        /** 图谱节点按**来源**着色（我说的 / AI 抽的 / 工具写的），而不是按类型。默认关=保持现状按类型。 */
        val graphColorBySource: Boolean,
    )

    val DEFAULT = Snapshot(
        mentions = false,
        mentionMinLen = 4,
        backlinks = true,
        diaryToGraph = false,
        tagUi = true,
        localGraph = true,
        graphColorBySource = false,
    )

    /** 标题下限的可选范围。低于 2 的话单个汉字会把整库连成一坨；高于 8 基本什么都匹配不到。 */
    const val MIN_LEN_LOW = 2
    const val MIN_LEN_HIGH = 8

    @Volatile private var cached: Snapshot? = null

    fun snapshot(c: Context): Snapshot = cached ?: load(c).also { cached = it }

    private fun load(c: Context): Snapshot {
        val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Snapshot(
            mentions = sp.getBoolean("mentions", DEFAULT.mentions),
            mentionMinLen = sp.getInt("mention_min_len", DEFAULT.mentionMinLen).coerceIn(MIN_LEN_LOW, MIN_LEN_HIGH),
            backlinks = sp.getBoolean("backlinks", DEFAULT.backlinks),
            diaryToGraph = sp.getBoolean("diary_to_graph", DEFAULT.diaryToGraph),
            tagUi = sp.getBoolean("tag_ui", DEFAULT.tagUi),
            localGraph = sp.getBoolean("local_graph", DEFAULT.localGraph),
            graphColorBySource = sp.getBoolean("graph_color_by_source", DEFAULT.graphColorBySource),
        )
    }

    fun save(c: Context, s: Snapshot) {
        val prev = cached
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("mentions", s.mentions)
            .putInt("mention_min_len", s.mentionMinLen.coerceIn(MIN_LEN_LOW, MIN_LEN_HIGH))
            .putBoolean("backlinks", s.backlinks)
            .putBoolean("diary_to_graph", s.diaryToGraph)
            .putBoolean("tag_ui", s.tagUi)
            .putBoolean("local_graph", s.localGraph)
            .putBoolean("graph_color_by_source", s.graphColorBySource)
            .apply()
        cached = s   // 同步刷缓存：设置页改完立刻生效
        // 只有标题下限变了才丢缓存——它是索引的输入之一。别的开关（比如图谱着色）跟扫描无关，
        // 顺手 invalidate 会让用户每换一次颜色就得重扫一遍。
        if (prev == null || prev.mentionMinLen != s.mentionMinLen) MemoryMentions.invalidate()
    }

    fun reset(c: Context) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        cached = DEFAULT
        MemoryMentions.invalidate()
    }
}
