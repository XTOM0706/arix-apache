package com.arix.app

import android.content.Context
import com.arix.app.theme.MessageDensity
import com.arix.app.theme.MetaBlockStyle
import com.arix.app.theme.ThemeConfigStore

/**
 * 聊天皮肤 —— 一键套一整套搭配，而不是逐项去调七八个滑杆。
 *
 * 为什么需要这层：[ChatAppearancePrefs] 把每个轴都开放出来了（圆角/尖角/头像/对齐/密度…），
 * 自由度是有了，可**大多数人不想当设计师**——他们想的是"给我弄成微信那样"或者"给我干净点"。
 * 逐项调出一套协调的搭配，中间必然经过一堆难看的中间态，很多人调两下就放弃了。
 *
 * 皮肤只碰**聊天观感**这几个轴，刻意**不碰配色来源、亮暗、主色种子**：
 * 那些是全局主题，用户可能费了半天劲挑的，换个聊天皮肤就被顶掉是很讨厌的事。
 * 同理气泡底色一律留 [ChatAppearancePrefs.ARGB_UNSET]（跟随主题）——皮肤定的是**形态**，配色归主题管，
 * 两边各管一段才不会打架（写死颜色的皮肤在用户切亮暗时会变成一块突兀的硬色块）。
 */
object ChatSkins {

    /**
     * 一套皮肤 = 两侧的形态 + 几个聊天相关的主题轴。
     *
     * @param id 落盘用的稳定标识（改中文名不该让"当前选中"丢失）。
     * @param density  null = 不动用户现有的消息密度。
     * @param metaBlock null = 不动工具/思考块的呈现风格。
     */
    data class Skin(
        val id: String,
        val name: String,
        val desc: String,
        val user: ChatAppearancePrefs.SideStyle,
        val ai: ChatAppearancePrefs.SideStyle,
        val density: MessageDensity? = null,
        val metaBlock: MetaBlockStyle? = null,
    )

    private val BASE = ChatAppearancePrefs.DEFAULT_USER

    val ALL: List<Skin> = listOf(
        Skin(
            id = "default",
            name = "默认",
            desc = "当前出厂观感：头像与名字在气泡上方，圆角跟随主题。",
            user = BASE,
            ai = BASE,
            density = MessageDensity.MEDIUM,
            metaBlock = MetaBlockStyle.TIMELINE,
        ),
        Skin(
            id = "merged",
            name = "连体尖角",
            desc = "气泡自己伸出一只脚指向头像，整体感强。头像仍在上方。",
            user = BASE.copy(bubbleCornerDp = 18, tail = ChatAppearancePrefs.Tail.MERGED),
            ai = BASE.copy(bubbleCornerDp = 18, tail = ChatAppearancePrefs.Tail.MERGED),
            density = MessageDensity.MEDIUM,
        ),
        Skin(
            id = "beside",
            name = "旁侧头像",
            desc = "头像挪到气泡旁边，小圆角 + 箭头指过去，紧凑排布。",
            user = BASE.copy(
                bubbleCornerDp = 6, tail = ChatAppearancePrefs.Tail.ARROW,
                tailAnchor = ChatAppearancePrefs.TailAnchor.TOP,
                avatarBeside = true, showName = false, avatarSizeDp = 34, avatarCornerDp = 8,
            ),
            ai = BASE.copy(
                bubbleCornerDp = 6, tail = ChatAppearancePrefs.Tail.ARROW,
                tailAnchor = ChatAppearancePrefs.TailAnchor.TOP,
                avatarBeside = true, showName = false, avatarSizeDp = 34, avatarCornerDp = 8,
            ),
            density = MessageDensity.COMPACT,
        ),
        Skin(
            id = "clean",
            name = "纯净",
            desc = "去掉头像和名字，只剩文字与留白。长文阅读最舒服。",
            user = BASE.copy(bubbleCornerDp = 20, showAvatar = false, showName = false),
            ai = BASE.copy(bubbleCornerDp = 20, showAvatar = false, showName = false),
            density = MessageDensity.COMFORTABLE,
            metaBlock = MetaBlockStyle.TIMELINE,
        ),
        Skin(
            id = "terminal",
            name = "终端",
            desc = "直角、极窄间距、两侧都靠左——像一份对话日志。",
            user = BASE.copy(
                bubbleCornerDp = 2, showAvatar = false, showName = true,
                align = ChatAppearancePrefs.Align.LEFT,
            ),
            ai = BASE.copy(
                bubbleCornerDp = 2, showAvatar = false, showName = true,
                align = ChatAppearancePrefs.Align.LEFT,
            ),
            density = MessageDensity.COMPACT,
            metaBlock = MetaBlockStyle.GLASS,
        ),
        Skin(
            id = "card",
            name = "卡片",
            desc = "大圆角 + 宽松留白 + 方头像，偏杂志感。",
            user = BASE.copy(bubbleCornerDp = 26, avatarSizeDp = 36, avatarCornerDp = 10),
            ai = BASE.copy(bubbleCornerDp = 26, avatarSizeDp = 36, avatarCornerDp = 10),
            density = MessageDensity.COMFORTABLE,
            metaBlock = MetaBlockStyle.TIMELINE,
        ),
    )

