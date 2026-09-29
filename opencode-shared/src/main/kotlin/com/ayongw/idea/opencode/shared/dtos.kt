package com.ayongw.idea.opencode.shared

import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import java.time.LocalDateTime

// ==================== 现有 DTO ====================

@Serializable
data class ChatMessageDto(
    val id: String,
    val content: String,
    val author: String,
    val isMyMessage: Boolean,
    @Serializable(with = LocalDateTimeSerializer::class)
    val timestamp: LocalDateTime,
    val type: ChatMessage.ChatMessageType,
    val tool: ToolCallDto? = null
)

fun ChatMessageDto.toChatMessage(): ChatMessage {
    return ChatMessage(
        id = id,
        content = content,
        author = author,
        isMyMessage = isMyMessage,
        timestamp = timestamp,
        type = type,
        tool = tool
    )
}

fun ChatMessage.toChatMessageDto(): ChatMessageDto {
    return ChatMessageDto(
        id = id,
        content = content,
        author = author,
        isMyMessage = isMyMessage,
        timestamp = timestamp,
        type = type,
        tool = tool
    )
}

// ==================== 新增 DTO: MessagePart ====================

@Serializable
data class MessagePartDto(
    val id: String,
    val type: MessagePart.PartType,
    val content: String,
    val metadata: Map<String, String>,
    @Serializable(with = LocalDateTimeSerializer::class)
    val timestamp: LocalDateTime,
    val isStreaming: Boolean = false
)

fun MessagePartDto.toMessagePart(): MessagePart {
    val part = MessagePart(
        id = id,
        type = type,
        content = content,
        metadata = metadata,
        timestamp = timestamp
    )
    part.isStreaming = isStreaming
    return part
}

fun MessagePart.toMessagePartDto(): MessagePartDto {
    return MessagePartDto(
        id = id,
        type = type,
        content = content,
        metadata = metadata,
        timestamp = timestamp,
        isStreaming = isStreaming
    )
}

// ==================== 新增 DTO: SessionState ====================

@Serializable
data class SessionStateDto(
    val sessionId: String,
    val title: String,
    val parts: List<MessagePartDto>,
    val status: SessionStatus,
    val pendingPermission: PermissionRequestDto?,
    @Serializable(with = LocalDateTimeSerializer::class)
    val createdAt: LocalDateTime,
    @Serializable(with = LocalDateTimeSerializer::class)
    val updatedAt: LocalDateTime,
    val contextFiles: List<ContextFileDto>,
    /** 会话所属工作目录（opencode v2 session.location.directory），用于按工作区过滤 */
    val directory: String? = null
)

fun SessionStateDto.toSessionState(): SessionState {
    return SessionState(
        sessionId = sessionId,
        title = title,
        parts = parts.map { it.toMessagePart() },
        status = status,
        pendingPermission = pendingPermission?.toPermissionRequest(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        contextFiles = contextFiles.map { it.toContextFile() }
    )
}

fun SessionState.toSessionStateDto(): SessionStateDto {
    return SessionStateDto(
        sessionId = sessionId,
        title = title,
        parts = parts.map { it.toMessagePartDto() },
        status = status,
        pendingPermission = pendingPermission?.toPermissionRequestDto(),
        createdAt = createdAt,
        updatedAt = updatedAt,
        contextFiles = contextFiles.map { it.toContextFileDto() }
    )
}

// ==================== 新增 DTO: 待决权限请求 ====================

/**
 * 待决权限请求，由事件流 `permission.asked` 驱动。
 *
 * @param requestId 回复用的请求 ID（实测为 `per_` 前缀，即 `data.id`）
 * @param action 权限动作，如 `external_directory`
 * @param resources 涉及的资源（如 `["/tmp/&#42;"]`）
 */
@Serializable
data class PendingPermissionDto(
    val sessionId: String,
    val requestId: String,
    val action: String,
    val resources: List<String> = emptyList()
)

// ==================== 新增 DTO: PermissionRequest ====================

@Serializable
data class PermissionRequestDto(
    val permissionId: String,
    val toolName: String,
    val description: String,
    val riskLevel: RiskLevel,
    val paramsJson: String,
    @Serializable(with = LocalDateTimeSerializer::class)
    val requestedAt: LocalDateTime,
    val response: PermissionResponse? = null
)

fun PermissionRequestDto.toPermissionRequest(): PermissionRequest {
    val req = PermissionRequest(
        permissionId = permissionId,
        toolName = toolName,
        description = description,
        riskLevel = riskLevel,
        paramsJson = paramsJson,
        requestedAt = requestedAt
    )
    req.response = response
    return req
}

fun PermissionRequest.toPermissionRequestDto(): PermissionRequestDto {
    return PermissionRequestDto(
        permissionId = permissionId,
        toolName = toolName,
        description = description,
        riskLevel = riskLevel,
        paramsJson = paramsJson,
        requestedAt = requestedAt,
        response = response
    )
}

// ==================== DTO: 上下文附件 ====================

@Serializable
data class ContextFileDto(
    val path: String,
    val name: String,
    val summary: String,
    @Serializable(with = LocalDateTimeSerializer::class)
    val addedAt: LocalDateTime,
    val isExplicit: Boolean,
    val kind: ContextKind = ContextKind.FILE,
    /** kind=SKILL 时 opencode 侧技能 id */
    val skillId: String? = null
)

fun ContextFileDto.toContextFile(): ContextFile {
    return ContextFile(
        path = path,
        name = name,
        summary = summary,
        addedAt = addedAt,
        isExplicit = isExplicit,
        kind = kind,
        skillId = skillId
    )
}

fun ContextFile.toContextFileDto(): ContextFileDto {
    return ContextFileDto(
        path = path,
        name = name,
        summary = summary,
        addedAt = addedAt,
        isExplicit = isExplicit,
        kind = kind,
        skillId = skillId
    )
}

// ==================== DTO: 输入区候选（命令 / 规则 / 工作区条目） ====================

/** 命令，对应 v2 `Command.Info` */
@Serializable
data class CommandDto(
    val name: String,
    val description: String? = null
)

/** 规则，对应 v2 `Reference.Info` */
@Serializable
data class ReferenceDto(
    val name: String,
    val path: String,
    val description: String? = null
)

/** 工作区条目，`path` 相对工作区根目录 */
@Serializable
data class WorkspaceEntryDto(
    val path: String,
    val name: String,
    val isDirectory: Boolean
)

/** 会话当前选中的模式与模型（切换会话后回读） */
@Serializable
data class SessionSelectionDto(
    val agentId: String? = null,
    val providerId: String? = null,
    val modelId: String? = null
)

/** 随消息下发的上下文（输入框文本 mention 的解析结果，不落会话存储） */
@Serializable
data class PromptContextDto(
    val attachments: List<ContextFileDto> = emptyList(),
    /** 文本中选中的命令名；null 表示本次走普通 prompt */
    val commandName: String? = null
)