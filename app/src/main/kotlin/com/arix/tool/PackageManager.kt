package com.arix.tool

import android.content.Context
import com.arix.app.MemoryManager

data class PackageDef(
    val id: String,
    val name: String,
    val description: String,
    val category: String,
    val tools: List<Tool>,
    val enabledByDefault: Boolean = false,
    val requiresPermissions: List<String> = emptyList()
)

object PackageManager {
    private val packages = mutableMapOf<String, PackageDef>()
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * 注册功能包。**工具一律进工具表，不管这个包开没开。**
     *
     * 原来是「包没启用 → 工具压根不 register」，后果是 AI 连自己有这个能力都不知道：
     * 让它回条微信，它看不见 notification 工具（notif_aware 默认关），于是跑去用 ui_control
     * 硬点屏幕——它不是选错了工具，是它眼里根本没有对的那个。
     * 现在全都进表，没启用的由 [ToolManager] 在描述里标注「未启用」、调用时挡下并告诉它怎么申请。
     */
    fun register(pkg: PackageDef) {
        packages[pkg.id] = pkg
        pkg.tools.forEach {
            // 内置装配走 registerBuiltin：把名字钉进保留表，第三方包之后顶不掉（见 ToolManager.register）
            ToolManager.registerBuiltin(it)
            ToolManager.bindPackage(it.name, pkg.id)
        }
    }

    fun enable(id: String) {
        packages[id] ?: return
        getPrefs().edit().putBoolean("pkg_$id", true).apply()
    }

    fun disable(id: String) {
        packages[id] ?: return
        getPrefs().edit().putBoolean("pkg_$id", false).apply()
    }

    /** AI 要用的工具所在包没开 → 发申请给用户（走 SettingProposalBus，用户在对话里点同意才生效）。 */
    fun proposeEnable(id: String, reason: String) {
        val pkg = packages[id] ?: return
        com.arix.app.SettingProposalBus.add(
            "pkg:$id", "启用功能包「${pkg.name}」", "true", isEnabled(id).toString(),
            reason.ifBlank { pkg.description })
    }

    fun isEnabled(id: String): Boolean {
        val pkg = packages[id] ?: return false
        val prefs = getPrefs()
        return if (prefs.contains("pkg_$id")) prefs.getBoolean("pkg_$id", false)
        else pkg.enabledByDefault
    }

    fun getAllPackages(): List<PackageDef> = packages.values.toList()

    fun getEnabledPackages(): List<PackageDef> = packages.values.filter { isEnabled(it.id) }

    private fun getPrefs() = appContext!!.getSharedPreferences("arix_packages", Context.MODE_PRIVATE)
}

