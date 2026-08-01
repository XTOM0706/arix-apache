package com.arix.tool

import android.content.Context

/**
 * 按需下载的「我们自己的产物」的来源（第三方大件不走这里——语音模型直接从 sherpa-onnx
 * 上游原厂下，见 SttModelManager）。
 *
 * **当前只有终端 APK 一件。**
 *
 * ## 为什么不再让用户自己填下载地址
 *
 * 早先这里有个「自建下载基址」的输入框。它当初存在的唯一理由是：**仓库还没公开、没有官方地址**，
 * 不给用户一个能填的地方，下载功能就是死的。
 *
 * 仓库公开之后这个理由就没了，而留着它是有代价的：
 *  · 多一个**能被改写的下载来源**——虽然下载后有「签名须与本 App 一致」的校验兜底
 *    （见 [ApkInstaller]），但少一个入口总是更好；
 *  · 那个框允许明文 http；
 *  · 终端页上多一个普通用户根本不该碰的 URL 输入框。
 *
 * 国内直连 GitHub 不稳这个真实问题，由 [CloudMarketplace.openGh] 的**镜像回退**解决，
 * 不需要让用户手填。真要离线/局域网分发，手动侧载 APK 这条路永远都在。
 */
object RemoteAssets {

    /**
     * 终端**自己的**仓库。它在那儿独立发布、有自己的版本线
     * （写这段时终端是 v1.1，主 App 是 v0.1.0）——两边的发版节奏本来就不该绑在一起。
     */
    private const val TERMINAL_REPO = "XTOM0706/arix-terminal"

    /**
     * 终端安装包的下载直链：去 [TERMINAL_REPO] 的**最新 release** 里找那个 apk。
     *
     * 为什么不写死一个带 tag 的地址（那样不用联网、也不会失败）：
     * 写死就意味着**每发一版都要改这一行代码**，而漏改的表现是用户点了下载拿到 404、
     * 且完全看不出原因。宁可多一次网络请求，换"发版不用动代码"。
     *
     * 也不能用 GitHub 的 `releases/latest/download/<name>` 这条免解析的捷径：
     * 它**不解析 prerelease**，而我们的开发快照就是标着 prerelease 的。
     *
     * **不按固定文件名匹配**，只要是 `.apk` 就认：资产名由终端那边定
     * （现在叫 `arix-terminal-1.1-arm64.apk`，带版本号，每版都不一样）。
     * 写死名字的表现是某天突然 404，而 UI 只会说"下载失败"。
     *
     * @return null = 这次没解析到（没发过版 / 网络不通 / 资产名对不上）。
     *         调用方要给一句人话，别只说"失败"。
     */
    suspend fun terminalApkUrl(ctx: Context): String? =
        com.arix.app.UpdateChecker.assetUrl(ctx, TERMINAL_REPO) { it.endsWith(".apk", true) }
}
