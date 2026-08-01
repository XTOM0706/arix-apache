package com.arix.app

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

// 全屏图片查看器：双指缩放/旋转/拖动，双击复位或放大，单击空白关闭；顶栏可旋转90°/复位/关闭。
// 可复用于聊天图片、Markdown 图、文件页等（前后端同步统一入口）。
@Composable
fun ImageViewerDialog(uri: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        var scale by remember { mutableStateOf(1f) }
        var rotation by remember { mutableStateOf(0f) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        fun reset() { scale = 1f; rotation = 0f; offset = Offset.Zero }
        val transform = rememberTransformableState { zoomChange, panChange, rotationChange ->
            scale = (scale * zoomChange).coerceIn(1f, 6f)
            rotation += rotationChange
            offset += panChange
        }
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.93f))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { if (scale > 1.05f) reset() else scale = 2.5f },
                        onTap = { if (scale <= 1.05f) onDismiss() },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            coil.compose.AsyncImage(
                model = coil.request.ImageRequest.Builder(ctx).data(uri).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale; scaleY = scale; rotationZ = rotation
                        translationX = offset.x; translationY = offset.y
                    }
                    .transformable(transform),
            )
            Row(Modifier.align(Alignment.TopEnd).padding(10.dp)) {
                IconButton(onClick = { rotation += 90f }) { Icon(Icons.Outlined.RotateRight, contentDescription = "旋转", tint = Color.White) }
                IconButton(onClick = { reset() }) { Icon(Icons.Outlined.Refresh, contentDescription = "复位", tint = Color.White) }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "关闭", tint = Color.White) }
            }
        }
    }
}
