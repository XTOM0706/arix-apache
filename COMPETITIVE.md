# Arix 竞品逐功能对照（源码级）

> ⚠️ **2026-07-27：本文件多处已过时，引用前先读 `RESEARCH-COMPETITIVE-2026-07-27.md`。**
> 已知失效项：① Eta 的 GUI 已改成无障碍优先、root 兜底，且 2026-07-26 起是 PolyForm 禁商用许可证；
> ② 橘瓣的「HNSW 向量记忆」是空壳（代码里 `// No-op: vector index removed`）；
> ③ 下面「A 档缺口」里的**迁移导入我们早已具备**（`ImportConverters.kt` 覆盖酒馆 V1/V2/世界书/Operit/PNG 卡）；
> ④「工具面窄」不是缺口——Operit(170 工具) 和 grok-build 都在给自己的工具爆炸打补丁，
> 反过来印证了我们「工具 1 用多、数量精简防幻觉」的原则。
> **本文件里未标注证据的"缺口"一律当作未核实，别直接拿去当短板复述。**
>
> 生成于 2026-07-11。对照五款安卓 AI 助手**最新版源码**逐文件核对（工具注册表 / i18n 字符串 / 路由表 / 数据模型），非 README 概述。
> 可视化矩阵（~140 细目、可筛选缺口）：见本次会话 Artifact「Arix 竞品逐功能对照矩阵」。
> 旧文档 `GAP.md`（2026-07-02，仅对 Operit archive）已过时，以本文件为准。

## 版本快照
| App | 版本 | 日期 | 仓库 | 技术栈 |
|---|---|---|---|---|
| **Operit AI** | v1.12.0 | 2026-07-01 | AAswordman/Operit | Kotlin/Compose，2655 文件，功能超集 |
| **RikkaHub** | 2.4.1 | 2026-07-08 | rikkahub/rikkahub | Kotlin/Compose，10 模块，DB v24 |
| **Kelivo** | v1.1.17 | 2026-06-18 | Chevey339/kelivo | Flutter 跨平台（安卓/iOS/鸿蒙/桌面）|
| **Hermes** | v20250504 | 2025-05-04 | SelectXn00b/HermesApp | Operit 外壳 + Hermes Agent Loop 内核 |
| **橘瓣 OrangeChat** | master | 2026-07-10 | sue1231513/orangechat | RikkaHub fork + QuickJS 插件 + 陪伴层 |
| **Arix（我们）** | — | — | E:\OnyxAI | Kotlin/Compose，手表优先，语音+陪伴 |

---

## ⚠️ 先纠正认知：Arix 已经具备的（不是缺口）
源码自审确认，以下常被误判为缺口的功能**我们已有**：
- **可执行的 MCP 客户端**（`McpTool.kt`，`mcp_*` 动态工具，JSON-RPC over HTTP）
- **行内 + 块级 LaTeX**（JLaTeXMath）
- **记忆图谱可视化**（力导向，2026-07）
- **每功能模型绑定**（purpose: chat/reasoning/vision/title/tts/stt）
- **语音唤醒词**（自定义录入，五家竞品里只有 Operit 也有）+ 本地离线 STT/TTS
- **五级权限闸门** + ALLOW/ASK/FORBID
- **交互状态层/状态卡**（实验，Arix 独有）
- 角色卡 + AI 生成 + Waifu + 世界书 + SillyTavern V1/V2 导入
- Markdown（表格/代码块/图片/任务列表/footnote/details/内联HTML）+ 玻璃质感 UI

---

## 🎯 真正的缺口（按价值/工作量排序）

