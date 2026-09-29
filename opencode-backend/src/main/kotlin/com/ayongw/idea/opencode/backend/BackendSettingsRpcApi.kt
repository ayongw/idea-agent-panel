@file:Suppress("UnstableApiUsage")

package com.ayongw.idea.opencode.backend

import com.ayongw.idea.opencode.backend.repository.ConfigScope
import com.ayongw.idea.opencode.backend.repository.JsoncEditor
import com.ayongw.idea.opencode.backend.repository.OpenCodeConfigStore
import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.McpServerDto
import com.ayongw.idea.opencode.shared.McpTimeoutDto
import com.ayongw.idea.opencode.shared.ModelDto
import com.ayongw.idea.opencode.shared.ProviderDto
import com.ayongw.idea.opencode.shared.RuleFileContentDto
import com.ayongw.idea.opencode.shared.RuleFileDto
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.ayongw.idea.opencode.shared.SettingsSnapshotDto
import com.ayongw.idea.opencode.shared.SettingsWriteResultDto
import com.ayongw.idea.opencode.shared.ShellOptionDto
import com.ayongw.idea.opencode.shared.SkillDto
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.project.Project
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * 设置读写 RPC 实现
 *
 * - 读：优先 opencode v2 HTTP 接口（provider/model/mcp/skill/integration/shell），配置项读配置文件
 * - 写：以配置文件定点 patch 为主；仅 shell 与 apiKey 有可用 HTTP 写接口
 * - 每次写入：读 → 内存中 patch → 备份 `.bak` → 原子写
 */
class BackendSettingsRpcApi : SettingsRpcApi {

    private val store = OpenCodeConfigStore()

    // ==================== 读 ====================

    override suspend fun getSnapshot(projectId: ProjectId): SettingsSnapshotDto {
        val project = projectId.findProjectOrNull()
            ?: return SettingsSnapshotDto("", "", warnings = listOf("未找到项目"))
        val projectDir = projectDir(project)
        val globalFile = store.resolveFile(ConfigScope.GLOBAL, projectDir)
        val projectFile = store.resolveFile(ConfigScope.PROJECT, projectDir)
        val warnings = mutableListOf<String>()

        val globalConfig = readConfig(globalFile, warnings)
        val projectConfig = readConfig(projectFile, warnings)
        val client = BackendChatRepositoryModel.getInstance(project).getRestClient()

        val integrations = fetch("集成清单", warnings) { client.getIntegrations() }.orEmpty()
        val liveProviders = fetch("供应商清单", warnings) { client.getProviders() }.orEmpty()
        val liveModels = fetch("模型清单", warnings) { client.getModels() }.orEmpty()
        val liveMcp = fetch("MCP 列表", warnings) { client.getMcpServers(projectDir?.toString()) }.orEmpty()
        val skills = fetch("技能清单", warnings) { client.getSkills() }.orEmpty()
        val shells = fetch("shell 清单", warnings) { client.getShells() }.orEmpty()

        return SettingsSnapshotDto(
            globalConfigPath = globalFile.toString(),
            projectConfigPath = projectFile.toString(),
            providers = SettingsMapping.providers(globalConfig, projectConfig, liveProviders, integrations),
            models = liveModels.mapNotNull { live ->
                val id = live.str("id") ?: return@mapNotNull null
                ModelDto(
                    id = id,
                    modelID = live.str("modelID") ?: id,
                    providerID = live.str("providerID").orEmpty(),
                    name = live.str("name") ?: id
                )
            },
            defaultModel = resolveDefaultModel(globalConfig, projectConfig, client, warnings),
            skills = projectConfig.element("skills").stringList() + globalConfig.element("skills").stringList(),
            discoveredSkills = skills.mapNotNull { live ->
                val id = live.str("id") ?: return@mapNotNull null
                SkillDto(id = id, name = live.str("name"), path = live.str("path"), description = live.str("description"))
            },
            mcpServers = SettingsMapping.mcpServers(globalConfig, projectConfig, liveMcp),
            mcpTimeout = SettingsMapping.mcpTimeout(projectConfig, globalConfig),
            shell = projectConfig.str("shell") ?: globalConfig.str("shell"),
            shells = shells.mapNotNull { live ->
                val path = live.str("path") ?: return@mapNotNull null
                ShellOptionDto(path = path, name = live.str("name"), acceptable = live.boolean("acceptable") ?: true)
            },
            instructions = projectConfig.element("instructions").stringList() +
                globalConfig.element("instructions").stringList(),
            ruleFiles = ruleFiles(projectDir),
            warnings = warnings
        )
    }

