package com.ayongw.plugins.opencode.shared

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.util.*

/**
 * 消息部件 - 对话中的最小渲染单元
 * 一条消息可包含多个部件（文本、代码块、思考过程、权限请求、工具调用等）
 */
@Serializable
data class MessagePart(
    val id: String = UUID.randomUUID().toString(),
    val type: PartType,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    @Contextual
    val timestamp: LocalDateTime = LocalDateTime.now()
) {
    /** 部件类型 */
    enum class PartType {
        TEXT,           // 普通文本/Markdown
        CODE,           // 代码块
        REASONING,      // 思考过程
        PERMISSION,     // 权限确认请求
        TOOL_USE,       // 工具调用
        TOOL_RESULT,    // 工具调用结果
        ERROR           // 错误信息
    }

    /** 是否为流式部件（正在接收中） - 非序列化字段 */
    @Transient
    var isStreaming: Boolean = false

    /** 流式内容缓冲区 - 非序列化字段 */
    @Transient
    val streamingBuffer: StringBuilder = StringBuilder()

    companion object {
        /** 创建文本部件 */
        fun text(content: String): MessagePart = MessagePart(type = PartType.TEXT, content = content)

        /** 创建代码块部件 */
        fun code(language: String, content: String): MessagePart =
            MessagePart(type = PartType.CODE, content = content, metadata = mapOf("language" to language))

        /** 创建思考过程部件 */
        fun reasoning(content: String): MessagePart = MessagePart(type = PartType.REASONING, content = content)

        /** 创建权限请求部件 */
        fun permission(
            permissionId: String,
            toolName: String,
            description: String,
            riskLevel: String = "medium"
        ): MessagePart =
            MessagePart(
                type = PartType.PERMISSION,
                content = description,
                metadata = mapOf(
                    "permissionId" to permissionId,
                    "toolName" to toolName,
                    "riskLevel" to riskLevel
                )
            )

        /** 创建工具调用部件 */
        fun toolUse(toolName: String, paramsJson: String): MessagePart =
            MessagePart(
                type = PartType.TOOL_USE,
                content = paramsJson,
                metadata = mapOf("toolName" to toolName)
            )

        /** 创建工具结果部件 */
        fun toolResult(toolName: String, resultJson: String, isError: Boolean = false): MessagePart =
            MessagePart(
                type = PartType.TOOL_RESULT,
                content = resultJson,
                metadata = mapOf("toolName" to toolName, "isError" to isError.toString())
            )

        /** 创建错误部件 */
        fun error(message: String): MessagePart = MessagePart(type = PartType.ERROR, content = message)
    }
}