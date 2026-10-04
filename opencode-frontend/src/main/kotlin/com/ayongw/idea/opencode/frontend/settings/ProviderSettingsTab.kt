package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.ProviderDto
import com.ayongw.idea.opencode.shared.ProviderModelDto
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.DefaultTableCellRenderer
import javax.swing.table.DefaultTableModel
import javax.swing.table.TableCellRenderer

/**
 * 模型面板（上中下三段，master-detail）
 *
 * 上：默认模型（写配置 `model`）
 * 中：供应商列表（id / 名称 / 是否自定义 + 行内「设置」按钮，重新配置连接 URL 与 API Key）
 * 下：选中供应商的模型列表（模型 id / 名称 / 启用；行内勾选禁用启用，自定义供应商下可增删）
 *
 * 整页均为 opencode 设置：统一写入**全局**配置文件，界面上不带作用域选择。
 */
internal class ProviderSettingsTab : AbstractSettingsTab() {

    override val title: String = OpencodeFrontendBundle.message("settings.opencode.tab.models")

    private val sourceLabel = JBLabel(" ").apply { font = JBUI.Fonts.smallFont() }

    // ==================== 默认模型 ====================

    private val defaultModelCombo = JComboBox<String>()
    private val defaultModelNone = OpencodeFrontendBundle.message("settings.opencode.model.default.none")

    // ==================== 供应商 ====================

    private val providerModel = object : DefaultTableModel(
        arrayOf(
            OpencodeFrontendBundle.message("settings.opencode.provider.id"),
            OpencodeFrontendBundle.message("settings.opencode.provider.name"),
            OpencodeFrontendBundle.message("settings.opencode.provider.custom"),
            OpencodeFrontendBundle.message("settings.opencode.provider.action")
        ),
        0
    ) {
        override fun isCellEditable(row: Int, column: Int): Boolean = false
    }
    // 列宽随表格宽度自适应；Custom / Settings 两个紧凑列按内容定宽，不参与拉伸
    private val providerTable = buildTable(providerModel, listOf(130, 150, 56, 76)).apply {
        autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        pinColumnWidth(PROVIDER_CUSTOM_COLUMN)
        pinColumnWidth(PROVIDER_ACTION_COLUMN)
    }

    // ==================== 模型 ====================

    private val modelTitleLabel = JBLabel(" ").apply { font = JBUI.Fonts.label().asBold() }

