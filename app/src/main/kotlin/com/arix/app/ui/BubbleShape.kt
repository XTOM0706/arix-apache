package com.arix.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.arix.app.ChatAppearancePrefs
import com.arix.app.theme.LocalThemeConfig
import com.arix.app.theme.spacing

// ============================================================
// 聊天气泡外观 —— 形状 / 配色 / 头像参数的**渲染侧**
// 配置契约见 ChatAppearancePrefs（那边只管存取，这边只管画）。
// ============================================================

/**
 * 一侧气泡的**已解析**外观。
 *
 * 和 [ChatAppearancePrefs.SideStyle] 分开两个类型，是因为那个是「盘上存了什么」（Int/ARGB/哨兵），
 * 这个是「这一帧要怎么画」（Dp/Color/null=跟随主题）。中间那层换算（读 SharedPreferences、
 * 把 ARGB 变 Color）只该在进入聊天页时做一次，不该混进每个气泡的重组里。
 */
@Immutable
data class ChatBubbleStyle(
    /** null = 跟随主题 `shapes.large`（旧观感），非 null = 用户指定的圆角。 */
    val cornerDp: Int?,
    val tail: ChatAppearancePrefs.Tail,
    /** null = 跟随主题令牌（用户侧 primaryContainer / AI 侧 surfaceContainerHigh）。 */
    val bubbleColor: Color?,
    /** null = 跟随主题；若 [bubbleColor] 被自定义了，则按其亮度自动取可读前景色。 */
    val textColor: Color?,
    val avatarSize: Dp,
    val avatarCorner: Dp,
    val showAvatar: Boolean,
    val showName: Boolean,
    val avatarBeside: Boolean,
    val align: ChatAppearancePrefs.Align,
    val tailAnchor: ChatAppearancePrefs.TailAnchor,
) {
    companion object {
        /**
         * 「一个像素都不变」的基线 = 现有代码里量出来的实际值：
         * 圆角走主题 shapes.large（不是 medium，也不是定值——它随「形状档位」设置在 14/20/24dp 之间变），
         * 头像 30dp 正圆，用户侧和 AI 侧**都**显示头像与名字（Telegram 式，头像在气泡上方一行）。
         */
        val LEGACY = ChatBubbleStyle(
            cornerDp = null,
            tail = ChatAppearancePrefs.Tail.NONE,
            bubbleColor = null,
            textColor = null,
            avatarSize = 30.dp,
            avatarCorner = 15.dp,
            showAvatar = true,
            showName = true,
            avatarBeside = false,
            align = ChatAppearancePrefs.Align.AUTO,
            tailAnchor = ChatAppearancePrefs.TailAnchor.TOP,
        )
    }
}

/** 两侧合起来的一份快照。整份不变 → 气泡子树可跳过重组。 */
@Immutable
data class ChatAppearance(
    val user: ChatBubbleStyle,
    val ai: ChatBubbleStyle,
) {
    fun side(isUser: Boolean): ChatBubbleStyle = if (isUser) user else ai

    companion object {
        val LEGACY = ChatAppearance(ChatBubbleStyle.LEGACY, ChatBubbleStyle.LEGACY)
    }
}

/**
 * 气泡是 LazyColumn 里最高频重组的东西，绝不能在里面读 SharedPreferences。
 * 用 static 版：这份快照只在用户改完设置回到聊天页时整体换一次，读它的地方不需要被追踪成状态订阅。
 *
 * 默认值给 LEGACY 而不是 prefs 默认值：任何忘了 provide 的入口（分享成图、唤醒页…）都还是旧观感，
 * 不会因为漏接线而悄悄变样。
 */
val LocalChatAppearance = staticCompositionLocalOf { ChatAppearance.LEGACY }

/**
 * 从 prefs 读一次并换算成渲染参数。**只在聊天页顶层调用一次**，往下用 [LocalChatAppearance] 发。
 *
 * @param revision 外观设置改动后递增它即可强制重读（回到聊天页时 +1）。不传就只在进入页面时读一次。
 */
@Composable
fun rememberChatAppearance(revision: Any? = Unit): ChatAppearance {
    val ctx = LocalContext.current
    return remember(revision) {
        ChatAppearance(
            user = resolveSide(ChatAppearancePrefs.style(ctx, user = true)),
            ai = resolveSide(ChatAppearancePrefs.style(ctx, user = false)),
        )
    }
}

/**
 * 存储值 → 渲染值。
 *
 * 圆角这一项要特别处理：现网气泡用的是主题 `shapes.large`，它**随「形状档位」设置在 14/20/24dp 之间浮动**，
 * 根本没有一个能写进 prefs 的定值。所以「用户没碰过这一侧的设置」时一律回落主题，
 * 只有他真的动过才认那个数字——否则升级当天所有人的气泡都会被钉死成一个固定圆角。
 * 负数同样表示「跟随主题」，方便配置侧把默认值改成 -1 让语义变显式。
 */
