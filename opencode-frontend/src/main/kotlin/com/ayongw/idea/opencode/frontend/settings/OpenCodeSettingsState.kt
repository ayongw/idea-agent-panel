package com.ayongw.idea.opencode.frontend.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * OpenCode 连接配置（应用级）：Server 地址与 Basic 认证用户名
 *
 * 密码不在此持久化（明文凭据会被 IDE 判为敏感信息），见 [OpenCodePasswordStore]。
 */
@State(
    name = "OpenCodeSettings",
    storages = [Storage("opencode-settings.xml")]
)
class OpenCodeSettingsState : PersistentStateComponent<OpenCodeSettingsState> {

    /** Server 地址，如 http://127.0.0.1:4096（opencode serve 默认端口 4096） */
    var serverUrl: String = DEFAULT_SERVER_URL

    /** Basic 认证用户名，opencode serve 默认 opencode */
    var username: String = DEFAULT_USERNAME

    override fun getState(): OpenCodeSettingsState = this

    override fun loadState(state: OpenCodeSettingsState) {
        serverUrl = state.serverUrl
        username = state.username
    }

    companion object {
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:4096"
        const val DEFAULT_USERNAME = "opencode"

        fun getInstance(): OpenCodeSettingsState {
            return ApplicationManager.getApplication().getService(OpenCodeSettingsState::class.java)
        }
    }
}