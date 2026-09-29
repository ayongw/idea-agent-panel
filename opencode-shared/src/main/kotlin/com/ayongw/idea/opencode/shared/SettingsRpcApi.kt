@file:Suppress("UnstableApiUsage")

package com.ayongw.idea.opencode.shared

import com.intellij.platform.project.ProjectId
import com.intellij.platform.rpc.RemoteApiProviderService
import fleet.rpc.RemoteApi
import fleet.rpc.Rpc
import fleet.rpc.remoteApiDescriptor

/**
 * 设置读写 RPC：前端设置页 ↔ 后端
 *
 * 通道策略：读多走 opencode v2 HTTP 接口，写以配置文件定点 patch 为主
 * （仅 shell 与 apiKey 存在可用的 HTTP 写接口）。
 */
@Rpc
interface SettingsRpcApi : RemoteApi<Unit> {
    companion object {
        suspend fun getInstance(): SettingsRpcApi {
            return RemoteApiProviderService.resolve(remoteApiDescriptor<SettingsRpcApi>())
        }
    }

    /** 设置快照（配置 + 服务端目录 + 运行状态） */
    suspend fun getSnapshot(projectId: ProjectId): SettingsSnapshotDto

    /** 写入/更新供应商：`providers.<id>` */
    suspend fun saveProvider(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        provider: ProviderDto
    ): SettingsWriteResultDto

    /** 删除供应商 */
    suspend fun removeProvider(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        providerId: String
    ): SettingsWriteResultDto

    /** 设置默认模型（`provider/model`，null 表示删除该键） */
    suspend fun setDefaultModel(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        model: String?
    ): SettingsWriteResultDto

    /**
     * 启用/禁用供应商下的模型
     *
     * 写 `providers.<id>.models.<mid>.disabled`：true 写入禁用，false 删除该键。
     * 服务端 `GET /api/model` 会过滤掉被禁模型，故该状态以配置文件为唯一真源。
     *
     * @param scope 写入作用域，取该供应商在配置里声明的作用域（未声明时用 GLOBAL）
     */
    suspend fun setProviderModelEnabled(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        providerId: String,
        modelId: String,
        enabled: Boolean
    ): SettingsWriteResultDto

    /** 新增/更新供应商下的模型：按键写 `providers.<id>.models.<mid>`，不影响同层其它字段 */
    suspend fun saveProviderModel(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        providerId: String,
        modelId: String,
        name: String?
    ): SettingsWriteResultDto

    /** 删除配置里声明的模型（写 null 删键）；仅服务端目录带来的模型删不掉，只能用禁用 */
    suspend fun removeProviderModel(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        providerId: String,
        modelId: String
    ): SettingsWriteResultDto

    /** 保存 skills 目录/URL 列表 */
    suspend fun saveSkills(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        paths: List<String>
    ): SettingsWriteResultDto

    /** 确保该作用域的配置文件存在（缺失时建立空 `{}`），供「打开配置文件」入口使用 */
    suspend fun ensureConfigFile(projectId: ProjectId, scope: ConfigScopeDto): SettingsWriteResultDto

    /** 保存/更新 MCP 服务器：`mcp.servers.<name>` */
    suspend fun saveMcpServer(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        server: McpServerDto
    ): SettingsWriteResultDto

    /** 删除 MCP 服务器 */
    suspend fun removeMcpServer(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        name: String
    ): SettingsWriteResultDto

    /** 保存 MCP 超时：`mcp.timeout`（null 表示删除） */
    suspend fun saveMcpTimeout(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        timeout: McpTimeoutDto?
    ): SettingsWriteResultDto

    /** 保存 instructions 列表（V2 只接受不解析，仅作兼容保留） */
    suspend fun saveInstructions(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        paths: List<String>
    ): SettingsWriteResultDto

    /** 读取规则文件内容（AGENTS.md） */
    suspend fun readRuleFile(projectId: ProjectId, path: String): RuleFileContentDto

    /** 写入规则文件内容（纯文本，不做整目录覆盖） */
    suspend fun saveRuleFile(
        projectId: ProjectId,
        path: String,
        content: String
    ): SettingsWriteResultDto

    /** 写入供应商 apiKey（走 `integration/{id}/connect/key`，落 opencode 数据库） */
    suspend fun saveCredential(
        projectId: ProjectId,
        integrationId: String,
        key: String,
        label: String?
    ): SettingsWriteResultDto

    /** 设置 shell：优先调 `PATCH /api/experimental/config`，失败回退写配置文件 */
    suspend fun setShell(projectId: ProjectId, shell: String?): SettingsWriteResultDto

    /** 触发 opencode 配置重载 */
    suspend fun reloadConfig(projectId: ProjectId): SettingsWriteResultDto
}