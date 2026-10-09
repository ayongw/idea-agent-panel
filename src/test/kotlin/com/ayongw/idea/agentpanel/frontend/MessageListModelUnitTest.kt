package com.ayongw.idea.agentpanel.frontend

import com.ayongw.idea.agentpanel.frontend.chatApp.ui.MessageListModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 消息顺序模型的顺序 / 增删 / gridy 派生（TSD-30 §5.7 纯逻辑层）
 */
class MessageListModelUnitTest {

    @Test
    fun `sync 后按服务端顺序保留且 gridy 连续`() {
        val model = MessageListModel()
        model.sync(listOf("a", "b", "c"))

        assertEquals(listOf("a", "b", "c"), model.ids)
        assertEquals(0, model.indexOf("a"))
        assertEquals(1, model.indexOf("b"))
        assertEquals(2, model.indexOf("c"))
        assertEquals(3, model.size)
    }

    @Test
    fun `sync 整体替换顺序（顺序变化后 gridy 随模型派生）`() {
        val model = MessageListModel()
        model.sync(listOf("a", "b"))
        // 顺序变化：c 插入到最前
        model.sync(listOf("c", "a", "b"))

        assertEquals(0, model.indexOf("c"))
        assertEquals(1, model.indexOf("a"))
        assertEquals(2, model.indexOf("b"))
    }

    @Test
    fun `不存在 id 返回 -1 且 contains 为 false`() {
        val model = MessageListModel()
        model.sync(listOf("a"))

        assertEquals(-1, model.indexOf("missing"))
        assertFalse(model.contains("missing"))
        assertTrue(model.contains("a"))
    }

    @Test
    fun `清空后为空且 gridy 无派生`() {
        val model = MessageListModel()
        model.sync(listOf("a", "b"))
        model.sync(emptyList())

        assertTrue(model.ids.isEmpty())
        assertEquals(0, model.size)
        assertEquals(-1, model.indexOf("a"))
    }
}
