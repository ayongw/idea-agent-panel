package com.ayongw.idea.agentpanel.frontend.settings

import com.ayongw.idea.agentpanel.frontend.CoroutineScopeHolder
import com.ayongw.idea.agentpanel.frontend.AgentPanelBundle
import com.ayongw.idea.agentpanel.frontend.chatApp.ui.utils.ButtonUtils
import com.ayongw.idea.agentpanel.shared.ConfigScopeDto
import com.ayongw.idea.agentpanel.shared.SettingsRpcApi
import com.ayongw.idea.agentpanel.shared.SettingsSnapshotDto
import com.ayongw.idea.agentpanel.shared.SettingsWriteResultDto
import com.intellij.icons.AllIcons
import com.intellij.ide.actions.ShowFilePathAction
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.io.File
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.TableModel

/** 设置页中的一个 Tab */
internal interface SettingsTab {
    val title: String
    val component: JComponent

    /**
     * 是否需要在设置页打开时立即加载
     *
     * 参与「OK」统一提交的插件自身设置页必须置为 true，
     * 否则用户未访问该 Tab 就点 OK 时，会用空表单把已有配置写回默认值。
     * 其余 Tab 在首次显示 / 切换时按需加载。
     */
    val eager: Boolean get() = false

    /** 由 `Configurable.apply()` 处理的可写项；自带保存按钮的面板返回 false */
    fun isModified(): Boolean = false

    fun apply() {}

    /** 从状态/后端重新加载数据 */
    fun reload() {}
}

/**
 * 设置子面板基类
 *
 * 统一约定：标题行（标题 + 刷新）→ 内容区（随窗口拉伸）→ 状态行（友好文案 + tooltip 明细）；
 * 以及「取当前项目 → 调 RPC → 回 EDT 渲染」的异步骨架。
 */
internal abstract class AbstractSettingsTab : SettingsTab {

    protected val statusLabel = JBLabel(" ").apply { font = JBUI.Fonts.smallFont() }

    /** 是否有一次快照加载在途（避免重复请求同一份数据） */
    private var loading = false

    protected fun currentProject(): Project? = ProjectManager.getInstance().openProjects.firstOrNull()

