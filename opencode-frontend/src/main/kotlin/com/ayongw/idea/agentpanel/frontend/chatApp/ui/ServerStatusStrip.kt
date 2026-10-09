package com.ayongw.idea.agentpanel.frontend.chatApp.ui

import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.agentpanel.frontend.settings.AgentSettingsState
import com.ayongw.idea.agentpanel.shared.ServerStateDto
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JProgressBar

/**
 * Server 运行时状态条（TSD-31 §5.1）
 *
 * 位于 `TopBar` 与 `ChatList` 之间，**只读** [ServerStateDto]，**仅在非就绪状态显示**：
 * - `DISCOVERING` / `STARTING` / `STOPPING`：进度指示 + 文案（含端口）
 * - `NEEDS_CREDENTIALS`：他人实例，就地输入密钥「接入」，或「改用插件自启实例」/「打开设置」改地址
 * - `FAILED`：失败分类文案 + 重试 / 打开设置（CLI 缺失时加「安装文档」）+ 可展开输出尾巴
 * - `IDLE` / `READY` / `REUSING` / `STOPPED`：整条隐藏，不占用纵向空间
 *
 * 组件只做渲染与回调；状态更新与回调触发均由装配方（[AgentChatApp]）在 EDT 上完成。
 */
class ServerStatusStrip(
    private val onRetry: () -> Unit,
    private val onStartOwnInstance: () -> Unit,
    private val onSubmitCredentials: (username: String, password: String) -> Unit,
    private val onOpenSettings: () -> Unit,
    private val onOpenCliDocs: () -> Unit,
) : JPanel() {

    private val messageLabel = JBLabel()

    private val progressBar = JProgressBar().apply {
        isIndeterminate = true
        isBorderPainted = false
        isVisible = false
        preferredSize = Dimension(JBUI.scale(90), JBUI.scale(4))
    }

    private val detailLabel = JBLabel().apply {
        font = JBUI.Fonts.smallFont()
        foreground = ChatAppColors.Server.secondaryText
        isVisible = false
    }

    private val retryButton = JButton(AgentPanelBundle.message("server.strip.action.retry")).apply {
        addActionListener { onRetry() }
    }

    private val startOwnButton = JButton(AgentPanelBundle.message("server.strip.action.start.own")).apply {
        addActionListener { onStartOwnInstance() }
    }

    private val cliDocsButton = JButton(AgentPanelBundle.message("server.strip.action.cli.docs")).apply {
        addActionListener { onOpenCliDocs() }
    }

    private val settingsButton = JButton(AgentPanelBundle.message("server.strip.action.settings")).apply {
        addActionListener { onOpenSettings() }
    }

    /**
     * 操作按钮行（独立于文案行，右对齐）。
     *
     * 不能与文案同行用 `BorderLayout`（WEST 文案 + EAST 按钮）拼：宽度不足时 BorderLayout
     * 给 WEST 完整 preferred 宽度、EAST 又定位在 `width - eastWidth`，两者会**直接重叠**
     * （实测 830px 宽的工具窗即重叠）。拆行后任意宽度都不重叠；全部按钮不可见时整行折叠。
     */
    private val actionRow = JPanel(FlowLayout(FlowLayout.RIGHT, ChatUIConstants.Spacing.SMALL, 0)).apply {
        isOpaque = false
        isVisible = false
        add(retryButton)
        add(startOwnButton)
        add(cliDocsButton)
        add(settingsButton)
    }

    private val usernameField = JBTextField(AgentSettingsState.DEFAULT_USERNAME).apply { columns = 10 }

    private val passwordField = JBPasswordField().apply { columns = 14 }

    private val connectButton = JButton(AgentPanelBundle.message("server.strip.action.connect")).apply {
        addActionListener {
            onSubmitCredentials(usernameField.text.trim(), String(passwordField.password).trim())
        }
    }

    private val credentialsRow = JPanel(FlowLayout(FlowLayout.LEFT, ChatUIConstants.Spacing.SMALL, 0)).apply {
        isOpaque = false
        isVisible = false
        add(JBLabel(AgentPanelBundle.message("server.strip.credentials.username")))
        add(usernameField)
        add(JBLabel(AgentPanelBundle.message("server.strip.credentials.password")))
        add(passwordField)
        add(connectButton)
    }

    private val outputToggleButton = JButton().apply {
        isVisible = false
        addActionListener { setOutputExpanded(!outputExpanded) }
    }

    private val outputArea = JBTextArea().apply {
        isEditable = false
        lineWrap = false
        font = Font(Font.MONOSPACED, Font.PLAIN, font.size)
        foreground = ChatAppColors.Server.secondaryText
        border = BorderFactory.createEmptyBorder()
    }

    private val outputScroll = JBScrollPane(outputArea).apply {
        isVisible = false
        preferredSize = Dimension(JBUI.scale(OUTPUT_WIDTH), JBUI.scale(OUTPUT_HEIGHT))
    }

    private val outputRow = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(outputToggleButton, BorderLayout.NORTH)
        add(outputScroll, BorderLayout.CENTER)
    }

    private var outputExpanded = false

    /** 上次渲染的状态名，用于在状态切换时收起输出、清掉界面上残留的密钥 */
    private var renderedState: String? = null

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(JBUI.scale(1), 0, JBUI.scale(1), 0, ChatAppColors.Server.border),
            BorderFactory.createEmptyBorder(JBUI.scale(4), JBUI.scale(8), JBUI.scale(4), JBUI.scale(8)),
        )
        add(buildMessageRow())
        add(actionRow)
        add(detailLabel)
        add(credentialsRow)
        add(outputRow)
        isVisible = false
    }

    /** 渲染一份状态快照（幂等；调用方需保证在 EDT 上执行） */
    fun update(state: ServerStateDto) {
        if (renderedState != state.state) {
            renderedState = state.state
            passwordField.text = ""
            outputExpanded = false
        }
        when (state.state) {
            ServerStateDto.STATE_DISCOVERING -> renderProgress(
                AgentPanelBundle.message("server.strip.discovering"),
                ChatAppColors.Server.progressBackground,
                ChatAppColors.Server.progressText,
            )

            ServerStateDto.STATE_STARTING -> renderProgress(
                state.port?.let { AgentPanelBundle.message("server.strip.starting", it) }
                    ?: AgentPanelBundle.message("server.strip.discovering"),
                ChatAppColors.Server.progressBackground,
                ChatAppColors.Server.progressText,
            )

            ServerStateDto.STATE_STOPPING -> renderProgress(
                AgentPanelBundle.message("server.strip.stopping"),
                ChatAppColors.Server.progressBackground,
                ChatAppColors.Server.progressText,
            )

            ServerStateDto.STATE_NEEDS_CREDENTIALS -> renderNeedsCredentials(state)

            ServerStateDto.STATE_FAILED -> renderFailed(state)

            else -> hideStrip()
        }
    }

    private fun renderProgress(message: String, fill: Color, textColor: Color) {
        isVisible = true
        background = fill
        messageLabel.text = message
        messageLabel.foreground = textColor
        progressBar.isVisible = true
        detailLabel.isVisible = false
        credentialsRow.isVisible = false
        showButtons(retry = false, startOwn = false, docs = false, settings = false)
        outputToggleButton.isVisible = false
        outputScroll.isVisible = false
        revalidate()
        repaint()
    }

    private fun renderNeedsCredentials(state: ServerStateDto) {
        isVisible = true
        background = ChatAppColors.Server.progressBackground
        messageLabel.text = AgentPanelBundle.message("server.strip.needs.credentials")
        messageLabel.foreground = ChatAppColors.Server.progressText
        progressBar.isVisible = false
        applyDetail(state.detail)
        credentialsRow.isVisible = true
        showButtons(retry = false, startOwn = true, docs = false, settings = true)
        outputToggleButton.isVisible = false
        outputScroll.isVisible = false
        revalidate()
        repaint()
    }

    private fun renderFailed(state: ServerStateDto) {
        isVisible = true
        background = ChatAppColors.Server.errorBackground
        messageLabel.text = failureText(state.failure)
        messageLabel.foreground = ChatAppColors.Server.errorText
        progressBar.isVisible = false
        applyDetail(state.detail)
        credentialsRow.isVisible = false
        showButtons(
            retry = true,
            startOwn = false,
            docs = state.failure == ServerStateDto.FAILURE_CLI_NOT_FOUND,
            settings = true,
        )
        val hasOutput = state.outputTail.isNotEmpty()
        outputArea.text = state.outputTail.joinToString("\n")
        outputArea.caretPosition = 0
        outputToggleButton.isVisible = hasOutput
        outputToggleButton.text = outputToggleText()
        outputScroll.isVisible = hasOutput && outputExpanded
        revalidate()
        repaint()
    }

    private fun hideStrip() {
        isVisible = false
        progressBar.isVisible = false
        credentialsRow.isVisible = false
        detailLabel.isVisible = false
        outputToggleButton.isVisible = false
        outputScroll.isVisible = false
        revalidate()
        repaint()
    }

    private fun applyDetail(detail: String?) {
        detailLabel.text = detail.orEmpty()
        detailLabel.isVisible = !detail.isNullOrBlank()
    }

    private fun setOutputExpanded(expanded: Boolean) {
        outputExpanded = expanded
        outputToggleButton.text = outputToggleText()
        outputScroll.isVisible = expanded && outputArea.text.isNotEmpty()
        revalidate()
        repaint()
    }

    private fun outputToggleText(): String = AgentPanelBundle.message(
        if (outputExpanded) "server.strip.action.hide.output" else "server.strip.action.show.output"
    )

    private fun showButtons(retry: Boolean, startOwn: Boolean, docs: Boolean, settings: Boolean) {
        retryButton.isVisible = retry
        startOwnButton.isVisible = startOwn
        cliDocsButton.isVisible = docs
        settingsButton.isVisible = settings
        // 无按钮时整行折叠，不占纵向空间
        actionRow.isVisible = retry || startOwn || docs || settings
    }

    /** 失败分类 → 文案 key；分类由后端枚举下发，未知分类回落到通用文案 */
    private fun failureText(failure: String?): String = AgentPanelBundle.message(
        when (failure) {
            ServerStateDto.FAILURE_CLI_NOT_FOUND -> "server.failure.cli.not.found"
            ServerStateDto.FAILURE_PORT_IN_USE -> "server.failure.port.in.use"
            ServerStateDto.FAILURE_AUTH_FAILED -> "server.failure.auth.failed"
            ServerStateDto.FAILURE_READY_TIMEOUT -> "server.failure.ready.timeout"
            ServerStateDto.FAILURE_PROCESS_EXITED -> "server.failure.process.exited"
            ServerStateDto.FAILURE_UNREACHABLE -> "server.failure.unreachable"
            else -> "server.failure.unknown"
        }
    )

    private fun buildMessageRow(): JPanel = JPanel(
        FlowLayout(FlowLayout.LEFT, ChatUIConstants.Spacing.SMALL, 0),
    ).apply {
        isOpaque = false
        add(messageLabel)
        add(progressBar)
    }

    private companion object {
        /** 输出尾巴显示区宽度上限（逻辑像素） */
        const val OUTPUT_WIDTH = 520

        /** 输出尾巴显示区高度（逻辑像素） */
        const val OUTPUT_HEIGHT = 120
    }
}