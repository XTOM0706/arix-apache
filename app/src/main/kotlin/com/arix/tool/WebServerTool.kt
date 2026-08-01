package com.arix.tool

import android.content.Context
import com.arix.app.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 内嵌极简 HTTP 静态文件服务器（AI 工具 web_server）。
 *
 * 用 Java [ServerSocket] 手写，零新依赖：AI 把生成好的网页/报告/可视化放进私有目录后，
 * 一键起服务，用户在手表/手机浏览器或同一局域网的电脑上直接打开看。
 *
 * 安全边界（三重）：
 *   1. 只服务 app 私有目录（filesDir）内的文件——根目录默认 filesDir/web_root，
 *      指定 dir 也会被强制约束在 filesDir 之内，越界直接拒绝。
 *   2. 防路径穿越：URL 解码后做规范化（canonical）比对，`..` / 绝对路径逃逸一律 403。
 *   3. 绑定选择：默认只绑回环 127.0.0.1（仅本机可访问）；lan=true 才绑 0.0.0.0 暴露到局域网。
 *   只读：仅支持 GET / HEAD，不接受任何写入/上传。
 *
 * 生命周期：accept 循环跑在后台守护线程，请求交给小线程池处理；stop 关闭 ServerSocket
 * 让 accept 抛异常退出，线程池 shutdownNow，真正释放端口。单例（companion）持有运行态，
 * 因此 stop/status 能跨多次工具调用命中同一个实例。
 */
