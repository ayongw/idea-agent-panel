package com.ayongw.plugins.opencode.frontend.chatApp.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.ayongw.plugins.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ButtonUtils
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ChatAppIcons
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.plugins.opencode.shared.SessionState
import com.ayongw.plugins.opencode.shared.SessionStateDto
import com.ayongw.plugins.opencode.shared.toSessionState
import com.intellij.icons.AllIcons
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.time.format.DateTimeFormatter
import java.util.Collections
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.JList
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JTextField
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.border.EmptyBorder

/**
 * 会话列表组件 - 左侧边栏
 */
class SessionList(
    private val project: Project,
    private val onSessionClick: (String) -> Unit,           // sessionId -> 切换会话
    private val onNewSession: () -> Unit,                   // 创建新会话
    private val onRenameSession: (String, String) -> Unit,  // (sessionId, newTitle) -> 重命名
    private val onDeleteSession: (String) -> Unit,          // sessionId -> 删除
) : JPanel() {

    private val sessionList = JBList<SessionItem>()
    private val scrollPane = JBScrollPane(sessionList)
    private val emptyState = createEmptyState()

    private val sessions = mutableListOf<SessionState>()
    private var currentSessionId: String? = null

    init {
        setupAppearance()
        setupList()
        setupKeyBindings()
    }

    private fun setupAppearance() {
        layout = BorderLayout()
        background = ChatAppColors.Panel.background
        border = JBUI.Borders.customLine(
            JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
            0, 1, 0, 0
        )
        preferredSize = Dimension(JBUI.scale(ChatUIConstants.SessionList.PREF_WIDTH), 400)
        minimumSize = Dimension(JBUI.scale(ChatUIConstants.SessionList.MIN_WIDTH), 0)
    }

    private fun createEmptyState(): JPanel {
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(JBUI.scale(ChatUIConstants.Spacing.XLARGE))

            val iconLabel = JBLabel(ChatAppIcons.Header.search).apply {
                alignmentX = Component.CENTER_ALIGNMENT
                border = JBUI.Borders.emptyBottom(JBUI.scale(ChatUIConstants.Spacing.MEDIUM))
            }
            add(iconLabel)

            val textLabel = JBLabel(OpencodeFrontendBundle.message("chat.session.empty")).apply {
                foreground = ChatAppColors.Text.disabled
                font = JBFont.regular()
                alignmentX = Component.CENTER_ALIGNMENT
                border = JBUI.Borders.emptyBottom(JBUI.scale(ChatUIConstants.Spacing.NORMAL))
            }
            add(textLabel)

            val newBtn = ButtonUtils.createActionButton(
                icon = AllIcons.General.Add,
                tooltip = OpencodeFrontendBundle.message("chat.session.new.tooltip"),
                size = ChatUIConstants.Button.LARGE_ACTION_BUTTON_SIZE,
                action = onNewSession
            )
            newBtn.alignmentX = Component.CENTER_ALIGNMENT
            add(newBtn)
        }
    }

    private fun setupList() {
        val listModel = DefaultListModel<SessionItem>()
        sessionList.model = listModel

        sessionList.apply {
            cellRenderer = SessionCellRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(ChatUIConstants.SessionList.ITEM_HEIGHT)
            isVisible = false

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount == 2) {
                        val index = locationToIndex(e.point)
                        if (index >= 0) {
                            val session = sessions[index]
                            onSessionClick(session.sessionId)
                        }
                    } else if (e.button == MouseEvent.BUTTON3) { // Right click
                        val index = locationToIndex(e.point)
                        if (index >= 0) {
                            setSelectedIndex(index)
                            showContextMenu(index, e.x, e.y)
                        }
                    }
                }
            })
        }
        add(scrollPane, BorderLayout.CENTER)
        add(emptyState, BorderLayout.CENTER)
        showEmptyState()
    }

    private fun setupKeyBindings() {
        val inputMap = sessionList.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = sessionList.actionMap

        // Enter to select
        inputMap.put(KeyStroke.getKeyStroke("ENTER"), "selectSession")
        actionMap.put("selectSession", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                val index = sessionList.selectedIndex
                if (index >= 0) {
                    onSessionClick(sessions[index].sessionId)
                }
            }
        })

        // Delete key
        inputMap.put(KeyStroke.getKeyStroke("DELETE"), "deleteSession")
        actionMap.put("deleteSession", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                val index = sessionList.selectedIndex
                if (index >= 0) {
                    val session = sessions[index]
                    if (sessions.size > 1) { // Don't delete last session
                        onDeleteSession(session.sessionId)
                    }
                }
            }
        })

        // N for new session
        inputMap.put(KeyStroke.getKeyStroke("N"), "newSession")
        actionMap.put("newSession", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                onNewSession()
            }
        })
    }

    private fun showContextMenu(index: Int, x: Int, y: Int) {
        val session = sessions[index]
        val menu = JPopupMenu()

        // Rename
        val renameAction = object : AbstractAction(OpencodeFrontendBundle.message("chat.session.rename")) {
            override fun actionPerformed(e: ActionEvent?) {
                showRenameDialog(session)
            }
        }
        renameAction.putValue(AbstractAction.SMALL_ICON, ChatAppIcons.Session.rename)
        menu.add(renameAction)

        // Delete (if not last)
        if (sessions.size > 1) {
            val deleteAction = object : AbstractAction(OpencodeFrontendBundle.message("chat.session.delete")) {
                override fun actionPerformed(e: ActionEvent?) {
                    onDeleteSession(session.sessionId)
                }
            }
            deleteAction.putValue(AbstractAction.SMALL_ICON, ChatAppIcons.Session.delete)
            menu.add(deleteAction)
        }

        // Duplicate
        val duplicateAction = object : AbstractAction(OpencodeFrontendBundle.message("chat.session.duplicate")) {
            override fun actionPerformed(e: ActionEvent?) {
                // TODO: 实现复制会话
            }
        }
        duplicateAction.putValue(AbstractAction.SMALL_ICON, ChatAppIcons.Session.duplicate)
        menu.add(duplicateAction)

        menu.show(sessionList, x, y)
    }

    private fun showRenameDialog(session: SessionState) {
        val textField = JTextField(session.title).apply {
            selectAll()
            columns = 30
        }

        val dialog = object : DialogWrapper(true) {
            init {
                title = OpencodeFrontendBundle.message("chat.session.rename.dialog.title")
                setOKButtonText(OpencodeFrontendBundle.message("chat.session.rename"))
                setCancelButtonText(OpencodeFrontendBundle.message("common.cancel"))
                init()
            }

            override fun createCenterPanel(): JComponent {
                return textField
            }

            override fun doOKAction() {
                val newTitle = textField.text.trim()
                if (newTitle.isNotBlank() && newTitle != session.title) {
                    onRenameSession(session.sessionId, newTitle)
                }
                close(DialogWrapper.OK_EXIT_CODE)
            }
        }

        dialog.show()
    }

    /**
     * 更新会话列表
     */
    fun updateSessions(newSessions: List<com.ayongw.plugins.opencode.shared.SessionStateDto>, activeSessionId: String?) {
        sessions.clear()
        sessions.addAll(newSessions.map { it.toSessionState() })
        currentSessionId = activeSessionId

        val listModel = sessionList.model as DefaultListModel<SessionItem>
        listModel.clear()
        newSessions.forEach { listModel.addElement(SessionItem(it.toSessionState())) }

        if (sessions.isEmpty()) {
            showEmptyState()
        } else {
            showListState()
            // 选中当前会话
            val activeIndex = sessions.indexOfFirst { it.sessionId == activeSessionId }
            if (activeIndex >= 0) {
                sessionList.selectedIndex = activeIndex
                sessionList.ensureIndexIsVisible(activeIndex)
            }
        }
    }

    private fun showEmptyState() {
        sessionList.isVisible = false
        emptyState.isVisible = true
        revalidate()
        repaint()
    }

    private fun showListState() {
        sessionList.isVisible = true
        emptyState.isVisible = false
        revalidate()
        repaint()
    }

    /**
     * 获取当前选中的会话 ID
     */
    val selectedSessionId: String?
        get() {
            val index = sessionList.selectedIndex
            return if (index >= 0 && index < sessions.size) sessions[index].sessionId else null
        }
}

