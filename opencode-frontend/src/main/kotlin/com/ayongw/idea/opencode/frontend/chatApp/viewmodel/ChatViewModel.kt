package com.ayongw.idea.opencode.frontend.chatApp.viewmodel

import com.intellij.openapi.Disposable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.ayongw.idea.opencode.shared.AgentDto
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ModelDto
import com.ayongw.idea.opencode.shared.SessionStateDto
import com.ayongw.idea.opencode.shared.SessionUsageDto

interface ChatViewModelApi : Disposable {
    val chatMessagesFlow: StateFlow<List<ChatMessage>>
    val allSessionsFlow: StateFlow<List<SessionStateDto>>
    val serverConnectedFlow: StateFlow<Boolean>
    val currentSessionId: StateFlow<String?>

    /** 当前会话是否正在执行（事件流驱动，决定输入框显示「发送」还是「停止」） */
    val sessionRunningFlow: StateFlow<Boolean>

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
}

class ChatViewModel(
    private val coroutineScope: CoroutineScope,
    private val repository: ChatRepositoryApi
) : ChatViewModelApi {

    private val _chatMessagesFlow = MutableStateFlow(emptyList<ChatMessage>())

    override val chatMessagesFlow: StateFlow<List<ChatMessage>> = _chatMessagesFlow.asStateFlow()

    private val _promptInputState = MutableStateFlow<MessageInputState>(MessageInputState.Disabled)
    override val promptInputState: StateFlow<MessageInputState> = _promptInputState.asStateFlow()

    override val allSessionsFlow: StateFlow<List<SessionStateDto>> = repository.allSessionsFlow

    override val serverConnectedFlow: StateFlow<Boolean> = repository.serverConnectedFlow

    override val currentSessionId: StateFlow<String?> = repository.currentSessionId

    override val sessionRunningFlow: StateFlow<Boolean> = repository.sessionRunningFlow

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

                repository.sendMessage(currentUserMessage)

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
        }
    }

    override fun switchSession(sessionId: String) {
        coroutineScope.launch {
            repository.switchSession(sessionId)
            openTab(sessionId)
            _usageFlow.value = null
            refreshUsage()
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
        coroutineScope.launch {
            runCatching { repository.listAgents() }.onSuccess { agents ->
                _agentsFlow.value = agents
                if (_selectedAgentId.value == null) {
                    _selectedAgentId.value = agents.firstOrNull()?.id
                }
            }
            runCatching { repository.listModels() }.onSuccess { models ->
                _modelsFlow.value = models
                if (_selectedModel.value == null) {
                    _selectedModel.value = models.firstOrNull()
                }
            }
        }
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
}