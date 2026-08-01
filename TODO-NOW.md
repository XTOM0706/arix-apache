# 当前进度与待办（2026-07-19 会话）

> 这份文件是**接手入口**。窗口关掉/上下文丢了，先读这里。
> 权威长期路线看 `TODO-V1.md`，终端专项看 `TODO-TERMINAL-APP.md`。
>
> **🆕 2026-07-27：接下来干什么已重新排过，看 `TODO-NEXT-0727.md`**（含战略定位、P0-P4 排序、
> 明确不做的清单、待拍板项）。依据是 `RESEARCH-COMPETITIVE-2026-07-27.md`（六竞品 + hermes-agent/
> grok-build/openclaw 源码级实测）与 `RESEARCH-MEMORY-EXTERNAL.md`。
> ⚠️ `COMPETITIVE.md`（2026-07-11）**多处已过时，别再引用它的缺口清单**。
>
> **本轮所有改动只做过编译验证（`assembleDebug` 通过），真机几乎全没验。**
> 产物：`app/build/outputs/apk/debug/app-debug.apk`、`terminal/build/outputs/apk/debug/terminal-debug.apk`

---

## 🆕 2026-07-30 会话·隐私级权限 + 原生协议 + 竞品四条（**最新，先看这里**）

> `:app:assembleDebug` 通过（APK 74.1MB）、**单测 114 个全绿**（原 94 + 新增 20）。**真机零验证。**
> 改动 44 个文件。这一轮开了 3 路子 agent 并行（文件所有权严格切开、一律禁跑 gradle），我做协议层与全部接线。

### 一、隐私级权限（你拍的板：新加一档等级）

`AndroidPermissionLevel` 在 STANDARD 与 ACCESSIBILITY 之间插了一档 **`PRIVATE`（"隐私"）**。
它的两条实际后果：默认策略 `ASK`（**不跟随**默认 ALLOW 的主开关）、**永不进模型自动审批**。

标上这一档的 9 个工具：`contacts` / `read_sms` / `calendar` / `notification` / `clipboard` /
`app_usage` / `screen_ocr` / `make_phone_call` / `health_measure`。

⭐ **这一轮查出来两个原先没记录的**：
- `screen_ocr` 自己的描述里就写着「有 root 或 Shizuku 时走 screencap，**不弹任何框**、秒出」，
  而它**显式**标了 STANDARD → 整屏截图（连着用户当时开着的任何 App）零提示。
- `make_phone_call` 在「AI 直接执行操作」开着且有 CALL_PHONE 时走 `ACTION_CALL` **真拨出去**，不经确认。

❌ **两个此前被误判为高危的，查清了不是**：`send_sms` 走 `ACTION_SENDTO`（调起短信编辑，用户自己按发送）、
`take_photo` 走 `ACTION_IMAGE_CAPTURE`（用户自己按快门）。用户都在回路里，保持 STANDARD。

权限页新增「个人数据」分组（一键全部允许/询问/禁止，三档整齐时才高亮）。审批弹窗的风险提示改成三档配色：
普通级不着色、隐私级 warning、无障碍以上才 error（隐私级会是最常弹的一档，每次刷红只会练出"无脑点允许"的手）。

### 二、原生 Anthropic / Gemini 协议

新增 `ChatProtocol`（OPENAI / ANTHROPIC / GEMINI）+ `AnthropicProtocol.kt` + `GeminiProtocol.kt`。
**只有四处按协议分家**：URL、认证头、请求体、单片解析。**SSE 读取循环三家共用**——
Anthropic 把事件类型写在 data 载荷的 `type` 字段里、Gemini 走 `alt=sse` 也是一行一片，
两家都不需要读 SSE 的 `event:` 行，所以那套"攒 data 行、空行时处理"的逻辑原样成立。

判定 = **用户显式指定 > 按官方域名认**。`detect()` 只认 `api.anthropic.com` /
`generativelanguage.googleapis.com`，且带 `/openai` 段的一律判回兼容层（用户特意填了兼容路径就是要走兼容层）。
⛔ **别往 detect 里加中转站关键字**：认错的代价是整个请求发错格式。中转站让用户在配置页显式选。

⭐ 顺手修掉一个时序雷：`ApiExtrasStore` 从前**只有配置页 bind** 它，于是"冷启动后不进配置页就直接聊天"
那一次读不到任何 override。对请求体模板只是"这次没生效"，对**协议选择却是发错格式**。已在 `XtomApp.onCreate` bind。

各协议的形状差异逐条写在两个文件的类注释里（Anthropic：system 是顶层、没有 tool 角色、`max_tokens` 必填、
temperature 值域 0~1、思考块必须连签名原样回传；Gemini：角色只有 user/model、工具结果**靠函数名认领不靠 id**、
模型名在 URL 里、schema 方言更窄要过白名单）。**改之前先读那两段注释。**

### 三、竞品四条（子 agent 做的，我接线）

| 条目 | 落点 | 真机要盯什么 |
|---|---|---|
| 酒馆卡多开场白 / 越狱指令 / 深度提示 | `CardRoleplayStore` / `CardPng` / `CharacterCardPage`；`ChatScreen.injectCardExtras` | 越狱指令必须在**整段历史之后**、深度提示是**独立一条消息** |
| 上下文窗口感知 + 条数上限 | `logic/ContextWindowDefaults` / `ContextWindow.kt` / `ContextCompressor` / `DialogSettingsPage` | 条数上限截断后仍要过 `sanitizePairing`（否则 400） |
| 会话锁定 | `ConversationEntity`+**Room 21→22** / `ConversationManager.delete` 返回 Boolean / 列表页锁标记 | 🔴 迁移；锁定的会话四条删除路径都该拦住 |
| 消息转发到别的会话 | `ForwardMessageDialog.kt` / `ConversationManager.forwardMessage` / 气泡菜单「转发到…」 | 转发过去的消息不带 toolCalls（否则目标会话残留悬空配对） |

⭐ **`openingStatement` 是个死字段**：全项目从来没有任何地方拿它开过场，只能编辑/导入导出/被搜索。
所以「角色卡先说开场白」是**真的新行为**，给了开关且**默认关**（`ChatBehaviorPrefs.cardGreeting`）。

⭐ **子 agent 点出、我修掉的两处"谎报成功"**：`manage_chats` 工具删锁定会话会回「已删除对话」（对模型撒谎）；
抽屉长按删除锁定会话静默无反应。两处都改成按 `delete()` 的返回值说实话。

### 四、这一轮的坑
- `Icons.AutoMirrored.Outlined.Forward` 报 Unresolved **不是图标不存在**，是那个文件缺 import（项目按文件逐个 import 图标）。
- 加枚举值先查三样：`ordinal` 有没有被持久化、`fromString` 有没有调用点、有没有穷尽 `when`。这次三样都干净所以能安全插在中间。
- 委托状态 `var`（`by remember { mutableStateOf(...) }`）**不吃智能转换**，判空后仍要 `?: return`。
- 弹窗放的位置决定 `scheme`/`accents` 能不能解析——要放在弹窗区那一层，不是随手放在 effect 旁边。

### 五、这一轮**没做**的
- i18n：新加的串没跑 wrap/embed，**397 条旧的未翻仍未翻**。另外 `ChatProtocol.displayName`（"OpenAI 兼容"等）
  在 cloudapi 模块里、没包 tr()，非中文界面不会翻。
- 上下文窗口的**进度显示**没接进聊天页 UI（功能部分已生效：压缩触发器已按 token 比例判、条数上限已在 `forSend` 里）。
- 角色卡三条的**导出回环**没接（`ImportExport.exportCardRoleplay` 那三行）。
- 两家原生协议**一次真实请求都没发过**——没有 key、也不该拿你的 key 去试。这是本轮最大的未验风险。

---

## 2026-07-28 会话·清存疑清单里「不用你拍板」的那批

> `:app:assembleDebug` 通过、**单测 94 个仍全绿**。真机零验证。做的都是 `OPEN-QUESTIONS.md` 里
> 状态为「已知缺口/已知冗余/已知垃圾/已知脆弱点」——**不需要你做产品判断**的那些；
> 标「待你定/待你审」的六条一个没动，等你发话。

| # | 做了什么 | 落点 |
|---|---|---|
| **Q36** | **堵掉「第三方包顶掉内置工具名」**（Q33 只堵了 MCP 一条，市场包/技能包/JS 插件三条还开着）。`ToolManager` 拆成两个入口：`registerBuiltin`（内置，名字进保留表）与 `register`（第三方，撞上保留名不覆盖、改注册成 `ext_<name>` 并**返回实际注册名**）。为什么要紧：权限键就是工具名，顶名 = 继承用户早先给那个名字设的放行策略 | `ToolManager`/`PackageManager`/`XtomApp`/`OperitCompat`/`PluginManager`/`SkillManager` |
| **Q35** | **MCP 客户端身份可伪造**：clientId 原是「远端 IP:端口」，而源端口是客户端自己挑的 → 本机任一 App 绑同一端口就继承那条「始终允许」。改成：设了访问令牌 → clientId = **令牌指纹**（身份可验证、跨连接稳定）；没设令牌 → `verified=false`，`ToolCaller.canRememberAlways` 判假，**弹窗不显示两个「始终」按钮**、`resolve` 也不落盘 | `Tool.kt`/`McpServer`/`ToolPermission`/`ToolPermissionDialog` |
| **Q41** | 经 OpenRouter 的推理模型**侧栏思考恒为空**：正文走 `delta.reasoning`，我们只读 `reasoning_content`。补读，且只认字符串形态——发成对象/数组的是回传凭证，仍归 `ReasoningPassthrough`，别当文本拼进去 | `cloudapi/CloudApiClient.parseDelta` |
| **Q23** | 硬信号判「没日程」原本靠 `content.startsWith("未来")`，工具文案一改就静默失效。抽出 `CalendarTool.upcoming(days)`：**null=没权限、空表=真没日程**，读失败照抛不吞 | `CalendarTool`/`HardSignalDigest` |
| **Q18** | 删掉 `MemoryManager.relevantPack()`：它和 `MemoryInjection.build` 是同一件事的两份实现（检索同走 `rankRelevant`），只有排版各写一版；留的那份是活的且预算规则更全 | `MemoryManager` |
| **Q45** | `headTail` 的 `cap` 改成**真硬上界**（中间那行省略提示也算进预算，原来 cap=1200 实际产出 ~1232） | `ContextCompressor` |
| **Q49** | 删掉 `:tools` / `:mcp` / `:persona` 三个**一个 .kt 都没有**的空模块壳 + `settings.gradle.kts` 的 include。⚠ `tools/` 目录留着——里面是 token_cost.py / i18n_*.py / competitor_watch.py，删的只是它的 gradle 模块身份 | `settings.gradle.kts` |

