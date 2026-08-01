package com.arix.tool

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

// ============================================================
// 工具权限系统（对齐 Operit：两层）
//   1) AndroidPermissionLevel —— 工具所需的“基础设施”特权等级（阶段2用它选择 Shizuku/root 执行器）
//   2) ToolPermission        —— 用户对该工具的放行策略：允许 / 询问 / 禁止
// 阶段1：只做“闸门”——执行前按策略拦截，ASK 时弹窗让用户决定，可记住“始终允许/禁止”。
// ============================================================

/** 工具所需的系统特权等级，由低到高。 */
enum class AndroidPermissionLevel(val label: String) {
    STANDARD("普通"),

    /**
     * **隐私级**：不需要任何特殊基础设施（安卓那层运行时权限给过就能跑），但**碰的是用户本人的东西**——
     * 通讯录、短信正文、日历、通知、剪贴板、应用使用情况、整屏截图、身体传感器读数，以及「直接拨出去」这种
     * 一按就对外发生、收不回来的动作。
     *
     * 为什么要单立一档而不是接着用 [STANDARD]：STANDARD 的语义是「跟随主开关」，而主开关默认 `ALLOW`——
     * 那是给「查天气、开手电筒、算个数」定的，对的。但同一档里塞着 `contacts`/`read_sms`/`clipboard`，
     * 结果是**新装的机器上，AI 读短信读剪贴板一次都不问**（安卓的运行时权限在权限页给过一次之后就再没有第二道闸）。
     * 抬到 ACCESSIBILITY 也不对：那一档的含义是「要无障碍这套基础设施」，`contacts` 压根不要，
     * 标上去会让权限页和能力裁剪（见 [ToolRequirement]）一起说谎。
     *
     * 这一档的两条实际后果：
     *  1. 默认策略 = `ASK`（见 [ToolPermissionManager.defaultFor]），**不跟随主开关**；点过「始终允许」就不再问。
     *  2. **永不进模型自动审批**（那条只放行 STANDARD，见 [ToolPermissionManager.askGate]）——
     *     让一个便宜的小模型替用户签字同意「读我的短信」，是这套系统最不该干的事。
     */
    PRIVATE("隐私"),

    ACCESSIBILITY("无障碍"),
    DEBUGGER("调试/Shizuku"),
    ADMIN("设备管理员"),
    ROOT("Root");

    companion object {
        fun fromString(s: String?): AndroidPermissionLevel =
            values().firstOrNull { it.name.equals(s, true) } ?: STANDARD
    }
}

/** 用户对某个工具的放行策略。 */
enum class ToolPermission {
    ALLOW,   // 自动放行，不打扰
    ASK,     // 每次询问
    FORBID;  // 永不允许

    companion object {
        fun fromString(s: String?): ToolPermission =
            values().firstOrNull { it.name.equals(s, true) } ?: ASK
    }
}

/**
 * 工具没被放行时，**到底为什么**。
 *
 * 原来闸门只回一个 Boolean，三种截然不同的情形（用户当场点了拒绝 / 用户早就把它设成禁止 / 框弹了没人应答）
 * 被压成同一句话回给模型，于是它只会一遍遍换个说法重试。回给模型的话必须让它知道下一步该干什么。
 */
enum class DenyReason {
    /** 用户本人当场点了「拒绝」（含「始终禁止」那一下）。 */
    USER_DENIED,
    /** 该工具被用户的权限策略设为禁止，压根没弹窗。 */
    POLICY_FORBID,
    /** 弹了框但没人应答：框当时不在用户眼前，攒够 60s 判拒。 */
    NO_RESPONSE,
    /** 问不出来（权限系统未初始化等）——保守判拒。 */
    UNAVAILABLE,
}

/** 闸门结论。[ToolPermissionManager.check] 是它的 Boolean 兼容封装。 */
sealed class ToolGate {
    data object Pass : ToolGate()
    data class Denied(val reason: DenyReason) : ToolGate()
}

object ToolPermissionManager {
    private const val PREFS = "xtom_tool_permissions"
    private const val KEY_MASTER = "__master__"   // 标准级工具的全局默认策略
    private var prefs: SharedPreferences? = null

