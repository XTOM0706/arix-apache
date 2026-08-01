package com.arix.app.theme

import org.json.JSONObject

// ============================================================
// ThemeConfig —— 运行时主题引擎的可序列化数据模型（DESIGN.md §1）
// 这是整套“UI 风格 / 主题包”的数据形态：配色来源 + 亮暗 + 字体 + 形状 +
// 组件风格 + 动效速度 + 模糊。存进 SharedPreferences（JSON），也可将来
// 导出成文件分享（§5）。所有令牌值最终由它驱动 MaterialExpressiveTheme。
// ============================================================

/** 配色来源。
 *  CUSTOM = 用户指定的主色种子（HSV 取色器手调，或个性化页「从这张图取色」由 ImageSeed 从背景图提出来），
 *  经 seedScheme 生成整套 M3 配色——图片提色不是单独一种来源，提完就是一个种子。
 *  MONET = 直接读系统 Monet 调色板资源（system_accent 与 system_neutral），含 Android 11-12 backport 设备（官改包/低版本无官方动态色 API 时也能用）。 */
enum class ColorSource { WALLPAPER, CUSTOM, FALLBACK, MONET, OCEAN }

/** 亮/暗模式。SYSTEM = 跟随系统。 */
enum class DarkMode { SYSTEM, LIGHT, DARK }

/** 字体族。CUSTOM=用户自选的 ttf/otf(路径存 customFontPath)。ROUNDED 暂回退系统默认。 */
enum class FontChoice { DEFAULT, SERIF, MONO, ROUNDED, CUSTOM }

/** 圆角/形状档位：紧凑 / 标准 / 表现力（M3E 大圆角）。 */
enum class ShapeScale { COMPACT, STANDARD, EXPRESSIVE }

/** 组件强调风格（按钮等默认变体）。 */
enum class ComponentStyle { FILLED, TONAL, OUTLINED }

/** 动效速度：FAST = Telegram 丝滑短促线性；STANDARD = 常规。 */
enum class MotionSpeed { FAST, STANDARD }

/** 模糊质量：关 / 仅关键面（默认）/ 高质量（DESIGN.md §4）。 */
enum class BlurQuality { OFF, KEY_SURFACES, HIGH }

/** 消息密度：聊天气泡内边距 + 消息间距的档位。紧凑省空间、舒适更透气。 */
enum class MessageDensity { COMPACT, MEDIUM, COMFORTABLE }

/**
 * 聊天滚动位置指示。
 * - [JUMPER]：右侧一列跳转圆钮（跳顶/上一条/下一条/回底），只在「最近滚过、手指松开」时浮现，**零逐帧进度计算**。
 * - [CAPSULE]：位置胶囊（拖动时浮现的小圆点 + 点开定位器），滑动时按 layoutInfo 算进度。
 * - [OFF]：不显示，最省。
 */
enum class ScrollIndicator { JUMPER, CAPSULE, OFF }

/**
 * 工具调用 / 思考块的呈现风格。
 * - [TIMELINE]：把一条 AI 消息里相邻的思考+工具步骤合并进**一张淡色卡 + 竖直时间线**，每步是「图标+标题+展开」，不是气泡。
 * - [GLASS]：各步各自一张玻璃卡（旧观感）。
 */
enum class MetaBlockStyle { TIMELINE, GLASS }

/**
 * 页面顶部 chrome 的风格。
 *
 * - [M3]：标准 Material 3 `TopAppBar`——返回在 navigationIcon 位、正经标题在 title 位。
 *   **默认**。规范内的东西有官方背书：状态层/涟漪/触控尺寸/滚动收缩都是对的。
 * - [GLASS]：内容从顶部渐变模糊玻璃下穿过 + 左上悬浮胶囊返回键。自创风格，不在任何 M3
 *   规范里，但省掉一整条实体标题栏的高度（手表上寸土寸金），有人就喜欢这个观感。
 *
 * 之所以做成可选而不是二选一砍掉：GLASS 是用户明确要过的，M3 是规范该有的样子，两者
 * 各有拥趸，没必要替用户决定。
 */
