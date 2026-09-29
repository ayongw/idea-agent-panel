package com.ayongw.idea.opencode.backend

import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.McpServerDto
import com.ayongw.idea.opencode.shared.McpTimeoutDto
import com.ayongw.idea.opencode.shared.ProviderDto
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 配置/服务端数据 → DTO 的纯映射（不触碰 IO，便于单测）
 *
 * 合并规则：项目级配置覆盖全局配置；配置里没有但服务端目录里有的条目也要列出（scope 为 null）。
 */
object SettingsMapping {

    /** 供应商：合并 `providers` 配置与服务端目录，并带上认证集成信息 */
    fun providers(
        globalConfig: JsonObject,
        projectConfig: JsonObject,
        live: List<JsonObject>,
        integrations: List<JsonObject>
    ): List<ProviderDto> {
        val globalProviders = globalConfig.obj("providers")
        val projectProviders = projectConfig.obj("providers")
        val liveById = live.mapNotNull { provider -> provider.str("id")?.let { it to provider } }.toMap()

        val ids = LinkedHashSet<String>()
        ids += liveById.keys
        ids += globalProviders.keySet()
        ids += projectProviders.keySet()

        // 字段级合并：项目级覆盖同名键，未覆盖的字段（如 package）仍取全局值
        return ids.map { id ->
            val projectCfg = projectProviders.objOrNull(id)
            val globalCfg = globalProviders.objOrNull(id)
            val integration = integrations.firstOrNull { it.str("id") == id }
            ProviderDto(
                id = id,
                name = projectCfg?.str("name") ?: globalCfg?.str("name") ?: liveById[id]?.str("name"),
                packageName = projectCfg?.str("package") ?: globalCfg?.str("package"),
                baseUrl = projectCfg?.objOrNull("settings")?.str("baseURL")
                    ?: globalCfg?.objOrNull("settings")?.str("baseURL"),
                models = (projectCfg?.objOrNull("models") ?: globalCfg?.objOrNull("models"))
                    ?.keySet()?.toList().orEmpty(),
                scope = when {
                    projectCfg != null -> ConfigScopeDto.PROJECT
                    globalCfg != null -> ConfigScopeDto.GLOBAL
                    else -> null
                },
                integrationId = integration?.str("id") ?: id,
                hasCredential = integration?.get("connections")?.let { connections ->
                    connections.isJsonArray && connections.asJsonArray.any { it.asObj()?.str("type") == "credential" }
                } ?: false
            )
        }.sortedBy { it.id }
    }

    /** MCP 服务器：合并 `mcp.servers` 配置与服务端运行状态 */
    fun mcpServers(
        globalConfig: JsonObject,
        projectConfig: JsonObject,
        live: List<JsonObject>
    ): List<McpServerDto> {
        val globalServers = globalConfig.obj("mcp").obj("servers")
        val projectServers = projectConfig.obj("mcp").obj("servers")
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
                url = cfg?.str("url"),
                environment = cfg?.element("environment").stringMap(),
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