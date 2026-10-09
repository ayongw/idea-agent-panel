package com.ayongw.idea.agentpanel.backend.agent.opencode.event

import com.ayongw.idea.agentpanel.shared.ChatMessage
import com.ayongw.idea.agentpanel.shared.ToolCallDto
import com.ayongw.idea.agentpanel.shared.ToolCallStatus
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 单会话的事件驱动流式状态机（纯逻辑，无 IntelliJ / 网络依赖，便于单测）。
 *
 * 契约（见 TSD-06 §5.5）：
 * - 文本 / 推理按 `(assistantMessageID, ordinal)` 累积；`session.*.ended` 的全文**覆盖校准**（防丢帧 / 重复）
 * - 正文落到 `TEXT` 气泡（id 即 `assistantMessageID`，便于后续对账覆盖）；推理落到独立的 `AI_THINKING` 气泡
 * - 工具调用落到 `TOOL` 卡片气泡（id 即 `callID`，与 REST 工具部件同 id，可被对账原地覆盖）
 * - 同一时刻只有一条消息在流式；`execution/step` 生命周期驱动 [isRunning]
 * - 失败事件把 `error.message` 落成一条正文气泡，避免面板卡在「响应中」
 * - `onEvent` 返回「是否应立即对外发布快照」：里程碑事件恒为 true，delta 事件按 [throttleMillis] 节流
 *   （内容始终累积，丢掉的只是中间态刷新；`ended` 会补齐全文）
 */
