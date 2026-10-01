package com.ayongw.idea.opencode.backend.event

import com.ayongw.idea.opencode.backend.repository.MessageMapper
import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.PendingPermissionDto
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * 事件流协调器（TSD-30 §5.8）：事件客户端生命周期 + 事件 → 会话状态 + 对账。
 *
 * 从 BackendChatRepositoryModel 抽出：状态流（消息/执行态/权限）与 REST 客户端由宿主提供，
 * 本类只拥有事件客户端与流式状态机；配置变更经 [restart] 重建，销毁经 [stop]。
 */
internal class EventStreamCoordinator(
    private val scope: CoroutineScope,
    private val messagesState: MutableStateFlow<List<ChatMessage>>,
    private val runningState: MutableStateFlow<Boolean>,
    private val permissionState: MutableStateFlow<PendingPermissionDto?>,
    private val messageMapper: MessageMapper,
    private val currentSessionIdProvider: () -> String?,
    private val restClientProvider: () -> OpenCodeRestClient,
    /** 对账完成后刷新会话列表（标题/token 已变） */
    private val onSessionsStale: suspend () -> Unit,
) {

    /** 事件驱动流式状态机（文本/推理累积、失败渲染） */
    private val streamState = SessionStreamState()

    /** 事件流客户端（配置变更时重建，随 Project 销毁关闭） */
    @Volatile
    private var eventClient: OpenCodeEventClient? = null

    private val log = Logger.getInstance(EventStreamCoordinator::class.java)

    /**
     * 建立事件流连接（幂等：同配置已连接直接返回，避免设置页反复下发时重建连接）。
     */
    fun start(serverUrl: String, username: String, password: String) {
        if (eventClient != null) return
        val client = OpenCodeEventClient(
            baseUrl = serverUrl,
            username = username,
            password = password,
            onEvent = ::handleOpenCodeEvent,
            onStateChanged = ::handleEventClientState
        )
        eventClient = client
        client.start()
        log.info("启动事件流：$serverUrl")
    }

    /** 关闭并重建事件流（Server 配置变更时调用） */
    fun restart(serverUrl: String, username: String, password: String) {
        log.info("重建事件流：$serverUrl")
        eventClient?.stop()
        eventClient = null
        runningState.value = false
        permissionState.value = null
        streamState.reset()
        start(serverUrl, username, password)
    }

    /** Project 销毁/当前会话切换：关闭客户端并复位流式状态 */
    fun stop() {
        eventClient?.stop()
        eventClient = null
    }

    /** 当前会话切换/删除：清空流式缓冲（不动客户端） */
    fun reset() = streamState.reset()

    /**
     * 事件 → 会话状态。
     *
     * 只处理当前会话：多会话并发时，非当前会话的事件不改变面板状态。
     */
    private fun handleOpenCodeEvent(event: OpenCodeEvent) {
        val sessionId = event.sessionIdOrNull() ?: return
        if (sessionId.isBlank() || sessionId != currentSessionIdProvider()) return

        val shouldPublish = streamState.onEvent(event)
        runningState.value = streamState.isRunning
        if (shouldPublish) publishStreamMessages()
        updatePendingPermission(event, sessionId)
        if (event.isExecutionTerminal()) {
            permissionState.value = null
            log.info("执行终态事件：${event::class.simpleName} session=$sessionId，开始对账")
            reconcile(sessionId)
        }
    }

    /** 权限请求：`permission.asked` 挂起待决项；执行终态/中断则清空 */
    private fun updatePendingPermission(event: OpenCodeEvent, sessionId: String) {
        if (event !is OpenCodeEvent.PermissionAsked) return
        permissionState.value = PendingPermissionDto(
            sessionId = sessionId,
            requestId = event.requestId,
            action = event.action,
            resources = event.resources
        )
    }

    /**
     * 连接建立（含重连成功）：以服务端为准对账一次，补齐断线期间漏掉的事件。
     */
    private fun handleEventClientState(state: OpenCodeEventClient.State) {
        log.info("事件流状态：$state")
        if (state != OpenCodeEventClient.State.CONNECTED) return
        currentSessionIdProvider()?.let { reconcile(it) }
    }

    /**
     * 对账兜底：拉服务端权威消息覆盖本地，并清空流式缓冲（执行已终结，内容已由 `ended` 校准）。
     *
     * 触发点：执行终态事件、重连成功。事件可能丢，REST 不会。
     */
    private fun reconcile(sessionId: String) {
        scope.launch {
            val restClient = restClientProvider()
            val result = restClient.getMessages(sessionId)
            val messages = result.getOrNull()
            if (messages == null) {
                log.warn("对账失败 session=$sessionId: ${result.exceptionOrNull()?.message}")
                return@launch
            }
            if (sessionId != currentSessionIdProvider()) return@launch
            streamState.reset()
            val restBubbles = messageMapper.toBubbles(messages)
            val merged = mergeReconcile(messagesState.value, restBubbles)
            messagesState.value = merged
            log.info(
                "对账完成 session=$sessionId: REST 消息 ${messages.size} 条 → " +
                    "气泡 ${merged.size} 条（保留本地 ${merged.size - restBubbles.size} 条，非全量重建）"
            )
            onSessionsStale()
        }
    }

    /** 执行终态：`succeeded` / `failed` / `interrupted` */
    private fun OpenCodeEvent.isExecutionTerminal(): Boolean =
        this is OpenCodeEvent.ExecutionSucceeded ||
            this is OpenCodeEvent.ExecutionFailed ||
            this is OpenCodeEvent.ExecutionInterrupted

    /**
     * 把流式消息按 id 合并进消息列表（新气泡追加、已有气泡就地更新），
     * 避免整段回答完成前面板空等。
     */
    private fun publishStreamMessages() {
        val streaming = streamState.messages()
        if (streaming.isEmpty()) return
        val byId = streaming.associateBy { it.id }
        val current = messagesState.value
        val merged = current.map { byId[it.id] ?: it }
        val knownIds = merged.mapTo(HashSet()) { it.id }
        val added = streaming.filter { it.id !in knownIds }
        messagesState.value = merged + added
        if (added.isNotEmpty()) {
            log.info("流式消息入列：新增 ${added.size} 条（${added.joinToString { it.type.name }})，当前气泡 ${messagesState.value.size} 条")
        }
    }

    private fun OpenCodeEvent.sessionIdOrNull(): String? = when (this) {
        is OpenCodeEvent.SessionCreated -> sessionId
        is OpenCodeEvent.SessionInboxEnqueued -> sessionId
        is OpenCodeEvent.SessionInboxDelivered -> sessionId
        is OpenCodeEvent.ExecutionStarted -> sessionId
        is OpenCodeEvent.ExecutionSucceeded -> sessionId
        is OpenCodeEvent.ExecutionFailed -> sessionId
        is OpenCodeEvent.ExecutionInterrupted -> sessionId
        is OpenCodeEvent.StepStarted -> sessionId
        is OpenCodeEvent.StepStreamed -> sessionId
        is OpenCodeEvent.StepEnded -> sessionId
        is OpenCodeEvent.StepFailed -> sessionId
        is OpenCodeEvent.TextStarted -> sessionId
        is OpenCodeEvent.TextDelta -> sessionId
        is OpenCodeEvent.TextEnded -> sessionId
        is OpenCodeEvent.ReasoningStarted -> sessionId
        is OpenCodeEvent.ReasoningDelta -> sessionId
        is OpenCodeEvent.ReasoningEnded -> sessionId
        is OpenCodeEvent.ToolInputStarted -> sessionId
        is OpenCodeEvent.ToolInputEnded -> sessionId
        is OpenCodeEvent.ToolCalled -> sessionId
        is OpenCodeEvent.ToolProgress -> sessionId
        is OpenCodeEvent.ToolSucceeded -> sessionId
        is OpenCodeEvent.PermissionAsked -> sessionId
        is OpenCodeEvent.ModelSelected -> sessionId
        is OpenCodeEvent.UsageUpdated -> sessionId
        is OpenCodeEvent.ShellCreated -> sessionId
        is OpenCodeEvent.ShellExited -> null
        OpenCodeEvent.ServerConnected -> null
        is OpenCodeEvent.Unexpected -> null
    }
}

