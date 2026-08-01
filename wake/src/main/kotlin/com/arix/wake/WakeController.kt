/*
 * Copyright 2025-2026 Arix.
 * SPDX-License-Identifier: AGPL-3.0-only
 *
 * Clean-room original implementation for the Arix wake module.
 * Not derived from any GPL/LGPL-licensed source.
 */

package com.arix.wake

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * [WakeEngine] 的实现：传感器门控状态机 + 窗口内检测工作器管理。见 DESIGN-WAKE.md §3。
 *
 * 「别一直听」：IDLE 不持麦；[submitTrigger] 收到门控事件后按 [WakeConfig.powerPolicy]
 * 决定开麦——窗口(ARMED，N 秒超时回 IDLE) 还是常听(ALWAYS_ON，仅充电)。
 *
 * clean-room：只依赖 [WakeWorker] 抽象，不接触任何 Operit 代码。工作器由 [workerFactory]
 * 注入——P2 传 [LegacyWakeWorker]，P3 换 clean-room pipeline，本类不动。
 *
 * 状态转移由公开入口 + 内部计时/命中回调驱动，均在实例锁下串行化。
 */
internal class WakeController(
    private val workerFactory: (WakeConfig) -> WakeWorker,
) : WakeEngine {

    private companion object {
        private const val TAG = "WakeController"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var config: WakeConfig = WakeConfig()

    @Volatile
    private var started = false

    @Volatile
    private var charging = false

    @Volatile
    private var _state: WakeState = WakeState.IDLE
    override val currentState: WakeState get() = _state

    override var onStateChanged: ((WakeState) -> Unit)? = null
    override var onWake: ((WakeDetection) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    private var worker: WakeWorker? = null
    private var workerJob: Job? = null
    private var windowJob: Job? = null

    /** 每个 worker 实例至多消费一次唤醒（legacy listener 命中后不自停、会连发回调）。 */
    @Volatile
    private var wakeConsumed = false

    @Synchronized
    override fun start(config: WakeConfig) {
        this.config = config
        if (started) return
        started = true
        Log.d(TAG, "start policy=${config.powerPolicy} detector=${config.detectorMode}")
        setState(WakeState.IDLE)
        // 持续常听：开启即开麦（必须在前台调用 start，麦克风才能在前台启动、之后后台不再重启不被静音）。
        if (config.powerPolicy == WakePowerPolicy.ALWAYS_ON) enterAlwaysOn()
        // 若启动时已在充电，等 WakeService 发 PowerConnected 再决定是否常听；此处不主动开麦。
    }

    @Synchronized
    override fun submitTrigger(trigger: WakeTrigger) {
        if (!started || config.powerPolicy == WakePowerPolicy.OFF) return
        Log.d(TAG, "trigger=${trigger.label} state=$_state policy=${config.powerPolicy}")
        WakeLog.d("触发: ${trigger.label} (当前 $_state)")
        when (trigger) {
            is WakeTrigger.ScreenOn ->
                if (config.enableScreenOnGate && autoGateAllowed()) openWindow()

            is WakeTrigger.Motion ->
                if (config.enableMotionGate && autoGateAllowed()) openWindow()

            is WakeTrigger.Explicit ->
                openWindow() // 显式呼出永远开窗（OFF 已在上面挡掉）

            is WakeTrigger.PowerConnected -> {
                charging = true
                if (config.powerPolicy == WakePowerPolicy.ALWAYS_ON_WHEN_CHARGING) enterAlwaysOn()
            }

            is WakeTrigger.PowerDisconnected -> {
                charging = false
                // 仅「插电常听」策略在拔电时停；「持续常听」策略不受电源影响。
                if (_state == WakeState.ALWAYS_ON && config.powerPolicy == WakePowerPolicy.ALWAYS_ON_WHEN_CHARGING) stopWorkerAndIdle()
            }
        }
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        started = false
        Log.d(TAG, "stop")
        cancelWindow()
        stopWorker()
        setState(WakeState.IDLE)
    }

    /** 自动门控（亮屏/抬腕）是否被当前策略允许。仅显式模式禁自动开窗；常听中忽略新窗口。 */
    private fun autoGateAllowed(): Boolean =
        config.powerPolicy != WakePowerPolicy.EXPLICIT_ONLY && _state != WakeState.ALWAYS_ON

    // --- 状态转移（均在 @Synchronized 入口内调用） ---

    private fun openWindow() {
        if (_state == WakeState.ALWAYS_ON) return
        if (_state != WakeState.ARMED) {
            startWorker()
            setState(WakeState.ARMED)
        }
        // 每次触发重置窗口计时（续窗）
        cancelWindow()
        windowJob = scope.launch {
            try {
                delay(config.windowMs)
                onWindowTimeout()
            } catch (_: CancellationException) {
            }
        }
    }

    private fun enterAlwaysOn() {
        if (_state == WakeState.ALWAYS_ON) return
        cancelWindow()
        startWorker() // 幂等：ARMED 时复用已运行的 worker
        setState(WakeState.ALWAYS_ON)
    }

    @Synchronized
    private fun onWindowTimeout() {
        // 窗口超时且仍在 ARMED（未命中）→ 停麦回 IDLE
        if (_state == WakeState.ARMED) {
            Log.d(TAG, "window timeout → IDLE")
            stopWorker()
            setState(WakeState.IDLE)
        }
    }

    private fun stopWorkerAndIdle() {
        cancelWindow()
        stopWorker()
        setState(WakeState.IDLE)
    }

    /**
     * 命中回调（来自 worker 协程）：去重后切到 controller 自己的协程处理，
     * 避免自取消当前 worker，也避免 legacy listener 连发回调导致重复处理。
     */
    @Synchronized
    private fun handleWake(det: WakeDetection) {
        if (wakeConsumed) return
        wakeConsumed = true
        scope.launch { finishWake(det) }
    }

    @Synchronized
    private fun finishWake(det: WakeDetection) {
        if (_state == WakeState.TRIGGERED) return // 去重：命中后多次回调只处理一次
        Log.d(TAG, "WAKE detected id=${det.detectorId} score=${det.score}")
        WakeLog.d("★ 唤醒命中！score=%.3f".format(det.score))
        setState(WakeState.TRIGGERED)
        cancelWindow()
        stopWorker()
        try {
            onWake?.invoke(det)
        } catch (e: Exception) {
            Log.e(TAG, "onWake callback error: ${e.message}")
        }
        // 命中后重新武装：持续常听 / 充电常听则回 ALWAYS_ON，否则回 IDLE 等下次门控
        val rearmAlwaysOn = config.powerPolicy == WakePowerPolicy.ALWAYS_ON ||
            (charging && config.powerPolicy == WakePowerPolicy.ALWAYS_ON_WHEN_CHARGING)
        if (started && rearmAlwaysOn) {
            startWorker()
            setState(WakeState.ALWAYS_ON)
        } else {
            setState(WakeState.IDLE)
        }
    }

    // --- worker 生命周期 ---

    private fun startWorker() {
        if (workerJob?.isActive == true) return // 幂等
        wakeConsumed = false
        val w = workerFactory(config)
        worker = w
        workerJob = scope.launch {
            try {
                w.run(
                    onWake = { det -> handleWake(det) },
                    onError = { msg -> onError?.invoke(msg) },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                onError?.invoke(e.message ?: "wake worker error")
            }
        }
    }

    private fun stopWorker() {
        try {
            worker?.stop()
        } catch (_: Exception) {
        }
        try {
            workerJob?.cancel()
        } catch (_: Exception) {
        }
        worker = null
        workerJob = null
    }

    private fun cancelWindow() {
        try {
            windowJob?.cancel()
        } catch (_: Exception) {
        }
        windowJob = null
    }

    private fun setState(next: WakeState) {
        if (_state == next) return
        _state = next
        WakeLog.d("状态 → $next")
        try {
            onStateChanged?.invoke(next)
        } catch (e: Exception) {
            Log.e(TAG, "onStateChanged callback error: ${e.message}")
        }
    }
}
