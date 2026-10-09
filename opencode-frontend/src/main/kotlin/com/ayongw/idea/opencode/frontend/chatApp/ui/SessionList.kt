package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
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
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
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

    private val sessionList = SessionRowList()
    private val scrollPane = JBScrollPane(sessionList)
    private val emptyState = createEmptyState()

    /** 过滤输入框（顶部） */
    private val filterField = JBTextField().apply {
        emptyText.text = OpencodeFrontendBundle.message("chat.session.filter.placeholder")
    }

    /** 列表/空状态双视图容器（CardLayout 切换；两者都放 CENTER 会互相覆盖导致列表永远不可见） */
    private val cards = JPanel(CardLayout())

    private val sessions = mutableListOf<SessionState>()

    /** 当前会话 id（行左侧标记） */
    private var currentSessionId: String? = null

    /** 已打开（tab 中存在）的会话 id：决定 Active / History 分组 */
    private var openedSessionIds: Set<String> = emptySet()

    /** 过滤文本（标题模糊匹配，大小写不敏感） */
    private var filterText: String = ""

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
        sessionList.model = DefaultListModel<SessionRow>()

        sessionList.apply {
            cellRenderer = SessionRowRenderer(
                isHovered = { i -> i == hoverIndex },
                currentSessionId = { this@SessionList.currentSessionId },
                canDelete = { this@SessionList.canDelete() }
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
                    // 分组标题行不响应点击
                    val row = (model as? DefaultListModel<SessionRow>)?.getElementAt(index) ?: return
                    val item = row as? SessionRow.Item ?: return

                    // 删除热区优先于切换：点在删除槽上不触发会话切换
                    if (e.clickCount == 1 && isDeleteSlotHit(e.x)) {
                        onDeleteSession(item.session.sessionId)
                        return
                    }

                    if (e.button == MouseEvent.BUTTON3) { // Right click
                        setSelectedIndex(index)
                        showContextMenu(index, e.x, e.y)
                    } else {
                        // 单击即切换：此前要求双击，用户表现为「点了没反应」（只会高亮选中）
                        onSessionClick(item.session.sessionId)
                    }
                }
            })
        }

        // 过滤：标题模糊匹配（大小写不敏感），即时过滤
        filterField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent) = onFilterChanged()
            override fun removeUpdate(e: DocumentEvent) = onFilterChanged()
            override fun changedUpdate(e: DocumentEvent) = onFilterChanged()
        })

        cards.apply {
            layout = CardLayout()
            add(scrollPane, CARD_LIST)
            add(emptyState, CARD_EMPTY)
        }
        val listColumn = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                JPanel(BorderLayout()).apply {
                    isOpaque = false
                    add(filterField, BorderLayout.CENTER)
                    border = JBUI.Borders.empty(
                        JBUI.scale(ChatUIConstants.Spacing.SMALL),
                        JBUI.scale(ChatUIConstants.Spacing.NORMAL)
                    )
                },
                BorderLayout.NORTH
            )
            add(cards, BorderLayout.CENTER)
        }
        add(listColumn, BorderLayout.CENTER)
        showEmptyState()
    }

    private fun onFilterChanged() {
        filterText = filterField.text.orEmpty().trim()
        rebuildRows()
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
     * 更新会话列表。
     *
     * @param openedIds 已打开（tab 中存在）的会话 id —— 决定 Active / History 分组
     */
    fun updateSessions(
        newSessions: List<SessionStateDto>,
        activeSessionId: String?,
        openedIds: List<String> = emptyList()
    ) {
        sessions.clear()
        sessions.addAll(newSessions.map { it.toSessionState() })
        currentSessionId = activeSessionId
        openedSessionIds = openedIds.toSet()
        rebuildRows()
    }

    /**
     * 按「过滤 → 已打开/未打开分组 → 排序」重建列表行。
     *
     * - 过滤：标题包含匹配（大小写不敏感），对两组同时生效
     * - 分组：已打开（Active）在上，未打开（History）在下，各自按更新时间倒序
     * - 只有非空分组才输出标题行（过滤后某组为空时不留孤零零的标题）
     */
    private fun rebuildRows() {
        val model = sessionList.model as? DefaultListModel<SessionRow> ?: return
        model.clear()

        val matched = if (filterText.isBlank()) {
            sessions
        } else {
            sessions.filter { SessionTitles.display(it.title).contains(filterText, ignoreCase = true) }
        }

        val (opened, history) = matched.partition { it.sessionId in openedSessionIds }
        appendSection(model, "chat.session.section.active", opened)
        appendSection(model, "chat.session.section.history", history)

        if (model.size() == 0) {
            showEmptyState()
            return
        }
        showListState()
        // 选中当前会话（渲染层另有左侧标记，这里让键盘/滚动定位也一致）
        val currentIndex = rows().indexOfFirst {
            it is SessionRow.Item && it.session.sessionId == currentSessionId
        }
        if (currentIndex >= 0) {
            sessionList.selectedIndex = currentIndex
            sessionList.ensureIndexIsVisible(currentIndex)
        }
    }

    private fun appendSection(
        model: DefaultListModel<SessionRow>,
        titleKey: String,
        items: List<SessionState>
    ) {
        if (items.isEmpty()) return
        model.addElement(SessionRow.Header(OpencodeFrontendBundle.message(titleKey)))
        items.sortedByDescending { it.updatedAt }.forEach {
            model.addElement(SessionRow.Item(it))
        }
    }

    private fun rows(): List<SessionRow> {
        val model = sessionList.model as? DefaultListModel<SessionRow> ?: return emptyList()
        return (0 until model.size()).map { model.getElementAt(it) }
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
 * 列表行：分组标题 或 会话项。
 *
 * 「已打开（Active）」与「未打开（History）」分组展示 —— 两者混排时用户无法一眼看出
 * 哪些会话已经打开过（tab 里已有），点进去才发现是刚打开过的。
 * 分组标题与会话共用**同一个 JList**（保留虚拟化），标题行不可选中。
 */
sealed class SessionRow {
    /** 分组标题（Active Sessions / Session History） */
    data class Header(val title: String) : SessionRow()

    /** 会话条目 */
    data class Item(val session: SessionState) : SessionRow()
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
 * 会话列表本体。
 *
 * **分组标题与会话行共用统一行高**：试过 `BasicListUI.getRowHeight(int)` 做变高行，
 * 实测它只改返回的 height，`getCellBounds` 的 y 偏移仍按统一行高步进
 * （20/50 混排时 y = 0,17,34,51，布局错位），故不可用。
 * 改为统一行高 + 标题行加底部分隔线来表达分组（参考样式里标题行本身也是常规高度）。
 */
private class SessionRowList : JBList<SessionRow>()

/**
 * 会话列表渲染器：分组标题行 / 会话行 / 当前会话左侧标记 / hover 删除按钮。
 *
 * 分组标题与会话共用一个列表（保留虚拟化，见 [SessionRowList]）。
 * 会话行的删除槽宽度恒定（[ChatUIConstants.SessionList.DELETE_SLOT]），
 * 与 [SessionList.isDeleteSlotHit] 的像素口径一致。
 */
private class SessionRowRenderer(
    /** 该行是否处于鼠标悬停 */
    private val isHovered: (Int) -> Boolean,
    /** 当前会话 id（左侧标记） */
    private val currentSessionId: () -> String?,
    /** 删除策略（列表统一收口） */
    private val canDelete: () -> Boolean
) : ListCellRenderer<SessionRow> {

    override fun getListCellRendererComponent(
        list: JList<out SessionRow>?,
        value: SessionRow?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component = when (value) {
        null -> JPanel()
        is SessionRow.Header -> headerRow(value.title)
        is SessionRow.Item -> sessionRow(value, index, isSelected)
    }

    /** 分组标题：加粗小字 + 底部分隔线（统一行高下靠分隔线读作分组，不可选中） */
    private fun headerRow(title: String): Component =
        JBLabel(title).apply {
            font = JBFont.medium().asBold()
            foreground = ChatAppColors.Text.disabled
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, ChatAppColors.Divider.line),
                JBUI.Borders.empty(0, JBUI.scale(ChatUIConstants.Spacing.LARGE))
            )
            verticalAlignment = SwingConstants.CENTER
            alignmentX = Component.LEFT_ALIGNMENT
        }

    private fun sessionRow(row: SessionRow.Item, index: Int, isSelected: Boolean): Component {
        val value = SessionItem(row.session)
        val isCurrent = value.sessionId == currentSessionId()

        val panel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = when {
                isCurrent -> ChatAppColors.Selection.currentRowHighlight
                isSelected -> ChatAppColors.Selection.rowHighlight
                else -> ChatAppColors.Panel.background
            }
            // 当前会话用左侧竖条标记：选中高亮会随鼠标移动，竖条才能稳定指明"当前会话"
            border = if (isCurrent) {
                BorderFactory.createMatteBorder(0, JBUI.scale(2), 0, 0, ChatAppColors.Tab.selectedIndicator)
            } else {
                JBUI.Borders.empty(0, JBUI.scale(2), 0, 0)
            }
            add(mainContent(value, isSelected, isCurrent), BorderLayout.CENTER)
            add(rightContent(value, index, isSelected), BorderLayout.EAST)
        }
        return panel
    }

    /**
     * 标题（**单行**）。
     *
     * 曾用「标题 + 预览」两行竖排，导致标题贴行顶、时间（EAST 侧垂直居中）落到下方，
     * 看起来像两行。现改为标题与时间同行：标题在 CENTER、时间在 EAST，
     * `JBLabel` 默认不换行，标题过长时**在边界处被裁切**（不换行、不撑高）。
     */
    private fun mainContent(value: SessionItem, isSelected: Boolean, isCurrent: Boolean): Component =
        JBLabel(value.title).apply {
            font = JBFont.medium()
            foreground = if (isSelected || isCurrent) JBColor.BLACK else ChatAppColors.Text.normal
            border = JBUI.Borders.empty(0, JBUI.scale(ChatUIConstants.Spacing.NORMAL))
            // 与右侧时间/删除槽垂直居中对齐，保证同一基线
            alignmentY = Component.CENTER_ALIGNMENT
            toolTipText = value.title
        }

    /** 时间 + 行尾删除槽（hover 才显示图标）；与标题同一基线 */
    private fun rightContent(value: SessionItem, index: Int, isSelected: Boolean): Component =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentY = Component.CENTER_ALIGNMENT

            add(
                JBLabel(value.timestamp).apply {
                    font = JBFont.small()
                    foreground = if (isSelected) JBColor.GRAY.darker() else ChatAppColors.Text.disabled
                }
            )

            val slot = JBUI.scale(ChatUIConstants.SessionList.DELETE_SLOT)
            add(
                JPanel().apply {
                    isOpaque = false
                    preferredSize = Dimension(slot, slot)
                    minimumSize = Dimension(slot, slot)
                    maximumSize = Dimension(slot, slot)
                    if (isHovered(index) && canDelete()) {
                        toolTipText = OpencodeFrontendBundle.message("chat.session.delete")
                        add(
                            JBLabel(ChatAppIcons.Session.delete).apply {
                                foreground = if (isSelected) JBColor.BLACK else ChatAppColors.Text.disabled
                            }
                        )
                    }
                }
            )
        }

    private companion object {
        /** 预览最大字符数 */
        const val PREVIEW_MAX_CHARS = 60
    }
}
