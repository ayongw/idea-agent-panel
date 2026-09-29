package com.ayongw.idea.opencode.backend.repository

import com.ayongw.idea.opencode.shared.ToolCallStatus
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * OpenCode Server v2 REST 客户端
 *
 * - 认证：HTTP Basic（用户名默认 opencode + 密码），密码为空则不鉴权
 * - 基路径：`/api`，响应统一为 `{ "data": ... }` 包裹
 * - 端点契约以 `GET /openapi.json` 为准（opencode serve 默认 http://127.0.0.1:4096）
 */
class OpenCodeRestClient(
    baseUrl: String,
    private val username: String = DEFAULT_USERNAME,
    private val password: String? = null
) {

    private val base: String = baseUrl.trim().trimEnd('/')
    private val gson = Gson()

    /**
     * 探测 Server 是否可用（v2 无 /global/health，用 GET /api/project）
     */
    suspend fun healthCheck(): Boolean = executeRequest("GET", "/project") { true }.isSuccess()

    /**
     * 获取所有会话列表
     * GET /api/session
     */
    suspend fun getAllSessions(): Result<List<OpenCodeSession>> {
        return executeRequest("GET", "/session") { json ->
            parseDataObjects(json).map { parseSession(it) }
        }
    }

    /**
     * 创建新会话
     * POST /api/session
     */
    suspend fun createSession(title: String? = null): Result<String> {
        val body = gson.toJson(if (title.isNullOrBlank()) emptyMap<String, Any>() else mapOf("title" to title))
        return executeRequest("POST", "/session", body) { json ->
            parseSession(dataObject(json)).id
        }
    }

    /**
     * 获取会话详情
     * GET /api/session/{sessionID}
     */
    suspend fun getSession(sessionId: String): Result<OpenCodeSession> {
        return executeRequest("GET", "/session/${encodePath(sessionId)}") { json ->
            parseSession(dataObject(json))
        }
    }

    /**
     * 删除会话
     * DELETE /api/session/{sessionID}
     */
    suspend fun deleteSession(sessionId: String): Result<Unit> {
        return executeRequest("DELETE", "/session/${encodePath(sessionId)}") { Unit }
    }

    /**
     * 重命名会话
     * PATCH /api/session/{sessionID}
     */
    suspend fun renameSession(sessionId: String, newTitle: String): Result<OpenCodeSession> {
        val body = gson.toJson(mapOf("title" to newTitle))
        return executeRequest("PATCH", "/session/${encodePath(sessionId)}", body) { json ->
            parseSession(dataObject(json))
        }
    }

    /**
     * 发送消息
     * POST /api/session/{sessionID}/prompt
     *
     * @param files 附件 uri 列表（如 file:///path/to/file）
     */
    suspend fun sendPrompt(
        sessionId: String,
        text: String,
        files: List<String> = emptyList()
    ): Result<Unit> {
        val body = mutableMapOf<String, Any>("text" to text)
        if (files.isNotEmpty()) {
            body["files"] = files.map { mapOf("uri" to it) }
        }
        return executeRequest("POST", "/session/${encodePath(sessionId)}/prompt", gson.toJson(body)) { Unit }
    }

    /**
     * 获取会话消息（对账用）
     * GET /api/session/{sessionID}/message
     */
    suspend fun getMessages(sessionId: String): Result<List<OpenCodeMessage>> {
        return executeRequest("GET", "/session/${encodePath(sessionId)}/message") { json ->
            parseDataObjects(json).mapNotNull { parseMessage(it) }
        }
    }

    /**
     * 回复权限请求
     * POST /api/session/{sessionID}/permission/{requestID}/reply
     */
    suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        decision: PermissionDecision
    ): Result<Unit> {
        val body = gson.toJson(mapOf("decision" to decision.wire))
        return executeRequest(
            "POST",
            "/session/${encodePath(sessionId)}/permission/${encodePath(requestId)}/reply",
            body
        ) { Unit }
    }

    /**
     * 中止执行
     * POST /api/session/{sessionID}/interrupt
     */
    suspend fun interruptSession(sessionId: String): Result<Unit> {
        return executeRequest("POST", "/session/${encodePath(sessionId)}/interrupt", "{}") { Unit }
    }

    /**
     * 列出可用 Agent（模式）
     * GET /api/agent
     */
    suspend fun listAgents(): Result<List<OpenCodeAgent>> {
        return executeRequest("GET", "/agent") { json ->
            parseDataObjects(json).map { parseAgent(it) }
        }
    }

    /**
     * 列出可用模型
     * GET /api/model
     */
    suspend fun listModels(): Result<List<OpenCodeModel>> {
        return executeRequest("GET", "/model") { json ->
            parseDataObjects(json).map { parseModel(it) }
        }
    }

    /**
     * 切换会话 Agent（模式）
     * POST /api/session/{sessionID}/agent
     */
    suspend fun switchAgent(sessionId: String, agentId: String): Result<Unit> {
        val body = gson.toJson(mapOf("agent" to agentId))
        return executeRequest("POST", "/session/${encodePath(sessionId)}/agent", body) { Unit }
    }

    /**
     * 切换会话模型
     * POST /api/session/{sessionID}/model
     */
    suspend fun switchModel(sessionId: String, providerId: String, modelId: String): Result<Unit> {
        val body = gson.toJson(mapOf("model" to mapOf("providerID" to providerId, "id" to modelId)))
        return executeRequest("POST", "/session/${encodePath(sessionId)}/model", body) { Unit }
    }

    // ==================== 设置类接口 ====================

    /**
     * 配置文档清单（按优先级低→高，含文档路径）
     * GET /api/config
     */
    suspend fun getConfigEntries(): Result<List<JsonObject>> =
        executeRequest("GET", "/config") { parseDataObjects(it) }

    /**
     * 供应商清单
     * GET /api/provider
     */
    suspend fun getProviders(): Result<List<JsonObject>> =
        executeRequest("GET", "/provider") { parseDataObjects(it) }

    /**
     * 模型清单
     * GET /api/model
     */
    suspend fun getModels(): Result<List<JsonObject>> =
        executeRequest("GET", "/model") { parseDataObjects(it) }

    /**
     * 默认模型（无默认时为 null）
     * GET /api/model/default
     */
    suspend fun getDefaultModel(): Result<JsonObject?> =
        executeRequest("GET", "/model/default") { parseDataObjects(it).firstOrNull() }

    /**
     * MCP 服务器与连接状态
     * GET /api/mcp
     *
     * @param directory 定位目录；不传时服务端可能返回空列表，建议显式传入项目目录
     */
    suspend fun getMcpServers(directory: String? = null): Result<List<JsonObject>> =
        executeRequest("GET", "/mcp${directoryQuery(directory)}") { parseDataObjects(it) }

    /**
     * 已注册技能清单
     * GET /api/skill
     */
    suspend fun getSkills(): Result<List<JsonObject>> =
        executeRequest("GET", "/skill") { parseDataObjects(it) }

    /**
     * 集成清单（含供应商的认证方式与已存凭据）
     * GET /api/integration
     */
    suspend fun getIntegrations(): Result<List<JsonObject>> =
        executeRequest("GET", "/integration") { parseDataObjects(it) }

    /**
     * 可用 shell 清单
     * GET /api/config/shell
     */
    suspend fun getShells(): Result<List<JsonObject>> =
        executeRequest("GET", "/config/shell") { parseDataObjects(it) }

    /**
     * 服务信息（version/pid/urls/paths）
     * GET /api/info
     */
    suspend fun getInfo(): Result<JsonObject> =
        executeRequest("GET", "/info") { objectOrData(it) }

    /**
     * 写入全局配置的 shell（v2 唯一可经 HTTP 落盘的配置字段）
     * PATCH /api/experimental/config
     */
    suspend fun setShell(shell: String?): Result<Unit> {
        val body = JsonObject().apply {
            add("shell", if (shell == null) JsonNull.INSTANCE else JsonPrimitive(shell))
        }.toString()
        return executeRequest("PATCH", "/experimental/config", body) { Unit }
    }

    /**
     * 触发配置重载（写入配置文件后兜底使用）
     * POST /api/location/reload
     */
    suspend fun reloadConfig(): Result<Unit> =
        executeRequest("POST", "/location/reload", "{}") { Unit }

    /**
     * 写入集成凭据（apiKey，落 opencode 数据库，非配置文件）
     * POST /api/integration/{integrationID}/connect/key
     */
    suspend fun connectKey(integrationId: String, key: String, label: String? = null): Result<Unit> {
        val body = JsonObject().apply {
            addProperty("key", key)
            if (!label.isNullOrBlank()) addProperty("label", label)
        }.toString()
        return executeRequest("POST", "/integration/${encodePath(integrationId)}/connect/key", body) { Unit }
    }

    /** 拼接 location.directory 查询参数 */
    private fun directoryQuery(directory: String?): String =
        directory?.takeIf { it.isNotBlank() }
            ?.let { "?location.directory=" + URLEncoder.encode(it, "UTF-8") }
            ?: ""

    // ==================== 认证与请求 ====================

    /**
     * 按需附加 Basic 认证头（密码为空则不鉴权）
     */
    private fun applyAuthHeader(connection: HttpURLConnection) {
        authHeaderValue()?.let { connection.setRequestProperty("Authorization", it) }
    }

    /** Basic 认证头取值，密码为空时返回 null（与事件流客户端共用 [OpenCodeAuth]） */
    private fun authHeaderValue(): String? = OpenCodeAuth.basicHeader(username, password)

    private suspend fun <T> executeRequest(
        method: String,
        path: String,
        body: String? = null,
        parse: (String) -> T
    ): Result<T> {
        val uri = URI("$base/api$path")
        return try {
            // HttpURLConnection 不支持 PATCH（JDK 限制），v2 的 session 重命名与 experimental.config 依赖它
            val response = if (method == "PATCH") patchRequest(uri, body) else connectionRequest(method, uri, body)
            if (response.code !in 200..299) {
                // 截断响应体，避免整串原始 JSON 灌进设置页状态栏
                Result.failure(IOException("HTTP ${response.code}: ${response.body.take(200)}"))
            } else {
                Result.success(parse(response.body))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    private fun connectionRequest(method: String, uri: URI, body: String?): RawResponse {
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = method
        connection.doOutput = body != null
        applyAuthHeader(connection)

        if (body != null) {
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }

        val code = connection.responseCode
        val text = if (code >= 400) {
            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
        } else {
            connection.inputStream.bufferedReader().use { it.readText() }
        }
        connection.disconnect()
        return RawResponse(code, text)
    }

    /** PATCH 走 JDK HttpClient（HttpURLConnection 不接受该方法） */
    private suspend fun patchRequest(uri: URI, body: String?): RawResponse = withContext(Dispatchers.IO) {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(READ_TIMEOUT_MS.toLong()))
            .method(
                "PATCH",
                body?.let { HttpRequest.BodyPublishers.ofString(it) } ?: HttpRequest.BodyPublishers.noBody()
            )
        if (body != null) builder.header("Content-Type", "application/json; charset=utf-8")
        authHeaderValue()?.let { builder.header("Authorization", it) }

        val client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(CONNECT_TIMEOUT_MS.toLong()))
            .build()
        val response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        RawResponse(response.statusCode(), response.body() ?: "")
    }

    private class RawResponse(val code: Int, val body: String)

    // ==================== 响应解析 ====================

    /** 取 `{ "data": {...} }` 中的数据对象 */
    private fun dataObject(json: String): JsonObject {
        val root = JsonParser.parseString(json)
        val data = if (root.isJsonObject) root.asJsonObject.get("data") else null
        return data?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("Unexpected response shape: ${json.take(200)}")
    }

    /** 取对象响应：优先 `{ "data": {...} }`，否则取根对象本身（如 /api/info 直接返回对象） */
    private fun objectOrData(json: String): JsonObject {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) throw IOException("Unexpected response shape: ${json.take(200)}")
        val obj = root.asJsonObject
        return obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: obj
    }

    /** 取 `{ "data": [ ... ] }` 中的数据数组 */
    private fun parseDataObjects(json: String): List<JsonObject> {
        val root = JsonParser.parseString(json)
        val data: JsonElement? = if (root.isJsonObject) root.asJsonObject.get("data") else root
        return when {
            data == null || data.isJsonNull -> emptyList()
            data.isJsonArray -> data.asJsonArray.mapNotNull { it as? JsonObject }
            data.isJsonObject -> listOf(data.asJsonObject)
            else -> emptyList()
        }
    }

    private fun parseSession(session: JsonObject): OpenCodeSession {
        val time = session.getAsJsonObject("time")
        val location = session.getAsJsonObject("location")
        val model = session.getAsJsonObject("model")
        return OpenCodeSession(
            id = session.string("id").orEmpty(),
            title = session.string("title").orEmpty(),
            createdAtMillis = time?.long("created") ?: 0L,
            updatedAtMillis = time?.long("updated") ?: 0L,
            agent = session.string("agent"),
            outcome = session.string("outcome"),
            directory = location?.string("directory"),
            costUsd = session.doubleOrNull("cost"),
            tokens = parseTokenUsage(session.getAsJsonObject("tokens")),
            modelId = model?.string("id"),
            providerId = model?.string("providerID")
        )
    }

    /** v2 消息为按 `type` 区分的联合类型，仅保留 user / assistant 两类文本消息 */
    private fun parseMessage(message: JsonObject): OpenCodeMessage? {
        val type = message.string("type") ?: return null
        val id = message.string("id").orEmpty()
        val createdMillis = message.getAsJsonObject("time")?.long("created") ?: 0L
        return when (type) {
            "user" -> OpenCodeMessage(id, "user", message.string("text").orEmpty(), createdMillis)
            "assistant" -> {
                val content = message.getAsJsonArray("content")
                OpenCodeMessage(
                    id = id,
                    role = "assistant",
                    content = assistantText(content),
                    createdMillis = createdMillis,
                    inputTokens = parseTokenUsage(message.getAsJsonObject("tokens"))?.input,
                    parts = parseAssistantParts(content)
                )
            }
            else -> null
        }
    }

    /**
     * 助手消息 `content[]` → 渲染部件（按原顺序）。
     *
     * 实测部件类型：`text` / `reasoning` / `tool`（`ToolState` 四态见 openapi.json）；
     * `reasoning` 暂不渲染（见 TSD-06 §10 遗留）。
     */
    private fun parseAssistantParts(content: JsonArray?): List<OpenCodePart> {
        if (content == null) return emptyList()
        return content.asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { part ->
                when (part.string("type")) {
                    "text" -> part.string("text")?.let { OpenCodePart.Text(it) }
                    "tool" -> parseToolCall(part)?.let { OpenCodePart.Tool(it) }
                    else -> null
                }
            }
            .toList()
    }

    /** `Session.Message.Assistant.Tool` → 内部模型；`state` 缺失视为不可渲染 */
    private fun parseToolCall(part: JsonObject): OpenCodeToolCall? {
        val state = part.getAsJsonObject("state") ?: return null
        val metadata = state.getAsJsonObject("metadata")
        return OpenCodeToolCall(
            callId = part.string("id").orEmpty(),
            name = part.string("name").orEmpty(),
            // streaming 阶段 input 是字符串片段，其余状态是对象
            input = state.get("input")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }.orEmpty(),
            // completed 取 content 文本；error 无 content 时退回结构化错误信息
            output = state.getAsJsonArray("content")
                ?.mapNotNull { (it as? JsonObject)?.string("text") }
                ?.joinToString("")
                .orEmpty()
                .ifBlank { state.getAsJsonObject("error")?.string("message").orEmpty() },
            status = parseToolStatus(state.string("status")),
            exit = metadata?.intOrNull("exit"),
            truncated = metadata?.bool("truncated") ?: false
        )
    }

    private fun parseToolStatus(status: String?): ToolCallStatus = when (status) {
        "streaming" -> ToolCallStatus.STREAMING
        "completed" -> ToolCallStatus.COMPLETED
        "error" -> ToolCallStatus.ERROR
        else -> ToolCallStatus.RUNNING
    }

    /** `TokenUsage.Info` → 内部模型；字段缺失时按 0 计 */
    private fun parseTokenUsage(obj: JsonObject?): OpenCodeTokenUsage? {
        if (obj == null) return null
        val cache = obj.getAsJsonObject("cache")
        return OpenCodeTokenUsage(
            input = obj.long("input"),
            output = obj.long("output"),
            reasoning = obj.long("reasoning"),
            cacheRead = cache?.long("read") ?: 0L,
            cacheWrite = cache?.long("write") ?: 0L
        )
    }

    private fun assistantText(content: JsonArray?): String {
        if (content == null) return ""
        return content.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "text" }
            .mapNotNull { it.string("text") }
            .joinToString("\n")
    }

    private fun parseAgent(agent: JsonObject): OpenCodeAgent = OpenCodeAgent(
        id = agent.string("id").orEmpty(),
        name = agent.string("name").orEmpty(),
        description = agent.string("description"),
        mode = agent.string("mode"),
        hidden = agent.get("hidden")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
    )

    private fun parseModel(model: JsonObject): OpenCodeModel = OpenCodeModel(
        id = model.string("id").orEmpty(),
        modelID = model.string("modelID").orEmpty(),
        providerID = model.string("providerID").orEmpty(),
        name = model.string("name").orEmpty(),
        limitContext = model.getAsJsonObject("limit")?.longOrNull("context")
    )

    private fun encodePath(segment: String): String = URLEncoder.encode(segment, "UTF-8")

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.long(name: String): Long =
        get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

    private fun JsonObject.longOrNull(name: String): Long? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

    private fun JsonObject.intOrNull(name: String): Int? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

    private fun JsonObject.bool(name: String): Boolean? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

    private fun JsonObject.doubleOrNull(name: String): Double? =
        get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

    // ==================== 数据模型 ====================

    /** 权限回复决策（对应 Session PATCH / permission reply 的 decision 枚举） */
    enum class PermissionDecision(val wire: String) {
        ONCE("once"),
        ALWAYS("always"),
        REJECT("reject")
    }

    data class OpenCodeSession(
        val id: String,
        val title: String,
        val createdAtMillis: Long,
        val updatedAtMillis: Long,
        val agent: String? = null,
        val outcome: String? = null,
        val directory: String? = null,
        /** 会话累计花费（USD） */
        val costUsd: Double? = null,
        /** 会话累计 token 用量 */
        val tokens: OpenCodeTokenUsage? = null,
        /** 当前会话模型 ID（`model.id`），用于匹配上下文窗口 */
        val modelId: String? = null,
        /** 当前会话模型供应商（`model.providerID`） */
        val providerId: String? = null
    )

    data class OpenCodeMessage(
        val id: String,
        val role: String,
        val content: String,
        val createdMillis: Long,
        /** 助手消息本次 step 的 input tokens（上下文占比分子）；用户消息或字段缺失为 null */
        val inputTokens: Long? = null,
        /** 助手消息 `content[]` 部件（按原顺序）；用户消息为空 */
        val parts: List<OpenCodePart> = emptyList()
    )

    /** 助手消息 `content[]` 部件（仅保留渲染需要的两类） */
    sealed class OpenCodePart {
        /** 正文片段（同一消息的多个片段仍合并为一个气泡） */
        data class Text(val text: String) : OpenCodePart()

        data class Tool(val call: OpenCodeToolCall) : OpenCodePart()
    }

    /** 工具调用部件（`Session.Message.Assistant.Tool`） */
    data class OpenCodeToolCall(
        /** 调用 ID（`call_` 前缀），即工具卡片气泡 id */
        val callId: String,
        val name: String,
        /** 入参 JSON 字符串 */
        val input: String,
        /** 输出正文（`error` 状态为错误信息） */
        val output: String,
        val status: ToolCallStatus,
        val exit: Int? = null,
        val truncated: Boolean = false
    )

    /** `TokenUsage.Info`：会话/助手消息的 token 用量 */
    data class OpenCodeTokenUsage(
        val input: Long,
        val output: Long,
        val reasoning: Long,
        val cacheRead: Long,
        val cacheWrite: Long
    )

    /** Agent（模式），对应 v2 Agent.Info */
    data class OpenCodeAgent(
        val id: String,
        val name: String,
        val description: String? = null,
        val mode: String? = null,
        val hidden: Boolean = false
    )

    /** 模型，对应 v2 Model.Info */
    data class OpenCodeModel(
        val id: String,
        val modelID: String,
        val providerID: String,
        val name: String,
        /** 上下文窗口（`limit.context`），未知为 null */
        val limitContext: Long? = null
    )

    sealed class Result<out T> {
        data class Success<T>(val value: T) : Result<T>()
        data class Failure(val exception: Throwable) : Result<Nothing>()

        companion object {
            fun <T> success(value: T): Result<T> = Success(value)
            fun <T> failure(exception: Throwable): Result<T> = Failure(exception)
        }

        fun isSuccess(): Boolean = this is Success
        fun isFailure(): Boolean = this is Failure

        fun getOrNull(): T? = if (this is Success) value else null
        fun getOrThrow(): T = if (this is Success) value else throw (this as Failure).exception
    }

    companion object {
        const val DEFAULT_USERNAME = OpenCodeAuth.DEFAULT_USERNAME

        /** 连接超时（毫秒） */
        const val CONNECT_TIMEOUT_MS = 10_000

        /** 读取超时（毫秒） */
        const val READ_TIMEOUT_MS = 30_000
    }
}