**这批里唯一能一眼看出效果的是 Q41**（OpenRouter 推理模型聊一句，看侧栏思考有没有字）。其余是安全/维护债。

---

## 2026-07-27 会话·P0/P3/P4 收尾

> `:app:assembleDebug` + **`:app:assembleRelease`（首次开 R8）** 均通过；**单测 94 个用例全绿**（`:logic:test` + `:app:testDebugUnitTest`）。
> 真机仍是零验证、未提交。

| # | 做了什么 | 结果 / 落点 |
|---|---|---|
| **P0-1** | **主 App 首次打出 release（开 R8）**。此前 `isMinifyEnabled=false`、`app/build/outputs/apk/release/` 根本不存在——这是颗没拆的雷。新写 `app/proguard-rules.pro`，逐条核过"谁是按名字被找到的"：JNI(sherpa-onnx)/AIDL/WorkManager/PDFBox/Shizuku 才 keep；**反射打的全是系统框架类**（Notification$ProgressStyle 等），R8 不动框架，不用 keep；序列化全是手写 org.json，实体不用 keep | **53.6MB**（debug 82.9MB，**-35%**）。⚠ 构建通过 ≠ 运行通过，反射炸点只有真机才看得出 |
| **P0-4** | 自查「MCP 是否每轮重连」。**结论：stdio 那条注释属实、连接确实复用**（`alive()` 为真直接返回、握手只在起进程那次、`startMutex` 保证只起一份、client 由 registry 长期持有、聊天每轮不刷新）。但同一条路上查出 5 个真问题并修了 | 写失败/超时不再折成同一种（只有"消息压根没写出去"才重连重发）、失败退避 5s→翻倍→封顶 2 分钟、起新进程前先收旧的、JSON-RPC 的 `error` 响应不再被当成正常结果、`clientFor` 认命令变更。HTTP 那条去掉了 `disconnect()`（安卓的 HttpURLConnection 背后是 OkHttp 连接池，disconnect 等于明着废掉复用） |
| **P0-5** | 自查「审批系统有无按文本内容旁路」。**结论：没有 Operit 那种 `rawText.contains("deny_tool")` 式旁路**——`checkGate` 只吃策略结果、不碰参数文本。但查出 4 条真实的伪装/越权路径 | 见下方「修掉的安全问题」 |
| **P3-1** | **第三方 MCP 工具网关**（`search_tool` + `use_tool`）。自家工具保持直连一个不收；只有第三方 MCP 走目录检索。第三方工具少于 8 个时不启用（只有两三个还搞目录纯添乱） | 新 `tool/McpGateway.kt` + `ToolManager`/`XtomApp` 接线 |
| **P3-2** | 能力等级**三处同时生效**：权限页顶部的 L0–L4 卡（含"再往上要做什么"）、系统提示里那句（**并进已有的"还开不了的能力"那行，不新起一行占 token**）、工具失败时的话术。少一处就会退化成"时灵时不灵" | 新 `app/CapabilityTier.kt`、`PermissionsPage`、`ToolManager` |
| **P3-4** | **分层试点**：新建 `:logic` 纯 Kotlin/JVM 模块（**不是** Android library），搬了两个零依赖的纯逻辑进去（`TextBudget`/`FuzzyMatch`），包名不变、调用方一行没改。准入门槛只有一条：不 import 任何 `android.*`/`androidx.*` | `./gradlew :logic:test` 秒级跑完、不需要设备；将来鸿蒙端这部分能原样复用 |
| **P3-5** | **给踩过的坑补单测**，不追覆盖率：`sanitizePairing`/`trimOldToolResults`/`evictOldImages`/`MemEdges`/`ToolArgValidator`/`FuzzyMatch`/`TextBudget`/`softWrap` | 7 个文件 **94 个用例全绿**。JVM 的 org.json 用 AOSP 那份(Apache-2.0)、**不是** org.json 官方包（后者 "Good, not Evil" 条款与 AGPL-3.0 不兼容），且只在测试期 |
| **P4-4** | 思考链签名透传**做全**：从只有 tool_call 级的 Gemini 一路，扩到**消息级**的原始载体槽（`reasoning_details`/`thinking_blocks`/`extra_content`），收到什么原样存、回传原样写回。带**端点指纹**（换了主机就不发，防"把 A 家的私有字段发给 B 家"这个 400 老坑）与 128KB 上限（防把会话行撑到打不开 App） | 新 `cloudapi/ReasoningPassthrough.kt` + `ChatMessage.extra`；app 侧三处持久化（会话/分支树/发送）已接线 |

### 修掉的安全问题（P0-5 查出来的）
1. **调用方洗白（最实在的一条提权链）**：`create_agent`/`workflow`/`local_search` 内部再调工具时**不传 caller**，一律吃默认值 `ToolCaller.Ai`。插件或外部 MCP 只要让外层工具过一次闸（点一次"始终允许"就永久），其派生的任意调用就全以 AI 身份执行，`plugin:<id>:` 那套权限命名空间形同虚设。→ 新增 `CallerContext` 协程上下文，身份**跟着协程走**；子 agent 因为要切到后台 scope，额外把 caller 显式透传下去。
2. **网关变越权通道**：`use_tool` 内部拿不到调用方，会以 AI 身份去调第三方工具。→ `ToolManager` 直接按调用方挡住非 AI 的 `use_tool`。
3. **MCP server 报的工具名能顶掉内置工具**：HTTP 那条把 server 报的名字**原样**当注册名，一个第三方端点把自己叫 `shell` 就能覆盖内置工具、并继承用户早先给那个名字设的放行策略。→ 强制 `mcp_` 前缀（与 stdio 那条对齐）。⚠ **这会让现存 HTTP MCP 工具改名，用户对旧名设过的策略回落默认。**
4. **模型自动审批的 3 分钟缓存不分调用方**：AI 刚被自动批过的同名同参调用，插件/MCP 3 分钟内可白蹭。→ 自动审批只对 `Ai`/`UserScript` 生效。⚠ **行为变化：开了自动审批后插件/MCP 会多弹框。**
5. 顺带：`StdioMcpRegistry.closeAll()` **全项目没有调用点**（退出 App 不收 MCP 子进程，它们跑在终端 App 那侧能活过我们整个进程）→ 挂到 `MainActivity.onDestroy` 且只在 `isFinishing` 时收。

### 单测挖出来的真 bug（已修）
`ContextCompressor.sanitizePairing` 的早退条件只看"有没有 assistant 带 tool_calls"。于是**最常见的残缺形态反而漏网**：用户把带 tool_calls 的那条 assistant 删了、只剩下面的 tool 回复——没有任何 assistant 带 tool_calls → 直接原样返回 → 那些没人认领的 tool 消息照样发出去、照样 400。已修并补了回归用例。

---

## 2026-07-27 会话·P2 理解层做完

> `:app:assembleDebug` **BUILD SUCCESSFUL**、`:data` 的 Room 迁移随之验过。**真机零验证、未提交。**
> 对应 `TODO-NEXT-0727.md` 的 P2 全 14 条 + P1 剩下的三个已知缺口。i18n 管线已跑（新串英文已补，其余 32 语仍空）。

### P2-A 便宜的六条
| # | 做了什么 | 落点 |
|---|---|---|
| P2-1 | 抽取窗口从「只看最后一问一答」加宽到「**最近 N 轮**、边界对齐 user」。这函数每 N 轮才跑一次，原来中间 N-1 轮结构上永远抽不到，而跨轮才显出来的模式恰恰最值得记 | `ChatScreen.autoExtractMemories` |
| P2-2 | 记忆类型加**约定层** `lesson`/`environment`/`convention`。原五类全是"关于人"的，没有一格装"关于怎么干活"的知识 | `MemoryTool` 枚举与描述、`MemoryPage` 显示名/图标/图谱配色、`MemorySalvage.llmMerge` |
| P2-3 | 四处元任务提示词加**注入防护**（抽取/摘要/建议/状态卡/标题）：对话里混着网页与工具带回的文本，元任务没人盯着，被劫持会把污染写进记忆与跨对话状态 | `ChatScreen`×3、`ContextCompressor.SUMMARY_SYS`、`InteractionState` |
| P2-4 | 写入前**语义去重**（余弦 ≥0.92）：换个说法就存成两条的问题。⚠ 只对新产生的记忆去重，**导入/备份恢复/救援一律跳过**——那些是在还原既有事实，合并=数据丢失 | `MemoryManager.upsertByTitle`/`findSemanticDuplicate` |
| P2-5 | 压缩护栏：摘要不比原文短 20% 就**不落盘**、摘要自身封顶 2000 字（它会被反复喂回合并，不封顶会自我膨胀） | `ContextCompressor` |
| P2-6 | 记忆满了把话**说给模型**：当前条数/上限/自动压缩已跑过 + 它本轮能做的三件事，而不是"请用户手动整理"（手表上没人看屏幕） | `MemoryTool.addMemory` |

