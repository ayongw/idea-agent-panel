package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ButtonUtils
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.frontend.chatApp.viewmodel.ApprovalMode
import com.ayongw.idea.opencode.shared.AgentDto
import com.ayongw.idea.opencode.shared.ModelDto
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem

/**
 * 底部输入工具条：审核类型 / 模式（agent）/ 会话模型 选择
 */
class InputToolbar(
    private val onApprovalModeSelected: (ApprovalMode) -> Unit,
    private val onAgentSelected: (AgentDto) -> Unit,
    private val onModelSelected: (ModelDto) -> Unit,
    private val onBeforeMenuOpen: () -> Unit
) : JPanel() {

    private val approvalButton = createMenuButton()
    private val modeButton = createMenuButton()
    private val modelButton = createMenuButton()

    private var approvalMode: ApprovalMode = ApprovalMode.AUTO
    private var agents: List<AgentDto> = emptyList()
    private var models: List<ModelDto> = emptyList()
    private var selectedAgentId: String? = null
    private var selectedModel: ModelDto? = null

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false

        approvalButton.apply {
            addActionListener {
                onBeforeMenuOpen()
                showMenu(
                    anchor = this,
                    items = ApprovalMode.entries.map { it to message(it.labelKey) },
                    selected = approvalMode
                ) { mode ->
                    approvalMode = mode
                    updateLabels()
                    onApprovalModeSelected(mode)
                }
            }
        }

        modeButton.apply {
            addActionListener {
                onBeforeMenuOpen()
                showMenu(
                    anchor = this,
                    items = agents.map { it.id to it.name },
                    selected = selectedAgentId
                ) { agentId ->
                    selectedAgentId = agentId
                    updateLabels()
                    agents.firstOrNull { it.id == agentId }?.let(onAgentSelected)
                }
            }
        }

        modelButton.apply {
            addActionListener {
                onBeforeMenuOpen()
                showMenu(
                    anchor = this,
                    items = models.map { it to it.name },
                    selected = selectedModel
                ) { model ->
                    selectedModel = model
                    updateLabels()
                    onModelSelected(model)
                }
            }
        }

        add(approvalButton)
        add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.NORMAL)))
        add(modeButton)
        add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.NORMAL)))
        add(modelButton)

        updateLabels()
    }

    /**
     * 由外层订阅 ViewModel 状态后调用
     */
    fun update(
        approvalMode: ApprovalMode,
        agents: List<AgentDto>,
        selectedAgentId: String?,
        models: List<ModelDto>,
        selectedModel: ModelDto?
    ) {
        this.approvalMode = approvalMode
        this.agents = agents
        this.selectedAgentId = selectedAgentId
        this.models = models
        this.selectedModel = selectedModel
        updateLabels()
    }

    private fun createMenuButton() = JButton().apply {
        isFocusable = false
        isBorderPainted = false
        isContentAreaFilled = false
        font = JBFont.small()
        foreground = ChatAppColors.Text.disabled
        ButtonUtils.applyHoverEffect(this)
    }

    private fun updateLabels() {
        approvalButton.text = "${message(approvalMode.labelKey)} ▾"
        approvalButton.toolTipText = message("chat.input.approval")

        modeButton.text = "${agents.firstOrNull { it.id == selectedAgentId }?.name ?: message("chat.input.mode")} ▾"
        modeButton.toolTipText = message("chat.input.mode")

        modelButton.text = "${selectedModel?.name ?: message("chat.input.model")} ▾"
        modelButton.toolTipText = message("chat.input.model")

        revalidate()
        repaint()
    }

    private fun <T> showMenu(
        anchor: JComponent,
        items: List<Pair<T, String>>,
        selected: T?,
        onSelect: (T) -> Unit
    ) {
        val menu = JPopupMenu()
        if (items.isEmpty()) {
            menu.add(JMenuItem(message("chat.input.mode")).apply { isEnabled = false })
        }
        items.forEach { (value, label) ->
            menu.add(
                JRadioButtonMenuItem(label, value == selected).apply {
                    addActionListener { onSelect(value) }
                }
            )
        }
        // 工具条在底部，菜单向上弹出
        menu.show(anchor, 0, -menu.preferredSize.height)
    }

    private fun message(key: String): String = OpencodeFrontendBundle.message(key)
}