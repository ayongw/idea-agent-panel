package com.ayongw.idea.opencode.backend.repository

import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ToolCallDto
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * REST 消息 → 面板气泡映射（TSD-30 §5.8）。
 *
 * 纯映射、无 IO/无状态：user 单气泡；assistant 按 `parts[]` 顺序拆成
 * 思考（id 带 [REASONING_ID_SUFFIX]）+ 正文 + 工具卡片，各 id 与事件流侧一致以便原地互相覆盖。
 * 输入的 REST 列表为最新在前，[toBubbles] 反转为面板所需的最早在前。
 */
internal class MessageMapper(private val aiAuthor: String) {

    /**
     * REST 消息列表 → 面板气泡列表。
     *
     * `GET /api/session/{id}/message` 返回 `data[]` 最新在前（下标 0 为最新），
     * 面板要求最早在前（与事件流追加顺序一致），故反转。
     */
    fun toBubbles(messages: List<OpenCodeMessage>): List<ChatMessage> =
        messages.asReversed().flatMap(::toChatMessages)

    /** opencode 消息 → 面板气泡：user 单条；assistant 按 `content[]` 顺序拆成思考 + 正文气泡 + 工具卡片 */
    private fun toChatMessages(openCodeMsg: OpenCodeMessage): List<ChatMessage> {
        val at = Instant.ofEpochMilli(openCodeMsg.createdMillis)
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        if (openCodeMsg.role == "user") {
            return listOf(
                ChatMessage(
                    id = openCodeMsg.id,
                    content = openCodeMsg.content,
                    author = "Me",
                    isMyMessage = true,
                    timestamp = at,
                    type = ChatMessage.ChatMessageType.TEXT
                )
            )
        }

        val bubbles = mutableListOf<ChatMessage>()
        var textEmitted = false
        openCodeMsg.parts.forEach { part ->
            when (part) {
                // 思考过程 → AI_THINKING 气泡：id 带后缀，与事件流侧一致（两路可原地互相覆盖）
                is OpenCodePart.Reasoning ->
                    bubbles += reasoningMessage(openCodeMsg, part.text, at)
                // 同一消息的多个 text 片段仍合并为一个气泡，落在首个 text 片段的位置
                is OpenCodePart.Text -> {
                    if (!textEmitted && openCodeMsg.content.isNotBlank()) {
                        bubbles += assistantTextMessage(openCodeMsg, at)
                        textEmitted = true
                    }
                }
                is OpenCodePart.Tool -> bubbles += toolMessage(part.call, at)
            }
        }
        if (!textEmitted && openCodeMsg.content.isNotBlank()) {
            bubbles += assistantTextMessage(openCodeMsg, at)
        }
        return bubbles
    }

    /** 思考过程气泡：id 与事件流侧 `assistantMessageId#reasoning` 一致 */
    private fun reasoningMessage(
        openCodeMsg: OpenCodeMessage,
        reasoning: String,
        at: LocalDateTime
    ) = ChatMessage(
        id = openCodeMsg.id + REASONING_ID_SUFFIX,
        content = reasoning,
        author = aiAuthor,
        isMyMessage = false,
        timestamp = at,
        type = ChatMessage.ChatMessageType.AI_THINKING
    )

    private fun assistantTextMessage(
        openCodeMsg: OpenCodeMessage,
        at: LocalDateTime
    ) = ChatMessage(
        id = openCodeMsg.id,
        content = openCodeMsg.content,
        author = aiAuthor,
        isMyMessage = false,
        timestamp = at,
        type = ChatMessage.ChatMessageType.TEXT
    )

    /** 工具卡片气泡：id 用 `call_*`，与事件流侧一致，两路可原地互相覆盖 */
    private fun toolMessage(call: OpenCodeToolCall, at: LocalDateTime): ChatMessage {
        val tool = ToolCallDto(
            callId = call.callId,
            name = call.name,
            input = call.input,
            output = call.output,
            status = call.status,
            exit = call.exit,
            truncated = call.truncated
        )
        return ChatMessage(
            id = call.callId,
            content = tool.summary,
            author = aiAuthor,
            isMyMessage = false,
            timestamp = at,
            type = ChatMessage.ChatMessageType.TOOL,
            tool = tool
        )
    }

    private companion object {
        /** 思考气泡 id 后缀（与事件流侧一致，见 SessionStreamState.REASONING_ID_SUFFIX） */
        const val REASONING_ID_SUFFIX = "#reasoning"
    }
}
