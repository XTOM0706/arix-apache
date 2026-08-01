package com.arix.app.theme

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

// ============================================================
// XtomColorSchemes —— 全 App 配色的唯一定义处（DESIGN.md §1）
// 三种来源：壁纸动态色（Monet, API31+）/ 自定义主色种子 / 内置品牌回退。
// 现有散落各页的写死颜色（Catppuccin）在此“收编”为回退配色，只存一处。
// 页面一律走 MaterialTheme.colorScheme 令牌，不再自己写死。
// ============================================================

/** 内置品牌回退（暗）—— 深底 + Arix 蓝强调色（深色背景用 300 #82abd0，用户 2026-07-16 定）。 */
fun fallbackDark(): ColorScheme = darkColorScheme(
    primary = Color(0xFF82ABD0),                    // Arix 蓝 300
    onPrimary = Color(0xFF0A2036),
    primaryContainer = Color(0xFF2C4A66),
    onPrimaryContainer = Color(0xFFD4E4F5),
    secondary = Color(0xFF89DCEB),
    onSecondary = Color(0xFF1E1E2E),
    secondaryContainer = Color(0xFF313244),
    onSecondaryContainer = Color(0xFFCDD6F4),
    tertiary = Color(0xFFCBA6F7),
    onTertiary = Color(0xFF1E1E2E),
    tertiaryContainer = Color(0xFF45475A),
    onTertiaryContainer = Color(0xFFCDD6F4),
    error = Color(0xFFF38BA8),
    onError = Color(0xFF1E1E2E),
    errorContainer = Color(0xFF45475A),
    onErrorContainer = Color(0xFFF38BA8),
    background = Color(0xFF11111B),
    onBackground = Color(0xFFCDD6F4),
    surface = Color(0xFF1E1E2E),
    onSurface = Color(0xFFCDD6F4),
    surfaceVariant = Color(0xFF313244),
    onSurfaceVariant = Color(0xFFBAC2DE),
    surfaceTint = Color(0xFF82ABD0),
    outline = Color(0xFF45475A),
    outlineVariant = Color(0xFF313244),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFCDD6F4),
    inverseOnSurface = Color(0xFF1E1E2E),
    inversePrimary = Color(0xFF5A8BC5),
    surfaceDim = Color(0xFF11111B),
    surfaceBright = Color(0xFF45475A),
    surfaceContainerLowest = Color(0xFF11111B),
    surfaceContainerLow = Color(0xFF181825),
    surfaceContainer = Color(0xFF1E1E2E),
    surfaceContainerHigh = Color(0xFF313244),
    surfaceContainerHighest = Color(0xFF45475A),
)

/**
 * 内置品牌回退（亮）—— Arix 蓝作强调色（用户 2026-07-16 定：浅色背景用 500 #5a8bc5）。
 * 背景/表面仍走干净的浅灰白梯度，但主色不再是黑——图标/主按钮/选中态都用 Arix 蓝，界面有了品牌色而非一片灰。
 */
