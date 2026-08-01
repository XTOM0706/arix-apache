# 前端重写 · 待办与进度（12 条反馈，2026-07-02）

## 第八轮（2026-07-03 夜，旧页重做 + 设置搜索 + 人话说明）
- **设置搜索** — 提交 `576f6d1`。设置中心顶部搜索框，按标题/关键词过滤分组条目。
- **设置中心长按人话** — 提交 `576f6d1`。每条设置长按弹出「人话」解释（干什么/有什么用）。
- **模型配置页重做** — 提交 `5411c4e`。Catppuccin→莫奈；生成参数加「ⓘ说明」弹窗（温度/top_p/最大Token/频率惩罚/存在惩罚 的调高调低影响）。
- **对话管理页重做** — 提交 `7b2a8de`。莫奈令牌，置顶用 accents.warning。
- **语音识别(STT)页重做** — 提交 `4f04a65`。莫奈；成功/警告态用 accents。
- **语音唤醒(Wake)页重做** — 提交 `cf3d570`。莫奈。
- **八个高级页批量重做** — 提交 `e515937`。终端/插件制作/API监控/触控/权限/崩溃报告/包管理/Operit兼容 全部莫奈令牌。
- **✅ 导入导出（全部完成）**：
  - `ImportConverters`（提交 `b998728`）：导入时把主流第三方格式统一「转成我们的」——酒馆 SillyTavern 角色卡 V1/V2（含 `data` 外壳、内嵌 character_book）、lorebook/world-info、Operit/通用记忆数组、snake_case 配置。**导出永远用我们自己的格式，不适配别人**（按用户要求）。
  - `ImportExportButtons` 通用组件：导出四法（系统分享 / 保存到文件[SAF] / 复制JSON / 导出到应用目录）+ 导入两法（从文件[SAF] / 粘贴JSON）。
  - 嵌入各页（提交 `6e1bed3`）：角色卡（整页导出全部+导入 / 单卡导出）、世界书（整页+单本）、记忆（按当前角色卡筛选范围）、模型配置（导出全部+导入）。三个 import 均支持整批数组。
  - 导入导出中心页重做（提交 `f406d31`）：莫奈化，集中处理对话/功能包(skill/sandbox/mcp)+通用粘贴导入。
- **✅ 全部旧页配色完成**：除主题定义文件与 MarkdownText 的具名颜色表（`<font color=red>` 用，须保留字面量）外，所有设置/功能页 0 处写死 0xFF。
- 铁律遵守：只用 MaterialTheme 令牌 + LocalXtomAccents，无写死 0xFF；一页一提交、编译过。



分支 master。已提交到 `3fe3ecb`。源码在 `app/src/main/kotlin/com/arix/app/`。
构建：`./gradlew.bat assembleDebug`，APK 在 `app/build/outputs/apk/debug/app-debug.apk`。
铁律：只动 UI 表现层；颜色/字体/形状/动效只用 MaterialTheme 令牌 + LocalXtomAccents，禁写死 0xFF；一项一提交、编译过才算完。

---

## 第二轮反馈（12 条，2026-07-03）

### ✅ 已完成
- **R1 工具调用文本泄漏** `<｜｜DSML｜｜tool_calls>` — 提交 `38cc855`。`MarkdownText.kt` 加 `stripLeakedToolCalls`（兼容全/半角竖线，含流式未闭合），显示与保存双端剥除（`ChatScreen` 两处 `content`）。⚠️ 仅剥文本，未解析执行——模型若不用原生 tool_calls，该次搜索本就没跑，属模型侧问题。
- **R2/R7 流式动画太硬 + Markdown 只在最后渲染** — 提交 `38cc855`。助手流式改为一律走 `MarkdownText` 实时渲染；`StreamingBubble` 加 ~90ms 节流快照兜住每 token 全量重解析；气泡加 `animateContentSize` 高度过渡。
- **R3 HTML/CSS/JS/折叠** — 提交 `c21c23e`。`<details>/<summary>` → 可点击折叠块（高度动画，内容递归 Markdown）；`<script>/<style>` 整块隐藏。CSS/JS 效果按约定不渲染（手写方案）。
- **R4 图片留白** — 提交 `ce57350`。根因：manifest 无 `usesCleartextTraffic`，http 图片被默认拦截→留白。已放开 + 改 `SubcomposeAsyncImage`（加载中转圈 / 失败显示 URL 可点开）。
- **R5 粗斜体/删除线** — 提交 `38cc855`。粗体/斜体本就正常（此前是流式只显纯文本的错觉）；新增 `~~删除线~~`，粗体内可再嵌套。
- **R6 &nbsp;/HTML 实体 + 大片空白** — 提交 `38cc855`。`decodeHtmlEntities` 解码命名+数字实体（`&nbsp;`→空格等）。
- **R9 搜索无动画** — 提交 `76f8ab7`。会话内搜索栏 `AnimatedVisibility` 展开/收起滑动+淡入。
- **R12 头像占横向空间** — 提交 `cb9bbad`。改 Telegram 式：头像(22dp)+名字移到气泡上方一行，气泡占满整行宽度。