### P2-B 核心（记忆第一次真正起作用）
| # | 做了什么 | 落点 |
|---|---|---|
| **P2-7** | **常驻记忆块进 volatileTail**。在这之前 `performSend` 里**一条记忆都不注入**——存了一堆，模型根本不知道自己有东西可查，这极可能就是「存了没用」的真因。按字符预算(1200)、置顶最多占 40%、放 volatileTail 不破坏前缀缓存、**不带容量表头** | 新 `app/MemoryInjection.kt` + `ChatScreen` |
| **P2-8** | 检索融合从「三段拼接」改成**打分**：`max(kw, 0.3*kw+0.7*vec)`（保护字面命中）+ 同卡 ×1.25 + confidence 权重 + 置顶加成。顺带修**记忆归属错**：`upsertByTitle` 原来只按标题查，不同角色卡的同名记忆会互相覆盖 | `MemoryManager.rankRelevant`、`MemoryDao.getByTitleInCard` |
| **P2-9** | **失败教训自动入记忆**：工具被拒/参数不合法/包没开/回复被截断 → 确定性写成一条 `lesson`（不调模型），同标题即同一条，重复发生只抬重要度。这是全套改造里**唯一有斜率**的一条 | 新 `app/LessonRecorder.kt`、`ToolResult.failKind`、`ToolManager`、`ChatScreen` |
| P2-10 | `MEMORY_MAX_COUNT` 语义改掉：**100 → 1000**，注释写明它是防失控的兜底不是预算，真正的预算在注入侧。手动"整理记忆"的目标也跟着走（原来写死剪到 200，会把刚放开的归档层又砍回去） | `MemoryTool`、`MemorySalvage` |
| P2-11 | 压缩前**抢救进记忆** + 原文**落盘可检索**：被压缩的那段抽一次长期记忆，原文存到工作区 `archive/`，摘要末尾附一行怎么检索（指向 `read_document`，不是默认关的 `file_read`） | `ContextCompressor.salvageToMemory`/`archiveRaw` |

### P2-C 差异化
| # | 做了什么 | 落点 |
|---|---|---|
| P2-12 | **硬信号沉淀**：位置/健康/日程/App 使用/通知来源/设备时区 → 六条固定标题的 `environment` 记忆，20 小时限频、逐项独立容错。硬信号确定、便宜、不幻觉，是骨架；桌面 agent 永远拿不到抬腕与心率 | 新 `app/HardSignalDigest.kt` + `ChatScreen` 调用点（cardId 传 null=关于用户本人，不跟着角色卡走） |
| P2-13 | `MemoryEntity` 加 `assertedAt` + `confidence`，Room **迁移 19→20** | `data/` 三个文件 |
| P2-14 | `MemEdges` 从一跳展开改成**两跳 BFS**（权重衰减 0.6、上限 8），并新增 `relevantPack()` 返回可直接注入的上下文包 | `MemoryManager` |

### 顺带补掉的 P1 遗留
- **Q5**：工具结果落盘的路径提示现在**先探 `read_document` 可不可达**（注册/包启用/能力前提/角色卡范围/权限策略五条一起判），不可达就不给死路、改成头尾预算翻倍多内联。
- **Q11**：角色卡导出补上 `worldBook` / 工具范围 / 对话示例 / 显示替换规则，导入侧对应还原且兼容老文件。
- **Q12**：i18n 管线已跑，12 条新串英文补齐（`missing en: 0`）。
- 顺带发现并修了一个真 bug：**按角色卡导出记忆一直是零条**——它走的是 `queryRelevant("")`，而空查询直接返回空列表，还不报错，看着像"这张卡本来就没记忆"。

---

## 2026-07-27 会话·P1 工具调用层

> `:app:assembleDebug` **BUILD SUCCESSFUL**（APK 已刷新）。**真机一条没验**，`tools/token_cost.py` 已重跑。
> 对应 `TODO-NEXT-0727.md` 的 P1 全 10 条。**未提交**。

| # | 做了什么 | 落点 |
|---|---|---|
| P1-1 | 按**运行前提**裁工具 schema（不是按 permissionLevel——那是风险分级，拿它当能力判据会误杀 file_op/browser 这些纯沙盒工具）。新增 `ToolRequirement` + `CapabilityProbe`（30s TTL，探测异常一律 fail-open），裁掉的能力压成一行 `missingCapabilitiesNote()` 进系统提示 | 新 `tool/ToolRequirement.kt`；`Tool.requires`；`ToolManager`；`XtomApp`(init)、`PermissionsPage`(ON_RESUME 作废缓存) |
| P1-2 | **工具挂角色卡**：per-card 排除表（存排除不存允许——以后新增功能包老卡自动带上）。编辑器里给了「不限制/干活形态/陪伴形态」三个预设 + 全包勾选明细；范围外的包压成一行 `cardScopeNote()` 告诉模型「这张卡不带」 | 新 `app/CardToolStore.kt`；`CharacterCardPage`；`ToolManager.getToolsJson(excludePkgs)`；`ChatScreen` 每轮现读 |
| P1-3 | 熔断从「整批签名 3 次」扩成**三层**：①同调用(名+参数) ②同工具名 ③最近 4 步的**序列**（治 A↔B 乒乓，openclaw #64500 证明按单工具计会被绕过）。前段阈值只往工具结果**开头**插一句提醒，后段才真掐断 | `ChatScreen.runWithToolLoop` |
| P1-4 | **拒绝短路**：同一批里用户当场拒了一个，后面的一律不执行、回「因前面被拒而取消」。（审批本来就是串行的——suspend 的 `map` 不并发，弹框不会同时冒 4 个） | `ChatScreen`、`WakeAssistantActivity` |
| P1-5 | 旧工具结果改**头尾各留**（答案常在尾部）+ **触发门槛**：整段对话不到 16000 字符时一个字都不裁（原来无条件裁、第 4 条就砍到 500 字） | `ContextCompressor.trimOldToolResults` |
| P1-6 | 超长工具结果**落盘工作区** `ai_workspace/tool_outputs/`，内联只留头 1500 + 尾 1200 + 全文路径。⚠ 路径指向的是 **`read_document`（默认开）不是 `file_read`（默认关）**——第一版写成 file_read 是给了条打不开的路，已修。装了终端 App 才额外提 grep。只留最近 40 个文件 | 新 `tool/ToolOutputStore.kt`；聊天/唤醒/子agent 三条链都换掉了原来的 `take(3000)` |
| P1-7 | 两种截断分清：超长**单行**按 2000 字符软换行（**不丢内容**，只给模型可锚定的结构），体积超限才走头尾丢弃 | `ToolOutputStore.softWrap` |
| P1-8 | 入站附件预检。图片：超 1.2MB 先降采样重压，压不动就不带图、只留工作区路径。**文本：不再 `take(8000)` 一刀切**，改成先估 token 再决定读法——小(≤2500tok)整份给／中(≤9000)给开头+说清总量／大只给结构卡片(行数·估算token·标题大纲或表头)+三条取用路子。配套给 `read_document` 加了 `offset/limit/pattern`（流式，不读进内存），因为 `file_read` 在默认关闭的包里，不能指望它 | 新 `tool/TextBudget.kt`；`ChatScreen.textAttachmentBlock`/`fitImageForContext`；`DocReadTool.readTextSmart/grepLines/readLineRange` |
| P1-9 | **原清单大部分是误判**：`file_*×8`/`make_directory` 是 `FileOpTool` 的内部委派、`deep_search`(XSearchTool) 是 `web_search depth=deep` 的实现，**全是活代码**，只是按「工具少而多用」没单独注册。真死的只有 3 个：`fetch`/`key_hook`/`wake`，已删（`TimeTool`/`CalculatorTool` 原本和 FetchTool 同住一个文件，搬进新 `BasicTools.kt`）。`token_cost.py` 的「维护债」措辞已改，免得下一轮照着名单删活代码 | 删 `FetchTool.kt`/`WakeTool.kt`+`KeyHookTool`；新 `BasicTools.kt` |
| P1-10 | **图片驱逐**：老图只留占位符、抽掉 base64（历史图片原来每轮整包重发）。占位符明写「**不要凭印象描述这些图**」——悄悄抽走会诱发它一本正经地"描述"看不见的图 | `ContextCompressor.evictOldImages` |

**token 现状**（`python tools/token_cost.py`）：Tool 实现 82 个 ≈ 9,144 tok；默认启用 38 个 ≈ **4,563 tok/轮**（与 7-27 基线持平——
删掉的 3 个本来就没注册、不占 token；P1-1/P1-2 的省法是**运行时**的，静态脚本量不到）。

**真机要验的**（一条没验）：① 角色卡编辑器新增的「工具范围」一节在小圆屏上点得到吗、存完下一轮真的少了工具吗
② 没装终端 App 的机器上 `linux_exec` 是否从工具表消失、系统提示里那行「当前设备还开不了的能力」话术对不对
③ 超长结果落盘后模型会不会真去 `file_read`（file_tools 包默认关，它得先申请）④ 拒绝一个工具后剩余调用是否显示为「已取消」
⑤ 老图驱逐后追问旧图，模型是否老实说「看不到了」而不是编
⑥ 丢一个大 log/大 md 进去：是否只给了结构卡片、模型是否真会用 `read_document(pattern=…)` 去定位而不是空手作答。

### 存疑清单已独立成文件

> P1/P2 两轮里所有「拍脑袋定的数」「没验证的判断」「明知有缺口没做的事」全部集中在 **`OPEN-QUESTIONS.md`**（Q1–Q29，含状态与「怎么才算判定了」）。
> 以后新增存疑一律往那份文件里追加，别再散落在各处 TODO 里。

---

## 🆕 2026-07-26 会话·新手向导

> `:app:assembleDebug` **BUILD SUCCESSFUL**。**用户 2026-07-26 真机走过一遍、当场没提问题**，已提交。
> （下面那份「真机必验」清单保留作回归项——用户是整体过了一遍，不是逐条对着打勾。）

