package com.arix.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream

/**
 * 前台服务：持有 [MediaProjection]，用 [ImageReader]+[VirtualDisplay] 抓「一帧」当前屏幕 → 存成 JPEG，
 * 再把文件路径交回 [ScreenCapture]，随后自我销毁。
 *
 * 为什么要前台服务：Android 10+（尤其 14+）要求先有一个 foregroundServiceType=mediaProjection 的前台服务
 * 在跑，才能调用 getMediaProjection()。本 App targetSdk=28 时该限制多被放宽，但很多国产 ROM 仍按平台版本强校验，
 * 故照规矩起一个前台服务最稳妥。授权数据 (resultCode + data Intent) 由 Intent extra 传入。
 *
 * 只抓一帧、抓完立刻释放 projection/virtualDisplay/imageReader 并 stopSelf——不常驻、不持续录屏。
 */
class ScreenCaptureService : Service() {

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    @Volatile private var delivered = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 1) 立刻进入前台（mediaProjection 类型），否则 getMediaProjection 可能被系统拒绝
        try {
            startForegroundCompat()
        } catch (e: Exception) {
            fail("无法进入前台服务：${e.message}")
            return START_NOT_STICKY
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)

        if (data == null) {
            fail("缺少投屏授权数据")
            return START_NOT_STICKY
        }

        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val mp = mpm.getMediaProjection(resultCode, data) ?: run {
                fail("获取屏幕投影失败（授权可能已失效）")
                return START_NOT_STICKY
            }
            projection = mp
            // Android 14+ 强制要求注册 Callback，否则 createVirtualDisplay 抛异常
            val th = HandlerThread("screen-capture").also { it.start() }
            thread = th
            val hd = Handler(th.looper)
            handler = hd
            mp.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { /* 系统/用户停止投影：忽略，抓帧流程自管生命周期 */ }
            }, hd)
            captureOneFrame(mp, hd)
        } catch (e: Exception) {
            fail("屏幕抓取初始化失败：${e.message}")
        }
        return START_NOT_STICKY
    }

    private fun captureOneFrame(mp: MediaProjection, hd: Handler) {
        val metrics = resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi
        if (width <= 0 || height <= 0) { fail("无法获取屏幕尺寸"); return }

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        imageReader = reader

        virtualDisplay = mp.createVirtualDisplay(
            "xtom-screen-ocr",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, hd
        )

        // 安全兜底：若 3 秒内没等到帧（部分 ROM 首帧偏慢/被拦），按失败收场，绝不无限挂起。
        hd.postDelayed({ if (!delivered) fail("屏幕抓取超时（未拿到画面帧）") }, 3000)

        reader.setOnImageAvailableListener({ r ->
            if (delivered) return@setOnImageAvailableListener
            val image = try { r.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bmp = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height, Bitmap.Config.ARGB_8888
                )
                bmp.copyPixelsFromBuffer(buffer)
                // 去掉行填充：裁回真实宽度
                val cropped = if (rowPadding == 0) bmp
                    else Bitmap.createBitmap(bmp, 0, 0, width, height).also { bmp.recycle() }
                val path = saveJpeg(cropped)
                cropped.recycle()
                succeed(path)
            } catch (e: Exception) {
                fail("屏幕画面转换失败：${e.message}")
            } finally {
                try { image.close() } catch (_: Exception) {}
            }
        }, hd)
    }

    private fun saveJpeg(bmp: Bitmap): String {
        val dir = File(cacheDir, "screen_ocr").apply { if (!exists()) mkdirs() }
        val f = File(dir, "screen_${System.currentTimeMillis()}.jpg")
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        return f.absolutePath
    }

    private fun succeed(path: String) {
        if (delivered) return
        delivered = true
        cleanup()
        ScreenCapture.onFrameResult(Result.success(path))
        stopSelfSafely()
    }

    private fun fail(msg: String) {
        if (delivered) return
        delivered = true
        cleanup()
        ScreenCapture.onFrameResult(Result.failure(IllegalStateException(msg)))
        stopSelfSafely()
    }

    private fun cleanup() {
        try { virtualDisplay?.release() } catch (_: Exception) {}
        try { imageReader?.setOnImageAvailableListener(null, null) } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}
        try { projection?.stop() } catch (_: Exception) {}
        virtualDisplay = null; imageReader = null; projection = null
    }

    private fun stopSelfSafely() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {}
        try { thread?.quitSafely() } catch (_: Exception) {}
        stopSelf()
    }

    override fun onDestroy() {
        cleanup()
        try { thread?.quitSafely() } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "屏幕取字", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Arix 屏幕取字")
            .setContentText("正在抓取当前屏幕…")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    companion object {
        private const val CHANNEL_ID = "xtom_screen_ocr"
        private const val NOTIF_ID = 42081
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        /** 用授权结果启动本前台服务开始抓帧。 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val i = Intent(context, ScreenCaptureService::class.java)
                .putExtra(EXTRA_RESULT_CODE, resultCode)
                .putExtra(EXTRA_RESULT_DATA, data)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }
    }
}
