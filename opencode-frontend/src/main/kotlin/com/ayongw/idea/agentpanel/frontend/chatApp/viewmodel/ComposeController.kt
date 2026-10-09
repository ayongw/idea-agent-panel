package com.ayongw.idea.agentpanel.frontend.chatApp.viewmodel

import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.MentionSupport
import com.ayongw.idea.agentpanel.shared.AgentDto
import com.ayongw.idea.agentpanel.shared.ContextFileDto
import com.ayongw.idea.agentpanel.shared.ContextKind
import com.ayongw.idea.agentpanel.shared.ModelDto
import com.ayongw.idea.agentpanel.shared.ModelProviderDto
import com.ayongw.idea.agentpanel.shared.SessionUsageDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDateTime

/**
 * 输入区编排控制器（Phase 3）：Agent/模型选择、审批模式、用量、会话附件、mention 与工作区候选。
 *
 * 状态均为本组件持有；逻辑从 ChatViewModel 原样迁入（均为 repository 透传 + 本地选择态），
 * 候选文案经 bundle 读取。
 */
internal class ComposeController(
    private val coroutineScope: CoroutineScope,
    private val repository: ChatRepositoryApi,
    /** 工作区根目录，用于 `#` mention 的相对路径换算 */
    private val basePath: String? = null,
) : ComposeApi {

    private val _agentsFlow = MutableStateFlow(emptyList<AgentDto>())
    override val agentsFlow: StateFlow<List<AgentDto>> = _agentsFlow.asStateFlow()

    private val _modelsFlow = MutableStateFlow(emptyList<ModelDto>())
    override val modelsFlow: StateFlow<List<ModelDto>> = _modelsFlow.asStateFlow()

    private val _selectedAgentId = MutableStateFlow<String?>(null)
    override val selectedAgentId: StateFlow<String?> = _selectedAgentId.asStateFlow()

    private val _selectedModel = MutableStateFlow<ModelDto?>(null)
    override val selectedModel: StateFlow<ModelDto?> = _selectedModel.asStateFlow()

    private val _approvalMode = MutableStateFlow(ApprovalMode.AUTO)
    override val approvalMode: StateFlow<ApprovalMode> = _approvalMode.asStateFlow()

    private val _usageFlow = MutableStateFlow<SessionUsageDto?>(null)
    override val usageFlow: StateFlow<SessionUsageDto?> = _usageFlow.asStateFlow()

    override val contextFilesFlow = repository.contextFilesFlow

    private val _mentionCandidatesFlow = MutableStateFlow(emptyList<MentionSupport.Candidate>())
    override val mentionCandidatesFlow: StateFlow<List<MentionSupport.Candidate>> =
        _mentionCandidatesFlow.asStateFlow()

    private val _workspaceCandidatesFlow = MutableStateFlow(emptyList<MentionSupport.Candidate>())
    override val workspaceCandidatesFlow: StateFlow<List<MentionSupport.Candidate>> =
        _workspaceCandidatesFlow.asStateFlow()

    private val _modelProvidersFlow = MutableStateFlow(emptyList<ModelProviderDto>())
    override val modelProvidersFlow: StateFlow<List<ModelProviderDto>> =
        _modelProvidersFlow.asStateFlow()

    private var mentionCandidatesLoaded = false

    /** 模型供应商清单是否已预拉（弹窗打开时仍会强制刷新） */
    private var modelProvidersLoaded = false

    /** `#` 检索去抖任务（连续输入只保留最后一次） */
    private var workspaceSearchJob: Job? = null

    // ==================== Agent / 模型 ====================

    override fun loadAgentsAndModels() {
        coroutineScope.launch { refreshAgentsAndModels() }
    }

    /** 拉取 Agent / 模型列表并补齐默认选中（模式：build → plan → 首项；模型：服务端默认 → 首项） */
    suspend fun refreshAgentsAndModels() {
        runCatching { repository.listAgents() }.onSuccess { agents ->
            _agentsFlow.value = agents
            if (_selectedAgentId.value == null || agents.none { it.id == _selectedAgentId.value }) {
                _selectedAgentId.value = defaultAgentId(agents)
            }
        }
        runCatching { repository.listModels() }.onSuccess { models ->
            _modelsFlow.value = models
            if (_selectedModel.value == null) {
                _selectedModel.value = resolveInitialModel(models)
            }
        }
    }

    /**
     * 初始模型：服务端默认模型（设置页展示的 Default model）优先，
     * 不在本机模型清单里或取不到时退回列表首项
     */
    private suspend fun resolveInitialModel(models: List<ModelDto>): ModelDto? {
        val fallback = models.firstOrNull() ?: return null
        val preferred = runCatching { repository.getDefaultModel() }.getOrNull() ?: return fallback
        return models.firstOrNull { it.providerID == preferred.providerID && it.modelID == preferred.modelID }
            ?: fallback
    }

    /** 默认模式：build 优先，其次 plan，最后取首项 */
    private fun defaultAgentId(agents: List<AgentDto>): String? =
        agents.firstOrNull { it.id == BUILD_AGENT_ID }?.id
            ?: agents.firstOrNull { it.id == PLAN_AGENT_ID }?.id
            ?: agents.firstOrNull()?.id

    /** 切换会话后按服务端回读该会话的模式与模型（读不到则保留当前值） */
    suspend fun applySessionSelection(sessionId: String) {
        val selection = runCatching { repository.getSessionSelection(sessionId) }.getOrNull() ?: return
        selection.agentId?.takeIf { it.isNotBlank() }?.let { agentId ->
            if (_agentsFlow.value.any { it.id == agentId }) _selectedAgentId.value = agentId
        }
        val modelId = selection.modelId?.takeIf { it.isNotBlank() } ?: return
        _modelsFlow.value.firstOrNull {
            it.modelID == modelId && (selection.providerId.isNullOrBlank() || it.providerID == selection.providerId)
        }?.let { _selectedModel.value = it }
    }

    override fun switchAgent(agentId: String) {
        coroutineScope.launch {
            runCatching { repository.switchAgent(agentId) }
                .onSuccess { _selectedAgentId.value = agentId }
        }
    }

    override fun switchModel(model: ModelDto) {
        coroutineScope.launch {
            runCatching { repository.switchModel(model.providerID, model.modelID) }
                .onSuccess {
                    _selectedModel.value = model
                    refreshUsage()
                }
        }
    }

    override fun setApprovalMode(mode: ApprovalMode) {
        _approvalMode.value = mode
    }

    // ==================== 用量 ====================

    override fun refreshUsage() {
        coroutineScope.launch {
            _usageFlow.value = runCatching { repository.getSessionUsage() }.getOrNull()
        }
    }

    /** 清空用量（会话删除/切换中的旧数据先抹掉） */
    fun clearUsage() {
        _usageFlow.value = null
    }

    // ==================== 附件 ====================

    override fun addAttachments(attachments: List<ContextFileDto>) {
        if (attachments.isEmpty()) return
        coroutineScope.launch {
            attachments.forEach { runCatching { repository.addContextFile(it) } }
        }
    }

    override fun removeAttachment(path: String) {
        coroutineScope.launch { runCatching { repository.removeContextFile(path) } }
    }

    // ==================== mention / 工作区候选 ====================

    override fun ensureMentionCandidates(force: Boolean) {
        if (mentionCandidatesLoaded && !force) return
        mentionCandidatesLoaded = true
        coroutineScope.launch {
            val commands = runCatching { repository.listCommands() }.getOrDefault(emptyList())
            val skills = runCatching { repository.listSkills() }.getOrDefault(emptyList())
            val references = runCatching { repository.listReferences() }.getOrDefault(emptyList())
            _mentionCandidatesFlow.value =
                commands.map { commandCandidate(it.name, it.description) } +
                    skills.map { skillCandidate(it.id, it.name, it.path, it.description) } +
                    references.map { ruleCandidate(it.name, it.path, it.description) }
        }
    }

    override fun searchWorkspace(query: String) {
        // 连续输入时只保留最后一次检索（去抖），避免每次按键都打服务端
        workspaceSearchJob?.cancel()
        workspaceSearchJob = coroutineScope.launch {
            if (query.isNotBlank()) delay(WORKSPACE_SEARCH_DEBOUNCE_MS)
            val entries = if (query.isBlank()) {
                runCatching { repository.listWorkspaceDirectory(null) }.getOrDefault(emptyList())
            } else {
                runCatching { repository.findWorkspaceEntries(query) }.getOrDefault(emptyList())
            }
            _workspaceCandidatesFlow.value = entries.map {
                workspaceCandidate(it.path, it.name, it.isDirectory)
            }
        }
    }

    override fun browseWorkspaceDirectory(path: String?) {
        coroutineScope.launch {
            val entries = runCatching { repository.listWorkspaceDirectory(path) }.getOrDefault(emptyList())
            _workspaceCandidatesFlow.value = entries.map {
                workspaceCandidate(it.path, it.name, it.isDirectory)
            }
        }
    }

    override fun loadModelProviders() {
        coroutineScope.launch {
            _modelProvidersFlow.value =
                runCatching { repository.listModelProviders() }.getOrDefault(emptyList())
        }
    }

    /**
     * 预拉模型供应商清单（仅首次生效）：会话激活链上后台加载，
     * 避免模型弹窗首次打开时空白等待 RPC。弹窗打开时仍会经 loadModelProviders 强制刷新。
     */
    fun ensureModelProvidersLoaded() {
        if (modelProvidersLoaded) return
        modelProvidersLoaded = true
        loadModelProviders()
    }

    private fun commandCandidate(name: String, description: String?) = MentionSupport.Candidate(
        symbol = MentionSupport.COMMAND_SYMBOL,
        token = name,
        group = message("chat.mention.group.command"),
        label = "/$name",
        detail = description,
        command = true
    )

    private fun skillCandidate(id: String, name: String?, path: String?, description: String?): MentionSupport.Candidate {
        val display = name?.takeIf { it.isNotBlank() } ?: id
        return MentionSupport.Candidate(
            symbol = MentionSupport.COMMAND_SYMBOL,
            token = display,
            group = message("chat.mention.group.skill"),
            label = display,
            detail = description,
            attachment = ContextFileDto(
                path = path.orEmpty(),
                name = display,
                summary = description.orEmpty(),
                addedAt = LocalDateTime.now(),
                isExplicit = true,
                kind = ContextKind.SKILL,
                skillId = id
            )
        )
    }

    private fun ruleCandidate(name: String, path: String, description: String?) = MentionSupport.Candidate(
        symbol = MentionSupport.PATH_SYMBOL,
        token = name,
        group = message("chat.mention.group.rule"),
        label = name,
        detail = path,
        attachment = ContextFileDto(
            path = path,
            name = name,
            summary = description.orEmpty(),
            addedAt = LocalDateTime.now(),
            isExplicit = true,
            kind = ContextKind.RULE
        )
    )

    private fun workspaceCandidate(path: String, name: String, isDirectory: Boolean) = MentionSupport.Candidate(
        symbol = MentionSupport.PATH_SYMBOL,
        token = if (isDirectory) "$path/" else path,
        group = message("chat.mention.group.file"),
        label = name,
        detail = path,
        attachment = ContextFileDto(
            path = absolutePath(path),
            name = name,
            summary = if (isDirectory) MentionSupport.DIRECTORY_SUMMARY else "",
            addedAt = LocalDateTime.now(),
            isExplicit = true,
            kind = if (isDirectory) ContextKind.DIRECTORY else ContextKind.FILE
        )
    )

    /** 工作区相对路径 → 绝对路径 */
    private fun absolutePath(relative: String): String = when {
        relative.startsWith("/") -> relative
        basePath.isNullOrBlank() -> relative
        else -> "$basePath/${relative.trimStart('/')}"
    }

    private fun message(key: String): String = AgentPanelBundle.message(key)

    private companion object {
        /** 默认模式与其备选（与后端 listAgents 的排序口径一致） */
        const val BUILD_AGENT_ID = "build"
        const val PLAN_AGENT_ID = "plan"

        /** `#` 检索去抖窗口（毫秒） */
        const val WORKSPACE_SEARCH_DEBOUNCE_MS = 200L
    }
}
