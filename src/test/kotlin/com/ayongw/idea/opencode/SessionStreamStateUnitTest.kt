package com.ayongw.idea.opencode

import com.ayongw.idea.opencode.backend.event.OpenCodeError
import com.ayongw.idea.opencode.backend.event.OpenCodeEvent
import com.ayongw.idea.opencode.backend.event.OpenCodeEventParser
import com.ayongw.idea.opencode.backend.event.SessionStreamState
import com.ayongw.idea.opencode.shared.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 事件驱动流式状态机的契约回归：回放真实抓帧 fixture（见 TSD-06 §4.4/§5.5），
 * 断言「delta 累积 + ended 全文校准」「运行态生命周期」「失败可见」「节流发布」。
 */
class SessionStreamStateUnitTest {

    private fun events(fixture: String): List<OpenCodeEvent> =
        readFixture(fixture).lineSequence()
            .filter { it.startsWith("data: ") }
            .map { it.removePrefix("data: ") }
            .mapNotNull { OpenCodeEventParser.parse(it) }
            .toList()

    private fun readFixture(name: String): String =
        requireNotNull(javaClass.classLoader.getResource("sse/$name")) { "缺少 fixture: sse/$name" }.readText()

    /** 常量时钟：关闭节流影响，专注于状态语义 */
    private fun state(throttleMillis: Long = 0L) = SessionStreamState(throttleMillis = throttleMillis, clock = { 1_000L })

    // ==================== 成功流：正文 + 推理 ====================

    @Test
    fun successFixtureProducesReasoningAndTextBubbles() {
        val stream = state()
        events("real-session-success.txt").forEach { stream.onEvent(it) }

        val messages = stream.messages()
        assertEquals("应产出推理气泡 + 正文气泡", 2, messages.size)

        val reasoning = messages[0]
        assertEquals(ChatMessage.ChatMessageType.AI_THINKING, reasoning.type)
        assertTrue("推理气泡 id 应带 #reasoning 后缀", reasoning.id.endsWith(SessionStreamState.REASONING_ID_SUFFIX))
        assertTrue("推理内容应为 ended 全文", reasoning.content.contains("PONG"))

        val text = messages[1]
        assertEquals(ChatMessage.ChatMessageType.TEXT, text.type)
        assertEquals("PONG", text.content)
        assertTrue("正文气泡 id 应复用 assistantMessageID", text.id.startsWith("msg_"))
        assertFalse("流式气泡不是用户消息", text.isMyMessage)
    }

    @Test
    fun runningIsFalseAfterExecutionSucceeded() {
        val stream = state()
        val parsed = events("real-session-success.txt")

        stream.onEvent(parsed.first { it is OpenCodeEvent.ExecutionStarted })
        assertTrue("execution.started 后应处于执行中", stream.isRunning)

        val stepEnded = parsed.filterIsInstance<OpenCodeEvent.StepEnded>().last()
        stream.onEvent(stepEnded)
        assertTrue("单个 step 结束后执行可能继续，应保持执行中", stream.isRunning)

        stream.onEvent(parsed.filterIsInstance<OpenCodeEvent.ExecutionSucceeded>().last())
        assertFalse("execution.succeeded 后应结束执行态", stream.isRunning)
    }

    // ==================== 增量语义 ====================

    @Test
    fun endedTextOverridesAccumulatedDeltas() {
        val stream = state()
        stream.onEvent(OpenCodeEvent.TextStarted("ses_1", "msg_1", 0))
        stream.onEvent(OpenCodeEvent.TextDelta("ses_1", "msg_1", 0, "PON"))
        stream.onEvent(OpenCodeEvent.TextEnded("ses_1", "msg_1", 0, "PONG"))

        assertEquals("ended 全文应覆盖而非追加", "PONG", stream.messages().single().content)
    }

    @Test
    fun multipleOrdinalsAreJoinedInOrder() {
        val stream = state()
        stream.onEvent(OpenCodeEvent.TextStarted("ses_1", "msg_1", 0))
        stream.onEvent(OpenCodeEvent.TextStarted("ses_1", "msg_1", 1))
        stream.onEvent(OpenCodeEvent.TextDelta("ses_1", "msg_1", 1, "第二段"))
        stream.onEvent(OpenCodeEvent.TextDelta("ses_1", "msg_1", 0, "第一段"))

        assertEquals("同消息多段应按 ordinal 升序拼接", "第一段\n第二段", stream.messages().single().content)
    }

