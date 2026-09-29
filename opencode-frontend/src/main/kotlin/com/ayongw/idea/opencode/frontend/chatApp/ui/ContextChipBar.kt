package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ButtonUtils
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppIcons
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.MentionSupport
import com.ayongw.idea.opencode.shared.ContextFileDto
import java.awt.Dimension
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * 上下文条：左段是输入框文本 mention 的投影，右段是会话附件（＋ 按钮加入）。
 *
 * - 左段为只读投影，点 `×` 删除文本中对应的 mention（真源在输入框文本）；
 * - 右段点 `×` 直接移除会话附件。
 */
class ContextChipBar : JPanel() {

    /** 点击左段 mention 的 `×` */
    var onRemoveMention: ((MentionSupport.Span) -> Unit)? = null

    /** 点击右段会话附件的 `×` */
    var onRemoveAttachment: ((ContextFileDto) -> Unit)? = null

    private var mentions: List<MentionSupport.Span> = emptyList()
    private var attachments: List<ContextFileDto> = emptyList()

    init {
        setupAppearance()
        rebuild()
    }

    private fun setupAppearance() {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(
            ChatUIConstants.Spacing.SMALL,
            ChatUIConstants.Spacing.NORMAL
        )
    }

    /** 刷新两段内容 */
    fun update(mentionSpans: List<MentionSupport.Span>, sessionAttachments: List<ContextFileDto>) {
        mentions = mentionSpans
        attachments = sessionAttachments
        rebuild()
    }

    private fun rebuild() {
        removeAll()
        mentions.forEach { span ->
            addChip(
                text = "${span.symbol}${span.token}",
                type = ChipType.MENTION,
                tooltip = span.token,
                onRemove = { onRemoveMention?.invoke(span) }
            )
        }
        if (mentions.isNotEmpty() && attachments.isNotEmpty()) addSeparator()
        attachments.forEach { file ->
            addChip(
                text = file.name,
                type = ChipType.ATTACHMENT,
                tooltip = file.path,
                onRemove = { onRemoveAttachment?.invoke(file) }
            )
        }
        if (mentions.isEmpty() && attachments.isEmpty()) {
            add(
                JBLabel(OpencodeFrontendBundle.message("chat.context.empty")).apply {
                    foreground = ChatAppColors.Text.disabled
                    font = JBFont.small()
                }
            )
        }
        add(Box.createHorizontalGlue())
        revalidate()
        repaint()
    }

    private fun addSeparator() {
        add(Box.createHorizontalStrut(ChatUIConstants.Spacing.SMALL))
        add(
            JPanel().apply {
                preferredSize = Dimension(1, JBUI.scale(16))
                maximumSize = Dimension(1, JBUI.scale(16))
                background = ChatAppColors.Text.disabled
            }
        )
        add(Box.createHorizontalStrut(ChatUIConstants.Spacing.SMALL))
    }

    private fun addChip(text: String, type: ChipType, tooltip: String, onRemove: () -> Unit) {
        add(Chip(text, type, tooltip, onRemove))
        add(Box.createHorizontalStrut(ChatUIConstants.Spacing.SMALL))
    }

    /** chip 类型决定底色 */
    private enum class ChipType(val color: java.awt.Color) {
        MENTION(ChatAppColors.Context.autoFile),
        ATTACHMENT(ChatAppColors.Context.explicitFile)
    }

    private class Chip(
        text: String,
        type: ChipType,
        tooltip: String,
        onRemove: () -> Unit
    ) : JPanel() {

        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = true
            border = JBUI.Borders.empty(2, 8, 2, 2)
            background = type.color
            putClientProperty("JComponent.roundRect", true)

            add(
                JBLabel(text).apply {
                    font = JBFont.small()
                    foreground = ChatAppColors.Context.onContextChip
                    toolTipText = tooltip
                    maximumSize = Dimension(JBUI.scale(CHIP_MAX_TEXT_WIDTH), Int.MAX_VALUE)
                }
            )

            add(
                ButtonUtils.createActionButton(
                    icon = ChatAppIcons.Context.remove,
                    tooltip = "",
                    size = Dimension(JBUI.scale(16), JBUI.scale(16))
                ) { onRemove() }
            )
        }

        private companion object {
            /** chip 文本最大宽度（超出省略），避免长路径撑满整行 */
            const val CHIP_MAX_TEXT_WIDTH = 180
        }
    }
}