新增**首启新手向导**：8 步全屏分页（欢迎 → 连模型 → 权限 → 唤醒 → 认识你 → 选角色 → 导览 → 完成）。
定位是**真落配置**不是纯教学——每步写进各自既有的 Prefs/Room，走完就能直接聊天。

- **新文件**：`OnboardingPrefs.kt`（`done_version` 版本号标记 + 进程级闸门 `OnboardingGate`）、
  `OnboardingPage.kt`（向导本体，约 1100 行）。**没有新建任何存储**，全部复用现成入口：
  连模型→`CloudApiConfigManager`(Room `api_configs`, purpose="chat")、权限→和 `PermissionsPage` 同一套
  Intent/runtime permission、唤醒→`WakeService` prefs、认识你→`IdentityPrefs`+`UserPreferences`、
  选角色→`CharacterCardManager`(Room `character_cards`)。
- **接线**：`MainActivity.onCreate` 加 `OnboardingGate.init(act)`；`setContent` 里**二选一渲染**
  （向导 / MainScreen，不叠加——叠着背后整棵聊天树照样组合+绘制，白烧帧预算）。
  `navTo("onboarding")` 拦截成拉起闸门（不是页面路由），所以抽屉和设置中心共用一个 id。
  `DrawerLayoutStore.DEFAULTS` 补 `"onboarding" to ZONE_HIDDEN`（**不补的话 `load()` 会当未知 id 丢掉**）。
- **连模型降门槛**：置顶「一键使用免费模型」（`ApiProviders` 里 `FreeKind.NO_KEY` 那条，无需注册/Key），
  点一下即写配置 + 发一条 `streamChat("hi")` 验连通。**测通才放行下一步**；测不通给「不测了，直接保存」兜底。
  重跑向导会**改同一条配置**（`existingId`）而不是再塞一行。
- **权限分三档**：必需(录音/通知/电池豁免) · 推荐(悬浮窗/所有文件/相册/位置/相机) · 高级(无障碍/通知感知/
  使用情况/通讯录/日历/改系统设置，默认折叠)。授权状态**批量算进一个 Map**、`ON_RESUME` 才重算。
- **i18n 已同步**：新串全走 `tr()`，`i18n_wrap.py` 抽出 1122→1261 条，**英文 140 条已补齐**（`missing en: 0`），
  `i18n_embed.py` 已重生成 `I18nStrings.kt`。**其余 32 种语言仍空**，等下一轮 opencode 按 `I18N-HANDOFF.md` 补。

**性能红线（照 DESIGN-CHAT-PERF.md，已遵守）**：供应商 40+ 项走全屏 Dialog + `LazyColumn(key/contentType)`，
不做嵌套纵向滚动；派生集合全 `remember`；不套 `animateContentSize`；`beyondViewportPageCount = 0`。

### 动效（第二轮补的）——**新增通用件 `ui/FrameMotion.kt`，以后别再各页各抄一份**

手表上「系统动画缩放 = 0」极常见（省电 / A11 默认），标准 `tween`/`animate*AsState` 此时直接跳终值 = 一动不动
（唤醒流光、转圈、聊天页幽灵气泡都栽过）。所以向导的动效**全部帧驱动**：

- `ui/FrameMotion.kt` 提供：`rememberFrameProgress`（key 一变就跑一遍 0→1，入场用）、
  `rememberFrameFloat`（像 `animateFloatAsState` 但自推帧，中途改目标不跳）、`rememberBreath`（呼吸循环，
  **默认只呼吸 3 次就停** —— 手表上常驻动画=一直申请帧回调=耗电）、`OvershootEasing`（back-out 回弹）、
  `Modifier.staggerIn`（错峰淡入上浮）、`Modifier.revealVertically`（帧驱动的展开，替代 animateContentSize）、
  `FullMotion`（`MotionDurationScale` 覆写，给程序触发的动画用）。
- **返回 `State<Float>` 不是 `Float`**：调用方在 `graphicsLayer{}` / `layout{}` 的 lambda 里读它 →
  绘制/布局阶段的读取，只让图层失效，**不会每帧重组**。这是全套动效不卡的关键，改的时候别顺手 `by` 解构掉。
- 按钮翻页 `scope.launch(FullMotion) { pager.animateScrollToPage(…) }` —— 不加的话缩放=0 时直接瞬移，
  下面那套视差全白搭。**手指拖动不受影响**（跟手，不走动画系统）。
- 落到界面上的：分页视差+景深（在 `graphicsLayer` lambda 里读 `currentPageOffsetFraction`）/ 进度条改成
  单个 Canvas 里跟手连续滑的胶囊（原来 8 个点各挂一个 `animateDpAsState`，既是 8 份动画、改宽度还让整行重测量）/
  各步卡片错峰入场 / 步骤名换行淡入 / 欢迎徽标弹出+光晕呼吸 / 完成页勾冲出来+扩散一圈 /
  权限「已开」弹一下（跳去系统设置回来那一下必须看得见）/ 高级段落展开 + 箭头转 90° /
  角色卡与语气选项的选中缩放 / 「下一步」由灰变亮。

**已知未解**：手指甩动后的**吸附回弹**仍走 Pager 内部的滚动协程，`FullMotion` 覆不到它 ——
动画缩放=0 的设备上松手会直接吸附到位（拖动过程本身是跟手的，所以影响很小）。真要治得自定义 `flingBehavior`。

**两个刻意的设计决定（别当 bug 改掉）**：
1. 拦人用 `pager.settledPage` 不用 `currentPage` —— `currentPage` 在手指拖过一半时就翻，那一刻关
   `userScrollEnabled` 会掐断正在收尾的吸附动画、停在两页中间。
2. 分页里的表单状态一律 `rememberSaveable` —— `beyondViewportPageCount=0` 意味着翻走的页会被销毁，
   普通 `remember` 会把用户刚敲的密钥清空（Pager 给每页套了 SaveableStateProvider，saveable 才跨得过去）。
   连模型那步的 DB 预填有 `loaded` 挡重入，否则翻页回来会覆盖未保存的输入。
3. 喂给模型的文本**不翻译**：角色卡 `characterSetting`/`tone`/`length`、语气选项存进 `UserPreferences` 的值
   全是中文原串（`ChatScreen` 直接拼进系统提示词）；只有卡名/说明/界面文案走 `tr()`。
   角色卡去重也因此**按 `characterSetting` 比对而不是卡名**（卡名会跟着语言变）。

**真机必验**（全没验）：
1. 首启是否直接进向导；走完/跳过后不再出现；设置中心 → 新手向导 能重新拉起。
2. **圆屏手表**：左右滑翻页会不会和系统返回手势打架（抽屉当初就是为这个禁掉了右滑）——若打架就把
   `userScrollEnabled` 常关、只留按钮翻页。另看进度条胶囊在窄屏挤不挤。
2b. **动效必须在「开发者选项里把动画缩放调成 0」的状态下再验一遍** —— 这正是帧驱动那套要解决的场景，
   缩放=1 时看不出区别。该动的：翻页视差、进度胶囊滑动、卡片错峰入场、徽标弹出、权限「已开」弹一下、
   高级段展开。**已知**松手后的吸附回弹在缩放=0 时仍是瞬间到位（见上「已知未解」）。
3. 「一键使用免费模型」能否真的测通并落配置；之后聊天页直接能发消息。
4. 权限步：去系统设置授权后返回，状态是否变「已开」（ON_RESUME 刷新）。
5. 唤醒步打开「结束后去录唤醒词」→ 走完向导应直接落在语音唤醒页。
6. 选角色后进聊天，角色卡是否真的生效（默认卡切过去了）。

---

## 2026-07-26 会话·6 个 bug/功能

> `:app:assembleDebug` **BUILD SUCCESSFUL**，APK 已刷新。**真机一条都没验。** 未提交。
> 工作树里还压着 2026-07-25 的终端那 25 个文件（另一个提交单元，别混在一起提交）。

- [x] **① 前台不弹通知**：新建 `NotificationPrefs`（prefs `xtom_notification`）。`suppressInForeground` 默认 **开**、
      `suppressToolInForeground`（AI 工具主动发的通知）默认 **关**；统一闸门 `suppressed(ctx)=AppForeground.isForeground && …`。
      6 处打扰型通知在**函数入口**早退（ProactiveMessage/Reminders/Diary/ScheduleTool/DeepSearchAsync/SubAgentTool）——
      业务副作用（标记已触发/入库/追加进对话）全在调用方，没跟着跳过。`NotificationTool.send` 被拦时**如实告诉 AI 没弹出**，不谎报已发送。
      **前台服务常驻通知、唤醒全屏兜底、灵动岛一律没动**（拦前台服务会抛 ForegroundServiceDidNotStartInTimeException 崩进程）。
      开关在陪伴设置页新增的「通知打扰」段。
- [x] **② 工具拒绝回传 + 拒绝后停本轮**：`check(): Boolean` 之上加真闸门 `checkGate(): ToolGate`（`DenyReason` 四态：
      用户拒绝/策略禁止/60s 无人应答/问不到用户），`ToolManager` 按原因给**四套不同的话**回模型（**未 tr()**，见下方提示词审计）。
      `ToolResult` 加 `userDenied`。新设置项**「工具被拒绝后停止本轮」默认关**（`ConfigModePrefs`，对话设置页「执行方式」段）；
      开启后 tool message **照常全部入列再 break**（不破坏 tool_call_id 配对，否则 400），并跳过空回复兜底重问。语音路径同步。
