package com.arix.app

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Forward
import androidx.compose.material.icons.automirrored.outlined.Reply
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.Slideshow
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.TextFields
import androidx.compose.material.icons.outlined.Translate
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.theme.LocalXtomAccents
import com.arix.app.theme.LocalThemeConfig
import com.arix.app.theme.spacing
import com.arix.app.ui.glassSurface
import com.arix.app.ui.chatGlassCutout
import com.arix.app.ui.LocalChatAppearance
import com.arix.app.ui.XtomStyledBubbleSurface
import com.arix.app.ui.XtomStreamingCursor
import com.arix.app.ui.resolveBubbleTextColor
import com.arix.app.ui.ThinkingIndicator
import com.arix.cloudapi.model.ChatMessage

// ============================================================
// 聊天气泡 / 工具卡片 —— 表现层全令牌化（DESIGN.md §8 第3步）
// 颜色/形状一律取自 MaterialTheme.* 与 LocalXtomAccents，禁止写死 0xFF。
// 字号一律取 MaterialTheme.typography 的排版尺度，不写 10sp/11sp/13sp——写死的 sp 是
// 「看着不像 M3」的主因，也让「字体/字号」主题设置对聊天页失效。
// 可点的东西一律用 M3 组件（IconButton / Surface(onClick) / DropdownMenuItem），不用裸 Modifier.clickable
// ——裸 clickable 没有水波纹、没有 Role 语义、没有禁用态、没有最小触摸区。
// 交互逻辑（长按菜单、展开、头像长按 @ 引用、流式 active 等）与旧版一致。
// ============================================================

// 气泡上下文动作：把原先 15 个回调收成一个稳定的 (bubble, action) 事件，
// 让 ChatBubbleItem 可跳过重组（否则每次整页重组都重画所有可见气泡 + 重解析 Markdown）。
sealed interface BubbleAction {
    data object DismissMenu : BubbleAction
    data object LongPress : BubbleAction
    data object AvatarLongPress : BubbleAction
    data object Copy : BubbleAction
    /**
     * 打开整屏选字页，手指划选任意一段复制。
     *
     * ⚠ 实现**必须**是一个独立弹窗。为了滚动性能，每条气泡上的 SelectionContainer 是被主动删掉的
     * （见下方 ChatBubbleItem 正文处的注释），把它加回列表项里就是把那个卡顿原样请回来。
     * 见 [MessageTextSelectDialog]。
     */
    data object SelectText : BubbleAction
    data object Favorite : BubbleAction
    data object Translate : BubbleAction
    data object ReadAloud : BubbleAction
    data object RegenBiasClick : BubbleAction
    data class Regenerate(val bias: String) : BubbleAction
    data object Reply : BubbleAction
    data object Modify : BubbleAction
    data object EditAndResend : BubbleAction
    data object Rollback : BubbleAction
    data object Share : BubbleAction
    data object ShareImage : BubbleAction
    /** 转发这条消息到另一个会话（见 [ForwardMessageDialog]）。和 [Share] 不同：不出 App。 */
    data object ForwardTo : BubbleAction
    data object Info : BubbleAction
    data object Delete : BubbleAction
    data object DeleteFrom : BubbleAction
    data object MultiSelect : BubbleAction   // 进入多选模式（批量删除）
    data class SwitchVariant(val dir: Int) : BubbleAction   // 分支变体切换：-1 上一个 / +1 下一个
    /**
     * 分叉到新会话：把「到这条为止」的历史复制成一个**独立的新会话**，原会话一个字不动。
     *
     * 和 [SwitchVariant]（同一个会话内部的分支树）是两件事，别混：那个是"这一句的另一种说法"，
     * 换过去原来的说法就不在活动路径上了；这个是"从这里另开一条路走"，两边此后各过各的。
     * 菜单项由 [LocalChatBehavior] 的开关控制显隐（默认关）。
     */
    data object ForkToNew : BubbleAction
}

// 会话身份：用户/AI 的名称与头像 URI（仿 RikkaHub）。@Immutable + 单参传入 →
// 不破坏 ChatBubbleItem 的可跳过重组（同一实例不变即跳过）。
@Immutable
data class ChatIdentity(
    val userName: String = "我",
    val userAvatar: String? = null,
    val aiName: String = "助手",
    val aiAvatar: String? = null,
)

// 圆形头像：有图用 Coil 加载（crossfade 淡入动画），无图用名称首字。
// 容器换成 M3 Surface（形状/容器色/色调抬升都由组件按令牌处理），不再手搓 clip+background。
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Avatar(
    uri: String?,
    fallback: String,
    size: Dp = 28.dp,
    onLongPress: (() -> Unit)? = null,
    // 头像圆角。Unspecified = 保持正圆（所有旧调用点的行为），给了值就是圆角方块。
    // 加在末尾并带默认值，是为了不惊动散在 7 个文件里的既有调用。
    corner: Dp = Dp.Unspecified,
) {
    val scheme = MaterialTheme.colorScheme
    // 长按（AI 头像 @ 引用）只有 combinedClickable 能做，M3 没有带 onLongClick 的按钮；
    // 但它默认走 LocalIndication → 水波纹是有的。onLongClickLabel 让读屏能念出「长按能干嘛」。
    val clickMod = if (onLongPress != null) {
        Modifier.combinedClickable(
            onClick = {},
            onLongClick = onLongPress,
            onLongClickLabel = tr("引用这条消息"),
        )
    } else Modifier
    // 圆角封顶在边长的一半：再大也只是正圆，放开会让 RoundedCornerShape 按比例缩、形状反而不可控
    val shape = if (corner == Dp.Unspecified) CircleShape
        else androidx.compose.foundation.shape.RoundedCornerShape(corner.coerceIn(0.dp, size / 2))
    Surface(
        shape = shape,
        color = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurface,
        modifier = Modifier.size(size).then(clickMod),
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (!uri.isNullOrBlank()) {
                val avCtx = LocalContext.current
                // 记忆化：uri 不变就别每次组合都新建 ImageRequest（小气泡多时每条头像现建一个请求 = 滚动组合开销）。
                val avReq = remember(uri, avCtx) { coil.request.ImageRequest.Builder(avCtx).data(uri).crossfade(true).build() }
                coil.compose.AsyncImage(
                    model = avReq,
                    contentDescription = null,   // 旁边就是名字，读屏念图片是噪音
                    modifier = Modifier.fillMaxSize(),   // 外层 Surface 已按 CircleShape 裁剪，不用再 clip 一次
                    contentScale = ContentScale.Crop,
                )
            } else {
                // 这里的字号必须跟着 size 走：头像在各页是 28/30/36/40/52dp，钉死一个排版令牌
                // 在小头像上会溢出、在大头像上像颗芝麻。取 titleMedium 的字形（字体族跟随主题设置），
                // 只把字号按头像直径等比缩放——这是文件里唯一保留的比例字号，理由如上。
                Text(
                    fallback.take(1).ifBlank { "?" },
                    color = scheme.primary,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontSize = (size.value * 0.42f).sp,
                        fontWeight = FontWeight.Bold,
                    ),
                )
            }
        }
    }
}

