package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.block.CodeBlockPane
import com.ayongw.idea.opencode.frontend.chatApp.ui.utils.ChatUIConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.swing.JLabel
import javax.swing.JScrollPane
import javax.swing.JTextArea

/**
 * 代码块渲染档位（用户诉求：20 行以内按实际行直接展示，超过才出现滚动条）。
 *
 * - 1 行且不超长 → 紧凑标签（无头部行、无滚动容器）
 * - 2~[DIRECT_MAX_LINES] 行 → 头部行 + 文本区，**无滚动容器**，高度按实际行数
 * - 超过阈值 → 头部行 + 滚动容器（封顶高度，块内滚动）
 */
class CodeBlockRenderModeUnitTest {

    private fun pane(code: String) = CodeBlockPane("java", code)

    /** 递归收集所有标签（文本区在滚动容器内，直接遍历 component 拿不到） */
    private fun findLabels(root: java.awt.Container): List<javax.swing.JLabel> {
        val acc = mutableListOf<javax.swing.JLabel>()
        for (c in root.components) {
            if (c is javax.swing.JLabel) acc += c
            if (c is java.awt.Container) acc += findLabels(c)
        }
        return acc
    }

    /** 递归收集所有文本区（直接渲染档的文本区被滚动容器包了一层） */
    private fun textAreas(root: java.awt.Container): List<JTextArea> {
        val acc = mutableListOf<JTextArea>()
        for (c in root.components) {
            if (c is JTextArea) acc += c
            if (c is java.awt.Container) acc += textAreas(c)
        }
        return acc
    }

    private fun scrollPanes(root: java.awt.Container): List<JScrollPane> {
        fun walk(c: java.awt.Container): List<JScrollPane> {
            val self = if (c is JScrollPane) listOf(c) else emptyList()
            return self + c.components.filterIsInstance<java.awt.Container>().flatMap { walk(it) }
        }
        return walk(root)
    }

    @Test
    fun 单行短输出用紧凑标签() {
        val p = pane("null")
        assertEquals("单行应只含 1 个组件", 1, p.componentCount)
        assertTrue("应为标签", p.components[0] is JLabel)
        assertTrue("不应有滚动容器", scrollPanes(p).isEmpty())
    }

    @Test
    fun 十五行以内直接渲染全部行() {
        val code = (1..15).joinToString("\n") { "line $it" }
        val p = pane(code)

        val area = textAreas(p).single()
        assertEquals("应渲染全部 15 行", 15, area.text.lines().size)
        // 直接渲染档位按实际行数撑开，不出现折叠开关
        assertTrue("15 行不应出现折叠开关", findLabels(p).none { it.text.startsWith("Expand") })
    }

    @Test
    fun 直接渲染档位必须能横向滚动() {
        // 回归：此前直接渲染档把 JTextArea 裸放（lineWrap=false 且无滚动容器），
        // 超宽行被直接裁掉且不出现横向滚动条。现在一律套滚动容器。
        val p = pane("x".repeat(600))
        assertEquals("直接渲染档应套滚动容器以支持横向滚动", 1, scrollPanes(p).size)
    }

    @Test
    fun 十五行边界值直接渲染() {
        // 边界：恰好等于阈值仍走直接渲染（不多不少）
        val p = pane((1..ChatUIConstants.LargeContent.DIRECT_MAX_LINES).joinToString("\n") { "l$it" })
        // 边界：恰好等于阈值仍走直接渲染档（不折叠、不分页），滚动容器只负责横向溢出
        assertTrue("边界值应无折叠开关", findLabels(p).none { it.text.startsWith("Expand") })
    }

    @Test
    fun 超过十五行使用滚动容器() {
        val code = (1..ChatUIConstants.LargeContent.DIRECT_MAX_LINES + 1).joinToString("\n") { "line $it" }
        val p = pane(code)

        assertTrue("超过阈值应出现滚动容器", scrollPanes(p).isNotEmpty())
        val area = scrollPanes(p).single().viewport.view as JTextArea
        assertEquals("收起态渲染预览行", ChatUIConstants.LargeContent.CODE_PREVIEW_LINES, area.text.lines().size)
    }

    @Test
    fun 单行但超长时不走紧凑标签() {
        // 1 行但很长：紧凑标签会撑破行宽，应退回常规块
        val p = pane("x".repeat(ChatUIConstants.LargeContent.INLINE_CHIP_MAX_CHARS + 1))
        assertTrue("超长单行应含文本区", textAreas(p).isNotEmpty())
    }

    @Test
    fun 两行输出保留头部行() {
        val p = pane("first\nsecond")
        // 头部行 + 滚动容器（内含文本区）
        assertEquals("应为头部行 + 滚动容器", 2, p.componentCount)
        assertEquals("两行不应触发折叠", 1, scrollPanes(p).size)
    }
}
