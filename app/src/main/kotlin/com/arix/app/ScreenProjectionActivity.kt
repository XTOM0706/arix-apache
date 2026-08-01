package com.arix.app

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * 透明代理 Activity：向系统申请「屏幕录制/投屏」授权(MediaProjection)。
 *
 * 为什么需要它：MediaProjection 的授权是一次 startActivityForResult 流程——必须由一个 Activity
 * 弹出系统的「开始投影？」对话框并接住 (resultCode, data)。悬浮窗/服务/工具都不是 ActivityResult 宿主，
 * 故照搬 [AttachmentPickerActivity] 的「透明代理 Activity + 静态回调」样板，避免改动 MainActivity。
 *
 * 结果通过 [ScreenCapture.onConsentResult] 交回编排器；用户取消则回传 null。
 * 悬浮窗层级高于系统对话框，会遮住它，故拉起期间先隐藏悬浮窗内容（若在用），完成后由调用方恢复。
 */
class ScreenProjectionActivity : ComponentActivity() {
    private lateinit var launcher: ActivityResultLauncher<Intent>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
            val data = res.data
            if (res.resultCode == RESULT_OK && data != null) {
                ScreenCapture.onConsentResult(res.resultCode, data)
            } else {
                ScreenCapture.onConsentResult(RESULT_CANCELED, null)
            }
            finish()
        }
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            launcher.launch(mpm.createScreenCaptureIntent())
        } catch (e: Exception) {
            ScreenCapture.onConsentResult(RESULT_CANCELED, null)
            finish()
        }
    }

    companion object {
        /** 从任意 context（含悬浮窗/服务）拉起授权对话框。 */
        fun request(context: Context) {
            context.startActivity(
                Intent(context, ScreenProjectionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
