package com.arix.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/**
 * 聊天外观自定义 —— 气泡形状/尖角/配色、头像大小与圆角、是否显示头像与名字，**用户侧和 AI 侧各调各的**。
 *
 * 为什么两边分开存：用户消息和 AI 消息在视觉上承担的角色不同（一个是自己说的话、一个是对方的），
 * 主流 IM 也几乎都做成两边不同款。做成一套设置再"应用到两边"反而会逼用户在两种需求间二选一。
 *
 * 颜色用 [ARGB_UNSET] 表示「跟随主题」而不是存一个具体色值：主题会随明暗/取色方案变，
 * 存死了会在切主题后变成一块突兀的硬色块。只有用户明确挑了颜色才落一个真实 ARGB。
 */
object ChatAppearancePrefs {

    private const val PREF = "xtom_chat_appearance"

    /** 颜色未设置的哨兵值（Color.Unspecified 落不了盘，用一个不可能的 ARGB 表示"跟随主题"）。 */
    const val ARGB_UNSET = 0

    /** 气泡尖角样式。 */
    enum class Tail {
        /** 不画尖角，纯圆角矩形（当前行为）。 */
        NONE,

        /**
         * Telegram 式：尖角与气泡**同色连体**，长在贴近头像那一侧的下角，
         * 像气泡自己伸出来的一只脚。视觉上更整体。
         */
        MERGED,

        /**
         * 微信式：一个独立的小三角，从气泡侧边**中部或上部**指向头像，
         * 与气泡之间有明确的"箭头"感。指向性更强。
         */
        ARROW,
    }

    /** 气泡靠哪边。AUTO = 用户靠右、AI 靠左（当前行为）；LEFT/RIGHT = 强制该侧。尖角与头像都跟着这个方向走。 */
    enum class Align { AUTO, LEFT, RIGHT }

    /** 尖角在气泡侧边的**上下位置**。默认 TOP：头像无论在上方还是旁边(顶部对齐)都在上，尖角朝上才对着头像。 */
    enum class TailAnchor { TOP, MIDDLE, BOTTOM }

    /** 一侧（用户 或 AI）的全部外观参数。 */
    data class SideStyle(
        /** 气泡圆角（dp）。0 = 全直角。 */
        val bubbleCornerDp: Int,
        /** 尖角样式。 */
        val tail: Tail,
        /** 气泡底色 ARGB，[ARGB_UNSET] = 跟随主题。 */
        val bubbleArgb: Int,
        /** 气泡文字色 ARGB，[ARGB_UNSET] = 跟随主题（按底色自动取可读色）。 */
        val textArgb: Int,
        /** 头像边长（dp）。 */
        val avatarSizeDp: Int,
        /** 头像圆角（dp）。取值 ≥ 边长的一半即为正圆。 */
        val avatarCornerDp: Int,
        /** 是否显示头像。 */
        val showAvatar: Boolean,
        /** 是否显示名字。 */
        val showName: Boolean,
        /** 头像放在气泡**旁边**（微信式，尖角正对头像）而不是上方（Telegram 式，默认）。 */
        val avatarBeside: Boolean,
        /** 气泡靠哪边。 */
        val align: Align,
        /** 尖角上下位置。 */
        val tailAnchor: TailAnchor,
    ) {
        /** 颜色便捷读取：未设置时返回 null，让调用方回落到主题令牌。 */
        val bubbleColor: Color? get() = bubbleArgb.takeIf { it != ARGB_UNSET }?.let { Color(it) }
        val textColor: Color? get() = textArgb.takeIf { it != ARGB_UNSET }?.let { Color(it) }
    }

    /** 圆角的「跟随主题」哨兵。见 [DEFAULT_USER] 的说明。 */
    const val CORNER_FOLLOW_THEME = -1

