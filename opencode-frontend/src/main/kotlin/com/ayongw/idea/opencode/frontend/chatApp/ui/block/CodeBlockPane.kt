package com.ayongw.idea.opencode.frontend.chatApp.ui.block

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Toolkit
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

private val log = Logger.getInstance("com.ayongw.idea.opencode.frontend.chatApp.ui.block.CodeBlockPane")

internal class CodeBlockPane(
    private val language: String,
    private val code: String
) : JPanel() {

    private val codeLines = code.lines()
    private val collapsible = codeLines.size > ChatUIConstants.LargeContent.CODE_PREVIEW_LINES
    private val textArea = JBTextArea()
    private val scrollPane = JBScrollPane(textArea)
    private var expanded = false
    private val toggleLabel = JBLabel()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        textArea.apply {
            text = previewText()
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            border = EmptyBorder(8, 12, 8, 12)
        }

        scrollPane.apply {
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            border = EmptyBorder(4, 0, 4, 0)
            isOpaque = false
            viewport.isOpaque = false
        }
        applyPaneHeight(previewLineCount())

        add(buildHeader())
        add(scrollPane)

        log.debug(
            "codeblock lang=$language lines=${codeLines.size} chars=${code.length} " +
                "collapsible=$collapsible height=${scrollPane.preferredSize.height}"
        )
    }

    private fun previewLineCount(): Int =
        codeLines.size.coerceAtMost(ChatUIConstants.LargeContent.CODE_PREVIEW_LINES)

    private fun previewText(): String = if (collapsible) {
        codeLines.take(ChatUIConstants.LargeContent.CODE_PREVIEW_LINES).joinToString("\n")
    } else {
        code
    }

    /**
     * 固定代码块高度：按行数换算，展开态封顶 [ChatUIConstants.LargeContent.CODE_MAX_HEIGHT]。
     *
     * 不设上限时，工具输出（如 275 行的技能文档）会把气泡撑到几千像素高，消息区几乎全是空白。
     */
    private fun applyPaneHeight(lines: Int) {
        val lineHeight = JBUI.scale(ChatUIConstants.LargeContent.CODE_LINE_HEIGHT)
        val contentHeight = lineHeight * lines + JBUI.scale(16)
        val height = contentHeight.coerceAtMost(JBUI.scale(ChatUIConstants.LargeContent.CODE_MAX_HEIGHT))
        scrollPane.preferredSize = Dimension(JBUI.scale(ChatUIConstants.MessageBubble.CONTENT_WRAP_WIDTH), height)
        scrollPane.maximumSize = Dimension(Int.MAX_VALUE, height)
    }

    private fun buildHeader() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
        add(Box.createHorizontalGlue())

        if (collapsible) {
            toggleLabel.apply {
                text = collapsedLabel()
                font = JBFont.small()
                foreground = ChatAppColors.Text.disabled
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = EmptyBorder(0, 0, 0, JBUI.scale(8))
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) = toggle()
                })
            }
            add(toggleLabel)
        }

        add(JBLabel(language.uppercase()).apply {
            font = JBFont.small()
            foreground = ChatAppColors.Text.disabled
            border = EmptyBorder(0, 0, 0, JBUI.scale(8))
        })
        add(createCopyButton(code))
    }

    /** 展开/收起：收起只渲染预览行，展开后块内滚动，避免长输出撑爆消息列表 */
    private fun toggle() {
        expanded = !expanded
        if (expanded) {
            // 展开渲染全部行（不再截断行数）：高度仍封顶 320px，超长在块内滚动查看
            textArea.text = codeLines.joinToString("\n")
            applyPaneHeight(codeLines.size)
        } else {
            textArea.text = previewText()
            applyPaneHeight(previewLineCount())
        }
        toggleLabel.text = if (expanded) expandedLabel() else collapsedLabel()
        revalidate()
        repaint()
    }

    private fun collapsedLabel() = OpencodeFrontendBundle.message("chat.code.expand", codeLines.size)

    private fun expandedLabel() = OpencodeFrontendBundle.message("chat.code.collapse")

    private fun createCopyButton(text: String): JComponent {
        return JBLabel().apply {
            setIcon(com.intellij.icons.AllIcons.Actions.Copy)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent?) {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(text),
                        null
                    )
                }
            })
            toolTipText = OpencodeFrontendBundle.message("chat.code.copy.tooltip")
        }
    }
}
