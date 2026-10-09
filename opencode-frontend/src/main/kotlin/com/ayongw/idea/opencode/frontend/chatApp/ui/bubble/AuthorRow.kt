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
internal class TimeStampLabel(message: ChatMessage) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        val label = JBLabel(message.formattedTime()).apply {
            font = JBFont.small()
            foreground = ChatAppColors.Text.timestamp
        }
        add(Box.createHorizontalGlue())
        add(label)
    }
}

/**
 * 助手消息末尾的「本次 token」行：左对齐浅色，样式对齐 [TimeStampLabel]。
 *
 * 展示该条助手消息自身的 `Session.Message.Assistant.tokens` / `cost`（不是会话累计）：
 * 行内用 [ContextUsageFormatter.summary]，悬浮用 [detail] 展开输入/输出/推理/缓存读/缓存写/花费。
 * 文本格式复用底部指示器同一组纯函数，两处口径一致。
 *
 * 仅助手消息渲染（用户消息恒隐藏）；无用量时整体隐藏：BoxLayout 跳过不可见子组件，
 * 故不占高度，也不留空行。
 */
class TokenUsageRow(message: ChatMessage) : JPanel() {

    private val label = JBLabel().apply {
        font = JBFont.small()
        foreground = ChatAppColors.Text.timestamp
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
        add(label)
        update(message)
    }

    /** 更新 token 行；用量与花费皆空、或用户消息时隐藏（BoxLayout 跳过不可见组件） */
    fun update(message: ChatMessage) {
        val usage = SessionUsageDto(
            tokens = message.usage ?: TokenUsageDto(),
            cost = message.costUsd
        )
        val summary = if (message.isMyMessage) "" else ContextUsageFormatter.summary(usage)
        label.text = summary
        label.toolTipText = summary.takeIf { it.isNotBlank() }
            ?.let { ContextUsageFormatter.detail(usage) }
        isVisible = summary.isNotBlank()
    }
}
