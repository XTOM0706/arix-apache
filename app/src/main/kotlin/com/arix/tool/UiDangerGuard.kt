package com.arix.tool

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

// ============================================================
// UiDangerGuard —— AI 界面操作的「事前确认」。
//
// 项目已有的可视化是**事后**告诉用户「刚点了哪儿」。对转账、付款、删除、卸载这类
// 一步到位、事后撤不回来的操作，事后知道等于没知道。这里在手势真正注入**之前**
// 把用户拦下来问一句。
//
// 设计上最怕的不是漏报而是**误报**：一天问十次，用户就练成了闭眼点同意，
// 那时候真正该拦的那一次也拦不住。所以判定是「分级词表 + 旁证信号」的组合，
// 而不是见到关键词就问；并且同一个目标在短时间内只问一次。
// ============================================================

/**
 * 高危词表 —— 全项目唯一一处。
 *
 * 单独拎出来是因为这张表注定要长期调：用户反馈「这个也该问」「那个别问了」时，
 * 改的应该只有这里，而不是去判定函数里翻 if。
 */
object UiDangerLexicon {

    /** 危险等级。分两档是为了让「支付」和「发送」受到不同的对待。 */
    enum class Tier {
        /** 见词即问：这类词出现在一个按钮上，几乎不可能是无害的。 */
        CRITICAL,

        /** 见词存疑：日常界面里也大量出现（聊天的「发送」、表单的「确定」），要有旁证才问。 */
        SENSITIVE,
    }

    /** 钱、数据、设备状态，一步就没了的。 */
    val critical: List<String> = listOf(
        // 资金
        "确认支付", "立即支付", "确认付款", "立即付款", "确认转账", "转账", "付款", "支付",
        "提现", "确认提现", "还款", "确认还款", "借款", "确认借款", "充值", "确认充值",
        "免密支付", "开通免密", "确认扣款", "立即购买", "确认购买", "提交订单", "确认下单",
        // 数据/设备
        "删除", "永久删除", "清空", "清除数据", "恢复出厂", "格式化", "卸载", "解除绑定", "解绑",
        "注销账号", "注销", "退出登录",
        // 授权
        "同意授权", "允许授权", "确认授权", "同意并继续",
    )

    /** 语境依赖：只有当同屏还有别的危险迹象时才问。 */
    val sensitive: List<String> = listOf(
        "发送", "发布", "提交", "确认", "确定", "同意", "下一步", "继续", "立即领取", "开通",
    )

    /**
     * 目标文字最长多少字才当「按钮」看。
     *
     * 没有这条，聊天记录里一句「我把那个文件删除了」也会命中「删除」——
     * 而那只是屏幕上的一段话，点它什么也不会发生。按钮上的字基本都很短。
     */
    const val MAX_BUTTON_LABEL_LEN = 14

    /** 金融/支付类 App 的包名前缀。命中它，SENSITIVE 词就升格成要问。 */
    private val financePkgPrefixes = listOf(
        "com.eg.android.AlipayGphone",  // 支付宝
        "com.alipay",
        "com.unionpay",                 // 云闪付
        "com.icbc", "com.ccb", "com.abchina", "com.bankcomm", "com.cmbchina", "com.boc",
        "cmb.pb", "com.pingan", "com.cebbank", "com.citiccard", "com.cgbchina", "com.psbc",
        "com.jd.jrapp",                 // 京东金融
        "com.baidu.wallet",
        "com.paypal",
    )

    /**
     * 通用包名特征。银行 App 太多了，穷举不完；这些词出现在包名里基本就是金融类。
     * 故意不收「微信/QQ」：它们是聊天为主的混合 App，收进来会让每一次「发送」都弹框，
     * 而微信里真正危险的那步（转账/付款）本来就有 CRITICAL 词兜着。
     */
    private val financePkgHints = listOf("bank", "pay", "wallet", "finance", "alipay", "unionpay", "jinrong")

    fun isFinanceApp(pkg: String?): Boolean {
        if (pkg.isNullOrBlank()) return false
        val p = pkg.lowercase()
        return financePkgPrefixes.any { pkg.startsWith(it) } || financePkgHints.any { p.contains(it) }
    }

    /** 金额：¥100 / 100.00元 / $9.9。不认光秃秃的数字，否则任何列表页都算「有金额」。 */
    private val amountRegex = Regex("""[¥￥$]\s?\d|\d[\d,]*(\.\d{1,2})?\s?元""")

