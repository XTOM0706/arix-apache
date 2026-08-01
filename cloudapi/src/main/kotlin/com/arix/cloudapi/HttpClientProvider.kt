package com.arix.cloudapi

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 网络请求日志：进程内环形缓冲（**不落盘**）。
 *
 * 缓冲放在 cloudapi 模块（而非 app 的 NetLogTool）——因为拦截器写它、拦截器在共享
 * OkHttp 单例里，而 :cloudapi 不依赖 :app（依赖方向是 app→cloudapi）。app 的 NetLogTool
 * 只读这里的快照即可。原始 token/key/Authorization **在写入时就已打码**，缓冲里不留明文。
 */
object NetLogBuffer {
    /** 单条请求记录。url/auth 均为已脱敏后的值。 */
    data class Entry(
        val ts: Long,          // 发起时刻（epoch ms）
        val method: String,
        val url: String,       // 已脱敏
        val code: Int,         // HTTP 状态码；网络失败为 -1
        val durationMs: Long,
        val sizeBytes: Long,   // 响应体大小（Content-Length）；流式/未知为 -1
        val auth: String?,     // 已打码的 Authorization（无则 null）
        val error: String?,    // 失败原因（成功为 null）
    )

    private const val CAPACITY = 200
    private val ring = ArrayDeque<Entry>(CAPACITY)
    private val lock = Any()

    fun add(e: Entry) = synchronized(lock) {
        if (ring.size >= CAPACITY) ring.removeFirst()
        ring.addLast(e)
    }

    /** 最近 n 条（新→旧）。onlyErrors 只取失败/非 2xx。 */
    fun recent(n: Int, onlyErrors: Boolean = false): List<Entry> = synchronized(lock) {
        ring.asReversed().asSequence()
            .filter { !onlyErrors || it.error != null || it.code !in 200..299 }
            .take(n.coerceAtLeast(0))
            .toList()
    }

    fun size(): Int = synchronized(lock) { ring.size }
    fun clear() = synchronized(lock) { ring.clear() }
}

/**
 * 脱敏：URL 里 token/key/secret/sign/password/auth/session 类 query 值打码；Authorization 头打码。
 * 打码在写入缓冲前完成，明文永不进内存缓冲。
 */
internal object NetLogRedactor {
    private val SENSITIVE = listOf("token", "key", "secret", "sign", "password", "passwd", "pwd", "auth", "session")

    private fun isSensitive(name: String): Boolean {
        val n = name.lowercase()
        return SENSITIVE.any { n.contains(it) }
    }

    /** 保留头尾各 2 位，中间打码；过短整体打码。 */
    fun maskValue(v: String): String {
        if (v.isBlank()) return v
        return if (v.length <= 6) "***" else v.take(2) + "***" + v.takeLast(2)
    }

    fun maskUrl(url: HttpUrl): String {
        if (url.querySize == 0) return url.newBuilder().query(null).toString()
        val b = url.newBuilder().query(null)
        for (i in 0 until url.querySize) {
            val name = url.queryParameterName(i)
            val value = url.queryParameterValue(i) ?: ""
            b.addQueryParameter(name, if (isSensitive(name)) maskValue(value) else value)
        }
        return b.toString()
    }

    /** "Bearer sk-abcdef...xyz" → 保留方案名 + 值尾 4 位。 */
    fun maskAuth(header: String?): String? {
        if (header.isNullOrBlank()) return null
        val sp = header.indexOf(' ')
        return if (sp in 1 until header.length - 1) {
            val scheme = header.substring(0, sp)
            "$scheme ****${header.substring(sp + 1).takeLast(4)}"
        } else "****${header.takeLast(4)}"
    }
}

