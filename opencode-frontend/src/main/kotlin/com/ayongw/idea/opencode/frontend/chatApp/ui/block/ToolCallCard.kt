package com.ayongw.idea.opencode.frontend.chatApp.ui.block

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.shared.ToolCallDto
import com.ayongw.idea.opencode.shared.ToolCallStatus
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Color
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Dimension
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * 工具调用卡片：工具名 + 状态（+ 退出码）+ 入参摘要 + 输出正文。
 *
 * 数据来自 [ToolCallDto]：REST 为权威值，事件流补充运行中态，两者按 `callId` 原地互相覆盖。
 */
internal class ToolCallCard(private val tool: ToolCallDto) : JPanel() {

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        add(buildHeader())

        tool.input.takeIf { it.isNotBlank() }?.let { input ->
            add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(buildInputLabel(input))
        }

        tool.output.takeIf { it.isNotBlank() }?.let { output ->
            add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(CodeBlockPane(tool.name.ifBlank { FALLBACK_NAME }, output))
        }
    }

    private fun buildHeader() = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(HEADER_HEIGHT))

        add(JBLabel(tool.name.ifBlank { FALLBACK_NAME }).apply {
            font = JBFont.small().asBold()
            foreground = ChatAppColors.Text.normal
        })
        add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.NORMAL)))
        add(JBLabel(statusLabel()).apply {
            font = JBFont.small()
            foreground = statusColor()
        })
        tool.exit?.let { code ->
            add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(JBLabel(OpencodeFrontendBundle.message("chat.tool.exit") + " " + code).apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.timestamp
            })
        }
        if (tool.truncated) {
            add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(JBLabel(OpencodeFrontendBundle.message("chat.tool.truncated")).apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.timestamp
            })
        }
        add(Box.createHorizontalGlue())
    }

    private fun buildInputLabel(input: String) = JBLabel(input.toSingleLine()).apply {
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        foreground = ChatAppColors.Tool.secondary
        alignmentX = LEFT_ALIGNMENT
        toolTipText = input
    }

    private fun statusLabel(): String = when (tool.status) {
        ToolCallStatus.STREAMING -> OpencodeFrontendBundle.message("chat.tool.status.streaming")
        ToolCallStatus.RUNNING -> OpencodeFrontendBundle.message("chat.tool.status.running")
        ToolCallStatus.COMPLETED -> OpencodeFrontendBundle.message("chat.tool.status.completed")
        ToolCallStatus.ERROR -> OpencodeFrontendBundle.message("chat.tool.status.failed")
    }

    private fun statusColor(): Color = when (tool.status) {
        ToolCallStatus.COMPLETED -> ChatAppColors.Tool.success
        ToolCallStatus.ERROR -> ChatAppColors.Tool.error
        else -> ChatAppColors.Tool.running
    }

    /** 入参可能很长（整段 JSON），超长截断显示，完整值放 tooltip */
    private fun String.toSingleLine(): String {
        val single = replace("\n", " ").replace("\r", " ")
        return if (single.length > MAX_INPUT_CHARS) single.take(MAX_INPUT_CHARS) + ELLIPSIS else single
    }

    private companion object {
        const val FALLBACK_NAME = "tool"
        const val HEADER_HEIGHT = 20
        const val MAX_INPUT_CHARS = 120
        const val ELLIPSIS = "…"
    }
}
