# Arix 工具清单 × 竞品差集调研 · 2026-07-30

> 纯调研，未改任何代码。方法：
> ① 自己工具清单——直接读 `app/src/main/kotlin/com/arix/tool/*.kt` 源码 grep `override val name` + `class … : Tool`，
>   再核对 `PackageManager.kt` 的 `createBuiltInPackages()`（真正决定哪些工具会注册进工具表、模型摸得到）。
> ② 竞品——优先用本机已 clone 的源码（`F:\CompetitorRepos\{Operit,rikkahub,kelivo,orangechat,HermesApp}`），
>   直接 grep 工具注册处；其次用三天前的 `RESEARCH-COMPETITIVE-2026-07-27.md`（源码级，最可信）；
>   `COMPETITIVE.md`/`GAP.md` 只作为**未核实**背景，不当结论用。
> 每条差集都标了可信度：**[源码已核实]** 本轮亲自读过代码 / **[文档未核实]** 转引旧文档自述 / **[网络资料]**。

---

## 零、一个先纠正的数字

用户预估「约 87 个工具」。实测：源码里 `: Tool` 实现约 **86 个类**，但 `PackageManager.createBuiltInPackages()`
里真正注册进工具表、模型能看到并调用的只有 **约 77 个**（+ MCP 网关的 `search_tool`/`use_tool` 2 个元工具 = 79）。

差额去了哪：
- **8 个是设计内的内部委托类**：`FileWriteTool`/`FileEditTool`/`FileListTool`/`FileDeleteTool`/`FileMoveTool`/
  `FileCopyTool`/`MakeDirTool`/`FileExistsTool` 源码都在 `FileTools.kt`，但只被 `FileReadTool`（file_read）
  和 `FileOpTool`（file_op）用 `by lazy` 内部持有、自己不注册——这正是「文件 10 工具合并成 2 个」的实现方式
  （`PackageManager.kt:124-127` 注释原话），不是缺陷。
- **其余几个是没登记的遗留代码**：`FileArchiveTool`（file_archive，功能已被 file_op 的 zip/unzip 取代）、
  `XSearchTool`（deep_search，已并进 `web_search` 的 `depth=deep`）等。这条与三天前
  `RESEARCH-COMPETITIVE-2026-07-27.md` ⑮ 节「13 个 Tool 实现没被任何包登记」互相印证。[源码已核实]

下面「现有工具全表」只列**真正注册、模型能调到**的 77 个 + 2 个元工具。

---

## 一、Arix 现有工具全表（按 `PackageManager` 的 category 分组）

### 核心
| 工具名 | 能力（一句话） | action/关键参数 | 文件 |
|---|---|---|---|
| `memory` | 长期记忆：记用户信息+干活知识，search 检索 | type 分类 | `MemoryTool.kt` |

### 信息 / 网络
| 工具名 | 能力 | action/关键参数 | 文件 |
|---|---|---|---|
| `web_search` | 联网搜索；默认必应+百度+搜狗，可选 14 键控源/AnySearch/Perplexica | depth=fast/deep，type=text/image/video，site= | `SearchTool.kt`（后端引擎见 `search/SearchEngine.kt`） |
| `rag` | 本地知识库，按意思检索片段 | search/add/list/delete | `RagTool.kt` |
| `local_search` | 本机模糊搜索：对话/记忆/文件/日记/提醒/角色卡/世界书/功能包 | scope | `LocalSearchTool.kt` |
| `open_page`（包名 fetch） | 取网页正文，WebView 内核防 403 | format=auto/json/raw，mode=images/video | `OpenPageTool.kt` |
| `browser` | AI 驱动浏览器，连续多步操作网页+反爬伪装 | open + 编号元素 click/type/scroll/back | `BrowserTool.kt` |
| `http_request` | 通用 REST API 调用/下载文件 | method/headers/body/cookies/upload/save_to | `FileTools.kt` |
| `read_document` | PDF/DOCX/PPTX/XLSX/EPUB 等转文字 | into_memory 可直接入库 | `DocReadTool.kt` |
| `image_ocr` | 图片文字识别（视觉模型） | — | `ImageOcrTool.kt` |
| `github` | GitHub 查询 | search_repos/repo/issues/releases/trending/user | `NetworkTools.kt` |
| `net_diag` | 网络诊断 | ping/dns/http/port | `NetworkTools.kt` |
| `site_cookies` | 读取已登录站点 cookie（**只读**） | domain | `NetworkTools.kt` |
| `net_log` | 查最近网络请求日志 | — | `NetLogTool.kt` |
| `web_server` | 起本机 HTTP 静态服务器分享网页 | start/stop, lan | `WebServerTool.kt` |
| `message_gateway` | 转发消息出去：share(App)/webhook(机器人) | share/webhook/add_webhook/list_webhooks/remove_webhook | `MessageGatewayTool.kt` |

