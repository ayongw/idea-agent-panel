package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.bubble.MessageBubble
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.max
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Point
import java.awt.Rectangle
import javax.swing.Box
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.Scrollable

/**
 * 消息列表容器：卡片切换、按消息 id 同步/增删气泡、滚动与布局。
 *
 * 生命周期（TSD-30 §5.4）：实现 [Disposable]，由装配方（[OpenCodeChatApp]）注册到面板
 * 生命周期之下；[dispose] 负责取消内部协程 scope 并停掉流式刷新定时器。
 */
class ChatList(
    private val project: Project,
    /**
     * 刷新合并（TSD-30 §4.2 C-刷新）：窗口内多次 setMessages 合并为一次布局 + 一次滚动判定。
     *
     * 可注入：测试放大窗口即可确定性地覆盖「窗口内请求」分支，不依赖真实耗时。
     * 注意合并器是 leading-edge 节流且无 trailing 补偿，调用方不得用它挡结构性变化。
     */
    private val updateCoalescer: ListUpdateCoalescer = ListUpdateCoalescer(),
) : JPanel(), Disposable {
    private val messagesContainer: JPanel
    private val scrollPane: JScrollPane
    private val emptyPlaceholder: JPanel
    private val cardPanel: JPanel

    private val messageBubbles = mutableMapOf<String, MessageBubble>()

    /** 消息 id 顺序的唯一真源：gridy 由它派生（TSD-30 §5.2，消除下标错配） */
    private val listModel = MessageListModel()

    /** 粘底判定（TSD-30 §5.2）：用户不在底部时不抢滚动 */
    private val scrollPolicy = ScrollPolicy(JBUI.scale(ScrollPolicy.DEFAULT_THRESHOLD))

    /** 底部占位 filler 的显式引用（不再靠组件数量推断） */
    private val filler = Box.createVerticalGlue()

    /** 会话执行态（G3）：运行中思考气泡保持展开，结束后折叠（由装配方经 [setStreamRunning] 注入） */
    private var streamRunning = false

    /** 布局单一入口（TSD-30 §5.1）：revalidate + 几何自愈兜底 + repaint 收敛到一处 */
    private val layoutCoordinator: LayoutCoordinator

    /** UI 协程作用域 */
    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val log = Logger.getInstance(ChatList::class.java)

    companion object {
        private const val CARD_EMPTY = "empty"
        private const val CARD_MESSAGES = "messages"

        /** 滚动/布局自愈重试上限：覆盖首帧与切卡后 viewport validate 的调度窗口 */
        private const val SCROLL_RETRY_MAX = 3
    }

    init {
        setupAppearance()

        messagesContainer = createMessagesContainer()
        layoutCoordinator = LayoutCoordinator(messagesContainer)
        scrollPane = createScrollPane()
        emptyPlaceholder = createEmptyPlaceholder()

        cardPanel = JPanel(CardLayout()).apply {
            add(emptyPlaceholder, CARD_EMPTY)
            add(scrollPane, CARD_MESSAGES)
        }

        add(cardPanel, BorderLayout.CENTER)
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

        // 折叠在布局之前：一次布局直接按折叠后几何计算，避免折叠改高后几何 stale
        // （completeReasoning 只 revalidate 气泡自身，轻量组件链路的延迟校验不可靠，见 TSD-30）
        if (!streamRunning) {
            collapseThinkingBubbles()
        }
        // 集合变化（新增/删除/重排）才统一重挂；流式内容变化走 syncExistingMessages，不重挂容器
        val ids = messages.map { it.id }
        if (listModel.ids != ids) {
            removeDeletedMessages(messages)
            listModel.sync(ids)
            addNewMessages(messages)
            relayoutMessages()
            // 结构性变化必须立即布局，不参与节流：合并器无 trailing 补偿，
            // 命中 20ms 窗口会丢布局 → removeAll 后气泡 bounds 停留旧值，视口整屏空白
            layoutCoordinator.requestLayout(messageBubbles.values)
        } else if (updateCoalescer.request()) {
            // 纯内容更新（流式 delta）：按窗口合并为一次布局
            layoutCoordinator.requestLayout(messageBubbles.values)
        }
        scrollToBottom()
    }

    /**
     * 注入会话执行态（G3 思考折叠）：false 边沿折叠全部思考气泡；
     * setMessages 尾部的折叠兜住「终态信号先到、最后一条推理内容后到」的乱序。
     *
     * 折叠改变气泡高度，必须主动触发布局 + 滚动校准——此后可能不再有 setMessages，
     * 不补布局则几何永久 stale。
     */
    fun setStreamRunning(running: Boolean) {
        val wasRunning = streamRunning
        streamRunning = running
        if (wasRunning && !running) {
            collapseThinkingBubbles()
            if (messageBubbles.isNotEmpty()) {
                layoutCoordinator.requestLayout(messageBubbles.values)
                scrollToBottom()
            }
        }
    }

    /** 折叠当前所有思考气泡（[MessageBubble.completeReasoning] 自判是否思考消息） */
    private fun collapseThinkingBubbles() {
        messageBubbles.values.forEach { it.completeReasoning() }
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
            .forEach { id ->
                messageBubbles.remove(id)?.let { bubble ->
                    // 气泡不再显示：释放其内部 Disposable 子组件（如思考动画）
                    bubble.dispose()
                    messagesContainer.remove(bubble)
                }
            }
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
     * 粘底滚动 + 视口越界校准（最新消息）。
     *
     * 必须在 EDT 上「布局完成之后」执行：`setMessages` 里刚 add 的气泡此刻还没有
     * bounds，直接滚动会按旧高度滚到空白区域。
     *
     * 两个职责：
     * 1. **越界校准**：对账/删除使内容收缩后，旧滚动位置可能超出新内容高度，视口呈现一片空白。
     *    先把视口拉回 `maxY`，避免「整屏空白」（resize 时 RepaintManager 全量 validate 才恢复）。
     * 2. **粘底滚动**：仅当用户已在底部（阈值内）时滚到最新，上翻阅读期间不抢滚动（TSD-30 §5.2）。
     *
     * 用 `viewport.viewPosition` 直接设置（而非 `scrollRectToVisible`），语义确定、无「滚到可见」歧义。
     */
    private fun scrollToBottom() {
        scheduleScrollToBottom(attempt = 0)
    }

    /**
     * 尺寸为 0（首帧/切卡后 viewport validate 尚未执行）时不能直接放弃——
     * 若之后不再有 setMessages，布局与滚动都不会再被触发，视口永久空白。
     * 延迟重排到 EDT 队列重试（有上限），这是几何自愈的兜底入口。
     */
    private fun scheduleScrollToBottom(attempt: Int) {
        ApplicationManager.getApplication().invokeLater {
            val viewport = scrollPane.viewport
            if (messagesContainer.height <= 0 || viewport.extentSize.height <= 0) {
                if (attempt < SCROLL_RETRY_MAX && messageBubbles.isNotEmpty() && scrollPane.isVisible) {
                    scheduleScrollToBottom(attempt + 1)
                }
                return@invokeLater
            }
            val viewSizeHeight = viewport.viewSize.height
            val extentHeight = viewport.extentSize.height
            val maxY = max(0, viewSizeHeight - extentHeight)
            if (viewport.viewPosition.y > maxY) {
                // 视口越界（内容收缩）：先校准回底部
                viewport.viewPosition = Point(0, maxY)
                messagesContainer.repaint()
            }
            if (scrollPolicy.shouldStickToBottom(viewport.viewPosition.y.toInt(), extentHeight, viewSizeHeight)) {
                viewport.viewPosition = Point(0, maxY)
                // 视口已挪到底部，确保容器尺寸与内容一致（首帧消息的 viewSize 可能滞后为 0）
                layoutCoordinator.ensureLaidOut(messageBubbles.values)
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
        messageBubbles.values.forEach { it.dispose() }
        messagesContainer.removeAll()
        messageBubbles.clear()
        listModel.sync(emptyList())
        showEmptyPanel()
        layoutCoordinator.requestLayout(messageBubbles.values)
    }

    /** 布局单一入口（TSD-30 §5.1）已收敛至 [LayoutCoordinator]：动态增删子组件后
     *  只允许调 [LayoutCoordinator.requestLayout]，此处不再持有散落的布局方法 */

    override fun dispose() {
        uiScope.cancel()
        // 面板销毁：释放仍挂载的气泡（含思考动画等 Disposable 子组件）
        messageBubbles.values.forEach { it.dispose() }
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