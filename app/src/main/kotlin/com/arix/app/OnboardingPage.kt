package com.arix.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Savings
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Waves
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.arix.app.theme.screenFitPadding
import com.arix.app.ui.FullMotion
import com.arix.app.ui.XtomButton
import com.arix.app.ui.XtomCard
import com.arix.app.ui.XtomField
import com.arix.app.ui.isShortScreen
import com.arix.app.ui.OvershootEasing
import com.arix.app.ui.rememberBreath
import com.arix.app.ui.rememberFrameFloat
import com.arix.app.ui.rememberFrameProgress
import com.arix.app.ui.revealVertically
import com.arix.app.ui.staggerIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ============================================================
// 新手向导 —— 首次启动的一条龙引导：欢迎 → 连模型 → 权限 → 唤醒 → 认识你 → 选角色 → 导览 → 完成。
//
// 定位：**真落配置**，不是纯教学。每一步写进各自既有的 Prefs / Room，走完就能直接聊天，
// 不需要用户再去设置页补一遍。因此这里不新建任何存储，全部复用现成入口：
//   连模型 → CloudApiConfigManager（Room api_configs，purpose="chat"）
//   权限   → 和 PermissionsPage 同一套系统 Intent / runtime permission
//   唤醒   → WakeService 的 prefs
//   认识你 → IdentityPrefs + UserPreferences
//   选角色 → CharacterCardManager（Room character_cards）
//
// 显示与否由 [OnboardingGate] 管，MainActivity 在 setContent 里二选一渲染（不叠在 MainScreen 上：
// 叠着的话背后整个聊天树还在组合/绘制，白烧一份帧预算）。
//
// ⚠ 性能红线（照 DESIGN-CHAT-PERF.md 那套，别重蹈聊天页覆辙）：
//   · 进度点只读 pagerState.currentPage（翻页才重组），**不读 currentPageOffsetFraction**（那是每帧）。
//   · 供应商 40+ 项走全屏 Dialog + LazyColumn(key/contentType)，不塞进纵向滚动的分页里嵌套滚动。
//   · 派生集合（供应商分组、权限清单）一律 remember，不每次重组重建。
//   · 权限状态一次性批量刷新到一个 Map，ON_RESUME 才重算；不在每行各自调 binder 查询。
//   · 不套 animateContentSize（聊天页那个双层动画的坑），展开/收起直接换内容。
// ============================================================

/**
 * 当前这一步的入场进度（0→1），由分页那层提供。各步内部用 [stepIn] 让卡片按序错峰浮现，
 * 省得每个 step 函数都多带一个参数往下传。
 *
 * 用 static 版是因为**提供的是同一个 State 实例**（`rememberFrameProgress` 的返回值在这一页的
 * 生命周期里不变），每帧变的是它的 `.floatValue`，而那个值只在 graphicsLayer 的 lambda 里读
 * ——绘制阶段的读取，不会引起重组。踩过的反面教材：static CompositionLocal 的 provides 传字面量
 * lambda，值每次重组都是新对象，整棵子树跟着全量重组（见 DESIGN-CHAT-PERF.md 第八节）。
 */
private val AlwaysShown: State<Float> = mutableFloatStateOf(1f)
private val LocalStepEnter = staticCompositionLocalOf { AlwaysShown }

/** 本步第 [index] 张卡：按序淡入 + 上浮。 */
@Composable
private fun Modifier.stepIn(index: Int): Modifier = this.staggerIn(LocalStepEnter.current, index)

private const val STEP_WELCOME = 0
private const val STEP_MODEL = 1
private const val STEP_PERMISSION = 2
private const val STEP_WAKE = 3
private const val STEP_IDENTITY = 4
private const val STEP_ROLE = 5
private const val STEP_TOUR = 6
private const val STEP_DONE = 7
private const val STEP_COUNT = 8

/**
 * @param onFinish 走完/跳过时回调。参数是「结束后想直接打开的页面路由」，没有就传 null
 *                 （唤醒那步的「结束后去录唤醒词」用它；由 MainActivity 落到 NavRetain）。
 */
