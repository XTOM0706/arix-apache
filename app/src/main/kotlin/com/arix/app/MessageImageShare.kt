package com.arix.app

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.arix.app.theme.LocalXtomAccents
import com.arix.app.theme.XtomTheme
import com.arix.app.ui.XtomBubbleSurface
import com.arix.cloudapi.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import kotlin.coroutines.resume
import kotlin.math.roundToInt

// ============================================================
// 分享消息为图片 —— 把一条或多条消息渲染成图片，走系统分享。
//
// 做法（相比旧版手绘 Canvas 的关键改变）：
//   旧版用 android.graphics.Canvas + StaticLayout 手绘，于是
//     · Markdown 只能“粗结构排版”，行内 **粗体**/`码`/链接 只能被正则抹掉；
//     · 拿不到 Compose 主题，只能写死一套深色品牌配色（全 App 唯一的硬编码配色违例）。
//   新版把真正的 Compose UI 离屏渲染成位图：复用 MarkdownText / XtomBubbleSurface，
//   于是 Markdown 是“渲染”而不是“近似”，配色自动跟随 XtomTheme（浅色/深色/动态色全对）。
//
// 离屏渲染怎么做（以及为什么不用 GraphicsLayer）：
//   GraphicsLayer.toImageBitmap() 只能拿到“屏幕上已布局的那一份”，且走硬件位图 → 受 GPU
//   纹理上限死卡，超长图直接废；本项目还踩过 renderEffect 属于图层对象、图层自绘会递归崩溃的坑。
//   所以这里改用 ComposeView：挂到 decorView 下一个 0×0 的宿主里（不占位、不闪、不惊动屏幕布局），
//   手动 measure(EXACTLY 宽, UNSPECIFIED 高) → layout → 画进软件 Bitmap 的 Canvas。
//   软件画布不经过 GPU，所以高度不受纹理上限约束，只受内存约束。
//
// 超长图策略（见 pageCap / planPages）：
//   单张最高 SAFE_MAX_SIDE(8192px)，并按当前可用堆再压一档（手表小堆）。超了就**分页**，
//   优先在消息边界处切，多张一起分享，并明确 toast 告知“已分成 N 张图片”——绝不静默截断。
//
// 已知取舍：
//   · Markdown 里 WebView 承载的块（svg/iframe/mermaid/音视频）在软件画布上多半画不出来；
//   · 远程图片(Coil)若未加载完，图里就是占位——已留 ready + 若干帧 + 短暂 settle 尽量等。
// ============================================================
object MessageImageShare {

    /**
     * 导出图片的版式选项。
     *
     * 为什么单独一个对象而不是并进 [ChatAppearancePrefs]：那份是**聊天页**的观感，会被 LazyColumn 里的
     * 气泡读到（热路径）；这份只在按下「生成图片」那一瞬间读一次。混在一起会让人误以为改这里会影响聊天页。
     *
     * 默认：表头和水印都**开**。这一项是用户明确要求的版式改动（"参照一下其他竞品"），
     * 不是悄悄改行为——竞品的导出图基本都有表头+水印，缺了反而像半成品。要素净的可以关掉。
     */
    object Options {
        private const val PREF = "xtom_share_image"
        @Immutable
        data class Snapshot(
            /** 顶部那条：AI 头像 + 名字 + 模型 + 日期。 */
            val header: Boolean,
            /** 底部那条：Arix 标记 + 条数 + 日期。 */
            val watermark: Boolean,
            /** 把思考过程一起画进图里。 */
            val reasoning: Boolean,
            /** 把工具调用与结果一起画进图里。 */
            val toolCalls: Boolean,
        )

        val DEFAULT = Snapshot(header = true, watermark = true, reasoning = true, toolCalls = true)

        @Volatile private var cached: Snapshot? = null

        /** 渲染期间给 Composable 读的那一份。渲染是同步的一趟，用不着 CompositionLocal。 */
        @Volatile var current: Snapshot = DEFAULT
            private set

        fun snapshot(c: Context): Snapshot = cached ?: load(c).also { cached = it }

        private fun load(c: Context): Snapshot {
            val sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            return Snapshot(
                header = sp.getBoolean("header", DEFAULT.header),
                watermark = sp.getBoolean("watermark", DEFAULT.watermark),
                reasoning = sp.getBoolean("reasoning", DEFAULT.reasoning),
                toolCalls = sp.getBoolean("tool_calls", DEFAULT.toolCalls),
            )
        }

