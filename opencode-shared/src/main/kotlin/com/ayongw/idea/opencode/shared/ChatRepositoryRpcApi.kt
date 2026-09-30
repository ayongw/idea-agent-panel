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
     * 发送消息并携带本次上下文（输入框 mention 的解析结果 + 命令名）
     */
    suspend fun sendMessageWithContext(
        projectId: ProjectId,
        messageContent: String,
        context: PromptContextDto
    )

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
     * 添加显式上下文附件（文件 / 目录 / 技能 / 规则 / 命令）
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
     * 订阅指定会话的上下文附件（会话级，发送消息时随 prompt 一起下发）
     */
    suspend fun getContextFilesFlow(projectId: ProjectId, sessionId: String): Flow<List<ContextFileDto>>

    // ==================== 输入区候选（命令 / 规则 / 工作区文件） ====================

    /**
     * 可用命令清单（内置 + 自定义），对应 v2 `GET /api/command`
     */
    suspend fun listCommands(projectId: ProjectId): List<CommandDto>

    /**
     * 规则清单，对应 v2 `GET /api/reference`
     */
    suspend fun listReferences(projectId: ProjectId): List<ReferenceDto>

    /**
     * 技能清单，对应 v2 `GET /api/skill`
     */
    suspend fun listSkills(projectId: ProjectId): List<SkillDto>

    /**
     * 工作区文件检索（文件与目录），对应 v2 `GET /api/fs/find`
     */
    suspend fun findWorkspaceEntries(projectId: ProjectId, query: String, limit: Int = 50): List<WorkspaceEntryDto>

    /**
     * 浏览工作区目录，对应 v2 `GET /api/fs/list`
     *
     * @param path 目录相对路径；null 表示工作区根目录
     */
    suspend fun listWorkspaceDirectory(projectId: ProjectId, path: String?): List<WorkspaceEntryDto>

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

    /**
     * 列出可用 Agent（模式），对应 v2 GET /api/agent
     */
    suspend fun listAgents(projectId: ProjectId): List<AgentDto>

    /**
     * 列出可用模型，对应 v2 GET /api/model
     */
    suspend fun listModels(projectId: ProjectId): List<ModelDto>

    /**
     * 按供应商分组的模型清单（模型选择弹窗用）：`GET /api/model` + `GET /api/provider` 组装
     */
    suspend fun listModelProviders(projectId: ProjectId): List<ModelProviderDto>

    /**
     * 服务端默认模型（配置里的 `model`），对应 v2 GET /api/model/default；未配置时为 null
     */
    suspend fun getDefaultModel(projectId: ProjectId): DefaultModelDto?

    /**
     * 会话当前选中的模式与模型（切换会话后回读）
     */
    suspend fun getSessionSelection(projectId: ProjectId, sessionId: String): SessionSelectionDto

    /**
     * 切换当前会话的 Agent（模式），对应 v2 POST /api/session/{sessionID}/agent
     */
    suspend fun switchAgent(projectId: ProjectId, sessionId: String, agentId: String)

    /**
     * 切换当前会话的模型，对应 v2 POST /api/session/{sessionID}/model
     */
    suspend fun switchModel(projectId: ProjectId, sessionId: String, providerID: String, modelID: String)

    /**
     * 获取指定会话的用量快照（累计 tokens/cost + 最近一次 step 的 input + 模型上下文窗口）
     * @return 服务不可达或无数据时返回空的 [SessionUsageDto]
     */
    suspend fun getSessionUsage(projectId: ProjectId, sessionId: String): SessionUsageDto

    /**
     * 获取指定会话的执行状态流（事件流驱动）
     *
     * @return 该会话为当前会话时持续推送；非当前会话恒为 false
     */
    suspend fun getSessionRunningFlow(projectId: ProjectId, sessionId: String): Flow<Boolean>

    /**
     * 获取指定会话的待决权限请求流（事件流 `permission.asked` 驱动）
     *
     * @return null 表示当前无待决项（已回复 / 已结束 / 非当前会话）
     */
    suspend fun getPendingPermissionFlow(projectId: ProjectId, sessionId: String): Flow<PendingPermissionDto?>
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