class WebServerTool(private val context: Context) : Tool {
    override val name = "web_server"
    override val description =
        "在本机起一个极简 HTTP 静态服务器，把私有目录里的网页/文件用浏览器打开或分享到局域网。" +
            "action=start(port 默认8080、dir 默认 web_root 子目录、lan=true 才暴露到局域网否则仅本机)、" +
            "stop 关闭、status 查看运行状态。只读、只服务 app 私有目录、防路径穿越。" +
            "适合：AI 生成好一个 HTML 报告/可视化后，让用户在浏览器里直接看。"

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("start", "stop", "status")))
                put("description", tr("start=启动, stop=停止, status=查看状态"))
            })
            put("port", JSONObject().apply {
                put("type", "integer")
                put("description", tr("监听端口，默认 8080，范围 1024~65535"))
            })
            put("dir", JSONObject().apply {
                put("type", "string")
                put("description", tr("网站根目录（相对 filesDir 的子目录），默认 web_root；强制约束在 app 私有目录内"))
            })
            put("lan", JSONObject().apply {
                put("type", "boolean")
                put("description", tr("是否暴露到局域网：false(默认)仅本机 127.0.0.1 可访问；true 绑 0.0.0.0 同网设备可访问"))
            })
        })
        put("required", JSONArray(listOf("action")))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        when (params.optString("action", "").trim().lowercase()) {
            "start" -> doStart(params)
            "stop" -> doStop()
            "status" -> doStatus()
            else -> ToolResult(tr("未知 action，应为 start / stop / status。"), isError = true)
        }
    }

    private fun doStart(params: JSONObject): ToolResult {
        synchronized(lock) {
            running?.let {
                return ToolResult(
                    tr("已有服务器在运行（端口 ") + it.port + tr("）。请先 stop 再重开，或直接用现有地址。\n") + describe(it),
                )
            }
            val port = params.optInt("port", 8080)
            if (port !in 1024..65535)
                return ToolResult(tr("端口需在 1024~65535 之间：") + port, isError = true)
            val lan = params.optBoolean("lan", false)

            // 1) 解析并约束根目录在 filesDir 内
            val root = resolveRoot(params.optString("dir", ""))
                ?: return ToolResult(tr("根目录越界：只能服务 app 私有目录（filesDir）内的子目录。"), isError = true)
            if (!root.exists()) root.mkdirs()

            // 2) 绑定 ServerSocket
            val bindAddr = if (lan) InetAddress.getByName("0.0.0.0") else InetAddress.getByName("127.0.0.1")
            val socket = try {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(bindAddr, port), 50)
                }
            } catch (e: java.net.BindException) {
                return ToolResult(tr("端口 ") + port + tr(" 已被占用或不可绑定，请换一个端口：") + (e.message ?: ""), isError = true)
            } catch (e: Exception) {
                return ToolResult(tr("启动失败：") + (e.message ?: e.toString()), isError = true)
            }

            val pool = Executors.newFixedThreadPool(8) { r -> Thread(r, "web-server-worker").apply { isDaemon = true } }
            val srv = RunningServer(socket, pool, port, root, lan)
            val thread = Thread({ acceptLoop(srv) }, "web-server-accept").apply { isDaemon = true }
            srv.acceptThread = thread
            running = srv
            thread.start()

            return ToolResult(tr("HTTP 服务器已启动。\n") + describe(srv))
        }
    }

    private fun doStop(): ToolResult {
        synchronized(lock) {
            val srv = running ?: return ToolResult(tr("当前没有正在运行的服务器。"))
            srv.close()
            running = null
            return ToolResult(tr("已停止服务器（端口 ") + srv.port + tr("），共处理 ") + srv.requests.get() + tr(" 个请求。"))
        }
    }

    private fun doStatus(): ToolResult {
        synchronized(lock) {
            val srv = running ?: return ToolResult(tr("状态：未运行。用 action=start 启动。"))
            return ToolResult(tr("状态：运行中。\n") + describe(srv) + tr("\n已处理请求：") + srv.requests.get())
        }
    }

    private fun describe(srv: RunningServer): String {
        val sb = StringBuilder()
        sb.append(tr("根目录：")).append(srv.root.absolutePath).append("\n")
        sb.append(tr("本机访问：")).append("http://127.0.0.1:").append(srv.port).append("/\n")
        if (srv.lan) {
            val ip = lanIp()
            if (ip != null) sb.append(tr("局域网访问：")).append("http://").append(ip).append(":").append(srv.port).append("/")
            else sb.append(tr("局域网：已开放（0.0.0.0），但未取到局域网 IP，请查看本机 WLAN 地址。"))
        } else {
            sb.append(tr("局域网：未开放（仅本机可访问，如需同网设备访问请以 lan=true 重启）。"))
        }
        return sb.toString()
    }

    /** 根目录解析：约束在 filesDir 内，越界返回 null。 */
    private fun resolveRoot(dir: String): File? {
        val base = try { context.filesDir.canonicalFile } catch (_: Exception) { return null }
        val raw = when {
            dir.isBlank() -> File(base, "web_root")
            dir.startsWith("/") -> File(dir)
            else -> File(base, dir)
        }
        val f = try { raw.canonicalFile } catch (_: Exception) { return null }
        return if (f.path == base.path || f.path.startsWith(base.path + File.separator)) f else null
    }

    // ---- accept 循环 + 请求处理 ----

    private fun acceptLoop(srv: RunningServer) {
        while (!srv.serverSocket.isClosed) {
            val client = try {
                srv.serverSocket.accept()
            } catch (_: Exception) {
                break // socket 被 close()（stop）→ 退出循环
            }
            try {
                srv.pool.execute { handle(srv, client) }
            } catch (_: Exception) {
                runCatching { client.close() }
            }
        }
    }

    private fun handle(srv: RunningServer, client: Socket) {
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            val requestLine = readLine(input) ?: return
            // 读掉其余请求头直到空行（不解析，防止半开连接）
            while (true) {
                val h = readLine(input) ?: break
                if (h.isEmpty()) break
            }
            srv.requests.incrementAndGet()

            val parts = requestLine.split(" ")
            if (parts.size < 2) { sendError(client, 400, "Bad Request"); return }
            val method = parts[0].uppercase()
            if (method != "GET" && method != "HEAD") { sendError(client, 405, "Method Not Allowed"); return }

            var rawPath = parts[1].substringBefore('?').substringBefore('#')
            rawPath = try { URLDecoder.decode(rawPath, "UTF-8") } catch (_: Exception) { rawPath }

            val target = resolveWithin(srv.root, rawPath)
            if (target == null) { sendError(client, 403, "Forbidden"); return }

            var file = target
            if (file.isDirectory) {
                val index = File(file, "index.html")
                if (index.isFile) file = index
                else { sendListing(client, srv.root, file, method); return }
            }
            if (!file.isFile) { sendError(client, 404, "Not Found"); return }

            sendFile(client, file, method)
        } catch (_: Exception) {
            // 连接层错误忽略
        } finally {
            runCatching { client.close() }
        }
    }

    /** 请求路径在根目录内解析，穿越/越界返回 null。 */
    private fun resolveWithin(root: File, urlPath: String): File? {
        val clean = urlPath.trimStart('/')
        val base = try { root.canonicalFile } catch (_: Exception) { return null }
        val raw = if (clean.isEmpty()) base else File(base, clean)
        val f = try { raw.canonicalFile } catch (_: Exception) { return null }
        return if (f.path == base.path || f.path.startsWith(base.path + File.separator)) f else null
    }

    private fun sendFile(client: Socket, file: File, method: String) {
        val out = BufferedOutputStream(client.getOutputStream())
        val length = file.length()
        val header = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: ").append(contentType(file.name)).append("\r\n")
            append("Content-Length: ").append(length).append("\r\n")
            append("Connection: close\r\n")
            append("Cache-Control: no-cache\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        if (method != "HEAD") {
            file.inputStream().use { it.copyTo(out, 64 * 1024) }
        }
        out.flush()
    }

    private fun sendListing(client: Socket, root: File, dir: File, method: String) {
        val rel = dir.absolutePath.removePrefix(root.absolutePath).ifEmpty { "/" }
        val entries = (dir.listFiles() ?: emptyArray()).sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
        val body = buildString {
            append("<!doctype html><meta charset=\"utf-8\"><title>").append(esc(rel)).append("</title>")
            append("<h3>").append(esc(rel)).append("</h3><ul>")
            for (e in entries) {
                val n = e.name + if (e.isDirectory) "/" else ""
                append("<li><a href=\"").append(esc(n)).append("\">").append(esc(n)).append("</a></li>")
            }
            append("</ul>")
        }.toByteArray(Charsets.UTF_8)
        val out = BufferedOutputStream(client.getOutputStream())
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
        if (method != "HEAD") out.write(body)
        out.flush()
    }

    private fun sendError(client: Socket, code: Int, msg: String) {
        val body = "<h1>$code $msg</h1>".toByteArray(Charsets.UTF_8)
        val out = BufferedOutputStream(client.getOutputStream())
        out.write(("HTTP/1.1 $code $msg\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.UTF_8))
        out.write(body)
        out.flush()
    }

    /** 逐字节读一行（到 \n），去掉结尾 \r；EOF 且无内容返回 null。上限防超长行。 */
    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var read = false
        while (sb.length < 8192) {
            val b = input.read()
            if (b == -1) break
            read = true
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
        }
        return if (!read && sb.isEmpty()) null else sb.toString()
    }

    private fun contentType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "html", "htm" -> "text/html; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "xml" -> "application/xml; charset=utf-8"
        "txt", "md", "log", "csv" -> "text/plain; charset=utf-8"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "pdf" -> "application/pdf"
        "mp3" -> "audio/mpeg"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "wav" -> "audio/wav"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "wasm" -> "application/wasm"
        else -> "application/octet-stream"
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** 取一个站内（局域网）IPv4 地址用于展示。 */
    private fun lanIp(): String? = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress && it.address.size == 4 }
            ?.hostAddress
    } catch (_: Exception) { null }

    private class RunningServer(
        val serverSocket: ServerSocket,
        val pool: ExecutorService,
        val port: Int,
        val root: File,
        val lan: Boolean,
    ) {
        val requests = AtomicLong(0)
        @Volatile var acceptThread: Thread? = null

        fun close() {
            runCatching { serverSocket.close() }
            runCatching { pool.shutdownNow() }
            runCatching { acceptThread?.interrupt() }
        }
    }

    companion object {
        private val lock = Any()
        @Volatile private var running: RunningServer? = null
    }
}