    fun looksLikeAmount(text: String): Boolean = amountRegex.containsMatchIn(text)

    /** 这段文字命中了哪一档；都不中返回 null。长句直接判无（见 [MAX_BUTTON_LABEL_LEN]）。 */
    fun tierOf(label: String): Tier? {
        val t = label.trim()
        if (t.isBlank() || t.length > MAX_BUTTON_LABEL_LEN) return null
        if (critical.any { t.contains(it) }) return Tier.CRITICAL
        if (sensitive.any { t.contains(it) }) return Tier.SENSITIVE
        return null
    }
}

/** 一次判定的结论。[reason] 是给用户看的「为什么拦你」，不是日志。 */
data class UiDangerVerdict(val danger: Boolean, val reason: String = "", val noiseKey: String = "")

object UiDangerGuard {

    private const val TAG = "UiDangerGuard"
    private const val PREFS = "xtom_ui_danger"
    private const val KEY_ENABLED = "enabled"

    /**
     * 「始终允许/始终禁止」记在这个键上。
     *
     * 故意**不叫** "ui_control"：用户在这个框上点「始终允许」，意思是「以后别再为危险单步拦我」，
     * 不是「以后 ui_control 工具本身也不用问了」。两件事混在一个键里，用户点一次就会失去他没打算放弃的东西。
     */
    private const val PERM_KEY = "ui_control·危险操作"

    // ---- 开关（默认开：安全兜底要用户主动关，不能靠他主动开）----

    fun isEnabled(context: Context): Boolean =
        try {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
        } catch (e: Exception) {
            // 读不到开关就按「开」处理：安全功能失效时应该更保守，而不是更放任
            Log.w(TAG, "读取开关失败，按开启处理", e); true
        }

