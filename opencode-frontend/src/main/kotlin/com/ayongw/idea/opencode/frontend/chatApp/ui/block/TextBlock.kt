package com.ayongw.idea.opencode.frontend.chatApp.ui.block

import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Component.LEFT_ALIGNMENT
import javax.swing.JPanel
import javax.swing.JTextArea

internal class TextBlock(private val text: String) : JPanel() {

    /**
     * 正文文本组件：按容器宽度自动换行。不能用逐行 JBLabel——label 不换行，
     * 长段落 / 长 URL（LLM 输出无硬换行时）preferred 宽度会撑出视口，超出部分
     * 被裁断看不全。原则：宽度自适应换行优先，横向滚动（代码块）兜底，永不隐藏。
     */
    val textArea: JTextArea? = if (text.length > ChatUIConstants.LargeContent.MAX_TEXT_LENGTH) null
    else JTextArea().apply {
        this.text = this@TextBlock.text
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

    init {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        // Large text protection: if content > 10KB, use pagination
        if (text.length > ChatUIConstants.LargeContent.MAX_TEXT_LENGTH) {
            add(PaginatedTextPane(text))
        } else {
            add(textArea)
        }
    }
}
