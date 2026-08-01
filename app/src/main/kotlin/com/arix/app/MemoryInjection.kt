package com.arix.app

import android.content.Context

/**
 * 常驻记忆块 —— 每轮把**当前相关的记忆**放进系统提示的易变尾巴里。
 *
 * 为什么必须有：在这之前 `performSend` 里**一条记忆都不注入**。模型手上只有一个 `memory` 工具，
 * 而它不会平白无故去 search——于是记忆存了一堆，实际使用率接近零，用户的体感就是「存了没用」。
 * 对比过的四家全都在注入：常驻一段字符预算 / 注入一份用户档案 / 注入若干条检索片段。
 * 我们是唯一什么都不给的那个。
 *
 * 三条边界，都是有意的：
 *  1. **放 volatileTail，不放 staticSys**：静态前缀要逐字节稳定才能命中供应商的前缀缓存，
 *     记忆每轮都在变，塞进前缀等于把后面几千 token 的人设缓存一起废掉。
 *  2. **不带容量表头**：不写"你有个记忆库、用了 68%"这类话。那是变相的元指令，
 *     每轮提醒模型"你是个带记忆功能的程序"，违反本项目提示词的头号铁律。
 *  3. **只给数据，不规定它怎么用**：不写"请根据以下记忆回答"。给事实，怎么用是它的事。
 */
object MemoryInjection {
    /** 常驻块的字符预算。真正该限制的是这个数（每轮都在花），而不是记忆总条数。 */
    const val DEFAULT_CHAR_LIMIT = 1200

    /** 置顶记忆最多能占预算的百分之多少（其余名额留给"跟这次问的事相关的"）。 */
    private const val PIN_SHARE = 40

    /**
     * 拼一段可直接放进提示词的记忆块；没有可用记忆返回 null（调用方不追加任何东西）。
     *
     * @param userMessage 本轮用户说的话，用来做相关性检索（空串则退化成只给置顶/高重要度的）
     */
    suspend fun build(
        context: Context,
        cardId: Long?,
        userMessage: String,
        charLimit: Int = DEFAULT_CHAR_LIMIT,
    ): String? = try {
        val mm = MemoryManager(context)
        // ⚠ 超时兜底：检索里可能含一次 embedding 网络往返，而这里在**发送主路径上**——
        // 端点抽风时不能让"想不起来"变成"发不出去"。超时就这轮不注入，下轮再说。
        // ⚠ 这个超时也是「不开 LLM 语义兜底」的原因：一次对话模型往返 1.5 秒内基本回不来，
        //   而超时是**取消**——prompt token 已经发出去、钱照付，结果却丢弃。默认就是不开，这里只是把理由写在现场。
        val hits = kotlinx.coroutines.withTimeoutOrNull(1500) {
            // 多取一些再按预算截断：检索本身有相关性排序，靠前的先占位
            mm.queryRelevant(userMessage.take(500), 8, cardId)
        } ?: emptyList()
        if (hits.isEmpty()) null else {
            val sb = StringBuilder(PromptLang.pick("【已知信息】\n", "[Known information]\n"))
            var used = 0
            var pinUsed = 0
            for (m in hits) {
                // 单条也要有上限：一条 500 字的记忆能把整个预算吃掉，宁可多带几条不同的
                val line = "- ${m.title}：${m.content.take(160).replace('\n', ' ')}\n"
                if (used + line.length > charLimit) continue
                // 置顶记忆在检索里是无条件加成的，用户置顶了十几条就会把整个预算占满，
                // 于是每轮注入的都是同一批、跟这次问的事没关系。给它们留个上限，剩下的名额留给"这次相关的"。
                if (m.pinned) {
                    if (pinUsed + line.length > charLimit * PIN_SHARE / 100) continue
                    pinUsed += line.length
                }
                sb.append(line); used += line.length
            }
            if (used == 0) null else sb.toString().trimEnd()
        }
    } catch (_: Exception) { null }   // 记忆是增强不是主链路，坏了也不能挡住发送
}