- [x] **③ 模型自动审批**：新建 `ToolApprovalJudge`（骨架照 `CommandExplainer`，fail-safe 照 `UiDangerGuard`）。
      **默认关**。钩子只在 `ASK` 分支 + `permissionLevel==STANDARD`；**另有硬黑名单 `NEVER_AUTO`**（执行命令/改删文件/
      对外通信/读私密/装扩展/花钱六类，模型说 SAFE 也不放行）。判定失败一律回退问用户（绝不 fail-open）；
      参数走 `UntrustedWeb.fence` 围栏 + 「本机解读」那行也在围栏内（它拼了参数原文）；解析从严（首行不是 SAFE 即 DANGER）；
      3 分钟降噪缓存；自动放行走 `ToolActivityBus` 留痕。模型用途新增 `approval`「权限审批」卡。开关在权限页。
- [x] **④ 唤醒助手浮层（截图那个"两层 UI 叠一起"）**：a) 键盘不顶起 → `WakeOverlayHost.setImeMode()` 在输入框获焦时
      **动态摘 `FLAG_LAYOUT_NO_LIMITS`**（收起再加回，保住"无黑边"只在打字那几秒让位）；Activity 补
      `windowSoftInputMode="adjustResize"`，`XtomVoiceSession` 补 `SOFT_INPUT_ADJUST_RESIZE`。
      b) 背后聊天页透出 → **只在盖住自家 App 时**叠 `surface` 0.94 遮罩 + 触摸拦截，盖桌面仍是全透明磨砂。
      ⚠ Activity 路径**不能用 `AppForeground.isForeground`**（它自己就会把这值顶成 true），改用 started activity 计数 >1。
- [x] **⑤ Gemini 3 工具调用 400（`missing thought_signature`）**：根因是流式解析**白名单点名取字段**，
      把 tool_call 上的 `extra_content`（Gemini 放思考签名的地方）丢了 → 回传时无从写回。
      全链路加泛化透传槽 `extra`：解析 → 累积（**两份复制粘贴的副本都改了**）→ 请求回写 → 存盘 → 分支树 → 导入导出。
      另补：只带 `extra_content` 不带 `function` 的收尾块不再整块跳过，空壳条目在流末尾过滤（不会造出幽灵工具调用）。
- [x] **⑥ 屏幕圆角适配只有对话页吃得到**：真因不是"没注入"——`insetH/insetV` 本来就加在路由的内容 Box 上，
      但**顶栏（`TopGlassChrome`/`LargeTopAppBar`）是它的兄弟节点、吃不到**，且 `floatInset` 全项目只有 3 个消费点、
      **只加横向不加纵向**。改：顶栏各自 `screenFitPadding`，顶部胶囊/大标题补纵向 `floatFit`，栏高与
      `topChromeGap`/`topChromeGapHeight` 同源 `+floatInset`（否则内容被顶栏压住）。
      新增 `Modifier.screenFitPadding()/floatFitPadding()` 收编手拼（防下次再漏）。
      「推荐值」按钮改成按本机实算（圆屏短边 5.5%/9%；方屏读真实圆角半径 ×0.3/×0.45，读不到回落常量）——
      **只影响按钮给的建议值，默认值仍是 0**，存量用户界面不会突然内缩。

**真机必验**（全部没验）：
1. 前台开着 App 时到点的提醒/主动消息/日记/深度研究/子 agent 完成 —— 不应弹通知；切后台后照常弹。
2. 拒绝一次工具 → 看 AI 是否明白"是用户拒绝的"而不是重试；打开「拒绝后停止本轮」再拒一次 → 应立刻停。
3. 开模型自动审批 + 配个便宜小模型 → 只读类工具应静默放行并在活动中心留痕；shell/发短信/删文件必须照旧弹窗。
4. 唤醒浮层：点「打字…」胶囊应随键盘上移（摘 flag 期间可能短暂出现上下黑边，**收起后必须恢复**）；
   在聊天页触发主动"来电" → 背后聊天页不应可见；从桌面唤起 → 背景仍是磨砂桌面、**不能有灰底**。
5. Gemini 3 系列多轮工具调用（连续两次 shell）不再 400。
6. **圆屏手表**：各子页返回箭头/大标题不再被表圈切；拖「悬浮界面」滑杆各页都应跟着动；顶栏与内容之间不留白空一截。

**残留**：7 个独立 setContent 浮层宿主（FloatingScreenOcrButton/FloatingTtsPlayer/XtomVoiceSession/PermissionOverlayHost/
UiActionOverlay/WakeOverlayHost/MessageImageShare）**零圆角适配**，本轮没动；`file_op`/`notification`/`http_request` 等
STANDARD 级工具的权限等级是否合理，值得单独审一遍（现已被 `NEVER_AUTO` 兜住）。

---

## 🆕 2026-07-25 会话·终端大改（细节在 `TODO-TERMINAL-APP.md`「2026-07-25 大改」节）

> 工作树里目前**只有这一轮的 25 个文件**（6 新增 + 18 修改 + 1 新 drawable），是个干净的提交单元，**尚未提交**。
> 产物 `terminal/build/outputs/apk/debug/terminal-debug.apk`（51.6M）。`:terminal:assembleDebug` 与
> `:app:compileDebugKotlin` 均 BUILD SUCCESSFUL。**真机一条都没验。**

**做了什么**（用户 5 条需求 + 2 条追加）
- [x] **修「切了容器还在 Termux 里」**：真因不是切换没写进去，是**会话 argv 只在起会话那刻读一次设置**。
      加 `TerminalEnv.sessionSignature()`（env+backend+shell+系统root），`TermActivity.onResume` 比对不一致就自动重开当前会话。
      顺带干掉「改 shell / 改后端要手动重开终端」两条提示。
- [x] **发行版改走 OCI/Docker 镜像**（新 `OciImagePuller.kt`）：匿名 token → manifest list 挑 linux/arm64 →
      blob 边下边解 → 处理 whiteout；国内 daocloud/1ms.run/dockerproxy 回退。内置 9 个发行版 + 自定义镜像（先 probe 探架构）。
- [x] **系统终端**（`EnvRegistry.SYSTEM`）：直接跑 `/system/bin/sh`，可选 su。**红线：只对交互终端生效**，
      AI/MCP/插件（interactive=false）遇到它一律回落 proot（否则等于把设备 shell / su 递给 AI）。
- [x] **套餐(光板/精简/办公/专业)**：装完写脚本进容器，**首次进入时自动装**，`xtom-setup` 可重跑。
- [x] **抄 ZeroTermux 四条**：一键换源（宿主侧写文件）/ 多会话标签 / 备份恢复（自写 tar writer，zip 存不了软链）/ 快捷命令⚡。
- [x] **自创美化**（`TermBeautify`，不装 oh-my-zsh、**不要 Nerd Font**）+ **Neovide 光标**（指数逼近+凸包拖尾+正弦呼吸+闲下来停帧）。
- [x] **追加①：软键盘长按退格连续删除**——根因是终端 Editable 恒为空、输入法拿到空串就不肯连发（vendor patch #2）。
- [x] **追加②：功能重新归类**——抽屉=终端→运行环境→软件包→文件→**个性化**（原「外观」改名）；「运行后端」从外观页搬到运行环境页。
- [x] **追加③：UI 流畅性对齐主 App**（见 `DESIGN-CHAT-PERF.md` **第十二节**）：EnvActivity 逐行现算的磁盘读/JSON 解析全部记忆化+IO、
      Swatches 24 次正则→remember、选字体 copyTo 挪 IO（32MB 会 ANR）、FilesActivity 每行 new SimpleDateFormat→单例、
      两页补 contentType、`compose_stability.conf` 两模块共用并补 `:terminal` 段 11 个模型。

**⚠ vendor 改了两处**（terminal-view，Apache-2.0，NOTICE 已按 §4(b) 声明）：
patch #1 `mHideCursor`/`getCellTopOffset()`/`setCursorHidden()`（把光标让给动画浮层）；
patch #2 `getTextBeforeCursor()` 回占位文本 + `setImeDeleteRepeatWorkaround()`（长按退格连删）。

**下一步 = 真机实测这一整轮**（清单在 `TODO-TERMINAL-APP.md` 末尾「必验清单」9 条）。
装 apk：`terminal-debug.apk` 直接侧载即可；经主 App 分发才需要重打内置包。

---

## 🆕 2026-07-21 会话·未完成线头汇总（防忘）

> 这天开了很多线，编译都过、真机基本没验。分组如下。详见各专项文档 / DESIGN-*.md。

**A. 终端（TODO-TERMINAL-APP.md 有细节）**
- [x] Phase B 多发行版跑 proot：Alpine/Ubuntu/Debian 可装（+XZ 解码 `org.tukaani:xz`）、装/删/切 UI、安装器、通用发行版 proot 启动。编译+APK 过。
- [x] Phase B 两 bug 修：`isDistroInstalled` 改 lstat（不然 Alpine 装好被误删）；`Proot.run` 缺环境自动补装。
- [ ] **真机验 Phase B**：装 Alpine/Ubuntu/Debian → 能下载+解压+起 shell+apt/apk 联网？`rm -rf` 只毁容器不伤宿主？切回 Termux 无回归？
- [ ] **Phase A chroot 安全方向拍板**（a 下线 / b setuid 降 app-uid / c 门控+警告+3缓解）——红队判「同 uid=一键 root 后门」，**定案前 chroot 不可启用**；proot 安全可用。
- [ ] Phase C 终端内 `xtom-env` 指令（list/use/install/remove）——待 Phase B 真机验后再上，别在没验的地基上盖。
- [ ] Debian 点版本硬编码会过期→动态解析目录；国内镜像回退补齐。

**B. 聊天 / 工具**
- [x] **内联文本工具调用兜底解析**（`InlineToolCallParser`）——修 Nemotron/Hermes 等「工具调用当文本吐出→tool use failed」。**待编译验证 + 真机**；还要看语音路径 `WakeAssistantActivity` 要不要同款。
- [ ] **⭐ settings.get / settings.put 工具**（读写安卓系统设置，System/Global/Secure，需 WRITE_SECURE_SETTINGS 或走 Shizuku）——用户新要求，未做。
- [x] 网页抓取加强 #1 不可信围栏（`UntrustedWeb.fence`，open_page/fetch 9 处）+ cookie 级联（匿名→抓不到才带登录 cookie）。
- [ ] 网页加强残留：#1b browser 常驻会话/web_search 也套围栏（browser 要区分元素列表 vs 页面文字）；**#2 收紧带登录态 browser 动作**（被注入页面驱动已登录浏览器提交表单，最高危）；#3 HTTPS-only+私网IP拦截防 SSRF；#4 ServiceWorker 收敛；#5 字节级限长+offset 翻页。