### 生活
| 工具名 | 能力 | action/关键参数 | 文件 |
|---|---|---|---|
| `get_weather` | 天气+2小时分钟级降雨 | 不传参数=自动定位 | `WeatherTool.kt` |
| `map` | 地图导航/搜地点/附近/算路线 | navigate/search/nearby/route | `LifeTools.kt` |
| `train_tickets` | 12306 余票查询 | from/to/date | `LifeTools.kt` |
| `life_query` | 节假日/油价/汇率/垃圾分类/快递/IP归属/一言/星座/解梦/手机归属/热榜 | type=… | `LifeQueryTool.kt` |

### 陪伴 / 系统
| 工具名 | 能力 | action/关键参数 | 文件 |
|---|---|---|---|
| `diary` | AI 写/念每日日记 | write/read/list | `DiaryTool.kt` |
| `health_measure` | 现场测心率/血氧（启动传感器） | type=heart_rate/blood_oxygen | `DailyLifeTools.kt` |
| `notification` | 手机通知一条龙：读/回复/按按钮/点开/清除/自己发 | list/reply/press/open/dismiss/dismiss_all/send | `NotificationTool.kt` |
| `app_usage` | 各 App 使用时长/屏幕时间 | — | `UsageStatsTool.kt` |
| `schedule_task` | 排定时提醒/通知，一次性或周期 | set/list/cancel | `ScheduleTool.kt` |

### 设备控制
| 工具名 | 能力 | action/关键参数 | 文件 |
|---|---|---|---|
| `device_status` | 电量/内存/存储/机型/位置/健康摘要 | — | `DailyLifeTools.kt` |
| `set_reminder` | 创建/查看/取消提醒 | list/cancel，mode=calendar | `DailyLifeTools.kt` |
| `set_alarm` | 设系统闹钟 | — | `DailyLifeTools.kt` |
| `calendar` | 日程查看/新建 | list/add | `CalendarTool.kt` |
| `contacts` | 按名字查联系人电话 | — | `ContactsTool.kt` |
| `send_sms` | 调起短信编辑器（不自动发） | — | `DailyLifeTools.kt` |
| `read_sms` | 读收件箱短信（验证码等） | — | `DailyLifeTools.kt` |
| `make_phone_call` | 打电话 | — | `DailyLifeTools.kt` |
| `take_photo` | 打开相机拍照 | — | `DailyLifeTools.kt` |
| `music_control` | 控制媒体播放/音量、按歌名唤起播放 | now_playing/play_pause/…/play_song | `MusicControlTool.kt` |
| `song_profile` | 读一首歌的曲目信息+完整歌词 | — | `SongProfileTool.kt` |
| `brightness` | 屏幕亮度，含自动模式 | — | `DeviceTools.kt` |
| `volume` | 媒体/铃声/闹钟音量 | — | `DeviceTools.kt` |
| `torch` | 手电筒 | on/off/toggle | `DeviceTools.kt` |
| `wifi_info` | 当前 WiFi 连接信息（只读） | — | `DeviceTools.kt` |
| `app_launch` | 打开/搜索/装/卸/强停应用 | launch/search/open_activity/install/uninstall/stop | `DeviceTools.kt` |
| `clipboard` | 读写剪贴板 | — | `DeviceTools.kt` |
| `settings` | 读/改安卓系统设置 | get/put，namespace=system/global/secure | `SettingsTool.kt` |
| `send_intent` | 发任意 Intent/系统广播（高风险，默认关） | — | `IntentTool.kt` |
| `bluetooth` | 扫描/连接 BLE+经典蓝牙(SPP)，读写特征值 | scan/paired/connect/disconnect/services/… | `BleTool.kt` |
| `home_assistant` | 智能家居：查状态/调用服务 | list/state/call/config | `HomeAssistantTool.kt` |