fun resolveSide(s: ChatAppearancePrefs.SideStyle): ChatBubbleStyle {
    // 只有圆角需要「跟随主题」这条退路（它在盘上没有对应的定值）。其余项一律直接读盘：
    // 早先那版是「整侧等于默认值才跟随主题」，结果用户只要改了任意一项（比如换个尖角），
    // 头像尺寸和显隐就会一起跳变——一个开关牵动一堆无关外观，是很难自查的坑。
    // 现在 prefs 的默认值本身就等于现网实际观感，直接读就是对的。
    return ChatBubbleStyle(
        cornerDp = s.bubbleCornerDp.takeIf { it >= 0 }
            ?.coerceIn(ChatAppearancePrefs.CORNER_MIN, ChatAppearancePrefs.CORNER_MAX),
        tail = s.tail,
        bubbleColor = s.bubbleColor,
        textColor = s.textColor,
        avatarSize = s.avatarSizeDp.coerceIn(ChatAppearancePrefs.AVATAR_MIN, ChatAppearancePrefs.AVATAR_MAX).dp,
        avatarCorner = s.avatarCornerDp.coerceAtLeast(0).dp,
        showAvatar = s.showAvatar,
        showName = s.showName,
        avatarBeside = s.avatarBeside,
        align = s.align,
        tailAnchor = s.tailAnchor,
    )
}

// ------------------------------------------------------------
// 自定义气泡形状
// ------------------------------------------------------------

/**
 * 带尖角的气泡形状。
 *
 * 尖角走 [Shape] 而不是「气泡旁边再叠一个三角 Box」：气泡底色是 [glassSurface] 画的
 * （开玻璃时按自身在窗口里的位置采样预模糊背景），叠出来的三角拿不到同一份采样，
 * 会在玻璃气泡边上糊一块死板的纯色。做进 Outline 里，底色/裁剪/阴影就天然是一整块。
 * ARROW 也因此用 Shape 而非独立三角——理由同上，两种尖角共用一套路径构造反而更短。
 *
 * @param corner 圆角
 * @param tail 尖角样式
 * @param onRight 尖角在右（用户侧）还是在左（AI 侧）；RTL 下自动镜像
 * @param tailW 尖角伸出气泡主体的宽度
 * @param tailH 尖角的高度
 */
@Immutable
data class XtomBubbleShape(
    val corner: Dp,
    val tail: ChatAppearancePrefs.Tail,
    val onRight: Boolean,
    val tailAnchor: ChatAppearancePrefs.TailAnchor = ChatAppearancePrefs.TailAnchor.TOP,
    val tailW: Dp = 7.dp,
    val tailH: Dp = 10.dp,
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = with(density) { corner.toPx() }.coerceAtMost(minOf(size.width, size.height) / 2f)
        if (tail == ChatAppearancePrefs.Tail.NONE) {
            return Outline.Rounded(RoundRect(Rect(0f, 0f, size.width, size.height), CornerRadius(r)))
        }
        val tw = with(density) { tailW.toPx() }.coerceAtMost(size.width / 4f)
        val th = with(density) { tailH.toPx() }.coerceAtMost(size.height)
        // 阿拉伯语/希伯来语下整条消息行是镜像的，尖角得跟着翻，否则会指到屏幕外那侧
        val right = if (layoutDirection == LayoutDirection.Rtl) !onRight else onRight

        // 主体让出 tw：尖角是往外长的，不让位就会把气泡撑宽、文字被尖角压住
        val bodyL = if (right) 0f else tw
        val bodyR = if (right) size.width - tw else size.width

        val merged = tail == ChatAppearancePrefs.Tail.MERGED
        // MERGED 的脚是从气泡角上直接长出来的，那个角必须是直角——留着圆角会看出一道接缝
        val bottomTailR = if (merged) 0f else r
        val body = Path().apply {
            addRoundRect(
                RoundRect(
                    rect = Rect(bodyL, 0f, bodyR, size.height),
                    topLeft = CornerRadius(r),
                    topRight = CornerRadius(r),
                    bottomRight = CornerRadius(if (right) bottomTailR else r),
                    bottomLeft = CornerRadius(if (right) r else bottomTailR),
                )
            )
        }

        val edge = if (right) bodyR else bodyL          // 尖角贴的那条边
        val out = if (right) size.width else 0f          // 尖角最远端（伸到控件边界）
        val dir = if (right) 1f else -1f                 // 向外的方向
        val seam = 1.5f * dir                            // 与主体重叠一点点，避免 union 后留发丝缝

        val tailPath = Path().apply {
            if (merged) {
                // Telegram 式的「脚」：从底角起，外沿微凸出去到底边的尖，再从下方凹回来。
                // 凹回来的那一笔是关键——纯三角看着像被切了一刀，有内凹才像气泡自己流出来的。
                val top = size.height - th
                moveTo(edge - seam, top)
                quadraticTo(edge - seam, size.height - th * 0.18f, out, size.height)
                quadraticTo(edge + tw * 0.3f * dir, size.height, edge - seam, size.height)
                lineTo(edge - seam, top)
                close()
            } else {
                // 微信式：侧边一个小三角，尖端明确指向头像。上下位置由 tailAnchor 决定（默认 TOP 对着头像）。
                // 起点在圆角下方一点，免得三角根部啃掉圆角、看着像气泡缺了个口。
                val topBase = when (tailAnchor) {
                    ChatAppearancePrefs.TailAnchor.TOP -> r + th * 0.25f
                    ChatAppearancePrefs.TailAnchor.MIDDLE -> (size.height - th) / 2f
                    ChatAppearancePrefs.TailAnchor.BOTTOM -> size.height - th - r * 0.25f
                }
                val top = topBase.coerceIn(0f, (size.height - th).coerceAtLeast(0f))
                moveTo(edge - seam, top)
                lineTo(out, top + th * 0.45f)
                lineTo(edge - seam, top + th)
                close()
            }
        }

        // 用 op(Union) 而不是把两条子路径 addPath 到一起：子路径的绕向若不一致，
        // NonZero 填充会把重叠区判成空洞，气泡上会出现一块透明。
        val combined = Path().apply { op(body, tailPath, PathOperation.Union) }
        return Outline.Generic(combined)
    }
}

