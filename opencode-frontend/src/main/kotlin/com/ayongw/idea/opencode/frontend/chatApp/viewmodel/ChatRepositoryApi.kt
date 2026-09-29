package com.ayongw.idea.opencode.frontend.chatApp.viewmodel

import kotlinx.coroutines.flow.StateFlow
import com.ayongw.idea.opencode.shared.AgentDto
import com.ayongw.idea.opencode.shared.ChatMessage
import com.ayongw.idea.opencode.shared.ModelDto
import com.ayongw.idea.opencode.shared.SessionStateDto
import com.ayongw.idea.opencode.shared.SessionUsageDto

/**
 * Interface defining the contract for managing chat messages and sessions within a chat system.
 * Provides access to the flow of messages and supports operations for session management.
 */
interface ChatRepositoryApi {
    /**
     * Flow that emits a list of chat messages.
     * Updates with new messages as they are received or edited.
     */
    val messagesFlow: StateFlow<List<ChatMessage>>

    /**
     * Flow that emits all sessions.
     */
    val allSessionsFlow: StateFlow<List<SessionStateDto>>

    /**
     * Flow that emits server connection status.
     */
    val serverConnectedFlow: StateFlow<Boolean>

    /**
     * Current session ID flow.
     */
    val currentSessionId: StateFlow<String?>

    /**
     * Whether the current session is executing (driven by the server event stream).
     * Drives the input box between "send" and "stop".
     */
    val sessionRunningFlow: StateFlow<Boolean>

    /**
     * Sends a message with the provided content.
     *
     * @param messageContent The content of the message to be sent.
     */
    suspend fun sendMessage(messageContent: String)

    /**
     * Creates a new session.
     *
     * @param initialTitle Optional initial title for the session.
     * @return The new session ID.
     */
    suspend fun createSession(initialTitle: String?): String

    /**
     * Switches to the specified session.
     *
     * @param sessionId The ID of the session to switch to.
     */
    suspend fun switchSession(sessionId: String)

    /**
     * Deletes the specified session.
     *
     * @param sessionId The ID of the session to delete.
     */
    suspend fun deleteSession(sessionId: String)

    /**
     * Renames the specified session.
     *
     * @param sessionId The ID of the session to rename.
     * @param newTitle The new title for the session.
     */
    suspend fun renameSession(sessionId: String, newTitle: String)

    /**
     * Replies to a permission request.
     *
     * @param permissionId The ID of the permission request.
     * @param allow Whether to allow the permission.
     */
    suspend fun replyPermission(permissionId: String, allow: Boolean)

    /**
     * Aborts the current execution.
     */
    suspend fun abortExecution()

    /**
     * Lists available agents (modes) from the server.
     */
    suspend fun listAgents(): List<AgentDto>

    /**
     * Lists available models from the server.
     */
    suspend fun listModels(): List<ModelDto>

    /**
     * Switches the agent (mode) of the current session.
     */
    suspend fun switchAgent(agentId: String)

    /**
     * Switches the model of the current session.
     */
    suspend fun switchModel(providerID: String, modelID: String)

    /**
     * Gets the token usage snapshot of the current session.
     *
     * @return null when there is no current session; an empty snapshot when the server is unreachable.
     */
    suspend fun getSessionUsage(): SessionUsageDto?
}