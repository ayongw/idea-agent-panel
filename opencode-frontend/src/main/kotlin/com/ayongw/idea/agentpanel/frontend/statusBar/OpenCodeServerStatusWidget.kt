package com.ayongw.idea.agentpanel.frontend.statusBar

import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle
import com.ayongw.idea.agentpanel.frontend.settings.AgentSettingsConfigurable
import com.ayongw.idea.agentpanel.shared.ChatRepositoryRpcApi
import com.ayongw.idea.agentpanel.shared.ServerStateDto
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.StatusBarWidgetFactory
import com.intellij.platform.project.projectId
import com.intellij.ui.components.ActionLink
import com.intellij.ui.components.JBLabel
import com.intellij.ui.Gray
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.Consumer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import java.awt.BorderLayout
import java.awt.event.MouseEvent
import java.text.MessageFormat
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JPanel

/**
 * Server 状态栏 widget：把「服务是否可用」变成**项目级全局**信号。
 *
 * 为什么要做：原先状态条在聊天面板内，**面板一关就完全失联** —— 服务挂了只能等下次
 * 打开面板才发现。状态栏不受工具窗开合影响，且在所有项目窗口都可见。
 *
 * 分工（勿重复实现）：
 * - 状态栏（本 widget）：图标 + 悬停提示 + 点击弹窗（重试 / 设置）
 * - 面板内 [com.ayongw.idea.agentpanel.frontend.chatApp.ui.ServerStatusStrip]：完整详情、
 *   凭据输入、输出尾巴等重交互
 *
 * 状态源：直接订阅后端 [ChatRepositoryRpcApi.getServerStateFlow]（不依赖工具窗里的
 * ChatViewModel —— 它随工具窗创建与销毁，拿不到）。
 */
class OpenCodeServerStatusWidgetFactory : StatusBarWidgetFactory {

    override fun getId(): String = ID

    override fun getDisplayName(): String = "Server"

    override fun isAvailable(project: Project): Boolean = true

    override fun createWidget(project: Project, scope: CoroutineScope): StatusBarWidget =
        OpenCodeServerStatusWidget(project, scope)

    override fun disposeWidget(widget: StatusBarWidget) {
        Disposer.dispose(widget)
    }

    companion object {
        /** widget id：必须与 plugin.xml `com.intellij.statusBarWidgetFactory` 的 id 一致 */
        const val ID = "IdeaAgent.ServerStatus"
    }
}

private class OpenCodeServerStatusWidget(
    private val project: Project,
    scope: CoroutineScope
) : StatusBarWidget, ServerStatusPresenter.Strings {

    private val log = Logger.getInstance(OpenCodeServerStatusWidget::class.java)

    /** 平台提供的 widget 作用域：状态订阅与弹窗动作共用，dispose 时随平台一起取消 */
    private val scope = scope

    /** 由平台在 install 时注入；状态变化靠它触发重绘 */
    private var statusBar: StatusBar? = null

    private var state: ServerStateDto = ServerStateDto(state = ServerStateDto.STATE_IDLE)

    private val presentation = object : StatusBarWidget.IconPresentation {
        override fun getIcon(): Icon = ServerStatusPresenter.present(state, this@OpenCodeServerStatusWidget).icon
        override fun getTooltipText(): String = ServerStatusPresenter.present(state, this@OpenCodeServerStatusWidget).tooltip
        override fun getClickConsumer(): Consumer<MouseEvent> = Consumer(::showPopup)
    }

    init {
        // 状态栏空间有限：只放图标（TextPresentation 与 IconPresentation 不能同时用）
        scope.launchCollectStates()
    }

    /**
     * 订阅后端服务状态流（工具窗未打开时也能收到，服务挂了立刻变红）。
     *
     * `ChatRepositoryRpcApi.getInstance()` 是 suspend，必须在协程里取；
     * 后端不可达（如未装 opencode CLI）时保持 IDLE，不在状态栏刷错误。
     */
    private fun CoroutineScope.launchCollectStates() = launch {
        val flow = runCatching {
            ChatRepositoryRpcApi.getInstance().getServerStateFlow(project.projectId())
        }.getOrElse {
            log.info("取 Server 状态流失败，状态栏保持 IDLE: ${it.message}")
            return@launch
        }
        flow.onEach { newState ->
            val changed = newState.state != state.state || newState.failure != state.failure
            state = newState
            if (changed) refresh()
        }.launchIn(this)
    }

    override fun ID(): String = OpenCodeServerStatusWidgetFactory.ID

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = presentation

    override fun install(statusBar: StatusBar) {
        this.statusBar = statusBar
    }

    override fun dispose() {
        statusBar = null
    }

    /** 状态变化后请求状态栏重绘（平台按 presentation 重新取 icon/tooltip） */
    private fun refresh() {
        ApplicationManager.getApplication().invokeLater {
            statusBar?.updateWidget(ID())
        }
    }

    /**
     * 点击：弹窗展示状态摘要 + 快捷动作。
     *
     * 动作只放「重试探测」与「打开设置」两个高频入口；凭据输入、输出尾巴等重交互仍由
     * 面板内 ServerStatusStrip 承担，避免两处重复实现。
     */
    private fun showPopup(event: MouseEvent) {
        val current = ServerStatusPresenter.present(state, this)
        val content = JPanel(BorderLayout()).apply {
            isOpaque = false
            val rows = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(JBLabel(current.summary))
                state.baseUrl?.let { add(JBLabel(it).apply { foreground = Gray.GRAY }) }
            }
            add(rows, BorderLayout.CENTER)

            val actions = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                isOpaque = false
                add(
                    ActionLink(message("server.strip.action.retry")) {
                        scope.launch { runCatching { ChatRepositoryRpcApi.getInstance().retryServerStart(project.projectId()) } }
                    }
                )
                add(
                    ActionLink(message("server.strip.action.settings")) {
                        ShowSettingsUtil.getInstance()
                            .showSettingsDialog(project, AgentSettingsConfigurable::class.java)
                    }
                )
            }
            add(actions, BorderLayout.SOUTH)
        }
        JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, JBLabel(current.tooltip))
            .setRequestFocus(true)
            .setResizable(false)
            .createPopup()
            .show(RelativePoint(event))
    }

    // ==================== 文案（ServerStatusPresenter.Strings） ====================

    override fun ready(state: ServerStateDto): String = message("server.strip.ready")

    override fun reusing(state: ServerStateDto): String = message("server.strip.reusing")

    override fun progress(state: ServerStateDto): String = when (state.state) {
        ServerStateDto.STATE_STARTING ->
            MessageFormat.format(message("server.strip.starting"), state.port ?: 0)
        else -> message("server.strip.discovering")
    }

    override fun needsCredentials(): String = message("server.strip.needs.credentials")

    override fun failed(state: ServerStateDto): String = buildString {
        append(message(failureKey(state.failure)))
        state.detail?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
    }

    override fun idle(): String = message("server.strip.idle")

    /** 失败分类 → 文案 key（枚举与后端 OpenCodeServerFailure 同名，见 TSD-31） */
    private fun failureKey(failure: String?): String = when (failure) {
        "CLI_NOT_FOUND" -> "server.failure.cli.not.found"
        "PORT_IN_USE" -> "server.failure.port.in.use"
        "AUTH_FAILED" -> "server.failure.auth.failed"
        "READY_TIMEOUT" -> "server.failure.ready.timeout"
        "PROCESS_EXITED" -> "server.failure.process.exited"
        else -> "server.failure.unreachable"
    }

    private fun message(key: String): String = AgentPanelBundle.message(key)
}