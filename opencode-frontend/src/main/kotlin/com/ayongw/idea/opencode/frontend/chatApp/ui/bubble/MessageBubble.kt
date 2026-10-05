package com.ayongw.idea.opencode.frontend.chatApp.ui.bubble

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.ThinkingIndicator
import com.ayongw.idea.opencode.frontend.chatApp.ui.block.CodeBlockPane
import com.ayongw.idea.opencode.frontend.chatApp.ui.block.TextBlock
import com.ayongw.idea.opencode.frontend.chatApp.ui.block.ToolCallCard
import com.ayongw.idea.opencode.frontend.chatApp.ui.md.MarkdownSegment
import com.ayongw.idea.opencode.frontend.chatApp.ui.md.parseMarkdownWithCodeBlocks
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ToolCallDto
import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 消息气泡 - 支持流式更新
 */
private val log = Logger.getInstance("com.ayongw.idea.opencode.frontend.chatApp.ui.bubble.MessageBubble")

class MessageBubble(
    private val message: ChatMessage,
    private var isMatchingSearch: Boolean = false,
    private var isHighlightedInSearch: Boolean = false
) : JPanel(), Disposable {

    private val isMyMessage = message.isMyMessage

    /** 是否用户消息（决定对齐与气泡样式；供列表重排时取用） */
    val isMy: Boolean get() = isMyMessage

    /** 关联的消息 id（供列表顺序断言 / 检索定位） */
    val messageId: String get() = message.id

    /** 内容容器 - 用于动态更新 */
    private var contentContainer: JPanel? = null

    /** 思考区组件（G3）：流式期展开，结束折叠；空内容时先显示动画，首帧再替换为本组件 */
    private var reasoningSection: ReasoningSection? = null

    /** 思考动画组件（思考气泡初始态）：被内容骨架替换或气泡被删除时必须 dispose，否则 animator 挂到 ROOT 泄漏 */
    private var thinkingIndicator: ThinkingIndicator? = null

    /** 时间行（独立于气泡）：用户消息的气泡只包住内容，绘制时需避开该行 */
    private var timestampRow: JComponent? = null

    /** 当前渲染的内容段落 */
    private var currentSegments: List<MarkdownSegment> = emptyList()

    /** 思考内容指纹（推理区）：null = 未渲染（空思考初始只渲染动画） */
    private var reasoningSignature: String? =
        if (message.isAIThinkingMessage() && message.content.isNotBlank()) message.content else null

    /** 正文/工具内容指纹：null = 正文区未构建（合并气泡中思考先到、正文后补） */
    private var textSignature: String? = if (message.isAIThinkingMessage()) null else contentSignature(message)

    init {
        setupAppearance()

        val tool = message.tool
        log.debug(
            "new bubble id=${message.id.take(16)} type=${message.type} isMy=${message.isMyMessage} " +
                "author=${message.author} contentLen=${message.content.length} tool=${tool?.name ?: "-"}"
        )
        if (message.isToolMessage() && tool != null) {
            // 工具卡片自带标题行，不再显示作者名
            val card = buildToolCard(tool)
            contentContainer = card
            add(card)
        } else {
            // 助手消息：头像 + 名称；用户消息不显示标题（气泡只包内容，时间另起一行在气泡外）
            if (!message.isMyMessage) {
                add(AuthorRow(message))
                add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.MEDIUM)))
            }

            when {
                message.isTextMessage() -> appendContentContainer(message)
                message.isAIThinkingMessage() -> {
                    // 已完成的历史思考（非空内容）默认折叠；流式刚开始（空内容）先显示动画
                    if (message.content.isBlank()) {
                        add(ThinkingIndicator().also { thinkingIndicator = it })
                    } else {
                        insertReasoningStructure(expanded = false)
                    }
                }
            }
        }
    }

    /**
     * 上游按消息 id 推送新内容时就地刷新；内容未变则不动。
     *
     * 合并气泡：同一 assistant 消息的思考（id 带 #reasoning 后缀）与正文路由到同一气泡，
     * 按到达顺序分别驱动推理区 / 正文区（见 [updateReasoningContent] / [updateStreamingText]）。
     */
    fun syncWith(message: ChatMessage) {
        when {
            message.isAIThinkingMessage() -> {
                if (reasoningSignature != null && reasoningSignature == message.content) return
                updateReasoningContent(message.content)
            }
            message.isTextMessage() -> {
                if (textSignature != null && textSignature == contentSignature(message)) return
                updateStreamingText(message)
            }
            message.isToolMessage() -> message.tool?.let { tool ->
                if (textSignature != null && textSignature == toolSignature(tool)) return
                updateTool(tool)
            }
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

        // 用户消息：时间行独立于气泡之外，气泡底边停在时间行之前
        val bubbleBottom = if (isMyMessage) userBubbleBottom() else height
        if (bubbleBottom - margin <= marginH) {
            g2d.dispose()
            return
        }

        val shape = RoundRectangle2D.Float(
            marginH.toFloat(),
            margin.toFloat(),
            (width - 2 * marginH).toFloat(),
            (bubbleBottom - margin).toFloat(),
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

    /**
     * 用户消息气泡底边：到时间行之前留一个间距；时间行尚未完成布局时退回整高（避免画空）。
     */
    private fun userBubbleBottom(): Int {
        val timestamp = timestampRow ?: return height
        return if (timestamp.bounds.y > 0) {
            timestamp.bounds.y - JBUI.scale(ChatUIConstants.Spacing.SMALL)
        } else {
            height
        }
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
     * 更新流式文本内容（TSD-30 §5.3 块级增量）
     *
     * 正文区不存在（思考先到的合并气泡）时首帧构建：折叠思考区（正文开始输出 = 思考结束）、
     * 撤掉残留思考动画，再追加正文容器 + 时间行；已存在则只重建容器内块组件（容器保持挂载，
     * 不脱离父布局，避免闪烁）。
     */
    fun updateStreamingText(textMessage: ChatMessage) {
        val container = contentContainer
        if (container == null) {
            reasoningSection?.complete()
            dismissThinkingIndicator()
            appendContentContainer(textMessage)
        } else {
            populateContentContainer(container, textMessage.content)
        }
        textSignature = contentSignature(textMessage)
        contentContainer?.revalidate()
        // 内容增高/缩矮时气泡轮廓与时间行位置需重绘（用户消息气泡只包内容）
        repaint()
    }

    /**
     * 完成流式文本，最终渲染
     */
    fun completeStreamingText(finalContent: String) {
        updateStreamingText(message.copy(content = finalContent))
    }

    /**
     * 更新推理过程内容（G3：流式期间展开）
     *
     * 空思考首帧只保留动画；首帧非空内容（或动画升级）建折叠骨架并展开；
     * 正文区已存在时思考区插入到正文上方（合并气泡支持正文先到）。
     */
    fun updateReasoningContent(content: String) {
        if (reasoningSection == null) {
            if (content.isBlank()) {
                if (thinkingIndicator == null) {
                    add(ThinkingIndicator().also { thinkingIndicator = it })
                    revalidate()
                    repaint()
                }
                reasoningSignature = ""
                return
            }
            insertReasoningStructure(expanded = true)
        }
        reasoningSection!!.updateContent(content)
        reasoningSignature = content
        revalidate()
        repaint()
    }

    /**
     * 程序化折叠思考区（G3 流式结束自动折叠）：由 [ChatList] 在执行终态信号/非运行态对账时调用，
     * 仍可手动展开查看。流式期间的展开由 [updateReasoningContent] 保证。
     */
    fun completeReasoning() {
        reasoningSection?.let {
            it.complete()
            revalidate()
            repaint()
        }
    }

    /**
     * 思考区骨架（G3）：动画替换为 [ReasoningSection]（作者名 + 可折叠内容）。
     * 正文区已存在（正文先到的合并气泡）时插入到正文上方（作者行之后），否则追加尾部。
     */
    private fun insertReasoningStructure(expanded: Boolean) {
        dismissThinkingIndicator()
        val section = ReasoningSection(expanded)
        reasoningSection = section
        // 正文先到时组件为 [author, strut, content, strut, timestamp]，思考区插到正文之前
        add(section, if (contentContainer == null) componentCount else 2)
    }

    /** 追加正文容器 + 时间行（正文首帧；思考区/动画不受影响） */
    private fun appendContentContainer(textMessage: ChatMessage) {
        contentContainer = buildContentContainer(textMessage.content)
        add(contentContainer!!)
        // 用户消息：时间行在气泡之外，需留出「气泡内边距 + 与时间的间距」
        val gapBeforeTimestamp = if (textMessage.isMyMessage) {
            ChatUIConstants.MessageBubble.INNER_PADDING + ChatUIConstants.Spacing.SMALL
        } else {
            ChatUIConstants.Spacing.NORMAL
        }
        add(Box.createVerticalStrut(JBUI.scale(gapBeforeTimestamp)))
        add(TimeStampLabel(textMessage).also { timestampRow = it })
    }

    /** 撤掉思考动画（正文首帧 / 气泡销毁）：组件从布局移除并释放 animator，防 ROOT 泄漏 */
    private fun dismissThinkingIndicator() {
        thinkingIndicator?.let {
            remove(it)
            it.dispose()
        }
        thinkingIndicator = null
    }

    /**
     * 气泡被列表删除/清空时释放内部 Disposable 子组件（当前只有思考动画），
     * 避免 animator 注册树残留到 ROOT_DISPOSABLE（Disposer 泄漏检测在 IDE 关闭时报警）。
     */
    override fun dispose() {
        dismissThinkingIndicator()
    }

    /** 更新工具卡片（运行中 → 完成 / 失败） */
    private fun updateTool(tool: ToolCallDto) {
        val card = buildToolCard(tool)
        contentContainer?.let { remove(it) }
        contentContainer = card
        textSignature = toolSignature(tool)
        add(card)
        revalidate()
        repaint()
    }

    private fun buildToolCard(tool: ToolCallDto): JPanel = ToolCallCard(tool)

    /**
     * 构建内容容器（消息创建时的首次渲染）
     */
    private fun buildContentContainer(content: String): JPanel {
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }
        populateContentContainer(container, content)
        return container
    }

    /**
     * 往**已挂载**的内容容器填充当前 markdown 分段（流式增量复用：容器不脱离父布局）
     */
    private fun populateContentContainer(container: JPanel, content: String) {
        container.removeAll()
        currentSegments = parseMarkdownWithCodeBlocks(content)
        currentSegments.forEachIndexed { index, segment ->
            when (segment) {
                is MarkdownSegment.Text -> {
                    if (segment.content.isNotBlank()) {
                        container.add(TextBlock(segment.content))
                        if (index < currentSegments.lastIndex) {
                            container.add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
                        }
                    }
                }
                is MarkdownSegment.CodeBlock -> {
                    container.add(CodeBlockPane(segment.language, segment.code))
                    if (index < currentSegments.lastIndex) {
                        container.add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
                    }
                }
            }
        }
    }
}

/** 内容指纹：TOOL 卡片由「状态 + 入参 + 输出」决定是否需要重渲染 */
private fun contentSignature(message: ChatMessage): String =
    message.tool?.let(::toolSignature) ?: message.content

private fun toolSignature(tool: ToolCallDto): String = "${tool.status}|${tool.input}|${tool.output}"