### 工具（文件/代码/UI自动化）
| 工具名 | 能力 | action/关键参数 | 文件 |
|---|---|---|---|
| `file_read` | 工作区只读：读文件/列目录/查存在 | read(默认)/list/exists | `FileTools.kt` |
| `file_op` | 工作区改动：写/改/删/移/复制/建目录/压缩解压 | write/edit/delete/move/copy/mkdir/zip/unzip | `FileTools.kt` |
| `export_csv` | 导出 CSV 到下载目录 | — | `CsvExportTool.kt` |
| `file_converter` | 文本/图片格式互转，生成 PDF/Word | to= | `EnhancedTools.kt` |
| `image_crop` | 裁剪图片 | unit=pixel/ratio | `ImageCropTool.kt` |
| `generate_image` | 文生图（OpenAI兼容/通义万相/CogView） | provider | `FileTools.kt` |
| `code_runner` | 真实执行 python/js/bash/ruby/php | language | `EnhancedTools.kt` |
| `shell` | 执行 Shell 命令，多级权限 | privilege=app/shizuku/root/auto/probe | `ShellTool.kt` |
| `linux_exec` | 独立终端 App 里的完整 Linux(proot)环境 | run(默认)/start/input/read/kill/list | `LocalLinuxTool.kt` |
| `ui_control` | 读屏幕+代点/滑/输入/返回主页（无障碍） | dump/click_text/tap/long_press/swipe/set_text/back/home/recents/notifications | `UiControlTool.kt` |
| `skill`（技能录制） | 录制界面操作序列，一键回放 | record_start/record_stop/record_cancel/list/show/play/delete | `SkillRecorder.kt` |
| `screen_ocr` | 截屏识字或存图 | ocr(默认)/save | `ScreenOcrTool.kt` |
| `manage_chats` | 跨对话搜索/列出/读/改名/删除/归档/置顶 | search/list/read/rename/delete/archive/pin | `LifeTools.kt` |
| `local_infer` | 手机本地 GGUF 模型离线推理 | — | `NativeInference.kt` |
| `social_share` | 微信/QQ/朋友圈分享文本 | — | `KeySocialTool.kt` |

### 系统 / 高危 / 元任务
| 工具名 | 能力 | action/关键参数 | 文件 |
|---|---|---|---|
| `request_permission` | 发起权限/功能包授权申请 | permission="list"/"package:<id>" | `PermissionTool.kt` |
| `ask_user` | 拿不准就先问用户 | — | `AskUserTool.kt` |
| `todo` | 多步任务清单，边做边更新 | — | `TodoTool.kt` |
| `propose_setting_change` | 向用户申请改 app 设置（只提交申请） | target 白名单 | `ProposeSettingTool.kt` |
| `create_agent` | 创建最多 12 个并行子 agent 处理子任务 | — | `SubAgentTool.kt` |
| `workflow` | 把一串工具调用存成可复用流程 | create/run/list/show/delete | `WorkflowTool.kt` |
| `ai_guard` | AI 操作风险等级管控 | — | `AIGuardTool.kt` |
| `time` | 当前时间/日期/时区 | — | `BasicTools.kt` |
| `calculator` | 数学表达式计算 | — | `BasicTools.kt` |
| `plugin_creator` | 创建 skill/沙盒包/MCP 配置文件模板 | template=skill/sandbox/mcp | `OperitCompat.kt` |
| `worldbook` | 世界设定/角色背景管理 | — | `EnhancedTools.kt` |
| `skill_read` | 读已装技能正文（索引常驻、正文按需） | — | `SkillReadTool.kt` |
| `mcp_server` | 把本机工具通过内置 MCP Server 暴露给外部客户端 | start/stop/status | `McpServer.kt` |
| `tts` | 文字转语音 | — | `TtsTool.kt` |

