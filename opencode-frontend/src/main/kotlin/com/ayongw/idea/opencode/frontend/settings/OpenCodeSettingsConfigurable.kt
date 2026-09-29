package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTabbedPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

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
        val wrapper = JPanel(BorderLayout())
        wrapper.add(tabbed, BorderLayout.CENTER)
        panel = wrapper
        reset()
        return wrapper
    }

    override fun isModified(): Boolean = tabs.any { it.isModified() }

    override fun apply() = tabs.forEach { it.apply() }

    override fun reset() = tabs.forEach { it.reload() }
}