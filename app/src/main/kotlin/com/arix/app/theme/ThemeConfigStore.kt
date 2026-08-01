package com.arix.app.theme

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.arix.app.tr
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray

// ============================================================
// ThemeConfigStore —— 主题库的持久化 + 运行时状态源
// 贴现有 SharedPreferences 范式（见 ConfigModePrefs / UserPreferences），
// 不引入新依赖。改配置即时生效：XtomTheme 订阅 config 流，改设置全 App 重组。
//
// 存的是**一个列表 + 当前 id**（不是一份配置）：用户要能存好几套具名主题随时互切，
// 导入别人的主题包也只是往库里加一条，不再覆盖他正在用的那套。
// [config] 仍然只暴露「当前这套」，所以全 App 的读取方（XtomTheme/ChatSkins/各设置项）一行都不用改。
// ============================================================

object ThemeConfigStore {
    private const val PREFS = "xtom_theme"

    /** 旧版：整个 App 只有一份主题存在这个 key 上。现在只用于**迁移**和向下兼容镜像，见 [load]/[persist]。 */
    private const val KEY_LEGACY = "config_json"
    private const val KEY_THEMES = "themes_json"
    private const val KEY_CURRENT = "current_theme_id"

    private val _themes = MutableStateFlow(builtinPresets())
    /** 主题库全量（至少一条；顺序即页面上的展示顺序）。 */
    val themes: StateFlow<List<ThemeConfig>> = _themes.asStateFlow()

    // 初值取库里第一条（= 出厂观感），而不是裸的 ThemeConfig.Default：那份 id 是空的，
    // 万一 load() 之前就有人调 update()，空 id 会当成新主题塞进库里，多出一条来路不明的条目。
    private val _config = MutableStateFlow(builtinPresets().first())
    /** 当前正在用的那套主题。全 App 只认这个。 */
    val config: StateFlow<ThemeConfig> = _config.asStateFlow()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun newId(): String = java.util.UUID.randomUUID().toString().take(8)

    /**
     * 从磁盘装载主题库（无则用内置预设）。XtomTheme 首帧调用。
     *
     * ⚠ **向后兼容迁移**：老版本只写过一个 `config_json`。如果直接按「没有 themes_json 就用内置预设」
     * 处理，老用户升级上来会发现自己调了半天的主题被换成出厂预设——等于升级即清空。所以这里先看老 key：
     * 有就把它当成库里的**第一条并选中**，再把内置预设排在它后面（预设是白送的，不能顶掉他的）。
     * 迁移后老 key 不删：万一用户装回旧版本，旧版本还读得到自己那份（[persist] 持续镜像当前主题过去）。
     */
    fun load(context: Context) {
        val p = prefs(context)
        val stored = parseThemes(p.getString(KEY_THEMES, null))
        val list: List<ThemeConfig>
        val currentId: String
        if (stored.isNotEmpty()) {
            list = stored
            val want = p.getString(KEY_CURRENT, null)
            currentId = list.firstOrNull { it.id == want }?.id ?: list.first().id
        } else {
            val legacyJson = p.getString(KEY_LEGACY, null)
            if (!legacyJson.isNullOrBlank()) {
                // 老用户：他那份必须活下来且仍是选中态
                val mine = ThemeConfig.fromJson(legacyJson).let {
                    it.copy(id = it.id.ifBlank { newId() }, name = it.name.ifBlank { tr("我的主题") })
                }
                list = listOf(mine) + builtinPresets().filter { it.name != mine.name }
                currentId = mine.id
            } else {
                // 全新安装：库里直接是内置预设，第一条（Arix 默认）= 历史出厂观感，选中它，观感不变
                list = builtinPresets()
                currentId = list.first().id
            }
        }
        _themes.value = list
        _config.value = list.first { it.id == currentId }
        // 只有内容真变了（迁移过来的、补过 id 的）才写盘；正常启动一次白写都没有。
        if (serialize() != p.getString(KEY_THEMES, null) || currentId != p.getString(KEY_CURRENT, null)) persist(context)
        else publishShared(_config.value)   // 让独立进程的终端 App 取到当前配色
    }

