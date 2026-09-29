package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.MentionSupport
import java.awt.Component
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import java.awt.BorderLayout

/**
 * 输入框 mention 候选弹窗（`/` 命令与技能、`#` 文件与目录）。
 *
 * 键盘操作由输入框的 action 转发（`↑/↓` 移动、`Enter`/`Tab` 确认、`Esc` 关闭），
 * 弹窗本身不抢焦点，避免打断连续输入。
 */
class MentionPopup {

    /** 列表行：分组标题或可选项 */
    sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val candidate: MentionSupport.Candidate) : Row()
    }

    private val model = DefaultListModel<Row>()
    private val list = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RowRenderer()
        visibleRowCount = VISIBLE_ROWS
    }
    private val scrollPane = JBScrollPane(list).apply {
        border = JBUI.Borders.empty()
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
    }
    private val content = JPanel(BorderLayout()).apply {
        add(scrollPane, BorderLayout.CENTER)
    }
    private var popup: JBPopup? = null

    /** 当前弹窗是否可见 */
    val isVisible: Boolean get() = popup?.isVisible == true

    /** 展示候选（按分组组织为带标题的行） */
    fun show(anchor: Component, candidates: List<MentionSupport.Candidate>) {
        update(candidates)
        if (model.isEmpty) {
            hide()
            return
        }
        if (popup == null) {
            popup = JBPopupFactory.getInstance()
                .createComponentPopupBuilder(content, null)
                .setRequestFocus(false)
                .setCancelOnClickOutside(false)
                .setCancelKeyEnabled(false)
                .setResizable(false)
                .setMovable(false)
                .createPopup()
            popup?.showUnderneathOf(anchor)
        } else if (popup?.isVisible != true) {
            popup?.showUnderneathOf(anchor)
        }
    }

    /** 刷新候选内容（保持弹窗可见并重置选中项） */
    fun update(candidates: List<MentionSupport.Candidate>) {
        model.clear()
        candidates.groupBy { it.group }.forEach { (group, items) ->
            model.addElement(Row.Header(group))
            items.forEach { model.addElement(Row.Item(it)) }
        }
        selectFirstItem()
    }

    fun hide() {
        popup?.cancel()
        popup = null
    }

    /** 上下移动选中项（跳过分组标题） */
    fun moveSelection(delta: Int) {
        if (model.isEmpty) return
        var index = list.selectedIndex
        var remaining = delta
        while (remaining != 0) {
            val next = index + if (remaining > 0) 1 else -1
            if (next < 0 || next >= model.size()) break
            index = next
            if (model.get(index) is Row.Item) remaining += if (delta > 0) -1 else 1
        }
        if (model.get(index) is Row.Item) list.selectedIndex = index
        list.ensureIndexIsVisible(list.selectedIndex)
    }

    /** 当前选中的候选 */
    fun currentCandidate(): MentionSupport.Candidate? =
        (rowAt(list.selectedIndex) as? Row.Item)?.candidate

    private fun rowAt(index: Int): Row? =
        if (index in 0 until model.size()) model.get(index) else null

    private fun selectFirstItem() {
        val first = (0 until model.size()).firstOrNull { model.get(it) is Row.Item }
        if (first != null) {
            list.selectedIndex = first
            list.ensureIndexIsVisible(first)
        }
    }

    private class RowRenderer : ListCellRenderer<Row> {
        override fun getListCellRendererComponent(
            list: JList<out Row>?,
            value: Row?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): JComponent = when (value) {
            is Row.Header -> JBLabel(value.title).apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.disabled
                border = JBUI.Borders.empty(
                    ChatUIConstants.Spacing.SMALL,
                    ChatUIConstants.Spacing.NORMAL
                )
                isOpaque = false
            }
            is Row.Item -> JBLabel(value.candidate.label).apply {
                toolTipText = value.candidate.detail
                border = JBUI.Borders.empty(
                    ChatUIConstants.Spacing.SMALL,
                    ChatUIConstants.Spacing.NORMAL
                )
                isOpaque = true
                background = if (isSelected) ChatAppColors.Tab.selectedBackground else ChatAppColors.Panel.background
                foreground = ChatAppColors.Text.normal
            }
            else -> JBLabel("")
        }
    }

    private companion object {
        const val VISIBLE_ROWS = 8
        val PREFERRED_SIZE = Dimension(360, 240)
    }

    init {
        content.preferredSize = PREFERRED_SIZE
    }
}