### 🔴 A档 · 该补、不在路线图、竞品普遍有
| 功能 | 谁有 | 说明 |
|---|---|---|
| **真·JS 插件运行时** | Operit/Rikka/Kelivo*/橘瓣/Hermes | 我们 `OperitCompatTool`/`code_runner` 是**占位**，插件跑不起来。橘瓣/Rikka/Operit 都用 QuickJS 真执行。补它=解锁整个插件生态 |
| **Mermaid 流程图** | Operit/Rikka/Kelivo/橘瓣 | MarkdownText 加一块 |
| **代码语法高亮** | 全部 | 我们只有代码块无高亮 |
| **AI 翻译消息** | Rikka/Kelivo/橘瓣 | 便宜好评，一个长按项 + prompt |
| **QR 配置导出/导入** | Rikka/Kelivo/橘瓣 | 便宜，扫码即用 |
| **对话文件夹分组** | Operit/Rikka/Kelivo/橘瓣 | 便宜，我们对话平铺 |
| **向量/语义记忆（HNSW）** | 橘瓣(HNSW)/Hermes(全息HRR) | 大但战略相关，我们只有关键词+标签 |
| **消息分支/变体** | Operit/Rikka/Kelivo/橘瓣 | 需会话树数据模型 |
| **PDF/DOCX 解析入模** | Rikka/Kelivo/橘瓣/Operit | 我们仅附文件不解析 |
| **WebDAV/S3 备份** | Rikka/Kelivo/橘瓣 | 中等 |
| **本地离线模型（llama.cpp/MNN）** | Operit/Hermes | 大 |
| **文生图/图片搜索** | Operit(7绘图包)/Rikka/Kelivo | 中等 |
| **HTML 块内联渲染** | Operit/Rikka/Kelivo/橘瓣 | 我们仅内联标签 |

### 🟡 B档 · 缺口，但已在路线图（方向对，见 TODO-V1.md）
密钥池 · 上下文压缩/自动摘要 · 键控搜索API(XSEARCHING) · 工作流构建器+定时任务(P1) · 悬浮窗助手(P2) · 无障碍/Shizuku/Root 操控(P2) · proot Linux 环境(termux P2) · 屏幕OCR(P2) · 内嵌浏览器/FFmpeg/SQL查看(P3) · 主题全覆盖+i18n(P1) · 健康信号(P1) · 定位/主动消息(P1/P3) · 跨平台消息网关(P3) · 云同步(P3)。

### 🟢 战略参考：**橘瓣**最贴合 Arix 定位
橘瓣 = RikkaHub + 生活陪伴层，跟我们「手表+陪伴+记忆/状态」高度重合，其技术选型可直接抄：
**QuickJS 沙箱插件 · HNSW 向量记忆 · Gadgetbridge 取健康 · 高德定位/附近搜索 · Supabase 云同步 · ProactiveMessage 主动消息 · 每日总结 · AI 朋友圈**。

---

# 附录：各 App 逐包 / 逐工具清单（源码级）

## Operit AI v1.12.0 —— 功能超集
**内置工具（`ToolRegistration.kt`，~150 个）** 按域分组：
- **文件**：read_file(_full/_part/_binary)、write_file(_binary)、create_file、edit_file、apply_file、delete/copy/move_file、make_directory、list_files、find_files、file_exists、file_info、zip/unzip_files、open/share/download_file、grep_code、grep_context
- **Shell/终端**：execute_shell、create/close_terminal_session、execute_in_terminal_session(_streaming)、input_in_terminal_session、get_terminal_session_screen、execute_hidden_terminal_command、execute_sandbox_script_direct、read/write_environment_variable
- **UI 自动化**：tap、click_element、long_press、swipe、press_key、set_input_text、capture_screenshot、get_page_info、run_ui_subagent、close_all_virtual_displays
- **系统/设备**：device_info、get/modify_system_setting、install/uninstall_app、list_installed_apps、start/stop_app、send_broadcast、execute_intent、get/send_notification(s)、get_app_usage_time、get_device_location、toast、sleep、trigger_tasker_event
- **蓝牙/BLE**（20+）：经典 connect/listen/accept/send/read/close + BLE discover_services/read/write/subscribe_characteristic 等
- **音乐**：music_play(_queue)/pause/resume/stop/seek/set_volume/status
- **HTTP/浏览器**：http_request、multipart_request、manage_cookies、visit_web + 23 个 Playwright 式 browser_* 工具
- **FFmpeg**：ffmpeg_execute/convert/info · **计算**：calculate(JS 引擎)
- **记忆**：create/update/delete/move/query_memory、get_memory_by_title、link_memories、query/update/delete_memory_link、update_user_preferences
- **对话编排**：create/switch/list/find/delete_chat、update_chat_title、get_chat_messages(_range)、send_message_to_ai(_streaming)、start/stop_chat_service、list_character_cards
- **模型自配置**：list/create/update/delete_model_config、test_model_config_connection、list/get/set_function_model_config
- **语音自配置**：get/set_speech_services_config、test_tts_playback
- **工作流**：get(_all)/create/update/patch/enable/disable/delete/trigger_workflow
- **包/MCP**：use_package、package_proxy、restart_mcp_with_logs

