package com.ayongw.idea.agentpanel.shared

import kotlinx.serialization.Serializable

/**
 * 工具调用卡片数据（对应 opencode v2 助手消息 `content[]` 里的 `tool` 部件）
 *
 * 两路来源，气泡 id 统一用 [callId]，可互相原地覆盖：
 * - REST `GET /api/session/{id}/message`：权威值，负责对账与历史回放
 * - 事件流 `session.tool.*`：运行中即时态
 */
@Serializable
data class ToolCallDto(
    /** 调用 ID（`call_` 前缀），即卡片气泡 id */
    val callId: String,
    /** 工具名，如 `shell` / `write` */
    val name: String = "",
    /** 入参（JSON 字符串；`streaming` 阶段可能是未解析完的片段） */
    val input: String = "",
    /** 工具输出正文（`completed` 为 content 拼接；`error` 为错误信息） */
    val output: String = "",
    val status: ToolCallStatus = ToolCallStatus.RUNNING,
    /** shell 类工具的退出码，缺省为 null */
    val exit: Int? = null,
    /** 输出是否被服务端截断 */
    val truncated: Boolean = false
) {
    /** 检索用摘要（工具名 + 入参），落到 [ChatMessage.content] */
    val summary: String
        get() = listOf(name, input).filter { it.isNotBlank() }.joinToString(" ")
}

/** 工具调用状态（对应 v2 `Session.Message.ToolState` 四态） */
@Serializable
enum class ToolCallStatus {
    STREAMING,  // 入参仍在流式生成
    RUNNING,    // 已调用，执行中
    COMPLETED,  // 执行完成
    ERROR       // 执行失败
}