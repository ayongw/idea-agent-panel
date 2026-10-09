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
     * 消息流（REST/事件流对账后的 ChatMessage 列表），前端订阅为消息渲染真源。
     * Updates with new messages as they are received or edited.
     */
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
     * 获取所有会话列表
     */
    suspend fun getAllSessions(projectId: ProjectId): Flow<List<SessionStateDto>>

    /**
     * 创建新会话
     * @return 新会话的 sessionId
     */
    suspend fun createSession(projectId: ProjectId, initialTitle: String? = null): String

    /**
     * 切换会话；返回是否成功（消息已加载）。
     *
     * 返回成功标志而非 Unit：失败时前端不能乐观改 currentSessionId，
     * 否则会出现「tab 高亮切过去了、消息却是旧的」观感。
     */
    suspend fun switchSession(projectId: ProjectId, sessionId: String): Boolean

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
     * @param cliPath opencode CLI 路径覆盖；null/空 = 从 PATH 解析
     * @param autoStartServer 是否允许插件自动拉起 server
     * @param reuseExternalServer 是否允许复用非本插件启动的实例
     */
    suspend fun updateServerConfig(
        projectId: ProjectId,
        serverUrl: String,
        username: String,
        password: String,
        cliPath: String? = null,
        autoStartServer: Boolean = true,
        reuseExternalServer: Boolean = true
    )

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

    // ==================== Server 运行时（进程与连接管理，TSD-31） ====================

    /**
     * Server 运行状态流（状态条订阅）：状态、失败分类、端口、是否自有、引用计数、输出尾巴
     */
    suspend fun getServerStateFlow(projectId: ProjectId): Flow<ServerStateDto>

    /**
     * 生成 Git 提交信息（TSD-33）。
     *
     * 用**一次性会话**调用 opencode：建会话 → 发提示 → 取回复 → 删会话，
     * 不污染用户工作区的历史会话列表。
     */
    suspend fun generateCommitMessage(
        projectId: ProjectId,
        request: CommitMessageRequestDto
    ): CommitMessageResultDto

    /**
     * 重试启动 Server（等价于重新探测 → 复用 / 拉起）
     */
    suspend fun retryServerStart(projectId: ProjectId)

    /**
     * 跳过探测，直接拉起**本插件自有**的 Server
     *
     * 用于 `NEEDS_CREDENTIALS` 交互中的「改用插件自启实例」：用户不愿/无法提供他人实例密钥时，
     * 在备用端口上拉起自有实例（4096 常已被该他人实例占用）。
     */
    suspend fun startOwnServer(projectId: ProjectId)

    /**
     * 停止**本插件启动的** Server（强制归零引用后优雅终止）
     *
     * @return false 表示当前端点非自有实例（他人实例一律不终止）
     */
    suspend fun stopServer(projectId: ProjectId): Boolean

    /**
     * 提交他人实例的接入凭据（`NEEDS_CREDENTIALS` 交互）
     *
     * @return true 表示凭据校验通过并已复用该实例
     */
    suspend fun submitServerCredentials(projectId: ProjectId, username: String, password: String): Boolean

    /**
     * 排障兜底：清空共享注册表（不终止任何进程）
     */
    suspend fun resetServerRegistry(projectId: ProjectId)

    /**
     * 解析 opencode CLI 的实际可执行路径（设置页只读展示「会用到的路径」）
     *
     * 与拉起同源：设置项优先（非空但不可执行即视为未找到，**不回退** `PATH`）→
     * 登录 shell 的 `PATH`（IDE 进程 `PATH` 极窄，见 `LoginShellPath`）。
     * 阻塞（首次要探一次登录 shell），实现方放后台线程。
     *
     * @param cliPath 待解析的设置项值；null/空 = 从 `PATH` 解析
     * @return 可执行文件绝对路径；未找到返回 null
     */
    suspend fun resolveCliPath(projectId: ProjectId, cliPath: String? = null): String?
}

/**
 * Server 运行时状态快照（TSD-31 §6.3）
 *
 * 枚举以字符串下发，避免 RPC 侧对枚举序列化形态的额外约束；新增字段一律带默认值以保持向后兼容。
 */
@Serializable
data class ServerStateDto(
    /** 状态枚举名：IDLE / DISCOVERING / REUSING / NEEDS_CREDENTIALS / STARTING / READY / FAILED / STOPPING / STOPPED */
    val state: String,
    /** 失败分类枚举名：CLI_NOT_FOUND / PORT_IN_USE / AUTH_FAILED / READY_TIMEOUT / PROCESS_EXITED / UNREACHABLE */
    val failure: String? = null,
    /** 面向用户的补充说明（已脱敏） */
    val detail: String? = null,
    /** 当前端点地址（已剥离 userinfo） */
    val baseUrl: String? = null,
    val port: Int? = null,
    /** 端点是否由本插件拉起（决定能否「停止 Server」） */
    val owned: Boolean = false,
    /** 共享注册表上的引用者数量（自有实例才有意义） */
    val refCount: Int = 0,
    /** 失败时的输出尾巴（已脱敏，供展开查看） */
    val outputTail: List<String> = emptyList(),
) {
    companion object {
        // 状态枚举名（与后端 `OpenCodeServerState` 同名，前端据此决定状态条显隐与按钮）
        const val STATE_IDLE = "IDLE"
        const val STATE_DISCOVERING = "DISCOVERING"
        const val STATE_REUSING = "REUSING"
        const val STATE_NEEDS_CREDENTIALS = "NEEDS_CREDENTIALS"
        const val STATE_STARTING = "STARTING"
        const val STATE_READY = "READY"
        const val STATE_FAILED = "FAILED"
        const val STATE_STOPPING = "STOPPING"
        const val STATE_STOPPED = "STOPPED"

        // 失败分类枚举名（与后端 `OpenCodeServerFailure` 同名）
        const val FAILURE_CLI_NOT_FOUND = "CLI_NOT_FOUND"
        const val FAILURE_PORT_IN_USE = "PORT_IN_USE"
        const val FAILURE_AUTH_FAILED = "AUTH_FAILED"
        const val FAILURE_READY_TIMEOUT = "READY_TIMEOUT"
        const val FAILURE_PROCESS_EXITED = "PROCESS_EXITED"
        const val FAILURE_UNREACHABLE = "UNREACHABLE"
    }
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

// ==================== TSD-33：Git 提交信息生成 ====================

/**
 * 生成提交信息的请求。
 *
 * @param prompt 已组装并限长的提示词（前端负责采集与限长，见 CommitMessagePromptBuilder）
 * @param providerId 用户指定的生成模型供应商；**空表示未配置**，后端据此直接失败并引导设置
 * @param modelId 用户指定的生成模型 id
 */
@Serializable
data class CommitMessageRequestDto(
    val prompt: String,
    val providerId: String = "",
    val modelId: String = ""
)

/** 生成结果 */
@Serializable
data class CommitMessageResultDto(
    /** 是否成功 */
    val success: Boolean,
    /** 生成的提交信息文本（失败时为空） */
    val text: String = "",
    /** 失败分类，供前端选择提示与引导方式 */
    val reason: String = "",
    /** 面向用户的补充说明（已脱敏） */
    val detail: String = ""
) {
    companion object {
        /** 未配置生成模型：前端弹引导提示 + 「去设置」 */
        const val REASON_NO_MODEL = "NO_MODEL"
        /** 服务端不可达 / 调用失败 */
        const val REASON_UNAVAILABLE = "UNAVAILABLE"
        /** 超时 */
        const val REASON_TIMEOUT = "TIMEOUT"
    }
}
