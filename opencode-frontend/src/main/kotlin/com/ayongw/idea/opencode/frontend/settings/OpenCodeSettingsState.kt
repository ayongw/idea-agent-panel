package com.ayongw.idea.opencode.frontend.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * OpenCode 连接配置（应用级）：Server 地址与认证 Token
 */
@State(
    name = "OpenCodeSettings",
    storages = [Storage("opencode-settings.xml")]
)
class OpenCodeSettingsState : PersistentStateComponent<OpenCodeSettingsState> {

    /** Server 地址，如 http://localhost:8080 */
    var serverUrl: String = DEFAULT_SERVER_URL

    /** 认证 Token（请求头 Authorization: Bearer <token>），为空表示不鉴权 */
    var token: String = ""

    override fun getState(): OpenCodeSettingsState = this

    override fun loadState(state: OpenCodeSettingsState) {
        serverUrl = state.serverUrl
        token = state.token
    }

    companion object {
        const val DEFAULT_SERVER_URL = "http://localhost:8080"

        fun getInstance(): OpenCodeSettingsState {
            return ApplicationManager.getApplication().getService(OpenCodeSettingsState::class.java)
        }
    }
}