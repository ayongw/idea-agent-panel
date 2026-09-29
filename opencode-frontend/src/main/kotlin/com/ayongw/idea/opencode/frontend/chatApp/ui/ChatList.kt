package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
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