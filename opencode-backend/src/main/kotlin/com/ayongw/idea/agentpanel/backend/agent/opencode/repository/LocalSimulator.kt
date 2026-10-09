package com.ayongw.idea.agentpanel.backend.agent.opencode.repository

import com.ayongw.idea.agentpanel.shared.ChatMessage
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * 本地兜底模拟器（TSD-30 §5.8）：Server 不可达/发送失败时本地生成用户回声 + 思考 + 流式回复。
 *
 * 从 BackendChatRepositoryModel 抽出；只写自己持有的消息状态流，与真实链路隔离，
 * 属离线降级路径，不保证与 opencode 真实行为一致。
 */
internal class LocalSimulator(
    private val messagesState: MutableStateFlow<List<ChatMessage>>,
    private val messageFactory: ChatMessageFactory,
    private val responseGenerator: AIResponseGenerator,
) {

    /** 追加用户消息后进入模拟流式回复 */
    suspend fun simulate(messageContent: String) {
        messagesState.value += messageFactory.createUserMessage(messageContent)
        streamAIResponse(messageContent)
    }

    suspend private fun streamAIResponse(userMessage: String) {
        val thinkingMessage = messageFactory
            .createAIThinkingMessage("Hmm, let me think about this...")
        messagesState.value += thinkingMessage

        val reasoningSteps = listOf(
            "Analyzing the user's question...",
            "Considering relevant context and knowledge...",
            "Formulating a helpful response...",
            "Ready to provide answer."
        )

        for (step in reasoningSteps) {
            delay(300 + (0..200).random().toLong())
            val updatedThinking = thinkingMessage.copy(content = thinkingMessage.content + "\n$step")
            messagesState.value = messagesState.value
                .map { if (it.id == thinkingMessage.id) updatedThinking else it }
        }

        val responseContent = responseGenerator.generateAIResponse(userMessage)
        val aiMessage = messageFactory.createAIMessage(content = "")
        messagesState.value = messagesState.value
            .map { if (it.id == thinkingMessage.id) aiMessage else it }

        val chunks = chunkText(responseContent, 3..8)
        var accumulated = ""

        for (chunk in chunks) {
            delay(50 + (0..100).random().toLong())
            accumulated += chunk
            val updatedMessage = aiMessage.copy(content = accumulated)
            messagesState.value = messagesState.value
                .map { if (it.id == aiMessage.id) updatedMessage else it }
        }

        messagesState.value = messagesState.value
            .map { if (it.id == aiMessage.id) aiMessage.copy(content = responseContent) else it }
    }

    private fun chunkText(text: String, chunkSizeRange: IntRange): List<String> {
        val chunks = mutableListOf<String>()
        var index = 0
        while (index < text.length) {
            val chunkSize = (chunkSizeRange.start..chunkSizeRange.endInclusive).random()
            val endIndex = (index + chunkSize).coerceAtMost(text.length)
            chunks.add(text.substring(index, endIndex))
            index = endIndex
        }
        return chunks
    }
}
