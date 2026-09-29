package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.McpServerDto
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.intellij.platform.project.projectId
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * MCP 面板
 *
 * 只展示当前加载的 MCP 服务器：名称 + 状态 + 启用开关（写 `mcp.servers.<name>.disabled`）+ 齿轮打开配置文件。
 * 增删改一律直接编辑配置文件——MCP 的 PUT/DELETE 接口只做运行时 override，不落盘。
 */
internal class McpSettingsTab : AbstractSettingsTab() {

    override val title: String = OpencodeFrontendBundle.message("settings.opencode.tab.mcp")

    private val sourceLabel = JBLabel(" ")
    private val loadedLabel = JBLabel(" ").apply { font = JBUI.Fonts.smallFont() }
    private val cards = SettingsCardList(OpencodeFrontendBundle.message("settings.opencode.filter"))

    private var globalConfigPath: String = ""
    private var projectConfigPath: String = ""

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        val sourceBlock = JPanel(BorderLayout()).apply {
            add(sourceLabel, BorderLayout.WEST)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    add(
                        JButton(OpencodeFrontendBundle.message("settings.opencode.config.open")).apply {
                            addActionListener { openConfigFile(globalConfigPath, ConfigScopeDto.GLOBAL) }
                        }
                    )
                },
                BorderLayout.EAST
            )
        }

        val loadedBlock = JPanel(BorderLayout()).apply {
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    add(
                        JBLabel(OpencodeFrontendBundle.message("settings.opencode.mcp.list")).apply {
                            font = JBUI.Fonts.label().asBold()
                        }
                    )
                    add(loadedLabel)
                },
                BorderLayout.NORTH
            )
            add(cards, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            add(
                buildHeader(title, OpencodeFrontendBundle.message("settings.opencode.mcp.hint")) { reload() },
                BorderLayout.NORTH
            )
            add(
                JPanel(BorderLayout()).apply {
                    add(sourceBlock, BorderLayout.NORTH)
                    add(loadedBlock, BorderLayout.CENTER)
                },
                BorderLayout.CENTER
            )
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    override fun reload() {
        loadSnapshot { snapshot ->
            globalConfigPath = snapshot.globalConfigPath
            projectConfigPath = snapshot.projectConfigPath
            sourceLabel.text = wrappedHintHtml(
                OpencodeFrontendBundle.message(
                    "settings.opencode.mcp.source",
                    snapshot.globalConfigPath,
                    snapshot.projectConfigPath
                )
            )

            cards.setCards(snapshot.mcpServers.map { serverCard(it) })
            loadedLabel.text = OpencodeFrontendBundle.message("settings.opencode.count", snapshot.mcpServers.size)
        }
    }

    private fun serverCard(server: McpServerDto): SettingsCard = SettingsCard(title = server.name)
        .withAction(statusBadge(server))
        .withAction(
            JBCheckBox("", server.enabled).apply {
                toolTipText = OpencodeFrontendBundle.message("settings.opencode.mcp.toggle")
                addActionListener { toggle(server, isSelected) }
            }
        )
        .withAction(
            buildGearButton(OpencodeFrontendBundle.message("settings.opencode.config.open")) {
                val scope = server.scope ?: ConfigScopeDto.GLOBAL
                openConfigFile(
                    if (scope == ConfigScopeDto.PROJECT) projectConfigPath else globalConfigPath,
                    scope
                )
            }
        )

    private fun statusBadge(server: McpServerDto): JComponent {
        val status = server.status?.takeIf { it.isNotBlank() } ?: if (server.enabled) "pending" else "disabled"
        val (key, color) = when (status) {
            "connected" -> "settings.opencode.mcp.status.connected" to CONNECTED
            "failed" -> "settings.opencode.mcp.status.failed" to FAILED
            "needs_auth" -> "settings.opencode.mcp.status.needs.auth" to PENDING
            "pending" -> "settings.opencode.mcp.status.pending" to PENDING
            else -> "settings.opencode.mcp.status.disabled" to UIUtil.getContextHelpForeground()
        }
        return JBLabel(OpencodeFrontendBundle.message(key)).apply {
            font = JBUI.Fonts.smallFont()
            foreground = color
            toolTipText = server.statusError
        }
    }

    /** 启用/禁用：写 `mcp.servers.<name>.disabled`（仅配置里声明过的服务器可改） */
    private fun toggle(server: McpServerDto, enabled: Boolean) {
        val project = currentProject() ?: return
        val scope = server.scope
        if (scope == null) {
            showStatus(OpencodeFrontendBundle.message("settings.opencode.mcp.not.in.config"))
            return
        }
        showStatus(OpencodeFrontendBundle.message("settings.opencode.saving"))
        // 勾选已即时改变界面状态，失败时同样刷新回真实状态
        runAsync(
            { result ->
                if (result == null || !result.ok) showStatus(friendlyError(result?.message), result?.message)
                reload()
            }
        ) {
            SettingsRpcApi.getInstance().saveMcpServer(
                project.projectId(),
                scope,
                McpServerDto(
                    name = server.name,
                    type = server.type,
                    enabled = enabled,
                    command = server.command,
                    url = server.url,
                    environment = server.environment
                )
            )
        }
    }

    override fun isModified(): Boolean = false

    private companion object {
        val CONNECTED: Color = JBColor(Color(0x2E7D32), Color(0x81C784))
        val FAILED: Color = JBColor(Color(0xC62828), Color(0xEF9A9A))
        val PENDING: Color = JBColor(Color(0xE65100), Color(0xFFB74D))
    }
}