/**
 * 自定义外观版的气泡面。
 *
 * 没去改 [XtomBubbleSurface]：那个还被「分享为图片」等入口按旧签名调用，
 * 这里另起一个收 [ChatBubbleStyle] 的版本，两边互不影响。
 */
@Composable
fun XtomStyledBubbleSurface(
    isUser: Boolean,
    style: ChatBubbleStyle,
    modifier: Modifier = Modifier,
    onRight: Boolean = isUser,   // 尖角朝哪边（跟着「气泡位置」走，不再只看 isUser）
    content: @Composable () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val base = style.bubbleColor ?: if (isUser) scheme.primaryContainer else scheme.surfaceContainerHigh
    val fg = resolveBubbleTextColor(style, isUser)
    val shape = if (style.cornerDp == null && style.tail == ChatAppearancePrefs.Tail.NONE) {
        MaterialTheme.shapes.large    // 完全没自定义 → 走原来那条路，形状对象都是同一个
    } else {
        val corner = style.cornerDp?.dp ?: 24.dp
        remember(corner, style.tail, onRight, style.tailAnchor) { XtomBubbleShape(corner, style.tail, onRight = onRight, tailAnchor = style.tailAnchor) }
    }
    CompositionLocalProvider(LocalContentColor provides fg) {
        val pad = LocalThemeConfig.current.messageDensity.spacing().bubblePadding
        // 尖角占掉的那一条要额外补内边距，否则最靠边的字会压在尖角根部。补在尖角那一侧（onRight）。
        val tailPad = if (style.tail == ChatAppearancePrefs.Tail.NONE) 0.dp else 7.dp
        Box(
            modifier = modifier
                .widthIn(min = 40.dp, max = 320.dp)
                // 不再在这里 .clip(shape)：chatGlassCutout 内部已 clip 同一 shape（玻璃开时裁 drawImage），
                // 玻璃关/无壁纸走 background(base, shape) 画形状、正文在 padding 内不会溢出圆角/尖角 →
                // 外层这道 clip 是冗余的，尖角气泡还会 clipPath 裁两遍。删掉省一个 RenderNode/气泡。
                .chatGlassCutout(shape, base)
                .padding(
                    start = pad.dp + if (onRight) 0.dp else tailPad,
                    end = pad.dp + if (onRight) tailPad else 0.dp,
                    top = pad.dp,
                    bottom = pad.dp,
                ),
        ) { content() }
    }
}

/**
 * 气泡正文色。
 *
 * 用户只挑了底色、没挑字色时必须自己算一个能读的前景色：主题的 onPrimaryContainer 是配着
 * primaryContainer 调的，把底色换成任意一个亮黄，那套前景色就成了亮底浅字，一片看不清。
 * 这里的黑/白是**对比度兜底**，不是主题色——找不到任何令牌能保证与用户任选的颜色达标，
 * 所以这是文件里唯一允许出现的固定色值。
 */
@Composable
fun resolveBubbleTextColor(style: ChatBubbleStyle, isUser: Boolean): Color {
    val scheme = MaterialTheme.colorScheme
    style.textColor?.let { return it }
    val bg = style.bubbleColor ?: return if (isUser) scheme.onPrimaryContainer else scheme.onSurface
    // 0.5 是感知亮度的分界；不用纯黑纯白，稍收一点避免在 AMOLED 上刺眼
    return if (bg.luminance() > 0.5f) Color(0xFF1A1A1A) else Color(0xFFF2F2F2)
}
