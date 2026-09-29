@file:Suppress("UnstableApiUsage")

package com.ayongw.idea.opencode.backend

import com.ayongw.idea.opencode.backend.event.OpenCodeEvent
import com.ayongw.idea.opencode.backend.event.OpenCodeEventClient
import com.ayongw.idea.opencode.backend.event.SessionStreamState
import com.ayongw.idea.opencode.backend.repository.AIResponseGenerator
import com.ayongw.idea.opencode.backend.repository.ChatMessageFactory
import com.ayongw.idea.opencode.backend.repository.OpenCodeCredentials
import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ChatMessageDto
import com.ayongw.idea.opencode.shared.PendingPermissionDto
import com.ayongw.idea.opencode.shared.SessionUsageDto
import com.ayongw.idea.opencode.shared.TokenUsageDto
import com.ayongw.idea.opencode.shared.ToolCallDto
import com.ayongw.idea.opencode.shared.toChatMessageDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Service(Service.Level.PROJECT)
class BackendChatRepositoryModel : Disposable {
    companion object {
        /** 默认 Server 地址 */
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:4096"

        /** 助手消息展示名（与本地模拟模式一致） */
        const val AI_AUTHOR = "AI Buddy"

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

    /** Basic 认证密码：显式值 → OPENCODE_SERVER_PASSWORD → ~/.config/opencode/service.json */
    @Volatile
    private var password: String = OpenCodeCredentials.resolvePassword(null)

    /** OpenCode REST 客户端（配置变更时重建） */
    @Volatile
    private var restClient = OpenCodeRestClient(serverUrl, username, password)

    /** 本地消息缓存（当前会话的消息） */
    private val _messages = MutableStateFlow(emptyList<ChatMessage>())

    /** 所有会话列表缓存 */
    private val _allSessions = MutableStateFlow(emptyList<OpenCodeRestClient.OpenCodeSession>())

    /** 服务器连接状态 */
    private val _serverConnected = MutableStateFlow(false)

    /** 当前会话是否正在执行（事件流驱动，供 UI 切换「停止/发送」态） */
    private val _sessionRunning = MutableStateFlow(false)

    /** 当前会话的待决权限请求（事件流 `permission.asked` 驱动） */
    private val _pendingPermission = MutableStateFlow<PendingPermissionDto?>(null)

    /** 事件驱动流式状态机（文本/推理累积、失败渲染） */
    private val streamState = SessionStreamState()

    /** 事件流客户端（配置变更时重建，随 Project 销毁关闭） */
    @Volatile
    private var eventClient: OpenCodeEventClient? = null

    /** 模型内部协程作用域（随 Project 销毁取消） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 消息工厂（用于本地模拟模式） */
    private val chatMessageFactory = ChatMessageFactory(AI_AUTHOR, "Super Engineer")
    private val aiResponseGenerator = AIResponseGenerator()

    init {
        // 启动时加载会话列表（在后台协程中），随后挂上事件流
        scope.launch {
            loadSessions()
            ensureEventStream()
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

    /** 当前会话的执行状态流（事件流驱动） */
    fun getSessionRunningFlow(): Flow<Boolean> = _sessionRunning

    /** 当前会话的待决权限请求流（null = 无待决项） */
    fun getPendingPermissionFlow(): Flow<PendingPermissionDto?> = _pendingPermission

    // ==================== 事件流接入 ====================

    /**
     * 建立事件流连接（幂等）。
     *
     * 首次调用创建客户端；重复调用直接返回，避免设置页反复下发配置时重建连接。
     */
    private fun ensureEventStream() {
        if (eventClient != null) return
        val client = OpenCodeEventClient(
            baseUrl = serverUrl,
            username = username,
            password = password,
            onEvent = ::handleOpenCodeEvent,
            onStateChanged = ::handleEventClientState
        )
        eventClient = client
        client.start()
    }

    /** 关闭并重建事件流（Server 配置变更时调用） */
    private fun restartEventStream() {
        eventClient?.stop()
        eventClient = null
        _sessionRunning.value = false
        _pendingPermission.value = null
        streamState.reset()
        ensureEventStream()
    }

    /**
     * 事件 → 会话状态。
     *
     * 只处理当前会话：多会话并发时，非当前会话的事件不改变面板状态。
     */
    private fun handleOpenCodeEvent(event: OpenCodeEvent) {
        val sessionId = event.sessionIdOrNull() ?: return
        if (sessionId.isBlank() || sessionId != currentSessionId) return

        val shouldPublish = streamState.onEvent(event)
        _sessionRunning.value = streamState.isRunning
        if (shouldPublish) publishStreamMessages()
        updatePendingPermission(event, sessionId)
        if (event.isExecutionTerminal()) {
            _pendingPermission.value = null
            reconcile(sessionId)
        }
    }

    /** 权限请求：`permission.asked` 挂起待决项；执行终态/中断则清空 */
    private fun updatePendingPermission(event: OpenCodeEvent, sessionId: String) {
        if (event !is OpenCodeEvent.PermissionAsked) return
        _pendingPermission.value = PendingPermissionDto(
            sessionId = sessionId,
            requestId = event.requestId,
            action = event.action,
            resources = event.resources
        )
    }

    /**
     * 连接建立（含重连成功）：以服务端为准对账一次，补齐断线期间漏掉的事件。
     */
    private fun handleEventClientState(state: OpenCodeEventClient.State) {
        if (state != OpenCodeEventClient.State.CONNECTED) return
        currentSessionId?.let { reconcile(it) }
    }

    /**
     * 对账兜底：拉服务端权威消息覆盖本地，并清空流式缓冲（执行已终结，内容已由 `ended` 校准）。
     *
     * 触发点：执行终态事件、重连成功。事件可能丢，REST 不会。
     */
    private fun reconcile(sessionId: String) {
        scope.launch {
            val messages = restClient.getMessages(sessionId).getOrNull() ?: return@launch
            if (sessionId != currentSessionId) return@launch
            streamState.reset()
            _messages.value = messages.flatMap(::toChatMessages)
            loadSessions()
        }
    }

    /** 执行终态：`succeeded` / `failed` / `interrupted` */
    private fun OpenCodeEvent.isExecutionTerminal(): Boolean =
        this is OpenCodeEvent.ExecutionSucceeded ||
            this is OpenCodeEvent.ExecutionFailed ||
            this is OpenCodeEvent.ExecutionInterrupted

    /**
     * 把流式消息按 id 合并进消息列表（新气泡追加、已有气泡就地更新），
     * 避免整段回答完成前面板空等。
     */
    private fun publishStreamMessages() {
        val streaming = streamState.messages()
        if (streaming.isEmpty()) return
        val byId = streaming.associateBy { it.id }
        val current = _messages.value
        val merged = current.map { byId[it.id] ?: it }
        val knownIds = merged.mapTo(HashSet()) { it.id }
        _messages.value = merged + streaming.filter { it.id !in knownIds }
    }

    private fun OpenCodeEvent.sessionIdOrNull(): String? = when (this) {
        is OpenCodeEvent.SessionCreated -> sessionId
        is OpenCodeEvent.SessionInboxEnqueued -> sessionId
        is OpenCodeEvent.SessionInboxDelivered -> sessionId
        is OpenCodeEvent.ExecutionStarted -> sessionId
        is OpenCodeEvent.ExecutionSucceeded -> sessionId
        is OpenCodeEvent.ExecutionFailed -> sessionId
        is OpenCodeEvent.ExecutionInterrupted -> sessionId
        is OpenCodeEvent.StepStarted -> sessionId
        is OpenCodeEvent.StepStreamed -> sessionId
        is OpenCodeEvent.StepEnded -> sessionId
        is OpenCodeEvent.StepFailed -> sessionId
        is OpenCodeEvent.TextStarted -> sessionId
        is OpenCodeEvent.TextDelta -> sessionId
        is OpenCodeEvent.TextEnded -> sessionId
        is OpenCodeEvent.ReasoningStarted -> sessionId
        is OpenCodeEvent.ReasoningDelta -> sessionId
        is OpenCodeEvent.ReasoningEnded -> sessionId
        is OpenCodeEvent.ToolInputStarted -> sessionId
        is OpenCodeEvent.ToolInputEnded -> sessionId
        is OpenCodeEvent.ToolCalled -> sessionId
        is OpenCodeEvent.ToolProgress -> sessionId
        is OpenCodeEvent.ToolSucceeded -> sessionId
        is OpenCodeEvent.PermissionAsked -> sessionId
        is OpenCodeEvent.ModelSelected -> sessionId
        is OpenCodeEvent.UsageUpdated -> sessionId
        is OpenCodeEvent.ShellCreated -> sessionId
        is OpenCodeEvent.ShellExited -> null
        OpenCodeEvent.ServerConnected -> null
        is OpenCodeEvent.Unexpected -> null
    }

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
                streamState.reset()
                _sessionRunning.value = false
                _pendingPermission.value = null
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
        // 切换/新建会话：清空上一会话的流式缓冲、运行态与待决权限，避免串值
        streamState.reset()
        _sessionRunning.value = false
        _pendingPermission.value = null
        val result = restClient.getMessages(sessionId)
        if (result.isSuccess()) {
            _messages.value = result.getOrThrow().flatMap(::toChatMessages)
            currentSessionId = sessionId
        } else {
            _messages.value = emptyList()
        }
    }

    /** opencode 消息 → 面板气泡：user 单条；assistant 按 `content[]` 顺序拆成正文气泡 + 工具卡片 */
    private fun toChatMessages(openCodeMsg: OpenCodeRestClient.OpenCodeMessage): List<ChatMessage> {
        val at = Instant.ofEpochMilli(openCodeMsg.createdMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        if (openCodeMsg.role == "user") {
            return listOf(
                ChatMessage(
                    id = openCodeMsg.id,
                    content = openCodeMsg.content,
                    author = "Me",
                    isMyMessage = true,
                    timestamp = at,
                    type = ChatMessage.ChatMessageType.TEXT
                )
            )
        }

        val bubbles = mutableListOf<ChatMessage>()
        var textEmitted = false
        openCodeMsg.parts.forEach { part ->
            when (part) {
                // 同一消息的多个 text 片段仍合并为一个气泡，落在首个 text 片段的位置
                is OpenCodeRestClient.OpenCodePart.Text -> {
                    if (!textEmitted && openCodeMsg.content.isNotBlank()) {
                        bubbles += assistantTextMessage(openCodeMsg, at)
                        textEmitted = true
                    }
                }
                is OpenCodeRestClient.OpenCodePart.Tool -> bubbles += toolMessage(part.call, at)
            }
        }
        if (!textEmitted && openCodeMsg.content.isNotBlank()) {
            bubbles += assistantTextMessage(openCodeMsg, at)
        }
        return bubbles
    }

    private fun assistantTextMessage(
        openCodeMsg: OpenCodeRestClient.OpenCodeMessage,
        at: LocalDateTime
    ) = ChatMessage(
        id = openCodeMsg.id,
        content = openCodeMsg.content,
        author = AI_AUTHOR,
        isMyMessage = false,
        timestamp = at,
        type = ChatMessage.ChatMessageType.TEXT
    )

    /** 工具卡片气泡：id 用 `call_*`，与事件流侧一致，两路可原地互相覆盖 */
    private fun toolMessage(call: OpenCodeRestClient.OpenCodeToolCall, at: LocalDateTime): ChatMessage {
        val tool = ToolCallDto(
            callId = call.callId,
            name = call.name,
            input = call.input,
            output = call.output,
            status = call.status,
            exit = call.exit,
            truncated = call.truncated
        )
        return ChatMessage(
            id = call.callId,
            content = tool.summary,
            author = AI_AUTHOR,
            isMyMessage = false,
            timestamp = at,
            type = ChatMessage.ChatMessageType.TOOL,
            tool = tool
        )
    }

    /**
     * 回复权限请求
     */
    suspend fun replyPermission(permissionId: String, decision: OpenCodeRestClient.PermissionDecision) {
        currentSessionId?.let { sessionId ->
            restClient.replyPermission(sessionId, permissionId, decision)
            // 已回复：本地立即收起卡片，不等服务端事件
            if (_pendingPermission.value?.requestId == permissionId) {
                _pendingPermission.value = null
            }
        }
    }

    /**
     * 中止执行
     */
    suspend fun abortExecution() {
        currentSessionId?.let { sessionId ->
            restClient.interruptSession(sessionId)
            // 事件到达前先退出「执行中」，让「停止」立即生效；
            // 缓冲不清空 —— 实测中断链路会补发 reasoning/text.ended 全文，由它校准即可
            _sessionRunning.value = false
            _pendingPermission.value = null
        }
    }

    /**
     * 更新 Server 连接配置（由前端设置页通过 RPC 下发），并刷新连接状态
     */
    fun updateServerConfig(serverUrl: String, username: String, password: String) {
        val normalizedUrl = serverUrl.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER_URL }
        val normalizedUsername = username.trim().ifEmpty { OpenCodeRestClient.DEFAULT_USERNAME }
        // 密码留空时回退 OPENCODE_SERVER_PASSWORD / service.json，避免设置页空值把兜底覆盖掉
        val normalizedPassword = OpenCodeCredentials.resolvePassword(password)
        if (normalizedUrl == this.serverUrl &&
            normalizedUsername == this.username &&
            normalizedPassword == this.password
        ) {
            ensureEventStream()
            return
        }
        this.serverUrl = normalizedUrl
        this.username = normalizedUsername
        this.password = normalizedPassword
        this.restClient = OpenCodeRestClient(normalizedUrl, normalizedUsername, normalizedPassword)
        restartEventStream()
        scope.launch { loadSessions() }
    }

    /** 当前生效的 Server 地址 */
    fun getServerUrl(): String = serverUrl

    /** 当前生效的 REST 客户端（设置页复用其连接配置） */
    fun getRestClient(): OpenCodeRestClient = restClient

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
     * 会话用量快照：累计 tokens/cost + 最近一次 step 的 input（占比分子）+ 当前模型上下文窗口（分母）
     */
    suspend fun getSessionUsage(sessionId: String): SessionUsageDto {
        val session = restClient.getSession(sessionId).getOrThrow()
        val lastStepInput = restClient.getMessages(sessionId).getOrNull()
            ?.asReversed()
            ?.firstOrNull { it.role == "assistant" && it.inputTokens != null }
            ?.inputTokens
        return SessionUsageDto(
            tokens = session.tokens?.toDto() ?: TokenUsageDto(),
            cost = session.costUsd,
            lastStepInputTokens = lastStepInput,
            contextWindow = resolveContextWindow(session.providerId, session.modelId)
        )
    }

    /** 按当前会话模型匹配上下文窗口；模型未匹配到或服务不可达时返回 null（UI 不展示占比） */
    private suspend fun resolveContextWindow(providerId: String?, modelId: String?): Long? {
        if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) return null
        val models = runCatching { listModels() }.getOrNull() ?: return null
        return models.firstOrNull { it.providerID == providerId && it.modelID == modelId }?.limitContext
    }

    private fun OpenCodeRestClient.OpenCodeTokenUsage.toDto() = TokenUsageDto(
        input = input,
        output = output,
        reasoning = reasoning,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite
    )

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

    /** Project 销毁：关闭事件流并取消内部协程 */
    override fun dispose() {
        eventClient?.stop()
        eventClient = null
        scope.cancel()
    }
}