**扩展工具包（`assets/packages/`，31 个 JS 包）**：time、daily_life(18 工具含 wechat/qq 发消息、发朋友圈、定时任务、闪光灯、截屏、深色模式)、super_admin(terminal/shell)、system_tools(32 工具)、code_runner(JS/Node/Python/Ruby/Go/Rust/C/C++)、browser(20)、automatic_ui_base、automatic_ui_subagent(并行虚拟屏 agent)、extended_chat、extended_file_tools、extended_http_tools、extended_memory_tools、file_converter、ffmpeg、operit_editor(AI 写插件)、workflow、github(20 工具)、12306(火车票)、duckduckgo、tavily、google_search、zhipu_search、crossref(学术)、various_search(Bing/百度/搜狗/夸克 + 6 图片引擎)、7 个绘图包(openai/qwen/zhipu/minimax/nanobanana/siliconflow/xai_draw，含文生视频)

**LLM 供应商**：OpenAI(+Responses)、Claude、Gemini、Deepseek、Qwen、Doubao、Kimi、Mistral、Nvidia、OpenRouter、Ollama、llama.cpp、MNN、Mimo、NousPortal、FourRouter + **API 密钥池**(批量导入+测试)
**TTS**：System、OpenAI(+Realtime)、Doubao、MiniMax、Mimo、SiliconFlow、Vits、Http 模板 · **STT/唤醒**：OpenAI、Deepgram、Sherpa、SherpaMnn、Silero VAD、PersonalWake(自定义唤醒词)
**导航**：AI Chat / Shizuku Commands / Assistant Config / Settings / Tool Permissions / User Preferences(Guide+Settings) / Chat History / Packages / Memory Base / Terminal / Toolbox / Workflow / Token Config / About / Help
**工具箱**：文件管理器 · FFmpeg · Shell Executor · SQL 查看器 · TTS/STT · Logcat · UI 调试器 · 进程限制解除 · HTML 打包 · 权限管理 · AutoGLM(一键) · 工具测试器
**悬浮窗模式**：WINDOW / BALL / VOICE_BALL / FULLSCREEN(波形) / RESULT_DISPLAY / SCREEN_OCR(圈选)
**记忆**：力导向 GraphVisualizer + 文件夹 + 文档导入 + RAG 检索模拟/测试 + 类型化关联边
**工作流**：可视化节点画布 + cron/定时(ScheduleConfig) + WorkManager 后台 + 语音触发
**浏览器**：标签/历史/书签/下载 + 油猴用户脚本引擎
**权限五级**：standard/accessibility/debugger(Shizuku)/admin/root
**插件**：UnifiedMarket(浏览/装/发布) + MCP(uvx/npx/远程) + Skill + ToolPkg(QuickJS + Compose-DSL UI + 5 种 hook + AI 供应商注册 + 桌面 widget)
**其它**：Web→APK 打包 · 角色卡 Tavern PNG/JSON + 彩色 QR · Live2D/MMD/FBX 虚拟形象 · 群聊编排 · Claude 1h 缓存 · i18n 7 语言

