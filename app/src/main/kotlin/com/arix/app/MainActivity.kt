 package com.arix.app
 
 import android.Manifest
 import androidx.compose.animation.togetherWith
 import android.content.pm.PackageManager
 import android.media.AudioFormat
 import android.media.AudioRecord
 import android.media.MediaRecorder
 import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
 import androidx.activity.ComponentActivity
 import androidx.activity.enableEdgeToEdge
 import androidx.activity.compose.setContent
 import androidx.activity.compose.rememberLauncherForActivityResult
 import androidx.activity.result.contract.ActivityResultContracts
 import androidx.compose.animation.core.animateFloatAsState
 import androidx.compose.animation.core.tween
 import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
 import androidx.compose.foundation.background
 import androidx.compose.foundation.BorderStroke
 import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
 import androidx.compose.foundation.gestures.detectDragGestures
 import kotlin.math.roundToInt
 import androidx.compose.foundation.border
 import androidx.compose.foundation.clickable
 import androidx.compose.foundation.combinedClickable
 import androidx.compose.foundation.horizontalScroll
 import androidx.compose.foundation.layout.Arrangement
 import androidx.compose.foundation.layout.Box
 import androidx.compose.foundation.layout.Column
 import androidx.compose.foundation.layout.Row
 import androidx.compose.foundation.layout.Spacer
 import androidx.compose.foundation.layout.fillMaxSize
 import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredHeight
 import androidx.compose.foundation.layout.height
 import androidx.compose.foundation.layout.offset
 import androidx.compose.foundation.layout.navigationBarsPadding
 import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.heightIn
 import androidx.compose.foundation.layout.padding
 import androidx.compose.foundation.layout.size
 import androidx.compose.foundation.layout.width
 import androidx.compose.foundation.layout.widthIn
 import androidx.compose.foundation.lazy.LazyColumn
 import androidx.compose.foundation.lazy.items
 import androidx.compose.foundation.lazy.rememberLazyListState
 import androidx.compose.foundation.rememberScrollState
 import androidx.compose.foundation.shape.CircleShape
 import androidx.compose.foundation.shape.RoundedCornerShape
 import androidx.compose.foundation.text.KeyboardOptions
 import androidx.compose.foundation.verticalScroll
 import androidx.compose.material.icons.Icons
 import androidx.compose.material.icons.automirrored.outlined.ArrowBack
 import androidx.compose.material.icons.automirrored.outlined.Chat
 import androidx.compose.material.icons.outlined.Add
 import androidx.compose.material.icons.outlined.AutoAwesome
 import androidx.compose.material.icons.outlined.BugReport
 import androidx.compose.material.icons.outlined.Lan
 import androidx.compose.material.icons.outlined.Storage
 import androidx.compose.material.icons.outlined.SystemUpdate
 import androidx.compose.material.icons.outlined.ChatBubbleOutline
 import androidx.compose.material.icons.outlined.Timeline
 import androidx.compose.material.icons.outlined.Check
 import androidx.compose.material.icons.outlined.UnfoldMore
 import androidx.compose.material.icons.outlined.Code
 import androidx.compose.material.icons.outlined.Palette
 import androidx.compose.material.icons.outlined.RecordVoiceOver
 import androidx.compose.material.icons.outlined.Dashboard
 import androidx.compose.material.icons.outlined.Folder
 import androidx.compose.material.icons.outlined.Bookmark
 import androidx.compose.material.icons.outlined.History
 import androidx.compose.material.icons.outlined.Info
 import androidx.compose.material.icons.outlined.Psychology
 import androidx.compose.material.icons.outlined.Language
 import androidx.compose.material.icons.outlined.Public
 import androidx.compose.material.icons.outlined.Style
 import androidx.compose.ui.text.style.TextOverflow
 import androidx.compose.material.icons.outlined.Menu
 import androidx.compose.material.icons.outlined.Mic
 import androidx.compose.material.icons.outlined.Search
 import androidx.compose.material.icons.outlined.Segment
 import androidx.compose.material.icons.outlined.Settings
 import androidx.compose.material.icons.outlined.Tune
 import androidx.compose.material.icons.outlined.DragHandle
 import androidx.compose.material.icons.outlined.Close
 import androidx.compose.ui.graphics.vector.ImageVector
 import androidx.compose.ui.geometry.Offset
 import androidx.compose.ui.geometry.Rect
 import androidx.compose.ui.layout.onGloballyPositioned
 import androidx.compose.ui.layout.boundsInWindow
 import androidx.compose.ui.layout.positionInWindow
 import androidx.compose.animation.animateContentSize
 import androidx.compose.ui.zIndex
 import androidx.compose.material3.Surface
 import androidx.compose.material3.Button
 import androidx.compose.material3.ButtonDefaults
 import androidx.compose.material3.Card
 import androidx.compose.material3.CardDefaults
 import androidx.compose.material3.DrawerValue
 import androidx.compose.material3.DropdownMenu
 import androidx.compose.material3.DropdownMenuItem
 import androidx.compose.material3.ExperimentalMaterial3Api
 import androidx.compose.material3.HorizontalDivider
 import androidx.compose.material3.Icon
 import androidx.compose.material3.LinearProgressIndicator
 import androidx.compose.material3.MaterialTheme
 import androidx.compose.material3.ModalDrawerSheet
 import androidx.compose.material3.ModalNavigationDrawer
 import androidx.compose.material3.NavigationDrawerItem
 import androidx.compose.material3.NavigationDrawerItemDefaults
 import androidx.compose.material3.OutlinedTextField
 import androidx.compose.material3.OutlinedTextFieldDefaults
 import androidx.compose.material3.Scaffold
 import androidx.compose.material3.TopAppBarDefaults
 import androidx.compose.material3.Switch
 import androidx.compose.material3.SwitchDefaults
 import androidx.compose.material3.Text
 import androidx.compose.material3.TextButton
 import androidx.compose.material3.rememberDrawerState
 import androidx.compose.runtime.Composable
 import androidx.compose.runtime.DisposableEffect
 import androidx.compose.runtime.Immutable
 import androidx.compose.runtime.LaunchedEffect
 import androidx.compose.runtime.collectAsState
 import androidx.compose.runtime.derivedStateOf
 import androidx.compose.runtime.getValue
 import androidx.compose.runtime.key
 import androidx.compose.runtime.mutableLongStateOf
 import androidx.compose.runtime.mutableStateListOf
 import androidx.compose.runtime.mutableStateOf
 import androidx.compose.runtime.remember
 import androidx.compose.runtime.rememberCoroutineScope
 import androidx.compose.runtime.setValue
 import androidx.compose.ui.Alignment
 import androidx.compose.ui.Modifier
 import androidx.compose.ui.draw.alpha
 import androidx.compose.ui.draw.blur
 import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
 import androidx.compose.ui.draw.clip
 import androidx.compose.ui.draw.clipToBounds
 import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
 import androidx.compose.ui.draw.shadow
 import androidx.compose.ui.graphics.BlendMode
 import androidx.compose.ui.graphics.BlurEffect
 import androidx.compose.ui.graphics.Brush
 import androidx.compose.ui.graphics.Color
 import androidx.compose.ui.graphics.CompositingStrategy
 import androidx.compose.ui.graphics.graphicsLayer
 import androidx.compose.ui.graphics.TileMode
 import androidx.compose.ui.graphics.layer.GraphicsLayer
 import androidx.compose.ui.input.nestedscroll.nestedScroll
 import androidx.compose.ui.graphics.layer.drawLayer
 import androidx.compose.ui.layout.onGloballyPositioned
 import androidx.compose.ui.layout.onSizeChanged
 import androidx.compose.ui.layout.positionInWindow
 import androidx.compose.ui.layout.positionInRoot
 import androidx.compose.ui.graphics.rememberGraphicsLayer
 import androidx.compose.ui.input.pointer.pointerInput
 import androidx.compose.ui.platform.LocalClipboardManager
 import androidx.compose.ui.platform.LocalContext
 import androidx.compose.ui.text.font.FontFamily
 import androidx.compose.ui.text.font.FontWeight
 import androidx.compose.ui.text.input.PasswordVisualTransformation
 import androidx.compose.ui.unit.dp
 import androidx.compose.ui.unit.sp
 import androidx.core.content.ContextCompat
 import com.arix.app.ui.glassSurface
 import com.arix.cloudapi.CloudApiClient
 import com.arix.cloudapi.CloudApiConfig
 import com.arix.cloudapi.WhisperClient
 import com.arix.cloudapi.model.ChatMessage
 import android.content.Intent
 import androidx.compose.material.icons.outlined.Warning
