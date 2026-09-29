package com.ayongw.idea.opencode.frontend.chatApp.viewmodel

import com.intellij.openapi.Disposable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.MentionSupport
import com.ayongw.idea.opencode.shared.AgentDto
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ContextFileDto
import com.ayongw.idea.opencode.shared.ContextKind
import com.ayongw.idea.opencode.shared.ModelDto
import com.ayongw.idea.opencode.shared.ModelProviderDto
import com.ayongw.idea.opencode.shared.PendingPermissionDto
import com.ayongw.idea.opencode.shared.PermissionResponse
import com.ayongw.idea.opencode.shared.PromptContextDto
import com.ayongw.idea.opencode.shared.SessionStateDto
import com.ayongw.idea.opencode.shared.SessionUsageDto
import java.time.LocalDateTime

interface ChatViewModelApi : Disposable {
    val chatMessagesFlow: StateFlow<List<ChatMessage>>
    val allSessionsFlow: StateFlow<List<SessionStateDto>>
    val serverConnectedFlow: StateFlow<Boolean>
    val currentSessionId: StateFlow<String?>

    /** 当前会话是否正在执行（事件流驱动，决定输入框显示「发送」还是「停止」） */
    val sessionRunningFlow: StateFlow<Boolean>

    /** 当前会话的待决权限请求（事件流驱动）；null 表示无需确认 */
    val pendingPermissionFlow: StateFlow<PendingPermissionDto?>

    /** 回复权限请求（允许一次 / 始终允许 / 拒绝） */
    fun replyPermission(permissionId: String, response: PermissionResponse)

    fun onPromptInputChanged(input: String)

    fun onSendMessage()

    fun onAbortSendingMessage()

    fun searchChatMessagesHandler(): SearchChatMessagesHandler

    val promptInputState: StateFlow<MessageInputState>

    // 会话管理
    fun createSession(initialTitle: String? = null)

    fun switchSession(sessionId: String)

    fun deleteSession(sessionId: String)

    fun renameSession(sessionId: String, newTitle: String)

    /** 顶部已打开会话（tab）顺序 */
    val openedSessionIds: StateFlow<List<String>>

    /** 关闭会话 tab（仅关闭视图，不删除会话） */
    fun closeSessionTab(sessionId: String)

    /** 可用 Agent（模式）列表 */
    val agentsFlow: StateFlow<List<AgentDto>>

    /** 可用模型列表 */
    val modelsFlow: StateFlow<List<ModelDto>>

    /** 当前选中的 Agent（模式） */
    val selectedAgentId: StateFlow<String?>

    /** 当前选中的模型 */
    val selectedModel: StateFlow<ModelDto?>

    /** 审核类型（本地状态） */
    val approvalMode: StateFlow<ApprovalMode>

    /** 拉取 Agent/模型列表（打开底部下拉时调用） */
    fun loadAgentsAndModels()

    fun switchAgent(agentId: String)

    fun switchModel(model: ModelDto)

    fun setApprovalMode(mode: ApprovalMode)

    /** 当前会话用量（累计 token / 上下文占比）；无数据显示时为 null */
    val usageFlow: StateFlow<SessionUsageDto?>

    /** 拉取当前会话用量（切换会话 / 发送 / 中止 / 切换模型后调用） */
    fun refreshUsage()

    // ==================== 输入区上下文 ====================

    /** 当前会话的会话附件（＋ 按钮加入，chips 右段） */
    val contextFilesFlow: StateFlow<List<ContextFileDto>>

    /** 添加会话附件（去重由后端保证） */
    fun addAttachments(attachments: List<ContextFileDto>)

    /** 移除会话附件（按绝对路径） */
    fun removeAttachment(path: String)

    /** `/` 候选（命令 / 技能 / 规则）；首次调用时加载并缓存 */
    val mentionCandidatesFlow: StateFlow<List<MentionSupport.Candidate>>

    /** 加载（或刷新）`/` 候选 */
    fun ensureMentionCandidates(force: Boolean = false)

    /** `#` 候选（工作区检索结果；空 query 时列工作区根目录） */
    val workspaceCandidatesFlow: StateFlow<List<MentionSupport.Candidate>>

