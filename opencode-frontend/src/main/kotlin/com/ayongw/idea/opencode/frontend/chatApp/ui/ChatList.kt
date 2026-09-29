package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import javax.swing.Box
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable
import javax.swing.SwingUtilities

class ChatList(private val project: Project) : JPanel() {
    private val messagesContainer: JPanel
    private val scrollPane: JScrollPane
    private val emptyPlaceholder: JPanel
    private val cardPanel: JPanel

    private val messageBubbles = mutableMapOf<String, MessageBubble>()

    /** 诊断用：上次打印逐气泡几何时的气泡数量（只在与上次不同时打印，避免刷屏） */
    private var lastLoggedBubbleCount = -1

    /** 流式渲染控制器 */
    private val streamingController: StreamingRenderController

    /** UI 协程作用域 */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val log = Logger.getInstance(ChatList::class.java)

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_MESSAGES = "messages"
    }

    init {
        setupAppearance()

        messagesContainer = createMessagesContainer()
        scrollPane = createScrollPane()
        emptyPlaceholder = createEmptyPlaceholder()

        cardPanel = JPanel(CardLayout()).apply {
            add(emptyPlaceholder, CARD_EMPTY)
            add(scrollPane, CARD_MESSAGES)
        }

        add(cardPanel, BorderLayout.CENTER)

        // 初始化流式渲染控制器
        streamingController = StreamingRenderController(
            project = project,
            uiScope = uiScope,
            onMessageUpdate = { messageId, content ->
                messageBubbles[messageId]?.updateStreamingText(content)
            },
            onMessageComplete = { messageId ->
                // 流式完成，可选：触发最终渲染优化
            },
            onReasoningUpdate = { messageId, content ->
                messageBubbles[messageId]?.updateReasoningContent(content)
            },
            onReasoningComplete = { messageId ->
                // 推理完成
            }
        )
    }

    private fun setupAppearance() {
        layout = BorderLayout()
        background = ChatAppColors.Panel.background
    }

    private fun createMessagesContainer() = MessagesContainer().apply {
        background = ChatAppColors.Panel.background
        isOpaque = true
    }

    private fun createScrollPane() = JBScrollPane(messagesContainer).apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = null
        isVisible = false
        viewport.isOpaque = true
        viewport.background = ChatAppColors.Panel.background
    }

    private fun createEmptyPlaceholder() = JPanel(GridBagLayout()).apply {
        background = ChatAppColors.Panel.background
        add(
            JLabel(OpencodeFrontendBundle.message("chat.start.conversation")).apply {
                foreground = ChatAppColors.Text.disabled
                font = font.deriveFont(16f)
            }
        )
        isVisible = true
    }

    fun setMessages(messages: List<ChatMessage>) {
        log.info(
            "[diag] setMessages: ${messages.size} 条 = " +
                messages.joinToString { "${it.id.take(16)}/${it.type}/${it.content.length}" }
        )

        if (messages.isEmpty()) {
            clearMessages()
            return
        }

        if (messageBubbles.isEmpty()) {
            showMessagesPanel()
        }

        // 已有气泡：内容变化时就地重渲染（事件流累积的流式内容）
        syncExistingMessages(messages)

        removeDeletedMessages(messages)
        removeSpaceFillerIfPresent()

        addNewMessages(messages)

        refresh()
        scrollToBottom()
    }

    /**
     * 已存在的气泡：内容变化时就地重渲染（流式正文 / 推理 / 工具卡片运行中→完成）。
     *
     * 事件流按消息 id 推送累计全文，这里只做「内容是否变化」的比较，
     * 不做全量 diff（新消息由 [addNewMessages] 负责建气泡）。
     */
    private fun syncExistingMessages(messages: List<ChatMessage>) {
        messages.forEach { message ->
            messageBubbles[message.id]?.syncWith(message)
        }
    }

    fun updateSearchHighlights(searchState: SearchState) {
        val resultIds = searchState.searchResultIds
        val currentId = searchState.currentSelectedSearchResultId

        messageBubbles.forEach { (messageId, bubble) ->
            val isMatching = resultIds.contains(messageId)
            val isHighlighted = messageId == currentId

            bubble.updateSearchState(isMatching, isHighlighted)
        }
    }


    private fun addNewMessages(messages: List<ChatMessage>) {
        val gbc = baseConstraints()

        messages.forEachIndexed { index, message ->
            if (message.id !in messageBubbles) {
                val bubble = MessageBubble(message)
                messageBubbles[message.id] = bubble

                gbc.gridy = index
                if (message.isMyMessage) {
                    // 用户消息：气泡自适应宽度，右对齐
                    gbc.anchor = GridBagConstraints.EAST
                    gbc.fill = GridBagConstraints.NONE
                } else {
                    // 助手消息：整行块（无气泡底），占满可视宽度，长内容在块内裁剪/滚动
                    gbc.anchor = GridBagConstraints.WEST
                    gbc.fill = GridBagConstraints.HORIZONTAL
                }

                messagesContainer.add(bubble, gbc)
            }
        }

        fillRemainingSpace(messages.size, gbc)
    }

    private fun removeDeletedMessages(messages: List<ChatMessage>) {
        val currentIds = messages.map { it.id }.toSet()
        messageBubbles.keys
            .filter { it !in currentIds }
            .forEach { id ->
                messageBubbles.remove(id)?.let { messagesContainer.remove(it) }
            }
    }

    private fun showEmptyPanel() {
        (cardPanel.layout as CardLayout).show(cardPanel, CARD_EMPTY)
    }

    private fun showMessagesPanel() {
        (cardPanel.layout as CardLayout).show(cardPanel, CARD_MESSAGES)
    }

    private fun baseConstraints() = GridBagConstraints().apply {
        gridx = 0
        weightx = 1.0
        weighty = 0.0
        fill = GridBagConstraints.NONE
        insets = JBUI.insets(ChatUIConstants.Spacing.TINY)
    }

    private fun fillRemainingSpace(row: Int, gbc: GridBagConstraints) {
        gbc.gridy = row
        gbc.weighty = 1.0
        gbc.anchor = GridBagConstraints.NORTHWEST
        messagesContainer.add(Box.createVerticalGlue(), gbc)
    }

    private fun removeSpaceFillerIfPresent() {
        if (messagesContainer.componentCount > messageBubbles.size) {
            messagesContainer.remove(messagesContainer.componentCount - 1)
        }
    }

    /**
     * 滚动到底部（最新消息）。
     *
     * 必须在 EDT 上「布局完成之后」执行：`setMessages` 里刚 add 的气泡此刻还没有
     * bounds，直接 `scrollRectToVisible` 会按旧高度滚动，落到空白区域。
     */
    private fun scrollToBottom() {
        ApplicationManager.getApplication().invokeLater {
            if (messagesContainer.height > 0) {
                messagesContainer.scrollRectToVisible(
                    Rectangle(0, messagesContainer.height - 1, 1, messagesContainer.height)
                )
            }
            logGeometry("after-scroll")
        }
    }

    /** 诊断日志：打印容器/视口/各气泡的真实几何，用于排查「消息区空白」 */
    private fun logGeometry(tag: String) {
        val viewport = scrollPane.viewport
        log.info(
            "[diag] geometry($tag): chatList=${size.width}x${size.height} showing=$isShowing " +
                "card=${visibleCard()} bubbles=${messageBubbles.size} " +
                "container=${messagesContainer.size.width}x${messagesContainer.size.height} " +
                "pref=${messagesContainer.preferredSize.width}x${messagesContainer.preferredSize.height} " +
                "valid=${messagesContainer.isValid} " +
                "viewport=${viewport.extentSize.width}x${viewport.extentSize.height} " +
                "viewPos=${viewport.viewPosition.x},${viewport.viewPosition.y} " +
                "viewSize=${viewport.viewSize.width}x${viewport.viewSize.height}"
        )
        if (messageBubbles.size == lastLoggedBubbleCount) return
        lastLoggedBubbleCount = messageBubbles.size
        val gridBag = messagesContainer.layout as? GridBagLayout
        messagesContainer.components.forEach { child ->
            val gbc = gridBag?.getConstraints(child)
            log.info(
                "[diag]   child ${child.javaClass.simpleName}: " +
                    "bounds=${child.bounds.x},${child.bounds.y},${child.bounds.width}x${child.bounds.height} " +
                    "pref=${child.preferredSize.width}x${child.preferredSize.height} visible=${child.isVisible} " +
                    "grid=(${gbc?.gridx},${gbc?.gridy}) weight=(${gbc?.weightx},${gbc?.weighty}) " +
                    "fill=${gbc?.fill} anchor=${gbc?.anchor}"
            )
        }
    }

    private fun visibleCard(): String = when (cardPanel.components.firstOrNull { it.isVisible }) {
        scrollPane -> CARD_MESSAGES
        null -> "none"
        else -> CARD_EMPTY
    }

    fun scrollToMessage(messageId: String) {
        val bubble = messageBubbles[messageId]
        bubble?.scrollRectToVisible(bubble.bounds)
    }

    private fun clearMessages() {
        messagesContainer.removeAll()
        messageBubbles.clear()
        streamingController.cancelStreaming()
        showEmptyPanel()
        refresh()
    }

    private fun refresh() {
        messagesContainer.revalidate()
        ensureLaidOut()
        messagesContainer.repaint()
    }

    /**
     * 保证容器子树已完成布局。
     *
     * 实测结论（JDK 21 源码 + `[diag]` 日志）：
     * `Container.validate()` 的条件是 `!isValid() && peer != null`——轻量组件（scroll pane 里的 JPanel）
     * `peer == null`，所以 `validate()` 是**空操作**；`revalidate()` 的延迟校验在该链路里同样没落到
     * `layoutContainer`。结果是视口按 `preferredSize` 给容器 `setSize`（滚动条正常），但气泡 `bounds`
     * 恒为 `0x0`，一个都画不出来（整屏只剩面板底色）。
     *
     * 因此这里不依赖 Swing 的校验机制，直接同步跑布局。
     */
    private fun ensureLaidOut() {
        if (!SwingUtilities.isEventDispatchThread() || messagesContainer.width <= 0) return
        val missingGeometry = messageBubbles.values.any { it.parent === messagesContainer && it.width == 0 }
        if (messagesContainer.isValid && !missingGeometry) return
        messagesContainer.revalidate()
        forceLayout(messagesContainer)
        log.info(
            "[diag] ensureLaidOut: forced missing=$missingGeometry valid=${messagesContainer.isValid} " +
                "children=${messagesContainer.componentCount} size=${messagesContainer.size.width}x${messagesContainer.size.height}"
        )
    }

    /**
     * 递归强制布局（`doLayout` 直接调布局管理器，绕开 isValid / RepaintManager 的延迟校验）。
     */
    private fun forceLayout(container: Container) {
        container.doLayout()
        container.components.forEach { child ->
            if (child is Container && child.isVisible) forceLayout(child)
        }
    }
}

/**
 * 消息容器：宽度跟随视口（`tracksViewportWidth`），高度由内容决定。
 *
 * 若不跟随视口宽度，GridBag 的列宽会被「最宽的气泡」撑开（长代码行/长文本可达数千像素），
 * 视口横向又禁止滚动，结果就是整屏只看到气泡底色而看不到内容。
 */
private class MessagesContainer : JPanel(GridBagLayout()), Scrollable {
    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        JBUI.scale(ChatUIConstants.LargeContent.CODE_LINE_HEIGHT)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        visibleRect.height

    /** 宽度跟随视口，避免超宽内容把视口撑爆 */
    override fun getScrollableTracksViewportWidth(): Boolean = true

    /** 高度按内容，超出才出现纵向滚动条 */
    override fun getScrollableTracksViewportHeight(): Boolean = false
}