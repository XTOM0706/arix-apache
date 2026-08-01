package com.arix.tool.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URLEncoder

private const val MEDIA_UA =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
private const val MOBILE_UA =
    "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"

// ============================================================
// 图片搜索 —— Bing 图片(拿图片直链 murl)，百度图片兜底
// 复用同包 httpGetHtml / decodeHtmlEntities。返回 SearchResult：url=图片直链、snippet=缩略图。
// ============================================================
object ImageSearch {
    suspend fun search(query: String, max: Int): List<SearchResult> = withContext(Dispatchers.IO) {
        baidu(query, max).ifEmpty { bing(query, max) }   // 百度 acjson 是官方 API，更稳；Bing 图片 HTML 兜底
    }

    private fun bing(query: String, max: Int): List<SearchResult> {
        val html = httpGetHtml("https://cn.bing.com/images/search?q=${URLEncoder.encode(query, "UTF-8")}&form=HDRSC2", MEDIA_UA)
            ?: return emptyList()
        val out = ArrayList<SearchResult>()
        // Bing 图片结果：<a class="iusc" ... m="{&quot;murl&quot;:&quot;直链&quot;,&quot;turl&quot;:&quot;缩略图&quot;,...}">
        for (m in Regex("class=\"iusc\"[^>]*\\sm=\"([^\"]+)\"").findAll(html)) {
            if (out.size >= max) break
            val json = decodeHtmlEntities(m.groupValues[1])
            try {
                val o = JSONObject(json)
                val murl = o.optString("murl")
                val turl = o.optString("turl")
                val t = o.optString("t").ifBlank { o.optString("desc") }
                if (murl.isNotBlank()) out.add(SearchResult(t.ifBlank { "图片" }.take(80), turl, murl, "bing_image"))
            } catch (_: Exception) {}
        }
        return out
    }

    private fun baidu(query: String, max: Int): List<SearchResult> {
        val url = "https://image.baidu.com/search/acjson?tn=resultjson_com&word=${URLEncoder.encode(query, "UTF-8")}&pn=0&rn=$max"
        val json = httpGetHtml(url, MEDIA_UA, "https://image.baidu.com/") ?: return emptyList()
        val out = ArrayList<SearchResult>()
        try {
            val data = JSONObject(json).optJSONArray("data") ?: return out
            for (i in 0 until data.length()) {
                if (out.size >= max) break
                val o = data.optJSONObject(i) ?: continue
                val img = o.optString("middleURL").ifBlank { o.optString("thumbURL") }
                val t = o.optString("fromPageTitleEnc").ifBlank { "图片" }
                if (img.isNotBlank()) out.add(SearchResult(t.take(80), o.optString("thumbURL"), img, "baidu_image"))
            }
        } catch (_: Exception) {}
        return out
    }

    // markdown 图片格式，聊天里可直接渲染出图；同时给直链供 AI 配图/引用
    fun format(results: List<SearchResult>): String = buildString {
        append("🖼️ 图片搜索 · ${results.size} 张\n\n")
        results.forEachIndexed { i, r ->
            append("${i + 1}. ").append(r.title).append("\n")
            append("   ![](").append(r.url).append(")\n")
            append("   直链: ").append(r.url).append("\n\n")
        }
    }
}

// ============================================================
// 视频元数据 —— 各站官方/稳定接口拿标题/UP/时长/封面/页链（不需 yt-dlp）
// B站 x/web-interface/view、YouTube oembed；拿不到返回 null。
// ============================================================
object VideoMeta {
    // 一个入口兜所有站：专站(B站直链/YT信息/抖音无水印) → 通用标准(OG/JSON-LD/oembed)。
    // 全在 open_page 的 mode=video 内部，不新增工具/参数，模型只看到一个工具。
    suspend fun fetch(url: String): String? = withContext(Dispatchers.IO) {
        bilibili(url) ?: youtube(url) ?: douyin(url) ?: universal(url)
    }

    private fun bilibili(url: String): String? {
        val bv = Regex("(BV[0-9A-Za-z]{10})").find(url)?.groupValues?.get(1) ?: return null
        val json = httpGetHtml("https://api.bilibili.com/x/web-interface/view?bvid=$bv", MEDIA_UA, "https://www.bilibili.com/") ?: return null
        return try {
            val d = JSONObject(json).optJSONObject("data") ?: return null
            buildString {
                append("📺 ").append(d.optString("title")).append("\n")
                d.optJSONObject("owner")?.optString("name")?.takeIf { it.isNotBlank() }?.let { append("UP：").append(it).append("\n") }
                d.optInt("duration").takeIf { it > 0 }?.let { append("时长：").append(it).append(" 秒\n") }
                d.optString("pic").takeIf { it.isNotBlank() }?.let { append("封面：").append(it).append("\n") }
                d.optJSONObject("stat")?.optInt("view")?.takeIf { it > 0 }?.let { append("播放：").append(it).append("\n") }
                d.optLong("cid").takeIf { it > 0 }?.let { cid -> biliDirect(bv, cid)?.let { append("视频直链（B站，播放需带 Referer https://www.bilibili.com/）：").append(it).append("\n") } }
                append("页面：https://www.bilibili.com/video/").append(bv).append("\n")
            }
        } catch (_: Exception) { null }
    }

