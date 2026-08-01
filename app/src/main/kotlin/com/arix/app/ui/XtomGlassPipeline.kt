package com.arix.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ============================================================
// 玻璃背景「模糊一次·多处采样」管线（小米/HokoBlur 式），绕开 haze 在本机点采样放大导致的马赛克。
//
// 背景(壁纸)是静态的（滚动内容不在模糊源里，见 MainActivity 的 hazeSource 说明），所以只需：
//   壁纸下采样(1/DOWNSCALE) → 可分离盒式模糊(≈高斯) → 缓存成一张 ImageBitmap
// 每个玻璃面再用 drawImage（默认 FilterQuality.Low=双线性）按自身位置采样它——一次模糊、处处用，
// 放大走双线性所以平滑不露块（haze 的降采样是点采样才糊成「我的世界」）。
// 只在 壁纸/底色/蒙层/尺寸 变化时重算（挂后台线程），静态时近乎零成本。
// ============================================================

private const val DOWNSCALE = 4       // 下采样倍数：1/4 分辨率上模糊，够省又不至于糊过头
private const val BLUR_R = 6          // 小图上的盒式模糊半径（×3 pass ≈ 高斯）；换算到全屏约 4×6=24 级别
// 尺寸量化网格：折叠大标题(M3)时内容区高度会每帧变几十像素 → 若直接拿它当 key，会每帧重新
// 解码壁纸+三趟盒式模糊，泛滥的后台 blur 任务抢 CPU 让折叠掉帧（用户实测「大标题变小标题时很卡」）。
// 量化到 96px 网格后，一次折叠(~48px)最多跨一格 → 最多重算一次；背景是模糊图，量化几十像素的
// 拉伸差肉眼看不出。
private const val SIZE_QUANT = 96

/** 生成缓存的「下采样+模糊」背景位图；无壁纸(pageBg=null)返回 null（纯色背景玻璃只需薄霜 tint，不用模糊）。 */
@Composable
internal fun rememberGlassBackdrop(pageBg: String?, bgColor: Color, scrimAlpha: Float, sizePx: IntSize): ImageBitmap? {
    val context = LocalContext.current
    val bgArgb = bgColor.toArgb()
    val scrimArgb = bgColor.copy(alpha = scrimAlpha).toArgb()
    // 量化到网格再当 key/尺寸：抹掉折叠时每帧几十像素的抖动，避免重复重解码+重模糊（见 SIZE_QUANT）。
    // 向上取整保证背景不小于内容区（draw 端按原始 srcW/srcH 拉伸，略缩放看不出）。
    val qw = ((sizePx.width + SIZE_QUANT - 1) / SIZE_QUANT) * SIZE_QUANT
    val qh = ((sizePx.height + SIZE_QUANT - 1) / SIZE_QUANT) * SIZE_QUANT
    return produceState<ImageBitmap?>(null, pageBg, bgArgb, scrimArgb, qw, qh) {
        val w = qw; val h = qh
        if (pageBg == null || w < 2 || h < 2) { value = null; return@produceState }
        value = withContext(Dispatchers.Default) {
            runCatching {
                val dw = (w / DOWNSCALE).coerceAtLeast(1)
                val dh = (h / DOWNSCALE).coerceAtLeast(1)
                // 让 Coil 直接解码到目标小尺寸（省内存/带宽）；allowHardware=false 才能 getPixels
                val req = ImageRequest.Builder(context).data(pageBg).allowHardware(false).size(dw, dh).build()
                val src = (context.imageLoader.execute(req) as? SuccessResult)?.drawable?.toBitmap() ?: return@runCatching null
                val out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.drawColor(bgArgb)
                // 壁纸 center-crop 铺满小图（与显示端 ContentScale.Crop 一致）
                val sw = src.width.toFloat(); val sh = src.height.toFloat()
                val scale = maxOf(dw / sw, dh / sh)
                val m = Matrix().apply { setScale(scale, scale); postTranslate((dw - sw * scale) / 2f, (dh - sh * scale) / 2f) }
                canvas.drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
                canvas.drawColor(scrimArgb)   // 蒙层烘进背景（与显示端一致）
                val px = IntArray(dw * dh); out.getPixels(px, 0, dw, 0, 0, dw, dh)
                boxBlur(px, dw, dh, BLUR_R)
                out.setPixels(px, 0, dw, 0, 0, dw, dh)
                out.asImageBitmap()
            }.getOrNull()
        }
    }.value
}

