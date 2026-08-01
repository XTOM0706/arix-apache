package com.arix.app;

import com.arix.app.IIncognitoFetchCallback;

/**
 * 隐身进程提供的「取一个网页的正文」服务。
 *
 * oneway + 回调，而不是同步返回 String：一次页面加载可能要十几秒，
 * 同步 binder 调用会把一条 binder 线程按住那么久（binder 线程池只有 16 条）。
 */
oneway interface IIncognitoFetch {
    /**
     * @param url       要取的地址（只接受 https/http，由实现方再校验一次）
     * @param timeoutMs 超时上限，超了回错误而不是永远挂着
     * @param jsExpr    取正文用的 JS 表达式；传 null 用默认的 document.body.innerText
     */
    void fetchText(String url, int timeoutMs, String jsExpr, IIncognitoFetchCallback cb);

    /** 清掉这个隐身进程里攒下的一切（cookie/缓存/DOM storage）。用完即焚，不等进程死。 */
    void wipe();
}
