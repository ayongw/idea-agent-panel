package com.ayongw.plugins.opencode.frontend.chatApp.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.ayongw.plugins.opencode.shared.ChatMessage
import com.ayongw.plugins.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.plugins.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Rectangle
import javax.swing.Box
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane

class ChatList(private val project: Project) : JPanel() {
    private val messagesContainer: JPanel
    private val scrollPane: JScrollPane
    private val emptyPlaceholder: JPanel
    private val cardPanel: JPanel

    private val messageBubbles = mutableMapOf<String, MessageBubble>()

    /** 流式渲染控制器 */
    private val streamingController: StreamingRenderController

    /** UI 协程作用域 */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

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

    private fun createMessagesContainer() = JPanel().apply {
        layout = GridBagLayout()
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
        if (messages.isEmpty()) {
            clearMessages()
            return
        }

        if (messageBubbles.isEmpty()) {
            showMessagesPanel()
        }

        // 检测流式消息并更新控制器
        detectAndHandleStreaming(messages)

        removeDeletedMessages(messages)
        removeSpaceFillerIfPresent()

        addNewMessages(messages)

        refresh()
        scrollToBottom()
    }

    /**
     * 检测流式消息并同步到 StreamingRenderController
     */
    private fun detectAndHandleStreaming(messages: List<ChatMessage>) {
        // 查找当前正在流式的消息
        val streamingMessages = messages.filter { msg ->
            // 检查消息是否为 AI 且内容正在增长（简单启发式）
            !msg.isMyMessage && msg.type == ChatMessage.ChatMessageType.TEXT && isLikelyStreaming(msg)
        }

        // 这里可以添加更复杂的流式检测逻辑
        // 目前由后端模拟流式，前端通过消息内容变化检测
    }

    /**
     * 简单启发式：判断消息是否可能正在流式传输
     * 实际项目中应通过事件流或专门字段判断
     */
    private fun isLikelyStreaming(message: ChatMessage): Boolean {
        // 如果消息内容较短且以不完整句子结尾，可能正在流式
        return message.content.length < 500 && !message.content.endsWith(".") && !message.content.endsWith("。")
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
                gbc.anchor = if (message.isMyMessage) GridBagConstraints.EAST else GridBagConstraints.WEST

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

    private fun scrollToBottom() {
        val rect = Rectangle(0, messagesContainer.height - 1, 1, messagesContainer.height)
        messagesContainer.scrollRectToVisible(rect)
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
        messagesContainer.repaint()
    }
}