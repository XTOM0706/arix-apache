package com.arix.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arix.app.theme.LocalXtomAccents
import com.arix.app.ui.XtomField
import com.arix.app.ui.PageScaffold
import com.arix.tool.CloudMarketplace
import com.arix.tool.OperitCompat
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalFoundationApi::class)
@Composable fun OperitPage(scope: kotlinx.coroutines.CoroutineScope, context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    var localPkgs by remember { mutableStateOf(OperitCompat.getLoadedPackages()) }
    var marketItems by remember { mutableStateOf<List<CloudMarketplace.MarketItem>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var apiError by remember { mutableStateOf<String?>(null) }
    var selectedItem by remember { mutableStateOf<CloudMarketplace.MarketItem?>(null) }
    var categoryFilter by remember { mutableStateOf("all") }
    var longPressedItem by remember { mutableStateOf<CloudMarketplace.MarketItem?>(null) }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }   // 当前安装项的下载进度：0..1，-1=不确定(无 Content-Length)
    var tab by remember { mutableStateOf(0) }                 // 0=浏览市场 1=已安装管理（把管理入口做成显眼的 Tab）
    var ghToken by remember { mutableStateOf(GitHubAccount.token(context)) }   // 默认用全局 GitHub 登录的 token（抬高市场 API 限流）
    var ghRepoUrl by remember { mutableStateOf("") }
    var ghInstalling by remember { mutableStateOf(false) }
    var showRiskConfirm by remember { mutableStateOf(false) }
    var ghRiskDetail by remember { mutableStateOf("") }
    var itemComments by remember { mutableStateOf<List<CloudMarketplace.MarketComment>>(emptyList()) }
    var loadingComments by remember { mutableStateOf(false) }

    // 安装路由：skill 且指向**真实 GitHub 仓库**→ installFromGitHub（下 zip、抽 SKILL.md 真正落地 + 安全审查）；
    // 否则 downloadPackage（.toolpkg 沙盒包 / mcp）。此前 skill 一律 downloadPackage 只写个 loadLocalPackages
    // 不读的 stub json → 装了等于没装（用户说的「空壳只能看不能用」）。
    fun installItem(item: CloudMarketplace.MarketItem) {
        val repoUrl = item.downloadUrl.ifBlank { item.htmlUrl }
        val isSkillRepo = item.type == "skill" && repoUrl.contains("github.com") &&
            "/issues/" !in repoUrl && !repoUrl.endsWith(".toolpkg", ignoreCase = true)
        downloadingId = item.id; downloadProgress = -1f
        scope.launch {
            // 装前后的「已加载包数」对比 = 是否真的装上并生效（stub 占位不会让它增长）。
            val before = OperitCompat.getLoadedPackages().size
            val msg: String = if (isSkillRepo) {
                val r = CloudMarketplace.installFromGitHub(repoUrl, context)
                if (r.needsConfirm) { ghRepoUrl = repoUrl; ghRiskDetail = r.riskDetail; showRiskConfirm = true; downloadingId = null; return@launch }
                r.message
            } else {
                val ok = CloudMarketplace.downloadPackage(item, context) { p -> downloadProgress = p }
                val after = OperitCompat.getLoadedPackages().size
                // 真下到了 .toolpkg/.js 就算成功（重装/更新时已加载数不变，不能据此判失败）。
                val realArtifact = item.downloadUrl.endsWith(".toolpkg", true) || item.downloadUrl.endsWith(".js", true)
                when {
                    after > before -> tr("已安装并启用：") + item.name
                    ok && realArtifact -> tr("已安装/更新：") + item.name
                    ok && item.type == "mcp" -> tr("已添加 MCP 引用，需到 MCP 配置填真实端点才可用：") + item.name
                    ok -> tr("该项没有可直接安装的内容（可能只有说明、无 .toolpkg 直链或 SKILL.md 仓库）")
                    else -> tr("安装失败，下载源不可用：") + item.name
                }
            }
            localPkgs = OperitCompat.getLoadedPackages()
            downloadingId = null
            apiError = msg   // 列表页顶部显示
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()  // 任何页面都能立刻看到反馈
        }
    }

    // 进入即自动加载：先拉云端市场（联网，显示 loading），本地包只读缓存（不在主协程跑 refresh 的阻塞 IO/MCP 发现）
    LaunchedEffect(Unit) {
        localPkgs = OperitCompat.getLoadedPackages()
        if (marketItems.isEmpty()) {
            isLoading = true
            marketItems = CloudMarketplace.searchPackages(GitHubAccount.token(context).ifBlank { null })
            apiError = CloudMarketplace.lastFetchError
            isLoading = false
        }
    }

    // 打开详情时按需拉取真实评论（真源 commentsCount 恒为 0，改以 htmlUrl 判定是否可拉）
    LaunchedEffect(selectedItem?.id) {
        val it = selectedItem
        itemComments = emptyList()
        if (it != null && it.htmlUrl.isNotBlank()) {
            loadingComments = true
            itemComments = CloudMarketplace.getIssueComments(it, ghToken.ifBlank { null })
            loadingComments = false
        }
    }

    val filtered = remember(marketItems, categoryFilter, searchQuery) {
        val byCategory = marketItems.filter { categoryFilter == "all" || it.type == categoryFilter }
        val result = if (searchQuery.isBlank()) byCategory
        else {
            val exact = byCategory.filter { it.name.contains(searchQuery, true) || it.description.contains(searchQuery, true) }
            // 包名多是英文缩写，打错一两个字母很常见；模糊结果接在精确之后，不抢位置
            val have = exact.mapTo(HashSet()) { it.id }
            exact + com.arix.tool.FuzzyMatch.rankBy(searchQuery, byCategory.filter { it.id !in have }, 20) {
                listOf(it.name, it.description)
            }.map { it.item }
        }
        // 按 id 去重：让下面 LazyColumn 能用纯 id 作稳定 key（不掺 index），
        // 既治「搜索重排时同项换 key 被迫重新布局」，又避免远端源偶发同 id 造成重复 key 崩溃。
        result.distinctBy { it.id }
    }
    // 常量表 remember，别每次重组重建列表
    val types = remember { listOf("all" to tr("全部"), "script" to tr("脚本"), "package" to tr("沙盒"), "skill" to "Skill", "mcp" to "MCP") }

    if (selectedItem != null) { val item = selectedItem!!
        PageScaffold {
            IconButton(onClick = { selectedItem = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, tr("返回"), tint = scheme.primary) }
            Text(item.name, color = scheme.onSurface, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.type.uppercase(), color = when(item.type){"script"->scheme.secondary;"package"->scheme.primary;"skill"->scheme.primary;else->scheme.tertiary}, fontSize = 10.sp, modifier = Modifier.background(scheme.surfaceContainerHighest, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 2.dp))
                Spacer(Modifier.width(6.dp))
                Text(item.author, color = scheme.primary, fontSize = 12.sp)
                Spacer(Modifier.width(6.dp))
                if (item.downloads > 0) { Text("⬇ ${item.downloads}", color = scheme.onSurfaceVariant, fontSize = 11.sp); Spacer(Modifier.width(6.dp)) }
                if (item.featured) { Text(tr("★精选"), color = scheme.secondary, fontSize = 11.sp); Spacer(Modifier.width(6.dp)) }
                item.reactions["total"]?.let { if (it > 0) Text("❤️ $it", color = scheme.error, fontSize = 11.sp) }
            }
            HorizontalDivider(color = scheme.surfaceContainerHighest, modifier = Modifier.padding(vertical = 8.dp))
            Text(item.description, color = scheme.onSurface, fontSize = 13.sp)
            if (item.tags.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { item.tags.forEach { Text(it, color = scheme.onSurfaceVariant, fontSize = 9.sp, modifier = Modifier.background(scheme.surfaceContainerHighest, RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp)) } } }
            if (item.reactions.isNotEmpty() && item.reactions.any { it.key != "total" }) {
                Spacer(Modifier.height(8.dp))
                Text(tr("反应: %s").format(item.reactions.filter { it.key != "total" }.map { "${it.key} ${it.value}" }.joinToString("  ")), color = scheme.onSurfaceVariant, fontSize = 11.sp)
            }
            // 真实评论区（按需加载；真源无 commentsCount，凭 htmlUrl 判定）
            if (item.htmlUrl.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = scheme.surfaceContainerHighest)
                Text(tr("评论%s").format(if (itemComments.isNotEmpty()) " (${itemComments.size})" else ""), color = scheme.secondary, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(vertical = 6.dp))
                if (loadingComments) {
                    Text(tr("加载评论中…"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                } else if (itemComments.isEmpty()) {
                    Text(tr("暂无法加载评论（可能被限流，可填 Token 重试）"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                } else {
                    itemComments.forEach { c ->
                        Column(Modifier.fillMaxWidth().padding(vertical = 3.dp).background(scheme.surfaceContainerLowest, RoundedCornerShape(8.dp)).padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(c.author, color = scheme.primary, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(6.dp))
                                Text(c.createdAt, color = scheme.onSurfaceVariant, fontSize = 9.sp)
                            }
                            Text(c.body, color = scheme.onSurface, fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { installItem(item) }, colors = ButtonDefaults.buttonColors(containerColor = scheme.primary), shape = RoundedCornerShape(14.dp)) {
                    Text(if (downloadingId == item.id) tr("安装中...") else tr("安装"), color = scheme.onPrimary, fontSize = 13.sp)
                }
                val gh = item.htmlUrl.ifBlank { item.downloadUrl }
                if (gh.isNotBlank()) Button(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW).apply { data = Uri.parse(gh) })
                },
                    colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest), shape = RoundedCornerShape(14.dp)) { Text("GitHub", color = scheme.onSurface, fontSize = 13.sp) }
            }
        }
    } else {
        PageScaffold(scroll = false) {
            Text(tr("扩展市场"), color = scheme.primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            // 两个 Tab：浏览市场 / 已安装管理——把「管理已装扩展」做成显眼入口（用户之前找不到管理界面）
            androidx.compose.material3.TabRow(selectedTabIndex = tab, containerColor = androidx.compose.ui.graphics.Color.Transparent, contentColor = scheme.primary) {
                androidx.compose.material3.Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("浏览市场")) })
                androidx.compose.material3.Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("已安装") + " (${localPkgs.size})") })
            }
            Spacer(Modifier.height(6.dp))

            // 风险确认弹窗（两个 Tab 共用）
            if (showRiskConfirm) AlertDialog(
                onDismissRequest = { showRiskConfirm = false },
                title = { Text(tr("安全审查发现问题"), color = scheme.error, fontWeight = FontWeight.Bold) },
                text = { Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { Text(ghRiskDetail, color = scheme.onSurface, style = MaterialTheme.typography.bodySmall) } },
                confirmButton = {
                    TextButton(onClick = {
                        showRiskConfirm = false; ghInstalling = true; apiError = null
                        scope.launch {
                            val r = CloudMarketplace.installFromGitHub(ghRepoUrl.trim(), context, force = true)
                            apiError = r.message; localPkgs = OperitCompat.getLoadedPackages(); ghInstalling = false
                        }
                    }) { Text(tr("我了解风险，仍要安装"), color = scheme.error) }
                },
                dismissButton = { TextButton(onClick = { showRiskConfirm = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
                containerColor = scheme.surface, shape = RoundedCornerShape(20.dp),
            )

            if (tab == 0) {
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    XtomField(value = searchQuery, onValueChange = { searchQuery = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = tr("搜索扩展…"))
                    Button(onClick = { isLoading = true; apiError = null; scope.launch { marketItems = CloudMarketplace.searchPackages(ghToken.ifBlank { null }, searchQuery); apiError = CloudMarketplace.lastFetchError; isLoading = false } },
                        shape = RoundedCornerShape(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) { Text(tr("刷新")) }
                }
                // 从任意公共 GitHub 仓库装 skill（兼容 Claude 式 SKILL.md）
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    XtomField(value = ghRepoUrl, onValueChange = { ghRepoUrl = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = tr("GitHub 仓库地址装 skill…"))
                    Button(enabled = !ghInstalling && ghRepoUrl.isNotBlank(), onClick = {
                        ghInstalling = true; apiError = null
                        scope.launch {
                            val r = CloudMarketplace.installFromGitHub(ghRepoUrl.trim(), context)
                            ghInstalling = false
                            if (r.needsConfirm) { ghRiskDetail = r.riskDetail; showRiskConfirm = true } else { apiError = r.message; localPkgs = OperitCompat.getLoadedPackages() }
                        }
                    }, colors = ButtonDefaults.buttonColors(containerColor = scheme.surfaceContainerHighest, contentColor = scheme.onSurface), shape = RoundedCornerShape(12.dp)) { Text(if (ghInstalling) tr("装…") else tr("装")) }
                }
                // 分类
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    types.forEach { (k, v) ->
                        androidx.compose.material3.FilterChip(selected = categoryFilter == k, onClick = { categoryFilter = k }, label = { Text(v) })
                    }
                }
                if (isLoading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 4.dp))
                apiError?.let { Text(it, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp).fillMaxWidth().background(scheme.surfaceContainerHigh, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) }
                LazyColumn(Modifier.fillMaxSize().padding(top = 4.dp)) {
                    items(filtered.size, key = { filtered[it].id }, contentType = { filtered[it].type }) { idx ->
                        val item = filtered[idx]
                        val installing = downloadingId == item.id
                        val (srcLabel, srcColor) = when {
                            item.id.startsWith("gh:") -> tr("公共 Skill") to scheme.tertiary
                            item.id.startsWith("loc_") -> tr("本地索引") to scheme.onSurfaceVariant
                            else -> "Operit" to scheme.primary
                        }
                        val typeColor = when (item.type) { "script" -> scheme.secondary; "skill" -> scheme.tertiary; "mcp" -> accents.info; else -> scheme.primary }
                        androidx.compose.material3.Card(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            onClick = { selectedItem = item },
                            colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
                            shape = RoundedCornerShape(14.dp),
                        ) {
                            Column(Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.Extension, null, tint = typeColor, modifier = Modifier.size(22.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(item.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, color = scheme.onSurface, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                        if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Button(onClick = { installItem(item) }, enabled = !installing, shape = RoundedCornerShape(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp), modifier = Modifier.heightIn(min = 34.dp)) {
                                        Text(if (installing) tr("安装中") else tr("安装"), style = MaterialTheme.typography.labelMedium)
                                    }
                                }
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(item.type.uppercase(), color = typeColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(typeColor.copy(alpha = 0.12f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                                    if (item.author.isNotBlank()) Text(item.author, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                    if (item.downloads > 0) Text("⬇ ${item.downloads}", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                                    if (item.featured) Text("★", color = accents.warning, style = MaterialTheme.typography.labelSmall)
                                    Spacer(Modifier.weight(1f))
                                    Text(srcLabel, color = srcColor, style = MaterialTheme.typography.labelSmall)
                                }
                                if (installing) {
                                    Spacer(Modifier.height(6.dp))
                                    if (downloadProgress >= 0f) LinearProgressIndicator(progress = { downloadProgress }, modifier = Modifier.fillMaxWidth())
                                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                            }
                        }
                    }
                }
            } else {
                // 已安装管理
                Text(tr("装好的扩展都在这里：点开看详情，技能可开关，右上角可卸载。"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 6.dp))
                if (localPkgs.isEmpty()) {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Outlined.Extension, null, tint = scheme.surfaceContainerHighest, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(tr("还没安装任何扩展"), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { tab = 0 }) { Text(tr("去市场看看")) }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(localPkgs.size, key = { localPkgs[it].id }, contentType = { "pkg" }) { i ->
                            OperitPkgCard(localPkgs[i]) { localPkgs = OperitCompat.getLoadedPackages() }
                        }
                    }
                }
            }
        }
    }

    if (longPressedItem != null) {
        val lp = longPressedItem!!
        val isLoc = lp.id.startsWith("loc_")
        androidx.compose.material3.AlertDialog(onDismissRequest = { longPressedItem = null },
            title = {
                Column {
                    Text(lp.name, color = scheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("ID: ${lp.id}", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                }
            },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (isLoc) Text(tr("⚠ 本地索引项，需从云端仓库下载"), color = scheme.secondary, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
                Text(tr("发布者: %s").format(lp.author), color = scheme.primary, fontSize = 12.sp)
                Text(tr("类型: %s · 版本: %s").format(lp.type.uppercase(), lp.version), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(lp.description, color = scheme.onSurface, fontSize = 13.sp)
                if (lp.tags.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) { lp.tags.forEach { Text(it, color = scheme.onSurfaceVariant, fontSize = 9.sp, modifier = Modifier.background(scheme.surfaceContainerHighest, RoundedCornerShape(3.dp)).padding(horizontal = 3.dp, vertical = 1.dp)) } } }
                if (lp.commentsCount > 0 || lp.reactions.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (lp.commentsCount > 0) Text(tr("💬 %d 评论").format(lp.commentsCount), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                        lp.reactions["total"]?.let { if (it > 0) Text(tr("❤️ %d 反应").format(it), color = scheme.onSurfaceVariant, fontSize = 11.sp) }
                    }
                    if (lp.reactions.any { it.key != "total" }) {
                        Spacer(Modifier.height(2.dp))
                        Text(lp.reactions.filter { it.key != "total" }.map { "${it.key} ${it.value}" }.joinToString("  "), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                    }
                }
            }},
            confirmButton = { TextButton(onClick = { selectedItem = lp; longPressedItem = null }) { Text(tr("查看详情"), color = scheme.primary) } },
            dismissButton = { Row {
                if (!isLoc) TextButton(onClick = { installItem(lp); longPressedItem = null }) { Text(if (downloadingId == lp.id) tr("安装中...") else tr("安装"), color = scheme.primary) }
                TextButton(onClick = { longPressedItem = null }) { Text(tr("关闭"), color = scheme.onSurfaceVariant) }
            }},
            containerColor = scheme.surface, shape = RoundedCornerShape(20.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable fun OperitPkgCard(pkg: OperitCompat.OperitPackage, onChanged: () -> Unit = {}) {
    val scheme = MaterialTheme.colorScheme
    val accents = LocalXtomAccents.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var showDetail by remember { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }
    // skill 才有启用开关：关掉就不注入它的 SKILL.md 到系统提示（装多了免打架）。沙盒包/MCP 由工具注册决定，无此开关。
    var skillEnabled by remember(pkg.id) { mutableStateOf(if (pkg.type == "skill") com.arix.tool.SkillPrefs.isEnabled(context, pkg.id) else true) }
    val typeColor = when (pkg.type) { "skill" -> scheme.tertiary; "mcp" -> accents.info; else -> scheme.primary }
    androidx.compose.material3.Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
        onClick = { showDetail = true },
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = scheme.surfaceContainer),
        shape = RoundedCornerShape(14.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Extension, null, tint = typeColor, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(pkg.name, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    if (pkg.description.isNotBlank()) Text(pkg.description, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (pkg.type == "skill") androidx.compose.material3.Switch(
                    checked = skillEnabled,
                    onCheckedChange = { on -> skillEnabled = on; com.arix.tool.SkillPrefs.setEnabled(context, pkg.id, on) },
                    modifier = Modifier.scale(0.75f),
                )
                // 显眼的卸载按钮（此前只藏在详情弹窗里，用户找不到管理入口）
                IconButton(onClick = { confirmUninstall = true }) { Icon(Icons.Outlined.DeleteOutline, tr("卸载"), tint = scheme.error, modifier = Modifier.size(20.dp)) }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(pkg.type.uppercase(), color = typeColor, style = MaterialTheme.typography.labelSmall, modifier = Modifier.background(typeColor.copy(alpha = 0.12f), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                if (pkg.tools.isNotEmpty()) Text(tr("%d 个工具").format(pkg.tools.size), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                if (pkg.type == "skill") Text(if (skillEnabled) tr("已启用") else tr("已停用"), color = if (skillEnabled) accents.success else scheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
    if (showDetail) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { showDetail = false },
            title = { Text(pkg.name, color = scheme.onSurface, fontWeight = FontWeight.Bold) },
            text = { Column {
                Text(tr("类型: %s | 来源: %s").format(pkg.type, pkg.source), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                if (pkg.description.isNotBlank()) Text(pkg.description, color = scheme.onSurface, fontSize = 12.sp)
                if (pkg.systemPromptAddition.isNotBlank()) { Spacer(Modifier.height(4.dp)); Text("SystemPrompt: ${pkg.systemPromptAddition.take(200)}", color = scheme.onSurfaceVariant, fontSize = 10.sp, fontFamily = FontFamily.Monospace) }
                if (pkg.tools.isNotEmpty()) { Spacer(Modifier.height(4.dp)); Text(tr("注册工具: %s").format(pkg.tools.joinToString { it.name }), color = scheme.primary, fontSize = 10.sp) }
            }},
            confirmButton = { TextButton(onClick = { showDetail = false }) { Text(tr("确定"), color = scheme.primary) } },
            dismissButton = { TextButton(onClick = { showDetail = false; confirmUninstall = true }) { Text(tr("卸载"), color = scheme.error) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(20.dp))
    }
    if (confirmUninstall) {
        androidx.compose.material3.AlertDialog(onDismissRequest = { confirmUninstall = false },
            title = { Text(tr("卸载扩展"), color = scheme.onSurface, fontWeight = FontWeight.Bold) },
            text = { Text(tr("确定卸载「") + pkg.name + tr("」？会删掉它落地的文件，之后可重新从市场安装。"), color = scheme.onSurfaceVariant, fontSize = 12.sp) },
            confirmButton = { TextButton(onClick = {
                confirmUninstall = false
                scope.launch {
                    val ok = withContext(kotlinx.coroutines.Dispatchers.IO) { OperitCompat.uninstall(context, pkg) }
                    onChanged()
                    android.widget.Toast.makeText(context, if (ok) tr("已卸载：") + pkg.name else tr("卸载失败：") + pkg.name, android.widget.Toast.LENGTH_SHORT).show()
                }
            }) { Text(tr("卸载"), color = scheme.error) } },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
            containerColor = scheme.surface, shape = RoundedCornerShape(20.dp))
    }
}
