package com.arix.app

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

// ============================================================
// 字体市场 —— 一键下载开源字体并应用为自定义字体。曲线救国：curated 直链列表 + 流式下载到 filesDir/fonts。
// 全部开源可分发字体。URL 为最佳努力(GitHub release / jsdelivr 直链)，下不动会优雅报错，不影响「选文件」。
// ============================================================
object FontMarket {
    // cjk=是否含中日韩字形；covers=显示用覆盖标签。用于提示「此字体不支持某语言」。
    data class FontItem(val name: String, val desc: String, val url: String, val ext: String, val sizeMB: Int, val cjk: Boolean, val covers: String)

    val items = listOf(
        FontItem("霞鹜文楷", "开源中文楷体，柔和易读", "https://github.com/lxgw/LxgwWenKai/releases/download/v1.520/LXGWWenKai-Regular.ttf", "ttf", 19, true, "中文·日文·拉丁"),
        FontItem("霞鹜文楷 Lite", "文楷精简版，体积更小", "https://github.com/lxgw/LxgwWenKai-Lite/releases/download/v1.520/LXGWWenKaiLite-Regular.ttf", "ttf", 9, true, "中文·拉丁"),
        FontItem("JetBrains Mono", "等宽编程字体", "https://cdn.jsdelivr.net/gh/JetBrains/JetBrainsMono/fonts/ttf/JetBrainsMono-Regular.ttf", "ttf", 1, false, "拉丁·西里尔·希腊"),
        FontItem("Inter", "现代无衬线", "https://cdn.jsdelivr.net/gh/rsms/inter/docs/font-files/Inter-Regular.otf", "otf", 1, false, "拉丁·西里尔·希腊"),
    )

    // 需要 CJK 字形的界面语言（这些语言下选非 CJK 字体会缺字回退）
    private val cjkLangs = setOf("zh", "zh-TW", "ja", "ko")

    /** 该字体是否覆盖某界面语言；否则 UI 应提示会回退系统字体。 */
    fun coversLang(item: FontItem, langCode: String): Boolean =
        if (langCode in cjkLangs) item.cjk else true   // 非 CJK 语言这几款拉丁/CJK 都含拉丁字形

    /** 当前语言下选此字体的缺字提示，无问题返回 null。 */
    fun warnFor(item: FontItem, langCode: String): String? =
        if (!coversLang(item, langCode)) tr("⚠ 此字体不含%s字形，界面会回退系统字体").format(
            if (langCode.startsWith("zh")) tr("中文") else if (langCode == "ja") tr("日文") else if (langCode == "ko") tr("韩文") else tr("该语言")
        ) else null

    private fun safe(name: String) = name.replace(Regex("[^a-zA-Z0-9_\\u4e00-\\u9fa5-]"), "_")

    /** 下载字体到 filesDir/fonts；成功返回本地路径，失败 null。跟随重定向(GitHub release→对象存储)。 */
    suspend fun download(context: Context, item: FontItem): String? = withContext(Dispatchers.IO) {
        try {
            val dir = File(context.filesDir, "fonts").apply { mkdirs() }
            val out = File(dir, "market_${safe(item.name)}.${item.ext}")
            val conn = URL(item.url).openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 15000; conn.readTimeout = 60000
            conn.setRequestProperty("User-Agent", "Arix/1.0")
            if (conn.responseCode !in 200..299) { conn.disconnect(); return@withContext null }
            conn.inputStream.use { input -> out.outputStream().use { input.copyTo(it) } }
            conn.disconnect()
            // 校验确是字体(防 URL 重定向到 HTML 错误页等非字体文件被当字体应用→崩)
            val ok = out.length() > 1024 && try {
                val tf = android.graphics.Typeface.createFromFile(out)
                tf != null && tf != android.graphics.Typeface.DEFAULT
            } catch (_: Throwable) { false }
            if (ok) out.absolutePath else { out.delete(); null }
        } catch (_: Exception) { null }
    }
}
