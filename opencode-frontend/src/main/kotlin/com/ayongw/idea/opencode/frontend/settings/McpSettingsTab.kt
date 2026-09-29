package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.McpServerDto
import com.ayongw.idea.opencode.shared.McpTimeoutDto
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
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
 * MCP 面板：服务器列表（增删改）+ 运行状态 + 超时
 *
 * 写入 `mcp.servers.<name>`、`mcp.timeout`；状态来自 `GET /api/mcp`。
 */
internal class McpSettingsTab : AbstractSettingsTab() {

    override val title: String = OpencodeFrontendBundle.message("settings.opencode.tab.mcp")

    private val tableModel = DefaultTableModel(
        arrayOf(
            OpencodeFrontendBundle.message("settings.opencode.mcp.name"),
            OpencodeFrontendBundle.message("settings.opencode.mcp.type"),
            OpencodeFrontendBundle.message("settings.opencode.mcp.target"),
            OpencodeFrontendBundle.message("settings.opencode.mcp.enabled"),
            OpencodeFrontendBundle.message("settings.opencode.mcp.status"),
            OpencodeFrontendBundle.message("settings.opencode.scope")
        ),
        0
    )
    private val table = JTable(tableModel).apply { setSelectionMode(ListSelectionModel.SINGLE_SELECTION) }

    private val scopeCombo = JComboBox<String>()
    private val nameField = JBTextField()
    private val typeCombo = JComboBox<String>()
    private val commandField = JBTextField()
    private val urlField = JBTextField()
    private val environmentArea = JBTextArea(4, 40)
    private val enabledCheck = JBCheckBox(OpencodeFrontendBundle.message("settings.opencode.mcp.enabled"), true)

    private val startupField = JBTextField(8)
    private val catalogField = JBTextField(8)
    private val executionField = JBTextField(8)

    private val localLabel = OpencodeFrontendBundle.message("settings.opencode.mcp.type.local")
    private val remoteLabel = OpencodeFrontendBundle.message("settings.opencode.mcp.type.remote")

    private var servers: List<McpServerDto> = emptyList()

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        scopeCombo.model = buildScopeModel()
        typeCombo.model = DefaultComboBoxModel(arrayOf(localLabel, remoteLabel))
        nameField.columns = 24
        commandField.columns = 40
        urlField.columns = 40

        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) fillFormFromSelection()
        }

        val saveButton = JButton(OpencodeFrontendBundle.message("settings.opencode.save")).apply {
            addActionListener { saveServer() }
        }
        val deleteButton = JButton(OpencodeFrontendBundle.message("settings.opencode.delete")).apply {
            addActionListener { deleteServer() }
        }
        val saveTimeoutButton = JButton(OpencodeFrontendBundle.message("settings.opencode.save")).apply {
            addActionListener { saveTimeout() }
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.scope"), scopeCombo)
            .addComponent(JBScrollPane(table).apply { preferredSize = JBUI.size(760, 180) })
            .addSeparator()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.name"), nameField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.type"), typeCombo)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.command"), commandField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.url"), urlField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.environment"), JBScrollPane(environmentArea))
            .addComponent(enabledCheck)
            .addComponent(
                JPanel(BorderLayout()).apply {
                    add(saveButton, BorderLayout.WEST)
                    add(deleteButton, BorderLayout.EAST)
                }
            )
            .addSeparator()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.timeout.startup"), startupField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.timeout.catalog"), catalogField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.timeout.execution"), executionField)
            .addComponent(saveTimeoutButton)
            .addComponent(statusLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun reload() {
        loadSnapshot { snapshot ->
            servers = snapshot.mcpServers
            tableModel.rowCount = 0
            snapshot.mcpServers.forEach { server ->
                tableModel.addRow(
                    arrayOf(
                        server.name,
                        if (server.type == "remote") remoteLabel else localLabel,
                        server.url ?: server.command.joinToString(" "),
                        if (server.enabled) OpencodeFrontendBundle.message("settings.opencode.yes")
                        else OpencodeFrontendBundle.message("settings.opencode.no"),
                        server.statusError?.let { "${server.status.orEmpty()} ($it)" } ?: server.status.orEmpty(),
                        when (server.scope) {
                            ConfigScopeDto.GLOBAL -> OpencodeFrontendBundle.message("settings.opencode.scope.global")
                            ConfigScopeDto.PROJECT -> OpencodeFrontendBundle.message("settings.opencode.scope.project")
                            null -> OpencodeFrontendBundle.message("settings.opencode.scope.server")
                        }
                    )
                )
            }

            val timeout = snapshot.mcpTimeout
            startupField.text = timeout?.startup?.toString().orEmpty()
            catalogField.text = timeout?.catalog?.toString().orEmpty()
            executionField.text = timeout?.execution?.toString().orEmpty()
        }
    }

    private fun fillFormFromSelection() {
        val server = selectedServer() ?: return
        nameField.text = server.name
        typeCombo.selectedItem = if (server.type == "remote") remoteLabel else localLabel
        commandField.text = server.command.joinToString(" ")
        urlField.text = server.url.orEmpty()
        environmentArea.text = server.environment.entries.joinToString("\n") { "${it.key}=${it.value}" }
        enabledCheck.isSelected = server.enabled
        scopeCombo.selectedIndex = if (server.scope == ConfigScopeDto.PROJECT) 1 else 0
    }

    private fun selectedServer(): McpServerDto? {
        val row = table.selectedRow
        if (row < 0 || row >= servers.size) return null
        return servers[row]
    }

    private fun saveServer() {
        val name = nameField.text.trim()
        if (name.isBlank()) {
            statusLabel.text = OpencodeFrontendBundle.message("settings.opencode.mcp.name.required")
            return
        }
        val project = ProjectManager.getInstance().openProjects.first()
        val isRemote = typeCombo.selectedItem == remoteLabel
        val server = McpServerDto(
            name = name,
            type = if (isRemote) "remote" else "local",
            enabled = enabledCheck.isSelected,
            command = commandField.text.trim().split(' ').filter { it.isNotBlank() },
            url = urlField.text.trim().ifBlank { null },
            environment = environmentArea.text.lines()
                .map { it.trim() }
                .filter { it.contains('=') }
                .associate { line -> line.substringBefore('=').trim() to line.substringAfter('=').trim() }
        )
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().saveMcpServer(project.projectId(), scopeOf(scopeCombo), server)
        }
    }

    private fun deleteServer() {
        val server = selectedServer() ?: return
        val project = ProjectManager.getInstance().openProjects.first()
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().removeMcpServer(project.projectId(), scopeOf(scopeCombo), server.name)
        }
    }

    private fun saveTimeout() {
        val project = ProjectManager.getInstance().openProjects.first()
        val timeout = McpTimeoutDto(
            startup = startupField.text.trim().toLongOrNull(),
            catalog = catalogField.text.trim().toLongOrNull(),
            execution = executionField.text.trim().toLongOrNull()
        )
        val isEmpty = timeout.startup == null && timeout.catalog == null && timeout.execution == null
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().saveMcpTimeout(project.projectId(), scopeOf(scopeCombo), if (isEmpty) null else timeout)
        }
    }

    override fun isModified(): Boolean = false
}