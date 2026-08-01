package com.arix.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * 透明代理 Activity：给「悬浮窗里的唤醒助手」做附件选择/拍照——悬浮窗自身不是 ActivityResult 宿主。
 * 悬浮窗层级(TYPE_APPLICATION_OVERLAY)高于系统选择器/相机，会遮住它们，故拉起期间先隐藏悬浮窗内容、完成后恢复。
 * kind: "camera" 拍照 / "image" 相册图片 / "file" 任意文件。
 */
class AttachmentPickerActivity : ComponentActivity() {
    private lateinit var getContent: ActivityResultLauncher<String>
    private lateinit var takePicture: ActivityResultLauncher<Uri>
    private lateinit var reqCamPerm: ActivityResultLauncher<String>
    private var camUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WakeOverlayHost.setContentVisible(false) // 隐藏悬浮窗，避免遮住系统选择器/相机
        getContent = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            deliver(uris.map { it.toString() })
        }
        takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            deliver(if (ok && camUri != null) listOf(camUri.toString()) else emptyList())
        }
        reqCamPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) launchCamera() else deliver(emptyList())
        }
        when (intent.getStringExtra(EXTRA_KIND)) {
            "camera" ->
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera()
                else reqCamPerm.launch(Manifest.permission.CAMERA)
            "image" -> try { getContent.launch("image/*") } catch (_: Exception) { deliver(emptyList()) }
            else -> try { getContent.launch("*/*") } catch (_: Exception) { deliver(emptyList()) }
        }
    }

    private fun launchCamera() {
        try {
            val f = File(cacheDir, "wake_cam_${System.currentTimeMillis()}.jpg")
            camUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            takePicture.launch(camUri!!)
        } catch (_: Exception) { deliver(emptyList()) }
    }

    private fun deliver(uris: List<String>) {
        WakeOverlayHost.setContentVisible(true) // 恢复悬浮窗
        try { onPicked?.invoke(uris) } catch (_: Exception) {}
        onPicked = null
        finish()
    }

    companion object {
        private const val EXTRA_KIND = "kind"
        @Volatile var onPicked: ((List<String>) -> Unit)? = null

        /** 从任意 context（含悬浮窗）拉起。kind: "camera"/"image"/"file"。 */
        fun pick(context: Context, kind: String, onResult: (List<String>) -> Unit) {
            onPicked = onResult
            context.startActivity(
                Intent(context, AttachmentPickerActivity::class.java)
                    .putExtra(EXTRA_KIND, kind)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
