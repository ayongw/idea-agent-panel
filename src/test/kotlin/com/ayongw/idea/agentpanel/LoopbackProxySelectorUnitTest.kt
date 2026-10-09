package com.ayongw.idea.agentpanel

import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.LoopbackProxySelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * loopback 目标强制直连，外部目标委托默认选择器；connectFailed 一律转发。
 *
 * 回归点：IDE 代理（SOCKS5，例外为空）下，本机 opencode 请求被代理、陈旧隧道复用导致
 * `unexpected end of stream`。
 */
class LoopbackProxySelectorUnitTest {

    private val externalProxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", 7897))
    private val stub = StubSelector(listOf(externalProxy))
    private val selector = LoopbackProxySelector(stub)

    @Test
    fun loopbackTargetsBypassProxyAndDoNotTouchDelegate() {
        val uris = listOf(
            URI("http://127.0.0.1:4096/api/provider"),
            URI("http://127.1.2.3:4096/api/info"),
            URI("http://localhost:4096/"),
            URI("http://LOCALHOST:4096/"),
            URI("http://[::1]:4096/api/event"),
            URI("http://[0:0:0:0:0:0:0:1]:4096/"),
            URI("http://[::ffff:127.0.0.1]:4096/"),
            URI("http://[0:0:0:0:0:ffff:127.0.0.1]:4096/")
        )
        uris.forEach { uri ->
            assertEquals("uri=$uri", listOf(Proxy.NO_PROXY), selector.select(uri))
        }
        assertNull("loopback 不应调用委托", stub.lastSelectUri)
    }

    @Test
    fun externalTargetDelegates() {
        val uri = URI("https://api.example.com/models")
        val result = selector.select(uri)
        assertEquals(listOf(externalProxy), result)
        assertEquals(uri, stub.lastSelectUri)
    }

    @Test
    fun uriWithoutHostDelegates() {
        val uri = URI("mailto:opencode@example.com")
        assertNull(uri.host)
        assertSame(externalProxy, selector.select(uri).single())
        assertEquals(uri, stub.lastSelectUri)
    }

    @Test
    fun nullUriDelegates() {
        assertSame(externalProxy, selector.select(null).single())
        assertNull(stub.lastSelectUri)
    }

    @Test
    fun connectFailedForwardsToDelegate() {
        val uri = URI("http://127.0.0.1:4096/")
        val address: SocketAddress = InetSocketAddress.createUnresolved("127.0.0.1", 4096)
        val error = IOException("broken")
        selector.connectFailed(uri, address, error)
        assertEquals(uri, stub.failedUri)
        assertSame(address, stub.failedAddress)
        assertSame(error, stub.failedError)
    }

    @Test
    fun isLoopbackHostVariants() {
        listOf("127.0.0.1", "127.255.0.1", "localhost", "::1", "0:0:0:0:0:0:0:1")
            .forEach { assertTrue(it, LoopbackProxySelector.isLoopbackHost(it)) }
        listOf("192.168.1.1", "10.0.0.1", "example.com", "::2", "255.127.0.0.1")
            .forEach { assertEquals(it, false, LoopbackProxySelector.isLoopbackHost(it)) }
    }

    private class StubSelector(private val result: List<Proxy>) : ProxySelector() {
        var lastSelectUri: URI? = null
        var failedUri: URI? = null
        var failedAddress: SocketAddress? = null
        var failedError: IOException? = null

        override fun select(uri: URI?): List<Proxy> {
            lastSelectUri = uri
            return result
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            failedUri = uri
            failedAddress = sa
            failedError = ioe
        }
    }
}
