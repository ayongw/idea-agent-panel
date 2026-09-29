package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ButtonUtils
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppIcons
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.frontend.chatApp.viewmodel.MessageInputState
import com.ayongw.idea.opencode.shared.ContextFile
import com.ayongw.idea.opencode.shared.SessionUsageDto
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.KeyStroke
import javax.swing.ListCellRenderer
import javax.swing.border.CompoundBorder
import javax.swing.border.EmptyBorder
import javax.swing.border.LineBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class PromptInput(
    private val onInputChanged: (String) -> Unit,
    private val onSend: (String) -> Unit,
    private val onStop: (String) -> Unit,
    private val onHistorySelect: (String) -> Unit = {},
    private val contextChipBar: ContextChipBar,
    private val inputToolbar: InputToolbar
) : JPanel() {

    private val textArea: JBTextArea
    private val scrollPane: JBScrollPane
    private val sendButton: JButton
    private val usageIndicator = ContextUsageIndicator()

    private var currentState: MessageInputState = MessageInputState.Enabled("")
    private var skipInputChangeUpdate = false

    /** 输入历史 */
    private val inputHistory = mutableListOf<String>()
    private var historyIndex = -1

    init {
        setupAppearance()

        textArea = createTextArea()
        scrollPane = createScrollPane(textArea)
        sendButton = createSendButton()

        add(contextChipBar, BorderLayout.NORTH)
        add(createInputRow(), BorderLayout.CENTER)
        add(createToolbarRow(), BorderLayout.SOUTH)

        setupKeyBindings()
    }

    /**
     * 输入框一行：文本区在左，发送按钮在右
     */
    private fun createInputRow() = JPanel(BorderLayout(ChatUIConstants.Spacing.MEDIUM, 0)).apply {
        isOpaque = false
        add(scrollPane, BorderLayout.CENTER)
        add(sendButton, BorderLayout.EAST)
    }

    /**
     * 底部工具条一行：左侧审核类型 / 模式 / 模型，右侧会话用量与上下文占比
     */
    private fun createToolbarRow() = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(JBUI.scale(ChatUIConstants.Spacing.MEDIUM))
        add(inputToolbar, BorderLayout.WEST)
        add(usageIndicator, BorderLayout.EAST)
    }

    /** 更新会话用量展示（由外层订阅 ViewModel 状态后调用） */
    fun updateUsage(usage: SessionUsageDto?) {
        usageIndicator.updateUsage(usage)
    }

    private fun setupAppearance() {
        layout = BorderLayout(ChatUIConstants.Spacing.MEDIUM, 0)
        border = CompoundBorder(
            LineBorder(ChatAppColors.Prompt.border, ChatUIConstants.Input.BORDER_THICKNESS, true),
            JBUI.Borders.empty(ChatUIConstants.Spacing.MEDIUM, ChatUIConstants.Spacing.NORMAL)
        )
        background = ChatAppColors.Panel.background
    }

    private fun createTextArea() = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 1
        border = JBUI.Borders.empty(
            ChatUIConstants.Input.TEXT_AREA_PAD_VERTICAL,
            ChatUIConstants.Input.TEXT_AREA_PAD_HORIZONTAL
        )

        document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent?) = handleTextChange()
            override fun removeUpdate(e: DocumentEvent?) = handleTextChange()
            override fun changedUpdate(e: DocumentEvent?) = handleTextChange()
        })
    }

    private fun createScrollPane(content: JComponent) = object : JBScrollPane(content) {
        override fun getPreferredSize(): Dimension {
            val base = super.getPreferredSize()
            val capped = base.height.coerceIn(
                ChatUIConstants.Input.MIN_HEIGHT,
                ChatUIConstants.Input.MAX_HEIGHT
            )
            return Dimension(base.width, capped)
        }
    }.apply {
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = LineBorder(
            JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(),
            ChatUIConstants.Input.BORDER_THICKNESS
        )
        minimumSize = Dimension(ChatUIConstants.Input.MIN_WIDTH, ChatUIConstants.Input.MIN_HEIGHT)
    }

    private fun createSendButton() = ButtonUtils.createActionButton(
        icon = ChatAppIcons.Prompt.send,
        tooltip = OpencodeFrontendBundle.message("chat.prompt.send.tooltip"),
        size = ChatUIConstants.Button.SEND_BUTTON_SIZE
    ) { handleButtonClick() }.apply { isEnabled = false }

    private fun handleTextChange() {
        if (skipInputChangeUpdate) {
            skipInputChangeUpdate = false
            return
        }

        val text = textArea.text
        onInputChanged(text)

        sendButton.isEnabled = currentState != MessageInputState.Disabled && text.isNotBlank()
    }

    private fun setupKeyBindings() {
        val inputMap = textArea.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = textArea.actionMap

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send")
        actionMap.put("send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                val text = textArea.text.trim()
                if (text.isEmpty()) return
                when (currentState) {
                    is MessageInputState.Sending -> onStop(text)
                    else -> handleSend()
                }
            }
        })

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "newline")
        actionMap.put("newline", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                skipInputChangeUpdate = true
                textArea.insert("\n", textArea.caretPosition)
            }
        })

        // Up/Down for history navigation
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "historyUp")
        actionMap.put("historyUp", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                navigateHistory(forward = false)
            }
        })

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "historyDown")
        actionMap.put("historyDown", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                navigateHistory(forward = true)
            }
        })
    }

    fun updateState(state: MessageInputState) {
        currentState = state

        when (state) {
            MessageInputState.Disabled -> applySendButtonStyle(SendButtonStyle.Idle, textAreaEnabled = true, forceEnabled = false)
            is MessageInputState.Enabled,
            is MessageInputState.Sent,
            is MessageInputState.SendFailed -> applySendButtonStyle(SendButtonStyle.Ready, textAreaEnabled = true, forceEnabled = false)
            is MessageInputState.Sending -> applySendButtonStyle(SendButtonStyle.Stop, textAreaEnabled = false, forceEnabled = true)
        }
    }

    private fun applySendButtonStyle(style: SendButtonStyle, textAreaEnabled: Boolean, forceEnabled: Boolean) {
        val hasText = textArea.text.isNotBlank()
        sendButton.icon = style.iconFor(hasText)
        sendButton.toolTipText = OpencodeFrontendBundle.message(style.tooltipKey)
        sendButton.isEnabled = forceEnabled || (style.allowsSend && hasText)
        textArea.isEnabled = textAreaEnabled
    }

    private fun handleButtonClick() {
        when (currentState) {
            is MessageInputState.Sending -> handleStop()
            else -> handleSend()
        }
    }

    private fun handleSend() {
        val text = textArea.text.trim()
        if (text.isEmpty()) return

        // Add to history
        addToHistory(text)

        onSend(text)
        skipInputChangeUpdate = true
        textArea.text = ""
        historyIndex = -1
    }

    private fun handleStop() {
        onStop(textArea.text.trim())
    }

    private fun navigateHistory(forward: Boolean) {
        if (inputHistory.isEmpty()) return

        if (historyIndex == -1) {
            // First navigation - save current input if not empty
            val currentText = textArea.text.trim()
            if (currentText.isNotBlank()) {
                // Temporarily store at end
            }
            historyIndex = if (forward) 0 else inputHistory.lastIndex
        } else {
            historyIndex += if (forward) 1 else -1
        }

        historyIndex = historyIndex.coerceIn(0, inputHistory.lastIndex)
        textArea.text = inputHistory[historyIndex]
        skipInputChangeUpdate = true
    }

    private fun addToHistory(text: String) {
        if (text.isBlank()) return
        // Remove if already exists
        inputHistory.remove(text)
        // Add to front
        inputHistory.add(0, text)
        // Limit history size
        if (inputHistory.size > 50) {
            inputHistory.removeAt(inputHistory.lastIndex)
        }
        historyIndex = -1
    }

    /**
     * 显示历史记录弹窗
     */
    fun showHistoryPopup() {
        if (inputHistory.isEmpty()) return

        val list = JList(inputHistory.toTypedArray()).apply {
            cellRenderer = object : ListCellRenderer<String> {
                override fun getListCellRendererComponent(
                    list: JList<out String>?,
                    value: String?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): JComponent {
                    val label = JBLabel(value ?: "").apply {
                        border = JBUI.Borders.empty(8, 12)
                        if (isSelected) {
                            background = JBColor(Color(200, 220, 255), Color(40, 60, 90))
                            isOpaque = true
                        }
                    }
                    return label
                }
            }
            selectionMode = javax.swing.ListSelectionModel.SINGLE_SELECTION
        }

        val scrollPane = JBScrollPane(list).apply {
            preferredSize = Dimension(300, 200)
        }

        val popupMenu = JPopupMenu().apply {
            add(scrollPane)
            setLightWeightPopupEnabled(true)
        }

        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                val index = list.locationToIndex(e?.point ?: return)
                if (index >= 0) {
                    val selected = inputHistory[index]
                    textArea.text = selected
                    skipInputChangeUpdate = true
                    onHistorySelect(selected)
                    popupMenu.setVisible(false)
                }
            }
        })

        popupMenu.show(textArea, 0, textArea.height)
    }

    private enum class SendButtonStyle(val tooltipKey: String, val allowsSend: Boolean) {
        Idle("chat.prompt.send.tooltip", allowsSend = false),
        Ready("chat.prompt.send.tooltip", allowsSend = true),
        Stop("chat.prompt.stop.tooltip", allowsSend = false);

        fun iconFor(hasText: Boolean): Icon = when (this) {
            Idle -> ChatAppIcons.Prompt.send
            Ready -> if (hasText) ChatAppIcons.Prompt.send else ChatAppIcons.Prompt.send
            Stop -> ChatAppIcons.Prompt.stop
        }
    }
}