### 元工具（MCP 网关，单列）
| 工具名 | 能力 | 文件 |
|---|---|---|
| `search_tool` | 检索本机接入的第三方 MCP 工具目录（数量多、不常驻工具表） | `McpGateway.kt` |
| `use_tool` | 调用一个 search_tool 查到的第三方 MCP 工具 | `McpGateway.kt` |

另有两类**动态实例化、非固定名字**的工具通道，不计入上面 77 个：
- `McpTool` / `StdioMcpTool`：连上第三方 MCP server（HTTP 与 stdio 两条通道）后按对方报的工具名动态生成，
  注册名强制加 `mcp_` 前缀防顶掉内置工具同名。
- `OperitCompatTool`：把 Operit 传统脚本包（JS）的一个导出函数代理成 Arix 工具，同样是装包后动态生成。

---

## 二、差集表：竞品有、我们没有（或已被覆盖）

竞品源码来源：`F:\CompetitorRepos\{Operit, rikkahub, kelivo, orangechat}`（本轮亲自 grep 工具注册处）
+ `RESEARCH-COMPETITIVE-2026-07-27.md`（三天前源码级调研）。orangechat 是 RikkaHub 的 fork，
工具面比 RikkaHub 本体多出一大截（约 50+ 个原生 Kotlin 工具），本轮把它当"设备控制类工具"的最大参照样本。

