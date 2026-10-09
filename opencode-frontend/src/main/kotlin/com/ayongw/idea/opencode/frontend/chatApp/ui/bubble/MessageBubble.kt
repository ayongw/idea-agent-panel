package com.ayongw.idea.opencode.frontend.chatApp.ui.bubble

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
 * 消息气泡 — 按「对话轮次（turn）」合并渲染。
 *
 * 一次用户提问触发的多步执行（多轮 reasoning / 多个工具调用 / 最终正文）统一在一个
 * 回复主题气泡内：单个作者行、单个思考块（多轮以细分隔线区分）、工具卡片按到达
 * 顺序内联、正文 + 时间行在底部。
 */
private val log = Logger.getInstance("com.ayongw.idea.opencode.frontend.chatApp.ui.bubble.MessageBubble")

class MessageBubble(
    private val firstMessage: ChatMessage,
    private var isMatchingSearch: Boolean = false,
    private var isHighlightedInSearch: Boolean = false
) : JPanel(), Disposable {

    private val isMyMessage = firstMessage.isMyMessage

    /** 是否用户消息（决定对齐与气泡样式；供列表重排时取用） */
    val isMy: Boolean get() = isMyMessage

    /** 轮次组 key（首条消息规整后的 id；供列表顺序断言 / 检索定位） */
    val messageId: String = firstMessage.id.substringBefore(REASONING_ID_SUFFIX)

    /** 正文容器（markdown 块） */
    private var contentContainer: JPanel? = null

    /** 思考区组件：流式期展开，结束折叠；多轮思考统一收纳 */
    private var reasoningSection: ReasoningSection? = null

    /** 思考动画（初始态）：被思考块/正文替换或气泡删除时必须 dispose，否则 animator 泄漏 */
    private var thinkingIndicator: ThinkingIndicator? = null

    /** 时间行（独立于气泡）：用户消息的气泡只包住内容，绘制时需避开该行 */
    private var timestampRow: JComponent? = null

    /** 当前渲染的正文段落 */
    private var currentSegments: List<MarkdownSegment> = emptyList()

    /** 已并入气泡的原始消息 id（搜索 / 定位 containsId 判定） */
    private val memberIds = linkedSetOf<String>()

    /** 各原始消息已渲染签名（syncWith 幂等去重） */
    private val appliedSignatures = HashMap<String, String>()

    /** 多轮思考：原始消息 id -> 轮次文本（保持到达顺序） */
    private val reasoningRounds = LinkedHashMap<String, String>()

    /** 工具卡片：callId -> 卡片（保持到达顺序内联） */
    private val toolCards = LinkedHashMap<String, ToolCallCard>()

    init {
        setupAppearance()
        log.debug(
            "new turn bubble key=$messageId isMy=$isMyMessage " +
                "firstType=${firstMessage.type} contentLen=${firstMessage.content.length}"
        )
        if (!isMyMessage) {
            add(AuthorRow(firstMessage))
            add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.MEDIUM)))
        }
        route(firstMessage, streaming = false)
    }

    /**
     * 上游推送新消息时按原始 id 就地并入；签名未变则不动。
     *
     * reasoning → 思考块新增/更新一轮；tool → 对应卡片内联/原位更新；text → 正文区。
     */
    fun syncWith(message: ChatMessage) {
        val signature = signatureOf(message)
        if (appliedSignatures[message.id] == signature) return
        route(message, streaming = true)
        appliedSignatures[message.id] = signature
    }

    /** 气泡是否包含某原始消息 id（轮次合并后搜索 / 定位的路由依据） */
    fun containsId(id: String): Boolean = id == messageId || id in memberIds

    private fun signatureOf(message: ChatMessage): String =
        if (message.isToolMessage()) message.tool?.let(::toolSignature) ?: "" else message.content

    /** 路由一条消息到对应内容块（构造首条 / 流式并入共用） */
    private fun route(message: ChatMessage, streaming: Boolean) {
        memberIds += message.id
        when {
            message.isAIThinkingMessage() -> applyReasoning(message.id, message.content, streaming)
            message.isToolMessage() -> message.tool?.let(::applyTool)
            message.isTextMessage() -> applyText(message)
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

        // 不显式设置 minimumSize：高度为 0 时 GridBag 在空间不足回退 MINSIZE
        // 布局会把整个气泡 unmap 成 0x0；宽度/高度兜底统一走 getMinimumSize
        maximumSize = Dimension(JBUI.scale(ChatUIConstants.MessageBubble.MAX_WIDTH), Int.MAX_VALUE)
    }

    override fun getMinimumSize(): Dimension {
        val natural = super.getMinimumSize()
        return Dimension(
            maxOf(natural.width, JBUI.scale(ChatUIConstants.MessageBubble.MIN_WIDTH)),
            // 高度兜底 ≥1，避免子组件最小高度为 0 时气泡被 GridBag 整体置零
            natural.height.coerceAtLeast(1)
        )
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

    // ==================== 思考块（多轮统一） ====================

    /**
     * 并入一轮思考。
     *
     * 所有轮次均无文本时显示思考动画；首个非空轮次到达时把动画替换为思考块，多轮
     * 文本在同一块内以细分隔线区分（[ReasoningSection.setRounds]）。流式并入展开，
     * 构造首条历史内容默认折叠。
     */
    private fun applyReasoning(rawId: String, content: String, streaming: Boolean) {
        reasoningRounds[rawId] = content
        val rounds = reasoningRounds.values.filter { it.isNotBlank() }
        if (rounds.isEmpty()) {
            if (reasoningSection == null && thinkingIndicator == null) addIndicator()
            return
        }
        if (reasoningSection == null) buildReasoningSection(expanded = streaming)
        reasoningSection!!.setRounds(rounds, expand = streaming)
        revalidate()
        repaint()
    }

    /** 程序化折叠思考区（执行终态 / 非运行态对账时调用），仍可手动展开查看 */
    fun completeReasoning() {
        reasoningSection?.let {
            it.complete()
            revalidate()
            repaint()
        }
    }

    private fun addIndicator() {
        ThinkingIndicator().also {
            thinkingIndicator = it
            insertComponent(it)
        }
    }

    private fun buildReasoningSection(expanded: Boolean) {
        dismissIndicator()
        ReasoningSection(expanded).also {
            reasoningSection = it
            // 思考块恒定位于作者行之后（助手气泡 index=2；用户气泡无思考场景，尾部）
            add(it, if (isMyMessage) componentCount else 2)
        }
    }

    // ==================== 工具卡片（内联，原位更新） ====================

    /**
     * 并入工具卡片：新卡片按到达顺序插到思考块之后、已有卡片队列末尾（正文之前）；
     * 已存在的卡片（状态 / 输出更新）在相同位置原位替换，不引起整体重排。
     */
    private fun applyTool(tool: ToolCallDto) {
        if (tool.callId.isBlank()) return
        val existing = toolCards[tool.callId]
        if (existing != null) {
            val index = indexOfComponent(existing)
            remove(existing)
            ToolCallCard(tool).also {
                toolCards[tool.callId] = it
                add(it, index)
            }
        } else {
            ToolCallCard(tool).also {
                toolCards[tool.callId] = it
                add(it, toolInsertIndex())
            }
        }
        revalidate()
        repaint()
    }

    private fun toolInsertIndex(): Int {
        toolCards.values.lastOrNull()?.let { return indexOfComponent(it) + 1 }
        val anchor = reasoningSection ?: thinkingIndicator
        if (anchor != null) return indexOfComponent(anchor) + 1
        // 无作者行的用户气泡不存在工具；助手气泡作者行 + strut 之后
        return if (isMyMessage) componentCount else 2
    }

    /** 子组件在本气泡中的下标（未挂载返回 -1） */
    private fun indexOfComponent(component: java.awt.Component): Int = components.indexOf(component)

    // ==================== 正文 + 时间行 ====================

    /**
     * 并入正文（markdown）。正文开始输出即思考结束：折叠思考块、撤掉残留动画；
     * 正文容器不存在时首帧构建（含时间行），已存在则只重建容器内块（不脱离父布局）。
     */
    private fun applyText(textMessage: ChatMessage) {
        reasoningSection?.complete()
        dismissIndicator()
        val container = contentContainer
        if (container == null) {
            appendContentContainer(textMessage)
        } else {
            populateContentContainer(container, textMessage.content)
        }
        revalidate()
        repaint()
    }

    /** 插入组件：助手气泡恒定位于作者行 + strut 之后（index=2），用户气泡尾部追加 */
    private fun insertComponent(component: JComponent) {
        add(component, if (isMyMessage) componentCount else 2)
    }

    /** 追加正文容器 + 时间行（正文首帧；思考块 / 工具卡片不受影响） */
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

    /** 撤掉思考动画（思考块/正文首帧 / 气泡销毁）：组件移除并释放 animator，防 ROOT 泄漏 */
    private fun dismissIndicator() {
        thinkingIndicator?.let {
            remove(it)
            it.dispose()
        }
        thinkingIndicator = null
    }

    /**
     * 气泡被列表删除/清空时释放内部 Disposable 子组件（思考动画），
     * 避免 animator 注册树残留到 ROOT_DISPOSABLE（IDE 关闭时泄漏报警）。
     */
    override fun dispose() {
        dismissIndicator()
    }

    /**
     * 构建正文容器（首次渲染）
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
     * 往**已挂载**的正文容器填充当前 markdown 分段（流式增量复用：容器不脱离父布局）
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

/** 工具卡片签名：由「状态 + 入参 + 输出」决定是否需要重渲染 */
private fun toolSignature(tool: ToolCallDto): String = "${tool.status}|${tool.input}|${tool.output}"

/** 思考消息 id 后缀（与后端 SessionStreamState.REASONING_ID_SUFFIX 约定一致） */
private const val REASONING_ID_SUFFIX = "#reasoning"
