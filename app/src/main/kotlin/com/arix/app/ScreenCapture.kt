package com.arix.app

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * 屏幕抓取编排器：把「申请投屏授权 → 起前台服务抓一帧 → 拿到 JPEG 路径」串成一个 suspend 调用。
 *
 * 协作三方（都不改共享文件）：
 *  - [ScreenProjectionActivity]：透明代理 Activity 弹系统授权框，回调 [onConsentResult]。
 *  - [ScreenCaptureService]：前台服务持 MediaProjection 抓一帧，回调 [onFrameResult]。
 *  - 本对象：持有当前挂起的协程续体，串起上面两步并统一超时/清理。
 *
 * 一次只跑一个抓取（用 @Volatile 续体做互斥）；已在进行时再调直接失败，避免授权/服务竞态。
 */
object ScreenCapture {

    @Volatile private var cont: CancellableContinuation<Result<String>>? = null
    @Volatile private var appContext: Context? = null

    /**
     * 抓取当前屏幕并存成 JPEG，返回其绝对路径（存于 cacheDir/screen_ocr）。
     * 失败（用户拒绝授权/抓帧超时/异常）返回 [Result.failure]。调用方用完应自行删除该临时文件。
     */
    suspend fun captureToFile(context: Context): Result<String> = withContext(Dispatchers.Main) {
        if (cont != null) return@withContext Result.failure(IllegalStateException("已有屏幕抓取在进行中，请稍后再试"))
        appContext = context.applicationContext
        try {
            withTimeout(90_000) {
                suspendCancellableCoroutine { c ->
                    cont = c
                    c.invokeOnCancellation { cont = null }
                    // 先申请（或复用系统记住的）投屏授权
                    ScreenProjectionActivity.request(context.applicationContext)
                }
            }
        } catch (e: TimeoutCancellationException) {
            cont = null
            Result.failure(IllegalStateException("屏幕抓取超时（授权对话框未完成或未拿到画面）"))
        }
    }

    /** [ScreenProjectionActivity] 回调：拿到授权结果。granted 则起前台服务抓帧，否则直接失败收场。 */
    internal fun onConsentResult(resultCode: Int, data: Intent?) {
        val ctx = appContext
        if (data == null || ctx == null) {
            finish(Result.failure(IllegalStateException("已取消屏幕录制授权")))
            return
        }
        try {
            ScreenCaptureService.start(ctx, resultCode, data)
        } catch (e: Exception) {
            finish(Result.failure(IllegalStateException("启动屏幕抓取服务失败：${e.message}")))
        }
    }

    /** [ScreenCaptureService] 回调：抓帧完成（成功=JPEG 路径，失败=异常）。 */
    internal fun onFrameResult(result: Result<String>) {
        finish(result)
    }

    private fun finish(result: Result<String>) {
        val c = cont ?: return
        cont = null
        if (c.isActive) c.resume(result)
    }
}
