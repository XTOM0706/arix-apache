package com.arix.tool

import android.content.Context
import com.arix.app.tr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

// ============================================================
// 内置 MCP Server —— 让本 App 自己作为 MCP 服务端，把 ToolManager 里
// 已启用的工具暴露给【外部】MCP 客户端（Claude Desktop / Cursor / 别的 App）连接调用。
// 与 McpTool.kt（本 App 作为客户端连别人）方向相反、互补。
//
// 传输：手写 HTTP/1.1 + JSON-RPC 2.0（java ServerSocket，不引任何依赖）。
// 安全：默认【关】；默认只绑回环 127.0.0.1（仅本机可连）；可设访问令牌；
//       想开放到局域网(0.0.0.0)则【强制】要求先配令牌，否则拒绝启动。
// 权限：工具执行仍走 ToolManager.execute → 权限闸门（ALLOW/ASK/FORBID）不变，
//       无人值守时 ASK 会 60s 超时判为拒绝，高危工具默认拦得住。
// ============================================================

/** 持久化：内置 MCP Server 的开关/端口/令牌/绑定范围。自包含 prefs，不动共享配置。 */
object McpServerPrefs {
    private const val PREFS = "xtom_mcp_server"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context): Boolean = p(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = p(c).edit().putBoolean("enabled", v).apply()

    fun port(c: Context): Int = p(c).getInt("port", 8790).coerceIn(1024, 65535)
    fun setPort(c: Context, v: Int) = p(c).edit().putInt("port", v.coerceIn(1024, 65535)).apply()

    /** 访问令牌；非空则每个请求必须带 Authorization: Bearer <token> 或 X-MCP-Token 头。 */
    fun token(c: Context): String = p(c).getString("token", "") ?: ""
    fun setToken(c: Context, v: String) = p(c).edit().putString("token", v.trim()).apply()

    /** true=仅本机回环 127.0.0.1（默认，安全）；false=局域网可达 0.0.0.0（必须配 token）。 */
    fun loopbackOnly(c: Context): Boolean = p(c).getBoolean("loopback_only", true)
    fun setLoopbackOnly(c: Context, v: Boolean) = p(c).edit().putBoolean("loopback_only", v).apply()
}

/** MCP 服务端本体：起/停 ServerSocket，处理 HTTP JSON-RPC 请求。 */
object McpServer {
    private const val PROTOCOL_VERSION = "2024-11-05"

    @Volatile private var serverSocket: ServerSocket? = null
    private var scope: CoroutineScope? = null

    @Volatile var running: Boolean = false
        private set
    @Volatile var boundInfo: String = ""
        private set

    fun isRunning(): Boolean = running

    /**
     * 启动服务。已运行则先停再起（用当前 prefs 配置）。返回可读的监听地址（如 "127.0.0.1:8790"）。
     * 非回环绑定但未设令牌 → 抛异常拒绝启动（防裸奔到局域网）。
     */
    fun start(context: Context): String {
        val app = context.applicationContext
        stop()
        val loopback = McpServerPrefs.loopbackOnly(app)
        val token = McpServerPrefs.token(app)
        if (!loopback && token.isBlank()) {
            throw IllegalStateException(tr("开放到局域网(0.0.0.0)必须先设置访问令牌，已拒绝启动"))
        }
        val port = McpServerPrefs.port(app)
        val addr = InetAddress.getByName(if (loopback) "127.0.0.1" else "0.0.0.0")
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(addr, port))
        serverSocket = ss
        running = true
        boundInfo = "${if (loopback) "127.0.0.1" else "0.0.0.0"}:$port"

