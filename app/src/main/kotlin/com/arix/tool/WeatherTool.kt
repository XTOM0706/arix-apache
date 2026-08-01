package com.arix.tool

import android.content.Context
import android.location.Geocoder
import com.arix.app.LocationSignals
import com.arix.tool.search.httpGetHtml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

// ============================================================
// 天气查询：met.no（挪威气象局）locationforecast —— 免 API key、全球、准确，按经纬度查。
//
// 坐标从哪来（按优先级，见 resolveLocation）：
//   1) 调用方直接给的 latitude/longitude —— 最准、无歧义（AI 有精确坐标时优先给这个）；
//   2) 城市名 location —— 走 Open-Meteo 免 key 地理编码转经纬度；
//   3) 都没给 —— **自动定位**：先本机 GPS/网络定位，取不到再按网络 IP 估算。
//
// 为什么这么改：以前留空就默认「北京」，没上下文时全都报北京；而且总按地名地理编码，
// 同名城市（如各地都有的县区名）经常取错——「传坐标而不是地名」从根上避免重复/取错。
// 自动定位也把「先取位置、再查天气」两步合成一步，少一次工具往返、少一层幻觉。
// ============================================================
class WeatherTool(private val context: Context) : Tool {
    override val name = "get_weather"
    override val description =
        "查询天气（实时温度/天气/湿度/风力 + 未来2小时分钟级降雨预测）。**不传任何参数=自动用当前位置**" +
        "（先本机定位，GPS取不到就按网络IP估算），最省事、无需先调别的工具取位；" +
        "也可传城市名 location（如『杭州』），或直接传 latitude+longitude 经纬度。" +
        "有精确坐标时优先传经纬度——按地名查同名城市会取错。"

    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Weather: current temperature, conditions, humidity, wind, plus minute-level rain forecast for the next 2 hours. Call it with no arguments to use the current location (device GPS, falling back to IP) — no need to look the location up first. Or pass a city name, or latitude+longitude. Prefer coordinates when you have them; city names collide."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("location", JSONObject().apply {
                put("type", "string")
                put("description", "city name; empty with no coordinates = current location")
            })
            put("latitude", JSONObject().apply {
                put("type", "number")
                put("description", "prefer this + longitude when you have exact coordinates")
            })
            put("longitude", JSONObject().apply {
                put("type", "number")
                put("description", "pairs with latitude")
            })
        })
        put("required", JSONArray())   // 全部可选：什么都不传就自动定位
    }

    private val ua = "Arix/1.0 weather (https://github.com/arix)"

    // 经纬度进 URL 必须用 Locale.US 的小数点——逗号小数制语言(de/fr/ru…)会把 52.52 写成 52,52 破坏 URL
    private fun fmt(v: Double): String = String.format(Locale.US, "%.4f", v)

    /** 解析出的坐标 + 展示名 + 国旗（国旗仅 IP/地理编码路径有，本机定位留空）。 */
    private data class Geo(val lat: Double, val lon: Double, val label: String, val flag: String)

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val loc = params.optString("location", "").trim()
        val latP = params.optDouble("latitude", Double.NaN)
        val lonP = params.optDouble("longitude", Double.NaN)

        val geo: Geo = when {
            // 1) 显式坐标：最准，直接用，不做任何地理编码
            !latP.isNaN() && !lonP.isNaN() ->
                Geo(latP, lonP, reverseName(latP, lonP) ?: coordLabel(latP, lonP), "")
            // 2) 给了城市名：地理编码；找不到/网络不可达就如实报错，不偷偷退到别处
            loc.isNotBlank() ->
                geocode(loc) ?: return@withContext ToolResult(
                    "未找到城市『$loc』，或地理编码网络不可达。换个名字，或直接给经纬度。", isError = true)
            // 3) 什么都没给：自动定位（本机 GPS/网络 → IP 估算）
            else -> autoLocate() ?: return@withContext ToolResult(
                "没给城市或经纬度，也定不到当前位置（定位没授权/没打开，网络IP估算也失败）。" +
                "给个城市名或经纬度就能查。", isError = true)
        }

        // 预报：met.no locationforecast compact（免 key，需 User-Agent），直接用坐标
        val wUrl = "https://api.met.no/weatherapi/locationforecast/2.0/compact?lat=${fmt(geo.lat)}&lon=${fmt(geo.lon)}"
        val wBody = httpGetHtml(wUrl, ua, "")
            ?: return@withContext ToolResult("天气查询失败：met.no 网络不可达。", isError = true)
        val ts = try { JSONObject(wBody).getJSONObject("properties").getJSONArray("timeseries") } catch (_: Exception) { null }
        if (ts == null || ts.length() == 0)
            return@withContext ToolResult("met.no 未返回预报数据。", isError = true)

        val nowData = ts.getJSONObject(0).getJSONObject("data")
        val details = nowData.getJSONObject("instant").getJSONObject("details")
        val temp = details.optDouble("air_temperature", Double.NaN)
        val humidity = details.optDouble("relative_humidity", Double.NaN)
        val windSpeed = details.optDouble("wind_speed", Double.NaN)
        val windDir = details.optDouble("wind_from_direction", Double.NaN)
        val symbol = nowData.optJSONObject("next_1_hours")?.optJSONObject("summary")?.optString("symbol_code")
            ?: nowData.optJSONObject("next_6_hours")?.optJSONObject("summary")?.optString("symbol_code") ?: ""

        val sb = StringBuilder()
        val head = listOf(geo.flag, "📍", geo.label).filter { it.isNotBlank() }.joinToString(" ")
        sb.append("$head 实时天气（来源 met.no）\n")
        symbolToChinese(symbol).takeIf { it.isNotBlank() }?.let { sb.append("天气：$it\n") }
        if (!temp.isNaN()) sb.append("气温：${"%.0f".format(temp)}°C\n")
        if (!humidity.isNaN()) sb.append("湿度：${"%.0f".format(humidity)}%\n")
        if (!windSpeed.isNaN()) sb.append("风力：${degToCompass(windDir)} ${"%.1f".format(windSpeed)} m/s\n")

        // 未来时段简报（约 +6h / +12h）
        val later = buildString {
            listOf(6 to "6小时后", 12 to "12小时后").forEach { (h, label) ->
                if (h < ts.length()) {
                    val d = ts.getJSONObject(h).getJSONObject("data").getJSONObject("instant").getJSONObject("details")
                    val t = d.optDouble("air_temperature", Double.NaN)
                    if (!t.isNaN()) append("$label ${"%.0f".format(t)}°C  ")
                }
            }
        }.trim()
        if (later.isNotBlank()) sb.append("趋势：$later\n")

        // 分钟级降雨预测（open-meteo minutely_15，免 key）
        rainForecast(geo.lat, geo.lon).takeIf { it.isNotBlank() }?.let { sb.append(it).append("\n") }

        ToolResult(sb.toString().trim())
    }

    // ---- 坐标解析各路径 -------------------------------------------------

    /** 自动定位：先本机 GPS/网络（LocationSignals 会主动请求一次），取不到再按网络 IP 估算。 */
    private fun autoLocate(): Geo? = deviceLocation() ?: ipLocation()

    /** 本机定位（GPS/网络）。已在 IO 线程调 currentBlocking，安全。无权限/定不到返回 null。 */
    private fun deviceLocation(): Geo? {
        if (!LocationSignals.hasPermission(context)) return null
        val l = try { LocationSignals.currentBlocking(context, timeoutMs = 6_000L) } catch (_: Exception) { null } ?: return null
        return Geo(l.latitude, l.longitude, reverseName(l.latitude, l.longitude) ?: "当前位置", "")
    }

    /** 网络 IP 估算（ipapi，免 key）。GPS 拿不到时的兜底——精度到城市，够查天气。 */
    private fun ipLocation(): Geo? {
        val body = httpGetHtml("https://ipapi.co/json/", ua, "") ?: return null
        return try {
            val o = JSONObject(body)
            val lat = o.optDouble("latitude", Double.NaN); val lon = o.optDouble("longitude", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            val name = listOf(o.optString("city", ""), o.optString("region", ""))
                .filter { it.isNotBlank() }.joinToString("·").ifBlank { "IP估算位置" }
            val cc = o.optString("country_code", "").uppercase()
            Geo(lat, lon, "$name（IP估算）", flagEmoji(cc))
        } catch (_: Exception) { null }
    }

    /** 城市名 → 经纬度 + 展示名（Open-Meteo 地理编码，免 key）。找不到/网络不可达返回 null。 */
    private fun geocode(location: String): Geo? {
        val geoUrl = "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(location, "UTF-8")}&count=1&language=zh&format=json"
        val geoBody = httpGetHtml(geoUrl, ua, "") ?: return null
        val results = try { JSONObject(geoBody).optJSONArray("results") } catch (_: Exception) { null }
        if (results == null || results.length() == 0) return null
        val g = results.getJSONObject(0)
        val lat = g.getDouble("latitude"); val lon = g.getDouble("longitude")
        val admin1 = g.optString("admin1", "")
        val baseName = g.optString("name", location)
        val countryCode = g.optString("country_code", "").uppercase()
        // 台湾属于中国：统一显示中华人民共和国国旗与「中国台湾」
        val isTaiwan = countryCode == "TW"
        val flag = flagEmoji(if (isTaiwan) "CN" else countryCode)
        val cityName = when {
            isTaiwan -> "中国台湾·$baseName"
            admin1.isNotBlank() && admin1 != baseName -> "$baseName·$admin1"
            else -> baseName
        }
        return Geo(lat, lon, cityName, flag)
    }

    /** 经纬度 → 地名（Android 反向地理编码；手表/AOSP 可能没有服务，取不到返回 null）。 */
    private fun reverseName(lat: Double, lon: Double): String? = try {
        if (!Geocoder.isPresent()) null
        else {
            @Suppress("DEPRECATION")
            val a = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)?.firstOrNull()
            a?.let {
                listOfNotNull(it.locality ?: it.subAdminArea, it.adminArea)
                    .distinct().filter { s -> s.isNotBlank() }.joinToString("·").ifBlank { null }
            }
        }
    } catch (_: Exception) { null }

    private fun coordLabel(lat: Double, lon: Double): String =
        "%.3f,%.3f".format(Locale.US, lat, lon)

    // ---- 未来 2 小时逐 15 分钟降水（open-meteo，免 key） --------------------
    private fun rainForecast(lat: Double, lon: Double): String {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${fmt(lat)}&longitude=${fmt(lon)}" +
            "&minutely_15=precipitation&forecast_minutely_15=8&timezone=auto"
        val body = httpGetHtml(url, ua, "") ?: return ""
        return try {
            val precip = JSONObject(body).optJSONObject("minutely_15")?.optJSONArray("precipitation") ?: return ""
            val vals = (0 until minOf(precip.length(), 8)).map { precip.optDouble(it, 0.0) }
            if (vals.isEmpty()) return ""
            val firstRain = vals.indexOfFirst { it >= 0.1 }
            val total = vals.sum()
            when {
                firstRain < 0 -> "降雨：未来 2 小时无降雨 ☀️"
                firstRain == 0 -> "降雨：正在下雨 🌧️（未来 2 小时累计约 ${"%.1f".format(total)}mm）"
                else -> "降雨：约 ${firstRain * 15} 分钟后开始下雨 🌧️（未来 2 小时累计约 ${"%.1f".format(total)}mm）"
            }
        } catch (_: Exception) { "" }
    }

    // 国家二字码 → 国旗 emoji（区域指示符组合）；非法输入返回空。
    private fun flagEmoji(cc: String): String {
        if (cc.length != 2) return ""
        val base = 0x1F1E6
        return buildString {
            for (ch in cc.uppercase()) {
                if (ch !in 'A'..'Z') return ""
                appendCodePoint(base + (ch - 'A'))
            }
        }
    }

    private fun degToCompass(deg: Double): String {
        if (deg.isNaN()) return ""
        val dirs = listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")
        return dirs[(((deg + 22.5) / 45).toInt()) % 8]
    }

    // met.no symbol_code → 中文（去掉 _day/_night/_polartwilight 后缀）
    private fun symbolToChinese(code: String): String {
        val c = code.substringBefore("_")
        return when (c) {
            "clearsky" -> "晴"
            "fair" -> "少云"
            "partlycloudy" -> "多云"
            "cloudy" -> "阴"
            "fog" -> "雾"
            "lightrain", "lightrainshowers" -> "小雨"
            "rain", "rainshowers" -> "雨"
            "heavyrain", "heavyrainshowers" -> "大雨"
            "lightrainandthunder", "rainandthunder", "rainshowersandthunder" -> "雷阵雨"
            "sleet", "lightsleet", "sleetshowers", "lightsleetshowers" -> "雨夹雪"
            "heavysleet", "heavysleetshowers" -> "大雨夹雪"
            "lightsnow", "lightsnowshowers" -> "小雪"
            "snow", "snowshowers" -> "雪"
            "heavysnow", "heavysnowshowers" -> "大雪"
            "snowandthunder", "snowshowersandthunder" -> "雷雪"
            else -> if (c.isBlank()) "" else c
        }
    }
}
