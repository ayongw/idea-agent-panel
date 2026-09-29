package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.McpServerDto
import com.ayongw.idea.opencode.shared.McpTimeoutDto
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/**
 * MCP 面板（上中下三段）
 *
 * 上：配置来源（全局 / 项目配置文件路径）
 * 中：当前加载的 MCP 服务器（`GET /api/mcp` + 配置 `mcp.servers`）
 * 下：选中条目的详情编辑 + `mcp.timeout`
 */
internal class McpSettingsTab : AbstractSettingsTab() {

    override val title: String = OpencodeFrontendBundle.message("settings.opencode.tab.mcp")

    private val scopeCombo = JComboBox<String>()
    private val sourceLabel = JBLabel(" ").apply { font = JBUI.Fonts.smallFont() }

    private val serverModel = DefaultTableModel(
        arrayOf(
            OpencodeFrontendBundle.message("settings.opencode.mcp.name"),
            OpencodeFrontendBundle.message("settings.opencode.mcp.type"),
            OpencodeFrontendBundle.message("settings.opencode.mcp.target"),
            OpencodeFrontendBundle.message("settings.opencode.mcp.status"),
            OpencodeFrontendBundle.message("settings.opencode.mcp.scope"),
            OpencodeFrontendBundle.message("settings.opencode.mcp.enabled")
        ),
        0
    )
    private val serverTable = buildTable(serverModel, listOf(140, 80, 260, 160, 130, 70))

    private val nameField = JBTextField()
    private val typeCombo = JComboBox<String>()
    private val commandField = JBTextField()
    private val urlField = JBTextField()
    private val environmentArea = JBTextArea().apply { rows = 3 }
    private val enabledCheck = JBCheckBox(OpencodeFrontendBundle.message("settings.opencode.mcp.enabled"), true)

    private val startupField = JBTextField()
    private val catalogField = JBTextField()
    private val executionField = JBTextField()

    private val localLabel = OpencodeFrontendBundle.message("settings.opencode.mcp.type.local")
    private val remoteLabel = OpencodeFrontendBundle.message("settings.opencode.mcp.type.remote")

    private var servers: List<McpServerDto> = emptyList()

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        typeCombo.model = DefaultComboBoxModel(arrayOf(localLabel, remoteLabel))

        serverTable.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) fillFormFromSelection()
        }

        val listBlock = JPanel(BorderLayout()).apply {
            add(sourceLabel, BorderLayout.NORTH)
            add(JBScrollPane(serverTable), BorderLayout.CENTER)
        }

        val detailBlock = FormBuilder.createFormBuilder()
            .addComponent(JBLabel(OpencodeFrontendBundle.message("settings.opencode.mcp.detail")))
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.name"), nameField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.type"), typeCombo)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.command"), commandField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.url"), urlField)
            .addComponent(JBLabel(OpencodeFrontendBundle.message("settings.opencode.mcp.environment")))
            .addComponent(JBScrollPane(environmentArea))
            .addComponent(enabledCheck)
            .addComponent(
                JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                    add(
                        JButton(OpencodeFrontendBundle.message("settings.opencode.save")).apply {
                            addActionListener { saveServer() }
                        }
                    )
                    add(
                        JButton(OpencodeFrontendBundle.message("settings.opencode.delete")).apply {
                            addActionListener { deleteServer() }
                        }
                    )
                    add(buildScopeRow(scopeCombo))
                }
            )
            .panel

        val timeoutBlock = FormBuilder.createFormBuilder()
            .addSeparator()
            .addComponent(JBLabel(OpencodeFrontendBundle.message("settings.opencode.mcp.timeout")))
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.timeout.startup"), startupField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.mcp.timeout.catalog"), catalogField)
            .addLabeledComponent(
                OpencodeFrontendBundle.message("settings.opencode.mcp.timeout.execution"),
                executionField
            )
            .addComponent(
                JButton(OpencodeFrontendBundle.message("settings.opencode.save")).apply {
                    addActionListener { saveTimeout() }
                }
            )
            .panel

        return JPanel(BorderLayout()).apply {
            add(
                buildHeader(title, OpencodeFrontendBundle.message("settings.opencode.mcp.hint")) { reload() },
                BorderLayout.NORTH
            )
            add(listBlock, BorderLayout.CENTER)
            add(
                JPanel(BorderLayout()).apply {
                    add(
                        JPanel(BorderLayout()).apply {
                            add(detailBlock, BorderLayout.CENTER)
                            add(timeoutBlock, BorderLayout.SOUTH)
                        },
                        BorderLayout.CENTER
                    )
                    add(statusLabel, BorderLayout.SOUTH)
                },
                BorderLayout.SOUTH
            )
        }
    }

    override fun reload() {
        loadSnapshot { snapshot ->
            sourceLabel.text = OpencodeFrontendBundle.message(
                "settings.opencode.mcp.source",
                snapshot.globalConfigPath,
                snapshot.projectConfigPath
            )

            servers = snapshot.mcpServers
            serverModel.rowCount = 0
            snapshot.mcpServers.forEach { server ->
                serverModel.addRow(
                    arrayOf(
                        server.name,
                        if (server.type == "remote") remoteLabel else localLabel,
                        server.url ?: server.command.joinToString(" "),
                        server.statusError?.let { "${server.status.orEmpty()} ($it)" } ?: server.status.orEmpty(),
                        when (server.scope) {
                            ConfigScopeDto.GLOBAL -> OpencodeFrontendBundle.message("settings.opencode.scope.global")
                            ConfigScopeDto.PROJECT -> OpencodeFrontendBundle.message("settings.opencode.scope.project")
                            null -> OpencodeFrontendBundle.message("settings.opencode.scope.server")
                        },
                        if (server.enabled) OpencodeFrontendBundle.message("settings.opencode.yes")
                        else OpencodeFrontendBundle.message("settings.opencode.no")
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

    private fun selectedServer(): McpServerDto? = servers.getOrNull(serverTable.selectedRow)

    private fun saveServer() {
        val name = nameField.text.trim()
        if (name.isBlank()) {
            showStatus(OpencodeFrontendBundle.message("settings.opencode.mcp.name.required"))
            return
        }
        val project = currentProject() ?: return
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
        val project = currentProject() ?: return
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().removeMcpServer(project.projectId(), scopeOf(scopeCombo), server.name)
        }
    }

    private fun saveTimeout() {
        val project = currentProject() ?: return
        val timeout = McpTimeoutDto(
            startup = startupField.text.trim().toLongOrNull(),
            catalog = catalogField.text.trim().toLongOrNull(),
            execution = executionField.text.trim().toLongOrNull()
        )
        val isEmpty = timeout.startup == null && timeout.catalog == null && timeout.execution == null
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().saveMcpTimeout(
                project.projectId(),
                scopeOf(scopeCombo),
                if (isEmpty) null else timeout
            )
        }
    }

    override fun isModified(): Boolean = false
}