package com.arix.app

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.annotation.DrawableRes
import androidx.core.content.ContextCompat
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.core.graphics.drawable.IconCompat

/**
 * 桌面图标长按弹出的快捷方式（「新对话」「语音」）。
 *
 * ⭐ 为什么是**动态**快捷方式、而不是 res/xml/shortcuts.xml 那套静态的：
 * 静态快捷方式的 `android:shortcutShortLabel` **必须是 @string 资源引用**（写字面量的话系统解析出
 * resId=0，那条快捷方式会被直接丢掉）。本项目的界面文案统统走 [tr]（中文原串 → 31 种语言的译表，
 * 见 I18n），根本不在 res/values/strings.xml 里；要走静态就得为每条快捷方式在 31 份 values-xx 里
 * 各补一条 string，和现有 i18n 管线打两套。动态快捷方式的标题是运行时 CharSequence，[tr] 直接可用，
 * 用户在设置里切语言、下次冷启动就跟着变。
 * 唯一代价：装完得先启动过一次 App，长按图标才有这两项（静态的装完即有）。
 *
 * 图标：Material vector（本项目一贯不用 emoji）。但不能直接把 vector 资源交给启动器——那几个
 * ic_capsule_* 是白色前景，落到浅色启动器上就是白底白图、什么都看不见。这里自己渲成
 * **自适应图标位图**：深底 + 品牌蓝字形，交给启动器按它自己的形状去裁。
 */
object XtomShortcuts {

    private const val ID_NEW_CHAT = "xtom_sc_new_chat"
    private const val ID_VOICE = "xtom_sc_voice"

    private const val ACTION_NEW_CHAT = "com.arix.app.action.SHORTCUT_NEW_CHAT"
    private const val ACTION_VOICE = "com.arix.app.action.SHORTCUT_VOICE"

    // 品牌色：深蓝底 + Arix 蓝 300 字形（同 XtomColorSchemes 的 onPrimary / primary）
    private val BG: Int = 0xFF0A2036.toInt()
    private val FG: Int = 0xFF82ABD0.toInt()

    /** 自适应图标画布边长。启动器的安全区是中间 66%，字形按 46% 摆，四周留够被裁的余量。 */
    private const val CANVAS = 192
    private const val GLYPH = 0.46f

    /**
     * 发布/刷新两条快捷方式。幂等，冷启动调一次即可（放后台线程，别在首帧路径上做位图绘制）。
     *
     * 整体裹 runCatching：部分启动器（尤其手表上的精简启动器）根本不支持快捷方式，
     * 这里失败不该影响 App 启动。
     */
    fun publish(context: Context) {
        runCatching {
            // 磁贴同理：这条可能跑在 XtomTheme 组合之前，语言还没装载。
            runCatching { I18n.load(context) }
            val list = listOf(
                ShortcutInfoCompat.Builder(context, ID_NEW_CHAT)
                    .setShortLabel(tr("新对话"))
                    .setLongLabel(tr("新对话"))
                    .setIcon(icon(context, R.drawable.ic_capsule_message))
                    .setIntent(
                        Intent(context, MainActivity::class.java)
                            .setAction(ACTION_NEW_CHAT)
                            .putExtra(XtomWidget.EXTRA_NEW_CHAT, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                    .setRank(0)
                    .build(),
                ShortcutInfoCompat.Builder(context, ID_VOICE)
                    .setShortLabel(tr("语音"))
                    .setLongLabel(tr("语音唤醒"))
                    .setIcon(icon(context, R.drawable.ic_capsule_listening))
                    .setIntent(
                        Intent(context, MainActivity::class.java)
                            .setAction(ACTION_VOICE)
                            .putExtra(WakeService.EXTRA_WAKE, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                    .setRank(1)
                    .build(),
            )
            ShortcutManagerCompat.setDynamicShortcuts(context, list)
        }
    }

    /** 把 Material vector 渲成自适应图标位图：整块深底（启动器会按自己的形状裁），字形居中染品牌蓝。 */
    private fun icon(context: Context, @DrawableRes res: Int): IconCompat {
        val bmp = Bitmap.createBitmap(CANVAS, CANVAS, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(BG)
        // 取不到就只剩纯色底：宁可给个素图标，也不因为一张图把整次发布带崩。
        ContextCompat.getDrawable(context, res)?.mutate()?.let { d ->
            DrawableCompat.setTint(d, FG)   // 资源里写死的白 tint 会被这句顶掉
            val side = (CANVAS * GLYPH).toInt()
            val off = (CANVAS - side) / 2
            d.setBounds(off, off, off + side, off + side)
            d.draw(canvas)
        }
        return IconCompat.createWithAdaptiveBitmap(bmp)
    }
}
