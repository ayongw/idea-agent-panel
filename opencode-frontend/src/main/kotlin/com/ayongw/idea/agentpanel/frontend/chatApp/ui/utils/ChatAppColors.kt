package com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils

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

        val timestamp: Color = JBColor(Gray._160, Gray._160)

        val authorName: Color = JBColor(Color(0x5A, 0x63, 0x72), Color(180, 200, 220))
    }

    object Avatar {
        /** 助手头像底色 */
        val background: Color = JBColor(Color(66, 165, 245), Color(100, 181, 246))

        /** 助手头像文字色 */
        val foreground: Color = JBColor(Color.WHITE, Color.WHITE)
    }

    /** 分隔线（多轮思考轮次间） */
    object Divider {
        val line: Color = JBColor(Gray._210, Gray._80)
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

    object Selection {
        /** 行/条目高亮底色（会话行、mention 高亮等） */
        val rowHighlight: Color = JBColor(Color(200, 220, 255), Color(40, 60, 90))

        /** 当前会话行底色（比普通高亮更明确，配合左侧竖条使用） */
        val currentRowHighlight: Color = JBColor(Color(214, 231, 255), Color(36, 52, 78))
    }

    object Status {
        /** 警示红（用量超限、权限提示等） */
        val warning: Color = JBColor(Color(0xC0, 0x39, 0x2B), Color(0xFF, 0x8A, 0x80))
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

    object Server {
        /** 状态条底色：进行中（探测 / 启动 / 需要密钥） */
        val progressBackground: Color = JBColor(Color(232, 241, 254), Color(40, 50, 64))

        /** 状态条底色：失败 */
        val errorBackground: Color = JBColor(Color(253, 236, 234), Color(66, 44, 42))

        /** 状态条上/下分隔线 */
        val border: Color = JBColor.border()

        /** 进行中状态的主文案色 */
        val progressText: Color = JBColor(Color(0x1F, 0x4E, 0x79), Color(0x9E, 0xC5, 0xF0))

        /** 失败状态的主文案色 */
        val errorText: Color = JBColor(Color(0xC0, 0x39, 0x2B), Color(0xFF, 0x8A, 0x80))

        /** 补充说明（detail / 输出尾巴）文字色 */
        val secondaryText: Color = JBColor(Gray._96, Gray._150)
    }
}