        val sc = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = sc
        sc.launch {
            while (isActive && !ss.isClosed) {
                val sock = try { ss.accept() } catch (_: Exception) { break }
                launch { runCatching { handle(app, sock, token) } }
            }
        }
        return boundInfo
    }

    fun stop() {
        running = false
        boundInfo = ""
        runCatching { serverSocket?.close() }
        serverSocket = null
        runCatching { scope?.cancel() }
        scope = null
    }

    // ---- 单连接处理：解析 HTTP，取出 JSON-RPC body，分发，回写响应 ----
    private suspend fun handle(context: Context, socket: Socket, token: String) {
        socket.use { s ->
            s.soTimeout = 15000
            // 【安全 #5】每个连接一个能区分来源的 clientId，而不是所有客户端共用固定 "mcp-server"
            // （共用的话，用户对某工具点一次「始终允许」，本机任意 App 连上来都继承同一 mcp:mcp-server:* 键）。
            //
            // 身份取哪儿来，决定了这条授权记不记得住：
            //  · 设了访问令牌 → clientId = 令牌指纹。**只有拿着令牌的人才是它**，身份可验证，
            //    「始终允许」记在它头上是有意义的，且跨连接稳定（同一个客户端不必每次都问）。
            //  · 没设令牌 → 只能取「远端 IP:端口」。而源端口是客户端自己挑的：本机任一 App 绑同一个源端口
            //    再连过来就顶着同一身份、白蹭上次的放行（回环下大家都是 127.0.0.1，IP 区分不了谁是谁）。
            //    这种身份标成 verified=false，[ToolCaller.canRememberAlways] 据此禁掉「始终」——每次当面点头。
            val verified = token.isNotBlank()
            val clientId = if (verified) "token-" + tokenFingerprint(token)
            else (s.remoteSocketAddress as? InetSocketAddress)?.let {
                "${it.address?.hostAddress ?: "unknown"}:${it.port}"
            } ?: "unknown"
            val caller = ToolCaller.Mcp(clientId, verified)
            val input = BufferedInputStream(s.getInputStream())
            val out = s.getOutputStream()

            val requestLine = readAsciiLine(input) ?: return
            val httpMethod = requestLine.substringBefore(' ').trim().uppercase()

            val headers = HashMap<String, String>()
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
            }

            // CORS 预检
            if (httpMethod == "OPTIONS") { writeHttp(out, 204, ""); return }

            // 令牌校验（设了才校验）
            if (token.isNotBlank() && !authOk(headers, token)) {
                writeHttp(out, 401, jsonErr(null, -32001, "unauthorized").toString()); return
            }

            // GET 等非 POST：给个健康检查/状态页，方便 curl 验活
            if (httpMethod != "POST") {
                writeHttp(out, 200, JSONObject().put("server", "Arix MCP").put("ok", true).toString()); return
            }

            val len = headers["content-length"]?.toIntOrNull() ?: 0
            val body = String(readN(input, len), StandardCharsets.UTF_8)
            val response = dispatch(context, body, caller)
            if (response == null) writeHttp(out, 202, "")           // 通知(无 id)：202 无 body
            else writeHttp(out, 200, response)
        }
    }

    // ---- JSON-RPC 分发。返回响应 JSON 字符串；通知(无需回复)返回 null ----
    /** 令牌指纹：SHA-256 前 8 位十六进制。只用来当身份键，不回显、不参与校验。 */
    private fun tokenFingerprint(token: String): String = try {
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(StandardCharsets.UTF_8))
            .take(4).joinToString("") { "%02x".format(it) }
    } catch (_: Exception) { "unknown" }

    private suspend fun dispatch(context: Context, body: String, caller: ToolCaller.Mcp): String? {
        val req = try { JSONObject(body) } catch (_: Exception) {
            return jsonErr(null, -32700, "parse error").toString()
        }
        val id = req.opt("id").takeUnless { it == JSONObject.NULL }
        return when (req.optString("method", "")) {
            "initialize" -> jsonResult(id, JSONObject().apply {
                put("protocolVersion", PROTOCOL_VERSION)
                put("capabilities", JSONObject().put("tools", JSONObject()))
                put("serverInfo", JSONObject().put("name", "Arix").put("version", "1.0"))
            }).toString()
            // 客户端发来的通知，无需回复
            "notifications/initialized", "notifications/cancelled" -> null
            "ping" -> jsonResult(id, JSONObject()).toString()
            "tools/list" -> jsonResult(id, JSONObject().put("tools", toolsList())).toString()
            "tools/call" -> handleToolCall(context, id, req.optJSONObject("params") ?: JSONObject(), caller).toString()
            else -> if (id == null) null else jsonErr(id, -32601, "method not found").toString()
        }
    }

    /**
     * 把**已启用**的工具映射成 MCP tools 列表：{name, description, inputSchema}。
     *
     * 只给已启用的：外部客户端看不到「未启用」的说明，也调不到 request_permission 去申请启用
     * ——广告一个它调了必被挡、又没有恢复路径的工具，纯粹是挖坑。
     */
    private fun toolsList(): JSONArray = JSONArray().apply {
        for (t in ToolManager.getEnabledTools()) {
            put(JSONObject().apply {
                put("name", t.name)
                put("description", t.llmDescription)   // 对面是另一个模型，同样用模型侧那份
                put("inputSchema", t.parameters)   // 工具的 parameters 本身就是 JSON Schema
            })
        }
    }

    /** tools/call：执行对应工具（仍过权限闸门），结果包成 MCP content[]。 */
    private suspend fun handleToolCall(context: Context, id: Any?, params: JSONObject, caller: ToolCaller.Mcp): JSONObject {
        val name = params.optString("name", "")
        if (name.isBlank()) return jsonErr(id, -32602, "missing tool name")
        val args = params.optJSONObject("arguments") ?: JSONObject()
        // **必须显式标成 Mcp caller**：不传 caller 会默认成 ToolCaller.Ai，于是外部/本机 MCP 客户端
        // 直接继承用户给「AI」设的全部「始终允许」，STANDARD 工具零弹窗自动跑，连确认框都谎报成
        // 「AI 要…」。而 MCP 服务默认 loopback 连令牌都不要求 → 本机任一恶意 App 就能连上驱动整份
        // allow-list。分出独立命名空间后，MCP 走自己的策略（默认 ASK），不沾 AI 的信任。
        val result = ToolManager.execute(
            ToolCall(id = "mcp_srv_${System.currentTimeMillis()}", name = name, arguments = args,
                caller = caller)
        )
        return jsonResult(id, JSONObject().apply {
            put("content", JSONArray().put(JSONObject().put("type", "text").put("text", result.content)))
            put("isError", result.isError)
        })
    }

    // ---- JSON-RPC 信封 ----
    private fun jsonResult(id: Any?, result: Any): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0"); put("id", id ?: JSONObject.NULL); put("result", result)
    }

    private fun jsonErr(id: Any?, code: Int, msg: String): JSONObject = JSONObject().apply {
        put("jsonrpc", "2.0"); put("id", id ?: JSONObject.NULL)
        put("error", JSONObject().put("code", code).put("message", msg))
    }

    private fun authOk(headers: Map<String, String>, token: String): Boolean {
        val bearer = headers["authorization"]?.let { if (it.startsWith("Bearer ", true)) it.substring(7).trim() else it.trim() }
        return bearer == token || headers["x-mcp-token"]?.trim() == token
    }

    // ---- 极简 HTTP I/O（不引依赖） ----
    private fun readAsciiLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return sb.toString()
    }

    private fun readN(input: InputStream, n: Int): ByteArray {
        if (n <= 0) return ByteArray(0)
        val buf = ByteArray(n); var off = 0
        while (off < n) {
            val r = input.read(buf, off, n - off)
            if (r == -1) break
            off += r
        }
        return if (off == n) buf else buf.copyOf(off)
    }

    private fun writeHttp(out: OutputStream, status: Int, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val reason = when (status) {
            200 -> "OK"; 202 -> "Accepted"; 204 -> "No Content"
            401 -> "Unauthorized"; 500 -> "Internal Server Error"; else -> "OK"
        }
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Access-Control-Allow-Methods: POST, OPTIONS\r\n")
            append("Connection: close\r\n\r\n")
        }
        out.write(head.toByteArray(StandardCharsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }
}

