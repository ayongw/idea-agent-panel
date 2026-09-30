@file:Suppress("UnstableApiUsage")

package com.ayongw.idea.opencode.backend

import com.ayongw.idea.opencode.backend.mcp.McpToolsCache
import com.ayongw.idea.opencode.backend.mcp.McpToolsClient
import com.ayongw.idea.opencode.backend.mcp.McpToolsResult
import com.ayongw.idea.opencode.backend.repository.ConfigScope
import com.ayongw.idea.opencode.backend.repository.JsoncEditor
import com.ayongw.idea.opencode.backend.repository.OpenCodeConfigStore
import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.McpServerDto
import com.ayongw.idea.opencode.shared.McpTimeoutDto
import com.ayongw.idea.opencode.shared.McpToolDto
import com.ayongw.idea.opencode.shared.McpToolsDto
import com.ayongw.idea.opencode.shared.ProviderDto
import com.ayongw.idea.opencode.shared.RuleFileContentDto
import com.ayongw.idea.opencode.shared.RuleFileDto
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.ayongw.idea.opencode.shared.SettingsSnapshotDto
import com.ayongw.idea.opencode.shared.SettingsWriteResultDto
import com.ayongw.idea.opencode.shared.ShellOptionDto
import com.ayongw.idea.opencode.shared.SkillDto
import com.ayongw.idea.opencode.shared.SkillSourceDto
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.project.Project
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 设置读写 RPC 实现
 *
 * - 读：优先 opencode v2 HTTP 接口（provider/model/mcp/skill/integration/shell），配置项读配置文件
 * - 写：以配置文件定点 patch 为主；仅 shell 与 apiKey 有可用 HTTP 写接口
 * - 每次写入：读 → 内存中 patch → 备份 `.bak` → 原子写
 */
class BackendSettingsRpcApi : SettingsRpcApi {

    private val store = OpenCodeConfigStore()