fun fallbackLight(): ColorScheme = lightColorScheme(
    // 浅色模式点缀用**淡**蓝 300（用户 2026-07-16：浅色模式用浅的颜色做点缀）。
    // 填充按钮底是淡蓝时白字看不清 → onPrimary 配深藏蓝，保证对比。
    primary = Color(0xFF82ABD0),                    // Arix 蓝 300（淡）
    onPrimary = Color(0xFF0A2036),
    primaryContainer = Color(0xFFDCE8F6),           // 更淡：选中态底、次要按钮
    onPrimaryContainer = Color(0xFF12335A),
    secondary = Color(0xFF50607A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCE3EE),
    onSecondaryContainer = Color(0xFF1A2536),
    tertiary = Color(0xFF82ABD0),                   // 淡蓝 300，与 primary 一致（浅色点缀不用深蓝）
    onTertiary = Color(0xFF0A2036),
    tertiaryContainer = Color(0xFFDCE8F6),
    onTertiaryContainer = Color(0xFF12335A),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    // 层次方向：画布(background/surface)偏深、卡片容器偏亮(近白)——卡片比画布**亮**才会浮起来。
    // 原来反了(白画布+灰卡片=卡片陷进去发闷)，这是浅色配色发灰的真根源（用户 2026-07-16 指正）。
    // 画布用**中性灰**、不带蓝：品牌蓝只做点缀，不进主色调（用户 2026-07-16 明确）。
    // ⚠ 容器色阶必须**单调且五档互不相同**，画布还要明显低于最暗的那一档。
    // 2026-07-29 修：原来是 Lowest=FFFFFF / Low=FAFAFB / Container=FFFFFF / High=FAFAFB / Highest=F3F3F5
    // ——五档只有三个值，Lowest 与 Container 撞、Low 与 High 撞；而画布 EAEBED 与 Highest F3F3F5 只差 9 级。
    // 后果正是用户报的「按钮和部分元素变成同一种颜色，失去分辨」：
    // `surfaceContainerHighest` 是本项目事实上的**默认卡片色（全项目 111 处）**，它一旦贴近画布，卡片就没边了。
    // 现在：画布压到 E2E3E7，五档 FFFFFF→FCFCFD→F9F9FB→F5F5F8→F1F1F4 单调递减且步距均匀，
    // 画布与 Highest 差 15 级（与深色那半的层次感对齐）。方向仍是 M3 标准（Lowest 最亮=浮得最高）。
    background = Color(0xFFE2E3E7),
    onBackground = Color(0xFF1B1C1E),
    surface = Color(0xFFE2E3E7),
    onSurface = Color(0xFF1B1C1E),
    surfaceVariant = Color(0xFFDADBDF),
    onSurfaceVariant = Color(0xFF45474A),
    surfaceTint = Color(0xFF82ABD0),
    outline = Color(0xFFB2B3B8),
    outlineVariant = Color(0xFFD0D1D6),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2F3033),
    inverseOnSurface = Color(0xFFF2F2F4),
    inversePrimary = Color(0xFF82ABD0),
    surfaceDim = Color(0xFFCFD0D5),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF),    // 最亮：对话框/浮层这类浮得最高的
    surfaceContainerLow = Color(0xFFFCFCFD),
    surfaceContainer = Color(0xFFF9F9FB),
    surfaceContainerHigh = Color(0xFFF5F5F8),
    surfaceContainerHighest = Color(0xFFF1F1F4),   // 默认卡片色（用得最多），仍明显亮于画布
)

/**
 * Ocean 预设 —— 整套取自 rikkahub「Ocean」主题（用户 2026-07-16 选定，作为可选主题而非默认）。
 * 浅色：极浅冷调底 #F6FAFD + 青蓝主色 #116682；深色：近黑冷底 #0F1417 + 亮青主色 #8BD0EF。
 * 是完整 M3 色板，**不经 cleanLightSurfaces**（那会把它的冷调底刷成白，破坏 Ocean 观感）。
 */
fun oceanLight(): ColorScheme = lightColorScheme(
    primary = Color(0xFF116682), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFBDE9FF), onPrimaryContainer = Color(0xFF004D64),
    secondary = Color(0xFF4D616C), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFD0E6F2), onSecondaryContainer = Color(0xFF354A53),
    tertiary = Color(0xFF5D5B7D), onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE3DFFF), onTertiaryContainer = Color(0xFF454364),
    error = Color(0xFFBA1A1A), onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF93000A),
    background = Color(0xFFF6FAFD), onBackground = Color(0xFF171C1F),
    surface = Color(0xFFF6FAFD), onSurface = Color(0xFF171C1F),
    surfaceVariant = Color(0xFFDCE4E9), onSurfaceVariant = Color(0xFF40484C),
    surfaceTint = Color(0xFF116682),
    outline = Color(0xFF70787D), outlineVariant = Color(0xFFC0C8CD),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFF2C3134), inverseOnSurface = Color(0xFFEDF1F5), inversePrimary = Color(0xFF8BD0EF),
    surfaceDim = Color(0xFFD6DBDE), surfaceBright = Color(0xFFF6FAFD),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F4F8),
    surfaceContainer = Color(0xFFEAEEF2), surfaceContainerHigh = Color(0xFFE4E9EC), surfaceContainerHighest = Color(0xFFDFE3E7),
)

