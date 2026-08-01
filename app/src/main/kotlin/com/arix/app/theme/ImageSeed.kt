package com.arix.app.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ============================================================
// ImageSeed —— 从一张图里提「主色」，结果直接当 ThemeConfig.customSeed 的种子
//
// 为什么自己写而不是引 material-color-utilities（或 Palette）：
// 本项目对 APK 体积极敏感（build.gradle.kts 里为了几 MB 做过一堆取舍），而我们真正需要的
// 只是「一张图里最像强调色的那个颜色」这一个数——种子拿到后整套 M3 配色由 seedScheme 生成，
// 库里那套 CAM16/量化算法的精度在这儿看不出差别，不值一个新依赖。
//
// 算法（在极小的位图上做，见 [decodeSmall]）：
//   1. 丢掉透明像素、接近黑/白/灰的低饱和像素——它们是背景和阴影，不是「这张图的颜色」；
//   2. 按色相分 24 格（15°/格）做加权直方图，权重取饱和度平方：同样面积下更艳的那块更像主色，
//      灰蒙蒙的大背景不该只因为面积大就当选；
//   3. 取权重最大的那一格，格内按**单位圆**求平均色相（359° 与 1° 的算术平均是 180°=青，直接算错色）；
//   4. 饱和度/明度收进一个「能当强调色用」的区间再返回。
// ============================================================

object ImageSeed {

    /** 解码后的边长上限。32×32=1024 像素，够统计主色，且直方图耗时与原图分辨率无关。 */
    private const val TARGET = 32

    /** 色相分格数（15°/格）。太粗会把橙和黄并成一格，太细则同一片颜色被抖动切碎、谁也选不上。 */
    private const val BUCKETS = 24

    /**
     * 从图片里提主色，返回 ARGB（不透明）；解码失败返回 null。
     *
     * **强制切 IO 线程**（不是交给调用方自觉）：这里要开 ContentResolver 流、解码位图，
     * 用户随手选的是一张几千万像素的照片时，在主线程做这一串就是几秒的 ANR。
     */
    suspend fun extract(context: Context, uri: Uri): Int? = withContext(Dispatchers.IO) {
        val bmp = decodeSmall(context, uri) ?: return@withContext null
        try { seedOf(bmp) } finally { bmp.recycle() }
    }

    /**
     * 先读尺寸再按 inSampleSize 降采样解码，最后统一缩到 [TARGET] 见方。
     *
     * 降采样这一步同时解决两件事：**避免整张大图进内存**（inJustDecodeBounds 先探尺寸，
     * 真正解码时只解出小图，不会 OOM），以及把后面的统计开销钉死成常数。
     * inSampleSize 只能取 2 的幂，缩不到刚好，所以再补一次 createScaledBitmap。
     */
    private fun decodeSmall(context: Context, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) null else {
            var sample = 1
            // 留一档余量（降到 TARGET*3 附近再做精确缩放），直接降到 32 会因为 2 的幂跳档丢太多细节
            while (maxOf(w, h) / sample > TARGET * 3) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888   // 统计要读原始 8 位通道，别给 RGB_565
            }
            val raw = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            when {
                raw == null -> null
                raw.width <= TARGET && raw.height <= TARGET -> raw
                else -> Bitmap.createScaledBitmap(raw, TARGET, TARGET, true).also { if (it !== raw) raw.recycle() }
            }
        }
    } catch (_: Throwable) { null }   // OutOfMemoryError 也要接住：不能因为一张怪图把设置页崩掉

    /** 位图 → 主色。三级回退，保证「取色」按钮点下去总有结果，不会点了没反应。 */
    private fun seedOf(bmp: Bitmap): Int? {
        val n = bmp.width * bmp.height
        if (n <= 0) return null
        val px = IntArray(n)
        bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        // 第一遍按正常阈值挑「有颜色」的像素；黑白照/线稿会一个都挑不出来，放宽再来一遍；
        // 还是没有（纯灰度图）就退成整图平均色——给个灰主题也比「点了没变化」强。
        return dominant(px, minSat = 0.25f, minVal = 0.15f, maxVal = 0.95f)
            ?: dominant(px, minSat = 0.08f, minVal = 0.06f, maxVal = 0.99f)
            ?: averageColor(px)
    }

    private fun dominant(px: IntArray, minSat: Float, minVal: Float, maxVal: Float): Int? {
        val weight = DoubleArray(BUCKETS)
        val sinSum = DoubleArray(BUCKETS)
        val cosSum = DoubleArray(BUCKETS)
        val satSum = DoubleArray(BUCKETS)
        val valSum = DoubleArray(BUCKETS)
        val hsv = FloatArray(3)
        var used = 0
        for (c in px) {
            if ((c ushr 24) < 128) continue      // 半透明/全透明像素不算（PNG 图标四周一圈是空的）
            android.graphics.Color.colorToHSV(c, hsv)
            val h = hsv[0]
            val s = hsv[1]
            val v = hsv[2]
            if (s < minSat || v < minVal || v > maxVal) continue
            val w = (s * s).toDouble()           // 饱和度平方：越艳越有资格代表这张图
            val b = ((h / 360f * BUCKETS).toInt()).coerceIn(0, BUCKETS - 1)
            val rad = Math.toRadians(h.toDouble())
            weight[b] += w
            sinSum[b] += Math.sin(rad) * w
            cosSum[b] += Math.cos(rad) * w
            satSum[b] += s * w
            valSum[b] += v * w
            used++
        }
        if (used == 0) return null
        var best = 0
        for (i in 1 until BUCKETS) if (weight[i] > weight[best]) best = i
        val wsum = weight[best]
        if (wsum <= 0.0) return null
        val hue = ((Math.toDegrees(Math.atan2(sinSum[best], cosSum[best])) + 360.0) % 360.0).toFloat()
        // 收进「能当强调色用」的区间：图片主色常常又淡又暗（雾蒙蒙的风景照），原样拿去当种子
        // 会生成一套认不出颜色的配色；上限也压一下，免得荧光色刺眼。
        val sat = (satSum[best] / wsum).toFloat().coerceIn(0.35f, 0.95f)
        val value = (valSum[best] / wsum).toFloat().coerceIn(0.45f, 0.92f)
        return android.graphics.Color.HSVToColor(floatArrayOf(hue, sat, value))
    }

    /** 兜底：不透明像素的平均色。纯灰度图走这里，结果就是一个中性灰种子。 */
    private fun averageColor(px: IntArray): Int? {
        var r = 0L; var g = 0L; var b = 0L; var n = 0L
        for (c in px) {
            if ((c ushr 24) < 128) continue
            r += (c shr 16) and 0xFF
            g += (c shr 8) and 0xFF
            b += c and 0xFF
            n++
        }
        if (n == 0L) return null
        return android.graphics.Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }
}
