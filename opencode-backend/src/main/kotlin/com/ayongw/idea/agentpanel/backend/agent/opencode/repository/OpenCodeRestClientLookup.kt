package com.ayongw.idea.agentpanel.backend.agent.opencode.repository

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * OpenCodeRestClient 的设置类与上下文类端点（自主类拆出的扩展函数）
 *
 * 覆盖：config / provider / model / mcp / skill / command / reference / fs / integration / info / shell。
 * 传输经 [OpenCodeRestClient.execute]（internal，同模块可见），URL 工具与解析器同包共用。
 */

// ==================== 设置类接口 ====================

/**
 * 配置文档清单（按优先级低→高，含文档路径）
 * GET /api/config
 */
suspend fun OpenCodeRestClient.getConfigEntries(): OpenCodeResult<List<JsonObject>> =
    execute("GET", "/config") { OpenCodeResponseParser.parseDataObjects(it) }

/**
 * 供应商清单
 * GET /api/provider
 */
suspend fun OpenCodeRestClient.getProviders(): OpenCodeResult<List<JsonObject>> =
    execute("GET", "/provider") { OpenCodeResponseParser.parseDataObjects(it) }

/**
 * 供应商展示名映射（providerID → name，name 缺失时回落 id）
 * GET /api/provider
 */
suspend fun OpenCodeRestClient.listProviderNames(): OpenCodeResult<Map<String, String>> =
    execute("GET", "/provider") { json ->
        OpenCodeResponseParser.parseDataObjects(json).mapNotNull { obj ->
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            id to (obj.string("name")?.takeIf { it.isNotBlank() } ?: id)
        }.toMap()
    }

/**
 * 模型清单
 * GET /api/model
 */
suspend fun OpenCodeRestClient.getModels(): OpenCodeResult<List<JsonObject>> =
    execute("GET", "/model") { OpenCodeResponseParser.parseDataObjects(it) }

/**
 * 默认模型（无默认时为 null）
 * GET /api/model/default
 */
suspend fun OpenCodeRestClient.getDefaultModel(): OpenCodeResult<JsonObject?> =
    execute("GET", "/model/default") { OpenCodeResponseParser.parseDataObjects(it).firstOrNull() }

/**
 * MCP 服务器与连接状态
 * GET /api/mcp
 *
 * @param directory 定位目录；不传时服务端可能返回空列表，建议显式传入项目目录
 */
suspend fun OpenCodeRestClient.getMcpServers(directory: String? = null): OpenCodeResult<List<JsonObject>> =
    execute("GET", "/mcp${directoryQuery(directory)}") { OpenCodeResponseParser.parseDataObjects(it) }

/**
 * 已注册技能清单
 * GET /api/skill
 */
suspend fun OpenCodeRestClient.getSkills(): OpenCodeResult<List<JsonObject>> =
    execute("GET", "/skill") { OpenCodeResponseParser.parseDataObjects(it) }

/**
 * 技能清单（结构化）
 * GET /api/skill
 */
suspend fun OpenCodeRestClient.listSkillInfos(): OpenCodeResult<List<OpenCodeSkill>> =
    execute("GET", "/skill") { json ->
        OpenCodeResponseParser.parseDataObjects(json).mapNotNull { obj ->
            val id = obj.string("id")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            OpenCodeSkill(
                id = id,
                name = obj.string("name"),
                path = obj.string("path"),
                description = obj.string("description")
            )
        }
    }

// ==================== 输入区上下文：命令 / 规则 / 工作区文件 ====================

/**
 * 已注册命令清单（内置 + 自定义）
 * GET /api/command
 */
suspend fun OpenCodeRestClient.listCommands(directory: String? = null): OpenCodeResult<List<OpenCodeCommand>> {
    val query = queryString(listOfNotNull(locationPair(directory)))
    return execute("GET", "/command$query") { json ->
        OpenCodeResponseParser.parseDataObjects(json).mapNotNull { obj ->
            obj.string("name")?.takeIf { it.isNotBlank() }
                ?.let { OpenCodeCommand(name = it, description = obj.string("description")) }
        }
    }
}