fun oceanDark(): ColorScheme = darkColorScheme(
    primary = Color(0xFF8BD0EF), onPrimary = Color(0xFF003546),
    primaryContainer = Color(0xFF004D64), onPrimaryContainer = Color(0xFFBDE9FF),
    secondary = Color(0xFFB4CAD6), onSecondary = Color(0xFF1F333C),
    secondaryContainer = Color(0xFF354A53), onSecondaryContainer = Color(0xFFD0E6F2),
    tertiary = Color(0xFFC6C2EA), onTertiary = Color(0xFF2E2D4D),
    tertiaryContainer = Color(0xFF454364), onTertiaryContainer = Color(0xFFE3DFFF),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0F1417), onBackground = Color(0xFFDFE3E7),
    surface = Color(0xFF0F1417), onSurface = Color(0xFFDFE3E7),
    surfaceVariant = Color(0xFF40484C), onSurfaceVariant = Color(0xFFC0C8CD),
    surfaceTint = Color(0xFF8BD0EF),
    outline = Color(0xFF8A9297), outlineVariant = Color(0xFF40484C),
    scrim = Color(0xFF000000),
    inverseSurface = Color(0xFFDFE3E7), inverseOnSurface = Color(0xFF2C3134), inversePrimary = Color(0xFF116682),
    surfaceDim = Color(0xFF0F1417), surfaceBright = Color(0xFF353A3D),
    surfaceContainerLowest = Color(0xFF0A0F11), surfaceContainerLow = Color(0xFF171C1F),
    surfaceContainer = Color(0xFF1B2023), surfaceContainerHigh = Color(0xFF262B2D), surfaceContainerHighest = Color(0xFF303538),
)

/**
 * 系统动态色浅色的 surface 天生偏灰（Material You 浅色特性）。浅色下保留动态主色
 * (primary/secondary/tertiary 等由壁纸提取)，但把背景/表面换成干净白净梯度、正文加深，避免整屏发灰。暗色不动。
 */
private fun ColorScheme.cleanLightSurfaces(): ColorScheme = copy(
    // 同 fallbackLight 的层次方向：画布偏灰、卡片近白（卡片比画布亮才浮得起来），别再白画布+灰卡片。
    // 中性灰画布，不带色调（这条是壁纸动态色路径，主色仍随壁纸，但表面保持中性明暗层次）。
    // 色阶与 fallbackLight 完全同一套（撞色/塌陷同因同修，见那边的长注释）。
    // 两处必须保持一致：同一个组件在「品牌配色」和「动态色」下不该有两种层次感。
    background = Color(0xFFE2E3E7), onBackground = Color(0xFF1A1C1E),
    surface = Color(0xFFE2E3E7), onSurface = Color(0xFF1A1C1E),
    surfaceDim = Color(0xFFCFD0D5), surfaceBright = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFCFCFD),
    surfaceContainer = Color(0xFFF9F9FB), surfaceContainerHigh = Color(0xFFF5F5F8), surfaceContainerHighest = Color(0xFFF1F1F4),
    // 动态色的 outline 常常淡到看不见（Material You 浅色特性），一并压实——按钮描边看不见也是"失去分辨"的一种
    outline = Color(0xFFB2B3B8), outlineVariant = Color(0xFFD0D1D6),
)

/**
 * 壁纸莫奈动态色。
 *
 * **为什么不直接用 [dynamicLightColorScheme]/[dynamicDarkColorScheme]（安卓15「壁纸动态色不生效」的真因）：**
 * 官方动态色读的是系统 `system_accent*` 资源，而系统只把「跟随壁纸」的动态色 overlay 叠加到
 * **targetSdk≥31** 的应用上。本项目 targetSdk 钉在 28（为 proot/旧 SELinux 域，见 build.gradle），
 * 于是 `dynamicColorScheme` 在（尤其安卓15这种收紧的）ROM 上只拿到**默认调色板**——主色永远那一个、
 * 根本不随壁纸变，用户看到的就是「壁纸动态色不生效」。
 *
 * **修法：** 直接用 [WallpaperManager.getWallpaperColors]（API27+，**不需要权限、也不受 targetSdk 门限**，
 * 任何 ROM 都能取到当前壁纸算出的主/次/第三色）取壁纸主色作种子，自己生成整套 M3 配色——这样主色真随壁纸走。
 * 取不到壁纸色（低版本/动态壁纸无色/异常）再退官方动态色，最后退品牌配色。
 */
