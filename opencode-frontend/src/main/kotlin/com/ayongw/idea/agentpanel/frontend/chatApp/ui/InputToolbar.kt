package com.ayongw.idea.agentpanel.frontend.chatApp.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ButtonUtils
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.TextElipsis
import com.ayongw.idea.agentpanel.frontend.chatApp.viewmodel.ApprovalMode
import com.ayongw.idea.agentpanel.shared.AgentDto
import com.ayongw.idea.agentpanel.shared.ModelDto
import com.ayongw.idea.agentpanel.shared.ModelProviderDto
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

    /** 按钮之间的竖线分隔（左右各留白，宽度计入 [applyModelElision] 的固定宽度预算） */
    private val modeSeparator = createSeparator()
    private val modelSeparator = createSeparator()

    /** 不随宽度收缩的左侧固定部分（省略预算用，必须与实际挂载的组件保持一致） */
    private val fixedLeftComponents: List<JComponent>
        get() = listOf(approvalButton, modeSeparator, modeButton, modelSeparator)

    private val modelPicker = ModelPickerPopup(
        onSelect = { model -> selectModel(model) },
        onManage = { onManageModels() }
    )

    private var approvalMode: ApprovalMode = ApprovalMode.AUTO
    private var agents: List<AgentDto> = emptyList()
    private var providers: List<ModelProviderDto> = emptyList()
    private var selectedAgentId: String? = null
    private var selectedModel: ModelDto? = null

    /** 模型名（不含下拉箭头）；可见文本由 [applyModelElision] 按宽度省略后拼装 */
    private var modelName: String = ""

    /** 同排右侧其它组件占用宽度（见 [setReservedRightWidth]） */
    private var reservedRightWidth: Int = 0

    /** 本排可用宽度（见 [setAvailableWidth]）；0 表示外层尚未告知 */
    private var availableWidth: Int = 0

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
        add(modeSeparator)
        add(modeButton)
        add(modelSeparator)
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

    /** 竖线分隔：取分隔线色（弱于按钮文字，不抢注意力），两侧留白由自身 border 承担 */
    private fun createSeparator(): JComponent = JBLabel("|").apply {
        font = JBFont.small()
        foreground = ChatAppColors.Divider.line
        border = JBUI.Borders.empty(0, JBUI.scale(ChatUIConstants.Spacing.SMALL), 0, JBUI.scale(ChatUIConstants.Spacing.SMALL))
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
        approvalButton.text = "${message(approvalMode.labelKey)}$ARROW"
        modeButton.text = "${agents.firstOrNull { it.id == selectedAgentId }?.name ?: message("chat.input.mode")}$ARROW"
        // 模型名长度不可控（MiMo-V2.6-Flash Free / claude-sonnet-4-5-thinking-…）：
        // 只留名字本身，可见的「名字 + 下拉箭头」由 applyModelElision 按可用宽度拼装。
        modelName = selectedModel?.name ?: message("chat.input.model")
        modelButton.toolTipText = "$modelName$ARROW"
        modelButton.text = "$modelName$ARROW"

        revalidate()
        repaint()
    }

    /**
     * 本排可用宽度（工具条所在行的实测宽度），由外层在 resize 时下发。
     *
     * **不能用自身 `width` 当预算**：`BorderLayout` 把 WEST 的宽度设成它的 preferredSize，
     * 而 preferredSize 又由省略结果决定 —— 两者互相依赖会来回震荡（实测 450 → 预算 193，
     * 随后被压到 404 → 预算 147），既收敛不了也保证不了 EAST 有位置。
     * 取外层给的行宽则与自身 preferredSize 无关，一次算定即稳定。
     */
    fun setAvailableWidth(width: Int) {
        val w = width.coerceAtLeast(0)
        if (availableWidth == w) return
        availableWidth = w
        revalidate()
        repaint()
    }

    /**
     * 同一排右侧其它组件（如右侧用量指示器）占用的宽度。
     *
     * 必须由外层告知：本工具条是 `BorderLayout` 的 WEST 子组件，看不到 EAST 有多宽，
     * 若把富余全额吃掉，EAST 会被挤出可视区（实测 450px 下重叠 22px）。
     */
    fun setReservedRightWidth(width: Int) {
        val w = width.coerceAtLeast(0)
        if (reservedRightWidth == w) return
        reservedRightWidth = w
        revalidate()
        repaint()
    }

    /**
     * 窄窗口下收缩模型名：模型按钮吸收本排的剩余宽度，审核 / 模式按钮宽度固定。
     *
     * 放在 [doLayout] 而非 resize 监听里：每次布局都会重算，天然跟随窗口变化，
     * 且不需要额外的 ComponentListener 生命周期管理。
     */
    override fun doLayout() {
        applyModelElision()
        super.doLayout()
    }

    /** 按本排可用宽度省略模型名（下拉箭头始终保留，不参与省略） */
    private fun applyModelElision() {
        // 外层未下发行宽时退回自身宽度（首次布局前的兜底）
        val budgetWidth = if (availableWidth > 0) availableWidth else width
        if (modelName.isEmpty() || budgetWidth <= 0) return
        // 固定宽度按实际挂载的左侧组件累加（审核 / 分隔线 / 模式 / 分隔线），
        // 写死常量会在增减组件时与真实布局漂移，导致模型按钮挤压右侧用量
        val fixedWidth = fixedLeftComponents.sumOf { it.preferredSize.width } + reservedRightWidth
        val insets = modelButton.insets
        val metrics = modelButton.getFontMetrics(modelButton.font)
        // 极窄窗口下 budget 可能 ≤ 0；此时 TextElipsis 会「视为不限」而原样返回全名，
        // 反而更挤。故下限取一个省略号宽：退化到只显示「…」，不溢出。
        val budget = maxOf(
            budgetWidth - fixedWidth - insets.left - insets.right,
            metrics.stringWidth(TextElipsis.ELLIPSIS)
        )
        val shown = TextElipsis.elide(modelName, budget, metrics::stringWidth)
        val text = shown + ARROW
        if (text == modelButton.text) return
        modelButton.text = text
        // 仅靠 modelButton.revalidate() 不足以让本容器的 preferredSize 缓存失效
        //（实测改文本后 getPreferredSize() 仍返回旧值），需显式 invalidate 整条链
        modelButton.revalidate()
        revalidate()
        invalidate()
    }

    private companion object {
        /** 下拉 affordance：始终保留，不参与省略 */
        const val ARROW = " ▾"
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

    private fun message(key: String): String = AgentPanelBundle.message(key)
}