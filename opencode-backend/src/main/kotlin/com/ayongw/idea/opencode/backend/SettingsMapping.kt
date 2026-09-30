package com.ayongw.idea.opencode.backend

import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.McpServerDto
import com.ayongw.idea.opencode.shared.McpTimeoutDto
import com.ayongw.idea.opencode.shared.ProviderDto
import com.ayongw.idea.opencode.shared.ProviderModelDto
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 配置/服务端数据 → DTO 的纯映射（不触碰 IO，便于单测）
 *
 * 合并规则：项目级配置覆盖全局配置；配置里没有但服务端目录里有的条目也要列出（scope 为 null）。
 */
object SettingsMapping {

    /** V2 供应商在配置里的容器键 */
    const val PROVIDER_CONTAINER = "providers"

    /** V1 供应商在配置里的容器键（V1 是单数 `provider`，字段与模型禁用写法都不同） */
    const val LEGACY_PROVIDER_CONTAINER = "provider"

    /** V2 `package` 的前缀（`Provider.aisdk()` 生成，V1 的 `npm` 不带） */
    const val AISDK_PREFIX = "aisdk:"

    /** V1 用模型 `status` 表达禁用（`migrateModel` 里 `disabled = status === "deprecated"`） */
    const val LEGACY_DISABLED_STATUS = "deprecated"

    /** V1 两个历史供应商 id 会被 normalize 重命名 */
    private val LEGACY_PROVIDER_ID_RENAMES = mapOf(
        "azure-cognitive-services" to "azure",
        "google-vertex-anthropic" to "google-vertex"
    )

    /**
     * 一条供应商声明
     *
     * @param key 配置文件里的原始键（V1 历史 id 会被改名，写回时必须用原名）
     * @param legacy 来自 V1 的 `provider`：字段为 `npm` / `options.baseURL`，模型禁用写成 `status: "deprecated"`
     */
    data class ProviderConfig(val key: String, val id: String, val json: JsonObject, val legacy: Boolean)

    /** 写配置时的定位：容器键 + 实际写入的供应商键 */
    data class ProviderWriteTarget(val container: String, val key: String)

    /**
     * 收集配置里的供应商声明（V2 `providers` 与 V1 `provider` 两个容器都读）
     *
     * opencode 的 `normalize.ts` 对两者做同一层合并：**同名条目 V2 整体覆盖 V1**（`mergeMaps` 直接覆盖并报冲突），
     * 所以这里也以 V2 优先；V1 的历史 id 按 `ConfigMigrateV1.providerID` 重命名。
     */
    fun providerConfigs(config: JsonObject): Map<String, ProviderConfig> {
        val result = LinkedHashMap<String, ProviderConfig>()
        config.obj(PROVIDER_CONTAINER).entrySet().forEach { (key, value) ->
            value.asObj()?.let { result[key] = ProviderConfig(key, key, it, legacy = false) }
        }
        config.obj(LEGACY_PROVIDER_CONTAINER).entrySet().forEach { (key, value) ->
            val json = value.asObj() ?: return@forEach
            val id = LEGACY_PROVIDER_ID_RENAMES[key] ?: key
            if (result.containsKey(id)) return@forEach
            result[id] = ProviderConfig(key, id, json, legacy = true)
        }
        return result
    }

    /**
     * 写配置时该供应商应落在哪个容器、哪个键
     *
     * 跟随它当前的声明（V1 声明就写回 V1 的键），未声明过则按 V2 新建，
     * 避免在 V1 配置文件里凭空造出一份并存的 V2 条目（opencode 会为此报冲突诊断）。
     */
    fun providerWriteTarget(config: JsonObject, providerId: String): ProviderWriteTarget =
        providerConfigs(config)[providerId]?.let { entry ->
            if (entry.legacy) ProviderWriteTarget(LEGACY_PROVIDER_CONTAINER, entry.key)
            else ProviderWriteTarget(PROVIDER_CONTAINER, entry.key)
        } ?: ProviderWriteTarget(PROVIDER_CONTAINER, providerId)

