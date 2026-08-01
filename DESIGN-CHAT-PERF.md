# Arix · 聊天界面流畅性：我们是怎么让它不卡的

> 记录本（2026-07-23 汇总，起于「整个聊天 UI 卡、想上 Rust 干渲染」）。核心结论:
> **安卓聊天「卡」几乎全在 Compose 层（重组 / 组合 / 绘制 / 线程），不是算力不够——Rust 全程证伪。**
> 治法三件事叠加:①让列表天然可跳过 ②把解析/加载赶下主线程 ③绘制层用「假模糊」而非每帧真运算。
> 竞品对照(RikkaHub / Operit)印证:**我们的玻璃是三家里最省的,别再想「优化」它。**
> 接手聊天性能先读这里 + [[onyxai-chat-glass-mask]] / [[onyxai-chat-perf-lru-0723b]] 两条记忆。

---

## 零、诊断方法（最重要，别跳过）

**第一反应不是上 Rust,是分层定位。** 我们真栽过:以为「拿 Rust 干重活/渲染」能救,结果:

| 疑似 Rust 能救的 | 真相 |
|---|---|
| 图谱物理 | 瞬态量小,Compose 够 |
| 检索 | 早已后台 + 封顶 |
| markdown 解析 | 已节流 + 记忆化,瓶颈是**主线程**不是算法 |
| 超大对话 JSON 解析 | 唯一站得住的点,但「挪后台」就解决了,没必要上 Rust |

**结论:安卓 UI「卡」的排查顺序 = 重组风暴 → 每帧组合成本 → 绘制/图层 → 线程占用。逐层量,别猜。**
定位手段:看哪些 state 变一次会让整列表重组(读 state 的位置)、哪些 composable 每帧付出非记忆化成本、哪些重活跑在主线程。

---

## 一、列表层：让「已完成的消息」天然可跳过

目标:流式 / 滚动时,**没变的历史气泡一个都不重组**。

1. **稳定 key + contentType + animateItem**（`ChatScreen.kt` LazyColumn `items(...)`）:
   `key = { chatBubbles[it].id }`(进程内单调递增的稳定 id,不是 index)、`contentType = { role }`。插删中间不错位、不串展开态。
2. **`ChatBubble` 加 `@Immutable`**（`Models.kt`）:它含 `List` 字段,默认不稳定;标 `@Immutable` 后 Compose 按**结构相等**跳过,而不是只靠「实例复用的巧合」。
   - ⚠ **不要**把 List 换 `ImmutableList`:气泡从 DB JSON 反序列化,换类型会破 Gson/序列化,风险远超收益。`@Immutable` 注解本身就够(气泡是 replace 不 mutate,承诺成立)。
   - 对照:RikkaHub 用 `compose_compiler_config.conf` 强制标稳,同一个道理、殊途同归。
3. **气泡实例复用**:`reprojectBubbles` 复用实例,配合 strong-skipping(Kotlin 2.2 默认开)让未变气泡按 `===` / `equals` 跳过。
4. **流式气泡独立于历史列表**:流式预览是 LazyColumn 尾部**单独一个 item**(`StreamingBubble`),token 更新只重画这一条,不牵动历史气泡。流式实例也 `remember(shownContent, shownReasoning)` 记忆化,免得每 token 拿新 id 破坏相等。

---

## 二、常驻不销毁：切页回来不重建

- **聊天页常驻 composition**,不参与页面 `AnimatedContent` 换页(`MainActivity.kt` 约 618)。切到设置等页 = 只 `graphicsLayer{alpha}` 淡出 + `drawWithContent{ if(alpha>0) drawContent() }` **跳过整棵子树绘制** + `pointerInput` 吃掉触摸,**不 dispose**。
  - 为什么必须常驻:挂 `when` 分支里切页就 dispose → 生成协程还在往被丢弃的列表写、切回来是新实例从 DB 重读(生成中途没落盘)→「刚发的消息全不见了」。
- **玻璃 backdrop 缓存在常驻层之上**(`rememberGlassBackdrop`,仅壁纸/尺寸/蒙层变才后台重算),切页零成本、不重算模糊。
- 结论:切页返回**不重解析、不重建 backdrop**。只有**切对话**(`key(conversationKey)`)才整份重建——这是必然的。

---

## 三、解析层：赶下主线程 + 跨 dispose 缓存