// 附件缩略图：图片直接显示（Coil），非图片用文件图标兜底；可选右上角移除按钮。
// 容器换成 M3 Surface；缩略图本体也是 Surface(onClick)（拿水波纹 + Button 语义）；
// 移除键换成真 IconButton（水波纹 + Role.Button 语义 + contentDescription，原来是个裸 clickable 的 Icon）。
@Composable
fun AttachmentThumb(uri: String, onRemove: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val meta = remember(uri) { resolveFileMeta(ctx, uri) }
    val isImage = meta.mime.startsWith("image/")
    var showViewer by remember { mutableStateOf(false) }
    if (showViewer) ImageViewerDialog(uri) { showViewer = false }
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = scheme.surfaceContainerHighest,
        contentColor = scheme.onSurface,
        modifier = Modifier.widthIn(max = 220.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val thumbShape = MaterialTheme.shapes.small
            if (isImage) {
                Surface(
                    onClick = { showViewer = true },
                    shape = thumbShape,
                    color = scheme.surfaceContainerHigh,
                    modifier = Modifier.size(36.dp),
                ) {
                    coil.compose.AsyncImage(
                        model = coil.request.ImageRequest.Builder(ctx).data(uri).crossfade(true).build(),
                        contentDescription = tr("查看大图"),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
            } else {
                Surface(shape = thumbShape, color = scheme.surfaceContainerHigh, modifier = Modifier.size(36.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(iconForFile(meta.mime, meta.name), contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f, fill = false)) {
                // 文件名是标识符不是说明文字，一行 + 省略是对的；但用 MiddleEllipsis 从中间省，
                // 这样扩展名还看得见（尾部省略会把 ".pdf" 吃掉，等于不知道这是啥文件）。
                Text(
                    meta.name,
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                val sz = fmtFileSize(meta.size)
                if (sz.isNotBlank()) Text(sz, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
            if (onRemove != null) {
                Spacer(Modifier.width(4.dp))
                IconButton(
                    // 压到 36dp 迁就这一行的高度：M3 默认 40dp 会把这条 220dp 宽的附件条撑高、也吃掉文件名的宽度。
                    // 36dp 仍低于 48dp 的推荐触摸区，但比原来那个 16dp 的裸 Icon 强得多；
                    // 要严格合规就把这行 modifier 删掉、用默认尺寸。
                    onClick = onRemove,
                    modifier = Modifier.size(36.dp),
                    colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.onSurfaceVariant),
                ) {
                    Icon(Icons.Outlined.Close, contentDescription = tr("移除"), modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private data class FileMeta(val name: String, val size: Long, val mime: String)

private fun resolveFileMeta(ctx: android.content.Context, uriStr: String): FileMeta = try {
    val uri = android.net.Uri.parse(uriStr)
    var name = uri.lastPathSegment ?: tr("文件")
    var size = -1L
    val mime = ctx.contentResolver.getType(uri) ?: ""
    ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val ni = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
        val si = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
        if (c.moveToFirst()) {
            if (ni >= 0) c.getString(ni)?.let { name = it }
            if (si >= 0 && !c.isNull(si)) size = c.getLong(si)
        }
    }
    FileMeta(name, size, mime)
} catch (_: Exception) { FileMeta(tr("文件"), -1, "") }

private fun iconForFile(mime: String, name: String): ImageVector {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when {
        mime.startsWith("image/") -> Icons.Outlined.Image
        mime.startsWith("audio/") || ext in setOf("mp3", "wav", "flac", "m4a", "ogg") -> Icons.Outlined.AudioFile
        mime.startsWith("video/") || ext in setOf("mp4", "mkv", "avi", "mov", "webm") -> Icons.Outlined.Movie
        mime == "application/pdf" || ext == "pdf" -> Icons.Outlined.PictureAsPdf
        ext in setOf("zip", "rar", "7z", "tar", "gz") -> Icons.Outlined.FolderZip
        ext in setOf("doc", "docx", "odt", "rtf") -> Icons.AutoMirrored.Outlined.Article
        ext in setOf("xls", "xlsx", "csv") -> Icons.Outlined.TableChart
        ext in setOf("ppt", "pptx") -> Icons.Outlined.Slideshow
        ext in setOf("txt", "md", "markdown", "log") -> Icons.Outlined.Description
        ext in setOf("kt", "java", "py", "js", "ts", "c", "cpp", "h", "go", "rs", "json", "xml", "html", "css", "sh") -> Icons.Outlined.Code
        else -> Icons.AutoMirrored.Outlined.InsertDriveFile
    }
}

private fun fmtFileSize(b: Long): String = when {
    b < 0 -> ""
    b < 1024 -> "$b B"
    b < 1024 * 1024 -> "${b / 1024} KB"
    else -> "%.1f MB".format(b.toDouble() / (1024 * 1024))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun ChatBubbleItem(
    bubble: ChatBubble,
    showCursor: Boolean = false,
    showContextMenu: Boolean = false,
    showBiasSubmenu: Boolean = false,
    identity: ChatIdentity = ChatIdentity(),
    variantIndex: Int = 0,      // 该消息在其兄弟分支中的序号(从1起)；0=无分支
    variantCount: Int = 1,      // 该位置的分支总数；<=1 不显示切换箭头
    modelForCost: String? = null,   // 当前模型名，用于估算花费($)
    onAction: (ChatBubble, BubbleAction) -> Unit = { _, _ -> },
    // 这条气泡刚结束流式（思考在流式里是强制展开的，见 ChatScreen 的 justStreamedId）：
    // 让思考抽屉先以展开态组合一帧，再动画收起到默认态，把「思考结束抽屉瞬间合上」的硬切变平滑。
    reasoningStartOpen: Boolean = false,
) {
    val isUser = bubble.role == "user"
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    // 外观快照：一次读盘、整页共用。绝不能在这里调 ChatAppearancePrefs.style()——
    // 这个 composable 每条可见消息都跑，滚动时每帧都可能重入，读 SharedPreferences 会直接卡住列表。
    val baseStyle = LocalChatAppearance.current.side(isUser)
    // 显示细节开关（不透明度/模型名/token 用量/思考默认折叠…）。同样是 static local，读它不产生状态订阅。
    val display = LocalChatDisplay.current
    // 气泡不透明度：alpha 不能存进 bubbleArgb（那是取色器给的实色，用户挑颜色和调透明度是两件事，
    // 混在一个 Int 里就会「换个颜色透明度被重置」）。这里在**渲染前**把它合进这一帧的 style：
    // 底色为 null 表示跟随主题，得先取到主题令牌才能加 alpha；字色也必须同时定死——
    // 否则 resolveBubbleTextColor 见到「底色非 null」就改走亮度兜底的黑/白，一开透明度字就换色了。
    val bubbleAlpha = display.bubbleAlpha(isUser)
    val resolvedText = resolveBubbleTextColor(baseStyle, isUser)
    val themedBubble = baseStyle.bubbleColor ?: if (isUser) scheme.primaryContainer else scheme.surfaceContainerHigh
    val style = if (bubbleAlpha >= 1f) baseStyle else remember(baseStyle, bubbleAlpha, themedBubble, resolvedText) {
        baseStyle.copy(bubbleColor = themedBubble.copy(alpha = bubbleAlpha), textColor = resolvedText)
    }
    // 工具/思考呈现风格：TIMELINE=合并进一张时间线卡（非气泡）；GLASS=各自一张玻璃卡（旧）。
    val metaStyle = LocalThemeConfig.current.metaBlockStyle

    // 工具「结果」：作为 AI 回合的延续，缩进对齐到头像位、无头像（像一条执行日志的返回行）。
    // 工具「调用」不在此拦截——走正常路径，保留 AI 头像+名称，工具卡显示在头像下面。
    if (bubble.role == "tool") {
        // 原来这里写死 46dp。头像一改大就对不上了，而且关掉头像后左边会空出一整块。
        // 改成跟着 AI 侧头像走（30dp 头像 + 16dp 间距 = 46dp，默认值不变）。
        val aiStyle = LocalChatAppearance.current.ai
        val indent = if (aiStyle.showAvatar) aiStyle.avatarSize + 16.dp else 0.dp
        Column(modifier = Modifier.fillMaxWidth().padding(start = indent, top = 1.dp, bottom = 1.dp)) {
            // 必须把 toolCallId 传下去：卡片靠它去 ToolActivityBus 回查耗时/请求方/完整参数。
            // 不传的话展开区里那些字段全是空的——功能写了却等于没有。
            if (metaStyle == com.arix.app.theme.MetaBlockStyle.TIMELINE) ToolResultTimeline(bubble.text, bubble.toolCallId)
            else ToolResultCard(bubble.text, bubble.toolCallId)
        }
        return
    }

    Box(modifier = Modifier.fillMaxWidth()) {
        // Telegram 式：头像+名字挪到气泡上方一行，气泡占满整行宽度不再被头像挤窄（#12）
        // 消息间距按外观设置的「消息密度」取值（紧凑/适中/舒适），只读令牌、不改逻辑。
        val messageGap = LocalThemeConfig.current.messageDensity.spacing().messageGap
        // 气泡靠哪边（AUTO=用户右/AI左）：横向对齐、尖角方向、头像位置全跟它走。
        val onRight = when (style.align) {
            ChatAppearancePrefs.Align.LEFT -> false
            ChatAppearancePrefs.Align.RIGHT -> true
            else -> isUser
        }
        val besideAvatar = style.avatarBeside && style.showAvatar
        // 头像组件（上方版式/旁边版式共用；AI 侧带长按 @ 引用）
        val avatarNode: @Composable () -> Unit = {
            if (isUser) Avatar(uri = identity.userAvatar, fallback = identity.userName, size = style.avatarSize, corner = style.avatarCorner)
            else Avatar(uri = identity.aiAvatar, fallback = identity.aiName, size = style.avatarSize, corner = style.avatarCorner, onLongPress = { onAction(bubble, BubbleAction.AvatarLongPress) })
        }
        // 气泡主体各块（附件/思考/正文/工具卡/分支/用量）抽成 lambda，两种版式复用同一份、不重复。
        val bubbleBlocks: @Composable ColumnScope.() -> Unit = {
            if (isUser && !bubble.attachments.isNullOrEmpty()) {
                Column(modifier = Modifier.padding(bottom = 3.dp), horizontalAlignment = if (onRight) Alignment.End else Alignment.Start, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    bubble.attachments.forEach { AttachmentThumb(it) }
                }
            }
            // 顺序：先思考/工具、再正文。TIMELINE：思考+工具调用合并进一张时间线卡（放正文前，非气泡）；
            // GLASS：思考卡在正文前、工具调用卡在正文后（旧观感，见下）。
            val toolCalls = bubble.toolCalls?.takeIf { it.isNotEmpty() }
            val hasReasoning = !bubble.reasoning.isNullOrBlank()
            if (metaStyle == com.arix.app.theme.MetaBlockStyle.TIMELINE) {
                if (hasReasoning || toolCalls != null) ChainTimelineCard {
                    if (hasReasoning) ReasoningStep(bubble.reasoning!!, active = showCursor, startOpen = reasoningStartOpen)
                    toolCalls?.forEach { c -> ToolCallStep(c) }
                }
            } else if (hasReasoning) {
                ReasoningBlock(bubble.reasoning!!, active = showCursor, startOpen = reasoningStartOpen)
            }
            val isToolMeta = bubble.toolCalls != null && bubble.text.isBlank()
            if (!isToolMeta) XtomStyledBubbleSurface(
                isUser = isUser,
                style = style,
                onRight = onRight,   // 尖角朝气泡所在那一侧（也就是头像那一侧）
                // 流式时给气泡加高度过渡，文字增长更柔和（治“动画太硬”）
                modifier = if (showCursor) Modifier.animateContentSize(animationSpec = tween(180, easing = FastOutSlowInEasing)) else Modifier,
            ) {
                val rawBody = bubble.text.takeUnless { it == "null" } ?: " "
                val bodyText = rawBody
                // 走同一个解析函数而不是再写一遍 if(isUser)：自定义底色时的可读性兜底在那里，
                // 这里若各算各的，Markdown 正文就会和气泡里其它文字不是一个颜色
                val bodyColor = resolveBubbleTextColor(style, isUser)
                // 正文尺度走 bodyMedium 令牌（原来写死 13sp）。MarkdownText 只收 fontSize。
                val bodyStyle = MaterialTheme.typography.bodyMedium
                // 去掉每条气泡的 SelectionContainer：它是 Compose 列表里最贵的「每项」组件之一，小气泡多时滚动进出反复
                // 组合它就卡（用户实测：关玻璃、纯文本无 markdown 仍卡 → 锁定「组合开销」而非玻璃/markdown）。复制走长按菜单。
                // 「长文流式丢渲染」根治：超 8000 字流式期间才退纯文本兜底（真·超长墙），其余流式期间就有 Markdown。
                val bodyContent: @Composable () -> Unit = {
                    if (isUser) Text(bodyText, color = bodyColor, style = bodyStyle)
                    else if (showCursor && bodyText.length > 8000) Text(bodyText, color = bodyColor, style = bodyStyle)
                    else MarkdownText(bodyText, color = bodyColor, fontSize = bodyStyle.fontSize, streaming = showCursor)
                }
                // 只有流式态需要把光标接在正文右侧 → 用 Row(Bottom)；静态气泡直接放正文，省一层单子 Row + 底对齐测量。
                if (showCursor) {
                    Row(verticalAlignment = Alignment.Bottom) { bodyContent(); XtomStreamingCursor() }
                } else {
                    bodyContent()
                }
            }
            // 工具调用卡片：GLASS 放正文之后（旧时序）；TIMELINE 已并入正文前那张时间线卡。
            if (metaStyle != com.arix.app.theme.MetaBlockStyle.TIMELINE) toolCalls?.let { calls -> ToolCallCard(calls) }
            // 分支变体切换 ‹ k/n ›：仅该位置存在多个兄弟分支时显示（像 ChatGPT）
            if (variantCount > 1 && !isToolMeta) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 2.dp, start = if (onRight) 0.dp else 6.dp, end = if (onRight) 6.dp else 0.dp),
                ) {
                    val canPrev = variantIndex > 1
                    val canNext = variantIndex < variantCount
                    // 原来是 18dp 的 Icon + clickable：没水波纹、没禁用态、触摸区只有 18dp（表上基本点不中）。
                    // 换真 IconButton：禁用态/水波纹/Button 语义都由组件给，禁用色也走 M3 的 38% 令牌而不是手调的 35%。
                    // 压到 32dp 是取舍：这排是气泡底下的元信息，默认 40dp 会在每条分支消息下顶出一条大空带。
                    // 32dp 仍不到 48dp 推荐值，但比原来的 18dp 强得多；要严格合规就删掉这行 modifier。
                    // 箭头用 AutoMirrored 版：ar/fa/he 是 RTL，「上一个」在那边该指向右。
                    IconButton(
                        onClick = { onAction(bubble, BubbleAction.SwitchVariant(-1)) },
                        enabled = canPrev,
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.primary),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, contentDescription = tr("上一个分支"), modifier = Modifier.size(18.dp))
                    }
                    Text("$variantIndex/$variantCount", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 2.dp))
                    IconButton(
                        onClick = { onAction(bubble, BubbleAction.SwitchVariant(1)) },
                        enabled = canNext,
                        modifier = Modifier.size(32.dp),
                        colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.primary),
                    ) {
                        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = tr("下一个分支"), modifier = Modifier.size(18.dp))
                    }
                }
            }
            // 元信息行。模型名只认 bubble.model（**产出这条消息的**模型）：modelForCost 是「当前选的」模型，
            // 拿它给历史消息署名就是张冠李戴——早期消息没记 model 字段，宁可不显示也不能标错。
            val metaModel = bubble.model?.takeIf { display.showModelName && it.isNotBlank() }
            val metaUsage = bubble.usage?.takeIf { display.showTokenUsage }
            if (!isUser && (metaModel != null || bubble.usage != null || bubble.elapsedMs != null || bubble.tokensPerSec != null)) {
                // 用量/耗时/花费的一行小字。原来挤成一行没法读，拆开只是排版，拼串逻辑与顺序完全没动。
                val metaText = buildString {
                    if (metaModel != null) append(metaModel)
                    if (bubble.elapsedMs != null) {
                        if (isNotEmpty()) append(" · ")
                        val sec = bubble.elapsedMs / 1000.0
                        append("${"%.1f".format(java.util.Locale.US, sec)}s")
                    }
                    metaUsage?.let { u ->
                        if (isNotEmpty()) append(" · ")
                        append("${u.totalTokens}t (${tr("入%d/出%d").format(u.promptTokens, u.completionTokens)})")
                    }
                    // 花费不跟着「token 用量」一起关：钱和 token 是两件事，有人只想看花了多少、不关心 token 数。
                    bubble.usage?.let { u ->
                        val cost = com.arix.app.ModelPricing.costUsd(bubble.model ?: modelForCost, u.promptTokens, u.completionTokens)
                        if (cost != null) {
                            if (isNotEmpty()) append(" · ")
                            append(com.arix.app.ModelPricing.fmt(cost))
                        }
                    }
                    if (bubble.tokensPerSec != null) {
                        if (isNotEmpty()) append(" · ")
                        append("${"%.1f".format(java.util.Locale.US, bubble.tokensPerSec)} t/s")
                    }
                }
                if (metaText.isNotEmpty()) Text(metaText, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
            }
        } // end bubbleBlocks
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = messageGap.dp).combinedClickable(onClick = {}, onLongClick = { onAction(bubble, BubbleAction.LongPress) }),
            horizontalAlignment = if (onRight) Alignment.End else Alignment.Start,
        ) {
            // 名字行（旁边版式时头像不在这行、只留名字；上方版式时名字+头像跟着 onRight 排）
            val showTopRow = style.showName || (style.showAvatar && !besideAvatar)
            if (showTopRow) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)) {
                    val nameText = if (isUser) identity.userName else identity.aiName
                    if (onRight) {
                        if (style.showName) { Text(nameText, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium); if (style.showAvatar && !besideAvatar) Spacer(Modifier.width(5.dp)) }
                        if (style.showAvatar && !besideAvatar) avatarNode()
                    } else {
                        if (style.showAvatar && !besideAvatar) { avatarNode(); if (style.showName) Spacer(Modifier.width(5.dp)) }
                        if (style.showName) Text(nameText, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
            if (besideAvatar) {
                // 微信式：头像在气泡旁边（onRight 侧），尖角正对头像；顶部对齐让头像挨着气泡上沿。
                Row(verticalAlignment = Alignment.Top) {
                    if (!onRight) { avatarNode(); Spacer(Modifier.width(6.dp)) }
                    Column(horizontalAlignment = if (onRight) Alignment.End else Alignment.Start) { bubbleBlocks() }
                    if (onRight) { Spacer(Modifier.width(6.dp)); avatarNode() }
                }
            } else {
                bubbleBlocks()
            }
        } // end Column

        DropdownMenu(expanded = showContextMenu, onDismissRequest = { onAction(bubble, BubbleAction.DismissMenu) }) {
            if (showBiasSubmenu) {
                // 注意：Regenerate 里的中文是发给模型的**提示词**、不是界面文字，所以不能 tr()（翻了模型收到的指令就变了）。
                BubbleMenuItem(tr("更简短"), Icons.AutoMirrored.Outlined.ShortText) { onAction(bubble, BubbleAction.Regenerate("请用更简短的方式回答")) }
                BubbleMenuItem(tr("更详细"), Icons.AutoMirrored.Outlined.Notes) { onAction(bubble, BubbleAction.Regenerate("请用更详细的方式回答")) }
                BubbleMenuItem(tr("更个性化"), Icons.Outlined.Face) { onAction(bubble, BubbleAction.Regenerate("请用更个性化的方式回答")) }
                BubbleMenuItem(tr("完全重新生成"), Icons.Outlined.Autorenew) { onAction(bubble, BubbleAction.Regenerate("")) }
            } else {
                BubbleMenuItem(tr("复制"), Icons.Outlined.ContentCopy) { onAction(bubble, BubbleAction.Copy) }
                // 「复制」是整条，「选择文本」是划一段——挨着放，用户在同一处就能选到想要的粒度。
                BubbleMenuItem(tr("选择文本"), Icons.Outlined.TextFields) { onAction(bubble, BubbleAction.SelectText) }
                BubbleMenuItem(tr("收藏"), Icons.Outlined.Star) { onAction(bubble, BubbleAction.Favorite) }
                BubbleMenuItem(tr("翻译"), Icons.Outlined.Translate) { onAction(bubble, BubbleAction.Translate) }
                BubbleMenuItem(tr("朗读"), Icons.AutoMirrored.Outlined.VolumeUp, color = accents.success) { onAction(bubble, BubbleAction.ReadAloud) }
                // 原文是 tr("重新生成 ▸")——拿字符 "▸" 当图标，犯了项目铁律。改成标题 + 右侧真箭头图标
                // （AutoMirrored：RTL 下自动指向左）。
                if (bubble.role == "assistant") BubbleMenuItem(
                    tr("重新生成"), Icons.Outlined.Autorenew,
                    trailing = { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null) },
                ) { onAction(bubble, BubbleAction.RegenBiasClick) }
                BubbleMenuItem(tr("回复"), Icons.AutoMirrored.Outlined.Reply) { onAction(bubble, BubbleAction.Reply) }
                // 分叉到新会话（默认关，见 ChatBehaviorPrefs）。读的是 static CompositionLocal，
                // 不产生状态订阅、不读盘——这段在长按菜单里，属于列表热路径。
                // 图标走 AutoMirrored：分叉是个有方向的图形，RTL 语言下该镜像过来（同本文件其它箭头类图标）。
                if (LocalChatBehavior.current.forkToNewConversation) BubbleMenuItem(tr("分叉到新会话"), Icons.AutoMirrored.Outlined.AltRoute) { onAction(bubble, BubbleAction.ForkToNew) }
                BubbleMenuItem(tr("修改"), Icons.Outlined.Edit, color = accents.warning) { onAction(bubble, BubbleAction.Modify) }
                if (bubble.role == "user") BubbleMenuItem(tr("编辑并重发"), Icons.AutoMirrored.Outlined.Send, color = accents.warning) { onAction(bubble, BubbleAction.EditAndResend) }
                if (bubble.role == "user") BubbleMenuItem(tr("回滚到此"), Icons.Outlined.History, color = scheme.error) { onAction(bubble, BubbleAction.Rollback) }
                BubbleMenuItem(tr("分享"), Icons.Outlined.Share) { onAction(bubble, BubbleAction.Share) }
                BubbleMenuItem(tr("分享为图片"), Icons.Outlined.Image) { onAction(bubble, BubbleAction.ShareImage) }
                BubbleMenuItem(tr("转发到…"), Icons.AutoMirrored.Outlined.Forward) { onAction(bubble, BubbleAction.ForwardTo) }
                BubbleMenuItem(tr("信息"), Icons.Outlined.Info, color = scheme.onSurfaceVariant) { onAction(bubble, BubbleAction.Info) }
                BubbleMenuItem(tr("删除"), Icons.Outlined.Delete, color = scheme.error) { onAction(bubble, BubbleAction.Delete) }
                BubbleMenuItem(tr("从此处删除以下"), Icons.Outlined.DeleteSweep, color = scheme.error) { onAction(bubble, BubbleAction.DeleteFrom) }
                // 名字不能叫「多选删除」：多选栏里除了删除还有「分享为图片」（批量），
                // 叫删除等于把那个能力藏起来——用户找不到，只会以为没做。颜色同理别用 error 红。
                BubbleMenuItem(tr("多选（删除 / 生成图片）"), Icons.Outlined.Checklist) { onAction(bubble, BubbleAction.MultiSelect) }
            }
        }
    }
} // end ChatBubbleItem

/**
 * 气泡长按菜单的一项：文字 + 左侧 Material 矢量图标（项目铁律：不用 emoji / 不用 "×" "▸" 这种字符当图标）。
 * 颜色走 MenuDefaults.itemColors 而不是直接给 Text 上色——这样图标跟着文字一起变色、禁用态也由组件统一处理，
 * 这是 M3 的做法；旧写法只给 Text 染色，加了图标就会两个颜色对不上。
 */
@Composable
private fun BubbleMenuItem(
    text: String,
    icon: ImageVector,
    color: Color = MaterialTheme.colorScheme.onSurface,
    trailing: (@Composable () -> Unit)? = null,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        text = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null) },
        trailingIcon = trailing,
        colors = MenuDefaults.itemColors(textColor = color, leadingIconColor = color, trailingIconColor = color),
        onClick = onClick,
    )
}

