package com.arix.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.FormatListNumbered
import androidx.compose.material.icons.outlined.Functions
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Numbers
import androidx.compose.material.icons.outlined.Opacity
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material.icons.outlined.UnfoldLess
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Colorize
import androidx.compose.material.icons.outlined.FormatColorText
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.RoundedCorner
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material.icons.outlined.Straighten
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WrapText
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.arix.app.ui.SettingsHint
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.SettingsSlider
import com.arix.app.ui.SettingsToggle

/**
 * 聊天外观自定义：上半部分是**用真气泡组件渲染的实时预览**，下半部分是分「用户 / AI」两侧的调节项。
 *
 * 预览为什么必须复用 [ChatBubbleItem] 而不是自己画一套：自己画的话，真实气泡以后一改，
 * 预览不会跟着变，用户按预览调完到聊天页发现不是这样——货不对板。这里的做法是构造几条假的
 * [ChatBubble] 喂给真组件，并把「正在编辑、还没落盘」的样式通过 [LocalChatAppearance] 罩进去，
 * 于是预览走的是和聊天页**同一条渲染路径**。
 */
@Composable
fun ChatAppearancePage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    // 本页设了背景图时卡片必须不透明，否则内容压在照片上读不清（与其它设置页同规矩）
    val opaque = remember { PageBackgroundPrefs.get(context, "chat_appearance") != null }

    // 编辑中的两套样式常驻内存：滑杆每动一下都要立刻反映到预览，
    // 但绝不能每动一下就写一次 SharedPreferences（拖一次滑杆 = 上百次落盘）。
    var userStyle by remember { mutableStateOf(ChatAppearancePrefs.style(context, true)) }
    var aiStyle by remember { mutableStateOf(ChatAppearancePrefs.style(context, false)) }
    // 显示细节开关（不透明度/模型名/token/思考折叠/代码块/公式）。与两侧样式分开一个 state，
    // 因为它多数项没有「用户侧/AI 侧」之分（见 ChatDisplayOptions 的类注释）。
    var display by remember { mutableStateOf(ChatAppearancePrefs.display(context)) }
    var editUser by remember { mutableStateOf(false) }   // 顶部分段：false = 正在调 AI 侧
    var resetAsk by remember { mutableStateOf(false) }
    // reset 后要强制刷新两个 state，用一个 tick 触发重读，免得 UI 还停在旧值上
    var resetTick by remember { mutableStateOf(0) }

    // 落盘节流：值稳定 400ms 才写一次。LaunchedEffect 的 key 一变就会取消上一次的 delay，
    // 天然就是防抖——比自己拿 Handler 记时间戳干净，且随页面销毁自动取消。
    LaunchedEffect(userStyle, resetTick) {
        kotlinx.coroutines.delay(400)
        ChatAppearancePrefs.save(context, true, userStyle)
    }
    LaunchedEffect(aiStyle, resetTick) {
        kotlinx.coroutines.delay(400)
        ChatAppearancePrefs.save(context, false, aiStyle)
    }
    // 显示细节走同一套防抖：里面有不透明度滑杆，同样是拖一次能触发上百次写盘。
    LaunchedEffect(display, resetTick) {
        kotlinx.coroutines.delay(400)
        ChatAppearancePrefs.saveDisplay(context, display)
    }

    val cur = if (editUser) userStyle else aiStyle
    val setCur: (ChatAppearancePrefs.SideStyle) -> Unit = { s -> if (editUser) userStyle = s else aiStyle = s }

    Column(modifier = Modifier.fillMaxSize()) {
        // ---------- 预览区（钉在顶部，不随下面的设置一起滚走）----------
        // 调参数时眼睛要一直能看见效果；预览跟着滚出屏幕的话，滑杆和效果就没法同框了。
        ChatAppearancePreview(context = context, user = userStyle, ai = aiStyle, display = display)

        Column(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Spacer(Modifier.height(10.dp))

            // ---------- 一键皮肤 ----------
            // 放在最前面：多数人是来"换个样子"的，不是来当设计师逐项调滑杆的。
            // 套完之后下面所有滑杆照常可以接着改——皮肤只是给你一个像样的起点。
            SkinPicker(
                context = context,
                onApplied = { u, a -> userStyle = u; aiStyle = a },
                translucent = !opaque,
            )
            Spacer(Modifier.height(10.dp))

            // ---------- 侧别切换 ----------
            SideSegments(editUser = editUser, onSelect = { editUser = it })
            Spacer(Modifier.height(10.dp))

            // ---------- 气泡 ----------
            SettingsSection(tr("气泡"), Icons.Outlined.ChatBubbleOutline, translucent = !opaque) {
                SettingsSlider(
                    title = tr("气泡圆角"),
                    subtitle = tr("0 = 直角方块，越大越圆。"),
                    icon = Icons.Outlined.RoundedCorner,
                    value = cur.bubbleCornerDp.toFloat(),
                    range = ChatAppearancePrefs.CORNER_MIN.toFloat()..ChatAppearancePrefs.CORNER_MAX.toFloat(),
                    onValueChange = { setCur(cur.copy(bubbleCornerDp = it.toInt())) },
                    unit = "dp",
                )
                SettingsChoiceColumn(
                    title = tr("尖角样式"),
                    subtitle = tr("气泡指向头像那一侧的小尾巴。"),
                    icon = Icons.Outlined.Straighten,
                    options = listOf(
                        ChatAppearancePrefs.Tail.NONE to tr("无"),
                        ChatAppearancePrefs.Tail.MERGED to tr("连体尖角"),
                        ChatAppearancePrefs.Tail.ARROW to tr("箭头"),
                    ),
                    selected = cur.tail,
                    onSelect = { setCur(cur.copy(tail = it)) },
                )
                if (cur.tail == ChatAppearancePrefs.Tail.ARROW) {
                    SettingsChoiceColumn(
                        title = tr("箭头位置"),
                        subtitle = tr("箭头在气泡侧边的上下位置；默认「上」对着头像。"),
                        icon = Icons.Outlined.Straighten,
                        options = listOf(
                            ChatAppearancePrefs.TailAnchor.TOP to tr("上"),
                            ChatAppearancePrefs.TailAnchor.MIDDLE to tr("中"),
                            ChatAppearancePrefs.TailAnchor.BOTTOM to tr("下"),
                        ),
                        selected = cur.tailAnchor,
                        onSelect = { setCur(cur.copy(tailAnchor = it)) },
                    )
                }
                SettingsChoiceColumn(
                    title = tr("气泡位置"),
                    subtitle = tr("消息靠哪边；尖角与头像都跟着走。自动 = 自己靠右、对方靠左。"),
                    icon = Icons.Outlined.ChatBubbleOutline,
                    options = listOf(
                        ChatAppearancePrefs.Align.AUTO to tr("自动"),
                        ChatAppearancePrefs.Align.LEFT to tr("靠左"),
                        ChatAppearancePrefs.Align.RIGHT to tr("靠右"),
                    ),
                    selected = cur.align,
                    onSelect = { setCur(cur.copy(align = it)) },
                )
                ColorRow(
                    title = tr("气泡底色"),
                    icon = Icons.Outlined.Colorize,
                    argb = cur.bubbleArgb,
                    onPick = { setCur(cur.copy(bubbleArgb = it)) },
                )
                ColorRow(
                    title = tr("气泡文字色"),
                    icon = Icons.Outlined.FormatColorText,
                    argb = cur.textArgb,
                    onPick = { setCur(cur.copy(textArgb = it)) },
                )
                // 不透明度存在 display 里而不是 SideStyle 里：底色是取色器给的实色，
                // 把 alpha 掺进那个 ARGB 会变成「换个颜色透明度就被重置」。这里按当前编辑的那一侧读写。
                SettingsSlider(
                    title = tr("气泡不透明度"),
                    // 玻璃质感开着时气泡底色由 chatGlassCutout 自己按固定 alpha 上色，这根滑杆管不到——
                    // 与其让用户拖了没反应，不如把话说在前面。
                    subtitle = tr("调低能透出聊天背景图；100% = 实色（默认）。开了玻璃质感时气泡本就是半透明的，这项不生效。"),
                    icon = Icons.Outlined.Opacity,
                    value = (if (editUser) display.userBubbleAlphaPct else display.aiBubbleAlphaPct).toFloat(),
                    range = ChatAppearancePrefs.ALPHA_MIN_PCT.toFloat()..ChatAppearancePrefs.ALPHA_MAX_PCT.toFloat(),
                    onValueChange = { v ->
                        val pct = v.toInt()
                        display = if (editUser) display.copy(userBubbleAlphaPct = pct) else display.copy(aiBubbleAlphaPct = pct)
                    },
                    unit = "%",
                )
                SettingsHint(tr("「跟随主题」会随明暗/配色方案自动变；挑了具体颜色就固定不再跟着主题走。"))
            }

            // ---------- 头像与名字 ----------
            SettingsSection(tr("头像与名字"), Icons.Outlined.AccountCircle, translucent = !opaque) {
                SettingsToggle(
                    icon = Icons.Outlined.Visibility,
                    title = tr("显示头像"),
                    checked = cur.showAvatar,
                    onCheckedChange = { setCur(cur.copy(showAvatar = it)) },
                )
                SettingsToggle(
                    icon = Icons.Outlined.Visibility,
                    title = tr("显示名字"),
                    checked = cur.showName,
                    onCheckedChange = { setCur(cur.copy(showName = it)) },
                )
                // 头像关掉时这两个滑杆没有意义，直接不显示，别留一排调了没反应的控件
                if (cur.showAvatar) {
                    SettingsSlider(
                        title = tr("头像大小"),
                        icon = Icons.Outlined.AccountCircle,
                        value = cur.avatarSizeDp.toFloat(),
                        range = ChatAppearancePrefs.AVATAR_MIN.toFloat()..ChatAppearancePrefs.AVATAR_MAX.toFloat(),
                        onValueChange = { setCur(cur.copy(avatarSizeDp = it.toInt())) },
                        unit = "dp",
                    )
                    SettingsSlider(
                        title = tr("头像圆角"),
                        subtitle = tr("到边长一半就是正圆。"),
                        icon = Icons.Outlined.RoundedCorner,
                        value = cur.avatarCornerDp.toFloat(),
                        range = 0f..(ChatAppearancePrefs.AVATAR_MAX / 2).toFloat(),
                        onValueChange = { setCur(cur.copy(avatarCornerDp = it.toInt())) },
                        unit = "dp",
                    )
                    SettingsToggle(
                        icon = Icons.Outlined.AccountCircle,
                        title = tr("头像放在气泡旁边"),
                        subtitle = tr("微信式：头像挨着气泡、尖角正对头像；关 = 头像在气泡上方（默认）。"),
                        checked = cur.avatarBeside,
                        onCheckedChange = { setCur(cur.copy(avatarBeside = it)) },
                    )
                }
            }

            // ---------- 消息显示细节 ----------
            // 不放进上面的「用户 / AI」分段里：这些项是整页的，没有两侧之分，
            // 放进分段会让人以为「AI 侧的代码块行号」和「用户侧的」能分开调。
            SettingsSection(tr("消息信息"), Icons.Outlined.Info, translucent = !opaque) {
                SettingsToggle(
                    icon = Icons.Outlined.SmartToy,
                    title = tr("显示模型名"),
                    subtitle = tr("在耗时那行标出产出这条回复的模型。旧消息没记录过模型，仍然不显示。"),
                    checked = display.showModelName,
                    onCheckedChange = { display = display.copy(showModelName = it) },
                )
                SettingsToggle(
                    icon = Icons.Outlined.Numbers,
                    title = tr("显示 token 用量"),
                    subtitle = tr("每条回复消耗的输入/输出 token。关掉后花费仍会显示。"),
                    checked = display.showTokenUsage,
                    onCheckedChange = { display = display.copy(showTokenUsage = it) },
                )
                SettingsToggle(
                    icon = Icons.Outlined.Psychology,
                    title = tr("思考块默认折叠"),
                    subtitle = tr("关 = 一上来就摊开思考过程。思考进行中始终是展开的，不受这项影响。"),
                    checked = display.reasoningCollapsed,
                    onCheckedChange = { display = display.copy(reasoningCollapsed = it) },
                )
            }

            // ---------- 代码与公式 ----------
            SettingsSection(tr("代码与公式"), Icons.Outlined.Code, translucent = !opaque) {
                SettingsToggle(
                    icon = Icons.Outlined.WrapText,
                    title = tr("代码自动换行"),
                    subtitle = tr("长行折到下一行，不用横向拖。关 = 保持原样横着滚。"),
                    checked = display.codeWrap,
                    onCheckedChange = { display = display.copy(codeWrap = it) },
                )
                SettingsToggle(
                    icon = Icons.Outlined.UnfoldLess,
                    title = tr("长代码默认折叠"),
                    subtitle = tr("超过 14 行的代码块一进来就是收起的，点一下再展开。"),
                    checked = display.codeAutoCollapse,
                    onCheckedChange = { display = display.copy(codeAutoCollapse = it) },
                )
                SettingsToggle(
                    icon = Icons.Outlined.FormatListNumbered,
                    title = tr("显示行号"),
                    subtitle = tr("代码左侧一列行号。和自动换行同开时，折行的那几行会对不齐。"),
                    checked = display.codeLineNumbers,
                    onCheckedChange = { display = display.copy(codeLineNumbers = it) },
                )
                SettingsToggle(
                    icon = Icons.Outlined.Functions,
                    title = tr("渲染数学公式"),
                    subtitle = tr("把 \$…\$ 画成排版好的公式。关 = 原样显示源码，也不再把「\$5」误认成公式。"),
                    checked = display.renderLatex,
                    onCheckedChange = { display = display.copy(renderLatex = it) },
                )
            }

            // ---------- 动效与反馈 ----------
            EffectsSection(context = context, translucent = !opaque)

            // ---------- 自动朗读 ----------
            AutoReadSection(context = context, translucent = !opaque)

            // ---------- 恢复默认 ----------
            SettingsSection(tr("重置"), Icons.Outlined.RestartAlt, translucent = !opaque) {
                com.arix.app.ui.SettingsRow(
                    icon = Icons.Outlined.RestartAlt,
                    title = tr("恢复默认外观"),
                    subtitle = tr("用户侧和 AI 侧一起还原到出厂样式。"),
                    onClick = { resetAsk = true },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (resetAsk) {
        AlertDialog(
            onDismissRequest = { resetAsk = false },
            icon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
            title = { Text(tr("恢复默认外观？"), style = MaterialTheme.typography.titleMedium) },
            text = { Text(tr("两侧的圆角、尖角、配色、头像，以及消息信息、代码与公式的显示设置都会还原，无法撤销。"), style = MaterialTheme.typography.bodyMedium) },
            confirmButton = {
                TextButton(onClick = {
                    ChatAppearancePrefs.reset(context)
                    ChatAppearancePrefs.resetDisplay(context)
                    userStyle = ChatAppearancePrefs.DEFAULT_USER
                    aiStyle = ChatAppearancePrefs.DEFAULT_AI
                    display = ChatDisplayOptions.LEGACY
                    // 清盘之后紧跟着的那次节流写入必须写的是默认值，否则又把旧值写回去了
                    resetTick++
                    resetAsk = false
                }) { Text(tr("恢复默认")) }
            },
            dismissButton = { TextButton(onClick = { resetAsk = false }) { Text(tr("取消")) } },
        )
    }
}

// ============================================================
// 一键皮肤
// ============================================================

/**
 * 皮肤列表。选中即套用，上方预览立刻跟着变（[onApplied] 把两侧样式回灌给页面的编辑态，
 * 不然预览还是旧的，而且页面 400ms 的防抖落盘会把刚写进去的皮肤又覆盖回去）。
 */
@Composable
private fun SkinPicker(
    context: android.content.Context,
    onApplied: (ChatAppearancePrefs.SideStyle, ChatAppearancePrefs.SideStyle) -> Unit,
    translucent: Boolean,
) {
    // 只在进页面时读一次当前选中；之后由本地 state 跟着点击走（避免每次重组都读盘）
    var picked by remember { mutableStateOf(ChatSkins.current(context)) }
    SettingsSection(tr("聊天皮肤"), Icons.Outlined.Style, translucent = translucent) {
        ChatSkins.ALL.forEach { skin ->
            val selected = skin.id == picked
            com.arix.app.ui.SettingsRow(
                icon = if (selected) Icons.Outlined.Check else Icons.Outlined.Palette,
                title = tr(skin.name),
                subtitle = tr(skin.desc),
                onClick = {
                    val (u, a) = ChatSkins.apply(context, skin)
                    picked = skin.id
                    onApplied(u, a)
                },
            )
        }
        SettingsHint(tr("皮肤只定形态（圆角/尖角/头像/密度），配色仍跟随主题。套完之后下面每一项都还能接着单独调。"))
    }
}

// ============================================================
// 动效与反馈
// ============================================================

/**
 * 聊天特效开关。全部可关是刻意的：动效要拿性能和耗电换，出了问题得能一项一项排除掉
 * （见 [ChatEffectsPrefs] 的类注释）。
 */
@Composable
private fun EffectsSection(context: android.content.Context, translucent: Boolean) {
    var s by remember { mutableStateOf(ChatEffectsPrefs.snapshot(context)) }
    val set: (ChatEffectsPrefs.Snapshot) -> Unit = { n -> s = n; ChatEffectsPrefs.save(context, n) }
    SettingsSection(tr("动效与反馈"), Icons.Outlined.AutoAwesome, translucent = translucent) {
        SettingsChoiceColumn(
            title = tr("触感反馈"),
            subtitle = tr("发出去了、被停了、出错了，手感各不相同——不看屏幕也知道发生了什么。"),
            icon = Icons.Outlined.Vibration,
            options = listOf(
                ChatEffectsPrefs.Haptic.OFF to tr("关"),
                ChatEffectsPrefs.Haptic.LIGHT to tr("轻"),
                ChatEffectsPrefs.Haptic.FULL to tr("完整"),
            ),
            selected = s.haptic,
            onSelect = { set(s.copy(haptic = it)) },
        )
        SettingsToggle(
            icon = Icons.Outlined.ChatBubbleOutline,
            title = tr("滑动回复"),
            subtitle = tr("横向把消息往屏幕中间拽一下即可引用它，不用长按开菜单。"),
            checked = s.swipeToReply,
            onCheckedChange = { set(s.copy(swipeToReply = it)) },
        )
        SettingsToggle(
            icon = Icons.Outlined.AutoAwesome,
            title = tr("流式渐显"),
            subtitle = tr("正在接收的那条消息，新字从下边缘的柔光里浮出来，而不是硬蹦一行。"),
            checked = s.streamReveal,
            onCheckedChange = { set(s.copy(streamReveal = it)) },
        )
        SettingsToggle(
            icon = Icons.Outlined.ChatBubbleOutline,
            title = tr("按压回弹"),
            subtitle = tr("按住气泡时轻微缩一下，松手弹回。"),
            checked = s.pressBounce,
            onCheckedChange = { set(s.copy(pressBounce = it)) },
        )
    }
}

/**
 * 自动朗读。**默认全关**——自动出声是会当众响的那类功能，不能替用户开。
 *
 * 单独一节而不是并进「动效与反馈」：那一节管的是渲染热路径上的开关（每条气泡都要读、经
 * CompositionLocal 下发），朗读的判定点只有「一轮生成结束」那一处，两者不是一类东西。
 */
@Composable
private fun AutoReadSection(context: android.content.Context, translucent: Boolean) {
    var s by remember { mutableStateOf(AutoReadPrefs.snapshot(context)) }
    val set: (AutoReadPrefs.Snapshot) -> Unit = { n -> s = n; AutoReadPrefs.save(context, n) }
    SettingsSection(tr("自动朗读"), Icons.AutoMirrored.Outlined.VolumeUp, translucent = translucent) {
        SettingsToggle(
            icon = Icons.AutoMirrored.Outlined.VolumeUp,
            title = tr("自动朗读回复"),
            subtitle = tr("AI 每条回复收完就自动念出来。语音通话里不会重复念——那条路自己会念。"),
            checked = s.enabled,
            onCheckedChange = { set(s.copy(enabled = it)) },
        )
        SettingsToggle(
            icon = Icons.Outlined.Psychology,
            title = tr("只念台词"),
            subtitle = tr("角色扮演时跳过括号/星号里的动作与心理描写，只念引号内的话。整段没有引号时照常全念。"),
            checked = s.dialogueOnly,
            enabled = s.enabled,
            onCheckedChange = { set(s.copy(dialogueOnly = it)) },
        )
    }
}

// ============================================================
// 预览
// ============================================================

/**
 * 实时预览：**真** [ChatBubbleItem] + 假数据。
 *
 * 背景刻意贴近真实聊天页（聊天页支持背景图，见 [PageBackgroundPrefs]）：
 * 用户把气泡调成浅色时，只有在真实背景上才看得出「是不是糊了」。没设背景图时
 * 退回 surfaceContainerLow —— 比纯 surface 深一档，浅色气泡不至于隐形。
 */
@Composable
private fun ChatAppearancePreview(
    context: android.content.Context,
    user: ChatAppearancePrefs.SideStyle,
    ai: ChatAppearancePrefs.SideStyle,
    display: ChatDisplayOptions,
) {
    val scheme = MaterialTheme.colorScheme
    val chatBg = remember { PageBackgroundPrefs.get(context, "chat") }
    val scrim = remember { PageBackgroundPrefs.scrimAlpha(context) }
    // 身份取真实设置：预览里出现的是用户自己的名字和头像，才好判断头像大小/圆角合不合适
    val identity = remember {
        ChatIdentity(
            userName = IdentityPrefs.userName(context).ifBlank { tr("我") },
            userAvatar = IdentityPrefs.userAvatar(context),
            aiName = tr("助手"),
        )
    }
    // 样例对话：一条用户短消息、一条 AI 稍长的回复（带 Markdown，验证正文渲染没被配色搞坏读）、
    // 再一条用户消息，保证两侧样式在同一屏里能直接对比。
    // AI 那条刻意带齐「思考过程 / 代码块 / 公式 / 用量与模型名」——这几项都有对应开关，
    // 预览里没有这些东西的话，拨开关等于在拨一个看不见效果的按钮。
    val samples = remember {
        listOf(
            ChatBubble(role = "user", text = tr("帮我看看今天的日程")),
            ChatBubble(
                role = "assistant",
                // ⚠ 公式里的 $ 必须写成 \$：Kotlin 会把 "$t" 当成字符串模板去找一个不存在的变量 t（编译不过）
                text = tr("今天有两件事：上午十点的评审，下午三点要把周报发出去。\n\n剩余时间 \$t = 3.5\$ 小时。\n\n```kotlin\nval left = plan.filter { !it.done }\nleft.forEach { remind(it) }\n```"),
                reasoning = tr("先看日历里今天的条目，再挑出还没完成的。"),
                usage = com.arix.cloudapi.CloudApiClient.Usage(promptTokens = 812, completionTokens = 96, totalTokens = 908),
                elapsedMs = 1840,
                tokensPerSec = 21.4,
                model = "gpt-4o-mini",
            ),
            ChatBubble(role = "user", text = tr("好，出门前叫我")),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 150.dp, max = 260.dp)
            .background(scheme.surfaceContainerLow),
    ) {
        if (chatBg != null) {
            coil.compose.AsyncImage(
                model = chatBg,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // 和聊天页一样压一层遮罩，否则照片上的气泡对比度和真实页面对不上
            Box(Modifier.fillMaxSize().background(scheme.surface.copy(alpha = scrim)))
        }
        // 关键一步：把「正在编辑、尚未落盘」的样式罩给整棵预览子树。
        // ChatBubbleItem 从 LocalChatAppearance 读外观，于是预览和聊天页走同一条渲染路径。
        // 用渲染侧那一份 LocalChatAppearance（com.arix.app.ui），不能自己再定义一个同名的：
        // 两份类型名字一样但包不同，编译照过，运行时却是「设置页 provide 的是 A、气泡读的是 B」，
        // 预览调什么都不动——正是这个功能最不该出的「货不对板」。
        // resolveSide 把「盘上存的 Int/ARGB」换算成「这一帧怎么画」，与聊天页走同一个换算。
        // 显示细节同理：直接把编辑中的 display 罩下去，气泡/代码块/公式全从 LocalChatDisplay 读，
        // 与聊天页顶层 provide 的是同一个 local（定义在 ChatAppearancePrefs.kt，全项目只有那一份）。
        CompositionLocalProvider(
            com.arix.app.ui.LocalChatAppearance provides com.arix.app.ui.ChatAppearance(
                user = com.arix.app.ui.resolveSide(user),
                ai = com.arix.app.ui.resolveSide(ai),
            ),
            LocalChatDisplay provides display,
        ) {
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                samples.forEach { b ->
                    ChatBubbleItem(bubble = b, identity = identity)
                }
            }
        }
        // 预览区和下面的设置之间要有一条明确的边界，否则在小屏上会看成同一块内容
        Box(
            modifier = Modifier.fillMaxWidth().height(1.dp).align(Alignment.BottomCenter).background(scheme.outlineVariant),
        )
    }
}

// ============================================================
// 小控件
// ============================================================

/** 顶部「用户 / AI」分段：整行两等分，切到哪边下面就调哪边。 */
@Composable
private fun SideSegments(editUser: Boolean, onSelect: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(50),
        color = scheme.surfaceContainerHighest,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.padding(3.dp)) {
            listOf(
                Triple(true, tr("用户"), Icons.Outlined.Person),
                Triple(false, tr("AI"), Icons.Outlined.SmartToy),
            ).forEach { (isUser, label, icon) ->
                val on = isUser == editUser
                Surface(
                    onClick = { onSelect(isUser) },
                    shape = RoundedCornerShape(50),
                    color = if (on) scheme.primary else Color.Transparent,
                    modifier = Modifier.weight(1f),
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                            color = if (on) scheme.onPrimary else scheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 三选一之类的离散选项：选项文案在手表窄屏上横着排会被挤断，
 * 所以标题单独一行、选项另起一行铺开（SettingsChoiceRow 是横排版，这里放不下）。
 */
@Composable
private fun <T> SettingsChoiceColumn(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
            }
            Column {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = scheme.onSurface)
                if (!subtitle.isNullOrBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 6.dp)) {
            options.forEach { (v, label) ->
                val on = v == selected
                Surface(
                    onClick = { onSelect(v) },
                    shape = RoundedCornerShape(50),
                    color = if (on) scheme.primary else scheme.surfaceContainerHighest,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (on) scheme.onPrimary else scheme.onSurface,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 7.dp).fillMaxWidth(),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** 颜色行：右侧一块色块显示当前值，点开取色对话框。 */
@Composable
private fun ColorRow(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, argb: Int, onPick: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    var open by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { open = true }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, modifier = Modifier.weight(1f))
        Text(
            if (argb == ChatAppearancePrefs.ARGB_UNSET) tr("跟随主题") else tr("自定义"),
            style = MaterialTheme.typography.bodySmall,
            color = scheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(8.dp))
        // 「跟随主题」没有确定色值可展示，用一块空心描边框表示"没定死"
        Box(
            modifier = Modifier.size(28.dp).clip(RoundedCornerShape(8.dp))
                .background(if (argb == ChatAppearancePrefs.ARGB_UNSET) Color.Transparent else Color(argb))
                .border(1.dp, scheme.outline, RoundedCornerShape(8.dp)),
        )
    }
    if (open) ColorPickDialog(title = title, argb = argb, onDismiss = { open = false }, onPick = { onPick(it); open = false })
}

/**
 * 取色对话框：一排预设 + HSV 三滑杆。项目里原本只有 PersonalizationPage 里内联的一套主题取色，
 * 那套是直接改主题种子的、拿不出来复用，所以这里写一个通用的小取色器（手表小屏，不做色轮）。
 */
@Composable
private fun ColorPickDialog(title: String, argb: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // 初值：已有自定义色就从它拆 HSV，否则从主题主色起步（比从纯红起步更接近用户想要的）
    val seed = if (argb == ChatAppearancePrefs.ARGB_UNSET) scheme.primary.toArgb() else argb
    val hsv = remember { FloatArray(3).also { android.graphics.Color.colorToHSV(seed, it) } }
    var h by remember { mutableStateOf(hsv[0]) }
    var s by remember { mutableStateOf(hsv[1]) }
    var v by remember { mutableStateOf(hsv[2]) }
    val picked = 0xFF000000.toInt() or (android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF)

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Outlined.Palette, contentDescription = null) },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // 预设：大多数人只想快速换个颜色，不想拖三根滑杆
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                    // 这些是"用户自己挑的颜色"，是硬编码色值规则里唯一被允许的例外
                    listOf(0xFF1E88E5, 0xFF43A047, 0xFFFB8C00, 0xFFE53935, 0xFF8E24AA, 0xFF212121, 0xFFFAFAFA).forEach { c ->
                        val ci = c.toInt()
                        Box(
                            modifier = Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(6.dp))
                                .background(Color(ci)).border(1.dp, scheme.outlineVariant, RoundedCornerShape(6.dp))
                                .clickable { onPick(ci) },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(Color(picked))
                            .border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp)),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "#%06X".format(picked and 0xFFFFFF),
                        style = MaterialTheme.typography.bodyMedium,
                        color = scheme.onSurfaceVariant,
                    )
                }
                LabeledSlider(tr("色相"), h, 0f, 360f) { h = it }
                LabeledSlider(tr("饱和度"), s, 0f, 1f) { s = it }
                LabeledSlider(tr("明度"), v, 0f, 1f) { v = it }
            }
        },
        confirmButton = { TextButton(onClick = { onPick(picked) }) { Text(tr("使用此色")) } },
        dismissButton = {
            Row {
                // 「跟随主题」是一个正当选择，不能只让人挑死色——放在这里等价于"清除自定义"
                TextButton(onClick = { onPick(ChatAppearancePrefs.ARGB_UNSET) }) { Text(tr("跟随主题")) }
                TextButton(onClick = onDismiss) { Text(tr("取消")) }
            }
        },
    )
}

@Composable
private fun LabeledSlider(label: String, value: Float, from: Float, to: Float, onChange: (Float) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(value = value.coerceIn(from, to), onValueChange = onChange, valueRange = from..to, modifier = Modifier.fillMaxWidth())
    }
}