    /** 标题行：左侧标题（可带一行说明）、右侧刷新按钮 */
    protected fun buildHeader(title: String, hint: String? = null, onRefresh: () -> Unit): JComponent {
        val west = JPanel(BorderLayout()).apply {
            add(JBLabel(title).apply { font = JBUI.Fonts.label().asBold() }, BorderLayout.NORTH)
            hint?.let { add(buildHint(it), BorderLayout.SOUTH) }
        }
        return JPanel(BorderLayout()).apply {
            add(west, BorderLayout.WEST)
            add(
                JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                    add(
                        JButton(AgentPanelBundle.message("settings.agent.refresh")).apply {
                            addActionListener { onRefresh() }
                        }
                    )
                },
                BorderLayout.EAST
            )
        }
    }

    /** 说明文字（次要色、小字号），随窗口宽度折行 */
    protected fun buildHint(text: String): JComponent = JBLabel(text).apply {
        font = JBUI.Fonts.smallFont()
        foreground = UIUtil.getContextHelpForeground()
    }

    /** 统一列表样式：填满可用宽度、按列宽偏好按比例分配 */
    protected fun buildTable(model: TableModel, widths: List<Int> = emptyList()): JTable {
        val table = JBTable(model)
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        widths.forEachIndexed { index, width ->
            if (index < table.columnCount) table.columnModel.getColumn(index).preferredWidth = width
        }
        return table
    }

    /**
     * 滚动面板：宽度只作为 preferred 上限，避免表格 / 长文本把设置页撑出水平滚动条；
     * 实际显示宽度仍由外层布局（BorderLayout / FormBuilder）拉伸到可用宽度。
     */
    protected fun buildScroll(view: JComponent, height: Int): JBScrollPane =
        JBScrollPane(view).apply {
            preferredSize = Dimension(JBUI.scale(CONTENT_WIDTH), JBUI.scale(height))
        }

    /** 齿轮按钮：只画图标与 hover 背景，无边框、无焦点框 */
    protected fun buildGearButton(tooltip: String, onClick: () -> Unit): JButton =
        ButtonUtils.ToolbarButton(icon = AllIcons.General.GearPlain).apply {
            preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
            toolTipText = tooltip
            addActionListener { onClick() }
        }

    /** 打开配置文件：文件不存在时先让后端建出空 `{}`，再在编辑器中打开指定的那一个 */
    protected fun openConfigFile(path: String, scope: ConfigScopeDto) {
        val project = currentProject()
        if (project == null) {
            showStatus(AgentPanelBundle.message("settings.agent.no.project"))
            return
        }
        showStatus(AgentPanelBundle.message("settings.agent.loading"))
        CoroutineScopeHolder.getInstance(project).createScope("OpenCodeSettingsOpenFile").launch {
            val result = runCatching { SettingsRpcApi.getInstance().ensureConfigFile(project.projectId(), scope) }
                .getOrElse { SettingsWriteResultDto.fail(it.message ?: it.toString()) }
            ApplicationManager.getApplication().invokeLater {
                if (result.ok) openInEditor(path) else showStatus(friendlyError(result.message), result.message)
            }
        }
    }

    /** 在编辑器中打开文件（支持工作区外的绝对路径） */
    protected fun openInEditor(path: String) {
        val project = currentProject()
        if (project == null) {
            showStatus(AgentPanelBundle.message("settings.agent.no.project"))
            return
        }
        val file = LocalFileSystem.getInstance().refreshAndFindFileByPath(path)
        if (file == null || !file.isValid) {
            showStatus(AgentPanelBundle.message("settings.agent.file.missing", path), path)
            return
        }
        OpenFileDescriptor(project, file).navigate(true)
        showStatus(null)
    }

    /** 在系统文件管理器中定位路径（传文件则打开其所在目录） */
    protected fun openDirectory(path: String) {
        val file = File(path)
        val dir = if (file.isDirectory) file else file.parentFile
        if (dir == null || !dir.isDirectory) {
            showStatus(AgentPanelBundle.message("settings.agent.file.missing", path), path)
            return
        }
        ShowFilePathAction.openFile(dir)
        showStatus(null)
    }

    /** 状态行：友好文案 + 完整信息放 tooltip（过长文案截断，避免撑宽设置页） */
    protected fun showStatus(message: String?, detail: String? = null) {
        val text = message?.takeIf { it.isNotBlank() } ?: " "
        statusLabel.text = if (text.length > STATUS_MAX_CHARS) text.take(STATUS_MAX_CHARS) + "…" else text
        statusLabel.toolTipText = detail ?: message
    }

    /** 把后端/网络错误转成可读文案（完整内容由调用方放进 tooltip） */
    protected fun friendlyError(raw: String?): String {
        val text = raw.orEmpty()
        return when {
            text.contains("401") || text.contains("Unauthorized", ignoreCase = true) ->
                AgentPanelBundle.message("settings.agent.error.unauthorized")
            text.contains("403") -> AgentPanelBundle.message("settings.agent.error.forbidden")
            text.contains("ConnectException", ignoreCase = true) ||
                text.contains("Connection refused", ignoreCase = true) ->
                AgentPanelBundle.message("settings.agent.error.unreachable")
            text.isBlank() -> AgentPanelBundle.message("settings.agent.error.unknown")
            else -> text.take(200)
        }
    }

    /** 后台读取设置快照并回 EDT 渲染（服务端未就绪时自动重试，无需用户点刷新） */
    protected fun loadSnapshot(onLoaded: (SettingsSnapshotDto) -> Unit) {
        val project = currentProject()
        if (project == null) {
            showStatus(AgentPanelBundle.message("settings.agent.no.project"))
            onLoaded(SettingsSnapshotDto("", ""))
            return
        }
        // 打开设置页时会连续触发多次加载（createComponent + 平台 reset），同一次只保留一个在途请求
        if (loading) return
        loading = true
        showStatus(AgentPanelBundle.message("settings.agent.loading"))
        CoroutineScopeHolder.getInstance(project).createScope("OpenCodeSettingsLoad").launch {
            val loaded = runCatching { loadWithRetry() }
                .getOrElse { error ->
                    SettingsSnapshotDto("", "", warnings = listOf(error.message ?: error.toString()))
                }
            ApplicationManager.getApplication().invokeLater {
                loading = false
                val warnings = loaded.warnings
                if (warnings.isEmpty()) {
                    showStatus(null)
                } else {
                    showStatus(
                        warnings.map { friendlyError(it) }.distinct().joinToString("；"),
                        warnings.joinToString("\n")
                    )
                }
                onLoaded(loaded)
            }
        }
    }

    private suspend fun loadWithRetry(): SettingsSnapshotDto {
        var snapshot = fetchSnapshot()
        var attempt = 1
        // 刚打开设置页时项目/RPC/opencode 服务可能尚未就绪，接口会静默返回空清单 → 稍后重试
        while (isEmptySnapshot(snapshot) && attempt < LOAD_MAX_ATTEMPTS) {
            delay(LOAD_RETRY_DELAY_MS)
            snapshot = fetchSnapshot()
            attempt++
        }
        return snapshot
    }

    private suspend fun fetchSnapshot(): SettingsSnapshotDto {
        val project = currentProject() ?: return SettingsSnapshotDto("", "")
        return runCatching { SettingsRpcApi.getInstance().getSnapshot(project.projectId()) }
            .getOrElse { error -> SettingsSnapshotDto("", "", warnings = listOf(error.message ?: error.toString())) }
    }

    /** 无告警且三个服务端清单都为空 —— 视为「服务端尚未就绪」，而非「确实没有数据」 */
    private fun isEmptySnapshot(snapshot: SettingsSnapshotDto): Boolean =
        snapshot.warnings.isEmpty() &&
            snapshot.providers.isEmpty() &&
            snapshot.discoveredSkills.isEmpty() &&
            snapshot.mcpServers.isEmpty()

    /** 后台执行任意 RPC，回 EDT 处理结果（失败传 null） */
    protected fun <T> runAsync(onResult: (T?) -> Unit, action: suspend () -> T) {
        val project = currentProject() ?: return
        CoroutineScopeHolder.getInstance(project).createScope("OpenCodeSettingsCall").launch {
            val value = runCatching { action() }.getOrNull()
            ApplicationManager.getApplication().invokeLater { onResult(value) }
        }
    }

    /** 后台写入，完成后回 EDT 提示，成功时执行 [then]（通常用于刷新） */
    protected fun runWrite(then: () -> Unit = {}, action: suspend () -> SettingsWriteResultDto) {
        val project = currentProject()
        if (project == null) {
            showStatus(AgentPanelBundle.message("settings.agent.no.project"))
            return
        }
        showStatus(AgentPanelBundle.message("settings.agent.saving"))
        CoroutineScopeHolder.getInstance(project).createScope("OpenCodeSettingsSave").launch {
            val result = runCatching { action() }
                .getOrElse { SettingsWriteResultDto.fail(it.message ?: it.toString()) }
            ApplicationManager.getApplication().invokeLater {
                if (result.ok) {
                    val note = result.message
                    showStatus(
                        AgentPanelBundle.message("settings.agent.saved") + (note?.let { " ($it)" } ?: ""),
                        note
                    )
                    then()
                } else {
                    showStatus(friendlyError(result.message), result.message)
                }
            }
        }
    }

    private companion object {
        /** 内容块 preferred 宽度上限（逻辑像素），只影响布局计算，不影响实际拉伸 */
        const val CONTENT_WIDTH = 560

        /** 状态行展示的最大字符数，超出部分放 tooltip（避免长文案撑宽设置页） */
        const val STATUS_MAX_CHARS = 100

        /** 快照为空时的最大尝试次数与重试间隔 */
        const val LOAD_MAX_ATTEMPTS = 3
        const val LOAD_RETRY_DELAY_MS = 800L
    }
}