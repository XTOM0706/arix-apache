package com.arix.app

import android.content.Context

// ============================================================
// SearchApiPrefs —— 键控搜索服务的开关与密钥（AnySearch / Perplexica 等）
// 原则：这些都是 APK 之外的可选外部服务，默认关闭；开启后查询会发送到第三方。
// 拔掉不影响其余引擎与独立构建。
// ============================================================
object SearchApiPrefs {
    private const val PREFS = "search_api_prefs"
    private fun p(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // —— AnySearch（MCP，匿名可用，填 key 提速）——
    fun anySearchEnabled(c: Context): Boolean = p(c).getBoolean("anysearch_enabled", false)
    fun anySearchKey(c: Context): String = p(c).getString("anysearch_key", "") ?: ""
    fun setAnySearch(c: Context, enabled: Boolean, key: String) {
        p(c).edit().putBoolean("anysearch_enabled", enabled).putString("anysearch_key", key.trim()).apply()
    }

    // —— Perplexica（自托管，填自己的 baseUrl，如 http://192.168.1.10:3000）——
    // /api/search 必填 chat/embedding 模型：providerId(来自你实例 /api/providers 的 UUID) + model key。
    fun perplexicaEnabled(c: Context): Boolean = p(c).getBoolean("perplexica_enabled", false)
    fun perplexicaBaseUrl(c: Context): String = p(c).getString("perplexica_base", "") ?: ""
    fun perplexicaChatProvider(c: Context): String = p(c).getString("perplexica_chat_prov", "") ?: ""
    fun perplexicaChatModel(c: Context): String = p(c).getString("perplexica_chat_model", "") ?: ""
    fun perplexicaEmbedProvider(c: Context): String = p(c).getString("perplexica_embed_prov", "") ?: ""
    fun perplexicaEmbedModel(c: Context): String = p(c).getString("perplexica_embed_model", "") ?: ""
    fun setPerplexica(c: Context, enabled: Boolean, baseUrl: String, chatProv: String, chatModel: String, embedProv: String, embedModel: String) {
        p(c).edit()
            .putBoolean("perplexica_enabled", enabled)
            .putString("perplexica_base", baseUrl.trim().trimEnd('/'))
            .putString("perplexica_chat_prov", chatProv.trim())
            .putString("perplexica_chat_model", chatModel.trim())
            .putString("perplexica_embed_prov", embedProv.trim())
            .putString("perplexica_embed_model", embedModel.trim())
            .apply()
    }

    // —— 通用键控引擎（RikkaHub 系列：Tavily/Brave/Exa/Serper/Jina/Bocha/SearXNG/Firecrawl/... 统一配置）——
    // id = 引擎标识；needsBaseUrl 的（SearXNG/Firecrawl自托管/Ollama）额外填 baseUrl。默认全关。
    fun keyedEnabled(c: Context, id: String): Boolean = p(c).getBoolean("keyed_${id}_on", false)
    fun keyedKey(c: Context, id: String): String = p(c).getString("keyed_${id}_key", "") ?: ""
    fun keyedBaseUrl(c: Context, id: String): String = p(c).getString("keyed_${id}_base", "") ?: ""
    fun setKeyed(c: Context, id: String, enabled: Boolean, key: String, baseUrl: String = "") {
        p(c).edit()
            .putBoolean("keyed_${id}_on", enabled)
            .putString("keyed_${id}_key", key.trim())
            .putString("keyed_${id}_base", baseUrl.trim().trimEnd('/'))
            .apply()
    }

    // —— UGC 盲区突破：搜索时并发对社区平台(知乎/小红书/B站/Reddit…)定向，默认开 ——
    fun ugcEnabled(c: Context): Boolean = p(c).getBoolean("ugc_enabled", true)
    fun setUgcEnabled(c: Context, on: Boolean) = p(c).edit().putBoolean("ugc_enabled", on).apply()

    // —— embedding 重排端点(OpenAI 兼容 /embeddings)：深度搜索结果按相似度重排，砍离题/去重/topK ——
    fun embedEnabled(c: Context): Boolean = p(c).getBoolean("embed_enabled", false)
    fun embedBaseUrl(c: Context): String = p(c).getString("embed_base", "") ?: ""
    fun embedKey(c: Context): String = p(c).getString("embed_key", "") ?: ""
    fun embedModel(c: Context): String = p(c).getString("embed_model", "") ?: ""
    fun setEmbed(c: Context, enabled: Boolean, baseUrl: String, key: String, model: String) {
        p(c).edit()
            .putBoolean("embed_enabled", enabled)
            .putString("embed_base", baseUrl.trim().trimEnd('/'))
            .putString("embed_key", key.trim())
            .putString("embed_model", model.trim())
            .apply()
    }
}
