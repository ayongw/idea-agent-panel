package com.ayongw.idea.opencode.backend.repository

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Base64

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

    // ==================== 认证与请求 ====================

    /**
     * 按需附加 Basic 认证头（密码为空则不鉴权）
     */
    private fun applyAuthHeader(connection: HttpURLConnection) {
        val secret = password?.takeIf { it.isNotBlank() } ?: return
        val credentials = "$username:$secret".toByteArray(Charsets.UTF_8)
        connection.setRequestProperty("Authorization", "Basic ${Base64.getEncoder().encodeToString(credentials)}")
    }

    private suspend fun <T> executeRequest(
        method: String,
        path: String,
        body: String? = null,
        parse: (String) -> T
    ): Result<T> {
        return try {
            val connection = URI("$base/api$path").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.requestMethod = method
            connection.doOutput = body != null
            applyAuthHeader(connection)

            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode >= 400) {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }
            connection.disconnect()

            if (responseCode !in 200..299) {
                Result.failure(IOException("HTTP $responseCode: $responseBody"))
            } else {
                Result.success(parse(responseBody))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    // ==================== 响应解析 ====================

    /** 取 `{ "data": {...} }` 中的数据对象 */
    private fun dataObject(json: String): JsonObject {
        val root = JsonParser.parseString(json)
        val data = if (root.isJsonObject) root.asJsonObject.get("data") else null
        return data?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("Unexpected response shape: ${json.take(200)}")
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
        return OpenCodeSession(
            id = session.string("id").orEmpty(),
            title = session.string("title").orEmpty(),
            createdAtMillis = time?.long("created") ?: 0L,
            updatedAtMillis = time?.long("updated") ?: 0L,
            agent = session.string("agent"),
            outcome = session.string("outcome"),
            directory = location?.string("directory")
        )
    }

    /** v2 消息为按 `type` 区分的联合类型，仅保留 user / assistant 两类文本消息 */
    private fun parseMessage(message: JsonObject): OpenCodeMessage? {
        val type = message.string("type") ?: return null
        val id = message.string("id").orEmpty()
        val createdMillis = message.getAsJsonObject("time")?.long("created") ?: 0L
        return when (type) {
            "user" -> OpenCodeMessage(id, "user", message.string("text").orEmpty(), createdMillis)
            "assistant" -> OpenCodeMessage(id, "assistant", assistantText(message.getAsJsonArray("content")), createdMillis)
            else -> null
        }
    }

    private fun assistantText(content: JsonArray?): String {
        if (content == null) return ""
        return content.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "text" }
            .mapNotNull { it.string("text") }
            .joinToString("\n")
    }

    private fun encodePath(segment: String): String = URLEncoder.encode(segment, "UTF-8")

    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.long(name: String): Long =
        get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

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
        val directory: String? = null
    )

    data class OpenCodeMessage(
        val id: String,
        val role: String,
        val content: String,
        val createdMillis: Long
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
        const val DEFAULT_USERNAME = "opencode"
    }
}