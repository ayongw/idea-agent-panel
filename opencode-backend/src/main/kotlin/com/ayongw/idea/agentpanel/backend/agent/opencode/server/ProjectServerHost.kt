package com.ayongw.idea.agentpanel.backend.agent.opencode.server

import com.ayongw.idea.agentpanel.backend.BackendChatRepositoryModel
import com.ayongw.idea.agentpanel.shared.AgentPluginVersion
import com.intellij.openapi.project.Project
import java.nio.file.Path
import java.nio.file.Paths

/**
 * [OpenCodeServerHost] 的 Project 侧实现（TSD-31 §6.3）
 *
 * 只做「取配置 / 给工作目录 / 下发端点」三件事，全部经既有 `BackendChatRepositoryModel`，
 * 不新增任何连接机制（REST/SSE 契约不变）。
 */
internal class ProjectServerHost(private val project: Project) : OpenCodeServerHost {

    override fun connectionConfig(): OpenCodeServerConnectionConfig =
        BackendChatRepositoryModel.getInstance(project).currentServerConfig()

    /** 工作目录用 Project.basePath，保证 server 的默认会话归属与工作区一致 */
    override fun workingDirectory(): Path? = project.basePath?.let { Paths.get(it) }

    override fun onEndpointReady(endpoint: OpenCodeServerEndpoint) {
        BackendChatRepositoryModel.getInstance(project).applyManagedEndpoint(endpoint)
    }

    /** 写入共享注册表，供多窗口归属排查 */
    override fun pluginVersion(): String = AgentPluginVersion.get()
}