    /**
     * 默认值 = **当前线上观感**，一个像素都不要变。
     * 用户没动过设置就升级到这个版本时，界面必须和升级前一模一样——
     * 新增自定义能力不该顺手改掉所有人的既有观感。
     *
     * 以下数值是从现有渲染代码里**实测量出来**的（初稿凭印象填的四项全错，已纠正）：
     * - 圆角走主题 `shapes.large`，而它**随「形状档位」设置在 14/20/24dp 之间浮动**，
     *   根本没有一个能写死的定值 → 用 [CORNER_FOLLOW_THEME] 哨兵表示「跟随主题」。
     *   写死成某个数字的话，升级当天所有人的气泡圆角都会被钉住。
     * - 头像实际是 **30dp**（`Avatar()` 的形参默认值 28dp 是误导，气泡调用点传的是 30），正圆即 15dp。
     * - 现网是 Telegram 式布局，头像+名字在气泡**上方**一行，**用户侧和 AI 侧都显示**。
     */
    val DEFAULT_USER = SideStyle(
        bubbleCornerDp = CORNER_FOLLOW_THEME, tail = Tail.NONE, bubbleArgb = ARGB_UNSET, textArgb = ARGB_UNSET,
        avatarSizeDp = 30, avatarCornerDp = 15, showAvatar = true, showName = true,
        avatarBeside = false, align = Align.AUTO, tailAnchor = TailAnchor.TOP,
    )
    val DEFAULT_AI = DEFAULT_USER

    private fun p(c: Context) = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
    private fun key(user: Boolean, name: String) = (if (user) "user_" else "ai_") + name

    fun style(c: Context, user: Boolean): SideStyle {
        val d = if (user) DEFAULT_USER else DEFAULT_AI
        val sp = p(c)
        return SideStyle(
            bubbleCornerDp = sp.getInt(key(user, "bubble_corner"), d.bubbleCornerDp),
            tail = runCatching { Tail.valueOf(sp.getString(key(user, "tail"), d.tail.name) ?: d.tail.name) }
                .getOrDefault(d.tail),
            bubbleArgb = sp.getInt(key(user, "bubble_argb"), d.bubbleArgb),
            textArgb = sp.getInt(key(user, "text_argb"), d.textArgb),
            avatarSizeDp = sp.getInt(key(user, "avatar_size"), d.avatarSizeDp),
            avatarCornerDp = sp.getInt(key(user, "avatar_corner"), d.avatarCornerDp),
            showAvatar = sp.getBoolean(key(user, "show_avatar"), d.showAvatar),
            showName = sp.getBoolean(key(user, "show_name"), d.showName),
            avatarBeside = sp.getBoolean(key(user, "avatar_beside"), d.avatarBeside),
            align = runCatching { Align.valueOf(sp.getString(key(user, "align"), d.align.name) ?: d.align.name) }.getOrDefault(d.align),
            tailAnchor = runCatching { TailAnchor.valueOf(sp.getString(key(user, "tail_anchor"), d.tailAnchor.name) ?: d.tailAnchor.name) }.getOrDefault(d.tailAnchor),
        )
    }

    fun save(c: Context, user: Boolean, s: SideStyle) {
        p(c).edit()
            .putInt(key(user, "bubble_corner"), s.bubbleCornerDp)
            .putString(key(user, "tail"), s.tail.name)
            .putInt(key(user, "bubble_argb"), s.bubbleArgb)
            .putInt(key(user, "text_argb"), s.textArgb)
            .putInt(key(user, "avatar_size"), s.avatarSizeDp)
            .putInt(key(user, "avatar_corner"), s.avatarCornerDp)
            .putBoolean(key(user, "show_avatar"), s.showAvatar)
            .putBoolean(key(user, "show_name"), s.showName)
            .putBoolean(key(user, "avatar_beside"), s.avatarBeside)
            .putString(key(user, "align"), s.align.name)
            .putString(key(user, "tail_anchor"), s.tailAnchor.name)
            .apply()
    }

    fun reset(c: Context) = p(c).edit().clear().apply()

    /** 取值范围（设置页滑杆与渲染侧共用，避免两边各写一套上下限对不上）。 */
    const val CORNER_MIN = 0
    const val CORNER_MAX = 28
    const val AVATAR_MIN = 20
    const val AVATAR_MAX = 56

