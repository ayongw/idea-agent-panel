package com.ayongw.plugins.opencode.backend.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.squareup.okhttp3.*
import java.io.IOException
import java.lang.reflect.Type
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * OpenCode Server REST API 客户端
 * 对接 OpenCode 内置的会话管理 API
 */
class OpenCodeRestClient(
    private val baseUrl: String,
    private val okHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    private val gson = Gson()
    private val typeTokenSessionList = object : TypeToken<List<OpenCodeSession>>() {}.type
    private val typeTokenSession = object : TypeToken<OpenCodeSession>() {}.type

    /**
     * 健康检查
     */
    suspend fun healthCheck(): Boolean {
        val request = Request.Builder()
            .url("$baseUrl/global/health")
            .get()
            .build()

        return try {
            okHttpClient.newCall(request).execute().use { response ->
                response.isSuccessful
            }
        } catch (e: IOException) {
            false
        }
    }

    /**
     * 获取所有会话列表
     * GET /session
     */
    suspend fun getAllSessions(): Result<List<OpenCodeSession>> {
        val request = Request.Builder()
            .url("$baseUrl/session")
            .get()
            .build()

        return executeRequest(request) { response ->
            val json = response.body?.string() ?: "[]"
            gson.fromJson(json, typeTokenSessionList)
        }
    }

    /**
     * 创建新会话
     * POST /session
     */
    suspend fun createSession(title: String? = null): Result<String> {
        val json = gson.toJson(mapOf("title" to (title ?: "New Session")))
        val requestBody = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json)

        val request = Request.Builder()
            .url("$baseUrl/session")
            .post(requestBody)
            .build()

        return executeRequest(request) { response ->
            val json = response.body?.string() ?: "{}"
            val session = gson.fromJson(json, typeTokenSession)
            session.id
        }
    }

    /**
     * 获取会话详情
     * GET /session/{id}
     */
    suspend fun getSession(sessionId: String): Result<OpenCodeSession> {
        val request = Request.Builder()
            .url("$baseUrl/session/$sessionId")
            .get()
            .build()

        return executeRequest(request) { response ->
            val json = response.body?.string() ?: throw IOException("Empty response")
            gson.fromJson(json, typeTokenSession)
        }
    }

    /**
     * 删除会话
     * DELETE /session/{id}
     */
    suspend fun deleteSession(sessionId: String): Result<Unit> {
        val request = Request.Builder()
            .url("$baseUrl/session/$sessionId")
            .delete()
            .build()

        return executeRequest(request) { response ->
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
        val requestBody = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json)

        val request = Request.Builder()
            .url("$baseUrl/session/$sessionId")
            .patch(requestBody)
            .build()

        return executeRequest(request) { response ->
            val json = response.body?.string() ?: throw IOException("Empty response")
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
        val requestBody = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json)

        val request = Request.Builder()
            .url("$baseUrl/session/$sessionId/prompt_async")
            .post(requestBody)
            .build()

        return executeRequest(request) { response ->
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

        val request = Request.Builder()
            .url("$baseUrl/session/$sessionId/message")
            .get()
            .build()

        return executeRequest(request) { response ->
            val json = response.body?.string() ?: "[]"
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
        val requestBody = RequestBody.create(MediaType.get("application/json; charset=utf-8"), json)

        val request = Request.Builder()
            .url("$baseUrl/session/$sessionId/permissions/$permissionId")
            .post(requestBody)
            .build()

        return executeRequest(request) { response ->
            if (!response.isSuccessful) throw IOException("Failed to reply permission: ${response.code}")
            Unit
        }
    }

    /**
     * 中止执行
     * POST /session/{id}/abort
     */
    suspend fun abortExecution(sessionId: String): Result<Unit> {
        val request = Request.Builder()
            .url("$baseUrl/session/$sessionId/abort")
            .post(RequestBody.create(MediaType.get("application/json"), "{}"))
            .build()

        return executeRequest(request) { response ->
            if (!response.isSuccessful) throw IOException("Failed to abort: ${response.code}")
            Unit
        }
    }

    private suspend fun <T> executeRequest(
        request: Request,
        parse: (Response) -> T
    ): Result<T> {
        return try {
            val response = okHttpClient.newCall(request).execute()
            response.use {
                if (!it.isSuccessful) {
                    val errorBody = it.body?.string() ?: "Unknown error"
                    Result.failure(IOException("HTTP ${it.code}: $errorBody"))
                } else {
                    Result.success(parse(it))
                }
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
        fun getOrThrow(): T = if (this is Success) value else throw exception
    }
}