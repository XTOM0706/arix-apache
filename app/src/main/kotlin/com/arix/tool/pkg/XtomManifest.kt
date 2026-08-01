package com.arix.tool.pkg

import org.json.JSONArray
import org.json.JSONObject

/**
 * Arix 包格式（`.xtompkg` = 一个 zip，根目录放 `xtom.json`）。
 *
 * ## 为什么自己定一套，而不是沿用 Operit 的
 * Operit 的包能用，但它的安全模型有个根本窟窿：**manifest 里压根没有 permissions 字段**。
 * 装上的包 JS 拿的是 App 的全部权限（能读文件、发网络、跑 shell、操作其它 App 的界面），
 * 还能注入 UI 和导航，唯一的防线是服务端人工审核。对一个能读你通讯录、位置、通知的
 * 个人助理 App 来说，这个赌注太大。
 *
 * Arix 包反过来：**包必须先声明它要什么能力，用户装的时候看得到、点头才给，运行时只给声明过的**。
 * 这条链接的是本项目已有的工具权限审批系统（ALLOW/ASK/FORBID），不是另造一套。
 *
 * Operit 包不被抛弃——它们会被**转换**成 Arix 包（见 OperitImport），转换时按其代码
 * 实际调用的工具反推出权限声明，并标记来源为 imported，让用户知道这份声明是推断的而非作者声明的。
 */
data class XtomManifest(
    /** 稳定 id，反查/去重/冲突判定都靠它。同 id 视作同一个包的不同版本。 */
    val id: String,
    val version: String,
    /** 显示名。多语言：key=语言码，"" = 默认。 */
    val name: Map<String, String>,
    val description: Map<String, String>,
    val author: String = "",
    /** 入口脚本在 zip 内的相对路径。 */
    val main: String = "main.js",
    /** 本包声明要用的能力。装时逐条给用户看。**运行时只放行这里列出的**。 */
    val permissions: List<PkgPermission> = emptyList(),
    /** 本包提供的工具名（供 UI 展示 + 注册）。 */
    val provides: List<String> = emptyList(),
    /** 兼容的 App 版本区间（含）。空=不限。版本号形如 1.2.3。 */
    val minAppVer: String = "",
    val maxAppVer: String = "",
    /**
     * 发布者签名（base64）。**只有官方包会带**——我们签自己的包、公钥内置在 App 里。
     * 第三方包留空，装的时候明确标「未签名」由用户自行判断，不阻断。
     * 之所以不做第三方签名：那要求维护密钥分发与信任链，对个人项目是长期负担，
     * 而它多挡的场景(篡改/中间人) sha256 + HTTPS + 公开来源已挡掉大半。
     */
    val signature: String = "",
    /** 来源。`native`=Arix 原生包；`imported_operit`=从 Operit 包转换而来（权限是推断的）。 */
    val origin: String = "native",
) {
    fun nameFor(lang: String): String = name[lang] ?: name[""] ?: id
    fun descFor(lang: String): String = description[lang] ?: description[""] ?: ""

    /** 未签名 = 第三方包。UI 据此提示，但不阻断安装。 */
    val signed: Boolean get() = signature.isNotBlank()

    fun toJson(): String = JSONObject().apply {
        put("schema", SCHEMA)
        put("id", id); put("version", version)
        put("name", JSONObject(name)); put("description", JSONObject(description))
        put("author", author); put("main", main)
        put("permissions", JSONArray(permissions.map { it.id }))
        put("provides", JSONArray(provides))
        if (minAppVer.isNotBlank()) put("minAppVer", minAppVer)
        if (maxAppVer.isNotBlank()) put("maxAppVer", maxAppVer)
        if (signature.isNotBlank()) put("signature", signature)
        put("origin", origin)
    }.toString(2)

    companion object {
        const val SCHEMA = 1
        const val FILE = "xtom.json"

        /** 解析。坏 manifest 一律返回 null——宁可装不上，也不能装一个权限声明读不出的包。 */
        fun parse(json: String): XtomManifest? = try {
            val o = JSONObject(json)
            val id = o.optString("id").trim()
            if (id.isBlank()) null
            else XtomManifest(
                id = id,
                version = o.optString("version", "0.0.0"),
                name = o.localized("name", id),
                description = o.localized("description", ""),
                author = o.optString("author", ""),
                main = o.optString("main", "main.js"),
                // 认不出的权限**丢弃**而不是忽略整个字段：新版 App 加了权限、旧 App 读到不认识的，
                // 丢掉它比当作没有安全（少给权限只会让包功能缺失，多给才是事故）。
                permissions = o.optJSONArray("permissions").strings().mapNotNull { PkgPermission.of(it) },
                provides = o.optJSONArray("provides").strings(),
                minAppVer = o.optString("minAppVer", ""),
                maxAppVer = o.optString("maxAppVer", ""),
                signature = o.optString("signature", ""),
                origin = o.optString("origin", "native"),
            )
        } catch (_: Exception) { null }

        /** name/description 既接受纯字符串(单语言)也接受 {lang: text} 对象。 */
        private fun JSONObject.localized(key: String, fallback: String): Map<String, String> {
            val v = opt(key) ?: return mapOf("" to fallback)
            return when (v) {
                is String -> mapOf("" to v)
                is JSONObject -> v.keys().asSequence().associateWith { v.optString(it, "") }
                else -> mapOf("" to fallback)
            }
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }
    }
}

/**
 * 包能申请的能力。**粒度按「用户能理解的后果」分，不按内部工具名分**——
 * 用户看到「读取你的位置」能判断该不该给，看到「amap_geocode」不能。
 *
 * 每一条都对应一组工具；运行时的网关按这个映射放行（见 PkgGate）。
 */
enum class PkgPermission(val id: String, val label: String, val why: String) {
    NETWORK("network", "访问网络", "联网取数据。也意味着它能把读到的东西发出去。"),
    FILES_WORKSPACE("files_workspace", "读写 AI 工作区", "在 AI 的私有工作目录里读写文件。出不了这个目录。"),
    FILES_DEVICE("files_device", "读写设备文件", "读写工作区之外的文件。范围大，谨慎给。"),
    SHELL("shell", "执行命令", "在 Arix 工作台里跑命令。**等于把设备交给它**，只给你信得过的包。"),
    LOCATION("location", "读取位置", "拿到你的地理位置。"),
    CONTACTS("contacts", "读取通讯录", "读你的联系人。"),
    NOTIFICATIONS("notifications", "读取通知", "读手机上收到的通知（可能含验证码、私信）。"),
    HEALTH("health", "读取健康数据", "读步数/心率/睡眠等。"),
    CALENDAR("calendar", "读写日程", "看你的日程、往里加。"),
    MEMORY("memory", "读写记忆", "读你的记忆库、往里写。"),
    UI_CONTROL("ui_control", "操作界面", "代你点按/滑动/读屏幕。**能操作任何 App**，只给你信得过的包。"),
    MEDIA("media", "媒体控制", "控制播放、音量。"),
    ;

    /** 值得单独警示的高危项：给了就等于交出设备。 */
    val high: Boolean get() = this == SHELL || this == UI_CONTROL || this == FILES_DEVICE

    companion object {
        fun of(id: String): PkgPermission? = entries.firstOrNull { it.id == id }
    }
}