    /**
     * 供应商包名，统一成 V2 的 `aisdk:<npm>` 形式
     *
     * V1 的 `npm: "@ai-sdk/openai-compatible"` 经迁移后即 `package: "aisdk:@ai-sdk/openai-compatible"`，
     * 界面统一展示 V2 形式，写回 V1 时再剥掉前缀。
     */
    fun providerPackage(entry: ProviderConfig): String? {
        entry.json.str("package")?.let { return it }
        return if (entry.legacy) entry.json.str("npm")?.let { AISDK_PREFIX + it } else null
    }

    /** 供应商 baseURL：V2 取 `settings.baseURL`，V1 取 `api`（优先）或 `options.baseURL` */
    fun providerBaseUrl(entry: ProviderConfig): String? {
        entry.json.objOrNull("settings")?.str("baseURL")?.let { return it }
        if (!entry.legacy) return null
        return entry.json.str("api") ?: entry.json.objOrNull("options")?.str("baseURL")
    }

    /** 供应商：合并配置声明（V2/V1 两种写法）、服务端目录与已启用模型清单，并带上认证集成信息 */
    fun providers(
        globalConfig: JsonObject,
        projectConfig: JsonObject,
        live: List<JsonObject>,
        liveModels: List<JsonObject>,
        integrations: List<JsonObject>
    ): List<ProviderDto> {
        val globalProviders = providerConfigs(globalConfig)
        val projectProviders = providerConfigs(projectConfig)
        val liveById = live.mapNotNull { provider -> provider.str("id")?.let { it to provider } }.toMap()
        val enabledModelsByProvider = liveModels
            .mapNotNull { model -> model.str("providerID")?.let { it to model } }
            .groupBy({ it.first }, { it.second })

        val ids = LinkedHashSet<String>()
        ids += liveById.keys
        ids += globalProviders.keys
        ids += projectProviders.keys

        // 字段级合并：项目级覆盖同名键，未覆盖的字段（如 package）仍取全局值
        return ids.map { id ->
            val projectCfg = projectProviders[id]
            val globalCfg = globalProviders[id]
            val integration = integrations.firstOrNull { it.str("id") == id }
            ProviderDto(
                id = id,
                name = projectCfg?.json?.str("name") ?: globalCfg?.json?.str("name") ?: liveById[id]?.str("name"),
                packageName = projectCfg?.let { providerPackage(it) } ?: globalCfg?.let { providerPackage(it) },
                baseUrl = projectCfg?.let { providerBaseUrl(it) } ?: globalCfg?.let { providerBaseUrl(it) },
                models = models(
                    globalConfig = globalCfg,
                    projectConfig = projectCfg,
                    live = enabledModelsByProvider[id].orEmpty()
                ),
                scope = when {
                    projectCfg != null -> ConfigScopeDto.PROJECT
                    globalCfg != null -> ConfigScopeDto.GLOBAL
                    else -> null
                },
                custom = projectCfg != null || globalCfg != null,
                integrationId = integration?.str("id") ?: id,
                hasCredential = integration?.get("connections")?.let { connections ->
                    connections.isJsonArray && connections.asJsonArray.any { it.asObj()?.str("type") == "credential" }
                } ?: false
            )
        }.sortedBy { it.id }
    }

