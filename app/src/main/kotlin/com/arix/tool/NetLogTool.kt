package com.arix.tool

import com.arix.app.tr
import com.arix.cloudapi.NetLogBuffer
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * net_log —— 读最近 N 条网络请求日志（聊天/语音走的共享 OkHttp 单例）。
 *
 * 日志由 cloudapi 里的 NetLogInterceptor 写进进程内环形缓冲 NetLogBuffer（**不落盘**，
 * 重启即空）。URL 里的 token/key 等敏感 query、Authorization 头在写入时已打码，这里读到的
 * 已是脱敏结果。用于排查「为什么没联网/请求慢/报错」。只读、无副作用。
 */
class NetLogTool : Tool {
    override val name = "net_log"
    override val description = tr("查看最近的网络请求日志（方法/URL/状态码/耗时/响应大小/错误），用于排查联网问题、请求慢或接口报错。数据在内存里、不落盘，敏感参数已打码。")

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("n", JSONObject().apply {
                put("type", "integer")
                put("description", tr("返回最近多少条，默认 20，最大 200"))
            })
            put("only_errors", JSONObject().apply {
                put("type", "boolean")
                put("description", tr("只看失败或非 2xx 的请求，默认 false"))
            })
        })
        put("required", JSONArray())
    }

    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)

    override suspend fun execute(params: JSONObject): ToolResult {
        val n = params.optInt("n", 20).coerceIn(1, 200)
        val onlyErrors = params.optBoolean("only_errors", false)

        val entries = NetLogBuffer.recent(n, onlyErrors)
        if (entries.isEmpty()) {
            return ToolResult(
                if (NetLogBuffer.size() == 0) tr("暂无网络请求日志（本次运行还没发过请求，或已重启清空）。")
                else tr("没有符合条件的请求日志。")
            )
        }

        val sb = StringBuilder()
        sb.append(tr("最近网络请求")).append("（").append(entries.size).append("/")
            .append(NetLogBuffer.size()).append("）：\n")
        for (e in entries) {
            val codeStr = if (e.code < 0) "ERR" else e.code.toString()
            val sizeStr = if (e.sizeBytes < 0) "-" else humanSize(e.sizeBytes)
            sb.append(timeFmt.format(Date(e.ts)))
                .append("  ").append(e.method)
                .append("  ").append(codeStr)
                .append("  ").append(e.durationMs).append("ms")
                .append("  ").append(sizeStr)
                .append("  ").append(e.url)
            e.auth?.let { sb.append("  auth=").append(it) }
            e.error?.let { sb.append("  ").append(tr("错误")).append("=").append(it) }
            sb.append("\n")
        }
        return ToolResult(sb.toString().trim())
    }

    private fun humanSize(bytes: Long): String = when {
        bytes < 1024 -> "${bytes}B"
        bytes < 1024 * 1024 -> "${bytes / 1024}KB"
        else -> "%.1fMB".format(Locale.US, bytes / 1024.0 / 1024.0)
    }
}