**C. 竞品 & 系统集成**
- [x] 竞品 Eta 进监控（`competitor_watch.py` 6 家）；竞品克隆全挪 `F:\CompetitorRepos\`（E/C 已清）。
- [ ] **实时胶囊 阶段 A（免 Xposed，规格全在 `DESIGN-LIVE-CAPSULE.md`）**：HyperOS 超级岛（`miui.focus.*` 通知 extras，引 hyperisland_kit）+ ColorOS 16 流体云（Android16 Live Updates `Notification.ProgressStyle`+`setRequestPromotedOngoing`）。真机验：超级岛非白名单能否出岛 / CO16 是否被 OPPO 二次拦。
- [ ] Xposed 阶段 B（可选 root 增强，ColorOS/HyperOS 优先）：按键唤醒（ColorOS `OplusSpeechHandler.handleMessage` 已确认 / HyperOS `MiuiPhoneWindowManager` 需反编译）、CO14/15 流体云 hook（`com.oplus.systemui.statusbar.seeding.SeedlingPluginManager`）。新建独立 `:xposed` 模块、hook 只发 Intent 拉起 App。

**D. 唤醒 / 其它**
- [ ] ADB 保活（Shizuku 无 PC 自恢复）：落点=复用内嵌 Termux 打包 `adb` 自连回环拉起 Shizuku，`ShizukuMic` 下游零改；**用途=语音唤醒保活**（deviceidle 白名单/后台麦 appops 让唤醒服务不被杀）。研究完，未做。
- [ ] **全部未提交**；chroot 方向定了、真机验一轮后再统一整理提交。

---

## 🔐 零、安全审计状态（2026-07-19 晚，**做到第二轮，用户定明天再继续**）

对沙盒包/权限/文件/系统四个攻击面做了对抗红队（"如果我要入侵会怎么写"）。**已跑两轮红队 + 两轮补丁，第三轮明天再放（用户要求）。**

**第一轮补了 11 条，红队复核判「扎实攻不动」的**：短名绕包禁用(ToolManager 用 tool.name)、local_search 侧门读禁令数据、同 realm 令牌窃取核心锁(bootstrap 锁死 __mk/AndroidBridge/__resolve)、MCP 不再继承 AI allow-list。

**第二轮已补完**（编译通过 `:app:compileDebugKotlin`，真机未验——JS 运行时改动尤其要验）：
- ① FullBackup 外来包换 `.db` 仍改 LLM 端点/密钥 → **外来(无标记)包恢复时保住用户当前 api_configs**（恢复前读出、覆盖后写回），`ImportResult.apiConfigPreserved` 告知 UI。副作用：备份里引用旧 configId 的对话 configId 会 SET NULL 回落默认(不崩)
- ② http_request SSRF → `blockedHost` 改 `InetAddress.getAllByName` 逐地址判环回/私网/链路本地/组播/IPv4-mapped/ULA/元数据/CGNAT；`instanceFollowRedirects=false` 手动逐跳(≤5)每跳复检。残留：连接时二次解析有极小 TOCTOU(注释标了)
- ③ 锁死名单补 `__p/__t/__hooks/__om/__pkgFiles/__pkgMods/__mkToolCall/__mkTools/complete/log`（为锁 __om 等把 __resetRuntime 改成原地 delete 键）。**故意不锁** `__s`(计数器)/`__curPkg`/`__hookPkg`(每次重新赋值，锁绑定会坏功能)
- ④ SkillSecurityScan「运行时篡改」补 `__p/__t/__hooks`
- ⑤ MCP 按连接 `remoteSocketAddress`(IP:端口) 作 clientId，不同连接不共享「始终允许」
- ⑥ Operit 令牌经**闭包/参数**流转(别的包偷不到)：`__mkToolCall/__mkTools/loadOperitModule/__mkRequire` 都带令牌，Kotlin 三处 evaluateJavascript 传 `tokenFor(pkgId)`。**残留**：`window.OkHttp` 门面仍走全局空令牌→归 unknown(注释标了，有 SSRF+权限闸兜底)

**第三轮已补（2026-07-20，编译过 `:app:compileDebugKotlin` BUILD SUCCESSFUL，真机未验）**：
- ✅ 插件 `systemPromptCompose`/`processMessage` 钩子**从「无门控」改为「按注册包逐个审批」**。做法（复用全项目唯一的权限闸，不新开 UI）：
  - `JsPluginRuntime.hookOwners`（钩子类型→注册它的 `toolpkg_<id>` 集合），OperitCompat 扫描时填、`clear()` 清；
  - `fireHook(..., allowPkgs)` + JS `__fireHook` 第 6 参 `allowJson`：只放行获准 pkgId 的钩子，**解析失败/钩子无 pkgId 一律剔除(fail-closed)**；
  - `ToolPermissionManager.checkCapability(caller,key,...)`：插件默认 ASK、按 `plugin:<pkg>:` 前缀记忆「允许/询问/禁止」，prefs 没起来判拒；
  - `OperitFramework.gateSensitiveHook`：逐包审批，扫不到属主则退化成「未知来源插件」整体审批一次；改动系统提示/代答时 `ToolActivityBus` 记一条（避免黑箱化）。
  - **本轮新增·真机必验**：装一个注册 systemPromptCompose 的框架 .toolpkg，首次发送应弹一次审批框（聊天页前台可见），选「始终允许」后不再弹；「始终禁止」后系统提示应原样不被改。后台/唤醒场景无人看框会 60s blind-timeout 判拒（预期）。

### 🔴 第三轮红队（4 子agent 并行攻上面这个门控补丁 + 相邻面，2026-07-20）——**门控补丁被判「纸门」**
**已补（编译过 BUILD SUCCESSFUL，真机未验）**：
- ✅ #B 锁列表补 `loadOperitModule`/`__mkRequire`（这俩接真令牌作参数却可写→先注入的包覆写即偷别包 Plugin 级令牌、继承其"始终允许"）。`JsPluginRuntime.kt` 锁列表。
- ✅ #C 卸载清授权：`ToolPermissionManager.clearCallerOverrides("plugin:<id>:")`，`uninstall` 调。堵「卸载不清 `plugin:<id>:hook_*` → manifest id 相同的恶意包重装静默继承改系统提示权」。
- ✅ #3/#F `__fireHook` 过滤器 `aset` 改 `Object.create(null)`+own-property 判定（原 `var aset={}` 用 `'constructor'`/`'__proto__'` 当 pkgId 走原型链恒真值、通吃 allow 集）。
- ✅ #K `checkCapability` 不再走 `effectiveFor`（对非 Plugin caller 会下沉 masterMode 默认 ALLOW 静默放行）→ 只认显式 override、否则 ASK。
- ✅ #L `gateSensitiveHook` 扫不到属主改 **fail-closed 空集**（原「审批 unknown→null→无差别放行全部」危险退化）。
- ✅ #5/#D `promptInput` 钩子纳入同套逐包门控（原零审批可改喂给模型的输入）。
- ✅ #E 装前扫描 `SkillSecurityScan` 补规则「改写AI提示/代答」(CAUTION)，匹配 `register(SystemPromptComposeHook|MessageProcessingPlugin|PromptInputHook)`——原先这类包扫成 ✅SAFE。

**✅ 核心洞 #A 已按路线(a)根治（2026-07-20，编译+assembleDebug 均 BUILD SUCCESSFUL，⚠️真机未验——JS 运行时改动，最需要验）**：
- 洞：钩子属主 `h.pkgId` 可伪造 → allowPkgs 门控可被完全绕过（三条路见下方"曾经"）。前提=已有任一合法同类包被「始终允许」。
- 修法（闭包私有化，改 `JsPluginRuntime.kt` 的 `OPERIT_FW_JS` bootstrap + 少量 Kotlin）：
  - `__hooks`(钩子表)/`loadingPkg`(当前加载包指针)/`__mkRequire`/`__hpush` **全关进 OPERIT_FW_JS 的 IIFE 闭包，不挂 window** → 插件够不到，既不能直接 push 钩子表、也改不了属主指针、也不能覆写 __mkRequire 偷令牌。
  - `__hpush` 属主取**闭包私有 loadingPkg**（不再读可写的 `window.__curPkg`），且只在框架包加载期允许注册，`Object.defineProperty` 把每条钩子 pkgId 冻成不可改。
  - `__loadFrameworkPkg`/`__resetRuntime` 加**引导口令 nonce**（每进程 SecureRandom，嵌进闭包、window.__bn 捕获即删；Kotlin 每次调都带上）→ 插件自己 call 它以别的包名加载会因口令不符被拒。
  - `__fireHook` 的 allowPkgs 过滤改**手写循环**（不用 `Array.prototype.filter`，防覆写）+ 无原型 aset（防原型链 pkgId）。
  - **曾经的三条绕过路现全断**：①改 `__curPkg`→__hpush 不读它了；②直接 push `__hooks`→表私有够不到；③覆写 filter→不用 filter 了；④插件自调 __loadFrameworkPkg→无 nonce 被拒。
  - **真机必验（最高优先）**：装一个合法框架 .toolpkg（注册 systemPromptCompose 的），确认①**钩子仍能正常加载生效**（别因 nonce/闭包改动加载不了）②首发弹审批、始终允许后照常改提示、始终禁止后不改 ③WebView 被系统回收后重灌（切后台良久再回来）钩子仍在。**已知取舍**：钩子必须在 registerToolPkg 里**同步**注册（异步/延迟注册会被丢弃）——Operit 是同步的，但真机确认一下没有包依赖异步注册。
  - 产物已出：`app/build/outputs/apk/debug/app-debug.apk`。
- 次要未修：#H env `getEnv/envSet` 现读 `__hookPkg||__curPkg`→跨包偷别包存的 API key（应 Kotlin 侧按 invoke 上下文绑 pkgId）；#J 审批 `pending` 单槽并发会挂死/丢弃/审批错配（但 fail-closed 不假放行，修法=PendingRequest 带 id + resolve 校验）；#I `__p` 可预测 id 竞态改别包工具返回值(低)；#M 插件下载走第三方镜像无签名/哈希校验(叠加 #E 缺口才危险)；pkgId(manifest toolpkg_id) 无唯一性校验（同 id 共存变体，根治=授权绑内容指纹）。

**仍未修的残留（更早几轮的，下一轮）**：
- UiDangerGuard 仍不 gate `set_text`/`swipe`；shell `input tap` 完全绕过 ui_control
- 备份标记可伪造（固定名 zip 条目），根治需 HMAC；每插件单开 WebView 才是 realm 隔离的根治（与上面 #A 根治(b) 同一件事）

## ⚠️ 一、真机必验清单（**最高优先级，按风险排序**）

这些是 PC 上**验不了**的，且有几条不通会让功能变成负资产。

- [ ] **① 浮层摘窗是否赶得上手势**（不通则 AI 点不动屏幕，比没有这功能糟得多）
  - 开「对话设置 → 执行方式 → 操作可视化」，让 AI 连续点击 5~10 次，看有没有静默丢事件
  - 背景：`FLAG_NOT_TOUCHABLE` 只挡用户真手指；AI 手势走 `dispatchGesture` 注入，叠加 Android 12+ 触摸遮挡策略 + 目标 App 的 `filterTouchesWhenObscured`，**屏幕上只要悬着外来窗口就可能静默丢事件**
  - 现方案：手势前 `removeViewImmediate` 物理摘窗 → 执行 → 成功才画 → 600ms 后撤
  - `GESTURE_SETTLE_MS = 24ms` **是估的**，丢事件就加大（`UiActionOverlay.kt`）
  - 不通就先关开关，改成别的方案
- [ ] **② 高亮框原点是否下偏一个状态栏**（本轮唯一靠推理定的取值）
  - `dump` 一个已知控件再 `tap`，看框是正压在控件上还是整体下偏约 24dp
  - 偏了就把 `UiActionOverlay.kt` 的 `COMPENSATE_STATUS_BAR` 翻成 `true`（一处生效）
  - 依据：我们显式调了 `setFitInsetsTypes(0)`，Operit 没调所以它要手工减
- [ ] **③ 终端字体缩放**：双指捏合能否改字号、`A±` 按键、杀掉重开是否保持（prefs `term_ui/font_sp`）
- [ ] **④ Termux 第三方美化包**：装完重开终端配色/字体是否变、切后台回来是否热生效
  - 再验一份**故意写歪**的 `colors.properties`，确认终端仍能起来（vendor 的 `updateWith` 遇未知键会抛）
- [ ] **⑤ 终端玻璃观感**（记忆里写明「改玻璃参数必须真机确认」）
  - 注意：**没设背景图时玻璃会退化成半透明纯色**，要先设背景才看得到模糊
  - 参数 `DOWNSCALE=4` + `BLUR_R=6`（比主 App 温和），糊成马赛克就调
- [ ] **⑥ 聊天页本轮修复**：贴底跟随是否还会追不上、「回到最新」按钮位置、搜索跳转与卡顿、建议芯片两种位置
- [ ] **⑦ 文件回滚**：删目录的「不可回滚」文案是否清楚、超 100 条/20MB 的淘汰是否真删 blob
- [ ] **⑧ 本地搜索 `local_search`**：8 个源各搜一次；按 STOP 能否真停
- [ ] **⑨ 双栏文件管理**：520dp 阈值在你设备上落哪侧、窄屏退化是否正常

---

## 一点二、第三批已完成（编译过，真机未验）

- [x] **聊天外观自定义全套接通**：气泡渲染 + 设置页真实预览 + CompositionLocal（踩坑：两 agent 各建同名类不同包→编译过但预览不生效，已合并；prefs 默认值 4 项凭印象填错已按实测纠正）。路由 `chat_appearance`
- [x] **AI 活动中心**（路由 `activity_center`）：行为流时间轴 + 监控/风控合并标签页
- [x] **危险操作事前确认** `UiDangerGuard`（付款/转账/删除前弹窗；盲态强制确认）
- [x] **i18n** 补 208 键 × 33 语言，空格子归零（954→1162 键）；顺带修管线两坑
- [x] **主 App 滚动卡顿**：根因 `topChromeGap()` 在组合期读折叠进度→折叠时整页每帧重组（不是玻璃）。改 `layout{}` 延迟读，零重组。连带修 14 个页面
- [x] **终端 UI 丑/看不清**：根因①系统浅色致顶栏白 ②`PkgRow` 标题没给颜色继承纯黑在深底隐形。锁深色对齐主 App fallbackDark + 修文字令牌 + 全局兜底 + 垫半透深色底 + 玻璃调清爽（dim 0.45→0.35/tint 0.35→0.20/降采样温和/模糊更大）
- [x] **终端一批真 bug**（另一 agent）：文件页进度弹窗关不掉("炸了"主因)、Proot 取消无效/输出无界OOM、ExtraKeysView 窗口+Handler泄漏、PkgActivity apt 失败报成功
- [x] **安全补丁两轮**（见上「零」节）

## 一点五、第二批已完成（8 个 agent 并行，编译过，真机未验）

- [x] **聊天外观自定义**：气泡圆角/尖角三态(无·连体Telegram式·箭头微信式)/底色文字色、头像尺寸圆角、
      头像与名字显隐，**用户与 AI 两侧独立**；设置页带**复用真实气泡组件**的实时预览。路由 `chat_appearance`
  - ⚠ 踩到一个编译器发现不了的坑：两个 agent 各建了一份 `ChatAppearance`/`LocalChatAppearance`
    （`com.arix.app` vs `com.arix.app.ui`），**包名不同所以编译全过**，但设置页 provide 的是 A、
    气泡读的是 B → 预览怎么拖都不动。已合并到 `ui` 那份。
  - ⚠ 初稿 prefs 默认值 4 项全错（凭印象填的），已按实测纠正：圆角走 `shapes.large` 且随形状档位在
    **14/20/24dp 浮动**（无定值 → 用 -1 哨兵）、头像实际 30dp（不是 28）、正圆 15dp、
    用户侧头像与名字现网**是显示的**
- [x] **AI 活动中心**（路由 `activity_center`）：行为流时间轴 + API监控/风控合并成标签页；
      聊天页那个 Dialog 保留（就地看），公共渲染抽成 `ToolActivityTimeline` 复用。旧 `monitor` 路由转发过去
- [x] **危险操作事前确认** `UiDangerGuard`：付款/转账/删除前弹窗等确认，复用现成 ASK 挂起机制
  - 分级：CRITICAL 见词即问；SENSITIVE 需旁证（金融 App / 屏上有金额 / 有密码框）
  - 堵了最危险的绕过：金融 App + 密码框 → 任何点击都问（AI 可以不用 click_text、dump 完直接 tap 坐标，词表失灵）
  - 降噪：`包名|目标文字` + 3 分钟 TTL；`resetSession()` 接在每轮发送（上一轮的同意不顺延给新意图）
  - ⚠ **已知能力空洞**：只有 Shizuku 没开无障碍时读不到包名/屏幕内容 → **一律放行，这条路上没有保护**
- [x] **终端配色同步 + 补动画 + 全屏适配**
  - 配色走「零跨进程」：跟随系统深浅色 + Monet（含 **A11/A12 backport**，手表大量停在 A11）。
    理由：终端有 launcher 图标、**可能先于主 App 启动**，AIDL 对端没起来时首帧只能瞎猜 → 开屏闪色
  - 动画：转圈/进度条/按键条收起用 `withFrameNanos`（正是用户盯着等 apt 装完的地方，不转的圈=看着像卡死）；
    一次性转场用 AnimatedVisibility；按下反馈直接设 scale（手表上手指盖住键，涟漪看不到）
  - 键盘让位**按主 App 真机结论走**（不加 ime inset），理由写进注释防止被"修回去"
  - 圆屏按 `isScreenRound` 自动内缩（短边 5.5%），方屏为 0 = 空操作
- [x] **i18n**：183 + 25 键 × 33 语言补齐，空格子归零（表 954 → 1162 键）
  - 顺带修了管线两个问题：3.75 万条撑爆单类 64K 常量池（改每语言一个 object）；
    `.strip()` 会破坏**拼接片段**的首尾空格（那些空格是有意义的）
- [x] **对抗审查修掉的**（详见下节"教训"）

## 二、下一批要修（来自对抗审查）

> **2026-07-21：这份清单原写「均未修」，实际上已与代码脱节。** 全项目重新核对了一遍
> （只读审计 + 逐条验证），大半其实早在第三批修掉、只是没划掉；剩下真·未修的这轮又补了 4 条。
> 结论如下。编译状态：`:app` + `:terminal` `compileDebugKotlin` / `assembleDebug` 均 BUILD SUCCESSFUL；
> **仍真机未验**（性能/内存/安全类改动，尤其大图 ANR、ShellTool 有界输出要在真设备上确认观感）。

**核对发现早已修好（第三批做的，原清单漏划）：**
- [x] 终端 `Proot.kt` 取消无效 → `job.invokeOnCompletion { proc.destroyForcibly() }` 已在（`Proot.kt:58`）
- [x] 终端 `Proot.kt` 输出无界 OOM → `appendBounded`/`MAX_OUTPUT_CHARS=1_000_000` 已在（`Proot.kt:22-27`）
- [x] `FilesActivity` 进度弹窗关不掉 → 伪造 elvis 分支已删、`onDismissRequest` 走真 `dismiss`（`FilesActivity.kt:304/499`）
- [x] `ExtraKeysView` 窗口/Handler 泄漏 → `onDetachedFromWindow` 里 dismiss + `removeCallbacksAndMessages`（`:96-101`）
- [x] 终端 `ui/` 零调用组件 + `PkgActivity` 两调用 → 死代码已删、`PkgActivity.kt:78-79` 已补两调用
- [x] `PkgActivity` 缺「清除背景」→ 溢出菜单已加「清除背景」（`PkgActivity.kt:268-271`）
- [x] `PkgActivity` 诚实性（`&&` 链/`dpkg-query` 失败/`apt-cache` 失败）→ 三处均已诚实化（`:283/169-173/224-228`）

**本轮（2026-07-21）新修：**
- [x] **主线程拷大图 ANR** → `PageBackgroundPrefs.set` 的 `copyTo` 搬进 `withContext(Dispatchers.IO)`；
      4 个调用点（`PersonalizationPage`/终端 `FilesActivity`/`PkgActivity`/`TermSettingsActivity`）统一「后台拷贝→成功回主线程 bump version」
- [x] **`ShellTool` 预览全量拷贝 + stdout/stderr 无界增长** → 新增 `BoundedText`（头 4000+尾 8000，中间丢弃、`total` 记全量供如实报「共 X 字符」）；
      preview 的 `buildString` 不再重拼几 MB，从源头堵住 `find /` 的 OOM
- [x] **`ToolActivityBus` 常驻 ~6.4MB** → 分层保留：最近 40 条留完整 8000×2，更老的压成 400 字摘要（O(1)、跌出窗口时压一条）；最坏上界降到 ~0.77MB，近期卡片全量不变
- [x] **`TerminalInstaller` 无签名校验 / `RemoteAssets` 任意 scheme** → 下载后交安装器前校验「APK 签名须与本 App 一致」（对不上即丢弃，fail-closed）；
      `RemoteAssets` 加 `validBase` 只认 http/https（挡 `file://`/`content://` 注入面；明文 http 由签名校验兜底完整性，不砸局域网自建）

