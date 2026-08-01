package com.arix.tool

import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 安卓系统设置读写工具 —— 读/改屏幕亮度、自动旋转、字体缩放、开发者选项等系统设置。
 *
 * 一个工具带 action 参数（get/put），不拆两个（遵循「工具 1 用多、数量精简」）。
 *
 * 读（get）：多数系统设置项对普通 App 可读，先直接走 ContentResolver 读，读不到再退回
 * `settings get` 走 Shizuku。改（put）：`Settings.System` 需 WRITE_SETTINGS、`Global`/`Secure`
 * 需 WRITE_SECURE_SETTINGS（签名级普通 App 拿不到）——一律走 Shizuku（ADB 级，免 root）跑
 * `settings put`，Shizuku 没起来就明说要用户启用。
 *
 * 危险等级：能改系统行为，permissionLevel=DEBUGGER（默认 ASK，过全项目唯一的权限闸弹确认）。
 */
class SettingsTool(private val context: android.content.Context) : Tool {
    override val name = "settings"
    override val description =
        "读/改安卓系统设置：屏幕亮度(screen_brightness)、自动旋转(accelerometer_rotation)、" +
        "字体缩放(font_scale)、屏幕超时(screen_off_timeout)、开发者选项/USB调试(adb_enabled)等。" +
        "action=get 读取、put 写入。namespace=system(默认)/global/secure。" +
        "读多数项免权限；改 system 需 WRITE_SETTINGS、改 global/secure 需 Shizuku(免 root 的 ADB 级权限，" +
        "没有就 request_permission(permission=\"shizuku\") 申请)。"

