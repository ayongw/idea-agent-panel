package com.ayongw.idea.agentpanel.backend.agent.opencode.repository

import com.ayongw.idea.agentpanel.shared.ToolCallStatus
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException

/**
 * OpenCode REST 响应解析（自 [OpenCodeRestClient] 拆出）
 *
 * 负责 `{ "data": ... }` 包裹结构的展开与 DTO 映射；无实例状态，全部为纯解析。
 */
internal object OpenCodeResponseParser {

    /** 取 `{ "data": {...} }` 中的数据对象 */
    fun dataObject(json: String): JsonObject {
        val root = JsonParser.parseString(json)
        val data = if (root.isJsonObject) root.asJsonObject.get("data") else null
        return data?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("Unexpected response shape: ${json.take(200)}")
    }

    /** 取对象响应：优先 `{ "data": {...} }`，否则取根对象本身（如 /api/info 直接返回对象） */
    fun objectOrData(json: String): JsonObject {
        val root = JsonParser.parseString(json)
        if (!root.isJsonObject) throw IOException("Unexpected response shape: ${json.take(200)}")
        val obj = root.asJsonObject
        return obj.get("data")?.takeIf { it.isJsonObject }?.asJsonObject ?: obj
    }

    /** 取 `{ "data": [ ... ] }` 中的数据数组 */
    fun parseDataObjects(json: String): List<JsonObject> {
        val root = JsonParser.parseString(json)
        val data: JsonElement? = if (root.isJsonObject) root.asJsonObject.get("data") else root
        return when {
            data == null || data.isJsonNull -> emptyList()
            data.isJsonArray -> data.asJsonArray.mapNotNull { it as? JsonObject }
            data.isJsonObject -> listOf(data.asJsonObject)
            else -> emptyList()
        }
    }

    fun parseSession(session: JsonObject): OpenCodeSession {
        val time = session.getAsJsonObject("time")
        val location = session.getAsJsonObject("location")
        val model = session.getAsJsonObject("model")
        return OpenCodeSession(
            id = session.string("id").orEmpty(),
            title = session.string("title").orEmpty(),
            createdAtMillis = time?.long("created") ?: 0L,
            updatedAtMillis = time?.long("updated") ?: 0L,
            agent = session.string("agent"),
            outcome = session.string("outcome"),
            directory = location?.string("directory"),
            costUsd = session.doubleOrNull("cost"),
            tokens = parseTokenUsage(session.getAsJsonObject("tokens")),
            modelId = model?.string("id"),
            providerId = model?.string("providerID")
        )
    }

    /** v2 消息为按 `type` 区分的联合类型，仅保留 user / assistant 两类文本消息 */
    fun parseMessage(message: JsonObject): OpenCodeMessage? {
        val type = message.string("type") ?: return null
        val id = message.string("id").orEmpty()
        val createdMillis = message.getAsJsonObject("time")?.long("created") ?: 0L
        return when (type) {
            "user" -> OpenCodeMessage(id, "user", message.string("text").orEmpty(), createdMillis)
            "assistant" -> {
                val content = message.getAsJsonArray("content")
                OpenCodeMessage(
                    id = id,
                    role = "assistant",
                    content = assistantText(content),
                    createdMillis = createdMillis,
                    tokens = parseTokenUsage(message.getAsJsonObject("tokens")),
                    costUsd = message.doubleOrNull("cost"),
                    parts = parseAssistantParts(content)
                )
            }
            else -> null
        }
    }

    /**
     * 助手消息 `content[]` → 渲染部件（按原顺序）。
     *
     * 实测部件类型：`text` / `reasoning` / `tool`（`ToolState` 四态见 openapi.json）；
     * `reasoning` 在 [BackendChatRepositoryModel.toChatMessages] 映射为思考气泡（不再丢弃）。
     */
    fun parseAssistantParts(content: JsonArray?): List<OpenCodePart> {
        if (content == null) return emptyList()
        return content.asSequence()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { part ->
                when (part.string("type")) {
                    "text" -> part.string("text")?.let { OpenCodePart.Text(it) }
                    "reasoning" -> part.string("text")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { OpenCodePart.Reasoning(it) }
                    "tool" -> parseToolCall(part)?.let { OpenCodePart.Tool(it) }
                    else -> null
                }
            }
            .toList()
    }

