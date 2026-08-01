package com.arix.tool

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.arix.terminal.ITerminalCallback
import com.arix.terminal.ITerminalService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 主 App 侧的终端桥：绑定独立「Arix 终端」App（`com.arix.terminal`）的 ITerminalService，
 * 让 `linux_exec`（一次性 exec）和终端页（交互 PTY 会话）都跑在终端 App 的 proot 环境里。
 * 未安装终端 App 时 [isInstalled]=false，调用方应提示用户安装。
 */
/**
 * 增量 UTF-8 解码器：流是按任意字节数切块回传的，一个中文字符常被切在两块之间。
 * 逐块 `String(bytes)` 会让两边各得一个 U+FFFD（乱码）；这里把不完整的尾巴留到下一块再解。
 * 非线程安全，调用方自己串行化。
 */
internal class Utf8Streamer {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)
    private var pending = ByteArray(0)

    fun decode(data: ByteArray): String {
        if (data.isEmpty() && pending.isEmpty()) return ""
        val buf = java.nio.ByteBuffer.wrap(pending + data)
        val out = java.nio.CharBuffer.allocate(buf.remaining() + 1)
        decoder.decode(buf, out, false)
        pending = ByteArray(buf.remaining()).also { buf.get(it) }   // 半个字符，等下一块
        out.flip()
        return out.toString()
    }

    /** 流结束：把残留字节按"就这些了"解掉（真截断了就是替换字符）。 */
    fun flush(): String {
        if (pending.isEmpty()) return ""
        val buf = java.nio.ByteBuffer.wrap(pending)
        val out = java.nio.CharBuffer.allocate(pending.size + 1)
        decoder.decode(buf, out, true)
        decoder.flush(out)
        pending = ByteArray(0)
        out.flip()
        return out.toString()
    }
}

object TerminalClient {
    const val PKG = "com.arix.terminal"
    private const val ACTION = "com.arix.terminal.ITerminalService"

    @Volatile private var svc: ITerminalService? = null
    @Volatile private var conn: ServiceConnection? = null
    @Volatile private var pending: CompletableDeferred<ITerminalService?>? = null

