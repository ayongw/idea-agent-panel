@file:Suppress("UnstableApiUsage")

package com.ayongw.idea.opencode.shared

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Interface defining the contract for managing chat messages and interactions within a chat system.
 * Provides access to the flow of messages and supports operations for sending and editing chat messages.
 */
@Rpc
interface ChatRepositoryRpcApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): ChatRepositoryRpcApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<ChatRepositoryRpcApi>())
        }
    }

    /**
     * Flow that emits a list of chat messages.
     * Updates with new messages as they are received or edited.
     * @deprecated Use getSessionStateFlow instead
     */
    @Deprecated("Use getSessionStateFlow", ReplaceWith("getSessionStateFlow(projectId)"))
    suspend fun getMessagesFlow(projectId: ProjectId): Flow<List<ChatMessageDto>>

    /**
     * Sends a message with the provided content.
     *
     * @param messageContent The content of the message to be sent.
     */
    suspend fun sendMessage(projectId: ProjectId, messageContent: String)

    // ==================== 新增方法 ====================

    /**
     * 获取会话状态流（包含完整会话状态：消息、状态、权限请求等）
     */
    suspend fun getSessionStateFlow(projectId: ProjectId, sessionId: String): Flow<SessionStateDto>

    /**
     * 获取所有会话列表
     */
    suspend fun getAllSessions(projectId: ProjectId): Flow<List<SessionStateDto>>

    /**
     * 创建新会话
     * @return 新会话的 sessionId
     */
    suspend fun createSession(projectId: ProjectId, initialTitle: String? = null): String

    /**
     * 切换会话
     */
    suspend fun switchSession(projectId: ProjectId, sessionId: String)

    /**
     * 删除会话
     */
    suspend fun deleteSession(projectId: ProjectId, sessionId: String)

    /**
     * 重命名会话
     */
    suspend fun renameSession(projectId: ProjectId, sessionId: String, newTitle: String)

    /**
     * 回复权限请求
     * @param response ALLOW_ONCE / ALLOW_ALWAYS / REJECT
     */
    suspend fun replyPermission(
        projectId: ProjectId,
        sessionId: String,
        permissionId: String,
        response: PermissionResponse
    )

    /**
     * 中止当前执行
     */
    suspend fun abortExecution(projectId: ProjectId, sessionId: String)

    /**
     * 添加显式上下文文件
     */
    suspend fun addContextFile(projectId: ProjectId, sessionId: String, contextFile: ContextFileDto)

    /**
     * 移除显式上下文文件
     */
    suspend fun removeContextFile(projectId: ProjectId, sessionId: String, filePath: String)

    /**
     * 清空显式上下文
     */
    suspend fun clearContextFiles(projectId: ProjectId, sessionId: String)

    /**
     * 设置当前选区上下文
     */
    suspend fun setContextSelection(projectId: ProjectId, sessionId: String, selection: ContextSelectionDto?)

    /**
     * 获取 OpenCode Server 连接信息
     */
    suspend fun getServerInfo(projectId: ProjectId): ServerInfoDto

    /**
     * 下发 OpenCode Server 连接配置（前端设置页 → 后端）
     * @param serverUrl Server 地址，如 http://127.0.0.1:4096
     * @param username Basic 认证用户名，默认 opencode
     * @param password Basic 认证密码，为空表示不鉴权
     */
    suspend fun updateServerConfig(projectId: ProjectId, serverUrl: String, username: String, password: String)
}

/** Server 连接信息 */
@Serializable
data class ServerInfoDto(
    val isRunning: Boolean,
    val serverUrl: String?,
    val port: Int?,
    val version: String?,
    val error: String?
)