// Built-in package definitions
//
// ⚠ 关于 `enabledByDefault = false`（2026-07-28 起 map/life_query/media/browser 四个改成默认关）：
// **这不是砍功能**。默认关的包，工具照样注册进工具表、名字照样出现在系统提示里，只是**不发 schema**；
// 模型要用时会 `request_permission` 申请，用户点一下就开。改的是"开箱默认值"，不是"有没有这个能力"。
// 理由是每轮固定成本：这四个合计约 864 token/轮，在手表这种小上下文里是每一轮都在付的钱。
// （量法：`python tools/token_cost.py default`。）
fun createBuiltInPackages(context: Context): List<PackageDef> = listOf(
    PackageDef("memory", "记忆系统", "AI自动记录和检索用户信息、偏好、重要事实。支持角色卡绑定。", "核心",
        listOf(MemoryTool(MemoryManager(context), appContext = context)), enabledByDefault = true),
    PackageDef("web_search", "网络搜索 / 深度研究", "联网搜索+多轮深度研究：默认必应+百度+搜狗；deep=true 多轮铺开上百来源并综合(带引用/置信度/看过的页面列表)；site= 限定站内；另可选 AnySearch/Perplexica 及 14 键控源(Tavily/Brave/... 设置里启用)。", "信息",
        listOf(SearchTool(context)), enabledByDefault = true),
    PackageDef("weather", "天气查询", "查询实时天气与预报（met.no + open-meteo，全球免key）。不传参数自动定位当前位置（GPS→网络IP兜底），也可传城市名或经纬度。", "生活",
        listOf(WeatherTool(context)), enabledByDefault = true),
    PackageDef("map", "地图导航", "打开地图App(高德优先)导航到目的地或搜地点，逐步导航由地图App接管，免key。", "生活",
        listOf(MapTool(context)), enabledByDefault = false),
    PackageDef("train", "火车票查询", "查 12306 火车票余票：出发/到达/日期，返回车次/时刻/历时/各席别余票。", "生活",
        listOf(TrainTicketTool()), enabledByDefault = true),
    PackageDef("life_query", "生活查询", "节假日/黄历、油价、汇率、垃圾分类、快递、IP归属、一言、星座、解梦、手机归属——一个工具多用；部分需 mxnzp 免费 key。", "生活",
        listOf(LifeQueryTool(context)), enabledByDefault = false),
    PackageDef("daily_life", "生活助手", "日历提醒/闹钟/短信/拨号/相机/设备状态。免特殊权限；「AI 直接执行操作」开则直接设好，关则调起系统界面由用户确认。", "设备控制",
        listOf(DeviceStatusTool(context), ReminderTool(context), AlarmTool(context), CalendarTool(context), ContactsTool(context), SmsTool(context), PhoneCallTool(context), TakePhotoTool(context)), enabledByDefault = true),
    // 现场测量：主动触发手表传感器测心率/血氧（区别于 device_status/健康注入读历史）。默认关：会真的启动设备传感器、且需 tracker 侧开关。
    PackageDef("health_measure", "现场健康测量", "让手表/手环当场测一次心率或血氧(现场启动传感器，不是读历史)。需装「better health tracker」并在其里开「传感器 API」、且正佩戴设备。", "陪伴",
        listOf(HealthMeasureTool(context)), enabledByDefault = false),
    PackageDef("media", "媒体控制", "控制当前在放的音乐：播放/暂停/切歌/停 + 媒体音量；song_profile 读一首歌的曲目信息+歌词让 AI 能就歌聊。免特殊权限。", "设备控制",
        listOf(MusicControlTool(context), SongProfileTool()), enabledByDefault = false),
    // id 保持 notif_aware 不动：它是 SharedPreferences 的 key(pkg_$id)，改了用户已开启的包会退回默认状态。
    PackageDef("notif_aware", "手机通知", "读手机通知(微信/短信/日程/快递…)，并直接回复、按下通知上的按钮、点开、清除；也能自己发一条通知。需通知使用权。", "陪伴",
        listOf(NotificationTool(context)), enabledByDefault = false),
    PackageDef("app_usage", "使用情况", "查手机各 App 使用时长/屏幕时间，AI 据此体贴提醒。需使用情况访问权限。", "陪伴",
        listOf(UsageStatsTool(context)), enabledByDefault = false),
    // 与 doc_read(into_memory=true) **同一个库**：都把文档切块存进长期记忆并建语义索引，
    // 这里只是"按意思检索 + 管理"的入口。两边曾经各存各的、互相搜不到，2026-07-29 合并（见 DocChunker）。
    PackageDef("rag", "知识库", "存文档、按意思检索出相关段落原文（不是只回开头）。与「文档解析」导入的是同一个库。", "信息",
        listOf(RagTool(context)), enabledByDefault = false),
    // 默认开：它是只读的，且「想不起来以前记过什么」是高频场景——默认关等于 AI 永远答不出来
    PackageDef("local_search", "本地搜索", "在本机模糊搜索任何本地内容：对话记录/长期记忆/工作区文件/日记/提醒/角色卡/世界书/功能包。支持错字漏字词序颠倒，只读不改。", "信息",
        listOf(LocalSearchTool(context)), enabledByDefault = true),
    PackageDef("fetch", "网页获取", "open_page：浏览器内核打开网页取正文(防403、取动态内容/懒加载图)，format=raw/json 取原始HTML/API，mode=images/video 取图/视频直链。", "网络",
        listOf(OpenPageTool(context)), enabledByDefault = true),
    PackageDef("browser", "AI 隐身浏览器", "参照 browser-use 的 AI 驱动浏览器 + cloak 隐身反检测：像人一样连续操作网页(登录/搜索翻页/点进详情/填表提交)。伪装成真实 Chrome(抹掉 webdriver/WebView 指纹)绕过反爬/Cloudflare 类拦截；open 后返回带编号的可交互元素，AI 按编号 click/type/scroll/back 逐步操作、每步回读新状态。", "网络",
        listOf(BrowserTool(context)), enabledByDefault = false),
    PackageDef("http_tools", "HTTP 请求 / 下载文件", "调用 REST API：GET/POST/PUT/DELETE + headers + body + cookies + multipart 上传 + 可选跳过SSL；" +
        "另可把 URL 指向的文件下载到系统「下载」目录或 AI 工作区(save_to)。", "网络",
        listOf(HttpRequestTool(context)), enabledByDefault = false),
    PackageDef("image_gen", "文生图", "按文字描述生成图片：OpenAI 兼容接口 / 通义万相(阿里云百炼) / 智谱 CogView 三选一，" +
        "按文生图设置里填的地址自动认，需填 key。结果直接在聊天显示。", "增强对话",
        listOf(GenerateImageTool(context)), enabledByDefault = false),
    // 文件 10 工具合并成 2 个（防「选错文件动词」幻觉、省 schema token）：file_read=只读(read/list/exists，STANDARD自动放行)、
    // file_op=改动(write/edit/delete/move/copy/mkdir/zip/unzip，ACCESSIBILITY 要授权)。权限边界与拆开时一致。
    PackageDef("file_tools", "文件工具", "AI 在私有工作目录里查看(file_read)与改动(file_op：写/改/删/移/复制/建目录/压缩解压)文件（沙盒，禁止碰外部/系统路径）。", "工具",
        listOf(FileReadTool(context), FileOpTool(context)), enabledByDefault = false),
    PackageDef("doc_read", "文档解析", "把 PDF(文本层优先，扫描件回退逐页识图OCR)/DOCX/PPTX/xlsx/EPUB/文本/代码解析成文字读进来回答；对齐竞品「PDF/DOCX 入模」。", "信息",
        listOf(DocReadTool(context)), enabledByDefault = true),
    PackageDef("image_ocr", "图片取字", "从图片中提取文字(OCR)：给一张图(本地路径/content://或file:// URI)，走识图(vision)模型把图里文字按原排版逐行原样读出来。需激活视觉模型。", "信息",
        listOf(ImageOcrTool(context)), enabledByDefault = true),
    PackageDef("csv_export", "CSV 导出", "把表格数据(二维数组或对象数组)导出成 .csv 保存到系统下载目录，UTF-8带BOM中文不乱码，Excel/WPS可直接打开。", "工具",
        listOf(CsvExportTool(context)), enabledByDefault = false),
    PackageDef("schedule", "定时任务", "让 AI 排任意提醒/定时通知：一次性或周期(最小15分钟)，到点 App 自己弹通知。补齐 Proactive/Diary/固定提醒之外的通用定时能力。", "陪伴",
        listOf(ScheduleTool(context)), enabledByDefault = true),
    PackageDef("time_utils", "时间工具", "获取当前时间、日期、时区、计时等功能。", "工具",
        listOf(TimeTool()), enabledByDefault = true),
    PackageDef("calculator", "计算器", "数学表达式计算，支持基本运算和函数。", "工具",
        listOf(CalculatorTool()), enabledByDefault = true),
    PackageDef("permission", "权限请求", "工具被权限/未启用的功能包挡住时，AI 主动发起授权申请（运行时权限弹框、特殊权限跳系统页、Shizuku 授权、申请启用功能包）。", "系统",
        listOf(PermissionTool(context)), enabledByDefault = true),
    PackageDef("ask_user", "反问澄清", "AI 拿不准你想要什么时，先问清楚再动手：给几个方向让你点选，也能自己说，答完再继续。", "系统",
        listOf(AskUserTool()), enabledByDefault = true),
    PackageDef("todo", "任务清单", "AI 把多步骤活儿拆成清单，边做边勾进度给你看（仿 Claude Code 的 TODO）。", "系统",
        listOf(TodoTool()), enabledByDefault = true),
    PackageDef("app_settings", "改设置(申请制)", "AI 可向用户申请修改 app 设置(名字/开关类)，只发申请、用户同意才生效；API key/角色卡/模型受保护不可改。", "系统",
        listOf(ProposeSettingTool(context)), enabledByDefault = false),
    PackageDef("plugin_manager", "包管理器", "安装、卸载、启用/禁用功能包。支持Operit生态对接。", "系统",
        listOf(), enabledByDefault = true),
    PackageDef("shell", "Shell 命令", "执行 Android Shell 命令：应用沙盒级，或经 Shizuku(免root ADB级)/root 跑系统命令；支持超时和危险操作拦截。", "工具",
        listOf(ShellTool(context)), enabledByDefault = false),
    PackageDef("ui_control", "界面自动化", "读当前屏幕(文字+可点坐标)并代你点按/滑动/输入/返回主页——让 AI 帮你操作其它 App。需开无障碍服务，免 root。", "工具",
        listOf(UiControlTool(context)), enabledByDefault = false),
    PackageDef("social_share", "社交分享", "通过微信/QQ/朋友圈分享文本消息。", "工具",
        listOf(SocialShareTool(context)), enabledByDefault = false),
    // 设备控制 (日用)
    PackageDef("brightness", "屏幕亮度", "调整设备屏幕亮度，支持自动/手动。", "设备控制",
        listOf(BrightnessTool(context)), enabledByDefault = true),
    PackageDef("volume", "音量控制", "调整媒体/铃声/闹钟音量。", "设备控制",
        listOf(VolumeTool(context)), enabledByDefault = true),
    // 通用系统设置读写：比 brightness/volume 更底层、能改 adb_enabled 等敏感项，默认关、DEBUGGER 级需授权。
    PackageDef("settings", "系统设置读写", "读/改安卓系统设置：屏幕亮度/自动旋转/字体缩放/屏幕超时/开发者选项等(get/put; system/global/secure)。改 global/secure 需 Shizuku(免 root ADB 级)。", "设备控制",
        listOf(SettingsTool(context)), enabledByDefault = false),
    PackageDef("device_misc", "设备小工具", "手电筒开关、查当前 WiFi 连接信息。", "设备控制",
        listOf(TorchTool(context), WifiInfoTool(context)), enabledByDefault = true),
    PackageDef("app_launch", "应用管理", "按名称打开设备上的应用(重名时列出选项)、搜已装应用与它们的界面，以及装 apk / 卸载 / 强行停止" +
        "（装卸走系统自己的确认界面，不做静默安装；每次都会先问过你）。", "设备控制",
        listOf(AppLauncherTool(context)), enabledByDefault = true),
    // 任意 Intent/广播单独成包、默认关：它能打到任何一个导出组件，是整个 App 间的攻击面。
    // 工具本身挂 DEBUGGER 级 ⇒ 默认策略 ASK，且**永远进不了模型自动审批**（那条只放行 STANDARD 级）。
    PackageDef("intent", "任意 Intent / 广播", "发送任意 Intent 或系统广播（action/data/extras/组件/flags 都能带），触达别的 App 暴露出来的功能。" +
        "高风险能力：每次调用都会请你确认，默认关闭。", "设备控制",
        listOf(IntentTool(context)), enabledByDefault = false),
    PackageDef("clipboard", "剪贴板", "读取和设置系统剪贴板内容（Android 10+ 只有 App 在前台时才读得到）。", "设备控制",
        listOf(ClipboardTool(context)), enabledByDefault = true),
    PackageDef("tts", "文字转语音", "TTS将文本转换为语音朗读，支持中/英/日/韩。", "设备控制",
        listOf(TtsTool(context)), enabledByDefault = true),
    PackageDef("import_export", "导入导出", "导入导出角色卡/配置/对话/记忆/包，兼容Operit格式。", "系统",
        listOf(), enabledByDefault = true),
    // Operit 兼容
    // skill_read 放这个包：技能索引就是这个包吐进系统提示的，工具和索引同生共死——
    // 不会出现「索引让模型调一个不存在的工具」。用户关掉本包时 disabledCapabilitiesNote 会告诉模型怎么申请。
    PackageDef("operit_compat", "Operit兼容层", "加载.skill/.toolpkg/MCP配置，支持Operit生态。", "Operit",
        listOf(SkillReadTool(context)), enabledByDefault = true),
    PackageDef("operit_market", "云端市场", "GitHub Issues 包市场，搜索安装脚本/沙盒包/Skill/MCP。", "Operit",
        listOf(), enabledByDefault = true),
    // plugin_creator 是 AI 的自我扩展入口(能给自己新增技能/沙盒包/MCP 工具)，每次真正安装/卸载都会
    // 走 ToolPermissionManager.checkCapability 单独问用户确认——包本身默认开只是让它「可被调用」，
    // 不代表装东西不用同意。
    PackageDef("plugin_creator", "插件制作", "创建/安装 Skill/沙盒包/MCP：可现写内容，也可从 https 直链联网装；能列出、卸载自己装过的东西。每次真正安装/卸载都会请你当面确认。", "Operit",
        listOf(PluginCreatorTool(context)), enabledByDefault = true),
    // 增强对话 (Operit 移植)
    PackageDef("file_converter", "文件转换 / 生成文档", "文本(JSON/CSV/MD/HTML)互转 + 图片格式(PNG/JPG/WEBP)转换 + " +
        "把 Markdown/HTML 生成 PDF 或 Word(.docx) 存到「下载」目录（零依赖，不需要装任何东西）。", "工具",
        listOf(FileConverterTool(context)), enabledByDefault = false),
    PackageDef("manage_chats", "管理对话", "AI 可跨对话搜聊天记录(「我们以前聊过的那个…」)，并列出/读/改名/删除/归档/置顶你的对话历史(删档等敏感操作会请你确认)。", "工具",
        listOf(ManageChatsTool(context)), enabledByDefault = false),
    PackageDef("net_diag", "网络诊断", "ping(连通/延迟)/dns(解析)/http(响应头)/port(端口)——排查连不上、慢、被墙等网络问题。", "工具",
        listOf(NetDiagTool()), enabledByDefault = false),
    PackageDef("github", "GitHub 查询", "搜仓库/看仓库详情/issues/releases/近期热门/用户信息（官方 API，可选 token 提额）。", "工具",
        listOf(GithubTool()), enabledByDefault = false),
    PackageDef("image_crop", "图片裁剪", "裁剪图片：给一张图(本地路径/content://或file:// URI/工作区相对路径)和裁剪区域(像素 x/y/width/height 或 0~1 比例)，裁出子图存到工作区并返回路径。越界自动钳制、大图降采样防OOM。", "工具",
        listOf(ImageCropTool(context)), enabledByDefault = false),
    // id 保持 ble_scan 不动：它是 SharedPreferences 的 key(pkg_$id)，改了用户已开启的包会退回默认状态。
    // 显示名从「蓝牙扫描」改掉是因为它不再只会扫描了——现在能连上去读写。
    PackageDef("ble_scan", "蓝牙", "扫描附近的低功耗蓝牙(BLE)设备(名称/MAC/信号强度)，连上去读写特征值、订阅通知，" +
        "以及跟经典蓝牙串口(SPP)模块收发数据。写入设备会当场问你。需蓝牙权限(安卓12+)或定位权限(旧系统)。", "设备控制",
        listOf(BleTool(context)), enabledByDefault = false),
    PackageDef("home_assistant", "Home Assistant", "智能家居：列/查设备状态、调用服务开关灯/插座/空调等（直连 HA 官方 REST API，需填 HA 地址+长效令牌）。", "设备控制",
        listOf(HomeAssistantTool(context)), enabledByDefault = false),
    PackageDef("web_server", "内嵌 Web 服务器", "在本机起极简 HTTP 静态服务器，把私有目录里生成的网页/报告用浏览器打开或分享到局域网(lan=true)；只读、只服务 app 私有目录、防路径穿越。", "网络",
        listOf(WebServerTool(context)), enabledByDefault = false),
    PackageDef("message_gateway", "消息网关", "把一段消息转发出去：share 调起微信/QQ/Telegram/邮件等 App 由你点发送；webhook 把消息 POST 到企业微信/飞书/Discord/Slack 或自建机器人自动送达（URL 只存本机）。", "工具",
        listOf(MessageGatewayTool(context)), enabledByDefault = false),
    PackageDef("net_log", "网络日志", "查看最近网络请求(方法/URL/状态码/耗时/大小/错误)，排查联网问题；内存不落盘、敏感参数已打码。", "系统",
        listOf(NetLogTool()), enabledByDefault = false),
    PackageDef("mcp_server", "内置 MCP Server", "把本 App 已启用的工具通过 HTTP JSON-RPC 暴露给外部 MCP 客户端(Claude Desktop/Cursor 等)连接调用。默认关、只绑回环 127.0.0.1；开放局域网需设访问令牌。", "系统",
        listOf(McpServerTool(context)), enabledByDefault = false),
    PackageDef("screen_ocr", "屏幕取字/截图", "截当前屏幕：识别画面里的文字(交给识图模型逐行读出，需激活视觉模型)，或直接存成图片到「下载」目录给用户。有 root/Shizuku 时走 screencap 不弹框；否则用系统「屏幕录制」授权(首次弹框)。", "工具",
        listOf(ScreenOcrTool(context)), enabledByDefault = false),
    PackageDef("local_infer", "本地离线模型", "用手机本地 GGUF 模型(llama.cpp)完全离线回答/生成——隐私/断网场景。需装配原生推理库+模型(见 NATIVE-INFERENCE.md)。", "工具",
        listOf(LocalInferTool(context)), enabledByDefault = false),
    PackageDef("read_sms", "短信读取", "读取收到的短信(验证码/通知)，可按发件人过滤。含隐私、需短信权限、默认关。", "设备控制",
        listOf(ReadSmsTool(context)), enabledByDefault = false)
)