    /** 用户对一次 ASK 的选择。 */
    enum class Decision { ALLOW_ONCE, DENY_ONCE, ALWAYS_ALLOW, ALWAYS_DENY }

    data class PendingRequest(
        val toolName: String,
        val level: AndroidPermissionLevel,
        val intent: String,        // 人话：这一步到底想干什么
        val riskNote: String,      // 这个权限等级/工具的风险提示
        val argsPreview: String,   // 原始参数明细（展开细看）
        val deferred: CompletableDeferred<Decision>,
        /** 谁在要这个权限。弹窗必须说清——「AI 想跑 shell」和「某第三方插件想跑 shell」是两回事。 */
        val caller: ToolCaller = ToolCaller.Ai,
    )

    /** UI 观察此流：非空时弹权限请求框。 */
    val pending = MutableStateFlow<PendingRequest?>(null)

    /**
     * 当前有几个 UI 挂着这个弹窗（唤醒助手、后台子agent、外部 MCP 客户端那些地方压根没人渲染这个框）。
     * 注意：登记数**不等于**用户看得见——判据见 [canUserSee]。
     */
    private val uiCount = java.util.concurrent.atomic.AtomicInteger(0)
    fun attachUi() { uiCount.incrementAndGet() }
    fun detachUi() { uiCount.decrementAndGet() }

    private var appContext: Context? = null

    /** 没人看得见框时，最多等这么久就判拒——只在看不见的时候消耗。 */
    private const val BLIND_TIMEOUT_MS = 60_000L
    private const val STEP_MS = 1_000L

