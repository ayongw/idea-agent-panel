package com.ayongw.idea.agentpanel.frontend.settings

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * 设置页专用色值归口（与 chatApp 的 [ChatAppColors] 分域维护）：
 * MCP/供应商连接状态色、链接色、开关轨道色。
 */
object SettingsColors {

    /** 连接状态文字色 */
    object Status {
        /** 已连接（绿） */
        val connected: Color = JBColor(Color(0x2E7D32), Color(0x81C784))

        /** 连接失败（红） */
        val failed: Color = JBColor(Color(0xC62828), Color(0xEF9A9A))

        /** 待认证 / 进行中（橙） */
        val pending: Color = JBColor(Color(0xE65100), Color(0xFFB74D))
    }

    /** 链接色（重试链接等可点击文字） */
    val link: Color = JBColor(Color(0x2F6FEB), Color(0x548AF7))

    /** 开关控件轨道色 */
    object Switch {
        val on: Color = JBColor(Color(0x2F6FEB), Color(0x548AF7))
        val off: Color = JBColor(Color(0xC9CDD4), Color(0x5A5D63))
    }
}
