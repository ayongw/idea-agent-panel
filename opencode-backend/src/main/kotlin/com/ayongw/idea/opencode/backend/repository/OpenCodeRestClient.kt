package com.ayongw.idea.opencode.backend.repository

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * OpenCode Server v2 REST 客户端
 *
 * - 认证：HTTP Basic（用户名默认 opencode + 密码），密码为空则不鉴权
 * - 基路径：`/api`，响应统一为 `{ "data": ... }` 包裹
 * - 端点契约以 `GET /openapi.json` 为准（opencode serve 默认 http://127.0.0.1:4096）
 *
 * 职责拆分（TSD-30 §5.8）：本类保留会话 / 消息 / Agent / 模型核心端点；
 * 设置与上下文类端点见扩展文件 [OpenCodeRestClientLookup]，DTO 见 [OpenCodeModels]，
 * 响应解析见 [OpenCodeResponseParser]，HTTP 传输见 [OpenCodeHttpExecutor]。
 */
class OpenCodeRestClient(
    baseUrl: String,
    private val username: String = DEFAULT_USERNAME,
    private val password: String? = null
) {

    private val gson = Gson()
    private val executor = OpenCodeHttpExecutor(baseUrl, username, password)

    /** 传输委托（internal：供同模块的 [OpenCodeRestClientLookup] 扩展端点复用） */
    internal suspend fun <T> execute(
        method: String,
        path: String,
        body: String? = null,
        parse: (String) -> T
    ): OpenCodeResult<T> = executor.execute(method, path, body, parse)

    /**
     * 探测 Server 是否可用（v2 无 /global/health，用 GET /api/project）
     */
    suspend fun healthCheck(): Boolean = execute("GET", "/project") { true }.isSuccess()

    /**
     * 获取会话列表
     * GET /api/session
     *
     * @param directory 工作区目录；实测服务端只认 `?directory=<绝对路径>`（`location[...]` 形式会被忽略），
     *                  不传则返回本机全部目录的会话
     */
    suspend fun getAllSessions(directory: String? = null): OpenCodeResult<List<OpenCodeSession>> {
        val query = queryString(listOfNotNull(directory?.takeIf { it.isNotBlank() }?.let { "directory" to it }))
        return execute("GET", "/session$query") { json ->
            OpenCodeResponseParser.parseDataObjects(json).map { OpenCodeResponseParser.parseSession(it) }
        }
    }

    /**
     * 创建新会话
     * POST /api/session
     *
     * @param directory 会话归属的工作区；实测只认请求体里的 `location.directory`（顶层 `directory` 会被忽略），
     *                  不传则服务端按自身进程 cwd 归属
     */
    suspend fun createSession(title: String? = null, directory: String? = null): OpenCodeResult<String> {
        val body = gson.toJson(buildMap<String, Any> {
            title?.takeIf { it.isNotBlank() }?.let { put("title", it) }
            directory?.takeIf { it.isNotBlank() }?.let { put("location", mapOf("directory" to it)) }
        })
        return execute("POST", "/session", body) { json ->
            OpenCodeResponseParser.parseSession(OpenCodeResponseParser.dataObject(json)).id
        }
    }

    /**
     * 获取会话详情
     * GET /api/session/{sessionID}
     */
    suspend fun getSession(sessionId: String): OpenCodeResult<OpenCodeSession> {
        return execute("GET", "/session/${encodePath(sessionId)}") { json ->
            OpenCodeResponseParser.parseSession(OpenCodeResponseParser.dataObject(json))
        }
    }

    /**
     * 删除会话
     * DELETE /api/session/{sessionID}
     */
    suspend fun deleteSession(sessionId: String): OpenCodeResult<Unit> {
        return execute("DELETE", "/session/${encodePath(sessionId)}") { Unit }
    }

    /**
     * 重命名会话
     * PATCH /api/session/{sessionID}
     */
    suspend fun renameSession(sessionId: String, newTitle: String): OpenCodeResult<OpenCodeSession> {
        val body = gson.toJson(mapOf("title" to newTitle))
        return execute("PATCH", "/session/${encodePath(sessionId)}", body) { json ->
            OpenCodeResponseParser.parseSession(OpenCodeResponseParser.dataObject(json))
        }
    }

    /**
     * 发送消息
     * POST /api/session/{sessionID}/prompt
     *
     * @param files 文件附件（`uri` 为 `file:///path/to/file`）
     * @param skills 技能 id 列表
     */
    suspend fun sendPrompt(
        sessionId: String,
        text: String,
        files: List<PromptFile> = emptyList(),
        skills: List<String> = emptyList(),
        model: Pair<String, String>? = null
    ): OpenCodeResult<String> {
        val body = promptBody(text, files, skills, model)
        return execute("POST", "/session/${encodePath(sessionId)}/prompt", gson.toJson(body)) { json ->
            createdUserMessageId(json)
        }
    }

    /**
     * 执行命令（内置或自定义）
     * POST /api/session/{sessionID}/command
     *
     * @param name 命令名（`GET /api/command` 返回的 `name`）
     */
    suspend fun sendCommand(
        sessionId: String,
        name: String,
        text: String,
        files: List<PromptFile> = emptyList(),
        skills: List<String> = emptyList()
    ): OpenCodeResult<String> {
        val body = promptBody(text, files, skills)
        body["name"] = name
        return execute("POST", "/session/${encodePath(sessionId)}/command", gson.toJson(body)) { json ->
            createdUserMessageId(json)
        }
    }

    /**
     * `/prompt` 与 `/command` 的响应体是服务端创建出的 user 消息（`{ "data": { "id": "msg_*" } }`）。
     *
     * 面板用该 id 作为本地回声气泡的 id，与后续对账拿到的 REST id 一致，
     * 避免「本地 id → 服务端 id」换 key 造成的重建闪烁。
     * 形状不符合预期时返回空串（发送本身仍算成功）。
     */
    private fun createdUserMessageId(json: String): String =
        runCatching { OpenCodeResponseParser.dataObject(json).get("id")?.takeIf { it.isJsonPrimitive }?.asString.orEmpty() }
            .getOrDefault("")

    /** prompt / command 共有请求体：text + files + skills */
    private fun promptBody(
        text: String,
        files: List<PromptFile>,
        skills: List<String>,
        model: Pair<String, String>? = null
    ): MutableMap<String, Any> {
        val body = mutableMapOf<String, Any>("text" to text)
        // 指定模型（providerID / modelID）：不传则由服务端用会话默认模型
        model?.let { (providerId, modelId) ->
            if (providerId.isNotBlank() && modelId.isNotBlank()) {
                body["model"] = mapOf("providerID" to providerId, "modelID" to modelId)
            }
        }
        if (files.isNotEmpty()) {
            body["files"] = files.map { file ->
                buildMap<String, Any> {
                    put("uri", file.uri)
                    file.name?.takeIf { it.isNotBlank() }?.let { put("name", it) }
                    file.description?.takeIf { it.isNotBlank() }?.let { put("description", it) }
                }
            }
        }
        if (skills.isNotEmpty()) {
            body["skills"] = skills.map { mapOf("id" to it) }
        }
        return body
    }

    /**
     * 获取会话消息（对账用）
     * GET /api/session/{sessionID}/message
     */
    suspend fun getMessages(sessionId: String): OpenCodeResult<List<OpenCodeMessage>> {
        return execute("GET", "/session/${encodePath(sessionId)}/message") { json ->
            OpenCodeResponseParser.parseDataObjects(json).mapNotNull { OpenCodeResponseParser.parseMessage(it) }
        }
    }

    /**
     * 回复权限请求
     * POST /api/session/{sessionID}/permission/{requestID}/reply
     */
    suspend fun replyPermission(
        sessionId: String,
        requestId: String,
        decision: PermissionDecision
    ): OpenCodeResult<Unit> {
        val body = gson.toJson(mapOf("decision" to decision.wire))
        return execute(
            "POST",
            "/session/${encodePath(sessionId)}/permission/${encodePath(requestId)}/reply",
            body
        ) { Unit }
    }

    /**
     * 中止执行
     * POST /api/session/{sessionID}/interrupt
     */
    suspend fun interruptSession(sessionId: String): OpenCodeResult<Unit> {
        return execute("POST", "/session/${encodePath(sessionId)}/interrupt", "{}") { Unit }
    }

    /**
     * 列出可用 Agent（模式）
     * GET /api/agent
     */
    suspend fun listAgents(): OpenCodeResult<List<OpenCodeAgent>> {
        return execute("GET", "/agent") { json ->
            OpenCodeResponseParser.parseDataObjects(json).map { OpenCodeResponseParser.parseAgent(it) }
        }
    }

    /**
     * 列出可用模型
     * GET /api/model
     */
    suspend fun listModels(): OpenCodeResult<List<OpenCodeModel>> {
        return execute("GET", "/model") { json ->
            OpenCodeResponseParser.parseDataObjects(json).map { OpenCodeResponseParser.parseModel(it) }
        }
    }

    /**
     * 切换会话 Agent（模式）
     * POST /api/session/{sessionID}/agent
     */
    suspend fun switchAgent(sessionId: String, agentId: String): OpenCodeResult<Unit> {
        val body = gson.toJson(mapOf("agent" to agentId))
        return execute("POST", "/session/${encodePath(sessionId)}/agent", body) { Unit }
    }

    /**
     * 切换会话模型
     * POST /api/session/{sessionID}/model
     */
    suspend fun switchModel(sessionId: String, providerId: String, modelId: String): OpenCodeResult<Unit> {
        val body = gson.toJson(mapOf("model" to mapOf("providerID" to providerId, "id" to modelId)))
        return execute("POST", "/session/${encodePath(sessionId)}/model", body) { Unit }
    }

    companion object {
        const val DEFAULT_USERNAME = OpenCodeAuth.DEFAULT_USERNAME
    }
}