        fun save(c: Context, s: Snapshot) {
            c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putBoolean("header", s.header)
                .putBoolean("watermark", s.watermark)
                .putBoolean("reasoning", s.reasoning)
                .putBoolean("tool_calls", s.toolCalls)
                .apply()
            cached = s
        }

        internal fun bindForRender(c: Context) { current = snapshot(c) }
    }

    // ---- 导出尺寸 ----
    // 以“屏幕宽”为基准超采样：导出宽 = 屏宽 × ss，同时把 density 也 × ss →
    // 版面 dp 宽与用户在聊天页看到的完全一致，只是像素更多（更清晰）。
    private const val EXPORT_TARGET_PX = 1000
    private const val SS_MIN = 1f      // 下限 1.0：原生 density 已经清晰，没必要为大屏再放大（只会白白拉高、多分页）
    private const val SS_MAX = 3f

    // ---- 高度上限 ----
    // 8192：绝大多数设备的 GL_MAX_TEXTURE_SIZE 下限（接收方 App 通常要把图当纹理传）。
    // 没有实测 glGetIntegerv(GL_MAX_TEXTURE_SIZE)：那要自建 EGL 上下文，而在默认 display 上
    // eglInitialize/eglTerminate 有干扰 App 自身渲染器的风险（手表 GPU 弱，不值当）；
    // 何况我们画的是软件位图，本机 GPU 上限并不约束生成，只是接收方的经验值 → 取保守常数即可。
    private const val SAFE_MAX_SIDE = 8192
    private const val MIN_PAGE_H = 1200          // 分页下限：别切出一堆碎条
    private const val TEXT_CAP = 20000           // 单段文本上限：无界喂给排版会 OOM(Error，catch 不住)

    // ---- 入口 ----

    /** 分享一条或多条消息为图片。多条按会话顺序渲染进同一张（过长则自动分页）。 */
    suspend fun share(context: Context, bubbles: List<ChatBubble>, identity: ChatIdentity) {
        val activity = context.findActivity()
        if (activity == null) { toast(context, tr("生成图片失败：找不到界面宿主")); return }
        // 版式选项要在**渲染开始前**读一次：ShareCard/ShareBubble 是离屏 Composable，
        // 拿不到 Context（那个宿主是我们自己挂的，没有正常的 CompositionLocal 环境）。
        Options.bindForRender(context)
        val opts = Options.current
        val list = bubbles.filter { it.text.isNotBlank() || !it.reasoning.isNullOrBlank() || !it.toolCalls.isNullOrEmpty() }
            // 关掉「含工具调用」时，纯工具元数据的条目（没有正文、只有工具调用/结果）整条不画——
            // 留着会在图里剩一个空气泡。有正文的那些只是不画工具部分，条目本身保留。
            .filter { opts.toolCalls || it.role != "tool" }
            .filter { opts.toolCalls || it.text.isNotBlank() || !it.reasoning.isNullOrBlank() }
        if (list.isEmpty()) { toast(context, tr("没有可分享的消息")); return }

        try {
            val r = renderPages(activity, list, identity)
            if (r.files.isEmpty()) { toast(context, tr("生成图片失败")); return }
            // 降级必须说出来：分页 / 截断都给明确提示，不闷声出一张残图
            if (r.files.size > 1) toast(context, tr("内容较长，已分成") + " ${r.files.size} " + tr("张图片"))
            if (r.truncated) toast(context, tr("部分消息过长，图中已标注截断"))
            sendShare(context, r.files)
        } catch (t: Throwable) {
            // OOM 是 Error 不是 Exception，必须 catch Throwable；但 CancellationException 要原样重抛
            if (t is CancellationException) throw t
            toast(context, tr("生成图片失败：") + (t.message ?: t::class.java.simpleName))
        }
    }

    private class Rendered(val files: List<File>, val truncated: Boolean)

