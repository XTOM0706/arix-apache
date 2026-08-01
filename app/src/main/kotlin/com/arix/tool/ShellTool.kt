package com.arix.tool

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

class ShellTool(private val context: android.content.Context) : Tool {
    override val name = "shell"
    override val description = "执行 Shell 命令，返回标准输出/错误/退出码。privilege 决定权限级别：" +
        "app=应用沙盒(默认,普通命令)；shizuku=ADB级(免root，能跑 pm/am/settings/input/dumpsys 等系统命令)；root=最高权限(走 su)；" +
        "auto=自动挑当前能用的最高级(root>shizuku>app)；probe=只报告现在有哪些特权可用、不执行命令。" +
        "缺特权时用 request_permission(permission=\"shizuku\") 申请，别直接说做不到。"
    override val permissionLevel = AndroidPermissionLevel.DEBUGGER  // shell 执行属高危，默认询问

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("command", JSONObject().apply {
                put("type", "string")
                put("description", "要执行的 shell 命令")
            })
            put("privilege", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("app", "shizuku", "root", "auto", "probe")))
                put("description", "权限级别：app=应用沙盒(默认)；shizuku=ADB级免root跑系统命令(pm/am/settings/input)；root=su 最高权限；auto=自动挑能用的最高级；probe=只查当前有哪些特权可用")
            })
            put("timeout", JSONObject().apply {
                put("type", "integer")
                put("description", "超时时间（秒），默认10，最大60")
            })
            put("workdir", JSONObject().apply {
                put("type", "string")
                put("description", "工作目录，默认为应用私有目录（app 级有效）")
            })
        })
        put("required", JSONArray(listOf("command")))
    }

    private val blockedPrefixes = listOf(
        "rm -rf /",
        "rm -rf --no-preserve-root",
        "mkfs.",
        "dd if=",
        ":(){ :|:& };:",
        "chmod 777 /",
        "> /dev/sda",
        "mv /* /dev/null",
        "format",
    )

    private val allowedBaseDir: String by lazy {
        try { android.os.Environment.getExternalStorageDirectory().absolutePath } catch (_: Exception) { "/sdcard" }
    }

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        var privilege = params.optString("privilege", "app").lowercase()

        // probe：只报家底，不跑命令。AI 该能「先问再动手」，而不是每次撞一鼻子灰才知道自己有几斤几两
        if (privilege == "probe") return@withContext ToolResult(PrivilegeProbe.describe(context))

        val command = params.optString("command", "").trim()
        if (command.isBlank()) return@withContext ToolResult("请提供要执行的命令", isError = true)

        val timeoutSec = params.optInt("timeout", 10).coerceIn(1, 60)
        val workdir = params.optString("workdir", "").ifBlank { null }

        // Safety check（对所有权限级别都拦——root/shizuku 更要拦）
        val lowerCmd = command.lowercase()
        for (blocked in blockedPrefixes) {
            if (lowerCmd.contains(blocked)) {
                return@withContext ToolResult("安全拦截: 命令包含危险操作 '$blocked'，已被阻止执行。", isError = true)
            }
        }

        // auto：挑当前能用的最高级。省得 AI 自己去猜「这机器有没有 root」——猜错就是一次无谓的失败往返
        if (privilege == "auto") {
            privilege = when (PrivilegeProbe.best()) {
                Privilege.ROOT -> "root"; Privilege.SHIZUKU -> "shizuku"; Privilege.APP -> "app"
            }
        }

        // Shizuku（ADB/DEBUGGER 级）：走已验证的 Shizuku shell 通道跑系统命令，免 root。
        // 反射 waitFor 是阻塞的，withTimeout 打断不了它，故不套超时（Shizuku 命令一般很快）。
        if (privilege == "shizuku" || privilege == "adb" || privilege == "debugger") {
            val r = ShizukuAccess.exec(command)
                // 「没装」「装了没启动」「跑着没授权」用户要做的事完全不同，全糊成「未就绪」等于没说
                ?: return@withContext ToolResult(when {
                    !ShizukuAccess.installed(context) ->
                        "本机没装 Shizuku，跑不了系统命令。它能免 root 给 ADB 级权限；" +
                        "调 request_permission(permission=\"shizuku\") 我会告诉用户怎么装。"
                    !ShizukuAccess.running() ->
                        "Shizuku 装了但没启动。调 request_permission(permission=\"shizuku\") 让用户启动它。"
                    else ->
                        "Shizuku 在跑但没授权给 Arix。调 request_permission(permission=\"shizuku\") 弹授权框。"
                }, isError = true)
            // 特权身份也一样：退出码 0 不代表跑成了，输出里可能就是一句 Permission Denial
            val denial = permissionDenial(r.second)
            return@withContext ToolResult(buildString {
                appendLine("[shizuku] 退出码: ${r.first}")
                appendLine(r.second.ifBlank { "(无输出)" }.take(2000))
                if (denial != null) appendLine("\n⚠ 这条命令**没跑成**：$denial")
            }.trim(), isError = r.first != 0 || denial != null)
        }

        // root：先探一次再跑，别让 su 不存在时的报错以「命令执行异常」的面目出现
        if (privilege == "root" || privilege == "su") {
            if (!RootAccess.available()) return@withContext ToolResult(
                if (!RootAccess.suBinaryExists())
                    "本机没有 root（找不到 su）。改用 privilege=shizuku（免 root 的 ADB 级权限，" +
                    "可先 request_permission(permission=\"shizuku\") 申请），或换个不需要特权的做法。"
                else "本机有 root 但 Arix 没拿到授权，请用户在 root 管理器（Magisk 等）里给 Arix 授权后重试。",
                isError = true)
        }

        // 命令数组：root → su -c；否则应用沙盒 sh -c
        val cmdArray = if (privilege == "root" || privilege == "su") listOf("su", "-c", command) else listOf("sh", "-c", command)

        // 实时输出：命令可能跑满 60s，全程只显示「思考中」等于让 AI 黑箱操作设备。
        // 边读边推快照到 ToolStreamBus，聊天页的流式气泡自然就滚起来了。
        // 令牌在这里取、在 finally 里还，中途任何 return/异常/取消都不会把通道锁死。
        val busTok = ToolStreamBus.begin("Shell")
        var proc: Process? = null
        try {
            val dir = workdir?.let { File(it).takeIf { f -> f.exists() && f.isDirectory } }
            val process = ProcessBuilder()
                .command(cmdArray)
                .directory(dir)
                .redirectErrorStream(false)
                .start()
            proc = process

            // 有界累积：`find /` 一类命令能刷几十 MB，无界 StringBuilder 直接 OOM
            // （本项目有前科，且 catch(Exception) 抓不住 OutOfMemoryError）。只留头+尾，中间丢弃。
            val stdout = BoundedText()
            val stderr = BoundedText()

            // 按行读再解码（BufferedReader 内部按字符边界处理），不是按字节切块——
            // 逐块 String(bytes) 会把跨块的中文切成两个 U+FFFD，这坑项目里踩过。
            val stdoutReader = BufferedReader(InputStreamReader(process.inputStream))
            val stderrReader = BufferedReader(InputStreamReader(process.errorStream))

            val startTime = System.currentTimeMillis()
            val timeoutMs = timeoutSec * 1000L

            // stdout/stderr 合起来推，只推给人看；工具真正的返回值仍按原样分段（见下方 result）
            var lastPush = 0L
            fun pushPreview(force: Boolean) {
                val now = System.currentTimeMillis()
                // 限流 200ms：命令刷屏时每行都推会把 Compose 重组压垮，且人眼也看不过来
                if (!force && now - lastPush < 200) return
                lastPush = now
                // stdout/stderr 已有界（各 ≤ 头+尾 ≈ 12K），toString 不再是"重拼几 MB"；takeLast 取最新尾巴
                val snap = buildString {
                    append(stdout.toString())
                    if (stderr.isNotEmpty()) append(stderr.toString())
                }
                ToolStreamBus.update(busTok, snap.takeLast(4000))
            }

            while (process.isAlive || stdoutReader.ready() || stderrReader.ready()) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    process.destroyForcibly()
                    try { stdoutReader.close() } catch (_: Exception) {}
                    try { stderrReader.close() } catch (_: Exception) {}
                    return@withContext ToolResult("命令执行超时（${timeoutSec}秒），已强制终止。", isError = true)
                }

                var hadOutput = false
                while (stdoutReader.ready()) {
                    val line = stdoutReader.readLine() ?: break
                    stdout.appendLine(line)
                    hadOutput = true
                }
                while (stderrReader.ready()) {
                    val line = stderrReader.readLine() ?: break
                    stderr.appendLine(line)
                    hadOutput = true
                }
                if (hadOutput) pushPreview(false)
                if (!hadOutput && !process.isAlive) break
                delay(50)
            }
            pushPreview(true)  // 收尾补一帧：最后一批输出可能落在限流窗口里没推出去

            try { stdoutReader.close() } catch (_: Exception) {}
            try { stderrReader.close() } catch (_: Exception) {}

            val exitCode = try { process.waitFor() } catch (_: Exception) { -1 }

            val result = buildString {
                if (stdout.isNotEmpty()) {
                    append(stdout.toString().trim())
                }
                if (stderr.isNotEmpty()) {
                    if (isNotEmpty()) append("\n\n--- stderr ---\n")
                    append(stderr.toString().trim())
                }
                if (isEmpty()) append("(命令已执行，无输出)")
            }

            // 沙盒身份被拒 → 手上有特权就自动升一级重投，别指望模型每次都记得写 privilege=shizuku。
            // 用户装了、授权了 Shizuku，结果 AI 因为漏了个参数就一路碰壁瞎试——那 Shizuku 等于白装。
            permissionDenial(result)?.let {
                escalate(command)?.let { (code2, out2) ->
                    if (permissionDenial(out2) == null) return@withContext ToolResult(buildString {
                        appendLine("[以应用沙盒身份被拒，已自动改用特权身份重跑]")
                        appendLine("退出码: $code2")
                        appendLine(out2.ifBlank { "(无输出)" }.take(2000))
                    }.trim(), isError = code2 != 0)
                }
            }

            val denial = permissionDenial(result)
            val totalChars = stdout.total + stderr.total     // 真·全量长度（result 已有界，别拿它当总数）
            ToolResult(buildString {
                appendLine("退出码: $exitCode")
                if (result.isNotBlank()) {
                    appendLine(result.take(2000))
                }
                if (totalChars > 2000) {
                    appendLine("... (输出已截断，共 $totalChars 字符)")
                }
                if (denial != null) appendLine("\n⚠ 这条命令**没跑成**：$denial")
            }.trim(), isError = exitCode != 0 || denial != null)
        } catch (c: kotlinx.coroutines.CancellationException) {
            // 循环里有 delay 挂起点，用户按 STOP 时正是从这抛出来的。
            // 绝不能被下面的 catch(Exception) 吞掉（吞了就变成「停不掉」+一条假的执行异常），
            // 但进程得顺手杀掉，否则命令在后台继续跑，屏幕上却已经「停了」。
            try { proc?.destroyForcibly() } catch (_: Exception) {}
            throw c
        } catch (e: Exception) {
            ToolResult("命令执行异常: ${e.message}", isError = true)
        } finally {
            ToolStreamBus.end(busTok)
        }
    }

    /**
     * 沙盒被拒后的自动升权重投：Shizuku 优先（无侵扰），其次 root（仅当之前已探到有授权，
     * 不在这里主动弹 root 授权框——那会在用户没料到的时刻打断他）。返回 null=没有特权可用。
     */
    private fun escalate(command: String): Pair<Int, String>? {
        if (ShizukuAccess.granted()) return ShizukuAccess.exec(command)
        return null
    }

    /**
     * 退出码 0 ≠ 跑成了。
     *
     * `dumpsys` / `pm` / `am` 这类命令在权限不足时**照样 exit 0**，只把 "Permission Denial" 打到 stdout。
     * 只看退出码 → isError=false → 卡片显示「✓ 执行成功」，而内容其实是一句拒绝：
     * 用户被骗，AI 更被骗——它会当作拿到了数据继续往下推，或者换着花样瞎试（实测见过它
     * dumpsys 被拒后转头去读 sysfs 拿回一堆 n/a，全程以为自己在正常工作）。
     * 返回 null=没发现拒绝；否则=给 AI 看的原因+出路。
     */
    private fun permissionDenial(output: String): String? {
        val o = output.lowercase()
        val hit = when {
            "permission denial" in o -> "权限被拒"
            "permission denied" in o -> "权限被拒"
            "operation not permitted" in o -> "操作不被允许"
            "requires android.permission" in o -> "缺少所需的 android 权限"
            "securityexception" in o -> "触发了 SecurityException"
            else -> return null
        }
        // 有 Shizuku 的话上面已经自动升权重投过了，走到这儿说明**特权身份也被拒**——那就是真做不到。
        // 这时候必须明说「别重试」：不然模型会换着命令一条条试，条条被拒，白烧 token 还以为自己在推进。
        return if (ShizukuAccess.granted())
            "$hit。已经用特权身份重跑过，还是被拒——这条路走不通，换个做法，别再换命令重试了。"
        else
            "$hit —— 应用沙盒身份跑不了系统命令。这不是命令写错了，是身份不够：" +
            "调 request_permission(permission=\"shizuku\") 申请 ADB 级权限（免 root），之后重试即可。" +
            "别换着命令瞎试，换多少条都一样会被拒。"
    }
}