    private val mcpToolsClient = McpToolsClient()
    private val mcpToolsCache = McpToolsCache(fetcher = { server, dir -> mcpToolsClient.listTools(server, dir) })

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
            providers = SettingsMapping.providers(globalConfig, projectConfig, liveProviders, liveModels, integrations),
            defaultModel = resolveDefaultModel(globalConfig, projectConfig, client, warnings),
            skillSources = skillSources(globalConfig, projectConfig, projectDir),
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
        result += RuleFileDto(globalFile.toString(), ConfigScopeDto.GLOBAL, Files.isRegularFile(globalFile), preview(globalFile))
        if (projectDir != null) {
            val projectFile = projectDir.resolve("AGENTS.md")
            result += RuleFileDto(
                projectFile.toString(),
                ConfigScopeDto.PROJECT,
                Files.isRegularFile(projectFile),
                preview(projectFile)
            )
        }
        return result
    }

    /** 文件开头 [PREVIEW_CHARS] 个字符（去掉首尾空白），供列表预览；读不到时返回 null */
    private fun preview(file: Path): String? =
        runCatching { Files.readString(file) }.getOrNull()
            ?.trim()
            ?.take(PREVIEW_CHARS)
            ?.takeIf { it.isNotEmpty() }

    /**
     * 技能加载来源
     *
     * = opencode 约定扫描目录（全局/项目配置目录下的 `skill`、`skills`，见 `config/plugin/skill.ts`）
     * + 配置 `skills` 声明的路径 / URL。
     */
    private fun skillSources(
        globalConfig: JsonObject,
        projectConfig: JsonObject,
        projectDir: Path?
    ): List<SkillSourceDto> {
        val result = LinkedHashMap<String, SkillSourceDto>()
        listOfNotNull(store.globalConfigDir(), projectDir).forEach { base ->
            listOf("skill", "skills").forEach { name ->
                val dir = base.resolve(name)
                result[dir.toString()] = SkillSourceDto(dir.toString(), declared = false, exists = Files.isDirectory(dir))
            }
        }
        SettingsMapping.skillPaths(globalConfig, projectConfig).forEach { raw ->
            val remote = raw.startsWith("http://") || raw.startsWith("https://")
            val exists = !remote && Files.exists(store.expandUserPath(raw))
            result[raw] = SkillSourceDto(raw, declared = true, exists = exists)
        }
        return result.values.toList()
    }

    // ==================== 写 ====================

    /**
     * 保存供应商：写入位置与键名**跟随它当前的声明**——V1 的 `provider.<id>` 条目继续用
     * `npm` / `options.baseURL`，V2 的 `providers.<id>` 用 `package` / `settings.baseURL`，
     * 否则会在 V1 配置里造出一份并存的 V2 条目（opencode 会报冲突诊断）。
     */
    override suspend fun saveProvider(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        provider: ProviderDto
    ): SettingsWriteResultDto = write(projectId, scope) { config, text ->
        val target = SettingsMapping.providerWriteTarget(config, provider.id)
        val legacy = target.container == SettingsMapping.LEGACY_PROVIDER_CONTAINER
        var result = text
        provider.name?.let {
            result = JsoncEditor.patch(result, listOf(target.container, target.key, "name"), JsonPrimitive(it))
        }
        provider.packageName?.let {
            // V1 的 npm 存裸包名，界面统一按 V2 的 aisdk: 形式展示，写回 V1 时剥掉前缀
            val specifier = if (legacy) it.removePrefix(SettingsMapping.AISDK_PREFIX) else it
            val path = if (legacy) listOf(target.container, target.key, "npm")
            else listOf(target.container, target.key, "package")
            result = JsoncEditor.patch(result, path, JsonPrimitive(specifier))
        }
        provider.baseUrl?.let {
            val path = if (legacy) listOf(target.container, target.key, "options", "baseURL")
            else listOf(target.container, target.key, "settings", "baseURL")
            result = JsoncEditor.patch(result, path, JsonPrimitive(it))
        }
        // 注意：不写 models。模型由模型页按单个键增删改，整体覆盖会丢 limit/capabilities 等字段
        result
    }

    override suspend fun removeProvider(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        providerId: String
    ): SettingsWriteResultDto = write(projectId, scope) { config, text ->
        val target = SettingsMapping.providerWriteTarget(config, providerId)
        JsoncEditor.patch(text, listOf(target.container, target.key), null)
    }

    override suspend fun setDefaultModel(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        model: String?
    ): SettingsWriteResultDto = write(projectId, scope) { _, text ->
        JsoncEditor.patch(text, listOf("model"), model?.let { JsonPrimitive(it) })
    }

    /**
     * 启用/禁用模型：V2 写 `disabled`，V1 没有该字段、按迁移口径写 `status: "deprecated"`；
     * 启用一律删键，避免残留禁用标记。
     */
    override suspend fun setProviderModelEnabled(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        providerId: String,
        modelId: String,
        enabled: Boolean
    ): SettingsWriteResultDto = write(projectId, scope) { config, text ->
        val target = SettingsMapping.providerWriteTarget(config, providerId)
        val legacy = target.container == SettingsMapping.LEGACY_PROVIDER_CONTAINER
        val path = listOf(
            target.container,
            target.key,
            "models",
            modelId,
            if (legacy) "status" else "disabled"
        )
        val disabledValue = if (legacy) JsonPrimitive(SettingsMapping.LEGACY_DISABLED_STATUS) else JsonPrimitive(true)
        JsoncEditor.patch(text, path, if (enabled) null else disabledValue)
    }

    override suspend fun saveProviderModel(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        providerId: String,
        modelId: String,
        name: String?
    ): SettingsWriteResultDto = write(projectId, scope) { config, text ->
        val target = SettingsMapping.providerWriteTarget(config, providerId)
        // 只写 name 键：既能在缺失时补出 `models.<mid>` 条目，又不会覆盖 limit/capabilities 等已有字段
        JsoncEditor.patch(
            text,
            listOf(target.container, target.key, "models", modelId, "name"),
            JsonPrimitive(name?.takeIf { it.isNotBlank() } ?: modelId)
        )
    }

    override suspend fun removeProviderModel(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        providerId: String,
        modelId: String
    ): SettingsWriteResultDto = write(projectId, scope) { config, text ->
        val target = SettingsMapping.providerWriteTarget(config, providerId)
        JsoncEditor.patch(text, listOf(target.container, target.key, "models", modelId), null)
    }

    override suspend fun saveSkills(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        paths: List<String>
    ): SettingsWriteResultDto = write(projectId, scope) { _, text ->
        JsoncEditor.patch(text, listOf("skills"), stringArray(paths))
    }

    override suspend fun ensureConfigFile(projectId: ProjectId, scope: ConfigScopeDto): SettingsWriteResultDto {
        return try {
            val projectDir = projectId.findProjectOrNull()?.let { projectDir(it) }
            val file = store.resolveFile(scope.toBackendScope(), projectDir)
            if (!Files.isRegularFile(file)) store.writeAtomically(file, "{}\n")
            SettingsWriteResultDto.ok()
        } catch (e: Exception) {
            SettingsWriteResultDto.fail(e.message ?: e.toString())
        }
    }

    override suspend fun saveMcpServer(
        projectId: ProjectId,
        scope: ConfigScopeDto,
        server: McpServerDto
    ): SettingsWriteResultDto = write(projectId, scope) { config, text ->
        // 保留该条目里本 UI 未覆盖的键（如 oauth / codemode / protocol / cwd）
        val native = config.obj("mcp").obj("servers").objOrNull(server.name)
        val legacy = legacyMcpEntry(config, server.name)
        // 只在 V1 扁平形态声明过的服务器就地更新，避免文件里留下同名副本
        val path = if (native == null && legacy != null) {
            listOf("mcp", server.name)
        } else {
            listOf("mcp", "servers", server.name)
        }
        val existing = (native ?: legacy)?.deepCopy() ?: JsonObject()
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
        JsoncEditor.patch(text, path, existing)
    }

    /** V1 扁平形态 `mcp.<name>` 里的服务器条目（跳过保留键 `servers` / `timeout`） */
    private fun legacyMcpEntry(config: JsonObject, name: String): JsonObject? =
        if (name == "servers" || name == "timeout") null else config.obj("mcp").objOrNull(name)

    override suspend fun listMcpTools(projectId: ProjectId, serverName: String): McpToolsDto {
        val project = projectId.findProjectOrNull() ?: return McpToolsDto(serverName, error = "未找到项目")
        val projectDir = projectDir(project)
        val warnings = mutableListOf<String>()
        val globalConfig = readConfig(store.resolveFile(ConfigScope.GLOBAL, projectDir), warnings)
        val projectConfig = readConfig(store.resolveFile(ConfigScope.PROJECT, projectDir), warnings)
        val server = SettingsMapping.mcpServers(globalConfig, projectConfig, emptyList())
            .firstOrNull { it.name == serverName }
            ?: return McpToolsDto(serverName, error = "配置里没有该服务器：$serverName")

        // 起进程 / 发请求都放到 IO 线程，避免阻塞 RPC 线程
        val result = withContext(Dispatchers.IO) { mcpToolsCache.list(server, projectDir) }
        return when (result) {
            is McpToolsResult.Success -> McpToolsDto(
                serverName = serverName,
                tools = result.tools.map { McpToolDto(it.name, it.description) },
                note = result.note
            )

            is McpToolsResult.Failure -> McpToolsDto(serverName, error = result.message)
        }
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
        // 配置重载后工具清单可能已变（新增/移除服务器、改命令），清空缓存
        mcpToolsCache.invalidateAll()
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

    private fun stringArray(values: List<String>): JsonArray = JsonArray().apply { values.forEach { add(it) } }

    private fun stringObject(values: Map<String, String>): JsonObject = JsonObject().apply {
        values.forEach { (key, value) -> addProperty(key, value) }
    }

    private companion object {
        /** 规则文件列表预览的字符数 */
        const val PREVIEW_CHARS = 150
    }
}