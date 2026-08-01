package com.arix.app

import android.content.Context

// ============================================================
// 自动朗读 —— AI 每条回复**流式收完**就自动念出来。
//
// 为什么另开一个 Prefs 而不是并进 [ChatEffectsPrefs]：那个管的是「气泡怎么动」——触感、渐显、回弹，
// 全是**渲染路径**上的东西，所以它必须走进程内缓存、必须经 LocalChatEffects 下发到每条气泡。
// 自动朗读一条都不在渲染路径上：判定点只有一个（一轮生成结束的那一刻），读到的值也只被那一处用。
// 混进去只会让「渲染热路径的配置」这个边界变模糊，以后有人照着往里加真正的行为开关就会出事。
//
// 同样是这个原因，这里**不需要** CompositionLocal：ChatPage 在自己的作用域里 remember 一次即可，
// 气泡内部永远碰不到它。
// ============================================================
object AutoReadPrefs {

    private const val PREF = "xtom_auto_read"

    @Volatile private var cached: Snapshot? = null

    /**
     * @param enabled 总开关。
     * @param dialogueOnly 角色扮演模式：只念引号里的台词，跳过旁白与括号/星号里的动作、心理描写。
     *   见 [RoleplaySpeech.dialogueOnly]。
     */
    data class Snapshot(
        val enabled: Boolean,
        val dialogueOnly: Boolean,
    )

    /** 默认**全关**。自动出声是会当众响的那类功能，绝不能装完就替用户开了。 */
    val DEFAULT = Snapshot(enabled = false, dialogueOnly = false)

    fun snapshot(c: Context): Snapshot = cached ?: load(c).also { cached = it }

    private fun load(c: Context): Snapshot {
        val sp = c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Snapshot(
            enabled = sp.getBoolean("enabled", DEFAULT.enabled),
            dialogueOnly = sp.getBoolean("dialogue_only", DEFAULT.dialogueOnly),
        )
    }

    fun save(c: Context, s: Snapshot) {
        c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("enabled", s.enabled)
            .putBoolean("dialogue_only", s.dialogueOnly)
            .apply()
        cached = s   // 同步刷缓存：设置页一改，聊天页下一轮就生效
    }

    fun reset(c: Context) {
        c.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        cached = DEFAULT
    }
}

// ============================================================
// RoleplaySpeech —— 从角色扮演文本里挑出「该念出声的部分」。
//
// 角色扮演的回复通常长这样：
//   *她把伞收进怀里，抖了抖水珠*「你来晚了。」（其实她已经等了半小时，但不打算说。）
// 全文照念会把动作和心理描写一起念出来，听感非常怪。这里做两件事：
//   ① 去掉**旁白载体**：单星号 `*…*` 与括号 `（…）`/`(…)` 里的内容；
//   ② 从剩下的文本里抽出**引号内的台词**。
//
// ⚠ 兜底很关键：抽不到任何引号就返回「去掉旁白后的全文」，而不是空串。
// 绝大多数普通问答一个引号都没有，若没有这条兜底，一开这个子开关整个自动朗读就集体哑掉。
// ============================================================
object RoleplaySpeech {

    /** 成对引号：开 → 闭。同种可嵌套（「他说「好」」），所以按深度配对而不是找第一个闭合。 */
    private val PAIRS = mapOf(
        '「' to '」',
        '『' to '』',
        '“' to '”',   // “ ”
    )

    // 单星号包住的动作描写。刻意**不吃双星号**：`**这样**` 是 Markdown 加粗，属于正文强调，
    // 一并删掉会把话说漏。前后的 (?<!\*)/(?!\*) 就是干这个的。
    private val ASTERISK = Regex("""(?<!\*)\*(?!\*)[^*\n]+\*(?!\*)""")
    private val PAREN_FULL = Regex("""（[^（）]*）""")
    private val PAREN_HALF = Regex("""\([^()]*\)""")

    /**
     * 只保留该念出声的部分。抽不到台词时返回去掉旁白后的全文（见类注释的兜底说明）。
     */
    fun dialogueOnly(raw: String): String {
        val stripped = stripNarration(raw)
        val lines = extractQuoted(stripped)
        // 台词之间用换行分隔：TTS 引擎普遍会在换行处断一下，连着念不会糊成一句。
        return if (lines.isEmpty()) stripped.trim() else lines.joinToString("\n")
    }

    /** 去掉星号/括号包住的动作与心理描写。括号跑三轮，简单嵌套也能扒干净。 */
    private fun stripNarration(s: String): String {
        var t = ASTERISK.replace(s, " ")
        repeat(3) {
            val before = t
            t = PAREN_FULL.replace(t, " ")
            t = PAREN_HALF.replace(t, " ")
            if (t == before) return@repeat
        }
        return t
    }

    /**
     * 抽出所有引号内的文字。
     *
     * 成对引号（「」『』“”）按深度配对，允许同种嵌套；英文直引号 `"` 开闭同形，只能就近配对。
     * **没找到闭合就不当引号**——半个引号（比如流式截断、或正文里孤零零一个 `"`）不该把后面整段吞掉。
     */
    private fun extractQuoted(s: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        val n = s.length
        while (i < n) {
            val c = s[i]
            val close = PAIRS[c]
            if (close != null) {
                var depth = 1
                val sb = StringBuilder()
                var j = i + 1
                while (j < n) {
                    val d = s[j]
                    when {
                        d == c -> { depth++; sb.append(d) }
                        d == close -> { depth--; if (depth == 0) break; sb.append(d) }
                        else -> sb.append(d)
                    }
                    j++
                }
                if (j < n) {                       // 找到闭合才算数
                    sb.toString().trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    i = j + 1
                    continue
                }
            } else if (c == '"') {
                val j = s.indexOf('"', i + 1)
                if (j > i) {
                    s.substring(i + 1, j).trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return out
    }
}
