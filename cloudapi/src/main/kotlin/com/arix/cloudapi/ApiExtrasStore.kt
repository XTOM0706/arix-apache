package com.arix.cloudapi

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 每个模型配置的「进阶参数」自带存储——**不动 CloudApiConfig / Room 实体结构**，
 * 用一份独立的 SharedPreferences 旁挂在既有配置之外，按 `baseUrl|model` 归档：
 *   · body      —— 自定义请求体模板(JSON)，合并进 /chat/completions 请求体
 *                  （下发 provider 特有参数：enable_thinking / safe_mode / provider 路由…）
 *   · webSearch —— 供应商内置联网搜索透传开关
 *
 * 为何这样存：主聊天发送路径（ChatScreen）只按 8 个固定字段构造 CloudApiConfig，
 * 不经过 customHeaders，也无法给它加新字段（会牵动 DB/实体/多处共享文件）。因此让
 * CloudApiClient **自己按 baseUrl+model 反查**这份旁挂存储。CloudApiClient 手上没有
 * Context，故这里维护一份进程内内存镜像 [mem]：ConfigPage 打开时 bind() 从磁盘装载、
 * 保存时 set() 同时写盘+更新镜像，读取端(CloudApiClient)只走内存、无需 Context。
 *
 * 局限：冷启动后若「从未打开过配置页就直接聊天」，镜像尚未装载→模板/开关当次不生效
 * （下次进过配置页即恢复）。余额查询完全在配置页内发生，不受此局限影响。
 */
object ApiExtrasStore {
    private const val PREFS = "xtom_api_extras"

    @Volatile private var app: Context? = null
    private val mem = ConcurrentHashMap<String, String>()   // key -> extras json string
    @Volatile private var loaded = false

    fun keyOf(baseUrl: String, model: String): String =
        baseUrl.trim().trimEnd('/') + "|" + model.trim()

    /** ConfigPage 打开/编辑时调用：缓存 application context 并把磁盘装入内存镜像。 */
    fun bind(context: Context) {
        if (app == null) app = context.applicationContext
        if (!loaded) load()
    }

    private fun load() {
        try {
            app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.all?.forEach { (k, v) ->
                if (v is String) mem[k] = v
            }
        } catch (_: Exception) {}
        loaded = true
    }

    private fun get(baseUrl: String, model: String): JSONObject? {
        val raw = mem[keyOf(baseUrl, model)] ?: return null
        return try { JSONObject(raw) } catch (_: Exception) { null }
    }

    /** 该配置的自定义请求体模板（无则 null）。CloudApiClient 走内存镜像，无需 Context。 */
    fun bodyTemplate(baseUrl: String, model: String): JSONObject? =
        get(baseUrl, model)?.optJSONObject("body")

    /** 该配置是否开启供应商内置联网搜索透传。 */
    fun webSearch(baseUrl: String, model: String): Boolean =
        get(baseUrl, model)?.optBoolean("webSearch", false) ?: false

    /**
     * 用户**显式指定**的聊天协议（无则 null = 交给 [ChatProtocol.detect] 按域名认）。
     *
     * 给中转站/自建代理用：那些域名里没有 `api.anthropic.com` 这类关键字，自动判定认不出来，
     * 但它们背后可能就是原生 Anthropic/Gemini 端点。
     */
    fun protocolOverride(baseUrl: String, model: String): ChatProtocol? =
        ChatProtocol.fromString(get(baseUrl, model)?.optString("protocol", "")?.takeIf { it.isNotBlank() })

    /**
     * 单独存协议选择，**不动**同一条记录里的 body/webSearch。
     *
     * 之所以不并进 [set]：那个函数的签名被配置页按 4 个参数调着，而协议下拉在页面上是另一处控件；
     * 并进去就得让每个调用方都同时知道另外两项当前的值，漏一个就把用户的模板悄悄清空。
     * 传 null = 恢复自动判定。
     */
    fun setProtocol(context: Context, baseUrl: String, model: String, protocol: ChatProtocol?) {
        bind(context)
        val key = keyOf(baseUrl, model)
        val cur = get(baseUrl, model) ?: JSONObject()
        if (protocol == null) cur.remove("protocol") else cur.put("protocol", protocol.name)
        // 三项都空了就整条删掉，别在 prefs 里留一堆 `{}`
        if (!cur.has("body") && !cur.optBoolean("webSearch", false) && !cur.has("protocol")) {
            mem.remove(key)
            try { app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(key)?.apply() } catch (_: Exception) {}
            return
        }
        val s = cur.toString()
        mem[key] = s
        try { app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(key, s)?.apply() } catch (_: Exception) {}
    }

    /** 供 ConfigPage 回填编辑框：模板的漂亮字符串（无则空串）。 */
    fun bodyTemplateText(baseUrl: String, model: String): String {
        val b = bodyTemplate(baseUrl, model) ?: return ""
        return try { b.toString(2) } catch (_: Exception) { "" }
    }

    /**
     * 保存该配置的进阶参数。bodyTemplateJson 非空但非法 JSON 时返回错误信息且不落盘；
     * body 与 webSearch 皆空则整条删除。成功返回 null。
     */
    fun set(context: Context, baseUrl: String, model: String, bodyTemplateJson: String, webSearch: Boolean): String? {
        bind(context)
        val key = keyOf(baseUrl, model)
        val bodyTrim = bodyTemplateJson.trim()
        val bodyObj: JSONObject? = if (bodyTrim.isBlank()) null else try {
            JSONObject(bodyTrim)
        } catch (e: Exception) {
            return "请求体模板不是合法 JSON：${e.message}"
        }
        // 协议选择是另一处控件存的，这里必须**原样留着**——否则用户在进阶参数里点一次保存，
        // 就把他选的「Anthropic 原生」悄悄清回自动判定，表现是「昨天还好好的今天格式发错了」。
        val keepProtocol = get(baseUrl, model)?.optString("protocol", "")?.takeIf { it.isNotBlank() }
        if (bodyObj == null && !webSearch && keepProtocol == null) {
            mem.remove(key)
            try { app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.remove(key)?.apply() } catch (_: Exception) {}
            return null
        }
        val extras = JSONObject()
        if (bodyObj != null) extras.put("body", bodyObj)
        if (webSearch) extras.put("webSearch", true)
        keepProtocol?.let { extras.put("protocol", it) }
        val s = extras.toString()
        mem[key] = s
        try { app?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putString(key, s)?.apply() } catch (_: Exception) {}
        return null
    }
}
