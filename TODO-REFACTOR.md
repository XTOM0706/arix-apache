# 前端整体重构 · 进度（2026-07-03 起）

> 用户明确：**重构整个前端 UI**，参照 Operit 的外壳架构，配色/风格继续用我们的莫奈（按 `DESIGN.md`）。
> 「坑」定位：`DESIGN.md` 设计系统 + `theme/` 令牌 + `ui/XtomComponents` 都建好了，但**页面全绕开**——
> 各写死圆角/内距、给输入框强设定高(<56dp)→「文字下沉」；MainActivity 字符串路由+两平行 when；
> 全屏靠全局隐藏系统栏+魔法常量 58dp；~130 行重复 import 头贴满 16 个文件。
> 方案：**1 地基 + 2 聊天样板 + 3 逐页迁移**（用户已确认）。**本轮不抽 ViewModel**（避免翻倍工作量/丢功能风险），UI 稳后再单开一轮。

## 用的组件系统（迁移时一律用这些，别再手搓）
- 主题：`XtomTheme` 包 `MaterialTheme`，令牌来自 `theme/`：配色 `MaterialTheme.colorScheme`(XtomColorSchemes)、
  形状 `MaterialTheme.shapes`(XtomShapes)、字体 `MaterialTheme.typography`(XtomTypography)、
  语义色 `com.arix.app.theme.LocalXtomAccents.current`(success/warning/info)。**禁写死 0xFF**。
- 核心组件 `ui/XtomComponents.kt`：
  - **`XtomField`** —— 代替 `OutlinedTextField`(尤其带 `.height()` 的，那就是文字下沉病根)。参数 label/placeholder/
    password/singleLine/maxLines/leading/trailing/textStyle。
  - **`XtomCard`** —— 代替手搓 `Card`。**`PageScaffold`** —— 代替各页自写的 `Column(padding).verticalScroll`。
  - 还有 `XtomButton`/`XtomIconToggle`；聊天视觉件在 `ui/XtomChatComponents.kt`。

## 铁律（本轮）
- **不抽 ViewModel**（只做 UI/组件/inset）。**别碰聊天页 ChatScreen——已重构好、能用**。
- 一页一提交，`./gradlew.bat assembleDebug` 编译过才算完。令牌 only。不丢功能。

## 已重做/重构好的页（别再动）
聊天页、角色卡页、世界书页、记忆页、抽屉/侧边导航、设置中心页、文件页、**模型配置页(今天彻底重做)**、导入导出中心页。
**逐页迁移已全部完成（11/11，见下）——这些页也别再动，改动只做「细打磨」轮。**

## 进度（2026-07-03）
- ✅ 地基：删死代码 `Theme.kt`；新增 `XtomField`/`XtomCard`/`PageScaffold`（提交 88800e5）。
- ✅ 模型配置页彻底重做（提交 d1e3baf）：删「懒人/专业模式」→ 配置列表+编辑器，用途下放；迁 XtomField；
  清本页 130 行 import。顺带修 `CloudApiConfigManager.update()` 漏传 purpose/isActive 的 bug。
- ✅ 设置中心去掉每项「长按说明」字样（长按行为保留，提交 784818e）。

## ✅ 逐页迁移完成（2026-07-07，11/11，一页一提交，每页编译过）
| 页 | 提交 | 要点 |
|---|---|---|
| 对话管理 ConversationListScreen | 196b2de | XtomCard/XtomField/PageScaffold(scroll=false+LazyColumn) |
| 语音识别 SttPage | 0e0cc9b | 6 卡→XtomCard；API URL/Key→XtomField(password)；根无滚动→PageScaffold修溢出 |
| 语音唤醒 WakePage | b6d485e | 状态卡/日志卡→XtomCard；日志卡内层保留 heightIn+滚动 |
| 终端 TerminalPage | 2950510 | **命令框 .height(42)→XtomField 修下沉**；输出卡 weight 撑满；scroll=false 保 weight |
| 插件制作 PluginCreatorPage | 73807f5 | 名称/描述→XtomField(多行)；结果卡→XtomCard |
| API监控 MonitorPage | 43bc899 | 摘要/风控卡→XtomCard；调用记录条→令牌色 Row(保留成功/失败变色) |
| 触控 TouchPage | c309968 | **X/Y .height(40)→XtomField 修下沉**；结果卡→XtomCard |
| 权限 PermissionsPage | e995264 | 三组状态变色卡→令牌色 clickable Row(权限项/主开关/工具项) |
| 崩溃报告 CrashReportPage | af1889c | 列表卡→XtomCard(onClick)；详情/列表→PageScaffold(scroll=false)；AlertDialog 保留 |
| 包管理 PackagesPage | af894c1 | 包卡→令牌色 combinedClickable Column(点击开关/长按详情+启用态色)；AlertDialog 保留 |
| Operit兼容 OperitPage | dd4da6f | **搜索框 .height(36)→XtomField 修下沉**；市场/已装卡→令牌色 combinedClickable；2 AlertDialog 保留 |

**迁移中确立的约定（细打磨/后续页沿用）**：
- 背景随状态/语义变色的卡（成功失败、启用与否、本地远端），XtomCard 表达不了固定背景 →
  改用「令牌色背景的 clickable/combinedClickable Row/Column」（`.clip(shape).background(令牌色).clickable{}.padding()`），
  保住语义色且不留手搓 `Card`。**全程只用令牌，零 `Color(0x…)`（已 grep 校验 11 页皆空）**。