    private suspend fun resolveDefaultModel(
        globalConfig: JsonObject,
        projectConfig: JsonObject,
        client: OpenCodeRestClient,
        warnings: MutableList<String>
    ): String? {
        val live = fetch("默认模型", warnings) { client.getDefaultModel() }
        if (live != null) {
            val providerId = live.str("providerID")
            val id = live.str("id")
            if (!providerId.isNullOrBlank() && !id.isNullOrBlank()) return "$providerId/$id"
        }
        val configured = projectConfig.element("model") ?: globalConfig.element("model") ?: return null
        return when {
            configured.isJsonPrimitive -> configured.asString
            configured.isJsonObject -> {
                val obj = configured.asJsonObject
                val providerId = obj.str("providerID")
                val id = obj.str("model")
                if (providerId != null && id != null) "$providerId/$id" else null
            }
            else -> null
        }
    }

    private fun ruleFiles(projectDir: Path?): List<RuleFileDto> {
        val result = mutableListOf<RuleFileDto>()
        val globalFile = store.globalConfigDir().resolve("AGENTS.md")
        result += RuleFileDto(globalFile.toString(), ConfigScopeDto.GLOBAL, Files.isRegularFile(globalFile))
        if (projectDir != null) {
            val projectFile = projectDir.resolve("AGENTS.md")
            result += RuleFileDto(projectFile.toString(), ConfigScopeDto.PROJECT, Files.isRegularFile(projectFile))
        }
        return result
    }

    // ==================== 写 ====================

    override suspend fun saveProvider(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        provider: ProviderDto
    ): SettingsWriteResultDto = write(projectId, scope) { _, text ->
        var result = text
        provider.name?.let { result = JsoncEditor.patch(result, listOf("providers", provider.id, "name"), JsonPrimitive(it)) }
        provider.packageName?.let {
            result = JsoncEditor.patch(result, listOf("providers", provider.id, "package"), JsonPrimitive(it))
        }
        provider.baseUrl?.let {
            result = JsoncEditor.patch(result, listOf("providers", provider.id, "settings", "baseURL"), JsonPrimitive(it))
        }
        if (provider.models.isNotEmpty()) {
            result = JsoncEditor.patch(result, listOf("providers", provider.id, "models"), modelsObject(provider.models))
        }
        result
    }

    override suspend fun removeProvider(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        providerId: String
    ): SettingsWriteResultDto = write(projectId, scope) { _, text ->
        JsoncEditor.patch(text, listOf("providers", providerId), null)
    }

    override suspend fun setDefaultModel(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        model: String?
    ): SettingsWriteResultDto = write(projectId, scope) { _, text ->
        JsoncEditor.patch(text, listOf("model"), model?.let { JsonPrimitive(it) })
    }

    override suspend fun saveSkills(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        paths: List<String>
    ): SettingsWriteResultDto = write(projectId, scope) { _, text ->
        JsoncEditor.patch(text, listOf("skills"), stringArray(paths))
    }

    override suspend fun saveMcpServer(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        server: McpServerDto
    ): SettingsWriteResultDto = write(projectId, scope) { config, text ->
        // 保留该条目里本 UI 未覆盖的键（如 oauth / codemode / protocol）
        val existing = config.obj("mcp").obj("servers").objOrNull(server.name)?.deepCopy() ?: JsonObject()
        existing.remove("enabled")
        existing.remove("disabled")
        existing.addProperty("type", server.type)
        if (server.type == "remote") {
            existing.remove("command")
            server.url?.let { existing.addProperty("url", it) }
        } else {
            existing.remove("url")
            if (server.command.isNotEmpty()) existing.add("command", stringArray(server.command))
        }
        if (server.environment.isNotEmpty()) existing.add("environment", stringObject(server.environment))
        if (!server.enabled) existing.addProperty("disabled", true)
        JsoncEditor.patch(text, listOf("mcp", "servers", server.name), existing)
    }

    override suspend fun removeMcpServer(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        name: String
    ): SettingsWriteResultDto = write(projectId, scope) { _, text ->
        JsoncEditor.patch(text, listOf("mcp", "servers", name), null)
    }

    override suspend fun saveMcpTimeout(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        timeout: McpTimeoutDto?
    ): SettingsWriteResultDto = write(projectId, scope) { _, text ->
        val value = timeout?.let {
            JsonObject().apply {
                it.startup?.let { ms -> addProperty("startup", ms) }
                it.catalog?.let { ms -> addProperty("catalog", ms) }
                it.execution?.let { ms -> addProperty("execution", ms) }
            }
        }
        JsoncEditor.patch(text, listOf("mcp", "timeout"), value)
    }

    override suspend fun saveInstructions(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        paths: List<String>
    ): SettingsWriteResultDto = write(projectId, scope) { _, text ->
        JsoncEditor.patch(text, listOf("instructions"), stringArray(paths))
    }

    override suspend fun readRuleFile(projectId: ProjectId, path: String): RuleFileContentDto {
        val file = Paths.get(path)
        return if (Files.isRegularFile(file)) {
            RuleFileContentDto(path, exists = true, content = Files.readString(file))
        } else {
            RuleFileContentDto(path, exists = false, content = null)
        }
    }