Markdown 解析(块结构 / 行内注解 / 代码高亮)是每条气泡最贵的 CPU 项。两条治法:

1. **模块级有界 LRU,消掉「气泡滚出再滚入重解析」**（`MarkdownText.kt`）:
   - `remember(text)` 只在**同屏存活期**命中;LazyColumn 把滚出屏的气泡 dispose,滚回来是全新 composition → 缓存失效、逐条重解析(快速滚动最普遍的掉帧源)。
   - 修:`parseMarkdownCached` / `inlineAnnotatedCached` / `paragraphAnnotatedCached` / `highlightCodeCached` 四个模块级 LRU(access-order LinkedHashMap + synchronizedMap),跨 dispose 存活。
   - ⚠ **key 必须用完整文本(+颜色令牌),不能用 hash**——纯函数缓存碰撞 = 永久返回错内容。用 data class key(`MdInlineKey`/`MdCodeKey`)。
   - 对照:Operit 也有 `LruCache<String, List<MarkdownNode>>`(`StreamMarkdownRenderer.kt:618`),同一设计。
2. **流式解析挪后台线程**（`MarkdownText.kt` 的 `rememberStreamingBlocks`）:
   - 洞:流式那条消息每节流 tick(150/350ms)在**主线程**对累积全文整段 `parseMarkdown` → 长回复卡在这。
   - 修:`snapshotFlow{ latest.value }.distinctUntilChanged().collectLatest { withContext(Dispatchers.Default){ parseMarkdown } }`——解析在后台,新 token 一到 `collectLatest` **取消**在算的旧帧,主线程只拿算好的 blocks。
   - 两个坑:①**首帧同步解析一次防空闪**(否则流式气泡刚出现是空的);②`rememberUpdatedState` 让 `snapshotFlow` 能观察到**普通 String 参数**变化(普通参数不是 snapshot state,直接 `snapshotFlow{text}` 只发一次)。用稳定的 `collectLatest` 而非要 @OptIn 的 `mapLatest`。
   - 流式文本每 tick 都不同,故走**无缓存**的 `parseMarkdown`(不入 LRU,免污染历史气泡缓存);`MarkdownText(streaming=…)` 参数区分:`ChatComponents.kt` 气泡正文传 `streaming=showCursor`、唤醒页 `WakeAssistantActivity.kt` 传 `true`。
   - 对照:RikkaHub `Markdown.kt:245-252` 是 `mapLatest+flowOn(Default)`,同思路。
3. **去掉每条气泡的 `SelectionContainer`**（`ChatComponents.kt`）:它是 Compose 列表里最贵的「每项」组件之一,滚动进出反复组合就卡(实测:关玻璃、纯文本无 markdown 仍卡 → 锁定组合开销)。复制走长按菜单;要拖选再做「长按进选择模式」按需包。

---

## 四、绘制层 / 玻璃：假模糊,运算一次就够

**这是我们相对竞品最省的地方,别把它当负担。**

- **玻璃 = 预模糊壁纸位图 + 运行时贴图**:`g.backdrop` 是离线预模糊的壁纸,`rememberGlassBackdrop` **录一次缓存**;每气泡 `drawBehind` 里只 `drawImage` 贴自己那一块 + 一层薄 `drawRect(tint)`(`ChatGlass.kt`),**没有任何每帧 blur 运算**。
- **玻璃绑在气泡本体**(per-bubble `drawBehind`),气泡怎么动玻璃怎么动,**永不错位**;`.clip(shape)` 把片元着色**限死在气泡区域**,不是全屏填充。
- ⚠ **`drawBehind` 里绝不读每帧变的值**(如 overscroll 的 translationY):一读,回弹时每个气泡玻璃每帧重画 → 顶部下拉卡。代价:回弹 40dp 内模糊跟气泡轻移(不贴死固定背景),换下拉丝滑,划算。
- **回弹双弹**:过度滚动 `onPreFling` **消费全部速度**(否则列表把甩速 fling 到边缘触发系统二次回弹);`dampingRatio=1` 只治过冲、治不了双弹。
- **进度胶囊 O(1)**:`(首项index+项内比例)/(总条数−可见项数)` 全 `layoutInfo` 单快照 + `animateFloatAsState(120ms)` 抹平跳变。**别用「Σ每项高度求真总长」**(O(条数)/帧,气泡多就卡)。
- 滚动状态一律 `derivedStateOf` / `snapshotFlow` 读(`atBottom`/`progress`/`isScrollInProgress`),不裸读 `layoutInfo` 引发整列表重组。