    // ============================================================
    // 离屏渲染
    // ============================================================
    private suspend fun renderPages(activity: Activity, list: List<ChatBubble>, identity: ChatIdentity): Rendered {
        val dm = activity.resources.displayMetrics
        val ss = (EXPORT_TARGET_PX / dm.widthPixels.toFloat()).coerceIn(SS_MIN, SS_MAX)
        val widthPx = (dm.widthPixels * ss).roundToInt().coerceAtLeast(320)
        val truncated = list.any { it.text.length > TEXT_CAP || (it.reasoning?.length ?: 0) > TEXT_CAP }

        // 各条消息在卡片内的 y 起点（px）：用于分页时优先在消息边界切。
        // 普通 Map 即可——onGloballyPositioned 里写它不触发重组（写 State 会引起重组风暴）。
        val tops = java.util.concurrent.ConcurrentHashMap<Int, Int>()
        val ready = CompletableDeferred<Unit>()
        var host: FrameLayout? = null
        var view: ComposeView? = null
        val files = ArrayList<File>()

        try {
            withContext(Dispatchers.Main) {
                val root = activity.window.decorView as ViewGroup
                val v = ComposeView(activity)
                v.setContent {
                    XtomTheme {
                        LaunchedEffect(Unit) { ready.complete(Unit) }
                        val d = LocalDensity.current
                        // 超采样：density × ss（dp 版面不变、像素变多）；fontScale 保留用户设置
                        CompositionLocalProvider(LocalDensity provides Density(d.density * ss, d.fontScale)) {
                            ShareCard(list, identity) { i, y -> tops[i] = y }
                        }
                    }
                }
                // 0×0 宿主：系统布局把它当零尺寸（不占位/不闪/不惊动屏幕），
                // 我们自己 measure/layout ComposeView，不依赖系统给的约束。
                val h = FrameLayout(activity)
                h.addView(v, FrameLayout.LayoutParams(widthPx, ViewGroup.LayoutParams.WRAP_CONTENT))
                root.addView(h, FrameLayout.LayoutParams(0, 0))
                host = h; view = v
            }

            // 等首次组合 + 几帧 + 短暂 settle（给 Coil 头像/图片一点加载时间）
            withContext(Dispatchers.Main) {
                withTimeoutOrNull(3000) { ready.await() }
                awaitFrames(2)
                delay(150)
                awaitFrames(1)
            }

            val v = view ?: return Rendered(emptyList(), truncated)
            val totalH = withContext(Dispatchers.Main) { layoutNow(v, widthPx) }
            if (totalH <= 0) throw IllegalStateException("measured height = 0")

            // 小图用 ARGB_8888（画质好）；大图用 RGB_565（卡片背景不透明、无需 alpha，内存减半）
            val argb = widthPx.toLong() * totalH <= 4_000_000L
            val cap = pageCap(widthPx, if (argb) 4 else 2)
            val bounds = list.indices.mapNotNull { tops[it] }.sorted()
            val plan = planPages(totalH, bounds, cap)

            val dir = File(activity.cacheDir, "shared_images").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            val stamp = System.currentTimeMillis()
            plan.forEachIndexed { i, (top, h) ->
                // 每页都重新 measure/layout：期间若系统跑过一次布局（0×0 宿主会把子 View 压成 0 高），
                // 这里会自愈；measure→layout→draw 同一个主线程块内完成，中间不挂起，不会被冲掉。
                val bmp = withContext(Dispatchers.Main) {
                    layoutNow(v, widthPx)
                    drawPage(v, widthPx, top, h, argb)
                }
                val f = File(dir, if (plan.size == 1) "xtom_share_$stamp.png" else "xtom_share_${stamp}_${i + 1}.png")
                // PNG 编码是重活 → 挪出主线程（measure/layout/draw 属于 View 体系，只能在主线程）
                withContext(Dispatchers.Default) {
                    try { FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } finally { bmp.recycle() }
                }
                files.add(f)
            }
        } finally {
            // 无论成功/失败/取消都摘掉离屏宿主（否则 ComposeView 一直挂在 decorView 上）
            withContext(NonCancellable + Dispatchers.Main) { host?.let { (it.parent as? ViewGroup)?.removeView(it) } }
        }
        return Rendered(files, truncated)
    }

    /** 手动测量+布局：宽度定死，高度不设限（UNSPECIFIED）→ 拿到内容全高。返回总高(px)。 */
    private fun layoutNow(v: View, widthPx: Int): Int {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        v.layout(0, 0, v.measuredWidth, v.measuredHeight)
        return v.measuredHeight
    }

