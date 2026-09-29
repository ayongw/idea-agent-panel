package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.event.OpenCodeEvent
import com.ayongw.idea.opencode.backend.event.OpenCodeEventClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
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
            awaitUntil("应解析出流式文本") { events.any { it is OpenCodeEvent.TextEnded } }

            val ended = events.filterIsInstance<OpenCodeEvent.TextEnded>().last()
            val accumulated = events.filterIsInstance<OpenCodeEvent.TextDelta>()
                .filter { it.assistantMessageId == ended.assistantMessageId && it.ordinal == ended.ordinal }
                .joinToString("") { it.delta }
            assertEquals("ended.text 应等于同消息同段的 delta 累积", ended.text, accumulated)
            assertTrue("应含 execution.succeeded", events.any { it is OpenCodeEvent.ExecutionSucceeded })

            val recorded = server.takeRequest(5, TimeUnit.SECONDS)
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
        val client = client(events, states)

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