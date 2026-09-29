@file:Suppress("UnstableApiUsage")

package com.ayongw.plugins.opencode.backend

import com.ayongw.plugins.opencode.backend.repository.AIResponseGenerator
import com.ayongw.plugins.opencode.backend.repository.ChatMessageFactory
import com.ayongw.plugins.opencode.shared.ChatMessage
import com.ayongw.plugins.opencode.shared.ChatMessageDto
import com.ayongw.plugins.opencode.shared.toChatMessageDto
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

@Service(Service.Level.PROJECT)
class BackendChatRepositoryModel {
    companion object {
        fun getInstance(project: Project): BackendChatRepositoryModel {
            return project.getService(BackendChatRepositoryModel::class.java)
        }
    }

    private val chatMessageFactory = ChatMessageFactory("AI Buddy", "Super Engineer")
    private val aiResponseGenerator = AIResponseGenerator()
    private val _messages = MutableStateFlow(
        listOf(
            chatMessageFactory.createAIMessage(
                content = "Hello! I'm your AI Buddy. I'm here to help and chat with you about anything you'd like to discuss. How are you doing today?",
                timestamp = LocalDateTime.now().minusMinutes(30),
            ),
            chatMessageFactory.createAIMessage(
                content = "Feel free to ask me questions, share your thoughts, or just have a casual conversation. I'm designed to provide helpful and engaging responses!",
                timestamp = LocalDateTime.now().minusMinutes(25),
            ),
            chatMessageFactory.createAIMessage(
                content = "I can help with a wide variety of topics - from coding and technical questions to creative writing, analysis, math problems, or just friendly chat. What interests you?",
                timestamp = LocalDateTime.now().minusMinutes(20),
            )
        )
    )

    fun getMessagesFlow(): Flow<List<ChatMessageDto>> {
        return _messages.map { messagesList -> messagesList.map(ChatMessage::toChatMessageDto) }
    }

    suspend fun sendMessage(messageContent: String) {
        withContext(Dispatchers.IO) {
            try {
                // Emits the user message to a chat list
                _messages.value += chatMessageFactory.createUserMessage(messageContent)

                // Simulate AI responding with streaming
                simulateAIStreamingResponse(messageContent)
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // In case the message sending is canceled before a response is generated,
                    // we remove a loading placeholder message
                    _messages.value = _messages.value.filter { !it.isAIThinkingMessage() }

                    throw e

                }

                e.printStackTrace()
            }
        }
    }

    /**
     * 模拟 AI 流式响应
     * 先发送 thinking 消息，然后逐字符流式输出最终回复
     */
    private suspend fun simulateAIStreamingResponse(userMessage: String) {
        // 1. 创建 thinking 消息（推理过程）
        val thinkingMessage = chatMessageFactory
            .createAIThinkingMessage("Hmm, let me think about this...")
        _messages.value += thinkingMessage

        // 模拟推理过程流式输出
        val reasoningSteps = listOf(
            "Analyzing the user's question...",
            "Considering relevant context and knowledge...",
            "Formulating a helpful response...",
            "Ready to provide answer."
        )

        for (step in reasoningSteps) {
            delay(300 + (0..200).random().toLong())
            val updatedThinking = thinkingMessage.copy(content = thinkingMessage.content + "\n$step")
            _messages.value = _messages.value
                .map { if (it.id == thinkingMessage.id) updatedThinking else it }
        }

        // 2. 创建最终回复消息（初始为空，准备流式填充）
        val responseContent = aiResponseGenerator.generateAIResponse(userMessage)
        val aiMessage = chatMessageFactory.createAIMessage(content = "")
        _messages.value = _messages.value
            .map { if (it.id == thinkingMessage.id) aiMessage else it }

        // 3. 流式输出最终回复 - 按字符/词分块
        val chunks = chunkText(responseContent, 3..8)
        var accumulated = ""

        for (chunk in chunks) {
            delay(50 + (0..100).random().toLong()) // 模拟网络延迟
            accumulated += chunk
            val updatedMessage = aiMessage.copy(content = accumulated)
            _messages.value = _messages.value
                .map { if (it.id == aiMessage.id) updatedMessage else it }
        }

        // 4. 最终确保完整内容
        _messages.value = _messages.value
            .map { if (it.id == aiMessage.id) aiMessage.copy(content = responseContent) else it }
    }

    /**
     * 将文本按随机长度分块，模拟真实流式输出
     */
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