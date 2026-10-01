package com.ayongw.idea.opencode.frontend.chatApp.ui.block

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Component.LEFT_ALIGNMENT
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * 分页文本面板 - 用于大文本内容的分页渲染
 */
internal class PaginatedTextPane(private val text: String) : JPanel() {
    private var currentPage = 0
    private val lines: List<String>
    private val linesPerPage = ChatUIConstants.LargeContent.LINES_PER_PAGE
    private val totalPages: Int

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        lines = text.lines().toList()
        totalPages = (lines.size + linesPerPage - 1) / linesPerPage

        renderPage(0)
        if (totalPages > 1) {
            add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(PaginationControls())
        }
    }

    private fun renderPage(page: Int) {
        removeAll()
        currentPage = page

        val start = page * linesPerPage
        val end = (start + linesPerPage).coerceAtMost(lines.size)
        val pageLines = lines.subList(start, end)

        pageLines.forEachIndexed { index, line ->
            val label = JBLabel(line).apply {
                font = JBFont.regular()
                foreground = ChatAppColors.Text.normal
                alignmentX = LEFT_ALIGNMENT
            }
            add(label)
            if (index < pageLines.lastIndex) add(Box.createVerticalStrut(JBUI.scale(2)))
        }

        if (totalPages > 1) {
            add(Box.createVerticalStrut(JBUI.scale(ChatUIConstants.Spacing.SMALL)))
            add(PaginationControls())
        }

        revalidate()
        repaint()
    }

    private inner class PaginationControls : JPanel() {
        init {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT

            add(Box.createHorizontalGlue())

            val prevBtn = JBLabel(OpencodeFrontendBundle.message("chat.pagination.prev")).apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.disabled
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(4, 12)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        if (currentPage > 0) renderPage(currentPage - 1)
                    }
                })
                isEnabled = currentPage > 0
            }
            add(prevBtn)

            add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.MEDIUM)))

            val pageInfo = JBLabel("${currentPage + 1} / $totalPages").apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.timestamp
            }
            add(pageInfo)

            add(Box.createHorizontalStrut(JBUI.scale(ChatUIConstants.Spacing.MEDIUM)))

            val nextBtn = JBLabel(OpencodeFrontendBundle.message("chat.pagination.next")).apply {
                font = JBFont.small()
                foreground = ChatAppColors.Text.disabled
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(4, 12)
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        if (currentPage < totalPages - 1) renderPage(currentPage + 1)
                    }
                })
                isEnabled = currentPage < totalPages - 1
            }
            add(nextBtn)

            add(Box.createHorizontalGlue())
        }
    }
}