/** AI 工具：让 AI / 用户通过对话起停内置 MCP Server 并查状态。自包含，注册片段见文件注释。 */
class McpServerTool(private val context: Context) : Tool {

    override val name = "mcp_server"
    override val description = tr("控制本机内置 MCP Server：让外部 MCP 客户端(Claude Desktop/Cursor 等)连接本 App、调用这里已启用的工具。action=start 启动 / stop 停止 / status 查看状态。默认只绑回环 127.0.0.1，仅本机可连。")

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("start", "stop", "status")))
                put("description", tr("start=启动，stop=停止，status=查看运行状态/监听地址/已暴露工具数"))
            })
        })
        put("required", JSONArray(listOf("action")))
    }

    override suspend fun execute(params: JSONObject): ToolResult {
        return when (params.optString("action", "status")) {
            "start" -> try {
                val addr = McpServer.start(context)
                McpServerPrefs.setEnabled(context, true)
                ToolResult(tr("内置 MCP Server 已启动，监听 ") + addr + tr("，暴露 ") + ToolManager.getEnabledTools().size + tr(" 个工具。外部客户端用 HTTP JSON-RPC(POST) 连接此地址即可。"))
            } catch (e: Exception) {
                ToolResult(tr("启动失败：") + (e.message ?: ""), isError = true)
            }
            "stop" -> {
                McpServer.stop()
                McpServerPrefs.setEnabled(context, false)
                ToolResult(tr("内置 MCP Server 已停止。"))
            }
            else -> {
                val state = if (McpServer.isRunning()) tr("运行中，地址 ") + McpServer.boundInfo else tr("未运行")
                ToolResult(tr("MCP Server 状态：") + state + "，" + tr("已暴露 ") + ToolManager.getEnabledTools().size + tr(" 个工具。"))
            }
        }
    }
}