## RikkaHub 2.4.1 —— 聊天客户端标杆
**47 条路由**（RouteActivity.kt）：Chat/History/Favorite/MessageSearch、ShareHandler、Assistant(+8 子屏)、Translator、ImageGen、Setting(+18 子屏)、Backup、Extensions(Prompts/Skills/QuickMessages/Workspaces)、Workspaces(+Terminal)、Stats、WebView/Debug/Log
**21 服务商预设**：RikkaHub、OpenAI、Gemini、AiHubMix、硅基流动、DeepSeek、OpenRouter、Vercel AI Gateway、TokenPony、阿里百炼、火山Ark、Moonshot、智谱、StepFun、302.AI、腾讯Hunyuan、xAI、随想AI、MiniMax、MIMO、AckAI；**LRU 密钥轮换**
**模型角色**（各绑不同模型）：Chat / Fast / Compress / OCR / Title / Translation / Chat-Suggestion
**18 搜索源**：Bing、智谱、Tavily、Exa、SearXNG、LinkUp、Brave、Metaso、Ollama、Perplexity、Firecrawl、Jina、Bocha、RikkaHub、Grok、Tinyfish、Serper、自定义 JS
**11 TTS**：System、OpenAI、Gemini、MiniMax、Qwen、Groq、xAI、MiMo(声调词)、ElevenLabs、StepFun、Fish Audio · **5 ASR**：OpenAI、DashScope、火山、MiMo、StepFun
**助手 34 字段**：含 systemPrompt、chatModelId、regexes(正则替换)、presetMessages(few-shot)、lorebookIds、modeInjectionIds、enableMemory/useGlobalMemory、mcpServers、localTools、workspaceId、background 等
**提示词变量**：{char}/日期/时间/模型名/模型id/昵称/设备信息/系统版本/时区/locale/电量
**消息交互**：重生成/编辑/删除/复制/收藏/翻译(内联)/导出图片/导出MD/**分支fork**/朗读/引用/审批/跳转/SVG+HTML 预览
**输入附件**：图库/相机/音频/视频/文件/PDF/DOCX/**EPUB**/裁剪/HEIC/GIF/OCR
**渲染**：MD + 代码高亮(200+语言,行号) + LaTeX + 表格(卡片+CSV) + **Mermaid** + **化学式** + SVG 预览 + HTML
**MCP**：SSE/HTTP + OAuth + 每工具审批 + 每助手绑定 + JSON 导入
**workspace**：proot Linux + 内置终端 + 文件工具(各带审批) + CWD 绑定助手
**web 模块**：内嵌 web 服务器(JWT/端口/局域网/mDNS) + web 端文件夹管理
**本地工具**：Ask User、剪贴板、QuickJS、时间、TTS、日历读写、屏幕时间、文生图、时间提醒
**记忆**：每助手/全局共享 + AI 增删改 + 近期对话引用
**技能**：SKILL.md + GitHub URL 导入(支持子目录) + ZIP/MD
**Lorebook**：关键词/正则/大小写/常驻/注入位(系统前后/顶/最新前/深度)/注入角色/扫描深度/优先级
**文生图**：独立屏 + 画廊 + 比例 + 自定义尺寸 + 生成后编辑(保留参考图)
**主题**：动态色 + AMOLED纯黑 + 5 预设(秋黄/中性黑/海蓝/樱粉/草绿) + 自定义(HSL) + 导入导出 + 自定义字体文件 + 气泡透明度 + 渐变背景
**数据**：JSON 导出/导入 + WebDAV + S3 + **从 Chatbox/Cherry Studio 导入** + 对话文件夹 + QR 配置
**统计**：消息/对话/token/启动次数 + **日历热力图**
**i18n**：英/简中/繁中

