@file:Suppress("UnstableApiUsage")

package com.ayongw.idea.opencode.backend

import com.ayongw.idea.opencode.backend.event.OpenCodeEvent
import com.ayongw.idea.opencode.backend.event.OpenCodeEventClient
import com.ayongw.idea.opencode.backend.event.SessionStreamState
import com.ayongw.idea.opencode.backend.repository.AIResponseGenerator
import com.ayongw.idea.opencode.backend.repository.ChatMessageFactory
import com.ayongw.idea.opencode.backend.repository.OpenCodeCredentials
import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.ayongw.idea.opencode.backend.server.OpenCodeServerConnectionConfig
import com.ayongw.idea.opencode.backend.server.OpenCodeServerEndpoint
import com.ayongw.idea.opencode.backend.server.OpenCodeServerManager
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ChatMessageDto
import com.ayongw.idea.opencode.shared.ContextFileDto
import com.ayongw.idea.opencode.shared.ContextKind
import com.ayongw.idea.opencode.shared.PendingPermissionDto
import com.ayongw.idea.opencode.shared.PromptContextDto
import com.ayongw.idea.opencode.shared.SessionUsageDto
import com.ayongw.idea.opencode.shared.TokenUsageDto
import com.ayongw.idea.opencode.shared.ToolCallDto
import com.ayongw.idea.opencode.shared.toChatMessageDto
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
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
import java.nio.file.Paths
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

@Service(Service.Level.PROJECT)
class BackendChatRepositoryModel(private val project: Project) : Disposable {
    companion object {
        /** 默认 Server 地址 */
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:4096"

        /** 助手消息展示名（与本地模拟模式一致） */
        const val AI_AUTHOR = "AI Buddy"

        /** 思考气泡 id 后缀（与事件流侧一致，见 SessionStreamState.REASONING_ID_SUFFIX） */
        private const val REASONING_ID_SUFFIX = "#reasoning"

        /**
         * 对账增量合并：REST 权威**覆盖**同 id 内容、本地独有消息**保留**、REST 有而本地缺的**按时间补充**。
         *
         * 目标（对账交互流畅、消息不乱丢）：
         * - 本地事件流累积的顺序为骨架（thinking/tool 中间态不因 REST 缺失而消失、不整屏重排）
         * - 同 id 消息以 REST 为准（正文/tool 终态/思考最终内容）
         * - 断线遗漏的整段消息按 createdMillis 升序插入到正确位置
         *
         * 纯逻辑、无副作用，便于单测。
         */
        fun mergeReconcile(existing: List<ChatMessage>, rest: List<ChatMessage>): List<ChatMessage> {
            if (existing.isEmpty()) return rest
            if (rest.isEmpty()) return existing

            // 1) REST 权威内容覆盖同 id 本地消息（id 唯一：正文原 id、思考带 #reasoning、工具带 call_）
            val restById = rest.associateBy { it.id }
            val updated = existing.map { restById[it.id] ?: it }

            // 2) 补充 REST 有、本地没有的（断线遗漏），按时间升序整段插入（内部顺序保持 REST 的 parts 顺序）
            val existingIds = updated.mapTo(HashSet()) { it.id }
            val missing = rest.filter { it.id !in existingIds }
            if (missing.isEmpty()) return updated

            val anchor = updated.indexOfLast { !it.timestamp.isAfter(missing.first().timestamp) }
            return updated.toMutableList().apply { addAll(anchor + 1, missing) }
        }

        fun getInstance(project: Project): BackendChatRepositoryModel {
            return project.getService(BackendChatRepositoryModel::class.java)
        }
    }

    /** 当前活跃会话 ID */
    private var currentSessionId: String? = null

    /**
     * 当前工作区目录（按 Project.basePath 下发）
     *
     * 用于 `GET /api/session?directory=` 只取本工作区会话，以及新建会话时绑定工作区；
     * 首次由 RPC 层传入后固定，供内部刷新（创建/切换/删除/重命名后的重载）复用。
     */
    @Volatile
    private var workspaceDirectory: String? = null

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

    /**
     * 最近一次由设置页下发的连接配置（含 Server 管理项）
     *
     * Server 运行时（[OpenCodeServerManager]）据此决定探测目标与自动启动行为；
     * 插件自行选定端点后经 [applyManagedEndpoint] 下发，**不覆盖**本字段。
     */
    @Volatile
    private var pushedConfig = OpenCodeServerConnectionConfig(
        serverUrl = DEFAULT_SERVER_URL,
        username = OpenCodeRestClient.DEFAULT_USERNAME,
        password = OpenCodeCredentials.resolvePassword(null),
    )

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

