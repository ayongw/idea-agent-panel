package com.ayongw.idea.opencode.frontend.chatApp.ui.utils

import com.intellij.ui.Gray
import com.intellij.ui.JBColor
import java.awt.Color
import javax.swing.UIManager

object ChatAppColors {
    object Panel {
        val background: Color
            get() = UIManager.getColor("Panel.background") ?: JBColor.PanelBackground
    }

    object Text {
        val disabled: Color
            get() = JBColor.GRAY

        val normal: Color
            get() = UIManager.getColor("Label.foreground") ?: JBColor.foreground()

        val timestamp: Color = JBColor(Gray._192, Gray._160)

        val authorName: Color = JBColor(Color(219, 224, 235), Color(180, 200, 220))
    }

    object MessageBubble {
        val myBackground: Color
            get() = JBColor(
                Color(227, 242, 253),
                Color(37, 55, 70)
            )

        val othersBackground: Color
            get() = JBColor(
                Gray._245,
                Color(50, 50, 52)
            )

        val myBackgroundBorder: Color
            get() = JBColor(
                Color(144, 202, 249),
                Color(66, 165, 245)
            )

        val othersBackgroundBorder: Color
            get() = JBColor(
                Gray._189,
                Gray._97
            )

        val mySearchHighlightedBackground: Color
            get() = JBColor(
                Color(179, 229, 252),
                Color(58, 96, 115)
            )

        val othersSearchHighlightedBackground: Color
            get() = JBColor(
                Gray._224,
                Color(70, 73, 75)
            )

        val searchHighlightedBackgroundBorder: Color = JBColor(
            Color(66, 165, 245),
            Color(100, 181, 246)
        )

        val matchingMyBorder: Color
            get() = JBColor(
                Color(144, 202, 249),
                Color(79, 195, 247)
            )

        val matchingOthersBorder: Color
            get() = JBColor(
                Gray._158,
                Gray._117
            )
    }

    object Prompt {
        val border: Color = JBColor.border()
    }

    object Tab {
        val selectedBackground: Color = JBColor(Color(232, 241, 254), Color(45, 55, 70))
        val selectedIndicator: Color = JBColor(Color(66, 165, 245), Color(100, 181, 246))
    }

    object Tool {
        /** 进行中（入参流式 / 执行中）状态文字 */
        val running: Color = JBColor(Gray._128, Gray._160)

        /** 执行成功 */
        val success: Color = JBColor(Color(46, 125, 50), Color(129, 199, 132))

        /** 执行失败 */
        val error: Color = JBColor(Color(0xC0, 0x39, 0x2B), Color(0xFF, 0x8A, 0x80))

        /** 入参等次要文字 */
        val secondary: Color = JBColor(Gray._96, Gray._150)
    }

    object Context {
        val autoFile: Color = JBColor(Color(200, 230, 255), Color(40, 60, 80))
        val explicitFile: Color = JBColor(Color(255, 230, 200), Color(80, 60, 40))
        val onContextChip: Color = JBColor(Color(30, 60, 90), Color(200, 220, 240))
    }
}