    override suspend fun saveRuleFile(
        projectId: ProjectId,
        path: String,
        content: String
    ): SettingsWriteResultDto {
        return try {
            val file = Paths.get(path)
            if (Files.isRegularFile(file)) store.backup(file, Files.readString(file))
            store.writeAtomically(file, content)
            SettingsWriteResultDto.ok()
        } catch (e: Exception) {
            SettingsWriteResultDto.fail(e.message ?: e.toString())
        }
    }

    override suspend fun saveCredential(
        projectId: ProjectId,
        integrationId: String,
        key: String,
        label: String?
    ): SettingsWriteResultDto {
        val project = projectId.findProjectOrNull() ?: return SettingsWriteResultDto.fail("未找到项目")
        val client = BackendChatRepositoryModel.getInstance(project).getRestClient()
        val result = client.connectKey(integrationId, key, label)
        return if (result.isSuccess()) {
            SettingsWriteResultDto.ok()
        } else {
            SettingsWriteResultDto.fail(failureMessage(result))
        }
    }

    override suspend fun setShell(projectId: ProjectId, shell: String?): SettingsWriteResultDto {
        val project = projectId.findProjectOrNull() ?: return SettingsWriteResultDto.fail("未找到项目")
        val client = BackendChatRepositoryModel.getInstance(project).getRestClient()
        val viaApi = client.setShell(shell)
        if (viaApi.isSuccess()) return SettingsWriteResultDto.ok()

        // HTTP 不可用时回退写配置文件（作用域按全局，与接口语义一致）
        val fallback = write(projectId, ConfigScopeDto.GLOBAL) { _, text ->
            JsoncEditor.patch(text, listOf("shell"), shell?.let { JsonPrimitive(it) })
        }
        return if (fallback.ok) {
            fallback.copy(message = "接口写入失败，已改配置文件：${failureMessage(viaApi)}")
        } else {
            fallback
        }
    }

    override suspend fun reloadConfig(projectId: ProjectId): SettingsWriteResultDto {
        val project = projectId.findProjectOrNull() ?: return SettingsWriteResultDto.fail("未找到项目")
        val result = BackendChatRepositoryModel.getInstance(project).getRestClient().reloadConfig()
        return if (result.isSuccess()) SettingsWriteResultDto.ok() else SettingsWriteResultDto.fail(failureMessage(result))
    }

    // ==================== 写入骨架 ====================

    /**
     * 读 → 内存 patch → 备份 → 原子写
     *
     * @param transform 入参为（目标文件当前配置对象, 当前文本），返回新文本
     */
    private fun write(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        transform: (JsonObject, String) -> String
    ): SettingsWriteResultDto {
        return try {
            val projectDir = projectId.findProjectOrNull()?.let { projectDir(it) }
            val file = store.resolveFile(scope.toBackendScope(), projectDir)
            val original = store.readText(file)
            val current = store.readObject(file)
            val base = original?.takeIf { it.isNotBlank() } ?: "{}\n"
            val updated = transform(current, base)
            if (updated == base) return SettingsWriteResultDto.ok()
            val backup = original?.let { store.backup(file, it) }
            store.writeAtomically(file, updated)
            SettingsWriteResultDto.ok(backup?.toString())
        } catch (e: Exception) {
            SettingsWriteResultDto.fail(e.message ?: e.toString())
        }
    }

    // ==================== 辅助 ====================

    private fun projectDir(project: Project): Path? = project.basePath?.let { Paths.get(it) }

    private fun ConfigScopeDto.toBackendScope(): ConfigScope = when (this) {
        ConfigScopeDto.GLOBAL -> ConfigScope.GLOBAL
        ConfigScopeDto.PROJECT -> ConfigScope.PROJECT
    }

    private fun readConfig(file: Path, warnings: MutableList<String>): JsonObject =
        runCatching { store.readObject(file) }.getOrElse {
            warnings += "读取配置失败：$file（${it.message}）"
            JsonObject()
        }

    /** 调服务端接口，失败记入 warnings 并返回 null */
    private suspend fun <T> fetch(
        label: String,
        warnings: MutableList<String>,
        call: suspend () -> OpenCodeRestClient.Result<T>
    ): T? {
        return try {
            val result = call()
            if (result.isSuccess()) {
                result.getOrThrow()
            } else {
                warnings += "$label 读取失败：${failureMessage(result)}"
                null
            }
        } catch (e: Exception) {
            warnings += "$label 读取失败：${e.message}"
            null
        }
    }

    private fun failureMessage(result: OpenCodeRestClient.Result<*>): String =
        (result as? OpenCodeRestClient.Result.Failure)?.exception?.message ?: "未知错误"

    private fun modelsObject(models: List<String>): JsonObject = JsonObject().apply {
        models.forEach { add(it, JsonObject()) }
    }

    private fun stringArray(values: List<String>): JsonArray = JsonArray().apply { values.forEach { add(it) } }

    private fun stringObject(values: Map<String, String>): JsonObject = JsonObject().apply {
        values.forEach { (key, value) -> addProperty(key, value) }
    }
}