// 换页假模糊：把一张已渲染的位图快照做「下采样 + 盒式模糊」，一次算好（背景线程），逐帧只贴这张图。
// 同玻璃管线思路（模糊一次·多处/多帧用），不走逐帧 RenderEffect。返回下采样后的小图，绘制端 drawImage 放大铺满。
// ⚠ GraphicsLayer.toImageBitmap() 多为 HARDWARE 位图（getPixels 会崩）→ 先 copy 成 ARGB_8888 软件位图。
internal fun fakeBlurBitmap(src: ImageBitmap, downscale: Int = 5, radius: Int = 7): ImageBitmap? = runCatching {
    val ab0 = src.asAndroidBitmap()
    val ab = if (ab0.config == android.graphics.Bitmap.Config.HARDWARE) ab0.copy(android.graphics.Bitmap.Config.ARGB_8888, false) else ab0
    val w = (ab.width / downscale).coerceAtLeast(2)
    val h = (ab.height / downscale).coerceAtLeast(2)
    val small = android.graphics.Bitmap.createScaledBitmap(ab, w, h, true)
    val px = IntArray(w * h); small.getPixels(px, 0, w, 0, 0, w, h)
    boxBlur(px, w, h, radius)
    small.setPixels(px, 0, w, 0, 0, w, h)
    small.asImageBitmap()
}.getOrNull()

// 可分离盒式模糊，跑 3 遍(H+V) ≈ 高斯。在已下采样的小图上跑，且结果被缓存，成本可忽略。
private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int) {
    if (r < 1 || w < 2 || h < 2) return
    val tmp = IntArray(px.size)
    repeat(3) {
        boxH(px, tmp, w, h, r)   // px → tmp
        boxV(tmp, px, w, h, r)   // tmp → px
    }
}

private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val d = r * 2 + 1
    for (y in 0 until h) {
        val row = y * w
        var a = 0; var rr = 0; var g = 0; var b = 0
        for (i in -r..r) { val p = src[row + i.coerceIn(0, w - 1)]; a += (p ushr 24) and 0xff; rr += (p ushr 16) and 0xff; g += (p ushr 8) and 0xff; b += p and 0xff }
        for (x in 0 until w) {
            dst[row + x] = ((a / d) shl 24) or ((rr / d) shl 16) or ((g / d) shl 8) or (b / d)
            val out = src[row + (x - r).coerceIn(0, w - 1)]
            val inc = src[row + (x + r + 1).coerceIn(0, w - 1)]
            a += ((inc ushr 24) and 0xff) - ((out ushr 24) and 0xff)
            rr += ((inc ushr 16) and 0xff) - ((out ushr 16) and 0xff)
            g += ((inc ushr 8) and 0xff) - ((out ushr 8) and 0xff)
            b += (inc and 0xff) - (out and 0xff)
        }
    }
}

private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
    val d = r * 2 + 1
    for (x in 0 until w) {
        var a = 0; var rr = 0; var g = 0; var b = 0
        for (i in -r..r) { val p = src[i.coerceIn(0, h - 1) * w + x]; a += (p ushr 24) and 0xff; rr += (p ushr 16) and 0xff; g += (p ushr 8) and 0xff; b += p and 0xff }
        for (y in 0 until h) {
            dst[y * w + x] = ((a / d) shl 24) or ((rr / d) shl 16) or ((g / d) shl 8) or (b / d)
            val out = src[(y - r).coerceIn(0, h - 1) * w + x]
            val inc = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
            a += ((inc ushr 24) and 0xff) - ((out ushr 24) and 0xff)
            rr += ((inc ushr 16) and 0xff) - ((out ushr 16) and 0xff)
            g += ((inc ushr 8) and 0xff) - ((out ushr 8) and 0xff)
            b += (inc and 0xff) - (out and 0xff)
        }
    }
}