    fun byId(id: String?): Skin? = ALL.firstOrNull { it.id == id }

    /**
     * i18n 收集锚点 —— 永远不会被调用，只为让 `tools/i18n_wrap.py` 扫得到这些串。
     *
     * 皮肤名/说明走的是「延迟 tr()」：字面量以中文原串存进 [Skin]，渲染时才 `tr(skin.name)`
     * （见 ChatAppearancePage）。收集脚本只认 `tr() 里直接写中文字面量` 这一种写法，扫不到上面那些裸字面量，
     * 重跑一次就会把它们从 i18n/i18n_table.json 里删掉——译文还在文件里，key 没了，全体回落中文。
     * 所以这里把同样的串再以 tr() 字面量的形式写一遍，当作「这些 key 有人用」的登记。
     * ⚠️ 改上面 ALL 里的 name/desc，这里必须同步改。
     */
    @Suppress("unused")
    private fun i18nKeys() = listOf(
        tr("默认"), tr("当前出厂观感：头像与名字在气泡上方，圆角跟随主题。"),
        tr("连体尖角"), tr("气泡自己伸出一只脚指向头像，整体感强。头像仍在上方。"),
        tr("旁侧头像"), tr("头像挪到气泡旁边，小圆角 + 箭头指过去，紧凑排布。"),
        tr("纯净"), tr("去掉头像和名字，只剩文字与留白。长文阅读最舒服。"),
        tr("终端"), tr("直角、极窄间距、两侧都靠左——像一份对话日志。"),
        tr("卡片"), tr("大圆角 + 宽松留白 + 方头像，偏杂志感。"),
    )

    private const val PREF = "xtom_chat_appearance"
    private const val KEY_SKIN = "applied_skin"

    /** 上次套过哪套皮肤。只用来在列表里打个勾——用户之后手动改过某一项也不清除，
     *  那种"改了一格就说你没在用这套皮肤"的行为很惹人烦，而且我们也拦不住他改。 */
    fun current(c: Context): String? =
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY_SKIN, null)

    /**
     * 套上这套皮肤。
     *
     * 返回两侧样式，让调用方（设置页）能同步更新自己内存里那两份编辑态——
     * 页面上有实时预览和防抖落盘，直接写盘的话预览不会变、还会被它 400ms 后的防抖写回覆盖。
     */
    fun apply(c: Context, skin: Skin): Pair<ChatAppearancePrefs.SideStyle, ChatAppearancePrefs.SideStyle> {
        ChatAppearancePrefs.save(c, true, skin.user)
        ChatAppearancePrefs.save(c, false, skin.ai)
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY_SKIN, skin.id).apply()
        // 主题侧只动聊天相关的两个轴，配色/亮暗/字体一律不碰（见类注释）
        if (skin.density != null || skin.metaBlock != null) {
            val t = ThemeConfigStore.config.value
            ThemeConfigStore.update(c, t.copy(
                messageDensity = skin.density ?: t.messageDensity,
                metaBlockStyle = skin.metaBlock ?: t.metaBlockStyle,
            ))
        }
        return skin.user to skin.ai
    }
}