    /** `Session.Message.Assistant.Tool` → 内部模型；`state` 缺失视为不可渲染 */
    private fun parseToolCall(part: JsonObject): OpenCodeToolCall? {
        val state = part.getAsJsonObject("state") ?: return null
        val metadata = state.getAsJsonObject("metadata")
        return OpenCodeToolCall(
            callId = part.string("id").orEmpty(),
            name = part.string("name").orEmpty(),
            // streaming 阶段 input 是字符串片段，其余状态是对象
            input = state.get("input")?.let { if (it.isJsonPrimitive) it.asString else it.toString() }.orEmpty(),
            // completed 取 content 文本；error 无 content 时退回结构化错误信息
            output = state.getAsJsonArray("content")
                ?.mapNotNull { (it as? JsonObject)?.string("text") }
                ?.joinToString("")
                .orEmpty()
                .ifBlank { state.getAsJsonObject("error")?.string("message").orEmpty() },
            status = parseToolStatus(state.string("status")),
            exit = metadata?.intOrNull("exit"),
            truncated = metadata?.bool("truncated") ?: false
        )
    }

    private fun parseToolStatus(status: String?): ToolCallStatus = when (status) {
        "streaming" -> ToolCallStatus.STREAMING
        "completed" -> ToolCallStatus.COMPLETED
        "error" -> ToolCallStatus.ERROR
        else -> ToolCallStatus.RUNNING
    }

    /** `TokenUsage.Info` → 内部模型；字段缺失时按 0 计 */
    private fun parseTokenUsage(obj: JsonObject?): OpenCodeTokenUsage? {
        if (obj == null) return null
        val cache = obj.getAsJsonObject("cache")
        return OpenCodeTokenUsage(
            input = obj.long("input"),
            output = obj.long("output"),
            reasoning = obj.long("reasoning"),
            cacheRead = cache?.long("read") ?: 0L,
            cacheWrite = cache?.long("write") ?: 0L
        )
    }

    private fun assistantText(content: JsonArray?): String {
        if (content == null) return ""
        return content.asSequence()
            .mapNotNull { it as? JsonObject }
            .filter { it.string("type") == "text" }
            .mapNotNull { it.string("text") }
            .joinToString("\n")
    }

    fun parseAgent(agent: JsonObject): OpenCodeAgent = OpenCodeAgent(
        id = agent.string("id").orEmpty(),
        name = agent.string("name").orEmpty(),
        description = agent.string("description"),
        mode = agent.string("mode"),
        hidden = agent.get("hidden")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false
    )

    fun parseModel(model: JsonObject): OpenCodeModel = OpenCodeModel(
        id = model.string("id").orEmpty(),
        modelID = model.string("modelID").orEmpty(),
        providerID = model.string("providerID").orEmpty(),
        name = model.string("name").orEmpty(),
        limitContext = model.getAsJsonObject("limit")?.longOrNull("context"),
        free = isFreeModel(model)
    )

    /** 免费判定：`cost` 非空且各档 input/output 均为 0 */
    private fun isFreeModel(model: JsonObject): Boolean {
        val cost = model.getAsJsonArray("cost") ?: return false
        if (cost.isEmpty) return false
        return cost.all { element ->
            val tier = element as? JsonObject ?: return@all false
            val input = tier.doubleOrNull("input")
            val output = tier.doubleOrNull("output")
            (input == null || input == 0.0) && (output == null || output == 0.0)
        }
    }

    /** `FileSystem.Entry` → 目录项（相对 `location.directory` 的路径） */
    fun parseFsEntry(entry: JsonObject): OpenCodeFsEntry? {
        val path = entry.string("path")?.takeIf { it.isNotBlank() } ?: return null
        return OpenCodeFsEntry(path = path, type = entry.string("type") ?: "file")
    }
}

// ==================== JsonObject 取值扩展（同包顶层 internal，供客户端与 Lookup 端点共用） ====================

internal fun JsonObject.string(name: String): String? =
    get(name)?.takeIf { it.isJsonPrimitive }?.asString

internal fun JsonObject.long(name: String): Long =
    get(name)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

internal fun JsonObject.longOrNull(name: String): Long? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asLong

internal fun JsonObject.intOrNull(name: String): Int? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt

internal fun JsonObject.bool(name: String): Boolean? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean

internal fun JsonObject.doubleOrNull(name: String): Double? =
    get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
