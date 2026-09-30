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
import com.ayongw.idea.opencode.shared.ModelProviderDto
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JComponent
import javax.swing.JMenuItem
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem

/**
 * 底部输入工具条：审核类型 / 模式（agent）/ 会话模型 选择
 *
 * 模型选择为组件式弹窗（搜索 + 按供应商分组 + 免费标签 + 管理入口），见 [ModelPickerPopup]。
 */
class InputToolbar(
    private val onApprovalModeSelected: (ApprovalMode) -> Unit,
    private val onAgentSelected: (AgentDto) -> Unit,
    private val onModelSelected: (ModelDto) -> Unit,
    private val onBeforeMenuOpen: () -> Unit,
    /** 模型弹窗打开前刷新按供应商分组的模型 */
    private val onBeforeModelMenuOpen: () -> Unit = {},
    /** 模型弹窗「管理模型」入口 */
    private val onManageModels: () -> Unit = {}
) : JPanel() {

    private val approvalButton = createMenuButton()
    private val modeButton = createMenuButton()
    private val modelButton = createMenuButton()

    private val modelPicker = ModelPickerPopup(
        onSelect = { model -> selectModel(model) },
        onManage = { onManageModels() }
    )

    private var approvalMode: ApprovalMode = ApprovalMode.AUTO
    private var agents: List<AgentDto> = emptyList()
    private var providers: List<ModelProviderDto> = emptyList()
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
                onBeforeModelMenuOpen()
                modelPicker.show(this, providers, selectedModel)
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
        selectedModel: ModelDto?
    ) {
        this.approvalMode = approvalMode
        this.agents = agents
        this.selectedAgentId = selectedAgentId
        this.selectedModel = selectedModel
        updateLabels()
    }

    private fun createMenuButton() = ButtonUtils.ToolbarButton().apply {
        border = JBUI.Borders.empty(JBUI.scale(3), JBUI.scale(8))
        font = JBFont.small()
        foreground = ChatAppColors.Text.disabled
    }

    /** 模型弹窗选中回调（成功后由 ViewModel 状态回流更新标签） */
    private fun selectModel(model: ModelDto) {
        selectedModel = model
        updateLabels()
        onModelSelected(model)
    }

    /** 更新按供应商分组的模型（由外层订阅 ViewModel 状态后调用） */
    fun updateProviders(providers: List<ModelProviderDto>) {
        this.providers = providers
    }

    private fun updateLabels() {
        approvalButton.text = "${message(approvalMode.labelKey)} ▾"
        modeButton.text = "${agents.firstOrNull { it.id == selectedAgentId }?.name ?: message("chat.input.mode")} ▾"
        modelButton.text = "${selectedModel?.name ?: message("chat.input.model")} ▾"

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
            menu.add(JMenuItem(message("chat.input.loading")).apply { isEnabled = false })
        } else {
            // 单选组 + 选中项加 ✓：不依赖 LAF 对 JRadioButtonMenuItem 圆点的渲染
            val group = ButtonGroup()
            items.forEach { (value, label) ->
                val isCurrent = value == selected
                val item = JRadioButtonMenuItem(if (isCurrent) "✓ $label" else label)
                item.isSelected = isCurrent
                item.addActionListener { onSelect(value) }
                group.add(item)
                menu.add(item)
            }
        }
        // 工具条在底部，菜单向上弹出（y 取负的菜单高度）。
        // 必须走 show(invoker, x, y)：直接 setVisible(true) 时 JPopupMenu 没有 invoker，
        // IDE 的 OurPopupFactory.getPopup 的 owner 参数为非空，会抛 NPE。
        menu.show(anchor, 0, -menu.preferredSize.height)
    }

    private fun message(key: String): String = OpencodeFrontendBundle.message(key)
}