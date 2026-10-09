package com.ayongw.idea.agentpanel.frontend.settings

import com.ayongw.idea.agentpanel.frontend.CoroutineScopeHolder
import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle
import com.ayongw.idea.agentpanel.shared.ChatRepositoryRpcApi
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
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
 * 地址/用户名/Server 管理项写入插件设置（[AgentSettingsState]），
 * 密码写入 IDE 凭据存储（[OpenCodePasswordStore]）；保存时下发给后端使其即时生效。
 * 该面板是本设置页唯一由「OK」统一提交的 Tab（`isModified` / `apply`）。
 */
internal class ConnectionSettingsTab : AbstractSettingsTab() {

    override val title: String = AgentPanelBundle.message("settings.agent.tab.connection")

    /** 由「OK」统一提交，打开设置页即需就绪 */
    override val eager: Boolean = true

    private val serverUrlField = JBTextField(40)
    private val usernameField = JBTextField(30)
    private val passwordField = JBPasswordField().apply { columns = 30 }
    private val autoStartCheckBox = JCheckBox(AgentPanelBundle.message("settings.agent.server.auto.start"))
    private val reuseExternalCheckBox =
        JCheckBox(AgentPanelBundle.message("settings.agent.server.reuse.external"))
    private val cliPathField = JBTextField(40)