    @Test
    fun deltaPublishesOnlyAfterThrottleWindow() {
        var now = 0L
        val stream = SessionStreamState(throttleMillis = 100L, clock = { now })

        assertTrue("started 是里程碑，应发布", stream.onEvent(OpenCodeEvent.TextStarted("ses_1", "msg_1", 0)))

        now = 50L
        assertFalse("节流窗口内的 delta 不发布（内容仍累积）", stream.onEvent(OpenCodeEvent.TextDelta("ses_1", "msg_1", 0, "A")))

        now = 120L
        assertTrue("超出节流窗口的 delta 应发布", stream.onEvent(OpenCodeEvent.TextDelta("ses_1", "msg_1", 0, "B")))
        assertEquals("节流只影响刷新频率，不影响内容", "AB", stream.messages().single().content)

        now = 130L
        assertTrue("ended 是里程碑，恒发布", stream.onEvent(OpenCodeEvent.TextEnded("ses_1", "msg_1", 0, "AB")))
    }

    @Test
    fun emptyBubbleIsNotEmitted() {
        val stream = state()
        assertTrue(stream.onEvent(OpenCodeEvent.StepStarted("ses_1", "msg_1")))

        assertTrue("step.started 不应产出空气泡", stream.messages().isEmpty())
    }

    // ==================== 失败可见 ====================

    @Test
    fun errorFixtureRendersVisibleFailureAndStopsRunning() {
        val stream = state()
        events("real-session-error.txt").forEach { stream.onEvent(it) }

        assertFalse("失败后应结束执行态", stream.isRunning)
        val messages = stream.messages()
        assertEquals("失败流只应有一条失败气泡（不产出空正文气泡）", 1, messages.size)
        assertEquals(
            "provider.invalid-request: Provider request failed with HTTP 400",
            messages.single().content
        )
        assertEquals(ChatMessage.ChatMessageType.TEXT, messages.single().type)
    }

    @Test
    fun stepFailedAloneStopsRunningAndUsesAssistantScopeId() {
        val stream = state()
        stream.onEvent(OpenCodeEvent.StepStarted("ses_1", "msg_9"))
        stream.onEvent(
            OpenCodeEvent.StepFailed("ses_1", "msg_9", OpenCodeError("provider.invalid-request", "HTTP 400", 400))
        )

        assertFalse(stream.isRunning)
        assertEquals("opencode-failure:msg_9", stream.messages().single().id)
    }

    @Test
    fun failureWithoutMessageFallsBackToPlaceholderText() {
        val stream = state()
        stream.onEvent(OpenCodeEvent.ExecutionFailed("ses_1", OpenCodeError("", "", null)))

        assertEquals("请求失败", stream.messages().single().content)
    }

    // ==================== 用户中断（实测契约） ====================

    @Test
    fun executionInterruptedStopsRunningWithoutFailureBubble() {
        val stream = state()
        stream.onEvent(OpenCodeEvent.ExecutionStarted("ses_1"))
        stream.onEvent(OpenCodeEvent.TextStarted("ses_1", "msg_1", 0))
        stream.onEvent(OpenCodeEvent.TextEnded("ses_1", "msg_1", 0, "半截回答"))
        stream.onEvent(OpenCodeEvent.ExecutionInterrupted("ses_1"))

        assertFalse("中断后应结束执行态", stream.isRunning)
        assertEquals("中断只应保留已产出的正文，不弹失败气泡", 1, stream.messages().size)
        assertEquals("半截回答", stream.messages().single().content)
    }

    @Test
    fun abortedStepFailureIsInterruptionNotFailure() {
        val stream = state()
        stream.onEvent(OpenCodeEvent.ExecutionStarted("ses_1"))
        stream.onEvent(OpenCodeEvent.StepFailed("ses_1", "msg_1", OpenCodeError("aborted", "Step interrupted", null)))

        assertFalse("aborted 也应结束执行态", stream.isRunning)
        assertTrue("aborted 是用户主动中断，不应出现失败气泡", stream.messages().isEmpty())
    }

    // ==================== 无关事件与重置 ====================

    @Test
    fun nonContentEventsDoNotRequestPublish() {
        val stream = state()
        events("real-session-tool-call.txt")
            .filter { it !is OpenCodeEvent.TextStarted && it !is OpenCodeEvent.TextDelta && it !is OpenCodeEvent.TextEnded }
            .forEach { event ->
                assertFalse("${event::class.simpleName} 不应触发内容刷新", stream.onEvent(event))
            }
    }

    @Test
    fun resetClearsBuffersAndRunningState() {
        val stream = state()
        stream.onEvent(OpenCodeEvent.ExecutionStarted("ses_1"))
        stream.onEvent(OpenCodeEvent.TextStarted("ses_1", "msg_1", 0))
        stream.onEvent(OpenCodeEvent.TextEnded("ses_1", "msg_1", 0, "内容"))

        stream.reset()

        assertTrue("重置后应无消息", stream.messages().isEmpty())
        assertFalse("重置后应回到空闲", stream.isRunning)
    }
}