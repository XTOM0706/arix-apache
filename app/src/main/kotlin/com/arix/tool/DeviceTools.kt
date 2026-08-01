package com.arix.tool

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager as AndroidPm
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

// ============================================================
// Brightness Tool
// ============================================================
class BrightnessTool(private val context: Context) : Tool {
    override val name = "brightness"
    override val description = "调整设备屏幕亮度。支持设置百分比和自动亮度。"
    override val llmDescription = "Set screen brightness, or switch to auto."

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("level", JSONObject().apply {
                put("type", "integer")
                put("description", "0-100 percent, or -1 for auto")
            })
        })
        put("required", JSONArray(listOf("level")))
    }

    /**
     * 写一项系统设置。返回 null=成功，否则=失败原因。
     *
     * 两条路：有 WRITE_SETTINGS 就走正常 API；没有就走 Shizuku 的 `settings put`——它以 shell 身份写，
     * 压根不需要 WRITE_SETTINGS。以前只有第一条路，用户没给权限就只能干瞪眼。
     */
    private fun putSystem(key: String, value: Int): String? {
        if (try { Settings.System.canWrite(context) } catch (_: Exception) { false }) {
            return try { Settings.System.putInt(context.contentResolver, key, value); null }
            catch (e: Exception) { e.message ?: "写入失败" }
        }
        ShizukuAccess.exec("settings put system $key $value")?.let { (code, out) ->
            return if (code == 0) null else "settings put 失败：$out"
        }
        return "需要「修改系统设置」权限。可调 request_permission(permission=\"write_settings\") 申请" +
               "（有 Shizuku 的话我能直接开掉，不用麻烦用户）。"
    }

    // 亮度只要 Context，不要 Activity。原来靠 getTopActivity() 反射拿当前 Activity——
    // 而语音助手常态在后台，那时它必然返回 null，这工具在最该用的场景下直接废掉。
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val level = params.optInt("level", 50)
        try {
            if (level < 0) {
                val e = putSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC)
                return@withContext if (e == null) ToolResult("已设为自动亮度") else ToolResult("设自动亮度失败：$e", isError = true)
            }
            putSystem(Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                ?.let { return@withContext ToolResult("设置亮度失败：$it", isError = true) }
            val e = putSystem(Settings.System.SCREEN_BRIGHTNESS, level.coerceIn(0, 100) * 255 / 100)
            if (e == null) ToolResult("屏幕亮度已设为 $level%") else ToolResult("设置亮度失败：$e", isError = true)
        } catch (e: Exception) { ToolResult("设置亮度失败: ${e.message}", isError = true) }
    }
}

// ============================================================
// Torch（手电筒）—— 借鉴橘瓣 TorchTool
// ============================================================
class TorchTool(private val context: Context) : Tool {
    override val name = "torch"
    override val description = "开关手电筒/闪光灯。action=on/off/toggle。"
    override val llmDescription = "Toggle the flashlight. action=on/off/toggle."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply { put("action", JSONObject().apply { put("type", "string"); put("description", "on/off/toggle") }) })
        put("required", JSONArray(listOf("action")))
    }
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.Main) {
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as? android.hardware.camera2.CameraManager
            ?: return@withContext ToolResult("无相机服务", isError = true)
        try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return@withContext ToolResult("本设备没有闪光灯", isError = true)
            flashId = id
            ensureCallback(cm)   // 注册一次，让 torchOn 跟随硬件真实状态（别人/系统改了也同步），toggle 才不会反向
            val on = when (params.optString("action", "toggle")) { "on" -> true; "off" -> false; else -> !torchOn }
            cm.setTorchMode(id, on); torchOn = on
            ToolResult(if (on) "已开手电筒" else "已关手电筒")
        } catch (e: Exception) { ToolResult("手电控制失败：${e.message}", isError = true) }
    }
    companion object {
        @Volatile private var torchOn = false
        @Volatile private var flashId: String? = null
        @Volatile private var callbackRegistered = false
        private val torchCb = object : android.hardware.camera2.CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) { if (cameraId == flashId) torchOn = enabled }
        }
        private fun ensureCallback(cm: android.hardware.camera2.CameraManager) {
            if (callbackRegistered) return
            try { cm.registerTorchCallback(torchCb, android.os.Handler(android.os.Looper.getMainLooper())); callbackRegistered = true } catch (_: Exception) {}
        }
    }
}

