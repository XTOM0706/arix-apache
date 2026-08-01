package com.arix.app

import android.app.Activity
import android.app.Application
import android.os.Bundle

/**
 * 应用前台状态追踪。唤醒路由用：应用在前台就不弹助手（在应用内不唤醒）。
 * XtomApp 注册 [callbacks]。
 */
object AppForeground {
    @Volatile
    var isForeground = false
        private set

    private var startedCount = 0

    /**
     * 当前处于 started（可见）状态的 Activity 数。唤醒浮层判断「自己是不是盖在自家界面上」用：
     * [WakeAssistantActivity] 是半透明 Activity，不会让背后的 [MainActivity] onStop，
     * 故自己已计入后计数 >1 即说明背后还有自家 UI（需要遮罩），==1 说明背后是桌面（保持透明磨砂）。
     */
    val startedActivityCount: Int get() = startedCount

    val callbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            startedCount++
            isForeground = startedCount > 0
        }

        override fun onActivityStopped(activity: Activity) {
            startedCount = (startedCount - 1).coerceAtLeast(0)
            isForeground = startedCount > 0
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityResumed(activity: Activity) {}
        override fun onActivityPaused(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }
}
