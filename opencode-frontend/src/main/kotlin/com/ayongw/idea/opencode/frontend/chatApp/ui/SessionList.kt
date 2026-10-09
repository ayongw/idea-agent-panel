package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ButtonUtils
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppIcons
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.SessionTitles
import com.ayongw.idea.opencode.shared.SessionState
import com.ayongw.idea.opencode.shared.SessionStateDto
import com.ayongw.idea.opencode.shared.toSessionState
import com.intellij.icons.AllIcons
import java.awt.BorderLayout
import java.awt.CardLayout
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

    /** 列表/空状态双视图容器（CardLayout 切换；两者都放 CENTER 会互相覆盖导致列表永远不可见） */
    private val cards = JPanel(CardLayout())

    private val sessions = mutableListOf<SessionState>()
    private var currentSessionId: String? = null

    /** 鼠标悬停的行下标；-1 表示无悬停（决定是否显示行尾删除按钮） */
    private var hoverIndex: Int = -1

    private companion object {
        /** CardLayout 视图标识：会话列表 / 空状态 */
        const val CARD_LIST = "list"
        const val CARD_EMPTY = "empty"
    }

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
            cellRenderer = SessionCellRenderer(
                isHovered = { index -> index == hoverIndex },
                canDelete = ::canDelete
            )
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            fixedCellHeight = JBUI.scale(ChatUIConstants.SessionList.ITEM_HEIGHT)

            addMouseMotionListener(object : MouseAdapter() {
                override fun mouseMoved(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index != hoverIndex) {
                        hoverIndex = index
                        repaint()
                    }
                }

                override fun mouseExited(e: MouseEvent) {
                    if (hoverIndex != -1) {
                        hoverIndex = -1
                        repaint()
                    }
                }
            })

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val index = locationToIndex(e.point)
                    if (index < 0) return

                    // 删除热区优先于切换：点在删除槽上不触发会话切换
                    if (e.clickCount == 1 && isDeleteSlotHit(e.x)) {
                        onDeleteSession(sessions[index].sessionId)
                        return
                    }

                    if (e.button == MouseEvent.BUTTON3) { // Right click
                        setSelectedIndex(index)
                        showContextMenu(index, e.x, e.y)
                    } else {
                        // 单击即切换：此前要求双击，用户表现为「点了没反应」（只会高亮选中）
                        onSessionClick(sessions[index].sessionId)
                    }
                }
            })
        }
        cards.apply {
            layout = CardLayout()
            add(scrollPane, CARD_LIST)
            add(emptyState, CARD_EMPTY)
        }
        add(cards, BorderLayout.CENTER)
        showEmptyState()
    }

    /**
     * 删除热区判定：行尾固定 [ChatUIConstants.SessionList.DELETE_SLOT] 像素。
     *
     * 单元格由 renderer 每次绘制重建，不能持有按钮引用做事件消费（JList 会把点击
     * 判成"选中"），故按固定占位做命中测试。
     */
    private fun isDeleteSlotHit(x: Int): Boolean {
        val slot = JBUI.scale(ChatUIConstants.SessionList.DELETE_SLOT)
        return x >= sessionList.width - sessionList.insets.right - slot
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
                if (index >= 0 && canDelete()) {
                    onDeleteSession(sessions[index].sessionId)
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

        // Delete（策略统一走 canDelete，不再散落 `sessions.size > 1`）
        if (canDelete()) {
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

    /**
     * 是否允许删除会话 —— 唯一的删除策略点（行尾按钮 / 右键菜单 / Delete 键三处共用）。
     *
     * 允许删到只剩 0 个：删除当前会话且没有可切换的会话时，
     * `SessionController.removeTabAndRelocate` 会自动新建一个空会话兜底
     * （与关闭最后一个 tab 的行为一致），面板不会停在空白态。
     * 旧实现两处 `sessions.size > 1` 会留下「最后一个会话删不掉」的死角。
     */
    private fun canDelete(): Boolean = sessions.isNotEmpty()

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
    fun updateSessions(newSessions: List<com.ayongw.idea.opencode.shared.SessionStateDto>, activeSessionId: String?) {
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
        (cards.layout as CardLayout).show(cards, CARD_EMPTY)
        revalidate()
        repaint()
    }

    private fun showListState() {
        (cards.layout as CardLayout).show(cards, CARD_LIST)
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
    val title: String = SessionTitles.display(sessionState.title)
    val preview: String = sessionState.lastUserMessagePreview ?: ""
    val timestamp: String = sessionState.updatedAt.format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}

/**
 * 会话列表单元格渲染器。
 *
 * 行尾固定一个删除槽（[ChatUIConstants.SessionList.DELETE_SLOT]）：hover 该行时显示删除图标。
 * 槽位常驻只画图标与否，几何不随 hover 变化 —— 删除热区因此可由固定像素算出，
 * 无需持有按钮实例（JList 单元格每次绘制都新建，拿不到稳定引用）。
 */
private class SessionCellRenderer(
    /** 该行是否处于鼠标悬停（由列表跟踪 hoverIndex 后回传，便于单测） */
    private val isHovered: (Int) -> Boolean,
    /** 删除策略（列表统一收口，见 [SessionList.canDelete]） */
    private val canDelete: () -> Boolean
) : ListCellRenderer<SessionItem> {

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
            background = if (isSelected) ChatAppColors.Selection.rowHighlight else null
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

        // 右侧信息：时间 + 行尾删除槽
        val rightPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false

            val timeLabel = JBLabel(value.timestamp).apply {
                font = JBFont.small()
                foreground = if (isSelected) JBColor.GRAY.darker() else ChatAppColors.Text.disabled
            }
            add(timeLabel)

            // 删除槽：宽度恒定，hover 才显示图标（与 isDeleteSlotHit 的像素口径一致）
            val slot = JBUI.scale(ChatUIConstants.SessionList.DELETE_SLOT)
            add(JPanel().apply {
                isOpaque = false
                preferredSize = Dimension(slot, slot)
                minimumSize = Dimension(slot, slot)
                maximumSize = Dimension(slot, slot)
                if (isHovered(index) && canDelete()) {
                    toolTipText = OpencodeFrontendBundle.message("chat.session.delete")
                    add(JBLabel(ChatAppIcons.Session.delete).apply {
                        foreground = if (isSelected) JBColor.BLACK else ChatAppColors.Text.disabled
                    })
                }
            })
        }
        panel.add(rightPanel, BorderLayout.EAST)

        return panel
    }
}