@file:Suppress("UnstableApiUsage")

package com.ayongw.idea.agentpanel.backend

import com.ayongw.idea.agentpanel.backend.agent.opencode.event.EventStreamCoordinator
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.AIResponseGenerator
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.ChatMessageFactory
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.ConnectionManager
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.ContextFiles
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.LocalSimulator
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.MetadataGateway
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.MessageMapper
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.CommitMessageGenerator
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.SessionCatalog
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.OpenCodeCredentials
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.OpenCodeRestClient
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.OpenCodeSession
import com.ayongw.idea.agentpanel.backend.agent.opencode.repository.PermissionDecision
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerConnectionConfig
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerEndpoint
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerManager
import com.ayongw.idea.agentpanel.shared.ChatMessage
import com.ayongw.idea.agentpanel.shared.ChatMessageDto
import com.ayongw.idea.agentpanel.shared.ContextFileDto
import com.ayongw.idea.agentpanel.shared.PendingPermissionDto
import com.ayongw.idea.agentpanel.shared.PromptContextDto
import com.ayongw.idea.agentpanel.shared.toChatMessageDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 后端门面（RPC 实现背后的运行时对象）：按 Project 持有会话状态、消息流与连接编排。
 *
 * ============================ 防腐边界（agent 无关的外壳） ============================
 * 本类在 `backend` 根（**不在** `agent/` 下），因为它是面板与 agent 实现之间的门面：
 * 对外只暴露 `com.ayongw.idea.agentpanel.shared` 的中立契约，agent 专属实现全部委托给
 * `backend/agent/opencode/` 下的实现（当前唯一）。
 *
 * 新增 agent 时的演进点：把 opencode 的委托换成按 agent 分发（引入
 * `backend/agent/<agent>/` 实现 + 一个选择器），门面签名与 shared 契约保持不变——
 * **不要**把某个 agent 的概念（模型选择器、CLI 路径、MCP 清单…）往本类里堆，
 * 那些属于各自 agent 的适配层。
 */
@Service(Service.Level.PROJECT)
class BackendChatRepositoryModel(private val project: Project) : Disposable {
    companion object {
        /** 默认 Server 地址 */
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:4096"

        /** 助手消息展示名（与本地模拟模式一致） */
        const val AI_AUTHOR = "AI Buddy"

        /**
         * 「思考中」占位消息 id：发送成功后立即插入（空内容 AI_THINKING → 前端思考动画），
         * 首条真实流式消息到达（[publishStreamMessages]）或对账（[mergeReconcile]）时被清理
         */
        const val PENDING_THINKING_ID = "pending_thinking"

        fun getInstance(project: Project): BackendChatRepositoryModel {
            return project.getService(BackendChatRepositoryModel::class.java)
        }
    }

    /** 当前活跃会话 ID */
    private var currentSessionId: String? = null

    /** 连接配置（TSD-30 §5.8）：地址/认证 + REST 客户端 + 用户配置留档 */
    private val connections = ConnectionManager()

    /** 本地消息缓存（当前会话的消息） */
    private val _messages = MutableStateFlow(emptyList<ChatMessage>())

    /** 所有会话列表缓存 */
    private val _allSessions = MutableStateFlow(emptyList<OpenCodeSession>())

    /** 服务器连接状态 */
    private val _serverConnected = MutableStateFlow(false)

    /** 当前会话是否正在执行（事件流驱动，供 UI 切换「停止/发送」态） */
    private val _sessionRunning = MutableStateFlow(false)

    /** 当前会话的待决权限请求（事件流 `permission.asked` 驱动） */
    private val _pendingPermission = MutableStateFlow<PendingPermissionDto?>(null)

    /** 模型内部协程作用域（随 Project 销毁取消） */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** REST 消息 → 气泡映射（TSD-30 §5.8） */
    private val messageMapper = MessageMapper(AI_AUTHOR)

    /** 消息工厂：用户回声（sendMessage）与兜底模拟共用同一实例 */
    private val chatMessageFactory = ChatMessageFactory(AI_AUTHOR, "Super Engineer")

    /** 会话目录（TSD-30 §5.8）：会话 CRUD/列表 + 消息加载 */
    /** TSD-33：提交信息生成器（一次性会话） */
    private val commitMessageGenerator = CommitMessageGenerator(
        restClientProvider = { connections.restClient }
    )

