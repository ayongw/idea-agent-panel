package com.ayongw.idea.agentpanel.server

import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerOutputBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 输出缓冲行为（TSD-31 §3.6 / §4.4）：有界、单行截断、逐行脱敏、并发安全。
 */
class OpenCodeServerOutputBufferUnitTest {

    @Test
    fun `行数达到上限后丢弃最旧行`() {
        val buffer = OpenCodeServerOutputBuffer(maxLines = 3)

        (1..5).forEach { buffer.appendLine("line-$it") }

        assertEquals(3, buffer.size)
        assertEquals(listOf("line-3", "line-4", "line-5"), buffer.snapshot())
    }

    @Test
    fun `多行文本按行拆分存入`() {
        val buffer = OpenCodeServerOutputBuffer()

        buffer.append("first\r\nsecond\nthird")

        assertEquals(listOf("first", "second", "third"), buffer.snapshot())
    }

    @Test
    fun `超长单行被截断并带标记`() {
        val buffer = OpenCodeServerOutputBuffer(maxLineLength = 10)

        buffer.appendLine("0123456789abcdef")

        val line = buffer.snapshot().single()
        assertTrue("应保留前缀", line.startsWith("0123456789"))
        assertTrue("应带截断标记", line.endsWith("...[truncated]"))
    }

    @Test
    fun `登记密钥后原文被替换`() {
        val buffer = OpenCodeServerOutputBuffer()
        buffer.registerSecret("S3cr3t-Pw")

        buffer.appendLine("starting with OPENCODE_SERVER_PASSWORD=S3cr3t-Pw")

        val line = buffer.snapshot().single()
        assertFalse(line.contains("S3cr3t-Pw"))
        assertTrue(line.contains(OpenCodeServerOutputBuffer.MASK))
    }

    @Test
    fun `未登记的常见凭据形态兜底脱敏`() {
        val redacted = OpenCodeServerOutputBuffer.redact(
            "Authorization: Basic b3BlbmNvZGU6cHc= password=plain \"password\": \"json-pw\"",
            emptyList(),
        )

        assertFalse("Basic 头不得保留原文", redacted.contains("b3BlbmNvZGU6cHc="))
        assertFalse("password= 形态不得保留原文", redacted.contains("plain"))
        assertFalse("JSON password 字段不得保留原文", redacted.contains("json-pw"))
        assertEquals(3, redacted.split(OpenCodeServerOutputBuffer.MASK).size - 1)
    }

    @Test
    fun `取尾部若干行用于失败展示`() {
        val buffer = OpenCodeServerOutputBuffer()
        (1..10).forEach { buffer.appendLine("line-$it") }

        assertEquals(listOf("line-8", "line-9", "line-10"), buffer.snapshotTail(3))
        assertTrue("非正数请求返回空", buffer.snapshotTail(0).isEmpty())
    }

    @Test
    fun `并发写入不越界且不丢结构`() {
        val buffer = OpenCodeServerOutputBuffer(maxLines = 50)
        val threads = 8
        val perThread = 200
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)

        repeat(threads) { t ->
            pool.submit {
                start.await()
                repeat(perThread) { i -> buffer.appendLine("t$t-$i") }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("并发写入应在超时内完成", pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals("始终受上限约束", 50, buffer.size)
        assertTrue(buffer.snapshot().all { it.startsWith("t") })
    }
}