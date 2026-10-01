package com.ayongw.idea.opencode.frontend.chatApp.ui

import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.shared.ContextUsageFormatter
import com.ayongw.idea.opencode.shared.SessionUsageDto
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI

/**
 * 输入框下方右侧的会话用量指示器：最新一条助手消息的增量 token 与上下文占比。
 * 无数据时整块隐藏（不显示 0，避免误读）；占比达到阈值时用警示色。
 */
class ContextUsageIndicator : JBLabel() {

    init {
        isVisible = false
        font = JBFont.small()
        foreground = ChatAppColors.Text.disabled
        border = JBUI.Borders.emptyLeft(JBUI.scale(ChatUIConstants.Spacing.NORMAL))
    }

    /** 更新用量；null 或空数据时隐藏 */
    fun updateUsage(usage: SessionUsageDto?) {
        val summary = ContextUsageFormatter.summary(usage)
        text = summary
        toolTipText = if (summary.isBlank()) null else ContextUsageFormatter.detail(usage).ifBlank { null }
        foreground = if (ContextUsageFormatter.isWarning(usage)) ChatAppColors.Status.warning else ChatAppColors.Text.disabled
        isVisible = summary.isNotBlank()
        revalidate()
        repaint()
    }
}