## Kelivo v1.1.17 —— Flutter 跨平台，功能极全
**平台**：安卓/iOS/鸿蒙/Windows/macOS/Linux 单代码库
**导航**：聊天+左抽屉(助手+话题，双模式搜索) / Translate / Storage / Global Search / Settings；桌面导航栏 + 系统托盘 + 临时对话
**13 服务商**：OpenAI、SiliconFlow、Gemini、OpenRouter、KelivoIN、Tensdaq、DeepSeek、AIhubmix、阿里云、智谱、Claude、Grok、字节 + Vertex AI(location/project/service-account)
**密钥池**：4 策略(轮询/优先级/最少使用/随机) + 每 key 别名/优先级/状态 + 准确率统计 + 批量删错误 key
**16 搜索源**：Bing、DuckDuckGo、Tavily、Exa、智谱、SearXNG、LinkUp、Brave、Metaso、Ollama、Jina、Perplexity、Bocha、Serper、Querit、Grok
**9 TTS**：System、OpenAI、Gemini、MiniMax、Qwen、Groq、xAI、ElevenLabs、Mimo + **悬浮 TTS 播放器**（无 STT/无唤醒）
**模型内置工具**：Gemini(Google搜索/URL Context/代码执行/YouTube)、OpenAI Responses(代码解释器/文生图)、Claude(动态 web_search)
**推理预算**：Off/Auto/Light/Medium/Heavy/Extreme/Maximum + 自定义数值
**消息交互**：编辑(可加附件)/重生成(保留后续可选)/**版本切换(删本版本/删全部版本)**/**分支**/多选批量删/翻译/朗读/导出(MD/TXT/图片)/**5 模式跳转按钮**/小地图/追问建议
**助手编辑**：Basic/Prompts/MCP/Quick Phrase/Custom/**正则替换**/Local Tools/Memory 标签页；11 提示词变量；预设对话；头像(图/emoji/URL/**QQ导入**/**LobeHub**)；每助手聊天背景
**世界书**：优先级/关键词/正则/大小写/常驻/扫描深度 + 5 种注入位 + 注入角色
**MCP**：STDIO(桌面,.bat)/SSE/HTTP/内置 + 每服务器审批 + 每会话每助手绑定 + 内置工具(Fetch/Calculator) + 本地工具(时间/剪贴板/TTS/Ask User/计算器)
**渲染**：MD + 代码高亮(预览/保存/折叠/换行) + LaTeX(块+行内) + **Mermaid**(导PNG/全屏) + 表格(CSV/存图) + HTML WebView + 图片查看器(缩放/旋转/翻转)
**输入**：相机/图库/文件/PDF/DOCX/音频 + 拖拽上传 + 图片模式 + **图片 OCR** + 裁剪 + **排队发送** + 学习模式注入 + 上下文压缩
**主题**：动态色 + 纯背景 + 调色板 + 玻璃/纯色气泡 + 输入透明度 + **App字体+代码字体(Google Fonts)** + 大量显示开关 + **粒度化触感** + 桌面快捷键
**数据**：WebDAV + S3(User-Agent) + 本地备份 + **从 RikkaHub/Cherry/Chatbox 导入** + 智能合并/覆盖 + **备份提醒** + QR 配置 + 存储空间明细
**统计**：范围/热力图/token/助手/话题排行 · **请求日志查看器** · **网络代理(HTTP/SOCKS5)**
**后台**：安卓前台服务 / iOS Live Activities(灵动岛)
**i18n**：英/简中/繁中/日/韩/法/德/意/西（9 语言）

## Hermes v20250504 —— Operit 外壳 + Agent Loop 内核
> ⚠️ 是 **Operit 的 reskin**，共用 Operit 的 137 工具外壳；下面只列 Hermes 内核增量。
**内核工具（~42，`Toolsets.kt`）**：web_search/web_extract、terminal、process、read/write/patch/search_files、vision_analyze、image_generate、skills_list/view/manage、11 个 browser_*(含 CDP)、text_to_speech、todo、memory、session_search、clarify、**execute_code(Python 调工具)**、**delegate_task(子agent)**、cronjob、send_message、**mixture_of_agents**、Home Assistant 4 工具(ha_*)、discord_server、5 个 feishu_* + **RL 训练 10 工具(rl_*)**
**~20 消息网关平台**（gateway/platforms）：飞书、Discord、Telegram、Slack、WhatsApp、Signal、Matrix、Mattermost、Email、SMS、钉钉、微信、企业微信、QQ Bot、Home Assistant、BlueBubbles(iMessage)、Webhook、通用 API server；每频道模型 + SLOT 会话隔离 + 配对QR
**模型 adapter**：Anthropic、Bedrock(AWS)、Codex Responses、Gemini Native/CloudCode、Google Code Assist + 10 家模型 tool-call parser(DeepSeek/GLM/Kimi/Llama/Longcat/Mistral/Qwen/Qwen3-Coder)
**本地推理**：llama.cpp、MNN、QuickJS + MnnModelDownload
**记忆**：全息(HRR) + Honcho 两套 provider + 用户偏好档案
**技能录制器**：录制手动 UI 操作 → AI 生成可执行技能(保留元素 text/resourceId/className)
**ClawHub 市场** + **内置 MCP Server(8399 端口，暴露无障碍+截屏给 Claude Desktop/Cursor)**
**内置 JS 包(34)/示例技能(11)**：含 apktool、deepsearching、linux_ssh、windows_control、remote_operit 等
**i18n**：中/英/西/印尼/马来/葡（6）

