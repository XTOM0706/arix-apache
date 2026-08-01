package com.arix.app

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.PublicOff
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.arix.app.ui.SettingsHint
import com.arix.app.ui.SettingsRow
import com.arix.app.ui.SettingsSection
import com.arix.app.ui.SettingsToggle

/**
 * 「不进 App 也能用」两条入口的设置界面：耳机/媒体键、常驻悬浮球。
 *
 * 挂在哪一页由调用方决定（两节都是自包含的 [SettingsSection]，直接放进任意设置页的 Column 即可）。
 * `translucent` 的传法同项目其它设置页：`PageBackgroundPrefs.get(context, "<pageKey>") == null`。
 */
@Composable
fun FloatingAssistSection(context: Context, translucent: Boolean = true) {
    var s by remember { mutableStateOf(FloatingAssistPrefs.snapshot(context)) }
    // 一处落盘 + 两处生效：媒体会话按新设置上下线、保活服务按新设置起停。少任何一步都会出现
    // 「开关是开的但没反应」/「关了还在跑」。
    val set: (FloatingAssistPrefs.Snapshot) -> Unit = { n ->
        s = n
        FloatingAssistPrefs.save(context, n)
        MediaKeyController.sync(context)
        XtomOverlayService.sync(context)
    }

    // 悬浮窗权限是去系统页授的，回来时得重新读一次，否则界面一直显示"未授权"（同权限页的 ON_RESUME 刷新写法）
    var overlayOk by remember { mutableStateOf(FloatingChatBall.hasOverlayPermission(context)) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val ob = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                overlayOk = FloatingChatBall.hasOverlayPermission(context)
                s = FloatingAssistPrefs.snapshot(context)   // 拖到删除区关掉过的话，开关要跟着变
            }
        }
        lifecycleOwner.lifecycle.addObserver(ob)
        onDispose { lifecycleOwner.lifecycle.removeObserver(ob) }
    }

    MediaKeySection(s, set, translucent)
    FloatingBallSection(context, s, set, overlayOk, translucent)
}

// ============================================================
// 一、耳机 / 媒体键
// ============================================================

@Composable
private fun MediaKeySection(
    s: FloatingAssistPrefs.Snapshot,
    set: (FloatingAssistPrefs.Snapshot) -> Unit,
    translucent: Boolean,
) {
    SettingsSection(tr("耳机线控唤起"), Icons.Outlined.Headphones, translucent = translucent) {
        SettingsToggle(
            icon = Icons.Outlined.Headphones,
            title = tr("按耳机键唤起助手"),
            subtitle = tr("戴着耳机按一下线控就开始说话，不用解锁、不用找 App。蓝牙耳机、车机、有线线控都走这一条。"),
            checked = s.mediaKeyEnabled,
            onCheckedChange = { set(s.copy(mediaKeyEnabled = it)) },
        )

        if (s.mediaKeyEnabled) {
            SettingsToggle(
                icon = Icons.Outlined.MusicNote,
                title = tr("正在放歌时把键让回去"),
                subtitle = tr("听歌时按线控应该是暂停音乐，不是跳出个助手。建议一直开着——关掉就等于在和音乐 App 抢这个键。"),
                checked = s.yieldToMusic,
                onCheckedChange = { set(s.copy(yieldToMusic = it)) },
            )
            SettingsToggle(
                icon = Icons.Outlined.PublicOff,
                title = tr("总是接管媒体键"),
                subtitle = tr("关：只在 Arix 在前台或语音浮层开着时才接管，其余时间媒体键完全不受影响。开：屏幕黑着、App 在后台也能按——这才是耳机场景真正想要的，代价是常驻一条通知，且与音乐 App 共用同一个键。"),
                checked = s.scope == FloatingAssistPrefs.TakeoverScope.ALWAYS,
                onCheckedChange = {
                    set(s.copy(scope = if (it) FloatingAssistPrefs.TakeoverScope.ALWAYS
                    else FloatingAssistPrefs.TakeoverScope.WHEN_ACTIVE))
                },
            )

            ActionPicker(
                title = tr("按一下"),
                icon = Icons.Outlined.TouchApp,
                selected = s.singleTap,
                onSelect = { set(s.copy(singleTap = it)) },
            )
            ActionPicker(
                title = tr("按两下"),
                selected = s.doubleTap,
                onSelect = { set(s.copy(doubleTap = it)) },
            )
            ActionPicker(
                title = tr("长按"),
                selected = s.longPress,
                onSelect = { set(s.copy(longPress = it)) },
            )

            SettingsHint(tr("只接管「接听键 / 播放暂停键」。上一首、下一首、停止这几个键一律不碰，它们除了控制音乐没有第二种含义。"))
            SettingsHint(tr("有些耳机只上报「松开」不上报「按下」，那类设备上长按判不出来，按一下和按两下不受影响。"))
            SettingsHint(tr("Android 13 起系统会优先把媒体键给当前正在出声的播放器，所以正在放歌时基本轮不到我们——这正是想要的。没在放歌时能否收到键取决于系统的会话排序，属于尽力而为。"))
        } else {
            SettingsHint(tr("默认关：开着就意味着和音乐 App 共用同一个物理键，得由你自己决定值不值。"))
        }
    }
}