    // 能改系统行为的危险工具，默认询问（与 ShellTool 一致），过全项目唯一的权限闸弹确认。
    override val permissionLevel = AndroidPermissionLevel.DEBUGGER

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("get", "put")))
                put("description", "get=读取一个设置项；put=写入一个设置项（改系统行为，需授权）")
            })
            put("namespace", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("system", "global", "secure")))
                put("description", "设置命名空间：system(默认，亮度/旋转/字体等)、global(全局，如飞行模式)、secure(安全，如 adb_enabled)。改 global/secure 必须走 Shizuku")
            })
            put("key", JSONObject().apply {
                put("type", "string")
                put("description", "设置项名，如 screen_brightness / accelerometer_rotation / font_scale / adb_enabled")
            })
            put("value", JSONObject().apply {
                put("type", "string")
                put("description", "put 时要写入的值（如亮度 0-255、自动旋转 0/1）。get 时忽略")
            })
        })
        put("required", JSONArray(listOf("action", "key")))
    }

    // key：设置项名只允许字母数字下划线点。value：挡掉命令注入用的字符（引号/分号/反引号/$/管道/换行等），
    // 只留下常见的值字符（数字/字母/小数点/正负号/冒号/逗号/斜杠/空格），足够覆盖亮度/字体缩放/时长等。
    private val keyRe = Regex("^[A-Za-z0-9_.]+$")
    private val valueRe = Regex("^[A-Za-z0-9_.:,/+\\- ]+$")

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        try {
            val action = params.optString("action", "").trim().lowercase()
            val namespace = params.optString("namespace", "system").trim().lowercase()
                .ifBlank { "system" }
            val key = params.optString("key", "").trim()
            val value = params.optString("value", "").trim()

            if (action != "get" && action != "put")
                return@withContext ToolResult("action 只能是 get 或 put。", isError = true)
            if (namespace !in setOf("system", "global", "secure"))
                return@withContext ToolResult("namespace 只能是 system / global / secure。", isError = true)
            if (key.isBlank())
                return@withContext ToolResult("请提供设置项名 key，如 screen_brightness。", isError = true)
            if (!keyRe.matches(key))
                return@withContext ToolResult("key「$key」含非法字符，只允许字母/数字/下划线/点。", isError = true)

            if (action == "get") doGet(namespace, key) else doPut(namespace, key, value)
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c   // 用户按 STOP：绝不能被下面的 catch(Exception) 吞掉
        } catch (e: Exception) {
            ToolResult("设置操作异常: ${e.message}", isError = true)
        }
    }

    /** 读：先直接走 ContentResolver（多数项免权限），读不到再退回 `settings get` 走 Shizuku。 */
    private fun doGet(namespace: String, key: String): ToolResult {
        val direct = try {
            when (namespace) {
                "system" -> Settings.System.getString(context.contentResolver, key)
                "global" -> Settings.Global.getString(context.contentResolver, key)
                else -> Settings.Secure.getString(context.contentResolver, key)
            }
        } catch (_: Exception) { null }

        if (!direct.isNullOrBlank())
            return ToolResult("$namespace.$key = $direct")

        // 直接读不到（未设置，或该项对普通 App 不可读）：退回 shell，仍需 Shizuku
        val r = ShizukuAccess.exec("settings get $namespace $key")
            ?: return ToolResult(
                "$namespace.$key 直接读不到（可能未设置或对普通 App 不可读），想走系统命令兜底读需要 Shizuku（免 root 的 ADB 级权限）：" +
                shizukuHint(), isError = false)
        val out = r.second.trim()
        // `settings get` 对未设置的项会打印字面量 "null"
        return if (out.isBlank() || out.equals("null", true))
            ToolResult("$namespace.$key 未设置（无值）。")
        else
            ToolResult("$namespace.$key = $out")
    }

    /** 写：一律走 Shizuku 跑 `settings put`（system 需 WRITE_SETTINGS、global/secure 需签名级权限，普通 App 都拿不全）。 */
    private fun doPut(namespace: String, key: String, value: String): ToolResult {
        if (value.isBlank())
            return ToolResult("put 需要提供 value。", isError = true)
        if (!valueRe.matches(value))
            return ToolResult(
                "value「$value」含不允许的字符（为防命令注入，只允许数字/字母/小数点/正负号/冒号/逗号/斜杠/空格）。",
                isError = true)

        if (!ShizukuAccess.granted())
            return ToolResult("改系统设置需要 Shizuku（免 root 的 ADB 级权限）：" + shizukuHint(), isError = true)

        // key/value 已用白名单校验过（无引号/分号/反引号/$/管道/换行），单引号包裹 value 再兜一层。
        val r = ShizukuAccess.exec("settings put $namespace $key '$value'")
            ?: return ToolResult("改系统设置需要 Shizuku：" + shizukuHint(), isError = true)

        // Shizuku 退出码 0 不代表真写成——权限不足时会把 "Permission Denial" 打到输出里
        val out = r.second.trim()
        val denied = out.contains("Permission Denial", true) ||
            out.contains("SecurityException", true) ||
            out.contains("permission denied", true)
        if (r.first != 0 || denied)
            return ToolResult(buildString {
                appendLine("写入失败（退出码 ${r.first}）：$namespace.$key")
                if (out.isNotBlank()) appendLine(out.take(1000))
                if (denied) appendLine("被拒——这条路走不通，别换着命令重试。")
            }.trim(), isError = true)

        // 回读确认，把真正生效的值一并报出来
        val now = try {
            when (namespace) {
                "system" -> Settings.System.getString(context.contentResolver, key)
                "global" -> Settings.Global.getString(context.contentResolver, key)
                else -> Settings.Secure.getString(context.contentResolver, key)
            }
        } catch (_: Exception) { null }
        return ToolResult("已把 $namespace.$key 改为 $value" + (now?.let { "（当前值：$it）" } ?: "") + "。")
    }

    private fun shizukuHint(): String = when {
        !ShizukuAccess.installed(context) ->
            "本机没装 Shizuku。调 request_permission(permission=\"shizuku\") 我会告诉用户怎么装。"
        !ShizukuAccess.running() ->
            "Shizuku 装了但没启动。调 request_permission(permission=\"shizuku\") 让用户启动它。"
        else ->
            "Shizuku 在跑但没授权给 Arix。调 request_permission(permission=\"shizuku\") 弹授权框。"
    }
}