## 橘瓣 OrangeChat master —— RikkaHub fork + 陪伴层
> RikkaHub 基础功能全继承；下面是**增量**。
**内置 AI 工具（`LocalTools.kt` + `data/ai/tools/` 30+ 文件）**：eval_javascript(QuickJS)、get_time_info、clipboard_tool、text_to_speech、ask_user、**request_voice_call(AI发起通话)**、web_fetch、list_zip_contents、AlarmTool、AppLauncherTool、**AppUsageTool**、BatteryTool、BrightnessTool、**CalendarTool**、CameraTool、**ExploreNearbyTool(高德附近)**、**GadgetbridgeTool(健康)**、MediaScannerTool、MemoryTools、MusicTool、NotificationPostTool、SearchTools、SetWallpaperTool、ShareTool、SkillsTools、**SmsTool(读+发)**、StorageInfoTool、SystemIntentTools、SystemTools、TelephonyInfoTool、TorchTool、VibrateTool、VolumeTool、WakeScreenTool、WifiInfoTool、WorkspaceTools、ZipFilesTool
**后端服务（22 类）**：ChatService、ConversationSession、**KeepAliveService(保活)**、VoiceCallService、WebServerService、**AmapService/LocationService(定位)**、**AppUsageService**、CameraService、DeviceEventTrackingService、**NotificationListenerService(读通知)**、**GadgetbridgeService(健康)**、GatewayPollService、**MemoryBankService(向量记忆)**、ExternalMemoryService、**DailySummaryService/DiarySummaryService(每日总结/日记)**、**ProactiveMessageService/Worker(主动消息)**、SupabaseService/SupabaseSyncService(云同步)
**QuickJS 插件框架**：沙箱 host API(fetch/dataStore/musicPlayer/memoryBankBridge) + manifest(tools/config/customPage/promptTemplate/hooks/permissions) + WebView 自定义页
**内置插件(7)**：天气(wttr.in)、随机吃什么、共享阅读、**AI 朋友圈 Moments(Supabase)**、Supabase 记忆、净化备份、插件指南
**向量记忆**：**HNSW 索引** + Memory Bank + Supabase 云同步 + jieba 中文分词 FTS
**陪伴层**：定位/附近搜索(高德) · app 使用统计+轨迹 · 通知读取 · 设备事件追踪 · Gadgetbridge 健康(步数/心率/睡眠) · 主动消息(吃饭/睡觉/情感触发) · AI 发起语音通话
**数据同步**：WebDAV + S3 + Supabase + 从 Chatbox 导入 + 净化备份
**外观**：头像框 + 气泡透明度 + 思维链样式 + 输入背景 + 字体包导入

---

## Arix 现状（`E:\OnyxAI` 源码，作对照基准）
**28 个内置包**（`PackageManager.kt`）：memory、web_search、weather、daily_life(device_status/set_reminder/set_alarm/send_sms/make_phone_call/take_photo)、rag、fetch、http_tools、file_tools(read/write/list/delete)、time_utils、calculator、permission、plugin_manager、shell、touch、social_share、ai_guard、brightness、volume、app_launch、notification、clipboard、tts、import_export、operit_compat、operit_market、plugin_creator、worldbook、code_runner、file_converter
**~35 工具**：见 daily_life/file/device/搜索/记忆/tts/shell/touch/wake/mcp_* 等。**MCP 工具能执行；`code_runner`/`file_converter`/OperitCompat JS 为占位/半成品；`key_hook` 是空壳（需无障碍）**
**24 页**：chat / conversations / config(模型) / stt / tts / cards(角色卡) / settings / about / memory / packages / operit(云市场) / wake / permissions / plugins(插件制作) / import / terminal / monitor / touch / crash / settings_hub / worldtree(世界书) / files / projects(占位「开发中」)
**服务商预设 ~40 家**（`ApiProviders.kt`，5 组含免费无Key/国内/国外/本地）
**搜索引擎 9 个**：Bing/百度/搜狗/DuckDuckGo/Google/Scholar/Crossref/智谱(键控) + 天气(met.no) + fetch(robots)
**语音**：自定义唤醒词(VAD+KWS+录入) + 本地 sherpa STT(多语言下载) + 云 STT(硅基/Groq/Whisper) + TTS(neural vits/cloud/edge/system)
**记忆**：title/content/importance/tags/type/characterCardId/pinned/relatedIds(轻量图谱) + tag 交叉表 + 自动压缩 + 自动抽取 + 力导向图可视化
**主题**：ThemeConfig 运行时引擎(colorSource/darkMode/font/shape/component/motion/blur) + 莫奈动态色 + 玻璃质感 + 每页背景
**权限**：五级 AndroidPermissionLevel + ALLOW/ASK/FORBID（但 Shizuku/root/无障碍执行未接，shell/touch 仅 app 身份）
