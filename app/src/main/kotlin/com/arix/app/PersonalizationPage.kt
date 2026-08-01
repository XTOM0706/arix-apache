package com.arix.app

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.arix.app.ui.topChromeGapHeight

// 个性化设置页：外观/主题、身份与头像、页面背景、滚动自动隐藏顶栏。
// 从 AppSettingsPage 抽出，作为设置信息架构重构的第一步。
@Composable fun PersonalizationPage(context: android.content.Context) {
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    // 从系统设置页返回时刷新「流体云权限」等状态（同 PermissionsPage 范式）。
    var resumeTick by remember { mutableStateOf(0) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) resumeTick++
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }
    // 身份/头像（用户头像；AI 头像/偏好已移到角色卡）
    var userName by remember { mutableStateOf(IdentityPrefs.userName(context)) }
    var userAvatar by remember { mutableStateOf(IdentityPrefs.userAvatar(context)) }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            IdentityPrefs.setUserAvatar(context, uri.toString()); userAvatar = uri.toString()
        }
    }
    var autoHide by remember { mutableStateOf(ConfigModePrefs.autoHideBars(context)) }
    // 页面背景
    var pendingBgPage by remember { mutableStateOf("") }
    var bgTick by remember { mutableStateOf(0) }
    // 选完背景图后问一句「要不要按这张图配色」（存的是问哪一页那张）。
    // 不自动改：配色是用户自己一格格调出来的，替他改掉等于毁了他的设置。
    var offerSeedPage by remember { mutableStateOf<String?>(null) }
    /**
     * 从某一页的背景图里提主色 → 写进当前主题的 customSeed，并把配色来源切成「自定义色」。
     * 提色全程在 IO 线程（ImageSeed.extract 内部强制切），这里只在提完后回主线程弹提示。
     */
    fun seedFromPage(page: String) {
        scope.launch {
            // 连查路径都放 IO：get() 会去 /sdcard 上做几次 isFile/length，外部存储的系统调用不便宜
            val uriStr = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PageBackgroundPrefs.get(context, page) }
            if (uriStr == null) {
                android.widget.Toast.makeText(context, tr("这一页还没有背景图"), android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val argb = com.arix.app.theme.ImageSeed.extract(context, android.net.Uri.parse(uriStr))
            if (argb == null) {
                android.widget.Toast.makeText(context, tr("这张图读不出颜色，换一张试试"), android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            val seed = argb.toLong() and 0xFFFFFFFFL
            com.arix.app.theme.ThemeConfigStore.update(
                context,
                com.arix.app.theme.ThemeConfigStore.config.value.copy(
                    colorSource = com.arix.app.theme.ColorSource.CUSTOM,
                    customSeed = seed,
                ),
            )
            android.widget.Toast.makeText(context, tr("已按图片主色配色") + "  #%06X".format(argb and 0xFFFFFF), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val bgPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && pendingBgPage.isNotBlank()) {
            try { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            val page = pendingBgPage
            // 大图 copyTo 搬到 IO 线程：选一张千万像素照片会在主线程拷几秒→ANR。拷完回主线程刷新。
            scope.launch {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { PageBackgroundPrefs.set(context, page, uri.toString()) }
                bgTick++
                // 聊天/通用这两张是「满屏都看得见」的，选完顺手问一句要不要按它配色；
                // 其余页面不打扰，想要就点那一行的取色按钮。
                // （旧行为是自动切成「壁纸动态色」——那取的是**系统壁纸**的色，跟他刚选的这张图没关系，
                //   看起来像生效了其实是假的，已删。）
                if (page == "chat" || page == PageBackgroundPrefs.KEY_ALL) offerSeedPage = page
            }
        }
    }
    // 自定义字体选择器：选 ttf/otf → 拷进 filesDir/fonts → 设为 CUSTOM
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val path = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val dir = java.io.File(context.filesDir, "fonts").apply { mkdirs() }
                    val ext = (context.contentResolver.getType(uri)?.substringAfterLast('/') ?: "").let { if (it == "otf" || it == "sfnt") it else "ttf" }
                    // 先写进临时文件校验，别直接覆盖正在用的 custom_font.*——否则选到非字体文件时，
                    // 覆盖+校验失败+delete 会把用户原本好用的字体一起删掉（config 仍指向已不存在的路径→全局字体消失）。
                    val tmp = java.io.File(dir, "custom_font_incoming.$ext")
                    context.contentResolver.openInputStream(uri)?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    // 校验确是字体，否则 Compose 解析时会崩全屏
                    val valid = tmp.length() > 0 && try { android.graphics.Typeface.createFromFile(tmp).let { it != null && it != android.graphics.Typeface.DEFAULT } } catch (_: Throwable) { false }
                    if (valid) {
                        val dest = java.io.File(dir, "custom_font.$ext")   // 校验通过才替换正在用的字体
                        if (dest.exists()) dest.delete()
                        if (tmp.renameTo(dest)) dest.absolutePath
                        else { tmp.copyTo(dest, overwrite = true); tmp.delete(); dest.absolutePath }
                    } else { tmp.delete(); "" }   // 失败只删临时文件，原字体不动
                } catch (_: Exception) { "" }
            }
            if (path.isNotBlank()) {
                val t = com.arix.app.theme.ThemeConfigStore.config.value
                com.arix.app.theme.ThemeConfigStore.update(context, t.copy(font = com.arix.app.theme.FontChoice.CUSTOM, customFontPath = path))
                android.widget.Toast.makeText(context, tr("已应用自定义字体"), android.widget.Toast.LENGTH_SHORT).show()
            } else android.widget.Toast.makeText(context, tr("字体加载失败（请选 ttf/otf 字体文件）"), android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    var showFontMarket by remember { mutableStateOf(false) }
    var fontDownloading by remember { mutableStateOf<String?>(null) }
    // 主题包二维码分享/扫码导入
    var themeQr by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val themeQrImport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                try {
                    val bmp = context.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@withContext tr("读取失败")
                    val text = QrKit.decode(bmp) ?: return@withContext tr("未识别到二维码")
                    // 加进库而不是覆盖当前：他还没看见这套长什么样，不能先把他正用着的换掉
                    com.arix.app.theme.ThemeConfigStore.add(context, com.arix.app.theme.ThemeConfig.fromJson(text))
                    tr("已加入主题库")
                } catch (e: Exception) { tr("导入失败") + ": ${e.message}" }
            }
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState())) {
        Spacer(Modifier.topChromeGapHeight())   // 顶部悬浮玻璃让位：在滚动内容里，内容能从玻璃下滑过去化开
        // 身份与头像（默认展开：最常改）
        ExpandSection(tr("身份与头像"), null, scheme, initiallyOpen = true) {
                com.arix.app.ui.XtomField(value = userName, onValueChange = { userName = it; IdentityPrefs.setUserName(context, it) }, label = tr("我的名称"), singleLine = true, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp), modifier = Modifier.fillMaxWidth())
                Spacer(modifier = Modifier.height(8.dp))
                // FlowRow + heightIn：头像+按钮+清除挤一行，中文放得下，译文（如德语"Mein Avatar auswählen"）放不下就换行，别把「清除」顶出屏幕
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                ) {
                    Avatar(uri = userAvatar, fallback = userName.ifBlank { tr("我") })
                    Button(onClick = { avatarPicker.launch(arrayOf("image/*")) }, shape = MaterialTheme.shapes.large, modifier = Modifier.align(Alignment.CenterVertically).heightIn(min = 32.dp)) { Text(tr("选择我的头像"), fontSize = 11.sp) }
                    if (userAvatar != null) TextButton(onClick = { IdentityPrefs.setUserAvatar(context, null); userAvatar = null }, modifier = Modifier.align(Alignment.CenterVertically)) { Text(tr("清除"), color = scheme.onSurfaceVariant, fontSize = 11.sp) }
                }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 外观 / 主题
        ExpandSection(tr("外观 / 主题"), tr("配色·明暗·字体·圆角·动效·玻璃，改任意项即时生效"), scheme) {
                // 语言 / Language（i18n 样板：改成 English 即时切全 App；tr("中文") 目录见 I18n.kt / I18nEn.kt）
                val curLang by com.arix.app.I18n.lang.collectAsState()
                Text(tr("语言 / Language"), color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 4.dp, bottom = 3.dp))
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    com.arix.app.I18n.Lang.entries.forEach { l ->
                        androidx.compose.material3.Surface(onClick = { com.arix.app.I18n.set(context, l) }, shape = RoundedCornerShape(50), color = if (curLang == l) scheme.primary else scheme.surfaceContainerHighest, modifier = Modifier.padding(end = 6.dp)) {
                            Text(l.label, color = if (curLang == l) scheme.onPrimary else scheme.onSurface, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                val promptLangV by com.arix.app.PromptLangPrefs.version.collectAsState()
                com.arix.app.ui.SettingsChoiceRow(
                    title = tr("提示词语言"),
                    subtitle = tr("给 AI 的提示词用中文还是英文。英文更省 token（实测 28~43%），中文更贴合中文模型。只影响给模型看的文本，界面语言不受影响。"),
                    options = listOf("zh" to tr("中文"), "en" to tr("英文")),
                    selected = remember(promptLangV) { com.arix.app.PromptLangPrefs.get(context) },
                    onSelect = { com.arix.app.PromptLangPrefs.set(context, it) },
                )
                val theme by com.arix.app.theme.ThemeConfigStore.config.collectAsState()
                // ── 主题库：多套具名主题随时切。下面所有的配色/字体/圆角开关改的都是「当前这套」。
                val themes by com.arix.app.theme.ThemeConfigStore.themes.collectAsState()
                var menuFor by remember { mutableStateOf<String?>(null) }                                   // 展开了哪张卡的 ⋯ 菜单
                var renameFor by remember { mutableStateOf<com.arix.app.theme.ThemeConfig?>(null) }
                var deleteFor by remember { mutableStateOf<com.arix.app.theme.ThemeConfig?>(null) }
                var saveAsOpen by remember { mutableStateOf(false) }
                var nameDraft by remember { mutableStateOf("") }
                val sysDark = androidx.compose.foundation.isSystemInDarkTheme()
                Text(tr("主题库"), color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
                Text(tr("点一张即切换。下面的配色/字体/圆角等改的都是当前这套主题。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(bottom = 4.dp))
                Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                    themes.forEach { t ->
                        val isCurrent = t.id == theme.id
                        // 缩略配色：直接问真正的解析器要，卡片上看到的就是应用后的样子。
                        // remember 到 (t, 明暗) 上：WALLPAPER 那条分支要去读系统壁纸取色，不能每次重组都跑。
                        val prev = remember(t, sysDark) {
                            val d = when (t.darkMode) {
                                com.arix.app.theme.DarkMode.DARK -> true
                                com.arix.app.theme.DarkMode.LIGHT -> false
                                com.arix.app.theme.DarkMode.SYSTEM -> sysDark
                            }
                            com.arix.app.theme.resolveColorScheme(context, t, d)
                        }
                        Box(modifier = Modifier.padding(end = 8.dp)) {
                            androidx.compose.material3.Surface(
                                onClick = { com.arix.app.theme.ThemeConfigStore.applyTheme(context, t.id) },
                                shape = RoundedCornerShape(14.dp),
                                color = prev.surfaceContainer,
                                border = androidx.compose.foundation.BorderStroke(if (isCurrent) 2.dp else 1.dp, if (isCurrent) scheme.primary else scheme.outlineVariant),
                                modifier = Modifier.width(116.dp),
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Row {
                                        listOf(prev.primary, prev.secondary, prev.tertiary).forEach { c ->
                                            Box(modifier = Modifier.padding(end = 4.dp).size(16.dp).clip(RoundedCornerShape(50)).background(c))
                                        }
                                    }
                                    Text(t.name, color = prev.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                                    Text(if (isCurrent) tr("当前") else " ", color = prev.primary, fontSize = 9.sp)
                                }
                            }
                            androidx.compose.material3.IconButton(onClick = { menuFor = t.id }, modifier = Modifier.align(Alignment.TopEnd).size(26.dp)) {
                                androidx.compose.material3.Icon(Icons.Outlined.MoreVert, contentDescription = tr("更多"), tint = prev.onSurfaceVariant, modifier = Modifier.size(15.dp))
                            }
                            androidx.compose.material3.DropdownMenu(expanded = menuFor == t.id, onDismissRequest = { menuFor = null }) {
                                androidx.compose.material3.DropdownMenuItem(text = { Text(tr("应用"), fontSize = 12.sp) }, onClick = { menuFor = null; com.arix.app.theme.ThemeConfigStore.applyTheme(context, t.id) })
                                androidx.compose.material3.DropdownMenuItem(text = { Text(tr("重命名"), fontSize = 12.sp) }, onClick = { menuFor = null; nameDraft = t.name; renameFor = t })
                                // 库不能删空：只剩一套时禁用，不然进来就没主题可用
                                androidx.compose.material3.DropdownMenuItem(enabled = themes.size > 1, text = { Text(tr("删除"), fontSize = 12.sp, color = if (themes.size > 1) scheme.error else scheme.onSurfaceVariant) }, onClick = { menuFor = null; deleteFor = t })
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // 另存为：把当前调好的样子留个档，再接着折腾也不怕改回不去
                    TextButton(onClick = { nameDraft = tr("我的主题"); saveAsOpen = true }) { Text(tr("另存为新主题"), color = scheme.primary, fontSize = 12.sp) }
                }
                if (saveAsOpen) androidx.compose.material3.AlertDialog(
                    onDismissRequest = { saveAsOpen = false },
                    title = { Text(tr("另存为新主题"), color = scheme.onSurface) },
                    text = { com.arix.app.ui.XtomField(value = nameDraft, onValueChange = { nameDraft = it }, label = tr("主题名称"), singleLine = true, modifier = Modifier.fillMaxWidth()) },
                    confirmButton = { TextButton(onClick = { saveAsOpen = false; com.arix.app.theme.ThemeConfigStore.saveAs(context, nameDraft) }) { Text(tr("保存"), color = scheme.primary) } },
                    dismissButton = { TextButton(onClick = { saveAsOpen = false }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
                    containerColor = scheme.surface, shape = RoundedCornerShape(24.dp),
                )
                renameFor?.let { t ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { renameFor = null },
                        title = { Text(tr("重命名主题"), color = scheme.onSurface) },
                        text = { com.arix.app.ui.XtomField(value = nameDraft, onValueChange = { nameDraft = it }, label = tr("主题名称"), singleLine = true, modifier = Modifier.fillMaxWidth()) },
                        confirmButton = { TextButton(onClick = { com.arix.app.theme.ThemeConfigStore.rename(context, t.id, nameDraft); renameFor = null }) { Text(tr("保存"), color = scheme.primary) } },
                        dismissButton = { TextButton(onClick = { renameFor = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
                        containerColor = scheme.surface, shape = RoundedCornerShape(24.dp),
                    )
                }
                deleteFor?.let { t ->
                    androidx.compose.material3.AlertDialog(
                        onDismissRequest = { deleteFor = null },
                        title = { Text(tr("删除主题"), color = scheme.onSurface) },
                        text = { Text(tr("删除「%s」？删不掉的东西没有，但这套的设置就没了。").format(t.name), color = scheme.onSurfaceVariant, fontSize = 12.sp) },
                        confirmButton = { TextButton(onClick = { com.arix.app.theme.ThemeConfigStore.delete(context, t.id); deleteFor = null }) { Text(tr("删除"), color = scheme.error) } },
                        dismissButton = { TextButton(onClick = { deleteFor = null }) { Text(tr("取消"), color = scheme.onSurfaceVariant) } },
                        containerColor = scheme.surface, shape = RoundedCornerShape(24.dp),
                    )
                }
                @Composable fun ThemeRow(label: String, items: List<Triple<String, Boolean, () -> Unit>>) {
                    Text(label, color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
                    Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        items.forEach { (t, sel, onClick) ->
                            androidx.compose.material3.Surface(onClick = onClick, shape = RoundedCornerShape(50), color = if (sel) scheme.primary else scheme.surfaceContainerHighest, modifier = Modifier.padding(end = 6.dp)) {
                                Text(t, color = if (sel) scheme.onPrimary else scheme.onSurface, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp))
                            }
                        }
                    }
                }
                ThemeRow(tr("配色来源"), listOf(
                    Triple(tr("壁纸动态色"), theme.colorSource == com.arix.app.theme.ColorSource.WALLPAPER) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(colorSource = com.arix.app.theme.ColorSource.WALLPAPER)) },
                    Triple(tr("自定义色"), theme.colorSource == com.arix.app.theme.ColorSource.CUSTOM) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(colorSource = com.arix.app.theme.ColorSource.CUSTOM)) },
                    Triple(tr("品牌配色"), theme.colorSource == com.arix.app.theme.ColorSource.FALLBACK) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(colorSource = com.arix.app.theme.ColorSource.FALLBACK)) },
                    Triple("系统Monet", theme.colorSource == com.arix.app.theme.ColorSource.MONET) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(colorSource = com.arix.app.theme.ColorSource.MONET)) },
                    Triple(tr("海洋"), theme.colorSource == com.arix.app.theme.ColorSource.OCEAN) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(colorSource = com.arix.app.theme.ColorSource.OCEAN)) },
                ))
                // HSV 取色器：选「自定义色」时出现，拖动即调主色（松手提交，全 App 实时换色）
                if (theme.colorSource == com.arix.app.theme.ColorSource.CUSTOM) {
                    var h by remember { mutableStateOf(FloatArray(3).also { android.graphics.Color.colorToHSV((0xFF000000.toInt() or (theme.customSeed.toInt() and 0xFFFFFF)), it) }[0]) }
                    var s by remember { mutableStateOf(FloatArray(3).also { android.graphics.Color.colorToHSV((0xFF000000.toInt() or (theme.customSeed.toInt() and 0xFFFFFF)), it) }[1]) }
                    var v by remember { mutableStateOf(FloatArray(3).also { android.graphics.Color.colorToHSV((0xFF000000.toInt() or (theme.customSeed.toInt() and 0xFFFFFF)), it) }[2]) }
                    val rgb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
                    fun commit() {
                        val seed = (0xFF000000.toInt() or (android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) and 0xFFFFFF)).toLong() and 0xFFFFFFFFL
                        com.arix.app.theme.ThemeConfigStore.update(context, com.arix.app.theme.ThemeConfigStore.config.value.copy(customSeed = seed))
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                        Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(androidx.compose.ui.graphics.Color(rgb)).border(1.dp, scheme.outlineVariant, RoundedCornerShape(8.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(tr("自定义主色  #%06X").format(rgb and 0xFFFFFF), color = scheme.onSurface, fontSize = 12.sp)
                    }
                    Text(tr("色相"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                    androidx.compose.material3.Slider(value = h, onValueChange = { h = it }, onValueChangeFinished = { commit() }, valueRange = 0f..360f)
                    Text(tr("饱和度"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                    androidx.compose.material3.Slider(value = s, onValueChange = { s = it }, onValueChangeFinished = { commit() }, valueRange = 0f..1f)
                    Text(tr("明度"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                    androidx.compose.material3.Slider(value = v, onValueChange = { v = it }, onValueChangeFinished = { commit() }, valueRange = 0f..1f)
                }
                ThemeRow(tr("明暗"), listOf(
                    Triple(tr("跟随系统"), theme.darkMode == com.arix.app.theme.DarkMode.SYSTEM) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(darkMode = com.arix.app.theme.DarkMode.SYSTEM)) },
                    Triple(tr("浅色"), theme.darkMode == com.arix.app.theme.DarkMode.LIGHT) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(darkMode = com.arix.app.theme.DarkMode.LIGHT)) },
                    Triple(tr("深色"), theme.darkMode == com.arix.app.theme.DarkMode.DARK) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(darkMode = com.arix.app.theme.DarkMode.DARK)) },
                ))
                ThemeRow(tr("圆角"), listOf(
                    Triple(tr("紧凑"), theme.shape == com.arix.app.theme.ShapeScale.COMPACT) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(shape = com.arix.app.theme.ShapeScale.COMPACT)) },
                    Triple(tr("标准"), theme.shape == com.arix.app.theme.ShapeScale.STANDARD) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(shape = com.arix.app.theme.ShapeScale.STANDARD)) },
                    Triple(tr("表现力"), theme.shape == com.arix.app.theme.ShapeScale.EXPRESSIVE) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(shape = com.arix.app.theme.ShapeScale.EXPRESSIVE)) },
                ))
                ThemeRow(tr("组件风格"), listOf(
                    Triple(tr("填充"), theme.componentStyle == com.arix.app.theme.ComponentStyle.FILLED) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(componentStyle = com.arix.app.theme.ComponentStyle.FILLED)) },
                    Triple(tr("柔和"), theme.componentStyle == com.arix.app.theme.ComponentStyle.TONAL) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(componentStyle = com.arix.app.theme.ComponentStyle.TONAL)) },
                    Triple(tr("描边"), theme.componentStyle == com.arix.app.theme.ComponentStyle.OUTLINED) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(componentStyle = com.arix.app.theme.ComponentStyle.OUTLINED)) },
                ))
                ThemeRow(tr("动效"), listOf(
                    Triple(tr("丝滑"), theme.motion == com.arix.app.theme.MotionSpeed.FAST) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(motion = com.arix.app.theme.MotionSpeed.FAST)) },
                    Triple(tr("常规"), theme.motion == com.arix.app.theme.MotionSpeed.STANDARD) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(motion = com.arix.app.theme.MotionSpeed.STANDARD)) },
                ))
                ThemeRow(tr("顶栏毛玻璃"), listOf(
                    Triple(tr("关"), theme.blur == com.arix.app.theme.BlurQuality.OFF) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(blur = com.arix.app.theme.BlurQuality.OFF)) },
                    Triple(tr("关键面"), theme.blur == com.arix.app.theme.BlurQuality.KEY_SURFACES) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(blur = com.arix.app.theme.BlurQuality.KEY_SURFACES)) },
                    Triple(tr("高"), theme.blur == com.arix.app.theme.BlurQuality.HIGH) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(blur = com.arix.app.theme.BlurQuality.HIGH)) },
                ))
                // 全局玻璃：所有界面卡片/面板/气泡都变悬浮玻璃（真·背景模糊透出壁纸）。独立开关。
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(tr("Arix 高级效果"), color = scheme.onSurface, fontSize = 14.sp)
                        Text(tr("开启后所有界面变磨砂玻璃（真·背景模糊）+ 悬浮。手表版不考虑性能，追求质感。"), color = scheme.onSurfaceVariant, fontSize = 11.sp)
                    }
                    Switch(checked = theme.globalGlass, onCheckedChange = { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(globalGlass = it)) })
                }
                // 字号缩放：只放大文字(.sp)不动布局(dp)，全 App 生效（XtomTheme 里改 Density.fontScale）。
                // 松手才提交到 store（避免每帧全 App 重组），示例行用同样比例的 Density 实时预览。
                var fontScaleLocal by remember(theme.fontScale) { mutableStateOf(theme.fontScale) }
                val density = androidx.compose.ui.platform.LocalDensity.current
                val systemFontScale = density.fontScale / theme.fontScale.coerceAtLeast(0.01f)
                Text(tr("字号") + "  ${(fontScaleLocal * 100).toInt()}%", color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density, systemFontScale * fontScaleLocal)) {
                    Text(tr("示例：你好 Arix Aa 123"), color = scheme.onSurface, fontSize = 14.sp, modifier = Modifier.padding(bottom = 2.dp))
                }
                androidx.compose.material3.Slider(
                    value = fontScaleLocal,
                    onValueChange = { fontScaleLocal = it },
                    onValueChangeFinished = { com.arix.app.theme.ThemeConfigStore.update(context, com.arix.app.theme.ThemeConfigStore.config.value.copy(fontScale = (fontScaleLocal * 20).toInt() / 20f)) },
                    valueRange = 0.8f..1.4f,
                    steps = 11,
                )
                // 显示密度：乘到 Density.density(dp→px) 上，整体放大/缩小所有 dp 布局+文字（真 DPI 缩放），
                // 区别于只放大文字的字号。范围保守(0.85..1.15) 防撑破手表方屏，示例行实时预览。
                var displayDensityLocal by remember(theme.displayDensity) { mutableStateOf(theme.displayDensity) }
                // density.density 已含已提交的 displayDensity（本页在 XtomTheme 内），先除回原始密度再乘预览值，避免二次叠加。
                val systemDensity = density.density / theme.displayDensity.coerceAtLeast(0.01f)
                Text(tr("显示密度") + "  ${(displayDensityLocal * 100).toInt()}%", color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, bottom = 3.dp))
                androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(systemDensity * displayDensityLocal, systemFontScale * theme.fontScale)) {
                    Text(tr("示例：你好 Arix Aa 123"), color = scheme.onSurface, fontSize = 14.sp, modifier = Modifier.padding(bottom = 2.dp))
                }
                androidx.compose.material3.Slider(
                    value = displayDensityLocal,
                    onValueChange = { displayDensityLocal = it },
                    onValueChangeFinished = { com.arix.app.theme.ThemeConfigStore.update(context, com.arix.app.theme.ThemeConfigStore.config.value.copy(displayDensity = (displayDensityLocal * 20).toInt() / 20f)) },
                    valueRange = 0.85f..1.15f,
                    steps = 5,
                )
                Text(tr("整体放大界面（含布局，不只是文字）。过大可能裁切手表屏幕内容。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(bottom = 2.dp))
                // 消息密度：聊天气泡内边距/消息间距三档（紧凑/适中/舒适），气泡处按 LocalThemeConfig 读取。
                ThemeRow(tr("消息密度"), listOf(
                    Triple(tr("紧凑"), theme.messageDensity == com.arix.app.theme.MessageDensity.COMPACT) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(messageDensity = com.arix.app.theme.MessageDensity.COMPACT)) },
                    Triple(tr("适中"), theme.messageDensity == com.arix.app.theme.MessageDensity.MEDIUM) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(messageDensity = com.arix.app.theme.MessageDensity.MEDIUM)) },
                    Triple(tr("舒适"), theme.messageDensity == com.arix.app.theme.MessageDensity.COMFORTABLE) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(messageDensity = com.arix.app.theme.MessageDensity.COMFORTABLE)) },
                ))
                // 滚动指示：跳转按钮(零逐帧计算,最省) / 位置胶囊(拖动浮现的小点+点开定位器) / 关闭。
                ThemeRow(tr("滚动指示"), listOf(
                    Triple(tr("跳转按钮"), theme.scrollIndicator == com.arix.app.theme.ScrollIndicator.JUMPER) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(scrollIndicator = com.arix.app.theme.ScrollIndicator.JUMPER)) },
                    Triple(tr("位置胶囊"), theme.scrollIndicator == com.arix.app.theme.ScrollIndicator.CAPSULE) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(scrollIndicator = com.arix.app.theme.ScrollIndicator.CAPSULE)) },
                    Triple(tr("关闭"), theme.scrollIndicator == com.arix.app.theme.ScrollIndicator.OFF) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(scrollIndicator = com.arix.app.theme.ScrollIndicator.OFF)) },
                ))
                // 工具调用/思考样式：时间线(相邻步骤合并进一张淡色卡+竖线,非气泡) / 玻璃卡(各自一张,旧)。
                ThemeRow(tr("工具与思考"), listOf(
                    Triple(tr("时间线"), theme.metaBlockStyle == com.arix.app.theme.MetaBlockStyle.TIMELINE) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(metaBlockStyle = com.arix.app.theme.MetaBlockStyle.TIMELINE)) },
                    Triple(tr("玻璃卡"), theme.metaBlockStyle == com.arix.app.theme.MetaBlockStyle.GLASS) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(metaBlockStyle = com.arix.app.theme.MetaBlockStyle.GLASS)) },
                ))
                // 字体：内置族 + 自定义(选文件) + 字体市场
                ThemeRow(tr("字体"), listOf(
                    Triple(tr("系统默认"), theme.font == com.arix.app.theme.FontChoice.DEFAULT) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(font = com.arix.app.theme.FontChoice.DEFAULT)) },
                    Triple(tr("衬线"), theme.font == com.arix.app.theme.FontChoice.SERIF) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(font = com.arix.app.theme.FontChoice.SERIF)) },
                    Triple(tr("等宽"), theme.font == com.arix.app.theme.FontChoice.MONO) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(font = com.arix.app.theme.FontChoice.MONO)) },
                    Triple(tr("圆体"), theme.font == com.arix.app.theme.FontChoice.ROUNDED) { com.arix.app.theme.ThemeConfigStore.update(context, theme.copy(font = com.arix.app.theme.FontChoice.ROUNDED)) },
                    Triple(tr("自定义"), theme.font == com.arix.app.theme.FontChoice.CUSTOM) { fontPicker.launch(arrayOf("*/*")) },
                ))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { fontPicker.launch(arrayOf("*/*")) }) { Text(tr("选字体文件"), color = scheme.primary, fontSize = 12.sp) }
                    TextButton(onClick = { showFontMarket = true }) { Text(tr("字体市场"), color = scheme.primary, fontSize = 12.sp) }
                }
                // 主题包导入导出（复用 ImportExportButtons + QrKit，内容=ThemeConfig.toJson）
                Text(tr("主题包"), color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 10.dp, bottom = 3.dp))
                Text(tr("把当前外观(配色/圆角/组件/动效/模糊)导出成主题包分享。导入的主题包会**加进上面的主题库**，不会覆盖你正在用的这套。"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.arix.app.ui.ImportExportButtons(context = context, scope = scope, fileBaseName = "xtom_theme",
                        produceJson = { com.arix.app.theme.ThemeConfigStore.config.value.toJson() },
                        consumeJson = { json -> com.arix.app.theme.ThemeConfigStore.add(context, com.arix.app.theme.ThemeConfig.fromJson(json)); tr("已加入主题库") },
                        onResult = { msg -> android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show() })
                    TextButton(onClick = { runCatching { themeQr = QrKit.encode(com.arix.app.theme.ThemeConfigStore.config.value.toJson()) }.onFailure { android.widget.Toast.makeText(context, tr("生成失败"), android.widget.Toast.LENGTH_SHORT).show() } }) { Text(tr("二维码"), color = scheme.primary, fontSize = 12.sp) }
                    TextButton(onClick = { runCatching { themeQrImport.launch(arrayOf("image/*")) } }) { Text(tr("扫码导入"), color = scheme.primary, fontSize = 12.sp) }
                }
        }
        // 字体市场弹窗：一键下载开源字体并应用；标覆盖范围，缺当前语言字形则警告
        if (showFontMarket) {
            val curLangCode = com.arix.app.I18n.lang.collectAsState().value.code
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showFontMarket = false },
                title = { Text(tr("字体市场"), color = scheme.onSurface) },
                text = { Column {
                    Text(tr("下载开源字体并应用。中文界面请选含中文字形的字体。"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(bottom = 6.dp))
                    FontMarket.items.forEach { item ->
                        val warn = FontMarket.warnFor(item, curLangCode)
                        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(tr(item.name), color = scheme.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                    Text("${tr(item.desc)} · ${tr(item.covers)} · ~${item.sizeMB}MB", color = scheme.onSurfaceVariant, fontSize = 10.sp)
                                    if (warn != null) Text(warn, color = scheme.error, fontSize = 10.sp)
                                }
                                TextButton(enabled = fontDownloading == null, onClick = {
                                    fontDownloading = item.name
                                    scope.launch {
                                        val p = FontMarket.download(context, item)
                                        fontDownloading = null
                                        if (p != null) {
                                            com.arix.app.theme.ThemeConfigStore.update(context, com.arix.app.theme.ThemeConfigStore.config.value.copy(font = com.arix.app.theme.FontChoice.CUSTOM, customFontPath = p))
                                            showFontMarket = false
                                            android.widget.Toast.makeText(context, tr("已下载并应用：") + item.name, android.widget.Toast.LENGTH_SHORT).show()
                                        } else android.widget.Toast.makeText(context, tr("下载失败（换个字体或检查网络）"), android.widget.Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text(if (fontDownloading == item.name) tr("下载中…") else tr("下载应用"), color = scheme.primary, fontSize = 12.sp) }
                            }
                        }
                    }
                } },
                confirmButton = { TextButton(onClick = { showFontMarket = false }) { Text(tr("关闭"), color = scheme.primary) } },
                containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
            )
        }
        // 主题包二维码弹窗
        themeQr?.let { bmp ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { themeQr = null },
                title = { Text(tr("主题包二维码"), color = scheme.onSurface) },
                text = { Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    androidx.compose.foundation.Image(bitmap = bmp.asImageBitmap(), contentDescription = "二维码", modifier = Modifier.size(260.dp))
                    Text(tr("对方在 外观/主题 里点「扫码导入」选这张图即可套用"), color = scheme.onSurfaceVariant, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
                } },
                confirmButton = { TextButton(onClick = { themeQr = null }) { Text(tr("关闭"), color = scheme.primary) } },
                containerColor = scheme.surface, shape = RoundedCornerShape(24.dp)
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 屏幕适配（圆角适配）：手表圆屏/设备圆角会切掉四边，这里手调把界面往里收多少。
        // 滑杆拖动只改本地态（预览实时跟随），松手才写 ScreenFitPrefs（其 version 流驱动全 App 重组）。
        ExpandSection(tr("屏幕适配"), tr("手表圆屏/圆角会切掉画面四边，把界面往里收"), scheme) {
                var insetH by remember { mutableStateOf(ScreenFitPrefs.insetH(context)) }
                var insetV by remember { mutableStateOf(ScreenFitPrefs.insetV(context)) }
                var floatInset by remember { mutableStateOf(ScreenFitPrefs.floatInset(context)) }
                val isRound = remember { ScreenFitPrefs.isScreenRound(context) }
                val fitView = androidx.compose.ui.platform.LocalView.current
                val cfg = androidx.compose.ui.platform.LocalConfiguration.current
                // 预览：缩比模型（非像素级）。按「dp 内缩 / 屏幕 dp 尺寸」的比例缩到预览框里，
                // 形状同 WakeAssistantActivity 的 EdgeTraceLight：圆屏画整圆，否则读设备真实圆角(API31+)，退回 30dp。
                Box(modifier = Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                    androidx.compose.foundation.Canvas(modifier = if (isRound) Modifier.size(112.dp) else Modifier.size(width = 96.dp, height = 112.dp)) {
                        val stroke = 2.dp.toPx()
                        val swDp = cfg.screenWidthDp.toFloat().coerceAtLeast(1f)
                        val shDp = cfg.screenHeightDp.toFloat().coerceAtLeast(1f)
                        val ix = (insetH / swDp * size.width).coerceIn(0f, size.width / 2f - stroke)
                        val iy = (insetV / shDp * size.height).coerceIn(0f, size.height / 2f - stroke)
                        val fill = scheme.primary.copy(alpha = 0.30f)
                        if (isRound) {
                            drawCircle(color = scheme.outlineVariant, radius = size.minDimension / 2f - stroke / 2f, style = Stroke(width = stroke))
                            // 圆屏：内容区裁进圆内才符合观感（圆外本就被表圈切掉）
                            clipPath(Path().apply { addOval(androidx.compose.ui.geometry.Rect(stroke, stroke, size.width - stroke, size.height - stroke)) }) {
                                drawRect(color = fill, topLeft = Offset(ix, iy), size = Size(size.width - ix * 2, size.height - iy * 2))
                            }
                        } else {
                            val radPx = (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S)
                                fitView.rootWindowInsets?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() else null)
                                ?: 30.dp.toPx()
                            // 真实圆角是整屏尺度的，先换算成 dp 再按预览比例缩，否则预览里会大到糊成一团
                            val rad = (radPx / density / swDp * size.width).coerceIn(0f, size.minDimension / 2f)
                            val corner = androidx.compose.ui.geometry.CornerRadius(rad, rad)
                            drawRoundRect(color = scheme.outlineVariant, topLeft = Offset(stroke / 2f, stroke / 2f), size = Size(size.width - stroke, size.height - stroke), cornerRadius = corner, style = Stroke(width = stroke))
                            drawRect(color = fill, topLeft = Offset(ix, iy), size = Size(size.width - ix * 2, size.height - iy * 2))
                        }
                    }
                }
                Text(tr("界面宽度") + "  ${insetH.toInt()}dp", color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 6.dp, bottom = 3.dp))
                androidx.compose.material3.Slider(value = insetH, onValueChange = { insetH = it }, onValueChangeFinished = { ScreenFitPrefs.setInsetH(context, insetH) }, valueRange = 0f..48f)
                Text(tr("界面高度") + "  ${insetV.toInt()}dp", color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 3.dp))
                androidx.compose.material3.Slider(value = insetV, onValueChange = { insetV = it }, onValueChangeFinished = { ScreenFitPrefs.setInsetV(context, insetV) }, valueRange = 0f..48f)
                Text(tr("悬浮界面") + "  ${floatInset.toInt()}dp", color = scheme.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(bottom = 1.dp))
                Text(tr("聊天的顶栏/输入栏、唤醒助手界面"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(bottom = 3.dp))
                androidx.compose.material3.Slider(value = floatInset, onValueChange = { floatInset = it }, onValueChangeFinished = { ScreenFitPrefs.setFloatInset(context, floatInset) }, valueRange = 0f..48f)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        val (h, v, f) = ScreenFitPrefs.suggestRound(context)
                        insetH = h; insetV = v; floatInset = f
                        ScreenFitPrefs.setInsetH(context, h); ScreenFitPrefs.setInsetV(context, v); ScreenFitPrefs.setFloatInset(context, f)
                    }) { Text(tr("推荐值"), color = scheme.primary, fontSize = 12.sp) }   // 圆屏按短边比例、方屏按真实圆角半径算
                    TextButton(onClick = {
                        insetH = 0f; insetV = 0f; floatInset = 0f
                        ScreenFitPrefs.setInsetH(context, 0f); ScreenFitPrefs.setInsetV(context, 0f); ScreenFitPrefs.setFloatInset(context, 0f)
                    }) { Text(tr("重置"), color = scheme.onSurfaceVariant, fontSize = 12.sp) }
                }
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 页面背景
        ExpandSection(tr("页面背景"), tr("给各页设背景图，内容半透明显示其上；「通用」这张终端 App 也会用"), scheme) {
                PageBackgroundPrefs.PAGES.forEach { (key, name) ->
                    // 用 hasOwn 而不是 get：设了通用背景后 get 对每一页都返回非空，会让每页都显示"已设/清除"
                    val has = remember(bgTick) { PageBackgroundPrefs.hasOwn(context, key) }
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(name, color = scheme.onSurface, fontSize = 13.sp, modifier = Modifier.weight(1f))
                        // 取色：用**这一行这张图**提主色（不是系统壁纸）。用图标钮而不是第三个文字按钮——
                        // 手表窄屏上三个 TextButton 会把「清除」挤出屏幕。
                        if (has) androidx.compose.material3.IconButton(onClick = { seedFromPage(key) }, modifier = Modifier.size(32.dp)) {
                            androidx.compose.material3.Icon(Icons.Outlined.Palette, contentDescription = tr("从这张图取色"), tint = scheme.primary, modifier = Modifier.size(17.dp))
                        }
                        TextButton(onClick = { pendingBgPage = key; bgPicker.launch(arrayOf("image/*")) }) { Text(if (has) tr("更换") else tr("选图"), color = scheme.primary, fontSize = 11.sp) }
                        if (has) TextButton(onClick = { PageBackgroundPrefs.set(context, key, null); bgTick++ }) { Text(tr("清除"), color = scheme.error, fontSize = 11.sp) }
                    }
                }
                Text(tr("调色板按钮 = 从这一行的图里提主色，套到当前主题上（配色来源会切成「自定义色」，随时能改回去）。"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                var scrim by remember { mutableStateOf(PageBackgroundPrefs.scrimAlpha(context)) }
                Text(tr("内容不透明度") + " ${(scrim * 100).toInt()}% " + tr("（越低越透出背景图）"), color = scheme.onSurfaceVariant, fontSize = 10.sp, modifier = Modifier.padding(top = 6.dp))
                androidx.compose.material3.Slider(value = scrim, onValueChange = { scrim = it; PageBackgroundPrefs.setScrimAlpha(context, it) }, valueRange = 0.5f..1f)
        }
        // 选完聊天/通用背景后的一次性询问。给选择而不是替他决定：配色是用户自己调的，
        // 想让它跟图走的人点一下就行，不想的人一次都不会被改。
        offerSeedPage?.let { page ->
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { offerSeedPage = null },
                title = { Text(tr("按这张图配色？"), color = scheme.onSurface) },
                text = { Text(tr("提取这张背景图的主色，作为界面主色重算整套配色。不改也行——之后在「页面背景」里点那一行的调色板按钮随时能来。"), color = scheme.onSurfaceVariant, fontSize = 12.sp) },
                confirmButton = { TextButton(onClick = { offerSeedPage = null; seedFromPage(page) }) { Text(tr("取色"), color = scheme.primary) } },
                dismissButton = { TextButton(onClick = { offerSeedPage = null }) { Text(tr("不用"), color = scheme.onSurfaceVariant) } },
                containerColor = scheme.surface, shape = RoundedCornerShape(24.dp),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 滚动自动隐藏顶栏/输入栏
        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = scheme.surface), shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(defaultElevation = 0.dp), border = androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant)) {
            Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tr("滚动自动隐藏顶栏/输入栏"), color = scheme.onSurface, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text(tr("下滑隐藏、上滑或点屏幕呼出；关闭则始终显示"), color = scheme.onSurfaceVariant, fontSize = 10.sp)
                }
                Switch(checked = autoHide, onCheckedChange = { autoHide = it; ConfigModePrefs.setAutoHideBars(context, it) })
            }
        }
        Spacer(modifier = Modifier.height(12.dp))
        // 超级岛显示（免 root·仅小米 HyperOS）：AI 状态怎么显示=外观事，归在个性化里最好找。
        // 发焦点通知前经 Shizuku 把 xmsf 断网 ~1s 绕过云控白名单让状态真出岛。别机型不显示这项。
        val isXiaomi = remember {
            val m = (android.os.Build.MANUFACTURER ?: "").lowercase()
            val b = (android.os.Build.BRAND ?: "").lowercase()
            m.contains("xiaomi") || m.contains("redmi") || b.contains("redmi") || b.contains("poco")
        }
        // ColorOS/欧加系（OPPO/一加/realme）：它家的系统岛叫「流体云」。别和小米「超级岛」混叫。
        val isColorOs = remember {
            val m = (android.os.Build.MANUFACTURER ?: "").lowercase()
            val b = (android.os.Build.BRAND ?: "").lowercase()
            m.contains("oppo") || b.contains("oppo") || b.contains("oneplus") || m.contains("oneplus") || b.contains("realme") || m.contains("realme")
        }
        // 本机系统岛的**正确叫法**（超级岛≠流体云，是两套不同机制）：小米=超级岛、欧加=流体云、
        // 其它 A16=实时活动、无岛设备=实时胶囊（走普通通知）。设置页全程用它，避免搞混。
        val capsuleName = when {
            isXiaomi -> tr("超级岛")
            isColorOs -> tr("流体云")
            LiveCapsuleController.supportsLiveUpdates() -> tr("实时活动")
            else -> tr("实时胶囊")
        }
        // 实时胶囊外观与行为：配色/流光/浮窗/常驻/预览——**全 ROM 通用**
        // （超级岛全吃；流体云吃进度配色+图标+常驻；无岛设备走普通通知也吃常驻）。改任意项即时生效。
        val capV by CapsulePrefs.version.collectAsState()
        val capTranslucent = remember(bgTick) { PageBackgroundPrefs.get(context, "settings_hub") == null }
        com.arix.app.ui.SettingsSection(
            title = capsuleName,
            icon = Icons.Outlined.Bolt,
            translucent = capTranslucent,
        ) {
            com.arix.app.ui.SettingsChoiceRow(
                title = tr("岛显示位置"),
                subtitle = tr("应用内岛=App 内自绘、全机型一致效果全可控；系统岛=超级岛/流体云，部分机型只出默认样式或出不来。"),
                options = listOf(
                    "both" to tr("两个都要"),
                    "inapp" to tr("应用内岛"),
                    "system" to tr("系统岛"),
                    "off" to tr("关"),
                ),
                selected = remember(capV) { CapsulePrefs.displayMode(context) },
                onSelect = { CapsulePrefs.setDisplayMode(context, it) },
            )
            com.arix.app.ui.SettingsChoiceRow(
                title = tr("配色主题"),
                options = remember { CapsulePrefs.themes.map { it.id to tr(it.label) } },
                selected = remember(capV) { CapsulePrefs.theme(context) },
                onSelect = { CapsulePrefs.setTheme(context, it) },
            )
            com.arix.app.ui.SettingsToggle(
                icon = Icons.Outlined.Bolt,
                title = tr("流光动画"),
                subtitle = tr("图标/进度上做流光过渡。关则静态无动画。"),
                checked = remember(capV) { CapsulePrefs.animated(context) },
                onCheckedChange = { CapsulePrefs.setAnimated(context, it) },
            )
            com.arix.app.ui.SettingsToggle(
                icon = Icons.Outlined.Bolt,
                title = tr("弹浮窗大岛"),
                subtitle = tr("开：重要状态浮窗弹出抢屏。关（默认）：只在状态栏胶囊/小岛里更新，不弹脸上。"),
                checked = remember(capV) { CapsulePrefs.floatPopup(context) },
                onCheckedChange = { CapsulePrefs.setFloatPopup(context, it) },
            )
            com.arix.app.ui.SettingsChoiceRow(
                title = tr("结束后常驻"),
                subtitle = tr("一轮结束后岛留多久再收"),
                options = listOf(0 to tr("立即"), 3 to tr("3秒"), 5 to tr("5秒"), 10 to tr("10秒"), -1 to tr("手动关")),
                selected = remember(capV) { CapsulePrefs.lingerSeconds(context) },
                onSelect = { CapsulePrefs.setLingerSeconds(context, it) },
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, start = 4.dp)) {
                Button(onClick = {
                    val m = CapsulePrefs.displayMode(context)
                    if (m == "system" || m == "both") LiveCapsuleController.preview(context)
                    if (m == "inapp" || m == "both") CapsuleBridge.previewInApp(context)
                }, shape = MaterialTheme.shapes.large, modifier = Modifier.heightIn(min = 40.dp)) {
                    Text(tr("预览一下"), style = MaterialTheme.typography.labelLarge)
                }
            }
            com.arix.app.ui.SettingsHint(
                when {
                    isXiaomi -> tr("「预览一下」按当前配色/动画在岛上演示约 3 秒。小米需另开下面「超级岛显示」并授权 Shizuku 才真出岛，否则只是普通通知。")
                    isColorOs || LiveCapsuleController.supportsLiveUpdates() -> tr("「预览一下」按当前配色/动画演示约 3 秒。需在下面确认系统「实时活动 / Live Updates」已为本应用开启，否则会降级成普通通知。")
                    else -> tr("「预览一下」按当前配色/动画演示约 3 秒。本机无系统岛，状态走普通通知。")
                }
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // 超级岛显示（免 root·仅小米）：发焦点通知前经 Shizuku 把 xmsf 断网 ~1s 绕过云控白名单让状态真出岛。
        if (isXiaomi) {
            var superIsland by remember { mutableStateOf(SuperIslandPrefs.enabled(context)) }
            com.arix.app.ui.SettingsSection(
                title = tr("超级岛显示（免 root）"),
                icon = Icons.Outlined.Bolt,
                translucent = capTranslucent,
            ) {
                com.arix.app.ui.SettingsToggle(
                    icon = Icons.Outlined.Bolt,
                    title = tr("超级岛显示（需 Shizuku·仅小米）"),
                    subtitle = tr("免 root 让 Arix 状态真正出现在 HyperOS 超级岛。原理：发焦点通知时经 Shizuku 把小米服务框架断网约 1 秒（绕过云控白名单），随即恢复。副作用：小米推送(MiPush)会有约 1 秒抖动，长连接会自动重连。需先授权 Shizuku。"),
                    checked = superIsland,
                    onCheckedChange = { on ->
                        superIsland = on
                        SuperIslandPrefs.setEnabled(context, on)
                        // 开启且尚未授权 → 走 Shizuku 授权引导（没装/没启动/没授权分别引导）
                        if (on && !com.arix.tool.ShizukuAccess.granted()) {
                            scope.launch { com.arix.tool.ShizukuAccess.request(context, tr("让焦点通知真正出现在超级岛")) }
                        }
                    },
                )
                com.arix.app.ui.SettingsHint(tr("是否真出岛、MiPush 抖动、时序，均需在小米/HyperOS 真机确认。"))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        // 流体云 / 实时活动（ColorOS 16 及原生 Android 16，非小米）：走系统 Live Updates。
        // 关键坑——A16 的「实时活动/Live Updates」是**系统里逐应用的开关**，OPPO/ColorOS 默认可能关着，
        // 关着时我们发的升级版常驻通知会被系统降级成普通通知（=流体云不显示）。这里给状态 + 一键跳系统去开。
        if (LiveCapsuleController.supportsLiveUpdates() && !isXiaomi) {
            val promoted = remember(resumeTick) { LiveCapsuleController.canPromote(context) }
            com.arix.app.ui.SettingsSection(
                title = "$capsuleName · ${tr("系统开关")}",
                icon = Icons.Outlined.Bolt,
                translucent = capTranslucent,
            ) {
                val statusText = when (promoted) {
                    true -> tr("系统已允许：Arix 状态可显示为%s。").format(capsuleName)
                    false -> tr("系统当前未允许——%s会被降级成普通通知（不出岛）。请到系统通知设置里打开本应用的「实时活动 / Live Updates」。").format(capsuleName)
                    null -> tr("无法读取当前状态，请到系统通知设置里确认本应用的「实时活动 / Live Updates」已打开。")
                }
                com.arix.app.ui.SettingsHint(statusText)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp, start = 4.dp)) {
                    Button(
                        onClick = { openAppNotificationSettings(context) },
                        shape = MaterialTheme.shapes.large,
                        modifier = Modifier.heightIn(min = 40.dp),
                    ) {
                        Text(tr("去系统开启%s").format(capsuleName), style = MaterialTheme.typography.labelLarge)
                    }
                }
                com.arix.app.ui.SettingsHint(
                    if (isColorOs) tr("ColorOS 14/15（Android 15 及更低）无免 root 的流体云通道，本项仅在 Android 16+ 出现；此时用上面的配色/常驻等设置即可，状态走普通通知。")
                    else tr("实时活动仅在 Android 16+ 出现；低版本用上面的配色/常驻等设置即可，状态走普通通知。")
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        // 聊天界面：目前只有「建议芯片摆哪」。两种摆法各有取舍（内部不遮消息、上方更好点），
        // 所以给选择而不是替换掉旧行为。
        com.arix.app.ui.SettingsSection(
            title = tr("聊天界面"),
            icon = Icons.AutoMirrored.Outlined.Chat,
            // 本页设了背景图时卡片不能半透明——压在照片上会糊到读不出字
            // 必须 remember：get() 现在会去 /sdcard 上做 isFile+length()（最多 4 次系统调用），
            // 裸写在组合里等于每次重组都读一遍磁盘——滚动时每帧都在等 IO，直接卡给用户看。
            translucent = remember(bgTick) { PageBackgroundPrefs.get(context, "settings_hub") == null },
        ) {
            var inline by remember { mutableStateOf(ConfigModePrefs.suggestionInline(context)) }
            com.arix.app.ui.SettingsChoiceRow(
                title = tr("消息建议位置"),
                subtitle = tr("AI 给的「下一句」建议芯片摆在哪"),
                icon = Icons.Outlined.Lightbulb,
                options = listOf(true to tr("输入框内"), false to tr("输入框上方")),
                selected = inline,
                onSelect = { inline = it; ConfigModePrefs.setSuggestionInline(context, it) },
            )
        }
    }
}

/**
 * 跳到「本应用」的系统通知设置页——ColorOS/原生 Android 16 的「实时活动 / Live Updates」逐应用开关就在这里。
 * 首选 ACTION_APP_NOTIFICATION_SETTINGS，失败逐级回退到应用详情/全局通知设置，绝不崩。
 */
private fun openAppNotificationSettings(context: android.content.Context) {
    val flag = Intent.FLAG_ACTIVITY_NEW_TASK
    val candidates = listOf(
        Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName),
        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:${context.packageName}")),
        Intent(android.provider.Settings.ACTION_SETTINGS),
    )
    for (i in candidates) {
        try { context.startActivity(i.addFlags(flag)); return } catch (_: Throwable) { }
    }
}
