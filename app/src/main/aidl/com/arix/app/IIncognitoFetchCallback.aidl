package com.arix.app;

/**
 * 取页结果回调。oneway：被调方（主进程）不该让隐身进程的 binder 线程等在这儿。
 */
oneway interface IIncognitoFetchCallback {
    /**
     * @param text  取到的正文；失败时为 null
     * @param error 失败原因（人话，会回给模型/用户）；成功时为 null
     */
    void onResult(String text, String error);
}
