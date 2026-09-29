package com.ayongw.idea.opencode.frontend.settings

import com.ayongw.idea.opencode.frontend.OpencodeFrontendBundle
import com.ayongw.idea.opencode.shared.SettingsRpcApi
import com.intellij.openapi.project.ProjectManager
import com.intellij.platform.project.projectId
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 技能面板：`skills` 目录/URL 列表（写入配置文件）+ 服务端已发现技能（只读）
 */
internal class SkillSettingsTab : AbstractSettingsTab() {

    override val title: String = OpencodeFrontendBundle.message("settings.opencode.tab.skills")

    private val scopeCombo = JComboBox<String>()
    private val skillsArea = JBTextArea(6, 50)
    private val discoveredArea = JBTextArea(10, 60).apply { isEditable = false }

    override val component: JComponent = buildPanel()

    private fun buildPanel(): JComponent {
        scopeCombo.model = buildScopeModel()
        val saveButton = JButton(OpencodeFrontendBundle.message("settings.opencode.save")).apply {
            addActionListener { saveSkills() }
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(OpencodeFrontendBundle.message("settings.opencode.scope"), scopeCombo)
            .addLabeledComponent(
                OpencodeFrontendBundle.message("settings.opencode.skills.paths"),
                JBScrollPane(skillsArea)
            )
            .addComponent(saveButton)
            .addSeparator()
            .addLabeledComponent(
                OpencodeFrontendBundle.message("settings.opencode.skills.discovered"),
                JBScrollPane(discoveredArea)
            )
            .addComponent(statusLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun reload() {
        loadSnapshot { snapshot ->
            skillsArea.text = snapshot.skills.joinToString("\n")
            discoveredArea.text = snapshot.discoveredSkills.joinToString("\n") { skill ->
                listOf(skill.id, skill.name.orEmpty(), skill.path.orEmpty())
                    .filter { it.isNotBlank() }
                    .joinToString("  ")
            }
        }
    }

    private fun saveSkills() {
        val project = ProjectManager.getInstance().openProjects.first()
        val paths = skillsArea.text.lines().map { it.trim() }.filter { it.isNotBlank() }
        runWrite({ reload() }) {
            SettingsRpcApi.getInstance().saveSkills(project.projectId(), scopeOf(scopeCombo), paths)
        }
    }

    override fun isModified(): Boolean = false
}