class SessionStreamState(
    private val author: String = DEFAULT_AUTHOR,
    private val throttleMillis: Long = DEFAULT_THROTTLE_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis
) {

    private enum class Kind { TEXT, REASONING, TOOL }

    private class Bubble(val kind: Kind, val createdAtMillis: Long) {
        /** ordinal -> 文本片段 */
        val parts = LinkedHashMap<Int, String>()

        /** 工具卡片数据（仅 [Kind.TOOL] 有值） */
        var tool: ToolCallDto? = null
    }

    private val lock = Any()

    /** 按出现顺序保存的流式气泡：气泡 id -> 内容 */
    private val bubbles = LinkedHashMap<String, Bubble>()

    private var running = false
    private var failureText: String? = null
    private var failureId: String? = null
    private var lastPublishAtMillis = 0L

    /** 是否正在执行（`execution.started` 起，`execution.succeeded/failed` 或 `step.failed` 止） */
    val isRunning: Boolean
        get() = synchronized(lock) { running }

    /** 应用一条事件，返回是否需要立即对外发布快照 */
    fun onEvent(event: OpenCodeEvent): Boolean = synchronized(lock) {
        val publish = applyEvent(event)
        if (publish) lastPublishAtMillis = clock()
        publish
    }

    private fun applyEvent(event: OpenCodeEvent): Boolean = when (event) {
            is OpenCodeEvent.ExecutionStarted -> {
                running = true
                true
            }

            is OpenCodeEvent.StepStarted -> {
                running = true
                true
            }

            is OpenCodeEvent.ExecutionSucceeded -> {
                running = false
                finishToolCalls(ToolCallStatus.COMPLETED)
                true
            }

            is OpenCodeEvent.ExecutionFailed -> {
                running = false
                val interrupted = event.error.isUserInterruption()
                if (!interrupted) applyFailure(event.error, null)
                finishToolCalls(if (interrupted) ToolCallStatus.COMPLETED else ToolCallStatus.ERROR)
                true
            }

            is OpenCodeEvent.StepFailed -> {
                running = false
                val interrupted = event.error.isUserInterruption()
                if (!interrupted) applyFailure(event.error, event.assistantMessageId)
                finishToolCalls(if (interrupted) ToolCallStatus.COMPLETED else ToolCallStatus.ERROR)
                true
            }

            // 用户中断的终态事件（实测：中断链路只会来这个，不会有 execution.failed/succeeded）
            is OpenCodeEvent.ExecutionInterrupted -> {
                running = false
                finishToolCalls(ToolCallStatus.COMPLETED)
                true
            }

            is OpenCodeEvent.TextStarted -> {
                bubble(textBubbleId(event.assistantMessageId))
                true
            }

            is OpenCodeEvent.TextDelta -> {
                append(textBubbleId(event.assistantMessageId), event.ordinal, event.delta)
                shouldPublish()
            }

            is OpenCodeEvent.TextEnded -> {
                replace(textBubbleId(event.assistantMessageId), event.ordinal, event.text)
                true
            }

            is OpenCodeEvent.ReasoningStarted -> {
                bubble(reasoningBubbleId(event.assistantMessageId))
                true
            }

            is OpenCodeEvent.ReasoningDelta -> {
                append(reasoningBubbleId(event.assistantMessageId), event.ordinal, event.delta)
                shouldPublish()
            }

            is OpenCodeEvent.ReasoningEnded -> {
                replace(reasoningBubbleId(event.assistantMessageId), event.ordinal, event.text)
                true
            }

            // 工具调用：入参流式 → 已调用 → 执行结果（卡片按 callID 就地更新）
            is OpenCodeEvent.ToolInputStarted -> {
                updateTool(event.callId, name = event.toolName, status = ToolCallStatus.STREAMING)
                true
            }

            is OpenCodeEvent.ToolInputEnded -> {
                updateTool(event.callId, input = event.rawInput, status = ToolCallStatus.STREAMING)
                true
            }

            is OpenCodeEvent.ToolCalled -> {
                updateTool(event.callId, input = event.inputJson, status = ToolCallStatus.RUNNING)
                true
            }

            // shellID 等执行元信息不上卡片，无需刷新
            is OpenCodeEvent.ToolProgress -> false

            is OpenCodeEvent.ToolSucceeded -> {
                updateTool(
                    event.callId,
                    status = ToolCallStatus.COMPLETED,
                    output = event.output,
                    exit = event.exit,
                    truncated = event.truncated
                )
                true
            }

            // shell / 权限 / 用量 / 模型切换等不影响本状态的正文内容
            else -> false
        }

    /** 当前流式产生的消息快照（按出现顺序；空内容的气泡不产出，避免出现空气泡） */
    fun messages(): List<ChatMessage> = synchronized(lock) {
        val result = ArrayList<ChatMessage>(bubbles.size + 1)
        bubbles.forEach { (id, bubble) ->
            val at = toLocalDateTime(bubble.createdAtMillis)
            when (bubble.kind) {
                Kind.TOOL -> bubble.tool?.let { result += toolMessage(id, it, at) }
                else -> {
                    val content = joinParts(bubble.parts)
                    if (content.isNotBlank()) {
                        result += ChatMessage(
                            id = id,
                            content = content,
                            author = author,
                            isMyMessage = false,
                            timestamp = at,
                            type = if (bubble.kind == Kind.REASONING) {
                                ChatMessage.ChatMessageType.AI_THINKING
                            } else {
                                ChatMessage.ChatMessageType.TEXT
                            }
                        )
                    }
                }
            }
        }
        failureText?.let { text ->
            result += ChatMessage(
                id = failureId ?: FAILURE_ID,
                content = text,
                author = author,
                isMyMessage = false,
                timestamp = toLocalDateTime(clock()),
                type = ChatMessage.ChatMessageType.TEXT
            )
        }
        result
    }

    /** 清空缓冲与运行态（切换会话 / 中断清理） */
    fun reset() = synchronized(lock) {
        bubbles.clear()
        running = false
        failureText = null
        failureId = null
        lastPublishAtMillis = 0L
    }

    // ==================== 内部实现（均在 lock 内调用） ====================

    /**
     * 工具卡片就地更新：只覆盖本次事件携带的字段。
     *
     * 卡片 id 即 `callID`，与 REST 工具部件的 `id` 相同，对账时可原地替换。
     */
    private fun updateTool(
        callId: String,
        name: String? = null,
        input: String? = null,
        status: ToolCallStatus? = null,
        output: String? = null,
        exit: Int? = null,
        truncated: Boolean = false
    ) {
        if (callId.isBlank()) return
        val bubble = bubbles.getOrPut(callId) { Bubble(Kind.TOOL, clock()) }
        val current = bubble.tool ?: ToolCallDto(callId = callId)
        bubble.tool = current.copy(
            name = name ?: current.name,
            input = input ?: current.input,
            status = status ?: current.status,
            output = output ?: current.output,
            exit = exit ?: current.exit,
            truncated = truncated || current.truncated
        )
    }

    /** 执行收尾：仍在流式/执行中的工具卡片补一个终态（权威结果随后由 REST 对账覆盖） */
    private fun finishToolCalls(status: ToolCallStatus) {
        bubbles.values.forEach { bubble ->
            val tool = bubble.tool ?: return@forEach
            if (tool.status == ToolCallStatus.STREAMING || tool.status == ToolCallStatus.RUNNING) {
                bubble.tool = tool.copy(status = status)
            }
        }
    }

    private fun toolMessage(id: String, tool: ToolCallDto, at: LocalDateTime) = ChatMessage(
        id = id,
        content = tool.summary,
        author = author,
        isMyMessage = false,
        timestamp = at,
        type = ChatMessage.ChatMessageType.TOOL,
        tool = tool
    )

    private fun bubble(id: String): Bubble = bubbles.getOrPut(id) { Bubble(kindOf(id), clock()) }

    private fun append(bubbleId: String, ordinal: Int, delta: String) {
        val bubble = bubble(bubbleId)
        bubble.parts[ordinal] = (bubble.parts[ordinal] ?: "") + delta
    }

    private fun replace(bubbleId: String, ordinal: Int, text: String) {
        bubble(bubbleId).parts[ordinal] = text
    }

    private fun applyFailure(error: OpenCodeError, assistantMessageId: String?) {
        failureText = listOfNotNull(error.type.takeIf { it.isNotBlank() }, error.message.takeIf { it.isNotBlank() })
            .joinToString(": ")
            .ifBlank { DEFAULT_FAILURE_TEXT }
        failureId = FAILURE_ID_PREFIX + (assistantMessageId ?: EXECUTION_SCOPE)
    }

    private fun shouldPublish(): Boolean = clock() - lastPublishAtMillis >= throttleMillis

    private fun kindOf(bubbleId: String): Kind =
        if (bubbleId.endsWith(REASONING_ID_SUFFIX)) Kind.REASONING else Kind.TEXT

    /** 用户主动中断（实测 `step.failed.error.type == "aborted"`）：不是失败，不应弹失败气泡 */
    private fun OpenCodeError.isUserInterruption(): Boolean = type == ABORT_ERROR_TYPE

    private fun joinParts(parts: Map<Int, String>): String =
        parts.toSortedMap().values.filter { it.isNotEmpty() }.joinToString("\n")

    /** 正文气泡 id 直接复用 `assistantMessageID`，与 REST 对账的消息 id 一致 */
    private fun textBubbleId(assistantMessageId: String): String = assistantMessageId

    private fun reasoningBubbleId(assistantMessageId: String): String = assistantMessageId + REASONING_ID_SUFFIX

    private fun toLocalDateTime(epochMillis: Long) =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()

    companion object {
        const val DEFAULT_AUTHOR = "AI Buddy"

        /** 后端聚合刷新间隔，与前端渲染节奏（75ms）对齐 */
        const val DEFAULT_THROTTLE_MILLIS = 75L

        const val REASONING_ID_SUFFIX = "#reasoning"

        const val FAILURE_ID = "opencode-failure"

        /** 实测：用户中断时 `step.failed.error.type` 为该值 */
        const val ABORT_ERROR_TYPE = "aborted"

        private const val FAILURE_ID_PREFIX = "opencode-failure:"

        /** 无 assistantMessageID 的失败（如 `execution.failed`）归到该作用域 */
        private const val EXECUTION_SCOPE = "execution"

        private const val DEFAULT_FAILURE_TEXT = "请求失败"
    }
}