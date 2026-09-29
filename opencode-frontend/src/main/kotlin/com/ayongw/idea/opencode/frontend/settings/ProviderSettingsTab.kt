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
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
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
    private val providerTable = buildTable(providerModel, listOf(180, 200, 90, 100))

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
        override fun isCellEditable(row: Int, column: Int): Boolean = column == MODEL_ENABLED_COLUMN

        override fun setValueAt(value: Any?, row: Int, column: Int) {
            super.setValueAt(value, row, column)
            if (column != MODEL_ENABLED_COLUMN) return
            val model = currentModels.getOrNull(row) ?: return
            val enabled = value as? Boolean ?: return
            setModelEnabled(model, enabled)
        }
    }
    private val modelTable = buildTable(modelModel, listOf(220, 260, 90))

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
                providerTable.setRowSelectionInterval(row, row)
                providers.getOrNull(row)?.let { configureProvider(it) }
            }
        })
        providerTable.columnModel.getColumn(PROVIDER_ACTION_COLUMN).cellRenderer = ActionCellRenderer()

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
                    JButton(OpencodeFrontendBundle.message("settings.opencode.model.add")).apply {
                        addActionListener { addModel() }
                    },
                    JButton(OpencodeFrontendBundle.message("settings.opencode.model.remove")).apply {
                        addActionListener { removeModel() }
                    }
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
                .setProviderModelEnabled(project.projectId(), writeScope(provider), provider.id, model.id, enabled)
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

    /** 行内「设置」按钮的渲染器（表格按需绘制，不参与焦点，避免绘制出焦点框） */
    private class ActionCellRenderer : TableCellRenderer {
        private val button = JButton(OpencodeFrontendBundle.message("settings.opencode.provider.configure")).apply {
            isFocusable = false
            isFocusPainted = false
        }

        override fun getTableCellRendererComponent(
            table: JTable,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component = button
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

    private companion object {
        const val PROVIDER_ACTION_COLUMN = 3
        const val MODEL_ENABLED_COLUMN = 2
    }
}