// ============================================================
// 「非消息」元信息块的共用外壳：思考区 / 工具调用 / 工具结果
// ------------------------------------------------------------
// 这三种东西以前各写各的裸 Row + Modifier.clickable，飘在气泡流里没有容器、没有水波纹，
// 是「不像 Material」最扎眼的地方。现在统一成 M3 的展开卡惯用法：
//   一张 surfaceContainerLow 的 Surface（卡）→ 卡里第一行是可点的 Surface(onClick)（标题行）
//   → 下面挂正文。三者共用同一层壳，读起来是一家人，也跟消息气泡（primary / surfaceContainerHigh）区分开。
// 宽度上限 320dp 与 XtomBubbleSurface 对齐，卡随内容收放（收起时是个小胶囊，不是一条空长条）。
// ============================================================

@Composable
private fun MetaBlockCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    // 工具卡/思考卡（对话页里最显眼的白卡）也玻璃化。
    val shape = MaterialTheme.shapes.medium
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            // ⚠ 这里**不要**再套 animateContentSize：展开区已由 AnimatedVisibility(expandVertically) 逐帧
            // 动高度，卡片按子高度直接跟随即可。若这层也 animateContentSize，两层尺寸动画会对着同一次展开
            // 互相追（一个上报动高度、一个又对它做动画）→ 回弹/橡皮筋，就是用户说的「曲线太丑」。
            modifier = modifier.widthIn(max = 320.dp).clip(shape)
                .chatGlassCutout(shape, MaterialTheme.colorScheme.surfaceContainerLow),
            content = content,
        )
    }
}