/**
 * 规则清单（AGENTS.md 等载体文件）
 * GET /api/reference
 */
suspend fun OpenCodeRestClient.listReferences(): OpenCodeResult<List<OpenCodeReference>> =
    execute("GET", "/reference") { json ->
        OpenCodeResponseParser.parseDataObjects(json).mapNotNull { obj ->
            val name = obj.string("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val path = obj.string("path")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            OpenCodeReference(
                name = name,
                path = path,
                description = obj.string("description"),
                hidden = obj.bool("hidden") ?: false
            )
        }
    }

/**
 * 工作区文件检索（服务端按相关度递归排序，文件与目录均返回）
 * GET /api/fs/find
 *
 * @param directory 工作区根目录，返回路径以此为基准
 */
suspend fun OpenCodeRestClient.findEntries(
    query: String,
    directory: String?,
    limit: Int? = null
): OpenCodeResult<List<OpenCodeFsEntry>> {
    val params = buildList {
        add("query" to query)
        limit?.let { add("limit" to it.toString()) }
        locationPair(directory)?.let { add(it) }
    }
    return execute("GET", "/fs/find${queryString(params)}") { json ->
        OpenCodeResponseParser.parseDataObjects(json).mapNotNull(OpenCodeResponseParser::parseFsEntry)
    }
}

/**
 * 工作区目录浏览
 * GET /api/fs/list
 *
 * @param path 目录相对路径；null 表示工作区根目录
 */
suspend fun OpenCodeRestClient.listDirectory(path: String?, directory: String?): OpenCodeResult<List<OpenCodeFsEntry>> {
    val params = buildList {
        path?.takeIf { it.isNotBlank() }?.let { add("path" to it) }
        locationPair(directory)?.let { add(it) }
    }
    return execute("GET", "/fs/list${queryString(params)}") { json ->
        OpenCodeResponseParser.parseDataObjects(json).mapNotNull(OpenCodeResponseParser::parseFsEntry)
    }
}

/**
 * 集成清单（含供应商的认证方式与已存凭据）
 * GET /api/integration
 */
suspend fun OpenCodeRestClient.getIntegrations(): OpenCodeResult<List<JsonObject>> =
    execute("GET", "/integration") { OpenCodeResponseParser.parseDataObjects(it) }

/**
 * 可用 shell 清单
 * GET /api/config/shell
 */
suspend fun OpenCodeRestClient.getShells(): OpenCodeResult<List<JsonObject>> =
    execute("GET", "/config/shell") { OpenCodeResponseParser.parseDataObjects(it) }

/**
 * 服务信息（version/pid/urls/paths）
 * GET /api/info
 */
suspend fun OpenCodeRestClient.getInfo(): OpenCodeResult<JsonObject> =
    execute("GET", "/info") { OpenCodeResponseParser.objectOrData(it) }

/**
 * 写入全局配置的 shell（v2 唯一可经 HTTP 落盘的配置字段）
 * PATCH /api/experimental/config
 */
suspend fun OpenCodeRestClient.setShell(shell: String?): OpenCodeResult<Unit> {
    val body = JsonObject().apply {
        add("shell", if (shell == null) JsonNull.INSTANCE else JsonPrimitive(shell))
    }.toString()
    return execute("PATCH", "/experimental/config", body) { Unit }
}

/**
 * 触发配置重载（写入配置文件后兜底使用）
 * POST /api/location/reload
 */
suspend fun OpenCodeRestClient.reloadConfig(): OpenCodeResult<Unit> =
    execute("POST", "/location/reload", "{}") { Unit }

/**
 * 写入集成凭据（apiKey，落 opencode 数据库，非配置文件）
 * POST /api/integration/{integrationID}/connect/key
 */
suspend fun OpenCodeRestClient.connectKey(integrationId: String, key: String, label: String? = null): OpenCodeResult<Unit> {
    val body = JsonObject().apply {
        addProperty("key", key)
        if (!label.isNullOrBlank()) addProperty("label", label)
    }.toString()
    return execute("POST", "/integration/${encodePath(integrationId)}/connect/key", body) { Unit }
}
