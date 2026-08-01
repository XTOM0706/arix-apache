package com.arix.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 主进程这一侧的隐身取页入口。绑 `:incognito` 进程的 [IncognitoFetchService]，取完就解绑。
 *
 * 结果三态，调用方必须**分开处理**，别把后两种混成一句「失败了」：
 *  · [Result.Text]        —— 取到了，且**是真隐身**
 *  · [Result.Unsupported] —— 本机 API < 28，做不了真隐身（见 [IncognitoWeb.supported]）。
 *    这时**不许**偷偷用普通 WebView 顶上去还叫隐身 —— 要么让用户明确同意用普通方式，要么就别取。
 *  · [Result.Failed]      —— 能隐身，但这次没取到（超时/加载失败/服务起不来）
 */
object IncognitoFetch {

    sealed class Result {
        data class Text(val text: String) : Result()
        data object Unsupported : Result()
        data class Failed(val reason: String) : Result()
    }

    /** 绑定本身也要有上限：ROM 异常时 bindService 可能一直不回调，不能让调用方永久挂着。 */
    private const val BIND_TIMEOUT_MS = 8_000L

    /**
     * 隐身取一个网页的正文。
     *
     * @param jsExpr 自定义取正文的 JS 表达式（不给就取 `document.body.innerText`）。
     *               隐身进程刻意不带站点特化逻辑，需要特殊解析就从这里传进去（见 [IncognitoFetchService]）。
     */
    suspend fun text(
        context: Context,
        url: String,
        timeoutMs: Int = 25_000,
        jsExpr: String? = null,
    ): Result {
        if (!IncognitoWeb.supported) return Result.Unsupported

        val app = context.applicationContext
        val deferred = CompletableDeferred<Result>()
        // 留住远端引用：结束时要显式让它清掉本次痕迹（见 finally）。
        // @Volatile 不适用于局部变量，用单元素数组跨 lambda 传递。
        val remoteRef = arrayOfNulls<IIncognitoFetch>(1)

        val cb = object : IIncognitoFetchCallback.Stub() {
            override fun onResult(text: String?, error: String?) {
                val r = when {
                    !text.isNullOrBlank() -> Result.Text(text)
                    else -> Result.Failed(error ?: "没取到内容")
                }
                deferred.complete(r)
            }
        }

        val c = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                val remote = IIncognitoFetch.Stub.asInterface(service)
                if (remote == null) { deferred.complete(Result.Failed("隐身服务连不上")); return }
                remoteRef[0] = remote
                runCatching { remote.fetchText(url, timeoutMs, jsExpr, cb) }
                    .onFailure { deferred.complete(Result.Failed("隐身取页调用失败：${it.message}")) }
            }

            // 进程被系统回收/崩了：必须把等待方唤醒，否则整轮生成永久挂死
            override fun onServiceDisconnected(name: ComponentName?) {
                deferred.complete(Result.Failed("隐身进程断开"))
            }

            override fun onBindingDied(name: ComponentName?) {
                deferred.complete(Result.Failed("隐身进程已终止"))
            }

            override fun onNullBinding(name: ComponentName?) {
                deferred.complete(Result.Failed("隐身服务未返回接口"))
            }
        }
        return try {
            val bound = app.bindService(
                Intent(app, IncognitoFetchService::class.java), c, Context.BIND_AUTO_CREATE,
            )
            if (!bound) return Result.Failed("隐身服务起不来")
            // 总上限 = 取页超时 + 绑定余量；到点就走，别信对面一定会回
            withTimeoutOrNull(timeoutMs + BIND_TIMEOUT_MS) { deferred.await() }
                ?: Result.Failed("隐身取页超时")
        } finally {
            // 用完即焚：**先** wipe 再解绑。解绑后进程通常会被系统回收，但什么时候真收它不由我们
            // 说了算——只依赖回收就等于把"痕迹何时消失"交给运气。所以显式清一次。
            // wipe 是 oneway，不阻塞；解绑之后这条调用就发不出去了，顺序不能颠倒。
            runCatching { remoteRef[0]?.wipe() }
            runCatching { app.unbindService(c) }
        }
    }
}
