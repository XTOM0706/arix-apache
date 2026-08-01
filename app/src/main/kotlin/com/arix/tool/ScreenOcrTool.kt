package com.arix.tool

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.MediaStore
import com.arix.app.CloudApiConfigManager
import com.arix.app.ScreenCapture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * screen_ocr —— 抓当前屏幕，然后要么识别其中文字(action=ocr)，要么把图存下来给用户(action=save)。
 *
 * 「截图给用户看」和「截图给模型读」只有最后一步不同，前面「拿到屏幕这张图」是同一件事，
 * 所以合成一个工具加 action 参数，而不是再开一个 take_screenshot —— 工具越少，模型越不容易挑错。
 *
 * 抓图两条路，[ScreenGrab] 自动挑：
 *  - 有 root/Shizuku → `screencap -p`：不弹框、不起前台服务、快一个数量级。
 *    手表小圆屏上那个投屏授权框经常点不到甚至被 ROM 拦，能绕开就绕开。
 *  - 都没有 → 回退 MediaProjection（弹系统投屏授权框 + 前台服务抓帧）。
 *
 * OCR 部分完全复用 [ImageOcrTool] 的视觉模型逻辑（需在模型配置里激活一个视觉模型）。
 */
class ScreenOcrTool(private val context: Context) : Tool {
    override val name = "screen_ocr"
    override val description =
        "截当前屏幕：action=ocr(默认) 把画面里的文字交给识图(vision)模型按原排版逐行原样读出来(不翻译/不解释)，" +
            "需在模型配置里激活视觉模型；action=save 把截图存到系统「下载」目录并返回位置，用于给用户留图/发图，不做识别。" +
            "有 root 或 Shizuku 时走 screencap，不弹任何框、秒出；没有则回退系统「屏幕录制」授权(首次弹框)。" +
            "两条路都只能截到「正在显示」的画面：息屏时截出来是黑图，会直接报错而不是硬截，需要先唤醒屏幕。" +
            "锁屏/后台运行不影响 screencap 这条路。"

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("action", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("ocr", "save")))
                put("description", "ocr=识别屏幕上的文字(默认)；save=把截图保存到「下载」目录给用户，不识别")
            })
            put("filename", JSONObject().apply {
                put("type", "string")
                put("description", "仅 action=save：保存的文件名(不含扩展名也行，会自动补)。不给则按时间自动命名")
            })
        })
    }

    /**
     * 隐私级。**别改回 STANDARD**：本工具在有 root/Shizuku 时走 `screencap`，按上面那段描述——
     * 「不弹任何框、秒出」。整屏截图连着用户当时开着的任何 App（银行、私聊、密码框）一起拿走，
     * 而 STANDARD 会让它跟随默认 ALLOW 的主开关 = 一次都不问。
     * 它不需要无障碍那套基础设施，所以也不该标 ACCESSIBILITY。见 [AndroidPermissionLevel.PRIVATE]。
     */
    override val permissionLevel: AndroidPermissionLevel get() = AndroidPermissionLevel.PRIVATE

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val action = params.optString("action", "ocr").trim().lowercase().ifBlank { "ocr" }
        if (action != "ocr" && action != "save")
            return@withContext ToolResult("action 只能是 ocr(识别文字) 或 save(存图给用户)", isError = true)

        // action=ocr 要把画面交给识图(vision)模型；当前没激活视觉模型时这条路走不通。
        // 与其白截一张图、还可能先弹一次投屏授权框，再到 ImageOcrTool 里报「请激活视觉模型」，
        // 不如在截图前就把 AI 引到无障碍文字读屏：ui_control(action=dump) 不需要 vision，直接拿屏幕文字+可点坐标。
        if (action == "ocr" && CloudApiConfigManager(context).getActiveByPurpose("vision") == null) {
            return@withContext ToolResult(
                "当前没有激活「识图(vision)」模型，看图读屏走不通。改用 ui_control(action=dump)：它走无障碍直接读出屏幕上的文字和可点坐标，不需要视觉模型。" +
                    "若确实要对图像做识别，请先到 模型配置 激活一个视觉模型。",
                isError = true,
            )
        }

        // 1) 抓当前屏幕一帧（特权优先，投屏兜底）
        val path = try {
            ScreenGrab.capture(context).getOrElse {
                return@withContext ToolResult("屏幕截图失败：${it.message}", isError = true)
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return@withContext ToolResult("屏幕截图失败：${e.message}", isError = true)
        }

        try {
            if (action == "save") saveForUser(path, params.optString("filename", "").trim())
            // 复用 ImageOcrTool 的视觉模型 OCR：把这张截图当普通图片识字
            else ImageOcrTool(context).execute(JSONObject().put("image", path))
        } catch (c: CancellationException) {
            throw c
        } finally {
            // 临时截图不落盘留存：save 时已经复制进「下载」了，这份照删
            try { File(path).delete() } catch (_: Exception) {}
        }
    }

    /** action=save：把临时截图搬进系统「下载」目录（对齐 export_csv/保存代码块的惯例，用户在同一个地方找文件）。 */
    private fun saveForUser(tmpPath: String, rawName: String): ToolResult {
        val src = File(tmpPath)
        val bytes = try { src.readBytes() } catch (e: Exception) {
            return ToolResult("读取截图失败：${e.message}", isError = true)
        }
        if (bytes.isEmpty()) return ToolResult("截图是空文件，没保存", isError = true)

        // 扩展名跟着实际抓到的图走：screencap 出 PNG，MediaProjection 兜底出 JPEG
        val ext = if (src.extension.equals("png", true)) "png" else "jpg"
        val fileName = sanitizeFileName(rawName, ext)
        val saved = try {
            saveToDownloads(fileName, bytes, if (ext == "png") "image/png" else "image/jpeg")
        } catch (e: Exception) {
            return ToolResult("保存截图失败：${e.message}", isError = true)
        } ?: return ToolResult("保存失败：无法写入下载目录", isError = true)

        return ToolResult("已保存屏幕截图：$fileName（${bytes.size / 1024} KB）\n保存位置：$saved")
    }

    private fun sanitizeFileName(raw: String, ext: String): String {
        var name = raw.replace(Regex("[\\\\/:*?\"<>|\\r\\n]"), "_").trim().trim('.')
        if (name.isBlank()) name = "screenshot_${System.currentTimeMillis()}"
        if (name.length > 100) name = name.substring(0, 100)   // 防超长文件名触发 ENAMETOOLONG
        if (!name.lowercase().endsWith(".$ext")) name += ".$ext"
        return name
    }

    // 写入下载目录：Android 10+ 走 MediaStore.Downloads（免权限、可见）；更低版本落应用外部下载目录。
    private fun saveToDownloads(fileName: String, bytes: ByteArray, mime: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return null
            // 写失败(含取消/磁盘满)必须删掉这条 pending 记录，否则在下载库里留一条 7 天才自动清的隐形条目
            try {
                context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                    ?: run { context.contentResolver.delete(uri, null, null); return null }
            } catch (e: Throwable) {
                runCatching { context.contentResolver.delete(uri, null, null) }
                throw e
            }
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)   // 解除 pending，别的 App 才看得到文件
            context.contentResolver.update(uri, values, null, null)
            return "下载/$fileName"
        }
        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: return null
        val f = File(dir, fileName)
        f.writeBytes(bytes)
        return f.absolutePath
    }
}

