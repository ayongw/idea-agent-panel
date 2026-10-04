package com.ayongw.idea.opencode.backend.repository

import com.ayongw.idea.opencode.shared.ToolCallStatus

/**
 * OpenCode Server v2 REST 数据模型（自 [OpenCodeRestClient] 拆出，契约不变）
 *
 * 命名对应 openapi.json：会话 / 消息 / Agent / 模型 / 命令 / 规则 / 技能 / 文件系统条目
 */

/** 权限回复决策（对应 Session PATCH / permission reply 的 decision 枚举） */
enum class PermissionDecision(val wire: String) {
    ONCE("once"),
    ALWAYS("always"),
    REJECT("reject")
}

data class OpenCodeSession(
    val id: String,
    val title: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    val agent: String? = null,
    val outcome: String? = null,
    val directory: String? = null,
    /** 会话累计花费（USD） */
    val costUsd: Double? = null,
    /** 会话累计 token 用量 */
    val tokens: OpenCodeTokenUsage? = null,
    /** 当前会话模型 ID（`model.id`），用于匹配上下文窗口 */
    val modelId: String? = null,
    /** 当前会话模型供应商（`model.providerID`） */
    val providerId: String? = null
)

data class OpenCodeMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdMillis: Long,
    /** 助手消息本次 step 的 input tokens（上下文占比分子）；用户消息或字段缺失为 null */
    val inputTokens: Long? = null,
    /** 助手消息 `content[]` 部件（按原顺序）；用户消息为空 */
    val parts: List<OpenCodePart> = emptyList()
)

/** 助手消息 `content[]` 部件（仅保留渲染需要的两类） */
sealed class OpenCodePart {
    /** 正文片段（同一消息的多个片段仍合并为一个气泡） */
    data class Text(val text: String) : OpenCodePart()
    data class Reasoning(val text: String) : OpenCodePart()

    data class Tool(val call: OpenCodeToolCall) : OpenCodePart()
}

/** 工具调用部件（`Session.Message.Assistant.Tool`） */
data class OpenCodeToolCall(
    /** 调用 ID（`call_` 前缀），即工具卡片气泡 id */
    val callId: String,
    val name: String,
    /** 入参 JSON 字符串 */
    val input: String,
    /** 输出正文（`error` 状态为错误信息） */
    val output: String,
    val status: ToolCallStatus,
    val exit: Int? = null,
    val truncated: Boolean = false
)

/** `TokenUsage.Info`：会话/助手消息的 token 用量 */
data class OpenCodeTokenUsage(
    val input: Long,
    val output: Long,
    val reasoning: Long,
    val cacheRead: Long,
    val cacheWrite: Long
)

/** Agent（模式），对应 v2 Agent.Info */
data class OpenCodeAgent(
    val id: String,
    val name: String,
    val description: String? = null,
    val mode: String? = null,
    val hidden: Boolean = false
)

/** 模型，对应 v2 Model.Info */
data class OpenCodeModel(
    val id: String,
    val modelID: String,
    val providerID: String,
    val name: String,
    /** 上下文窗口（`limit.context`），未知为 null */
    val limitContext: Long? = null,
    /** 免费模型（`cost` 各档单价均为 0） */
    val free: Boolean = false
)

/** prompt / command 的文件附件（v2 `PromptInput.FileAttachment`） */
data class PromptFile(
    /** `file:///path/to/file`（目录同样以绝对路径 uri 传递） */
    val uri: String,
    val name: String? = null,
    val description: String? = null
)

/** 命令，对应 v2 `Command.Info` */
data class OpenCodeCommand(
    val name: String,
    val description: String? = null
)

/** 规则，对应 v2 `Reference.Info` */
data class OpenCodeReference(
    val name: String,
    val path: String,
    val description: String? = null,
    val hidden: Boolean = false
)

/** 技能，对应 v2 `Skill.Info` */
data class OpenCodeSkill(
    val id: String,
    val name: String? = null,
    val path: String? = null,
    val description: String? = null
)

/** 工作区条目，对应 v2 `FileSystem.Entry`（`path` 相对 `location.directory`） */
data class OpenCodeFsEntry(
    val path: String,
    /** `file` / `directory` */
    val type: String
) {
    val isDirectory: Boolean get() = type == "directory"
}

/**
 * REST 调用结果（原 `OpenCodeRestClient.Result`，提为顶层时加 OpenCode 前缀，
 * 避免与 `kotlin.Result` 混淆）
 */
sealed class OpenCodeResult<out T> {
    data class Success<T>(val value: T) : OpenCodeResult<T>()
    data class Failure(val exception: Throwable) : OpenCodeResult<Nothing>()

    companion object {
        fun <T> success(value: T): OpenCodeResult<T> = Success(value)
        fun <T> failure(exception: Throwable): OpenCodeResult<T> = Failure(exception)
    }

    fun isSuccess(): Boolean = this is Success
    fun isFailure(): Boolean = this is Failure

    fun getOrNull(): T? = if (this is Success) value else null
    fun getOrThrow(): T = if (this is Success) value else throw (this as Failure).exception

    /** 失败原因（成功时为 null），用于日志与提示 */
    fun exceptionOrNull(): Throwable? = (this as? Failure)?.exception
}