    private val modelModel = object : DefaultTableModel(
        arrayOf(
            OpencodeFrontendBundle.message("settings.opencode.model.id"),
            OpencodeFrontendBundle.message("settings.opencode.model.name"),
            OpencodeFrontendBundle.message("settings.opencode.model.enabled")
        ),
        0
    ) {
        // 启用状态由开关按钮（鼠标点击）切换，表格本身不再可编辑
        override fun isCellEditable(row: Int, column: Int): Boolean = false

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            super.setValueAt(value, row, column)
            if (column != MODEL_ENABLED_COLUMN) return
            val model = currentModels.getOrNull(row) ?: return
            val enabled = value as? Boolean ?: return
            setModelEnabled(model, enabled)
        }
    }
    // 列宽随表格宽度自适应；开关按钮所在列按内容定宽，不参与拉伸
    private val modelTable = buildTable(modelModel, listOf(160, 180, 60)).apply {
        autoResizeMode = JTable.AUTO_RESIZE_ALL_COLUMNS
        pinColumnWidth(MODEL_ENABLED_COLUMN)
    }

    // 增删模型仅对自定义供应商开放，非自定义供应商下隐藏（见 showModelsOfSelection）
    private val addModelButton = JButton(OpencodeFrontendBundle.message("settings.opencode.model.add")).apply {
        addActionListener { addModel() }
    }
    private val removeModelButton = JButton(OpencodeFrontendBundle.message("settings.opencode.model.remove")).apply {
        addActionListener { removeModel() }
    }

    private var providers: List<ProviderDto> = emptyList()
    private var currentModels: List<ProviderModelDto> = emptyList()
    private var selectedProviderId: String? = null

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        val defaultRow = JPanel(BorderLayout()).apply {
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    add(JBLabel(OpencodeFrontendBundle.message("settings.opencode.model.default")))
                    add(defaultModelCombo)
                    add(
                        JButton(OpencodeFrontendBundle.message("settings.opencode.save")).apply {
                            addActionListener { saveDefaultModel() }
                        }
                    )
                },
                BorderLayout.NORTH
            )
            add(sourceLabel, BorderLayout.SOUTH)
        }

        providerTable.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) showModelsOfSelection()
        }
        providerTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 1) return
                val row = providerTable.rowAtPoint(event.point)
                if (row < 0 || providerTable.columnAtPoint(event.point) != PROVIDER_ACTION_COLUMN) return
                // 非自定义供应商不可设置（该行也不渲染按钮）
                val provider = providers.getOrNull(row)?.takeIf { it.custom } ?: return
                providerTable.setRowSelectionInterval(row, row)
                configureProvider(provider)
            }
        })
        providerTable.columnModel.getColumn(PROVIDER_ACTION_COLUMN).cellRenderer =
            ActionCellRenderer { row -> providers.getOrNull(row)?.custom == true }

        // 启用状态：点开关按钮即切换（未禁用即启用）
        modelTable.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(event: MouseEvent) {
                if (event.clickCount != 1) return
                val row = modelTable.rowAtPoint(event.point)
                if (row < 0 || modelTable.columnAtPoint(event.point) != MODEL_ENABLED_COLUMN) return
                modelTable.setRowSelectionInterval(row, row)
                val enabled = modelModel.getValueAt(row, MODEL_ENABLED_COLUMN) as? Boolean ?: return
                modelModel.setValueAt(!enabled, row, MODEL_ENABLED_COLUMN)
            }
        })
        modelTable.columnModel.getColumn(MODEL_ENABLED_COLUMN).cellRenderer = SwitchCellRenderer()
        val modelTextRenderer = DisabledAwareRenderer(modelModel, MODEL_ENABLED_COLUMN)
        modelTable.columnModel.getColumn(MODEL_ID_COLUMN).cellRenderer = modelTextRenderer
        modelTable.columnModel.getColumn(MODEL_NAME_COLUMN).cellRenderer = modelTextRenderer

        val providerBlock = JPanel(BorderLayout()).apply {
            add(
                titledRow(
                    JBLabel(OpencodeFrontendBundle.message("settings.opencode.provider.title")),
                    JButton(OpencodeFrontendBundle.message("settings.opencode.provider.add")).apply {
                        addActionListener { addProvider() }
                    },
                    JButton(OpencodeFrontendBundle.message("settings.opencode.provider.delete")).apply {
                        addActionListener { deleteProvider() }
                    }
                ),
                BorderLayout.NORTH
            )
            add(buildScroll(providerTable, 140), BorderLayout.CENTER)
        }

        val modelBlock = JPanel(BorderLayout()).apply {
            add(
                titledRow(
                    modelTitleLabel,
                    addModelButton,
                    removeModelButton
                ),
                BorderLayout.NORTH
            )
            add(buildScroll(modelTable, 200), BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            add(buildHeader(title) { reload() }, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    add(defaultRow, BorderLayout.NORTH)
                    add(providerBlock, BorderLayout.CENTER)
                    add(modelBlock, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER
            )
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    /** 左标题 + 右操作按钮的一行 */
    private fun titledRow(title: JComponent, vararg buttons: JButton): JComponent = JPanel(BorderLayout()).apply {
        add(title, BorderLayout.WEST)
        add(
            JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { buttons.forEach { add(it) } },
            BorderLayout.EAST
        )
    }

    // ==================== 加载 ====================

    override fun reload() {
        loadSnapshot { snapshot ->
            providers = snapshot.providers
            sourceLabel.text = OpencodeFrontendBundle.message(
                "settings.opencode.models.source",
                snapshot.globalConfigPath
            )

            // 重建表格会清空选中项（连带把 selectedProviderId 置空），故先记住待恢复的 id
            val keepId = selectedProviderId
            providerModel.rowCount = 0
            val yes = OpencodeFrontendBundle.message("settings.opencode.yes")
            val no = OpencodeFrontendBundle.message("settings.opencode.no")
            snapshot.providers.forEach { provider ->
                providerModel.addRow(
                    arrayOf(provider.id, provider.name.orEmpty(), if (provider.custom) yes else no, "")
                )
            }

            val names = mutableListOf(defaultModelNone)
            snapshot.providers
                .flatMap { provider -> provider.models.filter { !it.disabled }.map { "${provider.id}/${it.id}" } }
                .distinct()
                .sorted()
                .forEach { names.add(it) }
            val current = snapshot.defaultModel?.takeIf { it.isNotBlank() }
            // 已配置的默认模型若不在清单里（如已禁用）也补进下拉，避免选择框变空
            if (current != null && current !in names) names.add(current)
            defaultModelCombo.model = DefaultComboBoxModel(names.toTypedArray())
            defaultModelCombo.selectedItem = current ?: defaultModelNone

            val index = snapshot.providers.indexOfFirst { it.id == keepId }
            when {
                index >= 0 -> providerTable.setRowSelectionInterval(index, index)
                snapshot.providers.isNotEmpty() -> providerTable.setRowSelectionInterval(0, 0)
                else -> showModelsOfSelection()
            }
        }
    }

    private fun showModelsOfSelection() {
        val provider = selectedProvider()
        selectedProviderId = provider?.id
        currentModels = provider?.models.orEmpty()
        // 增删模型只对自定义供应商开放
        val custom = provider?.custom == true
        addModelButton.isVisible = custom
        removeModelButton.isVisible = custom
        addModelButton.parent?.let {
            it.revalidate()
            it.repaint()
        }
        modelTitleLabel.text = provider
            ?.let { OpencodeFrontendBundle.message("settings.opencode.model.title.of", it.id) }
            ?: OpencodeFrontendBundle.message("settings.opencode.model.title")
        modelModel.rowCount = 0
        currentModels.forEach { model ->
            // 勾选项：未标记 disabled 即启用
            modelModel.addRow(arrayOf<Any?>(model.id, model.name.orEmpty(), !model.disabled))
        }
    }

    private fun selectedProvider(): ProviderDto? = providers.getOrNull(providerTable.selectedRow)

    private fun selectedModel(): ProviderModelDto? = currentModels.getOrNull(modelTable.selectedRow)

    /** 写入作用域：跟随该供应商声明所在作用域（未声明过——即内置供应商——写全局） */
    private fun writeScope(provider: ProviderDto): ConfigScopeDto = provider.scope ?: ConfigScopeDto.GLOBAL

    // ==================== 供应商操作 ====================

    private fun configureProvider(provider: ProviderDto) {
        val dialog = ProviderDialog(provider)
        if (!dialog.showAndGet()) return
        val project = currentProject() ?: return
        runWrite({ reload() }) {
            val api = SettingsRpcApi.getInstance()
            val saved = api.saveProvider(project.projectId(), writeScope(provider), dialog.toProvider(provider.id))
            val key = dialog.apiKey()
            if (!saved.ok || key == null) {
                saved
            } else {
                api.saveCredential(project.projectId(), provider.integrationId ?: provider.id, key, null)
            }
        }
    }

    private fun addProvider() {
        val dialog = ProviderDialog(null)
        if (!dialog.showAndGet()) return
        val project = currentProject() ?: return
        val id = dialog.providerId()
        dialog.apiKey()?.let { selectedProviderId = id }
        runWrite({ reload() }) {
            val api = SettingsRpcApi.getInstance()
            val saved = api.saveProvider(project.projectId(), ConfigScopeDto.GLOBAL, dialog.toProvider(id))
            val key = dialog.apiKey()
            if (!saved.ok || key == null) {
                saved
            } else {
                api.saveCredential(project.projectId(), id, key, null)
            }
        }
    }

    private fun deleteProvider() {
        val provider = selectedProvider() ?: return
        if (!provider.custom) {
            showStatus(OpencodeFrontendBundle.message("settings.opencode.provider.remove.only.custom"))
            return
        }
        if (!confirm(
                OpencodeFrontendBundle.message("settings.opencode.provider.delete.confirm", provider.id),
                OpencodeFrontendBundle.message("settings.opencode.provider.delete")
            )
        ) {
            return
        }
        val project = currentProject() ?: return
        selectedProviderId = null
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().removeProvider(project.projectId(), writeScope(provider), provider.id)
        }
    }

    // ==================== 模型操作 ====================

    private fun setModelEnabled(model: ProviderModelDto, enabled: Boolean) {
        val provider = selectedProvider() ?: return
        val project = currentProject() ?: return
        showStatus(OpencodeFrontendBundle.message("settings.opencode.saving"))
        // 勾选已即时改变表格状态，失败时同样刷新回真实状态，避免界面与配置不一致
        runAsync(
            { result ->
                if (result == null || !result.ok) showStatus(friendlyError(result?.message), result?.message)
                reload()
            }
        ) {
            SettingsRpcApi.getInstance()
                .setProviderModelEnabled(
                    project.projectId(),
                    writeScope(provider),
                    provider.id,
                    model.id,
                    enabled,
                    // 禁用后服务端不再返回该模型，把当前显示的名写进配置，列表才不会缺名称
                    model.name
                )
        }
    }

    private fun addModel() {
        val provider = selectedProvider() ?: return
        if (!provider.custom) {
            showStatus(OpencodeFrontendBundle.message("settings.opencode.model.custom.only"))
            return
        }
        val dialog = ModelDialog(provider.id)
        if (!dialog.showAndGet()) return
        val project = currentProject() ?: return
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance()
                .saveProviderModel(
                    project.projectId(),
                    writeScope(provider),
                    provider.id,
                    dialog.modelId(),
                    dialog.modelName()
                )
        }
    }

    private fun removeModel() {
        val provider = selectedProvider() ?: return
        val model = selectedModel() ?: return
        if (!model.declaredInConfig) {
            showStatus(OpencodeFrontendBundle.message("settings.opencode.model.remove.only.custom"))
            return
        }
        if (!confirm(
                OpencodeFrontendBundle.message("settings.opencode.model.remove.confirm", model.id),
                OpencodeFrontendBundle.message("settings.opencode.model.remove")
            )
        ) {
            return
        }
        val project = currentProject() ?: return
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance()
                .removeProviderModel(project.projectId(), writeScope(provider), provider.id, model.id)
        }
    }

    private fun saveDefaultModel() {
        val project = currentProject() ?: return
        val selected = (defaultModelCombo.selectedItem as? String)?.takeIf { it != defaultModelNone }
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().setDefaultModel(project.projectId(), ConfigScopeDto.GLOBAL, selected)
        }
    }

    private fun confirm(message: String, title: String): Boolean =
        Messages.showYesNoDialog(message, title, Messages.getQuestionIcon()) == Messages.YES

    override fun isModified(): Boolean = false

    /** 行内「设置」按钮的渲染器（表格按需绘制，不参与焦点，避免绘制出焦点框）；[canConfigure] 为 false 的行渲染为空白 */
    private class ActionCellRenderer(private val canConfigure: (Int) -> Boolean) : TableCellRenderer {
        private val button = JButton(OpencodeFrontendBundle.message("settings.opencode.provider.configure")).apply {
            isFocusable = false
            isFocusPainted = false
        }
        private val blank = DefaultTableCellRenderer()

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component = if (canConfigure(row)) {
            button
        } else {
            blank.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column)
        }
    }

    /** 模型行渲染：禁用中的行用次要色，与启用行区分 */
    private class DisabledAwareRenderer(
        private val model: DefaultTableModel,
        private val enabledColumn: Int
    ) : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)
            val enabled = model.getValueAt(row, enabledColumn) as? Boolean ?: true
            component.foreground =
                if (!enabled && !isSelected) UIUtil.getContextHelpForeground() else UIUtil.getTableForeground()
            return component
        }
    }

    /** 启用状态渲染为开关：开=蓝色轨道 + 右侧白点，关=灰色轨道 + 左侧白点 */
    private class SwitchCellRenderer : TableCellRenderer {
        private val view = SwitchView()

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            view.on = value as? Boolean ?: false
            view.background = if (isSelected) table.selectionBackground else table.background
            return view
        }

        private class SwitchView : JComponent() {
            var on: Boolean = false

            init {
                isOpaque = true
            }

            override fun getPreferredSize(): Dimension =
                Dimension(JBUI.scale(SWITCH_WIDTH), JBUI.scale(SWITCH_HEIGHT))

            override fun paintComponent(g: Graphics) {
                g.color = background
                g.fillRect(0, 0, width, height)
                val g2 = g.create() as Graphics2D
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                    val trackW = JBUI.scale(SWITCH_WIDTH).toDouble()
                    val trackH = JBUI.scale(SWITCH_HEIGHT).toDouble()
                    val x = (width - trackW) / 2
                    val y = (height - trackH) / 2
                    g2.color = if (on) SettingsColors.Switch.on else SettingsColors.Switch.off
                    g2.fill(RoundRectangle2D.Double(x, y, trackW, trackH, trackH, trackH))
                    val pad = JBUI.scale(SWITCH_PADDING).toDouble()
                    val knob = trackH - pad * 2
                    val knobX = if (on) x + trackW - knob - pad else x + pad
                    g2.color = Color.WHITE
                    g2.fill(Ellipse2D.Double(knobX, y + pad, knob, knob))
                } finally {
                    g2.dispose()
                }
            }

            private companion object {
                const val SWITCH_WIDTH = 34
                const val SWITCH_HEIGHT = 18
                const val SWITCH_PADDING = 2
            }
        }
    }

    /** 供应商设置：名称 / 连接 URL / API Key；新增时额外填 id 与运行时包 */
    private class ProviderDialog(private val existing: ProviderDto?) : DialogWrapper(true) {

        private val isNew = existing == null

        private val idField = JBTextField(20)
        private val nameField = JBTextField(24)
        private val packageField = JBTextField(30)
        private val baseUrlField = JBTextField(32)
        private val apiKeyField = JBPasswordField().apply { columns = 30 }

        init {
            title = OpencodeFrontendBundle.message(
                if (isNew) "settings.opencode.provider.add" else "settings.opencode.provider.configure"
            )
            setOKButtonText(OpencodeFrontendBundle.message("settings.opencode.save"))
            setCancelButtonText(OpencodeFrontendBundle.message("common.cancel"))
            existing?.let {
                idField.text = it.id
                nameField.text = it.name.orEmpty()
                packageField.text = it.packageName.orEmpty()
                baseUrlField.text = it.baseUrl.orEmpty()
            }
            idField.isEnabled = isNew
            packageField.isEnabled = isNew
            init()
        }

        override fun createCenterPanel(): JComponent {
            val builder = FormBuilder.createFormBuilder()
                .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.id"), idField)
                .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.name"), nameField)
            if (isNew) {
                builder.addLabeledComponent(
                    OpencodeFrontendBundle.message("settings.opencode.provider.package"),
                    packageField
                )
            }
            return builder
                .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.baseurl"), baseUrlField)
                .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.apikey"), apiKeyField)
                .addComponent(
                    JBLabel(OpencodeFrontendBundle.message("settings.opencode.provider.apikey.hint")).apply {
                        font = JBUI.Fonts.smallFont()
                        foreground = com.intellij.util.ui.UIUtil.getContextHelpForeground()
                    }
                )
                .panel
        }

        override fun doOKAction() {
            if (providerId().isBlank()) {
                Messages.showWarningDialog(
                    OpencodeFrontendBundle.message("settings.opencode.provider.id.required"),
                    OpencodeFrontendBundle.message("settings.opencode.provider.add")
                )
                return
            }
            super.doOKAction()
        }

        fun providerId(): String = idField.text.trim()

        fun apiKey(): String? = String(apiKeyField.password).trim().ifBlank { null }

        fun toProvider(id: String): ProviderDto = ProviderDto(
            id = id,
            name = nameField.text.trim().ifBlank { null },
            packageName = packageField.text.trim().ifBlank { null },
            baseUrl = baseUrlField.text.trim().ifBlank { null }
        )
    }

    /** 新增模型：模型 id + 名称 */
    private class ModelDialog(providerId: String) : DialogWrapper(true) {

        private val idField = JBTextField(24)
        private val nameField = JBTextField(28)

        init {
            title = OpencodeFrontendBundle.message("settings.opencode.model.add") + " · " + providerId
            setOKButtonText(OpencodeFrontendBundle.message("settings.opencode.save"))
            setCancelButtonText(OpencodeFrontendBundle.message("common.cancel"))
            init()
        }

        override fun createCenterPanel(): JComponent = FormBuilder.createFormBuilder()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.model.id"), idField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.model.name"), nameField)
            .panel

        override fun doOKAction() {
            if (modelId().isBlank()) {
                Messages.showWarningDialog(
                    OpencodeFrontendBundle.message("settings.opencode.model.id.required"),
                    OpencodeFrontendBundle.message("settings.opencode.model.add")
                )
                return
            }
            super.doOKAction()
        }

        fun modelId(): String = idField.text.trim()

        fun modelName(): String? = nameField.text.trim().ifBlank { null }
    }

    /** 把某列按内容宽度钉住（min=max=preferred）：自适应模式下仍不参与拉伸，按钮/开关保持原大小 */
    private fun JTable.pinColumnWidth(column: Int) {
        columnModel.getColumn(column).apply {
            minWidth = preferredWidth
            maxWidth = preferredWidth
        }
    }

    private companion object {
        const val MODEL_ID_COLUMN = 0
        const val MODEL_NAME_COLUMN = 1
        const val MODEL_ENABLED_COLUMN = 2
        const val PROVIDER_CUSTOM_COLUMN = 2
        const val PROVIDER_ACTION_COLUMN = 3
    }
}