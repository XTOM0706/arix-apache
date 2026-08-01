package com.arix.app

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter

// QR 编解码（zxing 纯 Java）。用于配置的二维码分享/扫码导入——仍是「转化」我们自己的 JSON，不用竞品格式。
object QrKit {
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()

    // 生成二维码位图。容量不足（内容过大）时抛异常，调用方兜底提示。
    fun encode(text: String, size: Int = 720): Bitmap {
        val hints = mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8")
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints)
        val w = matrix.width; val h = matrix.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) pixels[y * w + x] = if (matrix.get(x, y)) BLACK else WHITE
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
    }

    // 从位图解码二维码文本；解不出返回 null。
    fun decode(bmp: Bitmap): String? {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h); bmp.getPixels(px, 0, w, 0, 0, w, h)
        val source = RGBLuminanceSource(w, h, px)
        val hints = mapOf(DecodeHintType.TRY_HARDER to true, DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        return try {
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: Exception) {
            // 反色再试（浅底深码/深底浅码兼容）
            try { MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source.invert())), hints).text }
            catch (_: Exception) { null }
        }
    }
}