    /**
     * 供应商模型清单：配置里声明的（含禁用状态）与服务端**已启用**清单取并集
     *
     * 服务端 `GET /api/model` 会过滤掉被禁模型，禁用状态只存在于配置里，
     * 所以必须并集，否则界面上被禁模型会直接消失、无法再启用。
     */
    private fun models(
        globalConfig: ProviderConfig?,
        projectConfig: ProviderConfig?,
        live: List<JsonObject>
    ): List<ProviderModelDto> {
        // 服务端已启用模型：id / modelID 都建索引（两者可能不同，任一个都能匹配配置里的键）
        val canonicalId = LinkedHashMap<String, String>()
        val names = HashMap<String, String?>()
        live.forEach { model ->
            val id = model.str("id") ?: model.str("modelID") ?: return@forEach
            names[id] = model.str("name")
            listOfNotNull(model.str("id"), model.str("modelID")).distinct().forEach { canonicalId[it] = id }
        }

        val result = LinkedHashMap<String, ProviderModelDto>()
        canonicalId.values.distinct().forEach { id ->
            result[id] = ProviderModelDto(id = id, name = names[id])
        }

        // 配置声明：补禁用状态 / declaredInConfig，并纳入服务端未返回的（被禁用或尚未生效）
        listOf(globalConfig, projectConfig).forEach { entry ->
            entry?.json?.objOrNull("models")?.entrySet()?.forEach { (key, value) ->
                val declared = value.asObj()
                val id = canonicalId[key] ?: key
                val existing = result[id]
                result[id] = ProviderModelDto(
                    id = id,
                    name = declared?.str("name") ?: existing?.name,
                    disabled = modelDisabled(entry.legacy, declared) ?: existing?.disabled ?: false,
                    declaredInConfig = true
                )
            }
        }
        // 名称缺失时按 id 兜底（如禁用中的模型：服务端不返回它、配置里也没声明 name），列表不出现空名称
        return result.values
            .map { if (it.name.isNullOrBlank()) it.copy(name = displayNameOf(it.id)) else it }
            .sortedBy { it.id }
    }

    /** id 兜底显示名：`claude-opus-4.7` → `Claude Opus 4.7`（仅用于展示，不写回配置） */
    private fun displayNameOf(id: String): String = id
        .split('-')
        .filter { it.isNotBlank() }
        .joinToString(" ") { part -> part.replaceFirstChar { it.uppercaseChar() } }
        .ifBlank { id }

    /** 模型是否被禁用：V2 看 `disabled`，V1 看 `status == "deprecated"`（V1 没有 `disabled` 字段） */
    private fun modelDisabled(legacy: Boolean, model: JsonObject?): Boolean? {
        model ?: return null
        model.boolean("disabled")?.let { return it }
        return if (legacy && model.str("status") == LEGACY_DISABLED_STATUS) true else null
    }

    /**
     * 技能加载来源：配置 `skills` 声明的路径 / URL
     *
     * opencode 兼容两种写法（`packages/core/src/config/normalize.ts`）：
     * 字符串数组，或 `{ "paths": [...], "urls": [...] }` 对象——两种都要读，否则界面上会漏展示。
     */
    fun skillPaths(globalConfig: JsonObject, projectConfig: JsonObject): List<String> =
        (skillPathsOf(globalConfig) + skillPathsOf(projectConfig)).distinct()

    private fun skillPathsOf(config: JsonObject): List<String> {
        val skills = config.element("skills") ?: return emptyList()
        if (skills.isJsonArray) return skills.stringList()
        val obj = skills.asObj() ?: return emptyList()
        return obj.element("paths").stringList() + obj.element("urls").stringList()
    }

