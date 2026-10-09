package com.ayongw.idea.agentpanel.frontend.chatApp.viewmodel

import com.ayongw.idea.agentpanel.shared.ServerStateDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Server 运行时控制器（TSD-31）：ServerApi 的薄实现，全部委托 repository 并在 scope 中执行。
 */
internal class ServerController(
    private val coroutineScope: CoroutineScope,
    private val repository: ChatRepositoryApi,
) : ServerApi {

    override val serverStateFlow: StateFlow<ServerStateDto> = repository.serverStateFlow

    override fun retryServerStart() {
        coroutineScope.launch { runCatching { repository.retryServerStart() } }
    }

    override fun startOwnServer() {
        coroutineScope.launch { runCatching { repository.startOwnServer() } }
    }

    override fun stopServer() {
        coroutineScope.launch { runCatching { repository.stopServer() } }
    }

    override fun submitServerCredentials(username: String, password: String) {
        coroutineScope.launch {
            runCatching { repository.submitServerCredentials(username, password) }
        }
    }
}
