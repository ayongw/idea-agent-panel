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
    private val allSessionsState: MutableStateFlow<List<OpenCodeSession>>,
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

    /**
     * 切换会话：返回是否成功（消息已加载）。
     *
     * 不再用 `getSession` 做前置门禁：它把「一次切换」变成 3 次串行往返
     * （getSession → loadMessages → loadSessions），且失败时整次切换被静默丢弃，
     * 前端却已乐观改了 currentSessionId → 观感是「切了但没反应」。
     * 现在只以 `loadMessages` 结果为准（失败不清空旧消息，见该方法注释），
     * 会话列表由前端 `getAllSessions` / `refreshSessions` 刷新，不在此重复拉取。
     */
    suspend fun switchSession(sessionId: String): Boolean {
        onCurrentSessionChanged(sessionId)
        log.info("切换会话 session=$sessionId")
        return loadMessages(sessionId)
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

    /** 加载指定会话的消息；返回是否加载成功（失败时保留旧消息，不清空） */
    suspend fun loadMessages(sessionId: String): Boolean {
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
            return true
        } else {
            log.warn("加载会话消息失败 session=$sessionId: ${result.exceptionOrNull()?.message}")
            // 失败不清空：currentSessionId 未切换（成功分支才变更），保留旧列表保证 UI 数据
            // 与当前会话状态一致；清空会造成「UI 空白但会话未变」的永久性消息消失观感
            return false
        }
    }
}
