@file:Suppress("UnstableApiUsage")

package com.ayongw.plugins.opencode.backend

import com.ayongw.plugins.opencode.shared.ChatMessageDto
import com.ayongw.plugins.opencode.shared.ChatRepositoryRpcApi
import com.ayongw.plugins.opencode.shared.ContextFileDto
import com.ayongw.plugins.opencode.shared.ContextSelectionDto
import com.ayongw.plugins.opencode.shared.MessagePartDto
import com.ayongw.plugins.opencode.shared.PermissionResponse
import com.ayongw.plugins.opencode.shared.ServerInfoDto
import com.ayongw.plugins.opencode.shared.SessionStateDto
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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

    // ==================== 新增方法存根 ====================

    override suspend fun getSessionStateFlow(projectId: ProjectId, sessionId: String): Flow<SessionStateDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        // 暂时复用现有消息流，转换为 SessionStateDto
        return model.getMessagesFlow().map { messages: List<ChatMessageDto> ->
            SessionStateDto(
                sessionId = sessionId,
                title = "会话 $sessionId",
                parts = messages.map { msgDto: ChatMessageDto ->
                    MessagePartDto(
                        id = msgDto.id,
                        type = com.ayongw.plugins.opencode.shared.MessagePart.PartType.TEXT,
                        content = msgDto.content,
                        metadata = emptyMap(),
                        timestamp = msgDto.timestamp,
                        isStreaming = false
                    )
                },
                status = com.ayongw.plugins.opencode.shared.SessionStatus.IDLE,
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
        return model.getMessagesFlow().map { messages: List<ChatMessageDto> ->
            listOf(SessionStateDto(
                sessionId = "default",
                title = "默认会话",
                parts = messages.map { msgDto: ChatMessageDto ->
                    MessagePartDto(
                        id = msgDto.id,
                        type = com.ayongw.plugins.opencode.shared.MessagePart.PartType.TEXT,
                        content = msgDto.content,
                        metadata = emptyMap(),
                        timestamp = msgDto.timestamp,
                        isStreaming = false
                    )
                },
                status = com.ayongw.plugins.opencode.shared.SessionStatus.IDLE,
                pendingPermission = null,
                createdAt = java.time.LocalDateTime.now(),
                updatedAt = java.time.LocalDateTime.now(),
                contextFiles = emptyList()
            ))
        }
    }

    override suspend fun createSession(projectId: ProjectId, initialTitle: String?): String {
        return java.util.UUID.randomUUID().toString()
    }

    override suspend fun switchSession(projectId: ProjectId, sessionId: String) {
        // TODO: 实现会话切换
    }

    override suspend fun deleteSession(projectId: ProjectId, sessionId: String) {
        // TODO: 实现会话删除
    }

    override suspend fun renameSession(projectId: ProjectId, sessionId: String, newTitle: String) {
        // TODO: 实现会话重命名
    }

    override suspend fun replyPermission(
        projectId: ProjectId,
        sessionId: String,
        permissionId: String,
        response: PermissionResponse
    ) {
        // TODO: 实现权限回复
    }

    override suspend fun abortExecution(projectId: ProjectId, sessionId: String) {
        // TODO: 实现中止执行
    }

    override suspend fun addContextFile(projectId: ProjectId, sessionId: String, contextFile: ContextFileDto) {
        // TODO: 实现添加上下文文件
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
        return ServerInfoDto(
            isRunning = false,
            serverUrl = null,
            port = null,
            version = null,
            error = "Server not started"
        )
    }
}