| # | 能力 | 谁有 | 我们真的没有 / 已被覆盖 | 建议 | 可信度 |
|---|---|---|---|---|---|
| 1 | **SSH 远程主机**：存主机、连接、执行命令、SFTP 上传/下载、host key 管理 | orangechat（`SshTool`/`SshHostsTool`/`SshSftpTool`，共 7 个工具名：`save_ssh_host`/`list_ssh_hosts`/`delete_ssh_host`/`ssh_forget_host_key`/`ssh_exec_saved`/`ssh_exec`/`ssh_upload`/`ssh_download`） | **真没有**。`shell`/`linux_exec` 只管本机/proot 容器，不连远程主机 | 这是一个新能力域（远程主机凭据管理+协议客户端），不适合塞进 shell/linux_exec 的参数里。若要做，建议**新开一个 `ssh` 工具**（action=connect/exec/upload/download/list_hosts/forget_host），而不是塞进现有工具 | [源码已核实] |
| 2 | **事件触发式自动化**：cron / WiFi连断 / 蓝牙设备 / 耳机插拔 / 电源 / 电量阈值 / 地理围栏 / App启动关闭 / App前台时长 / 通知内容(含正则) / 开机 / 亮灭屏 → 触发一段流程 | orangechat 工作流引擎 20+ 触发器（`RESEARCH-COMPETITIVE-2026-07-27.md` ⑤ 节，未在本轮重读源码）；Operit `create_workflow`+`trigger_workflow`+`enable_workflow`/`disable_workflow`（本轮读到工具名，触发源细节未读） | **真没有**。我们的 `workflow` 只能被模型手动 `action=run` 调用；`schedule_task` 只有 delaySeconds/atEpochMillis/repeatSeconds 纯计时器，**没有任何设备事件触发**（源码确认：`ScheduleTool.kt` 全文档搜不到 cron/trigger 字样） | 建议**不新开工具**：给现有 `workflow` 加一个 `trigger` 参数（cron 表达式 或 事件类型枚举），`action=create` 时可选传，达成即自动 `run`。符合"合并进已有工具"原则，且这是本次差集里价值最大的一条——从"手动执行的宏"升级成"自动化规则引擎" | orangechat 部分[文档未核实]，Operit 工具名[源码已核实]/触发细节[未核实]，我方无触发器[源码已核实] |
| 3 | **AI 主动发起语音通话**（全双工、ASR常开、VAD、打断/barge-in，像真的打电话） | orangechat `request_voice_call` + `VoiceCallService.kt`（790行，本轮读到工具签名，服务体未重读，`RESEARCH-COMPETITIVE-2026-07-27.md` 已读过实现细节） | **真没有**。我们有 `tts`(单次朗读) 和聊天里的语音输入，但没有"AI 主动拨打、常开双向语音、可被打断"的通话模式 | 这不是一个工具参数能解决的——需要通话 UI + 常开 ASR/VAD + barge-in TTS，是新子系统级投入，不建议现在归为"加个 action"级别的活。列为差集但标注**大工程量** | [源码已核实]（工具签名）+ [文档未核实]（服务体细节转引三天前调研） |
| 4 | **设置壁纸** `set_wallpaper` | orangechat `SetWallpaperTool.kt` | 真没有 | 小，建议合并进设备控制簇里的一个已有工具（如与 `brightness`/`torch` 同类的"设备小工具"包再加一个工具，或干脆挂在 `app_launch` 旁边新增一个极小工具——两种都行，规模太小不值得单独立项） | [源码已核实] |
| 5 | **App 锁 / 专注模式**：设 PIN，锁定的 App 被打开时弹回桌面+PIN 遮罩（基于无障碍拦截，非系统级） | orangechat `AppLockTool.kt`（`app_lock`：set_pin/lock_app/unlock_app/list_locked_apps） | 真没有 | 这是一个独立的持续性防护功能（不是一次性动作），不适合并进 `app_usage`（只读统计）或 `ui_control`。若要做建议新开小工具，但这是数字健康/家长控制向能力，与语音助手核心场景关联不强——只排序不做淘汰判断，留给用户拍板优先级 | [源码已核实] |
| 6 | `show_toast`（弹一条系统 Toast 提示） | orangechat `ToastTool.kt` | 真没有（`notification` 的 send 是走通知栏，不是 Toast） | 小，建议合并进 `notification` 工具再加一个 action（如 `toast`），不新开工具 | [源码已核实] |
| 7 | `vibrate`（触发震动反馈） | orangechat `VibrateTool.kt` | 真没有 | 小，建议合并进设备控制簇（如 `clipboard`/`torch` 那批"设备小工具"包新增一个工具，或挂进 `device_status` 旁边） | [源码已核实] |
| 8 | `wake_screen`（点亮屏幕） | orangechat `WakeScreenTool.kt` | 真没有 | 小，同上，建议合并进设备控制簇 | [源码已核实] |
| 9 | `get_telephony_info`（SIM/运营商/信号强度等电话状态信息） | orangechat `TelephonyInfoTool.kt` | 真没有（`device_status` 目前是电量/内存/存储/机型/位置/健康，不含电话状态） | 小，建议合并进 `device_status` 的返回字段 | [源码已核实] |
| 10 | `list_zip_contents`（列 zip 内容，不解压） | orangechat（`LocalTools.kt:480`） | 真没有（`file_op` 只有 zip/unzip，没有"只看列表"） | 小，建议给 `file_op` 的 zip/unzip 家族加一个 `list` 子动作 | [源码已核实] |
| 11 | Cookie **写入/清除**（不只读） | Operit `manage_cookies`（`ToolRegistration.kt:1897`，本轮只读到工具名，未读实现确认是否支持写） | 不确定。我们的 `site_cookies` 描述明确写"读取"，未见写入/清除动作 | 若确认 Operit 真支持写，建议给 `site_cookies` 加 action=set/clear，不新开工具 | Operit 有此工具名[源码已核实]，其读写范围[未核实]；我方只读[源码已核实] |
| 12 | 跨渠道**入站**机器人：QQ 开放平台 WebSocket 私聊、微信 iLink 协议扫码登录长轮询接收消息 | 橘瓣(orangechat 前身之一) `RESEARCH-COMPETITIVE-2026-07-27.md` ⑤节（本轮未重新核实源码，orangechat 里没找到对应文件，可能在 IM 相关目录未检索到） | 真没有。我们的 `message_gateway` 只做**出站**（share/webhook），没有接收外部消息并触发 AI 响应的能力 | 这也是子系统级投入（持久连接+会话管理），不是加参数能解决的。列为差集但标**大工程量**，且价值判断留给用户（涉及是否要做"IM 机器人"这条产品线） | [文档未核实，转引三天前调研，本轮未在 orangechat 源码里重新定位到对应文件] |
| 13 | Gemini/OpenAI **供应商原生工具透传**（code_execution/url_context/youtube grounding，OpenAI code_interpreter/image_generation 走托管 API 而非本地实现） | kelivo `builtin_tools.dart`（本轮读过全文） | **不算缺口**——同等能力我们用自己的 `code_runner`/`open_page`/`generate_image` 本地实现已经覆盖，这只是"用供应商托管版本 vs 自己做"的路线差异，不是能力缺口。唯一新意是 **YouTube 视频内容问答**（Gemini grounding 专属，我们没有针对 YouTube 的专门抓取） | 不建议现在动；YouTube 视频问答如果要做，建议并进 `open_page` 或 `web_search` 的一个 mode，不新开工具 | [源码已核实] |