---

## 五、加载层

- **大对话 messagesJson 达 MB**:`JSONArray` 解析原在 `LaunchedEffect`(主线程)冻 UI → 挪 `withContext(Dispatchers.Default)`。
- 数据库侧的启动崩溃(单列超 2MB CursorWindow)另见 [[onyxai-conv-cursorwindow-crash-0722]](分列拼装 + 分块读)。

---

## 六、手表专项（动画缩放常 = 0）

- **凡「必须一直动」的东西一律 `withFrameNanos` 手推**:手表系统动画缩放=0 很常见,`tween`/`infiniteTransition`/`basicMarquee` 会直接跳终值(跑马灯、转圈、按键条收起都栽过)。
- **`imePadding()` 以真机为准**:静态推理会得「edge-to-edge 下窗口不 resize、要自己让位」,实机相反,加了双重偏移把输入框顶半空。
- **`animateItem` 的 tween spec 提到文件级 `val`**(`ChatScreen.kt` 的 `BUBBLE_FADE_IN`/`BUBBLE_PLACEMENT`/`BUBBLE_FADE_OUT`),别每气泡每帧新建。
- 删除退出动画:缩放≈0 时 `fadeOutSpec=null` 直接消失(否则 fadeOut 永不完成 → 残留幽灵气泡 → 重新生成花屏)。

---

## 七、竞品对照（为什么说我们的玻璃已经最省）

派 agent 通读 `F:\CompetitorRepos\{rikkahub,Operit}` 得出:

| App | 玻璃做法 | 成本定位 |
|---|---|---|
| **RikkaHub** | 几乎不用:Haze 只挂输入框一层、可关;气泡纯色 Surface+alpha(`ChatMessage.kt:365`) | **回避**成本 |
| **Operit** | 真实时 `RenderEffect` 硬件模糊(Kyant Backdrop / fletchmckee Liquid 库,API33+,低于退化无模糊 tint;`LiquidGlass.kt`/`WaterGlass.kt`);每气泡各跑一次有界 GPU blur+折射 | 用**硬件扛**(比我们贵) |
| **我们** | 预模糊壁纸录一次 + 运行时每气泡贴图,`clip` 限区域 | **三家最省**(无每帧 blur 运算) |

- Operit 做对且**我们本来就做到**的一条:源层只捕获**静止背景**、不捕获滚动内容(我们 backdrop 就是静止壁纸)。
- 审计/Operit-agent 说我们「每帧全屏 blit」——**不成立**,`clip` 已把着色限死在气泡区域。用户原话:「假模糊、运算一次就行了」,对。
- **想升级到 Operit 那种液态折射观感才需要上 RenderEffect,那是加成本换效果、不是治卡。** 玻璃保持现状。

**值得学 RikkaHub / Operit 的只有一条:把解析赶下主线程**(第三节已做),不是玻璃技巧。

---

## 八、走过的弯路（都别重犯）

- ~~saveLayer + `BlendMode.DstIn` 抠玻璃蒙版~~ → 真机漏满全屏。`clipPath` 才靠谱(后来连蒙版都不用了)。
- ~~独立 ChatGlassLayer / Canvas 层 + 和列表各自 graphicsLayer「对表同步」~~ → 两层帧延迟、回弹错位。**回到 per-bubble drawBehind 才对**(Operit 也是玻璃绑各气泡节点、和列表同帧,不是独立异步层)。
- ~~并集 clipPath 单层蒙版 + 登记表~~ → 按气泡数 O(N) 每帧建并集裁剪,气泡多更卡。
- ~~上 Rust 干渲染/物理/检索~~ → 全证伪,卡在 Compose 层。
- `static CompositionLocal` 的 `provides` 传字面量 lambda → 每次重组全量重组子树。**provides 的值必须 `remember` 稳定**(`LocalChatOverscroll`)。
- 列表尾部有 6 个常驻零高度 utility item(`TRAILING_UTILITY_ITEMS`),凡「拿最后可见 item 下标判到底」的地方都要算进容差。

---

## 九、可切换选项（2026-07-23 加）