private fun wallpaperScheme(context: Context, dark: Boolean): ColorScheme {
    wallpaperSeed(context)?.let { return seedScheme(it, dark) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context).cleanLightSurfaces()
    return if (dark) fallbackDark() else fallbackLight()
}

/** 当前系统壁纸的主色（ARGB）；取不到返回 null。getWallpaperColors 免权限、不受 targetSdk 门限。 */
private fun wallpaperSeed(context: Context): Int? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) return null   // getWallpaperColors 是 API27
    return try {
        WallpaperManager.getInstance(context)
            .getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
            ?.primaryColor?.toArgb()
    } catch (_: Exception) { null }
}

/** 标准 HSL→RGB（h:0..360, s/l:0..1）。用它按「色调阶」生成同色相不同明度的色，近似 M3 tonal palette。 */
private fun hsl(h: Float, s: Float, l: Float): Color {
    val a = s * minOf(l, 1f - l)
    fun f(n: Int): Float {
        val k = (n + h / 30f) % 12f
        return l - a * maxOf(-1f, minOf(k - 3f, 9f - k, 1f))
    }
    return Color(f(0).coerceIn(0f, 1f), f(8).coerceIn(0f, 1f), f(4).coerceIn(0f, 1f))
}

/**
 * 由**一个种子色**生成整套 M3 配色（无 material-color-utilities 依赖时的轻量 tonal 近似）。
 * 三条路径共用它：壁纸取色、用户 HSV 手调、[ImageSeed] 从背景图提的主色——种子从哪来不重要，
 * 到这儿都只是一个颜色，所以没必要为"图片提色"再开一种 ColorSource。
 * 强调色取种子色相+饱和度按色调阶铺开；中性面用极低饱和的同色相（被种子微染），保证明暗层次与对比。
 * 以品牌回退为底 copy，未覆盖的角色（error/scrim…）沿用回退，保证完整与对比安全。
 */
private fun seedScheme(seedArgb: Int, dark: Boolean, respectSeedChroma: Boolean = false): ColorScheme {
    val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(seedArgb, it) }
    val h = hsv[0]
    // 壁纸色是「捡来的」，太淡就抬饱和，保证是个能认出来的强调色；
    // 而用户手调 / 从图片提出来的种子是**他明确指定的颜色**，抬饱和等于擅自改色（他选灰就该得灰），
    // 所以那条路径只压上限不抬下限。
    val chroma = if (respectSeedChroma) hsv[1].coerceAtMost(0.90f) else hsv[1].coerceIn(0.30f, 0.90f)
    val hT = (h + 60f) % 360f                     // 第三色：色相 +60°
    fun p(t: Int) = hsl(h, chroma, t / 100f)                             // primary 调色阶
    fun sec(t: Int) = hsl(h, (chroma * 0.35f).coerceIn(0.08f, 0.40f), t / 100f)  // secondary：低饱和
    fun ter(t: Int) = hsl(hT, chroma * 0.70f, t / 100f)                  // tertiary
    fun n(t: Int) = hsl(h, 0.04f, t / 100f)       // 中性面：几乎无色、微染壁纸色相
    fun nv(t: Int) = hsl(h, 0.08f, t / 100f)      // 中性变体
    val base = if (dark) fallbackDark() else fallbackLight()
    return if (dark) base.copy(
        primary = p(80), onPrimary = p(20), primaryContainer = p(30), onPrimaryContainer = p(90),
        secondary = sec(80), onSecondary = sec(20), secondaryContainer = sec(30), onSecondaryContainer = sec(90),
        tertiary = ter(80), onTertiary = ter(20), tertiaryContainer = ter(30), onTertiaryContainer = ter(90),
        background = n(6), onBackground = n(90), surface = n(6), onSurface = n(90),
        surfaceVariant = nv(30), onSurfaceVariant = nv(80), surfaceTint = p(80),
        outline = nv(60), outlineVariant = nv(30),
        inverseSurface = n(90), inverseOnSurface = n(20), inversePrimary = p(40),
        surfaceDim = n(6), surfaceBright = n(24),
        surfaceContainerLowest = n(4), surfaceContainerLow = n(10),
        surfaceContainer = n(12), surfaceContainerHigh = n(17), surfaceContainerHighest = n(22),
    ) else base.copy(
        primary = p(40), onPrimary = p(100), primaryContainer = p(90), onPrimaryContainer = p(10),
        secondary = sec(40), onSecondary = sec(100), secondaryContainer = sec(90), onSecondaryContainer = sec(10),
        tertiary = ter(40), onTertiary = ter(100), tertiaryContainer = ter(90), onTertiaryContainer = ter(10),
        // 浅色沿用项目层次方向：画布(background/surface)偏灰、卡片近白，卡片才浮得起来。
        //
        // ⚠ 2026-07-29 修的塌陷（三条浅色路径里这条最严重）：
        // 原来 background=n(95) 而 surfaceContainerHighest 也是 n(95) —— **一模一样**。
        // 而 Highest 是本项目事实上的默认卡片色（全项目 111 处），于是浅色下卡片直接隐形；
        // 同时 surfaceContainer=n(100) 比 surfaceContainerLow=n(98) 还亮，五档色阶非单调、
        // Lowest 与 Container 撞成同一个纯白。这就是「按钮和部分元素变成同一种颜色」的直接来源。
        //
        // 现在：画布压到 87，五档 100→99→97→95→93 单调递减、步距均匀，
        // 画布与最暗那档（Highest=93）之间留 6 个色调点，卡片一定看得出边。
        background = n(87), onBackground = n(10), surface = n(87), onSurface = n(10),
        surfaceVariant = nv(86), onSurfaceVariant = nv(30), surfaceTint = p(40),
        outline = nv(48), outlineVariant = nv(76),
        inverseSurface = n(20), inverseOnSurface = n(95), inversePrimary = p(80),
        surfaceDim = n(82), surfaceBright = n(100),
        surfaceContainerLowest = n(100), surfaceContainerLow = n(99),
        surfaceContainer = n(97), surfaceContainerHigh = n(95), surfaceContainerHighest = n(93),
    )
}