// ============================================================
// WiFi 信息（只读）—— 借鉴橘瓣 WifiInfoTool
// ============================================================
class WifiInfoTool(private val context: Context) : Tool {
    override val name = "wifi_info"
    override val description = "查当前 WiFi 连接信息（名称/信号/链路速率/IP）。只读，不改设置。"
    override val llmDescription = "Current WiFi connection: name, signal, link speed, IP. Read-only."
    override val parameters = JSONObject().apply { put("type", "object"); put("properties", JSONObject()) }
    @Suppress("DEPRECATION")
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            ?: return@withContext ToolResult("无 WiFi 服务", isError = true)
        try {
            if (!wm.isWifiEnabled) return@withContext ToolResult("WiFi 未开启")
            val info = wm.connectionInfo ?: return@withContext ToolResult("当前未连接 WiFi")
            val ssid = info.ssid?.trim('"')?.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
                ?: return@withContext ToolResult("当前未连接 WiFi（或无定位权限拿不到名称）")
            val level = android.net.wifi.WifiManager.calculateSignalLevel(info.rssi, 5)
            val ipInt = info.ipAddress
            val ip = "${ipInt and 0xff}.${(ipInt shr 8) and 0xff}.${(ipInt shr 16) and 0xff}.${(ipInt shr 24) and 0xff}"
            ToolResult("📶 $ssid  信号 $level/4 (${info.rssi}dBm)  链路 ${info.linkSpeed}Mbps  IP $ip")
        } catch (e: SecurityException) {
            ToolResult("拿不到 WiFi 信息（缺 WiFi/定位权限）", isError = true)
        } catch (e: Exception) {
            ToolResult("读 WiFi 信息失败：${e.message}", isError = true)
        }
    }
}

// ============================================================
// Volume Tool
// ============================================================
class VolumeTool(private val context: Context) : Tool {
    override val name = "volume"
    override val description = "调整设备音量。支持媒体、铃声音量。"
    override val llmDescription = "Set device volume (media, ring, alarm, notification)."

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("level", JSONObject().apply {
                put("type", "integer")
                put("description", "0-100 percent")
            })
            put("type", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("media", "ring", "alarm", "notification")))
                put("description", "default media")
            })
        })
        put("required", JSONArray(listOf("level")))
    }

    // AudioManager 从 application Context 就能拿，不需要 Activity。原来走 getTopActivity()，
    // 于是 App 一退后台就「无法获取音频服务」——而语音助手正是常态在后台。
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val level = params.optInt("level", 50)
        val volType = params.optString("type", "media")
        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val streamType = when (volType) {
                "ring" -> AudioManager.STREAM_RING
                "alarm" -> AudioManager.STREAM_ALARM
                "notification" -> AudioManager.STREAM_NOTIFICATION
                else -> AudioManager.STREAM_MUSIC
            }
            val max = audioManager.getStreamMaxVolume(streamType)
            audioManager.setStreamVolume(streamType, level.coerceIn(0, 100) * max / 100, 0)
            ToolResult("${when(volType){"media"->"媒体";"ring"->"铃声";"alarm"->"闹钟";else->"通知"}}音量已设为 $level%")
        } catch (e: SecurityException) {
            // 免打扰模式下调铃声/通知音量需要「勿扰权限」，系统直接抛 SecurityException
            ToolResult("调音量被系统拒绝（多半是开了免打扰）：${e.message}", isError = true)
        } catch (e: Exception) { ToolResult("设置音量失败: ${e.message}", isError = true) }
    }
}

