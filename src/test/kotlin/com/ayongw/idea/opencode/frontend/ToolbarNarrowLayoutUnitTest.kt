package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.ContextUsageIndicator
import com.ayongw.idea.opencode.frontend.chatApp.ui.InputToolbar
import com.ayongw.idea.opencode.frontend.chatApp.viewmodel.ApprovalMode
import com.ayongw.idea.opencode.shared.AgentDto
import com.ayongw.idea.opencode.shared.ModelDto
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import com.ayongw.idea.opencode.shared.ContextUsageFormatter
import com.ayongw.idea.opencode.shared.SessionUsageDto
import com.ayongw.idea.opencode.shared.TokenUsageDto
import com.intellij.testFramework.TestApplicationManager
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.BorderLayout
import java.awt.Container
import java.awt.Dimension
import java.awt.Rectangle
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

/**
 * 窄窗口下底部工具条不重叠（对应实机截图：模型名与 token 用量叠在一起）。
 *
 * 复刻 [com.ayongw.idea.opencode.frontend.chatApp.ui.PromptInput] 的工具条结构
 * `BorderLayout(WEST=按钮组, EAST=用量)`：JDK BorderLayout 对 WEST/EAST 各自按 preferred
 * 摆放且**从不压缩**，两端 preferred 之和超过可用宽度时不是裁剪而是重叠绘制。
 * 独立复现见 temp/layout-probe/Probe2.java。
 */
class ToolbarNarrowLayoutUnitTest {

    private val usage = SessionUsageDto(
        tokens = TokenUsageDto(input = 11_300, output = 69, cacheRead = 18_500),
        lastStepInputTokens = 11_300,
        contextWindow = 200_000
    )

    @Before
    fun setUp() {
        TestApplicationManager.getInstance()
    }

    private data class Bounds(val toolbar: Rectangle, val usage: Rectangle) {
        val overlaps: Boolean
            get() = toolbar.x < usage.x + usage.width && usage.x < toolbar.x + toolbar.width
        override fun toString() = "toolbar=$toolbar usage=$usage"
    }

