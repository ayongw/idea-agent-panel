package com.ayongw.idea.opencode.frontend.chatApp.viewmodel

import com.intellij.openapi.Disposable
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.SessionStateDto

interface ChatViewModelApi : Disposable {
    val chatMessagesFlow: StateFlow<List<ChatMessage>>
    val allSessionsFlow: StateFlow<List<SessionStateDto>>
    val serverConnectedFlow: StateFlow<Boolean>
    val currentSessionId: StateFlow<String?>

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
    }

    private val _currentSessionId = MutableStateFlow<String?>(null)

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

                emitPromptInputState(
                    when (val currentInputState = getCurrentInputTextIfNotEmpty()) {
                        null -> MessageInputState.Disabled
                        else -> MessageInputState.Enabled(currentInputState)
                    }
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e

                emitPromptInputState(MessageInputState.SendFailed(e.message ?: "Unknown error", e))
            }
        }
    }

    override fun onAbortSendingMessage() {
        currentSendMessageJob?.cancel()

        emitPromptInputState(
            when (val currentPromptInput = getCurrentInputTextIfNotEmpty()) {
                null -> MessageInputState.Disabled
                else -> MessageInputState.Enabled(currentPromptInput)
            }
        )
    }

    override fun createSession(initialTitle: String?) {
        coroutineScope.launch {
            repository.createSession(initialTitle)
        }
    }

    override fun switchSession(sessionId: String) {
        coroutineScope.launch {
            repository.switchSession(sessionId)
        }
    }

    override fun deleteSession(sessionId: String) {
        coroutineScope.launch {
            repository.deleteSession(sessionId)
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