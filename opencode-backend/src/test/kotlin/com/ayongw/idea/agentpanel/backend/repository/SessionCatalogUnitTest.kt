package com.ayongw.idea.agentpanel.backend.agent.opencode.repository

import com.ayongw.idea.agentpanel.shared.ChatMessage
import com.ayongw.idea.agentpanel.shared.PendingPermissionDto
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.time.LocalDateTime

/**
 * 会话切换与列表加载的一致性（本地 HTTP Server 断言真实请求/响应）
 *
 * 两条底线：
 * 1. 切换会话**先加载成功再改 id** —— 否则加载失败表现为「高亮跳过去、内容还是旧的」
 * 2. 会话列表**始终带工作区目录** —— 否则服务端返回全机会话，外目录会话切过去一片空白
 */
class SessionCatalogUnitTest {

    private lateinit var server: HttpServer

    /** path → (HTTP 状态码, 响应体) */
    private val routes = mutableMapOf<String, Pair<Int, String>>()

    /** 每条请求的 "METHOD /api/xxx?query"，用于断言出参 */
    private val requests = mutableListOf<String>()

    private val messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val allSessions = MutableStateFlow<List<OpenCodeSession>>(emptyList())
    private val serverConnected = MutableStateFlow(true)
    private val running = MutableStateFlow(false)
    private val permission = MutableStateFlow<PendingPermissionDto?>(null)
    private var currentSessionId: String? = null
    private var resetCount = 0