    /** 气泡不透明度的上下限（百分比）。下限不给到 0：全透明就成了「没有气泡」，那是别的需求，不该靠滑杆滑出来。 */
    const val ALPHA_MIN_PCT = 10
    const val ALPHA_MAX_PCT = 100

    // ------------------------------------------------------------
    // 显示细节开关（见 [ChatDisplayOptions]）
    // ------------------------------------------------------------

    private const val DISPLAY_PREF = "xtom_chat_display"
    private fun dsp(c: Context) = c.getSharedPreferences(DISPLAY_PREF, Context.MODE_PRIVATE)

    fun display(c: Context): ChatDisplayOptions {
        val d = ChatDisplayOptions.LEGACY
        val sp = dsp(c)
        return ChatDisplayOptions(
            userBubbleAlphaPct = sp.getInt("user_bubble_alpha", d.userBubbleAlphaPct),
            aiBubbleAlphaPct = sp.getInt("ai_bubble_alpha", d.aiBubbleAlphaPct),
            showModelName = sp.getBoolean("show_model_name", d.showModelName),
            showTokenUsage = sp.getBoolean("show_token_usage", d.showTokenUsage),
            reasoningCollapsed = sp.getBoolean("reasoning_collapsed", d.reasoningCollapsed),
            codeWrap = sp.getBoolean("code_wrap", d.codeWrap),
            codeAutoCollapse = sp.getBoolean("code_auto_collapse", d.codeAutoCollapse),
            codeLineNumbers = sp.getBoolean("code_line_numbers", d.codeLineNumbers),
            renderLatex = sp.getBoolean("render_latex", d.renderLatex),
        )
    }

    fun saveDisplay(c: Context, d: ChatDisplayOptions) {
        dsp(c).edit()
            .putInt("user_bubble_alpha", d.userBubbleAlphaPct)
            .putInt("ai_bubble_alpha", d.aiBubbleAlphaPct)
            .putBoolean("show_model_name", d.showModelName)
            .putBoolean("show_token_usage", d.showTokenUsage)
            .putBoolean("reasoning_collapsed", d.reasoningCollapsed)
            .putBoolean("code_wrap", d.codeWrap)
            .putBoolean("code_auto_collapse", d.codeAutoCollapse)
            .putBoolean("code_line_numbers", d.codeLineNumbers)
            .putBoolean("render_latex", d.renderLatex)
            .apply()
    }

    fun resetDisplay(c: Context) = dsp(c).edit().clear().apply()
}

// ============================================================
// 显示细节开关 —— 契约、存取、下发全部钉死在这个文件里
// ------------------------------------------------------------
// 为什么放这里而不是各用各的：本项目栽过一次「两个人各建一份同名 ChatAppearance / LocalChatAppearance，
// 包名不同所以编译全过，运行时却是设置页 provide 了 A、气泡读的是 B」的坑。类型 + CompositionLocal +
// 读盘函数写在同一个文件，就没有「顺手再建一个」的空间。
// ============================================================

/**
 * 聊天页的**显示细节**开关：不属于「一侧气泡长什么样」，而是「每条消息/每个代码块显示到什么程度」。
 *
 * 为什么不塞进 [ChatAppearancePrefs.SideStyle]：那套是两侧各调各的形状/配色，
 * 而这里多数项（代码块、公式、思考块）根本没有「用户侧 / AI 侧」之分，硬塞进去会逼出两份永远同步的配置。
 * 唯独不透明度天然分两侧，就在这里存两个字段（`userBubbleAlphaPct` / `aiBubbleAlphaPct`）。
 *
 * 不透明度存**百分比整数**而不是 0..1 的 Float：设置页的 SettingsSlider 走的是「整数 + 单位」那套
 * （数值框 roundToInt），Float 到那里会被四舍五入成 0 或 1，滑杆等于废掉。
 */
