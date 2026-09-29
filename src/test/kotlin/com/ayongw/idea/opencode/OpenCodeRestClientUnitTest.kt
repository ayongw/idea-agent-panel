package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress

/**
 * OpenCodeRestClient 认证头与健康检查的本地验证（起本地 HTTP Server 断言真实请求）
 */
class OpenCodeRestClientUnitTest {

    private lateinit var server: HttpServer
    private var receivedAuthHeader: String? = null
    private var receivedPath: String? = null
    private var port: Int = 0

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            receivedAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            receivedPath = exchange.requestURI.path
            val body = if (exchange.requestURI.path.endsWith("/session")) "[]" else "{}"
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun baseUrl(): String = "http://127.0.0.1:$port"

    @Test
    fun tokenIsSentAsBearerAuthHeader() = runBlocking {
        val client = OpenCodeRestClient(baseUrl(), "secret-token")

        val result = client.getAllSessions()

        assertTrue("GET /session 应请求成功", result.isSuccess())
        assertEquals("/session", receivedPath)
        assertEquals("Bearer secret-token", receivedAuthHeader)
    }

    @Test
    fun noAuthHeaderWhenTokenIsBlank() = runBlocking {
        val client = OpenCodeRestClient(baseUrl(), "   ")

        client.getAllSessions()

        assertNull("Token 为空白时不应携带认证头", receivedAuthHeader)
    }

    @Test
    fun healthCheckCarriesAuthHeader() = runBlocking {
        val client = OpenCodeRestClient(baseUrl(), "health-token")

        val healthy = client.healthCheck()

        assertTrue("健康检查应返回成功", healthy)
        assertEquals("/global/health", receivedPath)
        assertEquals("Bearer health-token", receivedAuthHeader)
    }
}