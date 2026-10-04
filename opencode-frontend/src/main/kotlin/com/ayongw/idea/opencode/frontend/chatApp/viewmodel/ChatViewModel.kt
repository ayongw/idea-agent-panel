package com.ayongw.idea.opencode.frontend.chatApp.viewmodel

import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.MentionSupport
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.PendingPermissionDto
import com.ayongw.idea.opencode.shared.PermissionResponse
import com.ayongw.idea.opencode.shared.PromptContextDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 聊天面板 ViewModel（Phase 3 瘦核心）：消息/输入状态与发送编排，
 * 会话管理委托 [sessionController]，输入区编排委托 [composeController]，Server 管理委托 [serverController]。
 */
class ChatViewModel(
    private val coroutineScope: CoroutineScope,
    private val repository: ChatRepositoryApi,
    /** 工作区根目录，用于 `#` mention 的相对路径换算 */
    private val basePath: String? = null,
    /** 项目级 tab 持久化（IDE 重启后恢复打开的会话） */
    private val tabsState: OpenCodeSessionTabsState? = null
) : ChatViewModelApi {

    private val _chatMessagesFlow = MutableStateFlow(emptyList<ChatMessage>())
    override val chatMessagesFlow: StateFlow<List<ChatMessage>> = _chatMessagesFlow.asStateFlow()

    private val _promptInputState = MutableStateFlow<MessageInputState>(MessageInputState.Disabled)
    override val promptInputState: StateFlow<MessageInputState> = _promptInputState.asStateFlow()

    override val currentSessionId: StateFlow<String?> = repository.currentSessionId

    override val sessionRunningFlow: StateFlow<Boolean> = repository.sessionRunningFlow

    override val pendingPermissionFlow: StateFlow<PendingPermissionDto?> = repository.pendingPermissionFlow

    private val searchHandler: SearchChatMessagesHandler = SearchChatMessagesHandlerImpl(
        coroutineScope = coroutineScope,
        messagesFlow = repository.messagesFlow
    )

    private var currentSendMessageJob: Job? = null

    // ==================== 子控制器（compose → session，回调均在调用期执行） ====================

    private val composeController = ComposeController(coroutineScope, repository, basePath)

    private val sessionController = SessionController(
        coroutineScope = coroutineScope,
        repository = repository,
        tabsState = tabsState,
        afterSessionCreated = {
            // 把当前展示的模式/模型下发到新会话，避免「界面显示 A、实际按服务端默认 B 执行」
            composeController.selectedAgentId.value?.let { agentId ->
                runCatching { repository.switchAgent(agentId) }
            }
            composeController.selectedModel.value?.let { model ->
                runCatching { repository.switchModel(model.providerID, model.modelID) }
            }
            composeController.clearUsage()
            composeController.refreshUsage()
            composeController.refreshAgentsAndModels()
        },
        afterSessionActivated = { sessionId ->
            composeController.clearUsage()
            composeController.refreshUsage()
            composeController.refreshAgentsAndModels()
            composeController.applySessionSelection(sessionId)
        },
        onDraftRestored = { draft ->
            emitPromptInputState(
                if (draft.isNotBlank()) MessageInputState.Enabled(draft) else MessageInputState.Disabled
            )
        }
    )

    private val serverController = ServerController(coroutineScope, repository)

    override val openedSessionIds: StateFlow<List<String>> = sessionController.openedSessionIdsFlow

    init {
        repository.messagesFlow
            .onEach { messages -> _chatMessagesFlow.value = messages }
            .launchIn(coroutineScope)

        // 执行态：运行中切「停止」，结束后回到可发送并刷新用量
        repository.sessionRunningFlow.onEach { running ->
            if (running) {
                emitPromptInputState(MessageInputState.Sending(""))
            } else {
                emitPromptInputState(idlePromptInputState())
                composeController.refreshUsage()
            }
        }.launchIn(coroutineScope)
    }

    override fun onPromptInputChanged(input: String) {
        val current = _promptInputState.value
        _promptInputState.value = when {
            current is MessageInputState.Sending -> MessageInputState.Sending(input)
            input.isEmpty() -> MessageInputState.Disabled
            else -> MessageInputState.Enabled(input)
        }
        // 输入草稿按会话隔离（底部操作区随会话切换，切走保存、切回恢复）
        repository.currentSessionId.value?.let { sessionController.saveDraft(it, input) }
    }

    override fun onSendMessage() {
        currentSendMessageJob = coroutineScope.launch {
            try {
                val currentUserMessage = getCurrentInputTextIfNotEmpty() ?: return@launch
                emitPromptInputState(MessageInputState.Sending(""))

                // 输入框文本中的 mention（命令 / 技能 / 规则 / 文件）解析为结构化上下文
                val resolution = MentionSupport.resolve(
                    currentUserMessage,
                    composeController.mentionCandidatesFlow.value +
                        composeController.workspaceCandidatesFlow.value,
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

                // 发送成功后清空本会话草稿（输入框文本已随发送清空）
                repository.currentSessionId.value?.let { sessionController.clearDraft(it) }

                if (repository.sessionRunningFlow.value) {
                    // 事件流已开始：保持「停止」态，结束后由执行态订阅恢复
                    emitPromptInputState(MessageInputState.Sending(""))
                } else {
                    // 事件流不可用（如服务不可达走本地兜底）：立即恢复可发送
                    emitPromptInputState(idlePromptInputState())
                    composeController.refreshUsage()
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
        composeController.refreshUsage()
    }

    override fun replyPermission(permissionId: String, response: PermissionResponse) {
        coroutineScope.launch {
            runCatching { repository.replyPermission(permissionId, response) }
        }
    }

    override fun closeSessionTab(sessionId: String) = sessionController.closeSessionTab(sessionId)

    override fun searchChatMessagesHandler(): SearchChatMessagesHandler = searchHandler

    override val sessions: SessionApi = sessionController

    override val compose: ComposeApi = composeController

    override val server: ServerApi = serverController

    override fun dispose() {
        coroutineScope.cancel()
    }

    private fun idlePromptInputState(): MessageInputState =
        when (val input = getCurrentInputTextIfNotEmpty()) {
            null -> MessageInputState.Disabled
            else -> MessageInputState.Enabled(input)
        }

    private fun emitPromptInputState(state: MessageInputState) {
        _promptInputState.value = state
    }

    private fun getCurrentInputTextIfNotEmpty(): String? =
        _promptInputState.value.inputText.takeIf { it.isNotBlank() }
}
