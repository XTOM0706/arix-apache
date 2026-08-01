package com.arix.tool

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

// ============================================================
// HTTP 端点 MCP。与 stdio 那条（StdioMcpClient）并列。
//
// —— 现状（2026-07-29 起）：**有会话**。
//  * [rpc] 是本文件对外唯一的 RPC 入口：首次访问某端点先发 `initialize`、收下 `Mcp-Session-Id`、
//    补一条 `notifications/initialized`，之后每条请求都带会话；404 判为会话失效，清掉重握一次。
//    响应体裸 JSON 与 SSE 帧（`data: {...}`）两种都能解析。
//  * **握不上就静默降级回裸 POST**——无状态 server 照常工作。这条底线不能破：
//    补握手的目的是把原来必然失败的那半边救回来，不是把原来能用的弄坏。
//  * 2026-07-27 时这里是完全无会话的（从不发 initialize），于是「支持 MCP」对只认
//    「先握手再调用」的 Streamable-HTTP server 是假的。stdio 那条（StdioMcpClient）一直是对的。
//  * 连接复用：[post] 读完就关流、**不调 disconnect()**，别再加回去——那会明着废掉 OkHttp 连接池，
//    同一台 server 连调十次就是十次 TLS 握手。
//  * 发现路径（`tools/list`）原来在 `OperitCompat.McPToolKt.discoverMcpTools` 里另有一份裸 POST 实现，
//    已收敛到本文件的 [rpc]。`transport` 字段仍然没人读（写着 sse，实际永远是 POST），留待清理。
// ============================================================
data class McpServerConfig(
    val name: String,
    val url: String,
    /** ⚠ 没有任何代码读它。实际传输恒为「一次调用一次 POST」，不是 SSE。 */
    val transport: String = "sse",
    // 认证 header（如 Authorization: Bearer ...），由 parseAuthHeaders() 从配置 json 解析
    val authHeaders: Map<String, String> = emptyMap()
)

