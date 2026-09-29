package com.ayongw.plugins.opencode.shared

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.util.*

/** 会话运行状态 */
@Serializable
enum class SessionStatus {
    IDLE,                    // 空闲
    STREAMING,               // 流式响应中
    WAITING_PERMISSION,      // 等待权限确认
    ERROR,                   // 错误状态
    ABORTED                  // 已中止
}

/** 权限风险等级 */
@Serializable
enum class RiskLevel {
    LOW, MEDIUM, HIGH, CRITICAL
}

/** 权限响应 */
@Serializable
enum class PermissionResponse {
    ALLOW_ONCE,    // 允许一次
    ALLOW_ALWAYS,  // 始终允许
    REJECT         // 拒绝
}

/** 会话状态 */
@Serializable
data class SessionState(
    val sessionId: String = UUID.randomUUID().toString(),
    val title: String = "新会话",
    val parts: List<MessagePart> = emptyList(),
    val status: SessionStatus = SessionStatus.IDLE,
    val pendingPermission: PermissionRequest? = null,
    @Serializable(with = LocalDateTimeSerializer::class)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Serializable(with = LocalDateTimeSerializer::class)
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val contextFiles: List<ContextFile> = emptyList()
) {
    /** 获取最后一条用户消息的预览 */
    val lastUserMessagePreview: String?
        get() = parts.lastOrNull { it.type == MessagePart.PartType.TEXT && !it.content.isBlank() }?.content?.take(50)

    /** 获取消息数量 */
    val messageCount: Int
        get() = parts.size

    /** 是否有活跃的流式部件 */
    val hasStreamingPart: Boolean
        get() = parts.any { it.isStreaming }
}

/** 权限请求 */
@Serializable
data class PermissionRequest(
    val permissionId: String,
    val toolName: String,
    val description: String,
    val riskLevel: RiskLevel = RiskLevel.MEDIUM,
    val paramsJson: String = "{}",
    @Serializable(with = LocalDateTimeSerializer::class)
    val requestedAt: LocalDateTime = LocalDateTime.now(),
    var response: PermissionResponse? = null
) {
    /** 是否已响应 */
    val isResponded: Boolean
        get() = response != null
}

/** 上下文文件 */
@Serializable
data class ContextFile(
    val path: String,
    val name: String,
    val summary: String = "",
    @Serializable(with = LocalDateTimeSerializer::class)
    val addedAt: LocalDateTime = LocalDateTime.now(),
    val isExplicit: Boolean = true  // true=用户显式添加, false=自动收集
)

/** 上下文选区 */
@Serializable
data class ContextSelection(
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val content: String,
    @Serializable(with = LocalDateTimeSerializer::class)
    val selectedAt: LocalDateTime = LocalDateTime.now()
)

/** 提示上下文组装 */
@Serializable
data class PromptContext(
    val currentFile: ContextFile? = null,
    val selection: ContextSelection? = null,
    val explicitFiles: List<ContextFile> = emptyList(),
    val cursorPosition: CursorPosition? = null
) {
    /** 生成上下文摘要文本 */
    fun toContextSummary(): String {
        val builder = StringBuilder()

        currentFile?.let {
            builder.appendLine("## 当前文件: ${it.path}")
            if (it.summary.isNotBlank()) builder.appendLine(it.summary)
        }

        selection?.let {
            builder.appendLine("## 选中代码 (${it.filePath}:${it.startLine}-${it.endLine})")
            builder.appendLine("```")
            builder.appendLine(it.content)
            builder.appendLine("```")
        }

        explicitFiles.forEachIndexed { index, file ->
            builder.appendLine("## 上下文文件 ${index + 1}: ${file.path}")
            if (file.summary.isNotBlank()) builder.appendLine(file.summary)
        }

        return builder.toString()
    }
}

/** 光标位置 */
@Serializable
data class CursorPosition(
    val filePath: String,
    val line: Int,
    val column: Int
)