**仍未修（作者主动押后，超本次范围）：**
- [ ] `FileHistory` 的 `snapshotSkipped`/`diffPreview` 是**生成时翻译后存进索引**的，事后切语言老记录仍是旧语言。
      根治要存错误码、显示时再翻（要动 `FileHistory.kt` + `FileHistoryPage.kt`）——作者注释已承认超范围
- [ ] `i18n/_missing.json` 是生成的报告文件，提交前确认要不要入库

## 三、本轮已完成（编译过，真机未验）

### AI 行为可视化（用户要的「避免黑箱化」四条）
- [x] 实时操作日志面板（行为流时间轴）——组件早就写好但**没有入口、打不开**，本轮补上（点动作条展开 + 输入栏「+」菜单常驻入口）
- [x] 工具卡片可展开看全量：完整参数(JSON 美化)/完整返回/耗时/请求方/成败，各带复制按钮
- [x] 执行时实时提示：`shell` 接上实时输出流；`code_runner` 判定**不该改**（委派链路已有流，重复推会互抢 owner 令牌）
- [x] 操作可回放/可撤销：`FileHistory` + `设置 → 工具与扩展 → 文件改动历史`，看 diff、一键回退
  - 快照存 `filesDir/ai_file_history/`，**刻意在 `ai_workspace` 之外**——否则 AI 能看见/改/删掉自己的后悔药
  - 超 1MB 只记元信息并标「不可回滚」（不给假的回滚按钮）；回滚前重新哈希比对，被动过就警告
