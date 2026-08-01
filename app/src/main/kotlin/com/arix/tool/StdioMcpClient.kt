package com.arix.tool

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.Writer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ============================================================
// MCP STDIO 传输 —— 起一个 MCP server 子进程，行分隔 JSON-RPC over stdin/stdout。
// 与 HTTP 端点 MCP（McpTool）并列，补上本地 MCP server 这条路。
// 用安卓自带的 sh 起进程；需要 node/python 的 MCP server 由用户在 shell 环境里装好（Apache-2.0 版不含终端 App）。
//
// —— 连接生命周期（2026-07-27 逐行核对过代码路径，别再凭注释猜）——
//  * **进程复用**：一次 [ensureStarted] 起进程 + 一次 initialize 握手，之后 tools/list、tools/call
//    全走同一条 stdin/stdout。[alive] 为真就直接用，不重起、不重握手。
//    client 本身由 [StdioMcpRegistry] 按配置名长期持有（发现/刷新也拿同一个）。
//  * **并发**：[startMutex] 串行化冷启动，并发首调只会起一份进程、握一次手；在途请求按 id 各认各的。
//  * **重连**：进程死了（EOF / 写失败）会把 started 置 false，下一次调用自动重起并重新握手；
//    连败带指数退避（见 failStreak），免得命令根本跑不起来时每次调用都刷一个短命进程。
//    **只有「消息压根没写出去」才自动重发**（见 rpcRetrying）——写出去了没回音不重发，
//    对端可能已经把副作用做完了。
//  * **未解决**：[StdioMcpRegistry.closeAll] 全项目没有调用点，App 退出不收子进程。
//    要收得在 Application/主 Activity 挂钩。
// ============================================================
class StdioMcpClient(private val context: Context, internal val command: String) {
    private var proc: Process? = null                            // sh 兜底
    private var writer: Writer? = null
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val idSeq = AtomicInteger(1)
    @Volatile private var started = false
    private val lock = Any()
    private val writeLock = Any()      // 只串行化本地流的写
    private val startMutex = Mutex()

    /**
     * 起进程失败后的退避。没有它的话：命令根本跑不起来（没装 node / 命令写错）时，**每一次**调用都会
     * 重新拉起一个进程再空等 15s 握手——而模型碰到失败还会换个说法重试，于是一路刷出一串短命进程。
     * 连败第一次冷却 5s、此后翻倍、封顶 2 分钟；起成功立刻清零（用户装好 node 后不用干等）。
     */
    @Volatile private var failStreak = 0
    @Volatile private var coolUntilMs = 0L

    private fun nowMs() = android.os.SystemClock.elapsedRealtime()
    private fun inCooldown(): Boolean = failStreak > 0 && nowMs() < coolUntilMs
    private fun noteStartFailed() {
        failStreak = (failStreak + 1).coerceAtMost(6)
        coolUntilMs = nowMs() + (5_000L shl (failStreak - 1)).coerceAtMost(120_000L)
    }

    private fun alive(): Boolean =
        started && proc?.isAlive == true

    suspend fun ensureStarted(): Boolean {
        if (alive()) return true
        return startMutex.withLock {
            if (alive()) return@withLock true
            if (inCooldown()) return@withLock false
            if (!startTransport()) { noteStartFailed(); return@withLock false }
            // 只有真正起进程的这次做握手（并发 first-call 里另一个在这把锁上等，等到时 alive() 已真，不重复握手）
            val hello = rpcOnce("initialize", JSONObject().apply {
                put("protocolVersion", "2024-11-05")
                put("capabilities", JSONObject())
                put("clientInfo", JSONObject().apply { put("name", "Arix"); put("version", "1.0") })
            }, 15000)
            // 没握上手 + 进程已经死了 = 命令根本没跑起来（如没装 node）。诚实失败，别让调用方等 60s 超时。
            // ⚠ 这里不能调 rpcRetrying：它失败时会回头调 ensureStarted，而 startMutex 不可重入 = 死锁。
            if (hello !is Rpc.Done && !alive()) { noteStartFailed(); return@withLock false }
            notifyRpc("notifications/initialized", null)
            failStreak = 0; coolUntilMs = 0L
            true
        }
    }

    /**
     * 收掉当前传输通道（进程），并清空字段。
     * 起新进程前必须先做：进程被标记为死（started=false）时对象仍留在字段里，直接覆盖就等于把一个
     * **可能还活着**的子进程丢掉不管——之后谁也不再持有它，收不回来了。
     * destroy 刻意放在锁外：避免圈进锁里和 writeLine 互相卡死。
     */
    private fun disposeTransport() {
        val p = synchronized(lock) {
            val v = proc
            proc = null; writer = null
            v
        }
        try { p?.destroy() } catch (_: Exception) {}
    }