### 未列入差集、但值得记一笔的「已被现有工具覆盖」案例（防止误判为缺口）

- Operit 170 个原生工具里的**蓝牙全家桶**（`request_bluetooth_permission`/`get_bluetooth_state`/`scan_bluetooth_devices`/
  `bluetooth_connect`/`bluetooth_listen`/`bluetooth_accept`/`bluetooth_send`/`bluetooth_read`/`bluetooth_send_and_read`/
  `bluetooth_close`/`bluetooth_ble_connect`/`bluetooth_ble_discover_services`/`bluetooth_ble_read_characteristic`/
  `bluetooth_ble_write_characteristic`/… 共 13 个）——我们的 `bluetooth` 一个工具的 action 枚举全覆盖（scan/paired/connect/
  disconnect/services + BLE 读写订阅 + 经典 SPP 收发）。这正是"瑞士军刀"原则跑赢"170个原生工具"路线的实证。[源码已核实]
- Operit 的 `install_app`/`uninstall_app`/`list_installed_apps`/`start_app`/`stop_app`/`device_info`/`get_app_usage_time`/
  `execute_intent`/`send_broadcast`/`get_notifications`/`send_notification`/`modify_system_setting`/`get_system_setting`/
  `get_device_location` —— 分别被我们的 `app_launch`/`app_usage`/`send_intent`/`notification`/`settings`/`device_status`
  一一对应覆盖。[源码已核实]
- orangechat 的 UI 自动化家族（`find_node`/`click_node`/`set_text`/`global_action`/`take_screenshot`/`scroll`/`tap`/
  `long_press`/`read_window_tree`）—— 被我们的 `ui_control`（dump/click_text/tap/long_press/swipe/set_text/back/home/
  recents/notifications）一个工具覆盖。[源码已核实]
- orangechat 的 `eval_javascript`/`web_fetch`/`control_music`/`calendar_tool`/`search_web`/`scrape_web`/`explore_nearby`/
  `post_notification`/`memory_tool`/`get_wifi_info`/`get_volume`/`set_volume`/`get_brightness`/`set_brightness`/
  `get_battery_info`/`get_storage_info`/`set_torch`/`get_location`/`workspace_*` —— 全部有对应或被现有工具覆盖
  （code_runner/open_page/music_control/calendar/web_search/open_page/map/notification/memory/wifi_info/volume/
  brightness/device_status/torch/device_status/file_read+file_op）。[源码已核实]
- Operit 的 terminal session 系列（`create_terminal_session`/`execute_in_terminal_session`/`input_in_terminal_session`/…）
  —— 对应我们 `linux_exec` 的 start/input/read/kill/list。[源码已核实]

---

## 三、反向差集：我们有、竞品没有（真优势，别在优化时误砍）

