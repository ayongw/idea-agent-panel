package com.ayongw.idea.opencode.backend.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.lang.reflect.Type
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * OpenCode Server REST API 客户端
 * 使用 Java 内置 HttpURLConnection，避免外部依赖
 */
class OpenCodeRestClient(
    private val baseUrl: String,
    private val password: String? = null
) {

    private val gson = Gson()
    private val typeTokenSessionList = object : TypeToken<List<OpenCodeSession>>() {}.type
    private val typeTokenSession = object : TypeToken<OpenCodeSession>() {}.type

    /**
     * 设置密码（用于认证）
     */
    fun setPassword(password: String?) {
        // 这里只是占位，实际通过 executeRequest 传递
    }

    /**
     * 健康检查
     */
    suspend fun healthCheck(): Boolean {
        return try {
            val url = URL("$baseUrl/global/health")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.requestMethod = "GET"
            connection.connect()
            connection.responseCode == 200
        } catch (e: IOException) {
            false
        }
    }

    /**
     * 获取所有会话列表
     * GET /session
     */
    suspend fun getAllSessions(): Result<List<OpenCodeSession>> {
        return executeRequest("GET", "/session") { response ->
            val json = response.body ?: "[]"
            gson.fromJson(json, typeTokenSessionList)
        }
    }

    /**
     * 创建新会话
     * POST /session
     */
    suspend fun createSession(title: String? = null): Result<String> {
        val json = gson.toJson(mapOf("title" to (title ?: "New Session")))
        return executeRequest<String>("POST", "/session", json) { response ->
            val json = response.body ?: "{}"
            val session = gson.fromJson(json, typeTokenSession) as OpenCodeSession
            session.id
        }
    }

    /**
     * 获取会话详情
     * GET /session/{id}
     */
    suspend fun getSession(sessionId: String): Result<OpenCodeSession> {
        return executeRequest("GET", "/session/$sessionId") { response ->
            val json = response.body ?: throw IOException("Empty response")
            gson.fromJson(json, typeTokenSession)
        }
    }

    /**
     * 删除会话
     * DELETE /session/{id}
     */
    suspend fun deleteSession(sessionId: String): Result<Unit> {
        return executeRequest("DELETE", "/session/$sessionId") { response ->
            if (!response.isSuccessful) throw IOException("Failed to delete session: ${response.code}")
            Unit
        }
    }

    /**
     * 重命名会话
     * PATCH /session/{id}
     */
    suspend fun renameSession(sessionId: String, newTitle: String): Result<OpenCodeSession> {
        val json = gson.toJson(mapOf("title" to newTitle))
        return executeRequest("PATCH", "/session/$sessionId", json) { response ->
            val json = response.body ?: throw IOException("Empty response")
            gson.fromJson(json, typeTokenSession)
        }
    }

    /**
     * 发送消息（流式）
     * POST /session/{id}/prompt_async
     */
    suspend fun sendPromptAsync(
        sessionId: String,
        prompt: String,
        contextFiles: List<String> = emptyList()
    ): Result<Unit> {
        val json = gson.toJson(mapOf(
            "prompt" to prompt,
            "contextFiles" to contextFiles
        ))
        return executeRequest("POST", "/session/$sessionId/prompt_async", json) { response ->
            if (!response.isSuccessful) throw IOException("Failed to send prompt: ${response.code}")
            Unit
        }
    }

    /**
     * 获取会话消息（对账用）
     * GET /session/{id}/message
     */
    suspend fun getMessages(sessionId: String): Result<List<OpenCodeMessage>> {
        val typeTokenMessageList = object : TypeToken<List<OpenCodeMessage>>() {}.type

        return executeRequest("GET", "/session/$sessionId/message") { response ->
            val json = response.body ?: "[]"
            gson.fromJson(json, typeTokenMessageList)
        }
    }

    /**
     * 回复权限请求
     * POST /session/{id}/permissions/{permissionId}
     */
    suspend fun replyPermission(
        sessionId: String,
        permissionId: String,
        allow: Boolean
    ): Result<Unit> {
        val json = gson.toJson(mapOf("allow" to allow))
        return executeRequest("POST", "/session/$sessionId/permissions/$permissionId", json) { response ->
            if (!response.isSuccessful) throw IOException("Failed to reply permission: ${response.code}")
            Unit
        }
    }

    /**
     * 中止执行
     * POST /session/{id}/abort
     */
    suspend fun abortExecution(sessionId: String): Result<Unit> {
        return executeRequest("POST", "/session/$sessionId/abort", "{}") { response ->
            if (!response.isSuccessful) throw IOException("Failed to abort: ${response.code}")
            Unit
        }
    }

    private suspend fun <T> executeRequest(
        method: String,
        path: String,
        body: String? = null,
        parse: (HttpResponse) -> T
    ): Result<T> {
        return try {
            val url = URL("$baseUrl$path")
            val connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 30000
            connection.requestMethod = method
            connection.doOutput = body != null

            // 添加认证头
            password?.let {
                connection.setRequestProperty("Authorization", "Bearer $it")
            }

            if (body != null) {
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }

            val responseCode = connection.responseCode
            val responseBody = if (connection.responseCode >= 400) {
                connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } else {
                connection.inputStream.bufferedReader().use { it.readText() }
            }

            val response = HttpResponse(code = connection.responseCode, body = responseBody)

            if (!response.isSuccessful) {
                Result.failure(IOException("HTTP ${response.code}: ${response.body}"))
            } else {
                Result.success(parse(response))
            }
        } catch (e: IOException) {
            Result.failure(e)
        }
    }

    // ==================== 数据模型 ====================

    data class OpenCodeSession(
        val id: String,
        val title: String,
        val createdAt: String,
        val updatedAt: String,
        val messageCount: Int = 0,
        val lastMessagePreview: String? = null
    )

    data class OpenCodeMessage(
        val id: String,
        val role: String, // "user" | "assistant" | "system"
        val content: String,
        val timestamp: String,
        val type: String? = null,
        val metadata: Map<String, Any>? = null
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

    private data class HttpResponse(
        val code: Int,
        val body: String
    ) {
        val isSuccessful: Boolean = code in 200..299
    }
}