package com.ayongw.idea.opencode.frontend.chatApp.viewmodel

import kotlinx.coroutines.flow.StateFlow
import com.ayongw.idea.opencode.shared.AgentDto
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.CommandDto
import com.ayongw.idea.opencode.shared.ContextFileDto
import com.ayongw.idea.opencode.shared.DefaultModelDto
import com.ayongw.idea.opencode.shared.ModelDto
import com.ayongw.idea.opencode.shared.ModelProviderDto
import com.ayongw.idea.opencode.shared.PendingPermissionDto
import com.ayongw.idea.opencode.shared.PermissionResponse
import com.ayongw.idea.opencode.shared.PromptContextDto
import com.ayongw.idea.opencode.shared.ReferenceDto
import com.ayongw.idea.opencode.shared.SessionSelectionDto
import com.ayongw.idea.opencode.shared.SessionStateDto
import com.ayongw.idea.opencode.shared.SessionUsageDto
import com.ayongw.idea.opencode.shared.SkillDto
import com.ayongw.idea.opencode.shared.WorkspaceEntryDto

/**
 * Interface defining the contract for managing chat messages and sessions within a chat system.
 * Provides access to the flow of messages and supports operations for session management.
 */
interface ChatRepositoryApi {
    /**
     * Flow that emits a list of chat messages.
     * Updates with new messages as they are received or edited.
     */
    val messagesFlow: StateFlow<List<ChatMessage>>

    /**
     * Flow that emits all sessions.
     */
    val allSessionsFlow: StateFlow<List<SessionStateDto>>

    /**
     * Flow that emits server connection status.
     */
    val serverConnectedFlow: StateFlow<Boolean>

    /**
     * Current session ID flow.
     */
    val currentSessionId: StateFlow<String?>

    /**
     * Whether the current session is executing (driven by the server event stream).
     * Drives the input box between "send" and "stop".
     */
    val sessionRunningFlow: StateFlow<Boolean>

    /**
     * Pending permission request of the current session (driven by the server event stream).
     * null means there is nothing to confirm.
     */
    val pendingPermissionFlow: StateFlow<PendingPermissionDto?>

    /**
     * 发送消息并携带本次上下文（输入框 mention 的解析结果）
     */
    suspend fun sendMessageWithContext(messageContent: String, context: PromptContextDto)

    /**
     * 当前会话的会话附件（＋ 按钮加入，会话级常驻）
     */
    val contextFilesFlow: StateFlow<List<ContextFileDto>>

    /** 添加会话附件 */
    suspend fun addContextFile(attachment: ContextFileDto)

    /** 移除会话附件（按绝对路径） */
    suspend fun removeContextFile(path: String)

    /** 清空当前会话的会话附件 */
    suspend fun clearContextFiles()

    /** 命令清单（`/` 候选） */
    suspend fun listCommands(): List<CommandDto>

    /** 规则清单（`/` 候选） */
    suspend fun listReferences(): List<ReferenceDto>

    /** 技能清单（`/` 候选） */
    suspend fun listSkills(): List<SkillDto>

    /** 工作区文件检索（`#` 候选） */
    suspend fun findWorkspaceEntries(query: String, limit: Int = 50): List<WorkspaceEntryDto>

    /** 工作区目录浏览（`#` 候选，path 为 null 时列工作区根目录） */
    suspend fun listWorkspaceDirectory(path: String?): List<WorkspaceEntryDto>

    /** 按供应商分组的模型清单（模型选择弹窗） */
    suspend fun listModelProviders(): List<ModelProviderDto>

    /**
     * 拉取当前工作区会话列表（启动时拉一次，供「全部会话」弹窗与 tab 恢复使用）
     *
     * @return 本次拉到的会话列表；服务不可达时沿用上一次缓存
     */
    suspend fun loadSessions(): List<SessionStateDto>

    /** 服务端默认模型（配置里的 `model`；未配置或不可达时为 null） */
    suspend fun getDefaultModel(): DefaultModelDto?

    /** 指定会话当前选中的模式与模型（切换会话后回读）；不可达时为 null */
    suspend fun getSessionSelection(sessionId: String): SessionSelectionDto?

    /**
     * Creates a new session.
     *
     * @param initialTitle Optional initial title for the session.
     * @return The new session ID.
     */
    suspend fun createSession(initialTitle: String?): String

    /**
     * Switches to the specified session.
     *
     * @param sessionId The ID of the session to switch to.
     */
    suspend fun switchSession(sessionId: String)

    /**
     * Deletes the specified session.
     *
     * @param sessionId The ID of the session to delete.
     */
    suspend fun deleteSession(sessionId: String)

    /**
     * Renames the specified session.
     *
     * @param sessionId The ID of the session to rename.
     * @param newTitle The new title for the session.
     */
    suspend fun renameSession(sessionId: String, newTitle: String)

    /**
     * Replies to a permission request.
     *
     * @param permissionId The ID of the permission request.
     * @param response ALLOW_ONCE / ALLOW_ALWAYS / REJECT
     */
    suspend fun replyPermission(permissionId: String, response: PermissionResponse)

    /**
     * Aborts the current execution.
     */
    suspend fun abortExecution()

    /**
     * Lists available agents (modes) from the server.
     */
    suspend fun listAgents(): List<AgentDto>

    /**
     * Lists available models from the server.
     */
    suspend fun listModels(): List<ModelDto>

    /**
     * Switches the agent (mode) of the current session.
     */
    suspend fun switchAgent(agentId: String)

    /**
     * Switches the model of the current session.
     */
    suspend fun switchModel(providerID: String, modelID: String)

    /**
     * Gets the token usage snapshot of the current session.
     *
     * @return null when there is no current session; an empty snapshot when the server is unreachable.
     */
    suspend fun getSessionUsage(): SessionUsageDto?
}