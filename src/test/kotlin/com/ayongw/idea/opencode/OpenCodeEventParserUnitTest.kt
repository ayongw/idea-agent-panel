package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.event.OpenCodeEvent
import com.ayongw.idea.opencode.backend.event.OpenCodeEventParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/api/event` 帧解析的契约回归：直接回放真实抓帧 fixture（见 TSD-06 §4.3/§4.4）
 */
class OpenCodeEventParserUnitTest {

    private fun dataFrames(fixture: String): List<String> =
        readFixture(fixture).lineSequence()
            .filter { it.startsWith("data: ") }
            .map { it.removePrefix("data: ") }
            .toList()

    private fun readFixture(name: String): String =
        requireNotNull(javaClass.classLoader.getResource("sse/$name")) { "缺少 fixture: sse/$name" }.readText()

    private fun events(fixture: String): List<OpenCodeEvent> =
        dataFrames(fixture).mapNotNull { OpenCodeEventParser.parse(it) }

    @Test
    fun successFixtureYieldsTextStreamWithFinalTextAsDeltaAccumulation() {
        val parsed = events("real-session-success.txt")

        assertTrue("应无未映射事件", parsed.none { it is OpenCodeEvent.Unexpected })
        assertTrue("应含 server.connected", parsed.any { it is OpenCodeEvent.ServerConnected })
        assertTrue("应含流式文本", parsed.any { it is OpenCodeEvent.TextStarted })

        val ended = parsed.filterIsInstance<OpenCodeEvent.TextEnded>().last()
        val accumulated = parsed.filterIsInstance<OpenCodeEvent.TextDelta>()
            .filter { it.assistantMessageId == ended.assistantMessageId && it.ordinal == ended.ordinal }
            .joinToString("") { it.delta }
        assertEquals("ended.text 应等于同消息同段的 delta 累积", ended.text, accumulated)
        assertEquals("PONG", ended.text)

        val reasoning = parsed.filterIsInstance<OpenCodeEvent.ReasoningEnded>().last()
        assertTrue("reasoning 应带全文", reasoning.text.isNotBlank())

        val stepEnded = parsed.filterIsInstance<OpenCodeEvent.StepEnded>().last()
        assertEquals("stop", stepEnded.finish)
        assertTrue("step.ended 应带 token 用量", (stepEnded.tokens?.input ?: 0L) > 0L)

        assertTrue("应含 usage.updated", parsed.any { it is OpenCodeEvent.UsageUpdated })
        assertTrue("应含 execution.succeeded", parsed.any { it is OpenCodeEvent.ExecutionSucceeded })
    }

    @Test
    fun errorFixtureYieldsProviderFailureWithStatus() {
        val parsed = events("real-session-error.txt")

        val stepFailed = parsed.filterIsInstance<OpenCodeEvent.StepFailed>().single()
        assertEquals("provider.invalid-request", stepFailed.error.type)
        assertEquals(400, stepFailed.error.status)

        val executionFailed = parsed.filterIsInstance<OpenCodeEvent.ExecutionFailed>().single()
        assertEquals("provider.invalid-request", executionFailed.error.type)
    }

    @Test
    fun toolFixtureYieldsToolLifecycleAndPermissionRequest() {
        val parsed = events("real-session-tool-call.txt")

        assertTrue("应无未映射事件", parsed.none { it is OpenCodeEvent.Unexpected })

        val inputStarted = parsed.filterIsInstance<OpenCodeEvent.ToolInputStarted>().first()
        assertEquals("shell", inputStarted.toolName)
        assertTrue("工具调用 ID 应为 call_ 前缀", inputStarted.callId.startsWith("call_"))

        val called = parsed.filterIsInstance<OpenCodeEvent.ToolCalled>().first()
        assertTrue("入参应为 JSON 对象", called.inputJson.contains("echo hello"))

        val succeeded = parsed.filterIsInstance<OpenCodeEvent.ToolSucceeded>().first()
        assertTrue("输出应含命令结果", succeeded.output.contains("hello"))
        assertEquals(0, succeeded.exit)

        val permission = parsed.filterIsInstance<OpenCodeEvent.PermissionAsked>().single()
        assertTrue("requestId 应为 per_ 前缀", permission.requestId.startsWith("per_"))
        assertEquals("external_directory", permission.action)
        assertEquals(listOf("/tmp/*"), permission.resources)
        assertEquals(inputStarted.sessionId, permission.sessionId)
    }

    @Test
    fun shellCreatedSessionIdComesFromInfoMetadata() {
        val frame = """
            {"id":"evt_1","created":1,"type":"shell.created","location":{"directory":"/tmp/p"},
             "data":{"info":{"id":"sh_1","status":"running","command":"echo hi","cwd":"/tmp/p",
             "file":"/tmp/sh_1.out","metadata":{"sessionID":"ses_from_info"}}}}
        """.trimIndent()

        val event = OpenCodeEventParser.parse(frame) as OpenCodeEvent.ShellCreated
        assertEquals("ses_from_info", event.sessionId)
        assertEquals("sh_1", event.shellId)
        assertEquals("echo hi", event.command)
    }

    @Test
    fun unknownTypeIsReportedAsUnexpected() {
        val event = OpenCodeEventParser.parse("""{"type":"mystery.event","data":{}}""")
        assertEquals(OpenCodeEvent.Unexpected("mystery.event"), event)
    }

    @Test
    fun executionInterruptedIsMapped() {
        val event = OpenCodeEventParser.parse(
            """{"type":"session.execution.interrupted","data":{"sessionID":"ses_1"}}"""
        )

        assertEquals(OpenCodeEvent.ExecutionInterrupted("ses_1"), event)
    }

    @Test
    fun malformedFramesAreDropped() {
        assertNull("空帧应丢弃", OpenCodeEventParser.parse(""))
        assertNull("非 JSON 应丢弃", OpenCodeEventParser.parse("not-json"))
        assertNull("缺 type 应丢弃", OpenCodeEventParser.parse("""{"data":{}}"""))
        assertNull("JSON 数组应丢弃", OpenCodeEventParser.parse("""[{"type":"x"}]"""))
    }

    @Test
    fun knownEventWithoutSessionIdIsReportedAsUnexpected() {
        val event = OpenCodeEventParser.parse("""{"type":"session.execution.started","data":{}}""")
        assertEquals(OpenCodeEvent.Unexpected("session.execution.started"), event)
    }
}