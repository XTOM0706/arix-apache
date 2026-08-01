package com.arix.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ============================================================
// 联网搜索设置页 —— AnySearch / Perplexica / 深度研究(XSEARCH) / 14 键控引擎
// 从「应用设置」拆出，作为设置中心独立入口。默认全关。
// 视觉对齐「设置中心」：surfaceContainer 圆角卡(20dp)+细描边 + XtomField 输入。
// ============================================================

/** 与设置中心 SettingsGroup 同款卡：surfaceContainer + 20dp 圆角 + 1dp 细描边，全令牌。 */
@Composable
private fun SearchCard(content: @Composable ColumnScope.() -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

/** 卡内「标题 + 说明 + 右侧开关」的一致表头。 */
@Composable
private fun SwitchHeader(title: String, desc: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(2.dp))
            Text(desc, color = scheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** 卡内「标题 + 说明」的一致表头（无开关）。 */
@Composable
private fun PlainHeader(title: String, desc: String) {
    val scheme = MaterialTheme.colorScheme
    Text(title, color = scheme.onSurface, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(2.dp))
    Text(desc, color = scheme.onSurfaceVariant, fontSize = 12.sp)
}

@Composable fun SearchSettingsPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    val fieldStyle = TextStyle(fontSize = 13.sp)
    var anySearchOn by remember { mutableStateOf(SearchApiPrefs.anySearchEnabled(context)) }
    var anySearchKey by remember { mutableStateOf(SearchApiPrefs.anySearchKey(context)) }
    var pplxOn by remember { mutableStateOf(SearchApiPrefs.perplexicaEnabled(context)) }
    var pplxBase by remember { mutableStateOf(SearchApiPrefs.perplexicaBaseUrl(context)) }
    var pplxChatProv by remember { mutableStateOf(SearchApiPrefs.perplexicaChatProvider(context)) }
    var pplxChatModel by remember { mutableStateOf(SearchApiPrefs.perplexicaChatModel(context)) }
    var pplxEmbedProv by remember { mutableStateOf(SearchApiPrefs.perplexicaEmbedProvider(context)) }
    var pplxEmbedModel by remember { mutableStateOf(SearchApiPrefs.perplexicaEmbedModel(context)) }
    fun savePplx() = SearchApiPrefs.setPerplexica(context, pplxOn, pplxBase, pplxChatProv, pplxChatModel, pplxEmbedProv, pplxEmbedModel)
    var xsRid by remember { mutableStateOf(XSearchPrefs.researchConfigId(context)) }
    var xsRounds by remember { mutableStateOf(XSearchPrefs.defaultRounds(context)) }
    val xsCfgMgr = remember { CloudApiConfigManager(context) }
    val xsConfigs by xsCfgMgr.allConfigs.collectAsState(initial = emptyList())
    val keyedIds = remember { com.arix.tool.search.KeyedEngines.ids() }
    val keyedOn = remember { mutableStateMapOf<String, Boolean>().apply { keyedIds.forEach { put(it, SearchApiPrefs.keyedEnabled(context, it)) } } }
    val keyedKeyMap = remember { mutableStateMapOf<String, String>().apply { keyedIds.forEach { put(it, SearchApiPrefs.keyedKey(context, it)) } } }
    val keyedBaseMap = remember { mutableStateMapOf<String, String>().apply { keyedIds.forEach { put(it, SearchApiPrefs.keyedBaseUrl(context, it)) } } }
    var ugcOn by remember { mutableStateOf(SearchApiPrefs.ugcEnabled(context)) }
    var embedOn by remember { mutableStateOf(SearchApiPrefs.embedEnabled(context)) }
    var embedBase by remember { mutableStateOf(SearchApiPrefs.embedBaseUrl(context)) }
    var embedKey by remember { mutableStateOf(SearchApiPrefs.embedKey(context)) }
    var embedModel by remember { mutableStateOf(SearchApiPrefs.embedModel(context)) }
    fun saveEmbed() = SearchApiPrefs.setEmbed(context, embedOn, embedBase, embedKey, embedModel)

    com.arix.app.ui.PageScaffold {
        Text(tr("联网搜索 · 引擎与深度研究"), color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(tr("web_search 默认自适应：简单问题快搜秒回、复杂问题自动多轮深入并综合(带引用)。免配置即用(必应+百度+搜狗)；下面是可选增强引擎，默认全关。"), color = scheme.onSurfaceVariant, fontSize = 12.sp)
        Spacer(Modifier.height(12.dp))

        // 突破 UGC 盲区开关
        SearchCard {
            SwitchHeader(
                tr("突破 UGC 盲区"),
                tr("搜索时并发定向知乎/小红书/B站/微博/贴吧/豆瓣/Reddit/StackOverflow 等社区平台，把被主流引擎埋没的社区内容一并捞出。默认开。"),
                ugcOn,
            ) { ugcOn = it; SearchApiPrefs.setUgcEnabled(context, it) }
        }
        Spacer(Modifier.height(12.dp))

        // 结果相似度重排（embedding，治相关性）
        SearchCard {
            SwitchHeader(
                tr("结果相似度重排（embedding）"),
                tr("深度搜索的结果用 embedding 按与问题的相关性重排，砍掉离题的（搜「瑞安航空」混进来的「瑞安市」会被压下去）。填一个 OpenAI 兼容的 /embeddings 端点，不增包体。默认关。"),
                embedOn,
            ) { embedOn = it; saveEmbed() }
            if (embedOn) {
                Spacer(Modifier.height(10.dp))
                com.arix.app.ui.XtomField(value = embedBase, onValueChange = { embedBase = it; saveEmbed() }, label = tr("端点 baseUrl（到 /v1，如 https://api.openai.com/v1）"), singleLine = true, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                com.arix.app.ui.XtomField(value = embedKey, onValueChange = { embedKey = it; saveEmbed() }, label = tr("API key（可留空）"), singleLine = true, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                com.arix.app.ui.XtomField(value = embedModel, onValueChange = { embedModel = it; saveEmbed() }, label = tr("模型（如 text-embedding-3-small / bge-m3）"), singleLine = true, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(12.dp))

        // 联网搜索 · AnySearch（可选外部服务，默认关；开启后查询/抓取网址会发送到第三方）
        SearchCard {
            SwitchHeader(
                tr("AnySearch 统一搜索（第三方）"),
                tr("外部搜索服务(MCP)，匿名可用、填 key 提速，并可作为网页抓取的防 403 兜底。⚠️ 开启后你的搜索词与需抓取的网址会发送到 anysearch.com（第三方）；关闭则完全不外发。仅作可选引擎，不影响必应/百度等其它搜索。"),
                anySearchOn,
            ) { anySearchOn = it; SearchApiPrefs.setAnySearch(context, it, anySearchKey) }
            if (anySearchOn) {
                Spacer(Modifier.height(10.dp))
                com.arix.app.ui.XtomField(value = anySearchKey, onValueChange = { anySearchKey = it; SearchApiPrefs.setAnySearch(context, true, it) }, label = tr("AnySearch API Key（可留空＝匿名）"), singleLine = true, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(tr("获取 key：anysearch.com/console/api-keys（as_sk_…）。留空＝匿名调用（速率较低）。AI 用 web_search 时传 engine=anysearch 即走它。"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
            }
        }
        Spacer(Modifier.height(12.dp))

        // Perplexica · 自托管 AI 搜索（可选外部服务，默认关）
        SearchCard {
            SwitchHeader(
                tr("Perplexica 自托管搜索"),
                tr("你自部署的 Perplexica 实例。⚠️ 开启后查询会发送到你填的 baseUrl。/api/search 需 chat/embedding 模型：providerId 取自你实例 /api/providers 的 UUID。AI 用 web_search 传 engine=perplexica 即走它。"),
                pplxOn,
            ) { pplxOn = it; savePplx() }
            if (pplxOn) {
                Spacer(Modifier.height(10.dp))
                com.arix.app.ui.XtomField(value = pplxBase, onValueChange = { pplxBase = it; savePplx() }, label = tr("服务地址 baseUrl（如 http://192.168.1.10:3000）"), singleLine = true, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                com.arix.app.ui.XtomField(value = pplxChatProv, onValueChange = { pplxChatProv = it; savePplx() }, label = "chat providerId（UUID）", singleLine = true, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                com.arix.app.ui.XtomField(value = pplxChatModel, onValueChange = { pplxChatModel = it; savePplx() }, label = tr("chat 模型 key（如 gpt-4o-mini）"), singleLine = true, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                com.arix.app.ui.XtomField(value = pplxEmbedProv, onValueChange = { pplxEmbedProv = it; savePplx() }, label = "embedding providerId（UUID）", singleLine = true, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                com.arix.app.ui.XtomField(value = pplxEmbedModel, onValueChange = { pplxEmbedModel = it; savePplx() }, label = tr("embedding 模型 key（如 text-embedding-3-large）"), singleLine = true, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
            }
        }
        Spacer(Modifier.height(12.dp))

        // 深度研究(XSEARCH)：研究模型 + 默认轮数
        SearchCard {
            PlainHeader(
                tr("深度研究 · XSEARCH"),
                tr("多轮检索+综合的研究工具（工具名 deep_search，需在 工具/插件 里启用）。选做子查询扩展/综合用的研究模型与默认轮数。"),
            )
            Spacer(Modifier.height(10.dp))
            Text(tr("研究模型"), color = scheme.onSurfaceVariant, fontSize = 12.sp)
            val opts = listOf(0L to "跟随当前对话模型") + xsConfigs.map { it.id to it.name }
            opts.forEach { (id, label) ->
                Surface(onClick = { xsRid = id; XSearchPrefs.setResearchConfigId(context, id) }, shape = RoundedCornerShape(50), color = if (xsRid == id) scheme.primary else scheme.surfaceContainerHighest, modifier = Modifier.padding(vertical = 2.dp)) {
                    Text(label, color = if (xsRid == id) scheme.onPrimary else scheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("默认轮数：$xsRounds", color = scheme.onSurfaceVariant, fontSize = 12.sp, modifier = Modifier.weight(1f))
                listOf(1, 2, 3, 4, 5).forEach { n ->
                    Surface(onClick = { xsRounds = n; XSearchPrefs.setDefaultRounds(context, n) }, shape = RoundedCornerShape(50), color = if (xsRounds == n) scheme.primary else scheme.surfaceContainerHighest, modifier = Modifier.padding(start = 4.dp)) {
                        Text("$n", color = if (xsRounds == n) scheme.onPrimary else scheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 键控搜索引擎（RikkaHub 系列，14 源）：各自 API key，SearXNG 自托管填 baseUrl
        SearchCard {
            PlainHeader(
                tr("键控搜索引擎（RikkaHub 系列）"),
                tr("各需 API key（SearXNG 自托管填 baseUrl）。开启并填 key 后，web_search 传 engine=<id> 即用，并自动纳入深度研究。key 存本地，默认全关；查询会发送到对应第三方。"),
            )
            keyedIds.forEach { id ->
                val spec = com.arix.tool.search.KeyedEngines.spec(id)
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${spec?.displayName ?: id}  ($id)", color = scheme.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = keyedOn[id] == true, onCheckedChange = { keyedOn[id] = it; SearchApiPrefs.setKeyed(context, id, it, keyedKeyMap[id] ?: "", keyedBaseMap[id] ?: "") })
                }
                if (keyedOn[id] == true) {
                    if (spec?.needsKey == true) {
                        Spacer(Modifier.height(8.dp))
                        com.arix.app.ui.XtomField(value = keyedKeyMap[id] ?: "", onValueChange = { keyedKeyMap[id] = it; SearchApiPrefs.setKeyed(context, id, true, it, keyedBaseMap[id] ?: "") }, label = "API key", singleLine = true, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
                    }
                    if (spec?.needsBaseUrl == true) {
                        Spacer(Modifier.height(8.dp))
                        com.arix.app.ui.XtomField(value = keyedBaseMap[id] ?: "", onValueChange = { keyedBaseMap[id] = it; SearchApiPrefs.setKeyed(context, id, keyedOn[id] == true, keyedKeyMap[id] ?: "", it) }, label = tr("baseUrl（如 http://192.168.1.10:8080）"), singleLine = true, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))

        // 自定义 JS 搜索源：用户写 JS 定义搜索引擎（搜索时 engine=源名 调用）
        var jsSources by remember { mutableStateOf(com.arix.tool.search.JsSearchSources.list(context)) }
        var jsNewName by remember { mutableStateOf("") }
        var jsNewCode by remember { mutableStateOf("") }
        SearchCard {
            PlainHeader(
                tr("自定义 JS 搜索源"),
                tr("写一段 JS 定义搜索引擎：作用域有 query/maxResults 与 xtom.callTool('http_request'|'open_page', 参数)，return [{title,url,snippet}]。存好后搜索 engine=源名 调用。代码可触达本机工具、仅自用，默认关。"),
            )
            jsSources.forEach { s ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, color = scheme.onSurface, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Switch(checked = s.enabled, onCheckedChange = { com.arix.tool.search.JsSearchSources.setEnabled(context, s.name, it); jsSources = com.arix.tool.search.JsSearchSources.list(context) })
                    androidx.compose.material3.TextButton(onClick = { com.arix.tool.search.JsSearchSources.remove(context, s.name); jsSources = com.arix.tool.search.JsSearchSources.list(context) }) { Text(tr("删除"), color = scheme.error, fontSize = 12.sp) }
                }
            }
            Spacer(Modifier.height(10.dp))
            com.arix.app.ui.XtomField(value = jsNewName, onValueChange = { jsNewName = it }, label = tr("源名（engine 标识）"), singleLine = true, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            com.arix.app.ui.XtomField(value = jsNewCode, onValueChange = { jsNewCode = it }, label = tr("JS 代码"), singleLine = false, minLines = 5, textStyle = fieldStyle, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(4.dp))
            androidx.compose.material3.TextButton(onClick = { if (jsNewName.isNotBlank()) { com.arix.tool.search.JsSearchSources.upsert(context, jsNewName.trim(), jsNewCode, true); jsSources = com.arix.tool.search.JsSearchSources.list(context); jsNewName = ""; jsNewCode = "" } }) { Text(tr("保存并启用"), color = scheme.primary, fontSize = 12.sp) }
        }
    }
}
