package com.arix.tool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.arix.app.tr
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * image_crop —— 裁剪图片。
 * 给一张图(本地路径 / content:// / file:// URI / AI 工作区相对路径)，加一个裁剪区域，
 * 用 BitmapFactory 解码 + Bitmap.createBitmap 裁出子图，存进 app 私有目录并返回新图路径。
 * 裁剪区域支持两种单位：pixel(像素，默认) 或 ratio(0~1 比例，随分辨率自适应)。
 * 大图先按需降采样再裁，防手表 OOM；越界坐标一律钳制到图内；宽/高缺省时裁到右/下边缘。
 * 结果落在 AI 工作区的 cropped/ 子目录，其它文件/识图工具可直接用返回的相对路径继续处理。
 */
class ImageCropTool(private val context: Context) : Tool {
    override val name = "image_crop"
    override val description =
        "裁剪图片：给一张图(本地绝对路径、content:// 或 file:// URI，或 AI 工作区相对路径)和一个裁剪区域，" +
            "裁出子图并保存到 AI 工作区，返回新图片路径。区域参数 x/y/width/height：unit=pixel(默认,像素) 或 unit=ratio(0~1 比例)；" +
            "越界会自动钳制到图内，width/height 省略则裁到右/下边缘。可选 format=png(默认)/jpg/webp、output=输出文件名。"

    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("image", JSONObject().apply {
                put("type", "string")
                put("description", "图片来源：本地绝对路径、content:// URI、file:// URI 或 AI 工作区相对路径")
            })
            put("x", JSONObject().apply { put("type", "number"); put("description", "裁剪区左上角 X（unit=pixel 为像素、ratio 为 0~1），默认 0") })
            put("y", JSONObject().apply { put("type", "number"); put("description", "裁剪区左上角 Y（unit=pixel 为像素、ratio 为 0~1），默认 0") })
            put("width", JSONObject().apply { put("type", "number"); put("description", "裁剪区宽度（同 unit）；省略/<=0 则裁到右边缘") })
            put("height", JSONObject().apply { put("type", "number"); put("description", "裁剪区高度（同 unit）；省略/<=0 则裁到下边缘") })
            put("unit", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("pixel", "ratio")))
                put("description", "坐标单位：pixel=像素(默认)，ratio=0~1 比例")
            })
            put("format", JSONObject().apply {
                put("type", "string")
                put("enum", JSONArray(listOf("png", "jpg", "webp")))
                put("description", "输出图片格式，默认 png")
            })
            put("output", JSONObject().apply { put("type", "string"); put("description", "输出文件名(可选)，默认自动命名，存到工作区 cropped/ 下") })
        })
        put("required", JSONArray(listOf("image")))
    }

    override val permissionLevel: AndroidPermissionLevel get() = AndroidPermissionLevel.STANDARD

    @Suppress("DEPRECATION")
    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val raw = params.optString("image", "").trim()
        if (raw.isBlank()) return@withContext ToolResult(tr("请提供图片(image：本地路径或 content:// / file:// URI)"), isError = true)

        val unit = params.optString("unit", "pixel").lowercase().let { if (it == "ratio") "ratio" else "pixel" }
        val format = params.optString("format", "png").lowercase().let { if (it in FORMATS) it else "png" }

        // 1) 读原始字节（content:// / file:// / 绝对路径 / 工作区相对路径）
        val bytes = try {
            readBytes(raw)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return@withContext ToolResult(tr("读取图片失败：") + "${e.message}", isError = true)
        } ?: return@withContext ToolResult(tr("找不到或无法读取图片：") + raw, isError = true)

        // 2) 先解尺寸 → 大图按需降采样，防 OOM
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val origW = bounds.outWidth
        val origH = bounds.outHeight
        if (origW <= 0 || origH <= 0) return@withContext ToolResult(tr("这不是有效图片或格式不支持：") + raw, isError = true)

        var sample = 1
        while (maxOf(origW, origH) / sample > MAX_DECODE_SIDE) sample *= 2
        val src: Bitmap = try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
        } catch (e: OutOfMemoryError) {
            return@withContext ToolResult(tr("图片太大，内存不足，无法解码。"), isError = true)
        } ?: return@withContext ToolResult(tr("解码图片失败。"), isError = true)

        try {
            val dw = src.width
            val dh = src.height
            // 3) 把用户给的区域换算到（可能已降采样的）解码位图坐标系
            val hasW = params.has("width") && params.optDouble("width", 0.0) > 0.0
            val hasH = params.has("height") && params.optDouble("height", 0.0) > 0.0
            val (left, top, cw, ch) = if (unit == "ratio") {
                val rx = (params.optDouble("x", 0.0) * dw)
                val ry = (params.optDouble("y", 0.0) * dh)
                val rw = if (hasW) params.optDouble("width", 0.0) * dw else dw - rx
                val rh = if (hasH) params.optDouble("height", 0.0) * dh else dh - ry
                clampRect(rx, ry, rw, rh, dw, dh)
            } else {
                // 像素坐标基于原图分辨率；解码若降采样过，按缩放比映射
                val sx = dw.toDouble() / origW
                val sy = dh.toDouble() / origH
                val rx = params.optDouble("x", 0.0) * sx
                val ry = params.optDouble("y", 0.0) * sy
                val rw = if (hasW) params.optDouble("width", 0.0) * sx else dw - rx
                val rh = if (hasH) params.optDouble("height", 0.0) * sy else dh - ry
                clampRect(rx, ry, rw, rh, dw, dh)
            }
            if (cw <= 0 || ch <= 0) return@withContext ToolResult(tr("裁剪区域无效或完全在图外，无法裁剪。"), isError = true)

            // 4) 裁剪
            val cropped: Bitmap = try {
                Bitmap.createBitmap(src, left, top, cw, ch)
            } catch (e: OutOfMemoryError) {
                return@withContext ToolResult(tr("裁剪区域太大，内存不足。"), isError = true)
            }

            // 5) 存到工作区 cropped/ 子目录并返回路径
            val dir = File(AiWorkspace.root(context), "cropped").apply { if (!exists()) mkdirs() }
            val name = pickOutName(params.optString("output", ""), format)
            val dst = File(dir, name)
            val fmt = when (format) {
                "jpg" -> Bitmap.CompressFormat.JPEG
                "webp" -> Bitmap.CompressFormat.WEBP
                else -> Bitmap.CompressFormat.PNG
            }
            val ok = try {
                dst.outputStream().use { cropped.compress(fmt, 92, it) }
            } finally {
                if (cropped != src) cropped.recycle()
            }
            if (!ok) { dst.delete(); return@withContext ToolResult(tr("保存图片失败（该设备可能不支持该格式）。"), isError = true) }

            val rel = "cropped/$name"
            ToolResult(
                tr("已裁剪图片。") + "\n" +
                    tr("尺寸：") + "${cw}×${ch}\n" +
                    tr("工作区相对路径：") + rel + "\n" +
                    tr("绝对路径：") + dst.absolutePath + "（${dst.length() / 1024}KB）"
            )
        } catch (e: Throwable) {
            ToolResult(tr("裁剪失败：") + "${e.message}", isError = true)
        } finally {
            src.recycle()
        }
    }

    /** 把区域钳制进 [0,dw]×[0,dh]，返回整数的 (left, top, width, height)。 */
    private fun clampRect(rx: Double, ry: Double, rw: Double, rh: Double, dw: Int, dh: Int): Rect4 {
        val left = rx.toInt().coerceIn(0, (dw - 1).coerceAtLeast(0))
        val top = ry.toInt().coerceIn(0, (dh - 1).coerceAtLeast(0))
        val w = rw.toInt().coerceAtLeast(0).coerceAtMost(dw - left)
        val h = rh.toInt().coerceAtLeast(0).coerceAtMost(dh - top)
        return Rect4(left, top, w, h)
    }

    private fun pickOutName(requested: String, format: String): String {
        val base = requested.trim().substringAfterLast('/').substringAfterLast('\\')
        if (base.isNotBlank()) {
            val safe = base.replace(Regex("""[^A-Za-z0-9._\-一-龥]"""), "_")
            return if (safe.contains('.')) safe else "$safe.$format"
        }
        return "crop_${System.currentTimeMillis()}.$format"
    }

    /** 读图为字节：content:// / file:// / 绝对路径 / AI 工作区相对路径。对齐 ImageOcrTool。 */
    private fun readBytes(raw: String): ByteArray? {
        return when {
            raw.startsWith("content://") || raw.startsWith("file://") ->
                context.contentResolver.openInputStream(Uri.parse(raw))?.use { it.readBytes() }
            raw.startsWith("/") -> File(raw).takeIf { it.exists() && it.isFile }?.readBytes()
            else -> File(AiWorkspace.root(context), raw).takeIf { it.exists() && it.isFile }?.readBytes()
        }
    }

    private data class Rect4(val left: Int, val top: Int, val width: Int, val height: Int)

    companion object {
        private const val MAX_DECODE_SIDE = 4096   // 解码位图最长边上限，超过则降采样防 OOM
        private val FORMATS = setOf("png", "jpg", "webp")
    }
}