/**
 * 有界文本累积器：命令输出可能上几十 MB（`find /`），无界 StringBuilder 会 OOM，
 * 而返回值只取头 2000、实时预览只取尾 4000——攒全量纯属白烧内存。
 * 这里只留**头 [HEAD] + 尾 [TAIL]**、中间丢弃，[total] 仍记全量长度供如实报告"共 X 字符"。
 * 头供返回值截取（命令开头信息）、尾供预览 takeLast（最新进展）。按整行进出，不切断中文。
 */
private class BoundedText {
    private companion object { const val HEAD = 4000; const val TAIL = 8000 }
    private val headBuf = StringBuilder()
    private val tailBuf = StringBuilder()
    /** 累计写入的总字符数（含换行），可远大于实际留存。 */
    var total: Long = 0L; private set

    fun appendLine(line: String) {
        total += line.length + 1
        if (headBuf.length < HEAD) {
            headBuf.append(line).append('\n')       // 头没满：整行进头（允许略微超 HEAD，不切行）
        } else {
            tailBuf.append(line).append('\n')       // 头已满：进尾环，尾超限就丢最老
            if (tailBuf.length > TAIL) tailBuf.delete(0, tailBuf.length - TAIL)
        }
    }

    fun isNotEmpty(): Boolean = total > 0L

    /** 头 +（若中间丢过内容才加）省略标记 + 尾。 */
    override fun toString(): String = buildString {
        append(headBuf)
        if (tailBuf.isNotEmpty()) {
            if (total > headBuf.length + tailBuf.length) append("…（中间省略）…\n")
            append(tailBuf)
        }
    }
}
