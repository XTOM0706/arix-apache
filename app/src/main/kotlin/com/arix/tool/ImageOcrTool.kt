package com.arix.tool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.arix.app.CloudApiConfigManager
import com.arix.cloudapi.CloudApiClient
import com.arix.cloudapi.CloudApiConfig
import com.arix.cloudapi.model.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * image_ocr —— 从图片中提取文字(OCR)。
 * 机制对齐 DocReadTool 的「PDF 逐页识图OCR」：取当前激活的「识图(vision)」模型配置，
 * 把整张输入图读成 bytes → base64，构造一条带图片的视觉消息，让模型逐行原样输出图中文字。
 * 不做本地 OCR、不加第三方依赖，纯走视觉模型。
 */
class ImageOcrTool(private val context: Context) : Tool {
    override val name = "image_ocr"
    override val description =
        "从图片中提取文字(OCR)，走视觉模型：给一张图片(本地文件路径、content:// 或 file:// URI 均可)，" +
            "让识图模型把图里的所有文字按原排版逐行原样读出来(不翻译/不解释)。需在模型配置里激活一个视觉模型。"

    // 模型侧英文（见 Tool.llmDescription）
    override val llmDescription = "Extract text from an image via the vision model: give a path or content:// / file:// URI and it transcribes every line as laid out, no translation or commentary. Needs an active vision model."
    override val parameters = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject().apply {
            put("image", JSONObject().apply {
                put("type", "string")
                put("description", "absolute path, content:// or file:// URI, or a workspace-relative path")
            })
        })
        put("required", JSONArray(listOf("image")))
    }

    override val permissionLevel: AndroidPermissionLevel get() = AndroidPermissionLevel.STANDARD

    override suspend fun execute(params: JSONObject): ToolResult = withContext(Dispatchers.IO) {
        val raw = params.optString("image", "").trim()
        if (raw.isBlank()) return@withContext ToolResult("请提供图片(image：本地路径或 content:// / file:// URI)", isError = true)

        // 1) 视觉模型配置（缺失/未激活时友好报错）
        val vcfg = visionConfig()
            ?: return@withContext ToolResult("图片取字需要「识图(vision)」模型：请在 模型配置 里激活一个视觉模型再试。", isError = true)

        // 2) 读图 → bytes → base64（大图先降采样压成 JPEG，防手表 OOM，也让体积可控）
        val b64 = try {
            readImageBase64(raw)
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return@withContext ToolResult("读取图片失败：${e.message}", isError = true)
        } ?: return@withContext ToolResult("找不到或无法读取图片：$raw", isError = true)

        // 3) 一次视觉调用：整张图 OCR，逐行原样输出
        val client = CloudApiClient(vcfg)
        val prompt = "提取这张图片中的所有文字，按原排版逐行原样输出。只输出文字内容本身，不要翻译、不要解释、不要加任何说明或寒暄。"
        val out = StringBuilder()
        val res = try {
            client.streamChat(
                messages = listOf(ChatMessage("user", prompt)),
                images = listOf(b64),
                enableThinking = 0,
                onReasoningChunk = {},
                onContentChunk = { out.append(it) },
            )
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            return@withContext ToolResult("识图模型调用失败：${e.message}", isError = true)
        }
        // streamChat 不抛异常：鉴权/网络/停止等都放在 res.error 里。停止要向上传播，别当成"图里没字"。
        if (res.error == "已停止") throw CancellationException("已停止")

        val text = out.toString().trim()
        if (text.isBlank()) {
            // 有 error 且没收到任何文字 → 是调用失败(如 401/超时/非视觉模型)，如实报错，别误导成"无文字"
            res.error?.let { return@withContext ToolResult("识图模型调用失败：$it", isError = true) }
            return@withContext ToolResult("未从图片中识别到文字(可能是无文字图片)。", isError = true)
        }
        if (text.length > TEXT_CAP) ToolResult(text.take(TEXT_CAP) + "\n…(内容过长，已截断)") else ToolResult(text)
    }

    /** 取当前激活的「识图(vision)」模型配置；未激活视觉模型时返回 null。对齐 DocReadTool。 */
    private suspend fun visionConfig(): CloudApiConfig? {
        val m = CloudApiConfigManager(context)
        val e = m.getActiveByPurpose("vision") ?: return null
        return CloudApiConfig(
            e.baseUrl.trimEnd('/'), e.apiKey.trim(), e.model.trim(),
            e.temperature, e.topP, e.maxTokens, e.frequencyPenalty, e.presencePenalty,
        )
    }

    /**
     * 把 content:// / file:// / 绝对路径 / AI 工作区相对路径 的图片读成 base64。
     * 先解码尺寸做降采样(最长边 ~1600)，再压成 JPEG，避免大图 OOM、并与客户端 data:image/jpeg 一致。
     * 解码失败(非标准图片格式)则回退为原始字节 base64。
     */
    private fun readImageBase64(raw: String): String? {
        val bytes = readBytes(raw) ?: return null
        val downscaled = try { downscaleToJpeg(bytes) } catch (_: Exception) { null }
        val finalBytes = downscaled ?: bytes
        return android.util.Base64.encodeToString(finalBytes, android.util.Base64.NO_WRAP)
    }

    private fun readBytes(raw: String): ByteArray? {
        return when {
            raw.startsWith("content://") || raw.startsWith("file://") ->
                context.contentResolver.openInputStream(Uri.parse(raw))?.use { it.readBytes() }
            raw.startsWith("/") -> File(raw).takeIf { it.exists() && it.isFile }?.readBytes()
            else -> File(AiWorkspace.root(context), raw).takeIf { it.exists() && it.isFile }?.readBytes()
        }
    }

    private fun downscaleToJpeg(bytes: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / sample > MAX_SIDE) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        return try {
            ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.JPEG, 90, bos)
                bos.toByteArray()
            }
        } finally {
            bmp.recycle()
        }
    }

    companion object {
        private const val TEXT_CAP = 20000
        private const val MAX_SIDE = 1600
    }
}