enum class ChromeStyle { M3, GLASS }

/** 消息密度 → 一组间距令牌（dp 数值）。ChatComponents/气泡按当前密度取值。
 *  MEDIUM 刻意等于历史硬编码值（气泡内边距 10、消息间距 3），老用户升级观感不变。 */
data class MessageSpacing(val bubblePadding: Int, val messageGap: Int)

fun MessageDensity.spacing(): MessageSpacing = when (this) {
    MessageDensity.COMPACT -> MessageSpacing(bubblePadding = 7, messageGap = 1)
    MessageDensity.MEDIUM -> MessageSpacing(bubblePadding = 10, messageGap = 3)
    MessageDensity.COMFORTABLE -> MessageSpacing(bubblePadding = 13, messageGap = 6)
}

data class ThemeConfig(
    // 主题库里的身份。空串 = 还没入库（导入的主题包、老版本迁移前的那份），入库时由
    // ThemeConfigStore 补一个新 id。**必须有 id**：主题库要能「改的是哪一套」——光靠 name
    // 分不清（用户完全可以起两个同名主题），靠列表下标也不行（删一条后面全错位）。
    val id: String = "",
    val name: String = "默认",
    // 默认跟随系统莫奈取色（用户 2026-07-29 定）。
    //
    // 这和早先「默认品牌蓝，因为壁纸动态色灰扑扑」的结论**不冲突**——那说的是 [ColorSource.WALLPAPER]
    // （从壁纸像素里现算一个种子，风景照常算出一坨灰）。[MONET] 读的是 **ROM 自己调过的**
    // system_accent/system_neutral 调色板，ColorOS/HyperOS 这类都调得挺好，不是同一个东西。
    // 取不到调色板时会依次退壁纸取色、品牌色（见 resolveColorScheme），不会出现"选了没反应"。
    val colorSource: ColorSource = ColorSource.MONET,
    // 自定义主色种子（ARGB Long），colorSource == CUSTOM 时生效。默认取品牌蓝。
    val customSeed: Long = 0xFF89B4FAL,
    val darkMode: DarkMode = DarkMode.DARK,
    val font: FontChoice = FontChoice.DEFAULT,
    val customFontPath: String = "",   // font==CUSTOM 时的 ttf/otf 文件路径
    val shape: ShapeScale = ShapeScale.EXPRESSIVE,
    val componentStyle: ComponentStyle = ComponentStyle.FILLED,
    val motion: MotionSpeed = MotionSpeed.FAST,
    // 顶栏毛玻璃档位（与全局玻璃 globalGlass 分开）。默认 OFF：顶栏那套离屏录制费性能，
    // 想要顶栏也毛玻璃再开。全 UI 的玻璃由 globalGlass 独立控制。
    val blur: BlurQuality = BlurQuality.OFF,
    // 全局字号缩放（约 0.8..1.4）。只放大文字(.sp)，不动布局(dp)，避免整屏变形。
    val fontScale: Float = 1.0f,
    // 显示密度（真 DPI 缩放，约 0.85..1.15）。乘到 Density.density 上，整体放大/缩小所有 dp 布局+文字，
    // 区别于只放大文字的 fontScale。范围保守，避免撑破手表方屏布局。
    val displayDensity: Float = 1.0f,
    // 聊天消息密度（气泡内边距/消息间距档位）。默认 MEDIUM = 历史观感。
    val messageDensity: MessageDensity = MessageDensity.MEDIUM,
    // 顶部 chrome 风格。默认 M3：规范内的样子。GLASS 是自创的渐变模糊+悬浮胶囊，保留给喜欢的人。
    val chromeStyle: ChromeStyle = ChromeStyle.M3,
    // 全局玻璃：开了则**所有界面**的卡片/面板/气泡都变成悬浮玻璃（真·背景模糊透出壁纸）。
    // 独立开关（用户 2026-07-16 要求）。手表版不考虑性能，默认开。
    val globalGlass: Boolean = true,
    // 聊天滚动位置指示风格。默认 JUMPER（零逐帧计算，最流畅），可切位置胶囊/关闭。
    val scrollIndicator: ScrollIndicator = ScrollIndicator.JUMPER,
    // 工具调用/思考块风格。默认 TIMELINE（合并进一张时间线卡，非气泡），可切回玻璃卡。
    val metaBlockStyle: MetaBlockStyle = MetaBlockStyle.TIMELINE,
) {
    fun toJson(): String = toJsonObject().toString()

    /** 主题库要把多套主题拼进一个 JSONArray，所以序列化拆出「对象」这一层，避免存成一堆字符串再解析。 */
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("colorSource", colorSource.name)
        put("customSeed", customSeed)
        put("darkMode", darkMode.name)
        put("font", font.name)
        put("customFontPath", customFontPath)
        put("shape", shape.name)
        put("componentStyle", componentStyle.name)
        put("motion", motion.name)
        put("blur", blur.name)
        put("fontScale", fontScale.toDouble())
        put("displayDensity", displayDensity.toDouble())
        put("messageDensity", messageDensity.name)
        put("chromeStyle", chromeStyle.name)
        put("globalGlass", globalGlass)
        put("scrollIndicator", scrollIndicator.name)
        put("metaBlockStyle", metaBlockStyle.name)
    }

    companion object {
        // 出厂默认 = 壁纸动态色（DESIGN.md §1）。内测阶段不再保留旧观感。
        val Default = ThemeConfig()

        fun fromJson(json: String?): ThemeConfig {
            if (json.isNullOrBlank()) return Default
            return try { fromJsonObject(JSONObject(json)) } catch (_: Exception) { Default }
        }

        fun fromJsonObject(o: JSONObject): ThemeConfig {
            return try {
                ThemeConfig(
                    // 导入的主题包多半没有 id（或带着别人的 id）——入库时统一重发，见 ThemeConfigStore.add
                    id = o.optString("id", ""),
                    name = o.optString("name", Default.name),
                    colorSource = parseEnum(o.optString("colorSource"), Default.colorSource),
                    customSeed = o.optLong("customSeed", Default.customSeed),
                    darkMode = parseEnum(o.optString("darkMode"), Default.darkMode),
                    font = parseEnum(o.optString("font"), Default.font),
                    customFontPath = o.optString("customFontPath", Default.customFontPath),
                    shape = parseEnum(o.optString("shape"), Default.shape),
                    componentStyle = parseEnum(o.optString("componentStyle"), Default.componentStyle),
                    motion = parseEnum(o.optString("motion"), Default.motion),
                    blur = parseEnum(o.optString("blur"), Default.blur),
                    fontScale = o.optDouble("fontScale", Default.fontScale.toDouble()).toFloat().coerceIn(0.8f, 1.4f),
                    displayDensity = o.optDouble("displayDensity", Default.displayDensity.toDouble()).toFloat().coerceIn(0.85f, 1.15f),
                    messageDensity = parseEnum(o.optString("messageDensity"), Default.messageDensity),
                    chromeStyle = parseEnum(o.optString("chromeStyle"), Default.chromeStyle),
                    globalGlass = o.optBoolean("globalGlass", Default.globalGlass),
                    scrollIndicator = parseEnum(o.optString("scrollIndicator"), Default.scrollIndicator),
                    metaBlockStyle = parseEnum(o.optString("metaBlockStyle"), Default.metaBlockStyle),
                )
            } catch (_: Exception) {
                Default
            }
        }

        private inline fun <reified T : Enum<T>> parseEnum(value: String?, default: T): T =
            try { if (value.isNullOrBlank()) default else enumValueOf<T>(value) }
            catch (_: Exception) { default }
    }
}
