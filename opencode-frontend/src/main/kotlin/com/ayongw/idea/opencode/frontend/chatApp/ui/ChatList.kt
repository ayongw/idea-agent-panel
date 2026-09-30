package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.openapi.Disposable
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
import kotlinx.coroutines.cancel
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

/**
 * 消息列表容器：卡片切换、按消息 id 同步/增删气泡、滚动与布局。
 *
 * 生命周期（TSD-30 §5.4）：实现 [Disposable]，由装配方（[OpenCodeChatApp]）注册到面板
 * 生命周期之下；[dispose] 负责取消内部协程 scope 并停掉流式刷新定时器。
 */
class ChatList(private val project: Project) : JPanel(), Disposable {
    private val messagesContainer: JPanel
    private val scrollPane: JScrollPane
    private val emptyPlaceholder: JPanel
    private val cardPanel: JPanel

    private val messageBubbles = mutableMapOf<String, MessageBubble>()

    /** 消息 id 顺序的唯一真源：gridy 由它派生（TSD-30 §5.2，消除下标错配） */
    private val listModel = MessageListModel()

    /** 粘底判定（TSD-30 §5.2）：用户不在底部时不抢滚动 */
    private val scrollPolicy = ScrollPolicy(JBUI.scale(ScrollPolicy.DEFAULT_THRESHOLD))

    /** 刷新合并（TSD-30 §4.2 C-刷新）：窗口内多次 setMessages 合并为一次布局 + 一次滚动判定 */
    private val updateCoalescer = ListUpdateCoalescer()

    /** 底部占位 filler 的显式引用（不再靠组件数量推断） */
    private val filler = Box.createVerticalGlue()

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
        log.debug(
            "setMessages: ${messages.size} 条 = " +
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

        // 集合变化（新增/删除/重排）才统一重挂；流式内容变化走 syncExistingMessages，不重挂容器
        val ids = messages.map { it.id }
        if (listModel.ids != ids) {
            removeDeletedMessages(messages)
            listModel.sync(ids)
            addNewMessages(messages)
            relayoutMessages()
        }

        // 布局与滚动判定经合并器收口（窗口内一次布局 + 一次滚动）
        if (updateCoalescer.request()) {
            requestLayout()
            scrollToBottom()
        }
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


    /** 只把「新消息」建成气泡缓存；挂载顺序由 [relayoutMessages] 统一决定 */
    private fun addNewMessages(messages: List<ChatMessage>) {
        messages.forEach { message ->
            if (message.id !in messageBubbles) {
                messageBubbles[message.id] = MessageBubble(message)
            }
        }
    }

    private fun removeDeletedMessages(messages: List<ChatMessage>) {
        val currentIds = messages.map { it.id }.toSet()
        messageBubbles.keys
            .filter { it !in currentIds }
            .forEach { id -> messageBubbles.remove(id) }
    }

    /**
     * 按模型顺序统一重排：gridy 由 [MessageListModel] 索引派生，filler 显式引用。
     *
     * 气泡对象保持不变（不重建，避免闪烁），只重新挂载；增删后一律走这里，
     * 不再依赖「全表下标」或「组件数量推断 filler」（P1-4）。
     */
    private fun relayoutMessages() {
        messagesContainer.removeAll()
        val gbc = baseConstraints()
        listModel.ids.forEachIndexed { index, id ->
            val bubble = messageBubbles[id] ?: return@forEachIndexed
            gbc.gridy = index
            if (bubble.isMy) {
                // 用户消息：气泡自适应宽度，右对齐
                gbc.anchor = GridBagConstraints.EAST
                gbc.fill = GridBagConstraints.NONE
            } else {
                // 助手消息：整行块（无气泡底），占满可视宽度
                gbc.anchor = GridBagConstraints.WEST
                gbc.fill = GridBagConstraints.HORIZONTAL
            }
            messagesContainer.add(bubble, gbc)
        }
        gbc.gridy = listModel.size
        gbc.weighty = 1.0
        gbc.anchor = GridBagConstraints.NORTHWEST
        messagesContainer.add(filler, gbc)
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

    /**
     * 粘底滚动（最新消息）：仅在用户已在底部（阈值内）时执行，上翻阅读期间不抢滚动（TSD-30 §5.2）。
     *
     * 必须在 EDT 上「布局完成之后」执行：`setMessages` 里刚 add 的气泡此刻还没有
     * bounds，直接 `scrollRectToVisible` 会按旧高度滚动，落到空白区域。
     */
    private fun scrollToBottom() {
        ApplicationManager.getApplication().invokeLater {
            val viewport = scrollPane.viewport
            if (messagesContainer.height > 0 &&
                scrollPolicy.shouldStickToBottom(
                    viewPositionY = viewport.viewPosition.y.toInt(),
                    extentHeight = viewport.extentSize.height,
                    viewSizeHeight = viewport.viewSize.height,
                )
            ) {
                messagesContainer.scrollRectToVisible(
                    Rectangle(0, messagesContainer.height - 1, 1, messagesContainer.height)
                )
            }
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
        listModel.sync(emptyList())
        streamingController.cancelStreaming()
        showEmptyPanel()
        requestLayout()
    }

    /** 布局单一入口（TSD-30 §5.1）：revalidate + 几何自愈兜底 + repaint 收敛到一处 */
    private fun requestLayout() {
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
        log.debug(
            "ensureLaidOut: forced missing=$missingGeometry valid=${messagesContainer.isValid} " +
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

    override fun dispose() {
        streamingController.dispose()
        uiScope.cancel()
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