@Immutable
data class ChatDisplayOptions(
    /** 用户侧气泡不透明度（百分比 [ChatAppearancePrefs.ALPHA_MIN_PCT]..100）。配了聊天背景图才看得出效果。 */
    val userBubbleAlphaPct: Int,
    /** AI 侧气泡不透明度（百分比）。 */
    val aiBubbleAlphaPct: Int,
    /** 每条 AI 消息的元信息行里显示产出它的模型名。 */
    val showModelName: Boolean,
    /** 每条 AI 消息的元信息行里显示 token 用量。 */
    val showTokenUsage: Boolean,
    /** 思考块默认折叠（关 = 默认展开）。 */
    val reasoningCollapsed: Boolean,
    /** 代码块自动换行（关 = 横向滚动，长行不折）。 */
    val codeWrap: Boolean,
    /** 长代码块（>14 行）默认折叠起来。 */
    val codeAutoCollapse: Boolean,
    /** 代码块左侧显示行号。 */
    val codeLineNumbers: Boolean,
    /** 渲染 LaTeX 公式（关 = 原样显示 `$...$` 源码）。 */
    val renderLatex: Boolean,
) {
    /** 该侧的不透明度（0..1）。1 = 完全不透明，也就是「没开这个功能」的那条快路径。 */
    fun bubbleAlpha(isUser: Boolean): Float =
        (if (isUser) userBubbleAlphaPct else aiBubbleAlphaPct)
            .coerceIn(ChatAppearancePrefs.ALPHA_MIN_PCT, ChatAppearancePrefs.ALPHA_MAX_PCT) / 100f

    companion object {
        /**
         * 默认值 = **当前线上观感**，一个像素都不要变（与 [ChatAppearancePrefs.DEFAULT_USER] 同一条规矩）。
         * 逐项都是从现有渲染代码里量出来的：
         * - 气泡不透明：现网没有 alpha，取色是 `0xFF` 打头的实色 → 100%。
         * - 模型名：元信息行现在只有耗时/token/费用/t·s，**没有**模型名 → 关。
         * - token 用量：现在是无条件显示的 → 开。
         * - 思考块：`ReasoningBlock` 的 `expanded` 初值就是 false，本来就折叠 → 开（保持折叠）。
         * - 代码块：`CodeBlock` 现在横向滚动、不自动折叠、无行号 → 三项全关。
         * - 公式：`MdBlock.Math` 现在直接走 JLaTeXMath → 开。
         */
        val LEGACY = ChatDisplayOptions(
            userBubbleAlphaPct = 100,
            aiBubbleAlphaPct = 100,
            showModelName = false,
            showTokenUsage = true,
            reasoningCollapsed = true,
            codeWrap = false,
            codeAutoCollapse = false,
            codeLineNumbers = false,
            renderLatex = true,
        )
    }
}

/**
 * 和 `LocalChatAppearance` 同一条规矩：气泡/代码块是 LazyColumn 里最高频重组的东西，
 * 绝不能在里面调 [ChatAppearancePrefs.display]（读 SharedPreferences 会直接卡住列表）。
 * 只在聊天页顶层读一次，往下用这个 static local 发。
 *
 * 默认值给 LEGACY：任何忘了 provide 的入口（分享成图、唤醒页、其它页面里的 MarkdownText…）
 * 都还是旧观感，不会因为漏接线而悄悄变样。
 */
val LocalChatDisplay = staticCompositionLocalOf { ChatDisplayOptions.LEGACY }

/**
 * 从 prefs 读一次。**只在聊天页顶层调用一次**，往下用 [LocalChatDisplay] 发。
 *
 * @param revision 设置改动后递增它即可强制重读（聊天页是常驻 composition，从设置页回来不会重建，
 *                 不重读就一直是旧的——和 `rememberChatAppearance` 同一个坑，同一个解法）。
 */
@Composable
fun rememberChatDisplay(revision: Any? = Unit): ChatDisplayOptions {
    val ctx = LocalContext.current
    return remember(revision) { ChatAppearancePrefs.display(ctx) }
}