import com.arix.tool.PackageManager as XtomPackageManager
import com.arix.tool.PackageDef
import com.arix.tool.OperitCompat
import com.arix.tool.ImportExport
import com.arix.tool.PluginCreatorTool
import com.arix.tool.TtsTool
import com.arix.tool.ShellTool
import com.arix.app.ui.topChromeGapHeight
import com.arix.app.theme.floatFitPadding
import com.arix.app.theme.screenFitPadding
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.IntOffset
 import java.io.File
 import com.arix.stt.LanguageModel
 import com.arix.stt.SttEngine
 import com.arix.stt.SttModelManager
 import kotlinx.coroutines.Dispatchers
 import kotlinx.coroutines.delay
 import kotlinx.coroutines.isActive
 import kotlinx.coroutines.launch
 import kotlinx.coroutines.withContext
 import org.json.JSONObject
 import java.text.SimpleDateFormat
 import java.util.Date
 import java.util.Locale

 
 // ============================================================
 // MainActivity
 // ============================================================
 
 class MainActivity : ComponentActivity() {
     companion object { const val EXTRA_OPEN_CONV = "xtom_open_conv"; const val EXTRA_OPEN_PAGE = "xtom_open_page" }  // 主动消息通知点击→开到该对话；更新通知点击→开到指定页面
     // 唤醒/助手召出触发计数：每次收到 wake 广播或系统 ASSIST 手势自增，Compose 侧据此弹助手 overlay。
     private val assistTrigger = mutableStateOf(0)
     // 主动消息通知点开的目标对话（cold + onNewIntent 两条路径都写这里，Compose 侧据此跳转）
     private val openConv = mutableStateOf<Long?>(null)
     // 通知/快捷入口点开的目标页面（如「检查更新」）——同上，两条路径都写这里。
     private val openPage = mutableStateOf<String?>(null)
     // 桌面 widget「开始新对话」点击 → 每次自增，Compose 侧据此弹新对话角色卡选择
     private val newChatTrigger = mutableStateOf(0)
     // 系统分享面板 / 划词菜单送进来的外部内容 → 每次自增，Compose 侧据此切到聊天页
     // （内容本身在 ShareIntake 里，这里只是"该露脸了"的信号）
     private val shareTrigger = mutableStateOf(0)

     override fun onNewIntent(intent: Intent) {
         super.onNewIntent(intent)
         setIntent(intent)
         if (isAssistIntent(intent)) assistTrigger.value++
         intent.getLongExtra(EXTRA_OPEN_CONV, -1L).takeIf { it > 0 }?.let { openConv.value = it }
         intent.getStringExtra(EXTRA_OPEN_PAGE)?.takeIf { it.isNotBlank() }?.let { openPage.value = it }
         if (intent.getBooleanExtra(XtomWidget.EXTRA_NEW_CHAT, false)) newChatTrigger.value++
         // 悬浮球「打字发送」：文本投给 ShareIntake 收件箱，聊天页挂接即消费。
         intent.getStringExtra(FloatingChatBall.EXTRA_BALL_TEXT)?.takeIf { it.isNotBlank() }?.let {
             ShareIntake.postText(it, "悬浮球")
         }
         // ⚠ 分享/划词这条**必须在这里也接一次**：App 已经开着时系统复用 singleTask 实例走的是 onNewIntent，
         // 只在 onCreate 里读 intent 的话，从分享面板进来会什么都不发生（最容易漏的一处）。
         // 两种形态：分享面板直投（自带 SEND action）/ 划词代理转交（只带路由标记，内容已在收件箱）。
         if (ShareIntake.handle(this, intent) || ShareIntake.isRouteToChat(intent)) shareTrigger.value++
     }

     /** 隐藏状态栏 + 导航栏（含手势"小白条"），内容铺满方屏。上/下滑可临时唤出，不挤压内容。 */
     private fun hideSystemBars() {
         androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
             hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
             systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
         }
     }

     // 焦点重获时重新隐藏系统栏：弹键盘/关对话框/从助手或其他 Activity 返回后，系统会把状态栏+手势小白条
     // 重新显出且不再自动收回（onCreate 只隐藏了一次）——那道白条会盖住角色卡等非聊天页顶部。重获焦点即重隐。
     /**
      * 用户真的退出 App 时收掉 stdio MCP 子进程。
      *
      * `StdioMcpRegistry.closeAll()` 此前**全项目没有调用点**——那些子进程跑在终端 App 那侧，
      * 我们的进程没了它们还活着，用户看不见也杀不掉。只在 `isFinishing` 时收：
      * 旋转屏幕/切后台也会走 onDestroy，那时候收掉等于每次转屏都要重连一次。
      */
     override fun onDestroy() {
         super.onDestroy()
         if (isFinishing) runCatching { com.arix.tool.StdioMcpRegistry.closeAll() }
     }

     override fun onWindowFocusChanged(hasFocus: Boolean) {
         super.onWindowFocusChanged(hasFocus)
         if (hasFocus) hideSystemBars()
     }

     override fun onPause() {
         super.onPause()
         UpdatePrompt.foreground = false
     }

     override fun onResume() {
         super.onResume()
         UpdatePrompt.foreground = true
     }

     private fun isAssistIntent(i: Intent?): Boolean =
         i != null && (
             i.getBooleanExtra(WakeService.EXTRA_WAKE, false) ||
                 i.action == Intent.ACTION_ASSIST ||
                 i.action == "android.intent.action.VOICE_ASSIST"
         )

     override fun onCreate(savedInstanceState: Bundle?) {
         super.onCreate(savedInstanceState)
         enableEdgeToEdge() // 全屏：内容铺满方屏，系统栏透明
         // 完全全屏：默认隐藏状态栏 + 导航栏，内容顶到屏幕最上/最下（下滑/上滑可临时唤出，覆盖不挤压内容）；
         // 并允许绘制进刘海/挖孔区，避免顶部留黑边
         if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
             window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS }
         }
         hideSystemBars()
         if (isAssistIntent(intent)) assistTrigger.value++
         openConv.value = intent.getLongExtra(EXTRA_OPEN_CONV, -1L).takeIf { it > 0 }
         openPage.value = intent.getStringExtra(EXTRA_OPEN_PAGE)?.takeIf { it.isNotBlank() }
         if (intent.getBooleanExtra(XtomWidget.EXTRA_NEW_CHAT, false)) newChatTrigger.value++
         // 悬浮球「打字发送」：文本投给 ShareIntake 收件箱，聊天页挂接即消费（冷启动这一路）。
         intent.getStringExtra(FloatingChatBall.EXTRA_BALL_TEXT)?.takeIf { it.isNotBlank() }?.let {
             ShareIntake.postText(it, "悬浮球")
         }
         // 分享/划词的冷启动这一路（热启动那一路在 onNewIntent）。ShareIntake 自己挡重放：
         // 从「最近任务」点回来时系统会把原来那条分享 Intent 再发一遍，不挡就会重复灌一次内容。
         if (ShareIntake.handle(this, intent) || ShareIntake.isRouteToChat(intent)) shareTrigger.value++
         // WorkManager 调度类初始化会同步初始化 Room DB + 磁盘 IO——
         // 挪到后台线程，别卡在冷启动 onCreate 的首帧路径上（调度不依赖首帧，晚一拍无碍）。
         val act = this
         Thread {
             // 桌面图标长按的快捷方式（新对话 / 语音）。幂等，每次冷启动刷一遍，跟随语言设置变化。
             // 要绘位图，更不该待在首帧路径上，搭这趟后台车。
             XtomShortcuts.publish(act)
              // 打开就查更新：后台 Worker 异步查，不卡首帧；同版本只提醒一次。开关关着就不查。
              UpdateNotifier.checkNow(act)
          }.apply { isDaemon = true }.start()
         // 新手向导：首次启动（或用户在设置里点了重跑）先走引导。init 只认进程内第一次，
         // 手动拉起的向导不会被 Activity 重建顶掉。
         OnboardingGate.init(act)
         // 更新弹窗需要知道 App 在前台（前台才弹主页弹窗，后台走通知），接上全局前台标记。
         UpdatePrompt.foreground = true
         setContent {
             com.arix.app.theme.XtomTheme {
                 // 二选一渲染而不是把向导叠在 MainScreen 上：叠着的话背后整棵聊天树照样组合+绘制，
                 // 白烧一份帧预算（向导期间背景完全看不见）。
                 if (OnboardingGate.show.value) {
                     OnboardingPage(onFinish = { route ->
                         // 「结束后去录唤醒词」：写进 NavRetain，下面 MainScreen 首次组合时就落在那一页
                         if (route != null) NavRetain.page = route
                         OnboardingGate.finish(act)
                     })
                 } else {
                     MainScreen(
                         crashRestart = intent.getBooleanExtra(CrashHandler.EXTRA_CRASH_REPORT, false),
                         assistTrigger = assistTrigger.value,
                         openConvId = openConv.value,
                         openPageParam = openPage.value,
                         newChatTrigger = newChatTrigger.value,
                         shareTrigger = shareTrigger.value,
                     )
                 }
             }
         }
     }
 }
 
 // 进程级导航持有者：跨 key(lang) 重建（切语言）保留当前页与返回栈，避免被弹回 chat。
 private object NavRetain {
     var page: String = "chat"
     var stack: List<String> = emptyList()
 }

 // ============================================================
 // MainScreen
 // ============================================================
 
 @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
 @Composable fun MainScreen(crashRestart: Boolean = false, assistTrigger: Int = 0, openConvId: Long? = null, openPageParam: String? = null, newChatTrigger: Int = 0, shareTrigger: Int = 0) {
     val scope = rememberCoroutineScope()
     val context = LocalContext.current
     val scheme = MaterialTheme.colorScheme
     val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
     // 崩溃重启后直接进「崩溃报告」页（安全模式）：不回 chat，避免又加载导致崩溃的上次对话/状态而再次崩溃。
     // 页面/返回栈从 NavRetain 恢复：切语言时 XtomTheme 用 key(lang) 重建整棵树（让 tr() 重新求值），
     // 若不保留就会被弹回 chat。用进程级持有者存住当前页与栈，重建后原地恢复。
     var currentPage by remember { mutableStateOf(if (crashRestart) "crash" else NavRetain.page) }
     // 返回栈：进子页面压栈，返回键/返回箭头逐级弹栈；回到 chat(根)即清空
     val backStack = remember { androidx.compose.runtime.mutableStateListOf<String>().apply { if (!crashRestart) addAll(NavRetain.stack) } }
     androidx.compose.runtime.LaunchedEffect(currentPage, backStack.size) { NavRetain.page = currentPage; NavRetain.stack = backStack.toList() }
     // 导航方向：换页动画按此决定往哪飞（前进=新页从右滑入，后退=旧页从左滑回）。
     // 原来靠 targetState != "chat" 猜方向，多级返回时判反——「只往一个方向飞」的根因。
     var navForward by remember { mutableStateOf(true) }
     // "onboarding" 不是一个页面路由，是把全屏向导拉起来的开关：它盖在 MainScreen 之外（见 setContent），
     // 走完自己关掉并回到这里当前所在的页面。抽屉和设置中心用同一个 id，不用各写一套。
     fun navTo(p: String) {
         if (p == "onboarding") { OnboardingGate.open(); return }
         if (p != currentPage) { navForward = true; backStack.add(currentPage); currentPage = p }
     }
     fun navBack() { navForward = false; currentPage = if (backStack.isNotEmpty()) backStack.removeAt(backStack.lastIndex) else "chat" }
     // 预测式返回（跟手）：手指从边缘拖动时，当前页跟着缩放+平移预览，松手到位才真的返回；中途取消则弹回。
     // backProgress 0..1 = 手势进度；backEdgeLeft = 从左缘还是右缘拖（决定预览往哪偏）。
     val backAnim = remember { androidx.compose.animation.core.Animatable(0f) }
     var backEdgeLeft by remember { mutableStateOf(true) }
     androidx.activity.compose.PredictiveBackHandler(enabled = currentPage != "chat") { events ->
         try {
             events.collect { e ->
                 backEdgeLeft = e.swipeEdge == androidx.activity.BackEventCompat.EDGE_LEFT
                 backAnim.snapTo(e.progress)
             }
             // 完成：直接返回并瞬置 0（返回由换页转场接管，避免双重动画）
             navBack()
             backAnim.snapTo(0f)
         } catch (c: kotlinx.coroutines.CancellationException) {
             backAnim.animateTo(0f, androidx.compose.animation.core.tween(220)) // 取消 → 平滑弹回
             throw c
         }
     }
     androidx.compose.runtime.LaunchedEffect(currentPage) { if (currentPage == "chat") backStack.clear() }
     // 大标题折叠：统一用 NestedScrollConnection 接住当前子页(verticalScroll / LazyColumn 皆可)的竖向滚动，
     // 仿 Material TopAppBarScrollBehavior——上滑先消费一段把大标题收成小标题，下滑再吐回来。
     // heightOffset ∈ [-collapseDist, 0]，换算成 titleCollapse 0..1 喂给顶栏与 topChromeGap（让位同步收缩）。
     val topChromeStyleNow = com.arix.app.theme.LocalThemeConfig.current.chromeStyle
     val titleExpandedPx = with(androidx.compose.ui.platform.LocalDensity.current) { com.arix.app.ui.expandedChromeHeight().toPx() }
     val titleCollapsedPx = with(androidx.compose.ui.platform.LocalDensity.current) { com.arix.app.ui.collapsedChromeHeight().toPx() }
     val titleCollapseDist = (titleExpandedPx - titleCollapsedPx).coerceAtLeast(1f)
     var titleHeightOffset by remember { mutableStateOf(0f) }
     // 用 derivedStateOf 包住：创建时不读 titleHeightOffset，只有真正读 .value 的消费者(顶栏/各页 topChromeGap)才订阅并逐帧重组。
     // 否则在 MainScreen 本体里算 titleCollapse = 每帧滚动都让「整个 MainScreen」重组（非聊天页滚动卡顿的根因）。
     val titleCollapse = remember(titleCollapseDist) { androidx.compose.runtime.derivedStateOf { (-titleHeightOffset / titleCollapseDist).coerceIn(0f, 1f) } }
     // 只在 GLASS 风格消费滚动收标题；M3 风格由 Material 自己的 LargeTopAppBar + scrollBehavior 接管，别双重消费。
     val titleNestedScroll = remember(titleCollapseDist, topChromeStyleNow) {
         object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
             override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                 if (topChromeStyleNow != com.arix.app.theme.ChromeStyle.GLASS) return androidx.compose.ui.geometry.Offset.Zero
                 val prev = titleHeightOffset
                 titleHeightOffset = (titleHeightOffset + available.y).coerceIn(-titleCollapseDist, 0f)
                 val consumed = titleHeightOffset - prev
                 return if (consumed != 0f) androidx.compose.ui.geometry.Offset(0f, consumed) else androidx.compose.ui.geometry.Offset.Zero
             }
         }
     }
     LaunchedEffect(currentPage) { titleHeightOffset = 0f }   // 换页回到大标题
     // M3 风格的折叠大标题：Material 原生 LargeTopAppBar + 退出即收拢的滚动行为（rikkahub 同款机制）。
     val m3ScrollBehavior = androidx.compose.material3.TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
     LaunchedEffect(currentPage) { m3ScrollBehavior.state.heightOffset = 0f }   // 换页回到大标题
     // 唤醒/助手召出 overlay：assistTrigger 自增即弹出
     var showAssistant by remember { mutableStateOf(false) }
     LaunchedEffect(assistTrigger) { if (assistTrigger > 0) showAssistant = true }
     // 某功能请求跳到模型配置页（如 shell 翻译按钮发现没配翻译模型）→ 切到 config，ConfigPage 进入时自动展开对应用途卡
     val cfgJump by com.arix.app.ConfigJump.target.collectAsState()
     LaunchedEffect(cfgJump) { if (cfgJump != null) navTo("config") }
     // 联网自动备份：开了就在启动时按间隔后台整包备份一次（内部自查是否到点/联网/有 token）
     LaunchedEffect(Unit) { scope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { com.arix.app.GitHubBackup.maybeAutoBackup(context) }; runCatching { com.arix.app.WebDavBackup.maybeAutoBackup(context) }; runCatching { com.arix.app.GitHubBackup.notifyChanged(context) }; runCatching { com.arix.app.WebDavBackup.notifyChanged(context) } } }
     var conversationKey by remember { mutableStateOf(0) }
     var pendingConversationId by remember { mutableStateOf<Long?>(null) }
     // 主动消息通知点开：跳到该对话（openConvId 变化即路由；同 id 再次点击不重复触发）
     LaunchedEffect(openConvId) { if (openConvId != null) { pendingConversationId = openConvId; conversationKey++; currentPage = "chat" } }
     // 通知/快捷入口点开指定页面（如「检查更新」）：openPageParam 变化即路由
     LaunchedEffect(openPageParam) { if (openPageParam != null) navTo(openPageParam!!) }
     var showModelSwitcher by remember { mutableStateOf(false) }
    // 主页更新弹窗：UpdateNotifier 查到新版本且 App 在前台时把 release 塞进 UpdatePrompt.pending，
    // 这里观察变化弹窗。用户「稍后」= 记 postponeUntil 冷却；「更新」= 直接下载安装。
    if (UpdatePrompt.pending.value != null) {
        val relTag = UpdatePrompt.pending.value!!.tag
        UpdatePromptDialog(
            context = context,
            onDismiss = {
                UpdateNotifier.markNotified(context, relTag)
                UpdatePrompt.pending.value = null
            },
        )
    }
     // 桌面 widget「开始新对话」：回到聊天页并开一个新对话，等价顶栏「+」
     LaunchedEffect(newChatTrigger) { if (newChatTrigger > 0) { currentPage = "chat"; pendingConversationId = null; conversationKey++ } }
     // 分享面板 / 划词进来：先把人送到聊天页（内容由聊天页从 ShareIntake 取走）。
     // 不弹角色卡选择——用户是带着一段内容来的，让他先接着当前这段对话说，别拿个选择框拦在中间。
     LaunchedEffect(shareTrigger) { if (shareTrigger > 0) currentPage = "chat" }
     // 兜底：聊天页尚未接上 ShareIntake 时，把带围栏的文本经实时胶囊那条**既有**输入桥送进对话。
     // 它只能带文字、且是直接发出去（那条桥本身就是这个语义），所以纯图片分享在接线前不会有反应。
     // 聊天页接上之后（ShareIntake.chatConsumerAttached=true）这段自动让路，附件与「填进输入框待发」才生效。
     val sharedIn by ShareIntake.pending.collectAsState()
     LaunchedEffect(sharedIn) {
         val p = sharedIn ?: return@LaunchedEffect
         if (ShareIntake.chatConsumerAttached) return@LaunchedEffect
         ShareIntake.consume()
     }
     var chatSearchActive by remember { mutableStateOf(false) }
     var chatBarsVisible by remember { mutableStateOf(true) }  // 聊天页顶栏随滚动自动隐藏
     // 当前对话模型（顶栏小椭圆显示）
     val topConfigManager = remember { CloudApiConfigManager(context) }
     val topConfigs by topConfigManager.allConfigs.collectAsState(initial = emptyList())
     val activeModel by remember { derivedStateOf { topConfigs.find { it.isActive && it.purpose == "chat" }?.model ?: "" } }
     // 抽屉：对话列表（按时间分组）+ 全局搜索
     var showGlobalSearch by remember { mutableStateOf(false) }
     val drawerConvManager = remember { ConversationManager(context) }
     val activeConvs by drawerConvManager.repo.activeSummaries.collectAsState(initial = emptyList())
     var deleteConvId by remember { mutableStateOf<Long?>(null) } // 抽屉长按待删除的对话

     val dateFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }
     fun ts(): String = dateFormat.format(Date())
 
     var hasAudioPerm by remember { mutableStateOf(
         ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
     ) }
     val permLauncher = rememberLauncherForActivityResult(
         ActivityResultContracts.RequestPermission()
     ) { granted -> hasAudioPerm = granted }
     var pendingOnImagesPicked by remember { mutableStateOf<((List<String>) -> Unit)?>(null) }
     val imagePickerLauncher = rememberLauncherForActivityResult(
         ActivityResultContracts.OpenMultipleDocuments()
     ) { uris ->
         pendingOnImagesPicked?.invoke(uris.map { it.toString() })
         pendingOnImagesPicked = null
     }
     // 拍照：TakePicture 写入 FileProvider 生成的临时 URI，成功后把 URI 当作附件回灌
     var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
     val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
         if (success) pendingCameraUri?.let { uri -> pendingOnImagesPicked?.invoke(listOf(uri.toString())) }
         pendingOnImagesPicked = null; pendingCameraUri = null
     }
     fun launchCamera() {
         try {
             val f = File(context.cacheDir, "cam_${System.currentTimeMillis()}.jpg")
             val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
             pendingCameraUri = uri; cameraLauncher.launch(uri)
         } catch (_: Exception) {}
     }
     val cameraPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) launchCamera() }

     // 抽屉入口自定义排布：长按任意入口（或顶部「调节」球）进入独立整页编辑器（左排布 + 右组件面板），点/拖排布，实时保存。
     var drawerEditMode by remember { mutableStateOf(false) }
     fun enterDrawerEdit() { drawerEditMode = true; scope.launch { drawerState.close() } }
     val drawerLayout = remember { androidx.compose.runtime.mutableStateListOf<DrawerLayoutStore.Item>().apply { addAll(DrawerLayoutStore.load(context)) } }
     fun applyDrawerLayout(items: List<DrawerLayoutStore.Item>) { drawerLayout.clear(); drawerLayout.addAll(items); DrawerLayoutStore.save(context, items) }
     val drawerEditCtl = remember { DrawerEditCtl(drawerLayout) { applyDrawerLayout(it) } }
     // 每个入口的图标/短名/分组/点击动作——动作是页面局部闭包(navTo/showCardSelector 等)，只能在这里就地绑。
     // 大部分入口 = navTo(id)（id 即页面 key）；聊天/设置两个特殊。设置里的一切也纳入，默认在组件面板待选。
     val drawerEntryDefs = remember {
         fun nav(id: String, icon: ImageVector, label: String, group: String) =
             DrawerEntryDef(id, icon, label, group, id) { navTo(id); scope.launch { drawerState.close() } }
         listOf(
             nav("files", Icons.Outlined.Folder, "文件", "常用"),
             nav("memory", Icons.Outlined.Psychology, "记忆", "常用"),
             nav("browser", Icons.Outlined.Language, "浏览器", "常用"),
             DrawerEntryDef("newchat", Icons.AutoMirrored.Outlined.Chat, "聊天", "常用", null) { pendingConversationId = null; conversationKey++; currentPage = "chat"; scope.launch { drawerState.close() } },
             DrawerEntryDef("settings", Icons.Outlined.Settings, "设置", "常用", "settings_hub") { navTo("settings_hub"); scope.launch { drawerState.close() } },
             nav("personalization", Icons.Outlined.Palette, "个性化", "个性化"),
             nav("config", Icons.Outlined.Settings, "模型配置", "对话与模型"),
             nav("dialog_settings", Icons.Outlined.Style, "对话设置", "对话与模型"),
             nav("voice_clone", Icons.Outlined.RecordVoiceOver, "声音克隆", "对话与模型"),
             nav("user_scripts", Icons.Outlined.Code, "用户脚本", "对话与模型"),
             nav("search_settings", Icons.Outlined.Public, "联网搜索", "对话与模型"),
             nav("conversations", Icons.Outlined.History, "对话管理", "对话与模型"),
             nav("favorites", Icons.Outlined.Bookmark, "收藏", "对话与模型"),
             nav("usage", Icons.Outlined.Dashboard, "使用统计", "对话与模型"),
             nav("workflows", Icons.Outlined.Dashboard, "工作流", "对话与模型"),
             nav("import", Icons.Outlined.Folder, "导入导出", "对话与模型"),
             nav("wake", Icons.Outlined.Mic, "语音唤醒", "语音"),
             nav("projects", Icons.Outlined.Dashboard, "项目", "工具与扩展"),
             nav("tool_keys", Icons.Outlined.Settings, "工具密钥", "工具与扩展"),
             nav("packages", Icons.Outlined.Public, "本地包", "工具与扩展"),
             nav("file_history", Icons.Outlined.History, "改动历史", "工具与扩展"),
             nav("chat_appearance", Icons.Outlined.ChatBubbleOutline, "聊天外观", "工具与扩展"),
             nav("plugins", Icons.Outlined.Add, "插件制作", "工具与扩展"),
             nav("terminal", Icons.Outlined.Settings, "终端", "工具与扩展"),
             nav("permissions", Icons.Outlined.Menu, "权限管理", "系统"),
             // ⚠ 这四个要在抽屉「组件面板」里出现，还得在 DrawerLayoutStore.DEFAULTS 里补同名 id
             //   （load() 会按 DEFAULTS 的 known 集合过滤，没登记的 id 一律丢掉）。
             nav("proxy", Icons.Outlined.Lan, "网络代理", "系统"),
             nav("storage", Icons.Outlined.Storage, "存储占用", "系统"),
             nav("activity_center", Icons.Outlined.Timeline, "活动中心", "系统"),
             nav("app_log", Icons.Outlined.BugReport, "运行日志", "系统"),
             nav("crash", Icons.Outlined.Warning, "崩溃报告", "系统"),
             nav("update", Icons.Outlined.SystemUpdate, "检查更新", "系统"),
             nav("onboarding", Icons.Outlined.AutoAwesome, "新手向导", "系统"),
             nav("about", Icons.Outlined.Info, "关于", "系统"),
         )
     }
     val entryById = remember(drawerEntryDefs) { drawerEntryDefs.associateBy { it.id } }
     val drawerPaletteGroups = remember { listOf("常用", "个性化", "对话与模型", "语音", "工具与扩展", "系统") }

     ModalNavigationDrawer(
         drawerState = drawerState,
         // 不给「右滑划出抽屉」。这是手表：右滑是系统返回手势，圆屏上边缘又窄，
         // 滑聊天、点气泡时特别容易蹭出抽屉——想返回结果弹出个菜单，是最烦人的一类误触。
         // 抽屉照旧由顶栏的菜单按钮打开；已经打开时保留手势，否则划不回去只能点遮罩。
         gesturesEnabled = drawerState.isOpen,
         drawerContent = {
             ModalDrawerSheet(
                 // 宽 ≈ 屏宽 × 0.80。
                 modifier = Modifier.widthIn(max = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.80f).dp),
                 drawerContainerColor = scheme.surface,
                 drawerContentColor = scheme.onSurface,
                 drawerShape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp),
                 // 不用 drawerTonalElevation：它给 surface 叠一层主色调=抽屉颜色发蓝异常（用户反馈）。用纯 surface。
                 windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),  // 内容上移，与顶栏对齐
             ) {
                 // 顶部：Arix AI + 调节球（自定义排布） + 搜索小圆球（搜索对话消息内容）
                 Row(modifier = Modifier.fillMaxWidth().padding(start = 15.dp, end = 12.dp, top = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                     Text("Arix AI", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = scheme.primary, modifier = Modifier.weight(1f))
                     Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(scheme.surfaceContainerHighest).border(1.dp, scheme.outlineVariant, CircleShape).clickable { enterDrawerEdit() }, contentAlignment = Alignment.Center) {
                         Icon(Icons.Outlined.Tune, contentDescription = tr("自定义抽屉排布"), tint = scheme.onSurface, modifier = Modifier.size(19.dp))
                     }
                     Spacer(Modifier.width(8.dp))
                     Box(modifier = Modifier.size(38.dp).clip(CircleShape).background(scheme.surfaceContainerHighest).border(1.dp, scheme.outlineVariant, CircleShape).clickable { showGlobalSearch = true; scope.launch { drawerState.close() } }, contentAlignment = Alignment.Center) {
                         Icon(Icons.Outlined.Search, contentDescription = tr("搜索对话内容"), tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                     }
                 }
                 HorizontalDivider(color = scheme.outlineVariant)
                 val grouped = remember(activeConvs) {
                     val now = System.currentTimeMillis()
                     val cal = java.util.Calendar.getInstance().apply { timeInMillis = now; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
                     val todayStart = cal.timeInMillis
                     val order = listOf("今天", "昨天", "近7天", "更早")
                     val m = activeConvs.sortedByDescending { it.updatedAt }.groupBy { c ->
                         when { c.updatedAt >= todayStart -> "今天"; c.updatedAt >= todayStart - 86400000L -> "昨天"; c.updatedAt >= todayStart - 6 * 86400000L -> "近7天"; else -> "更早" }
                     }
                     order.mapNotNull { k -> m[k]?.let { k to it } }
                 }
                 // 导航项、角色卡、对话全在同一个可滚区里：小屏（手表）上把它们固定排布，
                 // 会先把 weight(1f) 的对话列表压成 0 高、再把底部按钮挤出屏幕，而导航项自身又不可滚 → 整个抽屉划不动。
                 LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
                     item(key = "nav") {
                         Column {
                             Spacer(modifier = Modifier.height(4.dp))
                             // 导航区入口按用户排布渲染（长按任意项进入编辑）
                             drawerLayout.filter { it.zone == DrawerLayoutStore.ZONE_NAV }.forEach { itm ->
                                 if (DrawerLayoutStore.isSpacer(itm.id)) { Spacer(Modifier.height(16.dp)); return@forEach }
                                 val def = entryById[itm.id] ?: return@forEach
                                 DrawerNavRow(
                                     icon = def.icon, label = tr(def.label),
                                     selected = def.selectedPage != null && currentPage == def.selectedPage,
                                     onClick = def.onClick, onLongClick = { enterDrawerEdit() },
                                 )
                             }
                             Spacer(modifier = Modifier.height(4.dp))
                             HorizontalDivider(color = scheme.outlineVariant)
                         }
                     }
                     item(key = "curcard") {
                         Column {
                             Row(modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 12.dp, top = 8.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                 Icon(Icons.Outlined.Style, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(15.dp))
                                 Spacer(Modifier.width(6.dp))
                                 Column(modifier = Modifier.weight(1f)) {
                                     Text(tr("通用助手"), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                 }
                             }
                             Text(tr("对话"), fontSize = 11.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 6.dp, bottom = 2.dp))
                         }
                     }
                     grouped.forEach { (label, convs) ->
                         // 时间分组标题：加粗、primary 色、上方留白更大，分组更明显
                         item(key = "h_$label") { Text(tr(label), fontSize = 12.sp, color = scheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 4.dp)) }
                         items(convs.size, key = { convs[it].id }) { i ->
                             val c = convs[i]
                             Row(
                                 modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 1.dp).clip(RoundedCornerShape(10.dp))
                                     .combinedClickable(
                                         onClick = { pendingConversationId = c.id; conversationKey++; currentPage = "chat"; scope.launch { drawerState.close() } },
                                         onLongClick = { deleteConvId = c.id },
                                     ).padding(horizontal = 12.dp, vertical = 9.dp),
                                 verticalAlignment = Alignment.CenterVertically,
                             ) {
                                 if (c.source == "voice") { Icon(Icons.Outlined.Mic, contentDescription = tr("语音对话"), tint = scheme.primary, modifier = Modifier.size(13.dp)); Spacer(Modifier.width(5.dp)) }
                                 Text(c.title.ifBlank { tr("新对话") }, fontSize = 14.sp, color = scheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                             }
                         }
                     }
                 }
                 deleteConvId?.let { delId ->
                     androidx.compose.material3.AlertDialog(
                         onDismissRequest = { deleteConvId = null },
                         title = { Text(tr("删除对话？"), color = scheme.onSurface, fontSize = 15.sp) },
                         text = { Text(tr("删除后无法恢复。"), color = scheme.onSurfaceVariant, fontSize = 13.sp) },
                         // drawerConvManager.delete（不是 repo.delete）：连工作区里的原文存档一起清，见 ConversationManager.delete
                         // delete 返回「到底删没删」——锁定的会话它不删。不看返回值的话，用户点了删除、
                         // 框关了、对话还在，什么提示都没有，只会以为 App 坏了。
                         confirmButton = { androidx.compose.material3.TextButton(onClick = {
                             scope.launch {
                                 if (!drawerConvManager.delete(delId)) android.widget.Toast.makeText(
                                     context, tr("已锁定的对话不能删除，先解锁再试"), android.widget.Toast.LENGTH_SHORT
                                 ).show()
                             }
                             deleteConvId = null
                         }) { Text(tr("删除"), color = scheme.error) } },
                         dismissButton = { androidx.compose.material3.TextButton(onClick = { deleteConvId = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
                         containerColor = scheme.surface, shape = RoundedCornerShape(24.dp),
                     )
                 }
                 val bottomEntries = drawerLayout.filter { it.zone == DrawerLayoutStore.ZONE_BOTTOM }
                 if (bottomEntries.isNotEmpty()) {
                     HorizontalDivider(color = scheme.outlineVariant)
                     // 底部悬浮入口：按用户排布 + 各自大小(小球/中胶囊/大整行)渲染，统一浅容器+描边（长按进入编辑）。
                     DrawerBottomZone(items = bottomEntries, entryById = entryById, onLongClick = { enterDrawerEdit() })
                 }
             } // end ModalDrawerSheet
         } // end drawerContent
     ) { // ModalNavigationDrawer content
         // 增加层级：抽屉是叠在对话页之上的一层——打开后系统返回/侧滑先「退这一层」= 关抽屉回对话页，
         // 而不是退出 App。编辑模式再多一层：先退出编辑，再退才关抽屉。声明在此(抽屉内容内)保证
         // 比库自带的抽屉返回处理更晚注册、优先级更高(BackHandler 后注册者先响应)。
         androidx.activity.compose.BackHandler(enabled = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open || drawerEditMode) {
             if (drawerEditMode) drawerEditMode = false
             else scope.launch { drawerState.close() }
         }
         // 顶部 chrome 两套风格，用户在个性化页选（见 ChromeStyle）：
         //  · M3(默认)   = 标准 TopAppBar：返回在 navigationIcon 位、正经标题在 title 位。规范内的样子。
         //  · GLASS      = 内容从渐变模糊玻璃下穿过 + 左上悬浮胶囊返回。自创，省一整条标题栏的高度。
         // 聊天页/浏览器页两套都不叠：前者自带玻璃顶栏，后者自带地址栏(含返回)。
         val chromeless = currentPage == "chat" || currentPage == "browser"
         val chromeStyle = com.arix.app.theme.LocalThemeConfig.current.chromeStyle
         val useM3Bar = !chromeless && chromeStyle == com.arix.app.theme.ChromeStyle.M3
         // 屏幕适配内缩：顶栏和页面内容都要吃到，所以在这里取一次（顶栏是内容 Box 的兄弟节点，
         // 只给内容 Box 加 padding 的老做法让顶栏漏了 → 圆屏上返回箭头/大标题被表圈切掉）。
         val fit = com.arix.app.theme.LocalScreenFit.current
         // 抽屉打开时主内容「稍微放大 + 模糊」：放大而不是缩小，避免露出空白要填色（莫奈填充不靠谱，用户反馈）。
         val drawerOpen = drawerState.isOpen || drawerState.targetValue == DrawerValue.Open
         val sink by androidx.compose.animation.core.animateFloatAsState(if (drawerOpen) 1f else 0f, tween(300), label = "drawerSink")
         val sinkMod = if (sink > 0.001f) Modifier
             .graphicsLayer { val s = 1f + 0.04f * sink; scaleX = s; scaleY = s }   // 放大溢出，不留空白
             .blur((sink * 10f).dp, androidx.compose.ui.draw.BlurredEdgeTreatment.Unbounded)
             else Modifier
         // 抽屉开时拦掉主内容的触摸（不触发对话页交互）。必须在 Initial pass 消费——
         // 默认 Main pass 是子元素(聊天页)先拿到事件、拦不住，Initial 在子元素之前拦截才有效。
         val fillMod = Modifier.fillMaxSize()
             .then(if (drawerOpen) Modifier.pointerInput(drawerOpen) {
                 awaitPointerEventScope {
                     while (true) {
                         awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                             .changes.forEach { it.consume() }
                     }
                 }
             } else Modifier)
         Box(fillMod) {
         Scaffold(
             // M3 风格：把 Material 折叠滚动行为接到 Scaffold，页面滚动即驱动 LargeTopAppBar 大↔小。
             modifier = if (useM3Bar) sinkMod.nestedScroll(m3ScrollBehavior.nestedScrollConnection) else sinkMod,
             topBar = {
                 if (useM3Bar) {
                     // rikkahub 式大标题：顶部大字，上滑折叠成常规小标题。
                     androidx.compose.material3.LargeTopAppBar(
                         // 屏幕适配：本栏贴屏顶 0dp 起画（enableEdgeToEdge + windowInsets(0)），圆屏上返回箭头和
                         // 大标题正落在被表圈切掉的四角里。下边缘不在屏幕边上，所以只收左右和上。
                         // 栏变高之后 Scaffold 给内容的内距也随之变大 → 下面的内容 Box 顶部不再重复加 insetV。
                         modifier = Modifier.screenFitPadding(bottom = false),
                         navigationIcon = {
                             androidx.compose.material3.IconButton(onClick = { navBack() }) {
                                 Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回"))
                             }
                         },
                         title = {
                             // 标题变化加动画：原来是硬切(页面淡入、标题却瞬间跳)
                             androidx.compose.animation.AnimatedContent(
                                 targetState = pageTitle(currentPage),
                                 transitionSpec = {
                                     (androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
                                         androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(180)) { it / 3 }) togetherWith
                                         androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                                 },
                                 label = "title",
                             ) { t -> Text(t, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                         },
                         colors = TopAppBarDefaults.largeTopAppBarColors(
                             // 收拢色 == 展开色：折叠时不跑 Material 的容器色交叉渐变（每帧色值动画=白churn）。
                             // 全屏沉浸下栏底本就该与内容同底、无缝，这与 rikkahub 的做法一致。
                             containerColor = scheme.background,
                             scrolledContainerColor = scheme.background,
                             titleContentColor = scheme.onSurface,
                             navigationIconContentColor = scheme.onSurface,
                         ),
                         windowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),  // 全屏沉浸：不吃状态栏留白
                         scrollBehavior = m3ScrollBehavior,
                     )
                 }
             },
             containerColor = scheme.background,
             // 全屏：系统栏已隐藏，Scaffold 不加任何 inset，内容铺满整屏
             contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
         ) { padding ->
             val pageBg = remember(currentPage) { PageBackgroundPrefs.get(context, currentPage) }
             // 蒙层不透明度：全局设置，读一次即可（原先每次重组都 getFloat，虽轻也没必要）。currentPage 变化时重取足够。
             val scrimAlpha = remember(currentPage) { PageBackgroundPrefs.scrimAlpha(context) }
             // GLASS 风格才画玻璃；M3 风格由上面的 TopAppBar 实心占位，再叠玻璃是多余的
             val useGlass = !chromeless && chromeStyle == com.arix.app.theme.ChromeStyle.GLASS
             // ⚠ 这层**不能**套 `.padding(padding)`（Scaffold 给的内容内距=M3 折叠大标题时每帧变的栏高）。
             // 一旦套了，折叠那 48px 里整棵子树（背景 AsyncImage + 常驻聊天页 composition + 换页容器）
             // 会被每帧重新测量——常驻聊天页是 heavy LazyColumn，白重测就是「大标题变小标题时很卡」的元凶
             // （深度排查两路确认：无壁纸也犯，因它是布局层而非绘制层成本）。
             // 改为：背景/常驻聊天满屏恒定不随折叠动；只把 `padding` 加到下面真正需要让位的换页 AnimatedContent 上，
             // 折叠时仅当前页（设置=LazyColumn）增量重测。
             Box(modifier = Modifier.fillMaxSize()) {
                 // 全局玻璃：所有玻璃面（卡片/气泡/顶栏/聊天输入栏）统一走自建「模糊一次·多处采样」管线（见 XtomGlassPipeline）——
                 // 采样预模糊好的静态壁纸，不再每帧把整页录进图层给顶栏做 RenderEffect 模糊（pageLayer/chatLayer 录制已删，省一大笔）。
                 // 独立开关；设备撑不住(<API31/低内存)退回不玻璃。
                 val glassOn = com.arix.app.theme.LocalThemeConfig.current.globalGlass && com.arix.app.theme.isBlurCapable(context)
                 Box(modifier = Modifier.fillMaxSize()) {
                 // 背景（底色 + 可选壁纸）= 玻璃的模糊源。背景本身保持清晰；模糊走自建「模糊一次·多处采样」管线
                 // （见 XtomGlassPipeline，绕开 haze 在本机点采样放大糊成马赛克的问题）。
                 // 测背景在屏上的尺寸与位置：玻璃面按 (自身 window 坐标 - 背景 window 坐标) 去采样预模糊图对应区域。
                 var bgSizePx by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
                 val bgOrigin = remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
                 // 稳定的 originProvider：别每次重组新建 lambda，否则 GlassState 每次都不等、glass 消费者整片重组
                 val glassOriginProvider = remember { { bgOrigin.value } }
                 Box(modifier = Modifier.matchParentSize()
                     .onSizeChanged { bgSizePx = it }
                     .onGloballyPositioned { bgOrigin.value = it.positionInWindow() }) {
                     Box(modifier = Modifier.matchParentSize().background(scheme.background))
                     if (pageBg != null) {
                         coil.compose.AsyncImage(model = pageBg, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Crop, modifier = Modifier.matchParentSize())
                         Box(modifier = Modifier.matchParentSize().background(scheme.background.copy(alpha = scrimAlpha)))
                     }
                 }
                 // 玻璃尺寸量化：M3 折叠大标题时内容区高度每帧变→bgSizePx 每帧变。若原样喂下去，
                 //  ① rememberGlassBackdrop 每帧重解码壁纸+重模糊（抢 CPU）；
                 //  ② GlassState.srcW/srcH 每帧不等，而 LocalGlass 是 staticCompositionLocalOf → 整棵子树每帧重组。
                 // 两者都是「大标题变小标题时很卡」的元凶。量化到 96px 网格（derivedStateOf 只在跨格时才发新值），
                 // 折叠期间尺寸恒定 → 不重模糊、不重组子树。背景是模糊图，量化几十像素的拉伸差看不出。
                 val glassSize by remember {
                     androidx.compose.runtime.derivedStateOf {
                         val q = 96
                         androidx.compose.ui.unit.IntSize(
                             ((bgSizePx.width + q - 1) / q) * q,
                             ((bgSizePx.height + q - 1) / q) * q,
                         )
                     }
                 }
                 // 预模糊背景（仅壁纸/底色/蒙层/量化尺寸变化时后台重算并缓存；折叠期间尺寸恒定=零重算）
                 val glassBackdrop = if (glassOn) com.arix.app.ui.rememberGlassBackdrop(pageBg, scheme.background, scrimAlpha, glassSize) else null
                 // 屏幕适配内缩加在这里而不是 PageScaffold：后者只有 5 个页面在用，盖不住全部 30+ 页。
                 // （`fit` 已在上面 useM3Bar 处取好——顶栏也要用同一份。）
                 androidx.compose.runtime.CompositionLocalProvider(
                     com.arix.app.ui.LocalGlass provides com.arix.app.ui.GlassState(
                         on = glassOn, backdrop = glassBackdrop, srcW = glassSize.width, srcH = glassSize.height,
                         originProvider = glassOriginProvider,
                     ),
                     com.arix.app.ui.LocalTitleCollapse provides titleCollapse,   // 各页 topChromeGap 读它，让位随折叠收缩
                 ) {
                 // 顶部内缩只在**非 M3** 时加：M3 顶栏自己已经吃了 insetV（见 topBar），栏因此变高，
                 // Scaffold 给内容的内距把这份让位原样带给了换页容器；这里再加一次就是白空一截。
                 // GLASS/无栏时顶栏是浮层不占布局，必须由这里给内容让出 insetV（并与顶栏的 top=insetV 对齐）。
                 Box(modifier = Modifier.screenFitPadding(top = !useM3Bar)) {
                 // ── 聊天页常驻 composition，不参与下面 AnimatedContent 的换页 ──
                 // 原先它挂在 when 分支里，切个页面就被 dispose——而整段会话状态（conversationMsgs /
                 // chatBubbles / tree / convId 全是 remember）跟着一起没。于是：生成协程还活着，继续往
                 // 那份已被丢弃的列表里写；切回来的是个新实例，从 DB 重读（而生成中途根本没落盘）
                 // → 用户看到「刚发的消息、AI 回复、工具卡全不见了」。两份状态从此再不合流。
                 // 常驻 = 永远只有一份状态，切回来直接接上，不需要重载、也不会产生孤儿实例。
                 val chatVisible = currentPage == "chat"
                 val chatAlpha by animateFloatAsState(
                     targetValue = if (chatVisible) 1f else 0f,
                     animationSpec = tween(if (chatVisible) 220 else 150),
                     label = "chatFade",
                 )
                 Box(modifier = Modifier.fillMaxSize()
                     .graphicsLayer { alpha = chatAlpha }
                     // 隐藏时**跳过整棵子树的 draw**（连带 chatLayer.record 也不跑）。
                     // 这是性能措施、不是正确性措施——alpha=0 本来就不显示；但 alpha=0 仍会照常绘制，
                     // 在手表上白画一整屏聊天+录一次图层，纯烧电。
                     // ⚠ 别因此以为 graphicsLayer 那行多余：删了 alpha 只留这行，淡入淡出就没了。
                     .drawWithContent { if (chatAlpha > 0.001f) drawContent() }
                     // 隐藏时**吃掉触摸**：常驻意味着它还在命中测试里，而盖在上面的页面是透明的
                     // ——不拦的话，点空白处会穿透下去滚动/点到底下看不见的聊天列表。
                     // 在 Initial 段（父→子）消费掉所有 change：consume 本身不中断派发，但标准手势
                     // （clickable/detectTapGestures 的 requireUnconsumed、scrollable 的 slop 检测）
                     // 都会 check isConsumed 而放弃 → 子树里的点击和滚动都失效。
                     //
                     // ⚠⚠ 顺序是承重的：**聊天包装层必须声明在下面的 AnimatedContent 之前**。
                     // 命中测试按 z 逆序（后声明的 z 更高、先命中），派发按分支深度优先，
                     // 所以上层页面整条分支跑完才轮到这里 Initial 消费 → 上层页面不受影响。
                     // 谁要是调换了这两者的顺序、或给谁加了 zIndex，**所有其他页面的点击和滚动会立刻全废，
                     // 而且编译不会报任何错**。
                     .pointerInput(chatVisible) {
                         if (!chatVisible) awaitPointerEventScope {
                             while (true) {
                                 awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                     .changes.forEach { it.consume() }
                             }
                         }
                     }
                 ) {
                     Box(modifier = Modifier.fillMaxSize()) {
                         Box(modifier = Modifier.fillMaxSize()) {
                         key(conversationKey) { ChatPage(ts = ::ts, scope = scope, context = context, conversationId = pendingConversationId,
                             onPickImage = { cb -> pendingOnImagesPicked = cb; imagePickerLauncher.launch(arrayOf("image/*")) },
                             onPickFile = { cb -> pendingOnImagesPicked = cb; imagePickerLauncher.launch(arrayOf("*/*")) },
                             onCamera = { cb -> pendingOnImagesPicked = cb; if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) launchCamera() else cameraPermLauncher.launch(Manifest.permission.CAMERA) },
                             searchActive = chatSearchActive, onSearchClose = { chatSearchActive = false },
                             onBarsVisible = { chatBarsVisible = it }, topContentPadding = 58.dp,
                             // 常驻之后「离开页面」不再等于「被销毁」：原本靠 dispose 停掉/收尾的东西
                             // （语音通话浮层、提示轮播、流式跟随滚动）得靠这个标志自己收手。
                             visible = chatVisible) }
                         }
                         // 透明浮动顶栏：覆盖在聊天内容之上，内容可从其下方透过；随滚动自动隐藏
                         androidx.compose.animation.AnimatedVisibility(
                             visible = chatBarsVisible,
                             modifier = Modifier.align(Alignment.TopCenter),
                             enter = androidx.compose.animation.slideInVertically(tween(220)) { -it } + androidx.compose.animation.fadeIn(tween(220)),
                             exit = androidx.compose.animation.slideOutVertically(tween(200)) { -it } + androidx.compose.animation.fadeOut(tween(160)),
                         ) {
                             // 顶栏底：开玻璃走自建管线采样模糊壁纸（同气泡，一套机制）；关玻璃退回渐变纯色底。
                             // 底缘 DstIn 渐隐让整条化进内容，胶囊在顶部不受影响。
                             val barGlassOn = com.arix.app.ui.LocalGlass.current.on
                             // drawWithCache 而非 drawWithContent：Brush 只随尺寸重建一次，不再每帧新建
                             // （顶栏含跑马灯时会每帧重绘，原来每帧 alloc 一个 3/4 段渐变）。绘制顺序严格不变。
                             val barBgMod = if (barGlassOn) Modifier
                                 .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                                 .glassSurface(androidx.compose.ui.graphics.RectangleShape, scheme.surfaceContainerHigh)
                                 .drawWithCache {
                                     // glassSurface 已在 drawBehind 画好模糊壁纸底；这里先 DstIn 渐隐底，再画内容(胶囊不跟着渐隐)
                                     val fade = (26.dp.toPx() / size.height).coerceIn(0f, 0.5f)
                                     val b = Brush.verticalGradient(0f to Color.Black, 1f - fade to Color.Black, 1f to Color.Transparent)
                                     onDrawWithContent {
                                         drawRect(brush = b, blendMode = BlendMode.DstIn)
                                         drawContent()
                                     }
                                 }
                             else Modifier.drawWithCache {
                                 val bg = scheme.background
                                 val b = Brush.verticalGradient(0f to bg, 0.7f to bg, 1f to Color.Transparent)
                                 onDrawWithContent {
                                     drawRect(brush = b)
                                     drawContent()
                                 }
                             }
                             Box(modifier = Modifier.fillMaxWidth().then(barBgMod)) {
                                 Row(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 16.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                 CircleTopButton(Icons.Outlined.Segment, tr("菜单"), onClick = { scope.launch { drawerState.open() } })
                                 Spacer(Modifier.width(6.dp))
                                 // 玻璃开时胶囊半透明（原本就是半透明悬浮胶囊，别做成纯色）
                                 val pillGlass = com.arix.app.ui.LocalGlass.current.on
                                 // 胶囊必须**可让位**：它原来是没有 weight 的固定宽子项，Row 会优先满足它
                                 // （角色名最宽 96 + 模型名 86 + 内边距图标 ≈ 230dp），窄屏上直接把后面的
                                 // 搜索/新建两颗圆钮挤出屏幕。裹一层 weight(1f) 的 Box：圆钮是无 weight 子项、
                                 // 先按本征宽度量好，剩下多少胶囊用多少，名字过长就在胶囊内部省略号截断。
                                 // 同时这层 Box 顶掉了原来那个 Spacer(weight(1f))——它已经承担了「把圆钮推到最右」。
                                 Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                 Surface(shape = RoundedCornerShape(50), color = if (pillGlass) scheme.surfaceContainerHighest.copy(alpha = 0.62f) else scheme.surfaceContainerHighest, border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant), shadowElevation = com.arix.app.ui.flatShadowElevation(4.dp), modifier = Modifier.height(38.dp)) {
                                    Row(modifier = Modifier.fillMaxHeight(), verticalAlignment = Alignment.CenterVertically) {
                                        Row(modifier = Modifier.fillMaxHeight().padding(start = 12.dp, end = if (activeModel.isBlank()) 10.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                            com.arix.app.ui.MarqueeText(tr("助手"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, active = chatVisible, modifier = Modifier.widthIn(max = 96.dp))
                                        }
                                        if (activeModel.isNotBlank()) {
                                            Box(modifier = Modifier.width(1.dp).fillMaxHeight().padding(vertical = 8.dp).background(scheme.outlineVariant))
                                            Row(modifier = Modifier.clickable { showModelSwitcher = true }.fillMaxHeight().padding(start = 8.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                                com.arix.app.ui.MarqueeText(activeModel, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, active = chatVisible, modifier = Modifier.widthIn(max = 86.dp))
                                                Spacer(Modifier.width(3.dp))
                                                Icon(Icons.Outlined.UnfoldMore, contentDescription = tr("切换模型"), tint = scheme.onSurfaceVariant, modifier = Modifier.size(13.dp))
                                            }
                                        }
                                    }
                                 }
                                 }
                                 // 三个圆钮常态统一中性色；只有「搜索激活」这种状态才点缀成蓝，别常态就上色。
                                 CircleTopButton(Icons.Outlined.Search, tr("搜索当前对话"), tint = if (chatSearchActive) scheme.primary else scheme.onSurfaceVariant, onClick = { chatSearchActive = !chatSearchActive })
                                 CircleTopButton(Icons.Outlined.Add, tr("新建对话"), onClick = { pendingConversationId = null; conversationKey++; currentPage = "chat" })
                             }
                             }
                         }
                     }
                 } // end 常驻聊天页（alpha/不画/吃触摸 的包装层）
                 // ── 其余页面照旧走 AnimatedContent 换页；聊天页不在其中（它常驻在上面） ──
                 androidx.compose.animation.AnimatedContent(
                     targetState = currentPage,
                     // nestedScroll 接住子页滚动喂给大标题折叠（聊天页在此容器之外，不受影响）。
                     // `.padding(padding)` 只加在这里（不在外层）：M3 折叠时栏高每帧变，只让当前页容器随之重测，
                     // 背景与常驻聊天页在外层满屏恒定、不陪着每帧重排（见外层 Box 注释）。GLASS 风格 padding≈0，无副作用。
                     modifier = Modifier
                         .padding(padding)
                         .nestedScroll(titleNestedScroll)
                     // 预测式返回跟手：拖动时整块换页容器缩小并向拖动缘反方向偏，配圆角，做「即将退出」的实时预览。
                         .graphicsLayer {
                         val p = backAnim.value
                         if (p > 0f) {
                             val s = 1f - 0.10f * p
                             scaleX = s; scaleY = s
                             translationX = (if (backEdgeLeft) 1f else -1f) * p * size.width * 0.06f
                             alpha = 1f - 0.15f * p
                             clip = true
                             shape = RoundedCornerShape((p * 28f).dp)
                         }
                     },
                     transitionSpec = {
                         // 参照 rikkahub：新页整幅从方向侧滑入淡入；旧页反向滑半程 + 轻微缩小 + 淡出。方向由 navForward 决定（前进右来/后退左来）。
                         val dir = if (navForward) 1 else -1
                         val e = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntOffset>(340, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                         val es = androidx.compose.animation.core.tween<Float>(340, easing = androidx.compose.animation.core.FastOutSlowInEasing)
                         val f = androidx.compose.animation.core.tween<Float>(200)
                         (androidx.compose.animation.slideInHorizontally(e) { w -> dir * w } + androidx.compose.animation.fadeIn(f)) togetherWith
                             (androidx.compose.animation.slideOutHorizontally(e) { w -> -dir * w / 2 } +
                                 androidx.compose.animation.scaleOut(es, targetScale = 0.92f) +
                                 androidx.compose.animation.fadeOut(f))
                     },
                     label = "page",
                 ) { animPage ->
                     // 换页「假模糊」：不逐帧 RenderEffect（那会在新页首composite 的同时整屏高斯模糊，手表卡）。
                     // 改法同玻璃——把本页录进 graphicsLayer，切换开始时取一次快照做下采样+盒式模糊(fakeBlurBitmap)，
                     // 逐帧只按进度 alpha 把这张模糊图叠在清晰页上淡出 → 一次模糊、逐帧只贴图。
                     // 快照没就绪(如入场页还没画第一帧)时优雅退化为清晰，绝不崩。
                     val blurFrac = transition.animateFloat(
                         transitionSpec = { androidx.compose.animation.core.tween(220) }, label = "pageBlur",
                     ) { st -> if (st == androidx.compose.animation.EnterExitState.Visible) 0f else 1f }
                     val pLayer = rememberGraphicsLayer()
                     var pBlur by remember { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
                     val transitioning = transition.currentState != transition.targetState
                     LaunchedEffect(transitioning) {
                         if (transitioning) {
                             androidx.compose.runtime.withFrameNanos {}   // 等本页至少录到一帧内容再取快照
                             val snap = runCatching { pLayer.toImageBitmap() }.getOrNull()
                             pBlur = if (snap != null && snap.width > 2) withContext(kotlinx.coroutines.Dispatchers.Default) { com.arix.app.ui.fakeBlurBitmap(snap) } else null
                         } else pBlur = null
                     }
                     Box(Modifier.fillMaxSize().drawWithContent {
                         val a = blurFrac.value
                         if (transitioning || a > 0.01f) {
                             // 切换期间：录进图层再画（好让快照能取到），并按进度叠模糊图
                             pLayer.record { this@drawWithContent.drawContent() }
                             drawLayer(pLayer)
                             val b = pBlur
                             if (b != null && a > 0.01f) {
                                 drawImage(image = b, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()), alpha = a.coerceIn(0f, 1f))
                             }
                         } else {
                             drawContent()   // 非切换（含页面内正常滚动）：直接画，不套图层，零额外开销
                         }
                     }) { when (animPage) {
                     // 空占位：真正的聊天页常驻在这层下面，让它透出来即可
                     "chat" -> Box(modifier = Modifier.fillMaxSize())
                     "conversations" -> ConversationListScreen(scope = scope, context = context,
                         onSelectConversation = { convId -> pendingConversationId = convId; conversationKey++; currentPage = "chat" })
                     "config" -> ConfigPage(scope = scope, context = context, hasAudioPerm = hasAudioPerm,
                         requestPerm = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) })
                     "stt" -> SttPage(addLog = { }, ts = ::ts, scope = scope, context = context, hasAudioPerm = hasAudioPerm,
                         requestPerm = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) })
                     "tts" -> TtsPage(scope = scope, context = context)
                     "voice_clone" -> VoiceClonePage(scope = scope, context = context, hasAudioPerm = hasAudioPerm, requestPerm = { permLauncher.launch(Manifest.permission.RECORD_AUDIO) })
                     "settings" -> AppSettingsPage(context = context)
                     "personalization" -> PersonalizationPage(context = context)
                     "dialog_settings" -> DialogSettingsPage(context = context)
                     "tool_keys" -> ToolKeysPage(context = context)
                     "search_settings" -> SearchSettingsPage(context = context)
                     "user_scripts" -> UserScriptPage(context = context)
                     "about" -> AboutPage(context = context)
                     "favorites" -> FavoritesPage(context = context)
                     "memory" -> MemoryPage(scope = scope, context = context)
                     "packages" -> PackagesPage(scope = scope, context = context)
                     "wake" -> WakePage(scope = scope, context = context)
                     "permissions" -> PermissionsPage(context = context)
                     "plugins" -> PluginCreatorPage(scope = scope, context = context)
                      "import" -> ImportExportPage(scope = scope, context = context)
                      "usage" -> UsageStatsPage(context = context)
                      "browser" -> BrowserPage(onBack = { currentPage = "chat" })
                      // 旧路由保留：外部/历史入口可能还指着它，MonitorPage 现在只是转发到活动中心
                      "monitor" -> MonitorPage(scope = scope)
                      "activity_center" -> ActivityCenterPage(scope = scope, onOpenRoute = { navTo(it) })
                      "crash" -> {
                         val crashReports = remember { mutableStateListOf<File>().also { it.addAll(CrashHandler.getCrashReports(context)) } }
                         CrashReportPage(scope = scope, context = context, reports = crashReports, onReportsChanged = { crashReports.clear(); crashReports.addAll(CrashHandler.getCrashReports(context)) })
                     }
                      // 非崩溃问题的自取证：内存环形日志（不落盘、不外发），与「崩溃报告」互补
                      "app_log" -> AppLogPage(context = context)
                      "proxy" -> ProxySettingsPage(context = context)
                      "storage" -> StorageUsagePage(context = context)
                      "update" -> UpdateCheckPage(context = context)
                      // 设置中心：搜索 + 分组卡片 + 长按人话说明
                      "settings_hub" -> SettingsHubPage { navTo(it) }
                      // 文件 / 项目：GPT 式入口
                      "files" -> FilesPage(context = context)
                      "file_history" -> FileHistoryPage(context = context)
                      "chat_appearance" -> ChatAppearancePage(context = context)
                      "projects" -> ProjectsPage(scope = scope, context = context,
                          onOpenConversation = { convId -> pendingConversationId = convId; conversationKey++; currentPage = "chat" })
                 } } }
                 } // end「常驻聊天 + 换页」容器 Box
                 } // end CompositionLocalProvider(LocalGlassBackdrop)
                 } // end 录制层 Box（只包页面内容，不含下面的玻璃层）
                 // 顶部玻璃 + 悬浮返回胶囊。整层除胶囊外没有任何 pointerInput，
                 // 不参与命中测试 → 不吃触摸，底下的内容照常点得到。
                 if (useGlass) {
                     TopGlassChrome(
                         title = pageTitle(currentPage),
                         onBack = { navBack() },
                         collapse = titleCollapse,
                         bigTitle = currentPage != "chat",   // 聊天页有自己的顶栏，不显示大标题
                         // 屏幕适配：这层是内容 Box 的兄弟节点，吃不到内容那边的内缩，得自己收。
                         // 只收左右和上（下边缘是渐隐边、不在屏幕边上）。收了 top=insetV 之后，
                         // 栏底 = insetV + 栏高，而内容顶 = insetV(内容 Box) + topChromeGap(=同一个栏高)，两边正好对齐。
                         modifier = Modifier.align(Alignment.TopCenter).screenFitPadding(bottom = false),
                     )
                 }
             }
         } // end Scaffold
         } // end 下沉/莫奈填充/拦触摸 Box
     } // end ModalNavigationDrawer

     // 抽屉排布编辑器：独立整页（左=导航区/底部区排布，右=组件面板）。
     androidx.compose.animation.AnimatedVisibility(
         visible = drawerEditMode,
         enter = androidx.compose.animation.fadeIn(tween(200)),
         exit = androidx.compose.animation.fadeOut(tween(200)),
     ) {
         val editSink = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
         Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = editSink, indication = null) {}) {
             DrawerLayoutEditorPage(
                 items = drawerLayout, ctl = drawerEditCtl, entryById = entryById, paletteGroups = drawerPaletteGroups,
                 onReset = { DrawerLayoutStore.reset(context); applyDrawerLayout(DrawerLayoutStore.load(context)) },
                 onDone = { drawerEditMode = false },
             )
         }
     }

     // 快捷切换对话模型：点顶栏胶囊右半的模型名弹出。只列「对话」用途的配置——
     // 其余用途（视觉/向量/翻译…）各有各的激活项，在这儿切会把它们的配置搅乱。
     if (showModelSwitcher) {
         val chatConfigs = remember(topConfigs) { topConfigs.filter { it.purpose == "chat" } }
         androidx.compose.material3.AlertDialog(
             onDismissRequest = { showModelSwitcher = false },
             title = { Text(tr("切换模型"), color = scheme.onSurface) },
             text = {
                 Column {
                     if (chatConfigs.isEmpty()) {
                         Text(tr("还没有「对话」用途的模型配置"), color = scheme.onSurfaceVariant, fontSize = 13.sp)
                     } else {
                         Text(tr("当前对话与后续新对话都用选中的模型"), color = scheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                         LazyColumn(modifier = Modifier.heightIn(max = 280.dp)) {
                             items(chatConfigs.size, key = { chatConfigs[it].id }) { idx ->
                                 val cfg = chatConfigs[idx]
                                 Card(
                                     modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                                     colors = CardDefaults.cardColors(containerColor = if (cfg.isActive) scheme.primaryContainer else scheme.surfaceContainerHigh),
                                     shape = MaterialTheme.shapes.medium,
                                     elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                                     onClick = {
                                         showModelSwitcher = false
                                         // switchTo 会先停掉同用途的其它配置再激活这个，正是「切换」的语义
                                         scope.launch { topConfigManager.switchTo(cfg.id) }
                                     },
                                 ) {
                                     Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                         Column(modifier = Modifier.weight(1f)) {
                                             Text(cfg.name, color = if (cfg.isActive) scheme.onPrimaryContainer else scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                             Text(cfg.model, color = if (cfg.isActive) scheme.onPrimaryContainer else scheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                         }
                                         if (cfg.isActive) Icon(Icons.Outlined.Check, contentDescription = tr("使用中"), tint = scheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
                                     }
                                 }
                             }
                         }
                     }
                 }
             },
             confirmButton = {
                 TextButton(onClick = { showModelSwitcher = false; navTo("config") }) { Text(tr("模型配置"), color = scheme.primary) }
             },
             dismissButton = { TextButton(onClick = { showModelSwitcher = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
             containerColor = scheme.surface, shape = MaterialTheme.shapes.extraLarge
         )
     } // end showModelSwitcher

     // 抽屉搜索小圆球：按标题/消息内容搜索所有对话，选中即打开
     if (showGlobalSearch) {
         ConversationSearchOverlay(
             scope = scope, context = context,
             onSelect = { convId -> pendingConversationId = convId; conversationKey++; currentPage = "chat"; showGlobalSearch = false },
             onDismiss = { showGlobalSearch = false },
         )
     }

     // 唤醒/助手召出全屏 overlay（Gemini 风动画）：进入对话即落进完整聊天页
     if (showAssistant) {
         WakeAssistantOverlay(
             phase = tr("已唤醒"),
             onEnter = { showAssistant = false; currentPage = "chat" },
             onClose = { showAssistant = false },
         )
     }
 } // end MainScreen

 // ============================================================
 // 会话搜索（顶栏放大镜）—— 按标题或消息内容搜索，选中即打开（仿 RikkaHub）
 // ============================================================
 @OptIn(ExperimentalMaterial3Api::class)
 @Composable private fun ConversationSearchOverlay(
     scope: kotlinx.coroutines.CoroutineScope,
     context: android.content.Context,
     onSelect: (Long) -> Unit,
     onDismiss: () -> Unit,
 ) {
     val scheme = MaterialTheme.colorScheme
     val convManager = remember { ConversationManager(context) }
     // 轻量投影流：不拉 messagesJson（会破 2MB 游标窗口）。
     val active by convManager.repo.activeSummaries.collectAsState(initial = emptyList())
     val archived by convManager.repo.archivedSummaries.collectAsState(initial = emptyList())
     var query by remember { mutableStateOf("") }
     val all = remember(active, archived) { active + archived }
     // 空词→按时间列元数据；有词→SQL LIKE 命中（标题/内容都在 SQLite 内部匹配，只投影小列，不物化大 JSON）。
     var results by remember { mutableStateOf<List<com.arix.data.dao.ConversationSummary>>(emptyList()) }
     LaunchedEffect(query, all) {
         val q = query.trim()
         results = if (q.isBlank()) all.sortedByDescending { it.updatedAt }.take(30)
                   else withContext(Dispatchers.IO) { convManager.repo.searchSummaries(q, 50) }
     }
     val dateFmt = remember { SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()) }
     Surface(modifier = Modifier.fillMaxSize(), color = scheme.background) {
         Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
             Row(verticalAlignment = Alignment.CenterVertically) {
                 com.arix.app.ui.XtomField(
                     value = query, onValueChange = { query = it },
                     modifier = Modifier.weight(1f),
                     placeholder = tr("搜索对话标题或内容"),
                     leading = { Icon(Icons.Outlined.Search, contentDescription = null, tint = scheme.onSurfaceVariant) },
                     textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                 )
                 TextButton(onClick = onDismiss) { Text(tr("关闭"), color = scheme.onSurfaceVariant, fontSize = 12.sp) }
             }
             Spacer(modifier = Modifier.height(8.dp))
             if (results.isEmpty()) {
                 Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                     Text(if (query.isBlank()) tr("暂无对话") else tr("无匹配对话"), color = scheme.onSurfaceVariant, fontSize = 13.sp)
                 }
             } else {
                 LazyColumn(modifier = Modifier.fillMaxSize()) {
                     items(results.size, key = { results[it].id }) { idx ->
                         val conv = results[idx]
                         // 片段按命中项 id 单列懒取 messagesJson（≤1MB 稳在窗口内），不整表拉大列。
                         var snip by remember(conv.id, query) { mutableStateOf("") }
                         LaunchedEffect(conv.id, query) {
                             snip = if (query.isBlank()) "" else withContext(Dispatchers.IO) { convMatchSnippet(convManager.repo.getMessagesJson(conv.id), query) }
                         }
                         Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainer), shape = RoundedCornerShape(14.dp), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), onClick = { onSelect(conv.id) }) {
                             Column(modifier = Modifier.padding(12.dp)) {
                                 Text(conv.title, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                                 if (snip.isNotBlank()) Text(snip, color = scheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 2)
                                 Text(dateFmt.format(Date(conv.updatedAt)), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp))
                             }
                         }
                     }
                 }
             }
         }
     }
 }

 // 从消息 JSON 里截取匹配关键词的一小段上下文（去掉 JSON 噪声，供搜索结果预览）
 private fun convMatchSnippet(messagesJson: String, query: String): String {
     val idx = messagesJson.indexOf(query, ignoreCase = true)
     if (idx < 0) return ""
     val start = (idx - 30).coerceAtLeast(0)
     val end = (idx + query.length + 40).coerceAtMost(messagesJson.length)
     return "…" + messagesJson.substring(start, end)
         .replace(Regex("[{}\\[\\]\"\\\\]"), " ")
         .replace(Regex("(role|content|reasoning|toolCalls|toolCallId)\\s*:"), " ")
         .replace(Regex("\\s+"), " ").trim() + "…"
 }
 
 // 设置分组列表：圆角卡片里若干行，每行 图标+文字，行间细分隔线（仿参考图，全令牌化）。
 // explain = 长按弹出的「人话」说明。
 private data class SettingsRow(val icon: androidx.compose.ui.graphics.vector.ImageVector, val label: String, val page: String, val explain: String = "")
 @OptIn(ExperimentalFoundationApi::class)
 @Composable private fun SettingsGroup(title: String, items: List<SettingsRow>, onClick: (String) -> Unit, onExplain: (SettingsRow) -> Unit) {
     val scheme = MaterialTheme.colorScheme
      if (title.isNotBlank()) Text(title, color = scheme.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp))
     // 全局玻璃：设置分组卡也玻璃化（透背后壁纸模糊）。关玻璃时 glassSurface 退化成半透明/纯色。
     val ggShape = RoundedCornerShape(20.dp)
     Box(modifier = Modifier.fillMaxWidth().clip(ggShape).glassSurface(ggShape, scheme.surfaceContainer).border(1.dp, scheme.outlineVariant, ggShape)) {
         Column {
             items.forEachIndexed { i, it ->
                 Row(modifier = Modifier.fillMaxWidth().combinedClickable(onClick = { onClick(it.page) }, onLongClick = { if (it.explain.isNotBlank()) onExplain(it) }).padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                     Icon(it.icon, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(22.dp))
                     Spacer(Modifier.width(16.dp))
                     Text(it.label, color = scheme.onSurface, fontSize = 15.sp, modifier = Modifier.weight(1f))
                 }
                 if (i < items.lastIndex) HorizontalDivider(color = scheme.outlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(start = 54.dp))
             }
         }
     }
 }

 // 设置中心页：搜索 + 分组 + 长按「人话」说明
 @Composable private fun SettingsHubPage(onNavigate: (String) -> Unit) {
     val scheme = MaterialTheme.colorScheme
     var q by remember { mutableStateOf("") }
     var explain by remember { mutableStateOf<SettingsRow?>(null) }
      val groups = remember(LocalLang.current) {
          listOf(
              tr("个性化") to listOf(
                 SettingsRow(Icons.Outlined.Palette, tr("个性化（外观 / 头像 / 背景）"), "personalization", tr("外观与主题(配色/明暗/圆角/字体/字号/密度/模糊/动效/主题包)、我的名字和头像、每页背景图、滚动自动隐藏顶栏。改任意外观项即时应用到全 App。"))),
             tr("对话与模型") to listOf(
                 SettingsRow(Icons.Outlined.Settings, tr("模型配置"), "config", tr("按用途配模型：对话/推理/视觉/标题，以及语音朗读(TTS)、语音识别(STT)。每个用途各选一个模型，含 API 地址/密钥/温度等参数。")),
                 SettingsRow(Icons.Outlined.Style, tr("对话设置（压缩 / 自动记忆 / 快捷短语）"), "dialog_settings", tr("长对话上下文压缩、AI 直接执行设备操作、对话后自动抽取记忆、快捷短语增删。")),
                SettingsRow(Icons.Outlined.RecordVoiceOver, tr("声音克隆"), "voice_clone", tr("录一段/选一段人声，调 Minimax 或硅基流动克隆 API 生成专属音色，克隆完可直接用于朗读。")),
                SettingsRow(Icons.Outlined.Code, tr("用户脚本（油猴式）"), "user_scripts", tr("给指定网址配 JS 脚本，open_page 打开匹配网址时自动注入执行（去广告/展开正文/破懒加载等）。")),
                 SettingsRow(Icons.Outlined.Public, tr("联网搜索（引擎 / 深度研究）"), "search_settings", tr("配置联网搜索：14 键控引擎(Tavily/Brave/…)、AnySearch、Perplexica，以及深度研究(deep_search)的研究模型与轮数。普通搜索默认必应+百度免配置；这里是可选增强，默认全关，开启并填 key 才用。")),
                 SettingsRow(Icons.Outlined.History, tr("对话管理"), "conversations", tr("查看/管理全部对话：搜索、重命名、归档、删除历史对话。")),
                 SettingsRow(Icons.Outlined.Bookmark, tr("收藏"), "favorites", tr("聊天里长按消息「收藏」的内容，集中查看、复制、删除。")),
                 SettingsRow(Icons.Outlined.Dashboard, tr("使用统计"), "usage", tr("对话/消息/Token 总量 + 近 17 周活跃热力图。纯本地统计。")),
                 SettingsRow(Icons.Outlined.Dashboard, tr("工作流"), "workflows", tr("把常做的一串操作存成可复用流程，一键跑（让 AI 用 workflow 工具建）。")),
                 SettingsRow(Icons.Outlined.Folder, tr("导入导出"), "import", tr("把角色卡/记忆/配置导出成文件备份，或从文件导入恢复。换手机、备份时用。")),
             ),
             tr("语音") to listOf(
                 SettingsRow(Icons.Outlined.Mic, tr("语音唤醒"), "wake", tr("喊唤醒词就能唤起助手，免动手。开了更方便但更耗电、后台常驻。")),
             ),
             tr("工具与扩展") to listOf(
                 SettingsRow(Icons.Outlined.Dashboard, tr("项目"), "projects", tr("把相关对话归组成项目，并给项目写一条『项目指令』——项目内每次对话自动带上。")),
                 SettingsRow(Icons.Outlined.Settings, tr("工具密钥（地图 / 生活 API / 提醒）"), "tool_keys", tr("地图 API Key、生活 API Key（mxnzp），以及查看/取消 App 内定时提醒。")),
                 SettingsRow(Icons.Outlined.Public, tr("本地包"), "packages", tr("本机装的工具插件包，管理启用/禁用。启用越多 AI 能力越强，但提示词更长、可能更慢。")),
                 SettingsRow(Icons.Outlined.History, tr("文件改动历史"), "file_history", tr("AI 改过工作目录里的哪些文件、改了什么，能看 diff 并一键退回改动前。快照存在 AI 够不到的地方，它删不掉自己的后悔药。")),
                 SettingsRow(Icons.Outlined.ChatBubbleOutline, tr("聊天外观"), "chat_appearance", tr("用户侧和 AI 侧分别调气泡圆角/尖角/配色、头像大小与圆角、是否显示头像和名字，页面上方实时预览。")),
                 SettingsRow(Icons.Outlined.Add, tr("插件制作"), "plugins", tr("自己做自定义工具插件(Skill/沙盒/MCP)。")),
             ),
             tr("系统") to listOf(
                 SettingsRow(Icons.Outlined.Menu, tr("权限管理"), "permissions", tr("管理 AI 工具的权限(录音/相机/定位等)，控制哪些能用。收紧→更安全但某些功能用不了；放开→更好用但风险高。")),
                 SettingsRow(Icons.Outlined.Lan, tr("网络代理"), "proxy", tr("让 App 的联网请求走你自己的 HTTP 或 SOCKS5 代理：填主机端口、可选账号密码，还能当场「测试连通」。默认关，关着完全等于现在的直连。proxy / vpn / 翻墙 / 科学上网 / socks / http proxy。")),
                 SettingsRow(Icons.Outlined.Storage, tr("存储占用"), "storage", tr("按类别看 App 占了多少：数据库、对话附件、图片缓存、AI 工作区、文档知识库、崩溃报告、备份临时文件。缓存类可单独清理，你的资料只统计不删。storage / cache / 清缓存 / 占用 / 空间。")),
                 SettingsRow(Icons.Outlined.Timeline, tr("AI 活动中心"), "activity_center", tr("AI 每次调用工具都记在这里：调了什么、谁在调、参数和返回、耗时、成没成或被拦下。另有 API 调用统计与风控等级。")),
                 SettingsRow(Icons.Outlined.BugReport, tr("运行日志"), "app_log", tr("没崩但不对劲的时候看这里：请求失败、工具异常、备份失败、MCP 重连都会记一条。只存在内存里的最近几百条，不写文件不上传，可一键复制或分享给开发者。log / logcat / 日志 / 调试 / 报错。")),
                 SettingsRow(Icons.Outlined.Warning, tr("崩溃报告"), "crash", tr("查看历史崩溃日志，排查问题用。")),
                 SettingsRow(Icons.Outlined.SystemUpdate, tr("检查更新"), "update", tr("手动去 GitHub Releases 比一下有没有新版本，有就给出更新说明和下载链接。默认关，且只在你点按钮时才联网，绝不后台自动检查。update / 升级 / 新版本 / version。")),
                 SettingsRow(Icons.Outlined.AutoAwesome, tr("新手向导"), "onboarding", tr("重新走一遍开箱引导：连模型、开权限、配唤醒、认识你、选角色，最后一页有当前配置的小结。配置会真写进去，不是只看看。")),
                 SettingsRow(Icons.Outlined.Info, tr("关于软件"), "about", tr("版本、开源许可(Apache-2.0)与开源致谢(OSS notices)。")),
             ),
         )
     }
     // LazyColumn 而非 Column+verticalScroll：折叠大标题那 48px 里，M3 的 LargeTopAppBar 会每帧改
     // 可用高度、GLASS 的让位 Spacer 会每帧收缩——非懒加载整列(25 行 + 5 张玻璃卡)会被每帧重新测量/重排而卡；
     // 懒列表只测量/摆放可见项，折叠时增量处理（同聊天页「让列表天然可跳过、只处理可见项」）。
     // 模糊匹配而非纯子串：打错一个字（「权现管理」）、只记得缩写（「mcp」「log」）、
     // 中英混着记（「proxy」找「网络代理」，靠 explain 里的关键词），都还能搜出来。
     // rankBy 按「标题 / 说明」分别打分取高分，长说明不会把短标题的命中稀释掉；结果按分排序，精确命中天然在前。
     val hits = remember(q, groups) {
         if (q.isBlank()) emptyList()
         else com.arix.tool.FuzzyMatch.rankBy(
             query = q,
             items = groups.flatMap { it.second },
             fields = { listOf(it.label, it.explain) },
         ).map { it.item }
     }
     LazyColumn(
         modifier = Modifier.fillMaxSize(),
         contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
         verticalArrangement = Arrangement.spacedBy(14.dp),
     ) {
         item(key = "gap", contentType = "gap") { Spacer(Modifier.topChromeGapHeight()) }   // 顶部悬浮玻璃让位
         item(key = "search", contentType = "search") {
             com.arix.app.ui.XtomField(value = q, onValueChange = { q = it }, modifier = Modifier.fillMaxWidth(),
                 placeholder = tr("搜索设置…"),
                 leading = { Icon(Icons.Outlined.Search, null, tint = scheme.onSurfaceVariant) },
                 textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp))
         }
         if (q.isBlank()) {
             items(groups, key = { it.first }, contentType = { "group" }) { (title, items) ->
                 SettingsGroup(title, items, onNavigate) { explain = it }
             }
         } else if (hits.isEmpty()) {
             item(key = "empty", contentType = "empty") { Text(tr("没找到相关设置"), color = scheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(16.dp)) }
         } else {
             item(key = "results", contentType = "group") { SettingsGroup(tr("搜索结果"), hits, onNavigate) { explain = it } }
         }
         item(key = "hint", contentType = "hint") { Text(tr("提示：长按任意项可看「人话」说明。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(start = 8.dp)) }
     }
     explain?.let { r ->
         androidx.compose.material3.AlertDialog(
             onDismissRequest = { explain = null },
             title = { Text(r.label, color = scheme.onSurface, fontSize = 15.sp) },
             text = { Text(r.explain, color = scheme.onSurfaceVariant, fontSize = 13.sp) },
             confirmButton = { TextButton(onClick = { explain = null }) { Text(tr("知道了"), color = scheme.primary) } },
             containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
         )
     }
 }

 // ============================================================
 // 顶部玻璃层 —— 渐变模糊 + 悬浮返回胶囊（取代原来的实心 TopAppBar）
 // ============================================================
 /** 页面 → 顶部标题。走 tr() 过 33 语言译表；不要 remember 它，静态 local 换语言时要能重算。 */
 private fun pageTitle(page: String): String = when (page) {
     "config" -> tr("模型配置"); "stt" -> tr("语音识别"); "tts" -> tr("语音朗读"); "voice_clone" -> tr("声音克隆"); "conversations" -> tr("对话管理")
     "memory" -> tr("记忆管理"); "packages" -> tr("本地包"); "wake" -> tr("语音唤醒")
     "permissions" -> tr("权限管理"); "plugins" -> tr("插件制作"); "import" -> tr("导入导出")
     "monitor" -> tr("监控 & 风控"); "activity_center" -> tr("AI 活动中心"); "crash" -> tr("崩溃报告"); "settings" -> tr("应用设置"); "personalization" -> tr("个性化"); "dialog_settings" -> tr("对话设置"); "tool_keys" -> tr("工具密钥"); "search_settings" -> tr("联网搜索"); "settings_hub" -> tr("设置"); "about" -> tr("关于软件"); "favorites" -> tr("收藏"); "files" -> tr("文件"); "file_history" -> tr("文件改动历史"); "chat_appearance" -> tr("聊天外观"); "projects" -> tr("项目"); "usage" -> tr("使用统计"); "user_scripts" -> tr("用户脚本")
     "proxy" -> tr("网络代理"); "storage" -> tr("存储占用"); "app_log" -> tr("运行日志"); "update" -> tr("检查更新")
     else -> "Arix"
 }

 /**
  * 顶部悬浮玻璃：把背后的页面内容模糊掉，并自上而下渐隐到全透明——内容从下面滑过时是「化开」而不是被切断。
  *
  * 手法沿用本项目既有的「背后模糊」(见聊天顶栏/底部输入浮层)：调用方把页面内容录进 [pageLayer]，
  * 这里给该图层挂 BlurEffect 再画一遍。两个坑（本项目踩过，别再踩）：
  *  1. renderEffect 是图层自身的属性 → 页面主体必须直接 drawContent()，不能也经这个图层，否则整屏糊。
  *  2. 本组件必须在录制范围之外（是录制 Box 的兄弟节点）→ 否则图层引用自己 = 递归崩溃。
  *
  * 除胶囊外不加任何 pointerInput：Compose 只对有 pointer input 的节点做命中测试，所以这层不吃触摸。
  */
 @OptIn(ExperimentalMaterial3Api::class)
 @Composable private fun TopGlassChrome(
     title: String,
     onBack: () -> Unit,
     collapse: androidx.compose.runtime.State<Float>,   // 0=展开(大标题) .. 1=收拢(小标题)。传 State：读 .value 延到本组件，父级 MainScreen 不因每帧滚动重组
     bigTitle: Boolean,     // true=子页显示 rikkahub 式折叠大标题；false=聊天页沿用旧小胶囊
     modifier: Modifier = Modifier,
 ) {
     val scheme = MaterialTheme.colorScheme
     // 顶栏底：开玻璃走自建管线采样模糊壁纸（同气泡，一套机制，不再每帧录整页做 RenderEffect）；关玻璃退回渐变纯色底。
     val glassOn = com.arix.app.ui.LocalGlass.current.on
     // 悬浮件贴边会被圆屏表圈/设备圆角切掉，按用户手调的量往里收；玻璃底仍满宽，只收胶囊
     val floatFit = com.arix.app.theme.LocalScreenFit.current.floatInset
     val tint = scheme.background
     // 矮屏（手表 ~320dp 高）上顶栏收紧：留白压掉，触控目标不动。与页面让位同一个来源，别各写各的。
     val compact = com.arix.app.ui.isShortScreen()
     val capsulePadV = if (compact) 5.dp else 12.dp
     // 栏高：大标题时随折叠从展开高收到收拢高（与 topChromeGap 同源，内容让位同步收缩）；小胶囊页固定。
     // ⚠ 本组件体内**不读** collapse.value。一读，折叠时整条顶栏每帧重组：glassSurface 的 modifier 链
     // 和 drawBehind 闭包被整个重建、Crossfade/圆钮/图标全部重跑。栏高只是个高度，放到下面的 layout{}
     // 里延迟读取即可 —— 只触发重新布局，不触发重组。标题那点确实需要重组的部分下沉到 CollapsingPageTitle。
     val expandedH = com.arix.app.ui.expandedChromeHeight()
     val collapsedH = com.arix.app.ui.collapsedChromeHeight()
     val fixedH = com.arix.app.ui.topChromeHeight()
     Box(
         modifier = modifier.fillMaxWidth()
             .then(
                 // 大标题页：栏高在布局阶段按折叠进度插值（延迟读 State，见上方说明）；小胶囊页高度固定。
                 // ⚠ 栏高要把 floatFit 算进去：胶囊/标题按 floatFit 往下挪了，而本 Box 是 Offscreen 合成层
                 // （会按自身尺寸裁剪），高度不跟着长就会把大标题的下半截切掉。topChromeGap 同样 +floatInset，
                 // 两边保持同源，内容让位与栏高一致。
                 if (bigTitle) Modifier.layout { measurable, constraints ->
                     val h = (androidx.compose.ui.unit.lerp(expandedH, collapsedH, collapse.value.coerceIn(0f, 1f)) + floatFit).roundToPx()
                     val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                     layout(p.width, h) { p.place(0, 0) }
                 } else Modifier.heightIn(min = fixedH + floatFit)
             )
             .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
             .then(if (glassOn) Modifier.glassSurface(androidx.compose.ui.graphics.RectangleShape, scheme.surfaceContainerHigh) else Modifier)
             .drawWithContent {
                 // 关玻璃：压一层背景色渐变底（糊不了就靠底色让胶囊读得清）。开玻璃时 glassSurface 已在 drawBehind 画好模糊壁纸底。
                 if (!glassOn) drawRect(brush = Brush.verticalGradient(0f to tint.copy(alpha = 0.72f), 0.55f to tint.copy(alpha = 0.28f), 1f to Color.Transparent))
                 // 整条玻璃底自上而下渐隐到全透明：底边不留硬线，内容从下面「化」进去
                 drawRect(brush = Brush.verticalGradient(0f to Color.Black, 0.45f to Color.Black.copy(alpha = 0.85f), 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                 drawContent()   // 胶囊画在 DstIn 之后 —— 它不该跟着渐隐
             },
     ) {
         if (!bigTitle) {
             // 聊天页：旧的「箭头+小标题」胶囊，保持原样不动。
             Surface(
                 onClick = onBack,
                 shape = RoundedCornerShape(50),
                 color = scheme.surfaceContainerHighest,
                 border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
                 shadowElevation = com.arix.app.ui.flatShadowElevation(4.dp),
                 // 悬浮内缩横纵都收（原来只加了横向，纵向上边照样被圆屏切）。它是 TopStart 定位的
                 // wrap-content 件，多出来的 end/bottom 内距不影响观感，也不影响可点区域。
                 modifier = Modifier.align(Alignment.TopStart).floatFitPadding().padding(start = 10.dp, top = capsulePadV, end = 10.dp),
             ) {
                 Row(
                     modifier = Modifier.heightIn(min = 44.dp).padding(start = 11.dp, end = 14.dp),
                     verticalAlignment = Alignment.CenterVertically,
                 ) {
                     Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回"), tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                     Spacer(Modifier.width(6.dp))
                     Text(title, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                 }
             }
         } else {
             // 子页：rikkahub 式折叠大标题。
             // 返回箭头做成独立圆钮常驻左上；大标题随 collapse 从「箭头下方·大字」插值到「箭头右侧·小字」。
             val arrowBox = 40.dp
             androidx.compose.material3.Surface(
                 onClick = onBack,
                 shape = androidx.compose.foundation.shape.CircleShape,
                 color = scheme.surfaceContainerHighest,
                 border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
                 shadowElevation = com.arix.app.ui.flatShadowElevation(4.dp),
                 // 同上：悬浮内缩横纵都收
                 modifier = Modifier.align(Alignment.TopStart).floatFitPadding().padding(start = 10.dp, top = capsulePadV),
             ) {
                 Box(Modifier.size(arrowBox + 4.dp), contentAlignment = Alignment.Center) {   // ≥44dp 触控
                     Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = tr("返回"), tint = scheme.onSurface, modifier = Modifier.size(20.dp))
                 }
             }
             // 展开态：标题在箭头下方、左对齐、大字；收拢态：移到箭头右侧、与箭头居中对齐、小字。
             // 单独一个 composable：唯一真正需要随折叠重组的就是字号，把订阅圈死在这一小块里，
             // 别让整条顶栏陪着它每帧重跑。
             CollapsingPageTitle(
                 title = title, collapse = collapse, compact = compact,
                 floatFit = floatFit, capsulePadV = capsulePadV, arrowBox = arrowBox,
                 modifier = Modifier.align(Alignment.TopStart),
             )
         }
     }
 }

/**
 * 折叠大标题的标题文本：位置走 [Modifier.offset] 的 lambda 重载（布局阶段读折叠进度 → 只重布局），
 * 字号插值确实要重组，但只波及这一个 Text，不再牵动整条顶栏。
 */
 @Composable private fun CollapsingPageTitle(
     title: String,
     collapse: androidx.compose.runtime.State<Float>,
     compact: Boolean,
     floatFit: androidx.compose.ui.unit.Dp,
     capsulePadV: androidx.compose.ui.unit.Dp,
     arrowBox: androidx.compose.ui.unit.Dp,
     modifier: Modifier = Modifier,
 ) {
     val scheme = MaterialTheme.colorScheme
     val bigSp = if (compact) 22f else 27f
     val smallSp = if (compact) 15f else 17f
     // 位置是 offset 算出来的（不是 padding），只能手拼；横纵都要带上 floatFit——
     // 纵向原来没带，圆屏上标题顶边照样被切。上面那颗返回圆钮走的是 Modifier.floatFitPadding()，两者取值必须一致。
     val expandedX = 16.dp + floatFit
     val collapsedX = 10.dp + floatFit + arrowBox + 4.dp + 12.dp
     val expandedY = capsulePadV + floatFit + arrowBox + 4.dp + 6.dp
     val collapsedY = capsulePadV + floatFit + 10.dp
     val screenW = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
     val density = androidx.compose.ui.platform.LocalDensity.current
     // 字号：整个折叠区间只有 ~10sp 变化，按 0.5sp 量化后再取值，把每帧重组降到十来次，
     // 视觉上看不出台阶（手表屏更看不出），却省掉绝大部分文本重新测量/排版。
     val titleSp by androidx.compose.runtime.remember(bigSp, smallSp) {
         androidx.compose.runtime.derivedStateOf {
             val c = collapse.value.coerceIn(0f, 1f)
             val raw = bigSp + (smallSp - bigSp) * c
             (Math.round(raw * 2f) / 2f).sp
         }
     }
     androidx.compose.animation.Crossfade(targetState = title, animationSpec = tween(200), label = "pageTitle") { t ->
         Text(
             t,
             color = scheme.onSurface,
             fontSize = titleSp,
             fontWeight = FontWeight.Bold,
             maxLines = 1,
             overflow = TextOverflow.Ellipsis,
             modifier = modifier
                 .offset {
                     // lambda 版 offset = 布局阶段读取，位置每帧平滑变化但不引发重组
                     val c = collapse.value.coerceIn(0f, 1f)
                     with(density) {
                         IntOffset(
                             androidx.compose.ui.unit.lerp(expandedX, collapsedX, c).roundToPx(),
                             androidx.compose.ui.unit.lerp(expandedY, collapsedY, c).roundToPx(),
                         )
                     }
                 }
                 .widthIn(max = screenW - collapsedX - 16.dp),
         )
     }
 }

 /**
  * 顶栏图标按钮。
  *
  * 用 M3 的 [androidx.compose.material3.FilledTonalIconButton]，**不再手搓**
  * `Box + CircleShape + border + background + clickable`。手搓那套看着像、实则缺一堆东西：
  *  · 触控目标只有 38dp —— 低于 M3/无障碍要求的 48dp，手表上更难点中
  *  · `clickable` 的涟漪是**方的**（没跟着裁成圆），点下去一圈方角
  *  · 没有状态层（hover/focus/pressed 的叠色）、没有 disabled 态
  *  · 阴影+描边+实底三件套堆在一起，比 M3 的 tonal 面更重、更"脏"
  * 这正是用户说的"标头那几个圆形按键不好看"。M3 组件把这些全给了，且随主题令牌走。
  */
 @Composable private fun CircleTopButton(
     icon: androidx.compose.ui.graphics.vector.ImageVector,
     contentDescription: String,
     tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
     onClick: () -> Unit,
 ) {
     val scheme = MaterialTheme.colorScheme
     val glassOn = com.arix.app.ui.LocalGlass.current.on
     // 底色用 surfaceContainerHighest（全局玻璃开时该令牌已是半透明→圆钮自动玻璃，不用再叠 glassSurface）。
     // 只一圈 border——之前 Surface 自带 border + glassSurface 又加一圈 = 双圈（用户反馈「套了一圈」）。
     Surface(
         onClick = onClick,
         shape = CircleShape,
         color = if (glassOn) scheme.surfaceContainerHighest.copy(alpha = 0.62f) else scheme.surfaceContainerHighest,
         border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
         shadowElevation = if (glassOn) 0.dp else com.arix.app.ui.flatShadowElevation(4.dp),
         modifier = Modifier.padding(horizontal = 2.dp).size(38.dp),
     ) {
         Box(contentAlignment = Alignment.Center) {
             Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(20.dp))
         }
     }
 }

 /**
  * i18n 收集锚点 —— 永远不会被调用，只为让 `tools/i18n_wrap.py` 扫得到这些串。
  *
  * 抽屉入口的短名、面板分组名、对话时间分组名走的是「延迟 tr()」：字面量以中文原串存进
  * [DrawerEntryDef] / 分组列表 / grouped 的 key，渲染时才 `tr(def.label)` / `tr(g)` / `tr(label)`。
  * 收集脚本只认 `tr() 里直接写中文字面量` 这一种写法，扫不到那些裸字面量 —— 「浏览器 / 聊天 / 改动历史 /
  * 新手向导 / 关于」这几项就是这么漏掉的（同文件其余 40 项恰好在别处出现过 tr() 字面量才留在表里，
  * 纯属运气）。这里统一登记一次，重跑脚本不会再把它们从 i18n/i18n_table.json 里删掉。
  * ⚠️ 改上面 drawerEntryDefs 的 label/group，这里必须同步改。
  */
 @Suppress("unused")
 private fun drawerI18nKeys() = listOf(
     // 面板分组
     tr("常用"), tr("个性化"), tr("对话与模型"), tr("语音"), tr("工具与扩展"), tr("系统"),
     // 入口短名
     tr("文件"), tr("记忆"), tr("聊天"), tr("设置"),
     tr("模型配置"), tr("对话设置"), tr("声音克隆"), tr("用户脚本"), tr("联网搜索"),
     tr("对话管理"), tr("收藏"), tr("使用统计"), tr("导入导出"),
     tr("语音唤醒"), tr("项目"), tr("工具密钥"), tr("本地包"), tr("改动历史"), tr("聊天外观"),
     tr("云端市场"), tr("插件制作"), tr("权限管理"), tr("活动中心"), tr("崩溃报告"),
     tr("新手向导"), tr("关于"),
     // 抽屉里对话列表的时间分组
     tr("今天"), tr("昨天"), tr("近7天"), tr("更早"),
 )

 // 抽屉一个可排布入口的定义：id + 图标 + 短名 + 面板分组 + 选中时对应页 + 点击动作（动作是 MainScreen 的局部闭包）
 class DrawerEntryDef(
     val id: String,
     val icon: ImageVector,
     val label: String,
     val group: String,
     val selectedPage: String?,
     val onClick: () -> Unit,
 )

 /** 导航区一行入口：仿 NavigationDrawerItem 外观，但支持长按（进入排布编辑）。 */
 @OptIn(ExperimentalFoundationApi::class)
 @Composable private fun DrawerNavRow(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, onLongClick: () -> Unit) {
     val scheme = MaterialTheme.colorScheme
     val bg = if (selected) scheme.secondaryContainer else Color.Transparent
     val fg = if (selected) scheme.onSecondaryContainer else scheme.onSurface
     val iconTint = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
     Row(
         modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
             .clip(RoundedCornerShape(14.dp)).background(bg)
             .combinedClickable(onClick = onClick, onLongClick = onLongClick)
             .padding(horizontal = 16.dp, vertical = 12.dp),
         verticalAlignment = Alignment.CenterVertically,
     ) {
          Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(22.dp))
          Spacer(Modifier.width(12.dp))
          // 长文本（英文等字母类语言）单行省略：不约束的话长标签会被挤成竖排窄条、占不满整行
          Text(label, color = fg, fontSize = 14.sp, fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
      }
  }

 /**
  * 底部区（正式抽屉里）：按每个入口自选的大小渲染——小=纯图标圆球、中=图标+文字胶囊、大=整行大按钮。
  * 统一浅容器+描边（修复原「聊天」按钮异常颜色）。长按任意一颗进入排布编辑。
  */
 @OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
 @Composable private fun DrawerBottomZone(items: List<DrawerLayoutStore.Item>, entryById: Map<String, DrawerEntryDef>, onLongClick: () -> Unit) {
     androidx.compose.foundation.layout.FlowRow(
         modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
         horizontalArrangement = Arrangement.spacedBy(8.dp),
         verticalArrangement = Arrangement.spacedBy(8.dp),
     ) {
         items.forEach { itm ->
             val frac = (itm.size / 100f).coerceIn(0.12f, 1f)
             if (DrawerLayoutStore.isSpacer(itm.id)) {
                 // 空白间隔：透明占位，撑出宽度把两边推开；长按可进编辑
                 Box(modifier = Modifier.fillMaxWidth(frac).height(44.dp).combinedClickable(onClick = {}, onLongClick = onLongClick))
                 return@forEach
             }
              val def = entryById[itm.id] ?: return@forEach
              if (itm.size <= DrawerLayoutStore.W_BALL_MAX) {
                  DrawerBottomBall(def.icon, tr(def.label), onClick = def.onClick, onLongClick = onLongClick)
              } else {
                  DrawerBottomPill(def.icon, tr(def.label), modifier = Modifier.fillMaxWidth(frac), onClick = def.onClick, onLongClick = onLongClick)
              }
         }
     }
 }

 /** 底部小圆球（size=小）：纯图标。 */
 @OptIn(ExperimentalFoundationApi::class)
 @Composable private fun DrawerBottomBall(icon: ImageVector, label: String, onClick: () -> Unit, onLongClick: () -> Unit) {
     val scheme = MaterialTheme.colorScheme
     Box(
         modifier = Modifier.size(46.dp).clip(CircleShape).background(scheme.surfaceContainerHigh)
             .border(1.dp, scheme.outlineVariant, CircleShape)
             .combinedClickable(onClick = onClick, onLongClick = onLongClick),
         contentAlignment = Alignment.Center,
     ) { Icon(icon, contentDescription = label, tint = scheme.primary, modifier = Modifier.size(20.dp)) }
 }

 /** 底部胶囊（size=中/大）：图标+文字。大号用 fillMaxWidth 占整行。 */
 @OptIn(ExperimentalFoundationApi::class)
 @Composable private fun DrawerBottomPill(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit, onLongClick: () -> Unit) {
     val scheme = MaterialTheme.colorScheme
     Surface(shape = RoundedCornerShape(50), color = scheme.surfaceContainerHigh, border = BorderStroke(1.dp, scheme.outlineVariant), modifier = modifier) {
         Row(
             modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 14.dp, vertical = 11.dp),
             horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
         ) {
             Icon(icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
             Spacer(Modifier.width(8.dp))
             Text(label, color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
         }
     }
 }

 // ============================================================
 // 抽屉排布编辑器：独立整页(DrawerLayoutEditorPage)——左=导航区/底部区排布，右=组件面板。
 //   · 左边长按拖动重排/改区、✕移除；底部入口拖右柄(吸附档位)/点入口(切下一档)调大小、加号选空位。
 //   · 右边点组件即加入选中格位；顶部有「空白间隔」生成器。
 // DrawerEditCtl 同时持有拖动状态 + 变更方法（直接落到 drawerLayout 并保存，正式抽屉实时更新）。
 // ============================================================
 class DrawerEditCtl(
     private val layout: androidx.compose.runtime.snapshots.SnapshotStateList<DrawerLayoutStore.Item>,
     private val apply: (List<DrawerLayoutStore.Item>) -> Unit,
 ) {
     // —— 拖动（抽屉内重排/改区） ——
     var id by mutableStateOf<String?>(null)
     var pointer by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)  // 手指窗口坐标
     var over by mutableStateOf<String?>(null)                                // 悬停在哪个区："nav"/"bottom"
     var editorOrigin by mutableStateOf(androidx.compose.ui.geometry.Offset.Zero)
     val zoneRects = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Rect>()
     val itemCenters = androidx.compose.runtime.mutableStateMapOf<String, androidx.compose.ui.geometry.Offset>()
     fun updateOver() { over = zoneRects.entries.firstOrNull { it.value.contains(pointer) }?.key }
     fun drop() { val d = id; reset(); if (d != null) routeDrop(d) }
     fun reset() { id = null; over = null }
     private fun routeDrop(dropId: String) {
         when (over) {
             DrawerLayoutStore.ZONE_NAV -> {
                 val y = pointer.y
                 val idx = layout.filter { it.zone == DrawerLayoutStore.ZONE_NAV && it.id != dropId }.count { (itemCenters[it.id]?.y ?: Float.MAX_VALUE) < y }
                 moveTo(dropId, DrawerLayoutStore.ZONE_NAV, idx)
             }
             DrawerLayoutStore.ZONE_BOTTOM -> {
                 val p = pointer
                 val idx = layout.filter { it.zone == DrawerLayoutStore.ZONE_BOTTOM && it.id != dropId }.count {
                     val c = itemCenters[it.id] ?: return@count false
                     c.y < p.y - 24f || (kotlin.math.abs(c.y - p.y) <= 24f && c.x < p.x)
                 }
                 moveTo(dropId, DrawerLayoutStore.ZONE_BOTTOM, idx)
             }
             else -> {}
         }
     }

     // —— 加入目标（右侧点组件放到哪） ——
     var selZone by mutableStateOf(DrawerLayoutStore.ZONE_NAV)
     var selPos by mutableStateOf(-1)

     // —— 变更（都重建整表并保存） ——
     private fun rebuild(nav: List<DrawerLayoutStore.Item>, bot: List<DrawerLayoutStore.Item>, hid: List<DrawerLayoutStore.Item>) = apply(nav + bot + hid)
     fun moveTo(id: String, zone: String, index: Int) {
         if (layout.none { it.id == id }) return
         val cur = layout.first { it.id == id }
         val nav = layout.filter { it.zone == DrawerLayoutStore.ZONE_NAV && it.id != id }.toMutableList()
         val bot = layout.filter { it.zone == DrawerLayoutStore.ZONE_BOTTOM && it.id != id }.toMutableList()
         val hid = layout.filter { it.zone == DrawerLayoutStore.ZONE_HIDDEN && it.id != id }.toMutableList()
         val moved = cur.copy(zone = zone)
         val target = when (zone) { DrawerLayoutStore.ZONE_NAV -> nav; DrawerLayoutStore.ZONE_BOTTOM -> bot; else -> hid }
         target.add(index.coerceIn(0, target.size), moved)
         rebuild(nav, bot, hid)
     }
     fun insertNew(item: DrawerLayoutStore.Item, zone: String, index: Int) {
         val nav = layout.filter { it.zone == DrawerLayoutStore.ZONE_NAV }.toMutableList()
         val bot = layout.filter { it.zone == DrawerLayoutStore.ZONE_BOTTOM }.toMutableList()
         val hid = layout.filter { it.zone == DrawerLayoutStore.ZONE_HIDDEN }.toMutableList()
         val target = when (zone) { DrawerLayoutStore.ZONE_NAV -> nav; DrawerLayoutStore.ZONE_BOTTOM -> bot; else -> hid }
         target.add(index.coerceIn(0, target.size), item.copy(zone = zone))
         rebuild(nav, bot, hid)
     }
     fun deleteItem(id: String) = apply(layout.filter { it.id != id })
     fun removeItem(id: String) { if (DrawerLayoutStore.isSpacer(id)) deleteItem(id) else moveTo(id, DrawerLayoutStore.ZONE_HIDDEN, Int.MAX_VALUE) }
     fun setSize(id: String, size: Int) = apply(layout.map { if (it.id == id) it.copy(size = size) else it })
     fun addToSel(id: String) {
         val cnt = layout.count { it.zone == selZone && it.id != id }
         val idx = if (selPos < 0) cnt else selPos.coerceIn(0, cnt)
         moveTo(id, selZone, idx)
         if (selPos >= 0) selPos = idx + 1
     }
     fun addSpacer() {
         val botCnt = layout.count { it.zone == DrawerLayoutStore.ZONE_BOTTOM }
         val idx = if (selZone == DrawerLayoutStore.ZONE_BOTTOM && selPos >= 0) selPos.coerceIn(0, botCnt) else botCnt
         insertNew(DrawerLayoutStore.Item(DrawerLayoutStore.newSpacerId(layout), DrawerLayoutStore.ZONE_BOTTOM, DrawerLayoutStore.W_DEFAULT), DrawerLayoutStore.ZONE_BOTTOM, idx)
         selZone = DrawerLayoutStore.ZONE_BOTTOM; selPos = idx + 1
     }
 }

 /** 一个可长按拖动、可点按的入口盒子。拖动中自身淡出(代理在飘)。 */
 @OptIn(ExperimentalFoundationApi::class)
 @Composable private fun DrawerDragBox(ctl: DrawerEditCtl, id: String, onTap: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
     var win by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
     val dragging = ctl.id == id
     Box(
         modifier = modifier
             .onGloballyPositioned { win = it.positionInWindow(); ctl.itemCenters[id] = it.boundsInWindow().center }
             .graphicsLayer { alpha = if (dragging) 0.32f else 1f }
             .clickable { onTap() }
             .pointerInput(id) {
                 detectDragGesturesAfterLongPress(
                     onDragStart = { local -> ctl.id = id; ctl.pointer = win + local; ctl.updateOver() },
                     onDrag = { change, delta -> change.consume(); ctl.pointer += delta; ctl.updateOver() },
                     onDragEnd = { ctl.drop() },
                     onDragCancel = { ctl.reset() },
                 )
             },
     ) { content() }
 }

 /** 预览项右上/左上的小角标（移除 / 大小循环）。 */
 @Composable private fun DrawerBadge(icon: ImageVector?, text: String?, tint: Color, bg: Color, onClick: () -> Unit) {
     val scheme = MaterialTheme.colorScheme
     Box(
         modifier = Modifier.size(20.dp).clip(CircleShape).background(bg).border(1.dp, scheme.outline, CircleShape).clickable { onClick() },
         contentAlignment = Alignment.Center,
     ) {
         if (icon != null) Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
         else if (text != null) Text(text, color = tint, fontSize = 11.sp, fontWeight = FontWeight.Bold)
     }
 }

 /** 底部区里的「空位」加号：点选后右侧组件放到这一格。选中高亮。 */
 @Composable private fun DrawerBottomInsertChip(selected: Boolean, onClick: () -> Unit) {
     val scheme = MaterialTheme.colorScheme
     Box(
         modifier = Modifier.height(42.dp).width(if (selected) 32.dp else 22.dp)
             .clip(RoundedCornerShape(9.dp))
             .background(if (selected) scheme.primary else scheme.surfaceContainerHighest)
             .border(1.dp, if (selected) scheme.primary else scheme.outlineVariant, RoundedCornerShape(9.dp))
             .clickable { onClick() },
         contentAlignment = Alignment.Center,
     ) { Icon(Icons.Outlined.Add, contentDescription = tr("空位"), tint = if (selected) scheme.onPrimary else scheme.onSurfaceVariant, modifier = Modifier.size(15.dp)) }
 }

 /**
  * 编辑器里底部区的一颗入口：所见即所得渲染真实宽度（占行百分比，太窄自动收成图标球）。
  * 左上移除✕，右侧「拖柄」——横向拖动无级调宽（球拖大即变回胶囊）。空白间隔(spacer)同样在此渲染。
  */
 @OptIn(ExperimentalFoundationApi::class)
 @Composable private fun EditorBottomItem(ctl: DrawerEditCtl, itm: DrawerLayoutStore.Item, def: DrawerEntryDef?, onRemove: () -> Unit, onResize: (Int) -> Unit) {
     val scheme = MaterialTheme.colorScheme
     val spacer = DrawerLayoutStore.isSpacer(itm.id)
     val ball = !spacer && itm.size <= DrawerLayoutStore.W_BALL_MAX
     val frac = (itm.size / 100f).coerceIn(0.12f, 1f)
     val wmod = if (ball) Modifier else Modifier.fillMaxWidth(frac)
     val curSize by androidx.compose.runtime.rememberUpdatedState(itm.size)
     // 点入口本体 = 循环切下一档大小（可靠，不用拖）。拖右柄 = 自由拖但吸附到档位。
     DrawerDragBox(ctl, itm.id, onTap = { onResize(DrawerLayoutStore.nextTier(curSize)) }, modifier = wmod) {
         Box(modifier = Modifier.padding(top = 7.dp, start = 7.dp, end = 7.dp)) {
             when {
                 spacer -> Box(
                     modifier = Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(10.dp))
                         .background(scheme.surfaceContainerHighest)
                         .border(1.dp, scheme.outline, RoundedCornerShape(10.dp)),
                     contentAlignment = Alignment.Center,
                 ) { Text(tr("空白"), color = scheme.onSurfaceVariant, fontSize = 10.sp) }
                 ball -> Box(
                     modifier = Modifier.size(44.dp).clip(CircleShape).background(scheme.surfaceContainerHigh).border(1.dp, scheme.outlineVariant, CircleShape),
                     contentAlignment = Alignment.Center,
                 ) { if (def != null) Icon(def.icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(19.dp)) }
                 else -> Row(
                     modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(scheme.surfaceContainerHigh)
                         .border(1.dp, scheme.outlineVariant, RoundedCornerShape(50)).padding(start = 10.dp, end = 20.dp, top = 8.dp, bottom = 8.dp),
                     verticalAlignment = Alignment.CenterVertically,
                 ) {
                     if (def != null) { Icon(def.icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text(tr(def.label), color = scheme.onSurface, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                 }
             }
             Box(Modifier.align(Alignment.TopStart).offset(x = (-5).dp, y = (-5).dp)) {
                 DrawerBadge(Icons.Outlined.Close, null, scheme.onError, scheme.error, onRemove)
             }
             // 当前档位标签（点入口/拖柄都能改）
             // 右侧拖柄：横向拖=自由拖但吸附到最近档位；柄上直接显示当前档位（球/小/中/大/半/满），
             // 不再单独放档位角标（原来角标在 TopEnd、拖柄在 CenterEnd，小尺寸下两者叠在一起——用户反馈重叠）。
             if (!spacer) {
                 var startSize by remember(itm.id) { mutableStateOf(itm.size) }
                 var accDx by remember(itm.id) { mutableStateOf(0f) }
                 Box(
                     modifier = Modifier.align(Alignment.CenterEnd).offset(x = 2.dp).width(22.dp).height(34.dp)
                         .clip(RoundedCornerShape(8.dp)).background(scheme.primary)
                         .pointerInput(itm.id) {
                             detectDragGestures(
                                 onDragStart = { startSize = curSize; accDx = 0f },
                                 onDrag = { change, d ->
                                     change.consume(); accDx += d.x
                                     val zoneW = ctl.zoneRects["bottom"]?.width ?: 0f
                                     if (zoneW > 0f) {
                                         val ns = DrawerLayoutStore.snapTier((startSize + accDx / zoneW * 100f).roundToInt().coerceIn(DrawerLayoutStore.W_MIN, DrawerLayoutStore.W_MAX))
                                         if (ns != curSize) onResize(ns)
                                     }
                                 },
                             )
                         },
                     contentAlignment = Alignment.Center,
                  ) { Text(tr(DrawerLayoutStore.tierShort(itm.size)), color = scheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
             }
         }
     }
 }

 // 抽屉排布编辑器 · 独立整页：左=导航区/底部区排布，右=组件面板。用 DrawerEditCtl 直接改 drawerLayout 并保存。
 @OptIn(ExperimentalFoundationApi::class, androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
 @Composable private fun DrawerLayoutEditorPage(
     items: List<DrawerLayoutStore.Item>,
     ctl: DrawerEditCtl,
     entryById: Map<String, DrawerEntryDef>,
     paletteGroups: List<String>,
     onReset: () -> Unit,
     onDone: () -> Unit,
 ) {
     val scheme = MaterialTheme.colorScheme
     val nav = items.filter { it.zone == DrawerLayoutStore.ZONE_NAV }
     val bottom = items.filter { it.zone == DrawerLayoutStore.ZONE_BOTTOM }
     val hidden = items.filter { it.zone == DrawerLayoutStore.ZONE_HIDDEN }
     val targetText = if (ctl.selZone == DrawerLayoutStore.ZONE_BOTTOM)
         String.format(tr("底部第%d格"), (if (ctl.selPos < 0) bottom.size else ctl.selPos) + 1)
     else tr("导航区末尾")
     var shown by remember { mutableStateOf(false) }
     LaunchedEffect(Unit) { shown = true }
     val prog by androidx.compose.animation.core.animateFloatAsState(if (shown) 1f else 0f, tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing), label = "editEnter")

     Box(modifier = Modifier.fillMaxSize().background(scheme.background).onGloballyPositioned { ctl.editorOrigin = it.positionInWindow() }) {
         Column(modifier = Modifier.fillMaxSize()) {
             Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                 Text(tr("排布抽屉"), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = scheme.primary, modifier = Modifier.weight(1f))
                 androidx.compose.material3.TextButton(onClick = onReset) { Text(tr("重置"), color = scheme.onSurfaceVariant, fontSize = 13.sp) }
                 androidx.compose.material3.TextButton(onClick = onDone) { Text(tr("完成"), color = scheme.primary, fontSize = 15.sp, fontWeight = FontWeight.Bold) }
             }
             Text(tr("将放入：") + targetText + "  ·  " + tr("点右侧组件加入；长按拖动排序，✕移除。底部入口拖右柄/点入口调档，可加空白间隔。"),
                 fontSize = 11.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
             HorizontalDivider(color = scheme.outlineVariant, modifier = Modifier.padding(top = 4.dp))
             Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                 // 左：导航区 + 底部区
                 Column(
                     modifier = Modifier.weight(0.56f).fillMaxHeight()
                         .graphicsLayer { translationX = (prog - 1f) * 90f; alpha = prog }
                         .verticalScroll(rememberScrollState()).padding(8.dp),
                 ) {
                     Text(tr("导航区"), fontSize = 12.sp, color = scheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 4.dp))
                     val navHi = ctl.over == "nav" && ctl.id != null
                     Column(
                         modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(14.dp))
                             .background(if (navHi) scheme.primary.copy(alpha = 0.10f) else scheme.surfaceContainerLow)
                             .border(1.5.dp, if (navHi) scheme.primary else scheme.outlineVariant, RoundedCornerShape(14.dp))
                             .onGloballyPositioned { ctl.zoneRects["nav"] = it.boundsInWindow() }
                             .padding(6.dp).animateContentSize(),
                         verticalArrangement = Arrangement.spacedBy(6.dp),
                     ) {
                         if (nav.isEmpty()) Text(tr("空——从右侧加入或拖入"), fontSize = 11.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                         nav.forEach { itm ->
                             val spacer = DrawerLayoutStore.isSpacer(itm.id)
                             val def = entryById[itm.id]
                             if (!spacer && def == null) return@forEach
                             key(itm.id) {
                                 DrawerDragBox(ctl, itm.id, onTap = {}) {
                                     Box {
                                         Row(
                                             modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(scheme.surfaceContainerHigh)
                                                 .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 9.dp),
                                             verticalAlignment = Alignment.CenterVertically,
                                         ) {
                                             if (spacer) {
                                                 Text(tr("空白"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                                             } else {
                                                 Icon(def!!.icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(18.dp))
                                                 Spacer(Modifier.width(8.dp))
                                                 Text(tr(def.label), color = scheme.onSurface, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                             }
                                         }
                                         Box(Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp)) {
                                             DrawerBadge(Icons.Outlined.Close, null, scheme.onError, scheme.error) { ctl.removeItem(itm.id) }
                                         }
                                     }
                                 }
                             }
                         }
                         val navSel = ctl.selZone == DrawerLayoutStore.ZONE_NAV
                         Row(
                             modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                 .background(if (navSel) scheme.primary.copy(alpha = 0.16f) else Color.Transparent)
                                 .border(1.dp, if (navSel) scheme.primary else scheme.outlineVariant, RoundedCornerShape(10.dp))
                                 .clickable { ctl.selZone = DrawerLayoutStore.ZONE_NAV; ctl.selPos = -1 }
                                 .padding(vertical = 7.dp),
                             horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                         ) {
                             Icon(Icons.Outlined.Add, contentDescription = null, tint = if (navSel) scheme.primary else scheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                             Spacer(Modifier.width(4.dp))
                             Text(tr("加到这里"), color = if (navSel) scheme.primary else scheme.onSurfaceVariant, fontSize = 11.sp)
                         }
                     }
                     Spacer(Modifier.height(10.dp))
                     Text(tr("底部区（拖右侧柄/点入口调档 · 点加号选空位）"), fontSize = 12.sp, color = scheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
                     val botHi = ctl.over == "bottom" && ctl.id != null
                     androidx.compose.foundation.layout.FlowRow(
                         modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
                             .background(if (botHi) scheme.primary.copy(alpha = 0.10f) else scheme.surfaceContainerLow)
                             .border(1.5.dp, if (botHi) scheme.primary else scheme.outlineVariant, RoundedCornerShape(14.dp))
                             .onGloballyPositioned { ctl.zoneRects["bottom"] = it.boundsInWindow() }
                             .padding(8.dp).animateContentSize(),
                         horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
                     ) {
                         DrawerBottomInsertChip(selected = ctl.selZone == DrawerLayoutStore.ZONE_BOTTOM && ctl.selPos == 0) { ctl.selZone = DrawerLayoutStore.ZONE_BOTTOM; ctl.selPos = 0 }
                         bottom.forEachIndexed { i, itm ->
                             val def = entryById[itm.id]
                             key(itm.id) {
                                 EditorBottomItem(ctl = ctl, itm = itm, def = def, onRemove = { ctl.removeItem(itm.id) }, onResize = { ns -> ctl.setSize(itm.id, ns) })
                             }
                             DrawerBottomInsertChip(selected = ctl.selZone == DrawerLayoutStore.ZONE_BOTTOM && ctl.selPos == i + 1) { ctl.selZone = DrawerLayoutStore.ZONE_BOTTOM; ctl.selPos = i + 1 }
                         }
                     }
                 }
                 // 右：组件面板
                 Column(
                     modifier = Modifier.weight(0.44f).fillMaxHeight()
                         .graphicsLayer { translationX = (1f - prog) * 160f; alpha = prog }
                         .clip(RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp))
                         .background(scheme.surfaceContainerLowest)
                         .verticalScroll(rememberScrollState()).padding(8.dp),
                     verticalArrangement = Arrangement.spacedBy(4.dp),
                 ) {
                     Text(tr("组件"), fontSize = 12.sp, color = scheme.onSurfaceVariant, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 2.dp))
                     Row(
                         modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(scheme.secondaryContainer)
                             .clickable { ctl.addSpacer() }.padding(horizontal = 8.dp, vertical = 8.dp),
                         verticalAlignment = Alignment.CenterVertically,
                     ) {
                         Icon(Icons.Outlined.Add, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(16.dp))
                         Spacer(Modifier.width(6.dp))
                         Text(tr("空白间隔"), color = scheme.onSecondaryContainer, fontSize = 11.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                     }
                     paletteGroups.forEach { g ->
                         val inGroup = hidden.filter { entryById[it.id]?.group == g }
                         if (inGroup.isNotEmpty()) {
                             Text(tr(g), fontSize = 10.sp, color = scheme.primary, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                             inGroup.forEach { itm ->
                                 val def = entryById[itm.id] ?: return@forEach
                                 key(itm.id) {
                                     Row(
                                         modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(scheme.surfaceContainerHigh)
                                             .border(1.dp, scheme.outlineVariant, RoundedCornerShape(10.dp))
                                             .clickable { ctl.addToSel(itm.id) }.padding(horizontal = 8.dp, vertical = 8.dp),
                                         verticalAlignment = Alignment.CenterVertically,
                                     ) {
                                         Icon(def.icon, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(16.dp))
                                         Spacer(Modifier.width(6.dp))
                                         Text(tr(def.label), color = scheme.onSurface, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                                         Icon(Icons.Outlined.Add, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(15.dp))
                                     }
                                 }
                             }
                         }
                     }
                     if (hidden.isEmpty()) Text(tr("全部已加入"), fontSize = 11.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(8.dp))
                     Spacer(Modifier.height(20.dp))
                 }
             }
         }
         // 跟手代理：拖动重排时飘在手指处的芯片
         val dragId = ctl.id
         if (dragId != null) {
             val ddef = entryById[dragId]; val spc = DrawerLayoutStore.isSpacer(dragId)
             Box(
                 modifier = Modifier
                     .offset { androidx.compose.ui.unit.IntOffset((ctl.pointer.x - ctl.editorOrigin.x - 50f).toInt(), (ctl.pointer.y - ctl.editorOrigin.y - 22f).toInt()) }
                     .zIndex(10f).shadow(10.dp, RoundedCornerShape(12.dp))
                     .clip(RoundedCornerShape(12.dp)).background(scheme.secondaryContainer).padding(horizontal = 12.dp, vertical = 10.dp),
                 contentAlignment = Alignment.Center,
             ) {
                 Row(verticalAlignment = Alignment.CenterVertically) {
                     if (ddef != null) { Icon(ddef.icon, contentDescription = null, tint = scheme.onSecondaryContainer, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                     Text(if (spc) tr("空白") else (ddef?.let { tr(it.label) } ?: ""), color = scheme.onSecondaryContainer, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                 }
             }
         }
     }
 }
