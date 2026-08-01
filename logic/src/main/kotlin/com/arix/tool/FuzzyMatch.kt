package com.arix.tool

/**
 * 全项目共用的轻量模糊匹配。
 *
 * 手表算力有限，所以打分是分级短路的：先做零成本判定(相等 → 子串)，只有粗筛(字符集重叠 + 长度)通过
 * 才落到 O(n·m) 的编辑距离，长文本直接跳过近似匹配。
 *
 * 分值恒定落在 0..1，且**精确命中永远高于模糊命中**（子串 ≥0.90，模糊 ≤0.85），
 * 调用方只要按分排序，就自动满足「精确在前、模糊作补充」，不会被模糊结果淹没。
 */
object FuzzyMatch {

    const val EXACT = 1.0f

    /** 低于此分视为不匹配。调用方要更严可以自己传更高的 minScore。 */
    const val MIN_SCORE = 0.30f

    /** 近似匹配是 O(pattern·target)，超过这个长度的目标只走子串判定，免得在手表上卡住。 */
    private const val APPROX_TARGET_CAP = 2000

    /** 子序列匹配在长文本里几乎必然命中(字符散落各处)，只对短目标(名字/标题)开放。 */
    private const val SUBSEQ_TARGET_CAP = 200

    data class Scored<T>(val item: T, val score: Float)

