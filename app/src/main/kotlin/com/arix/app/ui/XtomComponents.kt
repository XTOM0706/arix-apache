package com.arix.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.arix.app.theme.ComponentStyle
import com.arix.app.theme.LocalThemeConfig

// ============================================================
// XtomComponents —— 核心通用组件（DESIGN.md §8 第2步）
// 一律走 MaterialTheme.* 令牌 + ThemeConfig，绝不写死颜色。
// 替代散落各页的 darkTextFieldColors() / 手搓圆形按钮等。
// ============================================================

/** 输入框配色，全部取自当前配色令牌。 */
@Composable
fun xtomTextFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = MaterialTheme.colorScheme.onSurface,
    unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
    cursorColor = MaterialTheme.colorScheme.primary,
    focusedBorderColor = MaterialTheme.colorScheme.primary,
    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
    focusedLabelColor = MaterialTheme.colorScheme.primary,
    unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    focusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
    unfocusedPlaceholderColor = MaterialTheme.colorScheme.onSurfaceVariant,
)

/** 主题化按钮：变体（填充/色调/描边）由 ThemeConfig.componentStyle 决定。 */
@Composable
fun XtomButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    when (LocalThemeConfig.current.componentStyle) {
        ComponentStyle.FILLED ->
            Button(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, content = content)
        ComponentStyle.TONAL ->
            FilledTonalButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, content = content)
        ComponentStyle.OUTLINED ->
            OutlinedButton(onClick = onClick, modifier = modifier, enabled = enabled, shape = shape, content = content)
    }
}

/**
 * 紧凑输入框（修复「文字下沉」）。
 *
 * 旧代码到处给 OutlinedTextField 强设 .height(48.dp) 等低于 Material 最小值(56dp)的固定高度，
 * 导致文字/光标被挤到底部——这就是「文字下沉」。这里改用 BasicTextField 自绘装饰盒：
 * 高度由内容 + 上下 padding 决定，Row 垂直居中，永不下沉；filled 风格贴合莫奈现代观感。
 * 圆角走 MaterialTheme.shapes，配色全令牌。各页应改用它替代手搓的定高输入框。
 */
@Composable
fun XtomField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    label: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    password: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    verticalPadding: Dp = 11.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    var focused by remember { mutableStateOf(false) }
    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = if (focused) scheme.primary else scheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            enabled = enabled,
            singleLine = singleLine,
            minLines = minLines,
            maxLines = maxLines,
            textStyle = textStyle.copy(color = scheme.onSurface),
            cursorBrush = SolidColor(scheme.primary),
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            decorationBox = { inner ->
                Row(
                    modifier = Modifier
                        .clip(shape)
                        .background(scheme.surfaceContainerHighest)
                        .border(1.dp, if (focused) scheme.primary else scheme.outlineVariant, shape)
                        .padding(horizontal = 14.dp, vertical = verticalPadding),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (leading != null) { leading(); Spacer(Modifier.width(8.dp)) }
                    Box(Modifier.weight(1f)) {
                        if (value.isEmpty() && placeholder != null)
                            Text(placeholder, color = scheme.onSurfaceVariant, style = textStyle, maxLines = if (singleLine) 1 else maxLines)
                        inner()
                    }
                    if (trailing != null) { Spacer(Modifier.width(8.dp)); trailing() }
                }
            },
        )
    }
}

/** 标准卡片：surfaceContainer + 细描边 + shapes.large 圆角，全令牌。替代各页手搓 Card。 */
@Composable
fun XtomCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = MaterialTheme.shapes.large
    // 全局玻璃：开了就画背后壁纸的模糊 + 半透明卡色（玻璃透出背景）；关了 glassSurface 退化成半透明色。
    // 半透明用 surfaceContainer 压到 0.72 alpha，让模糊背景透出来但文字仍读得清。
    val glassOn = LocalGlassBackdrop.current.on
    // 全局玻璃开时卡片「悬浮」：外留一圈边距 + 更透的玻璃色，让卡浮在壁纸上而非贴边铺满。
    var m = modifier.fillMaxWidth().then(if (glassOn) Modifier.padding(horizontal = 4.dp, vertical = 3.dp) else Modifier).clip(shape)
        .glassSurface(shape, scheme.surfaceContainer)
        .border(1.dp, scheme.outlineVariant.copy(alpha = if (glassOn) 0.5f else 1f), shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Column(m.padding(14.dp), content = content)
}

/**
 * 卡片/浮层阴影高度。浅色**无阴影**（扁平），暗色保留阴影层次。
 *
 * 浅色的层次不靠阴影，靠「灰画布 vs 白卡片」的明暗差 + 描边区分——所以画布必须比卡片深
 * （见 fallbackLight 的 surface/container 令牌），否则加不加阴影都糊。用户 2026-07-16 定：浅色去阴影。
 */