- [x] 屏幕操作可视化：无障碍加结构化 `dumpNodes()`（保留元素 Rect，这是原先缺的地基）→ `UiActionBus` → 透明浮层画高亮框 + 常驻「正在操作」光晕

### 本地搜索
- [x] `local_search` 单工具 + `scope` 参数覆盖 8 类本地内容（守「工具 1 用多」原则）
  - 对话/记忆**委派**给已有实现，不重写；真正新增的是日记/提醒/角色卡/世界书/功能包（此前完全没有搜索入口）+ 工作区跨文件搜索

### 聊天页修复
- [x] 搜索跳转（4 个真机才会犯的场景全补）
- [x] 搜索卡顿：160ms 防抖 + 移出主线程 + `indexOf` 代替 `ignoreCase` 的 contains
- [x] 建议芯片收进输入胶囊 + 位置可选（个性化页）
- [x] 顶栏胶囊挤掉「+」：裹 `weight(1f)` 让位
- [x] 长名字跑马灯：**`basicMarquee` 在动画缩放=0 时不动**，改 `withFrameNanos` 手推
- [x] 行为流 UI 挡住消息：底部留白改按浮层实测高度动态给
- [x] 「回到最新」按钮藏到芯片行背后只露半个：底距跟浮层高度走
- [x] 追不上输出：贴底落点漏加前置 item + `atBottom` 容差没算尾部 6 个零高度工具 item
- [x] 撤销我上一轮加的 `imePadding()`（窗口已被系统 resize 过，双重偏移把输入框顶到半空）

### 终端
- [x] 缩放失效：根因是**返回值语义搞反**（vendor 传的是累计倍率且会拿返回值写回累计器，旧代码无条件 `return 1f`，增量刚攒上就被打回）；改 ±1sp 一档 + `A±` 长按连发
- [x] UI 照主 App 风格重做（独立实现一份「模糊一次多处采样」玻璃管线，terminal 不依赖 `:app`）
- [x] 文件管理照参考图改双栏（`< 520dp` 退化单栏）+ 底栏五键 + 浏览器式前进后退历史栈
- [x] Termux 美化：根因是**宿主从没读过** `.termux/colors.properties` 和 `font.ttf`（路径本来就对得上）
  - 附带发现：美化包结尾的 `termux-reload-settings` 发的是给 `com.termux` 的广播，本机没装官方 Termux，**那步必然静默无效**
- [x] `code_runner` 真 bug：片段写进主 App 私有目录再用相对路径跑，而 `linux_exec` 在独立终端 App 的 proot 里（另一 uid/HOME）**根本读不到**，装了终端反而必坏；改 heredoc 送进终端侧执行
- [x] 删 `termux-build/` 旧自编 bootstrap 流水线

### 对抗审查修掉的（本轮抓到 1 个 CRITICAL）
- [x] **CRITICAL** `local_search` 用 `runCatching` 包 suspend 调用 → 吞 `CancellationException` → STOP 停不掉（项目老坑）。顺带修了 `manage_chats`、`shell`、`viaAccessibility` 的同类问题
- [x] **权限降级后门**：`local_search` 委派 `manage_chats` 绕过全项目唯一权限闸，用户设「始终禁止」也能读全部聊天记录 → 等级抬到一致 + 尊重功能包开关
- [x] 把「不知道」说成「没有」：工作区扫描 400 上限，扫满没命中却返回 null，AI 会斩钉截铁说「没这个文件」
- [x] 模糊匹配对长文件静默失效（`FuzzyMatch` 近似匹配超 2000 字符短路成精确子串）→ 改分块打分

---

## 四、待办 / 未决

- [ ] **全部未提交**（约 79 个文件）。建议真机验完再提交；分四组：终端 App / 本地搜索 / 聊天页修复 / AI 行为可视化
- [ ] `TERMUX-BOOTSTRAP-BUILD.md` 是同一套废弃流水线的文档，也过时了，**待用户点头一并删**
- [ ] `RemoteAssets` 的终端 APK 下载地址仍留空让用户自填（仓库还私有、且要等改名定下来）
- [ ] 更名 Cyane 整体推迟（applicationId/包名大改，用户「先不急着改，做功能」）

---

## 五、几条别再踩的坑（本轮新增/复认）

1. **凡是「必须一直动」的东西一律 `withFrameNanos` 手推** —— 手表系统动画缩放=0 很常见，`tween`/`infiniteTransition`/`basicMarquee` 会直接跳终值。本轮我又栽了一次（跑马灯）。
2. **`imePadding()` 这条以真机为准**：静态推理会得出「edge-to-edge 下窗口不 resize、必须自己让位」，但实机相反，加了会双重偏移。
3. **两个 agent 各建同名类、包名不同 → 编译全过但功能是断的**（本轮 `LocalChatAppearance` 实例）。
   并行开工前先把**共享契约定死在一个文件里**，别让两边各自"顺手建一个"。
4. **写了但没接线**是本会话最高频的失误（行为流面板无入口 / 浮层开关无 UI / `resetSession()` 无人调 /
   `ToolResultCard` 没传 `toolCallId` 导致整个详情功能是死的）。编译器一个都发现不了，只能靠专门审查。
5. **`FLAG_NOT_TOUCHABLE` 挡不住「AI 注入手势被遮挡」** —— 触摸遮挡是按「屏上有无外来窗口」判定的，与 flag 无关。唯一可靠解法是物理摘窗（`removeViewImmediate`，不是 `GONE`/`alpha=0`/改 flags）。
6. **列表尾部有 6 个常驻零高度 item**，凡是「拿最后可见 item 下标判断到底了没」的地方都要算进容差（已提成 `TRAILING_UTILITY_ITEMS` 常量，改了 item 数量要同步）。
7. **`runCatching` catch 的是 `Throwable`，吞 `CancellationException`**；Kotlin 的 `CancellationException` 又继承自 `Exception`，所以 `catch (e: Exception)` 同样吞。凡 suspend 路径一律先 `catch (c: CancellationException) { throw c }`。
8. **vendor（`terminal-emulator`/`terminal-view`）diff 必须保持为空**。本轮两个终端 bug（缩放、美化）根因都是**宿主没按 vendor 契约来**，vendor 本身是对的。
