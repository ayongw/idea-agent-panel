package com.ayongw.idea.agentpanel.frontend.chatApp.viewmodel

import com.ayongw.idea.agentpanel.shared.SessionStateDto
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * 会话控制器（Phase 3）：历史会话 CRUD、tab 顺序与项目级持久化、启动引导、输入草稿。
 *
 * 从 ChatViewModel 迁入；会话切换引发的输入区联动（Agent/模型/用量）经构造回调转出，
 * 本组件只管会话与 tab 自身。
 *
 * @param afterSessionCreated 新会话建好并打开 tab 后：转发当前 Agent/模型、刷新用量与列表
 * @param afterSessionActivated 切换完成后：重置/刷新用量、刷新 Agent/模型、回读会话选择
 * @param onDraftRestored 草稿恢复后由核心写入输入框状态
 */
internal class SessionController(
    private val coroutineScope: CoroutineScope,
    private val repository: ChatRepositoryApi,
    private val tabsState: AgentSessionTabsState?,
    private val afterSessionCreated: suspend (sessionId: String) -> Unit,
    private val afterSessionActivated: suspend (sessionId: String) -> Unit,
    private val onDraftRestored: (draft: String) -> Unit,
    /** 切换失败回调（供 UI 提示；默认为空即静默） */
    private val onSwitchFailed: () -> Unit = {},
) : SessionApi {

    private val log = Logger.getInstance(SessionController::class.java)

    private val _currentSessionId = MutableStateFlow<String?>(null)

    private val _openedSessionIds = MutableStateFlow(emptyList<String>())

    /** 切换会话进行中（供 UI 显示加载提示）；失败路径也会复位 */
    private val _switching = MutableStateFlow(false)
    internal val switchingFlow: StateFlow<Boolean> = _switching.asStateFlow()

    /**
     * 会话列表拉取进行中。
     *
     * `allSessionsFlow` 初始值为空列表，与「拉到了但确实没有会话」无法区分 ——
     * 首次打开「全部会话」弹窗时若直接渲染空列表，用户会看到「没有任何会话」，
     * 实际只是还没加载完。UI 据此显示 loading 而非空态。
     */
    private val _sessionsLoading = MutableStateFlow(false)
    override val sessionsLoading: StateFlow<Boolean> = _sessionsLoading.asStateFlow()

    /** 顶部已打开会话（tab）顺序，供核心 ChatViewModel 暴露 */
    internal val openedSessionIdsFlow: StateFlow<List<String>> = _openedSessionIds.asStateFlow()

    /** IDE 重启后待恢复的会话（由持久化状态读入） */
    private var restoredSessionId: String? = null

    /** 会话输入草稿（会话级，不跨会话共享） */
    private val drafts = mutableMapOf<String, String>()

    init {
        // 恢复上次打开的会话 tab（IDE 重启后由项目级状态读入）
        tabsState?.let { state ->
            _openedSessionIds.value = state.openedSessionIds.toList()
            restoredSessionId = state.currentSessionId
        }

        // 同步当前会话 ID（并落盘 tab 状态）
        repository.currentSessionId.onEach { sessionId ->
            _currentSessionId.value = sessionId
            persistTabs()
        }.launchIn(coroutineScope)

        // 启动即拉取本工作区历史会话，并按需恢复 / 兜底创建
        coroutineScope.launch { bootstrapSessions() }
    }

    override val allSessionsFlow = repository.allSessionsFlow

    override val serverConnectedFlow = repository.serverConnectedFlow

    override fun createSession(initialTitle: String?) {
        coroutineScope.launch { createSessionInternal(initialTitle) }
    }

    override fun switchSession(sessionId: String) {
        coroutineScope.launch { switchSessionInternal(sessionId) }
    }

    override fun deleteSession(sessionId: String) {
        coroutineScope.launch {
            // 会话删除会把 currentSessionId 置空，需先判断是否当前会话再删除
            val wasCurrent = repository.currentSessionId.value == sessionId
            repository.deleteSession(sessionId)
            // 会话已删除，对应 tab 一并关闭（否则会留下点不开的死 tab，并被持久化）
            removeTabAndRelocate(sessionId, wasCurrent)
        }
    }

    override fun renameSession(sessionId: String, newTitle: String) {
        coroutineScope.launch {
            repository.renameSession(sessionId, newTitle)
        }
    }

    override fun loadSessions() {
        coroutineScope.launch { loadSessionsWithIndicator() }
    }

    /** 拉取会话列表并维护 [sessionsLoading]（成功/失败都复位，避免 loading 永久挂住） */
    private suspend fun loadSessionsWithIndicator(): List<SessionStateDto> {
        _sessionsLoading.value = true
        try {
            return repository.loadSessions()
        } finally {
            _sessionsLoading.value = false
        }
    }

    /** 关闭会话 tab（核心 ChatViewModel 委托调用；删除会话时内部也会调用） */
    fun closeSessionTab(sessionId: String) {
        coroutineScope.launch {
            val wasCurrent = repository.currentSessionId.value == sessionId
            removeTabAndRelocate(sessionId, wasCurrent)
        }
    }

    /**
     * 移除 tab 并在必要时重定位：被移除的是当前会话时，优先切到相邻 tab（右侧 → 左侧 → 任意剩余），
     * 没有可切的了就新建一个，避免面板停留在已关闭/已删除的会话上。
     */
    private suspend fun removeTabAndRelocate(sessionId: String, wasCurrent: Boolean) {
        val opened = _openedSessionIds.value
        val index = opened.indexOf(sessionId)
        if (index >= 0) {
            _openedSessionIds.value = opened - sessionId
            persistTabs()
        }
        if (!wasCurrent) return
        // 相邻优先取同一位置右侧（IDE 惯例），其次左侧；tab 本就不在列表时兜底取剩余最后一个
        val neighbor = opened.getOrNull(index + 1)
            ?: opened.getOrNull(index - 1)
            ?: _openedSessionIds.value.lastOrNull()
        if (neighbor != null) {
            switchSessionInternal(neighbor)
        } else {
            createSessionInternal(null, forceNew = true)
        }
    }

    override fun saveDraft(sessionId: String?, text: String) {
        if (sessionId != null) drafts[sessionId] = text
    }

    override fun loadDraft(sessionId: String?): String =
        sessionId?.let { drafts[it] }.orEmpty()

    /** 发送成功后清除本会话草稿 */
    internal fun clearDraft(sessionId: String) {
        drafts.remove(sessionId)
    }

    override fun restoreDraft(sessionId: String?, draft: String) {
        onDraftRestored(draft)
    }

    /**
     * 启动引导：拉取本工作区历史会话 → 恢复上次的 tab / 会话 → 一个都没有时默认创建一个
     */
    private suspend fun bootstrapSessions() {
        val sessions = runCatching { loadSessionsWithIndicator() }.getOrDefault(emptyList())
        val existingIds = sessions.map { it.sessionId }.toSet()
        // 会话已被删除的 tab 不再恢复；只在拉取成功且非空时裁剪，避免服务不可达时清空 tab
        if (existingIds.isNotEmpty()) {
            _openedSessionIds.value = _openedSessionIds.value.filter { it in existingIds }
        }

        val target = restoredSessionId?.takeIf { it in existingIds || existingIds.isEmpty() }
            ?: _openedSessionIds.value.lastOrNull()
            ?: sessions.firstOrNull()?.sessionId
        restoredSessionId = null

        when (target) {
            null -> createSessionInternal(null)
            else -> switchSessionInternal(target)
        }
    }

    /**
     * 新建会话 → 开 tab → 输入区联动
     *
     * 防重复创建：当前已有会话且还没发过任何消息（空白新会话）时直接复用，
     * 避免用户连点「新建」堆积一堆空会话。
     *
     * @param forceNew true 时跳过复用检查强制新建（关闭/删除会话后的重定位必须真建新会话）
     */
    private suspend fun createSessionInternal(initialTitle: String?, forceNew: Boolean = false) {
        val currentId = repository.currentSessionId.value
        if (!forceNew && currentId != null && repository.messagesFlow.value.isEmpty()) {
            openTab(currentId)
            return
        }
        val sessionId = repository.createSession(initialTitle)
        openTab(sessionId)
        afterSessionCreated(sessionId)
    }

    /** 切换会话 → 开 tab → 输入区联动 */
    private suspend fun switchSessionInternal(sessionId: String) {
        // 同会话重复切换（连点当前 tab）直接跳过，避免整轮 REST 刷新；
        // 必须留痕：日志中区分「跳过（已是当前会话）」与「无日志（点击事件未到达 UI 层）」
        if (repository.currentSessionId.value == sessionId) {
            log.info("切换会话跳过（已是当前会话）session=$sessionId")
            return
        }
        log.info("请求切换会话 session=$sessionId")
        _switching.value = true
        try {
            // 失败时保持原会话：不新开 tab、不联动输入区（否则会出现"高亮切了但消息没换"）
            if (!repository.switchSession(sessionId)) {
                log.warn("切换会话失败，已保持当前会话 session=$sessionId")
                onSwitchFailed?.invoke()
                return
            }
            openTab(sessionId)
            afterSessionActivated(sessionId)
        } finally {
            _switching.value = false
        }
    }

    /** 落盘 tab 状态（项目级持久化，IDE 重启后恢复） */
    private fun persistTabs() {
        tabsState?.apply {
            openedSessionIds = _openedSessionIds.value.toMutableList()
            currentSessionId = _currentSessionId.value
        }
    }

    private fun openTab(sessionId: String) {
        val opened = _openedSessionIds.value
        if (opened.contains(sessionId)) return
        _openedSessionIds.value = opened + sessionId
        persistTabs()
    }
}