/** 记录每次请求(方法/URL/状态码/耗时/响应大小)到 NetLogBuffer；脱敏后写入。 */
internal object NetLogInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val req = chain.request()
        val start = System.currentTimeMillis()
        val maskedUrl = NetLogRedactor.maskUrl(req.url)
        val maskedAuth = NetLogRedactor.maskAuth(req.header("Authorization"))
        try {
            val resp = chain.proceed(req)
            NetLogBuffer.add(
                NetLogBuffer.Entry(
                    ts = start,
                    method = req.method,
                    url = maskedUrl,
                    code = resp.code,
                    durationMs = System.currentTimeMillis() - start,
                    // 只读头里的 Content-Length，绝不读 body（会消费流式 SSE 响应）；未知为 -1
                    sizeBytes = resp.body?.contentLength() ?: -1L,
                    auth = maskedAuth,
                    error = null,
                )
            )
            return resp
        } catch (e: Exception) {
            NetLogBuffer.add(
                NetLogBuffer.Entry(
                    ts = start,
                    method = req.method,
                    url = maskedUrl,
                    code = -1,
                    durationMs = System.currentTimeMillis() - start,
                    sizeBytes = -1L,
                    auth = maskedAuth,
                    error = e.message ?: e.javaClass.simpleName,
                )
            )
            throw e
        }
    }
}

/**
 * 全局共享的 OkHttpClient 实例。
 *
 * 每个 OkHttpClient 都自带独立的线程池（Dispatcher）+ 连接池（ConnectionPool），
 * 空闲连接/线程默认 keep-alive 约 5 分钟才回收。此前 CloudApiClient / WhisperClient
 * 在每次构造时各 new 一个 client，一条消息可能拉起 2~3 个 client，长时间对话导致
 * 线程数、TCP 连接、内存持续攀升（手表低内存设备尤其明显）。
 *
 * 这里按超时需求预建两个共享单例（聊天流式 / 语音上传），全 App 复用，杜绝滋生。
 * 需要临时覆盖某项配置时用 `chat.newBuilder()`，它会复用底层线程池与连接池。
 */
/**
 * 代理配置的持有者。
 *
 * `:cloudapi` 不依赖 `:app`，读不到那边的 `ProxyPrefs`，所以由 app 侧在启动时和改设置时把值推过来。
 * 两个字段都是 `@Volatile` 的普通引用，不需要锁——写方只有设置页那一条路，读方每次建连各读一次。
 */
object NetProxyHolder {
    /** null = 直连。 */
    @Volatile var proxy: java.net.Proxy? = null
    /** `Basic xxx`，仅 HTTP 代理用；SOCKS5 的账密走 `java.net.Authenticator`（在 app 侧装）。 */
    @Volatile var basicAuth: String? = null
}

object HttpClientProvider {

    /**
     * ⚠ 用 `proxySelector` 而不是 `.proxy(...)`。
     *
     * 下面两个 client 都是 `by lazy` **单例**：`.proxy()` 在第一次构建时就把值固化了，
     * 用户之后在设置里改代理不会有任何效果，直到杀进程重开——那是最典型的"设置了没用"。
     * `ProxySelector` 每次建连才问一次，读的是 [NetProxyHolder] 的当前值，改完立刻生效。
     */
    private fun proxySelector() = object : java.net.ProxySelector() {
        override fun select(uri: java.net.URI?) =
            listOf(NetProxyHolder.proxy ?: java.net.Proxy.NO_PROXY)
        override fun connectFailed(uri: java.net.URI?, sa: java.net.SocketAddress?, e: java.io.IOException?) {}
    }

    /** HTTP 代理的 407 应答。已经带过一次还 407 → 返回 null 终止，否则 OkHttp 会无限重试。 */
    private val proxyAuth = okhttp3.Authenticator { _, response ->
        val h = NetProxyHolder.basicAuth ?: return@Authenticator null
        if (response.request.header("Proxy-Authorization") != null) null
        else response.request.newBuilder().header("Proxy-Authorization", h).build()
    }

    /** 聊天/补全（SSE 流式）：readTimeout 是两次 socket 读之间的上限，流式下合理。 */
    val chat: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(NetLogInterceptor)
            .proxySelector(proxySelector())
            .proxyAuthenticator(proxyAuth)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** 语音转写：writeTimeout 更长以适配音频 multipart 上传。 */
    val stt: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(NetLogInterceptor)
            .proxySelector(proxySelector())
            .proxyAuthenticator(proxyAuth)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 代理配置变了之后叫一下：把连接池里**已经按老路由建好**的连接扔掉。
     * 不叫的话，改完设置后一段时间内请求仍走旧路由（连接被复用），用户会以为没生效。
     */
    fun onProxyChanged() {
        runCatching { chat.connectionPool.evictAll() }
        runCatching { stt.connectionPool.evictAll() }
    }
}
