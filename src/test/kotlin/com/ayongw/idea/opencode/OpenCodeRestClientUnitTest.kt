package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.ayongw.idea.opencode.shared.ToolCallStatus
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    private var lastQuery: String? = null
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
            lastQuery = exchange.requestURI.query
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
    fun promptCarriesTextFilesAndSkills() = runBlocking {
        routes["/api/session/ses_1/prompt"] = """{"data":{"id":"msg_1"}}"""

        val result = client().sendPrompt(
            "ses_1",
            "你好",
            listOf(OpenCodeRestClient.PromptFile("file:///tmp/a.kt", "a.kt", "src")),
            listOf("skill_1")
        )

        assertTrue("发送 prompt 应成功", result.isSuccess())
        assertEquals("应返回服务端创建的 user 消息 id（用于本地回声气泡复用同一 id）", "msg_1", result.getOrThrow())
        assertEquals("POST", lastMethod)
        assertEquals("/api/session/ses_1/prompt", lastPath)
        assertTrue(lastBody!!.contains("\"text\":\"你好\""))
        assertTrue(lastBody!!.contains("\"uri\":\"file:///tmp/a.kt\""))
        assertTrue(lastBody!!.contains("\"name\":\"a.kt\""))
        assertTrue(lastBody!!.contains("\"description\":\"src\""))
        assertTrue(lastBody!!.contains("\"skills\":[{\"id\":\"skill_1\"}]"))
    }

    @Test
    fun promptIdIsBestEffortWhenResponseHasNoId() = runBlocking {
        routes["/api/session/ses_1/prompt"] = """{"data":{"delivery":"steer"}}"""

        val result = client().sendPrompt("ses_1", "你好")

        assertTrue("响应缺 id 时发送仍应成功", result.isSuccess())
        assertEquals("缺 id 时返回空串（调用方回退到本地 id）", "", result.getOrThrow())
    }

    @Test
    fun commandUsesCommandEndpointWithName() = runBlocking {
        routes["/api/session/ses_1/command"] = """{"data":{"id":"msg_2"}}"""

        val result = client().sendCommand("ses_1", "init", "请初始化", emptyList(), listOf("skill_2"))

        assertTrue("执行命令应成功", result.isSuccess())
        assertEquals("msg_2", result.getOrThrow())
        assertEquals("POST", lastMethod)
        assertEquals("/api/session/ses_1/command", lastPath)
        assertTrue(lastBody!!.contains("\"name\":\"init\""))
        assertTrue(lastBody!!.contains("\"text\":\"请初始化\""))
        assertTrue(lastBody!!.contains("\"skills\":[{\"id\":\"skill_2\"}]"))
        assertFalse("无文件附件时不应出现 files 字段", lastBody!!.contains("\"files\""))
    }

    @Test
    fun interruptUsesInterruptEndpoint() = runBlocking {
        routes["/api/session/ses_1/interrupt"] = """{"interrupted":true}"""

        client().interruptSession("ses_1")

        assertEquals("POST", lastMethod)
        assertEquals("/api/session/ses_1/interrupt", lastPath)
    }

    @Test
    fun listCommandsParsesNameAndDescription() = runBlocking {
        routes["/api/command"] = """
            {"data":[{"name":"init","description":"初始化项目"},{"name":"compact"}]}
        """.trimIndent()

        val commands = client().listCommands("/tmp/proj").getOrThrow()

        assertEquals("/api/command", lastPath)
        assertTrue("应带上工作目录", lastQuery!!.contains("location[directory]=/tmp/proj"))
        assertEquals(2, commands.size)
        assertEquals("init", commands[0].name)
        assertEquals("初始化项目", commands[0].description)
        assertNull("缺省描述应为 null", commands[1].description)
    }

    @Test
    fun listReferencesParsesPathAndHidden() = runBlocking {
        routes["/api/reference"] = """
            {"data":[
              {"name":"AGENTS.md","path":"/tmp/proj/AGENTS.md","description":"项目规范"},
              {"name":"global","path":"/home/me/.config/opencode/AGENTS.md","hidden":true}]}
        """.trimIndent()

        val references = client().listReferences().getOrThrow()

        assertEquals(2, references.size)
        assertEquals("/tmp/proj/AGENTS.md", references[0].path)
        assertFalse(references[0].hidden)
        assertTrue("hidden 字段应被解析", references[1].hidden)
    }

    @Test
    fun findEntriesSendsQueryAndLocation() = runBlocking {
        routes["/api/fs/find"] = """
            {"data":[{"path":"src/main/ChatList.kt","type":"file"},{"path":"src/main","type":"directory"}]}
        """.trimIndent()

        val entries = client().findEntries("ChatList", "/tmp/proj", 20).getOrThrow()

        assertEquals("/api/fs/find", lastPath)
        assertTrue(lastQuery!!.contains("query=ChatList"))
        assertTrue(lastQuery!!.contains("limit=20"))
        assertTrue(lastQuery!!.contains("location[directory]=/tmp/proj"))
        assertEquals("src/main/ChatList.kt", entries[0].path)
        assertFalse(entries[0].isDirectory)
        assertTrue("目录项应被识别", entries[1].isDirectory)
    }

    @Test
    fun listDirectorySendsPathAndLocation() = runBlocking {
        routes["/api/fs/list"] = """{"data":[{"path":"src/main","type":"directory"}]}"""

        val entries = client().listDirectory("src", "/tmp/proj").getOrThrow()

        assertEquals("/api/fs/list", lastPath)
        assertTrue(lastQuery!!.contains("path=src"))
        assertTrue(lastQuery!!.contains("location[directory]=/tmp/proj"))
        assertEquals("src/main", entries.single().path)
    }

    @Test
    fun providerNamesMapIdToDisplayName() = runBlocking {
        routes["/api/provider"] = """
            {"data":[{"id":"opencode","name":"OpenCode Zen"},{"id":"copilot"}]}
        """.trimIndent()

        val names = client().listProviderNames().getOrThrow()

        assertEquals("OpenCode Zen", names["opencode"])
        assertEquals("name 缺失时回落 id", "copilot", names["copilot"])
    }

    @Test
    fun modelsMarkFreeWhenAllCostTiersAreZero() = runBlocking {
        routes["/api/model"] = """
            {"data":[
              {"id":"m_free","modelID":"free-1","providerID":"opencode","name":"Free One",
               "limit":{"context":200000,"output":8000},
               "cost":[{"input":0,"output":0,"cache":{"read":0,"write":0}}]},
              {"id":"m_paid","modelID":"paid-1","providerID":"opencode","name":"Paid One",
               "limit":{"context":200000,"output":8000},
               "cost":[{"input":3,"output":15,"cache":{"read":0,"write":0}}]},
              {"id":"m_unknown","modelID":"unknown-1","providerID":"opencode","name":"Unknown"}]}
        """.trimIndent()

        val models = client().listModels().getOrThrow()

        assertTrue("单价全 0 视为免费", models[0].free)
        assertFalse("有单价则非免费", models[1].free)
        assertFalse("无 cost 字段不视为免费", models[2].free)
        assertEquals(200000L, models[0].limitContext)
    }

    @Test
    fun permissionReplySendsDecision() = runBlocking {
        routes["/api/session/ses_1/permission/per_1/reply"] = """{"data":{}}"""

        client().replyPermission("ses_1", "per_1", OpenCodeRestClient.PermissionDecision.ONCE)

        assertEquals("/api/session/ses_1/permission/per_1/reply", lastPath)
        assertTrue(lastBody!!.contains("\"decision\":\"once\""))
    }

    @Test
    fun agentsAreParsedFromDataEnvelope() = runBlocking {
        routes["/api/agent"] = """
            {"location":{"directory":"/tmp/proj"},"data":[
              {"id":"build","name":"Build","description":"默认 agent","mode":"primary","hidden":false},
              {"id":"plan","name":"Plan","mode":"primary","hidden":true}]}
        """.trimIndent()

        val agents = client().listAgents().getOrThrow()

        assertEquals("/api/agent", lastPath)
        assertEquals("opencode:secret", decodeBasic(lastAuthHeader!!))
        assertEquals(2, agents.size)
        assertEquals("build", agents[0].id)
        assertEquals("Build", agents[0].name)
        assertEquals("primary", agents[0].mode)
        assertTrue("hidden 字段应被解析", agents[1].hidden)
    }

    @Test
    fun modelsAreParsedFromDataEnvelope() = runBlocking {
        routes["/api/model"] = """
            {"data":[{"id":"claude-sonnet-5.5","modelID":"claude-sonnet-5.5",
            "providerID":"github-copilot","name":"Claude Sonnet 5.5","limit":{"context":200000,"output":64000}}]}
        """.trimIndent()

        val models = client().listModels().getOrThrow()

        assertEquals("/api/model", lastPath)
        assertEquals(1, models.size)
        assertEquals("claude-sonnet-5.5", models[0].modelID)
        assertEquals("github-copilot", models[0].providerID)
        assertEquals("Claude Sonnet 5.5", models[0].name)
        assertEquals("limit.context 应解析为上下文窗口", 200_000L, models[0].limitContext)
    }

    @Test
    fun sessionUsageFieldsAreParsed() = runBlocking {
        routes["/api/session/ses_1"] = """
            {"data":{"id":"ses_1","title":"标题","time":{"created":1,"updated":2},
            "model":{"id":"mimo-v2.6-flash-free","providerID":"opencode"},
            "cost":0.0123,
            "tokens":{"input":12300,"output":400,"reasoning":50,"cache":{"read":8100,"write":200}}}}
        """.trimIndent()

        val session = client().getSession("ses_1").getOrThrow()

        assertEquals(0.0123, session.costUsd!!, 1e-9)
        assertEquals(12_300L, session.tokens!!.input)
        assertEquals(50L, session.tokens!!.reasoning)
        assertEquals(8_100L, session.tokens!!.cacheRead)
        assertEquals(200L, session.tokens!!.cacheWrite)
        assertEquals("opencode", session.providerId)
        assertEquals("mimo-v2.6-flash-free", session.modelId)
    }

    @Test
    fun sessionUsageFieldsAreNullWhenAbsent() = runBlocking {
        routes["/api/session/ses_1"] = """
            {"data":{"id":"ses_1","title":"无用量","time":{"created":1,"updated":2}}}
        """.trimIndent()

        val session = client().getSession("ses_1").getOrThrow()

        assertNull(session.costUsd)
        assertNull(session.tokens)
        assertNull(session.modelId)
    }

    @Test
    fun switchAgentPostsAgentId() = runBlocking {
        routes["/api/session/ses_1/agent"] = """{"data":{}}"""

        client().switchAgent("ses_1", "plan")

        assertEquals("POST", lastMethod)
        assertEquals("/api/session/ses_1/agent", lastPath)
        assertTrue(lastBody!!.contains("\"agent\":\"plan\""))
    }

    @Test
    fun switchModelPostsModelRef() = runBlocking {
        routes["/api/session/ses_1/model"] = """{"data":{}}"""

        client().switchModel("ses_1", "github-copilot", "claude-sonnet-5.5")

        assertEquals("POST", lastMethod)
        assertEquals("/api/session/ses_1/model", lastPath)
        assertTrue(lastBody!!.contains("\"providerID\":\"github-copilot\""))
        assertTrue(lastBody!!.contains("\"id\":\"claude-sonnet-5.5\""))
    }

    @Test
    fun messagesAreParsedFromUnionTypes() = runBlocking {
        routes["/api/session/ses_1/message"] = """
            {"data":[
              {"id":"msg_1","type":"user","text":"问题","time":{"created":1000}},
              {"id":"msg_2","type":"assistant","time":{"created":2000},
               "tokens":{"input":480,"output":20,"reasoning":0,"cache":{"read":0,"write":0}},
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
        assertEquals("assistant 的 input tokens 是上下文占比分子", 480L, messages[1].inputTokens)
        assertNull("user 消息无 input tokens", messages[0].inputTokens)
        assertNotNull(messages[1].id)
    }

    @Test
    fun assistantTextAndToolPartsAreParsedInOrder() = runBlocking {
        routes["/api/session/ses_1/message"] = """
            {"data":[
              {"id":"msg_1","type":"assistant","time":{"created":1000},
               "content":[
                 {"type":"reasoning","text":"思考"},
                 {"type":"text","text":"我来执行"},
                 {"type":"tool","id":"call_1","name":"shell","executed":false,
                  "state":{"status":"completed","input":{"command":"echo hi"},
                           "content":[{"type":"text","text":"hi\n"}],
                           "metadata":{"status":"completed","truncated":false,"exit":0}},
                  "time":{"created":1001}},
                 {"type":"tool","id":"call_2","name":"read","executed":true,
                  "state":{"status":"running","input":{"path":"/tmp/a"},"metadata":{"status":"running"}},
                  "time":{"created":1002}}
               ]}
            ],"cursor":{}}
        """.trimIndent()

        val message = client().getMessages("ses_1").getOrThrow().single()
        val parts = message.parts

        assertEquals("reasoning 不参与渲染，text/tool 按原顺序保留", 3, parts.size)
        assertEquals("我来执行", (parts[0] as OpenCodeRestClient.OpenCodePart.Text).text)

        val shell = (parts[1] as OpenCodeRestClient.OpenCodePart.Tool).call
        assertEquals("call_1", shell.callId)
        assertEquals("shell", shell.name)
        assertTrue("completed 的入参对象应序列化为 JSON 字符串", shell.input.contains("echo hi"))
        assertEquals("hi\n", shell.output)
        assertEquals(ToolCallStatus.COMPLETED, shell.status)
        assertEquals(0, shell.exit)
        assertFalse(shell.truncated)

        val read = (parts[2] as OpenCodeRestClient.OpenCodePart.Tool).call
        assertEquals("call_2", read.callId)
        assertEquals(ToolCallStatus.RUNNING, read.status)
        assertNull("running 态无 metadata 时退出码为 null", read.exit)
    }

    @Test
    fun toolErrorAndStreamingStatesAreMapped() = runBlocking {
        routes["/api/session/ses_1/message"] = """
            {"data":[
              {"id":"msg_1","type":"assistant","time":{"created":1000},
               "content":[
                 {"type":"tool","id":"call_e","name":"write",
                  "state":{"status":"error","input":{"path":"/root/x"},
                           "error":{"type":"permission","message":"permission denied"}},
                  "time":{"created":1001}},
                 {"type":"tool","id":"call_s","name":"write",
                  "state":{"status":"streaming","input":"{\"path\":"},
                  "time":{"created":1002}}
               ]}
            ],"cursor":{}}
        """.trimIndent()

        val parts = client().getMessages("ses_1").getOrThrow().single().parts

        val error = (parts[0] as OpenCodeRestClient.OpenCodePart.Tool).call
        assertEquals(ToolCallStatus.ERROR, error.status)
        assertEquals("error 态无 content 时应退回结构化错误信息", "permission denied", error.output)
        assertTrue(error.truncated.not())

        val streaming = (parts[1] as OpenCodeRestClient.OpenCodePart.Tool).call
        assertEquals(ToolCallStatus.STREAMING, streaming.status)
        assertEquals("streaming 态的入参是未解析完的字符串", """{"path":""", streaming.input)
    }
}