    private val log = Logger.getInstance(BackendChatRepositoryModel::class.java)

    init {
        // 启动时加载会话列表（在后台协程中），随后挂上事件流
        scope.launch {
            loadSessions()
            ensureEventStream()
        }
        // Server 运行时：首次触达后端即开始探测（复用 / 拉起自有实例），不等待面板打开（TSD-31 §3.3）
        scope.launch { OpenCodeServerManager.getInstance(project).startIfIdle() }
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

    // ==================== 会话上下文附件 ====================

    /** 会话上下文附件（会话级；命令为一次性，发送成功后清除） */
    private val _contextFiles = MutableStateFlow<Map<String, List<ContextFileDto>>>(emptyMap())

    /** 指定会话的上下文附件流 */
    fun getContextFilesFlow(sessionId: String): Flow<List<ContextFileDto>> =
        _contextFiles.map { it[sessionId].orEmpty() }

    /** 添加附件：按 `kind + path` 去重（保留先添加者） */
    fun addContextFile(sessionId: String, contextFile: ContextFileDto) {
        val current = contextFilesOf(sessionId)
        if (current.any { it.kind == contextFile.kind && it.path == contextFile.path }) return
        _contextFiles.value = _contextFiles.value + (sessionId to (current + contextFile))
    }

    /** 移除指定路径的附件 */
    fun removeContextFile(sessionId: String, path: String) {
        val current = contextFilesOf(sessionId)
        if (current.none { it.path == path }) return
        _contextFiles.value = _contextFiles.value + (sessionId to current.filterNot { it.path == path })
    }

    /** 清空指定会话的附件 */
    fun clearContextFiles(sessionId: String) {
        if (contextFilesOf(sessionId).isEmpty()) return
        _contextFiles.value = _contextFiles.value - sessionId
    }

    private fun contextFilesOf(sessionId: String): List<ContextFileDto> =
        _contextFiles.value[sessionId].orEmpty()

    /** 会话附件（＋）与本次 mention 解析附件合并，按 `kind + path` 去重（会话附件在前） */
    private fun mergedAttachments(sessionId: String, incoming: List<ContextFileDto>): List<ContextFileDto> =
        (contextFilesOf(sessionId) + incoming).distinctBy { it.kind to it.path }

    /** 上下文附件 → prompt 文件附件（FILE / DIRECTORY / RULE 三类） */
    private fun promptFiles(attachments: List<ContextFileDto>): List<OpenCodeRestClient.PromptFile> =
        attachments
            .filter {
                it.kind == ContextKind.FILE ||
                    it.kind == ContextKind.DIRECTORY ||
                    it.kind == ContextKind.RULE
            }
            .map {
                OpenCodeRestClient.PromptFile(
                    uri = fileUri(it.path),
                    name = it.name,
                    description = it.summary.takeIf { summary -> summary.isNotBlank() }
                )
            }

    /** 上下文附件 → prompt 技能 id（SKILL 一类） */
    private fun promptSkills(attachments: List<ContextFileDto>): List<String> =
        attachments.filter { it.kind == ContextKind.SKILL }.mapNotNull { it.skillId }

    /** 绝对路径 → `file://` uri（自动转义空格与非 ASCII 字符） */
    private fun fileUri(path: String): String =
        if (path.startsWith("file:")) path else Paths.get(path).toUri().toString()

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
        log.info("启动事件流：$serverUrl")
    }

