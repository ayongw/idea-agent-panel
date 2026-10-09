package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.block.ToolCallCard
import com.ayongw.idea.opencode.shared.ToolCallDto
import com.ayongw.idea.opencode.shared.ToolCallStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具卡片折叠策略（对齐 Kiro 历史消息：执行中展开、终态折叠、用户手动选择优先）。
 */
class ToolCallCollapseUnitTest {

    @Test
    fun 执行中与流式中默认展开() {
        assertTrue(ToolCallCard.defaultExpanded(ToolCallStatus.STREAMING))
        assertTrue(ToolCallCard.defaultExpanded(ToolCallStatus.RUNNING))
    }

    @Test
    fun 到达终态后默认折叠() {
        assertFalse(ToolCallCard.defaultExpanded(ToolCallStatus.COMPLETED))
        assertFalse(ToolCallCard.defaultExpanded(ToolCallStatus.ERROR))
    }

    @Test
    fun 折叠策略只认终态与否不认工具种类() {
        // COMPLETED/ERROR 即折叠，与 name/exit 无关
        listOf(ToolCallStatus.values().toList()).flatten().forEach { status ->
            val expected = status != ToolCallStatus.COMPLETED && status != ToolCallStatus.ERROR
            assertEquals("status=$status", expected, ToolCallCard.defaultExpanded(status))
        }
    }
}