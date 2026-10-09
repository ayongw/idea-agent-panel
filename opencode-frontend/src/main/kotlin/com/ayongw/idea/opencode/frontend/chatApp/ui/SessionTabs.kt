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

    /** 已创建的 tab 实例（id -> 组件）：按 id 复用，避免每次刷新重建导致点击被吞 */
    private val tabs = linkedMapOf<String, SessionTabComponent>()

    /** 每个 tab 后的间隔占位，与 [tabs] 一一对应（重排时按序重新挂载） */
    private val tabStruts = linkedMapOf<String, Component>()

    /** 当前 tab 挂载顺序（与 [tabs] 的 key 序一致），用于判断是否需要重排 */
    private var mountedOrder: List<String> = emptyList()

    private var openedSessionIds: List<String> = emptyList()

    /** 空态提示是否已挂载（避免重复 add） */
    private var emptyLabelAdded: Boolean = false

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
     * 刷新 tab：openedIds 为已打开会话顺序，currentSessionId 为当前会话。
     *
     * **差量更新**（不是 removeAll 重建）：
     * `allSessionsFlow` 在每次切换会话时都会发射（后端 switchSession 内会刷新会话列表），
     * 若每次都重建组件，用户按下鼠标到释放之间组件被抽走，点击会被吞掉（表现为"要 点好几次"），
     * 同时整排 tab 闪烁。故：
     * - 标题 / 选中态变化 → 就地更新已有实例（常态，不动组件树）
     * - 开关 tab 导致顺序变化 → 才按序重挂（罕见路径）
     * - 关闭 tab → 仅移除对应实例
     */
    fun update(sessions: List<SessionStateDto>, openedIds: List<String>, currentSessionId: String?) {
        openedSessionIds = openedIds
        val titleById = sessions.associate { it.sessionId to SessionTitles.display(it.title) }

        if (openedIds.isEmpty()) {
            showEmptyState()
            return
        }
        removeEmptyState()

        // 关闭的 tab：移除实例与间隔占位
        (tabs.keys - openedIds.toSet()).forEach { sessionId ->
            tabs.remove(sessionId)?.let { tabStrip.remove(it) }
            tabStruts.remove(sessionId)?.let { tabStrip.remove(it) }
        }

        // 新开的 tab：创建实例 + 间隔占位
        openedIds.filterNot { it in tabs }.forEach { sessionId ->
            tabs[sessionId] = createTab(sessionId, titleById[sessionId] ?: sessionId, sessionId == currentSessionId)
            tabStruts[sessionId] = Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.TINY))
        }

        // 顺序变了才重挂（组件实例复用，不重建）
        if (mountedOrder != openedIds) {
            reorder(openedIds)
            mountedOrder = openedIds
        }

        // 常态路径：只就地改标题与选中态
        openedIds.forEach { sessionId ->
            tabs[sessionId]?.update(titleById[sessionId] ?: sessionId, sessionId == currentSessionId)
        }

        tabStrip.revalidate()
        tabStrip.repaint()
    }

    /** 按目标顺序重挂 tab 与间隔占位（复用已有实例） */
    private fun reorder(orderedIds: List<String>) {
        tabStrip.removeAll()
        orderedIds.forEach { sessionId ->
            tabs[sessionId]?.let { tabStrip.add(it) }
            tabStruts[sessionId]?.let { tabStrip.add(it) }
        }
    }

    /**
     * 无已打开会话：清空已挂载 tab 并显示空态提示。
     *
     * 必须真清：否则「有 tab → 全部关闭」时旧 tab 会留在栏里（守卫只判空态标签，
     * 不判已挂载 tab）。已卸载的组件一并从缓存移除，避免 id 复用时拿到陈旧实例。
     */
    private fun showEmptyState() {
        tabStrip.removeAll()
        tabs.clear()
        tabStruts.clear()
        mountedOrder = emptyList()
        emptyLabelAdded = true
        // 上面已 removeAll，此处无条件添加即恒为 1 个（条件添加会与 removeAll 打架导致标签丢失）
        tabStrip.add(
            JBLabel(message("chat.session.empty")).apply {
                foreground = ChatAppColors.Text.disabled
                border = JBUI.Borders.emptyLeft(JBUI.scale(ChatUIConstants.Spacing.NORMAL))
            }
        )
        tabStrip.revalidate()
        tabStrip.repaint()
    }

    private fun removeEmptyState() {
        if (!emptyLabelAdded) return
        emptyLabelAdded = false
        tabStrip.removeAll()
        tabStrip.revalidate()
    }

    private fun createTab(sessionId: String, title: String, selected: Boolean): SessionTabComponent {
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
class SessionTabComponent(
    private val sessionId: String,
    title: String,
    selected: Boolean,
    private val onSelect: (String) -> Unit,
    private val onClose: (String) -> Unit,
    private val onShowMenu: (Component, String) -> Unit
) : JPanel(BorderLayout(JBUI.scale(ChatUIConstants.Spacing.SMALL), 0)) {

    private val log = Logger.getInstance(SessionTabs::class.java)

    private val titleLabel = JBLabel(title.take(ChatUIConstants.TopBar.TAB_TITLE_MAX_CHARS)).apply {
        font = JBFont.small()
        toolTipText = title
    }

    private val closeButton = ButtonUtils.createActionButton(
        icon = ChatAppIcons.TopBar.closeTab,
        tooltip = OpencodeFrontendBundle.message("chat.topbar.close.tab"),
        size = Dimension(
            JBUI.scale(ChatUIConstants.TopBar.TAB_CLOSE_BUTTON_SIZE),
            JBUI.scale(ChatUIConstants.TopBar.TAB_CLOSE_BUTTON_SIZE)
        ),
        action = { onClose(sessionId) }
    )

    init {
        isOpaque = true
        maximumSize = Dimension(
            JBUI.scale(ChatUIConstants.TopBar.TAB_MAX_WIDTH),
            JBUI.scale(ChatUIConstants.TopBar.TAB_HEIGHT)
        )
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

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

        applyStyle(title, selected)
    }

    /**
     * 就地更新标题与选中态（不重建组件）。
     *
     * 组件被复用是刻意的：切换会话时 `allSessionsFlow` 会发射，若重建组件，
     * 用户按下到释放之间组件被抽离容器，点击会被吞（表现为"要 点好几次"）。
     */
    fun update(title: String, selected: Boolean) {
        val shown = title.take(ChatUIConstants.TopBar.TAB_TITLE_MAX_CHARS)
        if (titleLabel.text != shown) {
            titleLabel.text = shown
            titleLabel.toolTipText = title
        }
        applyStyle(title, selected)
        revalidate()
        repaint()
    }

    /** 选中态底色 / 指示条 / 文字色统一在此收敛 */
    private fun applyStyle(title: String, selected: Boolean) {
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
        titleLabel.foreground = if (selected) ChatAppColors.Text.normal else ChatAppColors.Text.disabled
    }

    /** 测试与诊断用：完整标题（未经截断） */
    val fullTitle: String get() = titleLabel.toolTipText ?: titleLabel.text
}
