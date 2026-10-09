package com.ayongw.idea.agentpanel

import com.ayongw.idea.agentpanel.backend.agent.opencode.mcp.McpTool
import com.ayongw.idea.agentpanel.backend.agent.opencode.mcp.McpToolsCache
import com.ayongw.idea.agentpanel.backend.agent.opencode.mcp.McpToolsResult
import com.ayongw.idea.agentpanel.shared.McpServerDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 工具清单缓存：TTL、失败不缓存、配置变更失效 */
class McpToolsCacheUnitTest {

    private var clock = 0L
    private var calls = 0

    private val server = McpServerDto(name = "s", type = "local", command = listOf("/bin/echo"))

    private fun cache(result: () -> McpToolsResult, ttlMs: Long = 1_000L): McpToolsCache =
        McpToolsCache(
            ttlMs = ttlMs,
            fetcher = { _, _ ->
                calls++
                result()
            },
            now = { clock }
        )

    private fun success(name: String = "a") = McpToolsResult.Success(listOf(McpTool(name, null)))

    @Test
    fun cachesSuccessWithinTtl() {
        val cache = cache({ success() })

        cache.list(server, null)
        clock += 999
        cache.list(server, null)

        assertEquals(1, calls)

        clock += 2
        cache.list(server, null)
        assertEquals(2, calls)
    }

    @Test
    fun doesNotCacheFailuresSoUserCanRetry() {
        val cache = cache({ McpToolsResult.Failure("boom") })

        assertTrue(cache.list(server, null) is McpToolsResult.Failure)
        assertTrue(cache.list(server, null) is McpToolsResult.Failure)

        assertEquals(2, calls)
    }

    @Test
    fun configChangeInvalidatesEntry() {
        val cache = cache({ success() })
        cache.list(server, null)

        // 命令变了 → 配置签名变化 → 重新拉取
        cache.list(server.copy(command = listOf("/bin/echo", "other")), null)

        assertEquals(2, calls)
    }

    @Test
    fun invalidateAllClearsCache() {
        val cache = cache({ success() })
        cache.list(server, null)
        cache.invalidateAll()
        cache.list(server, null)

        assertEquals(2, calls)
    }
}