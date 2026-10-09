package com.ayongw.idea.opencode.frontend.chatApp.ui.bubble

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ContextUsageFormatter
import com.ayongw.idea.opencode.shared.SessionUsageDto
import com.ayongw.idea.opencode.shared.TokenUsageDto
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal class AuthorName(message: ChatMessage) : JBLabel() {
    init {
        text = if (message.isMyMessage) {
            OpencodeFrontendBundle.message("chat.message.author.me")
        } else {
            message.author
        }

        font = JBFont.small().asBold()
        foreground = ChatAppColors.Text.authorName
        alignmentX = LEFT_ALIGNMENT
    }
}

/**
 * 助手消息头部：头像 + 名称（对齐参考样式 Agent/Kiro 的标题行）。
 */
internal class AuthorRow(message: ChatMessage) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(ChatUIConstants.MessageBubble.AVATAR_SIZE))

        add(AgentAvatar(message.author))
        add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.MessageBubble.AVATAR_GAP)))
        add(JBLabel(message.author).apply {
            font = JBFont.small().asBold()
            foreground = ChatAppColors.Text.authorName
        })
        add(Box.createHorizontalGlue())
    }
}

/** 助手头像：圆角方块 + 名称首字母（无需图标资源，随主题取色） */
internal class AgentAvatar(private val name: String) : JComponent() {
    init {
        alignmentX = LEFT_ALIGNMENT
        val side = JBUI.scale(ChatUIConstants.MessageBubble.AVATAR_SIZE)
        preferredSize = Dimension(side, side)
        minimumSize = Dimension(side, side)
        maximumSize = Dimension(side, side)
        toolTipText = name
    }

    override fun paintComponent(g: Graphics) {
        val g2d = g.create() as Graphics2D
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val corner = JBUI.scale(ChatUIConstants.Spacing.SMALL).toFloat()
        g2d.color = ChatAppColors.Avatar.background
        g2d.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), corner, corner))

        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "A"
        g2d.font = JBFont.small().asBold()
        g2d.color = ChatAppColors.Avatar.foreground
        val metrics = g2d.fontMetrics
        g2d.drawString(
            initial,
            (width - metrics.stringWidth(initial)) / 2,
            (height - metrics.height) / 2 + metrics.ascent
        )
        g2d.dispose()
    }
}
/**
 * 消息末尾的单行页脚：**时间 + 本次 token 合并到一行**。
 *
 * - 用户消息：只有时间，右对齐（页脚在气泡之外，见 [MessageBubble.paintComponent]）
 * - 助手消息：`token · 时间`，左对齐，与助手消息左对齐的正文风格一致
 *
 * token 缺失（用户消息 / 流式尚未产出）时自动隐藏该段，不会留下多余分隔符。
 */
class MessageFooter(message: ChatMessage) : JPanel() {

    private val timeLabel = JBLabel(message.formattedTime())
    private val tokenLabel = JBLabel()

    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        if (message.isMyMessage) {
            // 用户消息：仅时间，右对齐（与原 TimeStampLabel 行为一致）
            add(Box.createHorizontalGlue())
            add(timeLabel)
        } else {
            add(tokenLabel)
            add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(timeLabel)
        }

        listOf(timeLabel, tokenLabel).forEach {
            it.font = JBFont.small()
            it.foreground = ChatAppColors.Text.timestamp
        }
        update(message)
    }

    /** 刷新 token 段（用量随终态对账补齐，正文未变时也要更新） */
    fun update(message: ChatMessage) {
        if (message.isMyMessage) return
        val usage = SessionUsageDto(
            tokens = message.usage ?: TokenUsageDto(),
            cost = message.costUsd
        )
        val summary = ContextUsageFormatter.summary(usage)
        tokenLabel.text = summary
        tokenLabel.toolTipText = summary.takeIf { it.isNotBlank() }
            ?.let { ContextUsageFormatter.detail(usage) }
        tokenLabel.isVisible = summary.isNotBlank()
    }
}