    fun isInstalled(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0); true
    } catch (_: Exception) { false }

    @Synchronized
    private fun bind(ctx: Context): CompletableDeferred<ITerminalService?> {
        svc?.let { return CompletableDeferred(it) }
        pending?.let { return it }
        val d = CompletableDeferred<ITerminalService?>()
        if (!isInstalled(ctx)) { d.complete(null); return d }
        // 上一次连接断了但 conn 还挂着：先解绑，否则每次重连都新注册一个 ServiceConnection（泄漏）
        conn?.let { old -> runCatching { ctx.applicationContext.unbindService(old) }; conn = null }
        pending = d
        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                // 必须与 bind() 用同一把锁：否则后台线程可能在 svc 写入前读到 null、又在 pending 清空后
                // 读到 null，一路穿到下面把刚连上的这条连接 unbind 掉，再注册第二个。
                synchronized(TerminalClient) {
                    // 终端 App 进程被杀时 onServiceDisconnected 不一定及时到，靠死亡通知兜住
                    runCatching { binder?.linkToDeath({ svc = null }, 0) }
                    svc = ITerminalService.Stub.asInterface(binder); pending = null
                }
                d.complete(svc)
            }
            override fun onServiceDisconnected(name: ComponentName?) { svc = null }
        }
        conn = c
        val ok = try {
            ctx.applicationContext.bindService(Intent(ACTION).setPackage(PKG), c, Context.BIND_AUTO_CREATE)
        } catch (_: Exception) { false }
        if (!ok) { pending = null; conn = null; d.complete(null) }
        return d
    }

    private suspend fun service(ctx: Context): ITerminalService? =
        withTimeoutOrNull(8000) { bind(ctx).await() }

    /** 确保 proot 环境已装（首次会解压，可能几十秒）。 */
    suspend fun ensureReady(ctx: Context): Boolean {
        val s = service(ctx) ?: return false
        if (s.isReady) return true
        val done = CompletableDeferred<Boolean>()
        s.ensureInstalled(object : ITerminalCallback.Stub() {
            override fun onOutput(id: String?, data: ByteArray?) {}
            override fun onExit(id: String?, code: Int) {}
            override fun onEnvEvent(kind: String?, message: String?) {
                when (kind) { "done" -> done.complete(true); "error" -> done.complete(false) }
            }
        })
        return withTimeoutOrNull(180_000) { done.await() } ?: false
    }

    /**
     * 给 AI 看的环境简报（英文，终端侧现算）。取不到就返回 null —— 调用方一律当作「这次不给简报」，
     * 绝不要为此让整条命令失败：简报是**锦上添花的上下文**，不是执行前提。
     * 老版本终端 App 没有这个 AIDL 方法，跨进程调用会抛，也走这条。
     */
    suspend fun aiBrief(ctx: Context): String? {
        val s = service(ctx) ?: return null
        return runCatching { s.aiBrief() }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 图形化开关/启停（op = on/off/start/stop/view/status）。返回 null = 连不上终端 App，
     * 或者装的是没有这个 AIDL 方法的旧版终端（跨进程调用会抛，这里一并当作"做不了"）。
     */
    suspend fun gfxControl(ctx: Context, op: String): String? {
        val s = service(ctx) ?: return null
        return runCatching { s.gfxControl(op) }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    data class ExecResult(val output: String, val exitCode: Int, val timedOut: Boolean) {
        val ok: Boolean get() = !timedOut && exitCode == 0
    }

    /** 一次性命令（AI 用）：干净输出流；返回 null=终端 App 未安装。 */
    suspend fun exec(ctx: Context, command: String, timeoutMs: Long = 30_000, onChunk: (String) -> Unit = {}): ExecResult? {
        val s = service(ctx) ?: return null
        if (!ensureReady(ctx)) return ExecResult("终端环境安装失败或超时。", -1, false)
        val sb = StringBuilder()
        val done = CompletableDeferred<Int>()
        // 输出是按 8K 字节块回传的，逐块 String(data) 会把跨块的中文切成两个 U+FFFD——
        // 而这些字都要喂回给模型。用增量解码器把半个字符留到下一块。
        val utf8 = Utf8Streamer()
        s.exec(command, null, object : ITerminalCallback.Stub() {
            override fun onOutput(id: String?, data: ByteArray?) {
                if (data == null) return
                val t = synchronized(utf8) { utf8.decode(data) }
                if (t.isEmpty()) return
                synchronized(sb) { sb.append(t) }
                onChunk(t)
            }
            override fun onExit(id: String?, code: Int) { done.complete(code) }
            override fun onEnvEvent(kind: String?, message: String?) {
                if (kind == "error") { synchronized(sb) { sb.append(message ?: "") }; done.complete(-1) }
            }
        })
        val code = withTimeoutOrNull(timeoutMs) { done.await() }
        val tail = synchronized(utf8) { utf8.flush() }        // 收尾：吐出残留（截断的话就是替换字符）
        if (tail.isNotEmpty()) synchronized(sb) { sb.append(tail) }
        val out = synchronized(sb) { sb.toString() }
        return if (code == null) ExecResult(out, -1, true) else ExecResult(out, code, false)
    }

    // —— 长驻管道进程（MCP stdio server 用）——
    // 人用的交互终端不走这里：那是终端 App 的 TermActivity（真 pty，Intent 拉起）。

    class ProcessSession internal constructor(
        private val svc: ITerminalService,
        val id: String,
        private val dead: java.util.concurrent.atomic.AtomicBoolean,
    ) {
        val isAlive: Boolean get() = !dead.get()

        /**
         * 返回是否写出去了。**必须让调用方知道失败**：终端 App 被强停/被系统杀掉后，
         * 这里会抛 DeadObjectException；若吞掉并假装成功，调用方会一直以为通道还活着，
         * 每次调用都白等一轮超时、且永远不会重启传输。
         */
        fun write(text: String): Boolean = try {
            if (dead.get()) false else { svc.writeStdin(id, text.toByteArray()); true }
        } catch (_: Exception) { dead.set(true); false }

        fun closeStdin() = try { svc.closeStdin(id) } catch (_: Exception) { dead.set(true) }
        fun kill() { dead.set(true); try { svc.killProcess(id) } catch (_: Exception) {} }
    }

    /**
     * 在 proot 环境里起一个长驻子进程（stdin 持续可写）。
     * stdout 走 [onStdout]，**给的是原始字节块**——调用方自己按字节切行再解码，
     * 别在这里 String(data)：8K 一块的切分会把跨块的多字节 UTF-8 字符切坏成 U+FFFD。
     * stderr 走 [onStderr]（不与 stdout 合流，stdio 协议靠 stdout 分行解析）。
     *
     * [installIfNeeded]=false 时若环境还没装好就直接失败返回，不去触发那个动辄几十秒的
     * bootstrap 解压（发现/刷新这类路径不该被拖住）。
     * 返回 null = 终端 App 未安装 / 环境没装好 / 起不来。
     */
    suspend fun openProcess(
        ctx: Context, command: String, cwd: String? = null,
        onStdout: (ByteArray) -> Unit, onStderr: (String) -> Unit = {}, onExit: (Int) -> Unit = {},
        installIfNeeded: Boolean = true,
    ): ProcessSession? {
        val s = service(ctx) ?: return null
        if (installIfNeeded) {
            if (!ensureReady(ctx)) return null
        } else {
            if (!(try { s.isReady } catch (_: Exception) { false })) return null
        }
        // 进程可能在 openProcess 返回前就死了（回调是 oneway，可能先到）——用这个标记补记，别漏掉
        val dead = java.util.concurrent.atomic.AtomicBoolean(false)
        val id = s.openProcess(command, cwd, object : ITerminalCallback.Stub() {
            override fun onOutput(jid: String?, data: ByteArray?) { if (data != null) onStdout(data) }
            override fun onExit(jid: String?, code: Int) { dead.set(true); onExit(code) }
            override fun onEnvEvent(kind: String?, message: String?) {
                if (message.isNullOrEmpty()) return
                when (kind) {
                    "stderr" -> onStderr(message)
                    "error" -> { dead.set(true); onStderr(message) }
                }
            }
        })
        if (id.isNullOrEmpty()) return null
        return ProcessSession(s, id, dead)
    }
}