    /** 检索工作区条目（文件与目录） */
    fun searchWorkspace(query: String)

    /** 浏览工作区目录（进入目录时调用；path 为相对路径） */
    fun browseWorkspaceDirectory(path: String?)

    /** 按供应商分组的模型（模型选择弹窗） */
    val modelProvidersFlow: StateFlow<List<ModelProviderDto>>

    /** 刷新按供应商分组的模型 */
    fun loadModelProviders()
}

class ChatViewModel(
    private val coroutineScope: CoroutineScope,
    private val repository: ChatRepositoryApi,
    /** 工作区根目录，用于 `#` mention 的相对路径换算 */
    private val basePath: String? = null
) : ChatViewModelApi {

    private val _chatMessagesFlow = MutableStateFlow(emptyList<ChatMessage>())

    override val chatMessagesFlow: StateFlow<List<ChatMessage>> = _chatMessagesFlow.asStateFlow()

    private val _promptInputState = MutableStateFlow<MessageInputState>(MessageInputState.Disabled)
    override val promptInputState: StateFlow<MessageInputState> = _promptInputState.asStateFlow()

    override val allSessionsFlow: StateFlow<List<SessionStateDto>> = repository.allSessionsFlow

    override val serverConnectedFlow: StateFlow<Boolean> = repository.serverConnectedFlow

    override val currentSessionId: StateFlow<String?> = repository.currentSessionId

    override val sessionRunningFlow: StateFlow<Boolean> = repository.sessionRunningFlow

    override val pendingPermissionFlow: StateFlow<PendingPermissionDto?> = repository.pendingPermissionFlow

    private val searchChatMessagesHandler: SearchChatMessagesHandler = SearchChatMessagesHandlerImpl(
        coroutineScope = coroutineScope,
        messagesFlow = repository.messagesFlow
    )

    private var currentSendMessageJob: Job? = null

    init {
        repository
            .messagesFlow
            .onEach { messages -> _chatMessagesFlow.value = messages }
            .launchIn(coroutineScope)

        // 同步当前会话 ID
        coroutineScope.launch {
            repository.currentSessionId.onEach { sessionId ->
                _currentSessionId.value = sessionId
            }.launchIn(coroutineScope)
        }

        // 执行态：运行中切「停止」，结束后回到可发送并刷新用量
        coroutineScope.launch {
            repository.sessionRunningFlow.collect { running ->
                if (running) {
                    emitPromptInputState(MessageInputState.Sending(""))
                } else {
                    emitPromptInputState(idlePromptInputState())
                    refreshUsage()
                }
            }
        }
    }

    private fun idlePromptInputState(): MessageInputState =
        when (val input = getCurrentInputTextIfNotEmpty()) {
            null -> MessageInputState.Disabled
            else -> MessageInputState.Enabled(input)
        }

    private val _currentSessionId = MutableStateFlow<String?>(null)

    private val _openedSessionIds = MutableStateFlow(emptyList<String>())
    override val openedSessionIds: StateFlow<List<String>> = _openedSessionIds.asStateFlow()

    private val _agentsFlow = MutableStateFlow(emptyList<AgentDto>())
    override val agentsFlow: StateFlow<List<AgentDto>> = _agentsFlow.asStateFlow()

    private val _modelsFlow = MutableStateFlow(emptyList<ModelDto>())
    override val modelsFlow: StateFlow<List<ModelDto>> = _modelsFlow.asStateFlow()

    private val _selectedAgentId = MutableStateFlow<String?>(null)
    override val selectedAgentId: StateFlow<String?> = _selectedAgentId.asStateFlow()

    private val _selectedModel = MutableStateFlow<ModelDto?>(null)
    override val selectedModel: StateFlow<ModelDto?> = _selectedModel.asStateFlow()

    private val _approvalMode = MutableStateFlow(ApprovalMode.AUTO)
    override val approvalMode: StateFlow<ApprovalMode> = _approvalMode.asStateFlow()

    private val _usageFlow = MutableStateFlow<SessionUsageDto?>(null)
    override val usageFlow: StateFlow<SessionUsageDto?> = _usageFlow.asStateFlow()

    override fun onPromptInputChanged(input: String) {
        val currentPromptInputState = _promptInputState.value
        _promptInputState.value = when {
            currentPromptInputState is MessageInputState.Sending -> MessageInputState.Sending(input)
            input.isEmpty() -> MessageInputState.Disabled
            else -> MessageInputState.Enabled(input)
        }
    }

    override fun onSendMessage() {
        currentSendMessageJob = coroutineScope.launch {
            try {
                val currentUserMessage = getCurrentInputTextIfNotEmpty() ?: return@launch
                emitPromptInputState(MessageInputState.Sending(""))

                // 输入框文本中的 mention（命令 / 技能 / 规则 / 文件）解析为结构化上下文
                val resolution = MentionSupport.resolve(
                    currentUserMessage,
                    _mentionCandidatesFlow.value + _workspaceCandidatesFlow.value,
                    basePath
                )
                val textToSend = resolution.text.ifBlank { currentUserMessage }

                repository.sendMessageWithContext(
                    textToSend,
                    PromptContextDto(
                        attachments = resolution.attachments,
                        commandName = resolution.commandName
                    )
                )

                if (repository.sessionRunningFlow.value) {
                    // 事件流已开始：保持「停止」态，结束后由执行态订阅恢复
                    emitPromptInputState(MessageInputState.Sending(""))
                } else {
                    // 事件流不可用（如服务不可达走本地兜底）：立即恢复可发送
                    emitPromptInputState(idlePromptInputState())
                    refreshUsage()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                emitPromptInputState(MessageInputState.SendFailed(e.message ?: "Unknown error", e))
            }
        }
    }

    override fun onAbortSendingMessage() {
        currentSendMessageJob?.cancel()
        // 真正中断服务端执行（否则「停止」只停了本地等待）
        coroutineScope.launch { runCatching { repository.abortExecution() } }
        emitPromptInputState(idlePromptInputState())
        refreshUsage()
    }

    override fun createSession(initialTitle: String?) {
        coroutineScope.launch {
            val sessionId = repository.createSession(initialTitle)
            openTab(sessionId)
            _usageFlow.value = null
            refreshUsage()
            refreshAgentsAndModels()
        }
    }

    override fun switchSession(sessionId: String) {
        coroutineScope.launch {
            repository.switchSession(sessionId)
            openTab(sessionId)
            _usageFlow.value = null
            refreshUsage()
            refreshAgentsAndModels()
            applySessionSelection(sessionId)
        }
    }

    override fun refreshUsage() {
        coroutineScope.launch {
            _usageFlow.value = runCatching { repository.getSessionUsage() }.getOrNull()
        }
    }

    override fun closeSessionTab(sessionId: String) {
        _openedSessionIds.value = _openedSessionIds.value - sessionId
    }

    private fun openTab(sessionId: String) {
        val opened = _openedSessionIds.value
        if (opened.contains(sessionId)) return
        _openedSessionIds.value = opened + sessionId
    }

    override fun loadAgentsAndModels() {
        coroutineScope.launch { refreshAgentsAndModels() }
    }

    /** 拉取 Agent / 模型列表并补齐默认选中（顺序：build → plan → 首项） */
    private suspend fun refreshAgentsAndModels() {
        runCatching { repository.listAgents() }.onSuccess { agents ->
            _agentsFlow.value = agents
            if (_selectedAgentId.value == null || agents.none { it.id == _selectedAgentId.value }) {
                _selectedAgentId.value = defaultAgentId(agents)
            }
        }
        runCatching { repository.listModels() }.onSuccess { models ->
            _modelsFlow.value = models
            if (_selectedModel.value == null) {
                _selectedModel.value = models.firstOrNull()
            }
        }
    }

    /** 默认模式：build 优先，其次 plan，最后取首项 */
    private fun defaultAgentId(agents: List<AgentDto>): String? =
        agents.firstOrNull { it.id == BUILD_AGENT_ID }?.id
            ?: agents.firstOrNull { it.id == PLAN_AGENT_ID }?.id
            ?: agents.firstOrNull()?.id

    /** 切换会话后按服务端回读该会话的模式与模型（读不到则保留当前值） */
    private suspend fun applySessionSelection(sessionId: String) {
        val selection = runCatching { repository.getSessionSelection(sessionId) }.getOrNull() ?: return
        selection.agentId?.takeIf { it.isNotBlank() }?.let { agentId ->
            if (_agentsFlow.value.any { it.id == agentId }) _selectedAgentId.value = agentId
        }
        val modelId = selection.modelId?.takeIf { it.isNotBlank() } ?: return
        _modelsFlow.value.firstOrNull {
            it.modelID == modelId && (selection.providerId.isNullOrBlank() || it.providerID == selection.providerId)
        }?.let { _selectedModel.value = it }
    }

    override fun switchAgent(agentId: String) {
        coroutineScope.launch {
            runCatching { repository.switchAgent(agentId) }
                .onSuccess { _selectedAgentId.value = agentId }
        }
    }

    override fun switchModel(model: ModelDto) {
        coroutineScope.launch {
            runCatching { repository.switchModel(model.providerID, model.modelID) }
                .onSuccess {
                    _selectedModel.value = model
                    refreshUsage()
                }
        }
    }

    override fun setApprovalMode(mode: ApprovalMode) {
        _approvalMode.value = mode
    }

    // ==================== 输入区上下文与候选 ====================

    override val contextFilesFlow: StateFlow<List<ContextFileDto>> = repository.contextFilesFlow

    private val _mentionCandidatesFlow = MutableStateFlow(emptyList<MentionSupport.Candidate>())
    override val mentionCandidatesFlow: StateFlow<List<MentionSupport.Candidate>> = _mentionCandidatesFlow.asStateFlow()

    private val _workspaceCandidatesFlow = MutableStateFlow(emptyList<MentionSupport.Candidate>())
    override val workspaceCandidatesFlow: StateFlow<List<MentionSupport.Candidate>> = _workspaceCandidatesFlow.asStateFlow()

    private val _modelProvidersFlow = MutableStateFlow(emptyList<ModelProviderDto>())
    override val modelProvidersFlow: StateFlow<List<ModelProviderDto>> = _modelProvidersFlow.asStateFlow()

    private var mentionCandidatesLoaded = false

    /** `#` 检索去抖任务（连续输入只保留最后一次） */
    private var workspaceSearchJob: Job? = null

    override fun addAttachments(attachments: List<ContextFileDto>) {
        if (attachments.isEmpty()) return
        coroutineScope.launch {
            attachments.forEach { runCatching { repository.addContextFile(it) } }
        }
    }

    override fun removeAttachment(path: String) {
        coroutineScope.launch { runCatching { repository.removeContextFile(path) } }
    }

    override fun ensureMentionCandidates(force: Boolean) {
        if (mentionCandidatesLoaded && !force) return
        mentionCandidatesLoaded = true
        coroutineScope.launch {
            val commands = runCatching { repository.listCommands() }.getOrDefault(emptyList())
            val skills = runCatching { repository.listSkills() }.getOrDefault(emptyList())
            val references = runCatching { repository.listReferences() }.getOrDefault(emptyList())
            _mentionCandidatesFlow.value =
                commands.map { commandCandidate(it.name, it.description) } +
                    skills.map { skillCandidate(it.id, it.name, it.path, it.description) } +
                    references.map { ruleCandidate(it.name, it.path, it.description) }
        }
    }

    override fun searchWorkspace(query: String) {
        // 连续输入时只保留最后一次检索（去抖），避免每次按键都打服务端
        workspaceSearchJob?.cancel()
        workspaceSearchJob = coroutineScope.launch {
            if (query.isNotBlank()) delay(WORKSPACE_SEARCH_DEBOUNCE_MS)
            val entries = if (query.isBlank()) {
                runCatching { repository.listWorkspaceDirectory(null) }.getOrDefault(emptyList())
            } else {
                runCatching { repository.findWorkspaceEntries(query) }.getOrDefault(emptyList())
            }
            _workspaceCandidatesFlow.value = entries.map {
                workspaceCandidate(it.path, it.name, it.isDirectory)
            }
        }
    }

    override fun browseWorkspaceDirectory(path: String?) {
        coroutineScope.launch {
            val entries = runCatching { repository.listWorkspaceDirectory(path) }.getOrDefault(emptyList())
            _workspaceCandidatesFlow.value = entries.map {
                workspaceCandidate(it.path, it.name, it.isDirectory)
            }
        }
    }

    override fun loadModelProviders() {
        coroutineScope.launch {
            _modelProvidersFlow.value =
                runCatching { repository.listModelProviders() }.getOrDefault(emptyList())
        }
    }

    private fun commandCandidate(name: String, description: String?) = MentionSupport.Candidate(
        symbol = MentionSupport.COMMAND_SYMBOL,
        token = name,
        group = message("chat.mention.group.command"),
        label = "/$name",
        detail = description,
        command = true
    )

    private fun skillCandidate(id: String, name: String?, path: String?, description: String?): MentionSupport.Candidate {
        val display = name?.takeIf { it.isNotBlank() } ?: id
        return MentionSupport.Candidate(
            symbol = MentionSupport.COMMAND_SYMBOL,
            token = display,
            group = message("chat.mention.group.skill"),
            label = display,
            detail = description,
            attachment = ContextFileDto(
                path = path.orEmpty(),
                name = display,
                summary = description.orEmpty(),
                addedAt = LocalDateTime.now(),
                isExplicit = true,
                kind = ContextKind.SKILL,
                skillId = id
            )
        )
    }

    private fun ruleCandidate(name: String, path: String, description: String?) = MentionSupport.Candidate(
        symbol = MentionSupport.PATH_SYMBOL,
        token = name,
        group = message("chat.mention.group.rule"),
        label = name,
        detail = path,
        attachment = ContextFileDto(
            path = path,
            name = name,
            summary = description.orEmpty(),
            addedAt = LocalDateTime.now(),
            isExplicit = true,
            kind = ContextKind.RULE
        )
    )

    private fun workspaceCandidate(path: String, name: String, isDirectory: Boolean) = MentionSupport.Candidate(
        symbol = MentionSupport.PATH_SYMBOL,
        token = if (isDirectory) "$path/" else path,
        group = message("chat.mention.group.file"),
        label = name,
        detail = path,
        attachment = ContextFileDto(
            path = absolutePath(path),
            name = name,
            summary = if (isDirectory) MentionSupport.DIRECTORY_SUMMARY else "",
            addedAt = LocalDateTime.now(),
            isExplicit = true,
            kind = if (isDirectory) ContextKind.DIRECTORY else ContextKind.FILE
        )
    )

    /** 工作区相对路径 → 绝对路径 */
    private fun absolutePath(relative: String): String = when {
        relative.startsWith("/") -> relative
        basePath.isNullOrBlank() -> relative
        else -> "$basePath/${relative.trimStart('/')}"
    }

    private fun message(key: String): String = OpencodeFrontendBundle.message(key)

    override fun replyPermission(permissionId: String, response: PermissionResponse) {
        coroutineScope.launch {
            runCatching { repository.replyPermission(permissionId, response) }
        }
    }

    override fun deleteSession(sessionId: String) {
        coroutineScope.launch {
            repository.deleteSession(sessionId)
            if (_currentSessionId.value == null) _usageFlow.value = null
        }
    }

    override fun renameSession(sessionId: String, newTitle: String) {
        coroutineScope.launch {
            repository.renameSession(sessionId, newTitle)
        }
    }

    override fun searchChatMessagesHandler(): SearchChatMessagesHandler = searchChatMessagesHandler

    override fun dispose() {
        coroutineScope.cancel()
    }

    private fun emitPromptInputState(state: MessageInputState) {
        _promptInputState.value = state
    }

    private fun getCurrentInputTextIfNotEmpty(): String? = _promptInputState.value.inputText.takeIf { it.isNotBlank() }

    private companion object {
        /** 默认模式与其备选（与后端 listAgents 的排序口径一致） */
        const val BUILD_AGENT_ID = "build"
        const val PLAN_AGENT_ID = "plan"

        /** `#` 检索去抖窗口（毫秒） */
        const val WORKSPACE_SEARCH_DEBOUNCE_MS = 200L
    }
}