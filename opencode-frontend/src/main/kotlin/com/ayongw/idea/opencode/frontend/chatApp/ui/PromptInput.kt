package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.AttachmentPicker
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ButtonUtils
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppIcons
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.MentionSupport
import com.ayongw.idea.opencode.frontend.chatApp.viewmodel.MessageInputState
import com.ayongw.idea.opencode.shared.ContextFileDto
import com.ayongw.idea.opencode.shared.PendingPermissionDto
import com.ayongw.idea.opencode.shared.PermissionResponse
import com.ayongw.idea.opencode.shared.SessionUsageDto
import java.awt.BorderLayout
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
import javax.swing.border.LineBorder
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener

class PromptInput(
    private val onInputChanged: (String) -> Unit,
    private val onSend: (String) -> Unit,
    private val onStop: (String) -> Unit,
    private val onHistorySelect: (String) -> Unit = {},
    private val onPermissionDecide: (requestId: String, response: PermissionResponse) -> Unit = { _, _ -> },
    private val contextChipBar: ContextChipBar,
    private val inputToolbar: InputToolbar,
    /** 工作区根目录（本机文件选择器的初始目录） */
    private val basePath: String? = null,
    /** 会话附件新增（＋ 按钮选择的本机文件/目录） */
    private val onAddAttachments: (List<ContextFileDto>) -> Unit = {},
    /** 会话附件移除 */
    private val onRemoveAttachment: (ContextFileDto) -> Unit = {},
    /** `/` 候选需要加载（命令 / 技能 / 规则） */
    private val onMentionCandidatesNeeded: () -> Unit = {},
    /** `#` 候选检索（query 为空表示列工作区根目录） */
    private val onSearchWorkspace: (String) -> Unit = {},
    /** 进入目录（相对路径；null 表示工作区根目录） */
    private val onBrowseDirectory: (String?) -> Unit = {}
) : JPanel() {

    private val textArea: JBTextArea
    private val scrollPane: JBScrollPane
    private val sendButton: JButton
    private val attachButton: JButton
    private val usageIndicator = ContextUsageIndicator()
    private val permissionPrompt = PermissionPrompt { requestId, response -> onPermissionDecide(requestId, response) }
    private val mentionPopup = MentionPopup()

    /** `/` 候选（命令 / 技能）与 `#` 候选（规则 / 工作区文件与目录） */
    private var slashCandidates: List<MentionSupport.Candidate> = emptyList()
    private var ruleCandidates: List<MentionSupport.Candidate> = emptyList()
    private var fileCandidates: List<MentionSupport.Candidate> = emptyList()

    /** 会话附件（＋ 按钮加入），仅用于 chips 右段 */
    private var sessionAttachments: List<ContextFileDto> = emptyList()

    /** 当前输入框文本（会话草稿切走时读取） */
    fun currentText(): String = textArea.text

    /** 会话草稿恢复：整段替换输入框文本，同步按钮状态与 ViewModel 草稿 */
    fun setDraftText(text: String) {
        textArea.text = text
        textArea.caretPosition = text.length
        sendButton.isEnabled = currentState != MessageInputState.Disabled && text.isNotBlank()
        onInputChanged(text)
    }

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
        attachButton = createAttachButton()

        add(createChipRow(), BorderLayout.NORTH)
        add(createInputArea(), BorderLayout.CENTER)
        add(createToolbarRow(), BorderLayout.SOUTH)

        contextChipBar.onRemoveMention = { span -> removeMention(span) }
        contextChipBar.onRemoveAttachment = { file -> onRemoveAttachment(file) }

        setupKeyBindings()
    }

    /**
     * 上下文条（可横向滚动，chips 多时不会挤占输入区）
     */
    private fun createChipRow() = JBScrollPane(contextChipBar).apply {
        border = JBUI.Borders.empty()
        isOpaque = false
        viewport.isOpaque = false
        verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
        horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
        preferredSize = Dimension(0, JBUI.scale(CHIP_ROW_HEIGHT))
        minimumSize = Dimension(0, JBUI.scale(CHIP_ROW_HEIGHT))
    }

    /**
     * 输入区：上方权限确认条（有待决项时才显示），下方文本区 + 发送按钮
     */
    private fun createInputArea() = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(permissionPrompt, BorderLayout.NORTH)
        add(createInputRow(), BorderLayout.CENTER)
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
     * 底部工具条一行：左侧附件按钮 + 审核类型 / 模式 / 模型，右侧会话用量与上下文占比
     */
    private fun createToolbarRow() = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(JBUI.scale(ChatUIConstants.Spacing.MEDIUM))

        val left = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(
                JPanel().apply {
                    isOpaque = false
                    layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.X_AXIS)
                    add(attachButton)
                    add(javax.swing.Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.NORMAL)))
                    add(inputToolbar)
                },
                BorderLayout.WEST
            )
        }
        add(left, BorderLayout.WEST)
        add(usageIndicator, BorderLayout.EAST)
    }

    /** 更新会话用量展示（由外层订阅 ViewModel 状态后调用） */
    fun updateUsage(usage: SessionUsageDto?) {
        usageIndicator.updateUsage(usage)
    }

    /** 更新待决权限确认条（由外层订阅 ViewModel 状态后调用） */
    fun updatePendingPermission(permission: PendingPermissionDto?) {
        permissionPrompt.update(permission)
    }

    /** 更新会话附件（chips 右段） */
    fun updateSessionAttachments(attachments: List<ContextFileDto>) {
        sessionAttachments = attachments
        refreshChips()
    }

    /** 更新 `/` 候选（命令 / 技能 / 规则） */
    fun updateMentionCandidates(candidates: List<MentionSupport.Candidate>) {
        slashCandidates = candidates.filter { it.symbol == MentionSupport.COMMAND_SYMBOL }
        ruleCandidates = candidates.filter { it.symbol == MentionSupport.PATH_SYMBOL }
        refreshMentionPopup()
    }

    /** 更新 `#` 候选（工作区文件 / 目录） */
    fun updateWorkspaceCandidates(candidates: List<MentionSupport.Candidate>) {
        fileCandidates = candidates
        refreshMentionPopup()
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

    /** 附件按钮：选择本机任意文件 / 目录作为会话附件 */
    private fun createAttachButton() = ButtonUtils.createActionButton(
        icon = ChatAppIcons.Prompt.attach,
        tooltip = OpencodeFrontendBundle.message("chat.prompt.attach.tooltip"),
        size = ChatUIConstants.Button.SEND_BUTTON_SIZE
    ) {
        val picked = AttachmentPicker.pick(this, basePath)
        if (picked.isNotEmpty()) onAddAttachments(picked)
    }.apply { isFocusable = false }

    private fun handleTextChange() {
        if (skipInputChangeUpdate) {
            skipInputChangeUpdate = false
            return
        }

        val text = textArea.text
        onInputChanged(text)

        sendButton.isEnabled = currentState != MessageInputState.Disabled && text.isNotBlank()

        refreshChips()
        refreshMentionPopup()
    }

    private fun refreshChips() {
        contextChipBar.update(MentionSupport.spans(textArea.text), sessionAttachments)
    }

    /** 依据光标处的触发态刷新候选弹窗（`/`、`#`） */
    private fun refreshMentionPopup() {
        val trigger = MentionSupport.detectTrigger(textArea.text, textArea.caretPosition)
        if (trigger == null) {
            mentionPopup.hide()
            return
        }
        val source = when (trigger.symbol) {
            MentionSupport.COMMAND_SYMBOL -> {
                onMentionCandidatesNeeded()
                slashCandidates
            }
            else -> {
                onSearchWorkspace(trigger.query)
                ruleCandidates + fileCandidates
            }
        }
        val filtered = filterCandidates(source, trigger.query)
        mentionPopup.show(textArea, filtered)
    }

    private fun filterCandidates(
        source: List<MentionSupport.Candidate>,
        query: String
    ): List<MentionSupport.Candidate> {
        if (query.isEmpty()) return source
        return source.filter {
            it.token.contains(query, ignoreCase = true) ||
                it.label.contains(query, ignoreCase = true) ||
                it.detail?.contains(query, ignoreCase = true) == true
        }
    }

    /** 用选中的候选替换触发片段（mention 作为纯文本留在输入框内） */
    private fun applyCandidate(trigger: MentionSupport.Trigger, candidate: MentionSupport.Candidate) {
        val insertion = "${candidate.symbol}${candidate.token} "
        textArea.select(trigger.start, textArea.caretPosition)
        textArea.replaceSelection(insertion)
        skipInputChangeUpdate = true
        mentionPopup.hide()
    }

    /** 删除文本中的一处 mention（chips 左段的 `×`） */
    private fun removeMention(span: MentionSupport.Span) {
        val text = textArea.text
        if (span.end > text.length) return
        textArea.select(span.start, span.end)
        textArea.replaceSelection("")
        skipInputChangeUpdate = true
        refreshChips()
    }

    /** 进入目录：重新以该目录为基准检索（`→` 键，仅在目录项上生效） */
    private fun browseIntoDirectory() {
        val candidate = mentionPopup.currentCandidate() ?: return
        if (!candidate.token.endsWith("/")) return
        onBrowseDirectory(candidate.token.trimEnd('/'))
    }

    private fun setupKeyBindings() {
        val inputMap = textArea.getInputMap(JComponent.WHEN_FOCUSED)
        val actionMap = textArea.actionMap

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send")
        actionMap.put("send", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                acceptMentionOrSend()
            }
        })

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "newline")
        actionMap.put("newline", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                skipInputChangeUpdate = true
                textArea.insert("\n", textArea.caretPosition)
            }
        })

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_TAB, 0), "acceptMention")
        actionMap.put("acceptMention", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (mentionPopup.isVisible) acceptMention()
            }
        })

        // Up/Down：候选弹窗打开时为导航，否则为输入历史
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "historyUp")
        actionMap.put("historyUp", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (mentionPopup.isVisible) mentionPopup.moveSelection(-1) else navigateHistory(forward = false)
            }
        })

        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "historyDown")
        actionMap.put("historyDown", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (mentionPopup.isVisible) mentionPopup.moveSelection(1) else navigateHistory(forward = true)
            }
        })

        // Esc：关闭候选弹窗
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "dismissMention")
        actionMap.put("dismissMention", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                mentionPopup.hide()
            }
        })

        // Right：在目录候选上进入该目录
        inputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "browseDirectory")
        actionMap.put("browseDirectory", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                if (mentionPopup.isVisible) browseIntoDirectory() else textArea.caretPosition += 1
            }
        })
    }

    /** Enter：候选弹窗打开时确认候选，否则发送消息 */
    private fun acceptMentionOrSend() {
        if (mentionPopup.isVisible) {
            acceptMention()
            return
        }
        val text = textArea.text.trim()
        if (text.isEmpty()) return
        when (currentState) {
            is MessageInputState.Sending -> onStop(text)
            else -> handleSend()
        }
    }

    private fun acceptMention() {
        val trigger = MentionSupport.detectTrigger(textArea.text, textArea.caretPosition) ?: return
        val candidate = mentionPopup.currentCandidate() ?: return
        applyCandidate(trigger, candidate)
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
        mentionPopup.hide()
        refreshChips()
    }

    private fun handleStop() {
        onStop(textArea.text.trim())
    }

    private fun navigateHistory(forward: Boolean) {
        if (inputHistory.isEmpty()) return

        if (historyIndex == -1) {
            historyIndex = if (forward) 0 else inputHistory.lastIndex
        } else {
            historyIndex += if (forward) 1 else -1
        }

        historyIndex = historyIndex.coerceIn(0, inputHistory.lastIndex)
        textArea.text = inputHistory[historyIndex]
        skipInputChangeUpdate = true
        refreshChips()
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
                            background = ChatAppColors.Selection.rowHighlight
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
                    refreshChips()
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

    private companion object {
        /** 上下文条高度（单行 chips） */
        const val CHIP_ROW_HEIGHT = 30
    }
}