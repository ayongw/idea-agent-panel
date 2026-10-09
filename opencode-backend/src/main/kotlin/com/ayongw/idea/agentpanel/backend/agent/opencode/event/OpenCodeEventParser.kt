package com.ayongw.idea.agentpanel.backend.agent.opencode.event

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * `/api/event` 的 `data` 帧 → [OpenCodeEvent] 的纯函数解析
 *
 * 实测要点（见 TSD-06 §4.1）：服务端只发 `data:` 行、不发 `event:` 行，
 * 事件类型在 JSON 顶层的 `type` 字段；心跳是注释帧（`: heartbeat`），不会走到这里。
 */
object OpenCodeEventParser {

    /**
     * @return 解析出的事件；`data` 为空、非 JSON、缺 `type` 时返回 null；
     *         已知事件缺 `sessionID`（`shell.*` 与 `server.connected` 除外）时返回 [OpenCodeEvent.Unexpected]
     */
    fun parse(dataJson: String): OpenCodeEvent? {
        if (dataJson.isBlank()) return null
        val envelope = runCatching { JsonParser.parseString(dataJson) }.getOrNull()
            ?.takeIf { it.isJsonObject }
            ?.asJsonObject
            ?: return null
        val type = envelope.str("type") ?: return null
        val data = envelope.obj("data") ?: JsonObject()
        val sessionId = data.str("sessionID")

        if (requiresSessionId(type) && sessionId.isNullOrBlank()) {
            return OpenCodeEvent.Unexpected(type)
        }
        val sid = sessionId.orEmpty()

        return when (type) {
            "server.connected" -> OpenCodeEvent.ServerConnected

            "session.created" -> OpenCodeEvent.SessionCreated(
                sessionId = sid,
                title = data.str("title"),
                directory = data.obj("location")?.str("directory") ?: envelope.obj("location")?.str("directory")
            )

            "session.inbox.enqueued" -> OpenCodeEvent.SessionInboxEnqueued(
                sessionId = sid,
                inboxId = data.str("inboxID"),
                delivery = data.obj("item")?.str("delivery")
            )

            "session.inbox.delivered" -> OpenCodeEvent.SessionInboxDelivered(
                sessionId = sid,
                inboxId = data.str("inboxID")
            )

            "session.execution.started" -> OpenCodeEvent.ExecutionStarted(sid)

            "session.execution.succeeded" -> OpenCodeEvent.ExecutionSucceeded(sid)

            "session.execution.failed" -> OpenCodeEvent.ExecutionFailed(
                sessionId = sid,
                error = data.error() ?: OpenCodeError("unknown", "", null)
            )

            "session.execution.interrupted" -> OpenCodeEvent.ExecutionInterrupted(sid)

            "session.step.started" -> OpenCodeEvent.StepStarted(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty()
            )

            "session.step.streamed" -> OpenCodeEvent.StepStreamed(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty()
            )

            "session.step.ended" -> OpenCodeEvent.StepEnded(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty(),
                finish = data.str("finish") ?: data.str("rawFinish"),
                tokens = data.tokens(),
                cost = data.num("cost")
            )

            "session.step.failed" -> OpenCodeEvent.StepFailed(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID"),
                error = data.error() ?: OpenCodeError("unknown", "", null)
            )

            "session.text.started" -> OpenCodeEvent.TextStarted(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty(),
                ordinal = data.int("ordinal") ?: 0
            )

            "session.text.delta" -> OpenCodeEvent.TextDelta(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty(),
                ordinal = data.int("ordinal") ?: 0,
                delta = data.str("delta").orEmpty()
            )

            "session.text.ended" -> OpenCodeEvent.TextEnded(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty(),
                ordinal = data.int("ordinal") ?: 0,
                text = data.str("text").orEmpty()
            )

            "session.reasoning.started" -> OpenCodeEvent.ReasoningStarted(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty(),
                ordinal = data.int("ordinal") ?: 0
            )

            "session.reasoning.delta" -> OpenCodeEvent.ReasoningDelta(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty(),
                ordinal = data.int("ordinal") ?: 0,
                delta = data.str("delta").orEmpty()
            )

            "session.reasoning.ended" -> OpenCodeEvent.ReasoningEnded(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty(),
                ordinal = data.int("ordinal") ?: 0,
                text = data.str("text").orEmpty()
            )

            "session.tool.input.started" -> OpenCodeEvent.ToolInputStarted(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty(),
                callId = data.str("id").orEmpty(),
                toolName = data.str("name").orEmpty()
            )

            "session.tool.input.ended" -> OpenCodeEvent.ToolInputEnded(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty(),
                callId = data.str("id").orEmpty(),
                rawInput = data.str("text").orEmpty()
            )

            "session.tool.called" -> OpenCodeEvent.ToolCalled(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty(),
                callId = data.str("id").orEmpty(),
                inputJson = data.obj("input")?.toString() ?: "{}"
            )

            "session.tool.progress" -> OpenCodeEvent.ToolProgress(
                sessionId = sid,
                assistantMessageId = data.str("assistantMessageID").orEmpty(),
                callId = data.str("id").orEmpty(),
                shellId = data.obj("metadata")?.str("shellID")
            )

            "session.tool.success" -> {
                val metadata = data.obj("metadata")
                OpenCodeEvent.ToolSucceeded(
                    sessionId = sid,
                    assistantMessageId = data.str("assistantMessageID").orEmpty(),
                    callId = data.str("id").orEmpty(),
                    output = data.arr("content")
                        ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject?.str("text") }
                        ?.joinToString("")
                        .orEmpty(),
                    exit = metadata?.int("exit"),
                    truncated = metadata?.bool("truncated") ?: false
                )
            }

            "shell.created" -> {
                val info = data.obj("info")
                OpenCodeEvent.ShellCreated(
                    // v2 该事件无顶层 sessionID，只能从 info.metadata.sessionID 取
                    sessionId = info?.obj("metadata")?.str("sessionID"),
                    shellId = info?.str("id").orEmpty(),
                    command = info?.str("command").orEmpty(),
                    cwd = info?.str("cwd"),
                    outputFile = info?.str("file")
                )
            }

            "shell.exited" -> OpenCodeEvent.ShellExited(
                shellId = data.str("id").orEmpty(),
                exit = data.int("exit"),
                status = data.str("status")
            )

            "permission.asked" -> OpenCodeEvent.PermissionAsked(
                sessionId = sid,
                requestId = data.str("id").orEmpty(),
                action = data.str("action").orEmpty(),
                resources = data.strList("resources")
            )

            "session.model.selected" -> OpenCodeEvent.ModelSelected(
                sessionId = sid,
                modelId = data.obj("model")?.str("id").orEmpty(),
                providerId = data.obj("model")?.str("providerID").orEmpty()
            )

            "session.usage.updated" -> OpenCodeEvent.UsageUpdated(
                sessionId = sid,
                tokens = data.tokens(),
                cost = data.num("cost")
            )

            else -> OpenCodeEvent.Unexpected(type)
        }
    }

    /** `session.*` 与 `permission.*` 事件必须有 sessionID；`shell.*` / `server.connected` 例外 */
    private fun requiresSessionId(type: String): Boolean =
        type.startsWith("session.") || type.startsWith("permission.")
}

private fun JsonObject.str(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.num(name: String): Double? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble

private fun JsonObject.int(name: String): Int? = num(name)?.toInt()

private fun JsonObject.bool(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

private fun JsonObject.obj(name: String): JsonObject? =
    get(name)?.takeIf { it.isJsonObject }?.asJsonObject

private fun JsonObject.arr(name: String): JsonArray? =
    get(name)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.strList(name: String): List<String> =
    arr(name)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive && e.asJsonPrimitive.isString }?.asString }
        ?: emptyList()

private fun JsonObject.tokens(): TokenUsage? {
    val t = obj("tokens") ?: return null
    val cache = t.obj("cache")
    return TokenUsage(
        input = t.num("input")?.toLong() ?: 0L,
        output = t.num("output")?.toLong() ?: 0L,
        reasoning = t.num("reasoning")?.toLong() ?: 0L,
        cacheRead = cache?.num("read")?.toLong() ?: 0L,
        cacheWrite = cache?.num("write")?.toLong() ?: 0L
    )
}

private fun JsonObject.error(): OpenCodeError? {
    val e = obj("error") ?: return null
    return OpenCodeError(
        type = e.str("type") ?: "unknown",
        message = e.str("message").orEmpty(),
        status = e.int("status")
    )
}