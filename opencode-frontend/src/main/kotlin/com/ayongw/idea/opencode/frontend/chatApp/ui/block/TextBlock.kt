package com.ayongw.idea.opencode.frontend.chatApp.ui.block

import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Component.LEFT_ALIGNMENT
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JPanel

internal class TextBlock(private val text: String) : JPanel() {
    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        // Large text protection: if content > 10KB, use pagination
        if (text.length > ChatUIConstants.LargeContent.MAX_TEXT_LENGTH) {
            add(PaginatedTextPane(text))
        } else {
            val lines = text.lines().toList()
            lines.forEachIndexed { index, line ->
                val label = JBLabel(line).apply {
                    font = JBFont.regular()
                    foreground = ChatAppColors.Text.normal
                    alignmentX = LEFT_ALIGNMENT
                }
                add(label)
                if (index < lines.lastIndex) add(Box.createVerticalStrut(JBUI.scale(2)))
            }
        }
    }
}
