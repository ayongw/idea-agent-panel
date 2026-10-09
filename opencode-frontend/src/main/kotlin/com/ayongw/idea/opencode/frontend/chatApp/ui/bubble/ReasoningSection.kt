package com.ayongw.idea.opencode.frontend.chatApp.ui.bubble

import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Cursor
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTextArea

/** 思考区折叠箭头（G3） */
private const val COLLAPSE_CHEVRON = "▾"
private const val EXPAND_CHEVRON = "▸"

/**
 * 思考区组件（G3 / TSD-30 §5.8）：折叠标题行 + 多轮内容容器。
 *
 * 一次执行的多轮推理统一收纳在本块：每轮一个按宽度换行的 JTextArea，轮次间以
 * 细分隔线区分（[setRounds]）。流式期展开更新，执行结束经 [complete] 自动折叠，
 * 标题行可手动切换；折叠只改变内容容器可见性，布局由面板级 LayoutCoordinator 收口。
 */
internal class ReasoningSection(expanded: Boolean) : JPanel() {

    private var expandedState = expanded

    private val chevron = JBLabel(if (expanded) COLLAPSE_CHEVRON else EXPAND_CHEVRON).apply {
        foreground = ChatAppColors.Text.disabled
    }

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        isVisible = expanded
    }

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        add(buildHeader())
        add(contentPanel)
    }

    /**
     * 重建多轮内容（按到达顺序），轮次间插入细分隔线。
     *
     * @param expand true 时保证展开（流式更新）；折叠态下更新不改变折叠状态
     */
    fun setRounds(rounds: List<String>, expand: Boolean = false) {
        if (expand && !expandedState) setExpanded(true)
        contentPanel.removeAll()
        rounds.forEachIndexed { index, round ->
            if (index > 0) {
                contentPanel.add(Box.createVerticalStrut(JBUI.scale(ROUND_GAP)))
                contentPanel.add(buildDivider())
                contentPanel.add(Box.createVerticalStrut(JBUI.scale(ROUND_GAP)))
            }
            contentPanel.add(buildRoundArea(round))
        }
        contentPanel.revalidate()
    }

    /** 执行结束自动折叠（仍可手动展开） */
    fun complete() = setExpanded(false)

    /** 单轮内容区：JTextArea 按容器宽度自动换行（长思考不撑宽，见 LayoutCoordinator） */
    private fun buildRoundArea(round: String): JTextArea = JTextArea(round).apply {
        isEditable = false
        isOpaque = false
        isFocusable = false
        lineWrap = true
        wrapStyleWord = true
        font = JBFont.regular()
        foreground = ChatAppColors.Text.normal
        margin = JBUI.emptyInsets()
        alignmentX = LEFT_ALIGNMENT
    }

    /** 轮次分隔线：浅色细线，宽度跟随容器 */
    private fun buildDivider(): JSeparator = JSeparator().apply {
        alignmentX = LEFT_ALIGNMENT
        foreground = ChatAppColors.Divider.line
    }

    private fun buildHeader(): JPanel {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            add(JBLabel(OpencodeFrontendBundle.message("chat.message.reasoning.title")).apply {
                font = JBFont.small().asBold()
                foreground = ChatAppColors.Text.disabled
            })
            add(Box.createHorizontalStrut(JBUI.scale(4)))
            add(chevron)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    setExpanded(!expandedState)
                    revalidate()
                    repaint()
                }
            })
        }
        return header
    }

    private fun setExpanded(expanded: Boolean) {
        expandedState = expanded
        contentPanel.isVisible = expanded
        chevron.text = if (expanded) COLLAPSE_CHEVRON else EXPAND_CHEVRON
    }

    private companion object {
        /** 分隔线与上下轮次的间距 */
        const val ROUND_GAP = 6
    }
}
