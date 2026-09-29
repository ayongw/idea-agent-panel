package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.ProviderDto
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * 模型面板：供应商列表（增删改）与默认模型、apiKey
 *
 * 读取走 `/api/provider`、`/api/model`；写入改配置文件 `providers.*`、`model`，apiKey 走 `integration/{id}/connect/key`。
 */
internal class ProviderSettingsTab : AbstractSettingsTab() {

    override val title: String = OpencodeFrontendBundle.message("settings.opencode.tab.models")

    private val tableModel = DefaultTableModel(
        arrayOf(
            OpencodeFrontendBundle.message("settings.opencode.provider.id"),
            OpencodeFrontendBundle.message("settings.opencode.provider.name"),
            OpencodeFrontendBundle.message("settings.opencode.provider.package"),
            OpencodeFrontendBundle.message("settings.opencode.provider.baseurl"),
            OpencodeFrontendBundle.message("settings.opencode.provider.models"),
            OpencodeFrontendBundle.message("settings.opencode.scope"),
            OpencodeFrontendBundle.message("settings.opencode.provider.credential")
        ),
        0
    )
    private val table = JTable(tableModel).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }
    private val idField = JBTextField()
    private val nameField = JBTextField()
    private val packageField = JBTextField()
    private val baseUrlField = JBTextField()
    private val modelsField = JBTextField()
    private val apiKeyField = JBPasswordField()
    private val scopeCombo = JComboBox<String>()
    private val defaultModelCombo = JComboBox<String>()
    private val defaultModelNone = OpencodeFrontendBundle.message("settings.opencode.model.default.none")

    private var providers: List<ProviderDto> = emptyList()
    private var defaultModel: String? = null

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        idField.columns = 24
        nameField.columns = 24
        packageField.columns = 24
        baseUrlField.columns = 24
        modelsField.columns = 40
        apiKeyField.columns = 24

        scopeCombo.model = buildScopeModel()

        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) fillFormFromSelection()
        }

        val saveButton = JButton(OpencodeFrontendBundle.message("settings.opencode.save")).apply {
            addActionListener { saveProvider() }
        }
        val deleteButton = JButton(OpencodeFrontendBundle.message("settings.opencode.delete")).apply {
            addActionListener { deleteProvider() }
        }
        val credentialButton = JButton(OpencodeFrontendBundle.message("settings.opencode.provider.save.credential")).apply {
            addActionListener { saveCredential() }
        }
        val defaultModelButton = JButton(OpencodeFrontendBundle.message("settings.opencode.save")).apply {
            addActionListener { saveDefaultModel() }
        }

        val center = FormBuilder.createFormBuilder()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.scope"), scopeCombo)
            .addComponent(JBScrollPane(table).apply { preferredSize = JBUI.size(760, 200) })
            .addSeparator()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.id"), idField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.name"), nameField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.package"), packageField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.baseurl"), baseUrlField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.models"), modelsField)
            .addComponent(
                JPanel(BorderLayout()).apply {
                    add(saveButton, BorderLayout.WEST)
                    add(deleteButton, BorderLayout.CENTER)
                    add(credentialButton, BorderLayout.EAST)
                }
            )
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.apikey"), apiKeyField)
            .addSeparator()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.model.default"), defaultModelCombo)
            .addComponent(defaultModelButton)
            .addComponent(statusLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return center
    }

    override fun reload() {
        loadSnapshot { snapshot ->
            providers = snapshot.providers
            defaultModel = snapshot.defaultModel

            tableModel.rowCount = 0
            snapshot.providers.forEach { provider ->
                tableModel.addRow(
                    arrayOf(
                        provider.id,
                        provider.name.orEmpty(),
                        provider.packageName.orEmpty(),
                        provider.baseUrl.orEmpty(),
                        provider.models.joinToString(", "),
                        when (provider.scope) {
                            ConfigScopeDto.GLOBAL -> OpencodeFrontendBundle.message("settings.opencode.scope.global")
                            ConfigScopeDto.PROJECT -> OpencodeFrontendBundle.message("settings.opencode.scope.project")
                            null -> OpencodeFrontendBundle.message("settings.opencode.scope.server")
                        },
                        if (provider.hasCredential) OpencodeFrontendBundle.message("settings.opencode.yes")
                        else OpencodeFrontendBundle.message("settings.opencode.no")
                    )
                )
            }

            val model = DefaultComboBoxModel<String>()
            model.addElement(defaultModelNone)
            snapshot.models.map { "${it.providerID}/${it.modelID}" }.distinct().sorted().forEach { model.addElement(it) }
            defaultModelCombo.model = model
            defaultModelCombo.selectedItem = snapshot.defaultModel?.takeIf { it.isNotBlank() } ?: defaultModelNone
        }
    }

    private fun fillFormFromSelection() {
        val provider = selectedProvider() ?: return
        idField.text = provider.id
        nameField.text = provider.name.orEmpty()
        packageField.text = provider.packageName.orEmpty()
        baseUrlField.text = provider.baseUrl.orEmpty()
        modelsField.text = provider.models.joinToString(", ")
        apiKeyField.text = ""
        scopeCombo.selectedIndex = if (provider.scope == ConfigScopeDto.PROJECT) 1 else 0
    }

    private fun selectedProvider(): ProviderDto? {
        val row = table.selectedRow
        if (row < 0 || row >= providers.size) return null
        return providers[row]
    }

    private fun selectedScope(): ConfigScopeDto = scopeOf(scopeCombo)

    private fun models(): List<String> =
        modelsField.text.split(',').map { it.trim() }.filter { it.isNotBlank() }

    private fun saveProvider() {
        val id = idField.text.trim()
        if (id.isBlank()) {
            statusLabel.text = OpencodeFrontendBundle.message("settings.opencode.provider.id.required")
            return
        }
        val project = ProjectManager.getInstance().openProjects.first()
        val scope = selectedScope()
        val provider = ProviderDto(
            id = id,
            name = nameField.text.trim().ifBlank { null },
            packageName = packageField.text.trim().ifBlank { null },
            baseUrl = baseUrlField.text.trim().ifBlank { null },
            models = models()
        )
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().saveProvider(project.projectId(), scope, provider)
        }
    }

    private fun deleteProvider() {
        val provider = selectedProvider() ?: return
        val project = ProjectManager.getInstance().openProjects.first()
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().removeProvider(project.projectId(), selectedScope(), provider.id)
        }
    }

    private fun saveCredential() {
        val provider = selectedProvider() ?: return
        val key = String(apiKeyField.password).trim()
        if (key.isBlank()) {
            statusLabel.text = OpencodeFrontendBundle.message("settings.opencode.provider.apikey.required")
            return
        }
        val project = ProjectManager.getInstance().openProjects.first()
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().saveCredential(
                project.projectId(),
                provider.integrationId ?: provider.id,
                key,
                null
            )
        }
    }

    private fun saveDefaultModel() {
        val project = ProjectManager.getInstance().openProjects.first()
        val selected = (defaultModelCombo.selectedItem as? String)?.takeIf { it != defaultModelNone }
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().setDefaultModel(project.projectId(), selectedScope(), selected)
        }
    }

    override fun isModified(): Boolean = false
}