/**
 * 自定义主色种子 → 配色。种子可能来自 HSV 取色器，也可能是 [ImageSeed] 从背景图里提的主色。
 *
 * 走的是和壁纸色**同一条** [seedScheme] tonal 管线：以前这里只把种子塞进 primary、
 * 容器/次色/中性面全留着品牌回退色，结果是「换了主色但整屏还是原来那套蓝」——用户看不出改了啥。
 */
private fun customScheme(seed: Long, dark: Boolean): ColorScheme =
    seedScheme(seed.toInt() and 0x00FFFFFF or 0xFF000000.toInt(), dark, respectSeedChroma = true)

/**
 * 系统 Monet 调色板取色：直接读 system_accent 与 system_neutral 系统资源，映射到 M3 配色角色。
 * API31+ 用官方动态色；低版本（Android 11-12）若 ROM/官改包 backport 了这些资源也能取到，
 * 无 backport（资源不存在）则返回 null 由调用方回退。让没有官方动态色 API 的设备也能用系统动态色。
 */
private fun monetBackportScheme(context: Context, dark: Boolean): ColorScheme? {
    // ⚠ 顺序是**先读 system_accent* 资源、后走官方 API**，别调回来。
    // 官方 `dynamic*ColorScheme` 读的是同一份资源，但系统只把「跟随壁纸」的 overlay 叠给
    // **targetSdk≥31** 的应用；本项目钉在 28（为 proot/旧 SELinux 域），于是在 S+ 上它经常
    // 只拿到**默认调色板**——主色永远那一个、根本不随主题变，看起来就是「莫奈取色没生效 / 灰扑扑」。
    // 直接读资源至少在 ROM 真的做了 overlay 时能拿到真调色板；拿不到再退官方 API，最后由
    // 调用方退到壁纸取色（那条走 WallpaperManager，不受 targetSdk 门限，见 wallpaperScheme）。
    resourceMonetScheme(context, dark)?.let { return it }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        return if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context).cleanLightSurfaces()
    return null
}