    private val sessionCatalog: SessionCatalog = SessionCatalog(
        messagesState = _messages,
        allSessionsState = _allSessions,
        serverConnectedState = _serverConnected,
        runningState = _sessionRunning,
        permissionState = _pendingPermission,
        messageMapper = messageMapper,
        restClientProvider = { connections.restClient },
        currentSessionIdProvider = { currentSessionId },
        onCurrentSessionChanged = { currentSessionId = it },
        eventStreamReset = { eventStream.reset() },
        // 构造期就固定工作区目录：init 里的无参 loadSessions() 早于 RPC 层传目录
        initialDirectory = project.basePath
    )

    /** 事件流协调器（TSD-30 §5.8）：客户端生命周期 + 事件→状态 + 对账 */
    private val eventStream: EventStreamCoordinator = EventStreamCoordinator(
        scope = scope,
        messagesState = _messages,
        runningState = _sessionRunning,
        permissionState = _pendingPermission,
        messageMapper = messageMapper,
        currentSessionIdProvider = { currentSessionId },
        restClientProvider = { connections.restClient },
        onSessionsStale = { notifySessionsStale() }
    )

    /** Server 不可达时的本地兜底模拟（TSD-30 §5.8） */
    private val localSimulator = LocalSimulator(
        messagesState = _messages,
        messageFactory = chatMessageFactory,
        responseGenerator = AIResponseGenerator()
    )

    /** 元数据与用量网关（TSD-30 §5.8）：Agent/模型/命令/文件等查询透传 + 会话用量 */
    private val metadataGateway = MetadataGateway { connections.restClient }

    /** 会话上下文附件（TSD-30 §5.8）：会话级附件 + prompt 组装 */
    private val contextFiles = ContextFiles()

    private val log = Logger.getInstance(BackendChatRepositoryModel::class.java)

    init {
        // 启动时加载会话列表（在后台协程中），随后挂上事件流
        scope.launch {
            loadSessions()
            eventStream.start(connections.serverUrl, connections.username, connections.password)
        }
        // Server 运行时：首次触达后端即开始探测（复用 / 拉起自有实例），不等待面板打开（TSD-31 §3.3）
        scope.launch { OpenCodeServerManager.getInstance(project).startIfIdle() }
    }

    fun getMessagesFlow(): Flow<List<ChatMessageDto>> {
        return _messages.map { messagesList -> messagesList.map(ChatMessage::toChatMessageDto) }
    }

    fun getAllSessionsFlow(): Flow<List<OpenCodeSession>> {
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

    // ==================== 会话上下文附件门面（委托 ContextFiles，RPC 层仍经模型调用） ====================

    /** 指定会话的上下文附件流 */
    fun getContextFilesFlow(sessionId: String): Flow<List<ContextFileDto>> = contextFiles.flowOf(sessionId)

    /** 添加附件：按 `kind + path` 去重（保留先添加者） */
    fun addContextFile(sessionId: String, contextFile: ContextFileDto): Unit =
        contextFiles.add(sessionId, contextFile)

    /** 移除指定路径的附件 */
    fun removeContextFile(sessionId: String, path: String): Unit =
        contextFiles.remove(sessionId, path)

    /** 清空指定会话的附件 */
    fun clearContextFiles(sessionId: String): Unit = contextFiles.clear(sessionId)

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
                    localSimulator.simulate(messageContent)
                    return@withContext
                }

