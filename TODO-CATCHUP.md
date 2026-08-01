# Arix 竞品补齐清单（可推进 · 打勾表）

> 生成于 2026-07-11。对照 Operit v1.12.0 / RikkaHub 2.4.1 / Kelivo v1.1.17 / Hermes / 橘瓣 五款最新版**源码**核出的 Arix 缺口。
> **总表（source-level 逐包细目）见 `COMPETITIVE.md`；可视化矩阵见会话 Artifact。**

## 🔁 工作流（本文件怎么用）
1. 从下面挑一项（建议按批次顺序），开新对话开工。
2. 开工前可翻 **`COMPETITIVE.md`** 对应条目参考竞品**思路/字段命名**——但 Arix 是独立项目，**一律自研实现，不抄源码**。
3. 干完一项：回本文件把 `[ ]` 改成 `[x]`，一行提交。
4. 需要时回 `COMPETITIVE.md` 总表，把下一批再列成小表接着干。

**图例**：现状 ✕=无 / ◐=半成品·占位；`[路线图]`=已在 `TODO-V1.md`；★=关键项（补它解锁一整块）。
语音（唤醒/STT/TTS）是我们的强项、几乎无缺口，不列。

---

## 批次 1 · 便宜快补（体感差距大、单项工作量小）
- [x] 代码语法高亮（自写单遍扫描器，颜色取主题令牌｜MarkdownText.kt highlightCode）
- [x] Mermaid 流程图（自研解析 graph/flowchart + Canvas 分层绘制｜MarkdownText.kt MermaidView）
- [x] 代码块预览/保存/折叠（折叠>14行 + 保存到下载 MediaStore + 复制｜MarkdownText.kt CodeBlock）
- [x] AI 翻译消息 · 长按翻译（长按菜单「翻译」→模型流式翻译弹窗，自动判向｜ChatScreen BubbleAction.Translate）
- [x] QR 配置导出/导入（zxing 生成/选图解码，内容仍是我们的 JSON｜QrKit + ConfigPage）
- [x] 对话文件夹分组（DB folder 列 v15 + 筛选筹码/移动/文件夹标签｜ConversationListScreen）
- [x] 导出消息为 Markdown/TXT（对话列表每条「导出」→ Markdown/TXT/JSON，转化+系统分享｜ConversationListScreen + ImportExport）
- [x] 消息跳转/导航按钮（列表右下「回到最新」悬浮按钮｜ChatScreen）
- [x] Enter 发送可配置（ConfigModePrefs.enterToSend + 输入框 onPreviewKeyEvent，Shift+Enter 换行）`[路线图]`
- [x] 快捷短语 Quick Phrase（QuickPhrasePrefs + 输入区「+」插入 + 设置增删）
- [x] 图片查看器 缩放/旋转（ImageViewerDialog 双指缩放/旋转/拖动/双击复位，接聊天/Markdown/文件页）
- [x] 化学式 / SVG 代码渲染（```svg WebView 渲染｜MarkdownText.kt SvgView；化学式可经 SVG/LaTeX）

## 批次 2 · 中等（补齐主流聊天客户端能力）
- [x] 消息分支 fork / 变体版本切换（会话树｜MessageTree.kt 差分提交建树；重新生成/编辑重发自然产生兄弟分支，气泡下 ‹k/n› 左右切换带下游子树；新增 nullable branchesJson 列 v16，messagesJson 仍存活动路径向后兼容）
- [x] 多选 + 批量删除（列表右上「多选」→ 复选框+全选+批量删确认｜ConversationListScreen + ConversationDao.deleteMany）
- [ ] 分享为图片（现✕｜Operit/Rikka/Kelivo/橘瓣）
- [ ] PDF/DOCX/PPTX 解析入模（现✕｜四家）
- [ ] 图片 OCR 取文字 / 图片裁剪（现✕｜Rikka/Kelivo）
- [ ] 密钥池 多 key 轮换+策略（现✕｜Operit/Rikka/Kelivo）`[路线图 P1]`
- [ ] 上下文压缩 / 自动摘要（现✕｜四家）`[路线图 P1]`
- [ ] 消息队列 处理中排队（现✕｜Operit/Kelivo）
- [x] 键控搜索 API（现有 Bing/百度/搜狗/DDG/Google 抓取框架上加键控引擎 + 多轮研究）`[路线图 XSEARCHING]`
      - [x] AnySearch 统一搜索（MCP，匿名/key，默认关+隐私门控；extract 兼作 FetchTool 防403兜底｜SearchEngine.AnySearchEngine + SearchApiPrefs + SearchTool 门控 + AppSettingsPage 开关）
      - [x] Perplexica（自托管 AI 搜索，完整 baseUrl+chat/embed provider 配置｜PerplexicaEngine + 设置卡，默认关）
      - [x] XSEARCHING 1.0 多轮研究循环（deep_search 工具：多引擎→LLM评估→扩子查询→综合报告+引用+置信度；研究模型可自选、轮数可配｜XSearchTool + XSearchPrefs + 设置卡）
      - [x] 键控引擎 14 源（RikkaHub 2.4.1 全套：Tavily/Brave/Exa/Serper/Jina/博查/SearXNG/Firecrawl/Tinyfish/Ollama/Grok/Perplexity/LinkUp/秘塔｜统一 KeyedEngines 数据驱动 spec+设置列表；web_search engine 选项 + 自动纳入 XSEARCHING；未真机实测需填key）
      - [x] 本地 open_page（WebView 取正文防403）+ 工具合并 web_search(+deep)/open_page(+format) 4→2 防幻觉
- [ ] 世界书注入位/深度 · 正则替换 · 预设对话（现◐/✕｜Rikka/Kelivo/橘瓣）
- [ ] WebDAV / S3 备份（现✕｜Rikka/Kelivo/橘瓣）
- [ ] 自动定时备份（现✕｜Operit/Kelivo）
- [ ] 使用统计 / 热力图页（现◐｜Rikka/Kelivo）
- [ ] 文生图 / 图片搜索（现◐/✕｜Operit/Rikka/Kelivo）
- [ ] 多语言 i18n（现✕｜五家）`[路线图 P1]`

## 批次 3 · 大件（整块能力，分期做）
- [ ] ★ **真·JS 插件运行时**（现✕占位｜Operit/Rikka/橘瓣/Hermes 用 QuickJS 真执行）
- [ ] 插件/技能市场 + AI 生成插件（现◐｜Operit/Hermes）
- [ ] 工作流构建器 + 定时任务 cron（现✕｜Operit/Hermes）`[路线图 P1]`
- [ ] 代码执行 多语言 Py/Node/Go…（现✕｜Operit/Hermes）
- [x] 向量/语义记忆 embedding（memories 加 embedding 列 v17；EmbeddingClient 调 /embeddings；queryRelevant 语义(余弦)检索+关键词回退；新记忆自动建索引+设置页回填；ConfigPage 加「向量记忆」用途｜2026-07-12）· HNSW 暂线性扫描
- [ ] 文档导入+分块 RAG / 记忆文件夹（现◐｜Operit）
- [ ] 无障碍 UI 操控 + Shizuku/Root（现◐/✕｜Operit/Hermes）`[路线图 P2]`
- [ ] proot Linux 环境 / 内嵌浏览器（现✕｜Operit/Rikka/Hermes）`[路线图 P2-3]`
- [ ] 悬浮窗助手 / 屏幕 OCR 悬浮（现✕｜Operit/Hermes）`[路线图 P2]`
- [ ] 本地离线模型 llama.cpp/MNN（现✕｜Operit/Hermes）
- [ ] 云同步 多设备（现✕｜Rikka/橘瓣）`[路线图 P3]`

## 战略批次 · 陪伴层（最贴 Arix 定位，抄橘瓣）
- [x] 主动消息 AI 主动发起（WorkManager 周期任务→门控(静默/最小间隔/有模型有对话)→复用唤醒助手上下文生成一句→追加进最近对话+通知点开｜ProactiveMessage.kt，2026-07-12）
- [x] 健康数据 步数/心率/血氧/睡眠（读第三方 App「better health tracker」ContentProvider，非裸传感器；注入环境上下文+device_status｜HealthSignals.kt，2026-07-12）
- [ ] 定位 / 附近搜索 高德（现✕｜Operit/橘瓣）
- [x] 通知读取（NotificationListenerService→read_notifications）+ app 使用统计（UsageStatsManager→app_usage 工具：各 App 时长/总屏幕时间；权限页「使用情况访问」，默认关｜NotificationAwareness.kt / UsageStatsTool.kt，2026-07-12）
- [x] 日历读写（AI 直接读日程/建日程 CalendarContract；「AI 直接执行」开则直接写、关则跳系统日历｜CalendarTool.kt，2026-07-12）· 短信仍意图跳转
- [ ] 电量注入/感知（现◐仅工具查｜Rikka/橘瓣）`[路线图]`
- [x] 每日总结 / 日记（定时 WorkManager→AI 以人设口吻为当天对话写小段日记+通知；diary 工具 write/read/list；设置开关+时间｜Diary.kt，2026-07-12）
- [◐] 音乐控制（music_control：播放/暂停/切歌/停+媒体音量，AudioManager 媒体键，免权限｜MusicControlTool.kt，2026-07-12）· AI 朋友圈/智能家居仍✕

---
**参考**：`COMPETITIVE.md`（逐包总表）· `TODO-V1.md`（正式版路线图）· `DESIGN.md`（设计基准）· 会话 Artifact（可视化矩阵）。

---

## ✅ 本会话完成（2026-07-11 大批）
- [x] 上下文自动压缩/摘要（ContextCompressor：滚动摘要+并入user防400+sanitizePairing）
- [x] 分享消息为图片（MessageImageShare：Canvas卡片→FileProvider）
- [x] 使用统计/热力图页（UsageStatsPage：对话/消息/Token+近17周热力图）
- [x] 密钥池多key轮换 · 快速配置 · 每功能模型(agent/translate/summary用途)+一键跳转
- [x] 权限页对齐(所有文件/电池豁免/悬浮窗/ON_RESUME) · 工具授权弹窗讲人话(命令翻译按钮)
- [x] ask_user反问澄清 · todo任务清单 · 语音浮层同款卡片 · 统一入场动画
- [x] 视频砍yt-dlp改走端点(B站WBI直链/抖音无水印/YouTube/OG·JSON-LD·oembed通用，APK 105→87M)
- [x] 日用工具：地图导航(高德+Google API) · 火车票(12306) · 生活查询(节假日/油价/汇率/垃圾分类/快递/星座…一个工具)
- [x] 记忆增强(link/pin) · manage_chats · file_converter加图片 · http增强(cookie/multipart/SSL) · generate_image
- [x] GitHub私有仓库云备份 + 分块备份/对比 + 联网自动备份
- [x] **WebDAV 备份 + 定时备份**（WebDavBackup）
- [x] **公共 skill 市场**：从任意 GitHub 仓库装(Claude Code/opencode SKILL.md + opencode agents/commands) + **SkillSpector 式安全审查**(装前扫毒/详列问题/可放行)
- [x] **UI 自动化**：无障碍服务(读屏 dump+点按/滑动/输入/系统键，免root) + ui_control 工具
- [x] **工作流引擎**：WorkflowEngine(变量传递/权限审批/可停止) + workflow 工具 + 工作流页
- [x] **消息队列(处理中排队)**：AI回复时排队后续消息，回复完自动依次发
- [x] **世界书增强(注入位/正则触发)**：触发词/正则命中才注入 + system/user 注入位
- [x] 停止机制彻底修(finally挂起点+catch吞Cancellation链路全修) · 子agent不占主窗口
- [x] 消息分支 fork/变体切换（2026-07-12 完成，见批次2）
- [ ] 多语言 i18n 骨架（暂缓）
