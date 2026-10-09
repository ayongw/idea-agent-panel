package com.ayongw.idea.agentpanel.server

import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodePortAllocation
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodePortAllocator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 端口选择策略（TSD-31 §3.4）：4096 优先、冲突走备用端口、候选耗尽可上报。
 */
class OpenCodePortAllocatorUnitTest {

    private val candidates = listOf(4096, 4097, 4098)

    private fun allocator(vararg occupied: Int) =
        OpenCodePortAllocator { port -> port !in occupied.toSet() }

    @Test
    fun `默认端口可用时首选 4096`() {
        val result = allocator().allocate(candidates)

        assertTrue(result is OpenCodePortAllocation.Allocated)
        assertEquals(4096, (result as OpenCodePortAllocation.Allocated).port)
        assertEquals("可用即不再探测后续端口", listOf(4096), result.attempts)
    }

    @Test
    fun `默认端口被占用时依次取备用端口`() {
        val result = allocator(4096).allocate(candidates)

        assertEquals(4097, (result as OpenCodePortAllocation.Allocated).port)
        assertEquals(listOf(4096, 4097), result.attempts)
    }

    @Test
    fun `候选全部被占用时上报耗尽并保留尝试轨迹`() {
        val result = allocator(4096, 4097, 4098).allocate(candidates)

        assertTrue(result is OpenCodePortAllocation.Exhausted)
        assertEquals(listOf(4096, 4097, 4098), (result as OpenCodePortAllocation.Exhausted).attempts)
    }

    @Test
    fun `显式指定单一端口且被占用时直接失败不换端口`() {
        val result = allocator(5099).allocate(listOf(5099))

        assertTrue(result is OpenCodePortAllocation.Exhausted)
        assertEquals(listOf(5099), (result as OpenCodePortAllocation.Exhausted).attempts)
    }

    @Test
    fun `候选为空时立即判定耗尽`() {
        val result = allocator().allocate(emptyList())

        assertTrue(result is OpenCodePortAllocation.Exhausted)
        assertTrue((result as OpenCodePortAllocation.Exhausted).attempts.isEmpty())
    }
}