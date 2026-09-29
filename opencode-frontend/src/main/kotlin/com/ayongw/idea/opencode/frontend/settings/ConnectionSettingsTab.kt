package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.CoroutineScopeHolder
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.ChatRepositoryRpcApi
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 连接面板：Server 地址与 Basic 认证凭据（原设置页内容）+ shell 选择
 *
 * 凭据写入插件自身设置并下发后端；shell 走 `PATCH /api/experimental/config`（失败回退配置文件）。
 */
internal class ConnectionSettingsTab : AbstractSettingsTab() {

    override val title: String = OpencodeFrontendBundle.message("settings.opencode.tab.connection")

    private val serverUrlField = JBTextField()
    private val usernameField = JBTextField()
    private val passwordField = JBPasswordField()
    private val shellCombo = JComboBox<String>()
    private val defaultShellLabel = OpencodeFrontendBundle.message("settings.opencode.shell.default")

    /** 服务端当前生效的 shell（来自快照），用于判断是否改动 */
    private var currentShell: String? = null

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        val testConnectionButton = JButton(OpencodeFrontendBundle.message("settings.opencode.test.connection")).apply {
            addActionListener { testConnection() }
        }

        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.server.url"), serverUrlField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.username"), usernameField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.password"), passwordField)
            .addComponent(buildHint(OpencodeFrontendBundle.message("settings.opencode.password.hint")))
            .addComponent(testConnectionButton)
            .addSeparator()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.shell"), shellCombo)
            .addComponent(statusLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return JPanel(BorderLayout()).apply {
            add(buildHeader(title) { reload() }, BorderLayout.NORTH)
            add(form, BorderLayout.CENTER)
        }
    }

    override fun reload() {
        val state = OpenCodeSettingsState.getInstance()
        serverUrlField.text = state.serverUrl
        usernameField.text = state.username
        passwordField.text = state.password
        loadSnapshot { snapshot ->
            currentShell = snapshot.shell
            val model = DefaultComboBoxModel<String>()
            model.addElement(defaultShellLabel)
            snapshot.shells.forEach { model.addElement(it.path) }
            shellCombo.model = model
            shellCombo.selectedItem = snapshot.shell?.takeIf { it.isNotBlank() } ?: defaultShellLabel
        }
    }

    override fun isModified(): Boolean {
        val state = OpenCodeSettingsState.getInstance()
        return normalizedUrl() != state.serverUrl ||
            inputUsername() != state.username ||
            inputPassword() != state.password ||
            inputShell() != currentShell
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
                    ChatRepositoryRpcApi.getInstance().updateServerConfig(project.projectId(), serverUrl, username, password)
                }
            }
        }

        val shell = inputShell()
        if (shell != currentShell) {
            val project = currentProject()
            if (project == null) {
                showStatus(OpencodeFrontendBundle.message("settings.opencode.no.project"))
            } else {
                runWrite({ reload() }) {
                    SettingsRpcApi.getInstance().setShell(project.projectId(), shell)
                }
            }
        }
    }

    private fun normalizedUrl(): String {
        return serverUrlField.text.trim().trimEnd('/')
            .ifEmpty { OpenCodeSettingsState.DEFAULT_SERVER_URL }
    }

    private fun inputUsername(): String =
        usernameField.text.trim().ifEmpty { OpenCodeSettingsState.DEFAULT_USERNAME }

    private fun inputPassword(): String = String(passwordField.password).trim()

    private fun inputShell(): String? =
        (shellCombo.selectedItem as? String)?.takeIf { it.isNotBlank() && it != defaultShellLabel }

    /**
     * 探测连通性：先把输入值下发给后端（密码留空由后端回退环境变量 / service.json），
     * 再让后端用同一份凭据真发一次请求，最后读取连接状态——避免前端自行拼 Basic 造成的误判。
     */
    private fun testConnection() {
        val project = currentProject()
        if (project == null) {
            showStatus(OpencodeFrontendBundle.message("settings.opencode.no.project"))
            return
        }
        showStatus(OpencodeFrontendBundle.message("settings.opencode.testing"))
        runAsync({ info ->
            val ok = info?.isRunning == true
            showStatus(
                OpencodeFrontendBundle.message(
                    if (ok) "settings.opencode.test.success" else "settings.opencode.test.failed"
                ),
                info?.error
            )
        }) {
            val api = ChatRepositoryRpcApi.getInstance()
            api.updateServerConfig(project.projectId(), normalizedUrl(), inputUsername(), inputPassword())
            api.getAllSessions(project.projectId()).first()
            api.getServerInfo(project.projectId())
        }
    }
}