package com.ayongw.idea.opencode.backend.mcp

import com.ayongw.idea.opencode.backend.element
import com.ayongw.idea.opencode.backend.str
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** MCP 工具（只取展示所需的两个字段） */
data class McpTool(val name: String, val description: String?)

/** 工具清单拉取结果 */
sealed interface McpToolsResult {
    /**
     * @param note 非致命提示（如分页超限），与工具清单一起展示
     */
    data class Success(val tools: List<McpTool>, val note: String? = null) : McpToolsResult

    data class Failure(val message: String) : McpToolsResult
}

/**
 * MCP JSON-RPC 报文构造与解析（纯函数，便于单测）
 *
 * 只覆盖「列举工具」所需的最小方法集：`initialize` / `notifications/initialized` / `tools/list`。
 * 报文用 Gson 构造，避免手写字符串的转义问题。
 */
object McpProtocol {

    /** 请求的协议版本；本机 opencode 配置里的 local server 实测接受该版本 */
    const val PROTOCOL_VERSION = "2025-06-18"

    /** tools/list 分页上限，防服务端游标死循环 */
    const val MAX_PAGES = 10

    private const val CLIENT_NAME = "opencode-idea-panel"
    private const val CLIENT_VERSION = "0.1.0"

    fun initializeRequest(id: Int): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        addProperty("id", id)
        addProperty("method", "initialize")
        add("params", JsonObject().apply {
            addProperty("protocolVersion", PROTOCOL_VERSION)
            add("capabilities", JsonObject())
            add("clientInfo", JsonObject().apply {
                addProperty("name", CLIENT_NAME)
                addProperty("version", CLIENT_VERSION)
            })
        })
    }.toString()

    /** `notifications/initialized`：通知，无 id、服务端不响应 */
    fun initializedNotification(): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        addProperty("method", "notifications/initialized")
    }.toString()

    fun toolsListRequest(id: Int, cursor: String?): String = JsonObject().apply {
        addProperty("jsonrpc", "2.0")
        addProperty("id", id)
        addProperty("method", "tools/list")
        add("params", JsonObject().apply {
            cursor?.takeIf { it.isNotBlank() }?.let { addProperty("cursor", it) }
        })
    }.toString()

    /** 解析一行报文；非 JSON 或非对象（部分 server 会把日志打到 stdout）返回 null */
    fun parse(rawLine: String): JsonObject? {
        val line = rawLine.trim()
        if (line.isEmpty()) return null
        return runCatching { JsonParser.parseString(line) }
            .getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
    }

    fun idOf(message: JsonObject): Int? =
        message.element("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    fun resultOf(message: JsonObject): JsonObject? =
        message.element("result")?.takeIf { it.isJsonObject }?.asJsonObject

    /** 错误信息：`error.message`，缺失时给通用文案 */
    fun errorMessageOf(message: JsonObject): String? {
        val error = message.element("error")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        return error.str("message")?.takeIf { it.isNotBlank() } ?: "服务端返回错误（未提供 message）"
    }

    /** 解析 `result.tools`：缺名或非对象的条目跳过 */
    fun toolsOf(result: JsonObject): List<McpTool> {
        val array = result.element("tools")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return array.mapNotNull { item ->
            val obj = item?.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val name = obj.str("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            McpTool(name, obj.str("description")?.takeIf { it.isNotBlank() })
        }
    }

    fun cursorOf(result: JsonObject): String? = result.str("nextCursor")?.takeIf { it.isNotBlank() }
}