两处做成 `ThemeConfig` 里的可切换设置（个性化页），默认取更流畅那档:

- **滚动指示** `scrollIndicator`（默认 `JUMPER`）:`JUMPER`=右侧一列跳转圆钮(跳顶/上一条/下一条/回底),只在最近滚过+手指松开时浮现,**零逐帧进度计算**(位置点击时现取) / `CAPSULE`=位置胶囊(拖动小点+定位器,滑动算进度) / `OFF`=不显示。组件 `ChatScrollJumper`(`ChatNavigator.kt`)。
- **工具与思考** `metaBlockStyle`（默认 `TIMELINE`）:`TIMELINE`=一条 AI 消息内的思考+工具调用合并进**一张淡色卡 + 竖直时间线**(非气泡,`ChainTimelineCard`/`ChainStep`,`ChatComponents.kt`) / `GLASS`=各自一张玻璃卡(旧)。**局限**:跨气泡合并(思考/调用 + 独立 tool 结果气泡并一张卡)未做——它们是 LazyColumn 各自 item,合并要重构列表;现为气泡内合并 + 结果各自单步卡。

## 十、剩余 / 可选（未做）

- 审计 #4 已做（per-item `derivedStateOf` 隔离命中/菜单/选中三态）；语法高亮已异步化 + 超 6000 字降级为纯文本。
- 切对话(非切页)整份重建重解析:LRU 命中能省重复解析,若仍慢可考虑气泡级缓存 / 分页。
- 时间线卡的「跨气泡真合并」(见九),要重构 LazyColumn 分组,高风险,视需要再做。

## 十一、2026-07-25 深度复审（用户反馈「长对话快速滑动/跳到底仍卡」）

派两路只读 agent（每帧分配/绘制 + 流式/滚动热路径）逐行复审，结论：**每帧 / 每 token 的稳态热路径已经彻底优化、没有低垂果实**——流式隔离、150/350ms 节流、`collectLatest` 取消、快照跟随、光标 draw-only、滚动指示零逐帧订阅，全都到位（详见两份 agent 报告）。剩下两类是**吞吐/固有成本**，不是每帧 bug：

1. **快速 fling 长对话卡 = 首composite 吞吐天花板。** LRU 只缓存 markdown**解析**，缓存不了**文本布局（测字形）**。fling 速度越高，单位时间滚入越多新气泡 → 主线程首次组合+首次测量它们（每气泡 Column+per-block Text 布局）压不过帧。用户选择上 **(b) 方案（一直渲染 Markdown、不要占位降级）**：**已实现** `TextLayoutResult` 缓存 —— `MarkdownText.kt` 加 `mdLayoutCache`(LRU 80) + `CachedParagraph`（`Modifier.Node`=LayoutModifierNode+DrawModifierNode，measure 阶段拿真实 maxWidth 测量并缓存、draw 阶段 `Canvas.drawText` 画）。key=源串+colorsKey(基/链接/代码三色合成)+字号+宽度+密度+方向。**仅纯段落走它**：含内联公式退回 `Text`（要占位 InlineTextContent）、含链接退回 `Text`（`getLinkAnnotations` 非空，保留 `LinkAnnotation` 点击）。**收益范围**：滚出再滚回（来回滑长对话）的段落免重测字形；**首次 fling 穿过全新内容仍要测一次**（无解，那本就是第一次）。⚠ 视觉必须真机核：缓存布局与 `Text` 的折行/字体/行高/位置要一致，若某段落错位/串色即此处，回退把 `else CachedParagraph(...)` 改回 `Text(annotated,...)` 即可。
   - 未采纳的 (a)：fling 时降级纯文本占位——用户明确否掉（要一直 Markdown）。
2. **跳到底/搜索跳转/定位跳转卡 = `animateScrollToItem` 跨长距在动画途中 burst 首composite 大量落点气泡（配 `1_000_000` 大偏移更甚）。已修**：加 `LazyListState.animateJumpTo(index,offset)`（ChatScreen 文件级）——远距先 `scrollToItem` 瞬跳到落点±12 项、再 `animateScrollToItem` 动画最后一小段。**保留动画观感、把首composite 压成一小段**。三处已接：回底钮/`ChatLocatorDialog.onJump`/搜索命中 `LaunchedEffect`。用户明确要「保留动画、别瞬跳」，故不用纯 `scrollToItem`。

