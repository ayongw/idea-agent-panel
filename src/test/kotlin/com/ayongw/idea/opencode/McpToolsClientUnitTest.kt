package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.mcp.LoginShellPath
import com.ayongw.idea.opencode.backend.mcp.McpToolsClient
import com.ayongw.idea.opencode.backend.mcp.McpToolsResult
import com.ayongw.idea.opencode.shared.McpServerDto
import com.google.gson.JsonParser
import com.sun.net.httpserver.HttpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

/**
 * MCP 工具清单客户端：local（stdio 假进程）与 remote（Streamable HTTP 假服务端）两条通路
 */
class McpToolsClientUnitTest {

    private fun client(loginShellPath: LoginShellPath = LoginShellPath(shell = null)) =
        McpToolsClient(initializeTimeoutMs = 800, listTimeoutMs = 800, loginShellPath = loginShellPath)

    /** 打印给定 JSON 行后保持存活，模拟本地 MCP server */
    private fun fakeStdioServer(vararg payloads: String, keepAliveSeconds: Int = 3): McpServerDto {
        val script = buildString {
            payloads.forEach { payload ->
                append("printf '%s\\n' '").append(payload).append("'\n")
            }
            append("sleep $keepAliveSeconds\n")
        }
        return McpServerDto(name = "fake", type = "local", command = listOf("/bin/sh", "-c", script))
    }

    private fun success(result: McpToolsResult): McpToolsResult.Success {
        assertTrue("期望成功但得到：$result", result is McpToolsResult.Success)
        return result as McpToolsResult.Success
    }

    private fun failure(result: McpToolsResult): McpToolsResult.Failure {
        assertTrue("期望失败但得到：$result", result is McpToolsResult.Failure)
        return result as McpToolsResult.Failure
    }

    // ==================== local ====================

    @Test
    fun stdioListsToolsAndSkipsLogLinesAndNamelessEntries() {
        val server = fakeStdioServer(
            """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""",
            "booting fake mcp server...",
            """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"memory_search","description":"检索记忆"},{"description":"无名条目"}]}}"""
        )

        val result = success(client().listTools(server))

        assertEquals(listOf("memory_search"), result.tools.map { it.name })
        assertEquals("检索记忆", result.tools.single().description)
        assertEquals(null, result.note)
    }

    @Test
    fun stdioFollowsPaginationCursor() {
        val server = fakeStdioServer(
            """{"jsonrpc":"2.0","id":1,"result":{}}""",
            """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"a"}],"nextCursor":"c1"}}""",
            """{"jsonrpc":"2.0","id":3,"result":{"tools":[{"name":"b","description":"第二页"}]}}"""
        )

        val result = success(client().listTools(server))

        assertEquals(listOf("a", "b"), result.tools.map { it.name })
        assertEquals("第二页", result.tools.last().description)
    }

    @Test
    fun stdioReportsServerError() {
        val server = fakeStdioServer("""{"jsonrpc":"2.0","id":1,"error":{"message":"boom"}}""")

        assertEquals("boom", failure(client().listTools(server)).message)
    }

    @Test
    fun stdioReportsTimeoutWhenServerIsSilent() {
        val server = McpServerDto(name = "silent", type = "local", command = listOf("/bin/sh", "-c", "sleep 3"))

        val message = failure(client().listTools(server)).message

        assertTrue("实际：$message", message.contains("超时"))
    }

    @Test
    fun stdioReportsProcessExitWithStderrTail() {
        val server = McpServerDto(
            name = "crash",
            type = "local",
            command = listOf("/bin/sh", "-c", "echo 'cannot start' 1>&2; exit 7")
        )

        val message = failure(client().listTools(server)).message

        assertTrue("实际：$message", message.contains("进程已退出（exit=7）"))
        assertTrue("实际：$message", message.contains("cannot start"))
    }

    @Test
    fun stdioReportsStartFailureForMissingBinary() {
        val server = McpServerDto(name = "missing", type = "local", command = listOf("/nonexistent/mcp-server-xyz"))

        assertTrue(failure(client().listTools(server)).message.startsWith("启动失败"))
    }

    @Test
    fun stdioReportsMissingCommand() {
        val server = McpServerDto(name = "no-command", type = "local")

        assertTrue(failure(client().listTools(server)).message.contains("没有 command"))
    }

