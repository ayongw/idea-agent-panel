package com.ayongw.idea.opencode.backend.repository

import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * loopback 目标强制直连的代理选择器
 *
 * 背景：IDE 的 HTTP Proxy（如 Clash 的 SOCKS5）例外列表为空时，发往本机 opencode
 * （127.0.0.1:4096）的请求也会走代理；经 SOCKS 隧道复用被 server 超时关闭的陈旧连接时，
 * EOF 被「软化」成 `unexpected end of stream`（直连场景则是 RST）。本机服务流量本就不该绕代理，
 * 故 loopback 目标一律返回 [Proxy.NO_PROXY]；其余目标委托给 JVM 默认选择器（即 IDE 代理配置）。
 *
 * 仅按主机名做字符串判定，不触发 DNS。
 */
class LoopbackProxySelector(
    private val delegate: ProxySelector = requireNotNull(ProxySelector.getDefault())
) : ProxySelector() {

    override fun select(uri: URI?): List<Proxy> {
        // URI.host 对 IPv6 字面量保留方括号（如 `[::1]`），判定前先剥离
        val host = uri?.host?.removePrefix("[")?.removeSuffix("]")?.lowercase()
        if (host != null && isLoopbackHost(host)) return listOf(Proxy.NO_PROXY)
        return delegate.select(uri)
    }

    override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
        delegate.connectFailed(uri, sa, ioe)
    }

    companion object {

        /** 127/8 整段均为 loopback（IP 字面量，URI host 不含端口） */
        private val loopbackIpv4 = Regex("""127(?:\.\d{1,3}){3}""")

        /** IPv4-mapped IPv6 loopback，如 `::ffff:127.0.0.1` */
        private val loopbackMappedV6 = Regex("""(?:0:0:0:0:0:|::)ffff:127(?:\.\d{1,3}){3}""")

        /**
         * 主机名是否指向 loopback：`localhost`、127/8、`::1` 及其等价写法、IPv4-mapped 写法
         */
        fun isLoopbackHost(host: String): Boolean {
            val normalized = host.lowercase()
            return normalized == "localhost" ||
                normalized == "::1" ||
                normalized == "0:0:0:0:0:0:0:1" ||
                loopbackIpv4.matches(normalized) ||
                loopbackMappedV6.matches(normalized)
        }
    }
}