/**
 * 抓一帧屏幕：root/Shizuku 的 `screencap` 优先，MediaProjection 兜底。
 *
 * 为什么优先特权路：MediaProjection 每次都要弹系统投屏授权框（手表圆屏上那个框经常点不到/被 ROM 拦死）、
 * 要起前台服务、要等用户点（[ScreenCapture] 那边挂 90 秒）、还一次只能跑一个；
 * `screencap -p` 是 shell 直接问 SurfaceFlinger 要图，零交互、几百毫秒出结果，也没有并发限制。
 */
private object ScreenGrab {

    /** 成功返回图片绝对路径（cacheDir/screen_ocr 下的临时文件，调用方用完自己删）。 */
    suspend fun capture(context: Context): Result<String> {
        // 息屏时显示器不合成画面，screencap 抓到的是纯黑图、MediaProjection 同样黑屏——
        // 与其交一张黑图让模型瞎猜，不如直接说清楚。锁屏(亮着)和后台都没问题，只有真息屏不行。
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) return Result.failure(
            IllegalStateException("屏幕当前是息屏状态，截屏只能抓正在显示的画面（这时截出来是黑图）。请先唤醒屏幕再试。")
        )

        // 先把 AI 操作高亮浮层收掉再抓：它是我们自己画上去的框，被拍进截图里会当成界面内容
        // 喂给识图/OCR（模型会去"读"那个高亮框），而且 AI 常在连续操作中途截图，正是浮层亮着的时候。
        // delay 是等一帧真正渲染出去——同项目 FloatingScreenOcrButton 早验证过这个时序。
        val overlayWasUp = com.arix.app.UiActionOverlay.suppressForCapture()
        if (overlayWasUp) kotlinx.coroutines.delay(120)