@Composable
fun OnboardingPage(onFinish: (String?) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val compact = isShortScreen()

    val pager = rememberPagerState(pageCount = { STEP_COUNT })

    // 「连模型」那步是唯一会拦人的：没测通就不让往后走（但「跳过」永远可用）。
    var modelReady by remember { mutableStateOf(false) }
    // 结束后要跳的页面（唤醒那步可能设成 "wake"）
    var pendingRoute by remember { mutableStateOf<String?>(null) }

    // 用 settledPage 而不是 currentPage 判「拦不拦」：currentPage 在手指拖过一半时就翻，
    // 那一刻把 userScrollEnabled 关掉会掐断正在收尾的吸附动画，页面停在两页中间。
    // settledPage 只在完全停稳后变，关滚动时已经没有在飞的手势了。
    val locked = pager.settledPage == STEP_MODEL && !modelReady

    // 按钮翻页带 FullMotion：手表上「系统动画缩放=0」很常见，不加的话 animateScrollToPage 直接瞬移，
    // 下面那套视差/入场全白搭（手指拖动不受影响，因为那是跟手的，不走动画系统）。
    fun go(page: Int) { scope.launch(FullMotion) { pager.animateScrollToPage(page.coerceIn(0, STEP_COUNT - 1)) } }
    fun finish() = onFinish(pendingRoute)

    // 系统返回 = 上一步。第一页不接管，让它照常退出 App（首启时用户想走就该能走）。
    BackHandler(enabled = pager.currentPage > 0) { go(pager.currentPage - 1) }

    Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().screenFitPadding()) {

            // ---- 顶部：进度点 + 步骤名 ----
            StepHeader(pager = pager, compact = compact)

            // ---- 中部：分页内容 ----
            HorizontalPager(
                state = pager,
                modifier = Modifier.weight(1f),
                // 被拦住时禁掉滑动（只有这一种情况），其余步骤保留左右滑
                userScrollEnabled = !locked,
                // 不预组合相邻页：每页都是一整屏表单/清单，预建两页等于三倍首帧成本
                beyondViewportPageCount = 0,
                pageSpacing = 0.dp,
                key = { it },
            ) { page ->
                // 每页出场时依次浮现（每次这一页被组合就跑一遍；beyondViewportPageCount=0 意味着
                // 页面滑进来才组合，时机天然对得上）。给各步内部的卡片当错峰入场的总进度用。
                val enter = rememberFrameProgress(key = page, durationMs = 420)
                Column(
                    Modifier.fillMaxSize()
                        // 视差 + 景深：跟手指走，不经动画系统，所以动画缩放=0 也照样有。
                        // 三个量全在 graphicsLayer 的 lambda 里读 —— 绘制阶段的读取，只让图层失效，
                        // 整页不会每帧重组（读 currentPageOffsetFraction 是每帧变的，直接在组合里读就完了）。
                        .graphicsLayer {
                            val off = (pager.currentPage - page) + pager.currentPageOffsetFraction
                            val d = kotlin.math.abs(off).coerceIn(0f, 1f)
                            alpha = 1f - d * 0.55f
                            val s = 1f - d * 0.06f
                            scaleX = s; scaleY = s
                            translationX = off * size.width * 0.10f
                        }
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = if (compact) 12.dp else 18.dp),
                ) {
                    Spacer(Modifier.height(4.dp))
                    CompositionLocalProvider(LocalStepEnter provides enter) {
                    when (page) {
                        STEP_WELCOME -> WelcomeStep(compact)
                        STEP_MODEL -> ModelStep(compact = compact, onReadyChange = { modelReady = it })
                        STEP_PERMISSION -> PermissionStep(compact)
                        STEP_WAKE -> WakeStep(compact = compact, gotoWake = pendingRoute == "wake",
                            onGotoWakeChange = { pendingRoute = if (it) "wake" else null })
                        STEP_IDENTITY -> IdentityStep(compact)
                        STEP_ROLE -> RoleStep(compact)
                        STEP_TOUR -> TourStep(compact)
                        STEP_DONE -> DoneStep(compact)
                    }
                    }
                    Spacer(Modifier.height(16.dp))
                }
            }

            // ---- 底部：跳过 / 上一步 / 下一步 ----
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (pager.currentPage < STEP_DONE) {
                    TextButton(onClick = { finish() }) {
                        Text(tr("跳过"), color = scheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                } else {
                    Spacer(Modifier.width(8.dp))
                }
                Spacer(Modifier.weight(1f))
                if (pager.currentPage > 0) {
                    TextButton(onClick = { go(pager.currentPage - 1) }) {
                        Text(tr("上一步"), color = scheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // 「下一步」在连模型那步测通的瞬间由灰变亮，这一下是重要反馈：用户要靠它知道「可以走了」。
                // 灰->亮走帧驱动的透明度，别让动画缩放=0 的表上直接硬跳。
                val ready = rememberFrameFloat(if (locked) 0.45f else 1f, durationMs = 260)
                XtomButton(
                    onClick = { if (pager.currentPage >= STEP_DONE) finish() else go(pager.currentPage + 1) },
                    enabled = !locked,
                    modifier = Modifier.graphicsLayer { alpha = ready.value },
                ) {
                    Text(
                        if (pager.currentPage >= STEP_DONE) tr("开始聊天") else tr("下一步"),
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

/**
 * 顶部进度条 + 当前步骤名。
 *
 * 进度点整条画在一个 Canvas 里，分页位置**在 draw lambda 里读** —— 绘制阶段的读取，
 * 只重绘这一小条，不重组、更不重排。原来那版给 8 个点各挂一个 animateDpAsState：
 * 既是 8 份动画、宽度变化还会让整行重新测量，而且动画缩放=0 时根本不动。
 * 现在活动指示器跟着手指连续滑，松手前就一直跟手。
 */
@Composable
private fun StepHeader(pager: PagerState, compact: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val dot = 5.dp
    val gap = 5.dp
    val pill = 16.dp
    Column(Modifier.fillMaxWidth().padding(top = if (compact) 8.dp else 16.dp, bottom = 4.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(dot)
        ) {
            val dotPx = dot.toPx(); val gapPx = gap.toPx(); val pillPx = pill.toPx()
            val slot = dotPx + gapPx
            val totalW = slot * (STEP_COUNT - 1) + pillPx
            val left = (size.width - totalW) / 2f
            val r = dotPx / 2f
            // 连续位置：0..STEP_COUNT-1 之间的小数，拖动过程中就是分数值
            val pos = (pager.currentPage + pager.currentPageOffsetFraction)
                .coerceIn(0f, (STEP_COUNT - 1).toFloat())
            // 底：全部画成小圆点（活动指示器盖在上面，所以这里不必挖空）
            repeat(STEP_COUNT) { i ->
                // 已走过的点亮一些，让「还剩几步」一眼看得出
                val passed = i <= pos + 0.001f
                drawRoundRect(
                    color = if (passed) scheme.primary.copy(alpha = 0.45f) else scheme.surfaceContainerHighest,
                    topLeft = Offset(left + slot * i, 0f),
                    size = Size(dotPx, dotPx),
                    cornerRadius = CornerRadius(r, r),
                )
            }
            // 活动指示器：一颗拉长的胶囊，随 pos 连续平移
            drawRoundRect(
                color = scheme.primary,
                topLeft = Offset(left + slot * pos, 0f),
                size = Size(pillPx, dotPx),
                cornerRadius = CornerRadius(r, r),
            )
        }
        Spacer(Modifier.height(if (compact) 4.dp else 8.dp))
        // 步骤名换行时淡入 + 轻微上浮：settledPage 停稳才换字，拖动途中不闪
        val settled = pager.settledPage
        val titleFade = rememberFrameProgress(key = settled, durationMs = 260)
        Text(
            "${settled + 1}/$STEP_COUNT  ${stepTitle(settled)}",
            color = scheme.onSurfaceVariant,
            fontSize = 11.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp)
                .graphicsLayer {
                    alpha = titleFade.value
                    translationY = 6.dp.toPx() * (1f - titleFade.value)
                },
        )
    }
}

// tr() 直接包在字面量上：抽取脚本（tools/i18n_wrap.py）只认「tr( 后面紧跟双引号字面量」这一种形状，
// 写成 tr(变量) 的话这些串永远进不了翻译表，切成英文界面还是中文。
// 同理注释里也别摆出那个形状，否则会被当成真串抽进表里（已经踩过一次）。
private fun stepTitle(step: Int): String = when (step) {
    STEP_WELCOME -> tr("欢迎")
    STEP_MODEL -> tr("连接模型")
    STEP_PERMISSION -> tr("权限")
    STEP_WAKE -> tr("语音唤醒")
    STEP_IDENTITY -> tr("认识你")
    STEP_ROLE -> tr("选个角色")
    STEP_TOUR -> tr("能做什么")
    else -> tr("完成")
}

// ============================================================
// 通用小件
// ============================================================

/** 每步顶部的大标题 + 一句人话说明。整块当作本步第 0 个入场元素。 */
@Composable
private fun StepTitle(icon: ImageVector, title: String, subtitle: String, compact: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(Modifier.stepIn(0)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = scheme.primary, modifier = Modifier.size(if (compact) 20.dp else 24.dp))
            Spacer(Modifier.width(8.dp))
            Text(title, color = scheme.onSurface, fontSize = if (compact) 16.sp else 19.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(subtitle, color = scheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 17.sp)
    }
    Spacer(Modifier.height(if (compact) 10.dp else 14.dp))
}

/** 一条「图标 + 标题 + 说明」的静态条目，导览/欢迎页用。 */
@Composable
private fun FeatureRow(icon: ImageVector, title: String, desc: String) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Icon(icon, null, tint = scheme.primary, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(desc, color = scheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
        }
    }
}

/** 结果条：成功/失败/进行中三态，用图标不用 emoji。换态时淡入 + 上浮，别硬闪。 */
@Composable
private fun ResultLine(state: TestState, message: String) {
    val scheme = MaterialTheme.colorScheme
    if (state == TestState.IDLE) return
    val appear = rememberFrameProgress(key = state, durationMs = 220)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 6.dp).graphicsLayer {
            alpha = appear.value
            translationY = 5.dp.toPx() * (1f - appear.value)
        },
    ) {
        when (state) {
            TestState.TESTING -> CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 2.dp, color = scheme.primary)
            TestState.OK -> Icon(Icons.Outlined.Check, null, tint = scheme.primary, modifier = Modifier.size(15.dp))
            else -> Icon(Icons.Outlined.ErrorOutline, null, tint = scheme.error, modifier = Modifier.size(15.dp))
        }
        Spacer(Modifier.width(6.dp))
        Text(
            message,
            color = if (state == TestState.FAIL) scheme.error else scheme.onSurfaceVariant,
            fontSize = 11.sp,
            lineHeight = 15.sp,
        )
    }
}

private enum class TestState { IDLE, TESTING, OK, FAIL }

// ============================================================
// 1. 欢迎
// ============================================================

@Composable
private fun WelcomeStep(compact: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val context = LocalContext.current
    Spacer(Modifier.height(if (compact) 4.dp else 20.dp))

    // 图标：入场时从小弹到位（带一点回弹），落定后一圈光晕缓慢呼吸。
    // 全走 withFrameNanos，动画缩放=0 也照样动；呼吸只画在 Canvas 里，不重组。
    val pop = rememberFrameProgress(key = Unit, durationMs = 520, easing = OvershootEasing)
    val breath = rememberBreath(periodMs = 3200)
    val badge = if (compact) 48.dp else 64.dp
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(badge + 20.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val b = breath.value                       // 0..1 来回
            val r = (badge.toPx() / 2f) * (1f + 0.13f * b)
            drawCircle(
                color = scheme.primary.copy(alpha = 0.16f * (1f - b) * pop.value),
                radius = r,
            )
        }
        Box(
            Modifier.size(badge)
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) }
                .background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(if (compact) 26.dp else 34.dp))
        }
    }

    Spacer(Modifier.height(14.dp))
    Column(Modifier.stepIn(1)) {
        Text(tr("欢迎使用 Arix"), color = scheme.onSurface, fontSize = if (compact) 20.sp else 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            tr("一个能听懂你说话、能动手替你办事的助手。它跑在你自己的设备上，模型和密钥由你自己选、自己填，数据不经过我们的服务器。"),
            color = scheme.onSurfaceVariant, fontSize = 12.sp, lineHeight = 18.sp,
        )
    }
    Spacer(Modifier.height(14.dp))
    XtomCard(modifier = Modifier.stepIn(2)) {
        Text(tr("接下来会做这几件事"), color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        FeatureRow(Icons.Outlined.Bolt, tr("连上一个大模型"), tr("有免费的可以直接试，也可以填自己的密钥"))
        FeatureRow(Icons.Outlined.Shield, tr("按需要开权限"), tr("每项都写清楚开了能干嘛，不想开就不开"))
        FeatureRow(Icons.Outlined.Face, tr("告诉它你是谁"), tr("名字、称呼、想要什么脾气的助手"))
    }
    Spacer(Modifier.height(10.dp))
    // 语言：首启默认跟随系统语言，这里可随时直接切（整棵内容随 key(lang) 重组即时生效）
    Text(tr("语言 / Language"), color = scheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.stepIn(3))
    Spacer(Modifier.height(4.dp))
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).stepIn(3), verticalAlignment = Alignment.CenterVertically) {
        val curLang by com.arix.app.I18n.lang.collectAsState()
        com.arix.app.I18n.Lang.entries.forEach { l ->
            androidx.compose.material3.Surface(onClick = { com.arix.app.I18n.set(context, l) }, shape = RoundedCornerShape(50), color = if (curLang == l) scheme.primary else scheme.surfaceContainerHighest, modifier = Modifier.padding(end = 6.dp)) {
                Text(l.label, color = if (curLang == l) scheme.onPrimary else scheme.onSurface, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    Text(
        tr("大概两分钟。随时可以点左下角「跳过」，之后在 设置 → 新手向导 还能再走一遍。"),
        color = scheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
        modifier = Modifier.stepIn(3),
    )
}

// ============================================================
// 2. 连接模型
// ============================================================

/**
 * 元任务用的模型用途名。项目里的后台小活分两拨取配置：
 * `title` = 起标题/建议芯片/记忆抽取/状态卡；`summary` = 上下文摘要/记忆整理/冲突消解/挑记忆。
 * 向导里配小模型时**两个一起配**——只配一个的话另一半照旧回退主模型，用户会觉得"配了没用"。
 */
private val META_PURPOSES = listOf("title", "summary")

@Composable
private fun ModelStep(compact: Boolean, onReadyChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val cfgManager = remember { CloudApiConfigManager(context) }

    // 这些必须 rememberSaveable：分页只组合当前页（beyondViewportPageCount=0），
    // 往后翻一页再翻回来这一页是重新组合的，普通 remember 会把用户刚敲进去的密钥清空。
    // Pager 给每页套了 SaveableStateProvider，rememberSaveable 才跨得过这次销毁。
    var name by rememberSaveable { mutableStateOf("") }
    var base by rememberSaveable { mutableStateOf("") }
    var key by rememberSaveable { mutableStateOf("") }
    var model by rememberSaveable { mutableStateOf("") }
    var keyUrl by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    // 已有配置的 id：向导重跑时改这一条，不再多塞一行重复配置
    var existingId by rememberSaveable { mutableStateOf<Long?>(null) }
    var state by rememberSaveable { mutableStateOf(TestState.IDLE) }
    var message by rememberSaveable { mutableStateOf("") }
    // 进来时本来就有配置（重跑向导的人）。用它决定要不要露「先免费试一下」，
    // 而不是用 existingId——否则点完一键试用、刚存上，那张卡就当场消失，界面跳一下。
    var preconfigured by rememberSaveable { mutableStateOf(false) }
    var loaded by rememberSaveable { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    // 元任务小模型（可选）：见下面 MetaModelCard 的注释
    var metaModel by rememberSaveable { mutableStateOf("") }
    var metaMsg by rememberSaveable { mutableStateOf("") }
    var metaSaving by rememberSaveable { mutableStateOf(false) }

    // 进来先读现有的「对话」模型：重跑向导的人不该被要求从头再填一遍。
    // loaded 挡住重入：翻页回来时若再读一次库，会把用户还没保存的输入覆盖掉。
    LaunchedEffect(Unit) {
        if (loaded) return@LaunchedEffect
        val cur = withContext(Dispatchers.IO) { cfgManager.getActiveByPurpose("chat") ?: cfgManager.getActive() }
        // 已经配过元任务小模型的（重跑向导的人）把模型名回填，别让他以为没配过又填一遍
        metaModel = withContext(Dispatchers.IO) { cfgManager.getActiveByPurpose(META_PURPOSES.first())?.model }.orEmpty()
        loaded = true
        if (cur != null) {
            existingId = cur.id; name = cur.name; base = cur.baseUrl; key = cur.apiKey; model = cur.model
            preconfigured = true
            state = TestState.OK
            message = tr("已经配好了：") + cur.name
            onReadyChange(true)
        }
    }
    // 翻页回来重建时，把「已通过」这个结论同步回上层（上层的 modelReady 本身没丢，这里只是防止不同步）
    LaunchedEffect(state) { if (state == TestState.OK) onReadyChange(true) }

    fun pick(p: ApiProvider) {
        name = p.name; base = p.base; model = p.model; keyUrl = p.keyUrl; note = p.note
        if (p.free == FreeKind.NO_KEY) key = ""
        state = TestState.IDLE; message = ""
        onReadyChange(false)
    }

    // 存盘：成功测通后写库，或用户明确选择「不测直接存」。
    // 状态回写一律在 withContext 之后（主线程）做，别在 IO 块里改 Compose state。
    fun save(onDone: () -> Unit) {
        scope.launch {
            val id = existingId
            val label = name.ifBlank { tr("我的模型") }
            val savedId = withContext(Dispatchers.IO) {
                if (id == null) {
                    // add() 内部会 switchTo（设为激活），purpose 默认 "chat"
                    cfgManager.add(name = label, baseUrl = base, apiKey = key, model = model)
                } else {
                    cfgManager.update(
                        id = id, name = label,
                        baseUrl = base, apiKey = key, model = model,
                        purpose = "chat", isActive = true,
                    )
                    cfgManager.switchTo(id)
                    id
                }
            }
            existingId = savedId
            onDone()
        }
    }

    fun test(saveAnyway: Boolean = false) {
        if (base.isBlank() || model.isBlank()) {
            state = TestState.FAIL; message = tr("请先选一个服务商，或把接口地址和模型名填上"); return
        }
        state = TestState.TESTING; message = tr("正在发一条测试消息…")
        scope.launch {
            val err = try {
                val client = com.arix.cloudapi.CloudApiClient(
                    com.arix.cloudapi.CloudApiConfig(base.trimEnd('/'), key.trim(), model.trim())
                )
                client.streamChat(
                    listOf(com.arix.cloudapi.model.ChatMessage("user", "hi")),
                    null, 0, null, onReasoningChunk = {}, onContentChunk = {},
                ).error
            } catch (c: kotlinx.coroutines.CancellationException) {
                throw c   // 别把取消当成失败吞掉（项目铁律）
            } catch (e: Exception) {
                e.message ?: tr("连不上")
            }
            if (err == null) {
                save { state = TestState.OK; message = tr("连通了，已经保存并设为当前对话模型"); onReadyChange(true) }
            } else {
                state = TestState.FAIL
                message = err
                onReadyChange(false)
                if (saveAnyway) save { }
            }
        }
    }

    StepTitle(
        Icons.Outlined.Bolt, tr("连接大模型"),
        tr("Arix 本身不带模型，得先接一个。下面有不用注册就能用的，也可以填自己的密钥。"),
        compact,
    )

    // —— 置顶：一键免费试用 ——
    val freebie = remember { ApiProviders.all.firstOrNull { it.free == FreeKind.NO_KEY && it.base.startsWith("https") } }
    if (freebie != null && !preconfigured) {
        XtomCard(modifier = Modifier.stepIn(1)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Bolt, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("先免费试一下"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        tr("不用注册、不用密钥，点一下就能开始聊。之后随时能换成更好的模型。"),
                        color = scheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                    // ⚠ 必须写清楚对话发给谁。
                    // 这是新用户最可能点的一个按钮，而"不用注册、不用密钥"这句话很容易被理解成
                    // "那就是本地跑的"。实际上聊天内容会完整发给一个**第三方公共端点**，没有账号绑定
                    // 也就意味着我们和用户都对它无从约束。免费不是问题，不告诉人家才是。
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tr("注意：聊天内容会发送到第三方公共服务 %s，不需要账号、也不受本应用控制。别在这个模型下聊敏感内容。")
                            .format(freebie.name),
                        color = scheme.onSurfaceVariant.copy(alpha = 0.85f), fontSize = 10.sp, lineHeight = 14.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            XtomButton(onClick = { pick(freebie); test() }, enabled = state != TestState.TESTING) {
                Text(tr("一键使用免费模型"), fontSize = 12.sp)
            }
        }
        Spacer(Modifier.height(10.dp))
    }

    // —— 选服务商 ——
    XtomCard(modifier = Modifier.stepIn(2)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showPicker = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("服务商"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                Text(
                    name.ifBlank { tr("点这里挑一个") },
                    color = if (name.isBlank()) scheme.onSurfaceVariant else scheme.onSurface,
                    fontSize = 13.sp, fontWeight = FontWeight.Medium,
                )
            }
            Icon(Icons.Outlined.ChevronRight, null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        if (note.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(note, color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
        }
        if (keyUrl.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            TextButton(
                onClick = {
                    try {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(keyUrl)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    } catch (_: Exception) {}
                },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
            ) { Text(tr("去官网领密钥"), color = scheme.primary, fontSize = 11.sp) }
        }

        Spacer(Modifier.height(8.dp))
        XtomField(value = base, onValueChange = { base = it; onReadyChange(false); state = TestState.IDLE },
            label = tr("接口地址"), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        XtomField(value = key, onValueChange = { key = it; onReadyChange(false); state = TestState.IDLE },
            label = tr("密钥（不需要就留空）"), password = true, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(8.dp))
        XtomField(value = model, onValueChange = { model = it; onReadyChange(false); state = TestState.IDLE },
            label = tr("模型名"), modifier = Modifier.fillMaxWidth())

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            XtomButton(onClick = { test() }, enabled = state != TestState.TESTING) {
                Text(tr("测试并保存"), fontSize = 12.sp)
            }
            if (state == TestState.FAIL) {
                Spacer(Modifier.width(6.dp))
                TextButton(onClick = { save { state = TestState.OK; message = tr("已保存（没测通，聊天时可能报错）"); onReadyChange(true) } }) {
                    Text(tr("不测了，直接保存"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
        ResultLine(state, message)
    }

    if (state != TestState.OK) {
        Spacer(Modifier.height(8.dp))
        Text(
            tr("这一步测通了才能继续。实在配不上就点左下角「跳过」，之后在 设置 → 模型配置 里再弄。"),
            color = scheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
        )
    } else {
        // 主模型配好了再露这张卡：没配主模型时它无处沿用地址和密钥，先问只会让人一头雾水
        Spacer(Modifier.height(10.dp))
        XtomCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Savings, null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("配个便宜的小模型（可选）"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text(
                        tr("起标题、记住你说过的事、判断要不要放行工具——这些后台小活默认也在用上面那个模型。填一个便宜或免费的小模型名，它们就改用那个，聊天本身不受影响。"),
                        color = scheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            XtomField(
                value = metaModel, onValueChange = { metaModel = it; metaMsg = "" },
                label = tr("小模型名（接口地址和密钥沿用上面那条）"), modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            XtomButton(
                onClick = {
                    val m = metaModel.trim()
                    if (m.isBlank()) { metaMsg = tr("先填一个模型名"); return@XtomButton }
                    metaSaving = true
                    scope.launch {
                        // 一次写两个用途：项目里的元任务分别取 "title"（标题/建议/记忆抽取/状态卡）
                        // 和 "summary"（摘要/记忆整理/冲突消解）。只配一个的话另一半照旧回退到主模型，
                        // 用户会觉得"配了没用"。两个都指向同一个小模型，行为才和这张卡说的一致。
                        withContext(Dispatchers.IO) {
                            META_PURPOSES.forEach { p ->
                                val exist = cfgManager.getActiveByPurpose(p)
                                if (exist == null) {
                                    cfgManager.add(name = tr("小模型·") + p, baseUrl = base, apiKey = key, model = m, purpose = p)
                                } else {
                                    cfgManager.update(
                                        id = exist.id, name = exist.name, baseUrl = base, apiKey = key,
                                        model = m, purpose = p, isActive = true,
                                    )
                                    cfgManager.switchTo(exist.id)
                                }
                            }
                        }
                        metaSaving = false
                        metaMsg = tr("好了，后台小活改用它了。想换回去就在 设置 → 模型配置 里删掉这两条。")
                    }
                },
                enabled = !metaSaving,
            ) { Text(if (metaSaving) tr("保存中…") else tr("保存"), fontSize = 12.sp) }
            if (metaMsg.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(metaMsg, color = scheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 15.sp)
            }
        }
    }

    if (showPicker) {
        ProviderPickerDialog(
            onPick = { showPicker = false; pick(it) },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * 服务商选择器：40+ 项，走全屏 Dialog + LazyColumn。
 * 不做成分页内的嵌套纵向列表——那既是嵌套滚动冲突，又会把整份清单一次性组合出来。
 */
@Composable
private fun ProviderPickerDialog(onPick: (ApiProvider) -> Unit, onDismiss: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    // 拍平成「组标题 + 条目」的单层列表：LazyColumn 只处理可见项，key 用名称（唯一且稳定）
    val rows = remember {
        ApiProviders.groups.flatMap { g ->
            listOf<Any>(g.title) + g.items
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = scheme.background, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().screenFitPadding().padding(horizontal = 10.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(tr("选择服务商"), color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Outlined.Close, contentDescription = tr("关闭"), tint = scheme.onSurfaceVariant)
                    }
                }
                androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize()) {
                    items(
                        count = rows.size,
                        key = { i -> (rows[i] as? ApiProvider)?.name ?: "g_${rows[i]}" },
                        contentType = { i -> if (rows[i] is ApiProvider) "item" else "header" },
                    ) { i ->
                        when (val row = rows[i]) {
                            is String -> Text(
                                tr(row), color = scheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 4.dp),
                            )
                            is ApiProvider -> Column(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(row) }
                                    .padding(horizontal = 6.dp, vertical = 9.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(tr(row.name), color = scheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
                                    if (row.free != FreeKind.NONE) {
                                        Text(
                                            if (row.free == FreeKind.NO_KEY) tr("免密钥") else tr("有免费额度"),
                                            color = scheme.primary, fontSize = 10.sp,
                                        )
                                    }
                                }
                                if (row.note.isNotBlank()) {
                                    Text(tr(row.note), color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp, maxLines = 3)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ============================================================
// 3. 权限（必需 / 推荐 / 高级 三档）
// ============================================================

private class WizPerm(
    val tier: Int,            // 0=必需 1=推荐 2=高级
    val label: String,
    val why: String,
    val granted: (Context) -> Boolean,
    val request: () -> Unit,
)

@Composable
private fun PermissionStep(compact: Boolean) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val sdk = Build.VERSION.SDK_INT
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    // 一次性批量算出的授权状态：key=label。每行各自去查 binder 的话，16 项 × 每次重组 = 白烧主线程
    var granted by remember { mutableStateOf<Map<String, Boolean>>(emptyMap()) }
    var tick by remember { mutableStateOf(0) }

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    fun openSys(intent: Intent) {
        try { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {
            try {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {}
        }
    }
    fun has(p: String) = androidx.core.content.ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED

    // 清单只建一次。文案是「开了能干嘛」的人话，跟权限页同口径。
    val perms = remember {
        listOf(
            WizPerm(0, tr("录音"), tr("跟它说话、语音唤醒都靠这个。不开就只能打字。"),
                { has(Manifest.permission.RECORD_AUDIO) },
                { runtimeLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }),
            WizPerm(0, tr("通知"), tr("提醒、日记、后台任务做完了要通知你。"),
                { NotificationBootstrap.granted(context) },
                {
                    // ⚠ 不能直接 requestPermissions：本 App targetSdk=28，Android 13+ 对这类应用
                    // **不弹框、直接返回拒绝**。真正的入口是"创建第一个通知渠道"，见 NotificationBootstrap。
                    NotificationBootstrap.request(
                        context,
                        requestPermission = { if (sdk >= Build.VERSION_CODES.TIRAMISU) runtimeLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) },
                        openSettings = { openSys(it) },
                    )
                }),
            WizPerm(0, tr("电池优化豁免"), tr("不开的话，锁屏一会儿系统就把它杀了——唤醒词、定时提醒全会失灵。"),
                { (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager).isIgnoringBatteryOptimizations(context.packageName) },
                { openSys(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}"))) }),

            WizPerm(1, tr("悬浮窗"), tr("语音助手浮层、全屏提醒；AI 打开别的应用时，授权框也要靠它弹在上面。"),
                { Settings.canDrawOverlays(context) },
                { openSys(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) }),
            WizPerm(1, tr("所有文件访问"), tr("让 AI 读写手机里的文件（整理、改文档、找东西）。"),
                {
                    if (sdk >= Build.VERSION_CODES.R) android.os.Environment.isExternalStorageManager()
                    else has(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                },
                {
                    if (sdk >= Build.VERSION_CODES.R)
                        openSys(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}")))
                    else runtimeLauncher.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                }),
            WizPerm(1, tr("相册 / 媒体"), tr("发图给它看、让它解析截图和视频。"),
                { has(if (sdk >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE) },
                {
                    runtimeLauncher.launch(
                        if (sdk >= Build.VERSION_CODES.TIRAMISU) arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO)
                        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
                    )
                }),
            WizPerm(1, tr("位置"), tr("查天气、找附近的地方。"),
                { has(Manifest.permission.ACCESS_FINE_LOCATION) || has(Manifest.permission.ACCESS_COARSE_LOCATION) },
                { runtimeLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) }),
            WizPerm(1, tr("相机"), tr("举起来拍一张让它认。"),
                { has(Manifest.permission.CAMERA) },
                { runtimeLauncher.launch(arrayOf(Manifest.permission.CAMERA)) }),

            WizPerm(2, tr("无障碍（界面自动化）"), tr("让 AI 替你点按钮、滑屏幕、读屏幕上的内容。权限很大，想让它真动手才开。"),
                { XtomAccessibilityService.instance != null },
                { openSys(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
            WizPerm(2, tr("通知感知"), tr("让它知道你收到了什么通知（微信、短信、日程）。"),
                { NotificationAwarenessPrefs.hasAccess(context) },
                { openSys(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }),
            WizPerm(2, tr("使用情况访问"), tr("让它知道你在用哪些 App、屏幕时间多久。"),
                { com.arix.tool.UsageStatsTool.hasAccess(context) },
                { openSys(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }),
            WizPerm(2, tr("通讯录"), tr("按名字打电话、发消息。"),
                { has(Manifest.permission.READ_CONTACTS) },
                { runtimeLauncher.launch(arrayOf(Manifest.permission.READ_CONTACTS)) }),
            WizPerm(2, tr("日历"), tr("直接读你的日程、帮你建日程。"),
                { has(Manifest.permission.READ_CALENDAR) && has(Manifest.permission.WRITE_CALENDAR) },
                { runtimeLauncher.launch(arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)) }),
            WizPerm(2, tr("修改系统设置"), tr("调亮度、调音量。"),
                { try { Settings.System.canWrite(context) } catch (_: Exception) { false } },
                { openSys(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))) }),
        )
    }

    // 去系统设置页授权是「离开 App 再回来」，必须在 ON_RESUME 重算，否则回来还显示未授权。
    // 批量算一次放进 Map（每项都是 binder 调用，挪到 Default 线程，不占主线程）。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    LaunchedEffect(tick) {
        granted = withContext(Dispatchers.Default) {
            perms.associate { it.label to runCatching { it.granted(context) }.getOrDefault(false) }
        }
    }

    StepTitle(
        Icons.Outlined.Shield, tr("开哪些权限"),
        tr("每项都写了开了能干嘛。不想开就不开，对应的功能不能用而已，其余照常。"),
        compact,
    )

    val tiers = remember(perms) { perms.groupBy { it.tier } }

    PermTierCard(tr("必需"), tr("不开这几项，基本功能会不正常"), tiers[0].orEmpty(), granted, index = 1)
    Spacer(Modifier.height(10.dp))
    PermTierCard(tr("推荐"), tr("开了明显更好用"), tiers[1].orEmpty(), granted, index = 2)
    Spacer(Modifier.height(10.dp))

    XtomCard(modifier = Modifier.stepIn(3)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { showAdvanced = !showAdvanced }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("高级"), color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Text(tr("权限比较大，按需要再开"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
            }
            // 一个箭头转 90°，别用两个图标硬切
            val arrow = rememberFrameFloat(if (showAdvanced) 1f else 0f, durationMs = 220)
            Icon(
                Icons.Outlined.ChevronRight, null, tint = scheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = 90f * arrow.value },
            )
        }
        // 展开走 revealVertically：帧驱动地裁高度，等价 animateContentSize 的手感但动画缩放=0 也动。
        // 内容一直组合着（收起时高度为 0），所以展开不用等首次组合，第一帧就有东西可露。
        // 仍然不套 animateContentSize —— 双层展开动画是聊天页踩过的坑。
        val reveal = rememberFrameFloat(if (showAdvanced) 1f else 0f, durationMs = 260)
        Column(Modifier.revealVertically(reveal).graphicsLayer { alpha = reveal.value }) {
            Spacer(Modifier.height(4.dp))
            tiers[2].orEmpty().forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
                PermRow(p, granted[p.label] == true)
            }
        }
    }
}

@Composable
private fun PermTierCard(title: String, hint: String, items: List<WizPerm>, granted: Map<String, Boolean>, index: Int) {
    val scheme = MaterialTheme.colorScheme
    XtomCard(modifier = Modifier.stepIn(index)) {
        Text(title, color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Text(hint, color = scheme.onSurfaceVariant, fontSize = 10.sp)
        Spacer(Modifier.height(4.dp))
        items.forEachIndexed { i, p ->
            if (i > 0) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
            PermRow(p, granted[p.label] == true)
        }
    }
}

@Composable
private fun PermRow(p: WizPerm, ok: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            // label/why 在清单定义处就已经 tr() 过了，这里别再包一层（二次查表必然落空回退中文）
            Text(p.label, color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
            Text(p.why, color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Spacer(Modifier.width(8.dp))
        // 授权是「跳去系统设置、回来」的流程，回到向导时这一下变化必须看得见，否则用户不确定成没成。
        // 首次组合不播（key 初值就是当前状态），只有真的从「未开」翻成「已开」才弹一下。
        if (ok) {
            val pop = rememberFrameProgress(key = Unit, durationMs = 380, easing = OvershootEasing)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) },
            ) {
                Icon(Icons.Outlined.Check, null, tint = scheme.primary, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(3.dp))
                Text(tr("已开"), color = scheme.primary, fontSize = 11.sp)
            }
        } else {
            TextButton(onClick = p.request, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp)) {
                Text(tr("去开启"), color = scheme.primary, fontSize = 11.sp)
            }
        }
    }
}

// ============================================================
// 4. 语音唤醒
// ============================================================

@Composable
private fun WakeStep(compact: Boolean, gotoWake: Boolean, onGotoWakeChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var bgWake by remember { mutableStateOf(WakeService.bgWakeEnabled(context)) }
    var lockWake by remember { mutableStateOf(WakeService.lockScreenWakeEnabled(context)) }
    var greeting by remember { mutableStateOf(WakeService.wakeGreeting(context)) }

    StepTitle(
        Icons.Outlined.Mic, tr("语音唤醒"),
        tr("喊一声就能叫出它，不用先解锁点图标。唤醒词要你自己录一遍（录的是你的声音，只存在本机）。"),
        compact,
    )

    XtomCard(modifier = Modifier.stepIn(1)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("后台唤醒"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(tr("退出 App 后也能喊出来。默认亮屏才听，不是一直在听。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Switch(checked = bgWake, onCheckedChange = { bgWake = it; WakeService.setBgWake(context, it) })
        }
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("锁屏也能唤醒"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(tr("锁着屏幕也应答。方便，但别人也能喊。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Switch(checked = lockWake, onCheckedChange = { lockWake = it; WakeService.setLockScreenWake(context, it) })
        }
    }

    Spacer(Modifier.height(10.dp))
    XtomCard(modifier = Modifier.stepIn(2)) {
        Text(tr("唤醒后的第一句"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(tr("喊醒它之后它先说这句。留空就不说话，直接听你讲。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
        Spacer(Modifier.height(6.dp))
        XtomField(
            value = greeting,
            onValueChange = { greeting = it; WakeService.setWakeGreeting(context, it) },
            placeholder = tr("在呢"), modifier = Modifier.fillMaxWidth(),
        )
    }

    Spacer(Modifier.height(10.dp))
    XtomCard(modifier = Modifier.stepIn(3)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tr("向导结束后去录唤醒词"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(tr("录音要念好几遍，在这一步做太赶。走完向导直接带你去。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Switch(checked = gotoWake, onCheckedChange = onGotoWakeChange)
        }
    }
}

// ============================================================
// 5. 认识你
// ============================================================

/**
 * 语气选项。[value] 会被 UserPreferences 存下来、拼进系统提示词，所以**永远是中文原串、不翻译**
 * ——提示词是喂给模型的，不属于界面文字（I18n.kt 开头那条铁律）。[label]/[desc] 才是给人看的，走 tr()。
 */
private class ToneChoice(val value: String, val label: String, val desc: String)

@Composable
private fun IdentityStep(compact: Boolean) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    var userName by remember { mutableStateOf(IdentityPrefs.userName(context)) }
    var userAvatar by remember { mutableStateOf(IdentityPrefs.userAvatar(context)) }
    var tone by remember { mutableStateOf(UserPreferences.getTone(context)) }

    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // 不 take 持久授权的话，重启后头像就 403 变空白
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            IdentityPrefs.setUserAvatar(context, uri.toString()); userAvatar = uri.toString()
        }
    }

    StepTitle(
        Icons.Outlined.Face, tr("让它认识你"),
        tr("填了它就知道该怎么称呼你、用什么调子说话。都可以留空，之后在个性化页随时改。"),
        compact,
    )

    XtomCard(modifier = Modifier.stepIn(1)) {
        Text(tr("你的名字"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(tr("它会这么叫你。"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
        Spacer(Modifier.height(6.dp))
        XtomField(
            value = userName,
            onValueChange = { userName = it; IdentityPrefs.setUserName(context, it) },
            placeholder = tr("怎么称呼你"), modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(uri = userAvatar, fallback = userName.ifBlank { tr("我") }, size = 40.dp)
            Spacer(Modifier.width(10.dp))
            TextButton(onClick = { avatarPicker.launch(arrayOf("image/*")) }) {
                Text(tr("选个头像"), color = scheme.primary, fontSize = 12.sp)
            }
            if (userAvatar != null) {
                TextButton(onClick = { IdentityPrefs.setUserAvatar(context, null); userAvatar = null }) {
                    Text(tr("清除"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
        }
    }

    Spacer(Modifier.height(10.dp))
    XtomCard(modifier = Modifier.stepIn(2)) {
        Text(tr("希望它怎么说话"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        // 放在 remember 里而不是顶层 val：顶层 val 只在类加载时求值一次，切语言时 tr() 不会重算；
        // 切语言会让 XtomTheme 用 key(lang) 重建整棵树，remember 跟着重跑，译文才会变。
        val choices = remember {
            listOf(
                ToneChoice("自然随意", tr("自然随意"), tr("说话像平常聊天，不端着")),
                ToneChoice("简短直接", tr("简短直接"), tr("少废话，直接给结论")),
                ToneChoice("耐心细致", tr("耐心细致"), tr("多解释几句，把来龙去脉讲清楚")),
            )
        }
        choices.forEach { c ->
            val picked = tone == c.value
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .clickable {
                        tone = if (picked) "" else c.value
                        UserPreferences.setTone(context, tone)
                    }
                    .padding(horizontal = 4.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(c.label, color = scheme.onSurface, fontSize = 13.sp)
                    Text(c.desc, color = scheme.onSurfaceVariant, fontSize = 10.sp)
                }
                // 勾缩放进出，别硬闪（这三行是互斥选择，切换很频繁）
                val sel = rememberFrameFloat(if (picked) 1f else 0f, durationMs = 200)
                Icon(
                    Icons.Outlined.Check, null, tint = scheme.primary,
                    modifier = Modifier.size(16.dp).graphicsLayer {
                        scaleX = sel.value; scaleY = sel.value; alpha = sel.value
                    },
                )
            }
        }
    }
}

// ============================================================
// 6. 选个角色
// ============================================================

/**
 * 内置的三张起步角色卡。
 *
 * [setting]/[tone]/[length] 是**喂给模型的**（ChatScreen 直接拼进系统提示词），所以一律中文原串、
 * 不进翻译表。人设只写有用的约束：不塞举例、不喊口号、不加粗，免得模型学去一股人机味。
 * [name]/[desc] 是给人看的、也会存进角色卡，走 tr()。
 */
private class RolePreset(
    val name: String,
    val desc: String,
    val setting: String,
    val tone: String,
    val length: String,
)

@Composable
private fun RoleStep(compact: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val cardManager = remember { CharacterCardManager(context) }
    // 认「当前选的是哪张」用人设文本比对，不用卡名：卡名会跟着界面语言翻译，
    // 换个语言重跑向导就认不出自己上次建的卡了；人设是中文原串，永远稳定。
    var currentSetting by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }

    // 同上：放 remember 里，切语言时跟着重取译文
    val presets = remember {
        listOf(
            RolePreset(
                name = tr("助手"), desc = tr("干活为主，问什么答什么"),
                setting = "先给结论再给理由。不确定的地方直说不确定，不要编。用户没问的不要展开。",
                tone = "简洁", length = "短",
            ),
            RolePreset(
                name = tr("搭子"), desc = tr("语气轻松，聊天也能陪"),
                setting = "用平常聊天的语气，可以有情绪，但别演。用户在说事的时候先把事办了，再顺着聊。",
                tone = "轻松", length = "中",
            ),
            RolePreset(
                name = tr("顾问"), desc = tr("讲依据、讲取舍"),
                setting = "给建议时说明依据和代价，有多个方案就把权衡摆出来并给出推荐。涉及数字和日期先核对再说。",
                tone = "严谨", length = "中",
            ),
        )
    }

    LaunchedEffect(Unit) {
        currentSetting = withContext(Dispatchers.IO) { cardManager.getDefault()?.characterSetting.orEmpty() }
    }

    fun choose(p: RolePreset) {
        if (busy) return
        busy = true
        scope.launch {
            withContext(Dispatchers.IO) {
                // 重跑向导时别重复建卡：人设一样的已经有就直接设为默认
                val existing = cardManager.allCards.first().firstOrNull { it.characterSetting == p.setting }
                val id = existing?.id ?: cardManager.create(
                    name = p.name, description = p.desc, characterSetting = p.setting,
                    tone = p.tone, length = p.length,
                )
                cardManager.setDefault(id)
            }
            currentSetting = p.setting
            busy = false
        }
    }

    StepTitle(
        Icons.Outlined.AutoAwesome, tr("选个角色"),
        tr("决定它的说话方式和做事习惯。选了会建成一张角色卡，之后能改也能再建别的。"),
        compact,
    )

    presets.forEachIndexed { i, p ->
        val picked = currentSetting == p.setting
        // 选中的那张微微放大顶出来，其余略退后：点下去有实感，也一眼看得出选了哪张
        val sel = rememberFrameFloat(if (picked) 1f else 0f, durationMs = 240)
        XtomCard(
            onClick = { choose(p) },
            modifier = Modifier.stepIn(i + 1).graphicsLayer {
                val s = 1f + 0.025f * sel.value
                scaleX = s; scaleY = s
                alpha = 0.82f + 0.18f * sel.value
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(p.desc, color = scheme.onSurfaceVariant, fontSize = 11.sp)
                }
                // 勾随选中态缩放进出，而不是硬闪出来
                Icon(
                    Icons.Outlined.Check, null, tint = scheme.primary,
                    modifier = Modifier.size(18.dp).graphicsLayer {
                        scaleX = sel.value; scaleY = sel.value; alpha = sel.value
                    },
                )
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    Spacer(Modifier.height(4.dp))
    Text(
        tr("不想选就直接下一步，用默认设定。"),
        color = scheme.onSurfaceVariant, fontSize = 11.sp,
    )
}

// ============================================================
// 7. 能做什么（导览）
// ============================================================

@Composable
private fun TourStep(compact: Boolean) {
    val scheme = MaterialTheme.colorScheme
    StepTitle(
        Icons.Outlined.Info, tr("它还能做这些"),
        tr("下面这些都已经装好了，不用另外配。知道在哪就行，用不上可以先放着。"),
        compact,
    )
    XtomCard(modifier = Modifier.stepIn(1)) {
        FeatureRow(Icons.Outlined.Memory, tr("记忆"), tr("聊过的事会自己记下来，下次接着聊不用重讲。在「记忆」页能看、能改、能删。"))
        FeatureRow(Icons.Outlined.Settings, tr("工具"), tr("查天气、订日程、发消息、改文件、跑命令。每次动手前都会问你一句，不想让它做就拒绝。"))
        FeatureRow(Icons.Outlined.Public, tr("联网搜索"), tr("默认就能搜，不用配密钥。要更强的检索可以在「联网搜索」里接自己的引擎。"))
    }
    Spacer(Modifier.height(10.dp))
    XtomCard(modifier = Modifier.stepIn(2)) {
        FeatureRow(Icons.Outlined.Waves, tr("超级岛"), tr("屏幕顶上的一条小胶囊，正在干什么、干到哪一步一眼能看到。"))
        FeatureRow(Icons.Outlined.Terminal, tr("终端"), tr("装上独立的终端 App 之后，AI 能真的在你手机上跑命令。"))
        FeatureRow(Icons.Outlined.Timeline, tr("活动中心"), tr("它每次动手都记一笔：调了什么、参数是什么、成没成。不放心的时候去这里翻。"))
    }
    Spacer(Modifier.height(10.dp))
    XtomCard(modifier = Modifier.stepIn(3)) {
        FeatureRow(Icons.Outlined.Add, tr("左边抽屉"), tr("所有页面的入口都在那儿，还能按自己习惯重新排。"))
        FeatureRow(Icons.Outlined.Shield, tr("权限管理"), tr("哪些工具能用、哪些要问你、哪些直接禁掉，都在这里改。"))
    }
}

// ============================================================
// 8. 完成
// ============================================================

@Composable
private fun DoneStep(compact: Boolean) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    // 汇总只在进这一步时读一次（IO + binder），不每次重组重算
    var modelName by remember { mutableStateOf<String?>(null) }
    var roleName by remember { mutableStateOf<String?>(null) }
    var micOk by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val summary = withContext(Dispatchers.IO) {
            val mgr = CloudApiConfigManager(context)
            val cards = CharacterCardManager(context)
            Triple(
                (mgr.getActiveByPurpose("chat") ?: mgr.getActive())?.let { "${it.name} · ${it.model}" },
                cards.getDefault()?.name,
                androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            )
        }
        modelName = summary.first; roleName = summary.second; micOk = summary.third
    }

    Spacer(Modifier.height(if (compact) 4.dp else 16.dp))
    // 收尾这一下值得给点仪式感：勾冲出来（overshoot）+ 外圈扩散一次就停。
    val pop = rememberFrameProgress(key = Unit, durationMs = 560, easing = OvershootEasing)
    val ring = rememberFrameProgress(key = Unit, durationMs = 900)
    val badge = if (compact) 44.dp else 58.dp
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(badge + 26.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val t = ring.value
            // 一圈从徽标边缘扩出去并淡掉；跑完就没了，不常驻耗电
            drawCircle(
                color = scheme.primary.copy(alpha = 0.28f * (1f - t)),
                radius = (badge.toPx() / 2f) * (1f + 0.45f * t),
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        Box(
            Modifier.size(badge)
                .graphicsLayer { scaleX = pop.value; scaleY = pop.value; alpha = pop.value.coerceIn(0f, 1f) }
                .background(scheme.primaryContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Outlined.Check, null, tint = scheme.onPrimaryContainer, modifier = Modifier.size(if (compact) 24.dp else 32.dp))
        }
    }
    Spacer(Modifier.height(12.dp))
    Text(
        tr("配好了"), color = scheme.onSurface, fontSize = if (compact) 18.sp else 22.sp,
        fontWeight = FontWeight.Bold, modifier = Modifier.stepIn(0),
    )
    Spacer(Modifier.height(10.dp))

    XtomCard(modifier = Modifier.stepIn(1)) {
        SummaryLine(tr("对话模型"), modelName ?: tr("没配 —— 去 设置 → 模型配置 补上"), ok = modelName != null)
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
        SummaryLine(tr("角色"), roleName ?: tr("默认"), ok = true)
        HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.4f))
        SummaryLine(tr("录音权限"), if (micOk) tr("已开") else tr("没开，只能打字"), ok = micOk)
    }
    Spacer(Modifier.height(12.dp))
    Text(
        tr("想重新走一遍：设置 → 新手向导。"),
        color = scheme.onSurfaceVariant, fontSize = 11.sp, lineHeight = 16.sp,
        modifier = Modifier.stepIn(2),
    )
}

@Composable
private fun SummaryLine(label: String, value: String, ok: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.width(66.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            value,
            color = if (ok) scheme.onSurface else scheme.error,
            fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.weight(1f),
        )
    }
}
