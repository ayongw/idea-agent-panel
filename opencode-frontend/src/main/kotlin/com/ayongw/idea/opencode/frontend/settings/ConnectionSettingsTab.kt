package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.CoroutineScopeHolder
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.ChatRepositoryRpcApi
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 连接面板：**插件自身设置**（Server 地址、用户名、密码）+ **Server 管理**
 *
 * 地址/用户名/Server 管理项写入插件设置（[OpenCodeSettingsState]），
 * 密码写入 IDE 凭据存储（[OpenCodePasswordStore]）；保存时下发给后端使其即时生效。
 * 该面板是本设置页唯一由「OK」统一提交的 Tab（`isModified` / `apply`）。
 */
internal class ConnectionSettingsTab : AbstractSettingsTab() {

    override val title: String = OpencodeFrontendBundle.message("settings.opencode.tab.connection")

    /** 由「OK」统一提交，打开设置页即需就绪 */
    override val eager: Boolean = true

    private val serverUrlField = JBTextField(40)
    private val usernameField = JBTextField(30)
    private val passwordField = JBPasswordField().apply { columns = 30 }
    private val autoStartCheckBox = JCheckBox(OpencodeFrontendBundle.message("settings.opencode.server.auto.start"))
    private val reuseExternalCheckBox =
        JCheckBox(OpencodeFrontendBundle.message("settings.opencode.server.reuse.external"))
    private val cliPathField = JBTextField(40)

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        val testConnectionButton = JButton(OpencodeFrontendBundle.message("settings.opencode.test.connection")).apply {
            addActionListener { testConnection() }
        }

        val managementActions = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(
                JButton(OpencodeFrontendBundle.message("settings.opencode.server.stop")).apply {
                    addActionListener { stopManagedServer() }
                }
            )
            add(
                JButton(OpencodeFrontendBundle.message("settings.opencode.server.reset.registry")).apply {
                    addActionListener { resetServerRegistry() }
                }
            )
        }

        val form = FormBuilder.createFormBuilder()
            .addComponent(buildHint(OpencodeFrontendBundle.message("settings.opencode.connection.hint")))
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.server.url"), serverUrlField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.username"), usernameField)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.password"), passwordField)
            .addComponent(buildHint(OpencodeFrontendBundle.message("settings.opencode.password.hint")))
            .addComponent(testConnectionButton)
            .addComponent(statusLabel)
            .addComponent(buildHint(OpencodeFrontendBundle.message("settings.opencode.server.management")))
            .addComponent(autoStartCheckBox)
            .addComponent(reuseExternalCheckBox)
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.server.cli.path"), cliPathField)
            .addComponent(buildHint(OpencodeFrontendBundle.message("settings.opencode.server.cli.path.hint")))
            .addComponent(managementActions)
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
        passwordField.text = OpenCodePasswordStore.load()
        autoStartCheckBox.isSelected = state.autoStartServer
        reuseExternalCheckBox.isSelected = state.reuseExternalServer
        cliPathField.text = state.cliPath
    }

    override fun isModified(): Boolean {
        val state = OpenCodeSettingsState.getInstance()
        return normalizedUrl() != state.serverUrl ||
            inputUsername() != state.username ||
            inputPassword() != OpenCodePasswordStore.load() ||
            autoStartCheckBox.isSelected != state.autoStartServer ||
            reuseExternalCheckBox.isSelected != state.reuseExternalServer ||
            inputCliPath() != state.cliPath
    }

    override fun apply() {
        val state = OpenCodeSettingsState.getInstance()
        val serverUrl = normalizedUrl()
        val username = inputUsername()
        val password = inputPassword()
        val cliPath = inputCliPath()
        state.serverUrl = serverUrl
        state.username = username
        state.autoStartServer = autoStartCheckBox.isSelected
        state.reuseExternalServer = reuseExternalCheckBox.isSelected
        state.cliPath = cliPath
        // 密码存 IDE 凭据存储（不进插件设置文件）
        OpenCodePasswordStore.save(password)

        // 下发到后端，使新配置即时生效
        ProjectManager.getInstance().openProjects.forEach { project ->
            CoroutineScopeHolder.getInstance(project).createScope("OpenCodeSettingsPush").launch {
                runCatching {
                    ChatRepositoryRpcApi.getInstance().updateServerConfig(
                        project.projectId(),
                        serverUrl,
                        username,
                        password,
                        cliPath,
                        autoStartCheckBox.isSelected,
                        reuseExternalCheckBox.isSelected,
                    )
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

    /** CLI 路径覆盖：空 = 从 PATH 解析（保存空串，不用默认值兜底） */
    private fun inputCliPath(): String = cliPathField.text.trim()

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
            // Server 管理项一并下发，避免「测试连接」把自动启动 / 复用开关重置回默认值
            api.updateServerConfig(
                project.projectId(),
                normalizedUrl(),
                inputUsername(),
                inputPassword(),
                inputCliPath(),
                autoStartCheckBox.isSelected,
                reuseExternalCheckBox.isSelected,
            )
            api.getAllSessions(project.projectId()).first()
            api.getServerInfo(project.projectId())
        }
    }

    /** 停止本插件启动的 Server；他人实例不会被终止（后端直接返回 false） */
    private fun stopManagedServer() {
        val project = currentProject()
        if (project == null) {
            showStatus(OpencodeFrontendBundle.message("settings.opencode.no.project"))
            return
        }
        showStatus(OpencodeFrontendBundle.message("settings.opencode.server.stopping"))
        runAsync({ stopped: Boolean? ->
            showStatus(
                OpencodeFrontendBundle.message(
                    if (stopped == true) "settings.opencode.server.stop.done"
                    else "settings.opencode.server.stop.not.owned"
                )
            )
        }) {
            ChatRepositoryRpcApi.getInstance().stopServer(project.projectId())
        }
    }

    /** 排障兜底：清空共享注册表（不终止任何进程） */
    private fun resetServerRegistry() {
        val project = currentProject()
        if (project == null) {
            showStatus(OpencodeFrontendBundle.message("settings.opencode.no.project"))
            return
        }
        showStatus(OpencodeFrontendBundle.message("settings.opencode.saving"))
        runAsync({ _: Unit? ->
            showStatus(OpencodeFrontendBundle.message("settings.opencode.server.reset.done"))
        }) {
            ChatRepositoryRpcApi.getInstance().resetServerRegistry(project.projectId())
        }
    }
}