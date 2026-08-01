package com.arix.tool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

// mxnzp 免费 key（一个 key 解锁 油价/汇率/黄历/垃圾分类/快递/解梦/手机归属；不填则这些走无 key 兜底或提示）
object LifePrefs {
    private const val P = "xtom_life"
    private fun p(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)
    fun appId(c: Context) = (p(c).getString("id", "") ?: "").trim()
    fun appSecret(c: Context) = (p(c).getString("secret", "") ?: "").trim()
    fun set(c: Context, id: String, secret: String) = p(c).edit().putString("id", id.trim()).putString("secret", secret.trim()).apply()
}

private fun lg(url: String): String? = try {
    val c = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
        connectTimeout = 10000; readTimeout = 12000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36")
    }
    if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else { c.errorStream?.close(); null }
} catch (_: Exception) { null }

private fun e(s: String) = URLEncoder.encode(s, "UTF-8")

// ============================================================
// 生活查询 —— 一个工具多用：节假日/黄历、油价、汇率、垃圾分类、快递、IP归属、一言、星座、解梦、手机归属。
// 无 key(节假日/汇率/IP/一言/星座)开箱即用；油价/垃圾分类/快递/解梦/手机 需在设置填 mxnzp 免费 key。
// ============================================================
class LifeQueryTool(private val context: Context) : Tool {
    override val name = "life_query"
    override val description = "生活查询(一个工具多用)：type=holiday 节假日/调休、oil 油价、fx 汇率、garbage 垃圾分类、express 快递、ip IP归属、quote 一言、horoscope 星座、dream 周公解梦、phone 手机号归属、hotnews 各平台实时热榜。query=查询词(城市/物品/单号/IP/星座/关键词/平台名等)。"
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("type", JSONObject().apply { put("type", "string"); put("enum", JSONArray(listOf("holiday", "oil", "fx", "garbage", "express", "ip", "quote", "horoscope", "dream", "phone", "hotnews"))); put("description", "查询类别") })
            put("query", JSONObject().apply { put("type", "string"); put("description", "查询内容：holiday=日期(默认今天)；oil=省份；garbage/dream=物品/关键词；express=快递单号；ip=IP；horoscope=星座；phone=手机号；hotnews=平台名(微博/知乎/抖音/头条/B站/36氪/掘金/豆瓣/虎扑，默认微博)") })
            put("company", JSONObject().apply { put("type", "string"); put("description", "express 时的快递公司代码，如 SF/YTO/ZTO") })
            put("to", JSONObject().apply { put("type", "string"); put("description", "fx 目标币种(默认 USD,EUR,JPY,HKD)") })
        })
        put("required", JSONArray(listOf("type")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val q = params.optString("query", "").trim()
        val id = LifePrefs.appId(context); val sec = LifePrefs.appSecret(context)
        val mxnzp = id.isNotBlank() && sec.isNotBlank()
        fun needKey() = ToolResult("这个功能需要 mxnzp 免费 key（在 设置→应用设置→「生活 API Key」填，一个 key 解锁 油价/垃圾分类/快递/解梦/手机归属）", isError = true)
        fun mx(path: String) = lg("https://www.mxnzp.com/api/$path" + (if (path.contains("?")) "&" else "?") + "app_id=$id&app_secret=$sec")
        try {
            when (params.optString("type", "")) {
                "holiday" -> {
                    val date = q.ifBlank { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date()) }
                    val j = lg("https://timor.tech/api/holiday/info/$date") ?: return@withContext ToolResult("查不到节假日", isError = true)
                    val t = JSONObject(j).optJSONObject("type")
                    ToolResult("📅 $date · ${t?.optString("name") ?: ""}" + when (t?.optInt("type")) { 0 -> "（工作日）"; 1 -> "（周末）"; 2 -> "（法定节假日）"; 3 -> "（调休上班）"; else -> "" })
                }
                "fx" -> {
                    val to = params.optString("to", "USD,EUR,JPY,HKD")
                    val j = lg("https://api.frankfurter.dev/v1/latest?base=CNY&symbols=$to") ?: return@withContext ToolResult("查不到汇率", isError = true)
                    val r = JSONObject(j).optJSONObject("rates") ?: return@withContext ToolResult("汇率数据异常", isError = true)
                    ToolResult("💱 1 CNY ≈ " + r.keys().asSequence().joinToString("  ") { "${r.optDouble(it)} $it" })
                }
                "quote" -> lg("https://v1.hitokoto.cn/?encode=json")?.let { ToolResult("『${JSONObject(it).optString("hitokoto")}』— ${JSONObject(it).optString("from")}") } ?: ToolResult("获取失败", isError = true)
                "horoscope" -> {
                    if (q.isBlank()) return@withContext ToolResult("请提供星座（白羊/金牛…）", isError = true)
                    val map = mapOf("白羊" to "aries", "金牛" to "taurus", "双子" to "gemini", "巨蟹" to "cancer", "狮子" to "leo", "处女" to "virgo", "天秤" to "libra", "天蝎" to "scorpio", "射手" to "sagittarius", "摩羯" to "capricorn", "水瓶" to "aquarius", "双鱼" to "pisces")
                    val en = map.entries.firstOrNull { q.contains(it.key) }?.value ?: q
                    val j = lg("https://api.vvhan.com/api/horoscope?type=$en&time=today") ?: return@withContext ToolResult("查不到星座运势", isError = true)
                    val d = JSONObject(j).optJSONObject("data") ?: return@withContext ToolResult("星座数据异常", isError = true)
                    ToolResult("⭐ ${d.optString("title")} 今日  综合:${d.optJSONObject("index")?.optString("all") ?: ""}  幸运色:${d.optString("luckycolor")}  数字:${d.optString("luckynumber")}\n${d.optString("shortcomment")}")
                }
                "ip" -> {
                    val j = lg(if (q.isBlank()) "https://ipapi.co/json/" else "https://ipapi.co/$q/json/") ?: return@withContext ToolResult("查不到 IP 归属", isError = true)
                    val o = JSONObject(j)
                    ToolResult("🌐 ${o.optString("ip")}  ${o.optString("country_name")} ${o.optString("region")} ${o.optString("city")}  ${o.optString("org")}")
                }
                "oil" -> {
                    val prov = q.ifBlank { "北京" }
                    if (mxnzp) {
                        val d = mx("oil/search?province=${e(prov)}")?.let { JSONObject(it).optJSONObject("data") } ?: return@withContext ToolResult("查不到油价", isError = true)
                        ToolResult("⛽ ${prov}油价：92#=${d.optString("z92")}  95#=${d.optString("z95")}  98#=${d.optString("z98")}  0#=${d.optString("z0")}")
                    } else {
                        val d = lg("https://api.vvhan.com/api/oilPrice?province=${e(prov)}")?.let { JSONObject(it).optJSONObject("data") } ?: return@withContext ToolResult("查不到油价（配 mxnzp key 更稳）", isError = true)
                        ToolResult("⛽ ${prov}油价：92#=${d.optString("92")}  95#=${d.optString("95")}  98#=${d.optString("98")}  0#=${d.optString("0")}")
                    }
                }
                "garbage" -> {
                    if (!mxnzp) return@withContext needKey(); if (q.isBlank()) return@withContext ToolResult("请提供物品名", isError = true)
                    val d = mx("rubbish/type?name=${e(q)}")?.let { JSONObject(it).optJSONObject("data") } ?: return@withContext ToolResult("查不到", isError = true)
                    val cat = d.optJSONObject("aim")?.optString("goodsType")?.takeIf { it.isNotBlank() } ?: d.optString("name").takeIf { it.isNotBlank() } ?: "未知"
                    ToolResult("🗑️ $q：$cat")
                }
                "express" -> {
                    if (!mxnzp) return@withContext needKey(); if (q.isBlank()) return@withContext ToolResult("请提供快递单号", isError = true)
                    val code = params.optString("company", "").ifBlank { "AUTO" }
                    val list = mx("express/query?shipperCode=$code&logisticCode=${e(q)}")?.let { JSONObject(it).optJSONObject("data")?.optJSONArray("list") }
                    if (list == null || list.length() == 0) return@withContext ToolResult("暂无物流信息（可能需指定 company 快递公司代码）")
                    ToolResult("📦 $q\n" + (0 until minOf(list.length(), 6)).joinToString("\n") { val o = list.optJSONObject(it); "${o.optString("time")} ${o.optString("content")}" })
                }
                "dream" -> {
                    if (!mxnzp) return@withContext needKey(); if (q.isBlank()) return@withContext ToolResult("请提供关键词", isError = true)
                    val list = mx("dream/query?keyword=${e(q)}")?.let { JSONObject(it).optJSONObject("data")?.optJSONArray("list") }
                    ToolResult("🔮 梦见「$q」：" + ((0 until minOf(list?.length() ?: 0, 3)).joinToString("；") { list!!.optJSONObject(it).optString("desc").take(80) }).ifBlank { "无解释" })
                }
                "phone" -> {
                    if (!mxnzp) return@withContext needKey(); if (q.isBlank()) return@withContext ToolResult("请提供手机号", isError = true)
                    val d = mx("mobile/query?number=${e(q)}")?.let { JSONObject(it).optJSONObject("data") } ?: return@withContext ToolResult("查不到", isError = true)
                    ToolResult("📱 $q：${d.optString("province")}${d.optString("city")} ${d.optString("company")}")
                }
                "hotnews" -> {
                    // 60s.viki.moe（开源、Cloudflare Workers、免费无 key）各平台实时热榜
                    val map = linkedMapOf("微博" to "weibo", "知乎" to "zhihu", "抖音" to "douyin", "头条" to "toutiao", "今日头条" to "toutiao", "b站" to "bili", "哔哩" to "bili", "bilibili" to "bili", "36氪" to "36kr", "掘金" to "juejin", "豆瓣" to "douban", "虎扑" to "hupu")
                    val slug = map.entries.firstOrNull { q.contains(it.key, ignoreCase = true) }?.value ?: q.ifBlank { "weibo" }.lowercase()
                    val j = lg("https://60s.viki.moe/v2/$slug") ?: return@withContext ToolResult("查不到热点（支持：微博/知乎/抖音/头条/B站/36氪/掘金/豆瓣/虎扑）", isError = true)
                    val arr = JSONObject(j).optJSONArray("data")
                    if (arr == null || arr.length() == 0) return@withContext ToolResult("暂无热点数据（平台名可能不对，支持：微博/知乎/抖音/头条/B站/36氪/掘金/豆瓣/虎扑）")
                    val label = map.entries.firstOrNull { it.value == slug }?.key ?: slug
                    ToolResult("🔥 ${label}热榜\n" + (0 until minOf(arr.length(), 12)).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null   // 某行不是对象就跳过，别拖垮整张榜
                        val hot = o.optString("hot_value", "").takeIf { it.isNotBlank() }
                        "${i + 1}. ${o.optString("title")}" + (if (hot != null) "  🔥$hot" else "")
                    }.joinToString("\n"))
                }
                else -> ToolResult("未知 type", isError = true)
            }
        } catch (e: Exception) { ToolResult("查询失败: ${e.message}", isError = true) }
    }
}
