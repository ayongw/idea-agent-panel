package com.ayongw.plugins.opencode.frontend.chatApp.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.ayongw.plugins.opencode.shared.ChatMessage
import com.ayongw.plugins.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JEditorPane
import javax.swing.JPanel
import javax.swing.border.EmptyBorder
import javax.swing.event.HyperlinkEvent
import javax.swing.text.html.HTMLEditorKit

/**
 * 消息气泡 - 支持流式更新
 */
class MessageBubble(
    private val message: ChatMessage,
    private var isMatchingSearch: Boolean = false,
    private var isHighlightedInSearch: Boolean = false
) : JPanel() {

    private val isMyMessage = message.isMyMessage

    /** 内容容器 - 用于动态更新 */
    private var contentContainer: JPanel? = null

    /** 当前渲染的内容段落 */
    private var currentSegments: List<MarkdownSegment> = emptyList()

    init {
        setupAppearance()

        add(AuthorName(message))
        add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.MEDIUM)))

        when {
            message.isTextMessage() -> {
                contentContainer = buildContentContainer(message.content)
                add(contentContainer!!)
                add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.NORMAL)))
                add(TimeStampLabel(message))
            }
            message.isAIThinkingMessage() -> add(ThinkingIndicator())
        }
    }

    private fun setupAppearance() {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false

        border = JBUI.Borders.compound(
            JBUI.Borders.empty(
                ChatUIConstants.MessageBubble.VERTICAL_MARGIN,
                ChatUIConstants.MessageBubble.HORIZONTAL_MARGIN
            ),
            JBUI.Borders.empty(ChatUIConstants.MessageBubble.INNER_PADDING)
        )

        minimumSize = Dimension(JBUI.scale(ChatUIConstants.MessageBubble.MIN_WIDTH), 0)
        maximumSize = Dimension(JBUI.scale(ChatUIConstants.MessageBubble.MAX_WIDTH), Int.MAX_VALUE)
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)

        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val margin = JBUI.scale(ChatUIConstants.MessageBubble.VERTICAL_MARGIN)
        val marginH = JBUI.scale(ChatUIConstants.MessageBubble.HORIZONTAL_MARGIN)
        val cornerRadius = JBUI.scale(ChatUIConstants.MessageBubble.CORNER_RADIUS)

        val shape = RoundRectangle2D.Float(
            marginH.toFloat(),
            margin.toFloat(),
            (width - 2 * marginH).toFloat(),
            (height - 2 * margin).toFloat(),
            cornerRadius.toFloat(),
            cornerRadius.toFloat()
        )

        g2d.color = getMessageBackground()
        g2d.fill(shape)

        g2d.color = getBorderColor()
        g2d.stroke = BasicStroke(JBUI.scale(1).toFloat())
        g2d.draw(shape)

        g2d.dispose()
    }

    private fun getMessageBackground(): Color {
        return when {
            isHighlightedInSearch && isMyMessage -> ChatAppColors.MessageBubble.mySearchHighlightedBackground
            isHighlightedInSearch && !isMyMessage -> ChatAppColors.MessageBubble.othersSearchHighlightedBackground
            isMyMessage -> ChatAppColors.MessageBubble.myBackground
            else -> ChatAppColors.MessageBubble.othersBackground
        }
    }

    private fun getBorderColor(): Color {
        return when {
            isHighlightedInSearch -> ChatAppColors.MessageBubble.searchHighlightedBackgroundBorder
            isMatchingSearch && isMyMessage -> ChatAppColors.MessageBubble.matchingMyBorder
            isMatchingSearch && !isMyMessage -> ChatAppColors.MessageBubble.matchingOthersBorder
            isMyMessage -> ChatAppColors.MessageBubble.myBackgroundBorder
            else -> ChatAppColors.MessageBubble.othersBackgroundBorder
        }
    }

    fun updateSearchState(matching: Boolean, highlighted: Boolean) {
        isMatchingSearch = matching
        isHighlightedInSearch = highlighted
        repaint()
    }

    /**
     * 更新流式文本内容
     */
    fun updateStreamingText(newContent: String) {
        contentContainer?.let { container ->
            remove(container)
            val newContainer = buildContentContainer(newContent)
            contentContainer = newContainer
            add(newContainer, 2) // Insert after author name and spacer
            revalidate()
            repaint()
        }
    }

    /**
     * 完成流式文本，最终渲染
     */
    fun completeStreamingText(finalContent: String) {
        updateStreamingText(finalContent)
    }

    /**
     * 更新推理过程内容
     */
    fun updateReasoningContent(content: String) {
        // For thinking messages, we replace the ThinkingIndicator with content
        if (message.isAIThinkingMessage()) {
            removeAll()
            setupAppearance()
            add(AuthorName(message))
            add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.MEDIUM)))
            contentContainer = buildReasoningContent(content)
            add(contentContainer!!)
            revalidate()
            repaint()
        }
    }

    /**
     * 完成推理过程
     */
    fun completeReasoning(finalContent: String) {
        updateReasoningContent(finalContent)
    }

    /**
     * 构建内容容器
     */
    private fun buildContentContainer(content: String): JPanel {
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

        currentSegments = parseMarkdownWithCodeBlocks(content)
        currentSegments.forEachIndexed { index, segment ->
            when (segment) {
                is MarkdownSegment.Text -> {
                    if (segment.content.isNotBlank()) {
                        container.add(TextPane(segment.content))
                        if (index < currentSegments.lastIndex) container.add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
                    }
                }
                is MarkdownSegment.CodeBlock -> {
                    container.add(CodeBlockPane(segment.language, segment.code))
                    if (index < currentSegments.lastIndex) container.add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
                }
            }
        }

        return container
    }

    /**
     * 构建推理过程容器
     */
    private fun buildReasoningContent(content: String): JPanel {
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

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

        return container
    }
}

