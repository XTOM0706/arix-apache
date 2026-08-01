package com.arix.app

import android.app.Application
import android.os.Build
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import com.arix.tool.OperitCompat
import com.arix.tool.PackageManager
import com.arix.tool.ToolManager
import com.arix.tool.ToolPermissionManager
import com.arix.tool.createBuiltInPackages

class XtomApp : Application(), ImageLoaderFactory {

    companion object {
        /** 进程级 application context，供无 Activity 上下文的后台单例用。 */
        @Volatile
        var appContext: android.content.Context? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        appContext = applicationContext
        registerActivityLifecycleCallbacks(AppForeground.callbacks)
        CrashHandler.init(this)
        ToolPermissionManager.init(this)
        PackageManager.init(this)
        PromptLangPrefs.init(this)
        // 每个模型配置的旁挂进阶参数（请求体模板 / 内置联网搜索 / **接口协议**）。
        // 从前只有配置页 bind 它，于是「冷启动后不进配置页就直接聊天」那一次读不到任何 override。
        // 对模板和搜索开关而言那只是"这次没生效"；对协议选择却是**发错格式**——中转站被判回
        // OpenAI 兼容、请求体整个是另一种形状。所以必须在这里就把磁盘读进内存镜像。
        // 只是读一份小 prefs 到 map，不做 IO 之外的事。
        runCatching { com.arix.cloudapi.ApiExtrasStore.bind(this) }
        // 设备能力探测（工具表按「本机跑不跑得起」裁剪，见 ToolRequirement）。只存 application context，不做任何探测。
        com.arix.tool.CapabilityProbe.init(this)
        createBuiltInPackages(this).forEach { PackageManager.register(it) }
        // 第三方 MCP 工具网关的两个元工具。**不进 PackageManager**（它们不属于任何功能包，
        // 也不该被用户在包列表里单独关掉——该不该发由 getToolsJson 按"第三方工具够不够多"决定）。
        ToolManager.registerBuiltin(com.arix.tool.McpGateway.searchTool)
        ToolManager.registerBuiltin(com.arix.tool.McpGateway.useTool)
        // 装配 Operit 兼容层（本地包/JS插件/MCP）——此前从未初始化，导致整套 OperitCompat 一直是死的。
        // refresh 在后台跑（含 MCP 进程发现，别在主线程 runBlocking 造成 ANR）。
        OperitCompat.init(this)
        // 非首帧关键的初始化挪到后台：PDF 资源(仅抽 PDF 用)、按模型用量加载(仅统计页用)、MCP socket 绑定，
        // 都不该卡在冷启动主线程 onCreate 上。用守护线程一次跑掉。
        val app = this
        Thread {
            runCatching { com.tom_roush.pdfbox.android.PDFBoxResourceLoader.init(app) }
            runCatching { com.arix.cloudapi.ApiMonitor.init(app) }   // 加载持久化的按模型累计用量（供使用统计算花费）
            // 密钥健康档案：冷却/禁用状态跨重启存活。不加这行就退化成纯内存——
            // 进程一死就忘了哪枚 key 是坏的，下次启动又拿它去撞 401。
            runCatching { com.arix.cloudapi.KeyPool.init(app) }
            // 网络代理：把 app 侧的配置推给 cloudapi（那个模块不依赖 :app，读不到 ProxyPrefs）。
            // 关着代理时推的是 null = 直连，与从前完全一致。
            runCatching { ProxyPrefs.applyToNetwork(app) }
            // 媒体键（耳机线控唤起）：只挂一个生命周期回调驱动上下线，功能关着时零开销。
            // 默认关，且默认只在 App 活跃时才注册会话——否则会把用户听歌时的线控键抢走。
            runCatching { MediaKeyController.init(app) }
            // 悬浮球/媒体键的常驻档：进程被杀过就在这里自恢复（两者都默认关，关着时这行什么都不做）。
            runCatching { XtomOverlayService.sync(app) }
            if (com.arix.tool.McpServerPrefs.enabled(app)) runCatching { com.arix.tool.McpServer.start(app) }  // 内置 MCP Server：用户上次开着就恢复
            // 自动整理记忆：排一个低频周期任务（限频/开关判断都在 MemoryTidy 里，这里只负责排上）。
            // KEEP 幂等，冷启动重复调用不会把下一次执行推后。
            if (MemoryTidy.autoEnabled(app)) runCatching { MemoryTidy.schedule(app) }
            // 后台查更新：开关关着时 sync 会取消任务、开着才排（一天一次、只在不计流量的网络上）。
            // 幂等，冷启动重复调用不会把下一次执行推后。
            runCatching { UpdateNotifier.sync(app) }
            // 「调用前教训提示」的内存镜像：schema 每轮现读、必须同步无 IO（见 LessonRecorder.warmUp），
            // 所以启动时装一次。放这条守护线程里而不是主线程：它要读一次 DB。
            // 装完之前 hints 是空的 → 那一小会儿只是不带提示，不影响别的任何东西。
            runCatching { kotlinx.coroutines.runBlocking { LessonRecorder.warmUp(app) } }
            // 聊天全文索引的补建：老会话在这之前没有索引，不补的话跨对话搜索一直走全表扫
            // （把每个会话的完整 messagesJson 拉进内存逐条 parse）。幂等、单飞、分批带 delay，
            // 不放这儿的话要等用户第一次搜索时才触发，那一次仍然是全扫。
            runCatching {
                com.arix.data.db.AppDatabase.getInstance(app)          // 触发 ChatSearchIndex.attach
                com.arix.data.search.ChatSearchIndex.ensureBackfill()
            }
        }.apply { isDaemon = true }.start()
    }

    // 全局 Coil 加载器：注册动图(GIF/动画 WebP)解码器（coil-gif 已依赖，但默认加载器不含，需在此登记）。
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(ImageDecoderDecoder.Factory())
            else add(GifDecoder.Factory())
        }
        .crossfade(true)
        .build()
}
