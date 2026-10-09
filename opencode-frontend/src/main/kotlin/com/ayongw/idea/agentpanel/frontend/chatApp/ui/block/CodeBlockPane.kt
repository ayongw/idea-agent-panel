package com.ayongw.idea.agentpanel.frontend.chatApp.ui.block

import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatUIConstants
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

private val log = Logger.getInstance("com.ayongw.idea.agentpanel.frontend.chatApp.ui.block.CodeBlockPane")

class CodeBlockPane(
    private val language: String,
    private val code: String
) : JPanel() {

    private val codeLines = code.lines()
    private val collapsible = codeLines.size > ChatUIConstants.LargeContent.DIRECT_MAX_LINES

    /** 单行紧凑标签（无头部行、无滚动容器） */
    private val chipMode = codeLines.size <= ChatUIConstants.LargeContent.INLINE_CHIP_MAX_LINES &&
        code.length <= ChatUIConstants.LargeContent.INLINE_CHIP_MAX_CHARS

    /** 直接渲染（≤ [ChatUIConstants.LargeContent.DIRECT_MAX_LINES] 行，不套滚动容器） */
    private val directMode = !chipMode && codeLines.size <= ChatUIConstants.LargeContent.DIRECT_MAX_LINES
    private val textArea = JBTextArea()
    private val scrollPane = JBScrollPane(textArea)
    private var expanded = false
    private val toggleLabel = JBLabel()

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        if (chipMode) {
            // 短输出：单行紧凑块。工具输出里大量是 `null` / 单行日志，
            // 给它们滚动容器 + 头部行纯属浪费（见 INLINE_MAX_LINES 注释）
            add(inlineLabel())
            log.debug("codeblock inline lang=$language chars=${code.length} lines=${codeLines.size}")
        } else if (directMode) {
            buildDirect()
        } else {
            buildBlock()
        }
    }

    /**
     * 直接渲染：≤ [ChatUIConstants.LargeContent.DIRECT_MAX_LINES] 行时按实际行数撑开，
     * 不套滚动容器（用户诉求：20 行以内直接展示，超过才出现滚动条）。
     * 头部行保留（语言标识 + 复制按钮对多行内容仍有用）。
     */
    private fun buildDirect() {
        textArea.apply {
            text = code
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            border = EmptyBorder(8, 12, 8, 12)
        }
        scrollPane.apply {
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            // 长行必须能横向滚动：裸 JTextArea（lineWrap=false）会把超宽行直接裁掉且不出现滚动条
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            border = EmptyBorder(0, 0, 0, 0)
            isOpaque = false
            viewport.isOpaque = false
        }
        add(buildHeader())
        add(scrollPane)
        // 高度按实际行数，不封顶（≤20 行最多约 400px，可接受）
        val lineHeight = JBUI.scale(ChatUIConstants.LargeContent.CODE_LINE_HEIGHT)
        val height = lineHeight * codeLines.size + JBUI.scale(16)
        // 宽度不写死：靠 maximumSize 横向拉伸到气泡宽度，超宽内容交给滚动条
        scrollPane.preferredSize = Dimension(0, height)
        scrollPane.maximumSize = Dimension(Int.MAX_VALUE, height)
        log.debug("codeblock direct lang=$language lines=${codeLines.size} height=$height")
    }

    /** 内联紧凑块：单行、自适应宽度、无滚动容器与头部行 */
    private fun inlineLabel(): JComponent = JBLabel(codeLines.firstOrNull().orEmpty()).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        foreground = ChatAppColors.Text.normal
        border = EmptyBorder(JBUI.scale(2), JBUI.scale(6), JBUI.scale(2), JBUI.scale(6))
        background = ChatAppColors.MessageBubble.othersBackground
        isOpaque = true
        alignmentX = LEFT_ALIGNMENT
        toolTipText = code
    }

    /** 常规滚动代码块（多行内容） */
    private fun buildBlock() {
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
        // preferred 宽度留 0，由 maximumSize 拉伸到气泡实际宽度：
        // 写死宽度会让窄面板下的超宽行既被裁掉又不触发横向滚动条
        scrollPane.preferredSize = Dimension(0, height)
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

    private fun collapsedLabel() = AgentPanelBundle.message("chat.code.expand", codeLines.size)

    private fun expandedLabel() = AgentPanelBundle.message("chat.code.collapse")

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
            toolTipText = AgentPanelBundle.message("chat.code.copy.tooltip")
        }
    }
}