    private suspend fun startTransport(): Boolean {
        disposeTransport()
        // 系统 sh：MCP server 通常要 node/python，由用户在 shell 环境里装好；安卓自带 sh 起的是它自己的路径。
        return withContext(Dispatchers.IO) {
            try {
                val home = AiWorkspace.root(context)
                val pb = ProcessBuilder("sh", "-c", command).directory(home).apply {
                    environment()["HOME"] = home.absolutePath; environment()["PWD"] = home.absolutePath
                }
                pb.redirectErrorStream(false)
                val p = pb.start()
                synchronized(lock) { proc = p; writer = p.outputStream.writer(Charsets.UTF_8); started = true }
                startReaders(p)
                true
            } catch (_: Exception) { synchronized(lock) { started = false }; false }
        }
    }

    /** 一行 JSON-RPC 响应：认领对应的在途请求。 */
    private fun handleLine(line: String) {
        val l = line.trim()
        if (!l.startsWith("{")) return
        try {
            val o = JSONObject(l)
            val idi = when (val id = o.opt("id")) {
                is Int -> id; is Number -> id.toInt(); is String -> id.toIntOrNull(); else -> null
            }
            if (idi != null) pending.remove(idi)?.complete(o)
        } catch (_: Exception) {}
    }

    private fun failAllPending() {
        val err = JSONObject().put("error", "MCP server 进程已退出/连接断开")
        pending.values.forEach { it.complete(err) }
        pending.clear()
    }

    private fun startReaders(p: Process) {
        Thread {
            try {
                p.inputStream.bufferedReader().forEachLine { handleLine(it) }
            } catch (_: Exception) {}
            // EOF/进程死：让在途 rpc 立即失败，别空等到超时(60s)
            started = false
            failAllPending()
        }.apply { isDaemon = true; start() }
        // 排空 stderr，防管道写满阻塞子进程
        Thread { try { p.errorStream.bufferedReader().forEachLine { } } catch (_: Exception) {} }.apply { isDaemon = true; start() }
    }

    private fun writeLine(o: JSONObject): Boolean = try {
        val line = o.toString() + "\n"
        // 只在锁里取通道，**不要把 IO 调用留在锁内**：对端不读时会阻塞，连带 close()/startTransport 一起卡死。
        val w = synchronized(lock) { writer }
        when {
            // write+flush 不是原子的，并发 rpc 会把两行交织成一行烂 JSON、两边都等到超时。
            // 单独一把锁串行化——它只护本地流，不会像原来那样把 binder 调用圈进锁里。
            w != null -> synchronized(writeLock) { w.write(line); w.flush(); true }
            else -> false
        }
    } catch (_: Exception) { false }

    /** 一次 RPC 的三种结局。**「没写出去」必须和「没等到回音」分开**——只有前者重发是安全的。 */
    private sealed class Rpc {
        data class Done(val json: JSONObject) : Rpc()
        /** 通道已死，消息压根没发出去：对端什么都没做，重连后重发不会重复执行副作用。 */
        data object WriteFailed : Rpc()
        /** 写出去了但没等到响应：对端可能已经执行了（下过单、删过文件），**绝不自动重发**。 */
        data object Timeout : Rpc()
    }

    private suspend fun rpcOnce(method: String, params: JSONObject?, timeoutMs: Long = 20000): Rpc {
        val id = idSeq.getAndIncrement()
        val def = CompletableDeferred<JSONObject>()
        pending[id] = def
        val msg = JSONObject().apply {
            put("jsonrpc", "2.0"); put("id", id); put("method", method); if (params != null) put("params", params)
        }
        if (!writeLine(msg)) { pending.remove(id); return Rpc.WriteFailed }
        val r = withTimeoutOrNull(timeoutMs) { def.await() }
        pending.remove(id)
        return if (r != null) Rpc.Done(r) else Rpc.Timeout
    }

    /**
     * 发一次 RPC，**写失败时重连一次再发**。
     *
     * 为什么需要：终端 App 被强杀时 onExit 回调不一定到得了（binder 直接断），`isAlive` 还是真，
     * [ensureStarted] 于是认为通道好着——直到 writeLine 抛 DeadObjectException 才发现。原来这时只回
     * 一句「超时/无响应」，用户得再叫一次才会重连。写失败=消息没出门，重发是安全的（见 [Rpc]）。
     */
    private suspend fun rpcRetrying(method: String, params: JSONObject?, timeoutMs: Long = 20000): Rpc {
        val first = rpcOnce(method, params, timeoutMs)
        if (first !is Rpc.WriteFailed) return first
        started = false          // 让 alive() 判死，下面这次 ensureStarted 才会真去重起
        failAllPending()         // 同一条死通道上的其它在途请求也别空等到超时
        if (!ensureStarted()) return Rpc.WriteFailed
        return rpcOnce(method, params, timeoutMs)
    }

