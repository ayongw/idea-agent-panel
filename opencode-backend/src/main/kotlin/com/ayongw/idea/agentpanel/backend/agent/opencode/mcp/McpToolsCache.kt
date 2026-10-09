package com.ayongw.idea.agentpanel.backend.agent.opencode.mcp

import com.ayongw.idea.agentpanel.shared.McpServerDto
import java.nio.file.Path

/**
 * MCP 工具清单缓存
 *
 * - 命中条件：key 相同且在 TTL 内；key 含「服务器 + 配置签名」，配置一改自然失效
 * - **只缓存成功结果**：失败不缓存，UI 再次展开即可重试
 * - 同一 server 的并发请求按 key 串行，避免重复起进程 / 重复请求
 */
class McpToolsCache(
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val fetcher: (McpServerDto, Path?) -> McpToolsResult,
    private val now: () -> Long = System::currentTimeMillis
) {

    private class Entry(val expiresAt: Long, val result: McpToolsResult.Success)

    private val entries = mutableMapOf<String, Entry>()
    private val locks = mutableMapOf<String, Any>()

    fun list(server: McpServerDto, workingDir: Path?): McpToolsResult {
        val key = keyOf(server)
        cached(key)?.let { return it }
        synchronized(lockOf(key)) {
            // 双检：等锁期间可能已被同 key 的请求填充
            cached(key)?.let { return it }
            val result = fetcher(server, workingDir)
            if (result is McpToolsResult.Success) {
                synchronized(entries) {
                    if (entries.size >= MAX_ENTRIES) entries.clear()
                    entries[key] = Entry(now() + ttlMs, result)
                }
            }
            return result
        }
    }

    /** 清空缓存（配置重载后调用） */
    fun invalidateAll() {
        synchronized(entries) { entries.clear() }
    }

    private fun cached(key: String): McpToolsResult.Success? = synchronized(entries) {
        entries[key]?.takeIf { it.expiresAt > now() }?.result
    }

    private fun lockOf(key: String): Any = synchronized(locks) { locks.getOrPut(key) { Any() } }

    /** 配置签名：任一参与连接的字段变化都会导致缓存失效 */
    private fun keyOf(server: McpServerDto): String = listOf(
        server.name,
        server.type,
        server.command.joinToString(" "),
        server.cwd.orEmpty(),
        server.url.orEmpty(),
        server.environment.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" }
    ).joinToString("|")

    private companion object {
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L
        const val MAX_ENTRIES = 32
    }
}