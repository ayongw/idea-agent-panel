package com.ayongw.idea.opencode.frontend.chatApp.ui.block

import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatAppColors
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Dimension
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

    /**
     * 文本的自然宽度（不换行时渲染所需宽度）。
     *
     * 用一个 `lineWrap = false` 的测量副本取 preferred 宽度：开启换行的 JTextArea
     * **无法知道目标宽度**，其 preferred 宽度恒为约 121px（与内容长度无关，实测
     * 1 字符与 440 字符都是 121），所以不能直接用自身 preferred 宽度。
     *
     * 供气泡决定「背景画多宽」；不参与布局，故测量偏差只影响观感不会破坏换行与高度。
     */
    fun naturalWidth(): Int {
        val ta = textArea ?: return 0
        return JTextArea(text).apply {
            margin = ta.margin
            font = ta.font
        }.preferredSize.width
    }

    init {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT

        // BoxLayout 在交叉轴上会把组件拉伸到 maximumSize.width，只设 alignmentX
        // 是**无法**右对齐的 —— 组件被拉满后 alignmentX 失效，文字仍贴左。
        // 故把宽度上限压到文本自然宽度：
        //   短文本 → 不被拉伸，配合 alignmentX=RIGHT_ALIGNMENT 真正贴右
        //   长文本 → 上限超过容器宽，由 BoxLayout 按容器宽封顶后正常换行，高度由
        //            JTextArea 在拿到最终宽度后自行计算（不预设高度，避免陈旧高度）
        textArea?.let { ta ->
            val natural = naturalWidth()
            ta.maximumSize = Dimension(natural, Int.MAX_VALUE)
        }

        // Large text protection: if content > 10KB, use pagination
        if (text.length > ChatUIConstants.LargeContent.MAX_TEXT_LENGTH) {
            add(PaginatedTextPane(text))
        } else {
            add(textArea)
        }
    }
}