    fun setEnabled(context: Context, v: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, v).apply()
    }

    // ---- 降噪 ----

    /**
     * 问过的目标 → 问过的时间。
     *
     * 要解决两种骚扰：①AI 一轮里对同一个按钮点了三次（重试/确认二次弹窗）；
     * ②用户刚在这个 App 里同意过同一个动作，转头又被问一遍。
     * 用「目标身份 + 时间窗」而不是「整个会话放行」：换个按钮、换个 App，还是要问。
     */
    private val recentlyConfirmed = LinkedHashMap<String, Long>()
    private const val NOISE_TTL_MS = 3 * 60_000L
    private const val NOISE_MAX = 32

    @Synchronized
    private fun askedRecently(key: String): Boolean {
        val now = System.currentTimeMillis()
        recentlyConfirmed.entries.removeAll { now - it.value > NOISE_TTL_MS }
        return recentlyConfirmed.containsKey(key)
    }

    @Synchronized
    private fun remember(key: String) {
        recentlyConfirmed[key] = System.currentTimeMillis()
        while (recentlyConfirmed.size > NOISE_MAX) {
            recentlyConfirmed.remove(recentlyConfirmed.keys.first())
        }
    }

    /** 新一轮自动化开始时清空，免得上一轮的「问过了」跨轮生效。 */
    @Synchronized
    fun resetSession() { recentlyConfirmed.clear() }

    // ---- 判定 ----

    /** 只有这几种动作会真的「按下」东西。滑动、返回、输入文字本身不提交，不拦。 */
    private val gatedActions = setOf("click_text", "tap", "long_press")

    /**
     * 拦不拦这一步。
     *
     * 信号有三路，单独任何一路都不够准，组合起来才敢打扰用户：
     *  1. **目标文字**——最直接。CRITICAL 词单独成立；SENSITIVE 词要旁证。
     *  2. **当前 App**——在银行/支付 App 里，「确定」的含义和在记事本里完全不同。
     *  3. **同屏上下文**——出现金额或密码输入框，说明这一屏就是交易/授权页。
     */
    private fun judge(
        action: String, targetLabel: String, pkg: String?,
        screenText: String, hasPasswordField: Boolean, x: Int, y: Int,
        blind: Boolean = false,
    ): UiDangerVerdict {
        if (action !in gatedActions) return UiDangerVerdict(false)

        // 完全读不到屏（无障碍没开、走 Shizuku 盲点按坐标）时，词表/App/金额三个信号全失效，
        // 原来直接放行 = 攻击者只要不开无障碍，就能让 AI 盲点「确认付款」零拦截。
        // 保守起见：盲态下的坐标点按一律要确认。噪声由 noiseKey 网格去重兜着（同一格 3 分钟只问一次），
        // click_text 有自带文字、不受影响。
        if (blind && (action == "tap" || action == "long_press")) {
            return UiDangerVerdict(true, "看不到当前屏幕内容（无障碍未开），无法判断这一点会不会触发付款或删除，先跟你确认。",
                "${pkg.orEmpty()}|@${x / 24},${y / 24}")
        }

        val finance = UiDangerLexicon.isFinanceApp(pkg)
        val amountOnScreen = UiDangerLexicon.looksLikeAmount(screenText)
        // 目标身份：有文字就用文字，纯坐标点就用粗粒度网格（24px 内的抖动算同一个目标）
        val ident = targetLabel.trim().ifBlank { "@${x / 24},${y / 24}" }
        val key = "${pkg.orEmpty()}|$ident"

        when (UiDangerLexicon.tierOf(targetLabel)) {
            UiDangerLexicon.Tier.CRITICAL ->
                return UiDangerVerdict(true, "这一步可能直接花钱或抹掉数据，做完撤不回来。", key)

            UiDangerLexicon.Tier.SENSITIVE -> {
                // 单看「发送/确定」远不足以打扰用户，必须有旁证：要么在金融 App 里，要么这一屏有钱
                if (finance || amountOnScreen || hasPasswordField) {
                    return UiDangerVerdict(true, "这一屏涉及资金或账号安全，「$targetLabel」按下去可能就生效了。", key)
                }
            }

            null -> {}
        }

        // 兜住最危险的绕过路径：AI 不用 click_text，而是 dump 完直接 tap 坐标——
        // 这样目标文字可能是空的，词表完全失灵。密码输入框是个很强的信号：
        // 支付密码页本来就少见，在这种页面上多问一次的代价远小于漏掉一次。
        if (finance && hasPasswordField) {
            return UiDangerVerdict(true, "当前是支付/密码页面，任何点击都可能确认交易。", key)
        }
        return UiDangerVerdict(false, noiseKey = key)
    }

    // ---- 对外入口 ----

    /**
     * 在动作执行前检查。返回 null = 放行；返回 [ToolResult] = 拦下了，把它当作工具结果回给 AI。
     *
     * 三条不可动摇的行为约定：
     *  - 判定逻辑出任何岔子，一律降级为放行，并打日志。安全功能自己坏掉不该顺带把 AI 卡死。
     *  - 等用户点的过程是挂起的，用户按 STOP 必须能中断——所以 CancellationException 必须原样抛出去，
     *    不能被下面那个 catch(Exception) 顺手吞了（项目里「STOP 停不掉」的老坑就是这么来的）。
     *  - 不静默：拦下时给 AI 的文字要说清是被用户拒了，让它别傻等或重试。
     */
    suspend fun guard(
        context: Context,
        svc: com.arix.app.XtomAccessibilityService?,
        action: String,
        params: org.json.JSONObject,
    ): ToolResult? {
        if (!isEnabled(context)) return null
        if (action !in gatedActions) return null

        // 第一段：纯计算，可以整段兜底成「放行」
        val verdict = try {
            collectAndJudge(svc, action, params)
        } catch (e: Exception) {
            Log.w(TAG, "危险判定异常，本次放行（不拦截 AI 操作）", e)
            null
        } ?: return null

        if (!verdict.danger) return null

        // 用户此前选过「始终」：尊重它，但别把「始终禁止」办成静默失败
        when (ToolPermissionManager.override(PERM_KEY)) {
            ToolPermission.ALLOW -> return null
            ToolPermission.FORBID -> return ToolResult(
                "已被用户的安全设置拦下：危险界面操作被设为「始终禁止」。请换一种方式，或让用户到设置里改。",
                isError = true,
            )
            else -> {}
        }

        if (verdict.noiseKey.isNotBlank() && askedRecently(verdict.noiseKey)) return null

        // 第二段：挂起等用户。这里**不能**包 catch(Exception)，否则会吞掉取消。
        val decision = ToolPermissionManager.confirmAction(
            key = PERM_KEY,
            level = AndroidPermissionLevel.ACCESSIBILITY,
            intent = describeStep(context, svc, action, params),
            riskNote = verdict.reason,
            detail = "危险操作事前确认 · 可在设置里关闭",
        )

        // 问不出来（权限系统没初始化）：放行但留痕，绝不无声无息地卡住 AI
        if (decision == null) {
            Log.w(TAG, "权限系统未初始化，无法确认，本次放行：$action")
            return null
        }
        val allowed = decision == ToolPermissionManager.Decision.ALLOW_ONCE ||
            decision == ToolPermissionManager.Decision.ALWAYS_ALLOW
        if (!allowed) {
            return ToolResult("用户拒绝了这一步操作（这是一个可能有风险的界面操作）。请不要重试，先问清楚他想怎么做。", isError = true)
        }
        if (verdict.noiseKey.isNotBlank()) remember(verdict.noiseKey)
        return null
    }

    /** 采集三路信号并判定。读屏可能失败（无障碍刚断/界面在切），失败就只剩参数里的文字可用。 */
    private fun collectAndJudge(
        svc: com.arix.app.XtomAccessibilityService?,
        action: String,
        params: org.json.JSONObject,
    ): UiDangerVerdict {
        val x = params.optInt("x", -1)
        val y = params.optInt("y", -1)
        // blind = 压根读不到屏（无障碍服务没连，或 dumpNodes 抛异常）。这时三个危险信号全瞎，
        // 不能当「没危险」放行。click_text 例外：它自带目标文字，判定不依赖读屏。
        var readFailed = false
        val nodes = try { svc?.dumpNodes().orEmpty() } catch (e: Exception) {
            Log.w(TAG, "读屏失败，判定降级为只看参数文字", e); readFailed = true; emptyList()
        }
        val blind = (svc == null || readFailed)

        val label = when (action) {
            "click_text" -> params.optString("text", "").trim()
            // 坐标点：目标文字得反查——包含该点的最小矩形才是用户眼里被点的那个东西
            else -> nodes.filter { it.bounds.contains(x, y) }
                .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }?.label.orEmpty()
        }

        val pkg = try { svc?.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        val screenText = nodes.joinToString(" ") { it.label }
        return judge(action, label, pkg, screenText, hasPasswordField(svc), x, y, blind)
    }

    /**
     * 屏幕上有没有密码输入框。
     *
     * dumpNodes 拿不到 isPassword（它只保留有文字的节点，而密码框通常没文字），
     * 所以这里自己浅走一遍树。上限压得很死：这只是个旁证信号，不值得为它遍历几百个节点。
     */
    private fun hasPasswordField(svc: com.arix.app.XtomAccessibilityService?): Boolean = try {
        val root = svc?.rootInActiveWindow
        var found = false
        var visited = 0
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || found || visited > 200 || depth > 25) return
            visited++
            if (n.isPassword) { found = true; return }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
        found
    } catch (e: Exception) {
        Log.w(TAG, "密码框探测失败，按无处理", e); false
    }

    /**
     * 弹窗主文案。必须一次说清三件事：**做什么动作 · 点的是哪个元素 · 在哪个 App**。
     * 少任何一件用户都判断不了该不该同意——「AI 想点击确认」这种话等于没说。
     */
    private fun describeStep(
        context: Context,
        svc: com.arix.app.XtomAccessibilityService?,
        action: String,
        params: org.json.JSONObject,
    ): String {
        val pkg = try { svc?.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
        val where = appLabel(context, pkg)?.let { "在「$it」里" } ?: ""
        val verb = when (action) {
            "long_press" -> "长按"
            else -> "点击"
        }
        val x = params.optInt("x", -1)
        val y = params.optInt("y", -1)
        val what = when (action) {
            "click_text" -> "「${params.optString("text", "").trim()}」"
            else -> {
                val l = try {
                    svc?.dumpNodes().orEmpty().filter { it.bounds.contains(x, y) }
                        .minByOrNull { it.bounds.width().toLong() * it.bounds.height() }?.label.orEmpty()
                } catch (_: Exception) { "" }
                if (l.isBlank()) "屏幕 ($x,$y) 的位置" else "「$l」（$x,$y）"
            }
        }
        return "AI 想${where}$verb$what"
    }

    /** 包名对用户没意义，尽量换成他认得的 App 名。 */
    private fun appLabel(context: Context, pkg: String?): String? {
        if (pkg.isNullOrBlank()) return null
        if (pkg == context.packageName) return null   // 就是本 App，说「在 Arix 里」纯属废话
        return try {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
    }
}
