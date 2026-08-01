# CLAUDE.md — Arix 项目须知

> 每次会话自动加载。改动大方向/约定先更新本文件。**当前进度/下一步/接手入口看 `TODO-NOW.md`**（`TODO-REFACTOR.md` 是早期重构阶段的历史存档）。

## 项目
Arix：Android **Jetpack Compose** 语音 AI 助手。**方屏智能手表优先 + 手机响应式**。
设计基准是 `DESIGN.md`（M3 表现力 + 运行时主题 `ThemeConfig` + 莫奈动态色）。UI 全中文。

## 构建 / 安装
- 编译：`./gradlew.bat assembleDebug`（Windows，Git Bash 也可）。APK：`app/build/outputs/apk/debug/app-debug.apk`。
- **官改包 `adb install` 会报错，用户手动装**。改完自己不装。
- 模块：`app`(UI) / `cloudapi`(OkHttp streamChat) / `stt` / `wake` / `data`(Room)。

## 设计系统（写 UI 一律照这个，别手搓）
- 主题入口 `theme/XtomTheme.kt` 包 `MaterialTheme`。令牌：`MaterialTheme.colorScheme`(XtomColorSchemes)、
  `MaterialTheme.shapes`(XtomShapes)、`MaterialTheme.typography`(XtomTypography)、
  语义色 `com.arix.app.theme.LocalXtomAccents.current`（success/warning/info）。
- **铁律：颜色/形状/字体只用令牌，禁写死 `Color(0xFF…)`**（例外：`MarkdownText.kt` 的具名颜色表 red/green…）。
- 核心组件 `ui/XtomComponents.kt`：
  - **`XtomField`** 代替 `OutlinedTextField`——旧代码给输入框强设 `.height(<56dp)` 会导致**文字下沉**，一律换它。
  - **`XtomCard`** 代替手搓 `Card`；**`PageScaffold`** 代替各页自写的 `Column(padding).verticalScroll`。
  - `XtomButton`/`XtomIconToggle`；聊天视觉件在 `ui/XtomChatComponents.kt`。
  - 输入框配色 `ui.xtomTextFieldColors()`（旧 `darkTextFieldColors` 已删）。
- 通用导入导出：`ui/ImportExportButtons`（四导出/两导入）+ `tool/ImportConverters`。**内部规范格式恒为我们自己的；兼容竞品一律靠「转化」而非采用其格式**：导入时把酒馆/Operit 等转成我们的；导出时把我们的转化成通用/竞品可读格式（如对话→Markdown/TXT）。对话导出见 `ConversationListScreen.exportConv` + `ImportExport.exportConversation{Markdown,Text}`。

## 工作约定
- **一页一提交，编译过才算完**。commit 用中文，结尾带 `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`。
- **不丢功能**。**不改提示词，除非更好——且要让用户审计**。
- 只动 UI 表现层，别顺手重构业务逻辑（managers/tools/DB/网络），范围失控是新坑之源。
- 本轮重构：**不抽 ViewModel**（只做 UI/组件/inset）。

## 现状（前端整体重构已完成，2026-07 中旬起进入打磨/补功能阶段）
- 重构（参照 Operit 外壳 + 我们的莫奈）**已完成**：全部页面走 `XtomField`/`XtomCard`/`PageScaffold` + 令牌配色 + 字符串路由。原来「待迁移」的对话管理/语音识别/唤醒/终端/API监控/权限/崩溃报告/包管理/Operit 兼容也都迁完了。
- **成熟页别乱动**：聊天页(ChatScreen)、角色卡、世界书、记忆、抽屉/导航、设置中心、文件页、模型配置、导入导出、对话管理、Operit/包管理、活动中心、权限、崩溃报告等——改前先翻对应记忆（尤其性能类改动的坑）。
- **当前重心**：①性能/流畅性打磨（方法论看 `DESIGN-CHAT-PERF.md`：安卓 UI「卡」几乎全在 Compose 组合层，逐层量别猜）②竞品补齐（`TODO-CATCHUP.md`）③独立终端 App ④安全审计。下一步永远看 `TODO-NOW.md`。
- 约定不变：**不抽 ViewModel**；只动 UI 表现层，别顺手重构业务逻辑（managers/tools/DB/网络）；不丢功能；不擅改提示词（要改先让用户审）。

## 关键文件
- `MainActivity.kt`：Activity + `MainScreen`（`ModalNavigationDrawer`+`Scaffold`）。路由是 `var currentPage: String` + `when` 分发（待收成 sealed 模型）。全屏靠全局隐藏系统栏 + `WindowInsets(0)` + 魔法常量 58dp（待理顺）。
- `ChatScreen.kt`：聊天页（已重构，别动）。`MarkdownText.kt`：自写 Markdown 渲染器。
- 模型请求 `cloudapi/CloudApiClient.kt`：`streamChat`。endpoint 拼接已改灵活（认 /v1 /v4 /openai/compatible-mode；末尾 `#` 强制原样；无 Key 不发 Authorization）。
- 配置：`CloudApiConfigManager`（**注意 `update()` 必须带 purpose/isActive，整行替换**）；服务商预设 `ApiProviders.kt`。
- DB：Room `data/db/AppDatabase`（当前 version 19，加列要写 MIGRATION 并 +1）。大会话表 messagesJson/branchesJson 可超 2MB CursorWindow → 列表用轻量投影 `ConversationSummary`、单条走 `getByIdAssembled` 分列拼装（见记忆 conv-cursorwindow-crash）。

## 文档索引
`DESIGN.md`(设计基准) · **`TODO-NOW.md`(当前进度/下一步/接手入口)** · **`DESIGN-CHAT-PERF.md`(流畅性方法论：卡在 Compose 组合层，逐层量)** · `TODO-V1.md`(正式版路线图) · **`COMPETITIVE.md`(vs 5 竞品逐包总表)** · **`TODO-CATCHUP.md`(竞品补齐打勾表/工作流)** · `TODO-REFACTOR.md`/`TODO-UI.md`(重构历史存档) · `GAP.md`(旧, 已被 COMPETITIVE.md 取代) · `ARCHITECTURE.md` · `WORKLOG.md`。

> 补齐工作流：开新对话补功能时，先看 `TODO-CATCHUP.md` 挑项 → 翻 `COMPETITIVE.md` 参考竞品思路（**自研实现，不抄源码——Arix 是独立项目**）→ 干完回 `TODO-CATCHUP.md` 打勾。
