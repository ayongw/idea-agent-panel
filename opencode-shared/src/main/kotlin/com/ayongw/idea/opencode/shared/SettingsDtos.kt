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
    /** 默认模型，形如 `provider/model` */
    val defaultModel: String? = null,
    /** 技能的加载来源：配置声明的路径/URL + opencode 约定扫描目录 */
    val skillSources: List<SkillSourceDto> = emptyList(),
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
    /** 模型清单：配置里声明的（含禁用标记）与服务端启用清单的并集 */
    val models: List<ProviderModelDto> = emptyList(),
    /** 该供应商在配置里出现的作用域；仅存在于服务端目录（未写入配置）时为 null */
    val scope: ConfigScopeDto? = null,
    /** 是否自定义：配置里有声明即为自定义（可改 baseURL/apiKey、可增删模型） */
    val custom: Boolean = false,
    /** 认证集成 ID，用于写入 apiKey */
    val integrationId: String? = null,
    val hasCredential: Boolean = false
)

/**
 * 供应商下的模型
 *
 * 服务端 `GET /api/model` 只返回**已启用**模型，被禁用的只能从配置读
 * （`providers.<id>.models.<mid>.disabled`），故这里取两者并集。
 */
@Serializable
data class ProviderModelDto(
    val id: String,
    val name: String? = null,
    /** 配置里标记为 `disabled`（仅在配置文件中可见） */
    val disabled: Boolean = false,
    /** 是否在配置文件里声明过：只有声明过的模型才能从界面删除 */
    val declaredInConfig: Boolean = false
)

/** 技能加载来源（opencode 的约定目录 + 配置 `skills` 里声明的路径/URL） */
@Serializable
data class SkillSourceDto(
    val path: String,
    /** true = 配置 `skills` 声明；false = opencode 约定扫描目录 */
    val declared: Boolean = false,
    val exists: Boolean = false
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
    /** 本地服务器工作目录（原生配置字段 `cwd`，相对路径按工作区解析） */
    val cwd: String? = null,
    val url: String? = null,
    val environment: Map<String, String> = emptyMap(),
    /** 远程服务器附加请求头（原生配置字段 `headers`） */
    val headers: Map<String, String> = emptyMap(),
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

/** MCP 工具（仅展示所需字段） */
@Serializable
data class McpToolDto(
    val name: String,
    val description: String? = null
)

/**
 * 某个 MCP 服务器的工具清单（插件自连 MCP 拉取，opencode 无此接口，见 TSD-32）
 *
 * [error] 非空表示拉取失败（原因可直接展示）；[note] 是非致命提示（如分页超限）。
 */
@Serializable
data class McpToolsDto(
    val serverName: String,
    val tools: List<McpToolDto> = emptyList(),
    val error: String? = null,
    val note: String? = null
)

/** 可选 shell */
@Serializable
data class ShellOptionDto(
    val path: String,
    val name: String? = null,
    val acceptable: Boolean = true
)

/** 规则文件（AGENTS.md 候选） */
@Serializable
data class RuleFileDto(
    val path: String,
    val scope: ConfigScopeDto,
    val exists: Boolean = false,
    /** 文件开头若干字符，供列表预览；文件不存在时为 null */
    val preview: String? = null
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