| # | 能力 | 依据 | 可信度 |
|---|---|---|---|
| 1 | **MCP 双通道网关**（`search_tool`+`use_tool` 检索式访问第三方 MCP 工具，stdio+HTTP 双传输） | 三天前调研已确认这与 grok-build/Operit 的"工具面大了换检索目录"是独立收敛的同一设计，本轮四个竞品源码里都没见到同等的"网关+检索"模式（orangechat/rikkahub/kelivo 是白名单/挂角色卡式收敛，Operit 是 CLI 模式下暴露 search+proxy，思路类似但实现和我们不同） | [文档未核实，转引三天前调研] + 本轮未见反例[源码已核实] |
| 2 | **独立终端 App + proot 完整 Linux 环境**（`linux_exec`：bash/python3/apt/pip/git/coreutils 全生态，可装包） | Operit 的 terminal session 是在自己沙盒里跑命令，不是 proot 出一套完整 Linux 发行版用户态；orangechat/rikkahub/kelivo 都没有本地 Linux 环境这一层 | [源码已核实]（本轮读过三家工具清单均无此类） |
| 3 | **蓝牙统一成 1 个工具**（对比 Operit 13 个粒度化蓝牙工具） | 见上文"已覆盖案例"第一条，直接体现"瑞士军刀"原则的 token/幻觉面优势 | [源码已核实] |
| 4 | **健康数据三级源回落**（better health tracker ContentProvider → Gadgetbridge SQLite → Health Connect） | orangechat 只有 `GadgetbridgeTool` 单一数据源，没有三级回落链路（本轮读过其 `GadgetbridgeTool.kt` 命名与用途，未见分级策略） | [源码已核实，仅确认 orangechat 只有单一源；我方三级回落见项目既有记忆，本轮未重读 `DailyLifeTools.kt`/`HealthMeasureTool.kt` 实现细节，标注未在本轮复核] |
| 5 | **导入兼容 SillyTavern/TavernAI/世界书/Operit 格式/PNG 元数据**（`ImportConverters.kt`/`ImportExport.kt`） | 三天前调研明确这条"短板"已被推翻，本轮未见 orangechat/rikkahub/kelivo 有对应通用导入层（它们更多是"自己格式内迁移"，不是"吃进整个酒馆生态格式") | [文档未核实，转引三天前调研]；本轮未逐一核实四家的导入代码，标注未核实 |
| 6 | **子 agent 并行**（`create_agent`，最多 12 个并行、隔离上下文、汇总送达） | Operit 有多会话管理（`create_new_chat`/`switch_chat`/`send_message_to_ai`）但那是"同一用户手动切换/发消息给另一个会话"，不是"一次工具调用派生 N 个隔离子任务并行跑、自动汇总"；rikkahub/kelivo/orangechat 未见同类工具 | 本轮读到 Operit 工具名但未读语义细节确认差异是否成立，[未核实] |
| 7 | **默认单轮工具 token 成本低**（默认启用包约 4,563 token/轮，全量上限 9,373） | 三天前调研用统一口径量过：hermes 22,644、Operit 起步 2,111(激活包后 23,806)、rikkahub 默认 1 个工具 64 token、kelivo 默认 0 个。我们不是全场最省，但"精简度执行在描述长度上（中位59字符，全场最短）+ 默认暴露量克制"两条都成立 | [文档未核实，转引三天前调研，脚本 `tools/token_cost.py` 可复跑] |

---

## 四、总结口径说明（写给自己，别被后续引用当结论转述）

- 差集表第 1、2 条（SSH、事件触发自动化）是本轮**源码级新发现**、价值最高，第 2 条尤其符合"合并进现有工具"的项目原则。
- 第 3、12 条（语音通话、入站 IM 机器人）是**新能力域**，不是工具参数能解决的，需要先做工程量评估，不要直接排进
  "小活清单"。
- 第 4-10 条都是**小体量、可直接合并进现有工具的 action/参数**，性价比高，适合批量一次做掉。
- 第 11、13 条**证据不完整**，且第 13 条经核实后判定"不算真缺口"——已被本地实现覆盖，只是路线不同。
- 反向差集第 4、5、6 条依据不完整（本轮时间有限，部分是转引三天前调研或未逐项复核对方源码），
  标注了"未核实"的地方，下次要写进正式结论前应重新核实。