    private fun notifyRpc(method: String, params: JSONObject?) {
        writeLine(JSONObject().apply { put("jsonrpc", "2.0"); put("method", method); if (params != null) put("params", params) })
    }

    suspend fun listTools(): List<Tool> {
        if (!ensureStarted()) return emptyList()
        val res = (rpcRetrying("tools/list", null) as? Rpc.Done)?.json ?: return emptyList()
        val arr = res.optJSONObject("result")?.optJSONArray("tools") ?: res.optJSONArray("tools") ?: return emptyList()
        val out = ArrayList<Tool>()
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val n = t.optString("name", ""); if (n.isBlank()) continue
            val schema = t.optJSONObject("inputSchema") ?: t.optJSONObject("parameters") ?: JSONObject()
            out.add(StdioMcpTool(this, n, "mcp_${n.replace(" ", "_")}", "[MCP] ${t.optString("description", "")}", schema))
        }
        return out
    }

    /** 起不来时给模型/用户的一句人话（含退避冷却，免得它以为「再叫一次就好了」）。 */
    private fun startFailMessage(): String = when {
        inCooldown() ->
            "MCP(stdio) 上次启动失败，冷却中（约 ${((coolUntilMs - nowMs()) / 1000).coerceAtLeast(1L)} 秒后可再试）：$command"
        else ->
            "MCP(stdio) 启动失败：命令在 sh 里没跑起来（检查 node/python 是否已装、命令是否正确）。"
    }

    suspend fun callTool(toolName: String, args: JSONObject): String {
        if (!ensureStarted()) return startFailMessage()
        val res = when (val r = rpcRetrying(
            "tools/call", JSONObject().apply { put("name", toolName); put("arguments", args) }, 60000
        )) {
            is Rpc.Done -> r.json
            Rpc.Timeout -> return "MCP(stdio) 调用超时/无响应"
            Rpc.WriteFailed -> return "MCP(stdio) 连接已断开，重连没成功：$command"
        }
        // JSON-RPC 的 error 响应（和进程中途死掉时 failAllPending 塞的那条）以前会被当成正常结果原样
        // 回给模型：一坨 {"error":...} JSON，且 StdioMcpTool 的 isError 判不出来 → 模型当成功往下走。
        res.opt("error")?.takeIf { it != JSONObject.NULL }?.let { e ->
            val msg = (e as? JSONObject)?.optString("message", "")?.takeIf { it.isNotBlank() } ?: e.toString()
            return "MCP(stdio) 调用失败：$msg"
        }
        val result = res.optJSONObject("result") ?: res
        return result.optJSONArray("content")?.let { arr ->
            (0 until arr.length()).joinToString("\n") { arr.optJSONObject(it)?.optString("text", "") ?: "" }
        } ?: result.toString()
    }

    fun close() {
        started = false
        disposeTransport()
        synchronized(lineBuf) { lineBuf.reset() }
        failAllPending()
    }
}

class StdioMcpTool(
    private val client: StdioMcpClient,
    private val toolName: String,
    override val name: String,
    override val description: String,
    override val parameters: JSONObject
) : Tool {
    override suspend fun execute(params: JSONObject): ToolResult {
        val out = client.callTool(toolName, params)
        return ToolResult(out, isError = out.startsWith("MCP(stdio)"))
    }
}

// 持久 client 注册表（按配置名复用进程，别每次调用/刷新都重启）。
object StdioMcpRegistry {
    private val clients = ConcurrentHashMap<String, StdioMcpClient>()

    /**
     * 按配置名取同一个 client（= 同一个子进程）。
     * **命令变了要换人**：原来只按 name 取（getOrPut），用户在配置 json 里把 command 改掉之后，
     * 拿回来的仍是跑着**旧命令**的老 client——改了半天没反应，还白留一个旧进程。
     * 命令不同就先把老的收掉再建新的。
     */
    @Synchronized
    fun clientFor(context: Context, name: String, command: String): StdioMcpClient {
        clients[name]?.let { old ->
            if (old.command == command) return old
            old.close(); clients.remove(name)
        }
        return StdioMcpClient(context, command).also { clients[name] = it }
    }

    suspend fun discover(context: Context, name: String, command: String): List<Tool> =
        clientFor(context, name, command).listTools()

    /** ⚠ 目前**全项目没有调用点**：App 退出不收 MCP 子进程（尤其跑在终端 App 那侧的，能活过我们整个进程）。 */
    fun closeAll() { clients.values.forEach { it.close() }; clients.clear() }
}
