package com.arix.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 抽屉入口排布：用户在「排布抽屉」编辑器里把每个入口放进「导航区」「底部」或收回「隐藏」(=右侧组件面板)，
 * 区内排序，底部入口还能各自选大小(小圆球/中胶囊/大整行)。
 *
 * 只存 id + 归属区 + 区内顺序 + 大小；图标/文字/点击动作都在 MainActivity 里就地绑
 * （动作要用页面局部闭包 navTo/showCardSelector 等，存不进 prefs）。id 直接用页面 key（"cards"/"config"…），
 * 便于点击时 navTo(id)。
 *
 * 存储不变量：列表**按区分组**——先全部 nav、再全部 bottom、再全部 hidden，每组内部保持顺序。
 */
object DrawerLayoutStore {
    private const val PREFS = "xtom_drawer_layout"
    private const val KEY = "layout"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    const val ZONE_NAV = "nav"
    const val ZONE_BOTTOM = "bottom"
    const val ZONE_HIDDEN = "hidden"

    // 底部入口大小 = 占底部行宽的百分比。6 个固定档位，可自由拖拽——拖动时吸附到最近档位。
    // 档位：球/小/中/大/大半/整行（去掉了「极小」，最小直接就是球）。<= W_BALL_MAX 收成纯图标圆球。
    val WIDTH_TIERS = listOf(15, 34, 50, 66, 82, 100)
    const val W_MIN = 15
    const val W_MAX = 100
    const val W_DEFAULT = 34
    const val W_BALL_MAX = 17
    fun clampW(w: Int) = w.coerceIn(W_MIN, W_MAX)
    /** 吸附到最近档位。 */
    fun snapTier(w: Int): Int = WIDTH_TIERS.minByOrNull { kotlin.math.abs(it - w) } ?: W_DEFAULT
    /** 循环到下一档（点一下切换用）。 */
    fun nextTier(w: Int): Int { val i = WIDTH_TIERS.indexOf(snapTier(w)); return WIDTH_TIERS[(i + 1) % WIDTH_TIERS.size] }
    fun tierLabel(w: Int): String = when (WIDTH_TIERS.indexOf(snapTier(w))) {
        0 -> "球"; 1 -> "小"; 2 -> "中"; 3 -> "大"; 4 -> "大半"; else -> "整行"
    }
    /** 单字档位（塞进拖柄里显示用）：球/小/中/大/半/满。 */
    fun tierShort(w: Int): String = when (WIDTH_TIERS.indexOf(snapTier(w))) {
        0 -> "球"; 1 -> "小"; 2 -> "中"; 3 -> "大"; 4 -> "半"; else -> "满"
    }

    // 空白间隔占位：可加多个、宽度可调，用来在底部把入口推开。id 形如 "spacer:1"（实例唯一）。
    const val SPACER_PREFIX = "spacer:"
    fun isSpacer(id: String) = id.startsWith(SPACER_PREFIX)
    /** 基于现有条目生成下一个不冲突的空白 id（确定性，不用随机/时间）。 */
    fun newSpacerId(existing: List<Item>): String {
        val n = existing.mapNotNull { if (isSpacer(it.id)) it.id.removePrefix(SPACER_PREFIX).toIntOrNull() else null }.maxOrNull() ?: 0
        return SPACER_PREFIX + (n + 1)
    }

    /**
     * id -> 默认所属区。这张表也定义了「全集」：不在表里的 id 会被丢弃。
     * 默认抽屉 = 原来的 5 导航 + 2 底部；其余(设置里的一切)默认隐藏，在右侧面板待选。
     */
    private val DEFAULTS: List<Pair<String, String>> = listOf(
        // 常用（默认就摆出来的）
        "cards" to ZONE_NAV,
        "files" to ZONE_NAV,
        "worldtree" to ZONE_NAV,
        "memory" to ZONE_NAV,
        "browser" to ZONE_NAV,
        "newchat" to ZONE_BOTTOM,
        "settings" to ZONE_BOTTOM,
        // 设置里的入口（默认隐藏 = 在组件面板待选）
        "personalization" to ZONE_HIDDEN,
        "config" to ZONE_HIDDEN,
        // ⚠ 新页面必须在这儿登记：load() 按 DEFAULTS 的 known 集合过滤，没登记的 id 一律被丢掉，
        // 于是抽屉自定义面板里根本看不到它们（页面本身能用，只是没法拖进抽屉）。
        "proxy" to ZONE_HIDDEN,
        "storage" to ZONE_HIDDEN,
        "app_log" to ZONE_HIDDEN,
        "update" to ZONE_HIDDEN,
        "dialog_settings" to ZONE_HIDDEN,
        "companion_settings" to ZONE_HIDDEN,
        "voice_clone" to ZONE_HIDDEN,
        "user_scripts" to ZONE_HIDDEN,
        "search_settings" to ZONE_HIDDEN,
        "site_login" to ZONE_HIDDEN,
        "conversations" to ZONE_HIDDEN,
        "favorites" to ZONE_HIDDEN,
        "usage" to ZONE_HIDDEN,
        "workflows" to ZONE_HIDDEN,
        "import" to ZONE_HIDDEN,
        "wake" to ZONE_HIDDEN,
        "projects" to ZONE_HIDDEN,
        "tool_keys" to ZONE_HIDDEN,
        "packages" to ZONE_HIDDEN,
        "file_history" to ZONE_HIDDEN,
        "chat_appearance" to ZONE_HIDDEN,
        "operit" to ZONE_HIDDEN,
        "plugins" to ZONE_HIDDEN,
        "terminal" to ZONE_HIDDEN,
        "permissions" to ZONE_HIDDEN,
        "activity_center" to ZONE_HIDDEN,
        "crash" to ZONE_HIDDEN,
        "onboarding" to ZONE_HIDDEN,
        "about" to ZONE_HIDDEN,
    )

    data class Item(val id: String, val zone: String, val size: Int = W_DEFAULT)

    private fun zoneRank(zone: String) = when (zone) {
        ZONE_NAV -> 0; ZONE_BOTTOM -> 1; else -> 2
    }

    /**
     * 读取排布，与「全集」对账：丢未知 id、把缺失 id 按默认区补到末尾，最后按区分组。永远返回完整、分组好的列表。
     */
    fun load(c: Context): List<Item> {
        val stored = parse(p(c).getString(KEY, null))
        val known = DEFAULTS.map { it.first }.toSet()
        val result = ArrayList<Item>()
        val seen = HashSet<String>()
        for (it in stored) {
            if ((it.id in known || isSpacer(it.id)) && it.id !in seen && it.zone in setOf(ZONE_NAV, ZONE_BOTTOM, ZONE_HIDDEN)) {
                result.add(it); seen.add(it.id)
            }
        }
        for ((id, zone) in DEFAULTS) if (id !in seen) result.add(Item(id, zone))
        return result.sortedBy { zoneRank(it.zone) }
    }

    fun save(c: Context, items: List<Item>) {
        val grouped = items.sortedBy { zoneRank(it.zone) }
        val arr = JSONArray()
        for (it in grouped) arr.put(JSONObject().put("id", it.id).put("zone", it.zone).put("size", it.size))
        p(c).edit().putString(KEY, arr.toString()).apply()
    }

    fun reset(c: Context) = p(c).edit().remove(KEY).apply()

    private fun parse(s: String?): List<Item> {
        if (s.isNullOrBlank()) return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id", ""); val zone = o.optString("zone", "")
                val size = clampW(o.optInt("size", W_DEFAULT))
                if (id.isBlank()) null else Item(id, zone, size)
            }
        } catch (_: Exception) { emptyList() }
    }
}
