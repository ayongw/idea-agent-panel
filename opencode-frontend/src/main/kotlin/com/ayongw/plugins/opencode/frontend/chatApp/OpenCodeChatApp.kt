package com.ayongw.plugins.opencode.frontend.chatApp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import kotlinx.coroutines.*
import com.ayongw.plugins.opencode.frontend.CoroutineScopeHolder
import com.ayongw.plugins.opencode.frontend.chatApp.ui.*
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.plugins.opencode.frontend.chatApp.viewmodel.ChatViewModel
import java.awt.*
import javax.swing.*

class OpenCodeChatApp(
    private val viewModel: ChatViewModel,
    private val project: Project
) : JPanel() {

    private val toolbar: ChatToolbar
    private val sessionList: SessionList
    private val chatList: ChatList
    private val contextChipBar: ContextChipBar
    private val promptInput: PromptInput

    private val splitter = JBSplitter(false, 0.22f)

    init {
        setupAppearance()

        contextChipBar = ContextChipBar()
        toolbar = ChatToolbar(viewModel)
        sessionList = SessionList(
            project = project,
            onSessionClick = { sessionId -> viewModel.switchSession(sessionId) },
            onNewSession = { viewModel.createSession() },
            onRenameSession = { sessionId, newTitle -> viewModel.renameSession(sessionId, newTitle) },
            onDeleteSession = { sessionId -> viewModel.deleteSession(sessionId) }
        )
        chatList = ChatList(project)
        promptInput = PromptInput(
            onInputChanged = { text -> viewModel.onPromptInputChanged(text) },
            onSend = { _ -> viewModel.onSendMessage() },
            onStop = { _ -> viewModel.onAbortSendingMessage() },
            contextChipBar = contextChipBar
        )

        // 右侧面板：工具栏 + 消息列表 + 输入区
        val rightPanel = JPanel(BorderLayout()).apply {
            add(toolbar, BorderLayout.NORTH)
            add(chatList, BorderLayout.CENTER)
            add(promptInput, BorderLayout.SOUTH)
        }

        // 左侧面板：会话列表
        val leftPanel = JPanel(BorderLayout()).apply {
            add(sessionList, BorderLayout.CENTER)
        }

        splitter.firstComponent = leftPanel
        splitter.secondComponent = rightPanel
        splitter.setHonorComponentsMinimumSize(true)

        add(splitter, BorderLayout.CENTER)

        subscribeToViewModelUpdates()
    }

    private fun setupAppearance() {
        layout = BorderLayout()
        background = ChatAppColors.Panel.background
    }

    private fun subscribeToViewModelUpdates() {
        val coroutineScope = CoroutineScopeHolder.getInstance(project).createScope(OpenCodeChatApp::class.java.simpleName)

        coroutineScope.launch {
            viewModel.chatMessagesFlow.collect { messages ->
                ApplicationManager.getApplication().invokeLater {
                    chatList.setMessages(messages)
                }
            }
        }

        coroutineScope.launch {
            viewModel.promptInputState.collect { state ->
                promptInput.updateState(state)
            }
        }

        coroutineScope.launch {
            viewModel.searchChatMessagesHandler().searchStateFlow.collect { searchState ->
                ApplicationManager.getApplication().invokeLater {
                    toolbar.updateSearchState(searchState)
                    chatList.updateSearchHighlights(searchState)

                    val currentResultId = searchState.currentSelectedSearchResultId
                    if (currentResultId != null) {
                        chatList.scrollToMessage(currentResultId)
                    }
                }
            }
        }

        // 同步会话列表
        coroutineScope.launch {
            viewModel.allSessionsFlow.collect { sessions ->
                ApplicationManager.getApplication().invokeLater {
                    sessionList.updateSessions(sessions, viewModel.currentSessionId.value)
                }
            }
        }

        // 同步服务器连接状态
        coroutineScope.launch {
            viewModel.serverConnectedFlow.collect { connected ->
                // TODO: 显示连接状态指示器
            }
        }
    }
}