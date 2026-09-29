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
    val type: ChatMessage.ChatMessageType
)

fun ChatMessageDto.toChatMessage(): ChatMessage {
    return ChatMessage(
        id = id,
        content = content,
        author = author,
        isMyMessage = isMyMessage,
        timestamp = timestamp,
        type = type
    )
}

fun ChatMessage.toChatMessageDto(): ChatMessageDto {
    return ChatMessageDto(
        id = id,
        content = content,
        author = author,
        isMyMessage = isMyMessage,
        timestamp = timestamp,
        type = type
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
    val contextFiles: List<ContextFileDto>
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

// ==================== 新增 DTO: ContextFile ====================

@Serializable
data class ContextFileDto(
    val path: String,
    val name: String,
    val summary: String,
    @Serializable(with = LocalDateTimeSerializer::class)
    val addedAt: LocalDateTime,
    val isExplicit: Boolean
)

fun ContextFileDto.toContextFile(): ContextFile {
    return ContextFile(
        path = path,
        name = name,
        summary = summary,
        addedAt = addedAt,
        isExplicit = isExplicit
    )
}

fun ContextFile.toContextFileDto(): ContextFileDto {
    return ContextFileDto(
        path = path,
        name = name,
        summary = summary,
        addedAt = addedAt,
        isExplicit = isExplicit
    )
}

// ==================== 新增 DTO: ContextSelection ====================

@Serializable
data class ContextSelectionDto(
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val content: String,
    @Serializable(with = LocalDateTimeSerializer::class)
    val selectedAt: LocalDateTime
)

fun ContextSelectionDto.toContextSelection(): ContextSelection {
    return ContextSelection(
        filePath = filePath,
        startLine = startLine,
        endLine = endLine,
        content = content,
        selectedAt = selectedAt
    )
}

fun ContextSelection.toContextSelectionDto(): ContextSelectionDto {
    return ContextSelectionDto(
        filePath = filePath,
        startLine = startLine,
        endLine = endLine,
        content = content,
        selectedAt = selectedAt
    )
}

// ==================== 新增 DTO: PromptContext ====================

@Serializable
data class PromptContextDto(
    val currentFile: ContextFileDto? = null,
    val selection: ContextSelectionDto? = null,
    val explicitFiles: List<ContextFileDto> = emptyList(),
    val cursorPosition: CursorPositionDto? = null
)

fun PromptContextDto.toPromptContext(): PromptContext {
    return PromptContext(
        currentFile = currentFile?.toContextFile(),
        selection = selection?.toContextSelection(),
        explicitFiles = explicitFiles.map { it.toContextFile() },
        cursorPosition = cursorPosition?.toCursorPosition()
    )
}

fun PromptContext.toPromptContextDto(): PromptContextDto {
    return PromptContextDto(
        currentFile = currentFile?.toContextFileDto(),
        selection = selection?.toContextSelectionDto(),
        explicitFiles = explicitFiles.map { it.toContextFileDto() },
        cursorPosition = cursorPosition?.toCursorPositionDto()
    )
}

// ==================== 新增 DTO: CursorPosition ====================

@Serializable
data class CursorPositionDto(
    val filePath: String,
    val line: Int,
    val column: Int
)

fun CursorPositionDto.toCursorPosition(): CursorPosition {
    return CursorPosition(
        filePath = filePath,
        line = line,
        column = column
    )
}

fun CursorPosition.toCursorPositionDto(): CursorPositionDto {
    return CursorPositionDto(
        filePath = filePath,
        line = line,
        column = column
    )
}