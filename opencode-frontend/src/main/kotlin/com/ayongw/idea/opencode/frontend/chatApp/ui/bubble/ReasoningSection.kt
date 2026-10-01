package com.ayongw.idea.opencode.frontend.chatApp.ui.bubble

import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/** 思考区折叠箭头（G3） */
private const val COLLAPSE_CHEVRON = "▾"
private const val EXPAND_CHEVRON = "▸"

/**
 * 思考区组件（G3 / TSD-30 §5.8）：折叠标题行 + 内容容器。
 *
 * 从 [MessageBubble] 抽出——流式期经 [updateContent] 展开更新，执行结束经 [complete] 自动折叠，
 * 标题行可手动切换；折叠只改变内容容器可见性，布局仍由面板级 LayoutCoordinator 收口。
 */
internal class ReasoningSection(expanded: Boolean) : JPanel() {

    private var expandedState = expanded

    private val chevron = JBLabel(if (expanded) COLLAPSE_CHEVRON else EXPAND_CHEVRON).apply {
        foreground = ChatAppColors.Text.disabled
    }

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        isVisible = expanded
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(buildHeader())
        add(contentPanel)
    }

    /** 流式更新思考内容并保证展开态（G3） */
    fun updateContent(content: String) {
        if (!expandedState) setExpanded(true)
        populate(contentPanel, content)
    }

    /** 执行结束自动折叠（仍可手动展开） */
    fun complete() = setExpanded(false)

    private fun buildHeader(): JPanel {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JBLabel(OpencodeFrontendBundle.message("chat.message.reasoning.title")).apply {
                font = JBFont.small().asBold()
                foreground = ChatAppColors.Text.disabled
            })
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(chevron)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    setExpanded(!expandedState)
                    revalidate()
                    repaint()
                }
            })
        }
        return header
    }

    private fun setExpanded(expanded: Boolean) {
        expandedState = expanded
        contentPanel.isVisible = expanded
        chevron.text = if (expanded) COLLAPSE_CHEVRON else EXPAND_CHEVRON
    }

    /** 往内容容器逐行填充（每次流式更新整体替换，组件数 = 行数） */
    private fun populate(container: JPanel, content: String) {
        container.removeAll()
        val lines = content.lines().toList()
        lines.forEachIndexed { index, line ->
            val label = JBLabel(line).apply {
                font = JBFont.regular()
                foreground = ChatAppColors.Text.normal
                alignmentX = LEFT_ALIGNMENT
            }
            container.add(label)
            if (index < lines.lastIndex) container.add(Box.createVerticalStrut(JBUI.scale(2)))
        }
    }
}
