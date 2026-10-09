package com.ayongw.idea.agentpanel.frontend.chatApp.ui

import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.agentpanel.shared.ModelDto
import com.ayongw.idea.agentpanel.shared.ModelProviderDto
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel

/**
 * 模型选择弹窗：顶部搜索框 + 按供应商分组的模型列表 + 底部「管理模型」入口。
 *
 * 选中项带 `✓`，免费模型带「免费」标签；搜索命中时按结果平铺（弱化分组）。
 */
class ModelPickerPopup(
    private val onSelect: (ModelDto) -> Unit,
    private val onManage: () -> Unit
) {

    private sealed class Row {
        data class Header(val title: String) : Row()
        data class Item(val model: ModelDto) : Row()
    }

    private val model = DefaultListModel<Row>()
    private val searchField = JBTextField().apply {
        emptyText.text = AgentPanelBundle.message("chat.input.search.model")
        border = JBUI.Borders.empty(ChatUIConstants.Spacing.SMALL, ChatUIConstants.Spacing.NORMAL)
    }
    private val list = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = RowRenderer()
        visibleRowCount = VISIBLE_ROWS
    }

    private var popup: JBPopup? = null
    private var providers: List<ModelProviderDto> = emptyList()
    private var selected: ModelDto? = null

    private val content = JPanel(BorderLayout()).apply {
        preferredSize = Dimension(PREFERRED_WIDTH, PREFERRED_HEIGHT)
        add(searchField, BorderLayout.NORTH)
        add(
            JBScrollPane(list).apply {
                border = JBUI.Borders.empty()
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            },
            BorderLayout.CENTER
        )
        add(
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.emptyTop(ChatUIConstants.Spacing.SMALL)
                add(
                    JButton(AgentPanelBundle.message("chat.input.manage.model")).apply {
                        addActionListener {
                            close()
                            onManage()
                        }
                    },
                    BorderLayout.WEST
                )
            },
            BorderLayout.SOUTH
        )
    }

    init {
        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = rebuild()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = rebuild()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = rebuild()
        })
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                when (e.keyCode) {
                    KeyEvent.VK_ENTER -> currentModel()?.let {
                        close()
                        onSelect(it)
                    }
                    KeyEvent.VK_ESCAPE -> close()
                    KeyEvent.VK_DOWN -> list.requestFocusInWindow()
                    else -> Unit
                }
            }
        })
        list.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val index = list.locationToIndex(e.point)
                val row = rowAt(index) as? Row.Item ?: return
                close()
                onSelect(row.model)
            }
        })
    }

    private fun rowAt(index: Int): Row? =
        if (index in 0 until model.size()) model.get(index) else null

    /** 展示弹窗（向上弹出；已打开时仅刷新内容） */
    fun show(anchor: Component, providers: List<ModelProviderDto>, selected: ModelDto?) {
        this.providers = providers
        this.selected = selected
        searchField.text = ""
        rebuild()
        if (popup?.isVisible == true) return
        popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, searchField)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setResizable(false)
            .setMovable(false)
            .createPopup()
        popup?.showUnderneathOf(anchor)
        searchField.requestFocusInWindow()
    }

    private fun close() {
        popup?.cancel()
        popup = null
    }

    private fun currentModel(): ModelDto? =
        (rowAt(list.selectedIndex) as? Row.Item)?.model

    private fun rebuild() {
        val query = searchField.text.trim()
        model.clear()
        if (query.isEmpty()) {
            providers.forEach { provider ->
                if (provider.models.isEmpty()) return@forEach
                model.addElement(Row.Header(provider.name))
                provider.models.forEach { model.addElement(Row.Item(it)) }
            }
        } else {
            providers.asSequence()
                .flatMap { it.models.asSequence() }
                .filter { it.matches(query) }
                .forEach { model.addElement(Row.Item(it)) }
        }
        selectFirstItem()
    }

    private fun ModelDto.matches(query: String): Boolean =
        name.contains(query, ignoreCase = true) ||
            modelID.contains(query, ignoreCase = true) ||
            id.contains(query, ignoreCase = true)

    private fun selectFirstItem() {
        val first = (0 until model.size()).firstOrNull { model.get(it) is Row.Item }
        if (first != null) {
            list.selectedIndex = first
            list.ensureIndexIsVisible(first)
        }
    }

    private inner class RowRenderer : ListCellRenderer<Row> {
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
                border = JBUI.Borders.empty(ChatUIConstants.Spacing.SMALL, ChatUIConstants.Spacing.NORMAL)
                isOpaque = false
            }
            is Row.Item -> JPanel(BorderLayout()).apply {
                isOpaque = true
                background = if (isSelected) ChatAppColors.Tab.selectedBackground else ChatAppColors.Panel.background
                border = JBUI.Borders.empty(ChatUIConstants.Spacing.SMALL, ChatUIConstants.Spacing.NORMAL)
                add(
                    JBLabel(value.model.name).apply {
                        font = JBFont.small()
                        foreground = ChatAppColors.Text.normal
                        toolTipText = "${value.model.providerID}/${value.model.modelID}"
                    },
                    BorderLayout.CENTER
                )
                val trailing = JPanel().apply {
                    isOpaque = false
                    layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
                    if (value.model.free) {
                        add(
                            JBLabel(AgentPanelBundle.message("chat.input.free")).apply {
                                font = JBFont.small()
                                foreground = ChatAppColors.Text.disabled
                                border = JBUI.Borders.empty(0, JBUI.scale(ChatUIConstants.Spacing.SMALL))
                            }
                        )
                    }
                    if (value.model.matches(selected)) add(JBLabel("✓").apply { font = JBFont.small() })
                }
                add(trailing, BorderLayout.EAST)
            }
            else -> JBLabel("")
        }

        private fun ModelDto.matches(other: ModelDto?): Boolean =
            other != null && other.modelID == modelID && other.providerID == providerID
    }

    private companion object {
        const val VISIBLE_ROWS = 10
        const val PREFERRED_WIDTH = 360
        const val PREFERRED_HEIGHT = 300
    }
}