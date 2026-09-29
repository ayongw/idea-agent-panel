package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.table.DefaultTableModel

/**
 * 技能面板（上下分区）
 *
 * 上：技能加载目录（写入配置 `skills`，一行一个目录或 URL）
 * 下：opencode 已加载的技能（来自 `GET /api/skill`，只读）
 */
internal class SkillSettingsTab : AbstractSettingsTab() {

    override val title: String = OpencodeFrontendBundle.message("settings.opencode.tab.skills")

    private val scopeCombo = JComboBox<String>()
    private val dirsArea = JBTextArea().apply { rows = 4 }
    private val dirsSourceLabel = JBLabel(" ").apply { font = JBUI.Fonts.smallFont() }
    private val loadedCountLabel = JBLabel(" ").apply { font = JBUI.Fonts.smallFont() }

    private val loadedModel = DefaultTableModel(
        arrayOf(
            OpencodeFrontendBundle.message("settings.opencode.skills.col.name"),
            OpencodeFrontendBundle.message("settings.opencode.skills.col.id"),
            OpencodeFrontendBundle.message("settings.opencode.skills.col.path")
        ),
        0
    )
    private val loadedTable = buildTable(loadedModel, listOf(220, 200, 400))

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        val saveDirsButton = JButton(OpencodeFrontendBundle.message("settings.opencode.save")).apply {
            addActionListener { saveDirs() }
        }

        val dirsBlock = FormBuilder.createFormBuilder()
            .addComponent(JBLabel(OpencodeFrontendBundle.message("settings.opencode.skills.dirs")))
            .addComponent(buildScroll(dirsArea, 80))
            .addComponent(dirsSourceLabel)
            .addComponent(
                JPanel(BorderLayout()).apply {
                    add(buildScopeRow(scopeCombo), BorderLayout.WEST)
                    add(
                        JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { add(saveDirsButton) },
                        BorderLayout.CENTER
                    )
                }
            )
            .panel

        val loadedBlock = JPanel(BorderLayout()).apply {
            add(
                JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                    add(JBLabel(OpencodeFrontendBundle.message("settings.opencode.skills.loaded")))
                    add(loadedCountLabel)
                },
                BorderLayout.NORTH
            )
            add(buildScroll(loadedTable, 240), BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            add(
                buildHeader(title, OpencodeFrontendBundle.message("settings.opencode.skills.hint")) { reload() },
                BorderLayout.NORTH
            )
            add(
                JPanel(BorderLayout()).apply {
                    add(dirsBlock, BorderLayout.NORTH)
                    add(loadedBlock, BorderLayout.CENTER)
                },
                BorderLayout.CENTER
            )
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    override fun reload() {
        loadSnapshot { snapshot ->
            dirsArea.text = snapshot.skills.joinToString("\n")
            dirsSourceLabel.text = OpencodeFrontendBundle.message(
                "settings.opencode.skills.source",
                snapshot.globalConfigPath,
                snapshot.projectConfigPath
            )

            loadedModel.rowCount = 0
            snapshot.discoveredSkills.forEach { skill ->
                loadedModel.addRow(arrayOf(skill.name.orEmpty().ifBlank { skill.id }, skill.id, skill.path.orEmpty()))
            }
            loadedCountLabel.text = OpencodeFrontendBundle.message(
                "settings.opencode.count",
                snapshot.discoveredSkills.size
            )
        }
    }

    private fun saveDirs() {
        val project = currentProject() ?: return
        val paths = dirsArea.text.lines().map { it.trim() }.filter { it.isNotBlank() }
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().saveSkills(project.projectId(), scopeOf(scopeCombo), paths)
        }
    }

    override fun isModified(): Boolean = false
}