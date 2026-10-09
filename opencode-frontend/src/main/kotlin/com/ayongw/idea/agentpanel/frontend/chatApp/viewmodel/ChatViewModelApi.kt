package com.ayongw.idea.agentpanel.frontend.chatApp.viewmodel

import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.MentionSupport
import com.ayongw.idea.agentpanel.shared.AgentDto
import com.ayongw.idea.agentpanel.shared.ChatMessage
import com.ayongw.idea.agentpanel.shared.ContextFileDto
import com.ayongw.idea.agentpanel.shared.ModelDto
import com.ayongw.idea.agentpanel.shared.ModelProviderDto
import com.ayongw.idea.agentpanel.shared.PendingPermissionDto
import com.ayongw.idea.agentpanel.shared.PermissionResponse
import com.ayongw.idea.agentpanel.shared.SessionStateDto
import com.ayongw.idea.agentpanel.shared.SessionUsageDto
import com.ayongw.idea.agentpanel.shared.ServerStateDto
import com.intellij.openapi.Disposable
import kotlinx.coroutines.flow.StateFlow

/**
 * 聊天面板总 API（Phase 3 瘦身）：核心消息/输入状态 + 会话/编排/Server 三组子 API。
 *
 * 核心成员 ≤20；会话管理见 [sessions]，输入区编排（Agent/模型/附件/mention）见 [compose]，
 * Server 进程管理见 [server]。
 */
interface ChatViewModelApi : Disposable {
    val chatMessagesFlow: StateFlow<List<ChatMessage>>
    val currentSessionId: StateFlow<String?>

    /** 当前会话是否正在执行（决定输入框显示「发送」还是「停止」） */
    val sessionRunningFlow: StateFlow<Boolean>

    /**
     * 是否正在切换会话（消息加载中）。
     *
     * 切换期间消息区**刻意保留上一个会话的内容**（失败不清空，保证数据与当前会话一致），
     * 若无任何反馈，用户感知就是「点了没反应」。UI 据此显示加载提示。
     */
    val sessionSwitchingFlow: StateFlow<Boolean>

    /** 当前会话的待决权限请求；null 表示无需确认 */
    val pendingPermissionFlow: StateFlow<PendingPermissionDto?>

    val promptInputState: StateFlow<MessageInputState>

    /** 顶部已打开会话（tab）顺序 */
    val openedSessionIds: StateFlow<List<String>>

    fun onPromptInputChanged(input: String)

    fun onSendMessage()

    fun onAbortSendingMessage()

    /** 回复权限请求（允许一次 / 始终允许 / 拒绝） */
    fun replyPermission(permissionId: String, response: PermissionResponse)

    /** 关闭会话 tab（仅关闭视图，不删除会话） */
    fun closeSessionTab(sessionId: String)

    fun searchChatMessagesHandler(): SearchChatMessagesHandler

    val sessions: SessionApi

    val compose: ComposeApi

    val server: ServerApi
}

/** 会话组：历史会话 CRUD、连接状态、tab 持久化与输入草稿 */
interface SessionApi {
    val allSessionsFlow: StateFlow<List<SessionStateDto>>
    val serverConnectedFlow: StateFlow<Boolean>

    fun createSession(initialTitle: String? = null)

    fun switchSession(sessionId: String)

    fun deleteSession(sessionId: String)

    fun renameSession(sessionId: String, newTitle: String)

    /** 重新拉取本工作区会话列表（打开「全部会话」弹窗前刷新） */
    fun loadSessions()

    /**
     * 会话列表拉取进行中。
     *
     * `allSessionsFlow` 初始为空列表，与「拉到了但确实没有会话」不可区分；
     * UI 据此在首次打开时显示 loading，而不是直接给一个「空列表」的误导结论。
     */
    val sessionsLoading: StateFlow<Boolean>

    /** 切走前保存当前会话草稿 */
    fun saveDraft(sessionId: String?, text: String)

    /** 切回时读取目标会话草稿（无则空） */
    fun loadDraft(sessionId: String?): String

    /** 会话切换后恢复草稿：有草稿恢复为可发送，无则禁用 */
    fun restoreDraft(sessionId: String?, draft: String)
}

/** 输入组：Agent/模型/审批/用量 + 会话附件 + mention/工作区候选 */
interface ComposeApi {
    val agentsFlow: StateFlow<List<AgentDto>>
    val modelsFlow: StateFlow<List<ModelDto>>
    val selectedAgentId: StateFlow<String?>
    val selectedModel: StateFlow<ModelDto?>
    val approvalMode: StateFlow<ApprovalMode>

    /** 当前会话用量（累计 token / 上下文占比）；无数据时为 null */
    val usageFlow: StateFlow<SessionUsageDto?>

    /** 当前会话的会话附件（chips 右段） */
    val contextFilesFlow: StateFlow<List<ContextFileDto>>

    /** `/` 候选（命令 / 技能 / 规则）；首次调用时加载并缓存 */
    val mentionCandidatesFlow: StateFlow<List<MentionSupport.Candidate>>

    /** `#` 候选（工作区检索结果；空 query 时列工作区根目录） */
    val workspaceCandidatesFlow: StateFlow<List<MentionSupport.Candidate>>

    /** 按供应商分组的模型（模型选择弹窗） */
    val modelProvidersFlow: StateFlow<List<ModelProviderDto>>

    /** 拉取 Agent/模型列表（打开底部下拉时调用） */
    fun loadAgentsAndModels()

    fun switchAgent(agentId: String)

    fun switchModel(model: ModelDto)

    fun setApprovalMode(mode: ApprovalMode)

    /** 拉取当前会话用量（切换会话 / 发送 / 中止 / 切换模型后调用） */
    fun refreshUsage()

    /** 添加会话附件（去重由后端保证） */
    fun addAttachments(attachments: List<ContextFileDto>)

    /** 移除会话附件（按绝对路径） */
    fun removeAttachment(path: String)

    /** 加载（或刷新）`/` 候选 */
    fun ensureMentionCandidates(force: Boolean = false)

    /** 检索工作区条目（文件与目录） */
    fun searchWorkspace(query: String)

    /** 浏览工作区目录（进入目录时调用；path 为相对路径） */
    fun browseWorkspaceDirectory(path: String?)

    /** 刷新按供应商分组的模型 */
    fun loadModelProviders()
}

/** Server 运行时组：进程状态与连接管理（TSD-31） */
interface ServerApi {
    /** Server 运行状态流（状态条订阅） */
    val serverStateFlow: StateFlow<ServerStateDto>

    /** 重试启动 Server（重新探测 → 复用 / 拉起） */
    fun retryServerStart()

    /** 跳过探测，直接拉起本插件自有的 Server */
    fun startOwnServer()

    /** 停止本插件启动的 Server（他人实例不生效） */
    fun stopServer()

    /** 提交他人实例的接入凭据（`NEEDS_CREDENTIALS` 交互） */
    fun submitServerCredentials(username: String, password: String)
}
