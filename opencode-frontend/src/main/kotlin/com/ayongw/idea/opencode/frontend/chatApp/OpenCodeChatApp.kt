package com.ayongw.idea.opencode.frontend.chatApp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import com.ayongw.idea.opencode.frontend.CoroutineScopeHolder
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.*
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.frontend.chatApp.viewmodel.ApprovalMode
import com.ayongw.idea.opencode.frontend.chatApp.viewmodel.ChatViewModel
import com.ayongw.idea.opencode.frontend.settings.OpenCodeSettingsConfigurable
import com.ayongw.idea.opencode.shared.AgentDto
import com.ayongw.idea.opencode.shared.ModelDto
import com.ayongw.idea.opencode.shared.SessionStateDto
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import javax.swing.JPanel

class OpenCodeChatApp(
    private val viewModel: ChatViewModel,
    private val project: Project
) : JPanel() {

    private val topBar: TopBar
    private val chatList: ChatList
    private val contextChipBar: ContextChipBar
    private val inputToolbar: InputToolbar
    private val promptInput: PromptInput

    private var allSessions: List<SessionStateDto> = emptyList()
    private var allSessionsPopup: JBPopup? = null

    init {
        setupAppearance()

        contextChipBar = ContextChipBar()
        topBar = TopBar(
            viewModel = viewModel,
            onShowAllSessions = { anchor -> showAllSessionsPopup(anchor) },
            onOpenSettings = { openSettings() }
        )
        inputToolbar = InputToolbar(
            onApprovalModeSelected = { mode -> viewModel.setApprovalMode(mode) },
            onAgentSelected = { agent -> viewModel.switchAgent(agent.id) },
            onModelSelected = { model -> viewModel.switchModel(model) },
            onBeforeMenuOpen = { viewModel.loadAgentsAndModels() },
            onBeforeModelMenuOpen = { viewModel.loadModelProviders() },
            onManageModels = { openSettings(OpenCodeSettingsConfigurable.MODELS_TAB_INDEX) }
        )
        chatList = ChatList(project)
        promptInput = PromptInput(
            onInputChanged = { text -> viewModel.onPromptInputChanged(text) },
            onSend = { _ -> viewModel.onSendMessage() },
            onStop = { _ -> viewModel.onAbortSendingMessage() },
            onPermissionDecide = { requestId, response -> viewModel.replyPermission(requestId, response) },
            contextChipBar = contextChipBar,
            inputToolbar = inputToolbar,
            basePath = project.basePath,
            onAddAttachments = { attachments -> viewModel.addAttachments(attachments) },
            onRemoveAttachment = { attachment -> viewModel.removeAttachment(attachment.path) },
            onMentionCandidatesNeeded = { viewModel.ensureMentionCandidates() },
            onSearchWorkspace = { query -> viewModel.searchWorkspace(query) },
            onBrowseDirectory = { path -> viewModel.browseWorkspaceDirectory(path) }
        )

        add(topBar, BorderLayout.NORTH)
        add(chatList, BorderLayout.CENTER)
        add(promptInput, BorderLayout.SOUTH)

        subscribeToViewModelUpdates()
    }

    private fun setupAppearance() {
        layout = BorderLayout()
        background = ChatAppColors.Panel.background
    }

    /**
     * 当前工作区内的会话（按 opencode session.location.directory 过滤）
     */
    private fun workspaceSessions(): List<SessionStateDto> {
        val basePath = project.basePath
        return allSessions.filter { it.directory == null || basePath == null || it.directory == basePath }
    }

    /**
     * 「全部会话」弹窗：查看当前工作区内的所有会话
     */
    private fun showAllSessionsPopup(anchor: Component) {
        allSessionsPopup?.cancel()
        allSessionsPopup = null

        val sessionList = SessionList(
            project = project,
            onSessionClick = { sessionId ->
                allSessionsPopup?.cancel()
                viewModel.switchSession(sessionId)
            },
            onNewSession = {
                allSessionsPopup?.cancel()
                viewModel.createSession(null)
            },
            onRenameSession = { sessionId, newTitle -> viewModel.renameSession(sessionId, newTitle) },
            onDeleteSession = { sessionId -> viewModel.deleteSession(sessionId) }
        )
        sessionList.updateSessions(workspaceSessions(), viewModel.currentSessionId.value)

        val content = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(
                JBUI.scale(ChatUIConstants.AllSessionsPopup.WIDTH),
                JBUI.scale(ChatUIConstants.AllSessionsPopup.HEIGHT)
            )
            add(sessionList, BorderLayout.CENTER)
        }

        allSessionsPopup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, null)
            .setTitle(OpencodeFrontendBundle.message("chat.topbar.all.sessions"))
            .setRequestFocus(true)
            .setResizable(false)
            .setMovable(false)
            .setCancelOnClickOutside(true)
            .createPopup()

        allSessionsPopup?.showUnderneathOf(anchor)
    }

    private fun openSettings(tabIndex: Int? = null) {
        if (tabIndex != null) OpenCodeSettingsConfigurable.selectTab(tabIndex)
        ShowSettingsUtil.getInstance().showSettingsDialog(project, OpenCodeSettingsConfigurable::class.java)
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
                    topBar.updateSearchState(searchState)
                    chatList.updateSearchHighlights(searchState)

                    val currentResultId = searchState.currentSelectedSearchResultId
                    if (currentResultId != null) {
                        chatList.scrollToMessage(currentResultId)
                    }
                }
            }
        }

        // 顶部：已打开会话 tab
        coroutineScope.launch {
            combine(
                viewModel.allSessionsFlow,
                viewModel.openedSessionIds,
                viewModel.currentSessionId
            ) { sessions, openedIds, currentSessionId ->
                Triple(sessions, openedIds, currentSessionId)
            }.collect { (sessions, openedIds, currentSessionId) ->
                allSessions = sessions
                ApplicationManager.getApplication().invokeLater {
                    topBar.sessionTabs.update(sessions, openedIds, currentSessionId)
                }
            }
        }

        // 底部：审核类型 / 模式 / 模型
        coroutineScope.launch {
            combine(
                viewModel.approvalMode,
                viewModel.agentsFlow,
                viewModel.selectedAgentId,
                viewModel.selectedModel
            ) { approvalMode, agents, selectedAgentId, selectedModel ->
                InputToolbarState(approvalMode, agents, selectedAgentId, selectedModel)
            }.collect { state ->
                ApplicationManager.getApplication().invokeLater {
                    inputToolbar.update(
                        approvalMode = state.approvalMode,
                        agents = state.agents,
                        selectedAgentId = state.selectedAgentId,
                        selectedModel = state.selectedModel
                    )
                }
            }
        }

        // 底部：会话用量与上下文占比
        coroutineScope.launch {
            viewModel.usageFlow.collect { usage ->
                ApplicationManager.getApplication().invokeLater {
                    promptInput.updateUsage(usage)
                }
            }
        }

        // 输入区：待决权限确认
        coroutineScope.launch {
            viewModel.pendingPermissionFlow.collect { permission ->
                ApplicationManager.getApplication().invokeLater {
                    promptInput.updatePendingPermission(permission)
                }
            }
        }

        // 输入区：会话附件（chips 右段）
        coroutineScope.launch {
            viewModel.contextFilesFlow.collect { attachments ->
                ApplicationManager.getApplication().invokeLater {
                    promptInput.updateSessionAttachments(attachments)
                }
            }
        }

        // 输入区：`/` 候选（命令 / 技能 / 规则）
        coroutineScope.launch {
            viewModel.mentionCandidatesFlow.collect { candidates ->
                ApplicationManager.getApplication().invokeLater {
                    promptInput.updateMentionCandidates(candidates)
                }
            }
        }

        // 输入区：`#` 候选（工作区文件 / 目录）
        coroutineScope.launch {
            viewModel.workspaceCandidatesFlow.collect { candidates ->
                ApplicationManager.getApplication().invokeLater {
                    promptInput.updateWorkspaceCandidates(candidates)
                }
            }
        }

        // 底部：按供应商分组的模型
        coroutineScope.launch {
            viewModel.modelProvidersFlow.collect { providers ->
                ApplicationManager.getApplication().invokeLater {
                    inputToolbar.updateProviders(providers)
                }
            }
        }
    }

    private data class InputToolbarState(
        val approvalMode: ApprovalMode,
        val agents: List<AgentDto>,
        val selectedAgentId: String?,
        val selectedModel: ModelDto?
    )
}