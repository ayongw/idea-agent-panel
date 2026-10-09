package com.ayongw.idea.opencode.frontend.chatApp.ui.block

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * 工具调用入参块（问题 4）。
 *
 * 与输出块 [CodeBlockPane] 的关键差异：
 * - **按宽度换行**（`lineWrap = true`）而不是横向滚动。入参是 JSON 参数，读者要的是「看清有哪些
 *   参数」，横向滚动会逼着读者左右拖；输出才是逐字原文（如 `git status` 结果），必须横向可滚。
 * - 超长才折叠预览 + 「展开」，短参数**原样多行铺开**，不再压成一行再补省略号
 *   （此前 `ToolCallCard.toSingleLine()` 会把换行全替换成空格，长 JSON 直接看不到字段名）。
 */
internal class ToolInputBlock(private val input: String) : JPanel() {

    private val lines = input.lines()

    /** 超过该行数才折叠；短参数直接全量展示 */
    private val collapsible = lines.size > ChatUIConstants.ToolInput.PREVIEW_LINES

    private val textArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        foreground = ChatAppColors.Tool.secondary
        background = ChatAppColors.MessageBubble.othersBackground
        border = EmptyBorder(4, 8, 4, 8)
    }

    private val scrollPane = JBScrollPane(textArea).apply {
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = EmptyBorder(0, 0, 0, 0)
        isOpaque = false
        viewport.isOpaque = false
    }

    private val toggleLabel = JBLabel()

    @Volatile
    private var expanded = false

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        render()
    }

    private fun render() {
        removeAll()
        textArea.text = if (expanded) input else lines.take(PREVIEW_LINES).joinToString("\n")
        textArea.caretPosition = 0

        val visibleLines = if (expanded) lines.size else minOf(lines.size, PREVIEW_LINES)
        val height = JBUI.scale(ChatUIConstants.ToolInput.LINE_HEIGHT) * visibleLines +
            JBUI.scale(ChatUIConstants.ToolInput.VERTICAL_PADDING)
        scrollPane.preferredSize = Dimension(0, height)
        scrollPane.maximumSize = Dimension(Int.MAX_VALUE, height)
        add(scrollPane)

        if (collapsible) {
            toggleLabel.apply {
                text = if (expanded) {
                    OpencodeFrontendBundle.message("chat.tool.input.collapse")
                } else {
                    OpencodeFrontendBundle.message("chat.tool.input.expand", lines.size)
                }
                font = JBFont.small()
                foreground = ChatAppColors.Text.disabled
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = LEFT_ALIGNMENT
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) = toggle()
                })
                addMouseMotionListener(object : MouseMotionAdapter() {
                    override fun mouseMoved(e: MouseEvent?) {
                        textArea.toolTipText = null
                    }
                })
            }
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(toggleLabel)
        }
        revalidate()
        repaint()
    }

    private fun toggle() {
        expanded = !expanded
        render()
    }

    private companion object {
        /** 折叠前的预览行数 */
        const val PREVIEW_LINES = ChatUIConstants.ToolInput.PREVIEW_LINES
    }
}