/**
 * 元信息卡的标题行。Surface(onClick) 而不是 Modifier.clickable：水波纹、Role.Button 语义、
 * enabled 禁用态都是组件给的。
 *
 * 只有标题行可点、正文不可点——正文里是 SelectionContainer，整块可点会跟选字抢手势（也会改掉原有行为）。
 * expanded=null 表示这行点开的不是折叠区（工具结果点开的是弹窗），此时不画 chevron。
 */
@Composable
private fun DisclosureRow(
    onClick: () -> Unit,
    enabled: Boolean = true,
    expanded: Boolean? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = MaterialTheme.shapes.medium,
        color = Color.Transparent,        // 底色由外层 MetaBlockCard 给，这层只负责点击/水波纹
        contentColor = scheme.onSurface,  // Transparent 没有对应的 contentColorFor，必须显式给，否则内容色是 Unspecified
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            content()
            if (expanded != null) {
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                    contentDescription = if (expanded) tr("收起") else tr("展开"),
                    tint = scheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

// 思考区（非消息气泡）：进行中显示"思考中"动画，完成后折叠为"已思考"，点击展开，切换带高度动画。
// 折叠/展开与流式（active）行为与旧版逐字一致：active 时强制展开且标题行不可点。
// 初始展开态跟「思考块默认折叠」开关走；remember 挂它作 key，这样在外观设置页里一拨开关预览立刻跟着变
// （不挂 key 的话初值只在首次组合时取一次，预览拨了没反应，又是一次「货不对板」）。
@Composable
fun ReasoningBlock(text: String, active: Boolean, startOpen: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val defaultOpen = !LocalChatDisplay.current.reasoningCollapsed
    // 刚结束流式的新气泡（startOpen=true）：流式里思考是强制展开的，而新气泡是全新组合、
    // remember 归零——若默认折叠，新气泡一出现就是收起态 = 展开→收起硬切。这里让它先以
    // 展开态组合一帧，下一帧再动画收起到默认态（AnimatedVisibility 的 exit 动画就会播放）。
    var expanded by remember(defaultOpen) { mutableStateOf(defaultOpen || startOpen) }
    LaunchedEffect(startOpen, defaultOpen) {
        if (startOpen && !defaultOpen) {
            // 等一帧让「展开态」先落地，再翻成收起：直接翻的话 AnimatedVisibility 可能拿不到
            // 起点（同一帧里从初始隐藏直接到隐藏，exit 动画不会播）。
            withFrameNanos { }
            expanded = defaultOpen
        }
    }
    val open = active || expanded // 思考进行中始终展开；完成后由用户控制
    MetaBlockCard(modifier = Modifier.padding(bottom = 2.dp)) {
        DisclosureRow(onClick = { expanded = !expanded }, enabled = !active, expanded = open) {
            if (active) {
                // ThinkingIndicator 的默认 label 是没过 tr() 的中文，这里显式传 tr() 的（那个默认值在别的文件里，动不了）
                ThinkingIndicator(label = tr("思考中"))
            } else {
                Icon(Icons.Outlined.Psychology, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(tr("已思考"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            }
        }
        // #8 展开/收起都走动画（AnimatedVisibility）：收起不再“直接消失”；
        // 流式时正文用 animateContentSize 平滑增高，恢复“手绘/思考中”文本的生长动画。
        AnimatedVisibility(
            visible = open && text.isNotBlank(),
            enter = toolExpandEnter,   // 与工具卡共用同一组进出场，展开收起曲线一致
            exit = toolExpandExit,
        ) {
            SelectionContainer {
                Text(
                    text,
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(start = 12.dp, end = 12.dp, bottom = 10.dp)
                        .animateContentSize(animationSpec = tween(180, easing = FastOutSlowInEasing)),
                )
            }
        }
    }
}

// ============================================================
// 工具调用 / 工具结果 卡片
// ============================================================
// 手表屏太小，一屏塞不下几千字的返回：内联详情只铺前 N 字，剩下的靠复制按钮拿全文。
// 定这个数而不是无限滚：滚动条在圆屏上本来就难点，滚几十屏找不到头才是真的看不清。
private const val TOOL_INLINE_TEXT_LIMIT = 2000

/** 耗时的人话。给用户看的是「快不快」，不是让他心算 87340ms 是几分钟。 */
private fun toolDurationText(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

private fun busStatusLabel(s: com.arix.tool.ToolActivityBus.Status): String = when (s) {
    com.arix.tool.ToolActivityBus.Status.OK -> tr("成功")
    com.arix.tool.ToolActivityBus.Status.ERROR -> tr("失败")
    com.arix.tool.ToolActivityBus.Status.DENIED -> tr("被拒")
    com.arix.tool.ToolActivityBus.Status.CANCELLED -> tr("已取消")
    com.arix.tool.ToolActivityBus.Status.RUNNING -> tr("进行中")
}

/**
 * 展开态里「请求方 · 状态 · 耗时」那一行。
 *
 * 数据来自行为流总线，而总线是**内存态**：历史对话重新载入后这条记录早被环挤掉/进程重启没了。
 * 所以查不到时整行不画——用户看到的只是「这里没有额外信息」，而不是一句「加载失败」；
 * 那条记录本来就不该是消息的一部分，缺了不算错。
 */
@Composable
private fun ToolMetaLine(entry: com.arix.tool.ToolActivityBus.Entry?, modifier: Modifier = Modifier) {
    val e = entry ?: return
    val scheme = MaterialTheme.colorScheme
    val parts = buildList {
        add(e.callerLabel)
        add(busStatusLabel(e.status))
        if (!e.isRunning) add(toolDurationText(e.durationMs))   // 进行中没有终值，别显示 0ms 骗人
    }
    Text(
        parts.joinToString(" · "),
        color = scheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/**
 * 工具卡展开后的一块原文（参数 / 返回），右上角一键复制。
 *
 * 没去复用 ToolActivityPanel 里的同名块：那个是文件私有的，且面板是全屏、这里是聊天流里
 * 一张窄卡片，限高和截断的尺度本就不同——各写各的比为了「不重复」硬抽个公共组件更省事。
 */
@Composable
private fun ToolDetailBlock(title: String, body: String, onOpenFull: (() -> Unit)? = null) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    // 截断只影响显示，复制永远给全文——用户真要细看是把它贴到别处看，不是在表盘上读完
    val shown = remember(body) { body.take(TOOL_INLINE_TEXT_LIMIT) }
    val rest = body.length - shown.length
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = scheme.primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            // 「全文」入口：卡片里只铺得下前 N 字，真要通读还是得给一个全屏的地方（原来的结果弹窗）
            if (onOpenFull != null) {
                IconButton(onClick = onOpenFull, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Outlined.OpenInFull, contentDescription = tr("查看全文"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                }
            }
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(body)) },
                modifier = Modifier.size(24.dp),
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = tr("复制"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            }
        }
        Box(
            Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(scheme.surfaceContainerHighest)
                // 上限而非定高：短内容不撑出空白，长内容才滚
                .heightIn(max = 180.dp)
                .verticalScroll(rememberScrollState())
                .padding(8.dp),
        ) {
            SelectionContainer {
                Text(shown, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (rest > 0) {
            Text(
                tr("还有 %s 字未显示，可复制查看全文").format(rest.toString()),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/**
 * 展开区统一进出场。
 *
 * 高度用**临界阻尼弹簧**（DampingRatioNoBouncy = 不过冲）而不是 tween：tween 的 FastOutSlowIn 收尾是
 * 硬截断，弹簧是自然减速到停，展开收起都更「顺」——这正是用户报的「曲线太丑」。
 *
 * ⚠ 之所以以前显得丑，真凶不只在曲线：MetaBlockCard 那层还套了一个 animateContentSize，和这里的
 * expandVertically **两层尺寸动画在同一次展开里对着追**（一个逐帧上报高度、另一个又对这个动目标做 tween），
 * 于是回弹/橡皮筋。现在卡片那层已去掉，尺寸只由 expandVertically 一条曲线驱动，卡片按子高度直接跟随。
 * enter/exit 共用同一条弹簧曲线，对称才不会「开得软、收得硬」。 */
private val expandSizeSpec = spring<IntSize>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = Spring.StiffnessMediumLow,
)
private val toolExpandEnter = expandVertically(expandSizeSpec) + fadeIn(tween(200, easing = FastOutSlowInEasing))
private val toolExpandExit = shrinkVertically(expandSizeSpec) + fadeOut(tween(140, easing = FastOutSlowInEasing))

// 工具调用：扳手图标 + "调用 名称" + 展开完整参数。展开逻辑（每个 call 按 id 各记各的）没动。
@Composable fun ToolCallCard(calls: List<ChatMessage.ToolCallMsg>) {
    val scheme = MaterialTheme.colorScheme
    Column(
        // 尺寸动画交给里面每张 MetaBlockCard（避免这层与卡片双重 animateContentSize 叠加、动画打架）
        modifier = Modifier.widthIn(max = 320.dp).padding(bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),   // 原来是手写的 if (i > 0) Spacer(2.dp)，等价
    ) {
        calls.forEach { c ->
            var expanded by remember(c.id) { mutableStateOf(false) }
            val prettyArgs = remember(c.arguments) {
                try { org.json.JSONObject(c.arguments).toString(2) } catch (_: Exception) { c.arguments }
            }
            // 每次展开时重查：工具可能刚开始跑（此时只有 RUNNING），跑完后再展开要能看到终态与耗时
            val entry = remember(c.id, expanded) { com.arix.tool.ToolActivityBus.byCallId(c.id) }
            MetaBlockCard {
                DisclosureRow(onClick = { expanded = !expanded }, expanded = expanded) {
                    Icon(Icons.Outlined.Build, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    // 原文是 "调用 ${c.name}" ——整串没过 tr()，33 种语言下都念中文。补上。
                    Text(tr("调用 %s").format(c.name), color = scheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                }
                AnimatedVisibility(visible = expanded, enter = toolExpandEnter, exit = toolExpandExit) {
                    Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                        ToolMetaLine(entry)
                        ToolDetailBlock(tr("参数"), prettyArgs)
                    }
                }
            }
        }
    }
}

// 工具结果：收起时仍是一行「成功/失败 + 摘要」，展开后把这一步的**全部**摊开
// （请求方 / 状态 / 耗时 / 完整参数 / 完整返回）——AI 每一步都能查证，才不是黑箱。
@Composable fun ToolResultCard(result: String, toolCallId: String? = null) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember(toolCallId) { mutableStateOf(false) }
    // 详情：从行为流总线按 tool_call id 回查（内存态，重启/翻旧对话查不到 → 那部分不画，不是错误）。
    // 不塞进 ChatBubble/DB：耗时、请求方是「这次运行」的观察值，没必要为它改数据模型和存储。
    // 依赖 expanded 重查：工具可能是展开着跑完的，收合再展开要拿到终态而不是当初那份 RUNNING。
    val entry = remember(toolCallId, expanded) { toolCallId?.let { com.arix.tool.ToolActivityBus.byCallId(it) } }
    val durationMs = entry?.takeIf { !it.isRunning }?.durationMs
    // 成败优先取行为流里的**真状态**（工具返回时就记下了 isError），字符串匹配只作兜底。
    // ⚠ 兜底里这些中文是**工具返回文本的前缀**，不是界面文字——绝不能 tr()，翻了就匹配不上、
    // 失败会被当成功。这也正是不能只靠字符串判成败的原因：哪天工具那侧的文案被翻译或改写，
    // 这里就会静默地把失败显示成成功，而没有任何编译错误提醒。
    val isError = entry?.let { it.status == com.arix.tool.ToolActivityBus.Status.ERROR }
        ?: (result.startsWith("工具执行异常") || result.startsWith("工具未找到") ||
            result.contains("isError") || result.startsWith("错误") || result.startsWith("[Operit沙盒]"))
    val markColor = if (isError) scheme.error else scheme.primary
    val preview = remember(result) { result.replace("\n", " ").trim().take(60) }
    var showDetail by remember { mutableStateOf(false) }
    MetaBlockCard(modifier = Modifier.padding(bottom = 2.dp)) {
        // 只让标题行可点、不让整卡可点：展开区里是 SelectionContainer，整块可点会跟长按选字抢手势
        // （DisclosureRow 的既有取舍）。传 expanded 即由它画随状态翻转的 chevron，用户一眼知道这行能展开。
        DisclosureRow(onClick = { expanded = !expanded }, expanded = expanded) {
            Icon(Icons.Outlined.SubdirectoryArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(4.dp))
            // 原来成功/失败用的是 Check / Close。Close 在 M3 里的语义是「关闭/移除」，拿它当失败标记是图标误用
            // ——换成 CheckCircle / ErrorOutline（这俩本来就在 import 里躺着没人用）。
            Icon(if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, contentDescription = null, tint = markColor, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (isError) tr("执行失败") else tr("执行成功"), color = markColor, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.width(6.dp))
            // 摘要是一行预览（不是说明文字），省略是对的；weight 让耗时永远贴在右边不被挤掉
            Text(
                preview.ifBlank { tr("(无输出)") }, color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (durationMs != null) {
                Spacer(Modifier.width(6.dp))
                Text(toolDurationText(durationMs), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1)
            }
        }
        AnimatedVisibility(visible = expanded, enter = toolExpandEnter, exit = toolExpandExit) {
            Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp)) {
                ToolMetaLine(entry)
                // 参数只有总线里有（消息里存的是返回）；总线没了就不画这块
                val args = entry?.argsJson?.ifBlank { entry.argsPreview }.orEmpty()
                if (args.isNotBlank()) ToolDetailBlock(tr("参数"), args)
                // 返回以消息本体为准而不是总线：消息是落盘的全文，总线那份被截过、还可能已经不在了
                ToolDetailBlock(tr("返回"), result.ifBlank { tr("(无输出)") }, onOpenFull = { showDetail = true })
            }
        }
    }
    if (showDetail) ToolResultFullDialog(result, isError, markColor) { showDetail = false }
}

/** 工具结果全文弹窗（玻璃卡与时间线卡两种样式共用）。 */
@Composable
private fun ToolResultFullDialog(result: String, isError: Boolean, markColor: Color, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        // M3 的 AlertDialog 自带 icon 槽——原来是把图标塞进 title 里手搭一个 Row，那不是 M3 的排法
        icon = { Icon(if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle, contentDescription = null) },
        title = { Text(if (isError) tr("工具执行失败") else tr("工具执行结果")) },
        text = {
            // heightIn(max=) 不是定高：结果短时不撑出空白，长时才滚
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                SelectionContainer { Text(result.ifBlank { tr("(空)") }, style = MaterialTheme.typography.bodyMedium) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(tr("关闭")) } },
        dismissButton = {
            // 行为照旧：这颗只复制、不关弹窗
            TextButton(onClick = { clipboard.setText(AnnotatedString(result)) }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, modifier = Modifier.size(ButtonDefaults.IconSize))
                Spacer(Modifier.width(ButtonDefaults.IconSpacing))
                Text(tr("复制"))
            }
        },
        containerColor = scheme.surface,
        iconContentColor = markColor,
        // 标题/正文/按钮的文字色交给 AlertDialog 的默认令牌（onSurface / onSurfaceVariant / primary），
        // 不再逐个手写 color= —— 少一处能跑偏的地方
        shape = MaterialTheme.shapes.extraLarge,
    )
}

// ============================================================
// 时间线卡（TIMELINE 样式）—— 相邻的思考 + 工具步骤合并进一张淡色卡 + 竖直时间线，非气泡。
// 由 ThemeConfig.metaBlockStyle == TIMELINE 时启用；GLASS 时走上面各自一张 MetaBlockCard 的旧样式。
// ============================================================

/** 时间线卡容器：一张淡色卡（沿用玻璃）+ 左侧一条竖直时间线，装若干 [ChainStep]。 */
@Composable
private fun ChainTimelineCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = MaterialTheme.shapes.medium
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
        Column(
            modifier = modifier.widthIn(max = 320.dp).padding(bottom = 2.dp).clip(shape)
                .chatGlassCutout(shape, MaterialTheme.colorScheme.surfaceContainerHigh)
                // 竖直时间线：x=20dp（正对每步图标圆心：卡内边距10 + 图标半径10）；上下各留 16dp。
                // 画在玻璃之上、内容之下——步骤图标那颗不透明小圆会盖住穿过它的线。
                .drawBehind {
                    val x = 20.dp.toPx()
                    val top = 16.dp.toPx(); val bot = size.height - 16.dp.toPx()
                    if (bot > top) drawLine(color = lineColor, start = Offset(x, top), end = Offset(x, bot), strokeWidth = 1.dp.toPx())
                }
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

/** 时间线上的一步：图标（关进不透明小圆盖住时间线）+ 标题行 + 展开箭头；展开区缩进对齐标题。 */
@Composable
private fun ChainStep(
    icon: ImageVector,
    iconTint: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    enabled: Boolean = true,
    title: @Composable RowScope.() -> Unit,
    expandedContent: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth()) {
        DisclosureRow(onClick = onToggle, enabled = enabled, expanded = expanded) {
            Box(Modifier.size(20.dp).clip(CircleShape).background(scheme.surfaceContainerHigh), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(13.dp))
            }
            Spacer(Modifier.width(8.dp))
            title()
        }
        AnimatedVisibility(visible = expanded, enter = toolExpandEnter, exit = toolExpandExit) {
            Column(Modifier.padding(start = 38.dp, end = 10.dp, bottom = 8.dp), content = expandedContent)
        }
    }
}

/** 思考步骤（时间线版 ReasoningBlock）。 */
@Composable
private fun ReasoningStep(text: String, active: Boolean, startOpen: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val defaultOpen = !LocalChatDisplay.current.reasoningCollapsed   // 与 ReasoningBlock 同一个开关，两种呈现风格不能有两套脾气
    // 同 ReasoningBlock：刚结束流式的新气泡先以展开态组合一帧，再动画收起（见 startOpen 注释）。
    var expanded by remember(defaultOpen) { mutableStateOf(defaultOpen || startOpen) }
    LaunchedEffect(startOpen, defaultOpen) {
        if (startOpen && !defaultOpen) {
            withFrameNanos { }
            expanded = defaultOpen
        }
    }
    val open = active || expanded   // 进行中始终展开，且标题不可点（与 ReasoningBlock 一致）
    ChainStep(
        icon = Icons.Outlined.Psychology, iconTint = scheme.secondary,
        expanded = open, enabled = !active, onToggle = { expanded = !expanded },
        title = {
            if (active) ThinkingIndicator(label = tr("思考中"))
            else Text(tr("已思考"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
        },
        expandedContent = {
            if (text.isNotBlank()) Text(text, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        },
    )
}

/** 工具调用步骤（时间线版 ToolCallCard 的单条）。 */
@Composable
private fun ToolCallStep(c: ChatMessage.ToolCallMsg) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember(c.id) { mutableStateOf(false) }
    val prettyArgs = remember(c.arguments) { try { org.json.JSONObject(c.arguments).toString(2) } catch (_: Exception) { c.arguments } }
    val entry = remember(c.id, expanded) { com.arix.tool.ToolActivityBus.byCallId(c.id) }
    ChainStep(
        icon = Icons.Outlined.Build, iconTint = scheme.primary,
        expanded = expanded, onToggle = { expanded = !expanded },
        title = { Text(tr("调用 %s").format(c.name), color = scheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) },
        expandedContent = {
            ToolMetaLine(entry)
            ToolDetailBlock(tr("参数"), prettyArgs)
        },
    )
}

/** 工具结果（时间线版）：因结果是独立的 tool 气泡（LazyColumn 单独 item），单独一张单步时间线卡。 */
@Composable
fun ToolResultTimeline(result: String, toolCallId: String? = null) {
    val scheme = MaterialTheme.colorScheme
    var expanded by remember(toolCallId) { mutableStateOf(false) }
    var showDetail by remember { mutableStateOf(false) }
    val entry = remember(toolCallId, expanded) { toolCallId?.let { com.arix.tool.ToolActivityBus.byCallId(it) } }
    val durationMs = entry?.takeIf { !it.isRunning }?.durationMs
    val isError = entry?.let { it.status == com.arix.tool.ToolActivityBus.Status.ERROR }
        ?: (result.startsWith("工具执行异常") || result.startsWith("工具未找到") ||
            result.contains("isError") || result.startsWith("错误") || result.startsWith("[Operit沙盒]"))
    val markColor = if (isError) scheme.error else scheme.primary
    val preview = remember(result) { result.replace("\n", " ").trim().take(60) }
    ChainTimelineCard {
        ChainStep(
            icon = if (isError) Icons.Outlined.ErrorOutline else Icons.Outlined.CheckCircle,
            iconTint = markColor, expanded = expanded, onToggle = { expanded = !expanded },
            title = {
                Text(if (isError) tr("执行失败") else tr("执行成功"), color = markColor, style = MaterialTheme.typography.labelLarge)
                Spacer(Modifier.width(6.dp))
                Text(preview.ifBlank { tr("(无输出)") }, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (durationMs != null) { Spacer(Modifier.width(6.dp)); Text(toolDurationText(durationMs), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            },
            expandedContent = {
                ToolMetaLine(entry)
                val args = entry?.argsJson?.ifBlank { entry.argsPreview }.orEmpty()
                if (args.isNotBlank()) ToolDetailBlock(tr("参数"), args)
                ToolDetailBlock(tr("返回"), result.ifBlank { tr("(无输出)") }, onOpenFull = { showDetail = true })
            },
        )
    }
    if (showDetail) ToolResultFullDialog(result, isError, markColor) { showDetail = false }
}
