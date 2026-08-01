package com.arix.tool

import java.net.InetAddress
import java.net.URL

/**
 * WebGuard —— 给「AI 主动取网页」的入口（open_page / fetch / BrowserAgent 导航）做 SSRF 前置闸门。
 *
 * 免 root 也能让被 prompt injection 操纵的 AI 去打**本机 / 局域网设备 / 云元数据**（路由器后台、局域网 NAS、
 * 127.0.0.1 上的其它 App 端口、169.254.169.254…）——这是「网页当不可信数据」之外的另一条 SSRF 面。
 * 规则与主 App 的 http_request（FileTools.blockedHost）保持一致：只放行 http/https，逐个解析地址判私网/保留段。
 *
 * check(url) 返回 null=放行；非 null=拒绝原因（可直接回给模型/用户）。https 优先，http 明文允许但**仍拦私网**。
 */
object WebGuard {

    /** 返回 null = 放行；非 null = 拒绝原因。 */
    fun check(urlStr: String): String? {
        val url = try { URL(urlStr) } catch (_: Exception) { return "URL 格式无效" }
        val scheme = url.protocol?.lowercase()
        // 只允许 http/https：挡掉 file:// content:// javascript: data: ftp: 等本地/危险协议
        if (scheme != "http" && scheme != "https") return "不支持的协议 $scheme（仅允许 http/https）"
        val host = url.host?.trim('[', ']')?.lowercase()?.trimEnd('.') ?: return "缺少主机"
        if (host.isBlank()) return "缺少主机"
        // 先按主机名兜一层（即便 DNS 挂了也能挡常见内网名）
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local") ||
            host == "metadata" || host.endsWith(".internal")) return "内网主机名 $host"
        // 关键：解析成 InetAddress，对**每个**返回地址判环回/私网/链路本地/组播/云元数据/ULA/CGNAT。
        val addrs = try { InetAddress.getAllByName(host) } catch (_: Exception) { return "主机无法解析 $host" }
        if (addrs.isEmpty()) return "主机无法解析 $host"
        for (addr in addrs) {
            if (addr.isLoopbackAddress || addr.isAnyLocalAddress || addr.isLinkLocalAddress ||
                addr.isSiteLocalAddress || addr.isMulticastAddress) return "私有/保留地址 ${addr.hostAddress}"
            val ip = addr.address
            // IPv4-mapped IPv6 (::ffff:a.b.c.d)：上面的 isSiteLocal 等对映射地址判不出，手动拆内嵌 IPv4 再判
            if (ip.size == 16 && isV4Mapped(ip)) {
                val a = ip[12].toInt() and 0xff; val b = ip[13].toInt() and 0xff
                if (a == 127 || a == 10 || a == 0 || a >= 224 ||
                    (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254) ||
                    (a == 100 && b in 64..127))
                    return "私有/保留地址(IPv4 映射) $a.$b.${ip[14].toInt() and 0xff}.${ip[15].toInt() and 0xff}"
            }
            // IPv6 唯一本地地址 ULA (fc00::/7)：isSiteLocalAddress 只认已废弃的 fec0::/10，不认 ULA，手动兜；fe80:: 由 isLinkLocal 覆盖
            if (ip.size == 16 && (ip[0].toInt() and 0xfe) == 0xfc) return "IPv6 唯一本地地址(ULA)"
            if (ip.size == 4) {
                val a = ip[0].toInt() and 0xff; val b = ip[1].toInt() and 0xff
                val c = ip[2].toInt() and 0xff; val d = ip[3].toInt() and 0xff
                // 云元数据 169.254.169.254 已被 isLinkLocalAddress 覆盖，这里显式再兜一层让日志更清楚
                if (a == 169 && b == 254) return "链路本地/云元数据地址 $a.$b.$c.$d"
                // 100.64/10 运营商级 NAT（CGNAT），也归内网范畴
                if (a == 100 && b in 64..127) return "CGNAT 保留地址 $a.$b.$c.$d"
            }
        }
        return null
    }

    /** 判 IPv6 是否为 IPv4-mapped (::ffff:x.x.x.x)：前 10 字节全 0，第 11、12 字节为 0xff。 */
    private fun isV4Mapped(ip: ByteArray): Boolean {
        if (ip.size != 16) return false
        for (i in 0..9) if (ip[i].toInt() != 0) return false
        return (ip[10].toInt() and 0xff) == 0xff && (ip[11].toInt() and 0xff) == 0xff
    }
}
