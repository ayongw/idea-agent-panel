package com.ayongw.idea.opencode.backend.event

/**
 * 会话 token 用量（对应 v2 `TokenUsage.Info`）
 *
 * 实测结构：`{input, output, reasoning, cache:{read, write}}`
 */
data class TokenUsage(
    val input: Long = 0,
    val output: Long = 0,
    val reasoning: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0
)

/** 执行错误（对应 v2 `error{type, message, status}`） */
data class OpenCodeError(
    val type: String,
    val message: String,
    val status: Int? = null
)

/**
 * opencode v2 `GET /api/event` 事件（服务端只发 `data:` 行，类型在 JSON 的 `type` 字段内）
 *
 * 字段与事件名均来自真实抓帧实测，契约见 docs/tsd/TSD-06-事件流接入设计.md §4.3。
 */
sealed class OpenCodeEvent {

    // ==================== 会话 / 执行生命周期 ====================

    data class SessionCreated(val sessionId: String, val title: String?, val directory: String?) : OpenCodeEvent()

    data class SessionInboxEnqueued(val sessionId: String, val inboxId: String?, val delivery: String?) : OpenCodeEvent()

    data class SessionInboxDelivered(val sessionId: String, val inboxId: String?) : OpenCodeEvent()

    /** 一次用户提示触发的执行开始 */
    data class ExecutionStarted(val sessionId: String) : OpenCodeEvent()

    data class ExecutionSucceeded(val sessionId: String) : OpenCodeEvent()

    data class ExecutionFailed(val sessionId: String, val error: OpenCodeError) : OpenCodeEvent()

    data class StepStarted(val sessionId: String, val assistantMessageId: String) : OpenCodeEvent()

    /** 仅表示「有流式活动」，不含文本 */
    data class StepStreamed(val sessionId: String, val assistantMessageId: String) : OpenCodeEvent()

    data class StepEnded(
        val sessionId: String,
        val assistantMessageId: String,
        val finish: String?,
        val tokens: TokenUsage?,
        val cost: Double?
    ) : OpenCodeEvent()

    data class StepFailed(
        val sessionId: String,
        val assistantMessageId: String?,
        val error: OpenCodeError
    ) : OpenCodeEvent()

    // ==================== 推理 / 文本流（delta 不带 durable，ended 带全文） ====================

    data class TextStarted(val sessionId: String, val assistantMessageId: String, val ordinal: Int) : OpenCodeEvent()

    data class TextDelta(
        val sessionId: String,
        val assistantMessageId: String,
        val ordinal: Int,
        val delta: String
    ) : OpenCodeEvent()

    data class TextEnded(
        val sessionId: String,
        val assistantMessageId: String,
        val ordinal: Int,
        val text: String
    ) : OpenCodeEvent()

    data class ReasoningStarted(val sessionId: String, val assistantMessageId: String, val ordinal: Int) : OpenCodeEvent()

    data class ReasoningDelta(
        val sessionId: String,
        val assistantMessageId: String,
        val ordinal: Int,
        val delta: String
    ) : OpenCodeEvent()

    data class ReasoningEnded(
        val sessionId: String,
        val assistantMessageId: String,
        val ordinal: Int,
        val text: String
    ) : OpenCodeEvent()

    // ==================== 工具调用 ====================

    data class ToolInputStarted(
        val sessionId: String,
        val assistantMessageId: String,
        val callId: String,
        val toolName: String
    ) : OpenCodeEvent()

    data class ToolInputEnded(
        val sessionId: String,
        val assistantMessageId: String,
        val callId: String,
        val rawInput: String
    ) : OpenCodeEvent()

    data class ToolCalled(
        val sessionId: String,
        val assistantMessageId: String,
        val callId: String,
        val inputJson: String
    ) : OpenCodeEvent()

    data class ToolProgress(
        val sessionId: String,
        val assistantMessageId: String,
        val callId: String,
        val shellId: String?
    ) : OpenCodeEvent()

    data class ToolSucceeded(
        val sessionId: String,
        val assistantMessageId: String,
        val callId: String,
        val output: String,
        val exit: Int?,
        val truncated: Boolean
    ) : OpenCodeEvent()

    // ==================== 权限请求 ====================

    data class PermissionAsked(
        val sessionId: String,
        val requestId: String,
        val action: String,
        val resources: List<String>
    ) : OpenCodeEvent()

    // ==================== shell 执行（无顶层 sessionID，需从 info.metadata 取） ====================

    data class ShellCreated(
        val sessionId: String?,
        val shellId: String,
        val command: String,
        val cwd: String?,
        val outputFile: String?
    ) : OpenCodeEvent()

    data class ShellExited(val shellId: String, val exit: Int?, val status: String?) : OpenCodeEvent()

    // ==================== 其他 ====================

    /** 事件流连接建立（`server.connected`） */
    data object ServerConnected : OpenCodeEvent()

    data class ModelSelected(val sessionId: String, val modelId: String, val providerId: String) : OpenCodeEvent()

    data class UsageUpdated(val sessionId: String, val tokens: TokenUsage?, val cost: Double?) : OpenCodeEvent()

    /** 未映射的事件类型（前向兼容：仅计数，不参与状态更新） */
    data class Unexpected(val type: String) : OpenCodeEvent()
}