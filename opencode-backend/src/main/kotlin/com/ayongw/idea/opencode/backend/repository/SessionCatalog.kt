package com.ayongw.idea.opencode.backend.repository

import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.PendingPermissionDto
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 会话目录（TSD-30 §5.8）：会话列表加载/CRUD + 会话消息加载 + 默认模型读取。
 *
 * 从 BackendChatRepositoryModel 抽出；REST 客户端与当前会话 id 由宿主提供（可变、配置变更会重建），
 * 本类只拥有工作区目录与各会话状态流写入；流式状态复位经 [eventStreamReset] 回调事件流协调器。
 */
internal class SessionCatalog(
    private val messagesState: MutableStateFlow<List<ChatMessage>>,
    private val allSessionsState: MutableStateFlow<List<OpenCodeRestClient.OpenCodeSession>>,
    private val serverConnectedState: MutableStateFlow<Boolean>,
    private val runningState: MutableStateFlow<Boolean>,
    private val permissionState: MutableStateFlow<PendingPermissionDto?>,
    private val messageMapper: MessageMapper,
    private val restClientProvider: () -> OpenCodeRestClient,
    private val currentSessionIdProvider: () -> String?,
    /** 切换/新建/清空当前会话（宿主持有该字段，事件流回调也要读） */
    private val onCurrentSessionChanged: (String?) -> Unit,
    /** 清空流式状态机（事件流协调器的 reset，避免与协调器循环持有） */
    private val eventStreamReset: () -> Unit,
) {

    /**
     * 当前工作区目录（按 Project.basePath 下发）。
     *
     * 用于 `GET /api/session?directory=` 只取本工作区会话，以及新建会话时绑定工作区；
     * 首次由 RPC 层传入后固定，供内部刷新（创建/切换/删除/重命名后的重载）复用。
     */
    @Volatile
    private var workspaceDirectory: String? = null

    private val log = Logger.getInstance(SessionCatalog::class.java)

    /**
     * 创建新会话（绑定当前工作区）
     *
     * @param directory 工作区目录；不传时复用已记录的工作区
     */
    suspend fun createNewSession(title: String? = null, directory: String? = null): String? {
        directory?.takeIf { it.isNotBlank() }?.let { workspaceDirectory = it }
        val restClient = restClientProvider()
        val result = restClient.createSession(title, workspaceDirectory)
        if (result.isSuccess()) {
            val sessionId = result.getOrThrow()
            onCurrentSessionChanged(sessionId)
            log.info("新建会话 session=$sessionId, title=${title ?: "-"}, directory=${workspaceDirectory ?: "-"}")
            loadSessions()
            loadMessages(sessionId)
            return sessionId
        }
        log.warn("新建会话失败: ${result.exceptionOrNull()?.message}")
        return null
    }

    /** 切换会话 */
    suspend fun switchSession(sessionId: String) {
        val result = restClientProvider().getSession(sessionId)
        if (result.isSuccess()) {
            onCurrentSessionChanged(sessionId)
            log.info("切换会话 session=$sessionId")
            loadMessages(sessionId)
            loadSessions()
        } else {
            log.warn("切换会话失败 session=$sessionId: ${result.exceptionOrNull()?.message}")
        }
    }

    /** 删除会话 */
    suspend fun deleteSession(sessionId: String) {
        val result = restClientProvider().deleteSession(sessionId)
        if (result.isSuccess()) {
            log.info("删除会话 session=$sessionId")
            if (currentSessionIdProvider() == sessionId) {
                onCurrentSessionChanged(null)
                eventStreamReset()
                runningState.value = false
                permissionState.value = null
                messagesState.value = emptyList()
            }
            loadSessions()
        } else {
            log.warn("删除会话失败 session=$sessionId: ${result.exceptionOrNull()?.message}")
        }
    }

    /** 重命名会话 */
    suspend fun renameSession(sessionId: String, newTitle: String) {
        val result = restClientProvider().renameSession(sessionId, newTitle)
        if (result.isSuccess()) {
            loadSessions()
        }
    }

    /**
     * 加载当前工作区的会话列表
     *
     * `GET /api/session?directory=` 只返回本工作区会话；服务端不认 `location[...]` 过滤形式，
     * 因此必须显式传目录，否则会拿到全机所有目录的会话。
     *
     * @param directory 工作区目录；不传时复用已记录的工作区
     */
    suspend fun loadSessions(directory: String? = null) {
        directory?.takeIf { it.isNotBlank() }?.let { workspaceDirectory = it }
        val result = restClientProvider().getAllSessions(workspaceDirectory)
        if (result.isSuccess()) {
            allSessionsState.value = result.getOrThrow()
            serverConnectedState.value = true
        } else {
            serverConnectedState.value = false
        }
    }

    /**
     * 服务端默认模型（配置里的 `model`，即设置页展示的 Default model）。
     *
     * `GET /api/model/default` 无默认时返回 null。
     */
    suspend fun getDefaultModel(): Pair<String, String>? {
        val obj = restClientProvider().getDefaultModel().getOrNull() ?: return null
        val providerId = obj.primitiveString("providerID") ?: return null
        val modelId = obj.primitiveString("modelID") ?: obj.primitiveString("id") ?: return null
        return providerId to modelId
    }

    /** 取 JSON 原始值中的字符串（缺失或非原始类型时返回 null） */
    private fun JsonObject.primitiveString(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }

    /** 加载指定会话的消息 */
    suspend fun loadMessages(sessionId: String) {
        // 切换/新建会话：清空上一会话的流式缓冲、运行态与待决权限，避免串值
        eventStreamReset()
        runningState.value = false
        permissionState.value = null
        val result = restClientProvider().getMessages(sessionId)
        if (result.isSuccess()) {
            val messages = result.getOrThrow()
            val bubbles = messageMapper.toBubbles(messages)
            messagesState.value = bubbles
            onCurrentSessionChanged(sessionId)
            log.info("加载会话消息 session=$sessionId: REST 消息 ${messages.size} 条 → 气泡 ${bubbles.size} 条")
        } else {
            log.warn("加载会话消息失败 session=$sessionId: ${result.exceptionOrNull()?.message}")
            messagesState.value = emptyList()
        }
    }
}
