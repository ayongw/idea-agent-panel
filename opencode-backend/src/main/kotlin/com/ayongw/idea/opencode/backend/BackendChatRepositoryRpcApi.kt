@file:Suppress("UnstableApiUsage")

package com.ayongw.idea.opencode.backend

import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.ayongw.idea.opencode.shared.ChatMessageDto
import com.ayongw.idea.opencode.shared.ChatRepositoryRpcApi
import com.ayongw.idea.opencode.shared.ContextFileDto
import com.ayongw.idea.opencode.shared.ContextSelectionDto
import com.ayongw.idea.opencode.shared.MessagePartDto
import com.ayongw.idea.opencode.shared.PermissionResponse
import com.ayongw.idea.opencode.shared.ServerInfoDto
import com.ayongw.idea.opencode.shared.SessionStateDto
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first

class BackendChatRepositoryRpcApi : ChatRepositoryRpcApi {
    override suspend fun getMessagesFlow(projectId: ProjectId): Flow<List<ChatMessageDto>> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        return BackendChatRepositoryModel.getInstance(backendProject).getMessagesFlow()
    }

    override suspend fun sendMessage(
        projectId: ProjectId,
        messageContent: String
    ) {
        val backendProject = projectId.findProjectOrNull() ?: return
        return BackendChatRepositoryModel.getInstance(backendProject).sendMessage(messageContent)
    }

    // ==================== 会话管理 ====================

    override suspend fun getSessionStateFlow(projectId: ProjectId, sessionId: String): Flow<SessionStateDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        // 切换到指定会话并返回状态流
        model.switchSession(sessionId)
        return model.getMessagesFlow().map { messages ->
            SessionStateDto(
                sessionId = sessionId,
                title = getSessionTitle(model, sessionId),
                parts = messages.map { msgDto ->
                    MessagePartDto(
                        id = msgDto.id,
                        type = com.ayongw.idea.opencode.shared.MessagePart.PartType.TEXT,
                        content = msgDto.content,
                        metadata = emptyMap(),
                        timestamp = msgDto.timestamp,
                        isStreaming = false
                    )
                },
                status = com.ayongw.idea.opencode.shared.SessionStatus.IDLE,
                pendingPermission = null,
                createdAt = java.time.LocalDateTime.now(),
                updatedAt = java.time.LocalDateTime.now(),
                contextFiles = emptyList()
            )
        }
    }

    override suspend fun getAllSessions(projectId: ProjectId): Flow<List<SessionStateDto>> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        val model = BackendChatRepositoryModel.getInstance(backendProject)

        // 加载会话列表
        model.loadSessions()

        return model.getAllSessionsFlow().map { sessions ->
            sessions.map { session ->
                SessionStateDto(
                    sessionId = session.id,
                    title = session.title,
                    parts = emptyList(),
                    status = if (model.getCurrentSessionId() == session.id)
                        com.ayongw.idea.opencode.shared.SessionStatus.IDLE
                    else
                        com.ayongw.idea.opencode.shared.SessionStatus.IDLE,
                    pendingPermission = null,
                    createdAt = toLocalDateTime(session.createdAtMillis),
                    updatedAt = toLocalDateTime(session.updatedAtMillis),
                    contextFiles = emptyList()
                )
            }
        }
    }

    override suspend fun createSession(projectId: ProjectId, initialTitle: String?): String {
        val backendProject = projectId.findProjectOrNull() ?: return java.util.UUID.randomUUID().toString()
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        val sessionId = model.createNewSession(initialTitle)
        return sessionId ?: java.util.UUID.randomUUID().toString()
    }

    override suspend fun switchSession(projectId: ProjectId, sessionId: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        model.switchSession(sessionId)
    }

    override suspend fun deleteSession(projectId: ProjectId, sessionId: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        model.deleteSession(sessionId)
    }

    override suspend fun renameSession(projectId: ProjectId, sessionId: String, newTitle: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        model.renameSession(sessionId, newTitle)
    }

    override suspend fun replyPermission(
        projectId: ProjectId,
        sessionId: String,
        permissionId: String,
        response: PermissionResponse
    ) {
        val backendProject = projectId.findProjectOrNull() ?: return
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        val decision = when (response) {
            PermissionResponse.ALLOW_ALWAYS -> OpenCodeRestClient.PermissionDecision.ALWAYS
            PermissionResponse.ALLOW_ONCE -> OpenCodeRestClient.PermissionDecision.ONCE
            else -> OpenCodeRestClient.PermissionDecision.REJECT
        }
        model.replyPermission(permissionId, decision)
    }

    override suspend fun abortExecution(projectId: ProjectId, sessionId: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        model.abortExecution()
    }

    override suspend fun addContextFile(projectId: ProjectId, sessionId: String, contextFile: ContextFileDto) {
        // TODO: 实现添加上下文文件到 OpenCode Server
    }

    override suspend fun removeContextFile(projectId: ProjectId, sessionId: String, filePath: String) {
        // TODO: 实现移除上下文文件
    }

    override suspend fun clearContextFiles(projectId: ProjectId, sessionId: String) {
        // TODO: 实现清空上下文文件
    }

    override suspend fun setContextSelection(projectId: ProjectId, sessionId: String, selection: ContextSelectionDto?) {
        // TODO: 实现设置选区上下文
    }

    override suspend fun getServerInfo(projectId: ProjectId): ServerInfoDto {
        val backendProject = projectId.findProjectOrNull() ?: return ServerInfoDto(false, null, null, null, "Project not found")
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        val connected = model.getServerConnectedFlow().first()
        val serverUrl = model.getServerUrl()
        return ServerInfoDto(
            isRunning = connected,
            serverUrl = if (connected) serverUrl else null,
            port = if (connected) parsePort(serverUrl) else null,
            version = if (connected) "OpenCode Server" else null,
            error = if (connected) null else "OpenCode Server not running"
        )
    }

    override suspend fun updateServerConfig(projectId: ProjectId, serverUrl: String, username: String, password: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).updateServerConfig(serverUrl, username, password)
    }

    private fun parsePort(serverUrl: String): Int? =
        runCatching { java.net.URI(serverUrl).port }.getOrNull()?.takeIf { it > 0 }

    private fun toLocalDateTime(epochMillis: Long): java.time.LocalDateTime =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()

    private suspend fun getSessionTitle(model: BackendChatRepositoryModel, sessionId: String): String {
        // 从会话列表中查找标题
        return try {
            val sessions = model.getAllSessionsFlow().first()
            sessions.firstOrNull { it.id == sessionId }?.title ?: "会话 $sessionId"
        } catch (e: Exception) {
            "会话 $sessionId"
        }
    }
}