// Shizuku UserService 特权接口：在 shell(ADB) / root 身份的独立进程里跑，
// 供 App 反射系统隐藏 binder（IConnectivityManager 防火墙）——这是纯 shell(ShizukuMic.exec)
// 够不到的。实现见 XtomPrivilegedService，调用侧封装在 XmsfUnlock。
package com.arix.app;

interface IXtomPrivileged {
    /**
     * 开/关某 uid 的联网（防火墙 OEM_DENY chain）。
     * enabled=false 断网、true 恢复。返回是否成功（失败不抛，返回 false）。
     * 用于把小米服务框架(com.xiaomi.xmsf)短暂断网 → 焦点通知云控白名单 fail-open 放行。
     */
    boolean setPackageNetworkingEnabled(int uid, boolean enabled);
}