**顺带清掉的每帧分配（agent 发现，polish）**：聊天顶栏 `drawWithContent` 每帧新建 `Brush.verticalGradient` → 改 `drawWithCache`（Brush 随尺寸缓存一次；绘制顺序 DstIn→drawContent 严格不变）。仅在顶栏跑马灯动时省，非滚动路径。

**待验的一处（未改，agent 提）**：`CapsuleBridge.onOutputText(streaming.content)` 每 token 传**全量增长串**、未节流，若 bridge 内做格式化/IPC 则 O(n²)/长回复；确认成本后再决定是否节流/隐藏时跳过。

---

> **状态**:第一~六节均已落地,`:app:assembleDebug BUILD SUCCESSFUL`。**性能/观感类改动一律真机为准**——尤其玻璃手感、流式不丢字/不空闪、长列表快速来回滚。全部未提交(随 0719~0723 那批统一整理再提交)。

---

## 十一、把同一套手法推广到其它页面（2026-07-25）

聊天页那套「列表可跳过 + 派生记忆化 + 重活下主线程 + 数据类标稳」不是聊天专属,凡「响应式列表 / 每重组重算的派生集合 / 主线程读写」的页面都适用。这轮把它铺到了 5 个页面 + 1 个共享组件。

**跨模块标稳:配置文件而非注解。** 新增 `compose_stability.conf`(仓库根),在 `app/build.gradle.kts` 用
`composeCompiler { stabilityConfigurationFiles.add(rootProject.layout.projectDirectory.file("compose_stability.conf")) }` 挂上。
里面列「跨 data/cloudapi 模块 或 含 List/Map 字段」的只读数据类,强制 Compose 按结构相等判稳,让持有它们的列表行能跳过重组——**等价于给 ChatBubble 标 @Immutable,但不用让纯 data 模块凭空依赖 compose-runtime**(RikkaHub 同思路)。已收录:`MemoryEntity`/`CharacterCardEntity`/`ConversationSummary`(data)、`ApiMonitor.{ApiMonitorSummary,ProviderStats,CallRecord}`(cloudapi)、`OperitCompat.OperitPackage`/`CloudMarketplace.{MarketItem,MarketComment}`(tool)、`MemEdge`(app)。
- ⚠ **坑一:注释前缀是 `//` 不是 `#`**。用 `#` 会被当成 pattern → `Error parsing stability configuration file`,整个 compileDebugKotlin 失败。
- ⚠ **坑二:嵌套类用 `Outer.Inner` 点号**(如 `com.arix.tool.OperitCompat.OperitPackage`);写错**不报错、静默不生效**,所以 FQCN 要对着源码核包名+嵌套层级。
- app 模块内的只读数据类(如 `ToolActivityBus.Entry`,含 `JSONObject?` 会被默认判不稳)仍走**直接 `@Immutable` 注解**,与聊天页一致。

**各页落地(与聊天页对应的治法):**

| 页面 | 派生记忆化 | 每项 O(n)→O(1) | contentType | 重活下主线程 |
|---|---|---|---|---|
| ConversationListScreen | `folders`/`conversations` | `cards.find`→`cardsById` | ✓ | (usage 早已 IO) |
| MemoryPage ⭐ | `displayedMemories`(3 过滤+排序)/`folders` | `cards.find`→`cardById` | 主列表+两个 picker | 搜索 rank→`Dispatchers.Default`;状态卡 prefs+JSON→`produceState`+IO |
| CharacterCardPage | — | `stripCard` 的 `Regex` hoist + 结果 `remember(text)` | 主列表+生成对话 | (生成对话是 ephemeral,余下主线程读按需再说) |
| OperitPage | `types`;删死代码 `remoteItems/localIndexItems` | key 去 index 污染(`distinctBy{id}`+纯 id) | ✓×2 | 网络函数**本就** `withContext(IO)`(审计误报,已核) |
| ActivityCenterPage | 最近调用切片 `remember(recentCalls)` | — | (非 lazy 列表) | `getSummary/getRecent`→`Dispatchers.Default` |
| ToolActivityPanel(共享) | — | — | `contentType={callerKind}` | — |

