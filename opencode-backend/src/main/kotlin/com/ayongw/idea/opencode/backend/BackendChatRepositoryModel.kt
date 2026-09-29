@file:Suppress("UnstableApiUsage")

package com.ayongw.idea.opencode.backend

import com.ayongw.idea.opencode.backend.repository.AIResponseGenerator
import com.ayongw.idea.opencode.backend.repository.ChatMessageFactory
import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ChatMessageDto
import com.ayongw.idea.opencode.shared.toChatMessageDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

@Service(Service.Level.PROJECT)
class BackendChatRepositoryModel {
    companion object {
        /** 默认 Server 地址 */
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:4096"

        fun getInstance(project: Project): BackendChatRepositoryModel {
            return project.getService(BackendChatRepositoryModel::class.java)
        }
    }

    /** 当前活跃会话 ID */
    private var currentSessionId: String? = null

    /** Server 连接地址（由设置页下发覆盖） */
    @Volatile
    private var serverUrl: String = DEFAULT_SERVER_URL

    /** Basic 认证用户名（opencode serve 默认 opencode） */
    @Volatile
    private var username: String = OpenCodeRestClient.DEFAULT_USERNAME

    /** Basic 认证密码（opencode serve 启动时打印，环境变量 OPENCODE_SERVER_PASSWORD 兜底） */
    @Volatile
    private var password: String = System.getenv("OPENCODE_SERVER_PASSWORD") ?: ""

    /** OpenCode REST 客户端（配置变更时重建） */
    @Volatile
    private var restClient = OpenCodeRestClient(serverUrl, username, password)

    /** 本地消息缓存（当前会话的消息） */
    private val _messages = MutableStateFlow(emptyList<ChatMessage>())

    /** 所有会话列表缓存 */
    private val _allSessions = MutableStateFlow(emptyList<OpenCodeRestClient.OpenCodeSession>())

    /** 服务器连接状态 */
    private val _serverConnected = MutableStateFlow(false)

    /** 消息工厂（用于本地模拟模式） */
    private val chatMessageFactory = ChatMessageFactory("AI Buddy", "Super Engineer")
    private val aiResponseGenerator = AIResponseGenerator()

    init {
        // 启动时加载会话列表（在后台协程中）
        CoroutineScope(Dispatchers.IO).launch {
            loadSessions()
        }
    }

    fun getMessagesFlow(): Flow<List<ChatMessageDto>> {
        return _messages.map { messagesList -> messagesList.map(ChatMessage::toChatMessageDto) }
    }

    fun getAllSessionsFlow(): Flow<List<OpenCodeRestClient.OpenCodeSession>> {
        return _allSessions
    }

    fun getServerConnectedFlow(): Flow<Boolean> {
        return _serverConnected
    }

    fun getCurrentSessionId(): String? = currentSessionId

    /**
     * 发送消息 - 优先使用 OpenCode Server，失败时回退到模拟模式
     */
    suspend fun sendMessage(messageContent: String) {
        withContext(Dispatchers.IO) {
            try {
                val sessionId = currentSessionId ?: createNewSession()
                if (sessionId == null) {
                    // 无法连接到服务器，使用模拟模式
                    simulateLocalResponse(messageContent)
                    return@withContext
                }

                // 发送用户消息到本地缓存
                _messages.value += chatMessageFactory.createUserMessage(messageContent)

                // 调用 OpenCode Server 发送消息（流式）
                val result = restClient.sendPrompt(sessionId, messageContent)
                if (result.isFailure()) {
                    // 服务器调用失败，回退到模拟模式
                    simulateLocalResponse(messageContent)
                }
                // 流式响应通过 SSE 单独处理（在 BackendChatRepositoryRpcApi 中）
            } catch (e: Exception) {
                if (e is CancellationException) {
                    _messages.value = _messages.value.filter { !it.isAIThinkingMessage() }
                    throw e
                }
                // 任何异常都回退到模拟模式
                simulateLocalResponse(messageContent)
            }
        }
    }

    /**
     * 创建新会话
     */
    suspend fun createNewSession(title: String? = null): String? {
        val result = restClient.createSession(title)
        if (result.isSuccess()) {
            val sessionId = result.getOrThrow()
            currentSessionId = sessionId
            loadSessions()
            loadMessages(sessionId)
            return sessionId
        }
        return null
    }

    /**
     * 切换会话
     */
    suspend fun switchSession(sessionId: String) {
        val result = restClient.getSession(sessionId)
        if (result.isSuccess()) {
            currentSessionId = sessionId
            loadMessages(sessionId)
            loadSessions()
        }
    }

    /**
     * 删除会话
     */
    suspend fun deleteSession(sessionId: String) {
        val result = restClient.deleteSession(sessionId)
        if (result.isSuccess()) {
            if (currentSessionId == sessionId) {
                currentSessionId = null
                _messages.value = emptyList()
            }
            loadSessions()
        }
    }

    /**
     * 重命名会话
     */
    suspend fun renameSession(sessionId: String, newTitle: String) {
        val result = restClient.renameSession(sessionId, newTitle)
        if (result.isSuccess()) {
            loadSessions()
        }
    }

    /**
     * 获取所有会话
     */
    suspend fun loadSessions() {
        val result = restClient.getAllSessions()
        if (result.isSuccess()) {
            _allSessions.value = result.getOrThrow()
            _serverConnected.value = true
        } else {
            _serverConnected.value = false
        }
    }

