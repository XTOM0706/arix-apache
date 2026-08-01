package com.arix.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Arix 桌面小组件（watch/手机通用）。
 *
 * 简洁入口卡：显示 App 名「Arix」+ 一个大点按区，点击打开 MainActivity 并请求发起新对话。
 * 只用 RemoteViews（widget 不能跑 Compose）。updatePeriodMillis=0，仅在系统首次布置/尺寸变化/
 * 显式 notifyAppWidgetViewDataChanged 时回调 onUpdate，不做周期轮询。
 *
 * 说明：整块布局 root 绑同一个 PendingIntent，命中面积最大，手表小屏也好点。
 * “最近一条对话标题”按需求做成低成本可选项——为不引入 Room/存储编译依赖（严格范围要求），
 * 这里默认只放入口按钮，不读取历史；若日后要显示，把标题传给 [updateWidget] 即可。
 */
class XtomWidget : AppWidgetProvider() {

    companion object {
        /**
         * 打开 MainActivity 时携带此 extra=true，语义：「打开 App 并立即发起一段新对话」。
         * MainActivity 侧负责消费（本 widget 只管发）。
         */
        const val EXTRA_NEW_CHAT = "xtom_new_chat"

        // 私有 action + 专用非零 requestCode：PendingIntent 身份由 (requestCode, filterEquals(intent), pkg) 决定，
        // 不比 extra/flags。若沿用 ACTION_MAIN+LAUNCHER+requestCode=0，会与启动器/崩溃兜底(CrashHandler)/
        // 备份恢复重启(FullBackup.triggerRestart 的 FLAG_CANCEL_CURRENT) 那些 PI filterEquals 相撞 →
        // widget 的 PI 被取消/改写、按钮点了没反应或走错路径。显式组件已能启动 MainActivity，无需 MAIN/LAUNCHER。
        private const val ACTION_NEW_CHAT = "com.arix.app.action.WIDGET_NEW_CHAT"
        private const val RC_NEW_CHAT = 0x0A17

        /** 构造“打开即新对话”的 PendingIntent（FLAG_IMMUTABLE）。 */
        private fun newChatPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_NEW_CHAT
                putExtra(EXTRA_NEW_CHAT, true)
                // MainActivity 是 singleTask：已在前台时会走 onNewIntent；冷启动需要 NEW_TASK。
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                context,
                RC_NEW_CHAT,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        /**
         * 渲染并推送一个 widget 实例。
         * @param recentTitle 可选：最近一条对话标题；传非空即显示，传 null 只保留入口按钮。
         */
        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int,
            recentTitle: String? = null,
        ) {
            val views = RemoteViews(context.packageName, R.layout.xtom_widget)
            val pi = newChatPendingIntent(context)
            // 整块 root + 胶囊按钮都绑同一意图，点哪都能开新对话。
            views.setOnClickPendingIntent(R.id.xtom_widget_root, pi)
            views.setOnClickPendingIntent(R.id.xtom_widget_button, pi)

            if (!recentTitle.isNullOrBlank()) {
                views.setTextViewText(R.id.xtom_widget_recent, recentTitle)
                views.setViewVisibility(R.id.xtom_widget_recent, android.view.View.VISIBLE)
            } else {
                views.setViewVisibility(R.id.xtom_widget_recent, android.view.View.GONE)
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        for (id in appWidgetIds) {
            updateWidget(context, appWidgetManager, id, recentTitle = null)
        }
    }
}
