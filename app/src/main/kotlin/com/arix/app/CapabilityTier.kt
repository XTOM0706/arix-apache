package com.arix.app

import android.content.Context
import android.content.pm.ApplicationInfo

/**
 * 当前设备把 Arix 放在**哪一层能力**上（L0–L4）。
 *
 * 这不是新功能，是把已经写在战略里的分级**变成代码里的一个可回答的问题**：
 * 「我现在到底能干到哪一层」。以前这个答案散在各处——权限页显示一套、工具失败时说一套、
 * 提示词里又是一套，三处不一致的结果就是用户觉得"时灵时不灵"，而 AI 自己也说不清为什么这次不行。
 *
 * 判定一律**只读、不触发授权弹框**：这个函数会在权限页每次 ON_RESUME、以及拼提示词时被调到，
 * 那里弹一次 root 授权框是灾难。所以 root 只看 su 二进制在不在 + 已缓存的授权结果。
 */
enum class CapabilityTier(val level: Int, val label: String) {
    L0(0, "裸装"),
    L1(1, "已授权"),
    L2(2, "Shizuku"),
    L3(3, "Root"),
    L4(4, "系统应用");

    /** 给用户看的一句话：这一层解锁了什么。 */
    val unlocked: String get() = when (this) {
        L0 -> "聊天、记忆、角色卡、搜索、沙盒文件、终端、唤醒、MCP 都能用"
        L1 -> "加上读通知、看屏幕并代你操作、健康与日程信号"
        L2 -> "加上免 root 的系统级命令（pm/am/settings/input）"
        L3 -> "完整 shell，能做的没有边界"
        L4 -> "签名级权限、虚拟屏、后台自动化不打扰前台、不被杀"
    }

    /** 再往上一层要做什么（已在顶层返回 null）。 */
    val nextStep: String? get() = when (this) {
        L0 -> "开无障碍或通知使用权，就能读通知、代你操作界面"
        L1 -> "装 Shizuku（免 root，连一次电脑或用无线调试）可拿 ADB 级权限"
        L2 -> "已 root 的机器给 Arix 授权即可解锁完整 shell"
        L3 -> "刷 OnyxUI 或出厂内置，可解锁虚拟屏与后台自动化"
        L4 -> null
    }

    companion object {
        fun detect(context: Context): CapabilityTier = try {
            when {
                isSystemApp(context) -> L4
                // 只看 su 在不在 + 之前探过并且成功：绝不在这里真的去跑 su（会弹授权框）
                com.arix.tool.RootAccess.suBinaryExists() && com.arix.tool.RootAccess.grantedCached() -> L3
                com.arix.tool.ShizukuAccess.granted() -> L2
                hasAnyL1(context) -> L1
                else -> L0
            }
        } catch (_: Exception) { L0 }

        /** 系统应用（刷进 OnyxUI 或出厂内置）：拿得到签名级权限、住在系统里。 */
        private fun isSystemApp(c: Context): Boolean = try {
            (c.applicationInfo.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
        } catch (_: Exception) { false }

        /** L1 = 用户至少给了一项"观察/操作现实世界"的特殊权限。 */
        private fun hasAnyL1(c: Context): Boolean =
            XtomAccessibilityService.instance != null ||
                NotificationAwarenessPrefs.hasAccess(c) ||
                com.arix.tool.UsageStatsTool.hasAccess(c)
    }
}