    /** 全半角统一 + 小写 + 折叠空白，让「ＡＢＣ」「abc」「a  b c」彼此可比。 */
    fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = when {
                ch.code == 0x3000 -> ' '                                  // 全角空格
                ch.code in 0xFF01..0xFF5E -> (ch.code - 0xFEE0).toChar()  // 全角 ASCII → 半角
                else -> ch
            }
            sb.append(c.lowercaseChar())
        }
        return sb.toString().trim().replace(WS, " ")
    }

    private val WS = Regex("\\s+")

    private fun isCjk(c: Char): Boolean =
        c.code in 0x3400..0x9FFF || c.code in 0xF900..0xFAFF || c.code in 0x3040..0x30FF

    /**
     * 拆关键词。西文按空白/标点切；中文没有空格，所以按连续汉字段整段成词——
     * 段内的缺字/错字交给编辑距离处理（容错 2 时「北京天气」仍能命中「天气怎么样」），
     * 比按单字拆更省算力，也不会让单字噪音刷屏。
     */
    fun tokens(query: String): List<String> {
        val n = normalize(query)
        val out = LinkedHashSet<String>()
        val buf = StringBuilder()
        fun flush() { if (buf.isNotEmpty()) { out.add(buf.toString()); buf.clear() } }
        var i = 0
        while (i < n.length) {
            val c = n[i]
            when {
                isCjk(c) -> {
                    flush()
                    val start = i
                    while (i < n.length && isCjk(n[i])) i++
                    out.add(n.substring(start, i))
                }
                c.isLetterOrDigit() -> { buf.append(c); i++ }
                else -> { flush(); i++ }
            }
        }
        flush()
        return out.toList()
    }

    /** 短词只容 1 个错，长词容 2；两字以内不容错——否则「天气」会命中「天天」这类无关项。 */
    private fun tolerance(len: Int): Int = when {
        len <= 2 -> 0
        len <= 5 -> 1
        else -> 2
    }

    /**
     * 给 query 对 target 的匹配打分，0 表示不匹配。
     * 多关键词词序无关：覆盖率(命中几个词)主导，单词质量(精确/错字/子序列)做权重。
     */
    fun score(query: String, target: String): Float {
        if (query.isBlank() || target.isBlank()) return 0f
        val q = normalize(query)
        val t = normalize(target)
        if (q == t) return EXACT
        if (t.contains(q)) return if (t.startsWith(q)) 0.95f else 0.90f

        val toks = tokens(q)
        if (toks.isEmpty()) return 0f
        var sum = 0f
        var hit = 0
        for (tok in toks) {
            val s = tokenScore(tok, t)
            if (s > 0f) { hit++; sum += s }
        }
        var s = if (hit == 0) 0f else 0.85f * (hit.toFloat() / toks.size) * (sum / hit)

        // 整串子序列兜底：「wkspce」→「workspace」这类跳字输入，精确/编辑距离都够不着
        if (s < 0.45f && t.length <= SUBSEQ_TARGET_CAP) {
            val compact = q.filter { !it.isWhitespace() }
            if (compact.length >= 3 && isSubsequence(compact, t)) s = maxOf(s, 0.40f)
        }
        return if (s >= MIN_SCORE) s else 0f
    }

    /** 多字段打分取最高：如「标题 + 内容」分别算，避免长内容把短标题的命中稀释掉。 */
    fun bestScore(query: String, vararg fields: String): Float {
        var best = 0f
        for (f in fields) {
            val s = score(query, f)
            if (s > best) best = s
            if (best >= EXACT) break
        }
        return best
    }

    fun matches(query: String, target: String, minScore: Float = MIN_SCORE): Boolean =
        score(query, target) >= minScore

    /**
     * 打分 + 排序 + 截断。精确命中天然分高排前，模糊命中自动排在其后作补充。
     * text 返回该项参与匹配的文本（多字段自行拼接，或改用 rankBy）。
     */
    fun <T> rank(
        query: String,
        items: Iterable<T>,
        limit: Int = Int.MAX_VALUE,
        minScore: Float = MIN_SCORE,
        text: (T) -> String
    ): List<Scored<T>> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val out = ArrayList<Scored<T>>()
        for (item in items) {
            val s = score(query, text(item))
            if (s >= minScore) out.add(Scored(item, s))
        }
        out.sortByDescending { it.score }
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    /** rank 的多字段版：字段间取最高分，不会被长字段拉低。 */
    fun <T> rankBy(
        query: String,
        items: Iterable<T>,
        limit: Int = Int.MAX_VALUE,
        minScore: Float = MIN_SCORE,
        fields: (T) -> List<String>
    ): List<Scored<T>> {
        if (query.isBlank() || limit <= 0) return emptyList()
        val out = ArrayList<Scored<T>>()
        for (item in items) {
            val s = bestScore(query, *fields(item).toTypedArray())
            if (s >= minScore) out.add(Scored(item, s))
        }
        out.sortByDescending { it.score }
        return if (out.size > limit) out.subList(0, limit).toList() else out
    }

    // ---- 内部：单词打分与距离 ----

    private fun tokenScore(tok: String, target: String): Float {
        if (tok.isEmpty()) return 0f
        if (target.contains(tok)) return 1.0f
        val tol = tolerance(tok.length)
        if (tol > 0 && target.length <= APPROX_TARGET_CAP && shareEnoughChars(tok, target, tol)) {
            val d = approxContainsDistance(tok, target, tol)
            if (d >= 0) return 1.0f - 0.25f * d      // 错 1 字 → 0.75，错 2 字 → 0.5
        }
        if (tok.length >= 4 && target.length <= SUBSEQ_TARGET_CAP && isSubsequence(tok, target)) return 0.5f
        return 0f
    }

    /**
     * 廉价粗筛：目标里至少要出现 tok 的大部分不同字符，否则不可能在容错内匹配上。
     * 这一步把绝大多数候选挡在编辑距离之外——候选量大时性能全靠它。
     */
    private fun shareEnoughChars(tok: String, target: String, tol: Int): Boolean {
        val distinct = HashSet<Char>()
        for (c in tok) distinct.add(c)
        val need = distinct.size - tol
        if (need <= 0) return true
        var have = 0
        for (c in distinct) if (target.indexOf(c) >= 0) have++
        return have >= need
    }

    /**
     * 「带容错的子串匹配」：pat 作为近似子串出现在 text 中的最小编辑距离，超出 maxDist 返回 -1。
     * 首行置 0 = 允许在 text 任意位置起匹配（否则长目标 vs 短词的整串编辑距离恒超限，毫无意义）。
     */
    private fun approxContainsDistance(pat: String, text: String, maxDist: Int): Int {
        val n = text.length
        var prev = IntArray(n + 1)          // i=0 行全 0
        var cur = IntArray(n + 1)
        for (i in 1..pat.length) {
            cur[0] = i
            var rowMin = i
            val pc = pat[i - 1]
            for (j in 1..n) {
                val cost = if (pc == text[j - 1]) 0 else 1
                var v = prev[j - 1] + cost
                val del = prev[j] + 1
                if (del < v) v = del
                val ins = cur[j - 1] + 1
                if (ins < v) v = ins
                cur[j] = v
                if (v < rowMin) rowMin = v
            }
            // 行最小值随行号单调不减，整行都超容错就不可能再回落，提前退出
            if (rowMin > maxDist) return -1
            val tmp = prev; prev = cur; cur = tmp
        }
        val best = prev.minOrNull() ?: return -1
        return if (best <= maxDist) best else -1
    }

    private fun isSubsequence(needle: String, hay: String): Boolean {
        if (needle.isEmpty()) return true
        var i = 0
        for (c in hay) {
            if (c == needle[i]) { i++; if (i == needle.length) return true }
        }
        return false
    }
}
