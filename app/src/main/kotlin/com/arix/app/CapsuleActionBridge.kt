package com.arix.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 实时胶囊（超级岛 / 流体云）上「停止 / 输入」两个动作与聊天页之间的进程级桥。
 *
 * 岛上的动作按钮走 [CapsuleActionReceiver]（广播），但真正的「取消生成 / 发消息」逻辑活在
 * [ChatScreen] 的 composition 作用域里（sendJob / performSend / input 都是页面局部状态）。
 * 这里用进程级流把两者解耦：
 *  - [stop]：无重放的 SharedFlow。生成中 App 必然在跑、ChatPage 必然已组合，收集器接住即 `sendJob.cancel()`。
 *  - [pendingInput]：**保留最后值**的 StateFlow。岛上回复可能发生在 App 完全后台/冷启动，
 *    值留着直到已组合的 ChatPage 消费；[CapsuleActionReceiver] 同时把 App 拉前台，让收集器跑起来发出去。
 */
object CapsuleActionBridge {
    const val ACTION_STOP = "com.arix.capsule.STOP"
    const val ACTION_REPLY = "com.arix.capsule.REPLY"
    /** RemoteInput 结果键（岛内直接输入的文本）。 */
    const val KEY_REPLY_TEXT = "xtom_capsule_reply"

    private val _stop = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val stop: SharedFlow<Long> = _stop.asSharedFlow()
    /** 岛上「停止」→ 请求取消当前这轮生成。 */
    fun requestStop() { _stop.tryEmit(android.os.SystemClock.elapsedRealtime()) }

    private val _pendingInput = MutableStateFlow<String?>(null)
    val pendingInput: StateFlow<String?> = _pendingInput.asStateFlow()
    /** 岛上「输入」提交的文本，等 ChatPage 接走。 */
    fun submitInput(text: String) { _pendingInput.value = text }
    /** ChatPage 发出去后清空，避免重复发送。 */
    fun consumeInput() { _pendingInput.value = null }
}

/**
 * 接收实时胶囊动作按钮的广播：停止生成 / 岛内直接回复。
 * 在 AndroidManifest 注册（exported=false，仅本应用 PendingIntent 可触发）。
 */
class CapsuleActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CapsuleActionBridge.ACTION_STOP -> {
                // 只发停止信号：ChatPage 收集器取消 sendJob → 生成 finally 走 onGenerationEnd → 岛统一收尾，
                // 状态全经 CapsuleBridge 一条路，别在这儿直接 show 免得和它的节流/收岛状态打架。
                CapsuleActionBridge.requestStop()
            }
            CapsuleActionBridge.ACTION_REPLY -> {
                val text = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(CapsuleActionBridge.KEY_REPLY_TEXT)?.toString()?.trim()
                if (!text.isNullOrBlank()) {
                    CapsuleActionBridge.submitInput(text)
                    // 把 App 拉到前台，让已组合的 ChatPage 收集器接住 pendingInput 发出去（冷启动也靠它兜住）。
                    runCatching {
                        val open = Intent(context, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        }
                        context.startActivity(open)
                    }
                }
            }
        }
    }
}
