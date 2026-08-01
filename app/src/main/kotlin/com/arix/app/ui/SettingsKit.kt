package com.arix.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

// ============================================================
// SettingsKit —— 设置页通用「分组卡 + 行」组件
//
// 全 App 设置页的统一外观：分组标题在卡片「外面上方」（小图标 + 小号粗体，
// primary 着色，用来标注下面这张卡，而不是跟卡内各行的标题抢视觉），
// 卡片本体是圆角 Surface + outlineVariant 发丝描边（对齐 MainActivity 的 SettingsGroup）。
//
// 规矩：只用 MaterialTheme 令牌，绝不写死颜色；输入框一律 XtomField；
// 副标题允许折行（它是这一行唯一的解释，33 种语言译文比中文长 2-3 倍，
// 强行单行 = 保证把最该看的字省略掉）；字号走排版尺度，不写 10sp/11sp，
// Switch 不缩放（缩了就低于最小可点区域）。
// ============================================================

/**
 * 一组设置：标题在卡外上方，内容在卡里。
 *
 * @param translucent 卡片是否用半透明底色。本页设了背景图时必须传 false —— 半透明卡片
 *   压在用户任意一张照片上会糊到读不出字，这时候要用完全不透明的 surface。
 *   调用方自己判断：`PageBackgroundPrefs.get(context, "<pageKey>") == null`。
 *   （背景图由 MainActivity 在页面外层统一铺，组件内部拿不到，故由调用方传入。）
 */
@Composable
fun SettingsSection(
    title: String,
    icon: ImageVector? = null,
    translucent: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = scheme.primary)
    }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (translucent) scheme.surfaceContainer.copy(alpha = 0.72f) else scheme.surface,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Column(modifier = Modifier.padding(12.dp), content = content)
    }
}

/** 行左侧：图标 + 标题/副标题。各行共用，保证左边对齐一致。 */
@Composable
private fun RowScopeLeading(icon: ImageVector?, title: String, subtitle: String?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
        }
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, color = scheme.onSurface)
            if (!subtitle.isNullOrBlank()) {
                // 不设 maxLines=1：副标题是这行唯一的解释，多语言下必然被省略号吃掉。
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

/** 可点击的设置行：左图标 + 标题/副标题，右侧 chevron。 */
@Composable
fun SettingsRow(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))   // 先 clip 再 clickable，水波纹才是圆角的
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp)
            .heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowScopeLeading(icon, title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        Icon(Icons.Outlined.ChevronRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

/** 开关行：左图标 + 标题/副标题，右侧 Switch（不缩放）。trailing = Switch 之后再挂一个动作（如「编辑」）。 */
@Composable
fun SettingsToggle(
    icon: ImageVector?,
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp).heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowScopeLeading(icon, title, subtitle, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        // 关闭态显式配色：本主题里 outline 与 surfaceContainerHighest 同为 #45475A，M3 默认「滑块=outline / 轨道=
        // surfaceContainerHighest」两者同色 → 滑块隐形、只剩空框（部分设备上尤其明显，用户报的 bug）。
        // 滑块改用对比足够的 onSurfaceVariant，关着也一眼看得见。
        val sc = MaterialTheme.colorScheme
        Switch(
            checked = checked, onCheckedChange = onCheckedChange, enabled = enabled,
            colors = androidx.compose.material3.SwitchDefaults.colors(
                uncheckedThumbColor = sc.onSurfaceVariant,
                uncheckedTrackColor = sc.surfaceContainerHighest,
                uncheckedBorderColor = sc.outline,
            ),
        )
        if (trailing != null) trailing()
    }
}

/**
 * 数值行：标题/副标题在左，右边同时给「可直接键入的数字框」和滑杆。
 * 数值设置永远要能直接打字，不能只让人拖。
 */
@Composable
fun SettingsSlider(
    title: String,
    subtitle: String? = null,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (() -> Unit)? = null,
    unit: String = "",
    steps: Int = 0,
    icon: ImageVector? = null,
) {
    // 外层 if 的 else 分支要再包一层花括号，否则 `else @Composable { … }` 会被当成代码块而不是 lambda
    val unitLabel: (@Composable () -> Unit)? = if (unit.isBlank()) null else {
        { Text(unit, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowScopeLeading(icon, title, subtitle, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            XtomField(
                value = value.roundToInt().toString(),
                onValueChange = { s ->
                    s.filter { it.isDigit() }.toIntOrNull()?.let { n ->
                        onValueChange(n.toFloat().coerceIn(range.start, range.endInclusive))
                        onValueChangeFinished?.invoke()
                    }
                },
                modifier = Modifier.widthIn(min = 60.dp, max = 84.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                trailing = unitLabel,
                verticalPadding = 8.dp,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * 离散选择行：标题/副标题在左，右边一排圆头选项（保持原有「只能选这几个值」的语义，
 * 不像滑杆那样放开成连续值）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun <T> SettingsChoiceRow(
    title: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    subtitle: String? = null,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            RowScopeLeading(icon, title, subtitle, Modifier.weight(1f))
        }
        // 选项单独一行、可换行：原来标题在左/选项在右，英文等长译名下选项先占满宽度、
        // 标题被挤成近零宽窄条、逐字竖排（见多语言设置卡）。改成标题独占一行、选项 FlowRow 换行。
        FlowRow(
            modifier = Modifier.fillMaxWidth().padding(start = if (icon != null) 32.dp else 0.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            options.forEach { (v, label) ->
                val on = v == selected
                Surface(
                    onClick = { onSelect(v) },
                    shape = RoundedCornerShape(50),
                    color = if (on) scheme.primary else scheme.surfaceContainerHighest,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (on) scheme.onPrimary else scheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    )
                }
            }
        }
    }
}

/** 卡内说明/次要文字（副标题同级），供各页放在行下方的补充说明。 */
@Composable
fun SettingsHint(text: String, error: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
