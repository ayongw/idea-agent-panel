package com.ayongw.idea.agentpanel.server

import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OkHttpProbe
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerDiscovery
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerEndpoint
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerEndpointSource
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerFailure
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerHttpProbe
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerHttpResponse
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerProbePolicy
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerProbeResult
import com.sun.net.httpserver.HttpServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 发现与就绪探测（TSD-31 §3.3 / §3.5）
 *
 * HTTP 层用本地 `HttpServer` 桩横切：覆盖 200-JSON / data 包裹 / SPA-HTML 伪 200 / 401 / 403 / 503 /
 * 连接拒绝 / 读取超时 → 分类映射；轮询节奏与总超时用注入的 sleeper + clock 断言。
 */
class OpenCodeServerDiscoveryUnitTest {

    private lateinit var server: HttpServer
    private lateinit var executor: ExecutorService
    private var port: Int = 0
    private val routes = mutableMapOf<String, Pair<Int, String>>()
    private var handlerDelayMs: Long = 0
    private var lastAuthHeader: String? = null

    @Before
    fun setUp() {
        routes.clear()
        handlerDelayMs = 0
        lastAuthHeader = null
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        executor = Executors.newFixedThreadPool(2)
        server.executor = executor
        server.createContext("/") { exchange ->
            try {
                lastAuthHeader = exchange.requestHeaders.getFirst("Authorization")
                val (status, body) = routes[exchange.requestURI.path] ?: (404 to "{}")
                if (handlerDelayMs > 0) Thread.sleep(handlerDelayMs)
                val bytes = body.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } catch (_: Throwable) {
                // 超时用例里客户端已断开，桩侧无需上报
            }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun tearDown() {
        server.stop(0)
        executor.shutdownNow()
    }

    private fun endpoint(baseUrl: String = "http://127.0.0.1:$port", password: String? = "secret") =
        OpenCodeServerEndpoint(
            baseUrl = baseUrl,
            username = "opencode",
            password = password,
            port = 4096,
            source = OpenCodeServerEndpointSource.DEFAULT_PORT,
            owned = false,
        )

    private fun policy(readTimeoutMs: Int = 300, totalTimeoutMs: Long = 2_000) = OpenCodeServerProbePolicy(
        connectTimeoutMs = 500,
        readTimeoutMs = readTimeoutMs,
        totalTimeoutMs = totalTimeoutMs,
    )

    private fun discovery() = OpenCodeServerDiscovery(OkHttpProbe())

    private fun assertNotReady(
        result: OpenCodeServerProbeResult,
        failure: OpenCodeServerFailure,
        retryable: Boolean,
    ) {
        assertTrue("期望 NotReady，实际 $result", result is OpenCodeServerProbeResult.NotReady)
        result as OpenCodeServerProbeResult.NotReady
        assertEquals(failure, result.failure)
        assertEquals("重试语义不符：$result", retryable, result.retryable)
    }

    @Test
    fun `info 返回根对象 JSON 时判定就绪并携带 pid 与版本`() {
        routes["/api/info"] = 200 to """{"pid":4321,"version":"2.0.18"}"""

        val result = discovery().probe(endpoint())

        assertTrue("期望就绪，实际 $result", result is OpenCodeServerProbeResult.Ready)
        val info = (result as OpenCodeServerProbeResult.Ready).info
        assertEquals(4321L, info.pid)
        assertEquals("2.0.18", info.version)
        assertNotNull("应带 Basic 认证头", lastAuthHeader)
    }

    @Test
    fun `data 包裹的响应体同样判定就绪`() {
        routes["/api/info"] = 200 to """{"data":{"pid":7,"version":"x"}}"""

        val result = discovery().probe(endpoint())

        assertEquals(7L, (result as OpenCodeServerProbeResult.Ready).info.pid)
    }

    @Test
    fun `2xx 但返回 HTML 不得判定就绪且不再重试`() {
        routes["/api/info"] = 200 to "<!doctype html><html><body>SPA</body></html>"

        assertNotReady(discovery().probe(endpoint()), OpenCodeServerFailure.UNREACHABLE, retryable = false)
    }

    @Test
    fun `2xx 但 JSON 无 pid 与版本不得判定就绪`() {
        routes["/api/info"] = 200 to """{"foo":"bar"}"""

        assertNotReady(discovery().probe(endpoint()), OpenCodeServerFailure.UNREACHABLE, retryable = false)
    }

    @Test
    fun `401 与 403 判定为认证失败且不重试`() {
        routes["/api/info"] = 401 to "{}"
        assertNotReady(discovery().probe(endpoint()), OpenCodeServerFailure.AUTH_FAILED, retryable = false)

        routes["/api/info"] = 403 to "{}"
        assertNotReady(discovery().probe(endpoint()), OpenCodeServerFailure.AUTH_FAILED, retryable = false)
    }

    @Test
    fun `503 归类为不可达但可重试`() {
        routes["/api/info"] = 503 to "{}"

        assertNotReady(discovery().probe(endpoint()), OpenCodeServerFailure.UNREACHABLE, retryable = true)
    }

    @Test
    fun `无密码时不携带认证头`() {
        routes["/api/info"] = 200 to """{"pid":1}"""

        discovery().probe(endpoint(password = null))

        assertNull("密码为空不应附加 Authorization", lastAuthHeader)
    }

    @Test
    fun `连接被拒判为不可达且可重试`() {
        val closedPort = ServerSocket(0).use { it.localPort }

        val result = discovery().probe(endpoint(baseUrl = "http://127.0.0.1:$closedPort"))

        assertNotReady(result, OpenCodeServerFailure.UNREACHABLE, retryable = true)
    }

    @Test
    fun `读取超时判为不可达且可重试`() {
        routes["/api/info"] = 200 to """{"pid":1}"""
        handlerDelayMs = 1_500

        val result = discovery().probe(endpoint(), policy(readTimeoutMs = 200))

        assertNotReady(result, OpenCodeServerFailure.UNREACHABLE, retryable = true)
    }

    @Test
    fun `轮询退避后命中就绪`() {
        val probe = ScriptedProbe(
            listOf(
                OpenCodeServerHttpResponse(503, "{}"),
                OpenCodeServerHttpResponse(503, "{}"),
                OpenCodeServerHttpResponse(200, """{"pid":99}"""),
            ),
        )
        val sleeps = mutableListOf<Long>()
        var now = 0L
        val discovery = OpenCodeServerDiscovery(probe, sleeper = { sleeps += it; now += it }, clock = { now })

        val result = discovery.awaitReady(endpoint())

        assertEquals(99L, (result as OpenCodeServerProbeResult.Ready).info.pid)
        assertEquals("退避应为 250ms → 500ms", listOf(250L, 500L), sleeps)
        assertEquals(3, probe.calls)
    }

    @Test
    fun `认证失败立即返回不进入轮询`() {
        val probe = ScriptedProbe(listOf(OpenCodeServerHttpResponse(401, "{}")))
        val sleeps = mutableListOf<Long>()
        val discovery = OpenCodeServerDiscovery(probe, sleeper = { sleeps += it }, clock = { 0L })

        val result = discovery.awaitReady(endpoint())

        assertTrue(result is OpenCodeServerProbeResult.NotReady)
        assertEquals(OpenCodeServerFailure.AUTH_FAILED, (result as OpenCodeServerProbeResult.NotReady).failure)
        assertEquals(1, probe.calls)
        assertTrue("不应有任何等待", sleeps.isEmpty())
    }

    @Test
    fun `总超时后上报 READY_TIMEOUT 且累计等待不超过上限`() {
        val probe = ScriptedProbe(listOf(OpenCodeServerHttpResponse(503, "{}")))
        val sleeps = mutableListOf<Long>()
        var now = 0L
        val discovery = OpenCodeServerDiscovery(probe, sleeper = { sleeps += it; now += it }, clock = { now })

        val result = discovery.awaitReady(endpoint(), policy(totalTimeoutMs = 1_000))

        assertTrue(result is OpenCodeServerProbeResult.NotReady)
        assertEquals(OpenCodeServerFailure.READY_TIMEOUT, (result as OpenCodeServerProbeResult.NotReady).failure)
        assertEquals("累计等待应恰好到总超时上限", 1_000L, sleeps.sum())
        assertTrue("应发生多次重试", probe.calls > 1)
    }

    @Test
    fun `连接异常在轮询中被视为可重试`() {
        val probe = ScriptedProbe(
            listOf(
                ConnectException("Connection refused"),
                OpenCodeServerHttpResponse(200, """{"pid":5}"""),
            ),
        )
        var now = 0L
        val discovery = OpenCodeServerDiscovery(probe, sleeper = { now += it }, clock = { now })

        val result = discovery.awaitReady(endpoint())

        assertEquals(5L, (result as OpenCodeServerProbeResult.Ready).info.pid)
    }

    @Test
    fun `服务信息解析的边界情况`() {
        assertNull("非 JSON", OpenCodeServerDiscovery.parseServerInfo("<html>"))
        assertNull("空串", OpenCodeServerDiscovery.parseServerInfo(""))
        assertNull("JSON 数组", OpenCodeServerDiscovery.parseServerInfo("[]"))
        assertNull("无 pid 与版本", OpenCodeServerDiscovery.parseServerInfo("""{"data":{"foo":1}}"""))
        assertEquals("仅版本也接受", "2.0.18", OpenCodeServerDiscovery.parseServerInfo("""{"version":"2.0.18"}""")?.version)
        assertFalse("非数字 pid 视为缺失", OpenCodeServerDiscovery.parseServerInfo("""{"pid":"abc","version":"v"}""")!!.pid != null)
    }

    /** 按脚本依次返回响应的探测桩（耗尽后重复最后一个响应） */
    private class ScriptedProbe(private val steps: List<Any>) : OpenCodeServerHttpProbe {
        var calls: Int = 0
            private set

        override fun get(
            url: String,
            authHeader: String?,
            connectTimeoutMs: Int,
            readTimeoutMs: Int,
        ): OpenCodeServerHttpResponse {
            val step = steps[minOf(calls, steps.size - 1)]
            calls++
            return when (step) {
                is OpenCodeServerHttpResponse -> step
                is Throwable -> throw step
                else -> throw IllegalStateException("不支持的脚本步骤：$step")
            }
        }
    }
}