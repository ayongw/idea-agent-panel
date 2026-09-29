@file:Suppress("UnstableApiUsage")

package com.ayongw.plugins.opencode.frontend.chatApp.viewmodel

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.Service.Level
import com.intellij.openapi.project.Project
import com.intellij.platform.project.projectId
import fleet.rpc.client.durable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import com.ayongw.plugins.opencode.shared.ChatMessage
import com.ayongw.plugins.opencode.shared.ChatRepositoryRpcApi
import com.ayongw.plugins.opencode.shared.PermissionResponse
import com.ayongw.plugins.opencode.shared.SessionStateDto
import com.ayongw.plugins.opencode.shared.toChatMessage

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

    override suspend fun sendMessage(messageContent: String) {
        ChatRepositoryRpcApi.getInstance().sendMessage(project.projectId(), messageContent)
    }

    override suspend fun createSession(initialTitle: String?): String {
        val sessionId = ChatRepositoryRpcApi.getInstance().createSession(project.projectId(), initialTitle)
        _currentSessionId.value = sessionId
        coroutineScope.launch { refreshSessions() }
        return sessionId
    }

    override suspend fun switchSession(sessionId: String) {
        ChatRepositoryRpcApi.getInstance().switchSession(project.projectId(), sessionId)
        _currentSessionId.value = sessionId
        coroutineScope.launch { refreshSessions() }
    }

    override suspend fun deleteSession(sessionId: String) {
        ChatRepositoryRpcApi.getInstance().deleteSession(project.projectId(), sessionId)
        if (_currentSessionId.value == sessionId) {
            _currentSessionId.value = null
        }
        coroutineScope.launch { refreshSessions() }
    }

    override suspend fun renameSession(sessionId: String, newTitle: String) {
        ChatRepositoryRpcApi.getInstance().renameSession(project.projectId(), sessionId, newTitle)
        coroutineScope.launch { refreshSessions() }
    }

    override suspend fun replyPermission(permissionId: String, allow: Boolean) {
        _currentSessionId.value?.let { sessionId ->
            val response = if (allow) PermissionResponse.ALLOW_ONCE else PermissionResponse.REJECT
            ChatRepositoryRpcApi.getInstance().replyPermission(project.projectId(), sessionId, permissionId, response)
        }
    }

    override suspend fun abortExecution() {
        _currentSessionId.value?.let { sessionId ->
            ChatRepositoryRpcApi.getInstance().abortExecution(project.projectId(), sessionId)
        }
    }

    private fun refreshSessions() {
        coroutineScope.launch {
            ChatRepositoryRpcApi.getInstance().getAllSessions(project.projectId()).collect { sessions ->
                _allSessionsFlow.value = sessions
            }
        }
    }

    // 初始化：延迟加载会话列表和服务器状态
    init {
        coroutineScope.launch {
            refreshSessions()
            coroutineScope.launch {
                val info = ChatRepositoryRpcApi.getInstance().getServerInfo(project.projectId())
                _serverConnectedFlow.value = info.isRunning
            }
        }
    }
}