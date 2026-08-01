package com.arix.app

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * 「设为默认数字助理」——走 [RoleManager]，而不是盲跳系统设置页。
 *
 * 为什么重做：原来的做法是依次试 `android.settings.VOICE_INPUT_SETTINGS` →
 * `ACTION_MANAGE_DEFAULT_APPS_SETTINGS` → `ACTION_SETTINGS`，把用户扔进设置页让他自己找。
 * 跳过去了不代表能设成，用户看到的就是「点了没反应」。RoleManager 能**直接弹系统授权框**，
 * 还能提前告诉我们这台机器到底支不支持。
 *
 * 关键的是 [available]：有些 ROM（精简包/无 GMS/裁过 role 的）**整个助理 role 就是空的**，
 * 系统列表里一个候选都没有（连 Google 自己的都没有）。这种机器上不管 App 清单写得多对都设不上。
 * 与其让用户对着没反应的按钮反复点，不如如实说「这台设备不支持」。
 *
 * API 均由 android-36 的 android.jar 实测确认存在（ROLE_ASSISTANT / createRequestRoleIntent /
 * isRoleAvailable / isRoleHeld），非凭印象。
 */
object AssistantRole {

    /** Role API 从 Android 10(Q) 起才有。 */
    private fun rm(context: Context): RoleManager? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) null
        else context.getSystemService(Context.ROLE_SERVICE) as? RoleManager

    /**
     * 这台设备支不支持「数字助理」这个角色。
     * false = 系统里压根没有助理 role（不是我们的问题，任何 App 都设不上）。
     */
    fun available(context: Context): Boolean =
        runCatching { rm(context)?.isRoleAvailable(RoleManager.ROLE_ASSISTANT) == true }.getOrDefault(false)

    /** Arix 当前是不是默认助理。 */
    fun held(context: Context): Boolean =
        runCatching { rm(context)?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true }.getOrDefault(false)

    /**
     * 系统授权框的 Intent。返回 null = 这台机器给不了（不支持 role / 版本太低），调用方该退回设置页。
     * 注意即便返回了 Intent，也不保证系统一定接受——有的 ROM 把 role 声明成不可请求，
     * 那 startActivityForResult 会直接返回 RESULT_CANCELED，调用方要按「没设成」处理。
     */
    fun requestIntent(context: Context): Intent? =
        runCatching {
            val m = rm(context) ?: return null
            if (!m.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) return null
            m.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
        }.getOrNull()

    /** 退路：老系统/不支持 role 时，把用户送到能手动选的那个设置页。逐个试，因为各 ROM 支持的 action 不同。 */
    fun fallbackSettings(context: Context): Boolean {
        val tried = listOf(
            "android.settings.VOICE_INPUT_SETTINGS",
            android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            android.provider.Settings.ACTION_SETTINGS,
        )
        for (action in tried) {
            val ok = runCatching {
                context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (ok) return true
        }
        return false
    }

    /** 给界面用的一句人话诊断。 */
    fun diagnose(context: Context): String = when {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> "系统版本较低，只能到设置里手动选"
        !available(context) -> "这台设备不支持「数字助理」——系统里没有这个角色，任何应用都设不上（不是 Arix 的问题）。唤醒仍可用，走悬浮窗那条路即可。"
        held(context) -> "Arix 已是默认数字助理"
        else -> "点下面的按钮，系统会弹框让你确认"
    }
}
