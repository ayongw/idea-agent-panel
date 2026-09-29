package com.ayongw.idea.opencode.shared

import kotlinx.serialization.Serializable

/**
 * 设置管理相关 DTO（前后端 RPC 契约）
 *
 * 作用域与 opencode 配置一致：GLOBAL = `~/.config/opencode`，PROJECT = 当前项目目录。
 */

/** 配置作用域 */
@Serializable
enum class ConfigScopeDto {
    GLOBAL,
    PROJECT
}

/** 设置快照：一次性返回设置页所需的全部读数据 */
@Serializable
data class SettingsSnapshotDto(
    val globalConfigPath: String,
    val projectConfigPath: String,
    val providers: List<ProviderDto> = emptyList(),
    val models: List<ModelDto> = emptyList(),
    /** 默认模型，形如 `provider/model` */
    val defaultModel: String? = null,
    /** 配置中的 skills 目录/URL */
    val skills: List<String> = emptyList(),
    /** 服务端已发现的技能 */
    val discoveredSkills: List<SkillDto> = emptyList(),
    val mcpServers: List<McpServerDto> = emptyList(),
    val mcpTimeout: McpTimeoutDto? = null,
    val shell: String? = null,
    val shells: List<ShellOptionDto> = emptyList(),
    /** 配置中的 instructions（V2 不解析，仅兼容展示） */
    val instructions: List<String> = emptyList(),
    /** AGENTS.md 候选文件 */
    val ruleFiles: List<RuleFileDto> = emptyList(),
    /** 采集过程中的告警（如服务端不可用） */
    val warnings: List<String> = emptyList()
)

/** 供应商 */
@Serializable
data class ProviderDto(
    val id: String,
    val name: String? = null,
    /** 运行时包，如 `aisdk:@ai-sdk/openai-compatible` */
    val packageName: String? = null,
    /** `settings.baseURL` */
    val baseUrl: String? = null,
    val models: List<String> = emptyList(),
    /** 该供应商在配置里出现的作用域；仅存在于服务端目录（未写入配置）时为 null */
    val scope: ConfigScopeDto? = null,
    /** 认证集成 ID，用于写入 apiKey */
    val integrationId: String? = null,
    val hasCredential: Boolean = false
)

/** 已发现技能 */
@Serializable
data class SkillDto(
    val id: String,
    val name: String? = null,
    val path: String? = null,
    val description: String? = null
)

/** MCP 服务器（配置 + 运行状态） */
@Serializable
data class McpServerDto(
    val name: String,
    /** local / remote */
    val type: String,
    val enabled: Boolean = true,
    val command: List<String> = emptyList(),
    val url: String? = null,
    val environment: Map<String, String> = emptyMap(),
    /** 服务端状态：connected / pending / disabled / failed / needs_auth */
    val status: String? = null,
    val statusError: String? = null,
    val scope: ConfigScopeDto? = null
)

/** MCP 超时（毫秒） */
@Serializable
data class McpTimeoutDto(
    val startup: Long? = null,
    val catalog: Long? = null,
    val execution: Long? = null
)

/** 可选 shell */
@Serializable
data class ShellOptionDto(
    val path: String,
    val name: String? = null,
    val acceptable: Boolean = true
)

/** 规则文件（AGENTS.md 等） */
@Serializable
data class RuleFileDto(
    val path: String,
    val scope: ConfigScopeDto,
    val exists: Boolean = false
)

/** 规则文件内容 */
@Serializable
data class RuleFileContentDto(
    val path: String,
    val exists: Boolean,
    val content: String? = null
)

/** 写入结果：失败不抛异常，统一用结构化返回（跨 RPC 更友好） */
@Serializable
data class SettingsWriteResultDto(
    val ok: Boolean,
    val message: String? = null,
    /** 写前备份文件路径 */
    val backupPath: String? = null
) {
    companion object {
        fun ok(backupPath: String? = null): SettingsWriteResultDto =
            SettingsWriteResultDto(true, null, backupPath)

        fun fail(message: String): SettingsWriteResultDto =
            SettingsWriteResultDto(false, message, null)
    }
}