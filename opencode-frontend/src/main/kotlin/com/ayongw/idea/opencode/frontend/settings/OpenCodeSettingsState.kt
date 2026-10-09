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

    /** 是否允许插件自动拉起 server（默认开，见 TSD-31 §13 决策点 1） */
    var autoStartServer: Boolean = true

    /** 是否允许复用非本插件启动的实例（含经密钥接入，见 TSD-31 §13 决策点 2） */
    var reuseExternalServer: Boolean = true

    /** opencode CLI 路径覆盖；空 = 从 PATH 解析（CLI 缺失时的兜底入口） */
    var cliPath: String = ""


    override fun getState(): OpenCodeSettingsState = this

    override fun loadState(state: OpenCodeSettingsState) {
        serverUrl = state.serverUrl
        username = state.username
        autoStartServer = state.autoStartServer
        reuseExternalServer = state.reuseExternalServer
        cliPath = state.cliPath
    }

    companion object {
        const val DEFAULT_SERVER_URL = "http://127.0.0.1:4096"
        const val DEFAULT_USERNAME = "opencode"

        fun getInstance(): OpenCodeSettingsState {
            return ApplicationManager.getApplication().getService(OpenCodeSettingsState::class.java)
        }
    }
}