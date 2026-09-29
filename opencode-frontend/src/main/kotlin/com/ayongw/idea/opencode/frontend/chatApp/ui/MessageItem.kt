package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ToolCallDto
import com.ayongw.idea.opencode.shared.ToolCallStatus
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
private val log = Logger.getInstance("com.ayongw.idea.opencode.frontend.chatApp.ui.MessageItem")

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

    /** 当前已渲染内容的内容指纹，用于「内容是否变化」的比较；思考消息初始只渲染动画，故为空 */
    private var renderedContent: String = if (message.isAIThinkingMessage()) "" else contentSignature(message)

    init {
        setupAppearance()

        val tool = message.tool
        log.info(
            "[diag] new bubble id=${message.id.take(16)} type=${message.type} isMy=${message.isMyMessage} " +
                "author=${message.author} contentLen=${message.content.length} tool=${tool?.name ?: "-"}"
        )
        if (message.isToolMessage() && tool != null) {
            // 工具卡片自带标题行，不再显示作者名
            val card = buildToolCard(tool)
            contentContainer = card
            add(card)
        } else {
            // 助手消息：头像 + 名称（参考样式），用户消息：仅名称
            add(if (message.isMyMessage) AuthorName(message) else AuthorRow(message))
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
    }

    /**
     * 上游按消息 id 推送同一气泡的新内容时就地刷新（流式正文 / 推理 / 工具卡片）；内容未变则不动。
     */
    fun syncWith(message: ChatMessage) {
        if (contentSignature(message) == renderedContent) return
        when {
            message.isAIThinkingMessage() -> updateReasoningContent(message.content)
            message.isTextMessage() -> updateStreamingText(message.content)
            message.isToolMessage() -> message.tool?.let(::updateTool)
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

        // 助手消息按参考样式渲染为「整行块」（无气泡底）：仅用户消息与搜索命中时绘制气泡
        val paintBubble = isMyMessage || isMatchingSearch || isHighlightedInSearch
        if (!paintBubble) return

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
            renderedContent = newContent
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
            renderedContent = content
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

    /** 更新工具卡片（运行中 → 完成 / 失败） */
    private fun updateTool(tool: ToolCallDto) {
        val card = buildToolCard(tool)
        contentContainer?.let { remove(it) }
        contentContainer = card
        renderedContent = toolSignature(tool)
        add(card)
        revalidate()
        repaint()
    }

    private fun buildToolCard(tool: ToolCallDto): JPanel = ToolCallCard(tool)

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

/**
 * 助手消息头部：头像 + 名称（对齐参考样式 Agent/Kiro 的标题行）。
 */
private class AuthorRow(message: ChatMessage) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(ChatUIConstants.MessageBubble.AVATAR_SIZE))

        add(AgentAvatar(message.author))
        add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.MessageBubble.AVATAR_GAP)))
        add(JBLabel(message.author).apply {
            font = JBFont.small().asBold()
            foreground = ChatAppColors.Text.authorName
        })
        add(Box.createHorizontalGlue())
    }
}

/** 助手头像：圆角方块 + 名称首字母（无需图标资源，随主题取色） */
private class AgentAvatar(private val name: String) : JComponent() {
    init {
        alignmentX = LEFT_ALIGNMENT
        val side = JBUI.scale(ChatUIConstants.MessageBubble.AVATAR_SIZE)
        preferredSize = Dimension(side, side)
        minimumSize = Dimension(side, side)
        maximumSize = Dimension(side, side)
        toolTipText = name
    }

    override fun paintComponent(g: Graphics) {
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val corner = JBUI.scale(ChatUIConstants.Spacing.SMALL).toFloat()
        g2d.color = ChatAppColors.Avatar.background
        g2d.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), corner, corner))

        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
        g2d.font = JBFont.small().asBold()
        g2d.color = ChatAppColors.Avatar.foreground
        val metrics = g2d.fontMetrics
        g2d.drawString(
            initial,
            (width - metrics.stringWidth(initial)) / 2,
            (height - metrics.height) / 2 + metrics.ascent
        )
        g2d.dispose()
    }
}

sealed class MarkdownSegment {
    data class Text(val content: String) : MarkdownSegment()
    data class CodeBlock(val language: String, val code: String) : MarkdownSegment()
}

/** 内容指纹：TOOL 卡片由「状态 + 入参 + 输出」决定是否需要重渲染 */
private fun contentSignature(message: ChatMessage): String =
    message.tool?.let(::toolSignature) ?: message.content

private fun toolSignature(tool: ToolCallDto): String = "${tool.status}|${tool.input}|${tool.output}"

/**
 * 工具调用卡片：工具名 + 状态（+ 退出码）+ 入参摘要 + 输出正文。
 *
 * 数据来自 [ToolCallDto]：REST 为权威值，事件流补充运行中态，两者按 `callId` 原地互相覆盖。
 */
