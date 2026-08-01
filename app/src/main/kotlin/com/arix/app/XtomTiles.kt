package com.arix.app

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * 下拉通知栏里的快捷设置磁贴：一格点下去直接进语音 / 直接开新对话。
 *
 * [TileService] 是 API 24 起的，本项目 minSdk 26，不需要任何版本门控就能声明。
 *
 * ⚠ 磁贴点击一定发生在「App 很可能没在跑」的时候，所以两颗磁贴都不自己做事，只发一条带 extra 的
 * Intent 把 MainActivity 拉起来——冷启动走 onCreate、已在跑走 onNewIntent，两条路 MainActivity 早已接好
 * （EXTRA_WAKE / EXTRA_NEW_CHAT 都是现成的）。这里不新增任何一条状态通路。
 *
 * ⚠ targetSdk=28 的行为差异：Android 10+ 收紧了「后台启动 Activity」，但**从磁贴启动是被明确豁免的**
 * （系统把磁贴点击当作用户手势，`startActivityAndCollapse` 走的就是这条豁免）。所以这里不需要
 * 悬浮窗/全屏通知那套绕行。
 */
abstract class XtomTileService : TileService() {

    /** 磁贴上显示的名字。放在通知栏一格里，手表上更窄——只给两三个字。 */
    protected abstract fun label(): String

    /** 磁贴图标（Material vector；系统自己按亮/暗与激活态染色，别在这儿写死颜色）。 */
    protected abstract fun iconRes(): Int

    /** 点下去要拉起的 Intent（不含 flags，由 [launch] 统一补）。 */
    protected abstract fun target(): Intent

    /** PendingIntent 的 requestCode：必须每颗磁贴各不相同，也不能和 XtomWidget 的 0x0A17 撞。 */
    protected abstract fun requestCode(): Int

    override fun onStartListening() {
        super.onStartListening()
        // 磁贴服务可能在任何 Activity 之前就被系统绑起来，此时 I18n 还没装载过语言（默认中文）。
        // 一次 SharedPreferences 读，磁贴名才会跟着 App 内的语言设置走。
        runCatching { I18n.load(this) }
        val t: Tile = qsTile ?: return
        runCatching {
            t.label = label()
            t.icon = Icon.createWithResource(this, iconRes())
            t.state = Tile.STATE_INACTIVE   // 这两颗都是「点一下就干活」的动作磁贴，没有开/关态
            t.updateTile()
        }
    }

    override fun onClick() {
        super.onClick()
        val go = Runnable { launch() }
        // 锁屏上点磁贴：先让系统走解锁流程，解完再拉 Activity。不这么做的话 Activity 会起在锁屏后面，
        // 用户解完锁看到的是「什么都没发生」。
        if (isLocked) unlockAndRun(go) else go.run()
    }

    @Suppress("DEPRECATION")   // Android 14 以下只有 startActivityAndCollapse(Intent) 这一条路
    private fun launch() {
        val i = target().apply {
            // MainActivity 是 singleTask：已在前台会走 onNewIntent，冷启动需要 NEW_TASK。
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                // Android 14 起 startActivityAndCollapse(Intent) 被废弃，官方路径改成 PendingIntent。
                // 我们 targetSdk=28，旧重载在 14 上还不会抛，但没有理由走一条已经被点名的路。
                val pi = PendingIntent.getActivity(
                    this, requestCode(), i,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                startActivityAndCollapse(pi)
            } else {
                startActivityAndCollapse(i)
            }
        }
    }
}

/** 磁贴一：语音。等价于「手动唤醒」，落到全屏助手浮层。 */
class XtomVoiceTileService : XtomTileService() {
    override fun label(): String = tr("语音")
    override fun iconRes(): Int = R.drawable.ic_capsule_listening
    override fun requestCode(): Int = 0x0A18
    override fun target(): Intent =
        Intent(this, MainActivity::class.java)
            .setAction(ACTION)
            .putExtra(WakeService.EXTRA_WAKE, true)

    private companion object {
        // 私有 action：PendingIntent 的身份按 (requestCode, filterEquals, pkg) 算，不看 extra。
        // 不给各入口各自的 action，两颗磁贴/小组件之间会互相顶掉对方的 PendingIntent。
        const val ACTION = "com.arix.app.action.TILE_VOICE"
    }
}

/** 磁贴二：新对话。等价于顶栏「+」，回聊天页并弹角色卡选择。 */
class XtomNewChatTileService : XtomTileService() {
    override fun label(): String = tr("新对话")
    override fun iconRes(): Int = R.drawable.ic_capsule_message
    override fun requestCode(): Int = 0x0A19
    override fun target(): Intent =
        Intent(this, MainActivity::class.java)
            .setAction(ACTION)
            .putExtra(XtomWidget.EXTRA_NEW_CHAT, true)

    private companion object {
        const val ACTION = "com.arix.app.action.TILE_NEW_CHAT"
    }
}
