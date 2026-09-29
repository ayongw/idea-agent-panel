package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.CoroutineScopeHolder
import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.ConfigScopeDto
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.ayongw.idea.opencode.shared.SettingsSnapshotDto
import com.ayongw.idea.opencode.shared.SettingsWriteResultDto
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.launch
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.ListSelectionModel
import javax.swing.table.TableModel

/** 设置页中的一个 Tab */
internal interface SettingsTab {
    val title: String
    val component: JComponent

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
                        JButton(OpencodeFrontendBundle.message("settings.opencode.refresh")).apply {
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

    /** 作用域行：标签 + 下拉（默认全局） */
    protected fun buildScopeRow(combo: JComboBox<String>): JComponent {
        combo.model = buildScopeModel()
        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            add(JBLabel(OpencodeFrontendBundle.message("settings.opencode.scope")))
            add(combo)
        }
    }

    protected fun buildScopeModel(): DefaultComboBoxModel<String> = DefaultComboBoxModel(
        arrayOf(
            OpencodeFrontendBundle.message("settings.opencode.scope.global"),
            OpencodeFrontendBundle.message("settings.opencode.scope.project")
        )
    )

    protected fun scopeOf(combo: JComboBox<String>): ConfigScopeDto =
        if (combo.selectedIndex == 1) ConfigScopeDto.PROJECT else ConfigScopeDto.GLOBAL

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
                OpencodeFrontendBundle.message("settings.opencode.error.unauthorized")
            text.contains("403") -> OpencodeFrontendBundle.message("settings.opencode.error.forbidden")
            text.contains("ConnectException", ignoreCase = true) ||
                text.contains("Connection refused", ignoreCase = true) ->
                OpencodeFrontendBundle.message("settings.opencode.error.unreachable")
            text.isBlank() -> OpencodeFrontendBundle.message("settings.opencode.error.unknown")
            else -> text.take(200)
        }
    }

    /** 后台读取设置快照并回 EDT 渲染 */
    protected fun loadSnapshot(onLoaded: (SettingsSnapshotDto) -> Unit) {
        val project = currentProject()
        if (project == null) {
            showStatus(OpencodeFrontendBundle.message("settings.opencode.no.project"))
            onLoaded(SettingsSnapshotDto("", ""))
            return
        }
        showStatus(OpencodeFrontendBundle.message("settings.opencode.loading"))
        CoroutineScopeHolder.getInstance(project).createScope("OpenCodeSettingsLoad").launch {
            val snapshot = runCatching { SettingsRpcApi.getInstance().getSnapshot(project.projectId()) }
                .getOrElse { error ->
                    SettingsSnapshotDto("", "", warnings = listOf(error.message ?: error.toString()))
                }
            ApplicationManager.getApplication().invokeLater {
                val warnings = snapshot.warnings
                if (warnings.isEmpty()) {
                    showStatus(null)
                } else {
                    showStatus(
                        warnings.map { friendlyError(it) }.distinct().joinToString("；"),
                        warnings.joinToString("\n")
                    )
                }
                onLoaded(snapshot)
            }
        }
    }

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
            showStatus(OpencodeFrontendBundle.message("settings.opencode.no.project"))
            return
        }
        showStatus(OpencodeFrontendBundle.message("settings.opencode.saving"))
        CoroutineScopeHolder.getInstance(project).createScope("OpenCodeSettingsSave").launch {
            val result = runCatching { action() }
                .getOrElse { SettingsWriteResultDto.fail(it.message ?: it.toString()) }
            ApplicationManager.getApplication().invokeLater {
                if (result.ok) {
                    val note = result.message
                    showStatus(
                        OpencodeFrontendBundle.message("settings.opencode.saved") + (note?.let { " ($it)" } ?: ""),
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
    }
}