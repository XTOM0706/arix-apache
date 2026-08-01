package com.arix.tool

import android.content.Context
import com.arix.app.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// ============================================================
// Home Assistant 智能家居 —— 一个工具多用，直连 HA 官方 REST API（纯 HttpURLConnection，无第三方代码）：
//   list  = GET /api/states            列/查设备与实体状态（可按 domain/关键词过滤）
//   state = GET /api/states/<entity>   查单个实体详情（含全部属性）
//   call  = POST /api/services/<domain>/<service>  调用服务开关/控制设备（body {entity_id, ...}）
//   config= 保存 HA 基址 + 长效访问 token（正经入口在 设置→工具密钥→Home Assistant；这个 action 只是
//           「用户懒得翻设置、直接口述给 AI」时的便道——长效 token 是家庭网关的长期凭证，别主动索要）
// 鉴权：Authorization: Bearer <长效访问令牌>。配置存自带 prefs（HaPrefs）。
// ============================================================

/** HA 连接配置：基址(base url) + 长效访问 token，存自带 SharedPreferences。 */
object HaPrefs {
    private const val P = "xtom_home_assistant"
    private fun p(c: Context) = c.getSharedPreferences(P, Context.MODE_PRIVATE)
    /** 基址，如 http://homeassistant.local:8123 或 http://192.168.1.10:8123（自动去掉末尾斜杠）。 */
    fun baseUrl(c: Context) = (p(c).getString("base_url", "") ?: "").trim().trimEnd('/')
    /** 长效访问令牌（Home Assistant → 个人资料 → 长期访问令牌 里创建）。 */
    fun token(c: Context) = (p(c).getString("token", "") ?: "").trim()
    fun set(c: Context, baseUrl: String, token: String) = p(c).edit()
        .putString("base_url", baseUrl.trim().trimEnd('/'))
        .putString("token", token.trim())
        .apply()
    fun isConfigured(c: Context) = baseUrl(c).isNotBlank() && token(c).isNotBlank()
}

