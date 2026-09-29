package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.ProviderDto
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
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
    private val table = buildTable(tableModel, listOf(120, 140, 180, 200, 200, 100, 80))
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
        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) fillFormFromSelection()
        }

        val detail = FormBuilder.createFormBuilder()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.id"), idField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.name"), nameField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.package"), packageField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.baseurl"), baseUrlField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.models"), modelsField)
            .addComponent(
                JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    add(
                        JButton(OpencodeFrontendBundle.message("settings.opencode.save")).apply {
                            addActionListener { saveProvider() }
                        }
                    )
                    add(
                        JButton(OpencodeFrontendBundle.message("settings.opencode.delete")).apply {
                            addActionListener { deleteProvider() }
                        }
                    )
                    add(
                        JButton(OpencodeFrontendBundle.message("settings.opencode.provider.save.credential")).apply {
                            addActionListener { saveCredential() }
                        }
                    )
                }
            )
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.provider.apikey"), apiKeyField)
            .addSeparator()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.model.default"), defaultModelCombo)
            .addComponent(
                JButton(OpencodeFrontendBundle.message("settings.opencode.save")).apply {
                    addActionListener { saveDefaultModel() }
                }
            )
            .panel

        return JPanel(BorderLayout()).apply {
            add(buildHeader(title) { reload() }, BorderLayout.NORTH)
            add(
                JPanel(BorderLayout()).apply {
                    add(buildScopeRow(scopeCombo), BorderLayout.NORTH)
                    add(JBScrollPane(table), BorderLayout.CENTER)
                    add(detail, BorderLayout.SOUTH)
                },
                BorderLayout.CENTER
            )
            add(statusLabel, BorderLayout.SOUTH)
        }
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

    private fun selectedProvider(): ProviderDto? = providers.getOrNull(table.selectedRow)

    private fun selectedScope(): ConfigScopeDto = scopeOf(scopeCombo)

    private fun models(): List<String> =
        modelsField.text.split(',').map { it.trim() }.filter { it.isNotBlank() }

    private fun saveProvider() {
        val id = idField.text.trim()
        if (id.isBlank()) {
            showStatus(OpencodeFrontendBundle.message("settings.opencode.provider.id.required"))
            return
        }
        val project = currentProject() ?: return
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
        val project = currentProject() ?: return
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().removeProvider(project.projectId(), selectedScope(), provider.id)
        }
    }

    private fun saveCredential() {
        val provider = selectedProvider() ?: return
        val key = String(apiKeyField.password).trim()
        if (key.isBlank()) {
            showStatus(OpencodeFrontendBundle.message("settings.opencode.provider.apikey.required"))
            return
        }
        val project = currentProject() ?: return
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
        val project = currentProject() ?: return
        val selected = (defaultModelCombo.selectedItem as? String)?.takeIf { it != defaultModelNone }
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().setDefaultModel(project.projectId(), selectedScope(), selected)
        }
    }

    override fun isModified(): Boolean = false
}