        var privError: String? = null
        val priv = try { PrivilegeProbe.best() } catch (_: Exception) { Privilege.APP }
        if (priv != Privilege.APP) {
            val r = withContext(Dispatchers.IO) { screencap(context, priv) }
            r.onSuccess { return Result.success(it) }
            privError = r.exceptionOrNull()?.message
        }

        // 兜底：老路子 MediaProjection（弹授权框 + 前台服务）
        val fallback = ScreenCapture.captureToFile(context)
        return if (fallback.isFailure && privError != null) Result.failure(
            IllegalStateException("${priv.name.lowercase()} 截屏失败（$privError），回退投屏也失败：${fallback.exceptionOrNull()?.message}")
        ) else fallback
    }

    /**
     * `screencap -p <tmp>` 抓图。先落到 /data/local/tmp（shell UID 写得进、App 也读得到的中转站），
     * 再拷回自家 cache —— App 的 cacheDir 是 0700，shell 直接往里写会 permission denied。
     *
     * 退出码和文件本身都要验：screencap 在某些 ROM 上抓不到会 exit 0 却留个 0 字节文件，只信退出码会谎报成功。
     */
    private fun screencap(context: Context, priv: Privilege): Result<String> {
        val tmp = "/data/local/tmp/xtom_shot_${System.currentTimeMillis()}.png"
        try {
            // 反射的 waitFor 是阻塞的、withTimeout 打断不了（同 ShellTool 的坑），
            // 好在 screencap 就是抓一帧，几百毫秒的事，不会挂住。
            val (code, out) = exec(priv, "screencap -p $tmp")
                ?: return Result.failure(IllegalStateException("特权通道不可用（Shizuku 未授权？）"))
            if (code != 0) return Result.failure(
                IllegalStateException("screencap 退出码 $code${if (out.isNotBlank()) "：${out.take(200)}" else ""}")
            )

            val dir = File(context.cacheDir, "screen_ocr").apply { if (!exists()) mkdirs() }
            val dest = File(dir, "screen_${System.currentTimeMillis()}.png")
            // 让 App 读得到：/data/local/tmp 目录本身对 others 只给了执行位，文件权限得自己放开
            exec(priv, "chmod 644 $tmp")
            val bytes = readBack(priv, tmp)
            if (bytes == null || bytes.isEmpty()) return Result.failure(
                IllegalStateException("screencap 说成功了但没拿到图（文件为空或读不出来）")
            )
            dest.writeBytes(bytes)
            return Result.success(dest.absolutePath)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return Result.failure(IllegalStateException(e.message ?: "screencap 异常"))
        } finally {
            runCatching { exec(priv, "rm -f $tmp") }   // 中转文件不留在公共目录
        }
    }

    /**
     * 把中转文件读回来。直接 File 读优先（零拷贝开销）；
     * 读不到时退一步走 `base64` 管道——个别 ROM/SELinux 策略不让 App 碰 shell 写的文件，
     * 但 shell 自己 cat 出来的内容总是拿得到的（exec 的输出是文本，二进制得先 base64 才不会被吃掉）。
     */
    private fun readBack(priv: Privilege, tmp: String): ByteArray? {
        runCatching { File(tmp).takeIf { it.isFile && it.length() > 0 }?.readBytes() }
            .getOrNull()?.let { return it }
        val (code, out) = exec(priv, "base64 $tmp 2>/dev/null") ?: return null
        if (code != 0 || out.isBlank()) return null
        return runCatching {
            android.util.Base64.decode(out.filterNot { it.isWhitespace() }, android.util.Base64.DEFAULT)
        }.getOrNull()
    }

    /** 按当前特权级别跑命令：root 走 su，Shizuku 走已经调好的反射执行器。 */
    private fun exec(priv: Privilege, cmd: String): Pair<Int, String>? = when (priv) {
        Privilege.SHIZUKU -> ShizukuAccess.exec(cmd)
        Privilege.ROOT -> try {
            val p = ProcessBuilder(listOf("su", "-c", cmd)).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            p.waitFor() to out.trim()
        } catch (e: Exception) { -1 to (e.message ?: "su 执行失败") }
        Privilege.APP -> null
    }
}
