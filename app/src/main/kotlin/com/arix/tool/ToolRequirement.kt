package com.arix.tool

import android.content.Context

/**
 * 工具的**运行前提**：这台设备现在具不具备跑起它的条件。
 *
 * ⚠ 和 [AndroidPermissionLevel] 完全是两码事，别混：
 *   - `permissionLevel` 回答「这事有多危险、要不要问用户」——`file_op` 标 ACCESSIBILITY 只因为它能改文件，
 *     跟无障碍服务半点关系没有；`linux_exec` 标 DEBUGGER 也只是风险分级。
 *   - `requires`（本枚举）回答「跑不跑得起来」——不具备就是**必然失败**，不是「危险」。
 * 按 permissionLevel 去裁工具表会把 file_op / local_search / browser 这些纯沙盒工具误杀在 L0 上。
 *
 * 只给「不具备 = 100% 跑不了」的工具标。有降级路径的一律不标（screen_ocr 没 root 也能走系统录屏授权、
 * shell 没特权也能跑沙盒级命令），宁可多发一份 schema，也不要跟 AI 说「你没这能力」——那正是要根治的毛病。
 */
enum class ToolRequirement(
    /** 给模型看的能力名。 */
    val label: String,
    /** 一句话说明怎么解锁（拼进系统提示，让模型知道能申请，而不是以为自己天生没这本事）。 */
    val howTo: String,
) {
    /** 界面自动化：无障碍服务**或** Shizuku 任一即可（ui_control 的操作类动作走 Shizuku 也能落地）。 */
    UI_AUTOMATION("界面自动化（无障碍服务或 Shizuku）", "request_permission(permission=\"accessibility\") 或 request_permission(permission=\"shizuku\") 申请开启"),
    NOTIFICATION_LISTENER("通知使用权", "request_permission(permission=\"notification_listener\") 申请开启"),
    USAGE_STATS("使用情况访问权限", "request_permission(permission=\"usage_stats\") 申请开启"),
    TERMINAL_APP("Arix 终端 App", "让用户到「设置 → 终端」安装它"),
}

/**
 * 设备能力探测（带 TTL 缓存）。
 *
 * 为什么要缓存：[ToolManager.getToolsJson] 在**每一轮**工具循环里都会调一次，而探测要查 AppOps /
 * 已启用监听器列表 / 装没装某个包，都是 binder 调用。一轮工具循环 N 次 × 每次 4 个探测 = 白烧。
 * TTL 取 30s：用户去设置页开完权限回来，最多 30 秒就能被认出来（回到 App 时还会 [invalidate] 一次）。
 *
 * 探测异常一律**当作具备**（fail-open）：宁可发一份用不了的 schema 让它撞一次错，
 * 也不要凭一次读失败就把能力从 AI 眼前抹掉——后者它连"我可以申请"都不知道。
 */
object CapabilityProbe {
    private const val TTL_MS = 30_000L

    @Volatile private var appCtx: Context? = null
    @Volatile private var cachedAt = 0L
    @Volatile private var cachedMissing: Set<ToolRequirement> = emptySet()

    fun init(context: Context) { appCtx = context.applicationContext }

    /** 忘掉缓存：用户刚从设置页回来、刚装完终端 App 时调。 */
    fun invalidate() { cachedAt = 0L }

    /** 当前**不具备**的能力。取不到 context 时返回空集（= 什么都不裁）。 */
    fun missing(): Set<ToolRequirement> {
        val now = System.currentTimeMillis()
        val at = cachedAt
        if (at != 0L && now - at < TTL_MS) return cachedMissing
        val c = appCtx ?: return emptySet()
        val miss = ToolRequirement.values().filterNot { present(c, it) }.toSet()
        cachedMissing = miss
        cachedAt = now
        return miss
    }

    /** 这个前提现在满足吗（null = 无前提 = 永远满足）。 */
    fun has(req: ToolRequirement?): Boolean = req == null || req !in missing()

    private fun present(c: Context, r: ToolRequirement): Boolean = try {
        when (r) {
            ToolRequirement.UI_AUTOMATION ->
                com.arix.app.XtomAccessibilityService.instance != null || ShizukuAccess.granted()
            ToolRequirement.NOTIFICATION_LISTENER -> com.arix.app.NotificationAwarenessPrefs.hasAccess(c)
            ToolRequirement.USAGE_STATS -> UsageStatsTool.hasAccess(c)
            ToolRequirement.TERMINAL_APP -> TerminalClient.isInstalled(c)
        }
    } catch (_: Exception) { true }   // fail-open，见上方说明
}
