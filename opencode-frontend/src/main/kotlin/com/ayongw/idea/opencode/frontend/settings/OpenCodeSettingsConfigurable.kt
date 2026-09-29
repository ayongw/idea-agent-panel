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
import java.util.Base64
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * OpenCode 设置页（应用级）：Server 地址与 Basic 认证凭据
 */
class OpenCodeSettingsConfigurable : Configurable {

    private var panel: JComponent? = null
    private val serverUrlField = JBTextField()
    private val usernameField = JBTextField()
    private val passwordField = JBPasswordField()
    private val testConnectionLabel = JBLabel("")

    override fun getDisplayName(): String = OpencodeFrontendBundle.message("settings.opencode.title")

    override fun createComponent(): JComponent {
        serverUrlField.columns = 40
        usernameField.columns = 40
        passwordField.columns = 40
        testConnectionLabel.font = JBUI.Fonts.smallFont()

        val testConnectionButton = JButton(OpencodeFrontendBundle.message("settings.opencode.test.connection")).apply {
            addActionListener { testConnection() }
        }

        panel = FormBuilder.createFormBuilder()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.server.url"), serverUrlField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.username"), usernameField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.password"), passwordField)
            .addComponent(testConnectionLabel)
            .addComponent(testConnectionButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        reset()
        return panel!!
    }

    override fun isModified(): Boolean {
        val state = OpenCodeSettingsState.getInstance()
        return normalizedUrl() != state.serverUrl ||
            inputUsername() != state.username ||
            inputPassword() != state.password
    }

    override fun apply() {
        val state = OpenCodeSettingsState.getInstance()
        val serverUrl = normalizedUrl()
        val username = inputUsername()
        val password = inputPassword()
        state.serverUrl = serverUrl
        state.username = username
        state.password = password

        // 下发到后端，使新配置即时生效
        ProjectManager.getInstance().openProjects.forEach { project ->
            CoroutineScopeHolder.getInstance(project).createScope("OpenCodeSettingsPush").launch {
                runCatching {
                    ChatRepositoryRpcApi.getInstance()
                        .updateServerConfig(project.projectId(), serverUrl, username, password)
                }
            }
        }
    }

    override fun reset() {
        val state = OpenCodeSettingsState.getInstance()
        serverUrlField.text = state.serverUrl
        usernameField.text = state.username
        passwordField.text = state.password
        testConnectionLabel.text = ""
    }

    private fun normalizedUrl(): String {
        return serverUrlField.text.trim().trimEnd('/')
            .ifEmpty { OpenCodeSettingsState.DEFAULT_SERVER_URL }
    }

    private fun inputUsername(): String =
        usernameField.text.trim().ifEmpty { OpenCodeSettingsState.DEFAULT_USERNAME }

    private fun inputPassword(): String = String(passwordField.password).trim()

    private fun testConnection() {
        val serverUrl = normalizedUrl()
        val username = inputUsername()
        val password = inputPassword()
        testConnectionLabel.text = OpencodeFrontendBundle.message("settings.opencode.testing")

        ApplicationManager.getApplication().executeOnPooledThread {
            val reachable = probeServer(serverUrl, username, password)
            ApplicationManager.getApplication().invokeLater {
                testConnectionLabel.text = OpencodeFrontendBundle.message(
                    if (reachable) "settings.opencode.test.success" else "settings.opencode.test.failed"
                )
            }
        }
    }

    /**
     * 探测 GET /api/project（v2 无 /global/health），与真实请求一致地携带 Basic 凭据
     */
    private fun probeServer(serverUrl: String, username: String, password: String): Boolean {
        return try {
            val connection = URI("$serverUrl/api/project").toURL().openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 10000
            connection.requestMethod = "GET"
            if (password.isNotEmpty()) {
                val credentials = "$username:$password".toByteArray(Charsets.UTF_8)
                connection.setRequestProperty("Authorization", "Basic ${Base64.getEncoder().encodeToString(credentials)}")
            }
            val responseCode = connection.responseCode
            connection.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            false
        }
    }
}