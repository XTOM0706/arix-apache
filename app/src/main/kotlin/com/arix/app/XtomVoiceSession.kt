package com.arix.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.view.View
import androidx.compose.ui.platform.ComposeView

/**
 * 数字助手会话：承载 Siri 式浮层 UI（复用 [WakeAssistantScreen]）。会话窗口由系统托管，
 * 因此无需悬浮窗权限、不受后台 Activity 启动限制——助手手势或唤醒命中即弹出。
 */
class XtomVoiceSession(context: Context) : VoiceInteractionSession(context) {

    private var owner: SimpleComposeOwner? = null

    override fun onCreateContentView(): View {
        owner?.onDestroy() // 若重建 content view，先销毁旧宿主
        val o = SimpleComposeOwner()
        owner = o
        o.onCreate()
        return ComposeView(context).apply {
            o.attach(this)
            setContent {
                com.arix.app.theme.XtomTheme {
                    WakeAssistantScreen(
                        onClose = { hide() },
                        onOpenChat = {
                            try {
                                context.startActivity(
                                    Intent(context, MainActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                                )
                            } catch (_: Exception) {}
                            hide()
                        },
                    )
                }
            }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        // 会话窗口由系统托管，默认 softInputMode 未指定（多为 adjustPan：整窗上移，顶部被切）。
        // 显式要 ADJUST_RESIZE，让 ime inset 正常下发、浮层内容根的 imePadding() 生效，
        // 点「打字…」弹键盘时输入胶囊跟着上移而不是被盖住。窗口此刻已建好，取不到就算了不硬来。
        try {
            window?.window?.setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
            )
        } catch (_: Exception) {}
        owner?.onResume()
        com.arix.wake.WakeLog.d("数字助手会话已弹出")
    }

    override fun onLockscreenShown() {
        // VoiceInteractionSession 默认实现在锁屏出现时直接 hide() 会话——这正是「锁屏能识别到但助手不弹」的根因：
        // 走数字助手路径时，锁屏一出现系统就把会话自动隐藏了。开了「锁屏唤起」就保持显示（不调 super），
        // 像小爱/Assistant 的锁屏语音；关了则沿用默认隐藏（等解锁后再交互）。
        if (WakeService.lockScreenWakeEnabled(context)) {
            com.arix.wake.WakeLog.d("锁屏显示：保持数字助手会话（锁屏唤起已开）")
        } else {
            super.onLockscreenShown()
        }
    }

    override fun onHide() {
        owner?.onPause() // 暂停而非销毁：会话可反复 show/hide，销毁会导致下次 show 崩溃
        super.onHide()
        // 会话关闭 → 让唤醒服务重新武装（可再次唤醒）
        WakeService.notifyOverlayClosed()
    }

    override fun onDestroy() {
        owner?.onDestroy()
        owner = null
        super.onDestroy()
    }
}