/**
 * 对账增量合并：REST 权威**覆盖**同 id 内容、本地独有消息**保留**、REST 有而本地缺的**按时间补充**。
 *
 * - 本地事件流累积的顺序为骨架（thinking/tool 中间态不因 REST 缺失而消失、不整屏重排）
 * - 同 id 消息以 REST 为准（正文/tool 终态/思考最终内容）
 * - 断线遗漏的整段消息按 createdMillis 升序插入到正确位置
 *
 * 纯逻辑、无副作用，便于单测。
 */
fun mergeReconcile(existing: List<ChatMessage>, rest: List<ChatMessage>): List<ChatMessage> {
    if (existing.isEmpty()) return rest
    if (rest.isEmpty()) return existing

    // 1) REST 权威内容覆盖同 id 本地消息（id 唯一：正文原 id、思考带 #reasoning、工具带 call_）
    val restById = rest.associateBy { it.id }
    val updated = existing.map { restById[it.id] ?: it }

    // 2) 补充 REST 有、本地没有的（断线遗漏），按时间升序整段插入（内部顺序保持 REST 的 parts 顺序）
    val existingIds = updated.mapTo(HashSet()) { it.id }
    val missing = rest.filter { it.id !in existingIds }
    if (missing.isEmpty()) return updated

    val anchor = updated.indexOfLast { !it.timestamp.isAfter(missing.first().timestamp) }
    return updated.toMutableList().apply { addAll(anchor + 1, missing) }
}
