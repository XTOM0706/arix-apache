package com.arix.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

/**
 * 聊天**行为**开关 —— 每对话绑定模型、分叉到新会话、重新生成怎么做、AI 回复拆多条气泡、接分享进来的内容。
 *
 * 为什么又单开一个 Prefs：项目里已有三个同类件，各管一层，边界是清楚的——
 *  · [ChatAppearancePrefs]  长什么样（形状/配色/头像/密度）
 *  · [ChatEffectsPrefs]     怎么动（触感/滑动回复/流式渐显/按压回弹）
 *  · 这里                    **做什么**（一条消息按下去会发生什么、一次发送落到哪个模型上）
 * 前两类改坏了顶多难看，这一类改坏了会**动到数据**（截断、建新会话、换模型），所以每一项都必须
 * 能单独关掉、且默认一律取「和加这个功能之前一模一样」的那一侧——除了 [shareIntake]，理由见其注释。
 *
 * ⚠ 读取走**进程内缓存**，不是每次都开 SharedPreferences：其中几项（分叉菜单项显不显示）会被
 * 气泡长按菜单读到，那是 LazyColumn 里的路径，读盘会卡列表（同 [ChatEffectsPrefs] 的说明）。
 * 写入时同步刷缓存，设置页一改立刻生效，不用重进页面。
 */
object ChatBehaviorPrefs {

    private const val PREF = "xtom_chat_behavior"

    /** 一次性读全的快照。渲染侧只碰它，不碰 SharedPreferences。 */
    @Immutable
    data class Snapshot(
        /**
         * 每对话绑定模型：取模型时先看本会话 `configId`，取不到再回退全局激活项。
         * 关（默认）= 一直用全局激活项，也就是加这个功能之前的样子。
         */
        val perConversationModel: Boolean,
        /** 气泡长按菜单里出现「分叉到新会话」。 */
        val forkToNewConversation: Boolean,
        /**
         * 从中间重新生成时**保留**后续消息（重生成的回答接在对话末尾）。
         * 关（默认）= 老行为：从那一轮的用户消息起整体截断。
         */
        val regenKeepFollowing: Boolean,
        /** 重新生成前先弹一次确认。关（默认）= 点了就走。 */
        val regenConfirm: Boolean,
        /** AI 一次回复按行拆成几个小气泡连着弹出来（纯渲染，落库仍是完整一条）。 */
        val splitReplyByLine: Boolean,
        /** 拆气泡时两条之间的间隔（毫秒）。 */
        val splitDelayMs: Int,
        /**
         * 接收系统分享/划词进来的内容（文字填进输入框、附件进附件条）。
         *
         * ⚠ 全项目**唯一**默认开的一项。分享入口本身（[ShareIntake] + 代理 Activity）早就做完了，
         * 缺的只是聊天页这一段消费代码；默认关等于把一个已经存在的入口继续废着。关掉则退回
         * [MainScreen] 里那条既有兜底（只带文字、且直接发出去，见 ShareIntake.chatConsumerAttached）。
         */
        val shareIntake: Boolean,

        /**
         * 开新对话时，先由角色卡说一句开场白。
         *
         * ⚠ 默认**关**，因为这是一条真的新行为：角色卡的 `openingStatement`（酒馆卡的 `first_mes`）
         * 一直只是个能编辑、能导入导出、能被搜索到的字段，**全项目从来没有任何地方拿它开过场**。
         * 所以这不是"把坏了的修好"，是"把一直没接的接上"——默认开会让所有现存用户的新对话
         * 突然多出一条 AI 消息。开了之后：卡上有多条候选开场白（`alternate_greetings`）时先让用户挑一条。
         */
        val cardGreeting: Boolean,
    )

    /** 默认值 = 除 [Snapshot.shareIntake] 外，全部取「不改变现有行为」那一侧。 */
    val DEFAULT = Snapshot(
        perConversationModel = false,
        forkToNewConversation = false,
        regenKeepFollowing = false,
        regenConfirm = false,
        splitReplyByLine = false,
        splitDelayMs = 260,
        shareIntake = true,
        cardGreeting = false,
    )

    @Volatile private var cached: Snapshot? = null

    fun snapshot(c: Context): Snapshot = cached ?: load(c).also { cached = it }

    private fun load(c: Context): Snapshot {
        val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Snapshot(
            perConversationModel = sp.getBoolean("per_conv_model", DEFAULT.perConversationModel),
            forkToNewConversation = sp.getBoolean("fork_to_new", DEFAULT.forkToNewConversation),
            regenKeepFollowing = sp.getBoolean("regen_keep_following", DEFAULT.regenKeepFollowing),
            regenConfirm = sp.getBoolean("regen_confirm", DEFAULT.regenConfirm),
            splitReplyByLine = sp.getBoolean("split_reply_by_line", DEFAULT.splitReplyByLine),
            // 下限 0（不等待、一次性铺开），上限 3s：再长就不是"连着弹出来"而是卡住了
            splitDelayMs = sp.getInt("split_delay_ms", DEFAULT.splitDelayMs).coerceIn(0, 3000),
            shareIntake = sp.getBoolean("share_intake", DEFAULT.shareIntake),
            cardGreeting = sp.getBoolean("card_greeting", DEFAULT.cardGreeting),
        )
    }

    fun save(c: Context, s: Snapshot) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putBoolean("per_conv_model", s.perConversationModel)
            .putBoolean("fork_to_new", s.forkToNewConversation)
            .putBoolean("regen_keep_following", s.regenKeepFollowing)
            .putBoolean("regen_confirm", s.regenConfirm)
            .putBoolean("split_reply_by_line", s.splitReplyByLine)
            .putInt("split_delay_ms", s.splitDelayMs.coerceIn(0, 3000))
            .putBoolean("share_intake", s.shareIntake)
            .putBoolean("card_greeting", s.cardGreeting)
            .apply()
        cached = s   // 同步刷缓存：设置页改完立刻生效
    }

    fun reset(c: Context) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
        cached = DEFAULT
    }
}

/**
 * 往下发这份快照。**绝不能**让气泡自己去读 SharedPreferences——气泡长按菜单在 LazyColumn 里，
 * 那是全项目最热的几条路径之一（同 [com.arix.app.ui.LocalChatEffects] / [LocalChatDisplay] 的理由）。
 *
 * 默认值给 DEFAULT：任何忘了 provide 的入口（分享成图、唤醒页…）都还是旧行为，不会因为漏接线而变样。
 */
val LocalChatBehavior = staticCompositionLocalOf { ChatBehaviorPrefs.DEFAULT }

/**
 * 从 prefs 读一次。**只在聊天页顶层调用一次**，往下用 [LocalChatBehavior] 发。
 *
 * @param revision 递增/变化它即强制重读。聊天页是常驻 composition，从设置页回来不会重建，
 *                 不重读就一直是旧配置（和 rememberChatAppearance / rememberChatDisplay 同一个坑）。
 */
@Composable
fun rememberChatBehavior(revision: Any? = Unit): ChatBehaviorPrefs.Snapshot {
    val ctx = LocalContext.current
    return remember(revision) { ChatBehaviorPrefs.snapshot(ctx) }
}