    /** 关闭并重建事件流（Server 配置变更时调用） */
    private fun restartEventStream() {
        log.info("重建事件流：$serverUrl")
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
            log.info("执行终态事件：${event::class.simpleName} session=$sessionId，开始对账")
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
        log.info("事件流状态：$state")
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
            val result = restClient.getMessages(sessionId)
            val messages = result.getOrNull()
            if (messages == null) {
                log.warn("对账失败 session=$sessionId: ${result.exceptionOrNull()?.message}")
                return@launch
            }
            if (sessionId != currentSessionId) return@launch
            streamState.reset()
            val restBubbles = toBubbles(messages)
            val merged = mergeReconcile(_messages.value, restBubbles)
            _messages.value = merged
            log.info(
                "对账完成 session=$sessionId: REST 消息 ${messages.size} 条 → " +
                    "气泡 ${merged.size} 条（保留本地 ${merged.size - restBubbles.size} 条，非全量重建）"
            )
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
        val added = streaming.filter { it.id !in knownIds }
        _messages.value = merged + added
        if (added.isNotEmpty()) {
            log.info("流式消息入列：新增 ${added.size} 条（${added.joinToString { it.type.name }})，当前气泡 ${_messages.value.size} 条")
        }
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
    suspend fun sendMessage(messageContent: String) = sendMessage(messageContent, PromptContextDto())

    /**
     * 发送消息：合并「本次 mention 解析结果」与「会话附件（＋）」后组包；
     * 文本中带命令时走 `/command` 端点，否则走 `/prompt`。
     */
    suspend fun sendMessage(messageContent: String, context: PromptContextDto) {
        withContext(Dispatchers.IO) {
            try {
                val sessionId = currentSessionId ?: createNewSession()
                if (sessionId == null) {
                    // 无法连接到服务器，使用模拟模式
                    log.warn("发送失败：无可用会话（Server 不可达？），回退模拟响应")
                    simulateLocalResponse(messageContent)
                    return@withContext
                }

                // 会话上下文附件随消息下发：有命令时走命令端点，否则走普通 prompt
                val attachments = mergedAttachments(sessionId, context.attachments)
                val commandName = context.commandName?.takeIf { it.isNotBlank() }
                // 发送前的条数：流式气泡可能在本请求期间先到，稍后把用户消息插到它们之前
                val pendingIndex = _messages.value.size
                val result = if (commandName != null) {
                    restClient.sendCommand(
                        sessionId,
                        commandName,
                        messageContent,
                        promptFiles(attachments),
                        promptSkills(attachments)
                    )
                } else {
                    restClient.sendPrompt(
                        sessionId,
                        messageContent,
                        promptFiles(attachments),
                        promptSkills(attachments)
                    )
                }
                if (result.isFailure()) {
                    // 服务器调用失败，回退到模拟模式（模拟路径自带用户消息与回复）
                    log.warn("发送消息失败 session=$sessionId，回退模拟响应: ${result.exceptionOrNull()?.message}")
                    simulateLocalResponse(messageContent)
                } else {
                    // 用服务端返回的 user 消息 id 作为本地回声气泡的 id：与后续对账的 REST id 一致，
                    // 不会再因「换 id」把气泡删掉重建（表现为闪烁）
                    val serverMessageId = result.getOrThrow().takeIf { it.isNotBlank() }
                    val userMessage = chatMessageFactory.createUserMessage(messageContent)
                        .let { if (serverMessageId != null) it.copy(id = serverMessageId) else it }
                    insertUserMessage(pendingIndex, userMessage)
                    log.info(
                        "已发送消息 session=$sessionId, 长度=${messageContent.length}, " +
                            "消息=${serverMessageId ?: "-"}, command=${commandName ?: "-"}"
                    )
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
     * 把用户消息插到「发送前已有条数」的位置。
     *
     * 流式气泡（thinking / 正文）可能在本轮 prompt 请求期间先到并被追加到列表末尾，
     * 直接 `+=` 会让用户消息排到回复之后（问在下、答在上）；按发送前的位置插入可保证顺序。
     */
    private fun insertUserMessage(index: Int, message: ChatMessage) {
        val current = _messages.value
        _messages.value = current.toMutableList().apply { add(index.coerceIn(0, current.size), message) }
    }

    /**
     * 创建新会话（绑定当前工作区）
     *
     * @param directory 工作区目录；不传时复用已记录的工作区
     */
    suspend fun createNewSession(title: String? = null, directory: String? = null): String? {
        directory?.takeIf { it.isNotBlank() }?.let { workspaceDirectory = it }
        val result = restClient.createSession(title, workspaceDirectory)
        if (result.isSuccess()) {
            val sessionId = result.getOrThrow()
            currentSessionId = sessionId
            log.info("新建会话 session=$sessionId, title=${title ?: "-"}, directory=${workspaceDirectory ?: "-"}")
            loadSessions()
            loadMessages(sessionId)
            return sessionId
        }
        log.warn("新建会话失败: ${result.exceptionOrNull()?.message}")
        return null
    }

    /**
     * 切换会话
     */
    suspend fun switchSession(sessionId: String) {
        val result = restClient.getSession(sessionId)
        if (result.isSuccess()) {
            currentSessionId = sessionId
            log.info("切换会话 session=$sessionId")
            loadMessages(sessionId)
            loadSessions()
        } else {
            log.warn("切换会话失败 session=$sessionId: ${result.exceptionOrNull()?.message}")
        }
    }

    /**
     * 删除会话
     */
    suspend fun deleteSession(sessionId: String) {
        val result = restClient.deleteSession(sessionId)
        if (result.isSuccess()) {
            log.info("删除会话 session=$sessionId")
            if (currentSessionId == sessionId) {
                currentSessionId = null
                streamState.reset()
                _sessionRunning.value = false
                _pendingPermission.value = null
                _messages.value = emptyList()
            }
            loadSessions()
        } else {
            log.warn("删除会话失败 session=$sessionId: ${result.exceptionOrNull()?.message}")
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
     * 加载当前工作区的会话列表
     *
     * `GET /api/session?directory=` 只返回本工作区会话；服务端不认 `location[...]` 过滤形式，
     * 因此必须显式传目录，否则会拿到全机所有目录的会话。
     *
     * @param directory 工作区目录；不传时复用已记录的工作区
     */
    suspend fun loadSessions(directory: String? = null) {
        directory?.takeIf { it.isNotBlank() }?.let { workspaceDirectory = it }
        val result = restClient.getAllSessions(workspaceDirectory)
        if (result.isSuccess()) {
            _allSessions.value = result.getOrThrow()
            _serverConnected.value = true
        } else {
            _serverConnected.value = false
        }
    }

    /**
     * 服务端默认模型（配置里的 `model`，即设置页展示的 Default model）
     *
     * `GET /api/model/default` 无默认时返回 null。
     */
    suspend fun getDefaultModel(): Pair<String, String>? {
        val obj = restClient.getDefaultModel().getOrNull() ?: return null
        val providerId = obj.primitiveString("providerID") ?: return null
        val modelId = obj.primitiveString("modelID") ?: obj.primitiveString("id") ?: return null
        return providerId to modelId
    }

    /** 取 JSON 原始值中的字符串（缺失或非原始类型时返回 null） */
    private fun JsonObject.primitiveString(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

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
            val messages = result.getOrThrow()
            val bubbles = toBubbles(messages)
            _messages.value = bubbles
            currentSessionId = sessionId
            log.info("加载会话消息 session=$sessionId: REST 消息 ${messages.size} 条 → 气泡 ${bubbles.size} 条")
        } else {
            log.warn("加载会话消息失败 session=$sessionId: ${result.exceptionOrNull()?.message}")
            _messages.value = emptyList()
        }
    }

    /**
     * REST 消息列表 → 面板气泡列表。
     *
     * `GET /api/session/{id}/message` 返回的 `data[]` 是**最新在前**（下标 0 为最新），
     * 面板要求最早在前（与事件流追加顺序一致），故此处反转。
     */
    private fun toBubbles(messages: List<OpenCodeRestClient.OpenCodeMessage>): List<ChatMessage> =
        messages.asReversed().flatMap(::toChatMessages)

    /** opencode 消息 → 面板气泡：user 单条；assistant 按 `content[]` 顺序拆成思考 + 正文气泡 + 工具卡片 */
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
                // 思考过程 → AI_THINKING 气泡：id 带 #reasoning 后缀，与事件流侧一致（两路可原地互相覆盖）
                is OpenCodeRestClient.OpenCodePart.Reasoning ->
                    bubbles += reasoningMessage(openCodeMsg, part.text, at)
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

    /** 思考过程气泡：id 与事件流侧 `assistantMessageId#reasoning` 一致 */
    private fun reasoningMessage(
        openCodeMsg: OpenCodeRestClient.OpenCodeMessage,
        reasoning: String,
        at: LocalDateTime
    ) = ChatMessage(
        id = openCodeMsg.id + REASONING_ID_SUFFIX,
        content = reasoning,
        author = AI_AUTHOR,
        isMyMessage = false,
        timestamp = at,
        type = ChatMessage.ChatMessageType.AI_THINKING
    )

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
     *
     * 新增的 `cliPath` / `autoStartServer` / `reuseExternalServer` 只影响 Server 运行时
     * （[OpenCodeServerManager]）的探测与拉起行为，不改变 REST/SSE 契约。
     */
    fun updateServerConfig(
        serverUrl: String,
        username: String,
        password: String,
        cliPath: String? = null,
        autoStartServer: Boolean = true,
        reuseExternalServer: Boolean = true,
    ) {
        val normalizedUrl = serverUrl.trim().trimEnd('/').ifEmpty { DEFAULT_SERVER_URL }
        val normalizedUsername = username.trim().ifEmpty { OpenCodeRestClient.DEFAULT_USERNAME }
        // 密码留空时回退 OPENCODE_SERVER_PASSWORD / service.json，避免设置页空值把兜底覆盖掉
        val normalizedPassword = OpenCodeCredentials.resolvePassword(password)
        pushedConfig = pushedConfig.copy(
            serverUrl = normalizedUrl,
            username = normalizedUsername,
            password = normalizedPassword,
            cliPath = cliPath?.trim()?.takeIf { it.isNotBlank() },
            autoStartServer = autoStartServer,
            reuseExternalServer = reuseExternalServer,
        )
        applyConnection(normalizedUrl, normalizedUsername, normalizedPassword)
    }

    /**
     * Server 运行时选定端点后下发（TSD-31 §3.1）
     *
     * 与设置页下发共用同一套连接重建逻辑，但**不覆盖**用户配置：运行时的探测依据始终是用户配置。
     */
    fun applyManagedEndpoint(endpoint: OpenCodeServerEndpoint) {
        applyConnection(endpoint.baseUrl.trimEnd('/'), endpoint.username, endpoint.password.orEmpty())
    }

    /** 当前用户配置（Server 运行时读取） */
    fun currentServerConfig(): OpenCodeServerConnectionConfig = pushedConfig

    private fun applyConnection(serverUrl: String, username: String, password: String) {
        if (serverUrl == this.serverUrl &&
            username == this.username &&
            password == this.password
        ) {
            ensureEventStream()
            return
        }
        this.serverUrl = serverUrl
        this.username = username
        this.password = password
        this.restClient = OpenCodeRestClient(serverUrl, username, password)
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
     * 命令清单（内置 + 自定义），工作区维度
     */
    suspend fun listCommands(directory: String?): List<OpenCodeRestClient.OpenCodeCommand> =
        restClient.listCommands(directory).getOrThrow()

    /**
     * 规则清单（AGENTS.md 等）
     */
    suspend fun listReferences(): List<OpenCodeRestClient.OpenCodeReference> =
        restClient.listReferences().getOrThrow()

    /**
     * 技能清单（含 id / 展示名 / 路径 / 描述）
     */
    suspend fun listSkills(): List<OpenCodeRestClient.OpenCodeSkill> =
        restClient.listSkillInfos().getOrThrow()

    /**
     * 工作区文件检索（文件与目录，路径相对工作区根目录）
     */
    suspend fun findWorkspaceEntries(
        query: String,
        directory: String?,
        limit: Int
    ): List<OpenCodeRestClient.OpenCodeFsEntry> =
        restClient.findEntries(query, directory, limit).getOrThrow()

    /**
     * 工作区目录浏览（path 为 null 时列工作区根目录）
     */
    suspend fun listWorkspaceDirectory(
        path: String?,
        directory: String?
    ): List<OpenCodeRestClient.OpenCodeFsEntry> =
        restClient.listDirectory(path, directory).getOrThrow()

    /** 供应商展示名映射（providerID → name） */
    suspend fun listProviderNames(): Map<String, String> = restClient.listProviderNames().getOrThrow()

    /** 会话详情（回读模式与模型用）；不可达时为 null */
    suspend fun getSession(sessionId: String): OpenCodeRestClient.OpenCodeSession? =
        restClient.getSession(sessionId).getOrNull()

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

    /**
     * Project 销毁：先释放 Server 引用（仅最后一个引用者会终止自有进程），再关闭事件流、取消协程
     *
     * 顺序不可颠倒（TSD-31 §6.3）：先释放引用再断连接，避免「事件流还在重连、Server 已被终止」的空转。
     */
    override fun dispose() {
        runCatching {
            project.getServiceIfCreated(OpenCodeServerManager::class.java)?.dispose()
        }.onFailure { log.info("释放 Server 引用失败", it) }
        eventClient?.stop()
        eventClient = null
        scope.cancel()
    }
}