    // B站 WBI 签名 playurl 拿可播放直链（html5+fnval=1 走 MP4 durl；未登录约 360/480p，够用）。
    // 参考公开的 bilibili-API-collect（WBI 签名算法）。
    private fun biliDirect(bv: String, cid: Long): String? {
        val query = BiliWbi.sign(mapOf("bvid" to bv, "cid" to cid.toString(), "qn" to "64", "fnval" to "1", "fourk" to "1", "platform" to "html5")) ?: return null
        val json = httpGetHtml("https://api.bilibili.com/x/player/wbi/playurl?$query", MEDIA_UA, "https://www.bilibili.com/") ?: return null
        return try {
            JSONObject(json).optJSONObject("data")?.optJSONArray("durl")?.optJSONObject(0)?.optString("url")?.takeIf { it.isNotBlank() }
        } catch (_: Exception) { null }
    }

    // 抖音：解析 aweme_id → iesdouyin 分享页 _ROUTER_DATA 取 标题/封面/无水印直链（公开无 key 路径）。
    private fun douyin(url: String): String? {
        if (!url.contains("douyin.com")) return null
        val id = douyinId(url) ?: return null
        val html = httpGetHtml("https://www.iesdouyin.com/share/video/$id/", MOBILE_UA, "https://www.douyin.com/") ?: return null
        val idx = html.indexOf("_ROUTER_DATA"); if (idx < 0) return null
        val start = html.indexOf('{', idx); if (start < 0) return null
        val end = html.indexOf("</script>", start); if (end < 0) return null
        return try {
            val loader = JSONObject(html.substring(start, end).trim().trimEnd(';').trim()).optJSONObject("loaderData") ?: return null
            var item: JSONObject? = null
            val ks = loader.keys()
            while (ks.hasNext()) {
                val list = loader.optJSONObject(ks.next())?.optJSONObject("videoInfoRes")?.optJSONArray("item_list")
                if (list != null && list.length() > 0) { item = list.optJSONObject(0); break }
            }
            val i = item ?: return null
            buildString {
                append("📺 ").append(i.optString("desc").ifBlank { "抖音视频" }).append("\n")
                i.optJSONObject("author")?.optString("nickname")?.takeIf { it.isNotBlank() }?.let { append("作者：").append(it).append("\n") }
                i.optJSONObject("video")?.optJSONObject("cover")?.optJSONArray("url_list")?.optString(0)?.takeIf { it.isNotBlank() }?.let { append("封面：").append(it).append("\n") }
                i.optJSONObject("video")?.optJSONObject("play_addr")?.optJSONArray("url_list")?.optString(0)?.takeIf { it.isNotBlank() }?.let { append("视频直链（抖音无水印，播放需带 UA/Referer，链接会过期）：").append(it.replaceFirst("http://", "https://")).append("\n") }
                append("页面：https://www.douyin.com/video/").append(id).append("\n")
            }
        } catch (_: Exception) { null }
    }

