package com.arix.app

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.ui.XtomCard
import com.arix.app.ui.PageScaffold

/** 收藏夹：列出收藏的消息，点按复制、长按删除。借鉴 RikkaHub/Kelivo 的收藏。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable fun FavoritesPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    var tick by remember { mutableStateOf(0) }
    val favs = remember(tick) { FavoriteStore.list(context) }
    val fmt = remember { java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()) }

    PageScaffold(scroll = false) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text("收藏 (${favs.size})", color = scheme.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            if (favs.isNotEmpty()) TextButton(onClick = { FavoriteStore.clear(context); tick++ }) { Text(tr("清空"), color = scheme.error, fontSize = 12.sp) }
        }
        Text(tr("聊天里长按消息 →「收藏」加进来。点按复制，长按删除。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
        if (favs.isEmpty()) {
            Text(tr("还没有收藏。"), color = scheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(top = 12.dp))
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(favs, key = { it.ts }) { f ->
                    XtomCard(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .combinedClickable(
                            onClick = { clipboard.setText(AnnotatedString(f.text)) },
                            onLongClick = { FavoriteStore.remove(context, f.ts); tick++ },
                        )) {
                        Text(f.text, color = scheme.onSurface, fontSize = 13.sp, maxLines = 6)
                        Spacer(Modifier.height(4.dp))
                        Text("${if (f.role == "user") "我" else "AI"} · ${fmt.format(java.util.Date(f.ts))}", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