    /**
     * 更新**当前**这套主题（外观设置页改任意令牌都走这里，语义与旧版一致）。
     * id 对不上（例如别处 new 出来的配置）就按「覆盖当前」处理——保持老调用方的行为。
     */
    fun update(context: Context, config: ThemeConfig) {
        val id = config.id.ifBlank { _config.value.id }.ifBlank { newId() }
        val next = config.copy(id = id)
        val list = _themes.value.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx >= 0) list[idx] = next else list.add(next)
        _themes.value = list
        _config.value = next
        persist(context)
    }

    /** 切到库里的另一套主题。id 不存在则不动（别把用户切进空气里）。
     *  不叫 apply：那是 stdlib 作用域函数的名字，`ThemeConfigStore.apply(...)` 读起来会误以为是它。 */
    fun applyTheme(context: Context, id: String) {
        val target = _themes.value.firstOrNull { it.id == id } ?: return
        _config.value = target
        persist(context)
    }

    /** 把当前配置另存为新的一套并切过去（用户调好了想留个档，再接着折腾）。 */
    fun saveAs(context: Context, name: String): ThemeConfig {
        val copy = _config.value.copy(id = newId(), name = uniqueName(name.ifBlank { tr("我的主题") }))
        _themes.value = _themes.value + copy
        _config.value = copy
        persist(context)
        return copy
    }

    fun rename(context: Context, id: String, name: String) {
        val n = name.trim().ifBlank { return }
        _themes.value = _themes.value.map { if (it.id == id) it.copy(name = n) else it }
        if (_config.value.id == id) _config.value = _config.value.copy(name = n)
        persist(context)
    }

    /** 删一套。库不能删空（没主题可用 = 白屏级事故），删的是当前那套就自动切到剩下的第一条。 */
    fun delete(context: Context, id: String) {
        val rest = _themes.value.filter { it.id != id }
        if (rest.isEmpty()) return
        _themes.value = rest
        if (_config.value.id == id) _config.value = rest.first()
        persist(context)
    }

    /**
     * 把一套主题**加进库**（导入主题包/扫码走这里）。
     * 默认不切过去：导入是「多了一套可选的」，不该把用户当前用着的观感直接换掉——他还没看见长啥样。
     * 重发 id：别人导出的包里带的是他那边的 id，照搬会和本地条目撞号，撞了就变成「导入把我的某套覆盖了」。
     */
    fun add(context: Context, config: ThemeConfig, select: Boolean = false): ThemeConfig {
        val entry = config.copy(id = newId(), name = uniqueName(config.name.ifBlank { tr("导入的主题") }))
        _themes.value = _themes.value + entry
        if (select) _config.value = entry
        persist(context)
        return entry
    }

    /** 同名主题加序号：列表里两条一模一样的名字，用户根本分不出该点哪个。 */
    private fun uniqueName(base: String): String {
        val taken = _themes.value.map { it.name }.toSet()
        if (base !in taken) return base
        var i = 2
        while ("$base $i" in taken) i++
        return "$base $i"
    }

    private fun parseThemes(json: String?): List<ThemeConfig> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                arr.optJSONObject(i)?.let { ThemeConfig.fromJsonObject(it) }
            }.map { if (it.id.isBlank()) it.copy(id = newId()) else it }
        } catch (_: Exception) { emptyList() }
    }

    private fun serialize(): String =
        JSONArray().apply { _themes.value.forEach { put(it.toJsonObject()) } }.toString()

    private fun persist(context: Context) {
        prefs(context).edit()
            .putString(KEY_THEMES, serialize())
            .putString(KEY_CURRENT, _config.value.id)
            // 镜像当前主题到老 key：装回旧版本 APK 时它只认这个，至少配色不回到出厂
            .putString(KEY_LEGACY, _config.value.toJson())
            .apply()
        publishShared(_config.value)
    }

    /**
     * 内置预设。让主题库一进去就不是空的，也顺便当「这些轴能怎么搭」的样例。
     * 色值全是自己配的（品牌蓝/青玉/纸褐/品红），不取自任何竞品主题。
     * id 固定成可读常量而不是随机：这样重装/重跑 [load] 时预设不会重复堆一堆同名条目。
     */
    fun builtinPresets(): List<ThemeConfig> = listOf(
        // 出厂观感原样：品牌蓝 + 深色 + 大圆角 + 玻璃。放第一条，全新安装选中它 = 和以前一模一样。
        ThemeConfig.Default.copy(id = "preset_arix", name = tr("Arix 默认")),
        // 墨玉：青玉绿种子走 tonal 管线，柔和按钮 + 标准圆角，比默认更沉一点
        ThemeConfig(
            id = "preset_jade", name = tr("墨玉"),
            colorSource = ColorSource.CUSTOM, customSeed = 0xFF2FB6A0L,
            darkMode = DarkMode.DARK, shape = ShapeScale.STANDARD,
            componentStyle = ComponentStyle.TONAL, globalGlass = true,
        ),
        // 宣纸：浅色 + 赭石点缀 + 描边组件 + **关玻璃**（纸的观感不该有磨砂反光），紧凑圆角
        ThemeConfig(
            id = "preset_paper", name = tr("宣纸"),
            colorSource = ColorSource.CUSTOM, customSeed = 0xFFB07A4AL,
            darkMode = DarkMode.LIGHT, shape = ShapeScale.COMPACT,
            componentStyle = ComponentStyle.OUTLINED, globalGlass = false,
            metaBlockStyle = MetaBlockStyle.TIMELINE,
        ),
        // 霓虹：品红种子 + 表现力大圆角 + 填充按钮 + 玻璃，深色下最跳
        ThemeConfig(
            id = "preset_neon", name = tr("霓虹"),
            colorSource = ColorSource.CUSTOM, customSeed = 0xFFE05FA6L,
            darkMode = DarkMode.DARK, shape = ShapeScale.EXPRESSIVE,
            componentStyle = ComponentStyle.FILLED, globalGlass = true,
            metaBlockStyle = MetaBlockStyle.GLASS,
        ),
    )

    /**
     * 把配色相关配置发布到**共享文件** `/sdcard/Arix/theme.json`，供独立进程/独立 App 的终端读取同步取色
     * （两个 App 各自私有 prefs 互相读不到，只能经共享目录——与背景图同一套共享约定）。写临时再改名，防读到写一半。
     */
    /**
     * ⚠ 必须离开调用线程：[load] 是 XtomTheme 首帧调的，而这里是 `/sdcard` 上的
     * mkdirs + 写 + 删 + 改名（外部存储路径还可能触发一次卷挂载检查）——冷启动首帧同步做这一串，
     * 用户看到的就是启动慢。用 `limitedParallelism(1)` 而不是随便一个 IO 线程：
     * 连着改设置会连着发布，并发写同一个文件会写串，串行化后是「后提交的后写」，结果稳定。
     */
    private val publishScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.Dispatchers.IO.limitedParallelism(1) + kotlinx.coroutines.SupervisorJob()
    )
    @Volatile private var lastPublished: String? = null

    private fun publishShared(config: ThemeConfig) {
        val json = config.toJson()
        if (json == lastPublished) return   // 内容没变就别写盘：load() 与随后的 update() 常常是同一份
        lastPublished = json
        publishScope.launch {
            runCatching {
                val f = java.io.File(android.os.Environment.getExternalStorageDirectory(), "Arix/theme.json")
                f.parentFile?.mkdirs()
                val tmp = java.io.File(f.parentFile, "theme.json.tmp")
                tmp.writeText(json)
                if (f.exists()) f.delete()
                tmp.renameTo(f)
            }
        }
    }
}

/** 当前主题配置的 CompositionLocal，组件可直接读（如模糊/动效速度）。 */
val LocalThemeConfig = staticCompositionLocalOf { ThemeConfig.Default }