    private fun douyinId(url: String): String? {
        fun idIn(s: String) = Regex("/video/(\\d+)|\\b(\\d{15,21})\\b").find(s)?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }
        idIn(url)?.let { return it }
        // 短链 v.douyin.com/xxx → 手动读 Location 跟随跳转（最多 3 跳），每跳查 id
        var cur = url; var hops = 0
        while (hops++ < 3) {
            try {
                val c = (java.net.URL(cur).openConnection() as java.net.HttpURLConnection).apply {
                    instanceFollowRedirects = false; requestMethod = "GET"; connectTimeout = 8000; readTimeout = 8000
                    setRequestProperty("User-Agent", MOBILE_UA)
                }
                val code = c.responseCode; val loc = c.getHeaderField("Location")
                runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.close() }; c.disconnect()
                idIn(cur)?.let { return it }
                if (loc.isNullOrBlank()) break
                cur = if (loc.startsWith("http")) loc else java.net.URL(java.net.URL(cur), loc).toString()
            } catch (_: Exception) { break }
        }
        return idIn(cur)
    }

    // 通用：靠网页标准兜大量站点——Open Graph(og:video/og:image/og:title) + JSON-LD VideoObject(contentUrl/thumbnailUrl)
    // + oembed 自动发现(<link rel=alternate type=application/json+oembed>)。Vimeo/Dailymotion/新闻站/教程站等都吃这套。
    private fun universal(url: String): String? {
        val html = httpGetHtml(url, MEDIA_UA) ?: return null
        fun meta(prop: String): String? = Regex("<meta[^>]+(?:property|name)=[\"']${Regex.escape(prop)}[\"'][^>]+content=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { decodeHtmlEntities(it) }?.takeIf { it.isNotBlank() }
        val title = meta("og:title") ?: Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotBlank() }
        var cover = meta("og:image")
        var direct = meta("og:video:secure_url") ?: meta("og:video:url") ?: meta("og:video")
        var author: String? = null
        // JSON-LD VideoObject
        Regex("<script[^>]+application/ld\\+json[^>]*>([\\s\\S]*?)</script>", RegexOption.IGNORE_CASE).findAll(html).forEach { mm ->
            try {
                val node = JSONObject(mm.groupValues[1].trim())
                fun scan(o: JSONObject) {
                    if (!o.optString("@type").contains("VideoObject", true)) return
                    if (cover == null) cover = when (val t = o.opt("thumbnailUrl")) { is org.json.JSONArray -> t.optString(0); is String -> t; else -> null }?.takeIf { it.isNotBlank() }
                    if (direct == null) direct = o.optString("contentUrl").ifBlank { o.optString("embedUrl") }.takeIf { it.isNotBlank() }
                }
                scan(node)
                node.optJSONArray("@graph")?.let { for (k in 0 until it.length()) it.optJSONObject(k)?.let { g -> scan(g) } }
            } catch (_: Exception) {}
        }
        // oembed 自动发现
        Regex("<link[^>]+application/json\\+oembed[^>]+href=[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)?.let { oe ->
            httpGetHtml(decodeHtmlEntities(oe), MEDIA_UA)?.let { body ->
                try {
                    val o = JSONObject(body)
                    author = author ?: o.optString("author_name").takeIf { it.isNotBlank() }
                    if (cover == null) cover = o.optString("thumbnail_url").takeIf { it.isNotBlank() }
                } catch (_: Exception) {}
            }
        }
        if (title == null && cover == null && direct == null) return null
        return buildString {
            append("📺 ").append(title ?: "视频").append("\n")
            author?.let { append("作者：").append(it).append("\n") }
            cover?.let { append("封面：").append(it).append("\n") }
            direct?.let { append("视频直链：").append(it).append("\n") }
            append("页面：").append(url).append("\n")
        }
    }

    private fun youtube(url: String): String? {
        if (!url.contains("youtube.com") && !url.contains("youtu.be")) return null
        val json = httpGetHtml("https://www.youtube.com/oembed?format=json&url=${java.net.URLEncoder.encode(url, "UTF-8")}", MEDIA_UA) ?: return null
        return try {
            val o = JSONObject(json)
            buildString {
                append("📺 ").append(o.optString("title")).append("\n")
                o.optString("author_name").takeIf { it.isNotBlank() }?.let { append("作者：").append(it).append("\n") }
                o.optString("thumbnail_url").takeIf { it.isNotBlank() }?.let { append("封面：").append(it).append("\n") }
                append("页面：").append(url).append("\n")
            }
        } catch (_: Exception) { null }
    }
}

// ============================================================
// BiliWbi —— B站 WBI 签名（新版接口 playurl 等必须带 w_rid+wts，否则 -403）。
// 算法：nav 接口取 img_key/sub_key → 固定置换表重排取前 32 位 = mixinKey；
// 参数 + wts 排序拼串 + mixinKey 求 md5 = w_rid。img/sub key 缓存 1 小时。
// 参考公开的 SocialSisterYi/bilibili-API-collect。
// ============================================================
private object BiliWbi {
    private val TAB = intArrayOf(46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52)
    @Volatile private var cache: Pair<Long, String>? = null   // 取得时间 to mixinKey

    private fun md5(s: String): String =
        java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun mixinKey(): String? {
        cache?.let { if (System.currentTimeMillis() - it.first < 3600_000L) return it.second }
        val nav = httpGetHtml("https://api.bilibili.com/x/web-interface/nav", MEDIA_UA, "https://www.bilibili.com/") ?: return null
        return try {
            val wbi = JSONObject(nav).optJSONObject("data")?.optJSONObject("wbi_img") ?: return null
            fun keyOf(u: String) = u.substringAfterLast('/').substringBefore('.')
            val orig = keyOf(wbi.optString("img_url")) + keyOf(wbi.optString("sub_url"))
            if (orig.isBlank()) return null
            val mk = buildString { for (i in TAB) if (i < orig.length) append(orig[i]) }.take(32)
            mk.also { cache = System.currentTimeMillis() to it }
        } catch (_: Exception) { null }
    }

    /** 返回已签名的 query 串（含 w_rid+wts）；拿不到 key 返回 null。 */
    fun sign(params: Map<String, String>): String? {
        val mk = mixinKey() ?: return null
        val wts = (System.currentTimeMillis() / 1000).toString()
        val all = (params + ("wts" to wts)).toSortedMap()
        val q = all.entries.joinToString("&") { (k, v) ->
            "$k=" + URLEncoder.encode(v.filter { c -> c !in "!'()*" }, "UTF-8")
        }
        return "$q&w_rid=" + md5(q + mk)
    }
}