    @Test
    fun stdioGivesChildProcessTheLoginShellPath() {
        // 从 Finder 启动的 IDE，进程 PATH 只有 /usr/bin:/bin:...，codegraph/npx 这类裸命令会找不到
        val observed = File.createTempFile("mcp-path", ".txt").apply { deleteOnExit() }
        val server = McpServerDto(name = "fake", type = "local", command = listOf("/bin/sh", "-c", echoPathScript(observed)))
        val shellPath = LoginShellPath(shell = "/bin/zsh", probe = { "/opt/fake-nvm/bin" })

        assertEquals(listOf("t"), success(client(shellPath).listTools(server)).tools.map { it.name })

        val path = observed.readText()
        assertTrue("登录 shell 的条目应排在最前：$path", path.startsWith("/opt/fake-nvm/bin"))
        assertTrue("进程自身 PATH 仍作兜底：$path", path.contains("/bin"))
    }

    @Test
    fun stdioPrefersConfiguredEnvironmentOverProbedPath() {
        // 配置里显式声明的环境变量优先级最高，不被探测结果覆盖
        val observed = File.createTempFile("mcp-env", ".txt").apply { deleteOnExit() }
        val server = McpServerDto(
            name = "fake",
            type = "local",
            command = listOf("/bin/sh", "-c", echoPathScript(observed)),
            environment = mapOf("PATH" to "/only-configured")
        )
        val shellPath = LoginShellPath(shell = "/bin/zsh", probe = { "/opt/fake-nvm/bin" })

        success(client(shellPath).listTools(server))

        assertEquals("/only-configured", observed.readText())
    }

    /** 先把 `$PATH` 落到文件，再回放两份应答，最后保持存活（文件先于应答写入，读结果时不会竞态） */
    private fun echoPathScript(observed: File): String = buildString {
        append("printf '%s' \"\$PATH\" > ").append(observed.absolutePath).append("\n")
        append("printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}'\n")
        append("printf '%s\\n' '{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"t\"}]}}'\n")
        append("sleep 3\n")
    }

    // ==================== remote ====================

    @Test
    fun remoteListsToolsOverStreamableHttpAndEchoesSessionId() {
        val seenSessionId = AtomicReference<String?>(null)
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/mcp") { exchange ->
            val body = exchange.requestBody.bufferedReader().readText()
            when (JsonParser.parseString(body).asJsonObject.get("method").asString) {
                "initialize" -> {
                    exchange.responseHeaders.add("Mcp-Session-Id", "sess-1")
                    respond(
                        exchange,
                        200,
                        """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-06-18"}}""",
                        "application/json"
                    )
                }

                "notifications/initialized" -> {
                    exchange.sendResponseHeaders(202, -1)
                    exchange.close()
                }

                else -> {
                    seenSessionId.set(exchange.requestHeaders.getFirst("Mcp-Session-Id"))
                    val payload = """{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"ping","description":"PONG"}]}}"""
                    // 以 SSE 形式返回，覆盖 data: 行解析
                    respond(exchange, 200, "event: message\ndata: $payload\n\n", "text/event-stream")
                }
            }
        }
        http.start()
        try {
            val server = McpServerDto(
                name = "remote-fake",
                type = "remote",
                url = "http://127.0.0.1:${http.address.port}/mcp"
            )

            val result = success(client().listTools(server))

            assertEquals(listOf("ping"), result.tools.map { it.name })
            assertEquals("PONG", result.tools.single().description)
            assertEquals("sess-1", seenSessionId.get())
        } finally {
            http.stop(0)
        }
    }

    @Test
    fun remoteReportsOAuthAndNetworkFailures() {
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/mcp") { exchange ->
            respond(exchange, 401, """{"error":"unauthorized"}""", "application/json")
        }
        http.start()
        val unreachable = McpServerDto(name = "remote-fake", type = "remote", url = "http://127.0.0.1:${http.address.port}/mcp")
        try {
            assertTrue(failure(client().listTools(unreachable)).message.contains("需授权"))
        } finally {
            http.stop(0)
        }

        // 端口已停 → 网络不可达
        assertTrue(failure(client().listTools(unreachable)).message.contains("网络不可达"))
        // 没配 url
        assertTrue(
            failure(client().listTools(McpServerDto(name = "no-url", type = "remote"))).message.contains("没有 url")
        )
    }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, code: Int, body: String, contentType: String) {
        val bytes = body.toByteArray()
        exchange.responseHeaders.add("Content-Type", contentType)
        exchange.sendResponseHeaders(code, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}