/** 一档按键能绑的动作。选项多、手表窄，所以按每行两个排，而不是挤成一排横条。 */
@Composable
private fun ActionPicker(
    title: String,
    selected: FloatingAssistPrefs.KeyAction,
    onSelect: (FloatingAssistPrefs.KeyAction) -> Unit,
    icon: ImageVector? = null,
) {
    val scheme = MaterialTheme.colorScheme
    val options = listOf(
        FloatingAssistPrefs.KeyAction.VOICE to tr("唤起语音"),
        FloatingAssistPrefs.KeyAction.NEW_CHAT to tr("新对话"),
        FloatingAssistPrefs.KeyAction.STOP_GEN to tr("停止生成"),
        FloatingAssistPrefs.KeyAction.TOGGLE_BALL to tr("开关悬浮球"),
        FloatingAssistPrefs.KeyAction.NONE to tr("不做"),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = scheme.onSurface,
            )
        }
        options.chunked(2).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                row.forEach { (v, label) ->
                    val on = v == selected
                    Surface(
                        onClick = { onSelect(v) },
                        shape = RoundedCornerShape(50),
                        color = if (on) scheme.primary else scheme.surfaceContainerHighest,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (on) scheme.onPrimary else scheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        )
                    }
                }
                // 奇数个选项时补一格空位，免得最后一个被拉成整行宽
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

// ============================================================
// 二、常驻悬浮球
// ============================================================

@Composable
private fun FloatingBallSection(
    context: Context,
    s: FloatingAssistPrefs.Snapshot,
    set: (FloatingAssistPrefs.Snapshot) -> Unit,
    overlayOk: Boolean,
    translucent: Boolean,
) {
    SettingsSection(tr("悬浮球助手"), Icons.Outlined.BubbleChart, translucent = translucent) {
        SettingsToggle(
            icon = Icons.Outlined.BubbleChart,
            title = tr("显示悬浮球"),
            subtitle = tr("一个能拖的小球浮在任何 App 上方，点开就是输入框和最近几条消息。松手自动贴边，拖到屏幕底部可以关掉。"),
            checked = s.ballEnabled,
            enabled = overlayOk,
            onCheckedChange = { on ->
                // 没权限时不能静默失败：直接把用户送到系统授权页，开关保持原样
                if (on && !FloatingChatBall.hasOverlayPermission(context)) {
                    FloatingChatBall.requestOverlayPermission(context)
                } else {
                    set(s.copy(ballEnabled = on))
                }
            },
        )

        if (!overlayOk) {
            SettingsHint(tr("还没有悬浮窗权限，开不了。"), error = true)
            SettingsRow(
                icon = Icons.Outlined.OpenInFull,
                title = tr("去授予悬浮窗权限"),
                subtitle = tr("授权后回到这一页，开关会自动变成可用。"),
                onClick = { FloatingChatBall.requestOverlayPermission(context) },
            )
        }

        if (s.ballEnabled) {
            SettingsToggle(
                icon = Icons.Outlined.OpenInFull,
                title = tr("点开就全屏"),
                subtitle = tr("手表屏幕小，紧凑面板一弹输入法就没地方了。开了以后点小球直接进全屏面板。"),
                checked = s.ballFullScreenPanel,
                onCheckedChange = { set(s.copy(ballFullScreenPanel = it)) },
            )
            SettingsToggle(
                icon = Icons.Outlined.PowerSettingsNew,
                title = tr("开机后自动放回来"),
                subtitle = tr("关掉的话重启后要自己再来开一次。"),
                checked = s.ballRestoreOnBoot,
                onCheckedChange = { set(s.copy(ballRestoreOnBoot = it)) },
            )
            SettingsHint(tr("面板里打字发送会把对话送进主聊天并切到 Arix；点麦克风则是就地拉起语音浮层，盖在当前 App 上，不切走。"))
            SettingsHint(tr("悬浮球要常驻就得有一条前台通知把进程护住，否则退到后台几分钟就会被系统清掉。通知上带「关闭」，随时可以收。"))
        }
    }
}
