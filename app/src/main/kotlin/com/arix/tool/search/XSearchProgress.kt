package com.arix.tool.search

import kotlinx.coroutines.flow.MutableStateFlow

// ============================================================
// XSearchProgressBus —— 深度搜索(deep_search)执行进度总线
// XSearchTool 执行中实时推进度；ChatScreen 观察并显示「研究中」气泡（Grok 式）。
// 单飞进行中的深度搜索一次一个，够用一个全局 StateFlow。
// ============================================================
data class XSearchProgress(
    val phase: String,               // 搜索中 / 评估中 / 综合中
    val round: Int,
    val maxRounds: Int,
    val queries: List<String>,       // 本轮查询词
    val sourceCount: Int,            // 已累计去重来源数
    val recent: List<String>         // 最近找到/读的页面（标题 · 域名）
)

object XSearchProgressBus {
    val state = MutableStateFlow<XSearchProgress?>(null)
    fun update(p: XSearchProgress) { state.value = p }
    fun clear() { state.value = null }
}

fun domainOf(url: String): String = try {
    java.net.URL(if (url.startsWith("http")) url else "https://$url").host.removePrefix("www.")
} catch (_: Exception) { "" }

// 归一化 URL 作去重 key：统一大小写主机、去 www、去协议差异(http/https)、去末尾斜杠、去 #fragment。
// 保留 query（有些页面靠 query 区分文章/分页），避免误删。
fun normalizeUrl(raw: String): String {
    if (raw.isBlank()) return ""
    return try {
        val u = java.net.URL(if (raw.startsWith("http")) raw else "https://$raw")
        val host = u.host.removePrefix("www.").lowercase()
        val path = u.path.trimEnd('/')
        val q = u.query?.takeIf { it.isNotBlank() }?.let { "?$it" } ?: ""
        "$host$path$q"
    } catch (_: Exception) { raw.trim().trimEnd('/').substringBefore('#').lowercase() }
}
