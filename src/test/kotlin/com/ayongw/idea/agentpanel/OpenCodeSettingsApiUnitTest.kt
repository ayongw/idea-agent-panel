package com.ayongw.idea.agentpanel

import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.OpenCodeRestClient
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.connectKey
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.getConfigEntries
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.getDefaultModel
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.getInfo
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.getModels
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.getMcpServers
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.getProviders
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.reloadConfig
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.setShell
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetSocketAddress
import java.util.Base64

/**
 * 设置类接口与 opencode v2 契约的对齐验证（本地 HTTP Server 断言真实请求）
 *
 * 覆盖：`/api/config` 顶层数组、`/api/mcp` 的 location 查询参数、`/api/experimental/config` 的 PATCH 体、
 * `/api/integration/{id}/connect/key` 的凭据写入、`/api/location/reload`、`/api/info`
 */
class OpenCodeSettingsApiUnitTest {

    private lateinit var server: HttpServer
    private val routes = mutableMapOf<String, String>()
    private val statuses = mutableMapOf<String, Int>()
    private var lastMethod = ""
    private var lastPath = ""
    private var lastQuery: String? = null
    private var lastAuthHeader: String? = null
    private var lastBody: String? = null
    private var port: Int = 0

    @Before
    fun setUp() {
        routes.clear()
        statuses.clear()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/") { exchange ->
            lastMethod = exchange.requestMethod
            lastPath = exchange.requestURI.path
            lastQuery = exchange.requestURI.rawQuery
            lastAuthHeader = exchange.requestHeaders.getFirst("Authorization")
            lastBody = exchange.requestBody.use { it.readBytes().toString(Charsets.UTF_8) }
                .takeIf { it.isNotEmpty() }
            val code = statuses[lastPath] ?: 200
            val bytes = (routes[lastPath] ?: "{}").toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(code, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        port = server.address.port
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun client() = OpenCodeRestClient("http://127.0.0.1:$port", "opencode", "secret")

    private fun decodeBasic(header: String): String =
        String(Base64.getDecoder().decode(header.removePrefix("Basic ")))

    @Test
    fun configEntriesAreReadFromTopLevelArray() = runBlocking {
        routes["/api/config"] = """
            [{"type":"document","path":"/Users/me/.config/opencode/opencode.jsonc",
              "info":{"mcp":{"servers":{"demo":{"type":"local","command":["x"]}}}}},
             {"type":"directory","path":"/Users/me/.config/opencode"}]
        """.trimIndent()

        val entries = client().getConfigEntries().getOrThrow()

        assertEquals("/api/config", lastPath)
        assertEquals(2, entries.size)
        assertEquals("document", entries[0].get("type").asString)
        assertEquals("/Users/me/.config/opencode/opencode.jsonc", entries[0].get("path").asString)
        assertEquals("opencode:secret", decodeBasic(lastAuthHeader!!))
    }

    @Test
    fun providersAndModelsUnwrapDataEnvelope() = runBlocking {
        routes["/api/provider"] = """{"location":{"directory":"/tmp/p"},"data":[{"id":"hello-tw","name":"两轮"}]}"""
        routes["/api/model"] = """{"location":{"directory":"/tmp/p"},"data":[{"id":"GLM-5.2","providerID":"hello-tw"}]}"""

        val providers = client().getProviders().getOrThrow()
        val models = client().getModels().getOrThrow()

        assertEquals(1, providers.size)
        assertEquals("hello-tw", providers[0].get("id").asString)
        assertEquals("GLM-5.2", models[0].get("id").asString)
    }

    @Test
    fun defaultModelIsNullableWhenAbsent() = runBlocking {
        routes["/api/model/default"] = """{"location":{"directory":"/tmp/p"}}"""
        assertNull(client().getDefaultModel().getOrThrow())

        routes["/api/model/default"] = """{"location":{"directory":"/tmp/p"},"data":{"id":"x","providerID":"y"}}"""
        assertEquals("x", client().getDefaultModel().getOrThrow()!!.get("id").asString)
    }

    @Test
    fun mcpServersCarryEncodedLocationQuery() = runBlocking {
        routes["/api/mcp"] = """{"location":{"directory":"/tmp/p"},"data":[{"name":"codegraph","status":{"status":"connected"}}]}"""

        val servers = client().getMcpServers("/Users/me/workspace project").getOrThrow()

        assertEquals("/api/mcp", lastPath)
        assertEquals("location.directory=%2FUsers%2Fme%2Fworkspace+project", lastQuery)
        assertEquals("codegraph", servers.single().get("name").asString)
    }

    @Test
    fun mcpServersWithoutDirectoryOmitQuery() = runBlocking {
        routes["/api/mcp"] = """{"data":[]}"""

        client().getMcpServers()

        assertNull(lastQuery)
    }

    @Test
    fun setShellPatchesExperimentalConfig() = runBlocking {
        routes["/api/experimental/config"] = ""

        client().setShell("/bin/zsh")

        assertEquals("PATCH", lastMethod)
        assertEquals("/api/experimental/config", lastPath)
        assertTrue("请求体应含 shell", lastBody!!.contains("\"shell\":\"/bin/zsh\""))
    }

    @Test
    fun setShellSendsExplicitNullToReset() = runBlocking {
        routes["/api/experimental/config"] = ""

        client().setShell(null)

        assertTrue("重置时需显式传 null", lastBody!!.contains("\"shell\":null"))
    }

    @Test
    fun connectKeyPostsCredentialToIntegration() = runBlocking {
        routes["/api/integration/hello-tw/connect/key"] = ""

        val result = client().connectKey("hello-tw", "sk-123", label = "API key")

        assertTrue(result.isSuccess())
        assertEquals("POST", lastMethod)
        assertEquals("/api/integration/hello-tw/connect/key", lastPath)
        assertTrue(lastBody!!.contains("\"key\":\"sk-123\""))
        assertTrue(lastBody!!.contains("\"label\":\"API key\""))
    }

    @Test
    fun reloadConfigPostsToLocationReload() = runBlocking {
        routes["/api/location/reload"] = ""

        client().reloadConfig()

        assertEquals("POST", lastMethod)
        assertEquals("/api/location/reload", lastPath)
    }

    @Test
    fun infoReturnsObjectAndFailuresSurfaceAsResultFailure() = runBlocking {
        routes["/api/info"] = """{"version":"2.0.18","pid":1,"urls":["http://127.0.0.1:4096"],"paths":{"tmp":"/tmp"}}"""
        assertEquals("2.0.18", client().getInfo().getOrThrow().get("version").asString)

        statuses["/api/info"] = 503
        val failure = client().getInfo()
        assertTrue("503 应作为失败返回", failure.isFailure())
    }
}