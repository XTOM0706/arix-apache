package com.arix.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * 划词处理入口：任何 App 里长按选中一段文字，系统文本菜单里那一项「Arix」。
 *
 * 为什么单开一个透明 Activity、不直接把 PROCESS_TEXT 挂到 MainActivity 上：
 *  · 系统文本菜单是用 `startActivityForResult` 拉起处理方的，且**期望它落在调用方自己的任务栈里**
 *    （处理完可以把替换后的文字回传）。MainActivity 是 `singleTask`——它永远要去自己那个任务栈，
 *    这条链路上会立刻返回 RESULT_CANCELED，行为随 ROM 漂移。
 *  · 透明代理走默认 launchMode，在调用方任务里瞬间起停，再用 NEW_TASK 把 Arix 正常拉起来，
 *    两边各自待在该待的地方。（同款透明 Activity 范式见 AttachmentPickerActivity / ScreenProjectionActivity。）
 *
 * 我们是**只读**处理方：不回写替换文本——用户是要拿这段话去问 AI，不是要 Arix 改他的原文。
 * 所以永远 `RESULT_CANCELED`，宿主 App 里选中的字一个都不动。
 */
class ProcessTextActivity : ComponentActivity() {

    private companion object {
        /** 私有 action：和 widget/磁贴各自的 action 区分开，别让 PendingIntent 的身份互相顶掉。 */
        const val ACTION_OPEN = "com.arix.app.action.PROCESS_TEXT_OPEN"
    }

    @Suppress("DEPRECATION")   // overridePendingTransition：Android 14 才有替代 API，我们 minSdk 26
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 选中的文字进收件箱（外部内容，围栏在 SharedPayload.forModel 里加，不在这儿改内容）
        val hit = runCatching { ShareIntake.handle(this, intent) }.getOrDefault(false)
        if (hit) {
            runCatching {
                startActivity(
                    Intent(this, MainActivity::class.java).apply {
                        action = ACTION_OPEN
                        // 内容已经进收件箱了，这条只告诉 MainActivity「把人带到聊天页」。
                        putExtra(ShareIntake.EXTRA_ROUTE_CHAT, true)
                        // singleTask：App 已在跑就走 onNewIntent，没跑就冷启动。两条路都由 MainActivity 接。
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                )
            }
        }
        setResult(RESULT_CANCELED)
        finish()
        // 代理本身不该被看见：去掉它自己的进出场动画，视觉上就是「点了菜单，Arix 起来了」。
        overridePendingTransition(0, 0)
    }
}