### ⏳ 待你实测确认（headless 无法复现，属"手感/视觉"）
- **R8 长文追不上** — 应已被节流实时渲染改善；请重测，仍不行再调（可能要把跟随从 `scrollToItem` 换更激进策略）。
- **R10 删除无动画 + 始终显示与其他 UI 重叠** — 代码上 `animateItem(fadeOutSpec)` 应触发淡出；"重叠"我无法复现，需你截图/描述是哪个 UI 重叠（右键菜单？删除后残留？）才能定位。
- **R11 思考展开有动画收回没有** — 猜测：流式结束时"思考中(展开)"的流式气泡被换成全新的已折叠真实气泡，故收起没有过渡（是实例替换不是收起动画）。若是同一气泡内点击收起也没动画，请告知，那是另一处。

---

## 第三轮反馈（2026-07-03）

### ✅ 已完成
- **头像太小** — 提交 `523c884`。22→30dp。
- **图片无法加载(403)** — 提交 `523c884`。图片请求带浏览器 User-Agent，修图床 403。⚠️ 若 AI 给的是假/失效 URL 仍会显示失败态（点开可验证）。
- **高亮/任意颜色文本** — 提交 `523c884`。`==高亮==` / `<mark>`；`<font color>` / `<span style="color:">` 支持 #hex/rgb/色名。粗斜体本就正常（此前疑似流式卡顿或未闭合造成的错觉）。
- **流式一旦 Markdown 就卡/没动画** — 提交 `523c884`。流式长文(>1200字)期间先纯文本、完成再整段 Markdown；节流 90→150ms。
- **追不上** — 提交 `523c884`。跟随改滚到流式气泡"底部"(大 offset)始终露最新 token；atBottom 容差放宽。
- **任务列表复选框** — 提交 `fc9323c`。`- [ ]`/`- [x]` → ☐/☑（勾选加删除线）。
- **多级引用** — 提交 `fc9323c`。`>>` 按层级缩进+叠竖条。
- **滑动进度条** — 提交 `6b08eec`。聊天右侧细条随滚动反映位置（>5 条显示）。
- **顶栏瘦身** — 提交 `9abff8a`。标题+角色/偏好副标题并一行，删两块整宽卡片。
- **底部瘦身** — 提交 `9abff8a`。思考/工具胶囊缩小，输入图标/发送 48→40dp。

### ✅ 三个动画项（提交 `d828a27`）
- **展开 <details> 会卡** — 去掉展开的 `animateContentSize`，改一次性布局，避免大块内容逐帧重排。
- **滑到顶/底加弹性** — 加自定义 `elasticOverscroll`（nestedScroll + 阻尼位移 + spring 弹回），滑到边界继续拖有回弹。
- **思考收起没动画** — 思考正文改 `AnimatedVisibility`（expand/shrink+fade），展开/收起都过渡；⚠️ 流式气泡→真实气泡的实例替换那一下仍是硬切，属架构限制，待实测是否可接受。

