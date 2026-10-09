package com.ayongw.idea.agentpanel

import com.ayongw.idea.agentpanel.backend.agent.opencode.event.OpenCodeEvent
import com.ayongw.idea.agentpanel.backend.agent.opencode.event.OpenCodeEventClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 事件流客户端的连接/重连/鉴权行为验证：用 MockWebServer 回放真实抓帧 fixture
 */
class OpenCodeEventClientUnitTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader.getResource("sse/$name")) { "缺少 fixture: sse/$name" }.readText()

    private fun sseResponse(body: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun client(
        events: MutableList<OpenCodeEvent>,
        states: MutableList<OpenCodeEventClient.State> = CopyOnWriteArrayList()
    ) = OpenCodeEventClient(
        baseUrl = server.url("/").toString(),
        username = "opencode",
        password = "secret",
        onEvent = { events += it },
        onStateChanged = { states += it },
        initialBackoffMillis = 20,
        maxBackoffMillis = 40,
        readTimeoutMillis = 3_000
    )

    @Test
    fun replaysRealFixtureStreamWithBasicAuth() {
        server.enqueue(sseResponse(fixture("real-session-success.txt")))
        val events = CopyOnWriteArrayList<OpenCodeEvent>()
        val client = client(events)

        try {
            client.start()
            // 必须等**执行终态**，不能等中途的 TextEnded：
            // okhttp-sse 的 EventSource.Listener 由单个读取线程串行派发，事件顺序即 fixture 顺序，
            // 而 execution.succeeded 排在 text.ended 之后（中间还隔 step.streamed / step.ended /
            // usage.updated）。只等 TextEnded 时，只要解析还没读到终态就已返回 → 下面那条
            // 「应含 execution.succeeded」断言偶发失败。改为等终态，两条断言都必然成立，
            // 同时仍然能捕获「终态事件真的没来」（等满 5s 报超时）。
            awaitUntil("应解析出执行终态") { events.any { it is OpenCodeEvent.ExecutionSucceeded } }

            val ended = events.filterIsInstance<OpenCodeEvent.TextEnded>().last()
            val accumulated = events.filterIsInstance<OpenCodeEvent.TextDelta>()
                .filter { it.assistantMessageId == ended.assistantMessageId && it.ordinal == ended.ordinal }
                .joinToString("") { it.delta }
            assertEquals("ended.text 应等于同消息同段的 delta 累积", ended.text, accumulated)

            // fixture 的语义本身就是顺序断言的内容：正文结束早于执行终态
            assertTrue(
                "执行终态应晚于正文结束到达（事件顺序保真）",
                events.indexOfFirst { it is OpenCodeEvent.ExecutionSucceeded } >
                    events.indexOfFirst { it is OpenCodeEvent.TextEnded }
            )

            val recorded = server.takeRequest(5, TimeUnit.SECONDS)
            assertNotNull("应收到 /api/event 请求", recorded)
            assertEquals("/api/event", recorded!!.path)
            val expectedAuth = "Basic " + Base64.getEncoder().encodeToString("opencode:secret".toByteArray())
            assertEquals(expectedAuth, recorded.getHeader("Authorization"))
            assertEquals("text/event-stream", recorded.getHeader("Accept"))
        } finally {
            client.stop()
        }
    }

    @Test
    fun emitsNothingForHeartbeatOnlyStream() {
        server.enqueue(sseResponse(": heartbeat\n\n: heartbeat\n\n"))
        val events = CopyOnWriteArrayList<OpenCodeEvent>()
        val states = CopyOnWriteArrayList<OpenCodeEventClient.State>()
        val client = client(events, states)

        try {
            client.start()
            awaitUntil("应建立连接") { states.contains(OpenCodeEventClient.State.CONNECTED) }
            Thread.sleep(200)
            assertTrue("心跳注释帧不应产生事件", events.isEmpty())
        } finally {
            client.stop()
        }
    }

    @Test
    fun reconnectsAfterStreamClosedAndDeliversAgain() {
        server.enqueue(sseResponse(fixture("real-session-error.txt")))
        server.enqueue(sseResponse(fixture("real-session-error.txt")))
        val events = CopyOnWriteArrayList<OpenCodeEvent>()
        val states = CopyOnWriteArrayList<OpenCodeEventClient.State>()
        val connectedTwice = CountDownLatch(2)
        val client = OpenCodeEventClient(
            baseUrl = server.url("/").toString(),
            username = "opencode",
            password = "secret",
            onEvent = { events += it },
            onStateChanged = {
                states += it
                if (it == OpenCodeEventClient.State.CONNECTED) connectedTwice.countDown()
            },
            initialBackoffMillis = 20,
            maxBackoffMillis = 40,
            readTimeoutMillis = 3_000
        )

        try {
            client.start()
            assertTrue("应在断开后自动重连", connectedTwice.await(5, TimeUnit.SECONDS))
            awaitUntil("重连后应再次收到事件") { events.count { it is OpenCodeEvent.ExecutionFailed } >= 2 }
            assertTrue("应记录 RECONNECTING 状态", states.contains(OpenCodeEventClient.State.RECONNECTING))
        } finally {
            client.stop()
        }
    }

    @Test
    fun unauthorizedStopsReconnecting() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"_tag":"UnauthorizedError"}"""))
        val events = CopyOnWriteArrayList<OpenCodeEvent>()
        val states = CopyOnWriteArrayList<OpenCodeEventClient.State>()
        val client = client(events, states)

        try {
            client.start()
            awaitUntil("应进入 UNAUTHORIZED 状态") { states.contains(OpenCodeEventClient.State.UNAUTHORIZED) }
            Thread.sleep(200)
            assertEquals("凭据无效不应重连", 1, server.requestCount)
            assertTrue("状态应停在 UNAUTHORIZED", client.currentState == OpenCodeEventClient.State.UNAUTHORIZED)
        } finally {
            client.stop()
        }
    }

    @Test
    fun stopClosesConnectionAndRequests() {
        server.enqueue(sseResponse(fixture("real-session-success.txt")))
        val events = CopyOnWriteArrayList<OpenCodeEvent>()
        val states = CopyOnWriteArrayList<OpenCodeEventClient.State>()
        // 大 backoff：fixture 流立即结束会触发重连排程，20ms 快速重连在负载高时
        // 会与 stop() 赛跑导致断言偶发失败；此处只验证 stop 语义，用 5s backoff 消除竞态
        val client = OpenCodeEventClient(
            baseUrl = server.url("/").toString(),
            username = "opencode",
            password = "secret",
            onEvent = { events += it },
            onStateChanged = { states += it },
            initialBackoffMillis = 5_000,
            maxBackoffMillis = 5_000,
            readTimeoutMillis = 3_000
        )

        client.start()
        awaitUntil("应建立连接") { states.contains(OpenCodeEventClient.State.CONNECTED) }
        client.stop()

        assertEquals(OpenCodeEventClient.State.STOPPED, client.currentState)
        val countAfterStop = server.requestCount
        Thread.sleep(200)
        assertEquals("stop 后不应再发起请求", countAfterStop, server.requestCount)
    }

    private fun awaitUntil(message: String, timeoutMillis: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(20)
        }
        fail("等待超时：$message")
    }
}