    /**
     * 加载指定会话的消息
     */
    suspend fun loadMessages(sessionId: String) {
        val result = restClient.getMessages(sessionId)
        if (result.isSuccess()) {
            val messages = result.getOrThrow().map { openCodeMsg ->
                val isMy = openCodeMsg.role == "user"
                ChatMessage(
                    id = openCodeMsg.id,
                    content = openCodeMsg.content,
                    author = if (isMy) "Me" else "AI Buddy",
                    isMyMessage = isMy,
                    timestamp = Instant.ofEpochMilli(openCodeMsg.createdMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime(),
                    type = ChatMessage.ChatMessageType.TEXT
                )
            }
            _messages.value = messages
            currentSessionId = sessionId
        } else {
            _messages.value = emptyList()
        }
    }

    /**
     * 回复权限请求
     */
    suspend fun replyPermission(permissionId: String, decision: OpenCodeRestClient.PermissionDecision) {
        currentSessionId?.let { sessionId ->
            restClient.replyPermission(sessionId, permissionId, decision)
        }
    }

    /**
     * 中止执行
     */
    suspend fun abortExecution() {
        currentSessionId?.let { sessionId ->
            restClient.interruptSession(sessionId)
            _messages.value = _messages.value.filter { !it.isAIThinkingMessage() }
        }
    }

    /**
     * 更新 Server 连接配置（由前端设置页通过 RPC 下发），并刷新连接状态
     */
    fun updateServerConfig(serverUrl: String, username: String, password: String) {
        val normalizedUrl = serverUrl.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER_URL }
        val normalizedUsername = username.trim().ifEmpty { OpenCodeRestClient.DEFAULT_USERNAME }
        val normalizedPassword = password.trim()
        if (normalizedUrl == this.serverUrl &&
            normalizedUsername == this.username &&
            normalizedPassword == this.password
        ) {
            return
        }
        this.serverUrl = normalizedUrl
        this.username = normalizedUsername
        this.password = normalizedPassword
        this.restClient = OpenCodeRestClient(normalizedUrl, normalizedUsername, normalizedPassword)
        CoroutineScope(Dispatchers.IO).launch { loadSessions() }
    }

    /** 当前生效的 Server 地址 */
    fun getServerUrl(): String = serverUrl

    /**
     * 列出可用 Agent（模式）
     */
    suspend fun listAgents(): List<OpenCodeRestClient.OpenCodeAgent> = restClient.listAgents().getOrThrow()

    /**
     * 列出可用模型
     */
    suspend fun listModels(): List<OpenCodeRestClient.OpenCodeModel> = restClient.listModels().getOrThrow()

    /**
     * 切换指定会话的 Agent（模式）
     */
    suspend fun switchAgent(sessionId: String, agentId: String) {
        restClient.switchAgent(sessionId, agentId).getOrThrow()
    }

    /**
     * 切换指定会话的模型
     */
    suspend fun switchModel(sessionId: String, providerId: String, modelId: String) {
        restClient.switchModel(sessionId, providerId, modelId).getOrThrow()
    }

    /**
     * 本地模拟模式（服务器不可用时的 fallback）
     */
    private suspend fun simulateLocalResponse(messageContent: String) {
        _messages.value += chatMessageFactory.createUserMessage(messageContent)
        simulateAIStreamingResponse(messageContent)
    }

    // 保留原有的模拟流式响应逻辑作为 fallback
    private suspend fun simulateAIStreamingResponse(userMessage: String) {
        val thinkingMessage = chatMessageFactory
            .createAIThinkingMessage("Hmm, let me think about this...")
        _messages.value += thinkingMessage

        val reasoningSteps = listOf(
            "Analyzing the user's question...",
            "Considering relevant context and knowledge...",
            "Formulating a helpful response...",
            "Ready to provide answer."
        )

        for (step in reasoningSteps) {
            delay(300 + (0..200).random().toLong())
            val updatedThinking = thinkingMessage.copy(content = thinkingMessage.content + "\n$step")
            _messages.value = _messages.value
                .map { if (it.id == thinkingMessage.id) updatedThinking else it }
        }

        val responseContent = aiResponseGenerator.generateAIResponse(userMessage)
        val aiMessage = chatMessageFactory.createAIMessage(content = "")
        _messages.value = _messages.value
            .map { if (it.id == thinkingMessage.id) aiMessage else it }

        val chunks = chunkText(responseContent, 3..8)
        var accumulated = ""

        for (chunk in chunks) {
            delay(50 + (0..100).random().toLong())
            accumulated += chunk
            val updatedMessage = aiMessage.copy(content = accumulated)
            _messages.value = _messages.value
                .map { if (it.id == aiMessage.id) updatedMessage else it }
        }

        _messages.value = _messages.value
            .map { if (it.id == aiMessage.id) aiMessage.copy(content = responseContent) else it }
    }

    private fun chunkText(text: String, chunkSizeRange: IntRange): List<String> {
        val chunks = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val chunkSize = (chunkSizeRange.start..chunkSizeRange.endInclusive).random()
            val endIndex = (index + chunkSize).coerceAtMost(text.length)
            chunks.add(text.substring(index, endIndex))
            index = endIndex
        }
        return chunks
    }
}