- 固定布局页（含 `weight()` 撑满 / `LazyColumn` 填充）用 `PageScaffold(scroll=false)`；纯流式页用默认 `PageScaffold`。
- `items(list.size, key=…)` 用 count 重载，省掉 `lazy.items` import。
- AlertDialog 暂原样保留（已是令牌色），细打磨轮再看要不要统一。

## 结构级待办（更大，稳妥后做）
- MainActivity：字符串路由 + 两个平行 `when`(路由→组件 / 路由→标题) → 单一 sealed route 模型(含标题)；
  全屏 inset 单一真源(替换魔法 `topContentPadding=58.dp`；`displayCutoutPadding` 防挖孔遮挡)。
- 清理：root 与 `ui/` 双份聊天组件；`MarkdownText.kt` 7 处写死色(具名颜色表 red/green… 要保留)。

## 今天发现/修过的坑（下次注意）
- `CloudApiConfigManager.update()` 整行替换漏传 purpose/isActive → 已补参数修复。
- `CloudApiClient` endpoint 拼接改灵活：认版本段(/v1 /v4 /openai /compatible-mode/v1)只补 /chat/completions；
  末尾 `#` 强制原样；无 Key 不发 Authorization（支持免Key端点）。
- `ConfigModePrefs.isProMode` 现已无 UI 引用(留着兼容，不用管)。

---

# TODO — UI 重构会话（下一轮）

> 用户将开新对话做「UI 重构」，明确说过：**不仅重构 UI，还会顺带优化各种实现逻辑**。
> 所以下面这些没在功能补齐轮里做的，都留到重构一并做（现在做会被推翻）。
> 配套文档：功能对照见 `GAP.md`，进度/历史见 `WORKLOG.md`。

## 用户已定的最高优先级（重构时先做）
1. **记忆图谱可视化** — 节点/连线展示记忆关系（对齐 Operit MemoryScreen 图谱）。
   现状：记忆是扁平列表（MemoryPage in MainActivity.kt），MemoryTool 已支持 tags，
   DB 有 memory_tags / memory_tag_cross_ref 表可利用。
2. **工作流定时任务** — 定时唤醒 AI 执行一次性/周期任务（对齐 Operit schedule_one_time_task）。
   现状：无（旧的 workflow 空壳工具已删）。需要调度器（AlarmManager/WorkManager）+
   前台服务触发一次对话。可参考已有 WakeService（前台常驻）。

## 其余 ② 重 UI/架构级（重构按需）
- 主题系统（亮/暗/自定义色）+ 字体大小/密度 —— 目前全 app 颜色写死深色（0xFF1E1E2E 等）
- 多语言 i18n —— 目前全中文硬编码
- 悬浮窗助手（WindowManager overlay + 前台服务）
- 内嵌浏览器（WebView + 书签/历史）、代码编辑器工作区、远程电脑屏
- 屏幕 OCR 悬浮（MediaProjection + OCR）
- 聊天：多选模式、分享为图片（Compose graphicsLayer 截图）、分支/消息变体（需会话树数据模型）
- 工具箱：SQL 查看 / FFmpeg / UI 调试器

## 没做完的 ① 后端项（重构会重写发送链路，届时一并做，避免现在动出 bug）
- **上下文条数上限 / 自动摘要阈值** —— 需改 runWithToolLoop 发送逻辑，注意别切断
  tool_call/tool 配对（会 API 报错）。
- **每功能模型绑定** —— pro 模式已有 purpose 分类，但 title 生成/STT 校正等仍用当前 chat 配置。
- **Enter 发送可配置** —— 需把发送动作从超大内联 lambda 抽成可调用函数。
- **消息队列**（处理中排队）、**更多附件类型**（相机/位置/通知/记忆/应用包）。

## 重构必读的架构现状（避免重复踩坑）
- 主界面：`app/.../MainActivity.kt`（~2900 行，14 页抽屉 + ChatPage 全在一个文件，**重构重点**）。
- 消息渲染：`MarkdownText.kt`（自写 Markdown 渲染器，含代码块/图片/表格；重构可保留或替换）。
- 工具体系：`Tool` 接口 + `ToolManager`（单例）+ `PackageManager.createBuiltInPackages(context)`；
  权限闸门 `ToolPermissionManager`（AndroidPermissionLevel 五级 + ALLOW/ASK/FORBID）。
  已有工具见 PackageManager.kt；daily_life 生活包、file/http 工具、搜索(Bing/百度)、天气均已接。
- 模型请求：`cloudapi/.../CloudApiClient.kt`（OkHttp，streamChat）；配置 `CloudApiConfig`
  已含 温度/top_p/max_tokens/惩罚/customHeaders。
- DB：Room，`AppDatabase` **version = 11**（api_configs 有生成参数+customHeaders 列；
  memories 有 characterCardId）。加列迁移模式，改 schema 记得写 MIGRATION 并 +1。
- 权限阶段2（Shizuku/root/无障碍 ShellExecutor 工厂）也还没做 —— shell/touch 目前只以 app 身份跑。
- 构建：`.\gradlew.bat assembleDebug`；APK `app/build/outputs/apk/debug/app-debug.apk`（覆盖安装，官改包 adb install 会报错，用户手动装）。
