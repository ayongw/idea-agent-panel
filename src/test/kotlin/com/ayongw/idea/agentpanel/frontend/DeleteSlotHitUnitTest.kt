package com.ayongw.idea.agentpanel.frontend

import com.ayongw.idea.agentpanel.frontend.chatApp.ui.isDeleteSlotHitAt
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ChatUIConstants
import com.intellij.util.ui.JBUI
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 行尾删除热区命中判定。
 *
 * 回归点：JetBrains 的 `JBScrollPane` 用 **overlay 滚动条**，viewport 不为滚动条缩窄
 * （实测 420 宽的滚动面板里滚动条占最右 14px，列表仍是 417 宽）。若热区按列表物理右边界算，
 * 删除槽会落在滚动条下方 —— 既被遮住、点击也被滚动条吃掉。热区必须扣掉这段遮挡宽度。
 */
class DeleteSlotHitUnitTest {

    private val slot = JBUI.scale(ChatUIConstants.SessionList.DELETE_SLOT)
    private val rowRight = 420

    @Test
    fun 无滚动条时按行右边界内slot判定() {
        assertTrue(isDeleteSlotHitAt(rowRight - slot, rowRight = rowRight, rightInset = 0))
        assertFalse(isDeleteSlotHitAt(rowRight - slot - 1, rowRight = rowRight, rightInset = 0))
    }

    @Test
    fun 有滚动条遮挡时热区整体左移() {
        val inset = 14
        // 被滚动条盖住的那条：扣掉遮挡后应落在删除槽内
        assertTrue(isDeleteSlotHitAt(rowRight - slot, rowRight = rowRight, rightInset = inset))
        assertTrue(isDeleteSlotHitAt(rowRight - inset - slot, rowRight = rowRight, rightInset = inset))
        assertFalse(isDeleteSlotHitAt(rowRight - inset - slot - 1, rowRight = rowRight, rightInset = inset))
    }

    @Test
    fun 不扣遮挡宽度会漏判挡住区域的点击() {
        val inset = 14
        // 该点：扣掉遮挡后落在删除槽内，不扣则落到槽外（旧实现的漏判）
        val x = rowRight - slot - inset + 1
        assertTrue("扣遮挡应命中", isDeleteSlotHitAt(x, rowRight = rowRight, rightInset = inset))
        assertFalse("不扣遮挡即漏判（回归保护）", isDeleteSlotHitAt(x, rowRight = rowRight, rightInset = 0))
    }
}