    /** MCP 服务器：合并 `mcp.servers` 配置与服务端运行状态 */
    fun mcpServers(
        globalConfig: JsonObject,
        projectConfig: JsonObject,
        live: List<JsonObject>
    ): List<McpServerDto> {
        val globalServers = mcpServerEntries(globalConfig)
        val projectServers = mcpServerEntries(projectConfig)
        val liveByName = live.mapNotNull { server -> server.str("name")?.let { it to server } }.toMap()

        val names = LinkedHashSet<String>()
        names += projectServers.keySet()
        names += globalServers.keySet()
        names += liveByName.keys

        return names.map { name ->
            val projectCfg = projectServers.objOrNull(name)
            val cfg = projectCfg ?: globalServers.objOrNull(name)
            val status = liveByName[name]?.objOrNull("status")
            McpServerDto(
                name = name,
                type = cfg?.str("type") ?: "local",
                enabled = isEnabled(cfg),
                command = cfg?.element("command").stringList(),
                cwd = cfg?.str("cwd"),
                url = cfg?.str("url"),
                environment = cfg?.element("environment").stringMap(),
                headers = cfg?.element("headers").stringMap(),
                status = status?.str("status"),
                statusError = status?.str("error"),
                scope = when {
                    projectCfg != null -> ConfigScopeDto.PROJECT
                    cfg != null -> ConfigScopeDto.GLOBAL
                    else -> null
                }
            )
        }.sortedBy { it.name }
    }

    /**
     * MCP 服务器条目：原生形态 `mcp.servers.<name>`，兼容 V1 扁平形态 `mcp.<name>`
     * （opencode `config/normalize.ts` 同样兼容两者）；扁平扫描时跳过保留键 `servers` / `timeout`，同名以原生形态为准。
     */
    private fun mcpServerEntries(config: JsonObject): JsonObject {
        val mcp = config.obj("mcp")
        val entries = mcp.obj("servers").deepCopy()
        mcp.asMap().forEach { (name, value) ->
            if (name == "servers" || name == "timeout") return@forEach
            val entry = value.asObj() ?: return@forEach
            if (!entries.has(name)) entries.add(name, entry)
        }
        return entries
    }

    /** MCP 超时：字段级合并，项目级优先 */
    fun mcpTimeout(projectConfig: JsonObject, globalConfig: JsonObject): McpTimeoutDto? {
        val projectTimeout = projectConfig.obj("mcp").objOrNull("timeout")
        val globalTimeout = globalConfig.obj("mcp").objOrNull("timeout")
        if (projectTimeout == null && globalTimeout == null) return null
        return McpTimeoutDto(
            startup = projectTimeout?.long("startup") ?: globalTimeout?.long("startup"),
            catalog = projectTimeout?.long("catalog") ?: globalTimeout?.long("catalog"),
            execution = projectTimeout?.long("execution") ?: globalTimeout?.long("execution")
        )
    }

    /** 兼容 V1 `enabled` 与 V2 `disabled` 两种写法 */
    fun isEnabled(cfg: JsonObject?): Boolean {
        cfg ?: return true
        cfg.boolean("disabled")?.let { return !it }
        cfg.boolean("enabled")?.let { return it }
        return true
    }
}

// ==================== JSON 读取小工具（包内共用） ====================

// 注意：不要直接用 JsonObject.get(key)——Gson 2.11 对其加了非空断言，缺键时会抛 NPE，统一走 asMap()

fun JsonElement?.asObj(): JsonObject? = this?.takeIf { it.isJsonObject }?.asJsonObject

fun JsonObject.element(key: String): JsonElement? = asMap()[key]

fun JsonObject.obj(key: String): JsonObject = objOrNull(key) ?: JsonObject()

fun JsonObject.objOrNull(key: String): JsonObject? = element(key).asObj()

fun JsonObject.str(key: String): String? = element(key)?.takeIf { it.isJsonPrimitive }?.asString

fun JsonObject.boolean(key: String): Boolean? =
    element(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

fun JsonObject.long(key: String): Long? =
    element(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

fun JsonElement?.stringList(): List<String> =
    this?.takeIf { it.isJsonArray }
        ?.asJsonArray
        ?.mapNotNull { item -> item.takeIf { it.isJsonPrimitive }?.asString }
        .orEmpty()

fun JsonElement?.stringMap(): Map<String, String> =
    this?.takeIf { it.isJsonObject }
        ?.asJsonObject
        ?.entrySet()
        ?.associate { (key, value) -> key to (value.takeIf { it.isJsonPrimitive }?.asString ?: "") }
        .orEmpty()