private class AuthorName(message: ChatMessage) : JBLabel() {
    init {
        text = if (message.isMyMessage) {
            OpencodeFrontendBundle.message("chat.message.author.me")
        } else {
            message.author
        }

        font = JBFont.small().asBold()
        foreground = ChatAppColors.Text.authorName
        alignmentX = LEFT_ALIGNMENT
    }
}

sealed class MarkdownSegment {
    data class Text(val content: String) : MarkdownSegment()
    data class CodeBlock(val language: String, val code: String) : MarkdownSegment()
}

private fun parseMarkdownWithCodeBlocks(content: String): List<MarkdownSegment> {
    val segments = mutableListOf<MarkdownSegment>()
    val lines = content.lines().toList()
    var i = 0
    var textBuffer = StringBuilder()

    while (i < lines.size) {
        val line = lines[i]
        if (line.trim().startsWith("```")) {
            // Flush pending text
            if (textBuffer.isNotEmpty()) {
                segments.add(MarkdownSegment.Text(textBuffer.toString()))
                textBuffer = StringBuilder()
            }

            val fence = line.trim()
            val language = fence.substring(3).trim().takeIf { it.isNotBlank() } ?: "plaintext"
            i++
            val codeLines = mutableListOf<String>()
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                codeLines.add(lines[i])
                i++
            }
            // Skip closing fence
            if (i < lines.size) i++
            segments.add(MarkdownSegment.CodeBlock(language, codeLines.joinToString("\n")))
        } else {
            textBuffer.append(line).append("\n")
            i++
        }
    }

    if (textBuffer.isNotEmpty()) {
        segments.add(MarkdownSegment.Text(textBuffer.toString()))
    }

    return segments
}

private class TextPane(private val text: String) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        val lines = text.lines().toList()
        lines.forEachIndexed { index, line ->
            val label = JBLabel(line).apply {
                font = JBFont.regular()
                foreground = ChatAppColors.Text.normal
                alignmentX = LEFT_ALIGNMENT
            }
            add(label)
            if (index < lines.lastIndex) add(Box.createVerticalStrut(JBUI.scale(2)))
        }
    }
}

private class CodeBlockPane(
    private val language: String,
    private val code: String
) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        val textArea = JBTextArea().apply {
            text = code
            font = Font(Font.MONOSPACED, Font.PLAIN, 12)
            isEditable = false
            lineWrap = false
            wrapStyleWord = false
            border = EmptyBorder(8, 12, 8, 12)
        }

        val scrollPane = JBScrollPane(textArea).apply {
            verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            border = EmptyBorder(4, 0, 4, 0)
            isOpaque = false
            viewport.isOpaque = false
        }

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(28))
            add(Box.createHorizontalGlue())
            val label = JBLabel(language.uppercase()).apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.disabled
                border = EmptyBorder(0, 0, 0, JBUI.scale(8))
            }
            add(label)
            val copyBtn = createCopyButton(code)
            add(copyBtn)
        }

        add(header)
        add(scrollPane)
    }

    private fun createCopyButton(text: String): JComponent {
        return JBLabel().apply {
            setIcon(com.intellij.icons.AllIcons.Actions.Copy)
            cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(
                        java.awt.datatransfer.StringSelection(text),
                        null
                    )
                }
            })
            toolTipText = OpencodeFrontendBundle.message("chat.code.copy.tooltip")
        }
    }
}

private class TimeStampLabel(message: ChatMessage) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        val label = JBLabel(message.formattedTime()).apply {
            font = JBFont.small()
            foreground = ChatAppColors.Text.timestamp
        }
        add(Box.createHorizontalGlue())
        add(label)
    }
}