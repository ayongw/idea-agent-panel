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
 * 两类设置分列其中：
 * - 插件自身设置（Connection）：保存在 IDEA（密码进 IDE 凭据存储），由「OK」统一提交；
 * - opencode 设置（Models / Rules / Skills / MCP）：在 IDEA 中展示，写入 opencode 配置文件，面板内自带保存按钮。
 *
 * 各 Tab 在首次显示与切换时自动加载，无需先点刷新。
 */
class OpenCodeSettingsConfigurable : Configurable {

    companion object {
        /** 「模型」页下标（连接页之后的第一个 opencode 设置页） */
        const val MODELS_TAB_INDEX = 1

        /** 外部入口指定的初始 Tab（如模型弹窗的「管理模型」），创建组件时消费一次 */
        private var pendingTabIndex: Int? = null

        /** 指定下次打开设置页时选中的 Tab */
        fun selectTab(index: Int) {
            pendingTabIndex = index
        }
    }

    private var panel: JComponent? = null
    private var tabbed: JBTabbedPane? = null

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
        val pane = JBTabbedPane()
        tabs.forEach { tab -> pane.addTab(tab.title, tab.component) }
        // 切换 Tab 即加载该页数据
        pane.addChangeListener { reloadSelectedTab() }
        pendingTabIndex?.let { index -> pane.selectedIndex = index.coerceIn(0, tabs.size - 1) }
        pendingTabIndex = null
        tabbed = pane

        val wrapper = SettingsPage().apply { add(pane, BorderLayout.CENTER) }
        panel = wrapper
        reloadSelectedTab()
        return wrapper
    }

    override fun isModified(): Boolean = tabs.any { it.isModified() }

    override fun apply() = tabs.forEach { it.apply() }

    /** 需统一提交的 Tab（如 Connection）始终加载；其余页在显示 / 切换时加载 */
    override fun reset() {
        tabs.filter { it.eager }.forEach { it.reload() }
        if (tabbed?.selectedIndex != 0) reloadSelectedTab()
    }

    private fun reloadSelectedTab() {
        val index = tabbed?.selectedIndex ?: return
        tabs.getOrNull(index)?.reload()
    }
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