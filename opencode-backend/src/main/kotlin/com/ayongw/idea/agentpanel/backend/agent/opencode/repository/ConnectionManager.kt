package com.ayongw.idea.agentpanel.backend.agent.opencode.repository

import com.ayongw.idea.agentpanel.backend.BackendChatRepositoryModel
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerConnectionConfig
import com.ayongw.idea.agentpanel.backend.agent.opencode.server.OpenCodeServerEndpoint

/**
 * 连接配置管理（TSD-30 §5.8）：Server 地址/认证的归一化持有、REST 客户端重建、用户配置留档。
 *
 * 从 BackendChatRepositoryModel 抽出；只负责"配置 → 客户端"，不触碰事件流（由宿主在变更后
 * start/restart）。三元组（url/username/password）未变化时不重建客户端。
 */
internal class ConnectionManager {

    /** Server 连接地址（由设置页下发覆盖） */
    @Volatile
    var serverUrl: String = BackendChatRepositoryModel.DEFAULT_SERVER_URL
        private set

    /** Basic 认证用户名（opencode serve 默认 opencode） */
    @Volatile
    var username: String = OpenCodeRestClient.DEFAULT_USERNAME
        private set

    /** Basic 认证密码：显式值 → OPENCODE_SERVER_PASSWORD → ~/.config/opencode/service.json */
    @Volatile
    var password: String = OpenCodeCredentials.resolvePassword(null)
        private set

    /** OpenCode REST 客户端（配置变更时重建） */
    @Volatile
    var restClient: OpenCodeRestClient = OpenCodeRestClient(serverUrl, username, password)
        private set

    /**
     * 最近一次由设置页下发的连接配置（含 Server 管理项）
     *
     * Server 运行时据此决定探测目标与自动启动行为；插件自行选定端点后下发，**不覆盖**本字段。
     */
    @Volatile
    var pushedConfig: OpenCodeServerConnectionConfig = OpenCodeServerConnectionConfig(
        serverUrl = serverUrl,
        username = username,
        password = password,
    )
        private set

    /**
     * 设置页下发：归一化并更新连接，返回三元组是否发生变化（宿主据此 restart vs start）。
     *
     * `cliPath` / `autoStartServer` / `reuseExternalServer` 只影响 Server 运行时，不改变 REST/SSE 契约。
     */
    fun update(
        serverUrl: String,
        username: String,
        password: String,
        cliPath: String? = null,
        autoStartServer: Boolean = true,
        reuseExternalServer: Boolean = true,
    ): Boolean {
        val normalizedUrl = serverUrl.trim().trimEnd('/').ifEmpty { BackendChatRepositoryModel.DEFAULT_SERVER_URL }
        val normalizedUsername = username.trim().ifEmpty { OpenCodeRestClient.DEFAULT_USERNAME }
        // 密码留空时回退 OPENCODE_SERVER_PASSWORD / service.json，避免设置页空值把兜底覆盖掉
        val normalizedPassword = OpenCodeCredentials.resolvePassword(password)
        pushedConfig = pushedConfig.copy(
            serverUrl = normalizedUrl,
            username = normalizedUsername,
            password = normalizedPassword,
            cliPath = cliPath?.trim()?.takeIf { it.isNotBlank() },
            autoStartServer = autoStartServer,
            reuseExternalServer = reuseExternalServer,
        )
        return commit(normalizedUrl, normalizedUsername, normalizedPassword)
    }

    /**
     * Server 运行时选定端点后下发（TSD-31 §3.1），返回三元组是否变化。
     *
     * 与设置页下发共用同一套连接重建逻辑，但**不覆盖**用户配置。
     */
    fun applyEndpoint(endpoint: OpenCodeServerEndpoint): Boolean =
        commit(
            endpoint.baseUrl.trimEnd('/'),
            endpoint.username,
            endpoint.password.orEmpty()
        )

    /** 三元组变化才重建客户端；返回是否变化 */
    private fun commit(serverUrl: String, username: String, password: String): Boolean {
        if (serverUrl == this.serverUrl && username == this.username && password == this.password) {
            return false
        }
        this.serverUrl = serverUrl
        this.username = username
        this.password = password
        this.restClient = OpenCodeRestClient(serverUrl, username, password)
        return true
    }
}