@Composable
fun flatShadowElevation(elevation: androidx.compose.ui.unit.Dp): androidx.compose.ui.unit.Dp =
    if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.dp else elevation

/**
 * 顶部悬浮玻璃(MainActivity 的 TopGlassChrome)的高度。它是浮在内容之上的兄弟层、不占布局高度，
 * 所以每个页面得自己在顶上让出这么一段，否则第一个元素静止时就压在胶囊底下、读不了。
 *
 * 让位要让在「滚动容器内部」(LazyColumn 的 contentPadding.top / 滚动 Column 的第一个 Spacer)，
 * 不能写成容器自己的 .padding(top=)——那样这段空隙被钉在滚动之外，内容到顶就截断，
 * 而不是从玻璃下面滑过去化开，整个渐变模糊就白做了。
 * 固定顶栏的页面(工具条不滚)让不出这个效果，退化成普通顶部留白即可。
 *
 * 与 TopGlassChrome 的 heightIn(min = 68.dp) 对齐——那边改了这边跟着改。
 *
 * ⚠ 这是 **GLASS 风格**的让位高度。M3 风格下 TopAppBar 是实体组件、自己占了高度，
 * 页面再让 68dp 就是顶上白白空一大块 → 用 [topChromeGap] 取值，别直接用这个常量。
 */
val TopChromeHeight = 68.dp

/** 矮屏（手表）下的顶部高度：44dp 触控目标不动，只把上下留白从 12dp 压到 5dp。 */
val TopChromeHeightCompact = 54.dp

/** 屏幕矮到该用紧凑顶栏了吗。手表约 320dp 高，手机 700dp 以上。 */
@Composable
fun isShortScreen(): Boolean = LocalConfiguration.current.screenHeightDp < 420

/**
 * 大标题折叠比例：0 = 展开（rikkahub 式大标题）， 1 = 收拢（小标题贴在返回箭头旁）。
 * 由 MainScreen 的 NestedScrollConnection 按当前子页滚动写入；顶栏用它插值标题大小/位置，
 * [topChromeGap] 用它把内容顶部让位从「展开高」收到「收拢高」，两处同源、内容随折叠自然上移不留空。
 * 用 compositionLocalOf（非 static）：只有读它的节点（各页的让位 Spacer）在滚动时重组，不牵动全树。
 */
// 存 State<Float> 而非 Float：提供的是稳定的 State 实例，滚动时只有读 .value 的消费者(顶栏/各页 topChromeGap)重组，
// 不逼提供者 MainScreen 每帧重组。默认给个常量 0 的 State。
val LocalTitleCollapse = androidx.compose.runtime.compositionLocalOf<androidx.compose.runtime.State<Float>> { androidx.compose.runtime.mutableStateOf(0f) }

/** 大标题栏「展开」高度（含大标题 + 返回箭头行）。矮屏收窄。 */
@Composable
fun expandedChromeHeight(): Dp = if (isShortScreen()) 82.dp else 112.dp

/** 大标题栏「收拢」高度（只剩返回箭头 + 小标题一行）。 */
@Composable
fun collapsedChromeHeight(): Dp = if (isShortScreen()) 52.dp else 64.dp

/**
 * 顶部玻璃层的高度 —— 也是页面该让出的顶部留白，两处必须同一个来源。
 *
 * 68dp 在手机上占屏高不到 10%，但手表只有 ~320dp 高，68dp 就吃掉 21%，
 * 加上状态栏后头部比内容还占地方（用户实测反馈）。矮屏收到 54dp。
 * 只压留白不压 44dp 触控目标——那是无障碍底线。
 */
@Composable
fun topChromeHeight(): Dp = if (isShortScreen()) TopChromeHeightCompact else TopChromeHeight

/**
 * 当前风格下页面该让出的顶部高度。
 * - GLASS：让出 [topChromeHeight]，内容从玻璃下滑过去。
 * - M3：0 —— TopAppBar 已经实体占位，Scaffold 的 padding 会把内容顶下来，再让就是空一块。
 *
 * ⚠ **滚动页别用这个，用 [topChromeGapHeight]**。这里在组合期读了 `LocalTitleCollapse.current.value`，
 * 折叠时会拖着调用方每帧全量重组（原因见 [topChromeGapHeight] 的注释）。
 * 只有「取一个静态数值参与运算」的固定布局页（不随折叠变化的场景）才适合直接调它。
 */
