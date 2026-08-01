package com.arix.tool

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * linux_exec —— 在独立「Arix 终端」App 的 proot Linux 环境里干活。
 * 那边跑的是**原版 Termux**（proot 虚拟化 com.termux 前缀），bash/python/apt/git 全生态、随便装不打架。
 * 命令经 [TerminalClient] 绑定终端 App 的服务运行，输出流式回传聊天页。
 * 工具名保持 linux_exec 不改（AI 靠它认工具，改名要同步所有提示词/技能）。
 *
 * 两种用法：
 *  - **一次性**（action=run，默认，与从前完全一致）：跑完等结果。适合 `ls`、`pip install`、跑个脚本。
 *  - **会话式**（start/input/read/kill/list）：起一个**长驻**进程，之后往它的 stdin 喂东西、随时看它吐了什么。
 *    这条是补给那些一次性跑不了的活：交互式程序（python REPL、ssh、要输 y/n 的安装器）、
 *    以及跑几十分钟的任务（编译、训练）——一次性调用只能干等到超时，超时就什么都拿不回来。
 *    底层早就有（[TerminalClient.openProcess] 的 write/closeStdin/kill），只是一直没暴露给 AI。
 */
class LocalLinuxTool(private val context: Context) : Tool {
    override val name = "linux_exec"
    override val description = "在独立「Arix 终端」App 的 Linux 环境干活：bash/python3/apt/pip/git/coreutils 全可用，pkg/apt install 随便装不打架。" +
        "action=run（默认）跑一条命令等结果；start 起一个后台/交互会话（长任务、需要输入的程序）；input 往会话里输入；" +
        "read 看会话新吐出来的内容；kill 结束会话；list 看还开着哪些；env 看当前到底是哪个 Linux（发行版/包管理器/共享目录/图形化）；" +
        "gfx 开关与启停图形界面（那部分在容器外面，敲命令够不着）。" +
        "未安装终端 App 时会提示去安装。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Work in the Linux environment of the separate Arix Terminal app: bash/python3/apt/pip/git/coreutils, install anything with pkg/apt. " +
        "action=run (default) runs one command and waits for it — returns stdout+stderr merged plus the exit code. " +
        "action=start launches a long-running or interactive session and returns immediately with a session_id — use it for builds/downloads that outlast a timeout, and for programs that need input (a REPL, ssh, an installer asking y/n). " +
        "action=input writes to that session's stdin and shows what came back; action=read shows whatever is new since you last looked; action=kill ends it; action=list shows live sessions. " +
        "Prefer run for anything that finishes quickly — a session you forget to kill keeps a process alive. " +
        "action=env reports what this Linux actually is right now: distro, package manager, HOME, the folder shared with Android, and whether graphics is on. " +
        "The first run/start of a conversation already returns that report; use env again only if the user changed the environment. " +
        "action=gfx with command=on|off|start|stop|view|status controls the graphical display, which lives outside the container and cannot be reached with shell commands: " +
        "on gives the container a DISPLAY (your next command already has it), start brings the X server up, view puts the desktop on the user's screen. " +
        "GUI programs still have to be installed by you, and must be run in the background (`xterm &`) or your command never returns."
    override val permissionLevel = AndroidPermissionLevel.DEBUGGER
    // 命令全在独立终端 App 的 proot 环境里跑，没装它就是必然失败（本工具没有离线退路）
    override val requires = ToolRequirement.TERMINAL_APP

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string"); put("enum", JSONArray(listOf("run", "start", "input", "read", "kill", "list", "env", "gfx")))
                put("description", "run = one-shot (default); start/input/read/kill/list = interactive session; env = what this Linux actually is right now; gfx = control the graphical display")
            })
            // gfx 的操作词复用 command，不另开参数：schema 每轮都要发一遍，多一个字段就是每轮多花 token
            put("command", JSONObject().apply { put("type", "string"); put("description", "command, run under bash -lc; cwd is the terminal HOME. Needed for run and start. For action=gfx put one of: on, off, start, stop, view, status") })
            put("timeout", JSONObject().apply { put("type", "integer"); put("description", "run only: seconds, default 30, max 600; raise it for installs and builds") })
            put("session_id", JSONObject().apply { put("type", "string"); put("description", "which session (input/read/kill). On start you may propose one, otherwise you get an id back") })
            put("stdin", JSONObject().apply { put("type", "string"); put("description", "input to send (action=input). A newline is appended unless the text already ends with one — that newline is what makes the program act on it") })
            put("wait", JSONObject().apply { put("type", "integer"); put("description", "input/read: seconds to wait for output before answering, default 3, max 60. Use a bigger value when you know it takes a while") })
        })
        put("required", JSONArray(listOf<String>()))
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = params.optString("action", "run").trim().lowercase().ifBlank { "run" }
        if (action != "list" && !TerminalClient.isInstalled(context)) {
            return@withContext ToolResult(
                "完整 Linux 在独立「Arix 终端」App 里，尚未安装。请在「设置 → 终端」安装它，" +
                    "装好后本工具即可跑 apt/python/bash/git 等。", isError = true)
        }
        when (action) {
            "start" -> startSession(params)
            "input" -> inputSession(params)
            "read" -> readSession(params)
            "kill" -> killSession(params)
            "list" -> listSessions()
            "env" -> envBrief()
            "gfx" -> gfxControl(params)
            else -> runOnce(params)
        }
    }

    // ============ 环境简报 ============

    /**
     * 「这台此刻是哪个 Linux」。
     *
     * 工具描述是编译期写死的，说不出用户刚把环境从 Termux 切成了 Alpine、装没装好、图形化开没开。
     * 缺了这些，模型只能按最常见的猜（一上来 `apt-get`），在非 Debian 的容器里就是一轮白跑。
     *
     * 简报由终端 App 现算（见 TerminalBrief），这边不复制一份状态判断 —— 那必然会和真相漂移。
     */
    private suspend fun envBrief(): ToolResult {
        val brief = TerminalClient.aiBrief(context)
            ?: return ToolResult("终端 App 无法连接，拿不到环境信息。", isError = true)
        briefGiven = true
        return ToolResult(brief.trim())
    }

    // ============ 图形化 ============

    /**
     * 让 AI 能真的把图形界面拉起来。
     *
     * 为什么不能靠它自己在容器里敲命令解决：总开关是终端 App 的 SharedPreferences、
     * X server 是终端 App fork 的 `app_process` 子进程、Xvnc 是终端 App 托管的 Process ——
     * 三样全在容器外面。没有这条，模型能装上 xterm，然后卡在 `cannot open display`，
     * 接着去装更多的包试图修一个根本不在容器里的问题。
     *
     * 动作只有开关和启停这几个；改分辨率/换后端/装 X server App 不给 —— 那些是用户的选择，
     * 或者需要用户在系统层面点同意。每个动作都过工具审批（默认询问），不会被悄悄执行。
     */
    private suspend fun gfxControl(params: JSONObject): ToolResult {
        val op = params.optString("command", "").trim().lowercase().ifBlank { "status" }
        if (op !in GFX_OPS)
            return ToolResult("图形操作只能是：${GFX_OPS.joinToString("/")}（放在 command 里）", isError = true)
        val res = TerminalClient.gfxControl(context, op)
            ?: return ToolResult("终端 App 连不上，或者它的版本还没有图形控制。", isError = true)
        return ToolResult(briefPrefixOnce() + res.trim())
    }

    /**
     * 简报只在**本次进程里第一次真正用终端时**随结果附一遍。
     *
     * 为什么要自动附而不是等模型自己去问：它不知道有这么个东西可问，等它问等于没做。
     * 为什么只附一次：这段几百 token，每条命令都带就是每轮都在烧钱，而环境在一次对话里基本不变
     * （真变了模型可以显式 action=env 再要一份）。
     */
    @Volatile private var briefGiven = false

    private val GFX_OPS = listOf("on", "off", "start", "stop", "view", "status")

    private suspend fun briefPrefixOnce(): String {
        if (briefGiven) return ""
        briefGiven = true      // 先置位：拿不到也别每条命令都重试一次跨进程调用
        val brief = TerminalClient.aiBrief(context) ?: return ""
        return brief.trim() + "\n\n"
    }

    // ============ 一次性（原有行为，一字未改） ============

    private suspend fun runOnce(params: JSONObject): ToolResult {
        val command = params.optString("command", "").trim()
        if (command.isBlank()) return ToolResult("请提供要执行的命令", isError = true)
        val timeoutSec = params.optInt("timeout", 30).coerceIn(1, 600)

        // 流名可由调用方指定：code_runner 委派过来时显示「终端」会让用户以为自己在看别的东西，
        // 说「运行代码」才对得上他刚让 AI 做的事。参数从 params 走，不改 Tool 接口。
        val busTok = ToolStreamBus.begin(params.optString("stream_name", "").ifBlank { "终端" })
        val sb = StringBuilder()
        try {
            val res = TerminalClient.exec(context, command, timeoutSec * 1000L) { chunk ->
                synchronized(sb) { sb.append(chunk) }
                ToolStreamBus.update(busTok, synchronized(sb) { sb.toString() }.takeLast(4000))
            } ?: return ToolResult("终端 App 无法连接。", isError = true)

            val out = clean(res.output)
            // 环境简报：本次进程第一条命令随结果附一遍（放在最前面，模型先看到"这是什么机器"再看输出）
            val brief = briefPrefixOnce()
            if (res.timedOut) {
                return ToolResult(
                    brief + "[终端] 执行超时（${timeoutSec}s）。" + (if (out.isNotEmpty()) "\n" + out.take(2000) else "") +
                        "\n（要跑很久的活别用 run 干等：action=start 起个会话，之后 read 看进度。）",
                    isError = true)
            }
            return ToolResult(brief + buildString {
                appendLine("[终端] 退出码: ${res.exitCode}")
                if (out.isNotEmpty()) appendLine(out.take(4000)) else appendLine("(无输出)")
            }.trim(), isError = !res.ok)
        } finally {
            ToolStreamBus.end(busTok)
        }
    }

    // ============ 会话式 ============

    /**
     * 一个长驻进程 + 它到目前为止吐出来的东西。
     *
     * [cursor] 是「上次读到哪儿」：`read` 只回**新增**的部分，不然每次都把整段历史重发一遍，
     * 几轮下来就把上下文吃光了。缓冲区超上限时丢最旧的，同时把 cursor 跟着往前挪，不然会读到错位的内容。
     */
    private class Session(val id: String, val command: String) {
        /**
         * ⚠ 后置赋值、且回调只碰缓冲区不碰它：`openProcess` 的回调是 oneway，
         * **进程的头几行输出可能在 openProcess 还没返回时就到了**（那边的注释也说进程可能先死）。
         * 若把 proc 做成构造参数、让回调闭包引用一个还没构造好的 Session，那一刻就是崩溃。
         */
        @Volatile var proc: TerminalClient.ProcessSession? = null
        val startedAt = System.currentTimeMillis()
        @Volatile var exitCode: Int? = null
        private val utf8 = Utf8Streamer()
        private val buf = StringBuilder()
        private var cursor = 0
        private val maxBuf = 200_000      // 单会话最多留这么多字符，超了丢最旧的

        /** 输出是按字节块回传的，跨块的中文必须用增量解码器接住，否则两边各得一个 U+FFFD。 */
        fun onBytes(data: ByteArray) = append(synchronized(utf8) { utf8.decode(data) })

        fun append(text: String) {
            if (text.isEmpty()) return
            synchronized(buf) {
                buf.append(text)
                if (buf.length > maxBuf) {
                    val drop = buf.length - maxBuf
                    buf.delete(0, drop)
                    cursor = (cursor - drop).coerceAtLeast(0)
                }
            }
        }

        fun readNew(): String = synchronized(buf) {
            val from = cursor.coerceIn(0, buf.length)
            buf.substring(from).also { cursor = buf.length }
        }

        fun hasNew(): Boolean = synchronized(buf) { cursor < buf.length }
        fun size(): Int = synchronized(buf) { buf.length }
        val alive: Boolean get() = exitCode == null && proc?.isAlive != false
    }

    private companion object {
        const val MAX_SESSIONS = 4         // 手表上开太多长驻进程纯属拖机器
        val sessions = ConcurrentHashMap<String, Session>()
        val seq = java.util.concurrent.atomic.AtomicInteger(1)
    }

    private suspend fun startSession(params: JSONObject): ToolResult {
        val command = params.optString("command", "").trim()
        if (command.isBlank()) return ToolResult("请提供要在会话里跑的命令（比如 bash、python3 -i、或者一条很久才跑完的命令）", isError = true)
        reap(force = sessions.size >= MAX_SESSIONS)
        if (sessions.size >= MAX_SESSIONS)
            return ToolResult("同时开着的会话太多（${sessions.size}）。先 action=kill 关掉一个，或者用 action=list 看看哪个已经跑完了。", isError = true)
        // ⚠ 用 containsKey 而不是 `in`：sessions 是 ConcurrentHashMap，`in` 会解析成 containsValue（KT-18053）
        val id = params.optString("session_id", "").trim().takeIf { it.isNotBlank() && !sessions.containsKey(it) }
            ?: "s${seq.getAndIncrement()}"

        val session = Session(id, command)
        val proc = TerminalClient.openProcess(
            context, command,
            onStdout = { bytes -> session.onBytes(bytes) },
            // openProcess 那边 stderr 不与 stdout 合流（stdio 协议要分开）；这里是给人/模型看的，合进同一条流更好读
            onStderr = { msg -> session.append(msg) },
            onExit = { code -> session.exitCode = code },
        ) ?: return ToolResult("会话起不来：终端环境没装好，或者终端 App 连不上。先跑一条 action=run 的简单命令看看环境是否正常。", isError = true)

        session.proc = proc
        sessions[id] = session
        // 给它一小会儿把开头吐出来——「起好了但什么都没看到」会让模型立刻怀疑是不是失败了
        delay(800)
        val head = session.readNew()
        val brief = briefPrefixOnce()      // 会话式也可能是本次进程的第一条，同样先交代环境
        return ToolResult(buildString {
            append(brief)
            append("已启动会话 $id：$command\n")
            if (head.isNotBlank()) append(clean(head).take(2000)).append("\n")
            append("用 linux_exec(action=\"input\", session_id=\"$id\", stdin=\"…\") 输入、")
            append("linux_exec(action=\"read\", session_id=\"$id\") 看新输出、")
            append("linux_exec(action=\"kill\", session_id=\"$id\") 结束。活干完记得 kill，别把进程留着空跑。")
        })
    }

    private suspend fun inputSession(params: JSONObject): ToolResult {
        val s = sessions[params.optString("session_id", "").trim()] ?: return noSession(params)
        if (!s.alive) return ToolResult("会话 ${s.id} 已经结束了（退出码 ${s.exitCode ?: "未知"}）。要接着干就重新 start 一个。", isError = true)
        var text = params.optString("stdin", "")
        if (text.isEmpty()) return ToolResult("要输入什么？给 stdin。（只想看新输出用 action=read）", isError = true)
        // 不补换行的话，命令就只是躺在输入缓冲里，程序永远不会开始执行它——
        // 那表现成「输进去了但什么反应都没有」，最难查的一类。
        if (!text.endsWith("\n")) text += "\n"
        if (s.proc?.write(text) != true) {
            s.exitCode = s.exitCode ?: -1
            return ToolResult("写不进会话 ${s.id}（进程已经死了或终端 App 被杀了）。重新 start 一个。", isError = true)
        }
        val out = awaitOutput(s, params.optInt("wait", 3).coerceIn(0, 60) * 1000L)
        return ToolResult(render(s, out, "已输入"))
    }

    private suspend fun readSession(params: JSONObject): ToolResult {
        val s = sessions[params.optString("session_id", "").trim()] ?: return noSession(params)
        val out = awaitOutput(s, params.optInt("wait", 3).coerceIn(0, 60) * 1000L)
        return ToolResult(render(s, out, "会话 ${s.id}"))
    }

    private fun killSession(params: JSONObject): ToolResult {
        val id = params.optString("session_id", "").trim()
        val s = sessions.remove(id) ?: return noSession(params)
        runCatching { s.proc?.kill() }
        val tail = s.readNew()
        return ToolResult("已结束会话 $id。" + if (tail.isNotBlank()) "\n最后的输出：\n" + clean(tail).take(2000) else "")
    }

    private fun listSessions(): ToolResult {
        reap()
        if (sessions.isEmpty()) return ToolResult("当前没有开着的 Linux 会话。")
        return ToolResult(sessions.values.sortedBy { it.startedAt }.joinToString("\n") { s ->
            val age = (System.currentTimeMillis() - s.startedAt) / 1000
            "· ${s.id}  ${if (s.alive) "运行中" else "已结束(退出码 ${s.exitCode ?: "?"})"}  已开 ${age}s  缓冲 ${s.size()} 字  命令：${s.command.take(80)}"
        })
    }

    // ---- 会话零件 ----

    /** 等到有新输出为止（或等够时间）。轮询而不是回调等待：输出是一块一块来的，等"第一块"就返回会把答案切一半。 */
    private suspend fun awaitOutput(s: Session, budgetMs: Long): String {
        val deadline = System.currentTimeMillis() + budgetMs
        var quietSince = 0L
        while (System.currentTimeMillis() < deadline) {
            delay(200)
            if (s.hasNew()) {
                quietSince = System.currentTimeMillis()
                // 有东西了就再多等 600ms 的"安静期"，把连着来的几块一起收走
                while (System.currentTimeMillis() - quietSince < 600 && System.currentTimeMillis() < deadline) {
                    delay(200)
                    if (s.hasNew()) quietSince = System.currentTimeMillis()
                }
                break
            }
            if (!s.alive) break
        }
        return s.readNew()
    }

    private fun render(s: Session, out: String, prefix: String): String = buildString {
        append("[$prefix · 会话 ${s.id}] ")
        append(if (s.alive) "运行中" else "已结束，退出码 ${s.exitCode ?: "未知"}")
        append("\n")
        val cleaned = clean(out)
        if (cleaned.isBlank()) append("(这段时间没有新输出。要么它还在忙——加大 wait 再 read 一次；要么它在等你输入。)")
        else append(cleaned.take(6000))
    }

    private fun noSession(params: JSONObject): ToolResult {
        val id = params.optString("session_id", "")
        val live = sessions.keys.sorted().joinToString("/").ifBlank { "（一个都没有）" }
        return ToolResult("没有叫「$id」的会话。当前的会话：$live。先 action=start 起一个。", isError = true)
    }

    /**
     * 清掉已经跑完的会话。默认**留十分钟**——进程结束不等于模型已经把最后那段输出读走，
     * 立刻抹掉等于把结果扔了。[force]（起新会话时名额满了）就连最旧的那条一起清，别让死会话卡住名额。
     */
    private fun reap(force: Boolean = false) {
        val now = System.currentTimeMillis()
        sessions.entries.removeAll { (_, s) -> !s.alive && now - s.startedAt > 10 * 60_000 }
        if (!force) return
        sessions.values.filterNot { it.alive }.minByOrNull { it.startedAt }?.let { sessions.remove(it.id) }
    }

    /** 渲染进度条(\r 覆盖)+剥 ANSI，再滤掉 proot 下 linker 的无害警告，给 AI 干净结果。 */
    private fun clean(raw: String): String = TerminalRender.clean(raw)
        .lineSequence().filterNot { it.contains("WARNING: linker:") }
        .joinToString("\n").trim()
}