    @Before
    fun setUp() {
        routes.clear()
        requests.clear()
        currentSessionId = "ses_old"
        messages.value = listOf(oldBubble())
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += exchange.requestMethod + " " + path +
                (exchange.requestURI.rawQuery?.let { "?$it" } ?: "")
            val response = routes[path]
            val status = response?.first ?: 404
            val body = response?.second ?: """{"error":"not found"}"""
            val bytes = body.toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun oldBubble() = ChatMessage(
        id = "msg_old",
        content = "旧会话内容",
        author = "Me",
        isMyMessage = true,
        timestamp = LocalDateTime.of(2026, 10, 8, 10, 0),
        type = ChatMessage.ChatMessageType.TEXT
    )

    /** 登记一条 200 响应 */
    private fun ok(path: String, body: String) {
        routes[path] = 200 to body
    }

    private fun catalog(initialDirectory: String? = null) = SessionCatalog(
        messagesState = messages,
        allSessionsState = allSessions,
        serverConnectedState = serverConnected,
        runningState = running,
        permissionState = permission,
        messageMapper = MessageMapper("AI"),
        restClientProvider = { OpenCodeRestClient("http://127.0.0.1:${server.address.port}", "opencode", "secret") },
        currentSessionIdProvider = { currentSessionId },
        onCurrentSessionChanged = { currentSessionId = it },
        eventStreamReset = { resetCount++ },
        initialDirectory = initialDirectory
    )

    private fun messageOk(text: String = "新会话内容") =
        """{"data":[{"id":"msg_new","type":"user","text":"$text","time":{"created":1759300000000}}],"cursor":{}}"""

    private fun sessionsOk() = """
        {"data":[
          {"id":"ses_in","title":"本工作区","time":{"created":1,"updated":1},"location":{"directory":"/tmp/proj"}},
          {"id":"ses_out","title":"别的项目","time":{"created":2,"updated":2},"location":{"directory":"/tmp/other"}}
        ],"cursor":{}}
    """.trimIndent()

    // ==================== 切换会话 ====================

    @Test
    fun `加载失败时不切换 currentSessionId 也不清空旧消息`() = runBlocking {
        routes["/api/session/ses_new/message"] = 500 to """{"error":"SessionNotFoundError"}"""

        val ok = catalog().switchSession("ses_new")

        assertFalse("加载失败应返回 false", ok)
        assertEquals("失败时 currentSessionId 必须留在原会话", "ses_old", currentSessionId)
        assertEquals("失败时不清空消息（否则旧会话内容永久消失）", listOf("msg_old"), messages.value.map { it.id })
        assertEquals("失败仍需清一次流式缓冲", 1, resetCount)
    }

    @Test
    fun `加载成功才切换 currentSessionId 并装载消息`() = runBlocking {
        ok("/api/session/ses_new/message", messageOk())

        val ok = catalog().switchSession("ses_new")

        assertTrue("加载成功应返回 true", ok)
        assertEquals("成功后才切 currentSessionId", "ses_new", currentSessionId)
        assertEquals(listOf("msg_new"), messages.value.map { it.id })
        assertEquals("新会话内容", messages.value.single().content)
    }

    @Test
    fun `切到同一会话失败时 currentSessionId 不被污染`() = runBlocking {
        routes["/api/session/ses_old/message"] = 500 to """{"error":"boom"}"""

        catalog().switchSession("ses_old")

        assertEquals("失败的切换不应改写 id", "ses_old", currentSessionId)
        assertEquals("消息保持旧内容", listOf("msg_old"), messages.value.map { it.id })
    }

    // ==================== 会话列表作用域 ====================

    @Test
    fun `无参 loadSessions 仍按构造期目录请求并过滤外目录会话`() = runBlocking {
        ok("/api/session", sessionsOk())

        // initialDirectory 模拟 Project.basePath；不传任何参数（等同模型 init 的首拉）
        catalog(initialDirectory = "/tmp/proj").loadSessions()

        assertEquals(
            "无参调用也必须带 directory（否则服务端返回全机会话）",
            listOf("GET /api/session?directory=%2Ftmp%2Fproj"),
            requests
        )
        assertEquals("外目录会话应在客户端被滤掉", listOf("ses_in"), allSessions.value.map { it.id })
    }

    @Test
    fun `显式传入的目录覆盖构造期目录并作用于后续内部刷新`() = runBlocking {
        ok("/api/session", sessionsOk())
        val catalog = catalog(initialDirectory = "/tmp/proj")

        catalog.loadSessions("/tmp/other")
        catalog.loadSessions()

        assertEquals(
            "首次按显式目录，内部刷新沿用最近一次目录",
            listOf(
                "GET /api/session?directory=%2Ftmp%2Fother",
                "GET /api/session?directory=%2Ftmp%2Fother"
            ),
            requests
        )
    }

    @Test
    fun `目录未配置时不带查询参数也不做过滤`() = runBlocking {
        ok("/api/session", sessionsOk())

        catalog(initialDirectory = null).loadSessions()

        assertEquals(listOf("GET /api/session"), requests)
        assertEquals("无目录可依据时保留全部会话", 2, allSessions.value.size)
    }

    @Test
    fun `目录为空白串等同未配置且不污染后续显式目录`() = runBlocking {
        ok("/api/session", sessionsOk())

        val catalog = catalog(initialDirectory = "   ")
        catalog.loadSessions()
        catalog.loadSessions("/tmp/proj")

        assertEquals(
            "空白串不应被记成工作区目录，显式传入后应立即按新目录请求",
            listOf("GET /api/session", "GET /api/session?directory=%2Ftmp%2Fproj"),
            requests
        )
        assertEquals(listOf("ses_in"), allSessions.value.map { it.id })
    }

    @Test
    fun `会话列表加载失败时标记服务端不可达且保留旧列表`() = runBlocking {
        ok("/api/session", sessionsOk())
        val catalog = catalog(initialDirectory = "/tmp/proj")
        catalog.loadSessions()
        serverConnected.value = true

        routes["/api/session"] = 500 to """{"error":"boom"}"""
        catalog.loadSessions()

        assertFalse("失败应标记服务端不可达", serverConnected.value)
        assertEquals("失败保留上一次列表（不把拉取失败当成没有会话）", listOf("ses_in"), allSessions.value.map { it.id })
    }
}
