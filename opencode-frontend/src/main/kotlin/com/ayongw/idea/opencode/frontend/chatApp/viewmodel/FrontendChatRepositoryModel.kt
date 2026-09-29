@file:Suppress("UnstableApiUsage")

package com.ayongw.idea.opencode.frontend.chatApp.viewmodel

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.ayongw.idea.opencode.frontend.settings.OpenCodePasswordStore
import com.ayongw.idea.opencode.frontend.settings.OpenCodeSettingsState
import com.ayongw.idea.opencode.shared.AgentDto
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ChatRepositoryRpcApi
import com.ayongw.idea.opencode.shared.ModelDto
import com.ayongw.idea.opencode.shared.PendingPermissionDto
import com.ayongw.idea.opencode.shared.PermissionResponse
import com.ayongw.idea.opencode.shared.SessionStateDto
import com.ayongw.idea.opencode.shared.SessionUsageDto
import com.ayongw.idea.opencode.shared.toChatMessage

@Service(Level.PROJECT)
class FrontendChatRepositoryModel(
    private val project: Project,
    private val coroutineScope: CoroutineScope
) : ChatRepositoryApi {
    companion object {
        fun getInstance(project: Project): FrontendChatRepositoryModel {
            return project.getService(FrontendChatRepositoryModel::class.java)
        }
    }

    private val _allSessionsFlow = MutableStateFlow(emptyList<SessionStateDto>())
    private val _serverConnectedFlow = MutableStateFlow(false)
    private val _currentSessionId = MutableStateFlow<String?>(null)
    private val _sessionRunningFlow = MutableStateFlow(false)
    private val _pendingPermissionFlow = MutableStateFlow<PendingPermissionDto?>(null)

    /** 当前会话的执行状态 / 待决权限订阅任务（切换会话时重启） */
    private var runningJob: Job? = null
    private var permissionJob: Job? = null

    override val messagesFlow: StateFlow<List<ChatMessage>> = flow {
        durable {
            ChatRepositoryRpcApi.getInstance().getMessagesFlow(project.projectId()).collect { valueFromBackend ->
                val mappedValue = valueFromBackend.map { messageDto -> messageDto.toChatMessage() }
                emit(mappedValue)
            }
        }
    }.stateIn(coroutineScope, initialValue = emptyList(), started = SharingStarted.Lazily)

    override val allSessionsFlow: StateFlow<List<SessionStateDto>> = _allSessionsFlow

    override val serverConnectedFlow: StateFlow<Boolean> = _serverConnectedFlow

    override val currentSessionId: StateFlow<String?> = _currentSessionId

    override val sessionRunningFlow: StateFlow<Boolean> = _sessionRunningFlow

    override val pendingPermissionFlow: StateFlow<PendingPermissionDto?> = _pendingPermissionFlow

    override suspend fun sendMessage(messageContent: String) {
        ChatRepositoryRpcApi.getInstance().sendMessage(project.projectId(), messageContent)
    }

    override suspend fun createSession(initialTitle: String?): String {
        val sessionId = ChatRepositoryRpcApi.getInstance().createSession(project.projectId(), initialTitle)
        _currentSessionId.value = sessionId
        refreshSessionScopedFlows()
        coroutineScope.launch { refreshSessions() }
        return sessionId
    }

    override suspend fun switchSession(sessionId: String) {
        ChatRepositoryRpcApi.getInstance().switchSession(project.projectId(), sessionId)
        _currentSessionId.value = sessionId
        refreshSessionScopedFlows()
        coroutineScope.launch { refreshSessions() }
    }

    override suspend fun deleteSession(sessionId: String) {
        ChatRepositoryRpcApi.getInstance().deleteSession(project.projectId(), sessionId)
        if (_currentSessionId.value == sessionId) {
            _currentSessionId.value = null
            refreshSessionScopedFlows()
        }
        coroutineScope.launch { refreshSessions() }
    }

    override suspend fun renameSession(sessionId: String, newTitle: String) {
        ChatRepositoryRpcApi.getInstance().renameSession(project.projectId(), sessionId, newTitle)
        coroutineScope.launch { refreshSessions() }
    }

    override suspend fun replyPermission(permissionId: String, response: PermissionResponse) {
        _currentSessionId.value?.let { sessionId ->
            ChatRepositoryRpcApi.getInstance().replyPermission(project.projectId(), sessionId, permissionId, response)
        }
    }

    override suspend fun abortExecution() {
        _currentSessionId.value?.let { sessionId ->
            ChatRepositoryRpcApi.getInstance().abortExecution(project.projectId(), sessionId)
        }
    }

    override suspend fun listAgents(): List<AgentDto> =
        ChatRepositoryRpcApi.getInstance().listAgents(project.projectId())

    override suspend fun listModels(): List<ModelDto> =
        ChatRepositoryRpcApi.getInstance().listModels(project.projectId())

    override suspend fun switchAgent(agentId: String) {
        _currentSessionId.value?.let { sessionId ->
            ChatRepositoryRpcApi.getInstance().switchAgent(project.projectId(), sessionId, agentId)
        }
    }

    override suspend fun switchModel(providerID: String, modelID: String) {
        _currentSessionId.value?.let { sessionId ->
            ChatRepositoryRpcApi.getInstance().switchModel(project.projectId(), sessionId, providerID, modelID)
        }
    }

    override suspend fun getSessionUsage(): SessionUsageDto? {
        val sessionId = _currentSessionId.value ?: return null
        return ChatRepositoryRpcApi.getInstance().getSessionUsage(project.projectId(), sessionId)
    }

    private fun refreshSessions() {
        coroutineScope.launch {
            ChatRepositoryRpcApi.getInstance().getAllSessions(project.projectId()).collect { sessions ->
                _allSessionsFlow.value = sessions
            }
        }
    }

    /** 订阅「随当前会话变化」的状态（执行态 / 待决权限）；切换会话时重启，无当前会话时归零 */
    private fun refreshSessionScopedFlows() {
        runningJob?.cancel()
        permissionJob?.cancel()
        val sessionId = _currentSessionId.value
        if (sessionId == null) {
            _sessionRunningFlow.value = false
            _pendingPermissionFlow.value = null
            return
        }
        val projectId = project.projectId()
        runningJob = coroutineScope.launch {
            ChatRepositoryRpcApi.getInstance()
                .getSessionRunningFlow(projectId, sessionId)
                .collect { _sessionRunningFlow.value = it }
        }
        permissionJob = coroutineScope.launch {
            ChatRepositoryRpcApi.getInstance()
                .getPendingPermissionFlow(projectId, sessionId)
                .collect { _pendingPermissionFlow.value = it }
        }
    }

    // 初始化：下发设置 → 延迟加载会话列表和服务器状态
    init {
        coroutineScope.launch {
            // 应用级设置下发到后端，保证重启后按设置连接
            val settings = OpenCodeSettingsState.getInstance()
            runCatching {
                ChatRepositoryRpcApi.getInstance()
                    .updateServerConfig(
                        project.projectId(),
                        settings.serverUrl,
                        settings.username,
                        OpenCodePasswordStore.load()
                    )
            }
            refreshSessions()
            coroutineScope.launch {
                val info = ChatRepositoryRpcApi.getInstance().getServerInfo(project.projectId())
                _serverConnectedFlow.value = info.isRunning
            }
        }
    }
}