### 📌 已决定不做
- **Mermaid 图** — 你选择暂不渲染，```mermaid 保持代码块显示源码。要出真实图需 WebView+mermaid.js，日后再议。

---

## 第七轮反馈（2026-07-03 晚二，可读性+自动隐藏+滚动条）
- **各胶囊去底** — 提交 `40d67d7`。顶栏图标/模型名/输入栏都改扁平无底、无边框（不留浅色块也不加边框，提升可读性）。
- **建议进输入框** — 提交 `40d67d7`。移除外部芯片；输入占位无建议时显示预设、有建议显示"💡 建议"。
- **进度条可拖 + 自动隐藏** — 提交 `40d67d7`。整条右侧 22dp 可拖动更好抓；滚动/拖动时显示、停约1.2s淡出。
- **抽屉字号/时间分组** — 提交 `40d67d7`。对话标题 14sp；时间分组标题加粗 primary、留白更大，更明显。
- **顶栏/输入栏滚动自动隐藏** — 提交 `e7aafbc`。下滑隐藏、上滑或点屏呼出；应用设置可关（默认开）。
- **⏳ 文件 / 项目**（功能，未做）：需数据层改造——**文件**要持久化上传/生成的文件（当前附件不落库）并做汇总页；**项目**要新增项目存储、把对话/文件归组、供 AI 分析偏好。属独立功能开发，建议单独一轮做（现为占位页，不影响其它功能）。

## 第六轮反馈（2026-07-03 晚，功能+抽屉重做）
- **动态输入提示** — 提交 `b5396f2`。占位轮换友好提示；每轮回复后 AI 生成"下一句"建议芯片，点击填入。
- **麦克风↔发送过渡动画** — 提交 `b5396f2`。AnimatedContent 淡入淡出+缩放，不再硬切。
- **抽屉 GPT 式重做** — 提交 `dd8d500`。顶部 Arix AI + 搜索球；导航=角色卡/文件/项目/记忆；对话按时间分组可滑动；底部=图标+聊天(新建) 与 设置圆球。**所有旧功能进「设置中心」settings_hub，不丢功能**；全程莫奈令牌。
  - 约束遵守：**不丢功能**（原 14 个高级页全部保留在设置中心）；**莫奈配色**（全用 MaterialTheme.colorScheme）。
  - ⚠️ 待定义：**文件 / 项目** 目前是占位页（功能未明确）——你想让它们各装什么，告诉我再实现。
  - ⚠️ 搜索球目前搜"所有对话"内容（复用旧全局搜索）；你原话是"搜这个角色卡的消息内容"，若要按当前角色卡过滤我再改。

## 第五轮反馈（2026-07-03 傍晚，界面大改）
- **滑动进度条可拖动 + 防卡** — 提交 `3fb60e6`。滑块 offset 用 lambda 在布局阶段定位（不每帧重组），可上下拖动滚动定位。
- **底部输入栏 ChatGPT 式一体胶囊** — 提交 `d6953d4`。一体圆角胶囊(BasicTextField 无边框)、整体抬高；左「+」菜单 = 拍照(FileProvider+相机)/图片/文件/让AI调用插件/思考(循环)/工具(开关)；右侧发送与语音合一(有字→发送，无字→语音)；删掉独立思考/工具胶囊行。
- **顶栏重做** — 提交 `e302762`。三条杠→Segment 艺术图标、搜索、新建各自独立圆底；去掉标题；菜单旁小椭圆显示当前 chat 模型；页内可编辑标题行移除（重命名转对话管理）。

## 第四轮反馈（2026-07-03 下午，提交 `6aa31f9`）
- **details 展开丢了动画** — 改回有动画：用 `AnimatedVisibility(expandVertically+fade)`，内容一次测量后按 clip 揭示，不逐帧重排（有动画且不卡）。
- **自动跟随追不到思考内容** — 跟随的 snapshotFlow 改按 `content.length + reasoning.length`，思考流式时也跟。
- **思考流式文本丢动画** — 思考正文加回 `animateContentSize` 平滑增高（同时保留收起的 `AnimatedVisibility`）。
- **弹性回弹太夸张 + 松手留大片空白回不来** — 根因：fling 惯性阶段仍在累积位移，`onPreFling` 已过去无法回弹。修：只在 `UserInput` 拖动阶段累积，`onPreFling`/`onPostFling` 双兜底 spring 回弹；幅度 110→40dp、阻尼加大，改轻微。
- **顶栏没精简** — 聊天页顶栏不再显示 App 名「Arix」（与页内可编辑标题行重复），新建按钮去掉圆底 chrome。
  - ⚠️ 若你想要的是把「对话标题」直接搬进顶栏（RikkaHub 式），需要把标题状态上提到顶栏并做重命名入口，是更大的改动——要的话下轮做。

## ✅ 已完成（本轮已提交）
- **#2 Markdown 图片** — 提交 `3fe3ecb`。`MarkdownText.kt` 的 `imageLine` 正则改为剥离 `"标题"`、URL 取到首个空格前。
  - ⚠️ 仍未做：**行内图片**（图片写在段落中间而非独占一行）不渲染——需要 Compose `inlineContent`（较复杂）。
- **#10 AI 标题偷懒** — 提交 `3fe3ecb`。根因：`ChatScreen.kt` 发送时立即把 `convTitle` 设成用户首句，挡住了后面的 AI 起名分支（条件是 `convTitle=="新对话"`）。已删掉那两处立即赋值（performSend + pendingAutoSend 两条路径），AI 起名失败/空才回退用户首句。
- **#12 改 AI 消息=就地改文本；改用户消息才重生成** — 提交 `0840396`。编辑弹窗按 `chatBubbles[idx].role` 分支：user→截断+回填输入框重生成；assistant→仅 `copy(text=)` + `saveConversation`，不截断不重生成。按钮文案随角色切换「保存」/「保存并重新生成」。
- **#9 生成时智能跟随滚动** — 提交 `f1b88c7`。新增 `atBottom` derivedState + `LaunchedEffect` 用 `snapshotFlow{streaming.content.length}` 观察内容增长，仅当用户在底部时 `scrollToItem`；上滑离底自动暂停，滑回底部恢复。不把 `streaming.content` 读进 body，保持流式重组隔离。
- **#1 会话内搜索 + 动画** — 提交 `79d057f`。顶栏放大镜改为切换 `chatSearchActive`，传入 `ChatPage(searchActive, onSearchClose)`；`ChatPage` 内渲染搜索条，按关键词过滤 `chatBubbles`，`animateScrollToItem` 平滑跳转，显示「第n/共m个」+ 上一个/下一个，命中气泡加 primary 边框高亮。旧 `ConversationSearchOverlay`（跨对话）保留在 `MainActivity.kt` 未接入，待接对话管理页。
- **#7 Markdown HTML 标签** — 提交 `dd1d429`。`inlineAnnotated` 加 `<` 分支：`b/strong→粗`、`i/em→斜`、`u/ins→下划线`、`code/kbd/tt→等宽`、`del/s/strike→删除线`、`a href→链接`、`br→换行`；未知标签仅去壳保留文字；不像标签的 `<`（如 `a < b`）原样输出。
- **#6 Markdown 脚注** — 提交 `36788bb`。行内 `[^label]` 渲染成上标（`BaselineShift.Superscript`）；整行 `[^id]: 说明` 收进 `footnotes`，消息末尾以分割线 + 小字弱色列出（`MdBlock.Footnotes`）。
- **#8 Markdown 定义列表** — 提交 `02d9fa6`。术语行 + 紧随其后一或多行 `: 释义` → `MdBlock.DefList`，术语加粗、释义缩进弱色。
- **#4 Markdown 行内数学 `$x$`** — 提交 `c064ef8`。`inlineAnnotated` 加 `onMath` 回调把 `$...$` 登记为 `InlineTextContent`；`MarkdownParagraph` 用 JLaTeXMath 预建 drawable 量宽高换算 em 占位、子组件铺满，公式随文字排版换行。两端贴空格/跨行/`$$` 排除，避免货币误判。
- **#11 删除动画** — 无需改码（已覆盖）。`ChatScreen.kt` items 直接子 `Box` 已带 `animateItem(fadeOutSpec)` 且按 `id` 作 key，删除即触发淡出；`animateItem` 内部有界，不会因大批增删堆叠卡死。待表上确认。

## ⛔ 未开始 / 待做（都需你实测后再定，非纯代码问题）

### #5 搜索新闻还会搜到昨天的（时效）
- `SearchTool.kt` / `search/SearchEngine.kt`：抓 HTML 没有时间过滤。可尝试：查询词自动加时间限定（如百度 `&gpc=stf` 时间范围、必应 `&filters=...`），或在结果里解析日期排序。免 key 抓取下时效难保证——彻底方案仍是带 key 的搜索 API。垃圾字问题用户说 AI 已能自动换引擎，OK。

### #3 AI 自选档「似乎直接不思考了」
- 已把 `CloudApiClient.kt` 的强制思考阈值从 `>0` 改到 `>=2`，AI自选(1)不再 API 层强制，靠提示词。用户要用难题测。若发现自选档从不思考：可能模型忽略提示词——可加强提示，或对 DeepSeek 之类保留一个「弱思考」信号。待观察。

## 备注
- 会话搜索的跨对话实现（`MainActivity.kt: ConversationSearchOverlay`、`convMatchSnippet`、顶栏 `showConvSearch`/放大镜）需按 #1 改造成当前对话内搜索。
- 数学 jlatexmath 依赖已加到 `app/build.gradle.kts`。
- 附件缩略图/工具卡/思考区/头像等本轮已完成，见 git log。
