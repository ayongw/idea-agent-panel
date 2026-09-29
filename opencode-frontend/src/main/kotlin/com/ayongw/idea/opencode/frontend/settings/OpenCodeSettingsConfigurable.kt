package com.ayongw.idea.opencode.frontend.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.ayongw.idea.opencode.frontend.CoroutineScopeHolder
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.ChatRepositoryRpcApi
import kotlinx.coroutines.launch
import java.net.HttpURLConnection
import java.net.URI
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * OpenCode 设置页（应用级）：Server 连接地址与认证 Token
 */
class OpenCodeSettingsConfigurable : Configurable {

    private var panel: JComponent? = null
    private val serverUrlField = JBTextField()
    private val tokenField = JBPasswordField()
    private val testConnectionLabel = JBLabel("")

    override fun getDisplayName(): String = OpencodeFrontendBundle.message("settings.opencode.title")

    override fun createComponent(): JComponent {
        serverUrlField.columns = 40
        tokenField.columns = 40
        testConnectionLabel.font = JBUI.Fonts.smallFont()

        val testConnectionButton = JButton(OpencodeFrontendBundle.message("settings.opencode.test.connection")).apply {
            addActionListener { testConnection() }
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.server.url"), serverUrlField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.token"), tokenField)
            .addComponent(testConnectionLabel)
            .addComponent(testConnectionButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val state = OpenCodeSettingsState.getInstance()
        return normalizedUrl() != state.serverUrl || inputToken() != state.token
    }

    override fun apply() {
        val state = OpenCodeSettingsState.getInstance()
        val serverUrl = normalizedUrl()
        val token = inputToken()
        state.serverUrl = serverUrl
        state.token = token

        // 下发到后端，使新配置即时生效
        ProjectManager.getInstance().openProjects.forEach { project ->
            CoroutineScopeHolder.getInstance(project).createScope("OpenCodeSettingsPush").launch {
                runCatching {
                    ChatRepositoryRpcApi.getInstance().updateServerConfig(project.projectId(), serverUrl, token)
                }
            }
        }
    }

    override fun reset() {
        val state = OpenCodeSettingsState.getInstance()
        serverUrlField.text = state.serverUrl
        tokenField.text = state.token
        testConnectionLabel.text = ""
    }

    private fun normalizedUrl(): String {
        return serverUrlField.text.trim().trimEnd('/')
            .ifEmpty { OpenCodeSettingsState.DEFAULT_SERVER_URL }
    }

    private fun inputToken(): String = String(tokenField.password).trim()

    private fun testConnection() {
        val serverUrl = normalizedUrl()
        val token = inputToken()
        testConnectionLabel.text = OpencodeFrontendBundle.message("settings.opencode.testing")

        ApplicationManager.getApplication().executeOnPooledThread {
            val reachable = probeHealth(serverUrl, token)
            ApplicationManager.getApplication().invokeLater {
                testConnectionLabel.text = OpencodeFrontendBundle.message(
                    if (reachable) "settings.opencode.test.success" else "settings.opencode.test.failed"
                )
            }
        }
    }

    /**
     * 探测 GET /global/health，与真实请求一致地携带 Token
     */
    private fun probeHealth(serverUrl: String, token: String): Boolean {
        return try {
            val connection = URI("$serverUrl/global/health").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"
            if (token.isNotEmpty()) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }
}