// ============================================================
// App Launcher Tool — 打开应用，重名询问
// ============================================================
class AppLauncherTool(private val context: Context) : Tool {
    override val name = "app_launch"
    override val description = "管理设备上的应用：打开、搜索已安装应用、打开应用内某个界面(activity)，以及装/卸/强停。" +
        "action=launch 按名称或包名打开；action=search 按名称搜应用(留空列出全部)，给了 package_name 则列出该应用的所有 activity；" +
        "action=open_activity 打开指定 activity(需 package_name + activity)；" +
        "action=install 安装工作目录里的 apk（走系统安装器，用户自己点确认，不做静默安装）；" +
        "action=uninstall 卸载(调起系统卸载确认框)；action=stop 强行停止某个应用。后三个每次都会先问过用户。"
    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Manage apps on the device. launch: open one by app name or package. search: find apps by name (empty = list all); with package_name it lists that app's activities. " +
        "open_activity: open one screen, needs package_name + activity. install: install an .apk from your workspace through the system installer (the user confirms; never silent). " +
        "uninstall: package_name, the system shows its own confirm dialog. stop: force-stop a running app by package_name. install/uninstall/stop always ask the user first."

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("launch", "search", "open_activity", "install", "uninstall", "stop")))
                put("description", "launch (default); search = find apps or list activities; open_activity = open one screen; install/uninstall/stop = manage an app")
            })
            put("name", JSONObject().apply {
                put("type", "string")
                put("description", "app name, e.g. 微信 or 设置. Empty on search lists all installed apps")
            })
            put("package_name", JSONObject().apply {
                put("type", "string")
                put("description", "package, e.g. com.tencent.mm. On search, listing its activities. Required for uninstall/stop")
            })
            put("activity", JSONObject().apply {
                put("type", "string")
                put("description", "activity class for open_activity; full name or dot-prefixed short form like .ui.LauncherUI")
            })
            put("path", JSONObject().apply {
                put("type", "string")
                put("description", "for install: the .apk inside your workspace (download it there first with http_request save_to + save_dir=workspace)")
            })
        })
        put("required", JSONArray())
    }

    // 启动 App 只要 Context（配 FLAG_ACTIVITY_NEW_TASK），不要 Activity。原来走 getTopActivity()，
    // 后台就是一句「无法获取上下文」——而「小爱同学，打开微信」这种话本来就多半是在后台说的。
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.Main) {
        val action = params.optString("action", "launch").ifBlank { "launch" }
        val name = params.optString("name", "")
        val pkg = params.optString("package_name", "")
        val activity = params.optString("activity", "")
        try {
            val pm = context.packageManager
            when (action) {
                "search" -> if (pkg.isNotBlank()) listActivities(pm, pkg) else searchApps(pm, name)
                "open_activity" -> openActivity(pkg, activity)
                "install" -> installApk(params.optString("path", ""))
                "uninstall" -> uninstallApp(pm, pkg.ifBlank { resolvePkgByName(pm, name) })
                "stop" -> stopApp(pm, pkg.ifBlank { resolvePkgByName(pm, name) })
                else -> launchApp(pm, name, pkg)
            }
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c   // 授权框挂起期间用户按 STOP，取消必须往上抛
        } catch (e: Exception) { ToolResult("失败: ${e.message}", isError = true) }
    }

    // ============ 装 / 卸 / 强停 ============
    //
    // 三个都是**不可逆或有副作用**的动作，而 app_launch 整体是 STANDARD 级（否则「打开微信」也要天天点同意，
    // 那是纯粹的体验倒退）。所以不动工具等级，改为在动作粒度上单独过一次闸：
    // [ToolPermissionManager.confirmAction] 用**独立的记忆键**（app_install/app_uninstall/app_stop），
    // 用户在这里点「始终允许」只放开这一个动作，不会顺手把整个 app_launch 变成免问。
    // 问不出来（权限系统没起来/没人在场）一律**判拒**：装卸应用不是能"先斩后奏"的事。

    private suspend fun gate(key: String, intent: String, risk: String): String? {
        val d = ToolPermissionManager.confirmAction(
            key = key, level = AndroidPermissionLevel.ACCESSIBILITY, intent = intent, riskNote = risk,
        ) ?: return "当前场景问不到用户，而装/卸/强停应用必须他本人点头，本次没有执行。"
        return if (d == ToolPermissionManager.Decision.ALLOW_ONCE || d == ToolPermissionManager.Decision.ALWAYS_ALLOW) null
        else "用户没有同意这次操作，已取消。别换个说法重试。"
    }

    /** 只给了应用名时把它解析成包名（唯一命中才认——卸错应用是不可逆的，宁可让它再问一句）。 */
    private fun resolvePkgByName(pm: AndroidPm, name: String): String {
        if (name.isBlank()) return ""
        val m = matchApps(pm, name)
        return if (m.size == 1) m[0].packageName else ""
    }

    private fun labelOf(pm: AndroidPm, pkg: String): String =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    /**
     * 安装 apk：**只走系统安装器**，用户在系统那个框上自己点「安装」。不做静默安装——
     * 静默安装要么要 root/Shizuku 的 `pm install`，要么要设备所有者身份，任何一条都不该由一句对话触发。
     */
    private suspend fun installApk(path: String): ToolResult {
        if (path.isBlank()) return ToolResult("要装哪个 apk？给 path（工作目录里的 .apk）", isError = true)
        val f = AiWorkspace.resolve(context, path) ?: return AiWorkspace.deny()
        if (!f.exists() || !f.isFile) return ToolResult("apk 不存在: $path。先用 http_request(save_to=\"x.apk\", save_dir=\"workspace\") 下载到工作目录。", isError = true)
        if (!f.name.endsWith(".apk", true)) return ToolResult("这不是 apk 文件：${f.name}", isError = true)
        gate("app_install", "安装应用安装包：${f.name}（${f.length() / 1024 / 1024}MB，会调起系统安装器）",
            "安装来路不明的 apk 可能带恶意行为。装之前确认这个包是你自己要的。")?.let { return ToolResult(it, isError = true) }
        // Android 8 起每个 App 都要单独拿到「安装未知应用」的许可，没有就先把用户送去那个开关
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            return ToolResult("系统还没允许 Arix 安装应用。已经帮忙打开「安装未知应用」的开关页，请用户给 Arix 打开这个权限，然后再让我装一次。", isError = true)
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", f)
        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        })
        return ToolResult("已调起系统安装器安装 ${f.name}。接下来由用户在系统的安装界面上点确认——装完之前别接着往下做假设。")
    }

    /** 卸载：调起系统卸载确认框（ACTION_DELETE 免权限）。系统那个框本身就是第二道闸。 */
    private suspend fun uninstallApp(pm: AndroidPm, pkg: String): ToolResult {
        if (pkg.isBlank()) return ToolResult("要卸载哪个应用？给 package_name（名字有重名或没命中时我不猜）", isError = true)
        if (pkg == context.packageName) return ToolResult("不能卸载 Arix 自己。", isError = true)
        val exists = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
        if (!exists) return ToolResult("$pkg 没装在这台设备上", isError = true)
        val label = labelOf(pm, pkg)
        gate("app_uninstall", "卸载应用：$label（$pkg）", "卸载会连同该应用的数据一起删掉，删了找不回来。")
            ?.let { return ToolResult(it, isError = true) }
        context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return ToolResult("已调起系统卸载界面：$label（$pkg）。由用户在系统框上确认。")
    }

    /**
     * 强行停止。三条路按效果从强到弱：
     *  1. Shizuku 的 `am force-stop`——真正的强停（等于设置里那个按钮）。
     *  2. `killBackgroundProcesses`——只杀后台进程，前台的杀不掉，而且要 KILL_BACKGROUND_PROCESSES 权限。
     *  3. 都不行就把用户送到该应用的信息页，让他自己点「强行停止」。
     * 一条都不假装成功：说"已停止"而其实没停，比直接说做不到更糟。
     */
    private suspend fun stopApp(pm: AndroidPm, pkg: String): ToolResult {
        if (pkg.isBlank()) return ToolResult("要停哪个应用？给 package_name", isError = true)
        if (pkg == context.packageName) return ToolResult("不能强停 Arix 自己（停了这一轮对话也就没了）。", isError = true)
        if (!runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess) return ToolResult("$pkg 没装在这台设备上", isError = true)
        val label = labelOf(pm, pkg)
        gate("app_stop", "强行停止应用：$label（$pkg）", "被停的应用会丢掉没保存的状态，闹钟/后台任务也会一起断。")
            ?.let { return ToolResult(it, isError = true) }
        if (ShizukuAccess.granted()) {
            // ⚠ execute 整个跑在 Dispatchers.Main（startActivity 要主线程），而跑 shell 是阻塞调用——必须切走
            val r = withContext(Dispatchers.IO) { ShizukuAccess.exec("am force-stop $pkg") }
            // am 权限不足时照样 exit 0，只把 Error 打进输出——别只看退出码（同 open_activity 的教训）
            if (r != null && r.first == 0 && !r.second.contains("Error", true) && !r.second.contains("Permission Denial", true))
                return ToolResult("已强行停止 $label（$pkg）")
        }
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        val killed = try { am?.killBackgroundProcesses(pkg); true } catch (_: SecurityException) { false } catch (_: Exception) { false }
        if (killed) return ToolResult("已杀掉 $label（$pkg）的后台进程。注意这不是完整的「强行停止」：它要是正在前台，或有前台服务，就还活着。")
        runCatching {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        return ToolResult("停不了 $label：没有 Shizuku、系统也不让普通应用杀别人的进程。已经打开它的应用信息页，让用户自己点「强行停止」。", isError = true)
    }

    private fun launchApp(pm: AndroidPm, name: String, pkg: String): ToolResult {
        fun launch(p: String): Boolean {
            val intent = pm.getLaunchIntentForPackage(p)?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return false
            context.startActivity(intent); return true
        }
        if (pkg.isNotBlank()) {
            return if (launch(pkg)) ToolResult("已打开: $pkg")
            else ToolResult("应用 $pkg 未安装或无法启动", isError = true)
        }
        if (name.isBlank()) return ToolResult("要打开哪个应用？给 name 或 package_name", isError = true)
        val matched = matchApps(pm, name)
        return when {
            matched.isEmpty() -> ToolResult("未找到名称包含 '$name' 的应用")
            matched.size == 1 -> {
                if (launch(matched[0].packageName)) ToolResult("已打开: ${pm.getApplicationLabel(matched[0])}")
                else ToolResult("${pm.getApplicationLabel(matched[0])} 无法启动", isError = true)
            }
            else -> {
                val sb = StringBuilder("找到 ${matched.size} 个匹配 '$name' 的应用，请选择:\n")
                matched.take(15).forEachIndexed { i, app ->
                    sb.append("${i + 1}. ${pm.getApplicationLabel(app)} (${app.packageName})\n")
                }
                ToolResult(sb.toString())
            }
        }
    }

    private fun matchApps(pm: AndroidPm, name: String) =
        pm.getInstalledApplications(AndroidPm.GET_META_DATA).let { all ->
            if (name.isBlank()) return@let all
            val exact = all.filter { app ->
                pm.getApplicationLabel(app).toString().contains(name, ignoreCase = true) ||
                    app.packageName.contains(name, ignoreCase = true)
            }
            // 应用名最容易被记错/念错(语音更甚)，精确没命中时用模糊兜一层，好过直接说「没找到」
            if (exact.isNotEmpty()) exact
            else FuzzyMatch.rankBy(name, all, 15) { listOf(pm.getApplicationLabel(it).toString(), it.packageName) }
                .map { it.item }
        }

    /** 搜/列应用。此前 AI 只能「打开」应用，没法先看看装了什么——重名或叫不准名字时就卡住了。 */
    private fun searchApps(pm: AndroidPm, name: String): ToolResult {
        val matched = matchApps(pm, name)
        if (matched.isEmpty()) return ToolResult("未找到名称包含 '$name' 的应用")
        // 列全部时只给能打开的（launcher 应用），否则几百个系统包会把上下文冲垮
        val show = if (name.isBlank()) matched.filter { pm.getLaunchIntentForPackage(it.packageName) != null } else matched
        val sorted = show.sortedBy { pm.getApplicationLabel(it).toString() }
        val cap = 80
        return ToolResult(buildString {
            append(if (name.isBlank()) "已安装的可启动应用 ${sorted.size} 个" else "匹配 '$name' 的应用 ${sorted.size} 个")
            if (sorted.size > cap) append("（只列前 $cap 个）")
            append("：\n")
            sorted.take(cap).forEach { append("· ${pm.getApplicationLabel(it)} (${it.packageName})\n") }
        }.trim())
    }

    /** 列出某应用声明的所有 activity——AI 得先知道有哪些界面，才谈得上打开指定界面。 */
    private fun listActivities(pm: AndroidPm, pkg: String): ToolResult {
        val info = try {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(pkg, AndroidPm.GET_ACTIVITIES)
        } catch (_: Exception) { return ToolResult("应用 $pkg 未安装", isError = true) }
        val acts = info.activities ?: return ToolResult("$pkg 没有声明任何 activity")
        val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        val launcher = runCatching { pm.getLaunchIntentForPackage(pkg)?.component?.className }.getOrNull()
        return ToolResult(buildString {
            append("$label ($pkg) 的 activity 共 ${acts.size} 个")
            if (acts.size > 60) append("（只列前 60 个）")
            append("：\n")
            acts.take(60).forEach { a ->
                append("· ${a.name}")
                if (a.name == launcher) append("  [主界面]")
                if (!a.exported) append("  [未导出，需 Shizuku]")
                append("\n")
            }
        }.trim())
    }

    /** 打开任意 activity。未导出的 activity 直接启动会被系统拒，有 Shizuku 就以 shell 身份 am start 兜底。 */
    private suspend fun openActivity(pkg: String, activity: String): ToolResult {
        if (pkg.isBlank() || activity.isBlank()) return ToolResult("需要 package_name + activity", isError = true)
        val cls = if (activity.startsWith(".")) pkg + activity else activity
        try {
            context.startActivity(Intent().apply {
                component = android.content.ComponentName(pkg, cls)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
            return ToolResult("已打开: $pkg/$cls")
        } catch (e: Exception) {
            if (ShizukuAccess.granted()) {
                val r = ShizukuAccess.exec("am start -n $pkg/$cls")
                // am start 权限不足时照样 exit 0，只把 Error 打进输出——别只看退出码（同 shell 工具的教训）
                if (r != null && r.first == 0 && !r.second.contains("Error", ignoreCase = true) &&
                    !r.second.contains("Permission Denial", ignoreCase = true)) {
                    return ToolResult("已打开(经 Shizuku): $pkg/$cls")
                }
                return ToolResult("打开失败: ${r?.second?.take(200) ?: e.message}", isError = true)
            }
            return ToolResult("打开失败: ${e.message}（未导出的界面需要 Shizuku 才能打开）", isError = true)
        }
    }
}

// Notification Tool 已并入 NotificationTool.kt 的 `notification`（读/回复/按按钮/点开/清除/发送 一个工具全包）。
// 拆成「只读」+「只发」两个工具的结果是：最常用的「回复通知」两个都不管，AI 只能退回 ui_control 点屏幕。

// ============================================================
// Clipboard Tool
// ============================================================
class ClipboardTool(private val context: Context) : Tool {
    override val name = "clipboard"

    /** 剪贴板里常是刚复制的密码/密钥/私聊片段。见 [AndroidPermissionLevel.PRIVATE]。 */
    override val permissionLevel = AndroidPermissionLevel.PRIVATE

    override val description = "读取或设置剪贴板内容。注意：Android 10+ 只有 App 在前台时才读得到剪贴板（系统限制），后台读会返回空。"
    override val llmDescription = "Read or write the clipboard. On Android 10+ reading only works while the app is in the foreground (OS restriction); a background read comes back empty."

    /** 剪贴板里躺的常是刚复制的密码/密钥/私聊片段，read 的结果原样落库就等于跟着备份上云。
     *  见 [SensitiveResultPolicy]。整个工具标掉（连 write 的「已写入…」回执一起），不按 action 区分：
     *  落库那一步只看得到工具名和参数串，要在那里重演本工具的 action 缺省逻辑，改一次这里就悄悄漂一次。
     *  代价只是丢掉一句一次性回执，而漏标一次就是明文泄露。 */
    override val ephemeralResult = true

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("read", "write")))
                put("description", "read or write")
            })
            put("text", JSONObject().apply {
                put("type", "string")
                put("description", "text to write; required for write")
            })
        })
        put("required", JSONArray(listOf("action")))
    }

    // ClipboardManager 从 application Context 就能拿，不需要 Activity（原来走 getTopActivity()，
    // 后台必然拿不到 → 一句「无法获取上下文」）。但**读**剪贴板另有一道系统闸：Android 10+ 起
    // 只有前台 App 读得到，后台读一律返回空——那是 OS 的隐私限制，换 Context 也绕不过，只能说实话。
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.Main) {
        try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            when (params.optString("action")) {
                "read" -> {
                    val clip = cm.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString() ?: ""
                    if (text.isNotBlank()) ToolResult("剪贴板: $text")
                    else if (!com.arix.app.AppForeground.isForeground)
                        ToolResult("读不到剪贴板：Android 10 起只有 App 在前台时才允许读，现在 Arix 在后台。" +
                            "让用户先把 Arix 打开到前台再试。", isError = true)
                    else ToolResult("剪贴板为空")
                }
                "write" -> {
                    val text = params.optString("text", "")
                    if (text.isBlank()) return@withContext ToolResult("请提供要写入的文本", isError = true)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Arix", text))
                    ToolResult("已写入剪贴板: ${text.take(50)}")
                }
                else -> ToolResult("未知操作", isError = true)
            }
        } catch (e: Exception) { ToolResult("剪贴板操作失败: ${e.message}", isError = true) }
    }
}

// ============================================================
// Helper: get current Activity
// ============================================================
internal fun getTopActivity(): android.app.Activity? {
    return try {
        val am = Class.forName("android.app.ActivityThread")
        val method = am.getMethod("currentActivityThread")
        val at = method.invoke(null)
        val field = am.getDeclaredField("mActivities")
        field.isAccessible = true
        val activities = field.get(at) as? Map<*, *> ?: return null
        for (record in activities.values) {
            val clazz = record?.javaClass ?: continue
            val pausedField = try { clazz.getDeclaredField("paused") } catch (_: Exception) { continue }
            pausedField.isAccessible = true
            if (pausedField.getBoolean(record)) continue
            val activityField = try { clazz.getDeclaredField("activity") } catch (_: Exception) { continue }
            activityField.isAccessible = true
            val act = activityField.get(record)
            if (act is android.app.Activity) return act
        }
        null
    } catch (_: Exception) { null }
}