**方法论复用**:审计先派只读 agent 按「稳定 key/contentType、每项非记忆化成本、派生集合未 remember、主线程重活、裸 layoutInfo 读、数据类未标稳」六条清单逐页扫,再逐条改。**审计是静态的,会误报**——OperitPage「网络在主线程」经核实是 callee 内部已 `withContext(IO)`,没动。

> **状态**:`:app:assembleDebug BUILD SUCCESSFUL`(2026-07-25),stability config 无解析错误。同样**真机为准**:重点验记忆页长列表快速滚 / 搜索输入不卡 / 会话页滚动、Operit 市场搜索重排不闪。全部未提交。

---

## 十二、再推广到独立终端 App（:terminal，2026-07-25）

同一份六条清单扫了终端的 4 个 Compose 页 + View 版 TermActivity。**stability config 现在两个模块共用同一份**
（`terminal/build.gradle.kts` 也挂 `composeCompiler { stabilityConfigurationFiles.add(...) }`）——
一个模块用不到的条目只是静默忽略，不会报错，所以不必拆成两份。

| 位置 | 改了什么 | 为什么 |
|---|---|---|
| EnvActivity ⭐ | `specById`/`flavors`/`activeFamily`/`mirrorOptions` 全部 `remember` 化；`prettyName` 挪 `produceState`+IO；备份列表挪 `produceState`+IO | **原先是在列表循环里逐行现算**：`EnvRegistry.spec()` 每次都重解一遍自定义镜像的 JSON、`prettyName()` 每行读一次 `/etc/os-release`（真·磁盘读）、`flavorOf()` 每行读一次 prefs。页面每重组一次就全跑一轮 |
| EnvActivity | 换源 / 删备份 挪 `Dispatchers.IO` | 换源要重写容器里好几个源文件（Fedora 那条逐行重写 `.repo`），别卡在点击那一帧 |
| TermSettingsActivity ⭐ | `Swatches` 的颜色 `remember(content)` | 原先每次重组对每个预设跑 6 次正则 = 24 次匹配，而这页点任何开关都会重组 |
| TermSettingsActivity | 选字体 `copyTo` 挪 IO；美化落地（写 4 个 rc 文件）挪 IO；`labelOf`/`familyOf` 记忆化 | 字体上限 32MB，**主线程拷 = 直接 ANR**，与之前修过的「主线程拷大图」同一类坑 |
| FilesActivity | `items(...)` 加 `contentType`（dir/file）；`SimpleDateFormat` 提为单例；行副标题 `remember(e)` | 原先**每行每次重组都 new 一个 SimpleDateFormat**（要解析 pattern + 拉 locale 数据），几百个文件时就是滚动掉帧里那份看不见的税 |
| PkgActivity | 三处 `items(...)` 加 `contentType`；行副标题 `remember(p)` | `shown` 本来就 `remember(list,filter,sortBy)` 过了（审计通过，没动） |
| TermActivity(View) | `sessionSignature`/`buildLaunchArgv`/`buildEnv` 挪 IO；`shortLabel` 加缓存 | 这三个都要读 passwd / lstat rootfs / 探 su —— **onResume 那一帧最紧张，不该在上面摸磁盘** |
| compose_stability.conf | 补 `:terminal` 段：`Entry`/`Listing`/`DistroSpec`/`DistroSource(.Tarball/.Oci)`/`TermBeautify.PromptStyle`/`TermuxStyle.ColorPreset`/`TerminalEnv.ShellInfo`/`DistroProvision.MirrorOption`/`QuickCmds.Cmd`/`BackupManager.BackupFile` | 都是「构造完不再变」但字段含 List/`java.io.File`/sealed 的只读模型，默认判不稳 → 持有它们的行每次全重组 |

**核过没动的**：FilesActivity 的目录扫描本来就在 IO（`listFiles`+`stat` 早就 `withContext`）；
`selection` 是 `Set` 不是 `List`，`e.path in p.selection` 已是 O(1)；PkgActivity 的派生列表已记忆化。
**审计是静态的会误报，动手前逐条对着源码核**——这条与第十一节的教训一致。

> **状态**:`:terminal:assembleDebug` + `:app:compileDebugKotlin` 均 BUILD SUCCESSFUL(2026-07-25)，
> 共用的 stability config 无解析错误。**真机为准**：重点验文件页大目录快速滚、运行环境页开合、
> 设置页点开关不卡、换字体不 ANR。全部未提交。