                // 会话上下文附件随消息下发：有命令时走命令端点，否则走普通 prompt
                val attachments = contextFiles.assembleForPrompt(sessionId, context.attachments)
                val commandName = context.commandName?.takeIf { it.isNotBlank() }
                // 发送前的条数：流式气泡可能在本请求期间先到，稍后把用户消息插到它们之前
                val pendingIndex = _messages.value.size
                val result = if (commandName != null) {
                    connections.restClient.sendCommand(
                        sessionId,
                        commandName,
                        messageContent,
                        attachments.files,
                        attachments.skills
                    )
                } else {
                    connections.restClient.sendPrompt(
                        sessionId,
                        messageContent,
                        attachments.files,
                        attachments.skills
                    )
                }
                if (result.isFailure()) {
                    // 服务器调用失败，回退到模拟模式（模拟路径自带用户消息与回复）
                    log.warn("发送消息失败 session=$sessionId，回退模拟响应: ${result.exceptionOrNull()?.message}")
                    localSimulator.simulate(messageContent)
                } else {
                    // 用服务端返回的 user 消息 id 作为本地回声气泡的 id：与后续对账的 REST id 一致，
                    // 不会再因「换 id」把气泡删掉重建（表现为闪烁）
                    val serverMessageId = result.getOrThrow().takeIf { it.isNotBlank() }
                    val userMessage = chatMessageFactory.createUserMessage(messageContent)
                        .let { if (serverMessageId != null) it.copy(id = serverMessageId) else it }
                    insertUserMessage(pendingIndex, userMessage)
                    // 立即给出「思考中」反馈（空内容思考消息 → 前端思考动画）：
                    // 首条流式事件到达前存在 REST 往返 + agent 启动空窗，避免界面无响应感
                    _messages.value += chatMessageFactory.createAIThinkingMessage("")
                        .copy(id = PENDING_THINKING_ID)
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
                localSimulator.simulate(messageContent)
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

    // ==================== 会话目录门面（委托 SessionCatalog，RPC 层仍经模型调用） ====================

    /** 事件流对账发现会话过期：刷新列表（经成员方法中转，避免属性初始化器互相引用的递归推断） */
    private suspend fun notifySessionsStale() = sessionCatalog.loadSessions()

    suspend fun loadSessions(directory: String? = null): Unit = sessionCatalog.loadSessions(directory)

    suspend fun createNewSession(title: String? = null, directory: String? = null): String? =
        sessionCatalog.createNewSession(title, directory)

    suspend fun switchSession(sessionId: String): Boolean = sessionCatalog.switchSession(sessionId)

    suspend fun deleteSession(sessionId: String): Unit = sessionCatalog.deleteSession(sessionId)

    suspend fun renameSession(sessionId: String, newTitle: String): Unit =
        sessionCatalog.renameSession(sessionId, newTitle)

    suspend fun getDefaultModel(): Pair<String, String>? = sessionCatalog.getDefaultModel()

    /** TSD-33：提交信息生成（一次性会话，见 CommitMessageGenerator） */
    suspend fun generateCommitMessage(request: com.ayongw.idea.agentpanel.shared.CommitMessageRequestDto) =
        commitMessageGenerator.generate(request)

    // ==================== 元数据网关门面（委托 MetadataGateway，RPC 层仍经模型调用） ====================

    suspend fun listAgents() = metadataGateway.listAgents()

    suspend fun listCommands(directory: String?) = metadataGateway.listCommands(directory)

    suspend fun listReferences() = metadataGateway.listReferences()

    suspend fun listSkills() = metadataGateway.listSkills()

    suspend fun findWorkspaceEntries(query: String, directory: String?, limit: Int) =
        metadataGateway.findWorkspaceEntries(query, directory, limit)

    suspend fun listWorkspaceDirectory(path: String?, directory: String?) =
        metadataGateway.listWorkspaceDirectory(path, directory)

    suspend fun listProviderNames() = metadataGateway.listProviderNames()

    suspend fun getSession(sessionId: String) = metadataGateway.getSession(sessionId)

    suspend fun listModels() = metadataGateway.listModels()

    suspend fun switchAgent(sessionId: String, agentId: String): Unit =
        metadataGateway.switchAgent(sessionId, agentId)

    suspend fun switchModel(sessionId: String, providerId: String, modelId: String): Unit =
        metadataGateway.switchModel(sessionId, providerId, modelId)

    suspend fun getSessionUsage(sessionId: String) = metadataGateway.getSessionUsage(sessionId)

    /**
     * 回复权限请求
     */
    suspend fun replyPermission(permissionId: String, decision: PermissionDecision) {
        currentSessionId?.let { sessionId ->
            connections.restClient.replyPermission(sessionId, permissionId, decision)
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
            connections.restClient.interruptSession(sessionId)
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
        val changed = connections.update(
            serverUrl, username, password, cliPath, autoStartServer, reuseExternalServer
        )
        afterConnectionApplied(changed)
    }

    /**
     * Server 运行时选定端点后下发（TSD-31 §3.1）：不覆盖用户配置，运行时探测依据始终是用户配置。
     */
    fun applyManagedEndpoint(endpoint: OpenCodeServerEndpoint) {
        afterConnectionApplied(connections.applyEndpoint(endpoint))
    }

    /** 当前用户配置（Server 运行时读取） */
    fun currentServerConfig(): OpenCodeServerConnectionConfig = connections.pushedConfig

    /** 当前生效的 Server 地址 */
    fun getServerUrl(): String = connections.serverUrl

    /** 当前生效的 REST 客户端（设置页复用其连接配置） */
    fun getRestClient(): OpenCodeRestClient = connections.restClient

    /**
     * 配置落库后的连接编排：三元组变更走 restart 并刷新会话列表；未变更仅 start（保活，对齐原语义）。
     */
    private fun afterConnectionApplied(changed: Boolean) {
        if (changed) {
            eventStream.restart(connections.serverUrl, connections.username, connections.password)
            scope.launch { loadSessions() }
        } else {
            eventStream.start(connections.serverUrl, connections.username, connections.password)
        }
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
        eventStream.stop()
        scope.cancel()
    }
}