@Composable
fun topChromeGap(): Dp =
    if (LocalThemeConfig.current.chromeStyle == com.arix.app.theme.ChromeStyle.GLASS)
        // 随折叠从展开高收到收拢高：滚动收标题时，让位 Spacer 同步变矮 → 内容自然上移、收拢后不留空隙。
        // +floatInset：顶栏为屏幕适配把胶囊/标题整体往下挪了同样的量、自身也长高了同样的量，
        // 让位不跟着长的话内容会被顶栏压住（两处必须同源，见 TopGlassChrome 的栏高计算）。
        androidx.compose.ui.unit.lerp(expandedChromeHeight(), collapsedChromeHeight(), LocalTitleCollapse.current.value.coerceIn(0f, 1f)) +
            com.arix.app.theme.LocalScreenFit.current.floatInset
    else 0.dp

/**
 * 顶部让位的 **Modifier 版**（用在让位 Spacer 上：`Spacer(Modifier.topChromeGapHeight())`）。
 * 取值与 [topChromeGap] 完全一致，区别只在**什么时候读折叠进度**。
 *
 * 为什么必须有它（这是「折叠大标题时整页掉帧」的根因）：
 * [topChromeGap] 是个返回 Dp 的普通 @Composable，函数体里读了 `LocalTitleCollapse.current.value`。
 * Spacer 自身不是独立的可重启作用域，于是这个订阅落在**调用它的那个页面 composable** 上。
 * 滚动折叠时 titleCollapse 每帧都变 → 整页每帧全量重组一次。设置页是 Column + verticalScroll，
 * 几十个 SettingsSection/SettingsToggle/XtomField 全部同时在 composition 里，
 * 于是每帧重跑一遍它们的组合，还顺带把每个 glassSurface 的 modifier 链和 drawBehind 闭包重建一遍。
 *
 * 这里改成在 `layout {}` 里读 `.value`：Compose 把布局 lambda 里的 State 读取算作「延迟读取」，
 * 只让这一个节点**重新布局**，不触发任何重组。让位本来就只是一个高度，重新布局足够了。
 */
@Composable
fun Modifier.topChromeGapHeight(): Modifier {
    // M3 风格下 TopAppBar 实体占位，不需要让位；此时连布局改写都省了
    if (LocalThemeConfig.current.chromeStyle != com.arix.app.theme.ChromeStyle.GLASS) return this
    // +floatInset 与 [topChromeGap]、TopGlassChrome 的栏高同源（顶栏因屏幕适配整体下挪并长高了这么多）
    val floatFit = com.arix.app.theme.LocalScreenFit.current.floatInset
    val expanded = expandedChromeHeight() + floatFit
    val collapsed = collapsedChromeHeight() + floatFit
    // 只取 State 实例本身，绝不在组合期读 .value —— 读了就前功尽弃
    val collapse = LocalTitleCollapse.current
    return this.layout { measurable, constraints ->
        val h = androidx.compose.ui.unit.lerp(expanded, collapsed, collapse.value.coerceIn(0f, 1f)).roundToPx()
        val placeable = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
        layout(placeable.width, h) { placeable.place(0, 0) }
    }
}

/**
 * 统一页面骨架：一致的横向内距 + 可选标题 + 可选滚动，消除各页各写 padding/verticalScroll 的乱象。
 * 列表页传 scroll=false 自己放 LazyColumn。标题走 typography 令牌。
 *
 * 顶部自动让出 [topChromeGap]：scroll=true 时这段在滚动内容里，内容能从玻璃下滑过去化开；
 * scroll=false 时它是固定留白，把调用方自己的工具条/列表顶下来避开胶囊。
 */
@Composable
fun PageScaffold(
    title: String? = null,
    scroll: Boolean = true,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val base = modifier.fillMaxSize().padding(horizontal = 12.dp)
    Column(if (scroll) base.verticalScroll(rememberScrollState()) else base) {
        Spacer(Modifier.topChromeGapHeight())   // 延迟读折叠进度：只重布局，不拖着整页每帧重组
        if (title != null) {
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
        } else Spacer(Modifier.height(4.dp))
        content()
        if (scroll) Spacer(Modifier.height(24.dp))
    }
}

/**
 * 圆形图标开关（聊天输入区的麦克风/思考/工具等）。大触控区，手表友好。
 * active 时用高亮底色，否则用中性高容器色——全部取自令牌。
 */
@Composable
fun XtomIconToggle(
    active: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    content: @Composable () -> Unit,
) {
    val bg = if (active) activeColor else MaterialTheme.colorScheme.surfaceContainerHighest
    Box(
        modifier = modifier
            .clip(CircleShape)
            .background(bg)
            .clickable(onClick = onClick)
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
