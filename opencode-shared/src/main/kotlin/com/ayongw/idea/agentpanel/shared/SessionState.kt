package com.ayongw.idea.agentpanel.shared

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
    val status: SessionStatus = SessionStatus.IDLE,
    val pendingPermission: PermissionRequest? = null,
    @Serializable(with = LocalDateTimeSerializer::class)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Serializable(with = LocalDateTimeSerializer::class)
    val updatedAt: LocalDateTime = LocalDateTime.now(),
    val contextFiles: List<ContextFile> = emptyList(),
    /** 会话最后一条用户消息预览（会话列表展示用；后端当前不填充，为 null） */
    val lastUserMessagePreview: String? = null
)

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

/** 上下文附件类型：决定发送时挂到 prompt 的哪个字段 */
@Serializable
enum class ContextKind {
    /** 文件（工作区内选中的或本机任意文件） */
    FILE,
    /** 目录（工作区浏览时选中） */
    DIRECTORY,
    /** opencode 技能，按 `skillId` 挂到 prompt 的 `skills` 字段 */
    SKILL,
    /** 规则文件（AGENTS.md 等），以文件形式传输 */
    RULE
}

/** 上下文附件（会话级；命令为一次性） */
@Serializable
data class ContextFile(
    val path: String,
    val name: String,
    val summary: String = "",
    @Serializable(with = LocalDateTimeSerializer::class)
    val addedAt: LocalDateTime = LocalDateTime.now(),
    val isExplicit: Boolean = true,  // true=用户显式添加, false=自动收集
    val kind: ContextKind = ContextKind.FILE,
    /** kind=SKILL 时 opencode 侧技能 id */
    val skillId: String? = null
)