/**
 * 会话列表项数据类
 */
data class SessionItem(
    val sessionState: SessionState
) {
    val sessionId: String = sessionState.sessionId
    val title: String = sessionState.title
    val preview: String = sessionState.lastUserMessagePreview ?: sessionState.parts.lastOrNull()?.content ?: ""
    val timestamp: String = sessionState.updatedAt.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
    val messageCount: Int = sessionState.messageCount
    val isActive: Boolean = sessionState.hasStreamingPart
}

/**
 * 会话列表单元格渲染器
 */
private class SessionCellRenderer : ListCellRenderer<SessionItem> {

    override fun getListCellRendererComponent(
        list: JList<out SessionItem>?,
        value: SessionItem?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        if (value == null) return JPanel()

        val panel = JPanel(BorderLayout()).apply {
            isOpaque = isSelected
            background = if (isSelected) JBColor(Color(200, 220, 255), Color(40, 60, 90)) else null
            border = JBUI.Borders.empty(JBUI.scale(ChatUIConstants.Spacing.NORMAL), JBUI.scale(ChatUIConstants.Spacing.XLARGE))
        }

        // 主内容区
        val mainPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false

            val titleLabel = JBLabel(value.title).apply {
                font = JBFont.medium().asBold()
                foreground = if (isSelected) JBColor.BLACK else ChatAppColors.Text.normal
            }
            add(titleLabel)

            add(Box.createVerticalStrut(JBUI.scale(2)))

            val previewLabel = JBLabel(value.preview.take(60)).apply {
                font = JBFont.small()
                foreground = if (isSelected) JBColor.GRAY.darker() else ChatAppColors.Text.disabled
            }
            add(previewLabel)
        }
        panel.add(mainPanel, BorderLayout.CENTER)

        // 右侧信息
        val rightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false

            val timeLabel = JBLabel(value.timestamp).apply {
                font = JBFont.small()
                foreground = if (isSelected) JBColor.GRAY.darker() else ChatAppColors.Text.disabled
                alignmentX = Component.RIGHT_ALIGNMENT
            }
            add(timeLabel)

            if (value.isActive) {
                add(Box.createVerticalStrut(JBUI.scale(4)))
                val activeBadge = JBLabel("●").apply {
                    font = JBFont.small()
                    foreground = ChatAppColors.MessageBubble.myBackgroundBorder
                    alignmentX = Component.RIGHT_ALIGNMENT
                }
                add(activeBadge)
            }
        }
        panel.add(rightPanel, BorderLayout.EAST)

        return panel
    }
}