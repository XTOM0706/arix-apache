package com.arix.app

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.service.voice.VoiceInteractionService

/**
 * Arix 数字助手根服务。设为默认助手后系统常驻此服务（比普通前台服务更稳、不易被 OEM 杀，
 * 且作为助手可获得后台麦克风权限）。助手手势/唤醒命中 → [launchAssist] 拉起系统托管的会话浮层
 * （[XtomVoiceSession]），无需悬浮窗权限、不受后台 Activity 启动限制。
 *
 * 说明：真正「麦克风不显示占用 + DSP 省电常听」需要 AlwaysOnHotwordDetector(DSP，多数设备只认
 * OEM 预置词) 或 HotwordDetectionService(隔离沙箱，API 33+)。这里先落地助手核心+会话，热词沙箱
 * 作为后续层；当前常听仍由 WakeService 软件管线负责，但作为助手其后台持麦更可靠。
 */
class XtomVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        instance = this
        com.arix.wake.WakeLog.d("数字助手就绪（VoiceInteractionService active）")
    }

    override fun onShutdown() {
        instance = null
        com.arix.wake.WakeLog.d("数字助手关闭")
        super.onShutdown()
    }

    /** 拉起会话浮层（系统托管）。须主线程调用。 */
    fun launchAssist() {
        try {
            showSession(Bundle(), 0)
        } catch (e: Exception) {
            com.arix.wake.WakeLog.d("showSession 失败: ${e.message}")
        }
    }

    companion object {
        @Volatile
        var instance: XtomVoiceInteractionService? = null
            private set

        /** Arix 是否是当前生效的数字助手。 */
        fun isActiveAssistant(context: Context): Boolean = try {
            isActiveService(context, ComponentName(context, XtomVoiceInteractionService::class.java))
        } catch (_: Exception) {
            false
        }
    }
}
