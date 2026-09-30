package com.ayongw.idea.opencode.frontend

import com.ayongw.idea.opencode.frontend.chatApp.ui.ListUpdateCoalescer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 刷新合并器（TSD-30 §5.3）：窗口内多次请求合并为一次，窗口期外放行
 */
class ListUpdateCoalescerUnitTest {

    @Test
    fun `窗口内多次请求只放行一次`() {
        var t = 0L
        val coalescer = ListUpdateCoalescer(windowMs = 100, now = { t })

        assertTrue("首个请求放行", coalescer.request())
        assertFalse("10ms 后仍在窗口内，合并", coalescer.request())
        assertFalse("再 10ms 仍合并", coalescer.request())
    }

    @Test
    fun `过窗口期后重新放行`() {
        var t = 0L
        val coalescer = ListUpdateCoalescer(windowMs = 100, now = { t })

        assertTrue(coalescer.request())
        t = 100
        assertTrue("过窗口期应放行", coalescer.request())
    }

    @Test
    fun `未请求时 shouldRun 为 true`() {
        val coalescer = ListUpdateCoalescer(now = { 0L })
        assertTrue("从未执行过应可直接运行", coalescer.shouldRun)
    }

    @Test
    fun `窗口参数可配置`() {
        var t = 0L
        val fast = ListUpdateCoalescer(windowMs = 10, now = { t })
        assertTrue(fast.request())
        t = 9
        assertFalse(fast.request())
        t = 10
        assertTrue("10ms 窗口到点放行", fast.request())
    }
}