    /** 宽度不足时两端是否重叠（重叠=真实 bug，会把两段文字画在同一处） */
    private fun boundsAt(panelWidth: Int, modelName: String): Bounds {
        val toolbar = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(labelled("Auto-approve"))
            add(Box.createHorizontalStrut(8))
            add(labelled("Build"))
            add(Box.createHorizontalStrut(8))
            add(labelled(modelName))
        }
        val west = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(toolbar, BorderLayout.WEST)
        }
        val usageIndicator = indicator()
        val root = JPanel(BorderLayout()).apply {
            border = javax.swing.BorderFactory.createEmptyBorder(6, 8, 6, 8)
            minimumSize = Dimension(ChatUIConstants.Panel.MIN_WIDTH, 0)
            add(west, BorderLayout.WEST)
            add(usageIndicator, BorderLayout.EAST)
        }
        root.setSize(panelWidth, 40)
        layoutTree(root)

        val westAbs = west.bounds
        val tb = toolbar.bounds
        // toolbar.bounds 相对 west，usage.bounds 相对 root —— 换算到同一坐标系再比
        val toolbarAbs = Rectangle(westAbs.x + tb.x, westAbs.y + tb.y, tb.width, tb.height)
        val usageBounds = usageIndicator.bounds
        return Bounds(toolbarAbs, usageBounds)
    }

    /** 用量指示器：外层只放输入/输出（缓存与占比在悬浮明细里） */
    private fun indicator(): ContextUsageIndicator = ContextUsageIndicator().apply {
        updateUsage(usage)
    }

    private fun labelled(text: String): JLabel = JLabel(text).apply {
        font = java.awt.Font("Dialog", java.awt.Font.PLAIN, 11)
    }

    private fun layoutTree(c: Container) {
        c.doLayout()
        for (child in c.components) if (child is Container) layoutTree(child)
    }

    @Test
    fun 最小宽度下两端不重叠() {
        val width = ChatUIConstants.Panel.MIN_WIDTH
        val b = boundsAt(width, "MiMo-V2.6-Flash Free")
        assertFalse("宽度 $width 下工具条两端不应重叠：$b", b.overlaps)
    }

    @Test
    fun 最小宽度下长模型名也不重叠() {
        val width = ChatUIConstants.Panel.MIN_WIDTH
        val b = boundsAt(width, "claude-sonnet-4-5-thinking-20250929")
        assertFalse("长模型名（$width）下工具条两端不应重叠：$b", b.overlaps)
    }

    @Test
    fun 面板最小宽度不小于用户要求的下限() {
        assertTrue(
            "最小宽度应 ≥ 450px（用户口径）",
            ChatUIConstants.Panel.MIN_WIDTH >= 450
        )
    }

    @Test
    fun 用量外层只展示输入输出() {
        var text = ""
        var tooltip: String? = null
        SwingUtilities.invokeAndWait {
            val indicator = indicator()
            text = indicator.text
            tooltip = indicator.toolTipText
        }
        assertTrue("外层应含输入", text.contains("↑11.3k"))
        assertTrue("外层应含输出", text.contains("↓69"))
        assertFalse("外层不应含缓存", text.contains("缓存"))
        assertFalse("外层不应含上下文占比", text.contains("上下文"))
        // 信息不丢：悬浮明细仍覆盖缓存 / 占比
        assertTrue("悬浮明细应含缓存", tooltip!!.contains("缓存"))
        assertTrue("悬浮明细应含上下文占比", tooltip.contains("上下文"))
        assertTrue("外层应显著短于完整摘要", text.length < ContextUsageFormatter.summary(usage).length)
    }

    // ==================== 模型名响应式省略（真实 InputToolbar） ====================

    private fun inputToolbar(modelName: String, width: Int): InputToolbar =
        InputToolbar({}, {}, {}, {}).apply {
            update(
                ApprovalMode.AUTO,
                listOf(AgentDto(id = "build", name = "Build")),
                "build",
                ModelDto(id = "m1", modelID = "mimo", providerID = "opencode", name = modelName)
            )
            // 模拟 PromptInput：行宽与右侧预留宽都由外层下发（工具条自身看不到 EAST）
            setAvailableWidth(width)
            setReservedRightWidth(0)
            setSize(width, 24)
            doLayout()
        }

    /** 模型按钮文本：三个下拉按钮（审核/模式/模型）中的最后一个 */
    private fun modelButtonText(bar: InputToolbar): String =
        bar.components.filterIsInstance<JButton>().map { it.text }.last { it.endsWith("▾") }

    private fun modelButton(bar: InputToolbar): JButton =
        bar.components.filterIsInstance<JButton>().last { it.text.endsWith("▾") }

    @Test
    fun 窄宽度下模型名被省略() {
        val name = "claude-sonnet-4-5-thinking-20250929"
        val bar = inputToolbar(name, 200)

        val text = modelButtonText(bar)
        org.junit.Assert.assertTrue("窄宽度下模型名应出现省略号：$text", text.contains("…"))
        org.junit.Assert.assertTrue("省略后应短于全名：$text", text.length < name.length + 2)
    }

    @Test
    fun 宽度充足时模型名不省略() {
        val name = "MiMo-V2.6-Flash Free"
        val bar = inputToolbar(name, 600)

        org.junit.Assert.assertEquals("宽度充足应显示全名", "$name ▾", modelButtonText(bar))
    }

    @Test
    fun 下拉箭头不参与省略() {
        // 窄到只剩省略号时，▾ 仍须保留（否则模型选择器失去下拉 affordance）
        val bar = inputToolbar("claude-sonnet-4-5-thinking-20250929", 200)

        org.junit.Assert.assertTrue(
            "模型按钮文本应始终以 ▾ 结尾：${modelButtonText(bar)}",
            modelButtonText(bar).endsWith(" ▾")
        )
    }

    @Test
    fun 省略后tooltip仍保留全名() {
        val name = "claude-sonnet-4-5-thinking-20250929"
        val bar = inputToolbar(name, 200)

        org.junit.Assert.assertEquals(
            "省略不应丢失全名（tooltip 承载）",
            "$name ▾",
            modelButton(bar).toolTipText
        )
    }

    @Test
    fun 省略后工具条为右侧用量留出空间() {
        // 真正的不变式：工具条 preferred + 用量 preferred <= 可用宽度（否则两端重叠）
        val usageIndicator = indicator()
        val rowWidth = ChatUIConstants.Panel.MIN_WIDTH
        val bar = inputToolbar("claude-sonnet-4-5-thinking-20250929", rowWidth)
        bar.setAvailableWidth(rowWidth)
        bar.setReservedRightWidth(usageIndicator.preferredSize.width + 8)
        bar.doLayout()

        org.junit.Assert.assertTrue(
            "工具条 ${bar.preferredSize.width} + 用量 ${usageIndicator.preferredSize.width} " +
                "不应超过行宽 $rowWidth",
            bar.preferredSize.width + usageIndicator.preferredSize.width <= rowWidth
        )
    }

    @Test
    fun 最小宽度下模型名与用量仍不重叠() {
        // 真实组件 + 真实省略逻辑：末端组合（自动省略 + 精简用量 + 450px 下限）。
        // 预留宽度由 PromptInput 这层下发（工具条自身看不到 EAST），此处模拟同样调用。
        val usageIndicator = indicator()
        val bar = inputToolbar("claude-sonnet-4-5-thinking-20250929", ChatUIConstants.Panel.MIN_WIDTH)
        // 模拟 PromptInput 下发的两个外部量：行宽 + 右侧用量预留宽
        bar.setAvailableWidth(ChatUIConstants.Panel.MIN_WIDTH)
        bar.setReservedRightWidth(usageIndicator.preferredSize.width + 8)
        bar.doLayout()

        val root = JPanel(BorderLayout()).apply {
            border = javax.swing.BorderFactory.createEmptyBorder(0, 0, 0, 0)
            add(bar, BorderLayout.WEST)
            add(usageIndicator, BorderLayout.EAST)
        }
        root.setSize(ChatUIConstants.Panel.MIN_WIDTH, 24)
        layoutTree(root)

        val a = bar.bounds
        val b = usageIndicator.bounds
        val overlaps = a.x < b.x + b.width && b.x < a.x + a.width
        org.junit.Assert.assertFalse(
            "真实组件在最小宽度下不应重叠：bar=$a usage=$b",
            overlaps
        )
    }
}
