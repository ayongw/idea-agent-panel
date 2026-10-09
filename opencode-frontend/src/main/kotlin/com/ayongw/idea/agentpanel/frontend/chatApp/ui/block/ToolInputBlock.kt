package com.ayongw.idea.agentpanel.frontend.chatApp.ui.block

import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatUIConstants
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseMotionAdapter
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.border.EmptyBorder

/**
 * 工具调用入参块（问题 4）。
 *
 * 与输出块 [CodeBlockPane] 的关键差异：
 * - **按宽度换行**（`lineWrap = true`）而不是横向滚动。入参是 JSON 参数，读者要的是「看清有哪些
 *   参数」，横向滚动会逼着读者左右拖；输出才是逐字原文（如 `git status` 结果），必须横向可滚。
 * - 超长才折叠预览 + 「展开」，短参数**原样多行铺开**，不再压成一行再补省略号
 *   （此前 `ToolCallCard.toSingleLine()` 会把换行全替换成空格，长 JSON 直接看不到字段名）。
 */
internal class ToolInputBlock(private val input: String) : JPanel() {

    private val lines = input.lines()

    /**
     * 是否折叠：按「气泡宽度下换行后的视觉行数」判断。
     *
     * 用逻辑行数（`\n` 数）判断不准 —— 一整行超长 JSON 逻辑行数只有 1，
     * 换行后却占好几行，不折叠就会被裁/挤高。
     */
    private val collapsible = lines.size > ChatUIConstants.ToolInput.PREVIEW_LINES

    private val textArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = false
        font = Font(Font.MONOSPACED, Font.PLAIN, 12)
        foreground = ChatAppColors.Tool.secondary
        background = ChatAppColors.MessageBubble.othersBackground
        border = EmptyBorder(4, 8, 4, 8)
    }

    private val scrollPane = JBScrollPane(textArea).apply {
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        border = EmptyBorder(0, 0, 0, 0)
        isOpaque = false
        viewport.isOpaque = false
    }

    private val toggleLabel = JBLabel()

    @Volatile
    private var expanded = false

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        render()
    }

    private fun render() {
        removeAll()
        textArea.text = if (expanded) input else lines.take(PREVIEW_LINES).joinToString("\n")
        textArea.caretPosition = 0

        // 高度必须按**换行后的视觉行数**算，不能按 `input.lines()` 的逻辑行数：
        // 入参常是一整行超长 JSON（如 `{"success":true,"id":"…","action":"…"}`），
        // 逻辑行数=1，但按气泡宽度换行后是 3~4 个视觉行；按逻辑行数给高度会把文本裁掉
        // 一大半（表现为「只显示一行半」）。
        //
        // 测量宽度优先用实际宽度；首帧渲染时 width 还是 0，退回参考宽度。
        // 无论测量准不准，垂直滚动条都保留 —— 兜底保证「不裁切」这条底线。
        val measureWidth = if (width > MIN_MEASURE_WIDTH) width else REFERENCE_MEASURE_WIDTH
        textArea.setSize(measureWidth, Int.MAX_VALUE / 2)
        var height = textArea.preferredSize.height

        // 超长入参仍保留折叠：按可视高度折算行数，超出阈值只给预览
        if (!expanded) {
            val lineH = JBUI.scale(ChatUIConstants.ToolInput.LINE_HEIGHT)
            val maxLines = ChatUIConstants.ToolInput.PREVIEW_LINES
            val maxHeight = lineH * maxLines + JBUI.scale(ChatUIConstants.ToolInput.VERTICAL_PADDING)
            if (height > maxHeight) height = maxHeight
        }
        height += JBUI.scale(ChatUIConstants.ToolInput.VERTICAL_PADDING)
        scrollPane.preferredSize = Dimension(0, height)
        scrollPane.maximumSize = Dimension(Int.MAX_VALUE, height)
        add(scrollPane)

        if (collapsible) {
            toggleLabel.apply {
                text = if (expanded) {
                    AgentPanelBundle.message("chat.tool.input.collapse")
                } else {
                    AgentPanelBundle.message("chat.tool.input.expand", lines.size)
                }
                font = JBFont.small()
                foreground = ChatAppColors.Text.disabled
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = LEFT_ALIGNMENT
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent?) = toggle()
                })
                addMouseMotionListener(object : MouseMotionAdapter() {
                    override fun mouseMoved(e: MouseEvent?) {
                        textArea.toolTipText = null
                    }
                })
            }
            add(Box.createVerticalStrut(JBUI.scale(2)))
            add(toggleLabel)
        }
        revalidate()
        repaint()
    }

    private fun toggle() {
        expanded = !expanded
        render()
    }

    private companion object {
        /** 折叠前的预览行数 */
        const val PREVIEW_LINES = ChatUIConstants.ToolInput.PREVIEW_LINES

        /** 小于此宽度认为「宽度尚未分配」，改用参考宽度测量 */
        const val MIN_MEASURE_WIDTH = 40

        /** 首帧测量用的参考宽度（典型窄面板气泡宽）；仅影响高度估算，垂直滚动兜底 */
        const val REFERENCE_MEASURE_WIDTH = 420
    }
}