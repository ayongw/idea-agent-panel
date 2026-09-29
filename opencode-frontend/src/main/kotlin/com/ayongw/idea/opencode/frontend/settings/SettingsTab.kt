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
import com.intellij.util.ui.JBUI
import kotlinx.coroutines.launch
import javax.swing.DefaultComboBoxModel
import javax.swing.JComboBox
import javax.swing.JComponent

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
 * 设置子面板基类：统一「取当前项目 → 调 RPC → 回 EDT 渲染」与状态提示
 */
internal abstract class AbstractSettingsTab : SettingsTab {

    protected val statusLabel = JBLabel(" ").apply { font = JBUI.Fonts.smallFont() }

    protected fun currentProject(): Project? = ProjectManager.getInstance().openProjects.firstOrNull()

    /** 作用域下拉（默认全局） */
    protected fun buildScopeModel(): DefaultComboBoxModel<String> = DefaultComboBoxModel(
        arrayOf(
            OpencodeFrontendBundle.message("settings.opencode.scope.global"),
            OpencodeFrontendBundle.message("settings.opencode.scope.project")
        )
    )

    protected fun scopeOf(combo: JComboBox<String>): ConfigScopeDto =
        if (combo.selectedIndex == 1) ConfigScopeDto.PROJECT else ConfigScopeDto.GLOBAL

    /** 后台读取设置快照并回 EDT 渲染 */
    protected fun loadSnapshot(onLoaded: (SettingsSnapshotDto) -> Unit) {
        val project = currentProject()
        if (project == null) {
            statusLabel.text = OpencodeFrontendBundle.message("settings.opencode.no.project")
            onLoaded(SettingsSnapshotDto("", ""))
            return
        }
        statusLabel.text = OpencodeFrontendBundle.message("settings.opencode.loading")
        CoroutineScopeHolder.getInstance(project).createScope("OpenCodeSettingsLoad").launch {
            val snapshot = runCatching { SettingsRpcApi.getInstance().getSnapshot(project.projectId()) }
                .getOrElse { error ->
                    SettingsSnapshotDto("", "", warnings = listOf(error.message ?: error.toString()))
                }
            ApplicationManager.getApplication().invokeLater {
                statusLabel.text = snapshot.warnings.joinToString("；").ifEmpty { " " }
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
            statusLabel.text = OpencodeFrontendBundle.message("settings.opencode.no.project")
            return
        }
        statusLabel.text = OpencodeFrontendBundle.message("settings.opencode.saving")
        CoroutineScopeHolder.getInstance(project).createScope("OpenCodeSettingsSave").launch {
            val result = runCatching { action() }
                .getOrElse { SettingsWriteResultDto.fail(it.message ?: it.toString()) }
            ApplicationManager.getApplication().invokeLater {
                statusLabel.text = if (result.ok) {
                    OpencodeFrontendBundle.message("settings.opencode.saved") +
                        (result.message?.let { " ($it)" } ?: "")
                } else {
                    OpencodeFrontendBundle.message("settings.opencode.save.failed") + " " + (result.message ?: "")
                }
                if (result.ok) then()
            }
        }
    }
}