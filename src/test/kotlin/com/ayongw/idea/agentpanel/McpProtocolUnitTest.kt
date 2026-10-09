package com.ayongw.idea.agentpanel

import com.ayongw.idea.agentpanel.backend.agent.opencode.mcp.McpProtocol
import com.ayongw.idea.agentpanel.backend.agent.opencode.mcp.McpTool
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MCP 报文构造与解析（纯函数） */
class McpProtocolUnitTest {

    @Test
    fun buildsInitializeAndToolsListRequests() {
        val initialize = JsonParser.parseString(McpProtocol.initializeRequest(1)).asJsonObject
        assertEquals("initialize", initialize.get("method").asString)
        assertEquals(1, initialize.get("id").asInt)
        assertEquals(
            McpProtocol.PROTOCOL_VERSION,
            initialize.getAsJsonObject("params").get("protocolVersion").asString
        )
        assertTrue(initialize.getAsJsonObject("params").has("clientInfo"))

        // 通知无 id（服务端不响应）
        val notification = JsonParser.parseString(McpProtocol.initializedNotification()).asJsonObject
        assertEquals("notifications/initialized", notification.get("method").asString)
        assertNull(McpProtocol.idOf(notification))

        assertTrue(McpProtocol.toolsListRequest(2, null).contains("\"params\":{}"))
        assertTrue(McpProtocol.toolsListRequest(2, "c1").contains("\"cursor\":\"c1\""))
    }

    @Test
    fun parsesOnlyJsonObjectLines() {
        assertNull(McpProtocol.parse("listening on stdio..."))
        assertNull(McpProtocol.parse("[1,2]"))
        assertNull(McpProtocol.parse(""))
        assertNotNull(McpProtocol.parse("""{"jsonrpc":"2.0","id":1,"result":{}}"""))
    }

    @Test
    fun extractsToolsSkippingNamelessEntries() {
        val result = JsonParser.parseString(
            """{"tools":[{"name":"a","description":"d"},{"description":"无名"},{"name":""},"x"]}"""
        ).asJsonObject

        assertEquals(listOf(McpTool("a", "d")), McpProtocol.toolsOf(result))
        assertNull(McpProtocol.cursorOf(result))
    }

    @Test
    fun reportsErrorMessageWithFallback() {
        assertEquals(
            "boom",
            McpProtocol.errorMessageOf(JsonParser.parseString("""{"error":{"message":"boom"}}""").asJsonObject)
        )
        assertEquals(
            "服务端返回错误（未提供 message）",
            McpProtocol.errorMessageOf(JsonParser.parseString("""{"error":{"code":-1}}""").asJsonObject)
        )
        assertNull(McpProtocol.errorMessageOf(JsonParser.parseString("""{"result":{}}""").asJsonObject))
    }
}