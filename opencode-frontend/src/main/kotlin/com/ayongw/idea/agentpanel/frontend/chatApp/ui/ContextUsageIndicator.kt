package com.ayongw.idea.agentpanel.frontend.chatApp.ui

import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.agentpanel.shared.ContextUsageFormatter
import com.ayongw.idea.agentpanel.shared.SessionUsageDto
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI

/**
 * 输入框下方右侧的会话用量指示器。
 *
 * 外层只放**输入 / 输出**两项（[ContextUsageFormatter.compact]）——底部工具条是
 * `BorderLayout(WEST=按钮组, EAST=本指示器)`，两端都按 preferred 不压缩，外层文本过宽
 * 会与模型名重叠绘制；缓存 / 上下文占比 / 推理 / 花费等全部信息由悬浮明细
 * （[ContextUsageFormatter.detail]）承载，信息不丢。占比达到阈值时用警示色。
 */
class ContextUsageIndicator : JBLabel() {

    init {
        isVisible = false
        font = JBFont.small()
        foreground = ChatAppColors.Text.disabled
        border = JBUI.Borders.emptyLeft(JBUI.scale(ChatUIConstants.Spacing.NORMAL))
    }

    /** 更新用量；null 或无 token 数据时隐藏（悬浮明细同样置空） */
    fun updateUsage(usage: SessionUsageDto?) {
        val compact = ContextUsageFormatter.compact(usage)
        text = compact
        toolTipText = if (compact.isBlank()) null else ContextUsageFormatter.detail(usage).ifBlank { null }
        foreground = if (ContextUsageFormatter.isWarning(usage)) ChatAppColors.Status.warning else ChatAppColors.Text.disabled
        isVisible = compact.isNotBlank()
        revalidate()
        repaint()
    }
}