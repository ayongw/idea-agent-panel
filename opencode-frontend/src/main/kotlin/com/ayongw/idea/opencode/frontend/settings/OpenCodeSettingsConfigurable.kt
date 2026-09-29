package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Scrollable

/**
 * OpenCode 设置页（应用级）：Tab 容器
 *
 * 连接（Server 地址与凭据、shell）・模型（供应商/默认模型/apiKey）・
 * 规则（AGENTS.md/instructions）・技能（skills）・MCP（servers/timeout）
 */
class OpenCodeSettingsConfigurable : Configurable {

    private var panel: JComponent? = null

    private val tabs: List<SettingsTab> by lazy {
        listOf(
            ConnectionSettingsTab(),
            ProviderSettingsTab(),
            RuleSettingsTab(),
            SkillSettingsTab(),
            McpSettingsTab()
        )
    }

    override fun getDisplayName(): String = OpencodeFrontendBundle.message("settings.opencode.title")

    override fun createComponent(): JComponent {
        val tabbed = JBTabbedPane()
        tabs.forEach { tab -> tabbed.addTab(tab.title, tab.component) }
        val wrapper = SettingsPage().apply { add(tabbed, BorderLayout.CENTER) }
        panel = wrapper
        reset()
        return wrapper
    }

    override fun isModified(): Boolean = tabs.any { it.isModified() }

    override fun apply() = tabs.forEach { it.apply() }

    override fun reset() = tabs.forEach { it.reload() }
}

/**
 * 设置页容器
 *
 * 宽度跟随设置对话框（表格 / 长文本不撑出横向滚动条），高度按内容纵向滚动。
 * 表单内容本身的 preferred 宽度已由各面板限制（见 `AbstractSettingsTab.buildScroll`）。
 */
private class SettingsPage : JPanel(BorderLayout()), Scrollable {

    override fun getPreferredScrollableViewportSize(): Dimension = preferredSize

    override fun getScrollableUnitIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        JBUI.scale(16)

    override fun getScrollableBlockIncrement(visibleRect: Rectangle, orientation: Int, direction: Int): Int =
        JBUI.scale(64)

    /** 横向铺满对话框，永不出现水平滚动条 */
    override fun getScrollableTracksViewportWidth(): Boolean = true

    /** 纵向按内容高度，超出则滚动 */
    override fun getScrollableTracksViewportHeight(): Boolean = false
}