    /**
     * 用户此刻**真的**看得见这个框吗。
     *
     * 不能只看 [uiCount]：聊天页是常驻 composition（切页面不销毁，见 ChatPage），登记会一直挂着，
     * 哪怕 App 已经退到别的 App 后面去了。而框属于 MainActivity 的 window，那时压在别人后面，
     * 用户看不见也点不到——只看登记数就会误判成「有人看得见」→ 无限等 → 整轮生成永久挂死。
     * 这正是「AI 打开某个 App 后要操作它，权限框却在后台点不了」的成因。
     */
    private fun canUserSee(): Boolean =
        com.arix.app.PermissionOverlayHost.isShowing ||
            (uiCount.get() > 0 && com.arix.app.AppForeground.isForeground)

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        appContext = context.applicationContext
    }

    // ---- 策略存取 ----
    fun masterMode(): ToolPermission = ToolPermission.fromString(prefs?.getString(KEY_MASTER, ToolPermission.ALLOW.name))
    fun setMasterMode(m: ToolPermission) { prefs?.edit()?.putString(KEY_MASTER, m.name)?.apply() }

    fun override(tool: String): ToolPermission? =
        prefs?.getString(tool, null)?.let { ToolPermission.fromString(it) }

    fun setOverride(tool: String, p: ToolPermission?) {
        prefs?.edit()?.apply { if (p == null) remove(tool) else putString(tool, p.name) }?.apply()
    }

    /**
     * 清掉某调用者命名空间下的**全部**策略（键前缀 = `caller.prefix`）。
     * 用于卸载插件时撤销它攒下的所有授权——否则 `plugin:<pkgId>:*` 的「始终允许」会残留在 prefs 里，
     * 日后一个 manifest id 相同的恶意包重装即静默继承（第三轮红队确认的 id 抢注/卸载残留链）。
     */
    fun clearCallerOverrides(prefix: String) {
        if (prefix.isBlank()) return
        val p = prefs ?: return
        val keys = p.all.keys.filter { it.startsWith(prefix) }
        if (keys.isEmpty()) return
        p.edit().apply { keys.forEach { remove(it) } }.apply()
    }

    /**
     * 未设置 per-tool override 时的默认：**只有** [AndroidPermissionLevel.STANDARD] 跟随主开关，
     * 其余（含 [AndroidPermissionLevel.PRIVATE]）一律 ASK。
     *
     * ⚠ 别把 PRIVATE 也并进「跟随主开关」那一侧：主开关默认 ALLOW，那正是「AI 读短信零提示」的成因。
     */
    private fun defaultFor(level: AndroidPermissionLevel): ToolPermission =
        if (level == AndroidPermissionLevel.STANDARD) masterMode() else ToolPermission.ASK

    fun effective(tool: String, level: AndroidPermissionLevel): ToolPermission =
        override(tool) ?: defaultFor(level)

    /**
     * 按调用者取策略。键加调用者前缀（AI 无前缀 = 与老数据兼容，用户已设的不丢）。
     *
     * 插件默认 **ASK** 而不是跟随主开关：主开关默认 ALLOW，是给「用户自己驱动的 AI」定的；
     * 第三方插件的代码不是用户写的，凭什么继承同一份信任。没设过 = 问一次。
     */
    fun effectiveFor(caller: ToolCaller, tool: String, level: AndroidPermissionLevel): ToolPermission =
        override(caller.prefix + tool)
            ?: when (caller) {
                is ToolCaller.Ai, is ToolCaller.UserScript -> defaultFor(level)
                else -> ToolPermission.ASK
            }

    // ---- 执行前检查 ----

    /**
     * 闸门本体：过 or 不过 + **为什么不过**。调用方（[ToolManager]）据此给模型不同的话。
     */
    suspend fun checkGate(tool: Tool, args: JSONObject, caller: ToolCaller = ToolCaller.Ai): ToolGate =
        when (effectiveFor(caller, tool.name, tool.permissionLevel)) {
            ToolPermission.ALLOW -> ToolGate.Pass
            ToolPermission.FORBID -> ToolGate.Denied(DenyReason.POLICY_FORBID)
            ToolPermission.ASK -> askGate(tool, args, caller)
        }

    /** Boolean 兼容封装：只关心「过不过」的老调用点（插件/MCP/内部工具复用）不用改。 */
    suspend fun check(tool: Tool, args: JSONObject, caller: ToolCaller = ToolCaller.Ai): Boolean =
        checkGate(tool, args, caller) is ToolGate.Pass

    /**
     * 门控一个「插件能力」——不是一次工具调用，而是某个包想动敏感链路（如改写系统提示、拦截代答）。
     * 复用工具那套「按 caller 记忆 允许/询问/禁止」的闸：[key] 作为该能力的存储键，always 记在
     * `caller.prefix + key` 上（不同包互不影响）。插件默认走 ASK（见 [effectiveFor]：Plugin 无 override 即询问），
     * 所以「装了就悄悄生效」变成「第一次要用时问一声、可记住」。prefs 没起来时 [confirmAction] 返回 null → 判拒（fail-closed）。
     */
    suspend fun checkCapability(
        caller: ToolCaller, key: String, level: AndroidPermissionLevel, intent: String, riskNote: String,
    ): Boolean {
        // **不走 effectiveFor**：那条对 Ai/UserScript 会下沉到 defaultFor(STANDARD)=masterMode(默认 ALLOW)，
        // 于是「能力门控」对非插件调用者变成静默放行的脚枪（第三轮红队 #K）。能力闸只认显式 override，
        // 没设过一律 ASK——无论 caller 是谁、无论 level。level 仅用于弹窗展示。
        return when (override(caller.prefix + key) ?: ToolPermission.ASK) {
            ToolPermission.ALLOW -> true
            ToolPermission.FORBID -> false
            ToolPermission.ASK -> confirmAction(key, level, intent, riskNote, caller = caller)
                ?.let { it == Decision.ALLOW_ONCE || it == Decision.ALWAYS_ALLOW } ?: false
        }
    }

    /**
     * ASK 分支：先给「模型自动审批」一次机会，不成再照旧弹窗问人。
     *
     * 自动审批的三条红线（改这里前先读 [com.arix.app.ToolApprovalJudge]）：
     *  1. **只有 STANDARD 级工具有资格被自动放行**。隐私/无障碍/Shizuku/设备管理员/Root 级一律照旧弹给用户——
     *     那几级出事就是不可逆的，再便宜的小模型也不配替用户签字。
     *     （[AndroidPermissionLevel.PRIVATE] 靠这个 `== STANDARD` 判断自动落在门外，这是它单立一档的另一半意义。）
     *  2. 判定链路上任何一环出岔子（没配模型/超时/异常/输出读不懂）都必须落回「问用户」，绝不 fail-open。
     *  3. **只有用户自己驱动的调用者（AI / 用户自己写的脚本）能走自动审批**。第三方插件、外部 MCP
     *     客户端的默认策略之所以是 ASK（见 [effectiveFor]），要的就是「不是用户写的代码，每次得他点头」；
     *     让一个小模型替他把这一下点了，等于把刚立的规矩自己拆了。
     *     顺带堵掉 ToolApprovalJudge 那份 3 分钟降噪缓存的越界复用：它的键是「工具名+参数」、**不含调用者**，
     *     插件只要凑出与刚才 AI 那次一模一样的调用，就能白蹭 AI 的放行结论（详见该文件 fingerprint）。
     */
    private suspend fun askGate(tool: Tool, args: JSONObject, caller: ToolCaller): ToolGate {
        val ctx = appContext
        val selfDriven = caller is ToolCaller.Ai || caller is ToolCaller.UserScript
        if (ctx != null && selfDriven && tool.permissionLevel == AndroidPermissionLevel.STANDARD &&
            com.arix.app.ToolApprovalJudge.autoApprove(ctx, tool.name, args, caller)
        ) return ToolGate.Pass

        return when (val o = askOutcome(
            key = tool.name, level = tool.permissionLevel,
            intent = describeIntent(tool.name, args), riskNote = riskNote(tool.permissionLevel),
            detail = rawArgs(args), caller = caller,
        )) {
            is AskOutcome.Decided ->
                if (o.decision == Decision.ALLOW_ONCE || o.decision == Decision.ALWAYS_ALLOW) ToolGate.Pass
                else ToolGate.Denied(DenyReason.USER_DENIED)
            AskOutcome.Timeout -> ToolGate.Denied(DenyReason.NO_RESPONSE)
            AskOutcome.Unavailable -> ToolGate.Denied(DenyReason.UNAVAILABLE)
        }
    }

    /**
     * 复用这套「挂起等用户点」的闸门，去确认一件**不是一次工具调用**的事
     * （目前用于 ui_control 的危险单步事前确认：工具本身已放行，但其中某一下点击可能是付款）。
     *
     * 为什么不让调用方自己 push [pending]：可见性判定、后台弹悬浮窗、STOP 取消时收框，
     * 这几件事的正确写法全在这条路径里，抄一份出去迟早会抄漏一条，然后就是「框收不掉」或「无限挂死」。
     *
     * [key] 既是弹窗上「工具：」那一行显示的名字，也是「始终允许/始终禁止」的记忆键——
     * 传一个**不与真实工具名相同**的键，免得用户在这儿点「始终允许」，顺手把那个工具本身的策略也改了。
     * 返回 null 表示压根没法问（prefs 未初始化），由调用方自己决定这时是放行还是拦下。
     */
    suspend fun confirmAction(
        key: String,
        level: AndroidPermissionLevel,
        intent: String,
        riskNote: String,
        detail: String = "",
        caller: ToolCaller = ToolCaller.Ai,
    ): Decision? = ask(key, level, intent, riskNote, detail, caller)

    /**
     * 弹框的三种结局。[ask] 把 Timeout 折叠成 DENY_ONCE（历史行为不变），
     * 只有工具闸门 [askGate] 要区分「他拒了」和「没人在场」——回给模型的话完全不同。
     */
    private sealed class AskOutcome {
        data class Decided(val decision: Decision) : AskOutcome()
        /** 没人看得见框，攒够 60s 判拒。 */
        data object Timeout : AskOutcome()
        /** 压根没法问（prefs 未初始化）。 */
        data object Unavailable : AskOutcome()
    }

    /** 弹一个框并挂起等用户点。返回 null = 没法问。 */
    private suspend fun ask(
        key: String, level: AndroidPermissionLevel, intent: String,
        riskNote: String, detail: String, caller: ToolCaller,
    ): Decision? = when (val o = askOutcome(key, level, intent, riskNote, detail, caller)) {
        is AskOutcome.Decided -> o.decision
        AskOutcome.Timeout -> Decision.DENY_ONCE   // 与改造前一致：超时=判拒
        AskOutcome.Unavailable -> null
    }

    private suspend fun askOutcome(
        key: String, level: AndroidPermissionLevel, intent: String,
        riskNote: String, detail: String, caller: ToolCaller,
    ): AskOutcome {
        if (prefs == null) return AskOutcome.Unavailable
        val d = CompletableDeferred<Decision>()
        pending.value = PendingRequest(key, level, intent, riskNote, detail, d, caller = caller)
        // App 在后台（多半是 AI 刚打开了别的 App，接着要操作它）→ 把框弹到悬浮窗里，盖在那个 App 之上。
        // 只在「主界面本来会渲染这个框」时这么做：唤醒助手/子agent/MCP 那些无 UI 场景照旧走超时判拒，行为不变。
        if (uiCount.get() > 0 && !com.arix.app.AppForeground.isForeground) {
            appContext?.let { com.arix.app.PermissionOverlayHost.showIfPossible(it) }
        }
        return try {
            awaitDecision(d)?.let { AskOutcome.Decided(it) } ?: AskOutcome.Timeout
        } finally {
            // 被 STOP 取消时也要把框收走：不收的话会留一个点了没任何反应的框杵在屏幕上。
            // 这也是「无限等」的逃生口——用户随时能按 STOP 把它连同这轮生成一起结束。
            if (pending.value?.deferred === d) pending.value = null
            com.arix.app.PermissionOverlayHost.hide()
        }
    }

    /**
     * 等用户决定。60s 预算**只在没人看得见框的时候消耗**：
     * - 看得见（前台，或框已弹在悬浮窗里）→ 无限等，必须他亲自选。定时替他判「拒绝」是最坏的做法：
     *   框自己没了，用户以为什么都没发生，AI 那头却收到「你不同意」，转头换招或放弃。
     * - 看不见 → 攒够 60s 收工（返回 null = **没人应答**），别把整轮生成永久挂死在一个谁也看不见的框上。
     *   注意 null 不等于「他拒绝了」：调用方要么折成判拒（[ask]，行为同改造前），要么原样区分开
     *   （[askGate] → [DenyReason.NO_RESPONSE]），好让模型知道「是没人在场」而不是「他说不行」。
     *
     * 分段等而不是一次性判定，是因为可见性在等待期间会变：App 切前台/切后台、悬浮窗弹出来，都算。
     */
    private suspend fun awaitDecision(d: CompletableDeferred<Decision>): Decision? {
        var blindMs = 0L
        while (true) {
            withTimeoutOrNull(STEP_MS) { d.await() }?.let { return it }
            if (canUserSee()) blindMs = 0L
            else {
                blindMs += STEP_MS
                if (blindMs >= BLIND_TIMEOUT_MS) return null   // null = 没人应答（调用方折成判拒）
            }
        }
    }

    /** 由 UI 调用以回应当前 pending 请求。 */
    fun resolve(decision: Decision) {
        val req = pending.value ?: return
        // 「始终」要记在**发起者**头上：给某个插件放行 shell，不等于给 AI 也放行。
        // 键带 caller 前缀（AI 无前缀，与老数据兼容）。
        val key = req.caller.prefix + req.toolName
        // 身份伪造得了的调用方（没设令牌的 MCP 客户端）不落盘：那条记忆日后会被「碰巧顶着同一个 id 的人」
        // 继承走。弹窗本来就不给它两个「始终」按钮，这里再兜一次底——降级成只管这一次。
        val remember = req.caller.canRememberAlways
        when {
            decision == Decision.ALWAYS_ALLOW && remember -> setOverride(key, ToolPermission.ALLOW)
            decision == Decision.ALWAYS_DENY && remember -> setOverride(key, ToolPermission.FORBID)
            else -> {}
        }
        req.deferred.complete(decision)
        pending.value = null
    }

    /**
     * 把一次工具调用翻成人话（如「点击界面上的「发送」」「联网搜索：…」「读取当前屏幕上的内容」）。
     * 公开薄封装，给实时胶囊 [com.arix.app.CapsuleBridge] 等「展示 AI 当前动作」的地方复用**同一套措辞**，
     * 免得各处各拼一版、和授权弹窗说的不是一回事。纯字符串、无副作用。
     */
    fun humanizeIntent(name: String, args: JSONObject): String = describeIntent(name, args)

    // 人话描述 AI 这一步要干什么——直接读它传的参数，而不是干巴巴的工具说明。
    private fun describeIntent(name: String, a: JSONObject): String {
        fun s(vararg keys: String): String? = keys.firstNotNullOfOrNull { a.optString(it, "").takeIf { v -> v.isNotBlank() } }
        return when (name) {
            "web_search", "bing", "baidu", "sogou", "google", "duckduckgo", "zhipu", "anysearch", "perplexica" ->
                "联网搜索：“${s("query", "q", "keyword") ?: "?"}”"
            "deep_search" -> "深度联网研究（可能抓取很多网页）：“${s("query", "q") ?: "?"}”"
            "open_page", "fetch" -> "打开网页读取内容：${s("url") ?: "?"}"
            "http_request" -> "发起网络请求：${s("method") ?: "GET"} ${s("url") ?: "?"}"
            "create_agent" -> "派 ${a.optJSONArray("tasks")?.length() ?: 0} 个子 agent 并行处理子任务"
            // file_read 只读合一(read/list/exists)、file_op 写操作合一(write/edit/delete/move/copy/mkdir/zip/unzip)：
            // 授权弹窗必须按 action 说实话，不能把「删除」说成「读取文件」骗着点同意。
            "file_read" -> when (s("action") ?: "read") {
                "list" -> "查看目录内容：${s("path") ?: "/"}"
                "exists" -> "检查文件是否存在：${s("path") ?: "?"}"
                else -> "读取文件：${s("path") ?: "?"}"
            }
            "file_op" -> when (s("action")) {
                "write" -> "写入/覆盖文件：${s("path") ?: "?"}（改动私有工作区里的文件）"
                "edit" -> "精确修改文件：${s("path") ?: "?"}"
                "delete", "remove", "rm" -> "删除：${s("path") ?: "?"}"
                "move", "rename", "mv" -> "移动/重命名：${s("from", "src", "source") ?: "?"} → ${s("to", "dst", "dest") ?: "?"}"
                "copy", "cp" -> "复制：${s("from", "src", "source") ?: "?"} → ${s("to", "dst", "dest") ?: "?"}"
                "mkdir", "make_directory", "makedir" -> "新建目录：${s("path") ?: "?"}"
                "zip" -> "压缩：${s("path") ?: "?"} → ${s("dest") ?: "?"}"
                "unzip" -> "解压：${s("path") ?: "?"} → ${s("dest") ?: "?"}"
                else -> "改动工作区文件：${s("path") ?: ""}"
            }
            "file_write" -> "写入/覆盖文件：${s("path") ?: "?"}（改动私有工作区里的文件）"
            "file_delete" -> "删除文件：${s("path") ?: "?"}"
            "file_move" -> "移动/重命名：${s("from", "src", "source") ?: "?"} → ${s("to", "dst", "dest") ?: "?"}"
            "file_copy" -> "复制文件：${s("from", "src", "source") ?: "?"} → ${s("to", "dst", "dest") ?: "?"}"
            "make_directory" -> "新建目录：${s("path") ?: "?"}"
            "file_list" -> "查看目录内容：${s("path") ?: "/"}"
            "file_exists" -> "检查文件是否存在：${s("path") ?: "?"}"
            "file_converter" -> "转换文件格式：${s("path", "from") ?: "?"}"
            "propose_setting_change" -> "提议修改设置：${s("key") ?: "?"} = ${s("value") ?: "?"}（还要你二次确认才生效）"
            "shell" -> "执行 Shell 命令：${s("command", "cmd") ?: "?"}"
            // linux_exec 从「跑一条命令」扩成了会话式（起后台会话/往里喂输入/看输出），按 action 分开说
            "linux_exec" -> when (s("action") ?: "run") {
                "start" -> "在 Linux 环境起一个后台会话：${s("command") ?: "?"}"
                "input" -> "往 Linux 会话「${s("session_id") ?: "?"}」里输入：${s("stdin")?.take(60) ?: ""}"
                "read" -> "查看 Linux 会话「${s("session_id") ?: "?"}」的新输出"
                "kill" -> "终止 Linux 会话「${s("session_id") ?: "?"}」"
                "list" -> "查看还开着哪些 Linux 会话"
                "env" -> "查看 Linux 环境信息（是哪个发行版、包管理器、图形化开没开）"
                // 图形化的开关会改到用户的设置、启停会起真进程，得让人看清楚它到底要干哪一件
                "gfx" -> when (s("command")?.lowercase()) {
                    "on" -> "打开图形界面支持（Linux 程序从此能开窗口）"
                    "off" -> "关掉图形界面支持，并停掉正在跑的显示服务"
                    "start" -> "启动图形显示服务"
                    "stop" -> "停止图形显示服务"
                    "view" -> "把 Linux 桌面显示到屏幕上"
                    else -> "查看图形界面的状态"
                }
                else -> "在 Linux 环境执行命令：${s("command") ?: "?"}"
            }
            "code_runner" -> "运行代码（${s("language", "lang") ?: "脚本"}）"
            "plugin_creator" -> "创建/修改插件：${s("name") ?: ""}"
            "ui_control" -> when (s("action")) {
                "dump" -> "读取当前屏幕上的内容"
                "click_text" -> "点击界面上的「${s("text") ?: "?"}」"
                "tap" -> "点击屏幕某个位置"
                "swipe" -> "在屏幕上滑动"
                "set_text" -> "往输入框写：${s("text") ?: ""}"
                "back" -> "按返回键"; "home" -> "回到主屏"; "recents" -> "打开最近任务"; "notifications" -> "下拉通知栏"
                else -> "操作手机界面"
            }
            "key_hook" -> "拦截/模拟按键"
            "make_phone_call" -> "拨打电话：${s("number", "phone") ?: "?"}"
            "send_sms" -> "发送短信给 ${s("number", "phone") ?: "?"}：${s("message", "content") ?: ""}"
            // app_launch 已从「只打开」合并成应用管理一条龙。必须按 action 说实话：
            // 把「卸载微信」说成「启动 App：微信」，那是骗着用户点的同意。
            "app_launch" -> when (s("action") ?: "launch") {
                "search" -> "查看设备上装了哪些应用" + (s("package_name")?.let { "（$it 的界面列表）" } ?: "")
                "open_activity" -> "打开应用内的界面：${s("package_name") ?: "?"}/${s("activity") ?: "?"}"
                "install" -> "安装应用安装包：${s("path") ?: "?"}"
                "uninstall" -> "卸载应用：${s("package_name", "name") ?: "?"}"
                "stop" -> "强行停止应用：${s("package_name", "name") ?: "?"}"
                else -> "启动 App：${s("package_name", "package", "app", "name") ?: "?"}"
            }
            // 任意 Intent/广播：这是提权面，弹窗必须把要发的东西原样摆出来给用户看
            "send_intent" -> buildString {
                append(if (s("type") == "broadcast") "发送系统广播" else "启动 Intent")
                s("action")?.let { append("：").append(it) }
                s("package_name")?.let { append("  → ").append(it) }
                s("component")?.let { append("/").append(it) }
                s("data")?.let { append("  data=").append(it.take(80)) }
                s("extras")?.let { append("  extras=").append(it.take(80)) }
            }
            "bluetooth" -> when (s("action") ?: "scan") {
                "connect" -> "连接蓝牙设备 ${s("address") ?: "?"}"
                "disconnect" -> "断开蓝牙设备 ${s("address") ?: "?"}"
                "services" -> "读取蓝牙设备 ${s("address") ?: "?"} 的服务列表"
                "read", "notifications" -> "读取蓝牙设备 ${s("address") ?: "?"} 的数据"
                "write" -> "向蓝牙设备 ${s("address") ?: "?"} 写入数据：${s("value")?.take(60) ?: ""}"
                "spp_connect" -> "用经典蓝牙(SPP)连接 ${s("address") ?: "?"}"
                "spp_send" -> "向蓝牙设备 ${s("address") ?: "?"} 发送：${s("value")?.take(60) ?: ""}"
                "spp_read" -> "读取蓝牙设备 ${s("address") ?: "?"} 发回来的数据"
                "paired" -> "查看已配对的蓝牙设备"
                else -> "扫描附近的蓝牙设备"
            }
            "clipboard" -> "读写剪贴板"
            // notification 已从「只发通知」合并成通知一条龙。这里必须按 action 分支说实话：
            // 说成「发送通知」而实际是替用户给联系人发微信，那是骗着他点的同意。
            "notification" -> when (s("action") ?: "list") {
                "reply" -> "替你回复通知${s("id")?.let { "#$it" } ?: ""}：${s("text") ?: ""}"
                "press" -> "按下通知${s("id")?.let { "#$it" } ?: ""}上的按钮「${s("button") ?: "?"}」"
                "open" -> "点开通知${s("id")?.let { "#$it" } ?: ""}（会跳到对应 App）"
                "dismiss" -> "清除通知${s("id")?.let { "#$it" } ?: ""}"
                "dismiss_all" -> "清空整个通知栏"
                "send" -> "发一条通知：${s("title") ?: ""}"
                else -> "读取手机最近的通知"
            }
            "brightness" -> "调节屏幕亮度"
            "volume" -> "调节音量"
            "set_alarm" -> "设置闹钟：${s("time") ?: ""}"
            "set_reminder" -> "设置提醒：${s("content", "text") ?: ""}"
            "take_photo" -> "调用相机拍照"
            "social_share" -> "分享内容到其他 App"
            "request_permission" -> "申请授权：${s("permission", "name") ?: "?"}"
            "ai_guard" -> "风控/安全相关操作"
            "memory", "rag" -> "读写长期记忆"
            "local_search" -> "在本机搜索：“${s("query", "q") ?: "?"}”" +
                (s("scope")?.takeIf { it.isNotBlank() && it != "all" }?.let { "（范围：$it）" } ?: "（对话/记忆/文件/日记等）")
            "worldbook" -> "读写世界书条目"
            "image_search" -> "搜索图片：“${s("query", "q") ?: "?"}”"
            "get_weather" -> "查询天气：${s("city", "location") ?: "当前位置"}"
            "calculator" -> "计算：${s("expression", "expr") ?: ""}"
            "tts" -> "语音播报文本"
            else -> "调用工具 $name" + rawArgs(a).let { if (it.isBlank()) "" else "（$it）" }
        }
    }

    private fun riskNote(level: AndroidPermissionLevel): String = when (level) {
        AndroidPermissionLevel.STANDARD -> "普通工具，风险低。"
        AndroidPermissionLevel.PRIVATE -> "碰的是你本人的数据（或直接对外发生的动作），不会自动放行、也不交给模型自动审批。"
        AndroidPermissionLevel.ACCESSIBILITY -> "需要无障碍：能读取/操作其它 App 的界面，谨慎。"
        AndroidPermissionLevel.DEBUGGER -> "需要 Shizuku/调试级权限：能执行系统命令，风险较高。"
        AndroidPermissionLevel.ADMIN -> "设备管理员级：可改设备安全策略，风险高。"
        AndroidPermissionLevel.ROOT -> "Root 级：几乎不受限，风险最高，务必确认。"
    }

    private fun rawArgs(a: JSONObject): String {
        val keys = a.keys().asSequence().toList()
        if (keys.isEmpty()) return ""
        return keys.joinToString("；") { k -> "$k=${a.opt(k)?.toString()?.take(80)}" }.take(300)
    }
}
