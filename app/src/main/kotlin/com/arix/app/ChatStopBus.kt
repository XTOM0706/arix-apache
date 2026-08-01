package com.arix.app

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 停止生成信号总线 —— 取代原胶囊（CapsuleActionBridge）的 stop 通道。
 *
 * 悬浮球 / 媒体键（线控）等入口触发停止时，走这里发一条无重放信号；
 * ChatPage 收集器接住即取消当前这轮生成。生成中 App 必然在跑、ChatPage 必然已组合，
 * 所以无重放足够（漏掉的信号本来就该漏：那次没人听得见 = 没有生成在跑）。
 */
object ChatStopBus {
    private val _stop = MutableSharedFlow<Long>(extraBufferCapacity = 8)
    val stop: SharedFlow<Long> = _stop.asSharedFlow()

    /** 触发一次停止。 */
    fun requestStop() { _stop.tryEmit(android.os.SystemClock.elapsedRealtime()) }
}
