package com.ayongw.idea.opencode.shared

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

private val timeFormatter: DateTimeFormatter? = DateTimeFormatter.ofPattern("HH:mm")

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val author: String,
    val isMyMessage: Boolean = false,
    val timestamp: LocalDateTime = LocalDateTime.now(),
    val type: ChatMessageType = ChatMessageType.TEXT,
    /** 工具调用卡片数据（仅 [ChatMessageType.TOOL] 有值），见 [ToolCallDto] */
    val tool: ToolCallDto? = null,
    /**
     * 本条消息的 token 用量（opencode `Session.Message.Assistant.tokens`）。
     * 仅助手正文气泡有值：流式期间未产出，终态后随 REST 对账补齐。
     */
    val usage: TokenUsageDto? = null,
    /** 本条消息花费（USD，`Session.Message.Assistant.cost`）；仅助手正文气泡有值 */
    val costUsd: Double? = null
) : Searchable {

    enum class ChatMessageType {
        AI_THINKING,
        TEXT,
        /** 工具调用卡片（工具名 + 状态 + 入参 + 输出） */
        TOOL;
    }

    @JvmOverloads
    fun formattedTime(dateTimeFormatter: DateTimeFormatter? = timeFormatter): String {
        return timestamp.format(dateTimeFormatter)
    }

    fun isTextMessage(): Boolean = this.type == ChatMessageType.TEXT

    fun isAIThinkingMessage(): Boolean = this.type == ChatMessageType.AI_THINKING

    fun isToolMessage(): Boolean = this.type == ChatMessageType.TOOL

    override fun matches(query: String): Boolean {
        if (query.isBlank()) return false

        return content.contains(query, ignoreCase = true)
    }
}