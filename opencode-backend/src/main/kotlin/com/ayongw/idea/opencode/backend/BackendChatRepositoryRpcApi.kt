@file:Suppress("UnstableApiUsage")

package com.ayongw.idea.opencode.backend

import com.ayongw.idea.opencode.backend.repository.OpenCodeFsEntry
import com.ayongw.idea.opencode.backend.repository.OpenCodeModel
import com.ayongw.idea.opencode.backend.repository.OpenCodeRestClient
import com.ayongw.idea.opencode.backend.repository.PermissionDecision
import com.ayongw.idea.opencode.backend.server.OpenCodeServerManager
import com.ayongw.idea.opencode.backend.server.OpenCodeServerStatus
import com.ayongw.idea.opencode.shared.AgentDto
import com.ayongw.idea.opencode.shared.ChatMessageDto
import com.ayongw.idea.opencode.shared.CommandDto
import com.ayongw.idea.opencode.shared.CommitMessageRequestDto
import com.ayongw.idea.opencode.shared.CommitMessageResultDto
import com.ayongw.idea.opencode.shared.DefaultModelDto
import com.ayongw.idea.opencode.shared.ModelDto
import com.ayongw.idea.opencode.shared.ModelProviderDto
import com.ayongw.idea.opencode.shared.ChatRepositoryRpcApi
import com.ayongw.idea.opencode.shared.ContextFileDto
import com.ayongw.idea.opencode.shared.PendingPermissionDto
import com.ayongw.idea.opencode.shared.PermissionResponse
import com.ayongw.idea.opencode.shared.PromptContextDto
import com.ayongw.idea.opencode.shared.ReferenceDto
import com.ayongw.idea.opencode.shared.ServerInfoDto
import com.ayongw.idea.opencode.shared.ServerStateDto
import com.ayongw.idea.opencode.shared.SessionSelectionDto
import com.ayongw.idea.opencode.shared.SessionStateDto
import com.ayongw.idea.opencode.shared.SessionUsageDto
import com.ayongw.idea.opencode.shared.SkillDto
import com.ayongw.idea.opencode.shared.WorkspaceEntryDto
import com.intellij.platform.project.ProjectId
import com.intellij.platform.project.findProjectOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class BackendChatRepositoryRpcApi : ChatRepositoryRpcApi {
    override suspend fun getMessagesFlow(projectId: ProjectId): Flow<List<ChatMessageDto>> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        return BackendChatRepositoryModel.getInstance(backendProject).getMessagesFlow()
    }

    override suspend fun sendMessageWithContext(
        projectId: ProjectId,
        messageContent: String,
        context: PromptContextDto
    ) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).sendMessage(messageContent, context)
    }

    // ==================== 会话管理 ====================

    override suspend fun getAllSessions(projectId: ProjectId): Flow<List<SessionStateDto>> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        val model = BackendChatRepositoryModel.getInstance(backendProject)

        // 加载会话列表（按项目目录过滤，只取本工作区会话）
        model.loadSessions(backendProject.basePath)

        return model.getAllSessionsFlow().map { sessions ->
            sessions.map { session ->
                SessionStateDto(
                    sessionId = session.id,
                    title = session.title,
                    status = com.ayongw.idea.opencode.shared.SessionStatus.IDLE,
                    pendingPermission = null,
                    createdAt = toLocalDateTime(session.createdAtMillis),
                    updatedAt = toLocalDateTime(session.updatedAtMillis),
                    contextFiles = emptyList(),
                    directory = session.directory
                )
            }
        }
    }

    override suspend fun createSession(projectId: ProjectId, initialTitle: String?): String {
        val backendProject = projectId.findProjectOrNull() ?: return java.util.UUID.randomUUID().toString()
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        // 绑定项目目录：否则服务端按自身进程 cwd 归属，会话不会出现在本工作区列表里
        val sessionId = model.createNewSession(initialTitle, backendProject.basePath)
        return sessionId ?: java.util.UUID.randomUUID().toString()
    }

    override suspend fun switchSession(projectId: ProjectId, sessionId: String): Boolean {
        val backendProject = projectId.findProjectOrNull() ?: return false
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        return model.switchSession(sessionId)
    }

    override suspend fun deleteSession(projectId: ProjectId, sessionId: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        model.deleteSession(sessionId)
    }

    override suspend fun renameSession(projectId: ProjectId, sessionId: String, newTitle: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        model.renameSession(sessionId, newTitle)
    }

    override suspend fun replyPermission(
        projectId: ProjectId,
        sessionId: String,
        permissionId: String,
        response: PermissionResponse
    ) {
        val backendProject = projectId.findProjectOrNull() ?: return
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        val decision = when (response) {
            PermissionResponse.ALLOW_ALWAYS -> PermissionDecision.ALWAYS
            PermissionResponse.ALLOW_ONCE -> PermissionDecision.ONCE
            else -> PermissionDecision.REJECT
        }
        model.replyPermission(permissionId, decision)
    }

    override suspend fun abortExecution(projectId: ProjectId, sessionId: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        model.abortExecution()
    }

    override suspend fun addContextFile(projectId: ProjectId, sessionId: String, contextFile: ContextFileDto) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).addContextFile(sessionId, contextFile)
    }

    override suspend fun removeContextFile(projectId: ProjectId, sessionId: String, filePath: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).removeContextFile(sessionId, filePath)
    }

    override suspend fun clearContextFiles(projectId: ProjectId, sessionId: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).clearContextFiles(sessionId)
    }

    override suspend fun getContextFilesFlow(projectId: ProjectId, sessionId: String): Flow<List<ContextFileDto>> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        return BackendChatRepositoryModel.getInstance(backendProject).getContextFilesFlow(sessionId)
    }

    override suspend fun getServerInfo(projectId: ProjectId): ServerInfoDto {
        val backendProject = projectId.findProjectOrNull() ?: return ServerInfoDto(false, null, null, null, "Project not found")
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        val connected = model.getServerConnectedFlow().first()
        val serverUrl = model.getServerUrl()
        return ServerInfoDto(
            isRunning = connected,
            serverUrl = if (connected) serverUrl else null,
            port = if (connected) parsePort(serverUrl) else null,
            version = if (connected) "OpenCode Server" else null,
            error = if (connected) null else "OpenCode Server not running"
        )
    }

    override suspend fun updateServerConfig(
        projectId: ProjectId,
        serverUrl: String,
        username: String,
        password: String,
        cliPath: String?,
        autoStartServer: Boolean,
        reuseExternalServer: Boolean,
    ) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).updateServerConfig(
            serverUrl, username, password, cliPath, autoStartServer, reuseExternalServer,
        )
    }

    override suspend fun listAgents(projectId: ProjectId): List<AgentDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        return BackendChatRepositoryModel.getInstance(backendProject).listAgents()
            .filter { !it.hidden && it.mode == PRIMARY_AGENT_MODE }
            .sortedBy { agentRank(it.id) }
            .map { AgentDto(id = it.id, name = it.name, description = it.description, mode = it.mode) }
    }

    override suspend fun listModels(projectId: ProjectId): List<ModelDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        val providerNames = runCatching { model.listProviderNames() }.getOrDefault(emptyMap())
        return model.listModels().map { it.toModelDto(providerNames[it.providerID]) }
    }

    override suspend fun listModelProviders(projectId: ProjectId): List<ModelProviderDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        val providerNames = runCatching { model.listProviderNames() }.getOrDefault(emptyMap())
        return model.listModels()
            .groupBy { it.providerID }
            .map { (providerId, models) ->
                ModelProviderDto(
                    id = providerId,
                    name = providerNames[providerId] ?: providerId,
                    models = models.map { it.toModelDto(providerNames[providerId]) }
                )
            }
    }

    override suspend fun generateCommitMessage(
        projectId: ProjectId,
        request: CommitMessageRequestDto
    ): CommitMessageResultDto {
        val backendProject = projectId.findProjectOrNull()
            ?: return CommitMessageResultDto(
                success = false,
                reason = CommitMessageResultDto.REASON_UNAVAILABLE,
                detail = "项目不可用"
            )
        return BackendChatRepositoryModel.getInstance(backendProject).generateCommitMessage(request)
    }

    override suspend fun getDefaultModel(projectId: ProjectId): DefaultModelDto? {
        val backendProject = projectId.findProjectOrNull() ?: return null
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        val selection = runCatching { model.getDefaultModel() }.getOrNull() ?: return null
        return DefaultModelDto(providerID = selection.first, modelID = selection.second)
    }

    override suspend fun getSessionSelection(projectId: ProjectId, sessionId: String): SessionSelectionDto {
        val backendProject = projectId.findProjectOrNull() ?: return SessionSelectionDto()
        val session = BackendChatRepositoryModel.getInstance(backendProject).getSession(sessionId)
            ?: return SessionSelectionDto()
        return SessionSelectionDto(
            agentId = session.agent,
            providerId = session.providerId,
            modelId = session.modelId
        )
    }

    override suspend fun listCommands(projectId: ProjectId): List<CommandDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        return runCatching {
            BackendChatRepositoryModel.getInstance(backendProject).listCommands(backendProject.basePath)
        }.getOrDefault(emptyList()).map { CommandDto(name = it.name, description = it.description) }
    }

    override suspend fun listReferences(projectId: ProjectId): List<ReferenceDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        return runCatching {
            BackendChatRepositoryModel.getInstance(backendProject).listReferences().filter { !it.hidden }
        }.getOrDefault(emptyList())
            .map { ReferenceDto(name = it.name, path = it.path, description = it.description) }
    }

    override suspend fun listSkills(projectId: ProjectId): List<SkillDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        return runCatching {
            BackendChatRepositoryModel.getInstance(backendProject).listSkills()
        }.getOrDefault(emptyList()).map {
            SkillDto(id = it.id, name = it.name, path = it.path, description = it.description)
        }
    }

    override suspend fun findWorkspaceEntries(
        projectId: ProjectId,
        query: String,
        limit: Int
    ): List<WorkspaceEntryDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        return runCatching {
            BackendChatRepositoryModel.getInstance(backendProject)
                .findWorkspaceEntries(query, backendProject.basePath, limit)
        }.getOrDefault(emptyList()).map { it.toWorkspaceEntry() }
    }

    override suspend fun listWorkspaceDirectory(projectId: ProjectId, path: String?): List<WorkspaceEntryDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyList()
        return runCatching {
            BackendChatRepositoryModel.getInstance(backendProject)
                .listWorkspaceDirectory(path, backendProject.basePath)
        }.getOrDefault(emptyList()).map { it.toWorkspaceEntry() }
    }

    override suspend fun switchAgent(projectId: ProjectId, sessionId: String, agentId: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).switchAgent(sessionId, agentId)
    }

    override suspend fun switchModel(projectId: ProjectId, sessionId: String, providerID: String, modelID: String) {
        val backendProject = projectId.findProjectOrNull() ?: return
        BackendChatRepositoryModel.getInstance(backendProject).switchModel(sessionId, providerID, modelID)
    }

    override suspend fun getSessionUsage(projectId: ProjectId, sessionId: String): SessionUsageDto {
        val backendProject = projectId.findProjectOrNull() ?: return SessionUsageDto()
        return try {
            BackendChatRepositoryModel.getInstance(backendProject).getSessionUsage(sessionId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SessionUsageDto()
        }
    }

    override suspend fun getSessionRunningFlow(projectId: ProjectId, sessionId: String): Flow<Boolean> {
        val backendProject = projectId.findProjectOrNull() ?: return flowOf(false)
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        if (model.getCurrentSessionId() != sessionId) return flowOf(false)
        return model.getSessionRunningFlow()
    }

    override suspend fun getPendingPermissionFlow(
        projectId: ProjectId,
        sessionId: String
    ): Flow<PendingPermissionDto?> {
        val backendProject = projectId.findProjectOrNull() ?: return flowOf(null)
        val model = BackendChatRepositoryModel.getInstance(backendProject)
        if (model.getCurrentSessionId() != sessionId) return flowOf(null)
        return model.getPendingPermissionFlow()
    }

    private fun parsePort(serverUrl: String): Int? =
        runCatching { java.net.URI(serverUrl).port }.getOrNull()?.takeIf { it > 0 }

    // ==================== Server 运行时（进程与连接管理，TSD-31） ====================

    override suspend fun getServerStateFlow(projectId: ProjectId): Flow<ServerStateDto> {
        val backendProject = projectId.findProjectOrNull() ?: return emptyFlow()
        return OpenCodeServerManager.getInstance(backendProject).status.map { it.toServerStateDto() }
    }

    override suspend fun retryServerStart(projectId: ProjectId) {
        val backendProject = projectId.findProjectOrNull() ?: return
        // 探测/拉起是阻塞编排，放到 IO 线程，避免占住 RPC 线程
        withContext(Dispatchers.IO) { OpenCodeServerManager.getInstance(backendProject).retry() }
    }

    override suspend fun startOwnServer(projectId: ProjectId) {
        val backendProject = projectId.findProjectOrNull() ?: return
        withContext(Dispatchers.IO) { OpenCodeServerManager.getInstance(backendProject).startOwnInstance() }
    }

    override suspend fun stopServer(projectId: ProjectId): Boolean {
        val backendProject = projectId.findProjectOrNull() ?: return false
        return withContext(Dispatchers.IO) { OpenCodeServerManager.getInstance(backendProject).stopOwnedServer() }
    }

    override suspend fun submitServerCredentials(projectId: ProjectId, username: String, password: String): Boolean {
        val backendProject = projectId.findProjectOrNull() ?: return false
        return withContext(Dispatchers.IO) {
            OpenCodeServerManager.getInstance(backendProject).submitCredentials(username, password)
        }
    }

    override suspend fun resetServerRegistry(projectId: ProjectId) {
        val backendProject = projectId.findProjectOrNull() ?: return
        OpenCodeServerManager.getInstance(backendProject).resetRegistry()
    }

    override suspend fun resolveCliPath(projectId: ProjectId, cliPath: String?): String? {
        val backendProject = projectId.findProjectOrNull() ?: return null
        // 首次解析要探一次登录 shell 的 PATH（秒级），放到 IO 线程，避免占住 RPC 线程
        return withContext(Dispatchers.IO) { OpenCodeServerManager.getInstance(backendProject).resolveCliPath(cliPath) }
    }

    private fun OpenCodeServerStatus.toServerStateDto() = ServerStateDto(
        state = state.name,
        failure = failure?.name,
        detail = detail,
        baseUrl = endpoint?.displayUrl,
        port = port,
        owned = owned,
        refCount = refCount,
        outputTail = outputTail,
    )

    private fun OpenCodeModel.toModelDto(providerName: String?) = ModelDto(
        id = id,
        modelID = modelID,
        providerID = providerID,
        name = name,
        contextWindow = limitContext,
        providerName = providerName,
        free = free
    )

    private fun OpenCodeFsEntry.toWorkspaceEntry() = WorkspaceEntryDto(
        path = path,
        name = path.trimEnd('/').substringAfterLast('/').ifBlank { path },
        isDirectory = isDirectory
    )

    /** 模式排序：build → plan → 其余（保持服务端顺序） */
    private fun agentRank(agentId: String): Int = when (agentId) {
        BUILD_AGENT_ID -> 0
        PLAN_AGENT_ID -> 1
        else -> 2
    }

    private companion object {
        /** 仅暴露可作为「模式」切换的 primary agent（subagent 由 @ 调用，不适合作为模式） */
        const val PRIMARY_AGENT_MODE = "primary"

        /** 默认模式与其备选（排序与默认选中都按此优先级） */
        const val BUILD_AGENT_ID = "build"
        const val PLAN_AGENT_ID = "plan"
    }

    private fun toLocalDateTime(epochMillis: Long): java.time.LocalDateTime =
        java.time.Instant.ofEpochMilli(epochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
}