class McpTool internal constructor(
    name: String,
    override val description: String,
    override val parameters: JSONObject,
    private val serverUrl: String,
    private val toolName: String,
    // 每个 MCP 端点自带的认证 header（Bearer/自定义 key），发请求时带上
    private val authHeaders: Map<String, String> = emptyMap()
) : Tool {
    /**
     * 注册名**一律加 `mcp_` 前缀**（stdio 那条早就这么做，见 StdioMcpClient.listTools）。
     *
     * 不加会怎样：`ToolManager.register` 是 `tools[name] = tool`，**同名直接覆盖**。而实际在跑的发现路径
     * （`OperitCompat.McPToolKt.discoverMcpTools`）把 server 报的名字原样当注册名——于是一个第三方 MCP
     * 端点只要把自己的工具叫 `shell` / `file_op` / `web_search`，就能顶掉同名内置工具，还继承用户早先
     * 给那个名字设下的放行策略（权限键就是工具名）。名字是对面报的，等于让它自己填「我是谁」。
     * 前缀在这里兜底：无论哪条发现路径、无论调用方是否记得加，都进不了内置工具的命名空间。
     * 发给 server 的仍是原始 [toolName]，协议不受影响。
     */
    override val name: String =
        if (name.startsWith("mcp_")) name else "mcp_" + name.replace(" ", "_")

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", System.currentTimeMillis())
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", params)
                })
            }
            val json = sendMcpRequest(serverUrl, body, authHeaders)
            try {
                val result = json.optJSONObject("result") ?: json
                val content = result.optJSONArray("content")?.let { arr ->
                    (0 until arr.length()).map { i ->
                        arr.optJSONObject(i)?.optString("text", "") ?: ""
                    }
                }?.joinToString("\n") ?: result.optString("text", json.toString())
                ToolResult(content)
            } catch (_: Exception) {
                ToolResult(json.toString())
            }
        } catch (e: Exception) {
            // MCP 端点连不上/握手失败是"配了没反应"的头号来源，必须留痕
            com.arix.app.AppLog.e("MCP", "调用 $toolName 失败（$serverUrl）", e)
            ToolResult("MCP连接失败: ${e.message}", isError = true)
        }
    }

    companion object {
        suspend fun discoverTools(
            serverUrl: String,
            authHeaders: Map<String, String> = emptyMap()
        ): List<McpTool> = withContext(Dispatchers.IO) {
            try {
                val body = JSONObject().apply {
                    put("jsonrpc", "2.0")
                    put("id", 1)
                    put("method", "tools/list")
                }
                val json = sendMcpRequest(serverUrl, body, authHeaders)
                val tools = mutableListOf<McpTool>()
                val toolsArray = json.optJSONObject("result")?.optJSONArray("tools")
                    ?: json.optJSONArray("tools")
                if (toolsArray != null) {
                    for (i in 0 until toolsArray.length()) {
                        val toolJson = toolsArray.optJSONObject(i) ?: continue
                        val name = toolJson.optString("name", "")
                        val desc = toolJson.optString("description", "")
                        val params = toolJson.optJSONObject("inputSchema")
                            ?: toolJson.optJSONObject("parameters")
                            ?: JSONObject()
                        if (name.isNotBlank()) {
                            tools.add(McpTool(
                                name = "mcp_${name.replace(" ", "_")}",
                                description = "[MCP] $desc",
                                parameters = params,
                                serverUrl = serverUrl,
                                toolName = name,
                                authHeaders = authHeaders
                            ))
                        }
                    }
                }
                tools
            } catch (_: Exception) { emptyList() }
        }

        /**
         * 从 MCP 配置 json 解析出认证 header（HTTP/SSE 端点用）。支持：
         *   1) {"auth": {"type": "bearer", "token": "xxx"}}   -> Authorization: Bearer xxx
         *   2) {"auth": {"type": "basic",  "username": "u", "password": "p"}} -> Authorization: Basic base64(u:p)
         *      也支持 {"type":"basic","token":"已base64串"}
         *   3) {"auth": {"type": "header", "header": "X-Api-Key", "value": "xxx"}} -> X-Api-Key: xxx
         *      type 省略时默认按 bearer 处理（有 token 即可）。
         *   4) {"headers": {"X-Api-Key": "xxx", "X-Foo": "bar"}} -> 原样透传的自定义 header（可与 auth 并用）
         * 无认证配置时返回空 map（行为与原来完全一致）。
         */
        fun parseAuthHeaders(config: JSONObject): Map<String, String> {
            val headers = LinkedHashMap<String, String>()
            // 自定义 header 直传
            config.optJSONObject("headers")?.let { h ->
                h.keys().forEach { k -> h.optString(k, "").let { v -> if (v.isNotBlank()) headers[k] = v } }
            }
            // auth 块
            config.optJSONObject("auth")?.let { auth ->
                when (auth.optString("type", "bearer").trim().lowercase()) {
                    "basic" -> {
                        val token = auth.optString("token", "")
                        if (token.isNotBlank()) {
                            headers["Authorization"] = "Basic $token"
                        } else {
                            val u = auth.optString("username", "")
                            val p = auth.optString("password", "")
                            if (u.isNotBlank() || p.isNotBlank()) {
                                val enc = Base64.encodeToString("$u:$p".toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                                headers["Authorization"] = "Basic $enc"
                            }
                        }
                    }
                    "header", "custom", "apikey", "api_key" -> {
                        val key = auth.optString("header", "Authorization")
                        val value = auth.optString("value", auth.optString("token", ""))
                        if (value.isNotBlank()) headers[key] = value
                    }
                    // "bearer" 及未知/缺省
                    else -> {
                        val token = auth.optString("token", "")
                        if (token.isNotBlank()) headers["Authorization"] = "Bearer $token"
                    }
                }
            }
            return headers
        }

        // ============================================================
        // 会话（Streamable HTTP）
        //
        // 原来这条路是**完全无会话**的：一次调用 = 一次裸 POST，从不发 initialize、不带
        // Mcp-Session-Id。对无状态 server 正常，对**要求先握手**的 server 一律直接失败——
        // 也就是说「支持 MCP」这句话对一半用户是假的（stdio 那条 StdioMcpClient 一直是对的）。
        //
        // 补握手的原则是**不能把本来能用的 server 弄坏**：initialize 失败/不被支持时静默降级回
        // 原来的裸 POST 行为，绝不因为握手不成就拒绝后续请求。
        // ============================================================

        /** 协议版本。对方不认这个版本会在 initialize 的响应里报它自己的，我们不强求一致。 */
        private const val PROTOCOL_VERSION = "2025-06-18"

        /** url -> Mcp-Session-Id。server 不给就没有这一项，后续请求也就不带。 */
        private val sessions = java.util.concurrent.ConcurrentHashMap<String, String>()
        /** 已经尝试过握手的 url（成败都算）。失败会被移除，下次调用再试一次。 */
        private val handshaked = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        private val handshakeLock = Any()

        private class Resp(val json: JSONObject, val sessionId: String?, val code: Int)

        /**
         * 一次 POST。
         *
         * @param sessionId 有会话就带上 `Mcp-Session-Id`（规范要求握手之后每一条都带）。
         */
        private fun post(
            url: String,
            body: JSONObject,
            authHeaders: Map<String, String>,
            sessionId: String?,
            connectMs: Int = 10000,
            readMs: Int = 20000,
        ): Resp {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            // 规范允许 server 用 SSE 帧回一条 JSON-RPC 响应；不声明 Accept 的话有的实现会 406。
            conn.setRequestProperty("Accept", "application/json, text/event-stream")
            if (!sessionId.isNullOrBlank()) conn.setRequestProperty("Mcp-Session-Id", sessionId)
            // 认证/自定义 header（Authorization: Bearer ... 或指定 header 键值）
            authHeaders.forEach { (k, v) -> if (k.isNotBlank()) conn.setRequestProperty(k, v) }
            conn.doOutput = true
            OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
            // 读完就**关流**、并且**不调 disconnect()**：安卓的 HttpURLConnection 背后是 OkHttp 连接池，
            // 正常关流才能把这条 socket 还回池子给下次调用复用；disconnect() 是明着把它废掉，
            // 于是每调一次工具都要重连 + 重握 TLS（同一台 server 连着调十次就是十次握手）。
            // 原来两件都做错了：流没关 + 每次 disconnect。
            val code = conn.responseCode
            val responseBody = if (code in 200..299)
                conn.inputStream.bufferedReader().use { it.readText() }
            else
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            // header 名大小写不敏感，但不同实现写法不一，两种都问一遍
            val sid = conn.getHeaderField("Mcp-Session-Id") ?: conn.getHeaderField("mcp-session-id")
            return Resp(parseRpcBody(responseBody), sid, code)
        }

        /** 响应体可能是裸 JSON，也可能是 SSE 帧（`event: message` + `data: {...}`）。两种都吃。 */
        private fun parseRpcBody(raw: String): JSONObject {
            val t = raw.trim()
            if (t.isEmpty()) return JSONObject()
            if (t.startsWith("{")) return try { JSONObject(t) } catch (_: Exception) { JSONObject() }
            val data = t.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .lastOrNull { it.startsWith("{") }
            return if (data == null) JSONObject() else try { JSONObject(data) } catch (_: Exception) { JSONObject() }
        }

        /**
         * 对某个端点握一次手（每个 url 只握一次）。
         *
         * 握不上不报错、不抛：那多半是个无状态 server，照原来的裸 POST 走就是对的。
         */
        private fun ensureHandshake(url: String, authHeaders: Map<String, String>) {
            if (handshaked.containsKey(url)) return
            synchronized(handshakeLock) {
                if (handshaked.containsKey(url)) return
                handshaked[url] = true
                try {
                    val init = JSONObject().apply {
                        put("jsonrpc", "2.0"); put("id", 0); put("method", "initialize")
                        put("params", JSONObject().apply {
                            put("protocolVersion", PROTOCOL_VERSION)
                            put("capabilities", JSONObject())
                            put("clientInfo", JSONObject().put("name", "Arix").put("version", "1.0"))
                        })
                    }
                    val r = post(url, init, authHeaders, null)
                    if (!r.sessionId.isNullOrBlank()) sessions[url] = r.sessionId
                    // 只有对方确实按 MCP 应答了才补那条 initialized 通知；否则当它是无状态 server，什么都不发
                    if (r.json.has("result")) {
                        val notif = JSONObject().apply {
                            put("jsonrpc", "2.0"); put("method", "notifications/initialized")
                        }
                        runCatching { post(url, notif, authHeaders, sessions[url]) }
                    }
                } catch (_: Exception) {
                    handshaked.remove(url)   // 网络抖了一下而已，下次调用再试；别就此永久放弃握手
                }
            }
        }

        /**
         * 发一条 JSON-RPC 请求（带握手与会话）。**这是本文件对外唯一的 RPC 入口**，
         * 工具调用与工具发现都走它——原来发现那条在 `OperitCompat` 里另写了一份裸 POST，
         * 于是握手补在这里也惠及不到它，两份实现已收敛到这里。
         */
        internal fun rpc(
            url: String,
            body: JSONObject,
            authHeaders: Map<String, String> = emptyMap(),
            connectMs: Int = 10000,
            readMs: Int = 20000,
        ): JSONObject {
            ensureHandshake(url, authHeaders)
            var r = post(url, body, authHeaders, sessions[url], connectMs, readMs)
            // 404 + 我们带着会话 = 这个会话在服务端已经没了（重启/过期）。清掉重握一次再发。
            if (r.code == 404 && sessions.containsKey(url)) {
                sessions.remove(url); handshaked.remove(url)
                ensureHandshake(url, authHeaders)
                r = post(url, body, authHeaders, sessions[url], connectMs, readMs)
            }
            return r.json
        }

        private fun sendMcpRequest(
            url: String,
            body: JSONObject,
            authHeaders: Map<String, String> = emptyMap()
        ): JSONObject = rpc(url, body, authHeaders)
    }
}