    /** 画第 [top,top+h) 段：画布上移 top，位图边界天然充当裁剪 → 每页只落该段内容。 */
    private fun drawPage(v: View, w: Int, top: Int, h: Int, argb: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, if (argb) Bitmap.Config.ARGB_8888 else Bitmap.Config.RGB_565)
        val c = Canvas(bmp)
        c.translate(0f, -top.toFloat())
        v.draw(c)
        return bmp
    }

    /** 单页最大高度：保守纹理上限 与 当前可用堆的 1/4 取小（手表堆小，宁可多分几页也别 OOM）。 */
    private fun pageCap(widthPx: Int, bytesPerPx: Int): Int {
        val rt = Runtime.getRuntime()
        val avail = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
        val budget = (avail / 4).coerceAtLeast(6L * 1024 * 1024)
        val byMem = (budget / (widthPx.toLong() * bytesPerPx)).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return minOf(SAFE_MAX_SIDE, byMem).coerceAtLeast(MIN_PAGE_H)
    }

    /** 分页计划：尽量切在消息边界上；单条消息本身就超过一页时才硬切像素（返回 (top,height) 列表）。 */
    private fun planPages(totalH: Int, msgTops: List<Int>, cap: Int): List<Pair<Int, Int>> {
        if (totalH <= cap) return listOf(0 to totalH)
        val out = ArrayList<Pair<Int, Int>>()
        var y = 0
        while (y < totalH) {
            if (totalH - y <= cap) { out.add(y to (totalH - y)); break }
            val limit = y + cap
            val brk = msgTops.lastOrNull { it in (y + MIN_PAGE_H)..limit } ?: limit
            out.add(y to (brk - y))
            y = brk
        }
        return out
    }

    private suspend fun awaitFrames(n: Int) {
        repeat(n) {
            suspendCancellableCoroutine<Unit> { cont ->
                Choreographer.getInstance().postFrameCallback { if (cont.isActive) cont.resume(Unit) }
            }
        }
    }

    // ============================================================
    // 分享卡片（真 Compose UI；配色/形状/字体全走主题令牌，无硬编码色）
    // ============================================================
    @Composable
    private fun ShareCard(bubbles: List<ChatBubble>, identity: ChatIdentity, onMsgTop: (Int, Int) -> Unit) {
        val scheme = MaterialTheme.colorScheme
        val opts = Options.current
        Column(
            Modifier.fillMaxWidth().background(scheme.background).padding(horizontal = 10.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // ── 表头 ──
            // ⚠ 结构性限制：整张卡是**一次渲染成一根长图、再按像素切页**的（见 planPages），
            // 所以表头只会出现在第 1 页、页脚只在最后一页，而**逐页页码根本做不到**——那要每页
            // 单独重渲一次（N 倍的 measure/layout/draw，手表上不值当）。分了几页由外层 toast 告知。
            if (opts.header) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    Avatar(uri = identity.aiAvatar, fallback = identity.aiName, size = 34.dp)
                    Spacer(Modifier.width(7.dp))
                    Column(Modifier.weight(1f)) {
                        Text(identity.aiName, color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        // 副行：产出这段对话的模型 + 导出日期。模型取**最后一条 assistant 的**——
                        // 一段对话中途可以换模型，标最后那个才对得上图里最下面那条回答。
                        val model = bubbles.lastOrNull { it.role == "assistant" && !it.model.isNullOrBlank() }?.model
                        val sub = listOfNotNull(model, today()).joinToString(" · ")
                        if (sub.isNotBlank()) Text(sub, color = scheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(Modifier.height(4.dp))
            }

            bubbles.forEachIndexed { i, b ->
                Box(Modifier.fillMaxWidth().onGloballyPositioned { onMsgTop(i, it.positionInRoot().y.roundToInt()) }) {
                    ShareBubble(b, identity)
                }
            }

            // ── 页脚 ──
            if (opts.watermark) {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider(color = scheme.outlineVariant)
                Spacer(Modifier.height(5.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.AutoAwesome, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(11.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Arix", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                    Spacer(Modifier.weight(1f))
                    // 条数 + 日期：这两个是"这张图是什么"的最小说明。表头关掉时日期只剩这一处，所以放在这里而不是表头。
                    Text(
                        String.format(tr("%d 条消息"), bubbles.size) + " · " + today(),
                        color = scheme.onSurfaceVariant, fontSize = 9.sp,
                    )
                }
            }
        }
    }

    private fun today(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date())

    // 静态版气泡：布局对齐 ChatBubbleItem，但去掉一切交互（长按菜单/分支箭头/选中态），
    // 且思考块与工具卡一律**展开**——截图是静态的，折叠起来等于丢内容。
    @Composable
    private fun ShareBubble(b: ChatBubble, identity: ChatIdentity) {
        val scheme = MaterialTheme.colorScheme
        val isUser = b.role == "user"

        if (b.role == "tool") { StaticToolResult(b.text); return }

        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
            horizontalAlignment = if (isUser) Alignment.End else Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 2.dp, vertical = 1.dp)) {
                if (isUser) {
                    Text(identity.userName, color = scheme.onSurfaceVariant, fontSize = 11.sp)
                    Spacer(Modifier.width(5.dp))
                    Avatar(uri = identity.userAvatar, fallback = identity.userName, size = 30.dp)
                } else {
                    Avatar(uri = identity.aiAvatar, fallback = identity.aiName, size = 30.dp)
                    Spacer(Modifier.width(5.dp))
                    Text(identity.aiName, color = scheme.onSurfaceVariant, fontSize = 11.sp)
                }
            }
            val opts = Options.current
            if (opts.toolCalls) b.toolCalls?.takeIf { it.isNotEmpty() }?.let { StaticToolCalls(it) }
            if (opts.reasoning && !b.reasoning.isNullOrBlank()) StaticReasoning(b.reasoning)
            val isToolMeta = b.toolCalls != null && b.text.isBlank()
            if (!isToolMeta) XtomBubbleSurface(isUser = isUser) {
                val raw = b.text.takeUnless { it == "null" } ?: " "
                // 与聊天页一致：角色卡「显示替换规则」只影响显示，图里也照做，所见即所得
                val body = remember(raw, isUser) { capText(CardRoleplayStore.applyDisplay(raw, isUser)) }
                val color = if (isUser) scheme.onPrimary else scheme.onSurface
                if (isUser) Text(body, color = color, fontSize = 13.sp)
                else MarkdownText(body, color = color, fontSize = 13.sp)   // ← Markdown 真渲染
            }
        }
    }

    @Composable
    private fun StaticReasoning(text: String) {
        val scheme = MaterialTheme.colorScheme
        Column(Modifier.widthIn(max = 320.dp).padding(bottom = 2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp, horizontal = 2.dp)) {
                Icon(Icons.Outlined.Psychology, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(4.dp))
                Text(tr("已思考"), color = scheme.onSurfaceVariant, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            Text(capText(text), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(start = 6.dp, top = 2.dp))
        }
    }

    @Composable
    private fun StaticToolCalls(calls: List<ChatMessage.ToolCallMsg>) {
        val scheme = MaterialTheme.colorScheme
        Column(Modifier.widthIn(max = 320.dp).padding(bottom = 2.dp)) {
            calls.forEachIndexed { i, c ->
                if (i > 0) Spacer(Modifier.height(2.dp))
                val pretty = remember(c.arguments) {
                    try { org.json.JSONObject(c.arguments).toString(2) } catch (_: Exception) { c.arguments }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp, horizontal = 2.dp)) {
                    Icon(Icons.Outlined.Build, contentDescription = null, tint = scheme.primary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(tr("调用") + " ${c.name}", color = scheme.primary, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Text(capText(pretty.ifBlank { "{}" }), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp, start = 18.dp))
            }
        }
    }

    @Composable
    private fun StaticToolResult(result: String) {
        val scheme = MaterialTheme.colorScheme
        val accents = LocalXtomAccents.current
        val isError = result.startsWith("工具执行异常") || result.startsWith("工具未找到") ||
            result.contains("isError") || result.startsWith("错误") || result.startsWith("[Operit沙盒]")
        Column(Modifier.fillMaxWidth().padding(start = 46.dp, top = 1.dp, bottom = 1.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.SubdirectoryArrowRight, contentDescription = null, tint = scheme.onSurfaceVariant, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
                Text(
                    if (isError) tr("执行失败") else tr("执行成功"),
                    color = if (isError) scheme.error else accents.success,
                    fontSize = 11.sp, fontWeight = FontWeight.Medium,
                )
            }
            Text(capText(result.ifBlank { tr("(无输出)") }), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(start = 15.dp, top = 2.dp))
        }
    }

    // 截断超长文本：无界喂给排版会为几万行分配数组 → OOM(Error，catch 不住)。
    // 截断在图里留可见标记 + 外层 toast 提示，不做静默丢弃。
    private fun capText(s: String): String =
        if (s.length <= TEXT_CAP) s else s.take(TEXT_CAP) + "\n\n…" + tr("（内容过长，已截断）")

    // ============================================================
    // 分享 / 杂项
    // ============================================================
    private fun sendShare(context: Context, files: List<File>) {
        val uris = files.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply { putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        }
        intent.type = "image/png"
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        // ClipData：多图分享时接收方靠它拿到每个 uri 的读权限（只靠 EXTRA_STREAM 会有 App 读不到）
        intent.clipData = ClipData.newUri(context.contentResolver, "Arix", uris[0]).also { cd ->
            uris.drop(1).forEach { cd.addItem(ClipData.Item(it)) }
        }
        context.startActivity(Intent.createChooser(intent, tr("分享为图片")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun toast(context: Context, msg: String) = withContext(Dispatchers.Main) {
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun Context.findActivity(): Activity? {
        var c: Context? = this
        while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
        return null
    }
}
