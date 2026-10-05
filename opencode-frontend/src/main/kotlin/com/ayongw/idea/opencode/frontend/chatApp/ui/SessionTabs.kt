package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ButtonUtils
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppIcons
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.SessionTitles
import com.ayongw.idea.opencode.shared.SessionStateDto
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane

/**
 * 顶部会话 tab 栏
 *
 * 默认展示「已打开的会话」，关闭 tab 后可从右侧「全部会话」按钮查看当前工作区内的所有会话。
 * 右侧同时提供 新建会话 / 设置 入口。
 */
class SessionTabs(
    private val onSelect: (String) -> Unit,
    private val onClose: (String) -> Unit,
    private val onNewSession: () -> Unit,
    private val onShowAllSessions: (Component) -> Unit,
    private val onOpenSettings: () -> Unit
) : JPanel(BorderLayout()) {

    private val tabStrip = JPanel()
    private var openedSessionIds: List<String> = emptyList()
    private val log = Logger.getInstance(SessionTabs::class.java)

    init {
        setupAppearance()

        tabStrip.apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
        }

        add(createTabScrollPane(), BorderLayout.CENTER)
        add(createActionBar(), BorderLayout.EAST)
    }

    private fun setupAppearance() {
        background = ChatAppColors.Panel.background
        border = JBUI.Borders.customLine(
            JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
            0, 0, 1, 0
        )
    }

    private fun createTabScrollPane() = JBScrollPane(tabStrip).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        preferredSize = Dimension(0, JBUI.scale(ChatUIConstants.TopBar.TAB_HEIGHT + 8))
        minimumSize = Dimension(0, JBUI.scale(ChatUIConstants.TopBar.TAB_HEIGHT + 8))
    }

    private fun createActionBar(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyRight(JBUI.scale(ChatUIConstants.Spacing.NORMAL))
        }

        val newSessionButton = ButtonUtils.createActionButton(
            icon = ChatAppIcons.TopBar.newSession,
            tooltip = message("chat.topbar.new.session"),
            size = ChatUIConstants.Button.ACTION_BUTTON_SIZE,
            action = onNewSession
        )

        val allSessionsButton = ButtonUtils.createActionButton(
            icon = ChatAppIcons.TopBar.allSessions,
            tooltip = message("chat.topbar.all.sessions.tooltip"),
            size = ChatUIConstants.Button.ACTION_BUTTON_SIZE,
            action = {}
        ).apply { addActionListener { onShowAllSessions(this) } }

        val settingsButton = ButtonUtils.createActionButton(
            icon = ChatAppIcons.TopBar.settings,
            tooltip = message("chat.topbar.settings"),
            size = ChatUIConstants.Button.ACTION_BUTTON_SIZE,
            action = onOpenSettings
        )

        listOf(newSessionButton, allSessionsButton, settingsButton).forEachIndexed { index, button ->
            if (index > 0) {
                panel.add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            }
            panel.add(button)
        }
        return panel
    }

    /**
     * 刷新 tab：openedIds 为已打开会话顺序，currentSessionId 为当前会话
     */
    fun update(sessions: List<SessionStateDto>, openedIds: List<String>, currentSessionId: String?) {
        openedSessionIds = openedIds
        val titleById = sessions.associate { it.sessionId to SessionTitles.display(it.title) }

        tabStrip.removeAll()
        if (openedIds.isEmpty()) {
            tabStrip.add(
                JBLabel(message("chat.session.empty")).apply {
                    foreground = ChatAppColors.Text.disabled
                    border = JBUI.Borders.emptyLeft(JBUI.scale(ChatUIConstants.Spacing.NORMAL))
                }
            )
        } else {
            openedIds.forEach { sessionId ->
                tabStrip.add(createTab(sessionId, titleById[sessionId] ?: sessionId, sessionId == currentSessionId))
                tabStrip.add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.TINY)))
            }
        }
        tabStrip.revalidate()
        tabStrip.repaint()
    }

    private fun createTab(sessionId: String, title: String, selected: Boolean): JComponent {
        return SessionTabComponent(
            sessionId = sessionId,
            title = title,
            selected = selected,
            onSelect = onSelect,
            onClose = onClose,
            onShowMenu = { anchor, id -> showTabMenu(anchor, id) }
        )
    }

    private fun showTabMenu(anchor: Component, sessionId: String) {
        val menu = JPopupMenu()
        menu.add(object : AbstractAction(message("chat.topbar.close.tab")) {
            override fun actionPerformed(e: ActionEvent?) = onClose(sessionId)
        })

        val others = openedSessionIds.filter { it != sessionId }
        if (others.isNotEmpty()) {
            menu.add(object : AbstractAction(message("chat.topbar.close.other.tabs")) {
                override fun actionPerformed(e: ActionEvent?) = others.forEach { onClose(it) }
            })
        }
        menu.show(anchor, 0, anchor.height)
    }

    private fun message(key: String): String = OpencodeFrontendBundle.message(key)
}

/**
 * 单个会话 tab：点击选中、X 关闭、右键菜单
 */
private class SessionTabComponent(
    private val sessionId: String,
    title: String,
    selected: Boolean,
    private val onSelect: (String) -> Unit,
    private val onClose: (String) -> Unit,
    private val onShowMenu: (Component, String) -> Unit
) : JPanel(BorderLayout(JBUI.scale(ChatUIConstants.Spacing.SMALL), 0)) {

    private val log = Logger.getInstance(SessionTabs::class.java)

    init {
        isOpaque = true
        background = if (selected) ChatAppColors.Tab.selectedBackground else ChatAppColors.Panel.background
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(
                0, 0, ChatUIConstants.TopBar.TAB_INDICATOR_THICKNESS, 0,
                if (selected) ChatAppColors.Tab.selectedIndicator else ChatAppColors.Panel.background
            ),
            JBUI.Borders.empty(
                JBUI.scale(ChatUIConstants.Spacing.SMALL),
                JBUI.scale(ChatUIConstants.Spacing.NORMAL)
            )
        )
        maximumSize = Dimension(
            JBUI.scale(ChatUIConstants.TopBar.TAB_MAX_WIDTH),
            JBUI.scale(ChatUIConstants.TopBar.TAB_HEIGHT)
        )
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        val titleLabel = JBLabel(title.take(ChatUIConstants.TopBar.TAB_TITLE_MAX_CHARS)).apply {
            font = JBFont.small()
            foreground = if (selected) ChatAppColors.Text.normal else ChatAppColors.Text.disabled
            toolTipText = title
        }

        val closeButton = ButtonUtils.createActionButton(
            icon = ChatAppIcons.TopBar.closeTab,
            tooltip = OpencodeFrontendBundle.message("chat.topbar.close.tab"),
            size = Dimension(
                JBUI.scale(ChatUIConstants.TopBar.TAB_CLOSE_BUTTON_SIZE),
                JBUI.scale(ChatUIConstants.TopBar.TAB_CLOSE_BUTTON_SIZE)
            ),
            action = { onClose(sessionId) }
        )

        add(titleLabel, BorderLayout.CENTER)
        add(closeButton, BorderLayout.EAST)

        addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                // 事件层埋点：tab 点击无反应时用于区分「事件未到达」与「切换逻辑短路」
                log.info("tab mousePressed session=$sessionId button=${e.button}")
                if (e.isPopupTrigger || e.button == MouseEvent.BUTTON3) {
                    onShowMenu(this@SessionTabComponent, sessionId)
                } else {
                    onSelect(sessionId)
                }
            }
        })
    }
}