package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ButtonUtils
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.McpServerDto
import com.ayongw.idea.opencode.shared.McpToolDto
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Rectangle
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable
import javax.swing.ScrollPaneConstants

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

    private fun serverCard(server: McpServerDto): SettingsCard {
        val tools = ToolListPanel()
        val badge = JBLabel(statusText(server)).apply {
            font = JBUI.Fonts.smallFont()
            foreground = statusColor(server)
            toolTipText = server.statusError
        }
        return SettingsCard(title = server.name)
            .withAction(badge)
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
            // 展开时才拉取工具清单（不依赖 opencode 侧的启用开关）
            .withExpandable(tools.component) { loadTools(server, tools, badge) }
    }

    /** 展开时拉取工具清单：结果渲染进展开区，成功后徽章追加工具数 */
    private fun loadTools(server: McpServerDto, panel: ToolListPanel, badge: JBLabel) {
        val project = currentProject()
        if (project == null) {
            panel.showFailure(OpencodeFrontendBundle.message("settings.opencode.no.project")) {
                loadTools(server, panel, badge)
            }
            return
        }
        panel.showLoading()
        runAsync(
            { result ->
                if (result == null || result.error != null) {
                    panel.showFailure(
                        result?.error ?: OpencodeFrontendBundle.message("settings.opencode.error.unknown")
                    ) { loadTools(server, panel, badge) }
                } else {
                    panel.showTools(result.tools, result.note)
                    badge.text = withToolCount(statusText(server), result.tools.size)
                }
            }
        ) { SettingsRpcApi.getInstance().listMcpTools(project.projectId(), server.name) }
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

    /** 状态徽章文案：取 opencode 侧状态，缺失时按启用开关推断 */
    private fun statusText(server: McpServerDto): String = OpencodeFrontendBundle.message(statusKey(server))

    private fun statusColor(server: McpServerDto): Color = when (statusKey(server)) {
        "settings.opencode.mcp.status.connected" -> SettingsColors.Status.connected
        "settings.opencode.mcp.status.failed" -> SettingsColors.Status.failed
        "settings.opencode.mcp.status.needs.auth" -> SettingsColors.Status.pending
        "settings.opencode.mcp.status.pending" -> SettingsColors.Status.pending
        else -> UIUtil.getContextHelpForeground()
    }

    private fun statusKey(server: McpServerDto): String {
        val status = server.status?.takeIf { it.isNotBlank() } ?: if (server.enabled) "pending" else "disabled"
        return when (status) {
            "connected" -> "settings.opencode.mcp.status.connected"
            "failed" -> "settings.opencode.mcp.status.failed"
            "needs_auth" -> "settings.opencode.mcp.status.needs.auth"
            "pending" -> "settings.opencode.mcp.status.pending"
            else -> "settings.opencode.mcp.status.disabled"
        }
    }

    /** 徽章追加工具数：`connected (1 tool)` */
    private fun withToolCount(status: String, count: Int): String = OpencodeFrontendBundle.message(
        if (count == 1) "settings.opencode.mcp.badge.tool" else "settings.opencode.mcp.badge.tools",
        status,
        count
    )

    /**
     * 展开区内的工具清单，四态：加载中 / 失败+重试 / 空 / 清单。
     *
     * 清单过长时在展开区内部滚动（高度上限 [MAX_TOOLS_HEIGHT]），不撑高整个设置页。
     */
    private class ToolListPanel {

        private val rows = object : JPanel(), Scrollable {
            init {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

            override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

            override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
                JBUI.scale(16)

            override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
                JBUI.scale(64)

            override fun getScrollableTracksViewportWidth(): Boolean = true

            override fun getScrollableTracksViewportHeight(): Boolean = false
        }

        private val scroll = object : JBScrollPane(rows) {
            /** 高度收口：内容再多也不超过 [MAX_TOOLS_HEIGHT]（超出由滚动条承载） */
            override fun getPreferredSize(): Dimension {
                val size = super.getPreferredSize()
                return Dimension(size.width, minOf(size.height, JBUI.scale(MAX_TOOLS_HEIGHT)))
            }
        }.apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        val component: JComponent = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(scroll, BorderLayout.CENTER)
        }

        fun showLoading() = render(hint(OpencodeFrontendBundle.message("settings.opencode.mcp.tools.loading")))

        fun showEmpty() = render(hint(OpencodeFrontendBundle.message("settings.opencode.mcp.tools.empty")))

        fun showFailure(message: String, onRetry: () -> Unit) = render(
            hint(OpencodeFrontendBundle.message("settings.opencode.mcp.tools.failed", message)),
            retryLink(onRetry)
        )

        fun showTools(tools: List<McpToolDto>, note: String?) {
            if (tools.isEmpty()) {
                showEmpty()
                return
            }
            val components = tools.map { toolRow(it) }.toMutableList<JComponent>()
            note?.takeIf { it.isNotBlank() }?.let { components.add(hint(it)) }
            render(*components.toTypedArray())
        }

        private fun render(vararg components: JComponent) {
            rows.removeAll()
            components.forEach { rows.add(it) }
            rows.revalidate()
            rows.repaint()
            component.revalidate()
            component.repaint()
        }

        /** 一行工具：左侧等宽加粗工具名 + 右侧描述（单行截断，tooltip 显示全文） */
        private fun toolRow(tool: McpToolDto): JComponent = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(2)
            add(
                JBLabel(tool.name).apply {
                    font = Font(Font.MONOSPACED, Font.BOLD, JBUI.Fonts.smallFont().size)
                },
                BorderLayout.WEST
            )
            val description = tool.description.orEmpty()
            if (description.isNotBlank()) {
                add(
                    JBLabel(truncate(description, TOOL_DESC_MAX_CHARS)).apply {
                        font = JBUI.Fonts.smallFont()
                        foreground = UIUtil.getContextHelpForeground()
                        toolTipText = description
                        border = JBUI.Borders.emptyLeft(8)
                    },
                    BorderLayout.CENTER
                )
            }
        }

        private fun hint(text: String): JBLabel = JBLabel(text).apply {
            font = JBUI.Fonts.smallFont()
            foreground = UIUtil.getContextHelpForeground()
            alignmentX = Component.LEFT_ALIGNMENT
        }

        /** 重试小链接：失败态下再点一次即重新拉取 */
        private fun retryLink(onRetry: () -> Unit): JComponent =
            ButtonUtils.ToolbarButton(text = OpencodeFrontendBundle.message("settings.opencode.mcp.tools.retry")).apply {
                font = JBUI.Fonts.smallFont()
                foreground = SettingsColors.link
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = Component.LEFT_ALIGNMENT
                addActionListener { onRetry() }
            }
    }

    private companion object {
        /** 展开区滚动高度上限（逻辑像素） */
        const val MAX_TOOLS_HEIGHT = 160

        /** 工具描述单行截断长度（字符数），完整内容放 tooltip */
        const val TOOL_DESC_MAX_CHARS = 90
    }
}