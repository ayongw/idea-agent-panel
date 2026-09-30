package com.ayongw.idea.opencode.frontend.chatApp

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
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
import java.io.File
import javax.swing.JPanel

/**
 * 聊天面板装配：TopBar / Server 状态条 / ChatList / 输入区。
 *
 * 生命周期（TSD-30 §5.4）：实现 [Disposable]，由 Tool Window 工厂注册到
 * `toolWindow.disposable` 之下；面板级订阅与子组件（[ChatList]）都挂在本实例下，
 * 关闭工具窗即整体取消，避免 project 级 scope 叠加导致的状态串扰。
 */
class OpenCodeChatApp(
    private val viewModel: ChatViewModel,
    private val project: Project
) : JPanel(), Disposable {

    /** 面板级订阅 scope：随本面板销毁而取消（不挂 project 级） */
    private val panelScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val topBar: TopBar
    private val serverStatusStrip: ServerStatusStrip
    private val chatList: ChatList
    private val contextChipBar: ContextChipBar
    private val inputToolbar: InputToolbar
    private val promptInput: PromptInput

    private var allSessions: List<SessionStateDto> = emptyList()
    private var allSessionsPopup: JBPopup? = null

    /** 上次绑定的会话 id（用于底部操作区草稿的切走/切回判定） */
    private var lastBoundSessionId: String? = null

    init {
        setupAppearance()

        contextChipBar = ContextChipBar()
        topBar = TopBar(
            viewModel = viewModel,
            onShowAllSessions = { anchor -> showAllSessionsPopup(anchor) },
            onOpenSettings = { openSettings() }
        )
        serverStatusStrip = ServerStatusStrip(
            onRetry = { viewModel.retryServerStart() },
            onStartOwnInstance = { viewModel.startOwnServer() },
            onSubmitCredentials = { username, password -> viewModel.submitServerCredentials(username, password) },
            onOpenSettings = { openSettings(OpenCodeSettingsConfigurable.CONNECTION_TAB_INDEX) },
            onOpenCliDocs = { BrowserUtil.browse(CLI_DOCS_URL) }
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
        // ChatList 的定时器与协程 scope 挂到本面板生命周期下（TSD-30 §5.4）
        Disposer.register(this, chatList)
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
        add(
            JPanel(BorderLayout()).apply {
                add(serverStatusStrip, BorderLayout.NORTH)
                add(chatList, BorderLayout.CENTER)
            },
            BorderLayout.CENTER
        )
        add(promptInput, BorderLayout.SOUTH)

        subscribeToViewModelUpdates()
    }

    private fun setupAppearance() {
        layout = BorderLayout()
        background = ChatAppColors.Panel.background
    }

    /**
     * 当前工作区内的会话
     *
     * 服务端已按 `?directory=` 过滤，这里再做路径归一化兜底：去尾斜杠 + canonicalPath，
     * 兼容符号链接（如 `/var` 与 `/private/var`）与结尾斜杠差异。
     */
    private fun workspaceSessions(): List<SessionStateDto> {
        val basePath = project.basePath
        return allSessions.filter { session ->
            val directory = session.directory
            directory == null || basePath == null || normalizePath(directory) == normalizePath(basePath)
        }
    }

    private fun normalizePath(path: String): String =
        (runCatching { File(path).canonicalPath }.getOrNull() ?: path).trimEnd('/')

    /**
     * 「全部会话」弹窗：查看当前工作区内的所有会话
     */
    private fun showAllSessionsPopup(anchor: Component) {
        allSessionsPopup?.cancel()
        allSessionsPopup = null
        // 打开前刷新一次本工作区会话（启动后其他窗口/工具新建的会话也能看到）
        viewModel.loadSessions()

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
        val coroutineScope = panelScope

        coroutineScope.launch {
            viewModel.chatMessagesFlow.collect { messages ->
                ApplicationManager.getApplication().invokeLater {
                    chatList.setMessages(messages)
                }
            }
        }

        // 顶部：Server 运行时状态条（非就绪状态才显示）
        coroutineScope.launch {
            viewModel.serverStateFlow.collect { state ->
                ApplicationManager.getApplication().invokeLater {
                    serverStatusStrip.update(state)
                }
            }
        }

        // 底部操作区随会话切换：切走保存输入草稿，切回恢复目标会话草稿（会话级，非全局共享）
        coroutineScope.launch {
            viewModel.currentSessionId.collect { sessionId ->
                if (sessionId != lastBoundSessionId) {
                    val oldId = lastBoundSessionId
                    lastBoundSessionId = sessionId
                    if (oldId != null) viewModel.saveDraft(oldId, promptInput.currentText())
                    val draft = viewModel.loadDraft(sessionId)
                    viewModel.restoreDraft(sessionId, draft)
                    promptInput.setDraftText(draft)
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

    private companion object {
        /** CLI 缺失引导外链（仅官方站点，不指向可执行文件） */
        const val CLI_DOCS_URL = "https://opencode.ai/"
    }

    override fun dispose() {
        panelScope.cancel()
    }
}