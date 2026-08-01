package com.arix.app

import androidx.compose.runtime.Composable

/**
 * 旧的「监控 & 风控」页。内容已并入 [ActivityCenterPage]（AI 活动中心）的「监控 & 风控」标签。
 *
 * 这里留一层转发而不是直接删：路由 "monitor" 可能被设置中心的入口、深链或用户习惯记着，
 * 删掉就是白屏。转发能让老入口无缝落到新页，等新路由挂好、设置里的旧入口摘掉之后，
 * 这个文件可以整个删除。
 *
 * 不传 onOpenRoute：走老路由进来时拿不到导航回调，那就干脆不显示「文件改动历史」入口，
 * 而不是渲染一个点了没反应的按钮。
 */
@Deprecated("已并入 ActivityCenterPage，路由请改用 activity_center", ReplaceWith("ActivityCenterPage(scope)"))
@Composable
fun MonitorPage(scope: kotlinx.coroutines.CoroutineScope) = ActivityCenterPage(scope = scope)
