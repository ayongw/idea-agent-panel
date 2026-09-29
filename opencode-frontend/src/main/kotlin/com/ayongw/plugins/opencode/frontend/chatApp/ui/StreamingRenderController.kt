package com.ayongw.plugins.opencode.frontend.chatApp.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Timer

/**
 * 流式渲染控制器
 * 管理流式消息的缓冲、批量刷新(75ms)、UI 更新调度
 */
class StreamingRenderController(
    private val project: Project,
    private val uiScope: CoroutineScope,
    private val onMessageUpdate: (String, String) -> Unit, // (messageId, content) -> 更新 UI
    private val onMessageComplete: (String) -> Unit,       // messageId -> 完成流式
    private val onReasoningUpdate: (String, String) -> Unit, // (messageId, content) -> 推理更新
    private val onReasoningComplete: (String) -> Unit,       // messageId -> 推理完成
) : Disposable {

    /** 流式消息缓冲区: messageId -> StringBuilder */
    private val textBuffers = ConcurrentHashMap<String, StringBuilder>()

    /** 推理过程缓冲区: messageId -> StringBuilder */
    private val reasoningBuffers = ConcurrentHashMap<String, StringBuilder>()

    /** 待刷新的消息 ID 集合 */
    private val pendingFlushMessages = ConcurrentHashMap.newKeySet<String>()

    /** 待刷新的推理消息 ID 集合 */
    private val pendingFlushReasoning = ConcurrentHashMap.newKeySet<String>()

    /** 75ms 批量刷新定时器 */
    private val flushTimer: Timer

    /** 当前活跃的流式消息 ID */
    private var activeStreamingMessageId: String? = null

    /** 当前活跃的推理消息 ID */
    private var activeReasoningMessageId: String? = null

    init {
        flushTimer = Timer(75) { flushPendingUpdates() }
        flushTimer.start()
    }

    /** 开始流式文本消息 */
    fun onTextStart(messageId: String, initialContent: String = "") {
        activeStreamingMessageId = messageId
        val buffer = textBuffers.getOrPut(messageId) { StringBuilder() }
        buffer.setLength(0)
        if (initialContent.isNotBlank()) buffer.append(initialContent)
        pendingFlushMessages.add(messageId)
    }

    /** 流式文本增量 */
    fun onTextDelta(messageId: String, delta: String) {
        val buffer = textBuffers.getOrPut(messageId) { StringBuilder() }
        buffer.append(delta)
        pendingFlushMessages.add(messageId)
    }

    /** 结束流式文本消息 */
    fun onTextEnd(messageId: String) {
        activeStreamingMessageId = null
        val buffer = textBuffers.remove(messageId)
        if (buffer != null) {
            // 最后一次刷新完整内容
            onMessageUpdate(messageId, buffer.toString())
        }
        pendingFlushMessages.remove(messageId)
        onMessageComplete(messageId)
    }

    /** 开始推理过程 */
    fun onReasoningStart(messageId: String, initialContent: String = "") {
        activeReasoningMessageId = messageId
        val buffer = reasoningBuffers.getOrPut(messageId) { StringBuilder() }
        buffer.setLength(0)
        if (initialContent.isNotBlank()) buffer.append(initialContent)
        pendingFlushReasoning.add(messageId)
    }

    /** 推理过程增量 */
    fun onReasoningDelta(messageId: String, delta: String) {
        val buffer = reasoningBuffers.getOrPut(messageId) { StringBuilder() }
        buffer.append(delta)
        pendingFlushReasoning.add(messageId)
    }

    /** 结束推理过程 */
    fun onReasoningEnd(messageId: String) {
        activeReasoningMessageId = null
        val buffer = reasoningBuffers.remove(messageId)
        if (buffer != null) {
            onReasoningUpdate(messageId, buffer.toString())
        }
        pendingFlushReasoning.remove(messageId)
        onReasoningComplete(messageId)
    }

    /** 批量刷新待更新的消息 */
    private fun flushPendingUpdates() {
        if (pendingFlushMessages.isEmpty() && pendingFlushReasoning.isEmpty()) return

        // 在 EDT 线程执行 UI 更新
        ApplicationManager.getApplication().invokeLater {
            // 刷新文本消息
            pendingFlushMessages.forEach { messageId ->
                val buffer = textBuffers[messageId]
                if (buffer != null) {
                    onMessageUpdate(messageId, buffer.toString())
                }
            }

            // 刷新推理消息
            pendingFlushReasoning.forEach { messageId ->
                val buffer = reasoningBuffers[messageId]
                if (buffer != null) {
                    onReasoningUpdate(messageId, buffer.toString())
                }
            }
        }
    }

    /** 取消当前流式消息 */
    fun cancelStreaming(messageId: String? = null) {
        val ids = messageId?.let { listOf(it) } ?: textBuffers.keys
        ids.forEach { id ->
            textBuffers.remove(id)
            reasoningBuffers.remove(id)
            pendingFlushMessages.remove(id)
            pendingFlushReasoning.remove(id)
        }
        if (messageId == activeStreamingMessageId) activeStreamingMessageId = null
        if (messageId == activeReasoningMessageId) activeReasoningMessageId = null
    }

    /** 检查是否有活跃的流式消息 */
    val hasActiveStreaming: Boolean
        get() = activeStreamingMessageId != null || activeReasoningMessageId != null

    /** 获取当前活跃的流式消息 ID */
    val currentStreamingMessageId: String?
        get() = activeStreamingMessageId

    /** 获取当前活跃的推理消息 ID */
    val currentReasoningMessageId: String?
        get() = activeReasoningMessageId

    override fun dispose() {
        flushTimer.stop()
        textBuffers.clear()
        reasoningBuffers.clear()
        pendingFlushMessages.clear()
        pendingFlushReasoning.clear()
    }

    companion object {
        /** 创建用于测试的控制器 */
        fun createForTest(
            project: Project,
            onMessageUpdate: (String, String) -> Unit = { _, _ -> },
            onMessageComplete: (String) -> Unit = { _ -> },
            onReasoningUpdate: (String, String) -> Unit = { _, _ -> },
            onReasoningComplete: (String) -> Unit = { _ -> },
        ): StreamingRenderController {
            return StreamingRenderController(
                project = project,
                uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main),
                onMessageUpdate = onMessageUpdate,
                onMessageComplete = onMessageComplete,
                onReasoningUpdate = onReasoningUpdate,
                onReasoningComplete = onReasoningComplete,
            )
        }
    }
}