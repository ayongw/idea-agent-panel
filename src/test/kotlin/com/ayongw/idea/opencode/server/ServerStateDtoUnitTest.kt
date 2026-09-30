package com.ayongw.idea.opencode.server

import com.ayongw.idea.opencode.shared.ServerStateDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Server 运行时状态 DTO 的 RPC 契约（TSD-31 §9.1）
 *
 * 关键约束：新增字段一律带默认值，保证旧客户端能解析新载荷（向后兼容）。
 */
class ServerStateDtoUnitTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `完整状态编解码往返一致`() {
        val original = ServerStateDto(
            state = "FAILED",
            failure = "PROCESS_EXITED",
            detail = "自有进程已退出",
            baseUrl = "http://127.0.0.1:4097",
            port = 4097,
            owned = true,
            refCount = 2,
            outputTail = listOf("stub listening", "process exited with code 1"),
        )

        val decoded = json.decodeFromString<ServerStateDto>(json.encodeToString(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `仅状态字段的最小载荷也能解码（新增字段全部有默认值）`() {
        val decoded = json.decodeFromString<ServerStateDto>("""{"state":"READY"}""")

        assertEquals("READY", decoded.state)
        assertNull(decoded.failure)
        assertNull(decoded.detail)
        assertNull(decoded.baseUrl)
        assertNull(decoded.port)
        assertFalse(decoded.owned)
        assertEquals(0, decoded.refCount)
        assertTrue("输出尾巴默认应为空", decoded.outputTail.isEmpty())
    }

    @Test
    fun `未知字段被忽略以兼容后续版本`() {
        val decoded = json.decodeFromString<ServerStateDto>(
            """{"state":"STARTING","port":4096,"futureField":"ignored"}""",
        )

        assertEquals("STARTING", decoded.state)
        assertEquals(4096, decoded.port)
    }
}