class HomeAssistantTool(private val context: Context) : Tool {
    override val name = "home_assistant"
    override val description = tr("Home Assistant 智能家居控制（一个工具多用）：list=列/查设备实体状态（query 可按 domain 或关键词过滤，如 light/switch/climate/『客厅』）；state=查单个实体详情（传 entity_id）；call=调用服务开关/控制设备（传 entity_id + service，如 turn_on/turn_off/toggle，可选 data 传亮度/温度等额外参数）；config=保存 HA 基址与长效访问令牌。需先在设置或用 config 配好 HA 地址与 token。")

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("list", "state", "call", "config")))
                put("description", tr("list=列/查所有实体状态；state=查单个实体详情；call=调用服务控制设备；config=保存基址+token"))
            })
            put("entity_id", JSONObject().apply {
                put("type", "string")
                put("description", tr("实体 ID，如 light.living_room、switch.fan、climate.bedroom（state 必填；call 必填）"))
            })
            put("service", JSONObject().apply {
                put("type", "string")
                put("description", tr("call 的服务名，如 turn_on / turn_off / toggle / set_temperature。留空默认 toggle"))
            })
            put("domain", JSONObject().apply {
                put("type", "string")
                put("description", tr("call 的服务域，如 light / switch / climate。留空则自动取 entity_id 的前缀"))
            })
            put("data", JSONObject().apply {
                put("type", "object")
                put("description", tr("call 的额外服务参数（可选），如 {\"brightness\":200} 或 {\"temperature\":24}，会与 entity_id 一起提交"))
            })
            put("query", JSONObject().apply {
                put("type", "string")
                put("description", tr("list 的过滤（可选）：domain 名如 light/switch/sensor，或对实体 ID / 名称的关键词模糊匹配"))
            })
            put("base_url", JSONObject().apply {
                put("type", "string")
                put("description", tr("config 用：HA 基址，如 http://homeassistant.local:8123 或 http://192.168.1.10:8123"))
            })
            put("token", JSONObject().apply {
                put("type", "string")
                put("description", tr("config 用：HA 长效访问令牌（个人资料页创建）"))
            })
        })
        put("required", JSONArray(listOf("action")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = params.optString("action").trim()

        // config 不需要已配置即可执行
        if (action == "config") {
            val base = params.optString("base_url").trim()
            val token = params.optString("token").trim()
            if (base.isBlank() || token.isBlank())
                return@withContext ToolResult(tr("配置失败：base_url 与 token 都要填。base_url 如 http://192.168.1.10:8123，token 在 HA 个人资料页创建长效访问令牌。"), isError = true)
            if (!base.startsWith("http://") && !base.startsWith("https://"))
                return@withContext ToolResult(tr("base_url 需以 http:// 或 https:// 开头，如 http://192.168.1.10:8123"), isError = true)
            HaPrefs.set(context, base, token)
            return@withContext ToolResult(tr("已保存 Home Assistant 配置：") + HaPrefs.baseUrl(context))
        }

        if (!HaPrefs.isConfigured(context))
            return@withContext ToolResult(tr("尚未配置 Home Assistant。请用户到「设置 → 工具密钥 → Home Assistant」填入 HA 地址与长效访问令牌（令牌在 HA 的「个人资料 → 长效访问令牌」创建）；或者用户愿意直接告诉你地址和令牌的话，你也可以用 action=config 传 base_url + token 帮他存。"), isError = true)

        val base = HaPrefs.baseUrl(context)
        val token = HaPrefs.token(context)

        try {
            when (action) {
                "list" -> {
                    val body = httpGet("$base/api/states", token)
                        ?: return@withContext netError()
                    val arr = try { JSONArray(body) } catch (_: Exception) {
                        return@withContext ToolResult(tr("HA 返回的数据无法解析，请检查基址是否为 HA 地址。"), isError = true)
                    }
                    val q = params.optString("query").trim().lowercase()
                    val sb = StringBuilder()
                    var shown = 0
                    val total = arr.length()
                    for (i in 0 until total) {
                        val o = arr.optJSONObject(i) ?: continue
                        val eid = o.optString("entity_id")
                        val friendly = o.optJSONObject("attributes")?.optString("friendly_name") ?: ""
                        if (q.isNotBlank()) {
                            val dom = eid.substringBefore('.')
                            val hit = dom == q || eid.lowercase().contains(q) || friendly.lowercase().contains(q)
                            if (!hit) continue
                        }
                        val st = o.optString("state")
                        sb.append("• ").append(eid)
                        if (friendly.isNotBlank() && friendly != eid) sb.append(" (").append(friendly).append(")")
                        sb.append(" = ").append(st).append("\n")
                        shown++
                        if (shown >= 200) { sb.append(tr("…（结果过多已截断，请用 query 缩小范围）\n")); break }
                    }
                    if (shown == 0)
                        return@withContext ToolResult(if (q.isBlank()) tr("HA 没有任何实体。") else tr("没有匹配「") + params.optString("query").trim() + tr("」的实体。共 ") + total + tr(" 个实体。"))
                    ToolResult("🏠 Home Assistant " + tr("实体状态（显示 ") + shown + "/" + total + tr(" 个）：\n") + sb.toString().trimEnd())
                }
                "state" -> {
                    val eid = params.optString("entity_id").trim()
                    if (eid.isBlank()) return@withContext ToolResult(tr("请提供 entity_id，如 light.living_room"), isError = true)
                    val body = httpGet("$base/api/states/${enc(eid)}", token)
                        ?: return@withContext netError()
                    val o = try { JSONObject(body) } catch (_: Exception) { null }
                        ?: return@withContext ToolResult(tr("未找到实体「") + eid + tr("」，或它不存在。"), isError = true)
                    val friendly = o.optJSONObject("attributes")?.optString("friendly_name") ?: ""
                    val attrs = o.optJSONObject("attributes")
                    val sb = StringBuilder()
                    sb.append("🏠 ").append(eid)
                    if (friendly.isNotBlank() && friendly != eid) sb.append(" (").append(friendly).append(")")
                    sb.append("\n").append(tr("状态：")).append(o.optString("state")).append("\n")
                    o.optString("last_changed").takeIf { it.isNotBlank() }?.let { sb.append(tr("最后变化：")).append(it).append("\n") }
                    if (attrs != null && attrs.length() > 0) {
                        sb.append(tr("属性：\n"))
                        val keys = attrs.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            if (k == "friendly_name") continue
                            sb.append("  ").append(k).append(": ").append(attrs.opt(k)).append("\n")
                        }
                    }
                    ToolResult(sb.toString().trimEnd())
                }
                "call" -> {
                    val eid = params.optString("entity_id").trim()
                    if (eid.isBlank()) return@withContext ToolResult(tr("请提供 entity_id，如 light.living_room"), isError = true)
                    val service = params.optString("service").trim().ifBlank { "toggle" }
                    val domain = params.optString("domain").trim().ifBlank { eid.substringBefore('.') }
                    if (domain.isBlank()) return@withContext ToolResult(tr("无法确定服务域(domain)，请传 domain 或用带前缀的 entity_id。"), isError = true)
                    val payload = JSONObject().apply {
                        put("entity_id", eid)
                        // 合并额外参数（亮度/温度等）
                        params.optJSONObject("data")?.let { d ->
                            val ks = d.keys()
                            while (ks.hasNext()) { val k = ks.next(); put(k, d.opt(k)) }
                        }
                    }
                    val body = httpPost("$base/api/services/${enc(domain)}/${enc(service)}", token, payload.toString())
                        ?: return@withContext netError()
                    // HA 成功返回受该服务影响的实体状态数组（可能为空）
                    val changed = try { JSONArray(body).length() } catch (_: Exception) { -1 }
                    val tail = if (changed > 0) tr("（受影响 ") + changed + tr(" 个实体）") else ""
                    ToolResult("✅ " + tr("已调用 ") + "$domain.$service" + tr(" → ") + eid + tail)
                }
                else -> ToolResult(tr("未知 action：") + action, isError = true)
            }
        } catch (e: Exception) {
            ToolResult(tr("Home Assistant 请求失败：") + (e.message ?: e.toString()), isError = true)
        }
    }

    private fun netError() = ToolResult(tr("连不上 Home Assistant，或令牌无效。请检查：①手机与 HA 在同一网络且基址可达；②长效访问令牌正确；③基址含端口（默认 8123）。"), isError = true)

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun httpGet(url: String, token: String): String? = request(url, token, "GET", null)
    private fun httpPost(url: String, token: String, body: String): String? = request(url, token, "POST", body)

    private fun request(url: String, token: String, method: String, body: String?): String? = try {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10000; c.readTimeout = 15000
        c.setRequestProperty("Authorization", "Bearer $token")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("User-Agent", "Arix-HomeAssistant/1.0")
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = c.responseCode
        if (code in 200..299) {
            val s = c.inputStream.bufferedReader().use { it.readText() }
            c.disconnect(); s
        } else {
            c.errorStream?.close(); c.disconnect(); null
        }
    } catch (_: Exception) { null }
}