    /**
     * 只读展示后端**实际解析到**的 CLI 路径（与拉起同源）。
     *
     * 探测结果**不写回** [cliPathField]：该字段是显式覆盖，且设置项非空时定位器不回退 `PATH`
     * （见 `OpenCodeServerCliLocator`），把自动探测到的路径写死进去，一旦 nvm/opencode 升级
     * 换了目录就会直接报 CLI_NOT_FOUND。
     */
    private val detectedCliPathLabel = JBLabel(" ").apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
    }

    private val browseCliButton = JButton(
        AgentPanelBundle.message("settings.agent.server.cli.path.browse")
    ).apply {
        margin = JBUI.insets(0, JBUI.scale(8), 0, 0)
        addActionListener { browseCliPath() }
    }

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        val testConnectionButton = JButton(AgentPanelBundle.message("settings.agent.test.connection")).apply {
            addActionListener { testConnection() }
        }

        val managementActions = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            add(
                JButton(AgentPanelBundle.message("settings.agent.server.stop")).apply {
                    addActionListener { stopManagedServer() }
                }
            )
            add(
                JButton(AgentPanelBundle.message("settings.agent.server.reset.registry")).apply {
                    addActionListener { resetServerRegistry() }
                }
            )
        }

        val form = FormBuilder.createFormBuilder()
            .addComponent(buildHint(AgentPanelBundle.message("settings.agent.connection.hint")))
            .addLabeledComponent(AgentPanelBundle.message("settings.agent.server.url"), serverUrlField)
            .addLabeledComponent(AgentPanelBundle.message("settings.agent.username"), usernameField)
            .addLabeledComponent(AgentPanelBundle.message("settings.agent.password"), passwordField)
            .addComponent(buildHint(AgentPanelBundle.message("settings.agent.password.hint")))
            .addComponent(testConnectionButton)
            .addComponent(statusLabel)
            .addComponent(buildHint(AgentPanelBundle.message("settings.agent.server.management")))
            .addComponent(autoStartCheckBox)
            .addComponent(reuseExternalCheckBox)
            .addLabeledComponent(AgentPanelBundle.message("settings.agent.server.cli.path"), buildCliPathRow())
            .addComponent(buildHint(AgentPanelBundle.message("settings.agent.server.cli.path.hint")))
            .addComponent(detectedCliPathLabel)
            .addComponent(managementActions)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        return JPanel(BorderLayout()).apply {
            add(buildHeader(title) { reload() }, BorderLayout.NORTH)
            add(form, BorderLayout.CENTER)
        }
    }

    /** CLI 路径行：输入框 + 「浏览…」选择可执行文件 */
    private fun buildCliPathRow(): JComponent = JPanel(BorderLayout()).apply {
        isOpaque = false
        add(cliPathField, BorderLayout.CENTER)
        add(browseCliButton, BorderLayout.EAST)
    }

    override fun reload() {
        val state = AgentSettingsState.getInstance()
        serverUrlField.text = state.serverUrl
        usernameField.text = state.username
        passwordField.text = OpenCodePasswordStore.load()
        autoStartCheckBox.isSelected = state.autoStartServer
        reuseExternalCheckBox.isSelected = state.reuseExternalServer
        cliPathField.text = state.cliPath
        refreshDetectedCliPath()
    }

    /**
     * 只读刷新「实际会用到的 CLI 路径」：按输入框当前值向后端解析（输入为空即从 PATH 解析）。
     *
     * 解析要探一次登录 shell 的 `PATH`（首次秒级），走 [runAsync] 在后台执行。
     */
    private fun refreshDetectedCliPath() {
        val project = currentProject()
        if (project == null) {
            detectedCliPathLabel.text = " "
            detectedCliPathLabel.toolTipText = null
            return
        }
        detectedCliPathLabel.text = AgentPanelBundle.message("settings.agent.server.cli.path.detecting")
        detectedCliPathLabel.toolTipText = null
        runAsync({ path: String? ->
            detectedCliPathLabel.text = if (path.isNullOrBlank()) {
                AgentPanelBundle.message("settings.agent.server.cli.path.not.detected")
            } else {
                AgentPanelBundle.message("settings.agent.server.cli.path.detected", path)
            }
            detectedCliPathLabel.toolTipText = path
        }) {
            ChatRepositoryRpcApi.getInstance().resolveCliPath(project.projectId(), inputCliPath())
        }
    }

    /** 浏览选择 CLI 可执行文件（含工作区外路径），选完就地刷新解析结果 */
    private fun browseCliPath() {
        val descriptor = FileChooserDescriptorFactory.createSingleFileDescriptor()
            .withTitle(AgentPanelBundle.message("settings.agent.server.cli.path"))
        // 已填路径预选为其所在文件（VirtualFile 才可作初值）
        val initial = inputCliPath().takeIf { it.isNotEmpty() }
            ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
        val chosen = FileChooser.chooseFile(descriptor, currentProject(), initial) ?: return
        cliPathField.text = chosen.path
        refreshDetectedCliPath()
    }

    override fun isModified(): Boolean {
        val state = AgentSettingsState.getInstance()
        return normalizedUrl() != state.serverUrl ||
            inputUsername() != state.username ||
            inputPassword() != OpenCodePasswordStore.load() ||
            autoStartCheckBox.isSelected != state.autoStartServer ||
            reuseExternalCheckBox.isSelected != state.reuseExternalServer ||
            inputCliPath() != state.cliPath
    }

    override fun apply() {
        val state = AgentSettingsState.getInstance()
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
                // 「实际会用到的路径」随本次保存变化，回 EDT 重解析（后端配置已下发）
                ApplicationManager.getApplication().invokeLater { refreshDetectedCliPath() }
            }
        }
    }

    private fun normalizedUrl(): String {
        return serverUrlField.text.trim().trimEnd('/')
            .ifEmpty { AgentSettingsState.DEFAULT_SERVER_URL }
    }

    private fun inputUsername(): String =
        usernameField.text.trim().ifEmpty { AgentSettingsState.DEFAULT_USERNAME }

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
            showStatus(AgentPanelBundle.message("settings.agent.no.project"))
            return
        }
        showStatus(AgentPanelBundle.message("settings.agent.testing"))
        runAsync({ info ->
            val ok = info?.isRunning == true
            showStatus(
                AgentPanelBundle.message(
                    if (ok) "settings.agent.test.success" else "settings.agent.test.failed"
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
            showStatus(AgentPanelBundle.message("settings.agent.no.project"))
            return
        }
        showStatus(AgentPanelBundle.message("settings.agent.server.stopping"))
        runAsync({ stopped: Boolean? ->
            showStatus(
                AgentPanelBundle.message(
                    if (stopped == true) "settings.agent.server.stop.done"
                    else "settings.agent.server.stop.not.owned"
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
            showStatus(AgentPanelBundle.message("settings.agent.no.project"))
            return
        }
        showStatus(AgentPanelBundle.message("settings.agent.saving"))
        runAsync({ _: Unit? ->
            showStatus(AgentPanelBundle.message("settings.agent.server.reset.done"))
        }) {
            ChatRepositoryRpcApi.getInstance().resetServerRegistry(project.projectId())
        }
    }
}