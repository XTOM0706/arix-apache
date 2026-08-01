package com.arix.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import com.arix.app.ui.topChromeGapHeight

// 文件：集中查看 App 存下的文件（拍照/生成头像/世界书等）。点击图片查看，可删除。
// 说明：上传的附件目前发送后未持久化，后续做附件落库时会一并出现在这里。
@Composable fun FilesPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    var bump by remember { mutableStateOf(0) }
    var viewerUri by remember { mutableStateOf<String?>(null) }
    viewerUri?.let { u -> ImageViewerDialog(u) { viewerUri = null } }
    val files = remember(bump) {
        val dirs = listOfNotNull(context.cacheDir, context.filesDir, context.externalCacheDir)
        dirs.flatMap { d -> d.listFiles()?.asList() ?: emptyList() }
            .filter { it.isFile && it.length() > 0 }
            .sortedByDescending { it.lastModified() }
    }
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // 顶部悬浮玻璃让位。这页说明文字不滚(下面 LazyColumn 才滚)，让不出「从玻璃下滑过去化开」，退化成普通留白
        Spacer(Modifier.topChromeGapHeight())
        Text(tr("App 存下的文件（拍照 / 生成头像 / 数据等）"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp))
        if (files.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(tr("暂无文件"), color = scheme.onSurfaceVariant, fontSize = 13.sp) }
        } else LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(files.size, key = { files[it].absolutePath }) { idx ->
                val f = files[idx]
                val ext = f.extension.lowercase()
                val isImage = ext in setOf("jpg", "jpeg", "png", "webp", "gif", "bmp")
                Surface(shape = RoundedCornerShape(16.dp), color = scheme.surfaceContainer, border = BorderStroke(1.dp, scheme.outlineVariant), modifier = Modifier.fillMaxWidth()) {
                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(scheme.surfaceContainerHighest), contentAlignment = Alignment.Center) {
                            if (isImage) coil.compose.AsyncImage(model = f, contentDescription = null, modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(10.dp)).clickable { viewerUri = "file://${f.absolutePath}" }, contentScale = ContentScale.Crop)
                            else Icon(iconFor(ext), null, tint = scheme.primary, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(f.name, color = scheme.onSurface, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                            Text("${fmtSize(f.length())} · ${f.parentFile?.name ?: ""}", color = scheme.onSurfaceVariant, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(tr("删除"), color = scheme.error, fontSize = 11.sp, modifier = Modifier.clip(RoundedCornerShape(50)).clickable { runCatching { f.delete() }; bump++ }.padding(horizontal = 8.dp, vertical = 4.dp))
                    }
                }
            }
        }
    }
}

private fun iconFor(ext: String) = when (ext) {
    "mp3", "wav", "flac", "m4a", "ogg" -> Icons.Outlined.AudioFile
    "mp4", "mkv", "avi", "mov", "webm" -> Icons.Outlined.Movie
    "pdf" -> Icons.Outlined.PictureAsPdf
    "txt", "md", "log" -> Icons.Outlined.Article
    "json", "xml", "kt", "java", "py", "js", "html", "css" -> Icons.Outlined.Code
    else -> Icons.Outlined.InsertDriveFile
}
private fun fmtSize(b: Long) = when {
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "%.1f MB".format(b.toDouble() / (1024 * 1024))
}
