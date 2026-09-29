package com.ayongw.idea.opencode.frontend.chatApp.ui.utils

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.AlphaComposite
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import javax.swing.Icon
import javax.swing.JButton

object ButtonUtils {

    /**
     * 工具条按钮（图标或文字）
     *
     * 只 hover/pressed 时自绘一层圆角背景（背景色变化），不画边框、不画焦点框；
     * LAF 的按钮绘制在 hover 时会画白底 + 黑框，故这里完全自绘、不走 UI 代理。
     * 尺寸固定为内容尺寸，避免 BoxLayout 把按钮拉伸成大块。
     */
    internal open class ToolbarButton(text: String = "", icon: Icon? = null) : JButton(text, icon) {

        init {
            isFocusable = false
            isRequestFocusEnabled = false
            isFocusPainted = false
            isBorderPainted = false
            isContentAreaFilled = false
            isOpaque = false
            isRolloverEnabled = true
            margin = Insets(0, 0, 0, 0)
            border = JBUI.Borders.empty()
        }

        /** 固定尺寸：布局（BoxLayout 等）不会把它拉大 */
        override fun getMaximumSize(): Dimension = preferredSize

        /** 显式设置过尺寸就用它，否则按图标 + 文本 + 自身 padding 计算 */
        override fun getPreferredSize(): Dimension {
            if (isPreferredSizeSet) return super.getPreferredSize()
            val metrics = getFontMetrics(font)
            val label = text.orEmpty()
            val iconWidth = icon?.iconWidth ?: 0
            val iconHeight = icon?.iconHeight ?: 0
            val textWidth = if (label.isEmpty()) 0 else metrics.stringWidth(label)
            val gap = if (textWidth > 0 && iconWidth > 0) iconTextGap else 0
            val insets = insets
            return Dimension(
                textWidth + iconWidth + gap + insets.left + insets.right,
                maxOf(metrics.height, iconHeight) + insets.top + insets.bottom
            )
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                if (model.isEnabled && (model.isRollover || model.isPressed)) {
                    val arc = JBUI.scale(HOVER_ARC)
                    g2.color = if (model.isPressed) {
                        JBUI.CurrentTheme.ActionButton.pressedBackground()
                    } else {
                        JBUI.CurrentTheme.ActionButton.hoverBackground()
                    }
                    g2.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
                }
                if (!model.isEnabled) {
                    g2.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, DISABLED_ALPHA)
                }
                paintContent(g2)
            } finally {
                g2.dispose()
            }
        }

        override fun paintBorder(g: Graphics?) = Unit

        override fun paintChildren(g: Graphics?) = Unit

        /** 居中绘制图标与文本，替代 LAF 的按钮绘制 */
        private fun paintContent(g: Graphics2D) {
            g.font = font
            val metrics = g.fontMetrics
            val label = text.orEmpty()
            val iconWidth = icon?.iconWidth ?: 0
            val iconHeight = icon?.iconHeight ?: 0
            val textWidth = if (label.isEmpty()) 0 else metrics.stringWidth(label)
            val gap = if (textWidth > 0 && iconWidth > 0) iconTextGap else 0
            var x = (width - (iconWidth + gap + textWidth)) / 2
            icon?.paintIcon(this, g, x, (height - iconHeight) / 2)
            x += iconWidth + gap
            if (label.isNotEmpty()) {
                g.color = if (model.isEnabled) foreground else UIUtil.getInactiveTextColor()
                g.drawString(label, x, (height - metrics.height) / 2 + metrics.ascent)
            }
        }

        private companion object {
            const val HOVER_ARC = 6
            const val DISABLED_ALPHA = 0.45f
        }
    }

    fun createActionButton(
        icon: Icon,
        tooltip: String,
        size: Dimension = Dimension(24, 24),
        action: () -> Unit
    ): JButton = ToolbarButton(icon = icon).apply {
        toolTipText = tooltip
        preferredSize = size
        addActionListener { action() }
    }
}