package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64

/**
 * OpenCodeRestClient 与 opencode v2 契约的对齐验证（本地 HTTP Server 断言真实请求/响应）
 *
 * 契约依据：`opencode serve` 的 `GET /openapi.json`（Basic 认证 + `/api` 前缀 + `{ "data": ... }` 包裹）
 */
class OpenCodeRestClientUnitTest {

    private lateinit var server: HttpServer
    private val routes = mutableMapOf<String, String>()
    private var lastMethod = ""
    private var lastPath = ""
    private var lastAuthHeader: String? = null
    private var lastBody: String? = null
    private var port: Int = 0

    @Before
    fun setUp() {
        routes.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            lastMethod = exchange.requestMethod
            lastPath = exchange.requestURI.path
            lastAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            lastBody = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                .takeIf { it.isNotEmpty() }
            val bytes = (routes[lastPath] ?: "{}").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
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

    private fun client(password: String? = "secret") =
        OpenCodeRestClient("http://127.0.0.1:$port", "opencode", password)

    private fun decodeBasic(header: String): String =
        String(Base64.getDecoder().decode(header.removePrefix("Basic ")))

    @Test
    fun sessionsAreRequestedUnderApiPrefixWithBasicAuth() = runBlocking {
        routes["/api/session"] = """
            {"data":[{"id":"ses_1","title":"标题","agent":"build","outcome":"interrupted",
            "time":{"created":1790566425097,"updated":1790608302576},
            "location":{"directory":"/tmp/proj"}}],"cursor":{"previous":null,"next":null}}
        """.trimIndent()

        val result = client().getAllSessions()

        assertTrue("GET /api/session 应成功", result.isSuccess())
        assertEquals("/api/session", lastPath)
        assertEquals("Basic 认证头应可解码为 用户名:密码", "opencode:secret", decodeBasic(lastAuthHeader!!))
        val session = result.getOrThrow().single()
        assertEquals("ses_1", session.id)
        assertEquals("标题", session.title)
        assertEquals(1790566425097L, session.createdAtMillis)
        assertEquals("/tmp/proj", session.directory)
    }

    @Test
    fun noAuthHeaderWhenPasswordIsBlank() = runBlocking {
        routes["/api/session"] = """{"data":[],"cursor":{}}"""

        client(password = "   ").getAllSessions()

        assertNull("密码为空白时不应带认证头", lastAuthHeader)
    }

    @Test
    fun healthCheckProbesApiProject() = runBlocking {
        routes["/api/project"] = """[{"id":"proj_1"}]"""

        val healthy = client().healthCheck()

        assertTrue("探测应成功", healthy)
        assertEquals("/api/project", lastPath)
        assertEquals("Bearer 之外必须用 Basic", "opencode:secret", decodeBasic(lastAuthHeader!!))
    }

    @Test
    fun createSessionPostsTitleAndReadsDataEnvelope() = runBlocking {
        routes["/api/session"] = """{"data":{"id":"ses_new","title":"新会话","time":{"created":1,"updated":2}}}"""

        val result = client().createSession("新会话")

        assertEquals("POST", lastMethod)
        assertEquals("ses_new", result.getOrThrow())
        assertTrue("请求体应带 title", lastBody!!.contains("\"title\":\"新会话\""))
    }

    @Test
    fun promptCarriesTextAndFileUris() = runBlocking {
        routes["/api/session/ses_1/prompt"] = """{"data":{"id":"msg_1"}}"""

        val result = client().sendPrompt("ses_1", "你好", listOf("file:///tmp/a.kt"))

        assertTrue("发送 prompt 应成功", result.isSuccess())
        assertEquals("POST", lastMethod)
        assertEquals("/api/session/ses_1/prompt", lastPath)
        assertTrue(lastBody!!.contains("\"text\":\"你好\""))
        assertTrue(lastBody!!.contains("\"uri\":\"file:///tmp/a.kt\""))
    }

    @Test
    fun interruptUsesInterruptEndpoint() = runBlocking {
        routes["/api/session/ses_1/interrupt"] = """{"interrupted":true}"""

        client().interruptSession("ses_1")

        assertEquals("POST", lastMethod)
        assertEquals("/api/session/ses_1/interrupt", lastPath)
    }

    @Test
    fun permissionReplySendsDecision() = runBlocking {
        routes["/api/session/ses_1/permission/per_1/reply"] = """{"data":{}}"""

        client().replyPermission("ses_1", "per_1", OpenCodeRestClient.PermissionDecision.ONCE)

        assertEquals("/api/session/ses_1/permission/per_1/reply", lastPath)
        assertTrue(lastBody!!.contains("\"decision\":\"once\""))
    }

    @Test
    fun messagesAreParsedFromUnionTypes() = runBlocking {
        routes["/api/session/ses_1/message"] = """
            {"data":[
              {"id":"msg_1","type":"user","text":"问题","time":{"created":1000}},
              {"id":"msg_2","type":"assistant","time":{"created":2000},
               "content":[{"type":"text","text":"答案A"},{"type":"reasoning","text":"思考"},{"type":"text","text":"答案B"}]},
              {"id":"msg_3","type":"system","time":{"created":3000}}
            ],"cursor":{}}
        """.trimIndent()

        val messages = client().getMessages("ses_1").getOrThrow()

        assertEquals("只保留 user/assistant，两类之外的消息应过滤", 2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("问题", messages[0].content)
        assertEquals(2000L, messages[1].createdMillis)
        assertEquals("assistant 应拼接 text 片段并跳过多余类型", "答案A\n答案B", messages[1].content)
        assertNotNull(messages[1].id)
    }
}