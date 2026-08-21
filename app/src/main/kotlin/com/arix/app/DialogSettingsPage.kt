package com.arix.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.AltRoute
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WavingHand
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.automirrored.outlined.ShortText
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.AddLink
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Sell
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.HighlightAlt
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Compress
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.ShortText
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arix.app.ui.SettingsChoiceRow
import com.arix.app.ui.SettingsHint
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.SettingsSlider
import com.arix.app.ui.SettingsToggle
import com.arix.app.ui.topChromeGapHeight

// 对话设置：上下文压缩、AI 直接执行、对话后自动记忆、快捷短语
@Composable fun DialogSettingsPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    // 本页设了背景图时卡片必须不透明，否则内容压在照片上读不清
    val opaque = remember { PageBackgroundPrefs.get(context, "dialog_settings") != null }
    var ctxCompress by remember { mutableStateOf(ConfigModePrefs.contextCompress(context)) }
    var ctxKeep by remember { mutableStateOf(ContextCompressor.keepRecent(context).toString()) }
    var ctxTrigger by remember { mutableStateOf(ContextCompressor.triggerAt(context).toString()) }
    // 条数上限：与上面的自动压缩是两码事、可以同时开。默认关（0=不限），保持加这个功能之前的行为。
    var maxRecentEnabled by remember { mutableStateOf(ContextCompressor.maxRecentMessages(context) > 0) }
    var maxRecentText by remember {
        mutableStateOf((ContextCompressor.maxRecentMessages(context).takeIf { it > 0 } ?: 20).toString())
    }
    // 上下文窗口：读一次当前激活模型名，用来配这个模型的窗口大小（token）。
    var activeModel by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { activeModel = runCatching { CloudApiConfigManager(context).getActiveConfig()?.model }.getOrNull() }
    var winText by remember(activeModel) {
        mutableStateOf(activeModel?.let { ContextWindowPrefs.windowFor(context, it).toString() } ?: "")
    }
    var directActions by remember { mutableStateOf(ConfigModePrefs.directActions(context)) }
    var actionOverlay by remember { mutableStateOf(UiActionOverlay.isEnabled(context)) }
    var dangerConfirm by remember { mutableStateOf(com.arix.tool.UiDangerGuard.isEnabled(context)) }
    var denyStopsTurn by remember { mutableStateOf(ConfigModePrefs.toolDenyStopsTurn(context)) }
    var stuckGuard by remember { mutableStateOf(ConfigModePrefs.toolStuckGuard(context)) }
    var autoExtract by remember { mutableStateOf(ConfigModePrefs.autoExtractMemory(context)) }
    var autoExtractEvery by remember { mutableStateOf(ConfigModePrefs.autoExtractEvery(context)) }
    // 聊天行为：一份快照 + 一个写入口。ChatBehaviorPrefs.save 会同步刷进程内缓存，
    // 所以聊天页（常驻 composition）下次组合就是新值，不用重进页面。
    var behavior by remember { mutableStateOf(ChatBehaviorPrefs.snapshot(context)) }
    fun saveBehavior(s: ChatBehaviorPrefs.Snapshot) { behavior = s; ChatBehaviorPrefs.save(context, s) }
    var quickPhrases by remember { mutableStateOf(QuickPhrasePrefs.get(context)) }
    var newPhrase by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.topChromeGapHeight())   // 顶部悬浮玻璃让位：在滚动内容里，内容能从玻璃下滑过去化开
        // 长对话自动压缩上下文（滚动摘要）
        SettingsSection(tr("上下文"), Icons.Outlined.Compress, translucent = !opaque) {
            SettingsToggle(
                icon = Icons.Outlined.Compress,
                title = tr("长对话自动压缩上下文"),
                subtitle = tr("对话变长后，自动把早期消息滚动摘要成一段，发给模型时用摘要+最近原文，省 token、不撞上下文上限。摘要模型可在模型配置里单独指定。"),
                checked = ctxCompress,
                onCheckedChange = { ctxCompress = it; ConfigModePrefs.setContextCompress(context, it) },
            )
            if (ctxCompress) {
                Spacer(Modifier.height(6.dp))
                val keepInt = ctxKeep.toIntOrNull()
                val trigInt = ctxTrigger.toIntOrNull()
                val valid = keepInt != null && trigInt != null && keepInt >= 1 && trigInt > keepInt
                val onlyDigits: (String) -> String = { it.filter { ch -> ch.isDigit() }.take(4) }
                Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                    com.arix.app.ui.XtomField(
                        value = ctxKeep,
                        onValueChange = { ctxKeep = onlyDigits(it); ContextCompressor.setThresholds(context, ctxKeep.toIntOrNull() ?: -1, ctxTrigger.toIntOrNull() ?: -1) },
                        label = tr("保留最近 N 条"),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    com.arix.app.ui.XtomField(
                        value = ctxTrigger,
                        onValueChange = { ctxTrigger = onlyDigits(it); ContextCompressor.setThresholds(context, ctxKeep.toIntOrNull() ?: -1, ctxTrigger.toIntOrNull() ?: -1) },
                        label = tr("超过 N 条触发"),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(4.dp))
                SettingsHint(
                    if (valid) tr("摘要后保留最近这么多条原文；未摘要消息超过触发数才压缩。")
                    else tr("触发条数需大于保留条数，否则维持上次有效值（默认 保留 8 / 触发 24）。"),
                    error = !valid,
                )
            }
            Spacer(Modifier.height(6.dp))
            // 条数上限：独立于上面的自动压缩，两者可以同时开。默认关＝不限，和加这个功能之前一样。
            SettingsToggle(
                icon = Icons.Outlined.DataUsage,
                title = tr("限制带入的历史消息条数"),
                subtitle = tr("开：每次发送最多只带最近 N 条消息，更早的这次不发（对话记录本身不受影响）。关（默认）：不限，和现在一样带完整历史（或已压缩摘要+最近原文）。"),
                checked = maxRecentEnabled,
                onCheckedChange = { on ->
                    maxRecentEnabled = on
                    ContextCompressor.setMaxRecentMessages(context, if (on) (maxRecentText.toIntOrNull() ?: 20) else 0)
                },
            )
            if (maxRecentEnabled) {
                Spacer(Modifier.height(6.dp))
                com.arix.app.ui.XtomField(
                    value = maxRecentText,
                    onValueChange = { v ->
                        maxRecentText = v.filter { it.isDigit() }.take(4)
                        val n = maxRecentText.toIntOrNull()
                        if (n != null && n >= 1) ContextCompressor.setMaxRecentMessages(context, n)
                    },
                    label = tr("最多带最近 N 条"),
                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                )
                SettingsHint(tr("不会切断工具调用与工具结果的配对——裁到中间时会连带把断开的那半也一起清掉，宁可少留几条也不会发出坏请求。"))
            }
        }
        // 上下文窗口：这个模型能装多少 token、这一轮估计用了多少，逼近上限时自动压缩才有判据可依——
        // 不然只能等接口报错才知道塞爆了。常见模型给合理默认，猜不准就手动改。
        SettingsSection(tr("上下文窗口"), Icons.Outlined.DataUsage, translucent = !opaque) {
            if (activeModel == null) {
                SettingsHint(tr("暂无可用模型配置，先在模型配置页激活一个。"))
            } else {
                val model = activeModel!!
                val overridden = ContextWindowPrefs.isOverridden(context, model)
                SettingsHint(String.format(tr("当前激活模型：%s"), model))
                Row(modifier = Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
                    com.arix.app.ui.XtomField(
                        value = winText,
                        onValueChange = { v ->
                            winText = v.filter { it.isDigit() }.take(8)
                            val n = winText.toIntOrNull()
                            if (n != null && n > 0) ContextWindowPrefs.setWindow(context, model, n)
                        },
                        label = tr("窗口大小（token）"),
                        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(10.dp))
                    Button(
                        onClick = {
                            ContextWindowPrefs.setWindow(context, model, -1)
                            winText = ContextWindowPrefs.windowFor(context, model).toString()
                        },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) { Text(tr("恢复默认"), style = MaterialTheme.typography.labelLarge) }
                }
                SettingsHint(
                    if (overridden) tr("已手动设置这个模型的窗口大小；换一个模型名会各记各的。")
                    else tr("按模型名猜的默认值，服务商公开的实际窗口可能不同，猜不准就手动改；换一个模型名会各记各的。")
                )
            }
        }
        // AI 直接执行设备操作 vs 让用户自己确认
        SettingsSection(tr("执行方式"), Icons.Outlined.Bolt, translucent = !opaque) {
            SettingsToggle(
                icon = Icons.Outlined.Bolt,
                title = tr("AI 直接执行操作"),
                subtitle = tr("开：设闹钟/提醒时 AI 直接帮你设好，不用你确认。关：AI 只帮你打开对应界面（系统闹钟/日历），由你自己确认保存。（短信/电话为安全始终由你确认。）"),
                checked = directActions,
                onCheckedChange = { directActions = it; ConfigModePrefs.setDirectActions(context, it) },
            )
            // 事前确认与事后可视化是同一件事的两半（动手前问一声 / 动手后给你看），
            // 放一起用户才找得到；分开摆会让人以为只有一个。
            SettingsToggle(
                icon = Icons.Outlined.Shield,
                title = tr("危险操作前先问我"),
                subtitle = tr("付款、转账、删除这类操作，AI 动手前弹窗确认。判断依据是按钮文字 + 当前 App + 屏幕上有没有金额/密码框；普通点按不会打扰你。"),
                checked = dangerConfirm,
                onCheckedChange = { dangerConfirm = it; com.arix.tool.UiDangerGuard.setEnabled(context, it) },
            )
            // 与「危险操作前先问我」是一对：那条决定什么时候问你，这条决定你说不之后会怎样。
            SettingsToggle(
                icon = Icons.Outlined.Block,
                title = tr("工具被拒绝后停止本轮"),
                subtitle = tr("开启后，你拒绝某个工具时 AI 立即停止这一轮，不再继续尝试。关闭时（默认）它会知道「你拒绝了」，然后自己换个办法或直接回话。"),
                checked = denyStopsTurn,
                onCheckedChange = { denyStopsTurn = it; ConfigModePrefs.setToolDenyStopsTurn(context, it) },
            )
            // 内容级死循环保护：AI 卡住复读（hihihihi…/同一段反复）时掐掉；正常长分析反复读文件不误伤。
            SettingsToggle(
                icon = Icons.Outlined.Repeat,
                title = tr("死循环保护"),
                subtitle = tr("AI 生成无意义重复内容（同一句话/字母反复）时自动中断，逼它正常作答。默认开；长任务一切正常但偶尔被误掐时，可关掉让 AI 自己跑完。"),
                checked = stuckGuard,
                onCheckedChange = { stuckGuard = it; ConfigModePrefs.setToolStuckGuard(context, it) },
            )
            // 放在「直接执行」正下方：开了直接执行，用户最想要的下一件事就是"那它到底动了哪儿"。
            SettingsToggle(
                icon = Icons.Outlined.HighlightAlt,
                title = tr("操作可视化"),
                subtitle = tr("AI 点按/滑动屏幕时，在对应位置画一圈高亮，让你看清它动了哪里。需要「显示在其他应用上层」权限；没授权时自动跳过不影响操作。"),
                checked = actionOverlay,
                onCheckedChange = {
                    actionOverlay = it
                    UiActionOverlay.setEnabled(context, it)
                    // 开启时若还没授权，直接把用户送到授权页——否则开关看着是开的、实际什么都不画，
                    // 用户会以为功能坏了
                    if (it && !UiActionOverlay.hasOverlayPermission(context)) UiActionOverlay.requestOverlayPermission(context)
                },
            )
        }
        // 聊天行为：一条消息按下去会发生什么、一次发送落到哪个模型上。
        // 和「外观」「动效」分开是有意的——那两类改坏了顶多难看，这一类会动到数据（截断/建新会话/换模型）。
        SettingsSection(tr("聊天行为"), Icons.Outlined.Tune, translucent = !opaque) {
            SettingsToggle(
                icon = Icons.Outlined.Hub,
                title = tr("每个对话各用各的模型"),
                subtitle = tr("开：每个对话记住自己用哪个模型，输入框「+」菜单里会多出「本对话模型」。关（默认）：所有对话都跟着全局激活的那个走，顶栏一切就是全都换。"),
                checked = behavior.perConversationModel,
                onCheckedChange = { saveBehavior(behavior.copy(perConversationModel = it)) },
            )
            if (behavior.perConversationModel) {
                SettingsHint(tr("每个对话在创建时就记下了当时用的模型，所以刚打开这个开关时，旧对话会各自回到它当初那个模型上。绑的那条配置被删掉或改了用途时自动退回全局。"))
            }
            SettingsToggle(
                icon = Icons.AutoMirrored.Outlined.AltRoute,
                title = tr("消息菜单里加「分叉到新会话」"),
                subtitle = tr("长按任意一条消息，把「到这条为止」的历史复制成一个新对话，原对话一个字不动，之后两边各聊各的。想换个方向试试又不想毁掉现在这段时用。"),
                checked = behavior.forkToNewConversation,
                onCheckedChange = { saveBehavior(behavior.copy(forkToNewConversation = it)) },
            )
            SettingsToggle(
                icon = Icons.Outlined.Autorenew,
                title = tr("重新生成时保留后面的消息"),
                subtitle = tr("开：一条都不删，只把那句话回填输入框，重新生成的回答接在对话末尾。关（默认）：从那一轮起整体删掉再重发——干净，但删掉的找不回来。"),
                checked = behavior.regenKeepFollowing,
                onCheckedChange = { saveBehavior(behavior.copy(regenKeepFollowing = it)) },
            )
            SettingsToggle(
                icon = Icons.AutoMirrored.Outlined.HelpOutline,
                title = tr("重新生成前先问一句"),
                subtitle = tr("点了「重新生成」先弹一次确认，告诉你这一下会删掉什么。默认关（点了就走）。"),
                checked = behavior.regenConfirm,
                onCheckedChange = { saveBehavior(behavior.copy(regenConfirm = it)) },
            )
            SettingsToggle(
                icon = Icons.AutoMirrored.Outlined.Message,
                title = tr("AI 回复按行拆成多条气泡"),
                subtitle = tr("一次回复按换行拆成几个小气泡连着弹出来，像人一句一句说。代码块整块不拆。只是显示方式：存下来的、发给模型的仍是完整的一条。角色卡自己开了「一句一条」时以卡为准。"),
                checked = behavior.splitReplyByLine,
                onCheckedChange = { saveBehavior(behavior.copy(splitReplyByLine = it)) },
            )
            if (behavior.splitReplyByLine) {
                SettingsSlider(
                    title = tr("每条之间隔多久"),
                    icon = Icons.AutoMirrored.Outlined.Message,
                    value = behavior.splitDelayMs.toFloat(),
                    range = 0f..900f,
                    onValueChange = { saveBehavior(behavior.copy(splitDelayMs = it.toInt())) },
                    unit = "ms",
                )
            }
            SettingsToggle(
                icon = Icons.Outlined.Share,
                title = tr("接收分享进来的内容"),
                subtitle = tr("从别的 App 分享过来（或长按选中文字选 Arix）时，文字填进输入框、图片进附件条，你补一句再发。关掉则退回旧兜底：只能带文字，而且直接发出去。"),
                checked = behavior.shareIntake,
                onCheckedChange = { saveBehavior(behavior.copy(shareIntake = it)) },
            )
            SettingsToggle(
                icon = Icons.Outlined.WavingHand,
                title = tr("角色卡先说开场白"),
                subtitle = tr("开新对话时，先由角色卡说一句它的开场白。卡上写了多条候选（导入酒馆卡时带过来的）就让你先挑一条。默认关：开场白字段此前从未被用过，开了会让新对话多出一条 AI 消息。"),
                checked = behavior.cardGreeting,
                onCheckedChange = { saveBehavior(behavior.copy(cardGreeting = it)) },
            )
        }
        // 耳机键唤起 + 悬浮球：两者都是「不打开 App 也能用它」的入口，放一起。全部默认关。
        FloatingAssistSection(context = context, translucent = !opaque)
        // 自动记忆（对话后抽取）——与内联 memory 工具互补
        SettingsSection(tr("记忆"), Icons.Outlined.Psychology, translucent = !opaque) {
            SettingsToggle(
                icon = Icons.Outlined.Psychology,
                title = tr("对话后自动抽取记忆"),
                subtitle = tr("每隔几轮用模型抽取要记的信息(去重入库)，与 AI 主动记忆互补；开了更全但费 token。可在记忆页审阅/删改。"),
                checked = autoExtract,
                onCheckedChange = { autoExtract = it; ConfigModePrefs.setAutoExtractMemory(context, it) },
            )
            if (autoExtract) {
                SettingsChoiceRow(
                    title = tr("抽取频率（每 N 轮）"),
                    options = listOf(1, 2, 3, 5).map { it to it.toString() },
                    selected = autoExtractEvery,
                    onSelect = { autoExtractEvery = it; ConfigModePrefs.setAutoExtractEvery(context, it) },
                )
            }
        }
        // 快捷短语：聊天输入区「+」菜单一键插入；此处增删
        SettingsSection(tr("快捷短语"), Icons.Outlined.ShortText, translucent = !opaque) {
            SettingsHint(tr("常用语一键插入输入框（聊天输入区「+」→ 快捷短语）。"))
            quickPhrases.forEachIndexed { i, p ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp)) {
                    Text(p, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurface, modifier = Modifier.weight(1f))
                    IconButton(onClick = { QuickPhrasePrefs.removeAt(context, i); quickPhrases = QuickPhrasePrefs.get(context) }) {
                        Icon(Icons.Outlined.Delete, contentDescription = tr("删除"), tint = scheme.error)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp)) {
                com.arix.app.ui.XtomField(value = newPhrase, onValueChange = { newPhrase = it }, placeholder = tr("新增短语…"), singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(6.dp))
                Button(onClick = { if (newPhrase.isNotBlank()) { QuickPhrasePrefs.add(context, newPhrase); newPhrase = ""; quickPhrases = QuickPhrasePrefs.get(context) } }, enabled = newPhrase.isNotBlank(), shape = MaterialTheme.shapes.large, modifier = Modifier.heightIn(min = 40.dp)) { Text(tr("添加"), style = MaterialTheme.typography.labelLarge) }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/**
 * 记忆图谱功能开关。
 *
 * 补边扫描要花 CPU、日记入图要往记忆库**写行**，这两者必须能单独关掉；
 * 纯展示的那几项（关联面板/局部图谱/标签）只在用户主动打开的弹窗里读已有数据，
 * 不写不扫、无后台开销，所以默认开着——默认关掉的话「谁把我这条顶掉了」继续瞒着用户、
 * 模型花 token 写的标签继续没人看得见，等于白做。
 */