private class ToolCallCard(private val tool: ToolCallDto) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        add(buildHeader())

        tool.input.takeIf { it.isNotBlank() }?.let { input ->
            add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(buildInputLabel(input))
        }

        tool.output.takeIf { it.isNotBlank() }?.let { output ->
            add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(CodeBlockPane(tool.name.ifBlank { FALLBACK_NAME }, output))
        }
    }

    private fun buildHeader() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(HEADER_HEIGHT))

        add(JBLabel(tool.name.ifBlank { FALLBACK_NAME }).apply {
            font = JBFont.small().asBold()
            foreground = ChatAppColors.Text.normal
        })
        add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.NORMAL)))
        add(JBLabel(statusLabel()).apply {
            font = JBFont.small()
            foreground = statusColor()
        })
        tool.exit?.let { code ->
            add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(JBLabel(EXIT_LABEL + code).apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.timestamp
            })
        }
        if (tool.truncated) {
            add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(JBLabel(TRUNCATED_LABEL).apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.timestamp
            })
        }
        add(Box.createHorizontalGlue())
    }

    private fun buildInputLabel(input: String) = JBLabel(input.toSingleLine()).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        foreground = ChatAppColors.Tool.secondary
        alignmentX = LEFT_ALIGNMENT
        toolTipText = input
    }

    private fun statusLabel(): String = when (tool.status) {
        ToolCallStatus.STREAMING -> STATUS_STREAMING
        ToolCallStatus.RUNNING -> STATUS_RUNNING
        ToolCallStatus.COMPLETED -> STATUS_COMPLETED
        ToolCallStatus.ERROR -> STATUS_ERROR
    }

    private fun statusColor(): Color = when (tool.status) {
        ToolCallStatus.COMPLETED -> ChatAppColors.Tool.success
        ToolCallStatus.ERROR -> ChatAppColors.Tool.error
        else -> ChatAppColors.Tool.running
    }

    /** 入参可能很长（整段 JSON），超长截断显示，完整值放 tooltip */
    private fun String.toSingleLine(): String {
        val single = replace("\n", " ").replace("\r", " ")
        return if (single.length > MAX_INPUT_CHARS) single.take(MAX_INPUT_CHARS) + ELLIPSIS else single
    }

    private companion object {
        const val FALLBACK_NAME = "tool"
        const val HEADER_HEIGHT = 20
        const val MAX_INPUT_CHARS = 120
        const val ELLIPSIS = "…"
        const val EXIT_LABEL = "exit "
        const val TRUNCATED_LABEL = "输出已截断"
        const val STATUS_STREAMING = "准备中…"
        const val STATUS_RUNNING = "运行中…"
        const val STATUS_COMPLETED = "已完成"
        const val STATUS_ERROR = "失败"
    }
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

        // Large text protection: if content > 10KB, use pagination
        if (text.length > ChatUIConstants.LargeContent.MAX_TEXT_LENGTH) {
            add(PaginatedTextPane(text))
        } else {
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
}

/**
 * 分页文本面板 - 用于大文本内容的分页渲染
 */
private class PaginatedTextPane(private val text: String) : JPanel() {
    private var currentPage = 0
    private val lines: List<String>
    private val linesPerPage = ChatUIConstants.LargeContent.LINES_PER_PAGE
    private val totalPages: Int

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        lines = text.lines().toList()
        totalPages = (lines.size + linesPerPage - 1) / linesPerPage

        renderPage(0)
        if (totalPages > 1) {
            add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(PaginationControls())
        }
    }

    private fun renderPage(page: Int) {
        removeAll()
        currentPage = page

        val start = page * linesPerPage
        val end = (start + linesPerPage).coerceAtMost(lines.size)
        val pageLines = lines.subList(start, end)

        pageLines.forEachIndexed { index, line ->
            val label = JBLabel(line).apply {
                font = JBFont.regular()
                foreground = ChatAppColors.Text.normal
                alignmentX = LEFT_ALIGNMENT
            }
            add(label)
            if (index < pageLines.lastIndex) add(Box.createVerticalStrut(JBUI.scale(2)))
        }

        if (totalPages > 1) {
            add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(PaginationControls())
        }

        revalidate()
        repaint()
    }

    private inner class PaginationControls : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT

            add(Box.createHorizontalGlue())

            val prevBtn = JBLabel("← 上一页").apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.disabled
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(4, 12)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        if (currentPage > 0) renderPage(currentPage - 1)
                    }
                })
                isEnabled = currentPage > 0
            }
            add(prevBtn)

            add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.MEDIUM)))

            val pageInfo = JBLabel("${currentPage + 1} / $totalPages").apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.timestamp
            }
            add(pageInfo)

            add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.MEDIUM)))

            val nextBtn = JBLabel("下一页 →").apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.disabled
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(4, 12)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        if (currentPage < totalPages - 1) renderPage(currentPage + 1)
                    }
                })
                isEnabled = currentPage < totalPages - 1
            }
            add(nextBtn)

            add(Box.createHorizontalGlue())
        }
    }
}

private class CodeBlockPane(
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

        log.info(
            "[diag] codeblock lang=$language lines=${codeLines.size} chars=${code.length} " +
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
            textArea.text = codeLines
                .take(ChatUIConstants.LargeContent.MAX_CODE_LINES)
                .joinToString("\n")
            applyPaneHeight(ChatUIConstants.LargeContent.MAX_CODE_LINES)
        } else {
            textArea.text = previewText()
            applyPaneHeight(previewLineCount())
        }
        toggleLabel.text = if (expanded) expandedLabel() else collapsedLabel()
        revalidate()
        repaint()
    }

    private fun collapsedLabel() = "展开 ${codeLines.size} 行"

    private fun expandedLabel() = "收起"

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