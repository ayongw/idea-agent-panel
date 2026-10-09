package com.ayongw.idea.agentpanel.backend.agent.opencode.repository

import com.ayongw.idea.agentpanel.shared.SessionUsageDto
import com.ayongw.idea.agentpanel.shared.TokenUsageDto

/**
 * 元数据与用量网关（TSD-30 §5.8）：Agent/模型/命令/规则/技能/文件浏览等只读查询 + 切换 + 会话用量快照。
 *
 * 从 BackendChatRepositoryModel 抽出；均为对 REST 的薄透传（失败即抛），
 * REST 客户端由宿主经 [restClientProvider] 提供（配置变更会重建）。
 */
internal class MetadataGateway(
    private val restClientProvider: () -> OpenCodeRestClient,
) {

    /** 列出可用 Agent（模式） */
    suspend fun listAgents(): List<OpenCodeAgent> =
        restClientProvider().listAgents().getOrThrow()

    /** 命令清单（内置 + 自定义），工作区维度 */
    suspend fun listCommands(directory: String?): List<OpenCodeCommand> =
        restClientProvider().listCommands(directory).getOrThrow()

    /** 规则清单（AGENTS.md 等） */
    suspend fun listReferences(): List<OpenCodeReference> =
        restClientProvider().listReferences().getOrThrow()

    /** 技能清单（含 id / 展示名 / 路径 / 描述） */
    suspend fun listSkills(): List<OpenCodeSkill> =
        restClientProvider().listSkillInfos().getOrThrow()

    /** 工作区文件检索（文件与目录，路径相对工作区根目录） */
    suspend fun findWorkspaceEntries(
        query: String,
        directory: String?,
        limit: Int
    ): List<OpenCodeFsEntry> =
        restClientProvider().findEntries(query, directory, limit).getOrThrow()

    /** 工作区目录浏览（path 为 null 时列工作区根目录） */
    suspend fun listWorkspaceDirectory(
        path: String?,
        directory: String?
    ): List<OpenCodeFsEntry> =
        restClientProvider().listDirectory(path, directory).getOrThrow()

    /** 供应商展示名映射（providerID → name） */
    suspend fun listProviderNames(): Map<String, String> =
        restClientProvider().listProviderNames().getOrThrow()

    /** 会话详情（回读模式与模型用）；不可达时为 null */
    suspend fun getSession(sessionId: String): OpenCodeSession? =
        restClientProvider().getSession(sessionId).getOrNull()

    /** 列出可用模型 */
    suspend fun listModels(): List<OpenCodeModel> =
        restClientProvider().listModels().getOrThrow()

    /** 切换指定会话的 Agent（模式） */
    suspend fun switchAgent(sessionId: String, agentId: String) {
        restClientProvider().switchAgent(sessionId, agentId).getOrThrow()
    }

    /** 切换指定会话的模型 */
    suspend fun switchModel(sessionId: String, providerId: String, modelId: String) {
        restClientProvider().switchModel(sessionId, providerId, modelId).getOrThrow()
    }

    /**
     * 会话用量快照：累计 tokens/cost + 最近一次 step 的 input（占比分子）+ 当前模型上下文窗口（分母）
     */
    suspend fun getSessionUsage(sessionId: String): SessionUsageDto {
        val restClient = restClientProvider()
        val session = restClient.getSession(sessionId).getOrThrow()
        val lastStepInput = restClient.getMessages(sessionId).getOrNull()
            ?.asReversed()
            ?.firstOrNull { it.role == "assistant" && it.tokens != null }
            ?.tokens
            ?.input
        return SessionUsageDto(
            tokens = session.tokens?.toDto() ?: TokenUsageDto(),
            cost = session.costUsd,
            lastStepInputTokens = lastStepInput,
            contextWindow = resolveContextWindow(restClient, session.providerId, session.modelId)
        )
    }

    /** 按当前会话模型匹配上下文窗口；模型未匹配到或服务不可达时返回 null（UI 不展示占比） */
    private suspend fun resolveContextWindow(
        restClient: OpenCodeRestClient,
        providerId: String?,
        modelId: String?
    ): Long? {
        if (providerId.isNullOrBlank() || modelId.isNullOrBlank()) return null
        val models = runCatching { restClient.listModels().getOrThrow() }.getOrNull() ?: return null
        return models.firstOrNull { it.providerID == providerId && it.modelID == modelId }?.limitContext
    }

    private fun OpenCodeTokenUsage.toDto() = TokenUsageDto(
        input = input,
        output = output,
        reasoning = reasoning,
        cacheRead = cacheRead,
        cacheWrite = cacheWrite
    )
}