/** 直接读 ROM 的 `system_accent*` / `system_neutral*` 调色板资源；没有 backport/没做 overlay 则 null。 */
private fun resourceMonetScheme(context: Context, dark: Boolean): ColorScheme? {
    return try {
        val res = context.resources
        fun col(name: String): Color {
            val id = res.getIdentifier(name, "color", "android")
            if (id == 0) throw android.content.res.Resources.NotFoundException(name)
            return Color(androidx.core.content.ContextCompat.getColor(context, id))
        }
        col("system_accent1_500") // 探测：无 backport 直接抛异常 → null
        if (dark) darkColorScheme(
            primary = col("system_accent1_200"), onPrimary = col("system_accent1_800"),
            primaryContainer = col("system_accent1_700"), onPrimaryContainer = col("system_accent1_100"),
            secondary = col("system_accent2_200"), onSecondary = col("system_accent2_800"),
            secondaryContainer = col("system_accent2_700"), onSecondaryContainer = col("system_accent2_100"),
            tertiary = col("system_accent3_200"), onTertiary = col("system_accent3_800"),
            tertiaryContainer = col("system_accent3_700"), onTertiaryContainer = col("system_accent3_100"),
            background = col("system_neutral1_900"), onBackground = col("system_neutral1_100"),
            surface = col("system_neutral1_900"), onSurface = col("system_neutral1_100"),
            surfaceVariant = col("system_neutral2_700"), onSurfaceVariant = col("system_neutral2_200"),
            outline = col("system_neutral2_400"), outlineVariant = col("system_neutral2_700"),
            surfaceTint = col("system_accent1_200"),
            surfaceContainerLowest = col("system_neutral1_1000"), surfaceContainerLow = col("system_neutral1_900"),
            surfaceContainer = col("system_neutral1_800"), surfaceContainerHigh = col("system_neutral1_700"),
            surfaceContainerHighest = col("system_neutral2_700"),
        ) else lightColorScheme(
            primary = col("system_accent1_600"), onPrimary = col("system_accent1_0"),
            primaryContainer = col("system_accent1_100"), onPrimaryContainer = col("system_accent1_900"),
            secondary = col("system_accent2_600"), onSecondary = col("system_accent2_0"),
            secondaryContainer = col("system_accent2_100"), onSecondaryContainer = col("system_accent2_900"),
            tertiary = col("system_accent3_600"), onTertiary = col("system_accent3_0"),
            tertiaryContainer = col("system_accent3_100"), onTertiaryContainer = col("system_accent3_900"),
            background = col("system_neutral1_10"), onBackground = col("system_neutral1_900"),
            surface = col("system_neutral1_10"), onSurface = col("system_neutral1_900"),
            surfaceVariant = col("system_neutral2_100"), onSurfaceVariant = col("system_neutral2_700"),
            outline = col("system_neutral2_500"), outlineVariant = col("system_neutral2_100"),
            surfaceTint = col("system_accent1_600"),
            surfaceContainerLowest = col("system_neutral1_0"), surfaceContainerLow = col("system_neutral1_50"),
            surfaceContainer = col("system_neutral1_100"), surfaceContainerHigh = col("system_neutral1_100"),
            surfaceContainerHighest = col("system_neutral2_100"),
        // ⚠ 上面这几档在多数 ROM 的调色板里会取到**同一个色**（neutral1_100 用了两次、
        // neutral2_100 与它也常常几乎一样），直接用就是又一处「元素同色」。
        // 统一交给 cleanLightSurfaces 铺一套单调的中性阶：主色仍跟 ROM 走，只有表面层次由我们定。
        ).cleanLightSurfaces()
    } catch (_: Exception) { null }
}

/** 依据 ThemeConfig 解析出最终 ColorScheme。 */
fun resolveColorScheme(context: Context, config: ThemeConfig, dark: Boolean): ColorScheme =
    when (config.colorSource) {
        ColorSource.WALLPAPER -> wallpaperScheme(context, dark)
        ColorSource.CUSTOM -> customScheme(config.customSeed, dark)
        ColorSource.FALLBACK -> if (dark) fallbackDark() else fallbackLight()
        // 取不到 ROM 调色板时**先退壁纸取色**再退品牌色：壁纸那条走 WallpaperManager，
        // 不受 targetSdk 门限，是本项目唯一在任何 ROM 上都能真的"跟着系统变"的路（见 wallpaperScheme）。
        // 直接退品牌色的话，用户选了「莫奈取色」却看到一成不变的蓝，会以为功能坏了。
        ColorSource.MONET -> monetBackportScheme(context, dark) ?: wallpaperScheme(context, dark)
        ColorSource.OCEAN -> if (dark) oceanDark() else oceanLight()
    }
